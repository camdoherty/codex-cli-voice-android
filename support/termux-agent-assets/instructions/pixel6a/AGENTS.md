# AGENTS.md

## Purpose

This is the top-level Termux home workspace for Codex on the Pixel 6a staging
device.

## Staging Role

- Treat this phone as the clean validation surface for CCAT/CCVA Android
  deploys.
- Prefer repeatable repo/package/install flows over ad hoc file edits.
- Keep Pixel 6a device state intentionally simpler than Pixel 9 unless the
  user asks to mirror a Pixel 9 workflow.

## Evidence Rules

- Prefer direct local evidence from this device over generic Termux or Android
  advice.
- Before reporting success, verify with local commands, file hashes, package
  state, or observed Android/Termux behavior.
- Clearly separate proven results from attempted launches, assumptions, and
  user-confirmed observations.

## Managed Skills

- For Android intents, notifications, share sheets, clipboard, wake locks, or
  Termux:API helpers, use `~/.codex/skills/termux-agent-ops`.
- For persistent voice sessions or one-shot spoken output, use
  `~/.codex/skills/stts`.
- For safe Codex-managed Obsidian note maintenance, use
  `~/.codex/skills/obsidian-notes-maintainer`.
- For recent Codex session history or Devbox cdxinbox state, use
  `~/.codex/skills/codex-overview`.
- For user-selected tmux pane inspection or non-secret interactive shell help,
  use `~/.codex/skills/tmux-support`.

## CCAT Contract

- For CCAT/CCVA work spanning Devbox, Pixel 6a, and Pixel 9, follow
  `~/dev/CCAT_AGENT_CONTRACT.md` when present and when it does not conflict
  with closer repository-specific instructions.
- If the contract is missing, continue using direct local evidence and report
  that the shared contract was not present.

## Safety

- Do not overwrite existing skills, instruction files, launchers, or configs
  without an explicit user request.
- Do not change Android volume, permissions, accounts, or disruptive device
  settings unless explicitly requested.
- Do not ask the user to paste secrets into chat. For passwords, passphrases,
  tokens, or account prompts, ask the user to type directly on the device or in
  the selected tmux pane.
