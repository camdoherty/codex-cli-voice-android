# Codex CLI Android/Termux (CCAT)

Codex CLI cross-compiled for aarch64 Android/Termux, with Android-native voice
support.

Tested on Pixel6a and Pixel9 running Android 16.

## Included

- Upstream Codex CLI built for Android/Termux.
- Codex Bridge: local Android audio bridge for native microphone/TTS integration.
- STTS: local speech-to-text / text-to-speech mode using Android speech services.
- OpenAI Realtime voice mode through `codex-voice --allow-realtime`.
- Termux:Widget shortcuts and optional notification controls.
- Wake-word entry through Codex Bridge. Pixel6a screen-off wake testing passed
  during alpha validation, but Android-wide lock-screen reliability is not
  guaranteed.

## Voice Modes

### STTS

STTS is the local voice mode.

It uses Android speech recognition and text-to-speech through Codex Bridge,
with Termux:API fallback where available.

STTS works with normal Codex authentication and does not require Realtime API billing.

```sh
stts talk
stts wake
stts stop
```

### Realtime API Voice

Realtime voice uses OpenAI's Realtime API through Codex CLI, adapted for
Android-native audio.

```sh
codex-voice --allow-realtime
```

Realtime requires API billing. It is never started by the installer.

## Install

Install Termux, Termux:API, and Termux:Widget from F-Droid in the primary
Android user/profile.

In Termux:

```sh
pkg update
apt full-upgrade
pkg install curl
curl -fsSLO https://raw.githubusercontent.com/camdoherty/codex-cli-voice-android/main/install.sh
less install.sh
sh install.sh
```

The installer verifies checksums, installs the Codex CLI Android build,
installs STTS, creates Termux:Widget shortcuts, sets up `~/codex_notes`, and
stages the Codex Bridge APK in Android Downloads.

Android approval steps remain manual: shared storage, APK install, microphone
permission, notifications, widget overlay permission, and Codex sign-in.

Or build and deploy from source.

## Launch Surfaces

Installed shortcuts:

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

Codex Bridge can also expose notification buttons:

```text
Start / Talk
Wake Word
Stop
```

On supported devices, Assistant/Gemini can launch wake-word mode with:

```text
Hey Google, open Codex Wake Word
```

Assistant/Gemini app-name resolution varies by device. `Codex Bridge` itself
opens the Bridge UI only; it does not auto-arm wake word.

## Notes

CCAT sets up a default notes workspace:

```text
~/codex_notes
```

When shared storage is available, this points to:

```text
~/storage/shared/Documents/codex_notes
```

STTS/Codex can create, read, append, open, and share Markdown notes there when
asked.

Codex Bridge exposes two Android share targets:

```text
Codex Bridge: Save to Inbox
Codex Bridge: Review Now
```

Both stage shared text, links, and small files under:

```text
~/codex_notes/inbox/
```

`Save to Inbox` records the latest shared item and shows a durable notification
with a `Review` action. `Review Now` immediately queues an STTS/tmux review and
speaks a short summary. STTS can also inspect the latest inbox item when asked
things like "what did I share?" or "review the last thing I shared."

## Why Android?

Android is a useful Codex surface because it is always nearby and has native
speech input/output, notifications, clipboard, share intents, sensors, and
access to mobile notes/files when granted.

CCAT turns the phone into a practical intake and control surface for Codex:
spoken notes, quick prompts, status checks, markdown edits, notifications, and
handoff prompts for larger agents running elsewhere.

## Agent-Assisted Deployment

Advanced users can use Codex from a PC to build, deploy, and validate CCAT on a
phone over SSH/ADB.

This is experimental. The agent can follow the documented build and deploy
flow, but Android approval prompts, Codex sign-in, APK installation,
permissions, and billing-sensitive checks still require user supervision.

Recommended model: `gpt-5.5` with medium reasoning or better.

See [AGENT_BUILD_CCVA.md](AGENT_BUILD_CCVA.md).

## Trust And Safety

CCAT is designed to be inspectable.

- Codex Bridge listens only on loopback: `127.0.0.1:8765`.
- Realtime API usage requires explicit `--allow-realtime`.
- Release assets include checksums.
- The installer verifies downloaded assets before installing.
- The source-build path is documented for users who prefer to build and deploy
  without trusting prebuilt releases.
- STTS and Realtime are separate modes with separate cost behavior.

Android isolates app data by default. CCAT runs inside Termux and can access
files available to Termux, shared storage you grant, and anything you
explicitly pass to it.

Agent-assisted deployment is non-deterministic software automation. Review
commands, watch approval prompts, and avoid granting access to sensitive shared
folders unless needed.

## Build From Source

```sh
git clone https://github.com/camdoherty/codex-cli-voice-android.git
cd codex-cli-voice-android
scripts/release_build.sh v0.135.0-ccva.1
```

See [BUILD.md](BUILD.md).

## Documentation

- [VOICE_MODES.md](VOICE_MODES.md): STTS, Realtime, wake word, and launch
  behavior.
- [DEPLOY.md](DEPLOY.md): install, update, validation, and troubleshooting.
- [BUILD.md](BUILD.md): build and release pipeline.
- [AGENT_BUILD_CCVA.md](AGENT_BUILD_CCVA.md): agent-assisted source build and
  deploy.
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md): common Android/Termux setup issues.

## License

This project is licensed under Apache-2.0. Upstream Codex is also Apache-2.0;
see the upstream repository for its full source and notices.
