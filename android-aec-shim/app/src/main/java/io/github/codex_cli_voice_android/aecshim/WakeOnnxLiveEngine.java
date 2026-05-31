package io.github.codex_cli_voice_android.aecshim;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

final class WakeOnnxLiveEngine {
    private static final String TAG = "CodexWakeOnnx";

    interface Listener {
        void onScore(
                double score,
                int frames,
                long elapsedMs,
                long computeMs,
                double inputRmsDbfs,
                double inputPeakDbfs,
                double gainedInputRmsDbfs,
                double gainedInputPeakDbfs,
                int clippedSamples);

        void onDetected(
                double score,
                int frames,
                long elapsedMs,
                long computeMs,
                double inputRmsDbfs,
                double inputPeakDbfs,
                double gainedInputRmsDbfs,
                double gainedInputPeakDbfs,
                int clippedSamples);

        void onError(String code, String message);
    }

    private final Context context;
    private final WakeProfile profile;
    private final Listener listener;
    private volatile boolean running;
    private Thread thread;
    private AudioRecord recorder;

    WakeOnnxLiveEngine(Context context, WakeProfile profile, boolean emitScores, Listener listener) {
        this.context = context.getApplicationContext();
        this.profile = profile;
        this.listener = listener;
    }

    synchronized void start() {
        if (running) {
            return;
        }
        Log.i(TAG, "start profile=" + profile.id);
        running = true;
        thread = new Thread(this::runLoop, "codex-wake-onnx-live");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        long started = System.currentTimeMillis();
        Log.i(TAG, "stop begin thread=" + threadStateLocked());
        running = false;
        if (recorder != null) {
            try {
                Log.i(TAG, "AudioRecord.stop begin");
                recorder.stop();
                Log.i(TAG, "AudioRecord.stop complete");
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord.stop failed", e);
            }
        }
        if (thread != null && Thread.currentThread() != thread) {
            try {
                Log.i(TAG, "join begin");
                thread.join(1500);
                Log.i(TAG, "join complete alive=" + thread.isAlive());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "join interrupted", e);
            }
        }
        cleanupRecorder();
        thread = null;
        Log.i(TAG, "stop complete elapsedMs=" + (System.currentTimeMillis() - started));
    }

    synchronized String threadState() {
        return threadStateLocked();
    }

    private void runLoop() {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        long startedAtMs = System.currentTimeMillis();
        int frames = 0;
        Log.i(TAG, "runLoop begin profile=" + profile.id);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(1);
            Log.i(TAG, "ONNX sessions open begin");
            try (OrtSession melSession = env.createSession(profile.melspectrogramPath, options);
                 OrtSession embeddingSession = env.createSession(profile.embeddingPath, options);
                 OrtSession wakeSession = env.createSession(profile.modelPath, options)) {
                Log.i(TAG, "ONNX sessions open complete");
                WakeOnnxClipProbe.StreamingState state = new WakeOnnxClipProbe.StreamingState(env, melSession, embeddingSession);
                AudioRecord localRecorder = createRecorder();
                synchronized (this) {
                    recorder = localRecorder;
                }
                Log.i(TAG, "AudioRecord.startRecording begin");
                localRecorder.startRecording();
                Log.i(TAG, "AudioRecord.startRecording complete");
                short[] chunk = new short[WakeOnnxClipProbe.CHUNK_SAMPLES];
                while (running) {
                    int offset = 0;
                    while (running && offset < chunk.length) {
                        int read = localRecorder.read(chunk, offset, chunk.length - offset);
                        if (read < 0) {
                            throw new IllegalStateException("AudioRecord read failed: " + read);
                        }
                        offset += read;
                    }
                    if (!running) {
                        break;
                    }
                    InputLevel inputLevel = inputLevel(chunk);
                    GainResult gainResult = applyGain(chunk, profile.inputGainDb);
                    long computeStart = System.currentTimeMillis();
                    state.accept(gainResult.samples);
                    double score = WakeOnnxClipProbe.runWakeClassifier(
                            env,
                            wakeSession,
                            state.latestFeatures(16));
                    if (frames < 5) {
                        score = 0.0;
                    }
                    long computeMs = System.currentTimeMillis() - computeStart;
                    long elapsedMs = System.currentTimeMillis() - startedAtMs;
                    frames++;
                    if (score >= profile.threshold) {
                        Log.i(TAG, "detected score=" + score + " frame=" + frames + " elapsedMs=" + elapsedMs);
                        listener.onDetected(
                                score,
                                frames,
                                elapsedMs,
                                computeMs,
                                inputLevel.rmsDbfs,
                                inputLevel.peakDbfs,
                                gainResult.inputLevel.rmsDbfs,
                                gainResult.inputLevel.peakDbfs,
                                gainResult.clippedSamples);
                        running = false;
                        break;
                    }
                    listener.onScore(
                            score,
                            frames,
                            elapsedMs,
                            computeMs,
                            inputLevel.rmsDbfs,
                            inputLevel.peakDbfs,
                            gainResult.inputLevel.rmsDbfs,
                            gainResult.inputLevel.peakDbfs,
                            gainResult.clippedSamples);
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "permission denied", e);
            listener.onError("wake_permission_denied", e.getMessage() == null ? "RECORD_AUDIO denied" : e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "live wake error", e);
            listener.onError("wake_inference_error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            Log.i(TAG, "runLoop cleanup begin");
            running = false;
            cleanupRecorder();
            Log.i(TAG, "runLoop cleanup complete frames=" + frames);
        }
    }

    private AudioRecord createRecorder() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("RECORD_AUDIO permission denied");
        }
        int minBuffer = AudioRecord.getMinBufferSize(
                WakeOnnxClipProbe.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            throw new IllegalStateException("AudioRecord min buffer failed: " + minBuffer);
        }
        int bufferSize = Math.max(minBuffer, WakeOnnxClipProbe.CHUNK_SAMPLES * 4);
        Log.i(TAG, "AudioRecord create bufferSize=" + bufferSize + " minBuffer=" + minBuffer);
        AudioRecord record = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WakeOnnxClipProbe.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            try {
                record.release();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("AudioRecord failed to initialize");
        }
        return record;
    }

    private static InputLevel inputLevel(short[] samples) {
        long squares = 0L;
        int peak = 0;
        for (short sample : samples) {
            int value = Math.abs((int) sample);
            if (value > peak) {
                peak = value;
            }
            squares += (long) value * (long) value;
        }
        double rms = Math.sqrt(squares / (double) Math.max(1, samples.length));
        return new InputLevel(dbfs(rms), dbfs(peak));
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

    private static double dbfs(double value) {
        if (value <= 0.0) {
            return -120.0;
        }
        return 20.0 * Math.log10(value / 32767.0);
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

    private synchronized void cleanupRecorder() {
        if (recorder != null) {
            try {
                Log.i(TAG, "AudioRecord.release begin");
                recorder.release();
                Log.i(TAG, "AudioRecord.release complete");
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord.release failed", e);
            }
            recorder = null;
        }
    }

    private String threadStateLocked() {
        Thread current = thread;
        if (current == null) {
            return "none/running=" + running;
        }
        return current.getState().name() + "/alive=" + current.isAlive() + "/running=" + running;
    }
}
