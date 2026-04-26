# Codex CLI + Voice (Android Port)

Codex CLI cross-compiled for `aarch64-linux-android` with an Android AEC shim for realtime, native hardware audio.

Status: alpha. The current validated target is upstream Codex `rust-v0.125.0`. The package has been tested on Android with Termux on a Pixel-class ARM64 device; other devices are untested.

This repository contains build scripts, Android patches, a Termux packaging layout, deployment helpers, and the native Android audio shim. It does not vendor the upstream Codex source tree.

Requires OpenAI Plus account or API key for chat.
An API key is currently required for realtime voice mode since Plus does not expose streaming a

termux-api can be used for speech-to-text and text-to-speech for half-duplex (walkie-talkie-like) voice interaction.

## What This Builds`

- `codex`: upstream Codex CLI, cross-compiled for Termux/Android.
- `codex-api`: launcher that loads an OpenAI API key from `OPENAI_API_KEY` or `OPENAI_API_KEY_FILE`.
- `codex-voice`: guarded realtime launcher for native Android audio through the AEC shim.
- `codex-aec-shim-debug.apk`: Android app/service that exposes native capture/playback to Codex over a local WebSocket.

The Termux package installs under `$PREFIX/libexec/codex-cli-voice-android/` and exposes launchers in `$PREFIX/bin`.

## Important Cost Note

`codex-voice` uses the OpenAI Realtime API. The launcher intentionally refuses to start unless you pass `--allow-realtime` or set `CODEX_VOICE_ALLOW_REALTIME=1`.

## Manual Installation (On-Device)

If you are installing directly on Android with no PC:

1. Install [Termux](https://f-droid.org/packages/com.termux/) from F-Droid.
2. Download the latest release assets to your phone's Downloads folder:
   - `codex-cli-voice-android-rust-vX.X.X.tar.gz`
   - `codex-cli-voice-android-rust-vX.X.X.tar.gz.sha256`
   - `codex-aec-shim-debug.apk`, only required for `codex-voice`
3. Open Termux and install the CLI:

```sh
termux-setup-storage
cd "$HOME/storage/downloads"
sha256sum -c codex-cli-voice-android-rust-v*.tar.gz.sha256
tar -xzf codex-cli-voice-android-rust-v*.tar.gz -C "$PREFIX"
codex --version
```

For voice mode, install `codex-aec-shim-debug.apk` with Android's package installer, open the app once, grant microphone permission, then run:

```sh
codex-voice --allow-realtime
```

`codex-voice` uses the OpenAI Realtime API and is billable. The launcher refuses to start without the explicit `--allow-realtime` guard.

## Repository Guide

- [BUILD.md](BUILD.md): host setup and build commands.
- [DEPLOY.md](DEPLOY.md): safe SSH deploy, rollback backup, and smoke tests.
- [AUDIO_SHIM.md](AUDIO_SHIM.md): Android AEC shim build/install/runtime notes.
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md): known issues and quick checks.

## License

This project is licensed under Apache-2.0. Upstream Codex is also Apache-2.0; see the upstream repository for its full source and notices.
