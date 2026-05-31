# CCAT Android Intake TODO

This document tracks Android-native intake paths for CCAT.

## Android Share Target For Codex Bridge

Goal: let users share text, links, or small files from Android directly to
Codex Bridge, then have Codex prompt for the next action.

Implemented v1 flow:

1. User shares text, a link, or a file to `Codex Bridge`.
2. Bridge receives `ACTION_SEND` or `ACTION_SEND_MULTIPLE`.
3. Bridge reads shared `content://` payloads through `ContentResolver`.
4. Bridge stages small payloads through the approved Termux RunCommand path
   under:

```text
~/codex_notes/inbox/
```

5. Bridge triggers Termux through the existing approved RunCommand path.
6. Codex opens in the existing CCAT tmux/STTS surface with a conservative
   review prompt.

Command shape:

```sh
stts ingest ~/codex_notes/inbox/<item>/manifest.json
```

Safety constraints:

- Stage and prompt first; do not auto-execute shared content.
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
