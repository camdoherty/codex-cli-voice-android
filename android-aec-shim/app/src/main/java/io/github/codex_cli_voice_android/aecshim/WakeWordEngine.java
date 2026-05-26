package io.github.codex_cli_voice_android.aecshim;

interface WakeWordEngine {
    WakeProfile profile();

    boolean validateProfile();

    void start();

    void stop();

    boolean fakeDetect();
}
