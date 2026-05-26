package io.github.codex_cli_voice_android.aecshim;

final class FakeWakeWordEngine implements WakeWordEngine {
    private final WakeProfile profile = WakeProfile.fakeManual();
    private boolean running;

    @Override
    public WakeProfile profile() {
        return profile;
    }

    @Override
    public boolean validateProfile() {
        return true;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean fakeDetect() {
        return running;
    }
}
