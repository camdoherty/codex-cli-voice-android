# Troubleshooting

## One-Command Installer Fails

Run the auditable form to inspect the installer and retry with clearer local
state:

```sh
curl -fsSLO https://raw.githubusercontent.com/camdoherty/codex-cli-voice-android/main/install.sh
less install.sh
sh install.sh
```

The installer supports a non-mutating asset check:

```sh
sh install.sh --verify-only
```

If package install fails, refresh Termux package indexes and retry:

```sh
pkg update
sh install.sh
```

## Shared Storage Or APK Staging Fails

The installer stages the shim APK into:

```text
$HOME/storage/downloads/codex-aec-shim-debug.apk
```

If that path is missing, approve the Android storage prompt from:

```sh
termux-setup-storage
```

Then rerun the installer, or use:

```sh
sh install.sh --no-shim
```

to skip APK staging.

If Android blocks the APK install, open Downloads manually, tap
`codex-aec-shim-debug.apk`, and allow installs from the file manager or Termux
when Android asks. After installing, open the shim app and grant microphone
permission.

## Checksum Mismatch

Do not install mismatched assets. Clear the installer cache and retry:

```sh
rm -rf "$HOME/.cache/ccva-installer"
sh install.sh
```

If the mismatch repeats, compare the release asset checksums on GitHub before
continuing.

## Termux:API Not Responding

Install both pieces:

```sh
pkg install termux-api
```

Also install the Termux:API Android app from F-Droid, open it once, and grant
permissions. Then test:

```sh
termux-volume
```

## `codex-voice` Exits Immediately

This is intentional unless realtime billing is explicitly allowed:

```sh
codex-voice --allow-realtime
```

or:

```sh
CODEX_VOICE_ALLOW_REALTIME=1 codex-voice
```

`codex-voice --allow-realtime` is OpenAI Codex CLI Realtime voice mode adapted
for Android native audio through the AEC shim. It uses OpenAI Realtime API
billing. Use `$tts-stt start` for the Plus-friendly local half-duplex voice
mode.

## Missing API Key

Set `OPENAI_API_KEY` or create a key file:

```sh
printf '%s\n' 'OPENAI_API_KEY=REDACTED' > "$HOME/.oaienv"
chmod 600 "$HOME/.oaienv"
```

You can use another path with `OPENAI_API_KEY_FILE`.

Release packages do not include OpenAI credentials, `.oaienv`, `.ssh`, logs,
shell history, or device snapshots. Credentials stay on the device where the
user configures them.

## `WARNING: flock unsupported on Android`

The packaged `codex-api` and `codex-voice` launchers filter this known Android warning. If it appears during launch, check that your shell is resolving the expected launcher:

```sh
command -v codex-voice
readlink "$HOME/scripts/codex-voice"
codex --version
```

Redeploy the latest package if an old launcher or old install path is still shadowing the current one.

## Shim Not Connecting

Check the Android app is installed, microphone permission is granted, and the service is listening on:

```text
ws://127.0.0.1:8765/v1/audio
```

The shim is intended as a loopback-only local service. Do not expose
`127.0.0.1:8765` through a public tunnel.

Then launch:

```sh
CODEX_ANDROID_AUDIO_WS_URL=ws://127.0.0.1:8765/v1/audio codex-voice --allow-realtime
```

## SSH Deploy Fails

Confirm `.env` contains the right Termux SSH target:

```text
PIXEL_HOST=android-device-host-or-ip
PIXEL_USER=termux-ssh-user
PIXEL_PORT=8022
```

If you use a dedicated private key, set:

```text
PIXEL_IDENTITY=$HOME/.ssh/id_ed25519
```

The deploy script uses `SSH_CONFIG=/dev/null` by default so host machine aliases do not leak into public automation.
