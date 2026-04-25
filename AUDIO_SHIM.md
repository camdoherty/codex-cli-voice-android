# Android AEC Shim

The AEC shim is a small Android app/service that provides native microphone capture and speaker playback for Codex realtime sessions. Codex connects to it over a loopback WebSocket:

```text
codex-voice -> ws://127.0.0.1:8765/v1/audio -> Android AudioRecord/AudioTrack
```

The shim package id is:

```text
io.github.codex_cli_voice_android.aecshim
```

## Build

```bash
scripts/setup_android_toolchain.sh
scripts/build_aec_shim_apk.sh
```

## Install From Release

For normal use, download `codex-aec-shim-debug.apk` from the GitHub Release on the Android device and install it with Android's package installer.

After installing, open the app and grant microphone permission. The service should listen on:

```text
ws://127.0.0.1:8765/v1/audio
```

## Install Locally Built APK

Install the generated debug APK manually or upload it with:

```bash
cp .env.example .env
$EDITOR .env
scripts/upload_aec_shim_apk.sh
```

The FTP upload script requires `FTP_HOST` and `FTP_USER` in `.env` or the environment.

## Run Voice Mode

In Termux:

```sh
codex-voice --allow-realtime
```

or:

```sh
CODEX_VOICE_ALLOW_REALTIME=1 codex-voice
```

If you use a non-default shim URL:

```sh
CODEX_ANDROID_AUDIO_WS_URL=ws://127.0.0.1:8765/v1/audio codex-voice --allow-realtime
```

## Current Tuning Notes

- The shim is functional but should be considered alpha-quality audio.
- Realtime sessions are billable; use short validation prompts while tuning.
- If output sounds sped up or distorted, retest with the latest shim APK and confirm the installed package matches the current build.
