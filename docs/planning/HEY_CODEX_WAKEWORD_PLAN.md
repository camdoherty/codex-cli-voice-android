# Hey Codex Wake Word Plan

This plan describes how CCVA/STTS should support a custom `Hey Codex` wake
word using the existing Codex Bridge/openWakeWord-style ONNX path.

## Goal

Support `Hey Codex` as an optional STTS wake profile that can be imported,
probed, validated, and run on Android without making model training part of the
core CCVA installer.

## Recommended Scope

For the next implementation pass, CCVA should focus on:

- Importing custom wake profiles into Codex Bridge app-private storage.
- Validating model files and profile metadata.
- Running synthetic probe matrices against the Bridge `wake_onnx_probe` path.
- Running live Pixel validation through `stts wake --profile hey_codex`.
- Documenting external training workflows.

Do not make CCVA responsible for training wake models yet. Training should stay
an external advanced workflow until the import/probe/validation surface is
stable.

## Model Training

Train `Hey Codex` as an openWakeWord-compatible ONNX model outside CCVA.

Useful upstream references:

- `https://github.com/dscripka/openWakeWord`
- `https://github.com/dscripka/openWakeWord/blob/main/notebooks/automatic_model_training.ipynb`
- `https://openwakeword.com/`

The training workflow should produce:

```text
hey_codex.onnx
melspectrogram.onnx
embedding_model.onnx
profile.json
```

Training requirements:

- Positive examples for `hey codex`.
- Large negative/background examples.
- Near-negative examples:
  - `hey coding`
  - `hey cortex`
  - `hey codec`
  - `hey coders`
  - `okay codex`
  - `hey code`
  - ordinary sentences containing `Codex`
- Multiple speakers, distances, and room conditions.
- Export to ONNX compatible with the current Bridge inference path.

`Hey Codex` is a reasonable candidate phrase: it has enough syllables to be
trainable and `Codex` is uncommon. The main risk is false accepts from similar
code/coding/cortex/codec phrases.

## Profile Shape

The user-facing profile should be simple:

```json
{
  "id": "hey_codex",
  "label": "hey codex",
  "modelType": "onnx",
  "threshold": 0.997,
  "inputGainDb": 6,
  "cooldownMs": 1500
}
```

Bridge should own app-private storage paths. Users and agents should not need
to hand-write `/data/user/0/...` paths.

After import, Bridge can expand the profile internally to:

```json
{
  "id": "hey_codex",
  "label": "hey codex",
  "modelType": "onnx",
  "modelPath": "<bridge-app-storage>/wakeword_models/hey_codex/hey_codex.onnx",
  "melspectrogramPath": "<bridge-app-storage>/wakeword_models/hey_codex/melspectrogram.onnx",
  "embeddingPath": "<bridge-app-storage>/wakeword_models/hey_codex/embedding_model.onnx",
  "sampleRate": 16000,
  "frameMs": 80,
  "threshold": 0.997,
  "inputGainDb": 6,
  "cooldownMs": 1500,
  "licenseAcknowledged": true
}
```

## Desired UX

Target commands:

```sh
stts wake import ./hey_codex
stts wake validate hey_codex
stts wake probe hey_codex --thresholds 0.997,0.995 --gains 0,6,9
stts wake --profile hey_codex
```

Possible alpha fallback:

```sh
stts wake --wake-profile ./hey_codex/profile.json
```

## Import Boundary

Termux must not write directly into Bridge app-private storage on non-rooted
Android. Model ingestion should happen through the local Bridge control channel.

Recommended import behavior:

1. STTS reads local model files from Termux/shared storage.
2. STTS sends files to Bridge over `/v1/text-voice`.
3. Bridge writes files into its own app-private storage.
4. Bridge returns the stored profile id and validation result.

This is the same sandbox-safe boundary used for current wake model ingestion.

## Validation Plan

### Automated Synthetic Probe

Run `wake_onnx_probe` against a fixed fixture set:

```sh
scripts/wws_onnx_probe_matrix.py \
  --thresholds 0.997,0.995 \
  --gains 0,6,9 \
  --clip-root <hey-codex-fixtures>
```

Fixture set should include:

- `hey codex`
- `hey codex what can you do`
- `hey codex start voice mode`
- `okay codex`
- `hey codec`
- `hey cortex`
- `hey coding`
- `hey code`
- unrelated speech/noise clips

Record:

- positive hit rate
- near-negative false accepts
- min positive score
- max negative score
- raw/gained RMS and peak dBFS
- clipped sample count
- probe runtime

### Live Pixel Validation

Run on Pixel6a before any public claim:

- normal voice, screen awake
- quiet voice, screen awake
- normal voice, screen off
- fan/HVAC noise
- near phrases
- re-arm after a successful STTS turn
- long idle followed by wake

Pass criteria:

- reliable `Hey Codex` detection in normal voice
- acceptable quiet-voice detection
- no obvious false accepts from near phrases in short tests
- no heavy clipping at default gain
- stable re-arm after a full turn

## Default Tuning

Initial defaults should mirror the current WWS evidence:

```text
threshold=0.997
inputGainDb=6
cooldownMs=1500
```

Do not lower threshold or raise gain by default unless synthetic and live tests
show a clear improvement without more false accepts or clipping.

## Release Guidance

`Hey Codex` should ship only when:

- Import/validate/probe commands work from a fresh install.
- Pixel6a live validation passes.
- The profile can be disabled or switched back to `Hey Jarvis`.
- Documentation clearly says custom wake-word quality depends on model quality,
  phrase choice, mic placement, noise, and Android device behavior.

