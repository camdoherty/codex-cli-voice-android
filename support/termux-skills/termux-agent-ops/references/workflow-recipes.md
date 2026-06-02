# Workflow Recipes

Use these patterns as starting points for practical Codex-on-phone workflows.

## Notification Inbox

1. Count existing lines in `$HOME/notification_replies.txt`.
2. Send `scripts/notify-reply` with a short title and content.
3. Wait with `scripts/wait-notification-reply --since-line <line> --timeout 120`.
4. Treat timeout as no reply; do not keep polling unless requested.

## Voice Note To Local Text

1. Use `termux-dialog speech` or `termux-speech-to-text`.
2. Parse the returned JSON or transcript.
3. Create or update the requested local note.
4. Confirm with a notification or concise chat response.

## Long Local Job

1. Prefer tmux if the user may need to resume or inspect output.
2. Use `scripts/run-with-wakelock -- command args...` for screen/sleep resilience.
3. Notify on completion with exit status.
4. Release wake-lock even when the command fails.

## Clipboard URL Cleanup

1. Use `scripts/clipboard-clean-url --dry-run <url>` to verify the cleaned URL.
2. Run without `--dry-run` to write the result to the Android clipboard.
3. Return the cleaned URL in chat when useful.

## Phone Diagnostics

1. Gather only the needed data: battery, Wi-Fi connection, storage, or command availability.
2. Avoid private-data helpers unless the user requested them.
3. Summarize the device-specific evidence instead of giving generic Android advice.

## Share Latest File

1. Resolve the exact file path from the current task context.
2. Confirm the file exists.
3. Use `termux-share` for Android share sheet handoff.
4. Use `termux-open` or `termux-open-url` when the user asked to open rather than share.
