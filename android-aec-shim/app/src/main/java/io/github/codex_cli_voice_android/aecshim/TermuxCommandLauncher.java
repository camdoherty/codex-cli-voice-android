package io.github.codex_cli_voice_android.aecshim;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicInteger;

final class TermuxCommandLauncher {
    static final String ACTION_RESULT = "io.github.codex_cli_voice_android.aecshim.TERMUX_RUN_COMMAND_RESULT";
    static final String EXTRA_KIND = "kind";

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

    private static final AtomicInteger REQUEST_CODES = new AtomicInteger(2000);
    private static long lastProbeStartedAtMs;

    private TermuxCommandLauncher() {}

    static boolean controlsAvailable() {
        return "available".equals(AecShimState.termuxControlsState);
    }

    static void refreshAvailability(Context context, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastProbeStartedAtMs < 60000L && !"unknown".equals(AecShimState.termuxControlsState)) {
            return;
        }
        if (!force && "checking".equals(AecShimState.termuxControlsState) && now - lastProbeStartedAtMs < 15000L) {
            return;
        }
        if (!force && controlsAvailable() && now - lastProbeStartedAtMs < 60000L) {
            return;
        }
        if (!isTermuxInstalled(context)) {
            setUnavailable("Termux not installed");
            return;
        }
        if (context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
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
        if (ensureUsable(context)) {
            sendRunCommand(
                    context,
                    "talk",
                    "if command -v stts >/dev/null 2>&1; then exec stts talk; else exec sh "
                            + shellQuote(STTS_SCRIPT)
                            + " talk; fi",
                    false,
                    "STTS: Start / Talk",
                    "Starts or attaches the STTS tmux session and runs one voice turn.",
                    false);
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

    static void runStop(Context context) {
        if (ensureUsable(context)) {
            sendRunCommand(
                    context,
                    "stop",
                    "if command -v stts >/dev/null 2>&1; then exec stts stop; else exec sh "
                            + shellQuote(STTS_SCRIPT)
                            + " stop; fi",
                    true,
                    "STTS: Stop",
                    "Stops the STTS tmux session and voice helpers.",
                    false);
        }
    }

    static void handleResult(Context context, Intent intent) {
        String kind = intent == null ? "" : intent.getStringExtra(EXTRA_KIND);
        Bundle result = intent == null ? null : intent.getBundleExtra(RESULT_BUNDLE);
        if (result == null) {
            setUnavailable("Termux did not return a result");
            refreshBridgeNotification(context);
            return;
        }
        int err = result.getInt(RESULT_ERR, Activity.RESULT_OK);
        int exitCode = result.getInt(RESULT_EXIT_CODE, result.getInt("exit_code", 1));
        String errmsg = result.getString(RESULT_ERRMSG, "");
        if ("probe".equals(kind) && (err == Activity.RESULT_OK || err == 0) && exitCode == 0) {
            AecShimState.termuxControlsState = "available";
            AecShimState.termuxControlsLastError = "";
        } else {
            String detail = errmsg == null || errmsg.isEmpty()
                    ? "command failed: err=" + err + " exit=" + exitCode
                    : errmsg;
            setUnavailable(detail);
        }
        refreshBridgeNotification(context);
    }

    private static boolean ensureUsable(Context context) {
        if (controlsAvailable()) {
            return true;
        }
        refreshAvailability(context, true);
        return false;
    }

    private static void sendRunCommand(
            Context context,
            String kind,
            String shellCommand,
            boolean background,
            String label,
            String description,
            boolean wantResult) {
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
        } catch (SecurityException e) {
            setUnavailable("grant Run commands in Termux permission");
        } catch (Exception e) {
            setUnavailable(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
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
