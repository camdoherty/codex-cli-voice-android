package io.github.codex_cli_voice_android.aecshim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.net.InetSocketAddress;

public final class AecShimService extends Service {
    private AudioEngine audioEngine;
    private LoopbackAudioServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        AecShimState.serviceRunning = true;
        createNotificationChannel();
        audioEngine = new AudioEngine(this);
        server = new LoopbackAudioServer(new InetSocketAddress("127.0.0.1", 8765), audioEngine);
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
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundNow() {
        Notification notification = new Notification.Builder(this, NotificationIds.CHANNEL_ID)
                .setContentTitle("Codex AEC Shim")
                .setContentText("Listening on 127.0.0.1:8765")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NotificationIds.SERVICE_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NotificationIds.SERVICE_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NotificationIds.CHANNEL_ID,
                "Codex AEC Shim",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
