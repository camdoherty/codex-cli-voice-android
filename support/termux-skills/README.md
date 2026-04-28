# Termux Skill Mirrors

This directory mirrors selected on-device Codex skills that support Android voice
operation. The live skill location on Termux is:

```text
$HOME/.codex/skills/tts-stt
```

On the current Pixel test device, `$HOME` is a git repository, but `.codex/` is
ignored. That means direct edits to the live skill are not rollback-safe unless
they are mirrored here or moved into a tracked on-device worktree first.

## Safe Agent Workflow

1. Inspect the live skill on-device, but do not edit it directly for normal
   development.
2. Make source changes in this repo mirror under `support/termux-skills/tts-stt`.
3. Validate the mirrored scripts locally where possible.
4. Copy the updated mirror to the phone only after review.
5. Keep a timestamped backup of the live skill before replacement:

```sh
tar -czf "$HOME/tts-stt-skill-backup-$(date +%Y%m%d-%H%M%S).tar.gz" \
  -C "$HOME/.codex/skills" tts-stt
```

For experiments that must happen on the phone, use a separate tracked directory
under `$HOME/dev/` and copy into `$HOME/.codex/skills/tts-stt` only after the
change is validated.

## Repeatable Voice Tests

Use `tts-stt/references/testing-process.md` for the devbox-driven test process.
It documents Kokoro fixture generation, clean preflight checks, raw STT
calibration, full multi-turn validation, and the acceptance criteria for
changing timing defaults.
