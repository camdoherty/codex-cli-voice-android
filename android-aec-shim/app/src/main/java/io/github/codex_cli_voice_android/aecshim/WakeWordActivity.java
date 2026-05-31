package io.github.codex_cli_voice_android.aecshim;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

public final class WakeWordActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 21;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startWakeWhenReady();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startWakeAndOpenBridge();
        } else {
            AecShimState.lastError = "Grant microphone permission before starting wake word";
            openBridge();
        }
    }

    private void startWakeWhenReady() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        startWakeAndOpenBridge();
    }

    private void startWakeAndOpenBridge() {
        Intent intent = new Intent(this, AecShimService.class);
        intent.setAction(AecShimService.ACTION_STTS_WAKE);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        openBridge();
    }

    private void openBridge() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
        finish();
    }
}
