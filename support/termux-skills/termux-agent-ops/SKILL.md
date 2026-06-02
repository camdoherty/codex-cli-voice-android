---
name: termux-agent-ops
description: Operate Android and Termux on-device assistant workflows for Codex on this phone. Use when Codex needs Android intents, Termux:API commands, notifications, Direct Reply input, dialogs, clipboard, share sheets, TTS/STT, wake-locks, tmux-backed jobs, phone diagnostics, or durable local scripts for phone-resident automation.
---

# Termux Agent Ops

## Core Workflow

Prefer direct evidence from this device over generic Android or Termux advice.

1. Classify the phone surface: Android intent, notification, Direct Reply, dialog, clipboard, speech, share/open, wake-lock job, tmux pane, or diagnostics.
2. Check command availability with `scripts/termux-api-check <mode>` before relying on Termux:API helpers.
3. Start the API service with `termux-api-start` when notification, dialog, speech, clipboard, or device helper socket state is uncertain.
4. Use Android-native intents for Android-native actions: timers, alarms, app/settings launches, browser views, dialer, and share/open flows.
5. Verify user-visible behavior where possible. A successful exit does not prove a notification appeared, speech played, an alarm was created, or an activity opened.

## Surface Selection

- Need Android app or settings UI: use `am start`; read `references/android-intents.md` for examples.
- Need a timer or alarm: use `scripts/android-timer` or `scripts/android-alarm`; do not fake timers with delayed notifications.
- Need user input now: use `termux-dialog`; use notification Direct Reply only when a notification reply box is the right interface.
- Need background prompt input: use `scripts/notify-reply` and `scripts/wait-notification-reply`.
- Need clipboard cleanup or transformation: use `termux-clipboard-get`, `termux-clipboard-set`, or `scripts/clipboard-clean-url`.
- Need voice input/output: use `termux-speech-to-text`, `termux-dialog speech`, or `termux-tts-speak`; verify audibility or transcript when the result matters.
- Need a long local command to survive screen state: use `scripts/run-with-wakelock` and preferably run inside tmux when the user needs resumability.
- Need less common phone APIs: read `references/termux-api-catalog.md` before invoking camera, location, sensors, SMS, telephony, Wi-Fi scan, NFC, USB, SAF, media, or wallpaper helpers.

## Safety Rules

- Do not change Android volume, brightness, wallpaper, wake-lock state, Wi-Fi state, or disruptive settings unless explicitly requested.
- Ask for confirmation before sending SMS, placing calls, taking photos, recording audio, using location, or sharing files when the user did not directly request that action.
- For notification actions, use absolute paths or bundled scripts. Notification actions run through `dash -c` with a reduced environment.
- For Direct Reply, preserve multi-word replies as one message by routing through `scripts/save-notification-reply`; avoid embedding complex shell around `$REPLY`.
- Treat `termux-am` as optional. On this phone, `/data/data/com.termux/files/usr/bin/am` is the working Activity Manager command; `termux-am` may fail if its socket server is unavailable.

## Bundled Helpers

- `scripts/termux-api-check`: check helper availability by mode.
- `scripts/notify-reply`: send a notification with a Direct Reply button.
- `scripts/wait-notification-reply`: wait for new reply lines with a bounded timeout.
- `scripts/save-notification-reply`: append Direct Reply text to a file safely.
- `scripts/android-timer`: create a real Android timer through `am`.
- `scripts/android-alarm`: create a real Android alarm through `am`.
- `scripts/open-settings`: open common Android settings screens.
- `scripts/run-with-wakelock`: run a command while holding a Termux wake-lock, then notify the result.
- `scripts/clipboard-clean-url`: remove common tracking parameters from a URL.

## References

- Read `references/android-intents.md` for `am start`, `broadcast`, intent URI conversion, settings, timer, alarm, browser, dialer, and share examples.
- Read `references/workflow-recipes.md` for practical Codex-on-phone workflows and prompt patterns.
- Read `references/termux-api-catalog.md` for the broader Termux:API command catalog and lower-frequency helper guidance.
