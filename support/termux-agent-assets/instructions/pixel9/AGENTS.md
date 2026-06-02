# AGENTS.md

## Purpose

This is the top-level Termux home workspace for Codex on this phone.

## Routing And Safety

- Prefer direct local evidence from this device over generic Termux or Android advice.
- Prefer `~/dev` for workspace documentation and `~/scripts` for launcher scripts.
- For Android intents, notifications, share sheets, clipboard, TTS/STT, or Termux:API helpers, use the `termux-agent-ops` skill at `~/.codex/skills/termux-agent-ops`.
- For persistent voice sessions or one-shot spoken output, use the `stts` skill at `~/.codex/skills/stts`.
- For recent Codex session history, resume targets, or Devbox cdxinbox state, use the `codex-overview` skill at `~/.codex/skills/codex-overview`.
- For requests like `open <file or URL> in <app>`, resolve the exact file path or URL first, then follow `~/dev/app-open-dispatch.md`.
- For `/share` or `share <referenced file>`, resolve the most recently created, modified, discussed, or specifically referenced file, then use `termux-share` to open Android's share sheet.
- If a literal `/share` is intercepted by Codex CLI as an unknown slash command, treat `share <file>` or `please /share <file>` as the equivalent agent instruction.
- Do not change Android volume or disruptive device settings unless explicitly requested.

## Git Policy

- This repo is local/private unless a remote is explicitly configured.
- Track active Codex instructions, custom skills, launcher scripts, and workspace docs.
- Do not track Codex auth, logs, sessions, SQLite state, memories, caches, plugins, pycache, backups, or runtime files.
- Before editing tracked instruction files, check `git status`.
- Stage only intentional changes; never use broad `git add .` from home.

## Notes

- Existing directory-specific instructions for the local workspace live in `~/dev/AGENTS.md`.
- For CCAT/CCVA work spanning Devbox, Pixel 6a, and Pixel 9, follow the shared non-strict contract in `~/dev/CCAT_AGENT_CONTRACT.md` when it does not conflict with closer repository-specific instructions.
- Obsidian vault roots are device-specific; verify the exact Android path before opening or writing notes.
