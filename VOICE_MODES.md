# Voice Modes

Codex CLI Android/Termux (CCAT) ships two validated voice modes with different
goals and cost profiles. `$stts` is the local Android STT/TTS mode. `codex-voice
--allow-realtime` is OpenAI Codex CLI Realtime voice mode adapted for Android
native audio, and uses OpenAI Realtime API billing.

## Quick Chooser

Use **Local Half-Duplex Voice** for mobile voice intake and normal agent work
without Realtime API billing. `stts talk` starts the persistent `ccva-stts`
tmux session if needed, listens once, and returns to ready.

```sh
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" talk
```

or ask Codex:

```text
$stts talk
```

Use **OpenAI Codex Realtime Voice** only when you want Codex CLI's realtime
voice experience on Android native audio and accept Realtime API billing:

```sh
codex-voice --allow-realtime
```

## Local Half-Duplex Voice

`$stts` is a walkie-talkie-like voice mode that uses normal Codex
authentication. No Realtime API billing is required for the voice path.

STTS runs Codex turns through `codex exec` with `gpt-5.4-mini` and
`model_reasoning_effort="low"` by default to keep voice turns faster and
lower cost. Advanced users can override this with `CODEX_STTS_CODEX_MODEL` and
`CODEX_STTS_CODEX_REASONING_EFFORT`.

Default path:

```text
stts -> ws://127.0.0.1:8765/v1/text-voice -> Android TextToSpeech/SpeechRecognizer
```

Fallback path:

```text
stts -> termux-tts-speak / termux-speech-to-text
```

Start surfaces:

- Agent: `$stts talk`
- Command: `sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" talk`
- Termux:Widget friendly label: `STTS: Start + Talk`
- Wake word Termux:Widget label: `STTS: Wake Word`
- Session-only command: `stts session`
- Experimental continuous loop: `stts loop`

Command behavior:

- `stts` / `stts talk`: run one speech-to-text turn against the active session history.
- `stts session`: open or attach the persistent `ccva-stts` tmux workspace without listening.
- `stts wake`: arm Hey Jarvis/PTT-triggered turns in the persistent `ccva-stts`
  tmux session.
- `stts loop`: run the older continuous auto-listen loop. This is useful for
  testing, but may repeatedly trigger Android SpeechRecognizer beeps.
- `stts stop`: stop the active session and voice helpers.

Codex Bridge notification states:

- `STTS: Idle`: Bridge is running and no STTS turn is connected right now.
- `STTS: Ready`: STTS is connected or wake/PTT is armed.
- `STTS: Listening...`: Android speech recognition is active.
- `STTS: Thinking...`: Codex is generating a reply.
- `STTS: Speaking...`: Android TTS is playing the reply.

The default session safety timeout is one hour. Use `--timeout-seconds` only
for bounded tests.

Wake word notes:

- `stts wake` uses a bounded long wake-listen window instead of restarting
  every minute.
- The default wake input gain is `6 dB`, validated on Pixel6a as the current
  alpha balance for wake reliability without observed false triggers in a
  short fan/noise sanity run.
- The subtle cue means "wake detected; speak now." It is not played just
  because wake listening is armed.
- Pixel6a validation included a screen-off run through wake detection, STT,
  Codex reply generation, TTS playback, and re-arm.
- Treat screen-off behavior as device-validated alpha behavior, not a general
  Android guarantee. Android microphone, foreground-service, and
  SpeechRecognizer behavior can vary by device, OS version, and permissions.

Useful checks:

```sh
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" diag
stts-diag --download
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" say "Voice is ready."
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" stt-check
```

Stop and cleanup:

```sh
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" stop
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" cleanup
```

## OpenAI Codex Realtime Voice

`codex-voice --allow-realtime` starts OpenAI Codex CLI Realtime voice mode
adapted for Termux/Android. It uses the AEC shim native audio path:

```text
codex-voice -> ws://127.0.0.1:8765/v1/audio -> OpenAI Realtime
```

This mode requires an API key and can start OpenAI Realtime API billing. The
launcher intentionally refuses to run unless you pass:

```sh
codex-voice --allow-realtime
```

or:

```sh
CODEX_VOICE_ALLOW_REALTIME=1 codex-voice
```

Start surfaces:

- Command: `codex-voice --allow-realtime`
- Termux:Widget friendly label: `Realtime API Voice`
- Stop shortcut: `Realtime API Voice Stop`

The widget opens or attaches the stable `ccva-realtime` tmux session. It does
not start duplicate Realtime sessions when tapped again. Pane logging is enabled
by default under `~/.local/state/ccva-tmux/logs/`; set `CCVA_TMUX_LOG=0` before
launch to disable it.

Use `Realtime API Voice Stop` to stop the billable Realtime tmux session and
terminate any remaining Realtime process.

## Core CLI Surfaces

The standard Codex surfaces remain available alongside voice:

- `codex`
- `codex resume --last`
- Termux:Widget friendly labels: `Codex` and `Codex Resume Last`

The core widgets open or attach stable tmux sessions named `ccva-codex` and
`ccva-resume`. Pane logs are enabled by default under
`~/.local/state/ccva-tmux/logs/`; normal Codex session history remains the
default record.

Agents can install or refresh the widget shortcuts from a synced repo:

```sh
sh scripts/install_termux_launchers.sh
```

## First-Run Checklist

```sh
codex --version
codex-voice
codex-install-stts
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" diag
stts-diag --download
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" say "Voice is ready."
```

`codex-voice` without `--allow-realtime` should print the billing guard and exit
before any Realtime session starts.
