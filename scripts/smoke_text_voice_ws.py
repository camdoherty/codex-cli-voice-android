#!/usr/bin/env python3
"""Dependency-free smoke client for the Android shim /v1/text-voice WebSocket."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import socket
import struct
import time
from urllib.parse import urlparse


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    data = bytearray()
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise RuntimeError("socket closed")
        data.extend(chunk)
    return bytes(data)


def _send_frame(sock: socket.socket, text: str) -> None:
    payload = text.encode("utf-8")
    mask = os.urandom(4)
    header = bytearray([0x81])
    length = len(payload)
    if length < 126:
        header.append(0x80 | length)
    elif length <= 0xFFFF:
        header.append(0x80 | 126)
        header.extend(struct.pack("!H", length))
    else:
        header.append(0x80 | 127)
        header.extend(struct.pack("!Q", length))
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    sock.sendall(bytes(header) + mask + masked)


def _recv_frame(sock: socket.socket) -> str:
    first, second = _recv_exact(sock, 2)
    opcode = first & 0x0F
    masked = bool(second & 0x80)
    length = second & 0x7F
    if length == 126:
        length = struct.unpack("!H", _recv_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", _recv_exact(sock, 8))[0]
    mask = _recv_exact(sock, 4) if masked else b""
    payload = _recv_exact(sock, length) if length else b""
    if masked:
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    if opcode == 0x8:
        raise RuntimeError("websocket closed")
    if opcode != 0x1:
        return ""
    return payload.decode("utf-8", errors="replace")


def _connect(url: str, timeout_s: float) -> socket.socket:
    parsed = urlparse(url)
    if parsed.scheme != "ws":
        raise RuntimeError("only ws:// URLs are supported")
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port or 80
    path = parsed.path or "/"
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    sock = socket.create_connection((host, port), timeout=timeout_s)
    request = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "\r\n"
    )
    sock.sendall(request.encode("ascii"))
    response = sock.recv(4096).decode("iso-8859-1", errors="replace")
    if " 101 " not in response.splitlines()[0]:
        raise RuntimeError(f"websocket handshake failed: {response.splitlines()[0] if response else 'empty response'}")
    expected = base64.b64encode(
        hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
    ).decode("ascii")
    if expected not in response:
        raise RuntimeError("websocket accept key mismatch")
    sock.settimeout(timeout_s)
    return sock


def main() -> int:
    parser = argparse.ArgumentParser(description="Smoke-test the Android shim text voice WebSocket.")
    parser.add_argument("--url", default="ws://127.0.0.1:8765/v1/text-voice")
    parser.add_argument("--timeout-s", type=float, default=15.0)
    parser.add_argument("--tts", default="", help="Optional text to speak after status.")
    parser.add_argument("--start-stt", action="store_true", help="Start one STT turn and wait for stt_final/error.")
    parser.add_argument("--offline-only", action="store_true")
    args = parser.parse_args()

    sock = _connect(args.url, args.timeout_s)
    frames: list[dict[str, object]] = []
    started = time.time()

    def recv_json() -> dict[str, object]:
        while True:
            raw = _recv_frame(sock)
            if not raw:
                continue
            payload = json.loads(raw)
            payload["_elapsedMs"] = round((time.time() - started) * 1000)
            frames.append(payload)
            print(json.dumps(payload, sort_keys=True), flush=True)
            return payload

    recv_json()
    _send_frame(sock, json.dumps({"id": "status-1", "action": "status"}))
    recv_json()

    if args.start_stt:
        _send_frame(sock, json.dumps({
            "id": "stt-1",
            "action": "start_stt",
            "offlineOnly": args.offline_only,
            "timeoutMs": int(args.timeout_s * 1000),
        }))
        while True:
            payload = recv_json()
            if payload.get("event") in {"stt_final", "error"}:
                break

    if args.tts:
        _send_frame(sock, json.dumps({"id": "tts-1", "action": "tts_speak", "text": args.tts}))
        while True:
            payload = recv_json()
            if payload.get("event") in {"tts_complete", "error"}:
                break

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
