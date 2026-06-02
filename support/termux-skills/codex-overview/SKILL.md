---
name: codex-overview
description: Find, inspect, or resume recent Codex work across this Pixel and Devbox. Use for session history, host-labelled resume targets, subagent lineage, or Devbox cdxinbox todo/action state; do not use as a memory replacement.
---

# Codex Overview

Use this skill for concrete work discovery. Use memories for durable context
recall, not for finding exact sessions.

## Commands

Recent Pixel sessions:

```sh
sqlite3 "$HOME/.codex/state_5.sqlite" \
  "SELECT id, datetime(updated_at_ms/1000,'unixepoch'), source, cwd, substr(title,1,120) FROM threads WHERE archived=0 ORDER BY updated_at_ms DESC LIMIT 10;"
```

Recent Devbox sessions:

```sh
ssh -o BatchMode=yes -o ConnectTimeout=5 devbox \
  'python3 ~/.codex/skills/codex-overview/scripts/codex_overview.py list --max-age-hours 72 --limit 10'
```

Inspect Devbox item:

```sh
ssh -o BatchMode=yes -o ConnectTimeout=5 devbox \
  'python3 ~/.codex/skills/codex-overview/scripts/codex_overview.py inspect REF'
```

## Rules

- Label rows as `pixel` or `devbox` when combining hosts.
- Prefer bounded SSH execution on Devbox; do not copy active SQLite DB files.
- Keep default output compact. For detail, inspect the row and read linked
  rollout/log paths only as needed.
- Pixel has no local `cdxinbox` profile unless one is created later.
