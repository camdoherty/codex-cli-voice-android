#!/usr/bin/env python3
"""Run a compact offline wake-word probe matrix through Codex Bridge.

This uses the Bridge /v1/text-voice wake_onnx_probe action. It does not open
the microphone and does not run Codex. Use it to compare wake threshold/input
gain choices against a fixed WAV fixture set before doing live human tests.
"""

from __future__ import annotations

import argparse
import base64
import collections
import json
from pathlib import Path
import subprocess
import tempfile
import time
from typing import Any

from smoke_text_voice_ws import _connect, _recv_frame, _send_frame


DEFAULT_URL = "ws://127.0.0.1:18765/v1/text-voice"
DEFAULT_CLIP_ROOT = "/home/cad/dev/pixel9/tmp/ccva-wws-kokoro-gain-test/clips"
WAKE_PROFILE_ID = "hey_jarvis_dev"
WAKE_MODEL_APP_DIR = (
    "/data/user/0/io.github.codex_cli_voice_android.aecshim/files/"
    f"wakeword_models/{WAKE_PROFILE_ID}"
)


def parse_csv_floats(raw: str) -> list[float]:
    values = [float(item.strip()) for item in raw.split(",") if item.strip()]
    if not values:
        raise argparse.ArgumentTypeError("expected at least one comma-separated number")
    return values


def wake_profile(threshold: float, input_gain_db: float) -> dict[str, object]:
    return {
        "id": WAKE_PROFILE_ID,
        "label": "hey jarvis",
        "modelType": "onnx",
        "modelPath": f"{WAKE_MODEL_APP_DIR}/hey_jarvis_v0.1.onnx",
        "melspectrogramPath": f"{WAKE_MODEL_APP_DIR}/melspectrogram.onnx",
        "embeddingPath": f"{WAKE_MODEL_APP_DIR}/embedding_model.onnx",
        "sampleRate": 16000,
        "frameMs": 80,
        "threshold": threshold,
        "inputGainDb": input_gain_db,
        "cooldownMs": 1500,
        "licenseAcknowledged": True,
    }


def classify_clip(path: Path) -> str:
    stem = path.stem.lower()
    if stem == "hey_jarvis" or stem.startswith("hey_jarvis_"):
        return "positive"
    return "negative"


def iter_clips(root: Path, limit: int) -> list[Path]:
    clips = sorted(root.glob("**/*.wav"))
    if limit > 0:
        clips = clips[:limit]
    if not clips:
        raise RuntimeError(f"no WAV clips found under {root}")
    return clips


def recv_json(sock, started: float) -> dict[str, Any]:
    while True:
        raw = _recv_frame(sock)
        if not raw:
            continue
        payload = json.loads(raw)
        payload["_elapsedMs"] = round((time.time() - started) * 1000)
        return payload


def adb_forward(serial: str, local_port: int, remote_port: int) -> None:
    subprocess.run(
        ["adb", "-s", serial, "forward", f"tcp:{local_port}", f"tcp:{remote_port}"],
        check=True,
    )


def converted_wav(clip: Path, tmp_dir: Path) -> Path:
    out = tmp_dir / f"{clip.parent.name}-{clip.stem}.wav"
    subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(clip),
            "-ac",
            "1",
            "-ar",
            "16000",
            "-sample_fmt",
            "s16",
            str(out),
        ],
        check=True,
    )
    return out


def run_probe(sock, clip: Path, probe_wav: Path, threshold: float, gain: float, started: float) -> dict[str, Any]:
    request_id = f"probe-{time.time_ns()}"
    wav64 = base64.b64encode(probe_wav.read_bytes()).decode("ascii")
    _send_frame(
        sock,
        json.dumps(
            {
                "id": request_id,
                "action": "wake_onnx_probe",
                "profile": wake_profile(threshold, gain),
                "audioWavBase64": wav64,
            }
        ),
    )
    while True:
        event = recv_json(sock, started)
        if event.get("id") == request_id:
            row = {
                "clip": str(clip),
                "probe_wav": str(probe_wav),
                "clip_name": clip.stem,
                "speaker": clip.parent.name,
                "kind": classify_clip(clip),
                "threshold": threshold,
                "inputGainDb": gain,
                **event,
            }
            return row


def connect(url: str, timeout_s: float, started: float):
    sock = _connect(url, timeout_s)
    recv_json(sock, started)
    return sock


def summarize(rows: list[dict[str, Any]]) -> None:
    groups: dict[tuple[float, float], list[dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        groups[(float(row["threshold"]), float(row["inputGainDb"]))].append(row)

    print("threshold gain pos_hit/pos neg_hit/neg min_pos max_neg clipped max_ms")
    for (threshold, gain), items in sorted(groups.items()):
        positives = [row for row in items if row["kind"] == "positive"]
        negatives = [row for row in items if row["kind"] == "negative"]
        pos_hits = sum(1 for row in positives if row.get("triggered") is True)
        neg_hits = sum(1 for row in negatives if row.get("triggered") is True)
        pos_scores = [float(row.get("maxScore") or 0.0) for row in positives]
        neg_scores = [float(row.get("maxScore") or 0.0) for row in negatives]
        clipped = sum(int(row.get("clippedSamples") or 0) for row in items)
        max_ms = max(int(row.get("elapsedMs") or 0) for row in items)
        min_pos = min(pos_scores) if pos_scores else 0.0
        max_neg = max(neg_scores) if neg_scores else 0.0
        print(
            f"{threshold:.3f} {gain:g} "
            f"{pos_hits}/{len(positives)} {neg_hits}/{len(negatives)} "
            f"{min_pos:.6f} {max_neg:.6f} {clipped} {max_ms}"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="Run WWS ONNX probe matrix through Codex Bridge.")
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--clip-root", default=DEFAULT_CLIP_ROOT)
    parser.add_argument("--thresholds", type=parse_csv_floats, default=parse_csv_floats("0.997,0.995"))
    parser.add_argument("--gains", type=parse_csv_floats, default=parse_csv_floats("0,6,9,12"))
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--out", default="")
    parser.add_argument("--timeout-s", type=float, default=60.0)
    parser.add_argument("--adb-serial", default="", help="Optional adb serial; forwards local tcp:18765 to device tcp:8765.")
    args = parser.parse_args()

    if args.adb_serial:
        adb_forward(args.adb_serial, 18765, 8765)

    clip_root = Path(args.clip_root).expanduser()
    clips = iter_clips(clip_root, args.limit)
    out_path = Path(args.out).expanduser() if args.out else Path("tmp") / f"wws-probe-{int(time.time())}.jsonl"
    out_path.parent.mkdir(parents=True, exist_ok=True)

    started = time.time()
    sock = None
    rows: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="ccva-wws-probe-") as tmp:
        tmp_dir = Path(tmp)
        converted = {clip: converted_wav(clip, tmp_dir) for clip in clips}
        try:
            sock = connect(args.url, args.timeout_s, started)
            for threshold in args.thresholds:
                for gain in args.gains:
                    for clip in clips:
                        for attempt in range(2):
                            try:
                                row = run_probe(sock, clip, converted[clip], threshold, gain, started)
                                break
                            except RuntimeError as exc:
                                if attempt > 0 or "socket closed" not in str(exc).lower():
                                    raise
                                try:
                                    sock.close()
                                except Exception:
                                    pass
                                sock = connect(args.url, args.timeout_s, started)
                        row["probeAttempt"] = attempt + 1
                        rows.append(row)
                        with out_path.open("a", encoding="utf-8") as handle:
                            handle.write(json.dumps(row, sort_keys=True) + "\n")
        finally:
            if sock is not None:
                sock.close()

    summarize(rows)
    print(f"jsonl={out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
