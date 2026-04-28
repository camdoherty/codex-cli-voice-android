package io.github.codex_cli_voice_android.aecshim;

import android.content.Intent;

final class DefaultSpeechAudioSourceStrategy implements SpeechAudioSourceStrategy {
    @Override
    public void applyTo(Intent intent) {
        // Baseline mode lets the platform recognizer own microphone capture.
    }

    @Override
    public void close() {
        // No resources in baseline mode.
    }
}
