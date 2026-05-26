# Agent Notes

This repository is the public, sanitized build and deployment wrapper for
Codex CLI + Voice on Android/Termux.

## Rules

- Do not commit built artifacts such as `.tar.gz`, `.apk`, `.metadata`, or checksum files.
- Do not add private hostnames, LAN IPs, personal usernames, SSH key paths, auth files, or API keys to docs or scripts.
- Keep the upstream Codex version visible in `README.md` and `build.sh`.
- Prefer `.env.example` placeholders over real deployment values.
- Treat `codex-voice` as billable unless the realtime guard is active.
- Treat `support/termux-skills/stts` as the source mirror for the local Termux `stts` skill.
- Keep experimental activation prototypes, private logs, personal profiles, and prototype routing assumptions out of the stable release.

## Validation

Before publishing changes, run:

```bash
bash -n build.sh scripts/*.sh
python3 -m py_compile \
  scripts/smoke_text_voice_ws.py \
  scripts/autotest_text_voice_kokoro.py \
  scripts/autotest_termux_stts_skill.py \
  support/termux-skills/stts/scripts/stts_loop.py
rg -n 'replace-this-with-private-hostnames-or-paths|private-host|private-path|/home/[^/[:space:]]+|100\\.97\\.|192\\.168\\.'
find . -type f \( -name '*.tar.gz' -o -name '*.apk' -o -name '*.metadata' -o -name '*.pyc' \) -print
```

For full `stts` changes, follow
`support/termux-skills/stts/references/testing-process.md`. That process
documents cleanup/status preflight, raw STT calibration, full multi-turn
testing, and acceptance criteria.
