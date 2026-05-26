package io.github.codex_cli_voice_android.aecshim;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

final class AecShimState {
    static volatile boolean serviceRunning;
    static volatile boolean serverListening;
    static volatile boolean clientConnected;
    static volatile boolean captureRunning;
    static volatile boolean aecAvailable;
    static volatile boolean aecEnabled;
    static volatile boolean nsAvailable;
    static volatile boolean nsEnabled;
    static volatile int captureRate;
    static volatile int playbackRate;
    static volatile String lastError = "";
    static final AtomicLong micFrames = new AtomicLong();
    static final AtomicLong playFrames = new AtomicLong();
    static final AtomicLong micDrops = new AtomicLong();
    static final AtomicLong playDrops = new AtomicLong();
    static final AtomicLong playBytesQueued = new AtomicLong();
    static final AtomicLong playBytesWritten = new AtomicLong();

    private AecShimState() {}

    static void resetCounters() {
        micFrames.set(0);
        playFrames.set(0);
        micDrops.set(0);
        playDrops.set(0);
        playBytesQueued.set(0);
        playBytesWritten.set(0);
    }

    static String statsJson() {
        return String.format(
                Locale.US,
                "{\"type\":\"stats\",\"micFrames\":%d,\"playFrames\":%d,\"micDrops\":%d,\"playDrops\":%d,\"playBytesQueued\":%d,\"playBytesWritten\":%d,\"aec\":%s,\"ns\":%s,\"captureRate\":%d,\"playbackRate\":%d}",
                micFrames.get(),
                playFrames.get(),
                micDrops.get(),
                playDrops.get(),
                playBytesQueued.get(),
                playBytesWritten.get(),
                aecEnabled,
                nsEnabled,
                captureRate,
                playbackRate);
    }

    static String summary() {
        return "Service: " + (serviceRunning ? "running" : "stopped") + "\n"
                + "Server: " + (serverListening ? "listening" : "stopped") + "\n"
                + "Client: " + (clientConnected ? "connected" : "none") + "\n"
                + "Capture: " + (captureRunning ? "running" : "stopped") + "\n"
                + "AEC available/enabled: " + aecAvailable + "/" + aecEnabled + "\n"
                + "NS available/enabled: " + nsAvailable + "/" + nsEnabled + "\n"
                + "Rates capture/playback: " + captureRate + "/" + playbackRate + "\n"
                + "Mic frames/drops: " + micFrames.get() + "/" + micDrops.get() + "\n"
                + "Play frames/drops: " + playFrames.get() + "/" + playDrops.get() + "\n"
                + "Play bytes queued/written: " + playBytesQueued.get() + "/" + playBytesWritten.get() + "\n"
                + "\n"
                + TextVoiceStatus.summary() + "\n"
                + WakeWordTestStatus.summary() + "\n"
                + "Last error: " + (lastError.isEmpty() ? "none" : lastError);
    }
}
