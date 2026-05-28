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
  codex-cli-voice-android-rust-v0.134.0-ccva.1.tar.gz \
  codex-cli-voice-android-rust-v0.134.0-ccva.1.tar.gz.sha256
```

The script refuses to continue if the remote checksum differs.

For a first install on a clean Termux device:

```bash
ALLOW_FRESH_INSTALL=1 scripts/deploy_termux_package.sh \
  codex-cli-voice-android-rust-v0.134.0-ccva.1.tar.gz \
  codex-cli-voice-android-rust-v0.134.0-ccva.1.tar.gz.sha256
```

The deploy also installs or updates:

```text
$HOME/.codex/skills/stts
$HOME/scripts/codex-api
$HOME/scripts/codex-voice
$HOME/scripts/codex-install-stts
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
STTS: Attach Session
STTS: Start + Talk
STTS: Stop
```

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

## Optional Notification Controls

Codex Bridge can show STTS notification buttons only when Termux allows
external command execution. The bridge hides those buttons until setup is
available, so widgets and shell commands remain the default reliable controls.

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

Open Codex Bridge and tap `Check Termux Controls` once. The bridge does not run
periodic Termux command probes.

On Android 10+, Termux may require `Draw over other apps` for foreground
commands such as `Start / Talk` and `Attach` to open immediately from a
notification button. Without it, Android may require tapping the Termux
notification before the terminal session becomes visible.

When available, the notification buttons map to:

```text
Start / Talk -> stts talk
Attach       -> stts session
Stop         -> stts stop, plus immediate shim-side audio cancel
```

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
