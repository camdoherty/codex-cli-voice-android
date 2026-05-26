package io.github.codex_cli_voice_android.aecshim;

import java.util.Locale;

final class WakeWordTestStatus {
    static volatile boolean running;
    static volatile long startedAtMs;
    static volatile long lastHitAtMs;
    static volatile double threshold = 0.997;
    static volatile double lastScore;
    static volatile double maxScore;
    static volatile int lastFrame;
    static volatile int maxFrame;
    static volatile long lastElapsedMs;
    static volatile long lastComputeMs;
    static volatile double inputRmsDbfs = -120.0;
    static volatile double inputPeakDbfs = -120.0;
    static volatile String routedInput = "unknown";
    static volatile long passCount;
    static volatile long failCount;
    static volatile String lastVerdict = "";
    static volatile String lastSavedFile = "";
    static volatile String lastError = "";

    private WakeWordTestStatus() {}

    static void resetRun(double newThreshold) {
        running = true;
        startedAtMs = System.currentTimeMillis();
        lastHitAtMs = 0L;
        threshold = newThreshold;
        lastScore = 0.0;
        maxScore = 0.0;
        lastFrame = 0;
        maxFrame = 0;
        lastElapsedMs = 0L;
        lastComputeMs = 0L;
        inputRmsDbfs = -120.0;
        inputPeakDbfs = -120.0;
        routedInput = "unknown";
        lastVerdict = "";
        lastError = "";
    }

    static void stopRun() {
        running = false;
        startedAtMs = 0L;
    }

    static void resetWindowMetrics() {
        lastHitAtMs = 0L;
        lastScore = 0.0;
        maxScore = 0.0;
        lastFrame = 0;
        maxFrame = 0;
        lastElapsedMs = 0L;
        lastComputeMs = 0L;
    }

    static boolean recentlyHit(long nowMs) {
        return lastHitAtMs > 0L && nowMs - lastHitAtMs <= 1000L;
    }

    static String summary() {
        return "Wake test: " + (running ? "running" : "stopped") + "\n"
                + "Threshold: " + threshold + "\n"
                + "Last score/frame/compute ms: " + format(lastScore) + "/" + lastFrame + "/" + lastComputeMs + "\n"
                + "Max score/frame: " + format(maxScore) + "/" + maxFrame + "\n"
                + "Input RMS/peak dBFS: " + format(inputRmsDbfs) + "/" + format(inputPeakDbfs) + "\n"
                + "Routed input: " + routedInput + "\n"
                + "Last elapsed ms: " + lastElapsedMs + "\n"
                + "Pass/fail count: " + passCount + "/" + failCount + "\n"
                + "Last verdict: " + (lastVerdict.isEmpty() ? "none" : lastVerdict) + "\n"
                + "Last saved fail WAV: " + (lastSavedFile.isEmpty() ? "none" : lastSavedFile) + "\n"
                + "Wake test error: " + (lastError.isEmpty() ? "none" : lastError);
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
