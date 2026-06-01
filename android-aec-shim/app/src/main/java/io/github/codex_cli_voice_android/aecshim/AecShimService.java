package io.github.codex_cli_voice_android.aecshim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.net.InetSocketAddress;

public final class AecShimService extends Service {
    private static final String STTS_IDLE = "STTS: Idle";
    private static final String STTS_READY = "STTS: Ready";
    private static final String STTS_LISTENING = "STTS: Listening...";
    private static final String STTS_THINKING = "STTS: Thinking...";
    private static final String STTS_SPEAKING = "STTS: Speaking...";

    static final String ACTION_WAKE_TEST_START = "io.github.codex_cli_voice_android.aecshim.WAKE_TEST_START";
    static final String ACTION_WAKE_TEST_STOP = "io.github.codex_cli_voice_android.aecshim.WAKE_TEST_STOP";
    static final String ACTION_WAKE_TEST_PASS = "io.github.codex_cli_voice_android.aecshim.WAKE_TEST_PASS";
    static final String ACTION_WAKE_TEST_FAIL = "io.github.codex_cli_voice_android.aecshim.WAKE_TEST_FAIL";
    static final String ACTION_REFRESH_NOTIFICATION = "io.github.codex_cli_voice_android.aecshim.REFRESH_NOTIFICATION";
    static final String ACTION_STTS_START_TALK = "io.github.codex_cli_voice_android.aecshim.STTS_START_TALK";
    static final String ACTION_STTS_WAKE = "io.github.codex_cli_voice_android.aecshim.STTS_WAKE";
    static final String ACTION_STTS_STOP = "io.github.codex_cli_voice_android.aecshim.STTS_STOP";
    static final String ACTION_REVIEW_LATEST_SHARE = "io.github.codex_cli_voice_android.aecshim.REVIEW_LATEST_SHARE";

    private AudioEngine audioEngine;
    private AudioModeCoordinator audioModeCoordinator;
    private TextVoiceController textVoiceController;
    private WakeWordTestController wakeWordTestController;
    private LoopbackAudioServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        AecShimState.serviceRunning = true;
        createNotificationChannel();
        audioModeCoordinator = new AudioModeCoordinator();
        audioEngine = new AudioEngine(this, audioModeCoordinator);
        textVoiceController = new TextVoiceController(this, audioModeCoordinator);
        wakeWordTestController = new WakeWordTestController(this, audioModeCoordinator);
        TermuxCommandLauncher.refreshAvailability(this);
        if (!TermuxCommandLauncher.controlsAvailable()) {
            TermuxCommandLauncher.checkControls(this);
        }
        server = new LoopbackAudioServer(
                new InetSocketAddress("127.0.0.1", 8765),
                audioEngine,
                textVoiceController);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNow();
        try {
            if (!AecShimState.serverListening) {
                server.start();
                AecShimState.serverListening = true;
                AecShimState.lastError = "";
            }
        } catch (Exception e) {
            AecShimState.lastError = "server start failed: " + e.getMessage();
            stopSelf();
        }
        handleAction(intent == null ? null : intent.getAction());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        AecShimState.serviceRunning = false;
        AecShimState.serverListening = false;
        AecShimState.clientConnected = false;
        if (server != null) {
            try {
                server.stop(500);
            } catch (Exception ignored) {
            }
        }
        if (audioEngine != null) {
            audioEngine.stop();
        }
        if (textVoiceController != null) {
            textVoiceController.shutdown();
        }
        if (wakeWordTestController != null) {
            wakeWordTestController.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundNow() {
        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NotificationIds.SERVICE_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NotificationIds.SERVICE_ID, notification);
        }
    }

    void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NotificationIds.SERVICE_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = new Notification.Builder(this, NotificationIds.CHANNEL_ID)
                .setContentTitle("Codex Bridge")
                .setContentText(notificationText())
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true);
        if (TermuxCommandLauncher.controlsAvailable()) {
            builder.addAction(
                    android.R.drawable.ic_btn_speak_now,
                    "Start / Talk",
                    serviceIntent(ACTION_STTS_START_TALK, 10));
            builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Wake Word",
                    serviceIntent(ACTION_STTS_WAKE, 11));
            builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    serviceIntent(ACTION_STTS_STOP, 12));
        }
        return builder.build();
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, AecShimService.class);
        intent.setAction(action);
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private String notificationText() {
        String state = TextVoiceStatus.state;
        String wake = WakeWordStatus.wakeState;
        if ("stt_starting".equals(state) || "stt_listening".equals(state)) {
            return STTS_LISTENING;
        }
        if ("client_processing".equals(state)) {
            return STTS_THINKING;
        }
        if ("tts_starting".equals(state) || "tts_speaking".equals(state)) {
            return STTS_SPEAKING;
        }
        if ("listening".equals(wake)) {
            return STTS_READY;
        }
        if (TextVoiceStatus.textClientConnected) {
            return STTS_READY;
        }
        return STTS_IDLE;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NotificationIds.CHANNEL_ID,
                "Codex Bridge",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void handleAction(String action) {
        if (action == null || wakeWordTestController == null) {
            return;
        }
        if (ACTION_WAKE_TEST_START.equals(action)) {
            wakeWordTestController.start();
        } else if (ACTION_WAKE_TEST_STOP.equals(action)) {
            wakeWordTestController.stop();
        } else if (ACTION_WAKE_TEST_PASS.equals(action)) {
            wakeWordTestController.pass();
        } else if (ACTION_WAKE_TEST_FAIL.equals(action)) {
            wakeWordTestController.fail();
        } else if (ACTION_REFRESH_NOTIFICATION.equals(action)) {
            // Notification refresh only.
        } else if (ACTION_STTS_START_TALK.equals(action)) {
            TermuxCommandLauncher.runStartTalk(this);
        } else if (ACTION_STTS_WAKE.equals(action)) {
            TermuxCommandLauncher.runWake(this);
        } else if (ACTION_STTS_STOP.equals(action)) {
            textVoiceController.onStopButtonPressed();
            TermuxCommandLauncher.runStop(this);
        } else if (ACTION_REVIEW_LATEST_SHARE.equals(action)) {
            TermuxCommandLauncher.runSharedReviewLatest(this);
        }
        updateNotification();
    }
}
