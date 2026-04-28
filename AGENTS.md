# Agent Notes

This repository is a public, sanitized build and deployment wrapper for Codex CLI + Voice (Android).

## Rules

- Do not commit built artifacts such as `.tar.gz`, `.apk`, `.metadata`, or checksum files.
- Do not add private hostnames, LAN IPs, personal usernames, SSH key paths, or API keys to docs or scripts.
- Keep the upstream Codex version visible in `README.md` and `build.sh`.
- Prefer `.env.example` placeholders over real deployment values.
- Treat `codex-voice` as billable unless the realtime guard is active.
- Treat `support/termux-skills/tts-stt` as the source mirror for the local Termux `tts-stt` skill.
- Do not make unreviewed direct edits to `$HOME/.codex/skills/tts-stt` on a phone. On the current test device, `$HOME` is a git repo but `.codex/` is ignored, so live skill edits are not rollback-safe unless mirrored here or made in a tracked on-device worktree first.

## Validation

Before publishing changes, run:

```bash
bash -n build.sh scripts/*.sh
rg -n 'replace-this-with-private-hostnames-or-paths'
find . -type f \( -name '*.tar.gz' -o -name '*.apk' -o -name '*.metadata' \) -print
```

For local Termux voice skill checks, prefer the devbox harness:

```bash
python3 scripts/autotest_termux_tts_stt_skill.py \
  --ssh-target android-device-ssh-alias \
  --settle-ms 1000 \
  --clip /path/to/generated.wav \
  --expected-file /path/to/generated.txt
```

For full `tts-stt` changes, follow
`support/termux-skills/tts-stt/references/testing-process.md`. That process
documents Kokoro fixture generation, cleanup/status preflight, raw STT
calibration, full multi-turn testing, and acceptance criteria.
