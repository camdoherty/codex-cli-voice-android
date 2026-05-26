#!/usr/bin/env python3
"""Host harness for the on-device Termux stts skill.

It starts the Android skill over SSH, waits until the remote controller reports
that STT is listening, then plays a prepared WAV clip from the host speakers
and scores the resulting transcript.
"""

from __future__ import annotations

import argparse
import collections
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


DEFAULT_REMOTE_COMMAND = (
    'PYTHONUNBUFFERED=1 timeout 25 sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" '
    "stt-check --post-speech-delay 0"
)
DEFAULT_REMOTE_CLEANUP_COMMAND = 'sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" cleanup'
DEFAULT_REMOTE_STATUS_COMMAND = (
    'sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" status; '
    'printf "\\n--- voice/api processes ---\\n"; '
    'ps -ef | grep -E "stts_loop|termux-speech-to-text|termux-tts-speak|termux-api|codex exec" | grep -v grep || true'
)


@dataclass(frozen=True)
class Turn:
    label: str
    clip: Path
    expected: str


def _words(text: str) -> list[str]:
    return re.findall(r"[a-z0-9']+", text.lower())


def _word_recall(expected: str, actual: str) -> dict[str, object]:
    expected_words = _words(expected)
    actual_words = _words(actual)
    remaining = collections.Counter(actual_words)
    hits = 0
    for word in expected_words:
        if remaining[word] > 0:
            hits += 1
            remaining[word] -= 1
    recall = hits / len(expected_words) if expected_words else 0.0
    return {
        "expected_words": len(expected_words),
        "actual_words": len(actual_words),
        "matched_words": hits,
        "word_recall": round(recall, 3),
    }


def _select_player(raw: str) -> list[str]:
    return raw.split()


def _ssh_command(args: argparse.Namespace, remote_command: str | None = None) -> list[str]:
    cmd = ["ssh"]
    if args.ssh_config:
        cmd.extend(["-F", args.ssh_config])
    cmd.append(args.ssh_target)
    cmd.append(remote_command if remote_command is not None else args.remote_command)
    return cmd


def _ssh_run(args: argparse.Namespace, remote_command: str, label: str) -> dict[str, object]:
    started = time.time()
    completed = subprocess.run(
        _ssh_command(args, remote_command),
        text=True,
        capture_output=True,
        check=False,
    )
    output = completed.stdout
    if completed.stderr:
        output += completed.stderr
    print(f"--- {label} rc={completed.returncode} ---", flush=True)
    if output.strip():
        print(output.rstrip(), flush=True)
    return {
        "label": label,
        "return_code": completed.returncode,
        "elapsed_ms": round((time.time() - started) * 1000),
        "output": output,
    }


def _parse_turn(raw: str) -> Turn:
    parts = raw.split("|", 2)
    if len(parts) != 3:
        raise RuntimeError("--turn must use: label|/path/to/clip.wav|expected transcript")
    label, clip_raw, expected = (part.strip() for part in parts)
    if not label:
        raise RuntimeError("--turn label cannot be empty")
    clip = Path(clip_raw).expanduser()
    if not clip.exists():
        raise RuntimeError(f"turn clip not found: {clip}")
    if not expected:
        raise RuntimeError(f"--turn expected text cannot be empty: {label}")
    return Turn(label=label, clip=clip, expected=expected)


def _load_turns(args: argparse.Namespace) -> list[Turn]:
    if args.turn:
        return [_parse_turn(raw) for raw in args.turn]

    if args.baseline_only:
        return []

    clip = Path(args.clip).expanduser() if args.clip else None
    if clip is None:
        raise RuntimeError("--clip is required unless --turn or --baseline-only is used")
    if not clip.exists():
        raise RuntimeError(f"clip not found: {clip}")
    if args.expected:
        expected = args.expected
    elif args.expected_file:
        expected = Path(args.expected_file).expanduser().read_text(encoding="utf-8").strip()
    else:
        raise RuntimeError("--expected or --expected-file is required unless --turn or --baseline-only is used")
    if not expected:
        raise RuntimeError("expected text is required")
    return [Turn(label="turn-1", clip=clip, expected=expected)]


def _extract_transcripts(lines: list[str]) -> list[str]:
    transcripts: list[str] = []
    for line in lines:
        if line.startswith("transcript:"):
            transcripts.append(line.split(":", 1)[1].strip())
        if line.startswith("user:"):
            transcripts.append(line.split(":", 1)[1].strip())
    return transcripts


def _extract_listen_windows(events: list[dict[str, object]]) -> list[dict[str, object]]:
    windows: list[dict[str, object]] = []
    active: dict[str, object] | None = None
    for event in events:
        line = str(event["line"])
        if line.startswith("status: listening"):
            active = {
                "started_at": event["at"],
                "line": line,
            }
            continue
        if active is not None and line.startswith("status: no transcript"):
            duration_ms = round((float(event["at"]) - float(active["started_at"])) * 1000)
            active = {
                **active,
                "ended_at": event["at"],
                "ended_by": line,
                "duration_ms": duration_ms,
            }
            windows.append(active)
            active = None
    return windows


def _play(player: list[str], clip: Path) -> tuple[float, float]:
    started = time.time()
    subprocess.run([*player, str(clip)], check=True)
    return started, time.time()


def run(args: argparse.Namespace) -> dict[str, object]:
    turns = _load_turns(args)
    player = _select_player(args.player)
    started = time.time()
    lines: list[str] = []
    events: list[dict[str, object]] = []
    play_events: list[dict[str, object]] = []
    remote_checks: list[dict[str, object]] = []
    turn_index = 0

    if args.cleanup_before:
        remote_checks.append(_ssh_run(args, args.remote_cleanup_command, "cleanup-before"))
    if args.status_before:
        remote_checks.append(_ssh_run(args, args.remote_status_command, "status-before"))

    proc = subprocess.Popen(
        _ssh_command(args),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    try:
        assert proc.stdout is not None
        for raw_line in proc.stdout:
            line = raw_line.rstrip("\n")
            lines.append(line)
            event_time = time.time()
            events.append({"at": event_time, "elapsed_ms": round((event_time - started) * 1000), "line": line})
            print(line, flush=True)
            if args.baseline_only:
                continue
            if turn_index < len(turns) and args.ready_text in line:
                turn = turns[turn_index]
                if args.settle_ms > 0:
                    time.sleep(args.settle_ms / 1000)
                play_started, play_ended = _play(player, turn.clip)
                play_events.append(
                    {
                        "turn": turn.label,
                        "clip": str(turn.clip),
                        "expected": turn.expected,
                        "started_at": play_started,
                        "ended_at": play_ended,
                        "started_elapsed_ms": round((play_started - started) * 1000),
                        "ended_elapsed_ms": round((play_ended - started) * 1000),
                        "duration_ms": round((play_ended - play_started) * 1000),
                    }
                )
                turn_index += 1
        return_code = proc.wait(timeout=2)
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                proc.kill()
        if args.cleanup_after:
            remote_checks.append(_ssh_run(args, args.remote_cleanup_command, "cleanup-after"))
        if args.status_after:
            remote_checks.append(_ssh_run(args, args.remote_status_command, "status-after"))

    transcripts = _extract_transcripts(lines)
    turn_results: list[dict[str, object]] = []
    for index, turn in enumerate(turns):
        transcript = transcripts[index] if index < len(transcripts) else ""
        recall = _word_recall(turn.expected, transcript)
        turn_results.append(
            {
                "turn": turn.label,
                "clip": str(turn.clip),
                "expected": turn.expected,
                "transcript": transcript,
                "passed": bool(transcript) and recall["word_recall"] >= args.min_recall,
                **recall,
            }
        )
    listen_windows = _extract_listen_windows(events)
    all_turns_passed = all(bool(turn["passed"]) for turn in turn_results) if turn_results else True
    played_all_turns = len(play_events) == len(turns)
    baseline_passed = args.baseline_only and return_code == 0 and bool(listen_windows)
    result = {
        "passed": (
            baseline_passed
            if args.baseline_only
            else return_code == 0 and played_all_turns and all_turns_passed
        ),
        "return_code": return_code,
        "ssh_target": args.ssh_target,
        "player": player,
        "ready_text": args.ready_text,
        "settle_ms": args.settle_ms,
        "play_count": len(play_events),
        "played_all_turns": played_all_turns,
        "play_events": play_events,
        "events": events,
        "transcripts": transcripts,
        "turn_results": turn_results,
        "listen_windows": listen_windows,
        "remote_checks": remote_checks,
        "elapsed_ms": round((time.time() - started) * 1000),
        "lines": lines,
    }
    if len(turn_results) == 1:
        result.update(
            {
                "clip": turn_results[0]["clip"],
                "expected": turn_results[0]["expected"],
                "transcript": turn_results[0]["transcript"],
                "word_recall": turn_results[0]["word_recall"],
                "matched_words": turn_results[0]["matched_words"],
                "expected_words": turn_results[0]["expected_words"],
                "actual_words": turn_results[0]["actual_words"],
            }
        )
    if args.summary:
        Path(args.summary).expanduser().write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True), flush=True)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Synchronized host playback test for the Termux stts skill.")
    parser.add_argument("--ssh-target", default=os.environ.get("SSH_TARGET", "android-device-ssh-alias"))
    parser.add_argument("--ssh-config", default=os.environ.get("SSH_CONFIG", ""))
    parser.add_argument("--remote-command", default=DEFAULT_REMOTE_COMMAND)
    parser.add_argument("--remote-cleanup-command", default=DEFAULT_REMOTE_CLEANUP_COMMAND)
    parser.add_argument("--remote-status-command", default=DEFAULT_REMOTE_STATUS_COMMAND)
    parser.add_argument("--ready-text", default="status: listening for raw STT")
    parser.add_argument("--settle-ms", type=int, default=2500)
    parser.add_argument("--player", default="pw-play")
    parser.add_argument("--clip", default="")
    parser.add_argument("--expected", default="")
    parser.add_argument("--expected-file", default="")
    parser.add_argument(
        "--turn",
        action="append",
        default=[],
        help="Ordered turn as label|/path/to/clip.wav|expected transcript. May be repeated.",
    )
    parser.add_argument("--min-recall", type=float, default=0.75)
    parser.add_argument("--summary", default="")
    parser.add_argument("--baseline-only", action="store_true", help="Run without playback and report no-speech listen windows.")
    parser.add_argument("--cleanup-before", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--cleanup-after", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--status-before", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--status-after", action=argparse.BooleanOptionalAction, default=True)
    args = parser.parse_args()
    if not args.baseline_only and not args.turn and not args.clip:
        parser.error("--clip is required unless --turn or --baseline-only is used")
    if args.clip and not args.expected and not args.expected_file:
        parser.error("--expected or --expected-file is required with --clip")
    try:
        result = run(args)
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        print(f"autotest-termux-stts: {exc}", file=sys.stderr)
        return 1
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
