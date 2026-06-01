package io.github.codex_cli_voice_android.aecshim;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicInteger;

final class TermuxCommandLauncher {
    static final String ACTION_RESULT = "io.github.codex_cli_voice_android.aecshim.TERMUX_RUN_COMMAND_RESULT";
    static final String EXTRA_KIND = "kind";
    static final String EXTRA_ITEM_ID = "item_id";
    static final String EXTRA_REVIEW_NOW = "review_now";

    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    private static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";

    private static final String RESULT_BUNDLE = "result";
    private static final String RESULT_EXIT_CODE = "exitCode";
    private static final String RESULT_ERR = "err";
    private static final String RESULT_ERRMSG = "errmsg";

    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_SH = "/data/data/com.termux/files/usr/bin/sh";
    private static final String STTS_SCRIPT = TERMUX_HOME + "/.codex/skills/stts/scripts/stts-session.sh";
    private static final String PREFS = "termux_controls";
    private static final String PREF_AVAILABLE = "available";

    private static final AtomicInteger REQUEST_CODES = new AtomicInteger(2000);
    private static long lastProbeStartedAtMs;

    private TermuxCommandLauncher() {}

    static boolean controlsAvailable() {
        return "available".equals(AecShimState.termuxControlsState);
    }

    static void refreshAvailability(Context context) {
        if (isTermuxInstalled(context)
                && context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            if (prefs(context).getBoolean(PREF_AVAILABLE, false)) {
                AecShimState.termuxControlsState = "available";
                AecShimState.termuxControlsLastError = "";
                return;
            }
            if ("unknown".equals(AecShimState.termuxControlsState)) {
                AecShimState.termuxControlsState = "not checked";
                AecShimState.termuxControlsLastError = "tap Check Termux Controls";
            }
            return;
        }
        if (!isTermuxInstalled(context)) {
            rememberUnavailable(context);
            setUnavailable("Termux not installed");
            return;
        }
        rememberUnavailable(context);
        setUnavailable("grant Run commands in Termux permission");
    }

    static void checkControls(Context context) {
        long now = System.currentTimeMillis();
        if ("checking".equals(AecShimState.termuxControlsState) && now - lastProbeStartedAtMs < 15000L) {
            return;
        }
        if (!isTermuxInstalled(context)) {
            rememberUnavailable(context);
            setUnavailable("Termux not installed");
            return;
        }
        if (context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            rememberUnavailable(context);
            setUnavailable("grant Run commands in Termux permission");
            return;
        }
        lastProbeStartedAtMs = now;
        AecShimState.termuxControlsState = "checking";
        AecShimState.termuxControlsLastError = "";
        sendRunCommand(
                context,
                "probe",
                "test -x " + shellQuote(STTS_SCRIPT),
                true,
                "Codex Bridge probe",
                "Checks whether Codex Bridge can run STTS commands in Termux.",
                true);
    }

    static void runStartTalk(Context context) {
        showControlNotification(context, "Starting STTS talk...", "The voice turn is being queued in Termux.");
        if (!ensureUsable(context)) {
            showControlErrorNotification(context, AecShimState.termuxControlsSummary());
            return;
        }
        boolean started = sendRunCommand(
                context,
                "talk",
                "if command -v stts >/dev/null 2>&1; then exec stts talk; else exec sh "
                        + shellQuote(STTS_SCRIPT)
                        + " talk; fi",
                true,
                "STTS: Start / Talk",
                "Starts the STTS tmux session and runs one voice turn.",
                true);
        if (!started) {
            showControlErrorNotification(context, AecShimState.termuxControlsSummary());
        }
    }

    static void runAttach(Context context) {
        if (ensureUsable(context)) {
            sendRunCommand(
                    context,
                    "attach",
                    "if command -v stts >/dev/null 2>&1; then exec stts session; else exec sh "
                            + shellQuote(STTS_SCRIPT)
                            + " session; fi",
                    false,
                    "STTS: Attach Session",
                    "Opens the persistent STTS tmux workspace without starting a voice turn.",
                    false);
        }
    }

    static void runWake(Context context) {
        showControlNotification(context, "Starting wake word...", "Wake word is being armed in Termux.");
        if (!ensureUsable(context)) {
            showControlErrorNotification(context, AecShimState.termuxControlsSummary());
            return;
        }
        boolean started = sendRunCommand(
                context,
                "wake",
                "if command -v stts >/dev/null 2>&1; then exec stts wake; else exec sh "
                        + shellQuote(STTS_SCRIPT)
                        + " wake; fi",
                true,
                "STTS: Wake Word",
                "Starts the STTS tmux session and arms wake-word mode.",
                true);
        if (!started) {
            showControlErrorNotification(context, AecShimState.termuxControlsSummary());
        }
    }

    static void runStop(Context context) {
        showControlNotification(context, "Stopping STTS...", "Audio is cancelled; Termux stop is being queued.");
        if (!ensureUsable(context)) {
            showControlErrorNotification(context, AecShimState.termuxControlsSummary());
            return;
        }
        boolean started = sendRunCommand(
                context,
                "stop",
                "if command -v stts >/dev/null 2>&1; then exec stts stop; else exec sh "
                        + shellQuote(STTS_SCRIPT)
                        + " stop; fi",
                true,
                "STTS: Stop",
                "Stops the STTS tmux session and voice helpers.",
                true);
        if (!started) {
            showControlErrorNotification(context, AecShimState.termuxControlsSummary());
        }
    }

    static boolean runSharedIntake(Context context, String shellCommand, String itemId, boolean reviewNow) {
        if (!ensureUsable(context)) {
            return false;
        }
        Bundle callbackExtras = new Bundle();
        callbackExtras.putString(EXTRA_ITEM_ID, itemId);
        callbackExtras.putBoolean(EXTRA_REVIEW_NOW, reviewNow);
        return sendRunCommand(
                context,
                "share-stage",
                shellCommand,
                true,
                "Codex Bridge: Shared Item",
                "Stages shared Android content in the Codex inbox.",
                true,
                callbackExtras);
    }

    static void runSharedReviewLatest(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NotificationIds.SHARE_ID);
        }
        showShareReviewingNotification(context);
        if (ensureUsable(context)) {
            String command = "manifest=\"$HOME/.local/state/codex-stts/latest-share-manifest.txt\"; "
                    + "if [ ! -s \"$manifest\" ]; then echo 'No Codex inbox item is ready to review.'; exit 1; fi; "
                    + "path=$(cat \"$manifest\"); "
                    + "state=\"$HOME/.local/state/codex-stts\"; "
                    + "resume_wake=0; "
                    + "pid=$(cat \"$state/session.pid\" 2>/dev/null || true); "
                    + "mode=$(cat \"$state/session-mode.txt\" 2>/dev/null || true); "
                    + "if [ \"$mode\" = wake ] && [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null; then resume_wake=1; fi; "
                    + "if [ \"$resume_wake\" = 1 ]; then wake_arg=--then-wake; else wake_arg=; fi; "
                    + "if command -v stts >/dev/null 2>&1; then stts stop >/dev/null 2>&1 || true; exec stts ingest --speak $wake_arg \"$path\"; "
                    + "else sh \"$HOME/.codex/skills/stts/scripts/stts-session.sh\" stop >/dev/null 2>&1 || true; "
                    + "exec sh \"$HOME/.codex/skills/stts/scripts/stts-session.sh\" ingest --speak $wake_arg \"$path\"; fi";
            boolean started = sendRunCommand(
                    context,
                    "share-review",
                    command,
                    true,
                    "Codex Bridge: Review Shared Item",
                    "Reviews the latest saved Codex inbox item.",
                    true);
            if (!started) {
                showShareFailureNotification(context, AecShimState.termuxControlsSummary());
            }
        } else {
            showShareFailureNotification(context, AecShimState.termuxControlsSummary());
        }
    }

    static void handleResult(Context context, Intent intent) {
        String kind = intent == null ? "" : intent.getStringExtra(EXTRA_KIND);
        String itemId = intent == null ? "" : intent.getStringExtra(EXTRA_ITEM_ID);
        boolean reviewNow = intent != null && intent.getBooleanExtra(EXTRA_REVIEW_NOW, false);
        Bundle result = intent == null ? null : intent.getBundleExtra(RESULT_BUNDLE);
        if (result == null) {
            if (isShareKind(kind)) {
                showShareFailureNotification(context, "Termux did not return a result");
            } else if (isControlKind(kind)) {
                showControlErrorNotification(context, "Termux did not return a result");
            } else {
                setUnavailable("Termux did not return a result");
            }
            refreshBridgeNotification(context);
            return;
        }
        int err = result.getInt(RESULT_ERR, Activity.RESULT_OK);
        int exitCode = result.getInt(RESULT_EXIT_CODE, result.getInt("exit_code", 1));
        String errmsg = result.getString(RESULT_ERRMSG, "");
        if ("probe".equals(kind) && (err == Activity.RESULT_OK || err == 0) && exitCode == 0) {
            prefs(context).edit().putBoolean(PREF_AVAILABLE, true).apply();
            AecShimState.termuxControlsState = "available";
            AecShimState.termuxControlsLastError = "";
        } else if ("share-stage".equals(kind)) {
            if ((err == Activity.RESULT_OK || err == 0) && exitCode == 0) {
                if (reviewNow) {
                    showShareReviewStartedNotification(context);
                } else {
                    showShareSavedNotification(context, itemId == null ? "" : itemId);
                }
            } else {
                String detail = errmsg == null || errmsg.isEmpty()
                        ? "share save failed: err=" + err + " exit=" + exitCode
                        : errmsg;
                showShareFailureNotification(context, detail);
            }
        } else if ("share-review".equals(kind)) {
            if ((err == Activity.RESULT_OK || err == 0) && exitCode == 0) {
                showShareReviewStartedNotification(context);
            } else {
                String detail = errmsg == null || errmsg.isEmpty()
                        ? "review failed: err=" + err + " exit=" + exitCode
                        : errmsg;
                showShareFailureNotification(context, detail);
            }
        } else if (isControlKind(kind)) {
            if ((err == Activity.RESULT_OK || err == 0) && exitCode == 0) {
                showControlSuccessNotification(context, kind);
            } else {
                String detail = errmsg == null || errmsg.isEmpty()
                        ? "command failed: err=" + err + " exit=" + exitCode
                        : errmsg;
                showControlErrorNotification(context, detail);
            }
        } else {
            if ("probe".equals(kind)) {
                rememberUnavailable(context);
                String detail = errmsg == null || errmsg.isEmpty()
                        ? "command failed: err=" + err + " exit=" + exitCode
                        : errmsg;
                setUnavailable(detail);
            }
        }
        refreshBridgeNotification(context);
    }

    private static boolean ensureUsable(Context context) {
        if (controlsAvailable()) {
            return true;
        }
        refreshAvailability(context);
        return isTermuxInstalled(context)
                && context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isShareKind(String kind) {
        return "share-stage".equals(kind) || "share-review".equals(kind);
    }

    private static boolean isControlKind(String kind) {
        return "talk".equals(kind) || "wake".equals(kind) || "stop".equals(kind);
    }

    private static boolean sendRunCommand(
            Context context,
            String kind,
            String shellCommand,
            boolean background,
            String label,
            String description,
            boolean wantResult) {
        return sendRunCommand(context, kind, shellCommand, background, label, description, wantResult, null);
    }

    private static boolean sendRunCommand(
            Context context,
            String kind,
            String shellCommand,
            boolean background,
            String label,
            String description,
            boolean wantResult,
            Bundle callbackExtras) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra(EXTRA_COMMAND_PATH, TERMUX_SH);
        intent.putExtra(EXTRA_ARGUMENTS, new String[]{"-lc", shellCommand});
        intent.putExtra(EXTRA_WORKDIR, TERMUX_HOME);
        intent.putExtra(EXTRA_BACKGROUND, background);
        intent.putExtra(EXTRA_SESSION_ACTION, "0");
        intent.putExtra(EXTRA_COMMAND_LABEL, label);
        intent.putExtra(EXTRA_COMMAND_DESCRIPTION, description);
        if (wantResult) {
            Intent callback = new Intent(context, TermuxRunCommandResultReceiver.class);
            callback.setAction(ACTION_RESULT);
            callback.putExtra(EXTRA_KIND, kind);
            if (callbackExtras != null) {
                callback.putExtras(callbackExtras);
            }
            int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODES.incrementAndGet(),
                    callback,
                    flags);
            intent.putExtra(EXTRA_PENDING_INTENT, pendingIntent);
        }
        try {
            context.startService(intent);
            return true;
        } catch (SecurityException e) {
            setUnavailable("grant Run commands in Termux permission");
        } catch (Exception e) {
            setUnavailable(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return false;
    }

    static void showSharePendingNotification(Context context, boolean reviewNow) {
        if (reviewNow) {
            showShareReviewingNotification(context);
            return;
        }
        createNotificationChannel(context);
        Notification.Builder builder = new Notification.Builder(context, NotificationIds.CHANNEL_ID)
                .setContentTitle("Saving to Codex Inbox...")
                .setContentText("Shared content is being staged in Termux.")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true);
        notifyShare(context, builder.build());
    }

    private static void showShareReviewingNotification(Context context) {
        createNotificationChannel(context);
        Notification.Builder builder = new Notification.Builder(context, NotificationIds.CHANNEL_ID)
                .setContentTitle("Reviewing shared item...")
                .setContentText("Codex is preparing a spoken review.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true);
        notifyShare(context, builder.build());
    }

    private static void showShareReviewStartedNotification(Context context) {
        createNotificationChannel(context);
        Notification.Builder builder = new Notification.Builder(context, NotificationIds.CHANNEL_ID)
                .setContentTitle("Review started")
                .setContentText("Codex will speak when the review is ready.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setAutoCancel(true);
        notifyShare(context, builder.build());
    }

    private static void showShareSavedNotification(Context context, String itemId) {
        createNotificationChannel(context);
        String text = itemId == null || itemId.isEmpty()
                ? "Inbox received shared item."
                : "Inbox received shared item: " + itemId;
        PendingIntent review = reviewPendingIntent(context);
        Notification.Builder builder = new Notification.Builder(context, NotificationIds.CHANNEL_ID)
                .setContentTitle("Inbox received shared item")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentIntent(review)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_view, "Review", review);
        notifyShare(context, builder.build());
    }

    static void showShareFailureNotification(Context context, String detail) {
        createNotificationChannel(context);
        Notification.Builder builder = new Notification.Builder(context, NotificationIds.CHANNEL_ID)
                .setContentTitle("Shared item not saved")
                .setContentText(detail == null || detail.isEmpty() ? "Codex Bridge could not save the shared item." : detail)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true);
        notifyShare(context, builder.build());
    }

    private static void showControlSuccessNotification(Context context, String kind) {
        if ("wake".equals(kind)) {
            showControlNotification(context, "Wake word requested", "STTS wake word is starting in Termux.");
        } else if ("talk".equals(kind)) {
            showControlNotification(context, "STTS talk requested", "Tap Attach Session to view the tmux workspace.");
        } else if ("stop".equals(kind)) {
            showControlNotification(context, "STTS stop requested", "Voice helpers are stopping.");
        }
    }

    private static void showControlErrorNotification(Context context, String detail) {
        showControlNotification(
                context,
                "Codex Bridge setup required",
                detail == null || detail.isEmpty() ? "Termux controls are not available." : detail);
    }

    private static void showControlNotification(Context context, String title, String text) {
        createNotificationChannel(context);
        Notification.Builder builder = new Notification.Builder(context, NotificationIds.CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true);
        notifyControl(context, builder.build());
    }

    private static PendingIntent reviewPendingIntent(Context context) {
        Intent intent = new Intent(context, AecShimService.class);
        intent.setAction(AecShimService.ACTION_REVIEW_LATEST_SHARE);
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(context, 30, intent, flags);
    }

    private static void notifyShare(Context context, Notification notification) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        try {
            manager.notify(NotificationIds.SHARE_ID, notification);
        } catch (SecurityException e) {
            AecShimState.lastError = "Grant notification permission for Codex Bridge share alerts";
        }
    }

    private static void notifyControl(Context context, Notification notification) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        try {
            manager.notify(NotificationIds.CONTROL_ID, notification);
        } catch (SecurityException e) {
            AecShimState.lastError = "Grant notification permission for Codex Bridge controls";
        }
    }

    private static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NotificationIds.CHANNEL_ID,
                "Codex Bridge",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private static boolean isTermuxInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void setUnavailable(String reason) {
        AecShimState.termuxControlsState = "setup required";
        AecShimState.termuxControlsLastError = reason == null ? "" : reason;
    }

    private static void rememberUnavailable(Context context) {
        prefs(context).edit().putBoolean(PREF_AVAILABLE, false).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void refreshBridgeNotification(Context context) {
        if (!AecShimState.serviceRunning) {
            return;
        }
        Intent refresh = new Intent(context, AecShimService.class);
        refresh.setAction(AecShimService.ACTION_REFRESH_NOTIFICATION);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(refresh);
            } else {
                context.startService(refresh);
            }
        } catch (Exception ignored) {
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
