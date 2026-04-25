# Android Shim: Half-Duplex TTS/STT Architecture

**Status:** Evidence-Backed Implementation Plan
**Target:** Android 14+ (Pixel 9) / Termux
**Goal:** Provide low-latency, UI-free Speech-to-Text (STT) and Text-to-Speech (TTS) to the Codex CLI via a local WebSocket/HTTP interface, bypassing the `termux-api` limitations.

## 1. Architectural Summary

Instead of the Codex CLI dealing with raw audio bytes or slow `termux-api` shell wrappers, the existing **Android AEC Shim** (or an extended version of it) will act as a local API server. 

The architecture strictly enforces a **Text-Only Localhost Bridge**:
- **STT (Speech-to-Text):** The Android Shim captures audio using the hardware DSP, runs the native Android `SpeechRecognizer`, and streams the recognized *text* over a local WebSocket to the CLI.
- **TTS (Text-to-Speech):** The Codex CLI sends a plain text JSON payload to the Shim's HTTP/WebSocket server. The Shim uses the native Android `TextToSpeech` engine to synthesize and play the audio instantly.

**Result:** The Rust CLI never processes PCM audio bytes for half-duplex tasks. 

## 2. Evidence & Confirmed Assumptions

We have verified that Android 14+ strict background restrictions will not block this architecture, provided specific rules are followed.

### Assumption 1: `SpeechRecognizer` without a UI Popup
**Confirmed:** Yes, `SpeechRecognizer` can run silently in the background without launching the Google Assistant UI intent, but it requires strict compliance with Android 14's Foreground Service rules.
*   **Foreground Type:** The service must be explicitly declared as `foregroundServiceType="microphone"`.
*   **Initialization:** Android 14 restricts "while-in-use" permissions (`RECORD_AUDIO`). The Foreground Service *must* be started while the app's UI is visible. Once started, it can be backgrounded and continue to listen.
*   **Main Thread:** The `SpeechRecognizer` API mandates that `createSpeechRecognizer()` and `startListening()` are called on the Main Thread (UI thread), even if the service itself is running in the background. (Use `Handler(Looper.getMainLooper()).post { ... }`).
*   **On-Device:** API 31+ allows forcing offline models via `createOnDeviceSpeechRecognizer()`, ensuring privacy and speed.

### Assumption 2: `TextToSpeech` without a UI
**Confirmed:** Yes, Android's `TextToSpeech` (TTS) API is fully supported inside background/foreground services. It requires no UI context and plays directly through the `STREAM_MUSIC` or `STREAM_VOICE_CALL` audio routes.

### Assumption 3: Localhost IPC (Text via WebSockets)
**Confirmed:** A Kotlin `Foreground Service` can safely host a lightweight embedded server (like Ktor or NanoHTTPD) binding to `127.0.0.1`. Termux shares the same network namespace as the Android OS, so connections from the CLI to `ws://127.0.0.1:8765` will succeed flawlessly with zero-latency overhead.

## 3. Implementation Requirements

To implement this into the existing AEC Shim, the following Android specifics must be implemented:

### Manifest Permissions
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.INTERNET" /> <!-- For localhost sockets -->

<service
    android:name=".AudioShimService"
    android:foregroundServiceType="microphone"
    android:exported="false" />
```

### Protocol Draft (JSON over WebSocket)
**Client (Codex CLI) -> Server (Shim):**
```json
{ "action": "start_stt" }
{ "action": "stop_stt" }
{ "action": "tts_speak", "text": "Here is the code you requested." }
```

**Server (Shim) -> Client (Codex CLI):**
```json
{ "event": "stt_partial", "text": "how do I" }
{ "event": "stt_final", "text": "how do I write a bash script" }
{ "event": "tts_complete" }
```

## 4. Conclusion
Approach A (The Native Android Shim) is verified as technically viable on Android 14/16 and is vastly superior to `termux-api`. It provides the Codex CLI with hardware-accelerated speech engines while keeping the Rust codebase clean and completely devoid of complex audio encoding logic.
