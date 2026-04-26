# Supplementary Architecture Requirements: Wake Word & OS Stability

This document outlines the first-class Wake Word integration strategy, the microphone handoff mechanics, and OS-level stability requirements for the Android AEC Shim.

## 1. Wake Word Engine & Hardware Acceleration

The shim will use **openWakeWord** to allow for free, synthetic generation of custom wake words (e.g., "Hey Codex") without manual data collection.

*   **Phase 1 (CPU):** Implement the openWakeWord `.onnx` model using `onnxruntime-android`. This establishes the audio ingestion and thresholding loop.
*   **Phase 2 (NPU/LiteRT):** Convert the model to `.tflite` via the openWakeWord Colab. Use Google Play Services LiteRT (`play-services-tflite-java`) and the `NnApiDelegate` to offload the audio classification loop entirely to the Pixel 9's Tensor G4 NPU. This mimics the zero-drain profile of Google's locked DSP.

## 2. The Microphone Handoff Protocol (API 33+)

**Strict Rule:** The Foreground Service (FGS) must never allow `AudioRecord` (wake word) and `SpeechRecognizer` (active dictation) to collide over the microphone lock.

To guarantee zero dropped syllables when the user speaks fluidly (e.g., "Hey Codex run script"), use the **API 33 Pipe strategy**:
1.  **Passive Mode:** The FGS holds the `AudioRecord` lock, feeding audio to the wake word engine while maintaining the last 2 seconds in a Ring Buffer.
2.  **Detection:** Wake word triggers. Emits `{"event": "wake_word_detected"}` to the CLI.
3.  **The Pipe:** Open a Pipe (`ParcelFileDescriptor.createPipe()`). 
4.  **Handoff:** Pass the read-end of the pipe to `SpeechRecognizer.startListening()` using `RecognizerIntent.EXTRA_AUDIO_SOURCE`. 
5.  **Flush:** Immediately flush the 2-second historical Ring Buffer into the write-end of the pipe, followed seamlessly by the live microphone stream.
6.  **Completion:** Upon terminal STT state (`stt_final` or `stt_error`), close the pipe and `SpeechRecognizer.destroy()`. The FGS resumes passive wake word listening.

## 3. Audio Focus & Routing

*   Require `android.permission.MODIFY_AUDIO_SETTINGS`.
*   Both TTS playback and active STT dictation must be wrapped in an `AudioManager` focus request (`AUDIOFOCUS_GAIN_TRANSIENT`). This ducks background music.
*   Always call `abandonAudioFocus()` upon `tts_complete` or returning to passive mode.
*   Force TTS audio routing to `STREAM_MUSIC`. Do not use `STREAM_VOICE_CALL`, as it triggers telecom echo cancellation artifacts.

## 4. Lifecycle & IPC Stability

*   **Timeout Handling:** `SpeechRecognizer` throws `ERROR_SPEECH_TIMEOUT` (HTTP 6) upon silence. The State Machine must handle this gracefully, tearing down the pipe, emitting an `stt_timeout` event, and returning to `WAKE_WORD` mode.
*   **Debouncing:** The shim must debounce `onPartialResults` (max 4 emissions per second) to prevent WebSocket saturation.
*   **Socket Zombies:** Implement RFC 6455 Ping/Pong frames. If the WebSocket drops or Ping times out (>15s), the Shim must cleanly release `AudioManager` focus, destroy `SpeechRecognizer`, and return to a safe `IDLE` state to prevent orphaned hardware locks.
