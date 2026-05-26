# Agent Build Guide

This guide is for users who want a Codex, Claude, or similar coding agent to
build and deploy Codex CLI Voice Android from source instead of trusting release
assets.

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
- Add the `$stts` skill for Plus-friendly half-duplex local voice.
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
- `$stts` provides cheap, walkie-talkie-like interaction with a Plus account.
- `codex-voice --allow-realtime` exposes OpenAI Codex CLI Realtime voice mode
  with Android native audio when the user explicitly accepts Realtime billing.

## Agent Operating Rules

Before changing the user's machine or phone, ask for approval for:

- Installing packages or toolchains.
- Downloading upstream source or Android toolchain components.
- Connecting to an Android device over SSH.
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

## Build From Source

On a Linux build host:

```bash
git clone https://github.com/camdoherty/codex-cli-voice-android.git
cd codex-cli-voice-android
rustup target add aarch64-linux-android
scripts/setup_android_toolchain.sh
```

Build the Android shim APK:

```bash
scripts/build_aec_shim_apk.sh
```

Build the Codex CLI Termux package:

```bash
# Install Android NDK r29 separately, then point the build at it.
export ANDROID_NDK_HOME=/path/to/android-ndk-r29
./build.sh
```

Expected outputs:

```text
codex-cli-voice-android-rust-v0.134.0-ccva.1.tar.gz
codex-cli-voice-android-rust-v0.134.0-ccva.1.tar.gz.sha256
codex-cli-voice-android-rust-v0.134.0-ccva.1.tar.gz.metadata
android-aec-shim/app/build/outputs/apk/debug/app-debug.apk
```

Quick local checks:

```bash
bash -n build.sh scripts/*.sh
tar -tzf codex-cli-voice-android-rust-v0.134.0-ccva.1.tar.gz >/dev/null
sha256sum -c codex-cli-voice-android-rust-v0.134.0-ccva.1.tar.gz.sha256
```

## Deploy With SSH

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
  codex-cli-voice-android-rust-v0.134.0-ccva.1.tar.gz \
  codex-cli-voice-android-rust-v0.134.0-ccva.1.tar.gz.sha256
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
  say "Codex CLI Voice Android smoke test."
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
