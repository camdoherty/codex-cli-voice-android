package io.github.codex_cli_voice_android.aecshim;

final class AudioModeCoordinator {
    enum Mode {
        IDLE("idle"),
        REALTIME_PCM("realtime_pcm"),
        TEXT_STT("text_stt"),
        TEXT_TTS("text_tts");

        final String wireName;

        Mode(String wireName) {
            this.wireName = wireName;
        }
    }

    private Mode mode = Mode.IDLE;
    private String owner = "";

    synchronized boolean tryAcquire(Mode requested, String requestedOwner) {
        if (mode != Mode.IDLE) {
            return false;
        }
        mode = requested;
        owner = requestedOwner == null ? "" : requestedOwner;
        TextVoiceStatus.audioMode = mode.wireName;
        return true;
    }

    synchronized void release(Mode releasing, String releasingOwner) {
        if (mode == releasing && owner.equals(releasingOwner == null ? "" : releasingOwner)) {
            mode = Mode.IDLE;
            owner = "";
            TextVoiceStatus.audioMode = mode.wireName;
        }
    }

    synchronized boolean isBusy() {
        return mode != Mode.IDLE;
    }

    synchronized String modeName() {
        return mode.wireName;
    }

    synchronized String ownerName() {
        return owner;
    }
}
