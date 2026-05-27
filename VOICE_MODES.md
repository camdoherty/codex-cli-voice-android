# Voice Modes

Codex CLI Voice Android ships two validated voice modes with different goals
and cost profiles. `$stts` is the Plus-friendly local mode. `codex-voice
--allow-realtime` is OpenAI Codex CLI Realtime voice mode adapted for Android
native audio, and uses OpenAI Realtime API billing.

## Quick Chooser

Use **Local Half-Duplex Voice** for Plus-friendly mobile voice intake and normal
agent work. `stts start` creates or attaches the persistent `ccva-stts` tmux
session; `stts talk` sends one voice turn into that session.

```sh
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" start
```

or ask Codex:

```text
$stts start
```

Use **OpenAI Codex Realtime Voice** only when you want Codex CLI's realtime
voice experience on Android native audio and accept Realtime API billing:

```sh
codex-voice --allow-realtime
```

## Local Half-Duplex Voice

`$stts` is a walkie-talkie-like voice mode that can be used with Plus
accounts. No OpenAI API key is required for the voice path.

Default path:

```text
stts -> ws://127.0.0.1:8765/v1/text-voice -> Android TextToSpeech/SpeechRecognizer
```

Fallback path:

```text
stts -> termux-tts-speak / termux-speech-to-text
```

Start surfaces:

- Agent: `$stts start`
- Command: `sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" start`
- Termux:Widget: `stts-start`
- Termux:Widget friendly label: `Start STTS Voice Mode`
- Tap-to-talk command: `stts-talk`
- Wake mode command: `wake-voice-start`
- Experimental continuous loop: `stts-loop`

Command behavior:

- `stts start`: create or attach the persistent `ccva-stts` tmux session.
- `stts talk`: run one speech-to-text turn against the active session history.
- `stts wake`: run wake/PTT-triggered turns.
- `stts loop`: run the older continuous auto-listen loop. This is useful for
  testing, but may repeatedly trigger Android SpeechRecognizer beeps.
- `stts stop`: stop the active session and voice helpers.

The default session safety timeout is one hour. Use `--timeout-seconds` only
for bounded tests.

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
- Termux:Widget: `codex-voice`
- Termux:Widget friendly label: `Start API($) Realtime Voice Mode`

## Core CLI Surfaces

The standard Codex surfaces remain available alongside voice:

- `codex`
- `codex resume --last`
- Termux:Widget friendly labels: `Codex` and `Codex Resume Last`

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
