package io.github.codex_cli_voice_android.aecshim;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.Base64;
import android.util.Log;

import org.java_websocket.WebSocket;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class WakeWordController {
    private static final String TAG = "CodexWakeController";

    interface Sender {
        WebSocket currentClient();

        void send(WebSocket conn, JSONObject out);

        void sendError(WebSocket conn, String id, String code, String message);
    }

    private static final String OWNER = "wake_word";
    static final long DEFAULT_MAX_LISTEN_MS = 60L * 60L * 1000L;
    static final long HARD_MAX_LISTEN_MS = DEFAULT_MAX_LISTEN_MS;
    private static final int MAX_MODEL_FILE_BYTES = 8 * 1024 * 1024;

    private final Context context;
    private final AudioModeCoordinator audioModeCoordinator;
    private final WakeWordEngine engine;
    private final Sender sender;
    private final ExecutorService onnxExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "codex-wake-onnx-probe");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "codex-wake-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private ScheduledFuture<?> timeoutFuture;
    private boolean listening;
    private long startedAtMs;
    private WakeProfile activeProfile;
    private WakeOnnxLiveEngine liveEngine;
    private boolean liveMode;
    private boolean liveDebugScores;
    private long wakeGeneration;
    private long activeWakeGeneration;

    WakeWordController(Context context, AudioModeCoordinator audioModeCoordinator, WakeWordEngine engine, Sender sender) {
        this.context = context.getApplicationContext();
        this.audioModeCoordinator = audioModeCoordinator;
        this.engine = engine;
        this.sender = sender;
        activeProfile = engine.profile();
        WakeWordStatus.wakeProfileId = activeProfile.id;
        setIdle("");
    }

    synchronized void status(String id) {
        sender.send(sender.currentClient(), baseEvent(id, "wake_status"));
    }

    synchronized void start(String id, JSONObject in) {
        if (listening) {
            sender.sendError(sender.currentClient(), id, "wake_audio_busy", "wake word is already listening");
            return;
        }
        boolean requestedLiveMode = hasByoProfile(in);
        WakeProfile requestedProfile = requestedLiveMode ? WakeProfile.fromJson(in) : engine.profile();
        JSONObject validation = new JSONObject();
        if (requestedLiveMode) {
            try {
                putByoValidation(validation, requestedProfile);
                if (!validation.optBoolean("valid", false)) {
                    JSONObject out = baseEvent(id, "wake_start");
                    out.put("valid", false);
                    out.put("errors", validation.optJSONArray("errors"));
                    out.put("warnings", validation.optJSONArray("warnings"));
                    out.put("message", "profile validation failed");
                    sender.send(sender.currentClient(), out);
                    return;
                }
            } catch (JSONException e) {
                sender.sendError(sender.currentClient(), id, "wake_model_invalid", e.getMessage());
                return;
            }
        }
        if (!audioModeCoordinator.tryAcquire(AudioModeCoordinator.Mode.WAKE_WORD, OWNER)) {
            sender.sendError(sender.currentClient(), id, "wake_audio_busy", "audio mode is " + audioModeCoordinator.modeName());
            return;
        }
        long now = System.currentTimeMillis();
        long maxListenMs = clampMaxListenMs(in.optLong("maxListenMs", DEFAULT_MAX_LISTEN_MS));
        long generation = ++wakeGeneration;
        activeWakeGeneration = generation;
        activeProfile = requestedProfile;
        liveMode = requestedLiveMode;
        liveDebugScores = in.optBoolean("debugScores", false);
        listening = true;
        startedAtMs = now;
        WakeWordStatus.wakeState = "listening";
        WakeWordStatus.wakeProfileId = activeProfile.id;
        WakeWordStatus.wakeStartedAtMs = now;
        WakeWordStatus.wakeDeadlineAtMs = now + maxListenMs;
        WakeWordStatus.wakeMaxListenMs = maxListenMs;
        WakeWordStatus.lastWakeScore = 0.0;
        WakeWordStatus.lastWakeFrame = 0;
        WakeWordStatus.lastWakeLatencyMs = 0L;
        WakeWordStatus.lastWakeComputeMs = 0L;
        WakeWordStatus.lastWakeEvent = "wake_started";
        WakeWordStatus.lastWakeError = "";
        WakeWordStatus.lastWakeStopReason = "";
        WakeWordStatus.lastWakeStopStartedAtMs = 0L;
        WakeWordStatus.lastWakeStopCompletedAtMs = 0L;
        WakeWordStatus.lastWakeEngineThreadState = "";
        Log.i(TAG, "wake_start live=" + liveMode + " profile=" + activeProfile.id + " generation=" + generation);
        if (liveMode) {
            liveEngine = new WakeOnnxLiveEngine(
                    context,
                    activeProfile,
                    liveDebugScores,
                    new LiveWakeCallbacks(generation));
            liveEngine.start();
        } else {
            engine.start();
        }
        scheduleTimeout(maxListenMs);
        sender.send(sender.currentClient(), baseEvent(id, "wake_started"));
    }

    void stop(String id, boolean sendAck) {
        WakeOnnxLiveEngine engineToStop;
        synchronized (this) {
            if (!listening) {
                if (sendAck) {
                    String event = "stopping".equals(WakeWordStatus.wakeState) ? "wake_stopping" : "wake_idle";
                    sender.send(sender.currentClient(), baseEvent(id, event));
                }
                return;
            }
            engineToStop = beginStopLocked("wake_stopped");
        }
        stopEngineAndFinalize(engineToStop, "wake_stopped");
        if (sendAck) {
            sender.send(sender.currentClient(), baseEvent(id, "wake_stopped"));
        }
    }

    void fakeDetect(String id) {
        WakeOnnxLiveEngine engineToStop;
        synchronized (this) {
            if (!listening || !engine.fakeDetect()) {
                sender.sendError(sender.currentClient(), id, "wake_not_listening", "wake word is not listening");
                return;
            }
            WakeWordStatus.lastWakeLatencyMs = Math.max(0L, System.currentTimeMillis() - startedAtMs);
            engineToStop = beginStopLocked("wake_detected");
        }
        stopEngineAndFinalize(engineToStop, "wake_detected");
        sender.send(sender.currentClient(), baseEvent(id, "wake_detected"));
    }

    synchronized void cueReady(String id) {
        long started = System.currentTimeMillis();
        boolean ok = false;
        String error = "";
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            try {
                ok = tone.startTone(ToneGenerator.TONE_PROP_ACK, 120);
            } finally {
                tone.release();
            }
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            WakeWordStatus.lastWakeError = "cue_ready: " + error;
            Log.w(TAG, "cue_ready failed", e);
        }
        JSONObject out = baseEvent(id, ok ? "cue_ready" : "cue_failed");
        try {
            out.put("ok", ok);
            out.put("latencyMs", System.currentTimeMillis() - started);
            if (!error.isEmpty()) {
                out.put("message", error);
            }
        } catch (JSONException ignored) {
        }
        sender.send(sender.currentClient(), out);
    }

    synchronized void validate(String id, JSONObject in) {
        JSONObject out = baseEvent(id, "wake_model_validate");
        try {
            if (hasByoProfile(in)) {
                putByoValidation(out, WakeProfile.fromJson(in));
            } else {
                out.put("valid", engine.validateProfile());
                out.put("validationScope", WakeProfile.VALIDATION_SCOPE);
                out.put("message", "Fake/manual wake profile validation only; send profile/modelPath fields for BYO path validation.");
                out.put("profile", engine.profile().json());
            }
        } catch (JSONException ignored) {
        }
        sender.send(sender.currentClient(), out);
    }

    synchronized void putModelFile(String id, JSONObject in) {
        String profileId = sanitizePathPart(in.optString("profileId", "byo_wake_profile"));
        String filename = sanitizePathPart(in.optString("filename", ""));
        String dataBase64 = in.optString("dataBase64", "");
        String expectedSha256 = in.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
        if (filename.isEmpty()) {
            sender.sendError(sender.currentClient(), id, "wake_model_invalid", "filename is required");
            return;
        }
        if (dataBase64.isEmpty()) {
            sender.sendError(sender.currentClient(), id, "wake_model_invalid", "dataBase64 is required");
            return;
        }

        byte[] data;
        try {
            data = Base64.decode(dataBase64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            sender.sendError(sender.currentClient(), id, "wake_model_invalid", "dataBase64 is invalid");
            return;
        }
        if (data.length <= 0 || data.length > MAX_MODEL_FILE_BYTES) {
            sender.sendError(sender.currentClient(), id, "wake_model_invalid", "file size must be between 1 byte and 8 MiB");
            return;
        }

        String actualSha256 = sha256(data);
        if (!expectedSha256.isEmpty() && !expectedSha256.equals(actualSha256)) {
            sender.sendError(sender.currentClient(), id, "wake_model_sha_mismatch", "sha256 mismatch");
            return;
        }

        File dir = new File(context.getFilesDir(), "wakeword_models/" + profileId);
        File outFile = new File(dir, filename);
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                sender.sendError(sender.currentClient(), id, "wake_model_write_failed", "failed to create model directory");
                return;
            }
            try (FileOutputStream stream = new FileOutputStream(outFile, false)) {
                stream.write(data);
            }
            JSONObject out = baseEvent(id, "wake_model_put");
            out.put("profileId", profileId);
            out.put("filename", filename);
            out.put("path", outFile.getAbsolutePath());
            out.put("bytes", data.length);
            out.put("sha256", actualSha256);
            sender.send(sender.currentClient(), out);
        } catch (Exception e) {
            sender.sendError(sender.currentClient(), id, "wake_model_write_failed", e.getMessage());
        }
    }

    synchronized void onnxProbe(String id, JSONObject in) {
        WebSocket conn = sender.currentClient();
        WakeProfile profile = WakeProfile.fromJson(in);
        String audioWavBase64 = in.optString("audioWavBase64", "");
        JSONObject validation = new JSONObject();
        try {
            putByoValidation(validation, profile);
            if (!validation.optBoolean("valid", false)) {
                JSONObject out = baseEvent(id, "wake_onnx_probe");
                out.put("valid", false);
                out.put("errors", validation.optJSONArray("errors"));
                out.put("warnings", validation.optJSONArray("warnings"));
                out.put("message", "profile validation failed");
                sender.send(conn, out);
                return;
            }
            if (audioWavBase64.isEmpty()) {
                sender.sendError(conn, id, "wake_audio_invalid", "audioWavBase64 is required");
                return;
            }
        } catch (JSONException e) {
            sender.sendError(conn, id, "wake_onnx_error", e.getMessage());
            return;
        }
        onnxExecutor.execute(() -> runOnnxProbe(conn, id, profile, audioWavBase64));
    }

    private void runOnnxProbe(WebSocket conn, String id, WakeProfile profile, String audioWavBase64) {
        JSONObject out = baseEvent(id, "wake_onnx_probe");
        try {
            byte[] wav = Base64.decode(audioWavBase64, Base64.DEFAULT);
            WakeOnnxClipProbe.Result result = WakeOnnxClipProbe.run(profile, wav);
            out.put("valid", true);
            out.put("triggered", result.maxScore >= profile.threshold);
            out.put("maxScore", result.maxScore);
            out.put("threshold", profile.threshold);
            out.put("firstHitMs", result.firstHitMs >= 0L ? result.firstHitMs : JSONObject.NULL);
            out.put("frames", result.frames);
            out.put("sampleRate", result.sampleRate);
            out.put("samples", result.samples);
            out.put("ortVersion", result.ortVersion);
            out.put("providers", result.providers);
            out.put("elapsedMs", result.elapsedMs);
            sender.send(conn, out);
        } catch (IllegalArgumentException e) {
            sender.sendError(conn, id, "wake_audio_invalid", e.getMessage());
        } catch (Exception e) {
            sender.sendError(conn, id, "wake_onnx_error", e.getMessage());
        }
    }

    void shutdown() {
        WakeOnnxLiveEngine engineToStop = null;
        synchronized (this) {
            if (listening) {
                engineToStop = beginStopLocked("wake_shutdown");
            }
        }
        stopEngineAndFinalize(engineToStop, "wake_shutdown");
        onnxExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    private void onTimeout() {
        WakeOnnxLiveEngine engineToStop;
        synchronized (this) {
            if (!listening) {
                return;
            }
            engineToStop = beginStopLocked("wake_timeout");
        }
        stopEngineAndFinalize(engineToStop, "wake_timeout");
        sender.send(sender.currentClient(), baseEvent(null, "wake_timeout"));
    }

    private WakeOnnxLiveEngine beginStopLocked(String lastEvent) {
        Log.i(TAG, "beginStopLocked reason=" + lastEvent + " live=" + liveMode + " generation=" + activeWakeGeneration);
        WakeWordStatus.lastWakeStopReason = lastEvent;
        WakeWordStatus.lastWakeStopStartedAtMs = System.currentTimeMillis();
        WakeWordStatus.lastWakeStopCompletedAtMs = 0L;
        cancelTimeout();
        WakeOnnxLiveEngine engineToStop = liveEngine;
        if (liveEngine != null) {
            WakeWordStatus.lastWakeEngineThreadState = liveEngine.threadState();
        }
        liveEngine = null;
        engine.stop();
        liveMode = false;
        liveDebugScores = false;
        listening = false;
        startedAtMs = 0L;
        activeWakeGeneration = ++wakeGeneration;
        WakeWordStatus.wakeState = "stopping";
        WakeWordStatus.resetListeningFields();
        WakeWordStatus.lastWakeEvent = lastEvent == null ? "" : lastEvent;
        if (engineToStop == null) {
            finishStopLocked(lastEvent);
        }
        return engineToStop;
    }

    private void stopEngineAndFinalize(WakeOnnxLiveEngine engineToStop, String reason) {
        if (engineToStop == null) {
            return;
        }
        Log.i(TAG, "stopEngineAndFinalize begin reason=" + reason + " state=" + engineToStop.threadState());
        WakeWordStatus.lastWakeEngineThreadState = engineToStop.threadState();
        try {
            engineToStop.stop();
        } catch (Exception e) {
            WakeWordStatus.lastWakeError = "wake_stop: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            Log.w(TAG, "stopEngineAndFinalize failed reason=" + reason, e);
        }
        synchronized (this) {
            WakeWordStatus.lastWakeEngineThreadState = engineToStop.threadState();
            finishStopLocked(reason);
        }
        Log.i(TAG, "stopEngineAndFinalize complete reason=" + reason + " state=" + engineToStop.threadState());
    }

    private void finishStopLocked(String lastEvent) {
        audioModeCoordinator.release(AudioModeCoordinator.Mode.WAKE_WORD, OWNER);
        setIdle(lastEvent);
        WakeWordStatus.lastWakeStopCompletedAtMs = System.currentTimeMillis();
    }

    private void setIdle(String lastEvent) {
        WakeWordStatus.wakeState = "idle";
        WakeWordStatus.wakeProfileId = activeProfile == null ? engine.profile().id : activeProfile.id;
        WakeWordStatus.resetListeningFields();
        WakeWordStatus.lastWakeEvent = lastEvent == null ? "" : lastEvent;
    }

    private JSONObject baseEvent(String id, String event) {
        JSONObject out = new JSONObject();
        try {
            TextVoiceStatus.putId(out, id);
            out.put("event", event);
            out.put("state", TextVoiceStatus.state);
            out.put("audioMode", TextVoiceStatus.audioMode);
            WakeWordStatus.put(out);
        } catch (JSONException ignored) {
        }
        return out;
    }

    private long clampMaxListenMs(long requestedMs) {
        if (requestedMs <= 0L) {
            return DEFAULT_MAX_LISTEN_MS;
        }
        return Math.min(requestedMs, HARD_MAX_LISTEN_MS);
    }

    private void scheduleTimeout(long maxListenMs) {
        cancelTimeout();
        timeoutFuture = scheduler.schedule(this::onTimeout, maxListenMs, TimeUnit.MILLISECONDS);
    }

    private void cancelTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    private boolean hasByoProfile(JSONObject in) {
        return in.optJSONObject("profile") != null
                || !in.optString("modelPath", "").isEmpty()
                || !in.optString("melspectrogramPath", "").isEmpty()
                || !in.optString("embeddingPath", "").isEmpty();
    }

    private void putByoValidation(JSONObject out, WakeProfile profile) throws JSONException {
        JSONArray errors = new JSONArray();
        JSONArray warnings = new JSONArray();

        if (profile.id.isEmpty()) {
            errors.put("id is required");
        }
        if (profile.phrase.isEmpty()) {
            warnings.put("label/phrase is empty");
        }
        if (!"onnx".equals(profile.modelType)) {
            errors.put("modelType must be onnx for the first BYO implementation");
        }
        requireReadableFile(errors, "modelPath", profile.modelPath);
        requireReadableFile(errors, "melspectrogramPath", profile.melspectrogramPath);
        requireReadableFile(errors, "embeddingPath", profile.embeddingPath);
        if (profile.sampleRate != 16000) {
            errors.put("sampleRate must be 16000");
        }
        if (profile.frameMs != 80) {
            errors.put("frameMs must be 80");
        }
        if (profile.threshold <= 0.0 || profile.threshold >= 1.0) {
            errors.put("threshold must be > 0 and < 1");
        } else if (profile.threshold < 0.995) {
            warnings.put("threshold below 0.995 may false-accept near phrases in current hey_jarvis host testing");
        }
        if (profile.cooldownMs < 0L || profile.cooldownMs > 60000L) {
            errors.put("cooldownMs must be between 0 and 60000");
        }
        if (!profile.licenseAcknowledged) {
            errors.put("licenseAcknowledged must be true for BYO model assets");
        }

        out.put("valid", errors.length() == 0);
        out.put("validationScope", WakeProfile.BYO_VALIDATION_SCOPE);
        out.put("message", "BYO wake profile path/config validation only; wake_start performs live ONNX runtime loading and inference.");
        out.put("errors", errors);
        out.put("warnings", warnings);
        out.put("profile", profile.json());
    }

    private void requireReadableFile(JSONArray errors, String field, String path) {
        if (!WakeProfile.readableFile(path)) {
            errors.put(field + " must point to a readable file");
        }
    }

    private static String sanitizePathPart(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data);
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                out.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private final class LiveWakeCallbacks implements WakeOnnxLiveEngine.Listener {
        private final long generation;

        LiveWakeCallbacks(long generation) {
            this.generation = generation;
        }

        @Override
        public void onScore(double score, int frames, long elapsedMs, long computeMs) {
            WebSocket conn;
            WakeProfile profile;
            synchronized (WakeWordController.this) {
                if (!isCurrentGenerationLocked(generation)) {
                    return;
                }
                WakeWordStatus.lastWakeScore = score;
                WakeWordStatus.lastWakeFrame = frames;
                WakeWordStatus.lastWakeLatencyMs = elapsedMs;
                WakeWordStatus.lastWakeComputeMs = computeMs;
                WakeWordStatus.lastWakeEvent = "wake_score";
                if (!liveDebugScores) {
                    return;
                }
                conn = sender.currentClient();
                profile = activeProfile;
            }
            JSONObject out = baseEvent(null, "wake_score");
            try {
                out.put("score", score);
                out.put("threshold", profile.threshold);
                out.put("frame", frames);
                out.put("elapsedMs", elapsedMs);
                out.put("computeMs", computeMs);
            } catch (JSONException ignored) {
            }
            sender.send(conn, out);
        }

        @Override
        public void onDetected(double score, int frames, long elapsedMs, long computeMs) {
            WebSocket conn;
            WakeProfile profile;
            WakeOnnxLiveEngine engineToStop;
            synchronized (WakeWordController.this) {
                if (!isCurrentGenerationLocked(generation)) {
                    return;
                }
                WakeWordStatus.lastWakeScore = score;
                WakeWordStatus.lastWakeFrame = frames;
                WakeWordStatus.lastWakeLatencyMs = elapsedMs;
                WakeWordStatus.lastWakeComputeMs = computeMs;
                engineToStop = beginStopLocked("wake_detected");
                conn = sender.currentClient();
                profile = activeProfile;
            }
            stopEngineAndFinalize(engineToStop, "wake_detected");
            JSONObject out = baseEvent(null, "wake_detected");
            try {
                out.put("score", score);
                out.put("threshold", profile.threshold);
                out.put("frame", frames);
                out.put("elapsedMs", elapsedMs);
                out.put("computeMs", computeMs);
            } catch (JSONException ignored) {
            }
            sender.send(conn, out);
        }

        @Override
        public void onError(String code, String message) {
            WebSocket conn;
            WakeOnnxLiveEngine engineToStop;
            synchronized (WakeWordController.this) {
                if (!isCurrentGenerationLocked(generation)) {
                    return;
                }
                WakeWordStatus.lastWakeError = code + ": " + message;
                engineToStop = beginStopLocked("wake_error");
                conn = sender.currentClient();
            }
            stopEngineAndFinalize(engineToStop, "wake_error");
            sender.sendError(conn, null, code, message);
        }
    }

    private boolean isCurrentGenerationLocked(long generation) {
        return listening && liveMode && generation == activeWakeGeneration;
    }
}
