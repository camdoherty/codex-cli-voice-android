package io.github.codex_cli_voice_android.aecshim;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioDeviceInfo;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class WakeWordTestController {
    private static final String OWNER = "wake_test";
    private static final int RING_SECONDS = 20;
    private static final int RING_SAMPLES = WakeOnnxClipProbe.SAMPLE_RATE * RING_SECONDS;
    private static final double DEFAULT_THRESHOLD = 0.997;

    private final Context context;
    private final AudioModeCoordinator audioModeCoordinator;
    private final Object ringLock = new Object();
    private final short[] ring = new short[RING_SAMPLES];

    private volatile boolean running;
    private Thread thread;
    private AudioRecord recorder;
    private int ringWrite;
    private int ringCount;

    WakeWordTestController(Context context, AudioModeCoordinator audioModeCoordinator) {
        this.context = context.getApplicationContext();
        this.audioModeCoordinator = audioModeCoordinator;
    }

    synchronized void start() {
        if (running) {
            WakeWordTestStatus.lastError = "";
            return;
        }
        WakeProfile profile = defaultProfile();
        String validationError = validateProfile(profile);
        if (!validationError.isEmpty()) {
            WakeWordTestStatus.lastError = validationError;
            AecShimState.lastError = validationError;
            return;
        }
        if (!audioModeCoordinator.tryAcquire(AudioModeCoordinator.Mode.WAKE_WORD, OWNER)) {
            String error = "wake test audio busy: " + audioModeCoordinator.modeName();
            WakeWordTestStatus.lastError = error;
            AecShimState.lastError = error;
            return;
        }

        clearRing();
        running = true;
        WakeWordTestStatus.resetRun(profile.threshold);
        thread = new Thread(() -> runLoop(profile), "codex-wake-test");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        stopInternal();
    }

    synchronized void pass() {
        if (!running) {
            WakeWordTestStatus.lastError = "wake test mode is not running";
            return;
        }
        WakeWordTestStatus.passCount++;
        WakeWordTestStatus.lastVerdict = "pass";
        WakeWordTestStatus.resetWindowMetrics();
    }

    synchronized void fail() {
        if (!running) {
            WakeWordTestStatus.lastError = "wake test mode is not running";
            return;
        }
        try {
            short[] snapshot = snapshotRing();
            if (snapshot.length == 0) {
                WakeWordTestStatus.lastError = "no wake test audio captured yet";
                return;
            }
            String displayName = saveFailWav(snapshot);
            WakeWordTestStatus.failCount++;
            WakeWordTestStatus.lastVerdict = "fail";
            WakeWordTestStatus.lastSavedFile = displayName;
            WakeWordTestStatus.lastError = "";
            WakeWordTestStatus.resetWindowMetrics();
        } catch (Exception e) {
            String error = "wake test save failed: " + safeMessage(e);
            WakeWordTestStatus.lastError = error;
            AecShimState.lastError = error;
        }
    }

    synchronized void shutdown() {
        stopInternal();
    }

    private void runLoop(WakeProfile profile) {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        long startedAtMs = System.currentTimeMillis();
        int frames = 0;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(1);
            try (OrtSession melSession = env.createSession(profile.melspectrogramPath, options);
                 OrtSession embeddingSession = env.createSession(profile.embeddingPath, options);
                 OrtSession wakeSession = env.createSession(profile.modelPath, options)) {
                WakeOnnxClipProbe.StreamingState state = new WakeOnnxClipProbe.StreamingState(env, melSession, embeddingSession);
                AudioRecord localRecorder = createRecorder();
                synchronized (this) {
                    recorder = localRecorder;
                }
                localRecorder.startRecording();
                updateRoutedInput(localRecorder);
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
                    appendRing(chunk);
                    updateInputLevel(chunk);
                    updateRoutedInput(localRecorder);
                    long computeStart = System.currentTimeMillis();
                    state.accept(chunk);
                    double score = WakeOnnxClipProbe.runWakeClassifier(
                            env,
                            wakeSession,
                            state.latestFeatures(16));
                    if (frames < 5) {
                        score = 0.0;
                    }
                    long now = System.currentTimeMillis();
                    long computeMs = now - computeStart;
                    long elapsedMs = now - startedAtMs;
                    frames++;
                    updateScore(profile, score, frames, elapsedMs, computeMs, now);
                }
            }
        } catch (SecurityException e) {
            setError("wake test permission denied: " + safeMessage(e));
        } catch (Exception e) {
            setError("wake test error: " + safeMessage(e));
        } finally {
            synchronized (this) {
                running = false;
                cleanupRecorder();
                audioModeCoordinator.release(AudioModeCoordinator.Mode.WAKE_WORD, OWNER);
                WakeWordTestStatus.stopRun();
                thread = null;
            }
        }
    }

    private void updateScore(WakeProfile profile, double score, int frames, long elapsedMs, long computeMs, long nowMs) {
        WakeWordTestStatus.lastScore = score;
        WakeWordTestStatus.lastFrame = frames;
        WakeWordTestStatus.lastElapsedMs = elapsedMs;
        WakeWordTestStatus.lastComputeMs = computeMs;
        if (score > WakeWordTestStatus.maxScore) {
            WakeWordTestStatus.maxScore = score;
            WakeWordTestStatus.maxFrame = frames;
        }
        if (score >= profile.threshold) {
            WakeWordTestStatus.lastHitAtMs = nowMs;
        }
    }

    private void updateInputLevel(short[] samples) {
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
        WakeWordTestStatus.inputRmsDbfs = dbfs(rms);
        WakeWordTestStatus.inputPeakDbfs = dbfs(peak);
    }

    private void updateRoutedInput(AudioRecord localRecorder) {
        AudioDeviceInfo device = localRecorder.getRoutedDevice();
        if (device == null) {
            WakeWordTestStatus.routedInput = "unknown";
            return;
        }
        CharSequence product = device.getProductName();
        WakeWordTestStatus.routedInput = deviceTypeName(device.getType())
                + " id=" + device.getId()
                + " name=" + (product == null ? "" : product);
    }

    private synchronized void stopInternal() {
        running = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
        }
        if (thread != null && Thread.currentThread() != thread) {
            try {
                thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        cleanupRecorder();
    }

    private WakeProfile defaultProfile() {
        File base = new File(context.getFilesDir(), "wakeword_models/hey_jarvis_dev");
        return new WakeProfile(
                "hey_jarvis_dev",
                "onnx",
                "hey jarvis",
                "onnx",
                new File(base, "hey_jarvis_v0.1.onnx").getAbsolutePath(),
                new File(base, "melspectrogram.onnx").getAbsolutePath(),
                new File(base, "embedding_model.onnx").getAbsolutePath(),
                WakeOnnxClipProbe.SAMPLE_RATE,
                80,
                DEFAULT_THRESHOLD,
                1500L,
                true);
    }

    private String validateProfile(WakeProfile profile) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "RECORD_AUDIO permission denied";
        }
        if (!WakeProfile.readableFile(profile.modelPath)) {
            return "wake model missing: " + profile.modelPath;
        }
        if (!WakeProfile.readableFile(profile.melspectrogramPath)) {
            return "wake melspectrogram model missing: " + profile.melspectrogramPath;
        }
        if (!WakeProfile.readableFile(profile.embeddingPath)) {
            return "wake embedding model missing: " + profile.embeddingPath;
        }
        return "";
    }

    private AudioRecord createRecorder() {
        int minBuffer = AudioRecord.getMinBufferSize(
                WakeOnnxClipProbe.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            throw new IllegalStateException("AudioRecord min buffer failed: " + minBuffer);
        }
        int bufferSize = Math.max(minBuffer, WakeOnnxClipProbe.CHUNK_SAMPLES * 4);
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

    private synchronized void cleanupRecorder() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
    }

    private void appendRing(short[] samples) {
        synchronized (ringLock) {
            for (short sample : samples) {
                ring[ringWrite] = sample;
                ringWrite = (ringWrite + 1) % ring.length;
                if (ringCount < ring.length) {
                    ringCount++;
                }
            }
        }
    }

    private short[] snapshotRing() {
        synchronized (ringLock) {
            short[] out = new short[ringCount];
            int start = (ringWrite - ringCount + ring.length) % ring.length;
            for (int i = 0; i < ringCount; i++) {
                out[i] = ring[(start + i) % ring.length];
            }
            return out;
        }
    }

    private void clearRing() {
        synchronized (ringLock) {
            ringWrite = 0;
            ringCount = 0;
        }
    }

    private String saveFailWav(short[] samples) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String score = String.format(Locale.US, "%.6f", WakeWordTestStatus.maxScore).replace('.', '_');
        String displayName = "wake-fail-" + stamp + "-score-" + score + ".wav";

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CodexWakeTests");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IllegalStateException("failed to create MediaStore item");
        }
        try (OutputStream stream = resolver.openOutputStream(uri)) {
            if (stream == null) {
                throw new IllegalStateException("failed to open MediaStore output stream");
            }
            writeWav(stream, samples);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return "Download/CodexWakeTests/" + displayName;
    }

    private static void writeWav(OutputStream stream, short[] samples) throws Exception {
        int dataBytes = samples.length * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(36 + dataBytes);
        header.put(new byte[]{'W', 'A', 'V', 'E'});
        header.put(new byte[]{'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(WakeOnnxClipProbe.SAMPLE_RATE);
        header.putInt(WakeOnnxClipProbe.SAMPLE_RATE * 2);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(dataBytes);
        stream.write(header.array());

        ByteBuffer data = ByteBuffer.allocate(Math.min(dataBytes, 8192)).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            if (data.remaining() < 2) {
                stream.write(data.array(), 0, data.position());
                data.clear();
            }
            data.putShort(sample);
        }
        if (data.position() > 0) {
            stream.write(data.array(), 0, data.position());
        }
    }

    private void setError(String error) {
        WakeWordTestStatus.lastError = error;
        AecShimState.lastError = error;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private static double dbfs(double value) {
        if (value <= 0.0) {
            return -120.0;
        }
        return 20.0 * Math.log10(Math.min(value, 32768.0) / 32768.0);
    }

    private static String deviceTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "built_in_mic";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "bluetooth_sco";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "ble_headset";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "usb_headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "wired_headset";
            case AudioDeviceInfo.TYPE_TELEPHONY:
                return "telephony";
            default:
                return "type_" + type;
        }
    }
}
