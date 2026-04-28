# Devbox TTS-STT Test Process

This process validates the local Termux `tts-stt` path. It does not use the
OpenAI Realtime API. It does use the normal Codex CLI text path on the phone
when running full-loop tests.

Use this process when changing the mirrored skill under
`support/termux-skills/tts-stt`, when replacing the live phone skill, or when
retuning timing.

## Test Conditions

- Keep Bluetooth disconnected unless explicitly testing Bluetooth routing.
- Keep the phone unlocked, Termux visible, and the device near the devbox speaker.
- Start every run from a clean voice state.
- Save every harness summary JSON under `/tmp` with a descriptive name.
- Treat raw STT and full-loop behavior as separate signals. Raw STT can pass
  while full-loop STT fails after TTS output.
- Treat shim STT and Termux STT as separate signals. Full-shim mode must not
  leave `termux-speech-to-text` or Termux API SpeechToText helpers running.
- Treat shim TTS and Termux API TTS as separate signals. Current Pixel 9
  evidence prefers shim TTS; direct `termux-tts-speak` can hang even when shim
  TTS is audible and reports completion.

## Required Tools

- SSH access from the devbox to the Android device.
- A local audio player such as `pw-play`.
- Kokoro model files and a Python environment that can import `kokoro_onnx`.
- The on-device `tts-stt` skill installed at `$HOME/.codex/skills/tts-stt`.
- The Android AEC shim installed, running, and listening on
  `ws://127.0.0.1:8765/v1/text-voice` for preferred local TTS.

Use placeholders in public docs and scripts:

```sh
export SSH_TARGET=android-device-ssh-alias
export SSH_CONFIG=/path/to/ssh_config
export PYTHON_WITH_KOKORO=/path/to/python-with-kokoro
export FIXTURE_DIR=/tmp/pixel9-text-voice-kokoro-expanded-fixtures
```

## Generate Kokoro Fixtures

Generate the standard expanded fixture set:

```sh
"$PYTHON_WITH_KOKORO" scripts/autotest_text_voice_kokoro.py \
  --case-set expanded \
  --generate-only \
  --out-dir "$FIXTURE_DIR"
```

If the Kokoro model is not in a default path, add:

```sh
--kokoro-model /path/to/kokoro-v1.0.onnx \
--kokoro-voices /path/to/voices-v1.0.bin
```

Generate a deterministic stop phrase clip used by full-loop tests:

```sh
"$PYTHON_WITH_KOKORO" - <<'PY'
from pathlib import Path
import os
import sys

sys.path.insert(0, "scripts")
import autotest_text_voice_kokoro as k

class Args:
    kokoro_model = ""
    kokoro_voices = ""
    voice = "af_sarah"

out = Path(os.environ["FIXTURE_DIR"]) / "clips"
out.mkdir(parents=True, exist_ok=True)
args = Args()
model_path = k._resolve_path(args.kokoro_model, "KOKORO_MODEL", k.KOKORO_MODEL_CANDIDATES, "kokoro_model")
voices_path = k._resolve_path(args.kokoro_voices, "KOKORO_VOICES", k.KOKORO_VOICES_CANDIDATES, "kokoro_voices")
model = k._load_kokoro(model_path, voices_path)
text = "stop voice mode"
result = model.create(text, voice=args.voice)
audio_bytes, sample_rate, channels = k._coerce_audio(result)
k._write_wav(out / "99-stop_voice_mode.wav", audio_bytes, sample_rate, channels)
(out / "99-stop_voice_mode.txt").write_text(text + "\n", encoding="utf-8")
print(out / "99-stop_voice_mode.wav")
PY
```

## Clean Preflight

Before every test pass:

```sh
ssh -F "$SSH_CONFIG" "$SSH_TARGET" '
  sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" cleanup
'

ssh -F "$SSH_CONFIG" "$SSH_TARGET" '
  sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" status
  printf "\n--- voice/api processes ---\n"
  ps -ef | grep -E "tts_stt_loop|termux-speech-to-text|termux-tts-speak|termux-api|codex exec" | grep -v grep || true
'
```

Expected result: `not running` and no voice/API helper processes.

## No-Speech Baseline

Measure the Android recognizer no-speech window before playback tests:

```sh
python3 scripts/autotest_termux_tts_stt_skill.py \
  --ssh-target "$SSH_TARGET" \
  --ssh-config "$SSH_CONFIG" \
  --baseline-only \
  --ready-text 'status: listening' \
  --remote-command 'PYTHONUNBUFFERED=1 timeout 100 sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" --timeout-seconds 30 --post-speech-delay 6 start "Testing quiet listening baseline."' \
  --summary /tmp/pixel9-termux-tts-stt-baseline-summary.json
```

The current Pixel 9 evidence showed short, variable no-speech windows:
approximately 6.1 seconds on the first listen and 3.3 seconds on the second.
This is Android/Termux SpeechRecognizer behavior, not a Python loop timeout.

## Raw STT Calibration

Run raw STT before full-loop testing. This isolates Android STT from Codex reply
generation and TTS recovery.

Start with forced shim STT:

```sh
python3 scripts/autotest_termux_tts_stt_skill.py \
  --ssh-target "$SSH_TARGET" \
  --ssh-config "$SSH_CONFIG" \
  --settle-ms 1000 \
  --remote-command 'PYTHONUNBUFFERED=1 timeout 45 sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" --stt-backend shim stt-check --post-speech-delay 0' \
  --clip "$FIXTURE_DIR/clips/01-smoke_current_task.wav" \
  --expected-file "$FIXTURE_DIR/clips/01-smoke_current_task.txt" \
  --min-recall 0.85 \
  --summary /tmp/pixel9-shim-stt-raw-smoke-current-summary.json
```

If forced shim STT fails, rerun the same clip with `--stt-backend termux` to
separate shim regressions from room/audio/playback issues.

```sh
python3 scripts/autotest_termux_tts_stt_skill.py \
  --ssh-target "$SSH_TARGET" \
  --ssh-config "$SSH_CONFIG" \
  --settle-ms 1000 \
  --remote-command 'PYTHONUNBUFFERED=1 timeout 45 sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" stt-check --post-speech-delay 0' \
  --clip "$FIXTURE_DIR/clips/01-smoke_current_task.wav" \
  --expected-file "$FIXTURE_DIR/clips/01-smoke_current_task.txt" \
  --min-recall 0.85 \
  --summary /tmp/pixel9-termux-tts-stt-raw-smoke-current-summary.json
```

Then run a longer realistic command:

```sh
python3 scripts/autotest_termux_tts_stt_skill.py \
  --ssh-target "$SSH_TARGET" \
  --ssh-config "$SSH_CONFIG" \
  --settle-ms 1000 \
  --remote-command 'PYTHONUNBUFFERED=1 timeout 45 sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" stt-check --post-speech-delay 0' \
  --clip "$FIXTURE_DIR/clips/03-long_instruction.wav" \
  --expected-file "$FIXTURE_DIR/clips/03-long_instruction.txt" \
  --min-recall 0.85 \
  --summary /tmp/pixel9-termux-tts-stt-raw-long-instruction-summary.json
```

Known passing baseline: both realistic raw STT clips reached 1.0 word recall
with `--settle-ms 1000` and Bluetooth disconnected.

## One-Shot TTS Calibration

Verify shim TTS audibility before full-loop tests:

```sh
ssh -F "$SSH_CONFIG" "$SSH_TARGET" '
  sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" \
    --tts-backend shim \
    say "Skill speech is routed through the shim. Please confirm if you hear this."
'
```

Expected result: audible phone speech, `tts complete on shim`, and no lingering
voice/API helper processes after `status`.

If shim TTS is audible but `termux-tts-speak` hangs or is silent, keep
`--tts-backend auto` or `--tts-backend shim`. Use `--tts-backend termux` only
for fallback diagnostics.

## Full Multi-Turn Test

Run the full `tts-stt` loop after raw STT passes. This validates STT, Codex text
reply generation, TTS playback, bounded TTS drain, post-TTS recovery,
re-arming, and stop cleanup. Replace `--cwd "$HOME"` with the relevant repo
path for project-specific prompts.

Start with the full-shim target path:

```sh
python3 scripts/autotest_termux_tts_stt_skill.py \
  --ssh-target "$SSH_TARGET" \
  --ssh-config "$SSH_CONFIG" \
  --ready-text 'status: listening' \
  --settle-ms 1000 \
  --remote-command 'PYTHONUNBUFFERED=1 timeout 300 sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" --cwd "$HOME" --tts-backend shim --stt-backend shim --timeout-seconds 240 --post-speech-delay 6 --post-tts-recovery 3 start "Testing full shim tts stt."' \
  --turn "summarize|$FIXTURE_DIR/clips/01-smoke_current_task.wav|Codex summarize the current task and wait for my next instruction" \
  --turn "risk|$FIXTURE_DIR/clips/03-long_instruction.wav|Review the deployment notes identify the riskiest step and tell me what to test next" \
  --turn "stop|$FIXTURE_DIR/clips/99-stop_voice_mode.wav|stop voice" \
  --min-recall 0.85 \
  --summary /tmp/pixel9-shim-tts-stt-multiturn-summary.json
```

If full-shim mode fails while raw shim STT passes, run the proven hybrid
fallback as the control:

```sh
python3 scripts/autotest_termux_tts_stt_skill.py \
  --ssh-target "$SSH_TARGET" \
  --ssh-config "$SSH_CONFIG" \
  --ready-text 'status: listening' \
  --settle-ms 1000 \
  --remote-command 'PYTHONUNBUFFERED=1 timeout 300 sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" --cwd "$HOME" --tts-backend shim --timeout-seconds 240 --post-speech-delay 6 --post-tts-recovery 3 start "Testing multi turn tts stt."' \
  --turn "summarize|$FIXTURE_DIR/clips/01-smoke_current_task.wav|Codex summarize the current task and wait for my next instruction" \
  --turn "risk|$FIXTURE_DIR/clips/03-long_instruction.wav|Review the deployment notes identify the riskiest step and tell me what to test next" \
  --turn "stop|$FIXTURE_DIR/clips/99-stop_voice_mode.wav|stop voice" \
  --min-recall 0.85 \
  --summary /tmp/pixel9-termux-tts-stt-multiturn-summary.json
```

Known evidence with shim TTS and `--post-tts-recovery 3`:

- Forced raw shim STT on `01-smoke_current_task.wav`: 11/11 matched words, 1.0
  recall, no lingering voice/API helpers.
- Forced full-shim two-turn smoke
  (`--tts-backend shim --stt-backend shim`): summarize 11/11 matched words,
  stop phrase accepted as `stop voice mode`, no lingering voice/API helpers.
- Forced full-shim three-turn run 1: summarize 11/11, long deployment-notes
  command 15/15, stop phrase accepted as `stop voice mode`, no lingering
  voice/API helpers.
- Forced full-shim three-turn run 2: summarize 11/11, long deployment-notes
  command 15/15, stop phrase accepted as `stop voice mode`, no lingering
  voice/API helpers.
- Forced full-shim silence recovery: first listen returned no transcript after
  about 5.3 seconds, retry attempt captured `stop voice mode` with 1.0 recall,
  and cleanup left no voice/API helpers.
- `summarize`: 11/11 matched words, 1.0 recall.
- `risk`: 15/15 matched words, 1.0 recall in a targeted long-command run.
- Default `auto` backend long-command smoke also passed the 0.85 threshold
  with 0.867 recall; do not treat long STT as exact-word deterministic.
- `stop`: accepted by the skill if recognized as either `stop` or `stop voice`;
  harness expectations may need to match the exact generated stop fixture.
- Final status: not running, no voice/API helper processes.

## Acceptance Criteria

- Cleanup/status preflight reports no running session and no voice/API helpers.
- No-speech baseline records listener windows in the summary.
- Raw STT realistic clips pass with word recall at or above 0.85.
- Full multi-turn test plays all turns and passes every turn at or above 0.85.
- Full-shim tests do not spawn or leave `termux-speech-to-text` or Termux API
  SpeechToText helpers.
- Normal turns log `tts_complete`; with shim TTS, logs should include
  `tts_complete: shim reported completion`.
- The stop phrase exits cleanly.
- Post-test status reports no `tts_stt_loop`, `termux-speech-to-text`,
  `termux-tts-speak`, `termux-api`, or `codex exec` helpers.

## Interpreting Failures

- If raw STT fails, tune playback level, phone placement, fixture phrase, or
  recognizer settle timing before changing the full loop.
- If raw STT passes but full-loop STT fails after TTS, tune post-TTS recovery
  and response brevity first. The current Pixel 9 shim-TTS baseline is 3s.
- If no-speech windows are only a few seconds, do not treat that as the Python
  loop ending early. The recognizer is returning no-match.
- If a test is interrupted, run `cleanup` and verify process state before the
  next attempt.

## Next Hardening Test

The next pass can measure whether 3s is the smallest reliable shim-TTS
post-TTS recovery gap. Keep 3s as the stable baseline because it now has two
consecutive forced full-shim three-turn passes plus a forced full-shim
silence-recovery pass.

1. Keep Bluetooth disconnected and reuse the same fixtures.
2. Run the full multi-turn test with `--post-tts-recovery 3.0`.
3. If it passes twice, try `2.5`.
4. If either fails, rerun the current default `3.0` as the control.
5. Do not change the default until at least two consecutive full-loop passes
   exist at the candidate value.
