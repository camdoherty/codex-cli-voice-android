#!/usr/bin/env python3
"""Autonomous Kokoro playback test for the Android shim /v1/text-voice path.

The harness synthesizes deterministic speech clips on the devbox, waits until
the Pixel shim reports STT is listening, plays each clip through local speakers,
and scores the returned transcript with simple word recall.
"""

from __future__ import annotations

import argparse
import array
import collections
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
from typing import Any
from urllib.parse import urlparse
import wave

from smoke_text_voice_ws import _connect, _recv_frame, _send_frame


DEFAULT_URL = "ws://127.0.0.1:18765/v1/text-voice"
DEFAULT_TTS_REPLY = "Ready for your next instruction."
DEFAULT_VOICE = "af_sarah"

KOKORO_MODEL_CANDIDATES = (
    "~/models/kokoro/kokoro-v1.0.onnx",
    "~/models/kokoro/kokoro-v1.0.fp16.onnx",
    "~/models/kokoro/kokoro-v1.0.int8.onnx",
)
KOKORO_VOICES_CANDIDATES = (
    "~/models/kokoro/voices-v1.0.bin",
)

SMOKE_CASES = (
    {
        "id": "smoke_current_task",
        "text": "Codex, summarize the current task and wait for my next instruction.",
    },
)

EXPANDED_CASES = SMOKE_CASES + (
    {"id": "short_command", "text": "Open the task inbox and read the stuck item."},
    {
        "id": "long_instruction",
        "text": "Review the deployment notes, identify the riskiest step, and tell me what to test next.",
    },
    {"id": "correction_phrase", "text": "Correction, do not deploy yet, only prepare the checklist."},
    {"id": "cancellation_phrase", "text": "Cancel the current voice turn and return to idle."},
    {"id": "punctuation_command", "text": "Create three labels: urgent, blocked, and follow up."},
    {"id": "quiet_voice", "text": "This is a quieter command to check recognition tolerance.", "volume": 0.45},
    {"id": "faster_speech", "text": "Quickly check status then wait for confirmation."},
    {"id": "wake_style", "text": "Hey Pixel, I need an agent to inspect the todo inbox."},
    {"id": "noisy_room_phrase", "text": "Ignore the background noise and capture this command."},
    {"id": "silence_after_command", "text": "Record this command and then expect silence."},
    {"id": "device_control", "text": "Turn on notifications and copy the summary to the clipboard."},
)


def _resolve_path(explicit: str, env_name: str, candidates: tuple[str, ...], label: str) -> Path:
    raw = explicit or os.environ.get(env_name, "")
    if raw:
        path = Path(raw).expanduser()
        if path.exists():
            return path
        raise RuntimeError(f"{label} not found: {path}")
    for candidate in candidates:
        path = Path(candidate).expanduser()
        if path.exists():
            return path
    raise RuntimeError(f"{label} not found. Set {env_name} or pass --{label.replace('_', '-')}.")


def _load_kokoro(model_path: Path, voices_path: Path) -> Any:
    try:
        import kokoro_onnx  # type: ignore
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "kokoro_onnx is not importable. Run with a Python environment that has kokoro_onnx installed, "
            "or set PYTHON_WITH_KOKORO in the test docs."
        ) from exc
    return kokoro_onnx.Kokoro(str(model_path), str(voices_path))


def _coerce_audio(result: Any) -> tuple[bytes, int, int]:
    if isinstance(result, (bytes, bytearray)):
        return bytes(result), 0, 0
    if not isinstance(result, tuple) or len(result) < 2:
        raise RuntimeError(f"unsupported Kokoro output: {type(result)}")

    try:
        import numpy as np  # type: ignore
    except ModuleNotFoundError as exc:
        raise RuntimeError("numpy is required for Kokoro tuple audio output") from exc

    audio = np.asarray(result[0])
    sample_rate = int(result[1])
    if audio.dtype != np.int16:
        audio = np.clip(audio, -1.0, 1.0)
        audio = (audio * 32767).astype(np.int16)
    channels = 1 if audio.ndim == 1 else int(audio.shape[1])
    return audio.tobytes(), sample_rate, channels


def _write_wav(path: Path, audio_bytes: bytes, sample_rate: int, channels: int) -> None:
    if sample_rate == 0:
        path.write_bytes(audio_bytes)
        return
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(channels)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(audio_bytes)


def _scale_wav_in_place(path: Path, factor: float) -> None:
    if factor == 1.0:
        return

    with wave.open(str(path), "rb") as wav:
        params = wav.getparams()
        frames = wav.readframes(wav.getnframes())
    if params.sampwidth != 2:
        raise RuntimeError(f"volume scaling only supports 16-bit WAV files: {path}")
    samples = array.array("h")
    samples.frombytes(frames)
    if sys.byteorder != "little":
        samples.byteswap()
    for index, sample in enumerate(samples):
        samples[index] = max(-32768, min(32767, int(sample * factor)))
    if sys.byteorder != "little":
        samples.byteswap()
    scaled = samples.tobytes()
    with wave.open(str(path), "wb") as wav:
        wav.setparams(params)
        wav.writeframes(scaled)


def _cases(kind: str) -> tuple[dict[str, Any], ...]:
    return SMOKE_CASES if kind == "smoke" else EXPANDED_CASES


def generate_clips(args: argparse.Namespace, out_dir: Path) -> list[dict[str, Any]]:
    model_path = _resolve_path(args.kokoro_model, "KOKORO_MODEL", KOKORO_MODEL_CANDIDATES, "kokoro_model")
    voices_path = _resolve_path(args.kokoro_voices, "KOKORO_VOICES", KOKORO_VOICES_CANDIDATES, "kokoro_voices")
    model = _load_kokoro(model_path, voices_path)
    clips_dir = out_dir / "clips"
    clips_dir.mkdir(parents=True, exist_ok=True)

    clips: list[dict[str, Any]] = []
    for index, case in enumerate(_cases(args.case_set), start=1):
        case_id = str(case["id"])
        text = str(case["text"])
        voice = str(case.get("voice", args.voice))
        wav_path = clips_dir / f"{index:02d}-{case_id}.wav"
        txt_path = clips_dir / f"{index:02d}-{case_id}.txt"
        if not wav_path.exists() or args.regenerate:
            result = model.create(text, voice=voice)
            audio_bytes, sample_rate, channels = _coerce_audio(result)
            _write_wav(wav_path, audio_bytes, sample_rate, channels)
            _scale_wav_in_place(wav_path, float(case.get("volume", 1.0)))
        txt_path.write_text(text + "\n", encoding="utf-8")
        clips.append(
            {
                "id": case_id,
                "text": text,
                "voice": voice,
                "path": str(wav_path),
                "volume": float(case.get("volume", 1.0)),
            }
        )

    (out_dir / "clips.json").write_text(json.dumps(clips, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return clips


def _select_player(requested: str) -> list[str]:
    if requested != "auto":
        return requested.split()
    for candidate in ("pw-play", "paplay", "mpv", "aplay"):
        if shutil.which(candidate):
            if candidate == "mpv":
                return ["mpv", "--no-video", "--audio-display=no", "--really-quiet"]
            return [candidate]
    raise RuntimeError("no audio player found; install pw-play, paplay, mpv, or aplay")


def _play_clip(player: list[str], wav_path: Path) -> tuple[float, float]:
    started = time.time()
    subprocess.run([*player, str(wav_path)], check=True)
    return started, time.time()


def _recv_json(sock: socket.socket, frames: list[dict[str, Any]], started: float) -> dict[str, Any]:
    while True:
        raw = _recv_frame(sock)
        if not raw:
            continue
        payload = json.loads(raw)
        payload["_elapsedMs"] = round((time.time() - started) * 1000)
        frames.append(payload)
        print(json.dumps(payload, sort_keys=True), flush=True)
        return payload


def _words(text: str) -> list[str]:
    return re.findall(r"[a-z0-9']+", text.lower())


def _word_recall(expected: str, actual: str) -> dict[str, Any]:
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


def run_case(args: argparse.Namespace, clip: dict[str, Any], player: list[str], out_dir: Path) -> dict[str, Any]:
    case_id = str(clip["id"])
    case_dir = out_dir / "cases"
    case_dir.mkdir(parents=True, exist_ok=True)
    frames_path = case_dir / f"{case_id}.frames.jsonl"
    started = time.time()
    frames: list[dict[str, Any]] = []
    playback_started = 0.0
    playback_ended = 0.0
    stt_final = ""
    stt_error = ""
    tts_complete = False

    sock = _connect(args.url, args.timeout_s)
    try:
        _recv_json(sock, frames, started)
        _send_frame(sock, json.dumps({"id": f"{case_id}-status", "action": "status"}))
        _recv_json(sock, frames, started)
        _send_frame(
            sock,
            json.dumps(
                {
                    "id": f"{case_id}-stt",
                    "action": "start_stt",
                    "offlineOnly": args.offline_only,
                    "timeoutMs": int(args.timeout_s * 1000),
                }
            ),
        )

        while True:
            payload = _recv_json(sock, frames, started)
            event = payload.get("event")
            if event == "stt_listening" and playback_started == 0.0:
                if args.listen_settle_ms > 0:
                    time.sleep(args.listen_settle_ms / 1000)
                playback_started, playback_ended = _play_clip(player, Path(str(clip["path"])))
            elif event == "stt_final":
                stt_final = str(payload.get("text", ""))
                break
            elif event == "error":
                stt_error = str(payload.get("code", "error"))
                break

        if args.tts_reply:
            _send_frame(
                sock,
                json.dumps({"id": f"{case_id}-tts", "action": "tts_speak", "text": args.tts_reply}),
            )
            while True:
                payload = _recv_json(sock, frames, started)
                event = payload.get("event")
                if event == "tts_complete":
                    tts_complete = True
                    break
                if event == "error":
                    break
    finally:
        sock.close()

    with frames_path.open("w", encoding="utf-8") as handle:
        for frame in frames:
            handle.write(json.dumps(frame, sort_keys=True) + "\n")

    recall = _word_recall(str(clip["text"]), stt_final)
    final_after_playback_ms = None
    final_frames = [frame for frame in frames if frame.get("event") == "stt_final"]
    if final_frames and playback_ended:
        final_after_playback_ms = round((started + (final_frames[-1]["_elapsedMs"] / 1000) - playback_ended) * 1000)

    status_frames = [frame for frame in frames if frame.get("event") == "status"]
    status_ok = bool(status_frames and status_frames[-1].get("sttAvailable") and status_frames[-1].get("ttsReady"))
    summary = {
        "id": case_id,
        "expected": clip["text"],
        "actual": stt_final,
        "stt_error": stt_error,
        "stt_final": bool(stt_final),
        "tts_complete": tts_complete,
        "status_ok": status_ok,
        "playback_started_at": playback_started,
        "playback_ended_at": playback_ended,
        "listen_settle_ms": args.listen_settle_ms,
        "final_after_playback_ms": final_after_playback_ms,
        "frames_path": str(frames_path),
        **recall,
    }
    summary["passed"] = (
        status_ok
        and bool(stt_final)
        and summary["word_recall"] >= args.min_recall
        and (not args.tts_reply or tts_complete)
        and not stt_error
    )
    return summary


def _default_out_dir() -> Path:
    return Path("/tmp") / f"pixel9-text-voice-kokoro-{time.strftime('%Y%m%d-%H%M%S')}"


def main() -> int:
    parser = argparse.ArgumentParser(description="Run Kokoro playback tests against /v1/text-voice.")
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--timeout-s", type=float, default=15.0)
    parser.add_argument("--case-set", choices=["smoke", "expanded"], default="smoke")
    parser.add_argument("--out-dir", default="")
    parser.add_argument("--voice", default=DEFAULT_VOICE)
    parser.add_argument("--kokoro-model", default="")
    parser.add_argument("--kokoro-voices", default="")
    parser.add_argument("--player", default="auto", help="auto or a command prefix such as 'pw-play'")
    parser.add_argument("--offline-only", action="store_true")
    parser.add_argument("--regenerate", action="store_true")
    parser.add_argument("--generate-only", action="store_true")
    parser.add_argument("--tts-reply", default=DEFAULT_TTS_REPLY)
    parser.add_argument("--min-recall", type=float, default=0.75)
    parser.add_argument(
        "--listen-settle-ms",
        type=int,
        default=500,
        help="Delay after stt_listening before playback, to avoid clipping recognizer startup.",
    )
    args = parser.parse_args()

    out_dir = Path(args.out_dir).expanduser() if args.out_dir else _default_out_dir()
    out_dir.mkdir(parents=True, exist_ok=True)
    clips = generate_clips(args, out_dir)
    if args.generate_only:
        print(json.dumps({"out_dir": str(out_dir), "clips": clips}, indent=2, sort_keys=True))
        return 0

    player = _select_player(args.player)
    summaries = []
    for clip in clips:
        summaries.append(run_case(args, clip, player, out_dir))

    result = {
        "url": args.url,
        "case_set": args.case_set,
        "out_dir": str(out_dir),
        "player": player,
        "passed": all(item["passed"] for item in summaries),
        "cases": summaries,
    }
    (out_dir / "summary.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
