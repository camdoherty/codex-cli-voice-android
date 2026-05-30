# STTS Alpha Live Test Plan

This plan validates what STTS can do out of the box in live human use. It is
not a build test and it does not use the OpenAI Realtime API.

## Setup

- Start with the phone unlocked unless the batch explicitly tests screen-off
  behavior.
- Keep the shim app open/running.
- Keep Bluetooth disconnected unless explicitly testing routing.
- Start the monitor from the host before each batch:

```sh
scripts/stts_alpha_monitor.sh --target pixel6a-lan --batch baseline
```

Run phone commands from the host in a separate terminal:

```sh
ssh -F ~/.ssh/config pixel6a-lan 'stts cleanup'
ssh -F ~/.ssh/config pixel6a-lan 'stts doctor'
```

## Result Labels

Use these short labels when reporting each test:

- `pass`: heard useful spoken reply.
- `pass-rough`: worked, but transcript/timing/reply was awkward.
- `silent`: command exited or appeared done but no speech was heard.
- `hung`: command did not return or the phone stayed busy.
- `wrong`: audible reply did not match the spoken request.

## Batch 1: Baseline

1. Run:

```sh
ssh -F ~/.ssh/config pixel6a-lan 'stts cleanup && stts doctor && stts status'
```

Expected: doctor passes, status says not running.

2. Run:

```sh
ssh -F ~/.ssh/config pixel6a-lan 'stts say "STTS baseline speech test."'
```

Expected: audible speech and command exits.

3. Run:

```sh
ssh -F ~/.ssh/config pixel6a-lan 'timeout 45 stts stt-check'
```

Say: `summarize this voice test`

Expected: printed transcript is close enough to the phrase.

## Batch 2: One-Shot Talk

Run each command separately:

```sh
ssh -F ~/.ssh/config pixel6a-lan 'timeout 90 stts talk'
```

Speak one prompt after the phone starts listening.

Test prompts:

1. `Summarize what this project does in one sentence.`
2. `Give me the next three things to test.`
3. `Explain the difference between STTS and realtime voice mode.`
4. `Create a short todo list for improving STTS.`

Expected: one useful spoken Codex reply per run.

## Batch 3: Wake Once

Run:

```sh
ssh -F ~/.ssh/config pixel6a-lan 'timeout 120 stts wake --once'
```

Say wake phrase, then the test prompt:

1. `Hey Jarvis. What should I test next?`
2. `Hey Jarvis. Draft a short GitHub release note for this voice mode.`
3. `Hey Jarvis. Say one sentence confirming you heard me.`

Expected: wake phrase triggers listening and produces one spoken reply.

## Batch 4: Wake Word Screen-Off Baseline

Run:

```sh
ssh -F ~/.ssh/config pixel6a-lan 'stts wake'
```

1. Leave the phone screen off for more than 60 seconds.
2. Confirm there is no periodic wake cue or minute rollover.
3. Say: `Hey Jarvis. What can you do?`
4. Wait for the spoken reply.
5. Leave the phone screen off for more than 60 seconds again.

Expected: one wake listener remains armed past 60 seconds, the cue plays only
after wake detection, the turn completes, and wake mode re-arms. This was
validated on Pixel6a for 0.135; do not generalize it to all Android devices
without separate validation.

## Batch 5: Multi-Turn Talk Mode

Run before each spoken turn:

```sh
ssh -F ~/.ssh/config pixel6a-lan 'timeout 240 stts talk'
```

Speak these in sequence, waiting for each reply:

1. `Help me plan a short test of this Android voice mode.`
2. `Make it shorter and focus only on failure cases.`
3. `Turn that into a checklist.`
4. `Stop voice mode.`

Expected: context carries across turns and stop exits cleanly.

## Batch 6: Edge Cases

Run a fresh `stts talk` for each:

1. Say nothing.
2. Say: `status`
3. Speak for 30 seconds.
4. Say: `Actually delete that and say this instead, STTS needs clearer state feedback.`
5. Run `stts talk` three times in a row.

Expected: no wedged shim, no stale STT/TTS helper processes, and silent cases
are visible in monitor output.

## Release-Blocking Findings

Block the release if any of these appear repeatedly:

- Silent failures without a visible command error.
- Shim remains busy after the command exits.
- `stts cleanup` cannot restore a clean state.
- `stts talk` loses multi-turn context between turns.
- Wake mode falsely appears to pass when no reply was spoken.
