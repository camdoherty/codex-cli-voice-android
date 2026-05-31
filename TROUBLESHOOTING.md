# Troubleshooting

## One-Command Installer Fails

Confirm Termux is installed in the primary Android user/profile. Secondary
users and work profiles can fail Termux bootstrap because Termux packages are
built for the primary-user `$PREFIX` path.

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
apt full-upgrade
sh install.sh
```

If `curl` fails with a linker error such as a missing OpenSSL or ngtcp2 symbol,
the fresh Termux base packages are out of sync. Run `apt full-upgrade`, accept
the package maintainer versions for base Termux config prompts, then install or
retry `curl`.

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
permission. On staging devices with ADB, installing the staged Bridge APK with
`adb install -r` is a valid assisted path if file-manager or share-sheet routes
fail.

## ADB Install Of Termux Fails

On recent Android builds, direct `adb install` of the F-Droid Termux APK can
fail with:

```text
INSTALL_FAILED_VERIFICATION_FAILURE
```

Do not disable Android package verification globally. ADB is not required for
ordinary installs; install Termux from F-Droid instead. For maintainer or
power-user staging-device clean installs, use the ADB-assisted F-Droid path in
[DEPLOY.md](DEPLOY.md#adb-assisted-fresh-install): uninstall/reset with ADB,
then launch the Termux F-Droid package page and approve the install on-device.

## Widget Shortcut Does Not Open Termux

On Android 10+, Termux needs `Display over other apps` / `Draw over other apps`
permission before Termux:Widget can start visible terminal sessions from the
home screen:

```text
Android Settings -> Apps -> Termux -> Display over other apps -> Allow
```

After granting it, retry the widget. If the widget list is stale, refresh the
shortcuts by rerunning the installer or:

```sh
sh scripts/install_termux_launchers.sh
```

On a staging device with ADB, the overlay permission can usually be applied
with:

```sh
adb -s "$serial" shell appops set com.termux SYSTEM_ALERT_WINDOW allow
```

If the widget opens Termux but Codex or STTS cannot generate a reply, tap the
`Codex` shortcut and complete Codex sign-in first. STTS uses normal Codex
authentication for its agent turns.

## STTS Starts But Reply Generation Fails

Check the basics in this order:

```sh
codex --version
codex exec --help >/dev/null
stts-diag
```

Then open the `Codex` widget/shortcut and sign in if prompted. If Bridge is
running and `stts-diag` passes, authentication is the most likely issue on a
fresh install.

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
billing. Use `$stts talk` for the Plus-friendly local half-duplex voice
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
