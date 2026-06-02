# Agent Build Guide

This guide is for users who want a Codex, Claude, or similar coding agent to
build and deploy Codex CLI Android/Termux (CCAT) from source instead of
trusting release assets.

Android with Termux is effectively a Linux machine with phone hardware,
Android permissions, and Termux integration. The point of this project is not
only that Codex CLI runs on Android. The useful part is that Android becomes a
natural mobile intake surface for user intent, notes, project context, and
delegation to other agents.

## What Changed From Upstream Codex

This repo does not vendor upstream Codex. It fetches upstream Codex and applies
small Android/Termux patches.

Main changes:

- Cross-compile upstream Codex CLI for `aarch64-linux-android`.
- Package the binary for Termux with wrapper launchers.
- Patch Android build/runtime issues such as Cargo config, workspace metadata,
  file locking, URL opening, native TLS/Sentry constraints, and Android V8
  stubs.
- Add `codex-api` and `codex-voice` launchers for Termux.
- Add a guarded OpenAI Codex CLI Realtime voice path through Android native
  audio.
- Add the `$stts` skill for half-duplex local voice with normal Codex
  authentication and no Realtime API billing.
- Add the Android AEC shim APK for local loopback audio endpoints:
  `127.0.0.1:8765/v1/audio` and `127.0.0.1:8765/v1/text-voice`.
- Add deployment, smoke-test, and Termux:Widget shortcut scripts.

Why:

- Upstream Codex targets normal desktop/server environments.
- Android needs cross-compilation, Termux paths, Android-compatible runtime
  behavior, and explicit audio integration.
- Voice on a phone needs Android microphone/speaker APIs, permissions, and
  observable local service checks.

## Why Android Is Interesting

Android is a practical always-nearby agent intake surface:

- Voice notes and spoken user intent can become structured Codex prompts.
- Termux:API can expose notifications, dialogs, share/open intents, clipboard,
  battery, location, sensors, and device context.
- The phone can act as a lightweight orchestrator or intent translator for
  other agents, including todo/action delegation workflows.
- Markdown repos, Obsidian vaults, and project notes can be maintained from the
  same device used for capture.
- `$stts` provides walkie-talkie-like local interaction without Realtime API
  billing.
- `codex-voice --allow-realtime` exposes OpenAI Codex CLI Realtime voice mode
  with Android native audio when the user explicitly accepts Realtime billing.

## Agent Operating Rules

Before changing the user's machine or phone, ask for approval for:

- Installing packages or toolchains.
- Downloading upstream source or Android toolchain components.
- Establishing or connecting to Android Termux over SSH.
- Copying artifacts to a device.
- Installing or opening the shim APK.
- Running `codex-voice --allow-realtime`.

Do not ask for secrets in chat. If credentials are needed, ask the user to
configure them locally, for example with Codex login or a local `.oaienv`.

Do not assume Android UI actions succeeded. Verify observable state:

- Package installed with `pm path`.
- Termux:API app and CLI both work.
- Shim port `127.0.0.1:8765` is open.
- `/v1/text-voice` smoke test passes.
- Audible TTS is confirmed by the user.

## Questions For The User

Ask only what cannot be discovered safely:

- Which Android device should be targeted?
- Is Termux installed from F-Droid?
- Is Termux:API installed from F-Droid?
- Is SSH enabled in Termux, or should deployment be manual/on-device?
- Should the agent build locally, deploy to a phone, or only verify the repo?
- Is Realtime voice testing approved as a billable check?

If SSH/device access is declined, provide the manual install path from
[README.md](README.md) and keep the build local.

## Agent-Assisted Install Paths

Prefer the least invasive path that can prove the user's goal:

1. Public release install, ordinary user path:
   - User installs Termux, Termux:API, and Termux:Widget from F-Droid.
   - User opens Termux once and runs the installer from [README.md](README.md).
   - Agent reviews output, asks for Android approvals when needed, and verifies
     smoke tests.
2. SSH-assisted Termux install:
   - Use when Termux SSH is already working, or when the user approves setting
     it up during install.
   - Agent runs on-device commands over SSH and avoids ad hoc source copying
     unless testing unpublished artifacts.
   - On fresh/staging installs, SSH should be established early after Termux
     bootstrap so the agent can verify `~/.termux/termux.properties`, staged
     files, checksums, and smoke tests without relying on brittle ADB text
     input.
3. ADB-assisted staging install:
   - Use for maintainer/test devices that need repeatable clean validation.
   - ADB may uninstall/reset apps, launch F-Droid, install the Bridge APK, grant
     grantable runtime permissions, start Bridge, and capture screenshots/logs.
   - ADB does not remove the need for visible Android/F-Droid approval when
     Android requires it.
4. Manual fallback:
   - If SSH/ADB access is declined or unreliable, provide exact commands and ask
     the user to run them in Termux.

Failsafes:

- Back up Termux home before any uninstall/reset.
- Use the primary Android user/profile. Secondary users and work profiles can
  fail Termux bootstrap because Termux packages are built for the primary-user
  `$PREFIX` path.
- Do not disable Android package verification globally.
- Do not assume `termux-open`, `am start`, or a share intent succeeded; verify
  package state, Bridge service state, and loopback port availability.
- On fresh Termux, run `pkg update`, `apt full-upgrade`, and install `curl`
  before fetching the public installer.
- If SSH is approved, password SSH is acceptable for the first supervised
  connection. Do not ask for the password in chat. Prefer adding the user's
  approved public key to `~/.ssh/authorized_keys` before automation so the
  agent can run non-interactive verification commands.
- Treat `codex exec` authentication failures as setup issues after
  `codex --version` and `codex exec --help` pass.
- Ask the user to tap the `Codex` shortcut and complete sign-in before judging
  STTS reply generation.
- Keep Realtime tests explicit and billable-opt-in only.
- For Termux:Widget launchers on Android 10+, require Termux `Display over other
  apps` / `Draw over other apps` permission before judging widgets broken.
- For Codex Bridge notification buttons, verify Termux
  `allow-external-apps=true` and the Codex Bridge Android permission
  `Run commands in Termux environment`. The installer, SSH deploy helper, and
  launcher refresh script set the Termux property automatically.
- Preserve `~/codex_notes`; it is the user notes workspace. On standard
  installs it should point to `~/storage/shared/Documents/codex_notes` when
  shared storage is available.

## Agent Handoff Checklist

For a public-release fresh install, verify this sequence:

1. Termux, Termux:API, and Termux:Widget are installed from F-Droid in the
   primary Android user/profile.
2. Fresh Termux has run `pkg update`, `apt full-upgrade`, and `pkg install curl`.
3. The public installer from [README.md](README.md) completed with checksum
   verification.
4. Codex Bridge is installed, opened, permissioned, running, and listening on
   `127.0.0.1:8765`.
5. Termux has `Display over other apps` for widget-launched terminal sessions.
6. Bridge notification controls do not report a RunCommandService
   `allow-external-apps` setup error.
7. The user tapped `Codex` and completed Codex sign-in.
8. `codex --version`, `codex exec --help`, and `stts-diag` pass.
9. `~/codex_notes` exists and is Android-visible when shared storage is
   available.
10. `STTS: Start + Talk` works.
11. A simple note request can create/read/append a Markdown note in
    `~/codex_notes`.
12. `STTS: Wake Word` works if WWS is in scope.
13. `codex-voice --allow-realtime` is tested only with explicit billable
    approval.

## Build From Source

On a Linux build host:

```bash
git clone https://github.com/camdoherty/codex-cli-voice-android.git
cd codex-cli-voice-android
rustup target add aarch64-linux-android
scripts/setup_android_toolchain.sh
```

For a publishable release candidate, use the release pipeline:

```bash
scripts/release_prepare.sh rust-v0.136.0 --iteration 1
scripts/release_build.sh v0.136.0-ccva.2
```

Expected release outputs are under `dist/<release-tag>/`:

```text
dist/v0.136.0-ccva.2/codex-cli-voice-android-rust-v0.136.0-ccva.2.tar.gz
dist/v0.136.0-ccva.2/codex-cli-voice-android-rust-v0.136.0-ccva.2.tar.gz.sha256
dist/v0.136.0-ccva.2/codex-cli-voice-android-rust-v0.136.0-ccva.2.tar.gz.metadata
dist/v0.136.0-ccva.2/codex-aec-shim-v0.136.0-ccva.2-debug.apk
dist/v0.136.0-ccva.2/codex-aec-shim-v0.136.0-ccva.2-debug.apk.sha256
dist/v0.136.0-ccva.2/v0.136.0-ccva.2.json
```

For lower-level local iteration, build the Android shim APK directly:

```bash
scripts/build_aec_shim_apk.sh
```

Build an un-suffixed local Codex CLI Termux package:

```bash
# Install Android NDK r29 separately, then point the build at it.
export ANDROID_NDK_HOME=/path/to/android-ndk-r29
./build.sh
```

Expected local outputs:

```text
codex-cli-voice-android-rust-v0.136.0.tar.gz
codex-cli-voice-android-rust-v0.136.0.tar.gz.sha256
codex-cli-voice-android-rust-v0.136.0.tar.gz.metadata
android-aec-shim/app/build/outputs/apk/debug/app-debug.apk
```

Quick local checks:

```bash
bash -n build.sh scripts/*.sh
tar -tzf codex-cli-voice-android-rust-v0.136.0.tar.gz >/dev/null
sha256sum -c codex-cli-voice-android-rust-v0.136.0.tar.gz.sha256
```

For a release candidate, prefer:

```bash
scripts/release_doctor.sh v0.136.0-ccva.2
```

## Deploy With SSH

Only set up SSH with explicit user approval. SSH is strongly recommended for
agent-assisted installs because it lets the agent verify Termux private files,
checksums, and smoke tests without depending on fragile ADB text entry.

On the Android device, after Termux bootstrap and package updates:

```sh
pkg install openssh
passwd
sshd
whoami
```

Password SSH is enough for a supervised first connection:

```sh
ssh -p 8022 termux-user@android-host 'echo ssh-ok; whoami; uname -m'
```

For repeatable agent work, prefer a dedicated CCVA device key generated and
owned by the user/workstation, for example `~/.ssh/id_ed25519_ccva_pixel6a`.
Add only its public key to Termux:

```sh
mkdir -p ~/.ssh
chmod 700 ~/.ssh
printf '%s\n' 'PASTE_APPROVED_WORKSTATION_PUBLIC_KEY_HERE' >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

Then verify non-interactive SSH before using it for deployment. Example:

```sh
ssh pixel6a-ccva 'echo ssh-ok; whoami; hostname'
```

If this fails after a fresh Termux reinstall, remove only the stale host entry
for that device/port from `known_hosts`, confirm the new fingerprint with the
user, and retry. Do not disable host-key checking globally.

Configure `.env`:

```bash
cp .env.example .env
$EDITOR .env
```

Set:

```text
PIXEL_HOST=android-device-host-or-ip
PIXEL_USER=termux-ssh-user
PIXEL_PORT=8022
PIXEL_IDENTITY=$HOME/.ssh/id_ed25519
```

Deploy the CLI package:

```bash
ALLOW_FRESH_INSTALL=1 scripts/deploy_termux_package.sh \
  dist/v0.136.0-ccva.2/codex-cli-voice-android-rust-v0.136.0-ccva.2.tar.gz \
  dist/v0.136.0-ccva.2/codex-cli-voice-android-rust-v0.136.0-ccva.2.tar.gz.sha256
```

Or use the release validation wrapper:

```bash
scripts/release_validate_device.sh v0.136.0-ccva.2 --fresh --target Pixel6a
```

Stage the shim APK to Android Downloads from inside Termux. If the APK was
built on the host, first transfer `app-debug.apk` to the phone, or use a synced
repo/release asset path that exists on the phone.

```sh
sh scripts/install_aec_shim_apk.sh \
  /path/on/phone/to/app-debug.apk
```

If Android's installer does not open, ask the user to open the APK from
Downloads and install it manually. Then ask the user to open the shim app and
grant microphone permission.

## Smoke Tests

On the phone in Termux:

```sh
codex --version
codex-api --version
codex-voice --allow-realtime --version
codex-install-stts
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" status
```

Verify Termux:API:

```sh
pm path com.termux.api
timeout 8 termux-api-start
timeout 8 termux-volume
```

Verify the shim:

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
```

From a cloned repo on the phone:

```sh
python3 scripts/smoke_text_voice_ws.py --url ws://127.0.0.1:8765/v1/text-voice
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" \
  --tts-backend shim \
  say "Codex CLI Android Termux smoke test."
```

Ask the user to confirm they heard the final TTS smoke.

Only run the billable Realtime check with explicit approval:

```sh
codex-voice --allow-realtime
```

## Acceptance Criteria

The build/deploy is successful when:

- The CLI tarball builds and checksum verifies.
- The shim APK builds or release APK checksum verifies.
- `codex --version` reports the expected Codex version on Android.
- `$stts` is installed and reports status.
- Termux:API is installed as both the Android app and Termux package.
- The shim package is installed, the microphone permission is granted, and
  `127.0.0.1:8765` is open.
- `/v1/text-voice` smoke passes.
- The user confirms audible TTS.
- Unguarded `codex-voice` exits before starting Realtime billing.
- Billable Realtime is tested only if the user explicitly approves it.

## Troubleshooting Pointers

- If `termux-volume` hangs, install or open the Termux:API Android app and
  grant permissions.
- If the APK installer does not open, install the staged APK from Android
  Downloads manually.
- If the shim appears installed but voice does not work, verify the loopback
  port and run `/v1/text-voice` before testing Realtime.
- If `codex exec` returns `401 Unauthorized`, treat it as a local credential
  setup issue after `codex --version` and `codex exec --help` pass.
- Do not use `/tmp` for important Termux output. Prefer `$HOME` because Android
  app sandbox behavior can differ from a normal Linux host.
