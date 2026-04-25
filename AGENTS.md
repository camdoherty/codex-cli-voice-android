# Agent Notes

This repository is a public, sanitized build and deployment wrapper for Codex CLI + Voice (Android).

## Rules

- Do not commit built artifacts such as `.tar.gz`, `.apk`, `.metadata`, or checksum files.
- Do not add private hostnames, LAN IPs, personal usernames, SSH key paths, or API keys to docs or scripts.
- Keep the upstream Codex version visible in `README.md` and `build.sh`.
- Prefer `.env.example` placeholders over real deployment values.
- Treat `codex-voice` as billable unless the realtime guard is active.

## Validation

Before publishing changes, run:

```bash
bash -n build.sh scripts/*.sh
rg -n 'replace-this-with-private-hostnames-or-paths'
find . -type f \( -name '*.tar.gz' -o -name '*.apk' -o -name '*.metadata' \) -print
```
