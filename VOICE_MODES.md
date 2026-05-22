# Voice Modes

Codex CLI Voice Android ships two voice modes with different goals and cost
profiles.

## Quick Chooser

Use **Local Half-Duplex Voice** for normal agent work:

```sh
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" start
```

or ask Codex:

```text
$tts-stt start
```

Use **Realtime Voice** only when you want the native OpenAI Realtime experience
and accept Realtime API billing:

```sh
codex-voice --allow-realtime
```

## Local Half-Duplex Voice

`$tts-stt` is a walkie-talkie-like voice mode that can be used with Plus
accounts. No OpenAI API key is required for the voice path.

Default path:

```text
tts-stt -> ws://127.0.0.1:8765/v1/text-voice -> Android TextToSpeech/SpeechRecognizer
```

Fallback path:

```text
tts-stt -> termux-tts-speak / termux-speech-to-text
```

Start surfaces:

- Agent: `$tts-stt start`
- Command: `sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" start`
- Termux:Widget: `tts-stt-start`

Useful checks:

```sh
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" diag
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" say "Voice is ready."
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" stt-check
```

Stop and cleanup:

```sh
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" stop
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" cleanup
```

## Realtime Voice

`codex-voice` wraps Codex Realtime for Termux/Android. It uses the AEC shim
native audio path:

```text
codex-voice -> ws://127.0.0.1:8765/v1/audio -> OpenAI Realtime
```

This mode requires an API key and can start OpenAI Realtime billing. The
launcher intentionally refuses to run unless you pass:

```sh
codex-voice --allow-realtime
```

or:

```sh
CODEX_VOICE_ALLOW_REALTIME=1 codex-voice
```

## First-Run Checklist

```sh
codex --version
codex-voice
codex-install-tts-stt
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" diag
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" say "Voice is ready."
```

`codex-voice` without `--allow-realtime` should print the billing guard and exit
before any Realtime session starts.
