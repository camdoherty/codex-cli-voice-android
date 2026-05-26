package io.github.codex_cli_voice_android.aecshim;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

final class SpeechRecognizerEngine {
    interface Listener {
        void onListening(String id);

        void onPartial(String id, String text);

        void onFinal(String id, String text, long latencyMs);

        void onError(String id, String code, String message, long latencyMs);
    }

    private static final long PARTIAL_DEBOUNCE_MS = 250;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AudioFocusController audioFocus;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private SpeechAudioSourceStrategy audioSourceStrategy = new DefaultSpeechAudioSourceStrategy();
    private String activeId;
    private long activeGeneration;
    private long startedAtMs;
    private long lastPartialAtMs;
    private boolean active;

    SpeechRecognizerEngine(Context context, AudioFocusController audioFocus, Listener listener) {
        this.context = context.getApplicationContext();
        this.audioFocus = audioFocus;
        this.listener = listener;
        refreshAvailability();
    }

    void refreshAvailability() {
        TextVoiceStatus.sttAvailable = SpeechRecognizer.isRecognitionAvailable(context);
        TextVoiceStatus.onDeviceSttAvailable =
                Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
    }

    void start(
            String id,
            boolean offlineOnly,
            long timeoutMs,
            long completeSilenceMs,
            long possiblyCompleteSilenceMs,
            long minimumLengthMs
    ) {
        mainHandler.post(() -> startOnMain(
                id,
                offlineOnly,
                timeoutMs,
                completeSilenceMs,
                possiblyCompleteSilenceMs,
                minimumLengthMs));
    }

    void stop() {
        stop(null);
    }

    void stop(Runnable onStopped) {
        mainHandler.post(() -> {
            stopOnMain("stopped");
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
            stopOnMain("shutdown");
            if (onStopped != null) {
                onStopped.run();
            }
        });
    }

    private void startOnMain(
            String id,
            boolean offlineOnly,
            long timeoutMs,
            long completeSilenceMs,
            long possiblyCompleteSilenceMs,
            long minimumLengthMs
    ) {
        refreshAvailability();
        if (!TextVoiceStatus.sttAvailable) {
            listener.onError(id, "recognizer_unavailable", "No SpeechRecognizer service available", 0);
            return;
        }
        if (offlineOnly && !TextVoiceStatus.onDeviceSttAvailable) {
            listener.onError(id, "on_device_unavailable", "On-device SpeechRecognizer is unavailable", 0);
            return;
        }

        stopOnMain("replace");
        active = true;
        activeId = id;
        activeGeneration++;
        long generation = activeGeneration;
        startedAtMs = System.currentTimeMillis();
        lastPartialAtMs = 0;
        audioFocus.requestTransientSpeechFocus();

        try {
            recognizer = offlineOnly && Build.VERSION.SDK_INT >= 31
                    ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    : SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(new RecognitionCallbacks(generation));

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineOnly);
            if (completeSilenceMs > 0) {
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs);
            }
            if (possiblyCompleteSilenceMs > 0) {
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possiblyCompleteSilenceMs);
            }
            if (minimumLengthMs > 0) {
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minimumLengthMs);
            }
            audioSourceStrategy.applyTo(intent);

            recognizer.startListening(intent);
            listener.onListening(id);
            mainHandler.postDelayed(() -> {
                if (isCurrent(generation) && id.equals(activeId)) {
                    long latency = System.currentTimeMillis() - startedAtMs;
                    listener.onError(id, "stt_timeout", "STT hard timeout", latency);
                    stopOnMain("timeout");
                }
            }, Math.max(1000L, timeoutMs));
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startedAtMs;
            listener.onError(id, "stt_error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), latency);
            stopOnMain("start_error");
        }
    }

    private void stopOnMain(String reason) {
        active = false;
        activeGeneration++;
        audioSourceStrategy.close();
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        audioFocus.abandon();
        activeId = null;
    }

    private boolean isCurrent(long generation) {
        return active && generation == activeGeneration && activeId != null;
    }

    private final class RecognitionCallbacks implements RecognitionListener {
        private final long generation;

        RecognitionCallbacks(long generation) {
            this.generation = generation;
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onError(int error) {
            if (!isCurrent(generation)) {
                return;
            }
            String id = activeId;
            long latency = System.currentTimeMillis() - startedAtMs;
            String code = recognizerErrorCode(error);
            listener.onError(id, code, recognizerErrorMessage(error), latency);
            stopOnMain("recognizer_error");
        }

        @Override
        public void onResults(Bundle results) {
            if (!isCurrent(generation)) {
                return;
            }
            String id = activeId;
            long latency = System.currentTimeMillis() - startedAtMs;
            String text = bestText(results);
            listener.onFinal(id, text, latency);
            stopOnMain("final");
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (!isCurrent(generation)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastPartialAtMs < PARTIAL_DEBOUNCE_MS) {
                return;
            }
            lastPartialAtMs = now;
            listener.onPartial(activeId, bestText(partialResults));
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    }

    private static String bestText(Bundle bundle) {
        if (bundle == null) {
            return "";
        }
        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        return matches.get(0) == null ? "" : matches.get(0).trim();
    }

    private static String recognizerErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK:
                return "network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "no recognition match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "recognizer busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "speech timeout";
            default:
                return "recognizer error " + error;
        }
    }

    private static String recognizerErrorCode(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "stt_audio";
            case SpeechRecognizer.ERROR_CLIENT:
                return "stt_client";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "permission_denied";
            case SpeechRecognizer.ERROR_NETWORK:
                return "stt_network";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "stt_network_timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "stt_no_match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "stt_busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "stt_server";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "stt_timeout";
            default:
                return "stt_error";
        }
    }
}
