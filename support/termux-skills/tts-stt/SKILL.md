---
name: tts-stt
description: Start or recover a persistent live voice session in Termux, or speak one-shot text aloud, using local Android speech through the shim/Termux STT-TTS backends and codex exec. Use when the user asks to begin, resume, or operate a continuous voice conversation from Codex CLI on Pixel 9 / Android, or to read text aloud once.
---

# TTS STT

## Overview

Use this skill for a visible-screen, low-friction voice session in Termux. The interaction is turn-based, but the session itself persists until the user says `stop` or the eight-minute session timeout is reached.

## Start

When invoked as `$tts-stt start`:

1. Keep Termux visible if possible and keep the screen on.
2. Speak the opener: `Hey, what are we working on?` unless the user asked for different wording.
3. Start the persistent session controller with `sh scripts/tts-stt-session.sh start`.
4. Keep the conversation running until the user says `stop` or the eight-minute timeout is reached.
5. Let the controller gather the transcript, call `codex exec`, print visible status/transcript/response lines, speak the reply through shim TTS when available, wait for shim completion, wait the post-TTS recovery gap, and reopen listening automatically.

## One-Shot TTS

For a single spoken line or poem without starting the persistent mic loop:

1. Use `sh scripts/tts-stt-session.sh say "<text>"`.
2. Prefer this path over the full session when the user only wants speech output.
3. Keep the spoken text concise and plain enough for TTS.

## Turn Rules

- Prefer `--stt-backend auto` for live use. It tries shim STT first and falls back to Termux STT only when the shim recognizer is unavailable before listening starts.
- For one-shot speech, prefer `sh scripts/tts-stt-session.sh say "<text>"` instead of starting the full loop.
- Run the main loop through `sh scripts/tts-stt-session.sh start`; do not stop after the first transcript.
- For project-specific work, start the session with `--cwd /path/to/repo` so `codex exec` has the right workspace context.
- Do not change Android volume unless the user explicitly asks for it.
- The controller uses `--tts-backend auto` by default: Android shim TTS first, Termux API TTS fallback. Use `--tts-backend shim` for reliability tests and `--tts-backend termux` only when debugging Termux API TTS directly.
- Use `--stt-backend shim` to force the clean full-shim path. Use `--stt-backend termux` only to compare or debug the older Termux SpeechRecognizer path.
- The Termux fallback uses the `MUSIC` stream because the local Pixel 9 test made Termux's implicit `NOTIFICATION` default inaudible. Override with `--tts-stream` only when debugging routing.
- Use `sh scripts/tts-stt-session.sh status` to check the active PID, last transcript, and relevant audio stream levels.
- Use `sh scripts/tts-stt-session.sh diag` to verify Termux API commands, TTS engines, session status, and audio routing before deeper debugging.
- Use `sh scripts/tts-stt-session.sh stop` or `cleanup` after interrupted tests to stop orphaned STT/TTS helpers and clear stale session state.
- Use `sh scripts/tts-stt-session.sh stt-check` to test raw Android speech capture separately from Codex reply generation.
- Keep an adaptive post-speech pause before each listen turn so the user is not pressured to answer instantly and the mic does not reopen too early.
- Keep shim TTS as the preferred local spoken-output path. Direct `termux-tts-speak` has hung on the Pixel 9 while shim TTS remained audible and returned completion events.
- Keep shim STT as the preferred local-input target once it passes the same Kokoro multi-turn tests as the existing hybrid mode. The hybrid fallback remains useful because it is already proven on-device.
- Keep the post-TTS recovery gap enabled. The current reliable shim-TTS baseline is 3 seconds before arming STT because Android audio focus and recognizer routing can be unstable immediately after spoken output.
- Keep the empty-listen cooldown enabled. The controller waits after empty STT turns before re-arming because Android's recognizer start/stop beep becomes distracting if it restarts every 1-2 seconds. Tune with `--empty-listen-delay` only during testing.
- If no transcript appears, retry once while the app is still visible before assuming failure.
- If the user is making casual conversation, keep the dialogue moving with short small talk and one follow-up question.
- If the user asks for a concrete task, stop small talk and help with that task directly.
- If the session was interrupted, restart with `$tts-stt start`.

## Failure Handling

- If shim TTS is unavailable, the controller can fall back to `termux-tts-speak`; if shim STT is unavailable before listening starts, `--stt-backend auto` can fall back to `termux-speech-to-text`.
- In forced full-shim mode, do not spawn `termux-speech-to-text`; report shim STT errors directly.
- If `audio_busy` appears, do not hide it with a Termux fallback. It means another audio owner still holds the shim coordinator lock.
- If Android does not show the recognizer UI, confirm the app is foreground/visible.
- If the mic does not reopen after the assistant speaks, check for lingering `termux-speech-to-text`, `termux-tts-speak`, `termux-api`, or `codex exec` helpers, then run `cleanup` before retrying.
- If TTS dispatch succeeds but the user cannot hear it, first compare `--tts-backend shim` against `--tts-backend termux`, then check Bluetooth/output routing before changing streams or volume.
- If the user can hear `ALARM` or `MUSIC` but not the default path, keep `MUSIC` as the default and document the evidence; do not change Android volume unless explicitly requested.
- If the transcript is empty, obviously wrong, or cut off, ask for a repeat instead of guessing.
- Keep assistant replies short; long spoken replies increase the chance that the next STT turn starts while Android audio is still recovering.
- If the user says `stop`, end the conversation and leave the session idle.
- If the session hits the eight-minute timeout, speak a short timeout message and exit cleanly.

## References

- See [session-flow.md](references/session-flow.md) for the exact state machine and restart behavior.
- See [requirements-and-plan.md](references/requirements-and-plan.md) for the finalized requirements, decisions, and plan.
- See [audibility-findings.md](references/audibility-findings.md) for the Pixel 9 playback-routing evidence behind the default `MUSIC` stream.
- See [testing-process.md](references/testing-process.md) for the repeatable devbox Kokoro playback tests.
- Use [scripts/tts-stt-session.sh](scripts/tts-stt-session.sh) as the default session entrypoint, invoked via `sh`.
