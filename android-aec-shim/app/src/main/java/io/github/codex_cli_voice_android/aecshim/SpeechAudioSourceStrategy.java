package io.github.codex_cli_voice_android.aecshim;

import android.content.Intent;

interface SpeechAudioSourceStrategy {
    void applyTo(Intent intent);

    void close();
}
