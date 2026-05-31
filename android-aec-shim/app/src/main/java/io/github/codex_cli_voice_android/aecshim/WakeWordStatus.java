package io.github.codex_cli_voice_android.aecshim;

import org.json.JSONException;
import org.json.JSONObject;

final class WakeWordStatus {
    static volatile String wakeState = "idle";
    static volatile String wakeProfileId = WakeProfile.fakeManual().id;
    static volatile long wakeStartedAtMs;
    static volatile long wakeDeadlineAtMs;
    static volatile long wakeMaxListenMs;
    static volatile double wakeInputGainDb;
    static volatile long lastWakeLatencyMs;
    static volatile double lastWakeScore;
    static volatile int lastWakeFrame;
    static volatile long lastWakeComputeMs;
    static volatile double lastWakeInputRmsDbfs = -120.0;
    static volatile double lastWakeInputPeakDbfs = -120.0;
    static volatile double lastWakeGainedInputRmsDbfs = -120.0;
    static volatile double lastWakeGainedInputPeakDbfs = -120.0;
    static volatile int lastWakeClippedSamples;
    static volatile double maxWakeScore;
    static volatile int maxWakeFrame;
    static volatile String lastWakeEvent = "";
    static volatile String lastWakeError = "";
    static volatile String lastWakeStopReason = "";
    static volatile long lastWakeStopStartedAtMs;
    static volatile long lastWakeStopCompletedAtMs;
    static volatile String lastWakeEngineThreadState = "";

    private WakeWordStatus() {}

    static void resetListeningFields() {
        wakeStartedAtMs = 0L;
        wakeDeadlineAtMs = 0L;
        wakeMaxListenMs = 0L;
    }

    static void put(JSONObject out) throws JSONException {
        out.put("wakeState", wakeState);
        out.put("wakeProfileId", wakeProfileId);
        out.put("wakeStartedAtMs", wakeStartedAtMs);
        out.put("wakeDeadlineAtMs", wakeDeadlineAtMs);
        out.put("wakeMaxListenMs", wakeMaxListenMs);
        out.put("wakeInputGainDb", wakeInputGainDb);
        out.put("lastWakeLatencyMs", lastWakeLatencyMs);
        out.put("lastWakeScore", lastWakeScore);
        out.put("lastWakeFrame", lastWakeFrame);
        out.put("lastWakeComputeMs", lastWakeComputeMs);
        out.put("lastWakeInputRmsDbfs", lastWakeInputRmsDbfs);
        out.put("lastWakeInputPeakDbfs", lastWakeInputPeakDbfs);
        out.put("lastWakeGainedInputRmsDbfs", lastWakeGainedInputRmsDbfs);
        out.put("lastWakeGainedInputPeakDbfs", lastWakeGainedInputPeakDbfs);
        out.put("lastWakeClippedSamples", lastWakeClippedSamples);
        out.put("maxWakeScore", maxWakeScore);
        out.put("maxWakeFrame", maxWakeFrame);
        out.put("lastWakeEvent", lastWakeEvent);
        out.put("lastWakeError", lastWakeError);
        out.put("lastWakeStopReason", lastWakeStopReason);
        out.put("lastWakeStopStartedAtMs", lastWakeStopStartedAtMs);
        out.put("lastWakeStopCompletedAtMs", lastWakeStopCompletedAtMs);
        out.put("lastWakeEngineThreadState", lastWakeEngineThreadState);
        out.put("wakeValidationScope", WakeProfile.VALIDATION_SCOPE);
    }

    static String summary() {
        return "Wake state/profile: " + wakeState + "/" + wakeProfileId + "\n"
                + "Wake max listen/deadline ms: " + wakeMaxListenMs + "/" + wakeDeadlineAtMs + "\n"
                + "Wake input gain dB: " + wakeInputGainDb + "\n"
                + "Last wake score/frame/compute ms: " + lastWakeScore + "/" + lastWakeFrame + "/" + lastWakeComputeMs + "\n"
                + "Last wake input RMS/peak dBFS: " + lastWakeInputRmsDbfs + "/" + lastWakeInputPeakDbfs + "\n"
                + "Last wake gained input RMS/peak dBFS/clipped: " + lastWakeGainedInputRmsDbfs + "/" + lastWakeGainedInputPeakDbfs + "/" + lastWakeClippedSamples + "\n"
                + "Max wake score/frame: " + maxWakeScore + "/" + maxWakeFrame + "\n"
                + "Last wake latency/event: " + lastWakeLatencyMs + "/" + (lastWakeEvent.isEmpty() ? "none" : lastWakeEvent) + "\n"
                + "Last wake stop reason/start/done: " + lastWakeStopReason + "/" + lastWakeStopStartedAtMs + "/" + lastWakeStopCompletedAtMs + "\n"
                + "Last wake error/thread: " + (lastWakeError.isEmpty() ? "none" : lastWakeError) + "/" + lastWakeEngineThreadState;
    }
}
