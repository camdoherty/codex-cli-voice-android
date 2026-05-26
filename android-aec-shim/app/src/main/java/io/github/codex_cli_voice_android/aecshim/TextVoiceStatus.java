package io.github.codex_cli_voice_android.aecshim;

import org.json.JSONException;
import org.json.JSONObject;

final class TextVoiceStatus {
    static volatile boolean textClientConnected;
    static volatile boolean sttAvailable;
    static volatile boolean onDeviceSttAvailable;
    static volatile boolean ttsReady;
    static volatile String state = "idle";
    static volatile String audioMode = "idle";
    static volatile String lastError = "";
    static volatile long lastSttLatencyMs;
    static volatile long lastTtsLatencyMs;

    private TextVoiceStatus() {}

    static JSONObject json(String id) {
        JSONObject out = new JSONObject();
        try {
            putId(out, id);
            out.put("event", "status");
            out.put("protocol", 1);
            out.put("mode", "text_voice");
            out.put("state", state);
            out.put("audioMode", audioMode);
            out.put("textClientConnected", textClientConnected);
            out.put("sttAvailable", sttAvailable);
            out.put("onDeviceAvailable", onDeviceSttAvailable);
            out.put("ttsReady", ttsReady);
            out.put("lastError", lastError);
            out.put("lastSttLatencyMs", lastSttLatencyMs);
            out.put("lastTtsLatencyMs", lastTtsLatencyMs);
            WakeWordStatus.put(out);
        } catch (JSONException ignored) {
        }
        return out;
    }

    static void putId(JSONObject out, String id) throws JSONException {
        if (id == null || id.isEmpty()) {
            out.put("id", JSONObject.NULL);
        } else {
            out.put("id", id);
        }
    }

    static String summary() {
        return "Text voice client: " + (textClientConnected ? "connected" : "none") + "\n"
                + "Text voice state: " + state + "\n"
                + "Audio mode: " + audioMode + "\n"
                + "STT available/on-device: " + sttAvailable + "/" + onDeviceSttAvailable + "\n"
                + "TTS ready: " + ttsReady + "\n"
                + "Last STT/TTS latency ms: " + lastSttLatencyMs + "/" + lastTtsLatencyMs + "\n"
                + WakeWordStatus.summary() + "\n"
                + "Text voice last error: " + (lastError.isEmpty() ? "none" : lastError);
    }
}
