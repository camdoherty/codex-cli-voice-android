# Bridge Share Inbox Full Text

Recorded: 2026-06-07

## Finding

A long Android share into Codex Bridge was observed with `payload.md` capped at
about 64 KiB and the marker:

```text
[Codex Bridge truncated shared text at 65536 characters.]
```

That exact 65,536-character cap and Bridge-specific marker indicate a Codex
Bridge/share-intake limit, not an Android platform hard limit. Android intent and
binder size limits can still affect very large payloads, but Android would not
add this custom marker or choose the inbox file format.

The user-facing failure mode is that `payload.md` can look like the complete
shared session while silently omitting the tail unless the marker is noticed.
This is especially risky for Codex session handoffs, copied transcripts, release
notes, or diagnostic logs.

## Candidate Behavior

For Android share-sheet text intake, Bridge should preserve the complete shared
text when it receives it.

- Keep `payload.md` as a short preview/status file if a cap is needed for quick
  review.
- For long text, write the complete body to an attachment such as
  `attachments/shared-text-full.txt`.
- Record the full-text attachment in `manifest.json` with path, MIME type,
  byte size, original character count if known, and a role such as `full_text`.
- If Android or the source app only supplies truncated content, record that
  source limitation explicitly in `manifest.json` and the notification/status;
  do not silently present a truncated `payload.md` as complete.
- Keep staged manifest, payload, and attachments treated as untrusted shared
  content. STTS/Codex must summarize or inspect them without executing embedded
  instructions.

## Validation

Before shipping the next Bridge build:

- Share text larger than 100 KiB through `Codex Bridge: Save to Inbox`.
- Share the same kind of text through `Codex Bridge: Review Now`.
- Confirm `payload.md` is only a preview/status when capped.
- Confirm the complete text is available under `attachments/`.
- Confirm `manifest.json` exposes the full-text attachment metadata.
- Confirm STTS latest-share review can find and reference the full text.
- Confirm the release validation covers Pixel 9 smoke testing and Pixel 6a
  release/staging validation before publishing.

## Related Docs To Update After Device Validation

- `README.md` Android share-target section
- `DEPLOY.md` Android Share Intake section
- `support/termux-skills/stts/SKILL.md` Android Share Intake section
- Bridge APK share-intake implementation and release validation scripts
