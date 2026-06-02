# STTS Controller Modularization Plan

`support/termux-skills/stts/scripts/stts_loop.py` is now large enough that it
should be treated as release-critical technical debt. The current shape is
acceptable for alpha validation, but new STTS and WWS features should not keep
accumulating in one controller script indefinitely.

## Recommendation

Do not perform a broad refactor immediately before publishing a working release.
The current release path should prioritize stability, artifact freshness, and
device validation.

After the current release is locked, split `stts_loop.py` conservatively into
small modules while preserving the public command surface:

```text
stts start
stts talk
stts wake
stts stop
stts status
stts diag
stts session
```

This should be a mechanical extraction first, not a rewrite.

## Rationale

The issue is not line count by itself. The risk is that unrelated failure
domains now live in one file:

- Android Bridge WebSocket client behavior.
- STT, TTS, and audio recovery timing.
- Wake-word model import, detection, diagnostics, and re-arm behavior.
- tmux session, pane, PID, and FIFO lifecycle.
- Codex prompt construction and `codex exec` invocation.
- Notes workspace behavior, Android open/share commands, and spoken reply
  sanitization.
- CLI parsing and command routing.

When these concerns are interleaved, small changes become harder to review,
harder to test, and more likely to cause release regressions in unrelated
paths.

## Proposed Module Boundaries

Suggested post-release split:

```text
support/termux-skills/stts/scripts/stts/
  __init__.py
  bridge_client.py
  cli.py
  codex_exec.py
  diagnostics.py
  notes.py
  session.py
  voice_turn.py
  wake.py
```

Responsibilities:

- `bridge_client.py`: `/v1/text-voice` WebSocket connection, actions, events,
  reconnect behavior, and socket error normalization.
- `session.py`: tmux workspace, activity pane, PID file, command FIFO, status,
  start, attach, and stop lifecycle.
- `voice_turn.py`: one STTS turn from listen to transcript to Codex reply to
  TTS playback.
- `wake.py`: WWS command handling, wake model import/validation, wake loop,
  post-wake STT, re-arm behavior, and wake diagnostics.
- `codex_exec.py`: prompt assembly, `codex exec` invocation, model options,
  sandbox/workdir/add-dir handling, and source/tool-note capture.
- `notes.py`: `~/codex_notes` discovery, path handling, Android open/share
  helpers, and TTS-friendly reply sanitization.
- `diagnostics.py`: doctor/status helpers, audio volume summaries, runtime
  snapshots, and validation helpers that do not belong to core flow.
- `cli.py`: argparse, command dispatch, and compatibility wrappers.

Keep `stts_loop.py` temporarily as a thin entry point that imports `cli.main()`.

## Refactor Gates

Before extraction, add or preserve lightweight tests around behavior that has
recently been fragile:

- TTS-friendly note/path reply sanitization.
- `~/codex_notes` workspace discovery.
- `codex exec` sandbox/add-dir argument construction.
- Wake command option parsing.
- Session command FIFO send/read behavior.
- Non-billable mocked STTS turn flow.
- Wake diagnostic classification for timeout, socket closed, audio busy, and
  re-arm failure.

The goal is not exhaustive test coverage. The goal is enough coverage that a
mechanical extraction can prove it did not change the high-risk behavior.

## Suggested Sequence

1. Freeze behavior after the current validated release.
2. Add focused tests for the gates above.
3. Extract pure helpers first: notes, path sanitization, wake option parsing,
   prompt construction.
4. Extract Bridge client behavior.
5. Extract session/tmux lifecycle.
6. Extract voice-turn and wake loops last.
7. Validate on Pixel6a after each large extraction step.
8. Rebuild artifacts only after the full extraction branch passes local and
   device validation.

## Non-goals

- Do not rename public commands during this cleanup.
- Do not change STTS/WWS timing defaults as part of modularization.
- Do not change Android Bridge behavior as part of this refactor.
- Do not convert the controller into a framework or service manager.

This cleanup is intended to reduce release risk and make future STTS/WWS
development easier, not to redesign the product.

