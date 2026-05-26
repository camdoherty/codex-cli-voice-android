package io.github.codex_cli_voice_android.aecshim;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import org.java_websocket.WebSocket;
import org.json.JSONException;
import org.json.JSONObject;

final class TextVoiceController {
    private static final long DEFAULT_STT_TIMEOUT_MS = 8000L;

    private final Context context;
    private final AecShimService service;
    private final AudioModeCoordinator audioModeCoordinator;
    private final AudioFocusController sessionAudioFocus;
    private final SpeechRecognizerEngine stt;
    private final TextToSpeechEngine tts;
    private final WakeWordController wakeWord;
    private volatile WebSocket client;
    private State state = State.IDLE;

    private enum State {
        IDLE("idle"),
        STT_STARTING("stt_starting"),
        STT_LISTENING("stt_listening"),
        CLIENT_PROCESSING("client_processing"),
        TTS_STARTING("tts_starting"),
        TTS_SPEAKING("tts_speaking"),
        ERROR_RECOVERING("error_recovering");

        final String wireName;

        State(String wireName) {
            this.wireName = wireName;
        }
    }

    TextVoiceController(Context context, AudioModeCoordinator audioModeCoordinator) {
        this.service = context instanceof AecShimService ? (AecShimService) context : null;
        this.context = context.getApplicationContext();
        this.audioModeCoordinator = audioModeCoordinator;
        this.sessionAudioFocus = new AudioFocusController(context);
        this.stt = new SpeechRecognizerEngine(context, new AudioFocusController(context), new SttCallbacks());
        this.tts = new TextToSpeechEngine(context, new AudioFocusController(context), new TtsCallbacks());
        this.wakeWord = new WakeWordController(
                context,
                audioModeCoordinator,
                new FakeWakeWordEngine(),
                new WakeSender());
        setState(State.IDLE);
    }

    synchronized void onOpen(WebSocket conn) {
        WebSocket existing = client;
        if (existing != null && existing.isOpen()) {
            sendError(conn, null, "busy", "text voice client already connected");
            conn.close(1013, "text voice client already connected");
            return;
        }
        client = conn;
        TextVoiceStatus.textClientConnected = true;
        stt.refreshAvailability();
        send(conn, TextVoiceStatus.json(null));
    }

    synchronized boolean owns(WebSocket conn) {
        return conn != null && conn == client;
    }

    synchronized void onClose(WebSocket conn) {
        if (conn != client) {
            return;
        }
        wakeWord.stop(null, false);
        stopTextModes(null);
        client = null;
        TextVoiceStatus.textClientConnected = false;
        setState(State.IDLE);
    }

    void onMessage(String message) {
        JSONObject in;
        try {
            in = new JSONObject(message);
        } catch (JSONException e) {
            sendError(currentClient(), null, "invalid_action", "invalid JSON: " + e.getMessage());
            return;
        }
        String id = in.optString("id", "");
        String action = in.optString("action", "");
        if ("status".equals(action)) {
            stt.refreshAvailability();
            send(currentClient(), TextVoiceStatus.json(id));
        } else if ("wake_status".equals(action)) {
            wakeWord.status(id);
        } else if ("wake_start".equals(action)) {
            wakeWord.start(id, in);
        } else if ("wake_stop".equals(action)) {
            wakeWord.stop(id, true);
        } else if ("wake_fake_detect".equals(action)) {
            wakeWord.fakeDetect(id);
        } else if ("wake_model_validate".equals(action)) {
            wakeWord.validate(id, in);
        } else if ("wake_model_put".equals(action)) {
            wakeWord.putModelFile(id, in);
        } else if ("wake_onnx_probe".equals(action)) {
            wakeWord.onnxProbe(id, in);
        } else if ("cue_ready".equals(action)) {
            wakeWord.cueReady(id);
        } else if ("client_state".equals(action)) {
            setClientState(id, in);
        } else if ("start_stt".equals(action)) {
            startStt(id, in);
        } else if ("stop_stt".equals(action)) {
            stopStt(id, true);
        } else if ("tts_speak".equals(action)) {
            speak(id, in);
        } else if ("tts_stop".equals(action)) {
            stopTts(id, true);
        } else {
            sendError(currentClient(), id, "invalid_action", "unknown action: " + action);
        }
    }

    void onPttButtonPressed() {
        WebSocket conn = currentClient();
        if (conn == null || !conn.isOpen()) {
            TextVoiceStatus.lastError = "ptt_unavailable: no STTS companion connected";
            return;
        }
        sendEvent(null, "ptt_button_pressed");
    }

    void onDoneButtonPressed() {
        stopStt(null, true);
    }

    void onCancelButtonPressed() {
        sendEvent(null, "cancel_processing");
        stopTextModes(() -> setState(State.IDLE));
    }

    void onStopButtonPressed() {
        sendEvent(null, "cancel_processing");
        wakeWord.stop(null, false);
        stopTextModes(() -> setState(State.IDLE));
    }

    synchronized void shutdown() {
        wakeWord.shutdown();
        stt.shutdown(this::releaseSttMode);
        tts.shutdown(this::releaseTtsMode);
        client = null;
        TextVoiceStatus.textClientConnected = false;
        setState(State.IDLE);
    }

    private synchronized void startStt(String id, JSONObject in) {
        boolean force = in.optBoolean("force", false);
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendError(currentClient(), id, "permission_denied", "RECORD_AUDIO permission missing");
            return;
        }
        if (state != State.IDLE && !force) {
            sendError(currentClient(), id, "busy", "text voice state is " + state.wireName);
            return;
        }
        if (force) {
            stopTextModes(() -> acquireAndStartStt(id, in));
            return;
        }
        acquireAndStartStt(id, in);
    }

    private synchronized void acquireAndStartStt(String id, JSONObject in) {
        if (client == null) {
            return;
        }
        if (!audioModeCoordinator.tryAcquire(AudioModeCoordinator.Mode.TEXT_STT, "text_stt")) {
            sendError(currentClient(), id, "audio_busy", "audio mode is " + audioModeCoordinator.modeName());
            return;
        }
        sessionAudioFocus.requestExclusiveSpeechFocus();
        setState(State.STT_STARTING);
        stt.start(
                id,
                in.optBoolean("offlineOnly", false),
                Math.max(1000L, in.optLong("timeoutMs", DEFAULT_STT_TIMEOUT_MS)),
                Math.max(0L, in.optLong("completeSilenceMs", 0L)),
                Math.max(0L, in.optLong("possiblyCompleteSilenceMs", 0L)),
                Math.max(0L, in.optLong("minimumLengthMs", 0L)));
    }

    private synchronized void speak(String id, JSONObject in) {
        boolean interrupt = in.optBoolean("interrupt", false);
        if (state != State.IDLE && !interrupt) {
            sendError(currentClient(), id, "busy", "text voice state is " + state.wireName);
            return;
        }
        if (interrupt) {
            stopTextModes(() -> acquireAndSpeak(id, in));
            return;
        }
        acquireAndSpeak(id, in);
    }

    private synchronized void acquireAndSpeak(String id, JSONObject in) {
        if (client == null) {
            return;
        }
        if (!audioModeCoordinator.tryAcquire(AudioModeCoordinator.Mode.TEXT_TTS, "text_tts")) {
            sendError(currentClient(), id, "audio_busy", "audio mode is " + audioModeCoordinator.modeName());
            return;
        }
        setState(State.TTS_STARTING);
        tts.speak(id, in.optString("text", ""));
    }

    private synchronized void setClientState(String id, JSONObject in) {
        String requested = in.optString("state", "");
        if ("processing".equals(requested) || "thinking".equals(requested)) {
            sessionAudioFocus.requestExclusiveSpeechFocus();
            setState(State.CLIENT_PROCESSING);
            sendEvent(id, "client_state");
        } else if ("ready".equals(requested) || "idle".equals(requested)) {
            sessionAudioFocus.abandon();
            setState(State.IDLE);
            sendEvent(id, "client_state");
        } else {
            sendError(currentClient(), id, "invalid_state", "unsupported client state: " + requested);
        }
    }

    private void stopTextModes(Runnable onStopped) {
        stt.stop(() -> {
            releaseSttMode();
            tts.stop(() -> {
                releaseTtsMode();
                if (onStopped != null) {
                    onStopped.run();
                }
            });
        });
    }

    private void stopStt(String id, boolean sendAck) {
        boolean stateWasStt = isSttState();
        stt.stop(() -> {
            releaseSttMode();
            if (stateWasStt) {
                setState(State.IDLE);
            }
            if (sendAck) {
                sendEvent(id, "stt_stopped");
            }
        });
    }

    private void stopTts(String id, boolean sendAck) {
        boolean stateWasTts = isTtsState();
        tts.stop(() -> {
            releaseTtsMode();
            sessionAudioFocus.abandon();
            if (stateWasTts) {
                setState(State.IDLE);
            }
            if (sendAck) {
                sendEvent(id, "tts_stopped");
            }
        });
    }

    private synchronized boolean isSttState() {
        return state == State.STT_STARTING || state == State.STT_LISTENING;
    }

    private synchronized boolean isTtsState() {
        return state == State.TTS_STARTING || state == State.TTS_SPEAKING;
    }

    private void releaseSttMode() {
        audioModeCoordinator.release(AudioModeCoordinator.Mode.TEXT_STT, "text_stt");
    }

    private void releaseTtsMode() {
        audioModeCoordinator.release(AudioModeCoordinator.Mode.TEXT_TTS, "text_tts");
        sessionAudioFocus.abandon();
    }

    private synchronized WebSocket currentClient() {
        return client;
    }

    private synchronized void setState(State next) {
        state = next;
        TextVoiceStatus.state = next.wireName;
        if (service != null) {
            service.updateNotification();
        }
    }

    private void sendEvent(String id, String event) {
        JSONObject out = new JSONObject();
        try {
            TextVoiceStatus.putId(out, id);
            out.put("event", event);
            out.put("state", TextVoiceStatus.state);
        } catch (JSONException ignored) {
        }
        send(currentClient(), out);
    }

    private void sendError(WebSocket conn, String id, String code, String message) {
        TextVoiceStatus.lastError = code + ": " + message;
        JSONObject out = new JSONObject();
        try {
            TextVoiceStatus.putId(out, id);
            out.put("event", "error");
            out.put("code", code);
            out.put("message", message);
            out.put("state", TextVoiceStatus.state);
        } catch (JSONException ignored) {
        }
        send(conn, out);
    }

    private static void send(WebSocket conn, JSONObject out) {
        if (conn != null && conn.isOpen()) {
            conn.send(out.toString());
        }
    }

    private final class WakeSender implements WakeWordController.Sender {
        @Override
        public WebSocket currentClient() {
            return TextVoiceController.this.currentClient();
        }

        @Override
        public void send(WebSocket conn, JSONObject out) {
            TextVoiceController.send(conn, out);
        }

        @Override
        public void sendError(WebSocket conn, String id, String code, String message) {
            TextVoiceController.this.sendError(conn, id, code, message);
        }
    }

    private final class SttCallbacks implements SpeechRecognizerEngine.Listener {
        @Override
        public void onListening(String id) {
            setState(State.STT_LISTENING);
            sendEvent(id, "stt_listening");
        }

        @Override
        public void onPartial(String id, String text) {
            JSONObject out = new JSONObject();
            try {
                TextVoiceStatus.putId(out, id);
                out.put("event", "stt_partial");
                out.put("text", text);
            } catch (JSONException ignored) {
            }
            send(currentClient(), out);
        }

        @Override
        public void onFinal(String id, String text, long latencyMs) {
            TextVoiceStatus.lastSttLatencyMs = latencyMs;
            audioModeCoordinator.release(AudioModeCoordinator.Mode.TEXT_STT, "text_stt");
            setState(State.IDLE);
            JSONObject out = new JSONObject();
            try {
                TextVoiceStatus.putId(out, id);
                out.put("event", "stt_final");
                out.put("text", text);
                out.put("latencyMs", latencyMs);
            } catch (JSONException ignored) {
            }
            send(currentClient(), out);
        }

        @Override
        public void onError(String id, String code, String message, long latencyMs) {
            TextVoiceStatus.lastSttLatencyMs = latencyMs;
            audioModeCoordinator.release(AudioModeCoordinator.Mode.TEXT_STT, "text_stt");
            setState(State.IDLE);
            sendError(currentClient(), id, code, message);
        }
    }

    private final class TtsCallbacks implements TextToSpeechEngine.Listener {
        @Override
        public void onStarted(String id) {
            setState(State.TTS_SPEAKING);
            sendEvent(id, "tts_started");
        }

        @Override
        public void onComplete(String id, long latencyMs) {
            TextVoiceStatus.lastTtsLatencyMs = latencyMs;
            audioModeCoordinator.release(AudioModeCoordinator.Mode.TEXT_TTS, "text_tts");
            setState(State.IDLE);
            JSONObject out = new JSONObject();
            try {
                TextVoiceStatus.putId(out, id);
                out.put("event", "tts_complete");
                out.put("latencyMs", latencyMs);
            } catch (JSONException ignored) {
            }
            send(currentClient(), out);
        }

        @Override
        public void onError(String id, String code, String message, long latencyMs) {
            TextVoiceStatus.lastTtsLatencyMs = latencyMs;
            audioModeCoordinator.release(AudioModeCoordinator.Mode.TEXT_TTS, "text_tts");
            setState(State.IDLE);
            sendError(currentClient(), id, code, message);
        }
    }
}
