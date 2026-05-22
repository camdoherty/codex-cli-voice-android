# Audibility Findings

## Scope

This note records the local Android playback evidence behind the `tts-stt`
skill's TTS backend and fallback stream choices.

## Findings

- `termux-tts-speak -h` reports the implicit default stream as `NOTIFICATION`.
- The skill's previous default left `DEFAULT_TTS_STREAM` empty, so one-shot and session playback delegated to that implicit `NOTIFICATION` stream.
- A one-shot skill test returned `tts dispatched on default stream`, but the user reported no playback.
- `termux-audio-info` reported `BLUETOOTH_A2DP_IS_ON: false` and `WIREDHEADSET_IS_CONNECTED: false`, so the test was not obviously routed to Bluetooth or a wired headset.
- `termux-volume` reported nonzero `music`, `notification`, and `alarm` volumes during the test.
- Explicit stream tests were dispatched for `MUSIC` and `ALARM`.
- The user later reported: "I believe I heard alarm and final music."
- Later validation showed direct `termux-tts-speak -s MUSIC` could hang until
  timeout and produce no audible speech.
- Android shim TTS through `/v1/text-voice` was audible, returned
  `tts_complete`, and left no helper processes.

## Decision

Use shim TTS as the preferred skill-managed spoken-output path. Keep Termux API
TTS as a fallback and pin that fallback to `MUSIC`.

## Rationale

- The failure mode was not command availability: `termux-tts-speak`, `termux-speech-to-text`, `termux-volume`, and `codex` were present.
- Termux API TTS is not reliable enough for the critical path because it can
  hang after dispatch and may be silent even with nonzero stream volume.
- Shim TTS provides an explicit completion event and was audibly confirmed.
- The best-supported split is stream audibility: implicit `NOTIFICATION` accepted requests but was not reliably audible, while explicit `MUSIC` was heard.
- `MUSIC` is the least surprising stream for conversational playback. `ALARM` is too intrusive for normal voice chat and should remain a debugging option.
- Android volume should remain diagnostic-only unless the user explicitly asks to change it.

## Validation Commands

```sh
sh ~/.codex/skills/tts-stt/scripts/tts-stt-session.sh diag
sh ~/.codex/skills/tts-stt/scripts/tts-stt-session.sh --tts-backend shim say 'Testing TTS through the shim.'
sh ~/.codex/skills/tts-stt/scripts/tts-stt-session.sh --tts-backend termux say 'Testing TTS on the music stream.'
sh ~/.codex/skills/tts-stt/scripts/tts-stt-session.sh --tts-stream ALARM say 'Testing TTS on the alarm stream.'
```

## Agent Notes

- Do not treat `tts dispatched` as proof of audible playback.
- Prefer `--tts-backend shim` for live use and reliability tests.
- Use `--tts-backend termux` only to diagnose the fallback path.
- If playback is silent again, first inspect routing with `termux-audio-info` and stream levels with `termux-volume`.
- Do not change Android volume automatically.
- Keep `--tts-stream` as an explicit override for troubleshooting.
