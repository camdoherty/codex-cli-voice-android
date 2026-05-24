# Codex CLI + Voice (Android Port)

Codex CLI cross-compiled for `aarch64-linux-android` with an Android AEC shim for realtime, native hardware audio.

Status: alpha. The current validated target is upstream Codex `rust-v0.133.0`. The package has been tested on Android with Termux on a Pixel-class ARM64 device; other devices are untested.

This repository contains build scripts, Android patches, a Termux packaging layout, deployment helpers, and the native Android audio shim. It does not vendor the upstream Codex source tree.

Requires an OpenAI Plus account or API key for Codex chat.

## Voice Modes

| Mode | Command | Cost profile | Best for |
| --- | --- | --- | --- |
| Local Half-Duplex Voice | `$tts-stt start` or `tts-stt-start` | Works with Plus login; no API key required for the voice path | Cheap walkie-talkie-like agent sessions |
| Realtime Voice | `codex-voice --allow-realtime` | Uses OpenAI Realtime API billing | Native low-latency OpenAI voice |

Local Half-Duplex Voice uses the Android shim `/v1/text-voice` endpoint first:
Android `TextToSpeech` for spoken output and Android `SpeechRecognizer` for
one-shot speech input. Termux API speech commands remain fallback paths.

Realtime Voice uses the shim `/v1/audio` endpoint and streams audio through the
OpenAI Realtime API. The launcher refuses to start unless realtime billing is
explicitly allowed.

See [VOICE_MODES.md](VOICE_MODES.md) for details.

## What This Builds

- `codex`: upstream Codex CLI, cross-compiled for Termux/Android.
- `codex-api`: launcher that loads an OpenAI API key from `OPENAI_API_KEY` or `OPENAI_API_KEY_FILE`.
- `codex-voice`: guarded realtime launcher for native Android audio through the AEC shim.
- `codex-install-tts-stt`: installs or updates the local `$tts-stt` skill with backup.
- `codex-aec-shim-debug.apk`: Android app/service that exposes native capture/playback to Codex over a local WebSocket.

The Termux package installs under `$PREFIX/libexec/codex-cli-voice-android/` and exposes launchers in `$PREFIX/bin`.

## Important Cost Note

`codex-voice` uses the OpenAI Realtime API. The launcher intentionally refuses to start unless you pass `--allow-realtime` or set `CODEX_VOICE_ALLOW_REALTIME=1`.

## Manual Installation (On-Device)

If you are installing directly on Android with no PC:

1. Install [Termux](https://f-droid.org/packages/com.termux/) from F-Droid.
2. Install [Termux:API](https://f-droid.org/packages/com.termux.api/) from
   F-Droid if you want Termux fallback TTS/STT and diagnostics.
3. Download the latest release assets to your phone's Downloads folder:
   - `codex-cli-voice-android-rust-vX.X.X.tar.gz`
   - `codex-cli-voice-android-rust-vX.X.X.tar.gz.sha256`
   - `codex-aec-shim-debug.apk`, required for realtime voice and preferred
     local half-duplex voice
4. Open Termux and install the CLI:

```sh
termux-setup-storage
cd "$HOME/storage/downloads"
sha256sum -c codex-cli-voice-android-rust-v*.tar.gz.sha256
tar -xzf codex-cli-voice-android-rust-v*.tar.gz -C "$PREFIX"
codex-install-tts-stt
codex --version
```

For Local Half-Duplex Voice, install the AEC shim APK from Android Downloads,
open the shim app from Android, grant microphone permission, and confirm the
local service is listening:

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

Then start a session with one of:

```sh
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" start
```

or ask Codex to use:

```text
$tts-stt start
```

For Realtime Voice, use:

```sh
codex-voice --allow-realtime
```

`codex-voice` uses the OpenAI Realtime API and is billable. The launcher refuses to start without the explicit `--allow-realtime` guard.

## Repository Guide

- [BUILD.md](BUILD.md): host setup and build commands.
- [DEPLOY.md](DEPLOY.md): safe SSH deploy, rollback backup, and smoke tests.
- [VOICE_MODES.md](VOICE_MODES.md): voice mode chooser, commands, and cost boundaries.
- [AUDIO_SHIM.md](AUDIO_SHIM.md): Android AEC shim build/install/runtime notes.
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md): known issues and quick checks.

## License

This project is licensed under Apache-2.0. Upstream Codex is also Apache-2.0; see the upstream repository for its full source and notices.
