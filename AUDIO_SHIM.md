# Android AEC Shim

The AEC shim is a small Android app/service that provides native microphone
capture and speaker playback for OpenAI Codex CLI Realtime voice sessions on
Android. Codex connects to it over a loopback WebSocket:

```text
codex-voice -> ws://127.0.0.1:8765/v1/audio -> Android AudioRecord/AudioTrack
```

The shim package id is:

```text
io.github.codex_cli_voice_android.aecshim
```

## Voice Modes

The shim now exposes two separate local WebSocket paths:

```text
ws://127.0.0.1:8765/v1/audio
ws://127.0.0.1:8765/v1/text-voice
```

`/v1/audio` is the paid realtime path. It streams PCM audio between Codex CLI
and the Android native audio engine for OpenAI Realtime sessions.

`/v1/text-voice` is the local half-duplex path. It exchanges JSON text commands/events with the shim, uses Android `SpeechRecognizer` for one-shot STT, and uses Android `TextToSpeech` for spoken output. This path is turn-based and does not use the OpenAI Realtime API.

The Termux `tts-stt` skill uses `/v1/text-voice` as its preferred local TTS
backend and now supports shim STT through `--stt-backend auto|shim|termux`.
Default `auto` tries shim STT first and falls back to `termux-speech-to-text -p`
only when shim STT is unavailable before listening starts. Forced full-shim mode
is:

```sh
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" \
  --tts-backend shim \
  --stt-backend shim \
  start
```

Direct Termux API TTS is retained as a fallback because local Android evidence
showed `termux-tts-speak` can hang or be silent while shim TTS remains audible.

## Build

```bash
scripts/setup_android_toolchain.sh
scripts/build_aec_shim_apk.sh
```

## Install From Release

For normal use, download `codex-aec-shim-debug.apk` from the GitHub Release to
Android Downloads and install it with Android's package installer.

After installing, open the app from Android and grant microphone permission.
Starting an Android intent from Termux is not sufficient proof that the UI
opened or the service started; verify the loopback port from Termux.

The service should listen on:

```text
ws://127.0.0.1:8765/v1/audio
```

Use this check:

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

## Install Locally Built APK

Install the generated debug APK manually or upload it with:

```bash
cp .env.example .env
$EDITOR .env
scripts/upload_aec_shim_apk.sh
```

The FTP upload script requires `FTP_HOST` and `FTP_USER` in `.env` or the environment.

## Run OpenAI Codex Realtime Voice

In Termux:

```sh
codex-voice --allow-realtime
```

or:

```sh
CODEX_VOICE_ALLOW_REALTIME=1 codex-voice
```

If you use a non-default shim URL:

```sh
CODEX_ANDROID_AUDIO_WS_URL=ws://127.0.0.1:8765/v1/audio codex-voice --allow-realtime
```

## Smoke Local Text Voice

After the app service is running, a dependency-free client can verify the text bridge from Termux:

```sh
python3 scripts/smoke_text_voice_ws.py --url ws://127.0.0.1:8765/v1/text-voice
```

To test Android TTS through the shim:

```sh
python3 scripts/smoke_text_voice_ws.py --tts "Ready for your next instruction."
```

The on-device skill can test the same preferred TTS path directly:

```sh
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" \
  --tts-backend shim \
  say "Skill speech is routed through the shim."
```

## Autonomous Kokoro STT Playback Test

For evidence-backed STT tuning, the devbox can synthesize Kokoro clips, wait for
`stt_listening`, play the clip through speakers near the phone, capture all shim
frames, and score transcript word recall:

```sh
ssh -N -L 18765:127.0.0.1:8765 android-device-ssh-alias
/path/to/python-with-kokoro scripts/autotest_text_voice_kokoro.py \
  --url ws://127.0.0.1:18765/v1/text-voice \
  --case-set smoke
```

If the Kokoro model is not in a default local path, pass:

```sh
scripts/autotest_text_voice_kokoro.py \
  --kokoro-model /path/to/kokoro-v1.0.onnx \
  --kokoro-voices /path/to/voices-v1.0.bin
```

The harness writes generated clips, frame JSONL, and `summary.json` to a
timestamped `/tmp/codex-text-voice-kokoro-*` directory by default. Use
`--case-set expanded` after the smoke case passes.

## Testing The Termux `tts-stt` Skill

The local Termux skill is mirrored in this repo at
`support/termux-skills/tts-stt`. Its live phone path is
`$HOME/.codex/skills/tts-stt`.

Use the repeatable process in
`support/termux-skills/tts-stt/references/testing-process.md` for full
validation. That process covers Kokoro fixture generation, no-speech listener
baseline measurement, raw STT calibration, full multi-turn validation, evidence
capture, and cleanup.

A minimal raw STT calibration command is:

```sh
python3 scripts/autotest_termux_tts_stt_skill.py \
  --ssh-target android-device-ssh-alias \
  --settle-ms 1000 \
  --remote-command 'PYTHONUNBUFFERED=1 timeout 45 sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" --stt-backend shim stt-check --post-speech-delay 0' \
  --clip /tmp/codex-text-voice-kokoro-expanded-fixtures/clips/01-smoke_current_task.wav \
  --expected-file /tmp/codex-text-voice-kokoro-expanded-fixtures/clips/01-smoke_current_task.txt
```

This harness starts `tts-stt-session.sh stt-check` over SSH with unbuffered
output, waits for `status: listening for raw STT`, waits `--settle-ms`, plays
the clip from the devbox speakers, captures the transcript, and reports word
recall. After raw STT passes, run the full multi-turn test documented in the
testing process. The current full-loop target uses shim TTS, shim STT, and a
3-second post-TTS recovery gap before re-arming STT because immediate post-TTS
recognition was less reliable in earlier tests.

## Current Tuning Notes

- The shim is functional but should be considered alpha-quality audio.
- Realtime sessions are billable; use short validation prompts while tuning.
- The shim binds to loopback on `127.0.0.1:8765`; do not expose it through a
  public tunnel.
- If output sounds sped up or distorted, retest with the latest shim APK and confirm the installed package matches the current build.
- The local text-voice path is half-duplex and is the supported `$tts-stt`
  voice path for this release.
