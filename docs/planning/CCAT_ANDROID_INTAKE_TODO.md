# CCAT Android Intake TODO

This document tracks future Android-native intake paths for CCAT. These items
are not current release behavior unless separately implemented and validated.

## Android Share Target For Codex Bridge

Goal: let users share text, links, or files from Android directly to Codex
Bridge, then have Codex prompt for the next action.

Proposed flow:

1. User shares text, a link, or a file to `Codex Bridge`.
2. Bridge receives `ACTION_SEND` or `ACTION_SEND_MULTIPLE`.
3. Bridge copies shared content through Android `ContentResolver` when needed.
4. Bridge stages the payload under:

```text
~/codex_notes/inbox/
```

5. Bridge triggers Termux through the existing approved RunCommand path.
6. Codex opens in the existing CCAT tmux/STTS surface and prompts the user.

Initial command shape:

```sh
stts ingest ~/codex_notes/inbox/<item>
```

Possible fallback:

```sh
codex exec "Review the newest item in ~/codex_notes/inbox and ask what I want to do with it."
```

Safety constraints:

- Stage and prompt first; do not auto-execute shared content.
- Do not treat shared files as commands.
- Keep staged content in the notes inbox unless the user asks for another
  location.
- Support explicit user review before any destructive or external action.

Implementation notes:

- Add Android intent filters for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`.
- Start with `text/plain`, `text/uri-list`, and common document MIME types.
- Use `ContentResolver` for content URIs instead of assuming filesystem paths.
- Keep the inbox path stable and documented.
- Validate with Android share sheet from browser, notes app, file manager, and
  clipboard/text sources.
