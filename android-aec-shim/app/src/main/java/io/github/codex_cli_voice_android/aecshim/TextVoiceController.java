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
    private final AudioModeCoordinator audioModeCoordinator;
    private final SpeechRecognizerEngine stt;
    private final TextToSpeechEngine tts;
    private volatile WebSocket client;
    private State state = State.IDLE;

    private enum State {
        IDLE("idle"),
        STT_STARTING("stt_starting"),
        STT_LISTENING("stt_listening"),
        TTS_STARTING("tts_starting"),
        TTS_SPEAKING("tts_speaking"),
        ERROR_RECOVERING("error_recovering");

        final String wireName;

        State(String wireName) {
            this.wireName = wireName;
        }
    }

    TextVoiceController(Context context, AudioModeCoordinator audioModeCoordinator) {
        this.context = context.getApplicationContext();
        this.audioModeCoordinator = audioModeCoordinator;
        AudioFocusController audioFocus = new AudioFocusController(context);
        this.stt = new SpeechRecognizerEngine(context, audioFocus, new SttCallbacks());
        this.tts = new TextToSpeechEngine(context, audioFocus, new TtsCallbacks());
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

    synchronized void shutdown() {
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
        setState(State.STT_STARTING);
        stt.start(
                id,
                in.optBoolean("offlineOnly", false),
                Math.max(1000L, in.optLong("timeoutMs", DEFAULT_STT_TIMEOUT_MS)));
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
    }

    private synchronized WebSocket currentClient() {
        return client;
    }

    private synchronized void setState(State next) {
        state = next;
        TextVoiceStatus.state = next.wireName;
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
