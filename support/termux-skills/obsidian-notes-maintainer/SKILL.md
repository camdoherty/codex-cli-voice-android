---
name: obsidian-notes-maintainer
description: Maintain Codex-managed Markdown notes and safely work with Obsidian vault notes on Android/Termux or Devbox. Use when the user asks to create, save, open, read, organize, or troubleshoot Markdown notes involving Obsidian, codex_notes, vault-relative paths, Obsidian URIs, Android note launching, or Obsidian vault syncing via Syncthing.
metadata:
  short-description: Safely maintain notes and Obsidian vault handoffs
---

# Obsidian Notes Maintainer

## Boundaries

- Do not read or write user Obsidian vaults unless the user explicitly asks.
- `codex_notes` is Codex-managed and may be read or written for assistant notes, scratch notes, handoffs, and generated Markdown.
- If the user asks to open a Markdown file that is not already in a known vault, write or copy it into `codex_notes` first, then open that copy.
- If a user-vault target is ambiguous, inspect exact directory names first, fuzzy-search only if needed, and ask before writing when multiple plausible targets remain.
- Never move, delete, overwrite, or reorganize user-vault notes unless explicitly requested.

## Known Note Roots

Verify locally before acting if a path may differ on the current host.

If present, read local/private path defaults from:

```text
~/.codex/skills/obsidian-notes-maintainer/.local
```

The file uses shell-style variables such as `OBSIDIAN_VAULT_NAME`,
`OBSIDIAN_VAULT`, `OBSIDIAN_VAULT_ANDROID`, `OBSIDIAN_VAULT_DEVBOX`, and
`OBSIDIAN_VAULT_CANDIDATES`. Treat those values as local evidence, not public
defaults. Do not copy them into managed skill files, commits, release docs, or
chat unless the user explicitly asks.

If only `OBSIDIAN_VAULT_CANDIDATES` is set, inspect those paths and ask before
writing to a user vault. Do not guess a default vault from candidates alone.

User vaults, read/write only by explicit request:

```text
Devbox: set `OBSIDIAN_VAULT` or verify the local vault path first
Android/Termux: set `OBSIDIAN_VAULT` or verify the shared-storage vault path first
```

Codex-managed notes, read/write allowed:

```text
Devbox: verify or create a local codex_notes mirror only when explicitly needed
Android/Termux: ~/codex_notes
```

## Opening Notes In Obsidian

- Prefer Obsidian URIs over a desktop-only `obsidian` CLI.
- On Android/Termux, prefer `termux-open-url 'obsidian://...'`; fallback to `am start -a android.intent.action.VIEW -d 'obsidian://...'`.
- Use `termux-open` for ordinary files only when Obsidian URI routing is not needed.
- URI-encode reserved characters in `file=` or `path=` values.

Known vault-relative note:

```sh
termux-open-url 'obsidian://open?vault=VAULT_NAME&file=codex_notes%2Fexample.md'
```

Absolute path inside a known vault:

```sh
termux-open-url 'obsidian://open?path=%2Fstorage%2Femulated%2F0%2FVAULT_NAME%2Fcodex_notes%2Fexample.md'
```

Notes:

- `file=` is relative to the vault root.
- `path=` is an absolute filesystem path, but Obsidian still resolves it by finding a containing vault.
- Obsidian supports URI actions such as `open`, `new`, `daily`, `search`, and `choose-vault`; use only the action needed for the request.

## Creating Temporary Notes

For temporary generated notes:

1. Write the note under `codex_notes`, usually `inbox/`, `tmp/`, or `handoffs/`.
2. Use a clear filename with date/time or a task slug.
3. Open it through Obsidian using vault-relative `file=` or absolute `path=`.
4. If it is scratch-only, mention it can later be archived or deleted; do not delete it without explicit request.

## User Vault Saves

When the user says "save this to my vault under <directory>":

1. Confirm the vault root exists.
2. Search exact directory names first.
3. If no exact match exists, fuzzy-search nearby names.
4. If exactly one strong match exists, write there.
5. If multiple plausible matches exist, ask before writing.
6. Before overwrite, compare the existing file and ask unless overwrite was explicit.

## Reading Notes

- If the user names `codex_notes`, read directly.
- If the user names a user vault path or folder, read only the requested file or the minimum directory listing needed to resolve it.
- Do not crawl an entire user vault unless explicitly requested.

## Obsidian Vault Syncing

Use this only for Obsidian vault sync health, Syncthing diagnostics, missing notes across devices, sync conflicts, or vault freshness. Do not treat this as generic repo sync or Obsidian Sync unless the user says so.

Syncthing validation is diagnostic by default. Do not modify vault files, `.stignore`, Syncthing config, Android app settings, or Syncthing pause/resume state unless explicitly requested.

Minimal routine:

1. Identify the vault path on the current host.
2. Confirm the vault directory exists.
3. Check for `.stfolder`, `.stignore`, `.stversions/`, and `*.sync-conflict-*`.
4. Check newest modified Markdown files and unexpectedly old target files.
5. If a note is missing, compare only the requested path or folder across available hosts.
6. If `syncthing` is unavailable, do not assume sync failure; the Android Syncthing app may own the sync process.

Safe diagnostics:

```sh
test -d "$VAULT" && echo "vault_exists=$VAULT"
find "$VAULT" -maxdepth 2 \( -name .stfolder -o -name .stignore -o -name .stversions \) -print
find "$VAULT" -type f -name '*.sync-conflict-*' -print
find "$VAULT" -type f -name '*.md' -exec stat -c '%Y %n' {} \; | sort -nr | head -20
```

If asked to open Syncthing's local web UI and it is expected to be running:

```sh
termux-open-url 'http://127.0.0.1:8384'
```

Conflict handling:

1. List conflict files.
2. Identify the likely original note.
3. Compare timestamps and small diffs.
4. Recommend a merge plan.
5. Do not delete or overwrite either copy without explicit approval.
