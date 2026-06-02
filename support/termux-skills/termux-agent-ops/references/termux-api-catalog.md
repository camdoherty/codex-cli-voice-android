# Termux API Catalog

Load this file when the requested operation uses a lower-frequency Android-side helper or when deciding which Termux:API command fits a phone workflow.

## High-Use Helpers

- `termux-notification`, `termux-notification-remove`, `termux-notification-list`: Android notifications.
- `termux-dialog`: Android dialogs, including `confirm`, `text`, `speech`, `radio`, `spinner`, `sheet`, `checkbox`, `date`, and `time`.
- `termux-clipboard-get`, `termux-clipboard-set`: clipboard read/write.
- `termux-share`, `termux-open`, `termux-open-url`: Android share/open flows.
- `termux-tts-speak`, `termux-speech-to-text`: voice output and speech capture.
- `termux-wake-lock`, `termux-wake-unlock`: keep device awake for long jobs.
- `termux-battery-status`, `termux-wifi-connectioninfo`: lightweight diagnostics.

## Sensitive Or Disruptive Helpers

Ask or confirm unless the user explicitly requested the action.

- `termux-sms-send`, `termux-telephony-call`: sending messages or calls.
- `termux-camera-photo`, `termux-microphone-record`, `termux-location`: camera, audio recording, and location.
- `termux-volume`, `termux-brightness`, `termux-wallpaper`, `termux-wifi-enable`: device setting changes.
- `termux-contact-list`, `termux-call-log`, `termux-sms-list`, `termux-sms-inbox`: private local data.

## Specialized Helpers

Use only when the task specifically asks for them.

- `termux-sensor`: sensor listings and samples.
- `termux-media-player`, `termux-media-scan`: local media playback and scan.
- `termux-saf-*`: Android Storage Access Framework file operations.
- `termux-usb`, `termux-nfc`, `termux-infrared-*`: hardware-specific integrations.
- `termux-job-scheduler`: Android-scheduled jobs.
- `termux-keystore`, `termux-fingerprint`: secure storage and biometric confirmation.

Run `scripts/termux-api-check all` or a narrower mode before relying on these helpers.
