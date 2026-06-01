# CCAT Android Intake TODO

This document tracks Android-native intake paths for CCAT.

## Android Share Target For Codex Bridge

Goal: let users share text, links, or small files from Android directly to
Codex Bridge, then review them explicitly from a notification or STTS prompt.

Implemented v1 flow:

1. User shares text, a link, or a file to `Codex Bridge: Save to Inbox` or
   `Codex Bridge: Review Now`.
2. Bridge receives `ACTION_SEND` or `ACTION_SEND_MULTIPLE`.
3. Bridge reads shared `content://` payloads through `ContentResolver`.
4. Bridge stages small payloads through the approved Termux RunCommand path
   under:

```text
~/codex_notes/inbox/
```

5. Bridge records the latest staged manifest under STTS local state.
6. Save to Inbox shows an `Inbox received shared item` notification with an
   explicit `Review` action.
7. Review Now, or the later `Review` action, triggers Termux through the
   existing approved RunCommand path and queues a spoken STTS review.

Command shape:

```sh
stts ingest --speak ~/codex_notes/inbox/<item>/manifest.json
```

Safety constraints:

- Stage first; do not auto-execute shared content or auto-run Codex review.
- Do not treat shared files as commands.
- Keep staged content in the notes inbox unless the user asks for another
  location.
- Support explicit user review before any destructive or external action.

Implementation notes and limits:

- Use `ContentResolver` for content URIs instead of assuming filesystem paths.
- Bridge currently passes staged data to Termux as bounded base64 command
  payloads. This keeps v1 simple and avoids Bridge writing directly into
  Termux-private paths.
- Large attachments are metadata-only in v1 and should be surfaced as such by
  the review prompt.
- Keep the inbox path stable and documented.
- Validate with Android share sheet from browser, notes app, file manager, and
  clipboard/text sources.
