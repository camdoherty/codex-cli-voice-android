package io.github.codex_cli_voice_android.aecshim;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    static final String ACTION_START_SERVICE = "io.github.codex_cli_voice_android.aecshim.START_SERVICE";
    static final String ACTION_STOP_SERVICE = "io.github.codex_cli_voice_android.aecshim.STOP_SERVICE";

    private TextView statusView;
    private TextView wakeIndicator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, WakeWordTestStatus.running ? 250L : 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.BLACK);
        applySystemBarInsets(root, pad);

        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("Codex AEC Shim\nws://127.0.0.1:8765/v1/audio\nws://127.0.0.1:8765/v1/text-voice");
        title.setTextSize(20);
        title.setTextColor(Color.rgb(142, 255, 189));
        root.addView(title);

        Button permissions = new Button(this);
        permissions.setText("Grant Permissions");
        styleButton(permissions);
        permissions.setOnClickListener(v -> requestNeededPermissions());
        root.addView(permissions);

        Button start = new Button(this);
        start.setText("Start Foreground Service");
        styleButton(start);
        start.setOnClickListener(v -> startShimService());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop Service");
        styleButton(stop);
        stop.setOnClickListener(v -> stopShimService());
        root.addView(stop);

        Button refresh = new Button(this);
        refresh.setText("Refresh Status");
        styleButton(refresh);
        refresh.setOnClickListener(v -> refreshStatus());
        root.addView(refresh);

        wakeIndicator = new TextView(this);
        wakeIndicator.setText("Wake Test: stopped");
        wakeIndicator.setTextSize(18);
        wakeIndicator.setTextColor(Color.WHITE);
        wakeIndicator.setPadding(pad, pad / 2, pad, pad / 2);
        wakeIndicator.setBackgroundColor(Color.rgb(28, 28, 28));
        root.addView(wakeIndicator);

        Button wakeStart = new Button(this);
        wakeStart.setText("Start WWS Test Mode");
        styleButton(wakeStart);
        wakeStart.setOnClickListener(v -> sendWakeTestAction(AecShimService.ACTION_WAKE_TEST_START));
        root.addView(wakeStart);

        Button wakePass = new Button(this);
        wakePass.setText("Pass");
        styleButton(wakePass);
        wakePass.setOnClickListener(v -> sendWakeTestAction(AecShimService.ACTION_WAKE_TEST_PASS));
        root.addView(wakePass);

        Button wakeFail = new Button(this);
        wakeFail.setText("Fail - Save Last 20s WAV");
        styleButton(wakeFail);
        wakeFail.setOnClickListener(v -> sendWakeTestAction(AecShimService.ACTION_WAKE_TEST_FAIL));
        root.addView(wakeFail);

        Button wakeStop = new Button(this);
        wakeStop.setText("Stop WWS Test Mode");
        styleButton(wakeStop);
        wakeStop.setOnClickListener(v -> sendWakeTestAction(AecShimService.ACTION_WAKE_TEST_STOP));
        root.addView(wakeStop);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(statusView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1));

        setContentView(root);
        requestNeededPermissions();
        refreshStatus();
        handler.post(refreshRunnable);
        handleLifecycleAction(getIntent());
    }

    private void requestNeededPermissions() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 7);
        }
    }

    private void startShimService() {
        if (!hasRecordAudioPermission()) {
            AecShimState.lastError = "Grant microphone permission before starting service";
            requestNeededPermissions();
            refreshStatus();
            return;
        }
        Intent intent = new Intent(this, AecShimService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        statusView.postDelayed(this::refreshStatus, 500);
    }

    private void stopShimService() {
        stopService(new Intent(this, AecShimService.class));
        refreshStatus();
    }

    private void handleLifecycleAction(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_START_SERVICE.equals(action)) {
            startShimService();
        } else if (ACTION_STOP_SERVICE.equals(action)) {
            stopShimService();
        }
    }

    private void refreshStatus() {
        statusView.setText(AecShimState.summary());
        refreshWakeIndicator();
    }

    private void sendWakeTestAction(String action) {
        if (!hasRecordAudioPermission()) {
            AecShimState.lastError = "Grant microphone permission before starting wake test mode";
            requestNeededPermissions();
            refreshStatus();
            return;
        }
        Intent intent = new Intent(this, AecShimService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        statusView.postDelayed(this::refreshStatus, 300);
    }

    private void refreshWakeIndicator() {
        boolean hit = WakeWordTestStatus.recentlyHit(System.currentTimeMillis());
        if (hit) {
            wakeIndicator.setBackgroundColor(Color.rgb(0, 150, 70));
        } else if (WakeWordTestStatus.running) {
            wakeIndicator.setBackgroundColor(Color.rgb(70, 70, 20));
        } else {
            wakeIndicator.setBackgroundColor(Color.rgb(28, 28, 28));
        }
        wakeIndicator.setText("Wake Test: "
                + (WakeWordTestStatus.running ? "listening" : "stopped")
                + " | score " + String.format(java.util.Locale.US, "%.6f", WakeWordTestStatus.lastScore)
                + " | max " + String.format(java.util.Locale.US, "%.6f", WakeWordTestStatus.maxScore)
                + " | rms/peak " + String.format(java.util.Locale.US, "%.1f", WakeWordTestStatus.inputRmsDbfs)
                + "/" + String.format(java.util.Locale.US, "%.1f", WakeWordTestStatus.inputPeakDbfs)
                + " dBFS | input " + WakeWordTestStatus.routedInput
                + " | pass/fail " + WakeWordTestStatus.passCount + "/" + WakeWordTestStatus.failCount);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLifecycleAction(intent);
    }

    private boolean hasRecordAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        super.onDestroy();
    }

    private static void styleButton(Button button) {
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(28, 28, 28));
    }

    private static void applySystemBarInsets(View root, int basePad) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = insets.getSystemWindowInsetTop();
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    topInset = Math.max(topInset, cutout.getSafeInsetTop());
                }
            }
            view.setPadding(basePad, basePad + topInset, basePad, basePad);
            return insets;
        });
        root.requestApplyInsets();
    }
}
