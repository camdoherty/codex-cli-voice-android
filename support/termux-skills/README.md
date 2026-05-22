# Termux Skill Mirrors

This directory mirrors selected on-device Codex skills that support Android voice
operation. The live skill location on Termux is:

```text
$HOME/.codex/skills/tts-stt
```

Direct edits to the live skill are not rollback-safe unless they are mirrored
here or made in a tracked on-device worktree first.

## Safe Agent Workflow

1. Inspect the live skill on-device, but do not edit it directly for normal
   development.
2. Make source changes in this repo mirror under `support/termux-skills/tts-stt`.
3. Validate the mirrored scripts locally where possible.
4. Install the updated mirror with:

```sh
scripts/install_tts_stt_skill.sh
```

The installer creates a timestamped backup before replacing an existing live
skill.

## Repeatable Voice Tests

Use `tts-stt/references/testing-process.md` for the devbox-driven test process.
It documents Kokoro fixture generation, clean preflight checks, raw STT
calibration, full multi-turn validation, and the acceptance criteria for
changing timing defaults.
