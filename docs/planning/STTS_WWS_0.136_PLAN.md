# STTS WWS 0.136 Planning Notes

These notes track candidate wake-word hardening work after the 0.135 alpha
stabilization pass. They are not 0.135 release blockers.

## Current Direction

- Keep 0.135 focused on the working WWS baseline:
  - long bounded wake-listen window
  - cue only after wake detection
  - socket reconnect hardening
  - Pixel6a screen-off/locked validation documented as device-specific
- Treat 0.136 as the tuning and optimization pass for wake reliability,
  diagnostics, and idle efficiency.

## Candidate 0.136 Work

- Add live WWS diagnostics:
  - input RMS/peak
  - max wake score
  - detection latency
  - threshold used
  - screen/lock state when practical
  - 0.135 follow-up starts with passive Bridge diagnostics only; no gain,
    threshold, or energy-gate behavior changes.
- Evaluate wake threshold tuning:
  - current default is conservative
  - test lower values such as `0.995` before changing the default
  - keep `--wake-threshold` as an alpha override
- Evaluate optional software input gain before wake inference:
  - expose as a configurable dB value
  - test for clipping and false accepts
  - do not apply to Android SpeechRecognizer unless separately supported
- Evaluate optional energy/VAD pre-gate:
  - skip ONNX wake-model inference during quiet/silent frames
  - keep the microphone open and continue reading audio
  - use a hang window so speech onset is not clipped
  - keep disabled by default until validated

## Energy Gate Notes

An energy gate would compute short-window audio energy before running wake-word
inference. If the signal is below a configured threshold, Bridge can skip ONNX
classification for that frame.

Potential benefit:

- Lower idle CPU during silence.
- Possibly fewer false accepts from very quiet background noise.

Risks:

- Quiet or distant wake phrases may be missed.
- Typing, fans, music, or room noise can still pass an energy gate.
- Skipping frames may affect streaming wake features if implemented too
  aggressively.

Initial shape:

```text
read audio chunk
compute RMS/peak
if below gate and outside hang window:
  skip wake classifier
else:
  run wake classifier
```

Possible flags:

```text
--wake-input-gain-db 3
--wake-energy-gate-dbfs -55
--wake-energy-gate-hang-ms 500
```

## Validation Matrix

- Silence: no false wake events over a bounded idle run.
- Normal voice near phone: reliable detection.
- Normal voice at realistic distance: reliable detection.
- Soft voice: acceptable detection before changing defaults.
- Screen off/locked on Pixel6a: no regression from 0.135 behavior.
- Background noise:
  - typing
  - fan/HVAC
  - quiet music or video nearby
- Long idle:
  - 15 minutes
  - 30 minutes
  - re-arm after a successful turn

## Release Criteria

- Diagnostics are available before tuning defaults.
- Any threshold/gain/energy default change has Pixel6a evidence.
- Energy gate remains opt-in unless it clearly improves idle behavior without
  hurting soft or distant wake detection.
- Documentation clearly separates Pixel6a validation from Android-wide claims.
