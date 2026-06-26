package io.github.codex_cli_voice_android.aecshim;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.PowerManager;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioEngine {
    interface MicSink {
        void send(byte[] frame);
    }

    private static final int PROTOCOL_RATE = 24000;
    private static final int CHANNELS = 1;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int FRAME_MS = 20;
    private static final int PROTOCOL_FRAME_BYTES = PROTOCOL_RATE * FRAME_MS / 1000 * CHANNELS * BYTES_PER_SAMPLE;

    private final Context context;
    private final AudioModeCoordinator modeCoordinator;
    private final AudioManager audioManager;
    private final PowerManager powerManager;
    private final ArrayBlockingQueue<byte[]> playbackQueue = new ArrayBlockingQueue<>(250);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cleaningUp = new AtomicBoolean(false);

    private Thread captureThread;
    private Thread playbackThread;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private PowerManager.WakeLock wakeLock;
    private AudioFocusRequest focusRequest;
    private int previousMode = AudioManager.MODE_NORMAL;
    private int captureRate = PROTOCOL_RATE;
    private int playbackRate = PROTOCOL_RATE;

    AudioEngine(Context context, AudioModeCoordinator modeCoordinator) {
        this.context = context.getApplicationContext();
        this.modeCoordinator = modeCoordinator;
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
    }

    synchronized boolean start(MicSink sink) {
        if (running.get()) {
            return true;
        }
        if (cleaningUp.get()) {
            AecShimState.lastError = "audio stopping";
            return false;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AecShimState.lastError = "RECORD_AUDIO permission missing";
            return false;
        }
        if (!modeCoordinator.tryAcquire(AudioModeCoordinator.Mode.REALTIME_PCM, "audio")) {
            AecShimState.lastError = "audio busy: " + modeCoordinator.modeName();
            return false;
        }
        running.set(true);
        AecShimState.captureRunning = true;
        playbackQueue.clear();
        try {
            configureAudioSession();
            captureThread = new Thread(() -> captureLoop(sink), "codex-aec-capture");
            playbackThread = new Thread(this::playbackLoop, "codex-aec-playback");
            captureThread.start();
            playbackThread.start();
            return true;
        } catch (Exception e) {
            AecShimState.lastError = "audio start failed: " + e.getMessage();
            stopInternal();
            return false;
        }
    }

    void stop() {
        stopInternal();
    }

    private void stopInternal() {
        if (!cleaningUp.compareAndSet(false, true)) {
            return;
        }
        if (!running.getAndSet(false)) {
            cleaningUp.set(false);
            return;
        }
        AecShimState.captureRunning = false;
        playbackQueue.clear();
        try {
            stopRecorder();
            stopPlayer();
            releaseEffects();
            restoreAudioSession();
            join(captureThread);
            join(playbackThread);
        } finally {
            captureThread = null;
            playbackThread = null;
            modeCoordinator.release(AudioModeCoordinator.Mode.REALTIME_PCM, "audio");
            cleaningUp.set(false);
        }
    }

    void enqueuePlayback(byte[] frame) {
        byte[] normalized = normalizePlaybackFrame(frame);
        if (normalized.length == 0) {
            return;
        }
        AecShimState.playBytesQueued.addAndGet(normalized.length);
        if (!playbackQueue.offer(normalized)) {
            if (playbackQueue.poll() != null) {
                AecShimState.playDrops.incrementAndGet();
            }
            if (!playbackQueue.offer(normalized)) {
                AecShimState.playDrops.incrementAndGet();
            }
        }
    }

    void clearPlayback() {
        playbackQueue.clear();
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.play();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void captureLoop(MicSink sink) {
        boolean shouldStop = false;
        try {
            audioRecord = createRecorder(PROTOCOL_RATE);
            if (audioRecord == null) {
                audioRecord = createRecorder(48000);
            }
            if (audioRecord == null) {
                AecShimState.lastError = "AudioRecord init failed for 24 kHz and 48 kHz";
                shouldStop = true;
                return;
            }
            if (!running.get()) {
                return;
            }
            captureRate = audioRecord.getSampleRate();
            AecShimState.captureRate = captureRate;
            attachEffects(audioRecord.getAudioSessionId());
            if (!running.get()) {
                return;
            }
            int frameBytes = captureRate * FRAME_MS / 1000 * BYTES_PER_SAMPLE;
            byte[] readBuffer = new byte[frameBytes];
            audioRecord.startRecording();
            while (running.get()) {
                int read = audioRecord.read(readBuffer, 0, readBuffer.length);
                if (read <= 0) {
                    AecShimState.micDrops.incrementAndGet();
                    continue;
                }
                byte[] frame = Arrays.copyOf(readBuffer, read);
                sink.send(normalizeCaptureFrame(frame, captureRate));
            }
        } catch (Exception e) {
            if (running.get()) {
                AecShimState.lastError = "capture failed: " + e.getMessage();
                shouldStop = true;
            }
        } finally {
            stopRecorder();
            if (shouldStop) {
                stopInternal();
            }
        }
    }

    private void playbackLoop() {
        boolean shouldStop = false;
        try {
            audioTrack = createPlayer(PROTOCOL_RATE);
            if (audioTrack == null) {
                audioTrack = createPlayer(48000);
            }
            if (audioTrack == null) {
                AecShimState.lastError = "AudioTrack init failed for 24 kHz and 48 kHz";
                shouldStop = true;
                return;
            }
            if (!running.get()) {
                return;
            }
            playbackRate = audioTrack.getSampleRate();
            AecShimState.playbackRate = playbackRate;
            audioTrack.play();
            while (running.get()) {
                byte[] frame = playbackQueue.poll(100, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                byte[] output = playbackRate == PROTOCOL_RATE ? frame : upsample24To48(frame);
                int written = audioTrack.write(output, 0, output.length);
                if (written > 0) {
                    AecShimState.playFrames.incrementAndGet();
                    AecShimState.playBytesWritten.addAndGet(written);
                } else {
                    AecShimState.playDrops.incrementAndGet();
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                AecShimState.lastError = "playback failed: " + e.getMessage();
                shouldStop = true;
            }
        } finally {
            stopPlayer();
            if (shouldStop) {
                stopInternal();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createRecorder(int sampleRate) {
        int min = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            return null;
        }
        int frameBytes = sampleRate * FRAME_MS / 1000 * BYTES_PER_SAMPLE;
        int bufferSize = Math.max(min, frameBytes * 4);
        AudioRecord record = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            return null;
        }
        return record;
    }

    private AudioTrack createPlayer(int sampleRate) {
        int min = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            return null;
        }
        int frameBytes = sampleRate * FRAME_MS / 1000 * BYTES_PER_SAMPLE;
        int bufferSize = Math.max(min, frameBytes * 8);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            return null;
        }
        return track;
    }

    private void attachEffects(int audioSessionId) {
        AecShimState.aecAvailable = AcousticEchoCanceler.isAvailable();
        AecShimState.nsAvailable = NoiseSuppressor.isAvailable();
        if (AecShimState.aecAvailable) {
            echoCanceler = AcousticEchoCanceler.create(audioSessionId);
            if (echoCanceler != null) {
                echoCanceler.setEnabled(true);
                AecShimState.aecEnabled = echoCanceler.getEnabled();
            }
        }
        if (AecShimState.nsAvailable) {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId);
            if (noiseSuppressor != null) {
                noiseSuppressor.setEnabled(true);
                AecShimState.nsEnabled = noiseSuppressor.getEnabled();
            }
        }
    }

    private void releaseEffects() {
        if (echoCanceler != null) {
            echoCanceler.release();
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            noiseSuppressor.release();
            noiseSuppressor = null;
        }
        AecShimState.aecEnabled = false;
        AecShimState.nsEnabled = false;
    }

    private void configureAudioSession() {
        if (audioManager != null) {
            previousMode = audioManager.getMode();
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        }
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CodexAecShim:audio");
            wakeLock.acquire(10 * 60 * 1000L);
        }
    }

    private void restoreAudioSession() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        if (audioManager != null) {
            if (focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
            audioManager.setMode(previousMode);
        }
        focusRequest = null;
    }

    private void stopRecorder() {
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
            }
            record.release();
        }
    }

    private void stopPlayer() {
        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
        }
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] normalizeCaptureFrame(byte[] frame, int sampleRate) {
        if (sampleRate == PROTOCOL_RATE) {
            return fitFrame(frame, PROTOCOL_FRAME_BYTES);
        }
        if (sampleRate == 48000) {
            return downsample48To24(frame);
        }
        return fitFrame(frame, PROTOCOL_FRAME_BYTES);
    }

    private static byte[] normalizePlaybackFrame(byte[] frame) {
        int evenLength = frame.length - (frame.length % BYTES_PER_SAMPLE);
        if (evenLength <= 0) {
            return new byte[0];
        }
        return evenLength == frame.length ? frame : Arrays.copyOf(frame, evenLength);
    }

    private static byte[] fitFrame(byte[] frame, int targetBytes) {
        if (frame.length == targetBytes) {
            return frame;
        }
        byte[] out = new byte[targetBytes];
        System.arraycopy(frame, 0, out, 0, Math.min(frame.length, out.length));
        return out;
    }

    private static byte[] downsample48To24(byte[] input) {
        int samples = input.length / 2;
        int outSamples = samples / 2;
        byte[] out = new byte[outSamples * 2];
        for (int i = 0, j = 0; i + 3 < input.length && j + 1 < out.length; i += 4, j += 2) {
            out[j] = input[i];
            out[j + 1] = input[i + 1];
        }
        return fitFrame(out, PROTOCOL_FRAME_BYTES);
    }

    private static byte[] upsample24To48(byte[] input) {
        byte[] fitted = fitFrame(input, PROTOCOL_FRAME_BYTES);
        byte[] out = new byte[fitted.length * 2];
        for (int i = 0, j = 0; i + 1 < fitted.length; i += 2) {
            out[j++] = fitted[i];
            out[j++] = fitted[i + 1];
            out[j++] = fitted[i];
            out[j++] = fitted[i + 1];
        }
        return out;
    }
}
