package io.github.codex_cli_voice_android.aecshim;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

final class TextToSpeechEngine {
    interface Listener {
        void onStarted(String id);

        void onComplete(String id, long latencyMs);

        void onError(String id, String code, String message, long latencyMs);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AudioFocusController audioFocus;
    private final Listener listener;
    private TextToSpeech tts;
    private String activeId;
    private long startedAtMs;

    TextToSpeechEngine(Context context, AudioFocusController audioFocus, Listener listener) {
        this.context = context.getApplicationContext();
        this.audioFocus = audioFocus;
        this.listener = listener;
        mainHandler.post(this::initOnMain);
    }

    void speak(String id, String text) {
        mainHandler.post(() -> speakOnMain(id, text));
    }

    void stop() {
        stop(null);
    }

    void stop(Runnable onStopped) {
        mainHandler.post(() -> {
            stopOnMain();
            if (onStopped != null) {
                onStopped.run();
            }
        });
    }

    void shutdown() {
        shutdown(null);
    }

    void shutdown(Runnable onStopped) {
        mainHandler.post(() -> {
            stopOnMain();
            if (tts != null) {
                tts.shutdown();
                tts = null;
            }
            TextVoiceStatus.ttsReady = false;
            if (onStopped != null) {
                onStopped.run();
            }
        });
    }

    private void initOnMain() {
        tts = new TextToSpeech(context, status -> {
            TextVoiceStatus.ttsReady = status == TextToSpeech.SUCCESS;
            if (TextVoiceStatus.ttsReady && tts != null) {
                tts.setLanguage(Locale.getDefault());
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                tts.setOnUtteranceProgressListener(new ProgressCallbacks());
            } else {
                TextVoiceStatus.lastError = "TextToSpeech init failed: " + status;
            }
        });
    }

    private void speakOnMain(String id, String text) {
        if (tts == null || !TextVoiceStatus.ttsReady) {
            listener.onError(id, "tts_not_ready", "TextToSpeech is not ready", 0);
            return;
        }
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty()) {
            listener.onError(id, "invalid_action", "tts_speak text is empty", 0);
            return;
        }
        activeId = id;
        startedAtMs = System.currentTimeMillis();
        audioFocus.requestTransientSpeechFocus();
        Bundle params = new Bundle();
        int result = tts.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, id);
        if (result != TextToSpeech.SUCCESS) {
            listener.onError(id, "tts_error", "TextToSpeech speak failed: " + result, 0);
            audioFocus.abandon();
            activeId = null;
        }
    }

    private void stopOnMain() {
        if (tts != null) {
            tts.stop();
        }
        audioFocus.abandon();
        activeId = null;
    }

    private final class ProgressCallbacks extends UtteranceProgressListener {
        @Override
        public void onStart(String utteranceId) {
            mainHandler.post(() -> onStartOnMain(utteranceId));
        }

        @Override
        public void onDone(String utteranceId) {
            mainHandler.post(() -> onDoneOnMain(utteranceId));
        }

        @Override
        public void onError(String utteranceId) {
            mainHandler.post(() -> onErrorOnMain(utteranceId));
        }
    }

    private void onStartOnMain(String utteranceId) {
        if (utteranceId != null && utteranceId.equals(activeId)) {
            listener.onStarted(utteranceId);
        }
    }

    private void onDoneOnMain(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(activeId)) {
            return;
        }
        long latency = System.currentTimeMillis() - startedAtMs;
        TextVoiceStatus.lastTtsLatencyMs = latency;
        audioFocus.abandon();
        activeId = null;
        listener.onComplete(utteranceId, latency);
    }

    private void onErrorOnMain(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(activeId)) {
            return;
        }
        long latency = System.currentTimeMillis() - startedAtMs;
        audioFocus.abandon();
        activeId = null;
        listener.onError(utteranceId, "tts_error", "TextToSpeech utterance failed", latency);
    }
}
