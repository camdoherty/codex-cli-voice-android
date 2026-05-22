---
name: tts-stt
description: Start or recover a persistent live voice session in Termux, or speak one-shot text aloud, using local Android speech through the shim/Termux STT-TTS backends and codex exec. Use when the user asks to begin, resume, or operate a walkie-talkie-like local voice conversation from Codex CLI on Android, or to read text aloud once.
---

# TTS STT

## Overview

Use this skill for a visible-screen, low-friction, walkie-talkie-like voice
session in Termux. The interaction is turn-based, but the session persists until
the user says `stop` or the session timeout is reached.

This mode can be used with Plus accounts and does not require an OpenAI API key
for the voice path. It uses the Android AEC shim `/v1/text-voice` endpoint for
local TTS/STT when available, and falls back to Termux API speech commands.

## Start

When invoked as `$tts-stt start`:

1. Keep Termux visible if possible and keep the screen on.
2. Speak the opener: `Hey, what are we working on?` unless the user asked for different wording.
3. Start the persistent session controller with `sh scripts/tts-stt-session.sh start`.
4. Keep the conversation running until the user says `stop` or the session timeout is reached.
5. Let the controller gather the transcript, call `codex exec`, print visible status/transcript/response lines, speak the reply through shim TTS when available, wait for shim completion, wait the post-TTS recovery gap, and reopen listening automatically.

Manual equivalent from Termux:

```sh
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" start
```

## One-Shot TTS

For a single spoken line without starting the persistent mic loop:

```sh
sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" say "Voice is ready."
```

Prefer this path over the full session when the user only wants speech output.

## Turn Rules

- Prefer `--stt-backend auto` for live use. It tries shim STT first and falls back to Termux STT only when the shim recognizer is unavailable before listening starts.
- For one-shot speech, prefer `sh scripts/tts-stt-session.sh say "<text>"` instead of starting the full loop.
- Run the main loop through `sh scripts/tts-stt-session.sh start`; do not stop after the first transcript.
- For project-specific work, start the session with `--cwd /path/to/repo` so `codex exec` has the right workspace context.
- Do not change Android volume unless the user explicitly asks for it.
- The controller uses `--tts-backend auto` by default: Android shim TTS first, Termux API TTS fallback.
- Use `--tts-backend shim` and `--stt-backend shim` for reliability tests.
- Use `--tts-backend termux` or `--stt-backend termux` only to compare or debug fallback behavior.
- Use `sh scripts/tts-stt-session.sh status` to check the active PID, last transcript, and relevant audio stream levels.
- Use `sh scripts/tts-stt-session.sh diag` to verify Termux API commands, TTS engines, session status, and audio routing before deeper debugging.
- Use `sh scripts/tts-stt-session.sh stop` or `cleanup` after interrupted tests to stop orphaned STT/TTS helpers and clear stale session state.
- Use `sh scripts/tts-stt-session.sh stt-check` to test raw Android speech capture separately from Codex reply generation.
- If no transcript appears, retry once while the app is still visible before assuming failure.
- If the transcript is empty, obviously wrong, or cut off, ask for a repeat instead of guessing.
- Keep spoken replies short; long replies increase the chance that the next STT turn starts while Android audio is still recovering.
- If the user says `stop`, end the conversation and leave the session idle.

## References

- See [audibility-findings.md](references/audibility-findings.md) for playback-routing evidence.
- See [testing-process.md](references/testing-process.md) for repeatable validation steps.
- Use [scripts/tts-stt-session.sh](scripts/tts-stt-session.sh) as the default session entrypoint, invoked via `sh`.
