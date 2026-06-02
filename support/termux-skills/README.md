# Termux Skill Mirrors

This directory mirrors selected on-device Codex skills that support Android
voice and on-device agent operation. The live skill locations on Termux are:

```text
$HOME/.codex/skills/stts
$HOME/.codex/skills/termux-agent-ops
$HOME/.codex/skills/obsidian-notes-maintainer
$HOME/.codex/skills/codex-overview
$HOME/.codex/skills/tmux-support
```

Direct edits to the live skill are not rollback-safe unless they are mirrored
here or made in a tracked on-device worktree first.

## Safe Agent Workflow

1. Inspect the live skill on-device, but do not edit it directly for normal
   development.
2. Make source changes in this repo mirror under `support/termux-skills/`.
3. Validate the mirrored scripts locally where possible.
4. Install or report the updated mirrors with:

```sh
scripts/install_stts_skill.sh
scripts/install_termux_agent_assets.sh --dry-run --skills-only
scripts/install_termux_agent_assets.sh --apply --skills-only
```

The installers skip identical targets. Differing live skills are backed up and
incoming content is written beside the target unless an explicit force path is
used.

Instruction assets live under `support/termux-agent-assets/`. They are
reference assets and opt-in installs; package deploys should not replace
`AGENTS.md`, `.gitignore`, or contract docs automatically.

## Repeatable Voice Tests

Use `stts/references/testing-process.md` for the host-driven test process.
It documents Kokoro fixture generation, clean preflight checks, raw STT
calibration, full multi-turn validation, and the acceptance criteria for
changing timing defaults.
