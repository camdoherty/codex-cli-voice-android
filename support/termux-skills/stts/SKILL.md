---
name: stts
description: Start or recover a persistent live voice session in Termux, or speak one-shot text aloud, using local Android speech through the shim/Termux STT-TTS backends and codex exec. Use when the user asks to begin, resume, or operate a walkie-talkie-like local voice conversation from Codex CLI on Android, or to read text aloud once.
---

# STTS

## Overview

Use this skill for a visible-screen, low-friction, walkie-talkie-like voice
session in Termux. The interaction is turn-based, but the session persists until
the user says `stop` or the session timeout is reached.

This mode can be used with Plus accounts and does not require an OpenAI API key
for the voice path. It uses the Android AEC shim `/v1/text-voice` endpoint for
local STTS when available, and falls back to Termux API speech commands.

## Talk

When invoked as `$stts` or `$stts talk`:

1. Keep Termux visible if possible and keep the screen on.
2. Create the persistent `ccva-stts` tmux session if needed.
3. Run one voice turn with `sh scripts/stts-session.sh talk`.
4. Keep the session ready until the next turn, `stop`, or the session timeout is reached.
5. Use `stts session` only when the user wants to inspect/open the tmux workspace without listening.

Manual equivalent from Termux:

```sh
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" talk
```

## One-Shot TTS

For a single spoken line without starting the persistent mic loop:

```sh
sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" say "Voice is ready."
```

Prefer this path over the full session when the user only wants speech output.

## Turn Rules

- Prefer `--stt-backend auto` for live use. It tries shim STT first and falls back to Termux STT only when the shim recognizer is unavailable before listening starts.
- For one-shot speech, prefer `sh scripts/stts-session.sh say "<text>"` instead of starting the full loop.
- Run one turn with `sh scripts/stts-session.sh talk`; it starts the persistent session if needed.
- Use `sh scripts/stts-session.sh session` to open the tmux workspace without listening.
- Use `sh scripts/stts-session.sh loop` only when the user explicitly wants the experimental continuous auto-listen loop.
- For project-specific work, start the session with `--cwd /path/to/repo` so `codex exec` has the right workspace context.
- STTS uses `gpt-5.4-mini` with `model_reasoning_effort="low"` by default for lower-cost voice turns. Override with `CODEX_STTS_CODEX_MODEL` and `CODEX_STTS_CODEX_REASONING_EFFORT` when testing.
- Do not change Android volume unless the user explicitly asks for it.
- The controller uses `--tts-backend auto` by default: Android shim TTS first, Termux API TTS fallback.
- Use `--tts-backend shim` and `--stt-backend shim` for reliability tests.
- Use `--tts-backend termux` or `--stt-backend termux` only to compare or debug fallback behavior.
- Use `sh scripts/stts-session.sh status` to check the active PID, last transcript, and relevant audio stream levels.
- Use `sh scripts/stts-session.sh diag` to verify Termux API commands, TTS engines, session status, and audio routing before deeper debugging.
- Use `sh scripts/stts-session.sh stop` or `cleanup` after interrupted tests to stop orphaned STT/TTS helpers and clear stale session state.
- Use `sh scripts/stts-session.sh stt-check` to test raw Android speech capture separately from Codex reply generation.
- If no transcript appears, retry once while the app is still visible before assuming failure.
- If the transcript is empty, obviously wrong, or cut off, ask for a repeat instead of guessing.
- Keep spoken replies short; long replies increase the chance that the next STT turn starts while Android audio is still recovering.
- If the user says `stop`, end the conversation and leave the session idle.

## Notes Workspace

Use `~/codex_notes` as the default workspace for spoken note requests. On a
standard CCAT install this is a symlink to
`~/storage/shared/Documents/codex_notes` when shared storage is available, so
Android note apps and file managers can see the files.

- Prefer Markdown notes.
- Create, read, append, summarize, open, and share ordinary notes there without
  asking for extra permission.
- Ask before deleting notes, overwriting substantial content, or writing outside
  `~/codex_notes`.
- Use simple slug filenames from the user's request, with a timestamp fallback.
- Use `termux-open "$HOME/codex_notes/file.md"` for "open the note" requests
  and `termux-share "$HOME/codex_notes/file.md"` for "share the note" requests
  when those commands are available.
- If Android is locked or the share/open intent does not appear, tell the user
  briefly and suggest unlocking or retrying.

## References

- See [audibility-findings.md](references/audibility-findings.md) for playback-routing evidence.
- See [testing-process.md](references/testing-process.md) for repeatable validation steps.
- Use [scripts/stts-session.sh](scripts/stts-session.sh) as the default session entrypoint, invoked via `sh`.
