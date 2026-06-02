# AGENTS.md

## Purpose

This directory is the Termux working surface for Codex on this phone.

## Rules

- Prefer `~/dev` for workspace files and `~/scripts` for launcher scripts.
- Use the live tmux session when it already exists, but do not assume tmux is the default control surface.
- Prefer `find`, `grep`, and `ls` if `rg` is unavailable.
- Do not assume Node.js or npm exist.
- Keep changes small, deterministic, and easy to resume after exiting tmux.
- If a task depends on the current terminal state, inspect the live pane content before changing anything.
- For Android-side notifications, Direct Reply input, Termux:API helpers, clipboard, toast, TTS/STT, or Android automation bridges, use the `termux-agent-ops` skill at `~/.codex/skills/termux-agent-ops`.
- For persistent voice sessions or one-shot spoken output, use the `stts` skill at `~/.codex/skills/stts`.
- For recent Codex session history, resume targets, or Devbox cdxinbox state, use the `codex-overview` skill at `~/.codex/skills/codex-overview`.
- For requests like `open <file or URL> in <app>`, or `/share <referenced file>`, follow `app-open-dispatch.md`.
- When sending a notification that requires a reply, wait quietly up to 120 seconds before timing out. If the reply is optional, continue only when the next step does not depend on it.
- Track all workspace TODO items in `todo.md`.
- Update `todo.md` whenever tasks are added, completed, deferred, or clarified.

## Git Policy

- Follow the top-level `~/AGENTS.md` Git Policy.
- Check `git status` before editing tracked instructions or workspace docs.
- Stage only intentional changes; never use broad `git add .` from home.

## Notes

- The active tmux session uses a cache-backed socket under `~/.cache/codex-tmux/`.
- When tmux is active, the shell pane is for command execution and scratch work.
- `todo.md` is the canonical local task tracker for this directory.
- For CCAT/CCVA work spanning Devbox, Pixel 6a, and Pixel 9, follow `CCAT_AGENT_CONTRACT.md` when it does not conflict with closer repository-specific instructions.
