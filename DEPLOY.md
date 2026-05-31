# Deploy

The deployment helper is designed for safe iteration on a Termux target. It
uploads the package, verifies the remote SHA256, creates a rollback archive,
extracts into `$PREFIX`, installs the `$stts` skill, repairs launcher
symlinks, and runs non-paid smoke tests.

For a no-PC, on-device install from GitHub Releases, use the manual installation section in [README.md](README.md).

For agent-driven device work, prefer this flow:

1. Commit or otherwise stage source changes on the workstation.
2. Pull/sync the repo on the Android device when possible.
3. Run on-device install scripts from the synced repo or release assets.
4. Use SSH for commands and verification; avoid ad hoc file copying except for
   pre-release build artifacts that do not exist on GitHub yet.

Do not treat `termux-open` or `am start` as proof that Android displayed an
installer or started the shim. Verify observable state: package installed,
Termux:API service responsive, shim loopback port open, and smoke tests passed.

## Release Validation Wrapper

For release candidates built with `scripts/release_build.sh`, prefer the
validation wrapper before publishing:

```bash
scripts/release_validate_device.sh v0.136.0-ccva.1 --target Pixel6a
```

For a first install on a clean Termux target:

```bash
scripts/release_validate_device.sh v0.136.0-ccva.1 --fresh --target Pixel6a
```

The wrapper runs `release_doctor`, deploys the CLI package through
`deploy_termux_package.sh`, and writes a report under
`tmp/release-validation/`. If ADB is connected, add `--adb-serial SERIAL` to
capture development-only permission/runtime snapshots after deploy.

The wrapper does not replace human-visible Android checks. Bridge APK install,
microphone permission, widget overlay permission, Codex sign-in, `STTS: Start +
Talk`, `STTS: Wake Word`, and billable Realtime remain explicit validation
steps.

## Configure Target

```bash
cp .env.example .env
$EDITOR .env
```

Required values:

```text
PIXEL_HOST=android-device-host-or-ip
PIXEL_USER=termux-ssh-user
```

Optional values:

```text
PIXEL_PORT=8022
PIXEL_IDENTITY=$HOME/.ssh/id_ed25519
SSH_CONFIG=/dev/null
```

Despite the `PIXEL_*` variable names, the target can be any Android device running Termux with SSH enabled.

## Deploy Package

```bash
scripts/deploy_termux_package.sh \
  codex-cli-voice-android-rust-v0.135.0-ccva.1.tar.gz \
  codex-cli-voice-android-rust-v0.135.0-ccva.1.tar.gz.sha256
```

The script refuses to continue if the remote checksum differs.

For a first install on a clean Termux device:

```bash
ALLOW_FRESH_INSTALL=1 scripts/deploy_termux_package.sh \
  codex-cli-voice-android-rust-v0.135.0-ccva.1.tar.gz \
  codex-cli-voice-android-rust-v0.135.0-ccva.1.tar.gz.sha256
```

The deploy also installs or updates:

```text
$HOME/.codex/skills/stts
$HOME/scripts/codex-api
$HOME/scripts/codex-voice
$HOME/scripts/codex-install-stts
$HOME/scripts/ccva-tmux-run
$HOME/scripts/ccva-realtime-stop
```

Refresh user-facing Termux:Widget shortcuts from a synced repo with:

```sh
sh scripts/install_termux_launchers.sh
```

Expected launch surfaces after refresh:

```text
Codex
Codex Resume Last
Realtime API Voice
Realtime API Voice Stop
STTS: Attach Session
STTS: Start + Talk
STTS: Wake Word
STTS: Stop
```

The `Codex`, `Codex Resume Last`, and `Realtime API Voice` shortcuts use
`ccva-tmux-run` and attach stable tmux sessions named `ccva-codex`,
`ccva-resume`, and `ccva-realtime`. Pane logging is enabled by default under
`~/.local/state/ccva-tmux/logs/`; set `CCVA_TMUX_LOG=0` before launch to
disable it.

Use `Realtime API Voice Stop` to stop the billable Realtime tmux session and
terminate any remaining Realtime process.

For Termux:Widget shortcuts to open visible terminal sessions from the Android
home screen on Android 10+, grant:

```text
Android Settings -> Apps -> Termux -> Display over other apps -> Allow
```

This is part of the standard widget setup. Without it, Termux may block
background-launched terminal sessions with a `Display over other apps`
permission message.

The user-facing widget is the Termux:Widget home-screen widget, which displays
the installed shortcut list.

After a fresh install, tap the `Codex` shortcut once and complete Codex sign-in
before validating `$stts`. STTS uses normal Codex authentication; a voice
session can launch successfully but fail to generate replies until Codex is
signed in.

## Clean Staging Device

Use the Pixel6a or another staging phone to validate clean installs before
publishing a release. Cleanup should remove only CCVA-owned files and preserve
Termux, Termux:API, zsh, SSH, credentials, notes, projects, and shell history.

The cleanup helper is dry-run first:

```sh
sh scripts/clean_termux_ccva.sh
```

Apply the cleanup only after reviewing the dry-run output:

```sh
sh scripts/clean_termux_ccva.sh --apply
sh scripts/clean_termux_ccva.sh --verify
```

The script removes known CCVA-owned paths under `$PREFIX` and `$HOME`, including
the CLI launchers, libexec install, STTS skill, STTS runtime/cache, Termux
scripts, Termux:Widget shortcuts, installer cache, wake-model cache, release
artifacts/backups, and staged shim APKs.

It never removes:

```text
Termux or Termux:API
$HOME/.codex/config*
$HOME/.oaienv
$HOME/.ssh
shell config or history
repo clones
user notes or project files
```

On non-rooted Android, uninstalling the shim app is usually a manual Android UI
step. The cleanup script reports whether the package is installed, but a fully
clean device may still require:

```text
Android Settings -> Apps -> Codex Bridge -> Uninstall
```

## ADB-Assisted Fresh Install

This optional power-user path is for maintainers or testers who want repeatable
validation from a true clean Termux state on a staging phone. Ordinary users do
not need ADB: install Termux from F-Droid, open it once, then run the installer
from [README.md](README.md#installation).

Use the primary Android user/profile. Secondary users and work profiles can
fail Termux bootstrap because Termux packages are built for the primary-user
`$PREFIX` path.

This flow removes Termux app data, so create and verify a backup first. ADB can
do most of the reset and setup, but Android/F-Droid approval remains part of the
user-visible install path.

Backup the current primary-user Termux home before uninstalling:

```sh
stamp="$(date +%Y%m%d-%H%M%S)"
tar -C "$HOME" --exclude './storage' -czf "$HOME/termux-primary-backup-$stamp.tar.gz" .
sha256sum "$HOME/termux-primary-backup-$stamp.tar.gz" \
  > "$HOME/termux-primary-backup-$stamp.tar.gz.sha256"
```

Pull the archive to the workstation and verify the checksum before continuing.

From the workstation, uninstall CCVA and Termux apps:

```sh
serial="ANDROID_SERIAL_OR_HOST_PORT"
adb -s "$serial" uninstall io.github.codex_cli_voice_android.aecshim || true
adb -s "$serial" uninstall com.termux.widget || true
adb -s "$serial" uninstall com.termux.api || true
adb -s "$serial" uninstall com.termux || true
```

Install Termux add-ons with ADB if desired:

```sh
adb -s "$serial" install com.termux.api.apk
adb -s "$serial" install com.termux.widget.apk
```

For the main Termux app, prefer F-Droid on the device. On recent Android builds,
direct `adb install` of the F-Droid Termux APK may fail with
`INSTALL_FAILED_VERIFICATION_FAILURE`. The working assisted path is:

```sh
adb -s "$serial" shell am start \
  -a android.intent.action.VIEW \
  -d 'https://f-droid.org/packages/com.termux/'
```

Choose F-Droid if Android asks which app should open the link, then install
Termux from F-Droid. If the route opens a browser instead, open F-Droid directly
and search for `Termux`.

After Termux installs:

1. Open Termux once and let bootstrap complete.
2. Refresh the fresh base install before downloading with `curl`:

   ```sh
   pkg update
   apt full-upgrade
   pkg install curl openssh
   ```

   On a clean install, choose the package maintainer version for base Termux
   config prompts such as `openssl.cnf`, `sources.list`, and `bash.bashrc`.
3. Run the public installer from [README.md](README.md#installation).
4. Install the staged Codex Bridge APK from Downloads.
   For assisted staging, direct ADB install of the staged Bridge APK is also
   acceptable and avoids Android file-manager/provider quirks:

   ```sh
   adb -s "$serial" pull /sdcard/Download/codex-aec-shim-debug.apk /tmp/codex-aec-shim-debug.apk
   adb -s "$serial" install -r /tmp/codex-aec-shim-debug.apk
   ```

5. Grant microphone, notifications, and any optional Termux external-command
   permission. The installer sets Termux `allow-external-apps=true`
   automatically for Bridge notification controls; Android may still require
   the Codex Bridge `Run commands in Termux environment` permission.
6. For Termux:Widget launchers, grant Termux `Display over other apps`.
7. Tap the `Codex` shortcut once and complete Codex sign-in.
8. Run the smoke tests below, including `STTS: Start + Talk` and
   `STTS: Wake Word` if wake-word validation is in scope.

For staging devices with ADB, the grantable pieces can be applied with:

```sh
adb -s "$serial" shell appops set com.termux SYSTEM_ALERT_WINDOW allow
adb -s "$serial" shell pm grant com.termux android.permission.POST_NOTIFICATIONS || true
adb -s "$serial" shell pm grant io.github.codex_cli_voice_android.aecshim android.permission.RECORD_AUDIO || true
adb -s "$serial" shell pm grant io.github.codex_cli_voice_android.aecshim android.permission.POST_NOTIFICATIONS || true
adb -s "$serial" shell pm grant io.github.codex_cli_voice_android.aecshim com.termux.permission.RUN_COMMAND || true
```

Some Android builds may still require opening the app settings UI for special
permissions. Verify effective state with `appops get` and `dumpsys package`
instead of assuming the command succeeded.

Do not disable Android package verification globally for this flow. It weakens
device install protections and is not representative of normal user setup.

## Optional Notification Controls

Codex Bridge can show STTS notification buttons only when Termux allows
external command execution. The bridge hides those buttons until setup is
available, so widgets and shell commands remain the default reliable controls.
The public installer, SSH deploy helper, and launcher refresh script set the
Termux property automatically.

Enable the Termux side:

```sh
mkdir -p ~/.termux
grep -qxF 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null \
  || printf '%s\n' 'allow-external-apps=true' >> ~/.termux/termux.properties
termux-reload-settings
```

Then grant Codex Bridge the Android permission:

```text
Android Settings -> Apps -> Codex Bridge -> Permissions
-> Additional permissions -> Run commands in Termux environment
```

Codex Bridge auto-checks Termux controls when its foreground service starts and
remembers a successful check. If controls are missing or show setup required,
open Codex Bridge and tap `Check Termux Controls` to refresh the check.

On Android 10+, Termux may require `Draw over other apps` for foreground
commands such as `Start / Talk` and `Attach` to open immediately from a
notification button. Without it, Android may require tapping the Termux
notification before the terminal session becomes visible.

When available, the notification buttons map to:

```text
Start / Talk -> stts talk
Wake Word    -> stts wake
Stop         -> stts stop, plus immediate shim-side audio cancel
```

Use the Termux:Widget list for `STTS: Attach Session`.

`STTS: Idle` in the Codex Bridge notification is normal between turns. The
persistent `ccva-stts` tmux session can be ready while no `/v1/text-voice`
client is connected.

## Rollback

Each deploy creates a backup archive on the device and updates:

```text
$HOME/codex-backup-before-latest.tar.gz
```

Manual rollback from Termux:

```sh
cd "$PREFIX"
rm -rf libexec/codex-cli-voice-android opt/codex-termux
rm -f bin/codex bin/codex-api bin/codex-voice bin/codex-install-stts
tar -xzf "$HOME/codex-backup-before-latest.tar.gz" -C "$PREFIX"
```

## Smoke Tests

The deploy helper runs these non-paid checks remotely:

```sh
codex --version
codex-api --version
codex-voice --allow-realtime --version
codex exec --help >/dev/null
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" status
```

It also verifies that an unguarded `codex-voice` launch exits before starting billable realtime usage.

On a clean device, `codex exec` with a real prompt requires a configured Codex
login or API key. Treat a `401 Unauthorized` as a credential/setup issue, not as
an install failure, after `codex --version` and `codex exec --help` pass.

For release validation, also verify the user-facing shortcut path:

```text
Termux:Widget -> Codex -> sign in if needed
Termux:Widget -> STTS: Start + Talk
Termux:Widget -> STTS: Wake Word
```

Expected result: `Codex` opens a stable tmux session, STTS can speak/listen, and
wake word detection can start a local STTS turn after Bridge is running.

## Termux:API

The Termux `termux-api` package is only the CLI side. Install the separate
Android `Termux:API` app from F-Droid for volume, TTS, STT, and diagnostics.
Verify it before `$stts` validation:

```sh
pm path com.termux.api
timeout 8 termux-api-start
timeout 8 termux-volume
timeout 8 sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" status
```

If `termux-volume` hangs, install or open the Termux:API Android app and grant
permissions.

## Shim Verification

Stage locally built or pre-release shim APKs into shared Downloads, not Termux
private storage:

```sh
sh scripts/install_aec_shim_apk.sh ./codex-aec-shim-debug.apk
```

If Android's installer does not appear, open the staged APK from the Android
Downloads app. After install, open the shim app from Android, grant microphone
permission, and verify the service:

```sh
python3 - <<'PY'
import socket
s = socket.socket()
s.settimeout(2)
try:
    s.connect(("127.0.0.1", 8765))
    print("port-open")
finally:
    s.close()
PY
python3 scripts/smoke_text_voice_ws.py --url ws://127.0.0.1:8765/v1/text-voice
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" \
  --tts-backend shim \
  say "Shim voice smoke test."
```

The final TTS smoke requires user audible confirmation.

## Developer Bridge APK Update

For developer iterations, building the Bridge APK is only a compile/package
check. It does not update the Android device. After any Bridge-side change,
install the rebuilt APK, restart the foreground service, and verify the
loopback service before user testing.

With ADB connected to the target:

```sh
adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} install -r \
  android-aec-shim/app/build/outputs/apk/debug/app-debug.apk
adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} shell am start \
  -a io.github.codex_cli_voice_android.aecshim.START_SERVICE \
  -n io.github.codex_cli_voice_android.aecshim/.MainActivity
```

Then verify from Termux on the device:

```sh
python3 - <<'PY'
import socket
s = socket.create_connection(("127.0.0.1", 8765), 5)
print("bridge_port=ok")
s.close()
PY
```

If the changed STTS Python skill is part of the same test, deploy or reinstall
`support/termux-skills/stts/scripts/stts_loop.py` as well, then run:

```sh
python -m py_compile "$HOME/.codex/skills/stts/scripts/stts_loop.py"
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" \
  wake --fake-wake --once --no-wake-cue \
  --wake-stt-timeout-seconds 1 \
  --stt-complete-silence-ms 500
```

Capture the `ccva-stts` tmux pane or session log to confirm the fake-wake path
reached the expected `no transcript` or successful turn result before moving to
a live spoken test.
