# Deploy

The deployment helper is designed for safe iteration on a Termux target. It
uploads the package, verifies the remote SHA256, creates a rollback archive,
extracts into `$PREFIX`, installs the `$tts-stt` skill, repairs launcher
symlinks, and runs non-paid smoke tests.

For a no-PC, on-device install from GitHub Releases, use the manual installation section in [README.md](README.md).

For agent-driven device work, prefer this flow:

1. Commit or otherwise stage source changes on the devbox.
2. Pull/sync the repo on the Android device when possible.
3. Run on-device install scripts from the synced repo or release assets.
4. Use SSH for commands and verification; avoid ad hoc file copying except for
   pre-release build artifacts that do not exist on GitHub yet.

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
  codex-cli-voice-android-rust-v0.133.0.tar.gz \
  codex-cli-voice-android-rust-v0.133.0.tar.gz.sha256
```

The script refuses to continue if the remote checksum differs.

For a first install on a clean Termux device:

```bash
ALLOW_FRESH_INSTALL=1 scripts/deploy_termux_package.sh \
  codex-cli-voice-android-rust-v0.133.0.tar.gz \
  codex-cli-voice-android-rust-v0.133.0.tar.gz.sha256
```

The deploy also installs or updates:

```text
$HOME/.codex/skills/tts-stt
$HOME/scripts/codex-api
$HOME/scripts/codex-voice
$HOME/scripts/codex-install-tts-stt
```

## Rollback

Each deploy creates a backup archive on the device and updates:

```text
$HOME/codex-backup-before-latest.tar.gz
```

Manual rollback from Termux:

```sh
cd "$PREFIX"
rm -rf libexec/codex-cli-voice-android opt/codex-termux
rm -f bin/codex bin/codex-api bin/codex-voice bin/codex-install-tts-stt
tar -xzf "$HOME/codex-backup-before-latest.tar.gz" -C "$PREFIX"
```

## Smoke Tests

The deploy helper runs these non-paid checks remotely:

```sh
codex --version
codex-api --version
codex-voice --allow-realtime --version
codex exec --help >/dev/null
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" status
```

It also verifies that an unguarded `codex-voice` launch exits before starting billable realtime usage.
