package io.github.codex_cli_voice_android.aecshim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class TermuxRunCommandResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        TermuxCommandLauncher.handleResult(context, intent);
    }
}
