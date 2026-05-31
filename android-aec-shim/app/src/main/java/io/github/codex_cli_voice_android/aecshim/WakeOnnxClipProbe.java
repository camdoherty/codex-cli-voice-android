package io.github.codex_cli_voice_android.aecshim;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import org.json.JSONArray;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Locale;

final class WakeOnnxClipProbe {
    static final int SAMPLE_RATE = 16000;
    static final int CHUNK_SAMPLES = 1280;
    private static final int MEL_CONTEXT_SAMPLES = 160 * 3;
    private static final int MEL_WINDOW_FRAMES = 76;
    private static final int MEL_BINS = 32;
    private static final int EMBEDDING_DIM = 96;
    private static final int CLASSIFIER_FRAMES = 16;

    private WakeOnnxClipProbe() {}

    static Result run(WakeProfile profile, byte[] wavBytes) throws Exception {
        long started = System.currentTimeMillis();
        WavData wav = parseWavPcm16Mono16k(wavBytes);
        InputLevel rawInputLevel = inputLevel(wav.samples);
        GainResult gainResult = applyGain(wav.samples, profile.inputGainDb);
        short[] padded = pad(gainResult.samples, SAMPLE_RATE);

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(1);
            try (OrtSession melSession = env.createSession(profile.melspectrogramPath, options);
                 OrtSession embeddingSession = env.createSession(profile.embeddingPath, options);
                 OrtSession wakeSession = env.createSession(profile.modelPath, options)) {
                StreamingState state = new StreamingState(env, melSession, embeddingSession);
                double maxScore = 0.0;
                long firstHitMs = -1L;
                int frames = 0;
                for (int offset = 0; offset < padded.length - CHUNK_SAMPLES; offset += CHUNK_SAMPLES) {
                    short[] chunk = new short[CHUNK_SAMPLES];
                    System.arraycopy(padded, offset, chunk, 0, CHUNK_SAMPLES);
                    state.accept(chunk);
                    float[] features = state.latestFeatures(CLASSIFIER_FRAMES);
                    double score = runWakeClassifier(env, wakeSession, features);
                    if (frames < 5) {
                        score = 0.0;
                    }
                    if (score > maxScore) {
                        maxScore = score;
                    }
                    if (firstHitMs < 0L && score >= profile.threshold) {
                        firstHitMs = Math.round((offset / (double) SAMPLE_RATE) * 1000.0);
                    }
                    frames++;
                }

                Result result = new Result();
                result.maxScore = round(maxScore);
                result.firstHitMs = firstHitMs;
                result.frames = frames;
                result.sampleRate = wav.sampleRate;
                result.samples = wav.samples.length;
                result.inputGainDb = profile.inputGainDb;
                result.inputRmsDbfs = round(rawInputLevel.rmsDbfs);
                result.inputPeakDbfs = round(rawInputLevel.peakDbfs);
                result.gainedInputRmsDbfs = round(gainResult.inputLevel.rmsDbfs);
                result.gainedInputPeakDbfs = round(gainResult.inputLevel.peakDbfs);
                result.clippedSamples = gainResult.clippedSamples;
                result.ortVersion = env.getVersion();
                JSONArray providers = new JSONArray();
                for (Object provider : OrtEnvironment.getAvailableProviders()) {
                    providers.put(String.valueOf(provider));
                }
                result.providers = providers;
                result.elapsedMs = System.currentTimeMillis() - started;
                return result;
            }
        }
    }

    static double runWakeClassifier(OrtEnvironment env, OrtSession session, float[] features) throws Exception {
        try (OnnxTensor input = tensor(env, features, new long[]{1, CLASSIFIER_FRAMES, EMBEDDING_DIM});
             OrtSession.Result output = session.run(Collections.singletonMap("x.1", input))) {
            OnnxTensor tensor = (OnnxTensor) output.get(0);
            FloatBuffer scores = tensor.getFloatBuffer();
            return scores.get(0);
        }
    }

    static final class StreamingState {
        private final OrtEnvironment env;
        private final OrtSession melSession;
        private final OrtSession embeddingSession;
        private final ArrayDeque<Short> raw = new ArrayDeque<>(SAMPLE_RATE * 10);
        private float[] melBuffer = ones(MEL_WINDOW_FRAMES * MEL_BINS);
        private float[] featureBuffer;
        private int melRows = MEL_WINDOW_FRAMES;
        private int featureRows;
        private int accumulatedSamples;

        StreamingState(OrtEnvironment env, OrtSession melSession, OrtSession embeddingSession) throws Exception {
            this.env = env;
            this.melSession = melSession;
            this.embeddingSession = embeddingSession;
            this.featureBuffer = embeddings(new short[SAMPLE_RATE * 10]);
            this.featureRows = featureBuffer.length / EMBEDDING_DIM;
        }

        void accept(short[] samples) throws Exception {
            for (short sample : samples) {
                if (raw.size() >= SAMPLE_RATE * 10) {
                    raw.removeFirst();
                }
                raw.addLast(sample);
            }
            accumulatedSamples += samples.length;
            if (accumulatedSamples < CHUNK_SAMPLES) {
                return;
            }
            short[] melInput = lastRaw(accumulatedSamples + MEL_CONTEXT_SAMPLES);
            appendMels(melInput);
            for (int i = accumulatedSamples / CHUNK_SAMPLES - 1; i >= 0; i--) {
                int endRow = i == 0 ? melRows : melRows - (8 * i);
                int startRow = endRow - MEL_WINDOW_FRAMES;
                if (startRow >= 0 && endRow <= melRows) {
                    float[] window = new float[MEL_WINDOW_FRAMES * MEL_BINS];
                    System.arraycopy(melBuffer, startRow * MEL_BINS, window, 0, window.length);
                    appendFeature(embedding(window));
                }
            }
            accumulatedSamples = 0;
        }

        float[] latestFeatures(int rows) {
            float[] out = new float[rows * EMBEDDING_DIM];
            int available = Math.min(rows, featureRows);
            int srcRow = featureRows - available;
            int dstRow = rows - available;
            System.arraycopy(featureBuffer, srcRow * EMBEDDING_DIM, out, dstRow * EMBEDDING_DIM, available * EMBEDDING_DIM);
            return out;
        }

        private short[] lastRaw(int maxSamples) {
            int count = Math.min(maxSamples, raw.size());
            short[] out = new short[count];
            int skip = raw.size() - count;
            int index = 0;
            int seen = 0;
            for (short value : raw) {
                if (seen++ < skip) {
                    continue;
                }
                out[index++] = value;
            }
            return out;
        }

        private void appendMels(short[] samples) throws Exception {
            MelOutput mels = melspectrogram(samples);
            float[] combined = new float[(melRows + mels.rows) * MEL_BINS];
            System.arraycopy(melBuffer, 0, combined, 0, melRows * MEL_BINS);
            System.arraycopy(mels.values, 0, combined, melRows * MEL_BINS, mels.values.length);
            int rows = melRows + mels.rows;
            int maxRows = 10 * 97;
            if (rows > maxRows) {
                float[] trimmed = new float[maxRows * MEL_BINS];
                System.arraycopy(combined, (rows - maxRows) * MEL_BINS, trimmed, 0, trimmed.length);
                melBuffer = trimmed;
                melRows = maxRows;
            } else {
                melBuffer = combined;
                melRows = rows;
            }
        }

        private void appendFeature(float[] feature) {
            float[] combined = new float[(featureRows + 1) * EMBEDDING_DIM];
            System.arraycopy(featureBuffer, 0, combined, 0, featureRows * EMBEDDING_DIM);
            System.arraycopy(feature, 0, combined, featureRows * EMBEDDING_DIM, EMBEDDING_DIM);
            int rows = featureRows + 1;
            int maxRows = 120;
            if (rows > maxRows) {
                float[] trimmed = new float[maxRows * EMBEDDING_DIM];
                System.arraycopy(combined, (rows - maxRows) * EMBEDDING_DIM, trimmed, 0, trimmed.length);
                featureBuffer = trimmed;
                featureRows = maxRows;
            } else {
                featureBuffer = combined;
                featureRows = rows;
            }
        }

        private float[] embeddings(short[] samples) throws Exception {
            MelOutput spec = melspectrogram(samples);
            int windows = Math.max(0, ((spec.rows - MEL_WINDOW_FRAMES) / 8) + 1);
            float[] out = new float[windows * EMBEDDING_DIM];
            for (int i = 0; i < windows; i++) {
                float[] window = new float[MEL_WINDOW_FRAMES * MEL_BINS];
                System.arraycopy(spec.values, i * 8 * MEL_BINS, window, 0, window.length);
                float[] embedding = embedding(window);
                System.arraycopy(embedding, 0, out, i * EMBEDDING_DIM, EMBEDDING_DIM);
            }
            return out;
        }

        private MelOutput melspectrogram(short[] samples) throws Exception {
            float[] input = new float[samples.length];
            for (int i = 0; i < samples.length; i++) {
                input[i] = samples[i];
            }
            try (OnnxTensor tensor = tensor(env, input, new long[]{1, samples.length});
                 OrtSession.Result output = melSession.run(Collections.singletonMap("input", tensor))) {
                OnnxTensor out = (OnnxTensor) output.get(0);
                long[] shape = out.getInfo().getShape();
                if (shape.length != 4 || shape[0] != 1 || shape[1] != 1) {
                    throw new IllegalArgumentException("unexpected mel output shape");
                }
                int rows = (int) shape[2];
                int bins = (int) shape[shape.length - 1];
                if (bins != MEL_BINS) {
                    throw new IllegalArgumentException("unexpected mel bins: " + bins);
                }
                FloatBuffer buffer = out.getFloatBuffer();
                float[] values = new float[rows * MEL_BINS];
                for (int row = 0; row < rows; row++) {
                    int base = row * MEL_BINS;
                    for (int bin = 0; bin < MEL_BINS; bin++) {
                        values[base + bin] = (buffer.get(base + bin) / 10.0f) + 2.0f;
                    }
                }
                MelOutput result = new MelOutput();
                result.rows = rows;
                result.values = values;
                return result;
            }
        }

        private float[] embedding(float[] melWindow) throws Exception {
            try (OnnxTensor tensor = tensor(env, melWindow, new long[]{1, MEL_WINDOW_FRAMES, MEL_BINS, 1});
                 OrtSession.Result output = embeddingSession.run(Collections.singletonMap("input_1", tensor))) {
                OnnxTensor out = (OnnxTensor) output.get(0);
                FloatBuffer buffer = out.getFloatBuffer();
                float[] values = new float[EMBEDDING_DIM];
                buffer.get(values);
                return values;
            }
        }
    }

    private static final class MelOutput {
        int rows;
        float[] values;
    }

    static final class Result {
        double maxScore;
        long firstHitMs;
        int frames;
        int sampleRate;
        int samples;
        String ortVersion;
        JSONArray providers;
        long elapsedMs;
        double inputGainDb;
        double inputRmsDbfs;
        double inputPeakDbfs;
        double gainedInputRmsDbfs;
        double gainedInputPeakDbfs;
        int clippedSamples;
    }

    private static OnnxTensor tensor(OrtEnvironment env, float[] values, long[] shape) throws Exception {
        ByteBuffer bytes = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer floats = bytes.asFloatBuffer();
        floats.put(values);
        floats.rewind();
        return OnnxTensor.createTensor(env, floats, shape);
    }

    private static float[] ones(int count) {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = 1.0f;
        }
        return values;
    }

    private static short[] pad(short[] samples, int paddingSamples) {
        short[] out = new short[samples.length + (paddingSamples * 2)];
        System.arraycopy(samples, 0, out, paddingSamples, samples.length);
        return out;
    }

    private static GainResult applyGain(short[] samples, double gainDb) {
        if (Math.abs(gainDb) < 0.001) {
            return new GainResult(samples, inputLevel(samples), 0);
        }
        double multiplier = Math.pow(10.0, gainDb / 20.0);
        short[] out = new short[samples.length];
        int clippedSamples = 0;
        for (int i = 0; i < samples.length; i++) {
            int value = (int) Math.round(samples[i] * multiplier);
            if (value > Short.MAX_VALUE) {
                value = Short.MAX_VALUE;
                clippedSamples++;
            } else if (value < Short.MIN_VALUE) {
                value = Short.MIN_VALUE;
                clippedSamples++;
            }
            out[i] = (short) value;
        }
        return new GainResult(out, inputLevel(out), clippedSamples);
    }

    private static InputLevel inputLevel(short[] samples) {
        long squares = 0L;
        int peak = 0;
        for (short sample : samples) {
            int value = Math.min(Math.abs((int) sample), Short.MAX_VALUE);
            if (value > peak) {
                peak = value;
            }
            squares += (long) value * (long) value;
        }
        double rms = Math.sqrt(squares / (double) Math.max(1, samples.length));
        return new InputLevel(dbfs(rms), dbfs(peak));
    }

    private static double dbfs(double value) {
        if (value <= 0.0) {
            return -120.0;
        }
        return round(20.0 * Math.log10(value / Short.MAX_VALUE));
    }

    private static final class InputLevel {
        final double rmsDbfs;
        final double peakDbfs;

        InputLevel(double rmsDbfs, double peakDbfs) {
            this.rmsDbfs = rmsDbfs;
            this.peakDbfs = peakDbfs;
        }
    }

    private static final class GainResult {
        final short[] samples;
        final InputLevel inputLevel;
        final int clippedSamples;

        GainResult(short[] samples, InputLevel inputLevel, int clippedSamples) {
            this.samples = samples;
            this.inputLevel = inputLevel;
            this.clippedSamples = clippedSamples;
        }
    }

    private static double round(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.6f", value));
    }

    private static WavData parseWavPcm16Mono16k(byte[] bytes) {
        if (bytes.length < 44) {
            throw new IllegalArgumentException("WAV is too small");
        }
        if (!"RIFF".equals(ascii(bytes, 0, 4)) || !"WAVE".equals(ascii(bytes, 8, 4))) {
            throw new IllegalArgumentException("expected RIFF/WAVE WAV");
        }
        int offset = 12;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int dataOffset = -1;
        int dataSize = 0;
        while (offset + 8 <= bytes.length) {
            String id = ascii(bytes, offset, 4);
            int size = le32(bytes, offset + 4);
            int payload = offset + 8;
            if (payload + size > bytes.length) {
                throw new IllegalArgumentException("invalid WAV chunk size");
            }
            if ("fmt ".equals(id)) {
                int format = le16(bytes, payload);
                channels = le16(bytes, payload + 2);
                sampleRate = le32(bytes, payload + 4);
                bitsPerSample = le16(bytes, payload + 14);
                if (format != 1) {
                    throw new IllegalArgumentException("expected PCM WAV format");
                }
            } else if ("data".equals(id)) {
                dataOffset = payload;
                dataSize = size;
                break;
            }
            offset = payload + size + (size & 1);
        }
        if (channels != 1 || sampleRate != SAMPLE_RATE || bitsPerSample != 16) {
            throw new IllegalArgumentException("expected 16 kHz mono 16-bit PCM WAV");
        }
        if (dataOffset < 0 || dataSize <= 0 || (dataSize % 2) != 0) {
            throw new IllegalArgumentException("missing PCM data");
        }
        short[] samples = new short[dataSize / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) le16(bytes, dataOffset + (i * 2));
        }
        WavData wav = new WavData();
        wav.sampleRate = sampleRate;
        wav.samples = samples;
        return wav;
    }

    private static final class WavData {
        int sampleRate;
        short[] samples;
    }

    private static String ascii(byte[] bytes, int offset, int count) {
        return new String(bytes, offset, count, StandardCharsets.US_ASCII);
    }

    private static int le16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
