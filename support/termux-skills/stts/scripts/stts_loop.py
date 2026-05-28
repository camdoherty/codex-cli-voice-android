#!/data/data/com.termux/files/usr/bin/python
import argparse
import base64
import hashlib
import json
import os
import re
import select
import shlex
import signal
import shutil
import socket
import stat
import struct
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path


DEFAULT_OPENER = "Voice activated."
DEFAULT_POST_SPEECH_DELAY_SECONDS = 6
DEFAULT_POST_TTS_RECOVERY_SECONDS = 1.0
DEFAULT_TTS_DRAIN_TIMEOUT_SECONDS = 0.0
DEFAULT_REPLACEMENT_TTS_RECOVERY_SECONDS = 2.0
DEFAULT_SESSION_TIMEOUT_SECONDS = 60 * 60
DEFAULT_EMPTY_RETRIES = 1
DEFAULT_EMPTY_LISTEN_DELAY_SECONDS = 4.0
DEFAULT_TTS_STREAM = "MUSIC"
DEFAULT_TTS_BACKEND = "auto"
DEFAULT_STT_BACKEND = "auto"
DEFAULT_SHIM_STT_TIMEOUT_SECONDS = 12.0
DEFAULT_CODEX_MODEL = os.environ.get("CODEX_STTS_CODEX_MODEL", "gpt-5.4-mini")
DEFAULT_CODEX_REASONING_EFFORT = os.environ.get("CODEX_STTS_CODEX_REASONING_EFFORT", "low")
SHIM_TEXT_VOICE_HOST = "127.0.0.1"
SHIM_TEXT_VOICE_PORT = 8765
SHIM_TEXT_VOICE_PATH = "/v1/text-voice"
WAKE_PROFILE_ID = "hey_jarvis_dev"
WAKE_PHRASE = "hey jarvis"
WAKE_THRESHOLD = 0.997
WAKE_COOLDOWN_MS = 1500
WAKE_MAX_RUNTIME_SECONDS = 60 * 60
WAKE_REARM_DELAY_SECONDS = 1.0
WAKE_MODEL_RELEASE_BASE_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
WAKE_MODEL_CACHE_DIR = Path(
    os.environ.get(
        "CODEX_STTS_WAKE_MODEL_CACHE_DIR",
        str(Path.home() / ".cache" / "ccva-wake-models" / "openwakeword_hey_jarvis"),
    )
)
WAKE_MODEL_APP_DIR = (
    "/data/user/0/io.github.codex_cli_voice_android.aecshim/files/"
    f"wakeword_models/{WAKE_PROFILE_ID}"
)
WAKE_MODEL_FILES = {
    "hey_jarvis_v0.1.onnx": "94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb",
    "melspectrogram.onnx": "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f",
    "embedding_model.onnx": "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f",
}
STOP_PHRASES = {
    "stop",
    "please stop",
    "stop listening",
    "stop loop",
    "stop session",
    "stop the session",
    "stop the voice",
    "stop the voice loop",
    "stop the voice mode",
    "stop voice",
    "stop voice loop",
    "stop voice mode",
    "end voice",
    "end voice mode",
    "end session",
    "end the session",
    "goodbye",
    "bye",
    "quit",
}
INCOMPLETE_TRAILING_WORDS = {
    "a",
    "an",
    "and",
    "for",
    "i",
    "if",
    "it",
    "of",
    "or",
    "s",
    "that",
    "the",
    "this",
    "to",
    "want",
    "when",
    "while",
    "with",
}
RUNTIME_DIR = Path(
    os.environ.get(
        "CODEX_STTS_RUNTIME_DIR",
        str(Path.home() / ".local" / "state" / "codex-stts"),
    )
)
PID_PATH = RUNTIME_DIR / "session.pid"
COMMAND_FIFO_PATH = RUNTIME_DIR / "session.fifo"
ACTIVITY_PANE_PATH = RUNTIME_DIR / "activity-pane.txt"
TURN_DIR = RUNTIME_DIR / "turns"
TMUX_SESSION_NAME = os.environ.get("CODEX_STTS_TMUX_SESSION", "ccva-stts")
ACTIVE_TTS_PROCS: list[subprocess.Popen[str]] = []
LAST_TTS_COMPLETED = False


@dataclass
class CodexTurnResult:
    reply: str
    source_notes: list[str]


def ensure_command(name: str) -> None:
    if shutil.which(name) is None:
        raise RuntimeError(f"missing command: {name}")


def run_command(
    cmd: list[str],
    *,
    input_text: str | None = None,
    timeout_seconds: float | None = None,
) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            cmd,
            input=input_text,
            text=True,
            capture_output=True,
            check=False,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"{cmd[0]} timed out after {timeout_seconds:g}s") from exc


def start_termux_api_if_available() -> None:
    if not shutil.which("termux-api-start"):
        return
    api_result = run_command(["termux-api-start"])
    if api_result.returncode != 0:
        raise RuntimeError(api_result.stderr.strip() or "termux-api-start failed")


def get_volume_state() -> list[dict]:
    ensure_command("termux-volume")
    result = run_command(["termux-volume"], timeout_seconds=5)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "termux-volume failed")
    try:
        data = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("termux-volume returned invalid JSON") from exc
    return data if isinstance(data, list) else []


def find_volume_stream(volume_state: list[dict], stream_name: str) -> dict | None:
    wanted = stream_name.lower()
    for entry in volume_state:
        if str(entry.get("stream", "")).lower() == wanted:
            return entry
    return None


def sanitize_for_tts(text: str) -> str:
    sanitized = text.replace("\u2019", "'").replace("\u2018", "'")
    sanitized = sanitized.replace("\u201c", '"').replace("\u201d", '"')
    sanitized = re.sub(r"\s+", " ", sanitized)
    return sanitized.strip()


class ShimVoiceError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class ShimUnavailable(ShimVoiceError):
    pass


class WebSocketTextClient:
    def __init__(self, host: str, port: int, path: str, timeout_seconds: float) -> None:
        self.sock = socket.create_connection((host, port), timeout=timeout_seconds)
        self.sock.settimeout(timeout_seconds)
        self.buffer = bytearray()
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "\r\n"
        )
        self.sock.sendall(request.encode("ascii"))
        raw_response = bytearray()
        while b"\r\n\r\n" not in raw_response:
            raw_response.extend(self.sock.recv(4096))
        head, buffered = bytes(raw_response).split(b"\r\n\r\n", 1)
        self.buffer.extend(buffered)
        response = head.decode("iso-8859-1", errors="replace")
        if " 101 " not in response.splitlines()[0]:
            raise RuntimeError(f"shim websocket handshake failed: {response.splitlines()[0]}")
        expected = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
        ).decode("ascii")
        if expected not in response:
            raise RuntimeError("shim websocket accept key mismatch")

    def close(self) -> None:
        self.sock.close()

    def recv_exact(self, count: int) -> bytes:
        data = bytearray()
        if self.buffer:
            chunk = self.buffer[:count]
            del self.buffer[:count]
            data.extend(chunk)
        while len(data) < count:
            chunk = self.sock.recv(count - len(data))
            if not chunk:
                raise RuntimeError("shim websocket closed")
            data.extend(chunk)
        return bytes(data)

    def send_json(self, payload: dict[str, object]) -> None:
        text = json.dumps(payload, ensure_ascii=True)
        data = text.encode("utf-8")
        mask = os.urandom(4)
        header = bytearray([0x81])
        if len(data) < 126:
            header.append(0x80 | len(data))
        elif len(data) <= 0xFFFF:
            header.append(0x80 | 126)
            header.extend(struct.pack("!H", len(data)))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack("!Q", len(data)))
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(data))
        self.sock.sendall(bytes(header) + mask + masked)

    def _send_pong(self, payload: bytes) -> None:
        mask = os.urandom(4)
        header = bytearray([0x8A])
        header.append(0x80 | len(payload))
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        self.sock.sendall(bytes(header) + mask + masked)

    def recv_json(self) -> dict[str, object]:
        while True:
            first, second = self.recv_exact(2)
            opcode = first & 0x0F
            masked = bool(second & 0x80)
            length = second & 0x7F
            if length == 126:
                length = struct.unpack("!H", self.recv_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self.recv_exact(8))[0]
            mask = self.recv_exact(4) if masked else b""
            payload = self.recv_exact(length) if length else b""
            if masked:
                payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
            if opcode == 0x8:
                raise RuntimeError("shim websocket closed")
            if opcode == 0x9:
                self._send_pong(payload)
                continue
            if opcode != 0x1:
                continue
            return json.loads(payload.decode("utf-8", errors="replace"))

    def recv_json_timeout(self, timeout_seconds: float) -> dict[str, object] | None:
        readable, _, _ = select.select([self.sock], [], [], max(0.0, timeout_seconds))
        if not readable:
            return None
        return self.recv_json()


def say_text_shim(text: str) -> str:
    timeout_seconds = max(8.0, estimate_post_speech_pause(text, DEFAULT_POST_SPEECH_DELAY_SECONDS) + 8.0)
    client = WebSocketTextClient(
        SHIM_TEXT_VOICE_HOST,
        SHIM_TEXT_VOICE_PORT,
        SHIM_TEXT_VOICE_PATH,
        timeout_seconds,
    )
    try:
        client.recv_json()
        request_id = f"tts-{int(time.time() * 1000)}"
        client.send_json({"id": request_id, "action": "tts_speak", "text": text})
        while True:
            event = client.recv_json()
            if event.get("id") not in (request_id, None):
                continue
            if event.get("event") == "tts_complete":
                latency = event.get("latencyMs", "unknown")
                return f"tts complete on shim ({latency} ms)"
            if event.get("event") == "error":
                code = event.get("code", "tts_error")
                message = event.get("message", "shim TTS failed")
                raise RuntimeError(f"{code}: {message}")
    finally:
        client.close()


def say_text_termux(text: str, *, stream_name: str = DEFAULT_TTS_STREAM) -> str:
    ensure_command("termux-tts-speak")
    cmd = ["termux-tts-speak"]
    stream_label = "default"
    if stream_name:
        cmd.extend(["-s", stream_name])
        stream_label = stream_name.lower()
    cmd.append(text)
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
        start_new_session=True,
    )
    ACTIVE_TTS_PROCS.append(proc)
    # Give the Android bridge a moment to accept the request. If the process exits
    # immediately with an error, surface it; otherwise let it run independently.
    time.sleep(0.4)
    return_code = proc.poll()
    if return_code not in (None, 0):
        stderr = ""
        if proc.stderr is not None:
            stderr = proc.stderr.read().strip()
        raise RuntimeError(stderr or "termux-tts-speak failed")
    return f"tts dispatched on {stream_label} stream"


def say_text(
    text: str,
    *,
    stream_name: str = DEFAULT_TTS_STREAM,
    backend: str = DEFAULT_TTS_BACKEND,
) -> str:
    global LAST_TTS_COMPLETED

    spoken = sanitize_for_tts(text)
    LAST_TTS_COMPLETED = False
    if backend in ("auto", "shim"):
        try:
            result = say_text_shim(spoken)
            LAST_TTS_COMPLETED = True
            return result
        except Exception:
            if backend == "shim":
                raise

    return say_text_termux(spoken, stream_name=stream_name)


def protected_process_pids() -> set[int]:
    protected = {os.getpid()}
    pid = os.getppid()
    while pid > 1 and pid not in protected:
        protected.add(pid)
        try:
            stat = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
        except OSError:
            break
        after_name = stat.rsplit(") ", 1)
        if len(after_name) != 2:
            break
        fields = after_name[1].split()
        if len(fields) < 2 or not fields[1].isdigit():
            break
        pid = int(fields[1])
    return protected


def kill_matching_processes(pattern: str, *, exclude_pid: int | None = None) -> int:
    if shutil.which("pgrep") is None:
        return 0
    result = run_command(["pgrep", "-f", pattern])
    if result.returncode not in (0, 1):
        return 0

    killed = 0
    protected = protected_process_pids()
    for raw_pid in result.stdout.split():
        if not raw_pid.isdigit():
            continue
        pid = int(raw_pid)
        if pid in protected or pid == exclude_pid:
            continue
        try:
            os.kill(pid, signal.SIGTERM)
            killed += 1
        except ProcessLookupError:
            pass
    return killed


def cleanup_tts_helpers(grace_seconds: float = 0.25) -> int:
    stopped = 0
    still_tracked: list[subprocess.Popen[str]] = []
    for proc in ACTIVE_TTS_PROCS:
        if proc.poll() is not None:
            continue
        try:
            os.killpg(proc.pid, signal.SIGTERM)
            proc.wait(timeout=grace_seconds)
        except ProcessLookupError:
            pass
        except subprocess.TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=grace_seconds)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                still_tracked.append(proc)
        stopped += 1

    ACTIVE_TTS_PROCS[:] = still_tracked
    stopped += kill_matching_processes("termux-api TextToSpeech")
    return stopped


def prune_tts_helpers() -> None:
    ACTIVE_TTS_PROCS[:] = [proc for proc in ACTIVE_TTS_PROCS if proc.poll() is None]


def emit_optional(path: Path | None, label: str, text: str) -> None:
    line = f"{label}: {text}"
    print(line, flush=True)
    if path is not None:
        append_log(path, line)


def wait_for_tts_boundary(
    minimum_seconds: float,
    drain_timeout_seconds: float,
    transcript_path: Path | None,
) -> None:
    started = time.monotonic()
    minimum_deadline = started + max(0.0, minimum_seconds)
    final_deadline = minimum_deadline + max(0.0, drain_timeout_seconds)

    while True:
        prune_tts_helpers()
        now = time.monotonic()
        if now >= minimum_deadline and not ACTIVE_TTS_PROCS:
            emit_optional(transcript_path, "tts_complete", f"settled after {now - started:.1f}s")
            return
        if now >= minimum_deadline and drain_timeout_seconds <= 0:
            break
        if now >= final_deadline:
            break
        time.sleep(min(0.2, max(0.0, final_deadline - now)))

    stopped = cleanup_tts_helpers()
    elapsed = time.monotonic() - started
    if stopped:
        emit_optional(transcript_path, "tts_complete", f"speech boundary reached; cleaned {stopped} lingering helper(s) after {elapsed:.1f}s")
    else:
        emit_optional(transcript_path, "tts_complete", f"settled after {elapsed:.1f}s")


def pause_after_speech(
    text: str,
    minimum_seconds: int,
    transcript_path: Path,
    recovery_seconds: float,
    drain_timeout_seconds: float,
    next_state: str = "listening",
) -> None:
    global LAST_TTS_COMPLETED

    if LAST_TTS_COMPLETED:
        emit(transcript_path, "tts_complete", "shim reported completion")
        LAST_TTS_COMPLETED = False
    else:
        wait_for_tts_boundary(
            estimate_post_speech_pause(text, minimum_seconds),
            drain_timeout_seconds,
            transcript_path,
        )
    if recovery_seconds > 0:
        emit(transcript_path, "status", f"audio recovery; waiting {recovery_seconds:g}s before {next_state}")
        time.sleep(recovery_seconds)


def wait_after_one_shot_tts(text: str, minimum_seconds: int, drain_timeout_seconds: float) -> None:
    global LAST_TTS_COMPLETED

    if LAST_TTS_COMPLETED:
        emit_optional(None, "tts_complete", "shim reported completion")
        LAST_TTS_COMPLETED = False
        return
    wait_for_tts_boundary(
        estimate_post_speech_pause(text, minimum_seconds),
        drain_timeout_seconds,
        None,
    )


def estimate_post_speech_pause(text: str, minimum_seconds: int) -> float:
    words = len(text.split())
    estimated_seconds = 1.5 + (words / 2.8)
    return max(float(minimum_seconds), estimated_seconds)


def extract_final_transcript(raw_text: str) -> str:
    lines = [line.strip() for line in raw_text.splitlines() if line.strip()]
    if not lines:
        return ""

    deduped: list[str] = []
    for line in lines:
        if line not in deduped:
            deduped.append(line)

    latest = deduped[-1]
    strongest = max(deduped, key=len)
    if len(strongest) >= len(latest) + 4 and normalize_text(latest) in normalize_text(strongest):
        return strongest
    return latest


def extract_transcript_candidates(raw_text: str) -> list[str]:
    candidates: list[str] = []
    for line in raw_text.splitlines():
        candidate = line.strip()
        if candidate and candidate not in candidates:
            candidates.append(candidate)
    return candidates


def looks_incomplete(text: str) -> bool:
    words = normalize_text(text).split()
    return bool(words and words[-1] in INCOMPLETE_TRAILING_WORDS)


def build_incomplete_prompt(text: str) -> str:
    words = normalize_text(text).split()
    if len(words) >= 8:
        return f"I heard {text}, but it sounds like the end was cut off. Please repeat the whole request in a shorter sentence."
    return f"I heard {text}. Please repeat the whole request, not just the ending."


def record_stt_partial(candidate: str, candidates: list[str], transcript_path: Path | None) -> None:
    if not candidate or candidate in candidates:
        return
    candidates.append(candidate)
    if transcript_path is None:
        print(f"stt_partial: {candidate}", flush=True)
    else:
        emit(transcript_path, "stt_partial", candidate)


def emit_stt_status(transcript_path: Path | None, text: str) -> None:
    if transcript_path is None:
        print(f"stt_backend: {text}", flush=True)
    else:
        emit(transcript_path, "stt_backend", text)


def stop_process_group(proc: subprocess.Popen[str], grace_seconds: float = 0.5) -> None:
    try:
        os.killpg(proc.pid, signal.SIGTERM)
        proc.wait(timeout=grace_seconds)
    except ProcessLookupError:
        pass
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait(timeout=grace_seconds)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            pass


def listen_once_termux(
    delay_seconds: float,
    remaining_seconds: float | None = None,
    transcript_path: Path | None = None,
    stream_partials: bool = False,
) -> tuple[str, str]:
    ensure_command("termux-speech-to-text")
    start_termux_api_if_available()
    if delay_seconds > 0:
        if remaining_seconds is not None:
            time.sleep(min(delay_seconds, max(0.0, remaining_seconds)))
        else:
            time.sleep(delay_seconds)
    deadline = None
    if remaining_seconds is not None:
        deadline = time.monotonic() + max(1.0, remaining_seconds)

    proc = subprocess.Popen(
        ["termux-speech-to-text", "-p"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
        start_new_session=True,
    )
    raw_lines: list[str] = []
    candidates: list[str] = []

    try:
        while True:
            wait_seconds = 0.2
            if deadline is not None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    stop_process_group(proc)
                    raw_text = "".join(raw_lines)
                    return extract_final_transcript(raw_text), raw_text
                wait_seconds = min(wait_seconds, remaining)

            readable: list[object] = []
            if proc.stdout is not None:
                readable, _, _ = select.select([proc.stdout], [], [], wait_seconds)
            if readable and proc.stdout is not None:
                line = proc.stdout.readline()
                if line:
                    raw_lines.append(line)
                    if stream_partials:
                        record_stt_partial(line.strip(), candidates, transcript_path)
                    continue

            if proc.poll() is not None:
                break

        if proc.stdout is not None:
            for line in proc.stdout.read().splitlines():
                raw_lines.append(f"{line}\n")
                if stream_partials:
                    record_stt_partial(line.strip(), candidates, transcript_path)
        stderr = proc.stderr.read().strip() if proc.stderr is not None else ""
    finally:
        if proc.poll() is None:
            stop_process_group(proc)

    if proc.returncode != 0:
        raise RuntimeError(stderr or "termux-speech-to-text failed")
    raw_text = "".join(raw_lines)
    return extract_final_transcript(raw_text), raw_text


def listen_once_shim(
    delay_seconds: float,
    remaining_seconds: float | None = None,
    transcript_path: Path | None = None,
    stream_partials: bool = False,
    timeout_seconds: float = DEFAULT_SHIM_STT_TIMEOUT_SECONDS,
    offline_only: bool = False,
) -> tuple[str, str]:
    if delay_seconds > 0:
        if remaining_seconds is not None:
            time.sleep(min(delay_seconds, max(0.0, remaining_seconds)))
        else:
            time.sleep(delay_seconds)

    hard_timeout_seconds = max(1.0, timeout_seconds)
    if remaining_seconds is not None:
        hard_timeout_seconds = min(hard_timeout_seconds, max(1.0, remaining_seconds))

    try:
        client = WebSocketTextClient(
            SHIM_TEXT_VOICE_HOST,
            SHIM_TEXT_VOICE_PORT,
            SHIM_TEXT_VOICE_PATH,
            hard_timeout_seconds + 3.0,
        )
    except (OSError, RuntimeError) as exc:
        raise ShimUnavailable("shim_unavailable", str(exc)) from exc
    request_id = f"stt-{int(time.time() * 1000)}-{os.getpid()}"
    candidates: list[str] = []
    raw_lines: list[str] = []
    started = False
    try:
        status = client.recv_json()
        if status.get("event") == "status" and not status.get("sttAvailable", False):
            raise ShimUnavailable("recognizer_unavailable", "shim reports STT unavailable")
        client.send_json(
            {
                "id": request_id,
                "action": "start_stt",
                "offlineOnly": offline_only,
                "timeoutMs": int(hard_timeout_seconds * 1000),
                "completeSilenceMs": 3000,
                "possiblyCompleteSilenceMs": 3000,
                "minimumLengthMs": 1000,
            }
        )
        while True:
            event = client.recv_json()
            event_id = event.get("id")
            if event_id not in (request_id, None):
                continue
            event_name = event.get("event")
            if event_name == "stt_listening":
                started = True
                continue
            if event_name == "stt_partial":
                candidate = str(event.get("text", "")).strip()
                if candidate:
                    raw_lines.append(f"{candidate}\n")
                    if stream_partials:
                        record_stt_partial(candidate, candidates, transcript_path)
                continue
            if event_name == "stt_final":
                candidate = str(event.get("text", "")).strip()
                if candidate:
                    raw_lines.append(f"{candidate}\n")
                raw_text = "".join(raw_lines)
                return extract_final_transcript(raw_text), raw_text
            if event_name == "error":
                code = str(event.get("code", "stt_error"))
                message = str(event.get("message", "shim STT failed"))
                raw_text = "".join(raw_lines)
                if raw_text and code in {"stt_timeout", "speech_timeout", "stt_no_match", "no_match"}:
                    return extract_final_transcript(raw_text), raw_text
                if code in {"stt_timeout", "speech_timeout", "stt_no_match", "no_match"}:
                    return "", raw_text
                if code in {"recognizer_unavailable", "on_device_unavailable"} and not started:
                    raise ShimUnavailable(code, message)
                raise ShimVoiceError(code, message)
    except (ConnectionError, OSError, RuntimeError, TimeoutError) as exc:
        if isinstance(exc, ShimVoiceError):
            raise
        if not started and not raw_lines:
            raise ShimUnavailable("shim_unavailable", str(exc)) from exc
        raise
    finally:
        client.close()


def listen_once(
    delay_seconds: float,
    remaining_seconds: float | None = None,
    transcript_path: Path | None = None,
    stream_partials: bool = False,
    backend: str = DEFAULT_STT_BACKEND,
    shim_timeout_seconds: float = DEFAULT_SHIM_STT_TIMEOUT_SECONDS,
    shim_offline_only: bool = False,
) -> tuple[str, str]:
    if backend == "termux":
        return listen_once_termux(
            delay_seconds,
            remaining_seconds,
            transcript_path=transcript_path,
            stream_partials=stream_partials,
        )
    if backend == "shim":
        return listen_once_shim(
            delay_seconds,
            remaining_seconds,
            transcript_path=transcript_path,
            stream_partials=stream_partials,
            timeout_seconds=shim_timeout_seconds,
            offline_only=shim_offline_only,
        )
    try:
        return listen_once_shim(
            delay_seconds,
            remaining_seconds,
            transcript_path=transcript_path,
            stream_partials=stream_partials,
            timeout_seconds=shim_timeout_seconds,
            offline_only=shim_offline_only,
        )
    except ShimUnavailable as exc:
        emit_stt_status(transcript_path, f"shim unavailable; falling back to termux ({exc})")
        return listen_once_termux(
            0,
            remaining_seconds,
            transcript_path=transcript_path,
            stream_partials=stream_partials,
        )


def normalize_text(text: str) -> str:
    lowered = text.lower().strip()
    lowered = re.sub(r"[^a-z0-9\s]", " ", lowered)
    lowered = re.sub(r"\s+", " ", lowered)
    return lowered.strip()


def should_stop(text: str) -> bool:
    return normalize_text(text) in STOP_PHRASES


def process_is_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def read_session_pid() -> int | None:
    try:
        text = PID_PATH.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        return None
    if not text.isdigit():
        return None
    return int(text)


def write_session_pid() -> None:
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    PID_PATH.write_text(f"{os.getpid()}\n", encoding="utf-8")


def clear_session_pid() -> None:
    current = read_session_pid()
    if current == os.getpid():
        try:
            PID_PATH.unlink()
        except FileNotFoundError:
            pass


def prepare_command_fifo() -> None:
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    try:
        mode = COMMAND_FIFO_PATH.stat().st_mode
    except FileNotFoundError:
        os.mkfifo(COMMAND_FIFO_PATH)
        return
    if not stat.S_ISFIFO(mode):
        COMMAND_FIFO_PATH.unlink()
        os.mkfifo(COMMAND_FIFO_PATH)


def remove_command_fifo() -> None:
    try:
        if stat.S_ISFIFO(COMMAND_FIFO_PATH.stat().st_mode):
            COMMAND_FIFO_PATH.unlink()
    except FileNotFoundError:
        pass


def send_session_command(command: str, timeout_seconds: float = 1.0) -> bool:
    pid = read_session_pid()
    if not pid or not process_is_alive(pid) or not COMMAND_FIFO_PATH.exists():
        return False
    deadline = time.monotonic() + timeout_seconds
    payload = f"{command.strip()}\n".encode("utf-8")
    while time.monotonic() < deadline:
        try:
            fd = os.open(COMMAND_FIFO_PATH, os.O_WRONLY | os.O_NONBLOCK)
        except OSError:
            time.sleep(0.05)
            continue
        try:
            os.write(fd, payload)
            return True
        finally:
            os.close(fd)
    return False


def stop_existing_session() -> str:
    pid = read_session_pid()
    if not pid:
        return "no recorded session"
    if pid == os.getpid():
        return f"current session pid {pid}"
    if not process_is_alive(pid):
        try:
            PID_PATH.unlink()
        except FileNotFoundError:
            pass
        return f"removed stale pid {pid}"
    os.kill(pid, 15)
    deadline = time.time() + 2.0
    while time.time() < deadline:
        if not process_is_alive(pid):
            try:
                PID_PATH.unlink()
            except FileNotFoundError:
                pass
            return f"stopped previous session pid {pid}"
        time.sleep(0.1)
    return f"previous session pid {pid} still stopping"


def cleanup_voice_helpers() -> list[str]:
    messages: list[str] = []
    speech_killed = kill_matching_processes("termux-speech-to-text -p")
    if speech_killed:
        messages.append(f"stopped {speech_killed} speech recognizer process(es)")
    speech_api_killed = kill_matching_processes("termux-api SpeechToText")
    if speech_api_killed:
        messages.append(f"stopped {speech_api_killed} speech API bridge process(es)")
    tts_killed = kill_matching_processes("termux-tts-speak")
    if tts_killed:
        messages.append(f"stopped {tts_killed} TTS helper process(es)")
    tts_api_killed = kill_matching_processes("termux-api TextToSpeech")
    if tts_api_killed:
        messages.append(f"stopped {tts_api_killed} TTS API bridge process(es)")
    return messages


def cleanup_voice_processes() -> str:
    stop_requested = send_session_command("stop", timeout_seconds=0.2)
    messages: list[str] = []
    if stop_requested:
        messages.append("stop requested from active session")
        time.sleep(0.2)
    session_status = stop_existing_session()
    if session_status != "no recorded session" or not stop_requested:
        messages.append(session_status)
    messages.extend(cleanup_voice_helpers())
    clear_session_pid()
    remove_command_fifo()
    try:
        ACTIVITY_PANE_PATH.unlink()
    except FileNotFoundError:
        pass
    return "; ".join(messages)


def gather_host_summary(working_dir: str) -> str:
    model = run_command(["getprop", "ro.product.model"]).stdout.strip() or "unknown"
    android = run_command(["getprop", "ro.build.version.release_or_codename"]).stdout.strip() or "unknown"
    kernel = run_command(["uname", "-srmo"]).stdout.strip() or "unknown"
    now = run_command(["date"]).stdout.strip() or "unknown"

    battery_summary = "battery unknown"
    if shutil.which("termux-battery-status"):
        battery_result = run_command(["termux-battery-status"])
        if battery_result.returncode == 0 and battery_result.stdout.strip():
            try:
                battery = json.loads(battery_result.stdout)
                battery_summary = (
                    f"battery {battery.get('percentage', 'unknown')}% "
                    f"{str(battery.get('status', 'unknown')).lower()}"
                )
            except json.JSONDecodeError:
                pass

    return "\n".join(
        [
            f"device: {model}",
            f"android: {android}",
            f"kernel: {kernel}",
            f"cwd: {working_dir}",
            f"time: {now}",
            f"power: {battery_summary}",
        ]
    )


def resolve_working_dir(raw: str) -> str:
    if not raw:
        return os.getcwd()
    path = Path(raw).expanduser()
    if not path.is_absolute():
        path = Path.cwd() / path
    resolved = path.resolve()
    if not resolved.is_dir():
        raise RuntimeError(f"cwd is not a directory: {resolved}")
    return str(resolved)


def clip_spoken_text(text: str, limit: int = 240) -> str:
    one_line = " ".join(text.split())
    if len(one_line) <= limit:
        return one_line
    return one_line[: limit - 1].rstrip() + "..."


def format_history(history: list[tuple[str, str]], max_turns: int = 8) -> str:
    clipped = history[-max_turns:]
    if not clipped:
        return "(no prior turns)"
    return "\n".join(f"{speaker}: {clip_spoken_text(text)}" for speaker, text in clipped)


def format_source_notes(source_notes: list[str], max_notes: int = 6) -> str:
    clipped = [clip_spoken_text(note, 220) for note in source_notes[-max_notes:] if note.strip()]
    if not clipped:
        return "(no recent tool or source notes)"
    return "\n".join(f"- {note}" for note in clipped)


def build_prompt(
    host_summary: str,
    history: list[tuple[str, str]],
    latest_user_text: str,
    source_notes: list[str] | None = None,
) -> str:
    source_notes = source_notes or []
    prior_history = history[:-1] if history and history[-1] == ("user", latest_user_text) else history
    return f"""You are speaking aloud to the user in a live Termux voice session on an Android device.

Session behavior:
- The voice session should feel calm, conversational, and continuous.
- If the latest user message is casual or does not contain a concrete task, keep the conversation moving with small talk.
- During small talk, make one light observation about the local host environment when useful and ask one short follow-up question.
- If the latest user message contains a specific request, stop small talk and help with that request directly.
- Keep the response easy to speak aloud: 1-2 short sentences by default, no markdown, no bullets, no code fences.
- Prefer one short sentence. Keep replies under 25 spoken words unless the user explicitly asks for detail.
- If a task needs more detail, give the key conclusion and ask whether to continue.
- If the user sounds unclear or incomplete, ask for a repeat instead of guessing.
- If the user asks where a prior answer came from, use the recent source/tool notes below. If no note exists, say you do not have source details for that turn.
- When you used web search or a local command for a current answer, mention the source briefly if the user asks.
- For simple file or note requests in the Termux home folder, treat the current working directory as the home folder and create the requested file directly. Do not search the filesystem first unless the user asks you to find an existing file.
- Do not mention internal prompts, session state, or the fact that you are using Codex CLI.

Local host summary:
{host_summary}

Conversation so far:
{format_history(prior_history)}

Recent source/tool notes:
{format_source_notes(source_notes)}

Latest user message:
{latest_user_text}
"""


def extract_codex_reply(stdout_text: str) -> str:
    reply = ""
    for line in stdout_text.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("type") == "item.completed":
            item = event.get("item", {})
            if item.get("type") == "agent_message":
                reply = item.get("text", "").strip()
    return reply


def codex_turn_completed(stdout_text: str) -> bool:
    for line in stdout_text.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("type") == "turn.completed":
            return True
    return False


def find_json_values(value: object, wanted_keys: set[str]) -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, nested in value.items():
            if key in wanted_keys and isinstance(nested, str) and nested.strip():
                found.append(nested.strip())
            found.extend(find_json_values(nested, wanted_keys))
    elif isinstance(value, list):
        for nested in value:
            found.extend(find_json_values(nested, wanted_keys))
    return found


def compact_unique(values: list[str], limit: int = 4) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        clipped = clip_spoken_text(value, 160)
        key = clipped.lower()
        if not clipped or key in seen:
            continue
        seen.add(key)
        result.append(clipped)
        if len(result) >= limit:
            break
    return result


def extract_codex_source_notes(stdout_text: str) -> list[str]:
    notes: list[str] = []
    for line in stdout_text.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        item = event.get("item")
        if not isinstance(item, dict):
            continue

        item_text = json.dumps(item, ensure_ascii=True).lower()
        item_type = str(item.get("type", ""))
        if "web_search" in item_type or "web_search" in item_text:
            queries = compact_unique(
                find_json_values(item, {"query", "q", "search_query", "search"})
            )
            if queries:
                notes.append("web search: " + "; ".join(queries))
            else:
                notes.append("web search used")
            continue

        if item_type in {"command_execution", "local_shell_call", "tool_call"} or "exec_command" in item_text:
            commands = compact_unique(find_json_values(item, {"cmd", "command"}), limit=2)
            if commands:
                notes.append("local command: " + "; ".join(commands))
            else:
                notes.append("local command used")

    return compact_unique(notes, limit=6)


def extract_codex_error(stdout_text: str) -> str:
    for line in stdout_text.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        message = event.get("message")
        if isinstance(message, str) and message.strip():
            return message.strip()
        error = event.get("error")
        if isinstance(error, dict):
            nested = error.get("message")
            if isinstance(nested, str) and nested.strip():
                return nested.strip()
    return ""


def speakable_error(message: str) -> str:
    lowered = message.lower()
    if "usage limit" in lowered:
        return "Codex hit its usage limit. Try again later."
    if "api key" in lowered or "authentication" in lowered or "unauthorized" in lowered:
        return "Codex is not authenticated. Check the login or API key."
    if message:
        return f"Codex failed: {message[:160]}"
    return "Codex failed before returning a reply."


def codex_exec_command(cwd: str) -> list[str]:
    command = [
        "codex",
        "exec",
        "--skip-git-repo-check",
        "--ephemeral",
        "--json",
    ]
    if DEFAULT_CODEX_MODEL:
        command.extend(["-m", DEFAULT_CODEX_MODEL])
    if DEFAULT_CODEX_REASONING_EFFORT:
        command.extend(["-c", f'model_reasoning_effort="{DEFAULT_CODEX_REASONING_EFFORT}"'])
    command.extend(["-C", cwd, "-"])
    return command


def codex_exec_shell_command(cwd: str, prompt_path: Path, pipe_path: Path) -> str:
    command = codex_exec_command(cwd)
    return f"{shlex.join(command)} < {shlex.quote(str(prompt_path))} > {shlex.quote(str(pipe_path))} 2>&1"


def generate_reply(prompt: str, cwd: str) -> CodexTurnResult:
    ensure_command("codex")
    result = subprocess.run(
        codex_exec_command(cwd),
        input=prompt,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        detail = extract_codex_error(result.stdout) or result.stderr.strip()
        raise RuntimeError(speakable_error(detail))
    reply = extract_codex_reply(result.stdout)
    if not reply:
        raise RuntimeError("codex exec returned no assistant message")
    return CodexTurnResult(reply=reply, source_notes=extract_codex_source_notes(result.stdout))


def generate_reply_cancellable(prompt: str, cwd: str, client: WebSocketTextClient | None) -> CodexTurnResult:
    if client is not None and activity_pane_available():
        return generate_reply_visible_in_tmux(prompt, cwd, client)

    ensure_command("codex")
    proc = subprocess.Popen(
        codex_exec_command(cwd),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        start_new_session=True,
    )
    try:
        assert proc.stdin is not None
        proc.stdin.write(prompt)
        proc.stdin.close()
        while proc.poll() is None:
            if client is not None:
                try:
                    event = client.recv_json_timeout(0.2)
                except (BrokenPipeError, ConnectionError, OSError, RuntimeError):
                    stop_process_group(proc)
                    raise RuntimeError("STTS control socket closed; cancelled codex exec")
                if event and event.get("event") == "cancel_processing":
                    stop_process_group(proc)
                    raise RuntimeError("STTS turn cancelled")
            else:
                time.sleep(0.2)
        stdout = proc.stdout.read() if proc.stdout is not None else ""
        stderr = proc.stderr.read() if proc.stderr is not None else ""
    except Exception:
        if proc.poll() is None:
            stop_process_group(proc)
        raise
    if proc.returncode != 0:
        raise RuntimeError(stderr.strip() or stdout.strip() or "codex exec failed")
    reply = extract_codex_reply(stdout)
    if not reply:
        raise RuntimeError("codex exec returned no assistant message")
    return CodexTurnResult(reply=reply, source_notes=extract_codex_source_notes(stdout))


def read_activity_pane_id() -> str:
    try:
        return ACTIVITY_PANE_PATH.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        return ""


def tmux_pane_exists(pane_id: str) -> bool:
    if not pane_id or shutil.which("tmux") is None:
        return False
    result = run_command(["tmux", "list-panes", "-a", "-F", "#{pane_id}"])
    if result.returncode != 0:
        return False
    return pane_id in {line.strip() for line in result.stdout.splitlines()}


def activity_pane_available() -> bool:
    return tmux_pane_exists(read_activity_pane_id())


def reset_activity_pane(pane_id: str, message: str = "STTS Codex activity pane ready.") -> None:
    if not pane_id:
        return
    script = f"printf '%s\\n' {shlex.quote(message)}; exec sh"
    run_command(["tmux", "respawn-pane", "-k", "-t", pane_id, shlex.join(["sh", "-lc", script])])


def cancel_activity_pane(pane_id: str) -> None:
    reset_activity_pane(pane_id, "STTS Codex turn cancelled.")


def generate_reply_visible_in_tmux(prompt: str, cwd: str, client: WebSocketTextClient) -> CodexTurnResult:
    pane_id = read_activity_pane_id()
    if not tmux_pane_exists(pane_id):
        raise RuntimeError("STTS activity pane is unavailable")

    TURN_DIR.mkdir(parents=True, exist_ok=True)
    turn_id = time.strftime("%Y%m%d-%H%M%S") + f"-{os.getpid()}"
    prompt_path = TURN_DIR / f"{turn_id}.prompt.txt"
    output_path = TURN_DIR / f"{turn_id}.jsonl"
    rc_path = TURN_DIR / f"{turn_id}.rc"
    done_path = TURN_DIR / f"{turn_id}.done"
    pipe_path = TURN_DIR / f"{turn_id}.pipe"
    prompt_path.write_text(prompt, encoding="utf-8")
    codex_command = codex_exec_shell_command(cwd, prompt_path, pipe_path)

    script = f"""
set -u
cd {shlex.quote(cwd)} || exit 1
printf '\\033c'
printf 'STTS Codex turn {turn_id}\\n\\n'
rm -f {shlex.quote(str(pipe_path))} {shlex.quote(str(done_path))} {shlex.quote(str(rc_path))}
mkfifo {shlex.quote(str(pipe_path))}
tee {shlex.quote(str(output_path))} < {shlex.quote(str(pipe_path))} &
tee_pid=$!
{codex_command}
rc=$?
wait "$tee_pid" 2>/dev/null || true
rm -f {shlex.quote(str(pipe_path))}
printf '%s\\n' "$rc" > {shlex.quote(str(rc_path))}
touch {shlex.quote(str(done_path))}
printf '\\n[exit %s]\\n' "$rc"
exec sh
""".strip()
    result = run_command(["tmux", "respawn-pane", "-k", "-t", pane_id, shlex.join(["sh", "-lc", script])])
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "failed to run codex exec in activity pane")

    def completed_result() -> CodexTurnResult | None:
        stdout = output_path.read_text(encoding="utf-8", errors="replace") if output_path.exists() else ""
        if not stdout or not codex_turn_completed(stdout):
            return None
        reply = extract_codex_reply(stdout)
        if not reply:
            return None
        return CodexTurnResult(reply=reply, source_notes=extract_codex_source_notes(stdout))

    def wait_for_completed_result(timeout_seconds: float) -> CodexTurnResult | None:
        deadline_inner = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline_inner:
            result_inner = completed_result()
            if result_inner is not None:
                return result_inner
            if done_path.exists():
                break
            time.sleep(0.2)
        return completed_result()

    deadline = time.monotonic() + 300.0
    while not done_path.exists():
        if time.monotonic() >= deadline:
            cancel_activity_pane(pane_id)
            raise RuntimeError("codex exec timed out")
        try:
            event = client.recv_json_timeout(0.2)
        except (BrokenPipeError, ConnectionError, OSError, RuntimeError):
            result = wait_for_completed_result(2.0)
            if result is not None:
                return result
            cancel_activity_pane(pane_id)
            raise RuntimeError("STTS control socket closed; cancelled codex exec")
        if event and event.get("event") == "cancel_processing":
            cancel_activity_pane(pane_id)
            raise RuntimeError("STTS turn cancelled")

    stdout = output_path.read_text(encoding="utf-8", errors="replace") if output_path.exists() else ""
    rc = rc_path.read_text(encoding="utf-8", errors="replace").strip() if rc_path.exists() else "1"
    if rc != "0":
        detail = extract_codex_error(stdout) or stdout.strip() or f"codex exec failed with exit {rc}"
        raise RuntimeError(speakable_error(detail))
    reply = extract_codex_reply(stdout)
    if not reply:
        raise RuntimeError("codex exec returned no assistant message")
    return CodexTurnResult(reply=reply, source_notes=extract_codex_source_notes(stdout))


def shim_connect(timeout_seconds: float = 15.0) -> tuple[WebSocketTextClient, dict[str, object]]:
    client = WebSocketTextClient(
        SHIM_TEXT_VOICE_HOST,
        SHIM_TEXT_VOICE_PORT,
        SHIM_TEXT_VOICE_PATH,
        timeout_seconds,
    )
    return client, client.recv_json()


def send_action(client: WebSocketTextClient, action: str, **payload: object) -> str:
    request_id = f"{action}-{int(time.time() * 1000)}-{os.getpid()}"
    body: dict[str, object] = {"id": request_id, "action": action}
    body.update(payload)
    client.send_json(body)
    return request_id


def wait_for_id_event(
    client: WebSocketTextClient,
    request_id: str,
    accepted: set[str],
    timeout_seconds: float,
) -> dict[str, object]:
    deadline = time.monotonic() + timeout_seconds
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError(f"timeout waiting for {sorted(accepted)}")
        event = client.recv_json_timeout(min(0.5, remaining))
        if event is None:
            continue
        if event.get("event") == "error" and event.get("id") in (request_id, None):
            raise RuntimeError(f"{event.get('code', 'shim_error')}: {event.get('message', '')}")
        if event.get("id") in (request_id, None) and event.get("event") in accepted:
            return event


def cue_ready(client: WebSocketTextClient) -> None:
    try:
        request_id = send_action(client, "cue_ready")
        event = wait_for_id_event(client, request_id, {"cue_ready", "cue_failed"}, 3.0)
        print(summarize_wake_event(event), flush=True)
    except Exception as exc:
        print(f"wake_event: cue_failed message={exc}", flush=True)


def wake_profile(threshold: float = WAKE_THRESHOLD) -> dict[str, object]:
    return {
        "id": WAKE_PROFILE_ID,
        "label": WAKE_PHRASE,
        "modelType": "onnx",
        "modelPath": f"{WAKE_MODEL_APP_DIR}/hey_jarvis_v0.1.onnx",
        "melspectrogramPath": f"{WAKE_MODEL_APP_DIR}/melspectrogram.onnx",
        "embeddingPath": f"{WAKE_MODEL_APP_DIR}/embedding_model.onnx",
        "sampleRate": 16000,
        "frameMs": 80,
        "threshold": threshold,
        "cooldownMs": WAKE_COOLDOWN_MS,
        "licenseAcknowledged": True,
    }


def sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_wake_models(source_dir: Path = WAKE_MODEL_CACHE_DIR) -> None:
    ensure_command("curl")
    source_dir.mkdir(parents=True, exist_ok=True)
    for filename, expected in WAKE_MODEL_FILES.items():
        path = source_dir / filename
        if not path.exists() or sha256_path(path) != expected:
            url = f"{WAKE_MODEL_RELEASE_BASE_URL}/{filename}"
            result = run_command(["curl", "-fsSL", url, "-o", str(path)], timeout_seconds=120)
            if result.returncode != 0:
                raise RuntimeError(result.stderr.strip() or f"failed to download {url}")
        actual = sha256_path(path)
        if actual != expected:
            raise RuntimeError(f"checksum mismatch for {filename}: {actual}")


def import_wake_models(source_dir: Path = WAKE_MODEL_CACHE_DIR) -> None:
    missing = [name for name in WAKE_MODEL_FILES if not (source_dir / name).is_file()]
    if missing:
        raise RuntimeError("missing wake model files; run stts-diag --download")
    client, _status = shim_connect(15.0)
    try:
        for filename, expected in WAKE_MODEL_FILES.items():
            path = source_dir / filename
            actual = sha256_path(path)
            if actual != expected:
                raise RuntimeError(f"checksum mismatch for {filename}: {actual}")
            payload = base64.b64encode(path.read_bytes()).decode("ascii")
            request_id = send_action(
                client,
                "wake_model_put",
                profileId=WAKE_PROFILE_ID,
                filename=filename,
                sha256=expected,
                dataBase64=payload,
            )
            event = wait_for_id_event(client, request_id, {"wake_model_put"}, 60.0)
            if event.get("sha256") != expected:
                raise RuntimeError(f"shim reported unexpected sha for {filename}")
        request_id = send_action(client, "wake_model_validate", profile=wake_profile())
        event = wait_for_id_event(client, request_id, {"wake_model_validate"}, 10.0)
        if not event.get("valid"):
            raise RuntimeError(f"wake model validation failed: {event}")
    finally:
        client.close()


def validate_wake_models(threshold: float = WAKE_THRESHOLD) -> tuple[bool, str]:
    try:
        client, _status = shim_connect(5.0)
        try:
            request_id = send_action(client, "wake_model_validate", profile=wake_profile(threshold))
            event = wait_for_id_event(client, request_id, {"wake_model_validate"}, 8.0)
            if event.get("valid"):
                return True, "wake model valid"
            return False, f"wake model invalid; run stts-diag --download: {event}"
        finally:
            client.close()
    except Exception as exc:
        return False, f"wake model check failed; run stts-diag --download after shim is open: {exc}"


def run_stts_doctor(download: bool = False) -> int:
    if download:
        download_wake_models()
        import_wake_models()
    checks: list[tuple[str, bool, str]] = []
    for command in ("codex", "curl"):
        checks.append((command, shutil.which(command) is not None, shutil.which(command) or "missing"))
    try:
        client, status = shim_connect(5.0)
        client.close()
        checks.append(("shim", True, f"connected; state={status.get('state', 'unknown')}"))
    except Exception as exc:
        checks.append(("shim", False, str(exc)))
    valid, message = validate_wake_models()
    checks.append(("wake-model", valid, message))
    ok = all(item[1] for item in checks)
    for name, passed, detail in checks:
        print(f"{name}: {'ok' if passed else 'fail'} - {detail}")
    if not ok:
        print("recovery: stts-diag --download")
    return 0 if ok else 2


def wait_for_shim_tts(client: WebSocketTextClient, text: str, timeout_seconds: float = 60.0) -> None:
    request_id = send_action(client, "tts_speak", text=sanitize_for_tts(text), interrupt=True)
    wait_for_id_event(client, request_id, {"tts_complete"}, timeout_seconds)


def listen_once_on_client(client: WebSocketTextClient, timeout_seconds: float) -> str:
    request_id = send_action(
        client,
        "start_stt",
        timeoutMs=int(timeout_seconds * 1000),
        completeSilenceMs=3000,
        possiblyCompleteSilenceMs=3000,
        minimumLengthMs=1000,
    )
    deadline = time.monotonic() + timeout_seconds + 3.0
    candidates: list[str] = []
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return ""
        event = client.recv_json_timeout(min(0.5, remaining))
        if event is None:
            continue
        if event.get("event") == "cancel_processing":
            raise RuntimeError("STTS turn cancelled")
        if event.get("id") not in (request_id, None):
            continue
        if event.get("event") == "stt_partial":
            candidate = str(event.get("text", "")).strip()
            if candidate:
                candidates.append(candidate)
        elif event.get("event") == "stt_final":
            candidate = str(event.get("text", "")).strip()
            if candidate:
                candidates.append(candidate)
            return extract_final_transcript("\n".join(candidates))
        elif event.get("event") == "error":
            code = str(event.get("code", "stt_error"))
            if code in {"stt_timeout", "speech_timeout", "stt_no_match", "no_match"}:
                return ""
            raise RuntimeError(f"{code}: {event.get('message', '')}")


def run_stts_turn_on_client(
    client: WebSocketTextClient,
    cwd: str,
    history: list[tuple[str, str]],
    source_notes: list[str] | None = None,
    transcript: str | None = None,
    transcript_path: Path | None = None,
) -> bool:
    source_notes = source_notes if source_notes is not None else []
    if transcript is None:
        if transcript_path is not None:
            emit(transcript_path, "status", "listening")
        transcript = listen_once_on_client(client, 15.0)
    if not transcript:
        if transcript_path is not None:
            emit(transcript_path, "status", "no transcript")
        send_action(client, "client_state", state="ready")
        return False
    history.append(("user", transcript))
    if transcript_path is not None:
        emit(transcript_path, "user", transcript)
    if should_stop(transcript):
        if transcript_path is not None:
            emit(transcript_path, "assistant", "Okay, stopping here.")
        send_action(client, "client_state", state="ready")
        return True
    host_summary = gather_host_summary(cwd)
    prompt = build_prompt(host_summary, history, transcript, source_notes)
    if transcript_path is not None:
        emit(transcript_path, "status", "generating reply")
    send_action(client, "client_state", state="processing")
    try:
        result = generate_reply_cancellable(prompt, cwd, client)
        reply = result.reply
        source_notes.extend(result.source_notes)
        del source_notes[:-8]
        if transcript_path is not None and result.source_notes:
            emit(transcript_path, "sources", "; ".join(result.source_notes))
    except Exception as exc:
        reply = str(exc).strip() or "Codex failed before returning a reply."
        print(f"stts-turn: {exc}", file=sys.stderr)
    history.append(("assistant", reply))
    if transcript_path is not None:
        emit(transcript_path, "assistant", reply)
    wait_for_shim_tts(client, reply)
    if transcript_path is not None:
        emit(transcript_path, "tts", "tts complete on shim")
    send_action(client, "client_state", state="ready")
    return False


def run_talk(cwd: str, require_session: bool = True) -> int:
    if send_session_command("talk"):
        print(f"talk request sent to {TMUX_SESSION_NAME}")
        return 0
    if require_session:
        print("no active STTS session; run `stts session` first", file=sys.stderr)
        return 2
    client, _status = shim_connect(15.0)
    try:
        stopped = run_stts_turn_on_client(client, cwd, [])
        return 0 if not stopped else 130
    finally:
        client.close()


def read_fifo_commands(fifo_fd: int, pending: str) -> tuple[list[str], str]:
    try:
        data = os.read(fifo_fd, 4096)
    except BlockingIOError:
        return [], pending
    if not data:
        return [], pending
    pending += data.decode("utf-8", errors="replace")
    lines = pending.splitlines(keepends=True)
    commands: list[str] = []
    remainder = ""
    for line in lines:
        if line.endswith("\n") or line.endswith("\r"):
            command = line.strip()
            if command:
                commands.append(command)
        else:
            remainder = line
    return commands, remainder


def run_session_host(
    opener: str,
    post_speech_delay_seconds: int,
    post_tts_recovery_seconds: float,
    tts_drain_timeout_seconds: float,
    timeout_seconds: int,
    tts_stream: str,
    tts_backend: str,
    working_dir: str,
) -> int:
    ensure_command("codex")
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    stop_messages = [stop_existing_session()]
    helper_stop_messages = cleanup_voice_helpers()
    stop_messages.extend(helper_stop_messages)
    stop_status = "; ".join(stop_messages)
    write_session_pid()
    prepare_command_fifo()

    session_id = time.strftime("%Y%m%d-%H%M%S")
    transcript_path = RUNTIME_DIR / f"session-{session_id}.txt"
    (RUNTIME_DIR / "last-session.txt").write_text(str(transcript_path), encoding="utf-8")
    append_log(transcript_path, f"session_id: {session_id}")
    append_log(transcript_path, f"session_pid: {os.getpid()}")
    append_log(transcript_path, f"mode: session")
    append_log(transcript_path, f"startup: {stop_status}")
    append_log(transcript_path, f"working_dir: {working_dir}")

    history: list[tuple[str, str]] = []
    source_notes: list[str] = []
    emit(transcript_path, "status", "STTS ready")
    emit(transcript_path, "status", "ready; run stts talk, stts wake, or stts stop")

    fifo_fd = os.open(COMMAND_FIFO_PATH, os.O_RDONLY | os.O_NONBLOCK)
    keepalive_fd = os.open(COMMAND_FIFO_PATH, os.O_WRONLY | os.O_NONBLOCK)
    pending = ""
    stop_at = time.monotonic() + timeout_seconds
    try:
        while time.monotonic() < stop_at:
            readable, _, _ = select.select([fifo_fd], [], [], 1.0)
            if not readable:
                continue
            commands, pending = read_fifo_commands(fifo_fd, pending)
            for command in commands:
                normalized = normalize_text(command)
                if normalized in {"stop", "quit", "exit"}:
                    emit(transcript_path, "status", "stopping")
                    return 0
                if normalized == "status":
                    emit(transcript_path, "status", "ready")
                    continue
                if normalized == "talk":
                    try:
                        turn_client, _status = shim_connect(15.0)
                        try:
                            stopped = run_stts_turn_on_client(
                                turn_client,
                                working_dir,
                                history,
                                source_notes,
                                transcript_path=transcript_path,
                            )
                        finally:
                            turn_client.close()
                    except Exception as exc:
                        emit(transcript_path, "status", f"turn failed: {exc}")
                        stopped = False
                    if stopped:
                        return 0
                    emit(transcript_path, "status", "ready; run stts talk, stts wake, or stts stop")
                    continue
                emit(transcript_path, "status", f"unknown command: {command}")
        timeout_reply = "Timing out."
        emit(transcript_path, "assistant", timeout_reply)
        emit(transcript_path, "tts", say_text(timeout_reply, stream_name=tts_stream, backend=tts_backend))
        return 0
    finally:
        os.close(keepalive_fd)
        os.close(fifo_fd)
        remove_command_fifo()
        clear_session_pid()


SESSION_COMMANDS = {
    "say",
    "listen",
    "talk",
    "wake",
    "doctor",
    "model-import",
    "status",
    "diag",
    "stt-check",
    "stop",
    "cleanup",
    "session",
    "loop",
}


def replace_raw_command(raw_args: list[str], replacement: str) -> list[str]:
    updated = list(raw_args)
    for index, token in enumerate(updated):
        if token in SESSION_COMMANDS:
            updated[index] = replacement
            return updated
    return [replacement, *updated]


def tmux_session_exists(name: str) -> bool:
    result = run_command(["tmux", "has-session", "-t", name])
    return result.returncode == 0


def wait_for_session_ready(timeout_seconds: float = 10.0) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        pid = read_session_pid()
        if pid and process_is_alive(pid) and COMMAND_FIFO_PATH.exists():
            return True
        time.sleep(0.1)
    return False


def ensure_activity_pane(name: str) -> str:
    pane_id = read_activity_pane_id()
    if tmux_pane_exists(pane_id):
        run_command(["tmux", "select-layout", "-t", f"{name}:0", "even-vertical"])
        return pane_id

    result = run_command(["tmux", "list-panes", "-t", f"{name}:0", "-F", "#{pane_id}"])
    if result.returncode == 0:
        panes = [line.strip() for line in result.stdout.splitlines() if line.strip()]
        if len(panes) >= 2:
            pane_id = panes[1]
            ACTIVITY_PANE_PATH.write_text(f"{pane_id}\n", encoding="utf-8")
            reset_activity_pane(pane_id)
            run_command(["tmux", "select-layout", "-t", f"{name}:0", "even-vertical"])
            return pane_id

    idle_script = "printf '%s\\n' 'STTS Codex activity pane ready.'; exec sh"
    split = run_command(
        [
            "tmux",
            "split-window",
            "-v",
            "-t",
            f"{name}:0",
            "-P",
            "-F",
            "#{pane_id}",
            shlex.join(["sh", "-lc", idle_script]),
        ]
    )
    if split.returncode != 0:
        raise RuntimeError(split.stderr.strip() or "failed to create STTS activity pane")
    pane_id = split.stdout.strip()
    ACTIVITY_PANE_PATH.write_text(f"{pane_id}\n", encoding="utf-8")
    run_command(["tmux", "select-layout", "-t", f"{name}:0", "even-vertical"])
    return pane_id


def attach_or_switch_tmux_session(name: str) -> int:
    if not sys.stdout.isatty():
        print(f"stts session running in tmux: {name}")
        return 0
    if os.environ.get("TMUX"):
        result = run_command(["tmux", "switch-client", "-t", name])
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or f"failed to switch to tmux session {name}")
        return 0
    os.execvp("tmux", ["tmux", "attach-session", "-t", name])
    return 0


def ensure_tmux_session(
    raw_args: list[str],
    post_speech_delay_seconds: int,
    post_tts_recovery_seconds: float,
    tts_drain_timeout_seconds: float,
    timeout_seconds: int,
    tts_stream: str,
    tts_backend: str,
    working_dir: str,
) -> None:
    ensure_command("tmux")
    session_name = TMUX_SESSION_NAME
    session_exists = tmux_session_exists(session_name)
    if session_exists and not wait_for_session_ready(0.2):
        run_command(["tmux", "kill-session", "-t", session_name])
        remove_command_fifo()
        try:
            ACTIVITY_PANE_PATH.unlink()
        except FileNotFoundError:
            pass
        session_exists = False
    if not session_exists:
        script_path = str(Path(__file__).with_name("stts-session.sh"))
        session_args = replace_raw_command(raw_args, "session")
        command = shlex.join(["env", "CODEX_STTS_TMUX_HOST=1", "sh", script_path, *session_args])
        result = run_command(["tmux", "new-session", "-d", "-s", session_name, "-n", "stts", command])
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or f"failed to create tmux session {session_name}")
        print(f"created tmux session: {session_name}")
    if not wait_for_session_ready():
        raise RuntimeError("STTS session did not become ready")
    ensure_activity_pane(session_name)


def run_session_tmux(
    raw_args: list[str],
    opener: str,
    post_speech_delay_seconds: int,
    post_tts_recovery_seconds: float,
    tts_drain_timeout_seconds: float,
    timeout_seconds: int,
    tts_stream: str,
    tts_backend: str,
    working_dir: str,
) -> int:
    if os.environ.get("CODEX_STTS_TMUX_HOST") == "1":
        return run_session_host(
            opener,
            post_speech_delay_seconds,
            post_tts_recovery_seconds,
            tts_drain_timeout_seconds,
            timeout_seconds,
            tts_stream,
            tts_backend,
            working_dir,
        )

    ensure_tmux_session(
        raw_args,
        post_speech_delay_seconds,
        post_tts_recovery_seconds,
        tts_drain_timeout_seconds,
        timeout_seconds,
        tts_stream,
        tts_backend,
        working_dir,
    )
    return attach_or_switch_tmux_session(TMUX_SESSION_NAME)


def run_talk_tmux(
    raw_args: list[str],
    post_speech_delay_seconds: int,
    post_tts_recovery_seconds: float,
    tts_drain_timeout_seconds: float,
    timeout_seconds: int,
    tts_stream: str,
    tts_backend: str,
    working_dir: str,
) -> int:
    ensure_tmux_session(
        raw_args,
        post_speech_delay_seconds,
        post_tts_recovery_seconds,
        tts_drain_timeout_seconds,
        timeout_seconds,
        tts_stream,
        tts_backend,
        working_dir,
    )
    if not send_session_command("talk"):
        raise RuntimeError("failed to queue STTS talk turn")
    print(f"talk request sent to {TMUX_SESSION_NAME}")
    if sys.stdout.isatty():
        return attach_or_switch_tmux_session(TMUX_SESSION_NAME)
    return 0


def summarize_wake_event(event: dict[str, object]) -> str:
    name = event.get("event", "unknown")
    score = event.get("score", event.get("lastWakeScore", ""))
    threshold = event.get("threshold", "")
    frame = event.get("frame", event.get("lastWakeFrame", ""))
    elapsed = event.get("elapsedMs", event.get("lastWakeLatencyMs", ""))
    message = event.get("message", "")
    parts = [f"wake_event: {name}"]
    if score != "":
        parts.append(f"score={score}")
    if threshold != "":
        parts.append(f"threshold={threshold}")
    if frame != "":
        parts.append(f"frame={frame}")
    if elapsed != "":
        parts.append(f"elapsedMs={elapsed}")
    if message != "":
        parts.append(f"message={message}")
    return " ".join(parts)


def run_wake_loop(
    cwd: str,
    once: bool = False,
    fake_wake: bool = False,
    debug_scores: bool = False,
    threshold: float = WAKE_THRESHOLD,
    cue: bool = True,
) -> int:
    ok, message = validate_wake_models(threshold)
    if not ok and not fake_wake:
        raise RuntimeError(message)
    client, _status = shim_connect(15.0)
    history: list[tuple[str, str]] = []
    source_notes: list[str] = []
    stop_at = time.monotonic() + WAKE_MAX_RUNTIME_SECONDS
    try:
        while time.monotonic() < stop_at:
            payload = {
                "maxListenMs": 60_000,
                "debugScores": debug_scores,
            }
            if not fake_wake:
                payload["profile"] = wake_profile(threshold)
            request_id = send_action(client, "wake_start", **payload)
            started_event = wait_for_id_event(client, request_id, {"wake_started"}, 15.0)
            print(summarize_wake_event(started_event), flush=True)
            if cue:
                cue_ready(client)
            if fake_wake:
                send_action(client, "wake_fake_detect")
            while True:
                event = client.recv_json_timeout(1.0)
                if event is None:
                    if time.monotonic() >= stop_at:
                        send_action(client, "wake_stop")
                        return 0
                    continue
                event_name = event.get("event")
                if event_name in {"wake_score", "wake_timeout", "wake_detected", "wake_stopped", "wake_idle", "wake_error"}:
                    print(summarize_wake_event(event), flush=True)
                if event_name in {"wake_timeout", "wake_stopped", "wake_idle"}:
                    if once:
                        return 1
                    break
                if event_name == "ptt_button_pressed":
                    stop_id = send_action(client, "wake_stop")
                    wait_for_id_event(client, stop_id, {"wake_stopped", "wake_idle"}, 5.0)
                    run_stts_turn_on_client(client, cwd, history, source_notes)
                    time.sleep(WAKE_REARM_DELAY_SECONDS)
                    if once:
                        return 0
                    break
                if event_name == "wake_detected":
                    run_stts_turn_on_client(client, cwd, history, source_notes)
                    time.sleep(WAKE_REARM_DELAY_SECONDS)
                    if once:
                        return 0
                    break
                if event_name == "cancel_processing":
                    send_action(client, "wake_stop")
                    return 130
        return 0
    finally:
        try:
            send_action(client, "wake_stop")
            send_action(client, "stop_stt")
            send_action(client, "tts_stop")
        except Exception:
            pass
        client.close()


def append_log(path: Path, line: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(f"{line}\n")


def emit(path: Path, label: str, text: str) -> None:
    line = f"{label}: {text}"
    print(line, flush=True)
    append_log(path, line)


def format_status() -> str:
    pid = read_session_pid()
    if pid and process_is_alive(pid):
        pid_status = f"running pid {pid}"
    elif pid:
        pid_status = f"stale pid {pid}"
    else:
        pid_status = "not running"
    fifo_status = "command fifo ready" if COMMAND_FIFO_PATH.exists() else "command fifo missing"
    tmux_status = "tmux unknown"
    if shutil.which("tmux"):
        tmux_status = f"tmux {TMUX_SESSION_NAME} {'running' if tmux_session_exists(TMUX_SESSION_NAME) else 'not running'}"

    volume_status = "volume unknown"
    if shutil.which("termux-volume"):
        try:
            music = find_volume_stream(get_volume_state(), "MUSIC")
            notification = find_volume_stream(get_volume_state(), "NOTIFICATION")
            parts = []
            if music:
                parts.append(f"music {music.get('volume')}/{music.get('max_volume')}")
            if notification:
                parts.append(f"notification {notification.get('volume')}/{notification.get('max_volume')}")
            if parts:
                volume_status = ", ".join(parts)
        except Exception as exc:
            volume_status = f"volume check failed: {exc}"

    last_session = RUNTIME_DIR / "last-session.txt"
    last_status = "last session unknown"
    if last_session.exists():
        last_status = f"last session {last_session.read_text(encoding='utf-8').strip()}"
    return f"{pid_status}; {tmux_status}; {fifo_status}; {volume_status}; {last_status}"


def run_diag() -> int:
    checks = ["termux-tts-speak", "termux-speech-to-text", "termux-volume", "codex"]
    for name in checks:
        print(f"{name}: {shutil.which(name) or 'missing'}")
    if shutil.which("termux-tts-engines"):
        engines = run_command(["termux-tts-engines"])
        print("termux-tts-engines:")
        print((engines.stdout or engines.stderr).strip())
    print(f"status: {format_status()}")
    return 0


def run_stt_check(
    delay_seconds: int,
    stt_backend: str,
    stt_timeout_seconds: float,
    stt_offline_only: bool,
) -> int:
    print("status: listening for raw STT")
    transcript, raw_transcript = listen_once(
        delay_seconds,
        stream_partials=True,
        backend=stt_backend,
        shim_timeout_seconds=stt_timeout_seconds,
        shim_offline_only=stt_offline_only,
    )
    candidates = extract_transcript_candidates(raw_transcript)
    print(f"stt_candidates: {json.dumps(candidates, ensure_ascii=True)}")
    print(f"transcript: {transcript}")
    return 0 if transcript else 1


def pause_after_empty_listen(delay_seconds: float, transcript_path: Path) -> None:
    if delay_seconds <= 0:
        return
    emit(transcript_path, "status", f"no transcript; waiting {delay_seconds:g}s before listening again")
    time.sleep(delay_seconds)


def run_session(
    opener: str,
    delay_seconds: int,
    post_tts_recovery_seconds: float,
    tts_drain_timeout_seconds: float,
    timeout_seconds: int,
    empty_retries: int,
    empty_listen_delay_seconds: float,
    tts_stream: str,
    tts_backend: str,
    stt_backend: str,
    stt_timeout_seconds: float,
    stt_offline_only: bool,
    working_dir: str,
) -> int:
    if tts_backend == "termux":
        ensure_command("termux-tts-speak")
    if stt_backend == "termux":
        ensure_command("termux-speech-to-text")
    ensure_command("codex")

    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    stop_messages = [stop_existing_session()]
    helper_stop_messages = cleanup_voice_helpers()
    stop_messages.extend(helper_stop_messages)
    if any("TTS" in message for message in helper_stop_messages):
        time.sleep(DEFAULT_REPLACEMENT_TTS_RECOVERY_SECONDS)
        stop_messages.append(f"waited {DEFAULT_REPLACEMENT_TTS_RECOVERY_SECONDS:g}s after TTS helper cleanup")
    stop_status = "; ".join(stop_messages)
    write_session_pid()
    session_id = time.strftime("%Y%m%d-%H%M%S")
    transcript_path = RUNTIME_DIR / f"session-{session_id}.txt"
    meta_path = RUNTIME_DIR / "last-session.txt"
    host_summary = gather_host_summary(working_dir)
    history: list[tuple[str, str]] = []
    source_notes: list[str] = []
    start_time = time.time()

    meta_path.write_text(str(transcript_path), encoding="utf-8")
    append_log(transcript_path, f"session_id: {session_id}")
    append_log(transcript_path, f"session_pid: {os.getpid()}")
    append_log(transcript_path, f"startup: {stop_status}")
    append_log(transcript_path, f"working_dir: {working_dir}")
    append_log(transcript_path, "host_summary:")
    append_log(transcript_path, host_summary)

    history.append(("assistant", opener))
    emit(transcript_path, "assistant", opener)
    emit(transcript_path, "tts", say_text(opener, stream_name=tts_stream, backend=tts_backend))
    pause_after_speech(opener, delay_seconds, transcript_path, post_tts_recovery_seconds, tts_drain_timeout_seconds)

    try:
        while True:
            elapsed = time.time() - start_time
            if elapsed >= timeout_seconds:
                timeout_reply = "Timing out."
                emit(transcript_path, "assistant", timeout_reply)
                emit(transcript_path, "tts", say_text(timeout_reply, stream_name=tts_stream, backend=tts_backend))
                pause_after_speech(timeout_reply, delay_seconds, transcript_path, post_tts_recovery_seconds, tts_drain_timeout_seconds)
                return 0
            remaining = timeout_seconds - elapsed

            transcript = ""
            raw_transcript = ""
            for attempt in range(empty_retries + 1):
                if attempt == 0:
                    emit(transcript_path, "status", f"listening attempt {attempt + 1}/{empty_retries + 1}")
                else:
                    emit(transcript_path, "status", f"retry listening attempt {attempt + 1}/{empty_retries + 1}")
                transcript, raw_transcript = listen_once(
                    0,
                    remaining,
                    transcript_path=transcript_path,
                    stream_partials=True,
                    backend=stt_backend,
                    shim_timeout_seconds=stt_timeout_seconds,
                    shim_offline_only=stt_offline_only,
                )
                if transcript:
                    break
                if attempt < empty_retries:
                    pause_after_empty_listen(empty_listen_delay_seconds, transcript_path)
                    continue

            if not transcript:
                pause_after_empty_listen(empty_listen_delay_seconds, transcript_path)
                continue

            candidates = extract_transcript_candidates(raw_transcript)
            if candidates:
                emit(transcript_path, "stt_candidates", json.dumps(candidates, ensure_ascii=True))
            history.append(("user", transcript))
            emit(transcript_path, "user", transcript)

            if should_stop(transcript):
                goodbye = "Okay, stopping here."
                history.append(("assistant", goodbye))
                emit(transcript_path, "assistant", goodbye)
                emit(transcript_path, "tts", say_text(goodbye, stream_name=tts_stream, backend=tts_backend))
                pause_after_speech(goodbye, delay_seconds, transcript_path, post_tts_recovery_seconds, tts_drain_timeout_seconds)
                return 0

            if looks_incomplete(transcript):
                prompt_again = build_incomplete_prompt(transcript)
                history.append(("assistant", prompt_again))
                emit(transcript_path, "assistant", prompt_again)
                emit(transcript_path, "tts", say_text(prompt_again, stream_name=tts_stream, backend=tts_backend))
                pause_after_speech(prompt_again, delay_seconds, transcript_path, post_tts_recovery_seconds, tts_drain_timeout_seconds)
                continue

            prompt = build_prompt(host_summary, history, transcript, source_notes)
            emit(transcript_path, "status", "generating reply")
            try:
                result = generate_reply(prompt, working_dir)
                reply = result.reply
                source_notes.extend(result.source_notes)
                del source_notes[:-8]
                if result.source_notes:
                    emit(transcript_path, "sources", "; ".join(result.source_notes))
            except Exception as exc:
                fallback = "I hit a problem generating my reply. Ask again, or say stop."
                append_log(transcript_path, f"error: {exc}")
                history.append(("assistant", fallback))
                emit(transcript_path, "assistant", fallback)
                emit(transcript_path, "tts", say_text(fallback, stream_name=tts_stream, backend=tts_backend))
                pause_after_speech(fallback, delay_seconds, transcript_path, post_tts_recovery_seconds, tts_drain_timeout_seconds)
                continue

            history.append(("assistant", reply))
            emit(transcript_path, "assistant", reply)
            emit(transcript_path, "tts", say_text(reply, stream_name=tts_stream, backend=tts_backend))
            pause_after_speech(reply, delay_seconds, transcript_path, post_tts_recovery_seconds, tts_drain_timeout_seconds)
    finally:
        clear_session_pid()


def main() -> int:
    raw_args = sys.argv[1:]
    parser = argparse.ArgumentParser(description="Persistent STTS voice loop for Codex in Termux.")
    parser.add_argument(
        "command",
        nargs="?",
        default="talk",
        choices=[
            "say",
            "listen",
            "talk",
            "wake",
            "doctor",
            "model-import",
            "status",
            "diag",
            "stt-check",
            "stop",
            "cleanup",
            "session",
            "loop",
        ],
    )
    parser.add_argument("text", nargs="*")
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_SESSION_TIMEOUT_SECONDS)
    parser.add_argument("--post-speech-delay", type=int, default=DEFAULT_POST_SPEECH_DELAY_SECONDS)
    parser.add_argument("--post-tts-recovery", type=float, default=DEFAULT_POST_TTS_RECOVERY_SECONDS)
    parser.add_argument("--tts-drain-timeout", type=float, default=DEFAULT_TTS_DRAIN_TIMEOUT_SECONDS)
    parser.add_argument("--empty-retries", type=int, default=DEFAULT_EMPTY_RETRIES)
    parser.add_argument("--empty-listen-delay", type=float, default=DEFAULT_EMPTY_LISTEN_DELAY_SECONDS)
    parser.add_argument("--tts-stream", default=DEFAULT_TTS_STREAM)
    parser.add_argument("--tts-backend", default=DEFAULT_TTS_BACKEND, choices=["auto", "shim", "termux"])
    parser.add_argument("--stt-backend", default=DEFAULT_STT_BACKEND, choices=["auto", "shim", "termux"])
    parser.add_argument("--stt-timeout-seconds", type=float, default=DEFAULT_SHIM_STT_TIMEOUT_SECONDS)
    parser.add_argument("--stt-offline-only", action="store_true")
    parser.add_argument("--cwd", default="")
    parser.add_argument("--download", action="store_true", help="Download and import pinned openWakeWord assets before diagnostics.")
    parser.add_argument("--source-dir", default=str(WAKE_MODEL_CACHE_DIR), help="Wake model staging directory.")
    parser.add_argument("--once", action="store_true", help="For wake mode: exit after one completed activation.")
    parser.add_argument("--fake-wake", action="store_true", help="For wake mode: use fake/manual wake instead of ONNX.")
    parser.add_argument("--wake-debug-scores", action="store_true", help="For wake mode: print live wake score events.")
    parser.add_argument("--wake-threshold", type=float, default=WAKE_THRESHOLD, help="For wake mode: override the wake detection threshold.")
    parser.add_argument("--no-wake-cue", action="store_true", help="For wake mode: do not play the ready tone after arming.")
    args = parser.parse_args()

    try:
        if args.command == "say":
            if not args.text:
                raise RuntimeError("say requires text")
            spoken = " ".join(args.text)
            print(say_text(spoken, stream_name=args.tts_stream, backend=args.tts_backend), flush=True)
            wait_after_one_shot_tts(spoken, args.post_speech_delay, args.tts_drain_timeout)
            return 0
        if args.command == "listen":
            transcript, _raw_transcript = listen_once(
                args.post_speech_delay,
                stream_partials=True,
                backend=args.stt_backend,
                shim_timeout_seconds=args.stt_timeout_seconds,
                shim_offline_only=args.stt_offline_only,
            )
            if transcript:
                print(transcript)
            return 0
        opener = " ".join(args.text).strip() or DEFAULT_OPENER
        working_dir = resolve_working_dir(args.cwd)
        if args.command == "talk":
            return run_talk_tmux(
                raw_args,
                args.post_speech_delay,
                args.post_tts_recovery,
                args.tts_drain_timeout,
                args.timeout_seconds,
                args.tts_stream,
                args.tts_backend,
                working_dir,
            )
        if args.command == "wake":
            return run_wake_loop(
                working_dir,
                once=args.once,
                fake_wake=args.fake_wake,
                debug_scores=args.wake_debug_scores,
                threshold=args.wake_threshold,
                cue=not args.no_wake_cue,
            )
        if args.command == "doctor":
            return run_stts_doctor(download=args.download)
        if args.command == "model-import":
            if args.download:
                download_wake_models(Path(args.source_dir).expanduser())
            import_wake_models(Path(args.source_dir).expanduser())
            print("wake model imported")
            return 0
        if args.command == "status":
            print(format_status())
            return 0
        if args.command == "diag":
            if args.download:
                return run_stts_doctor(download=True)
            return run_diag()
        if args.command == "stt-check":
            return run_stt_check(
                args.post_speech_delay,
                args.stt_backend,
                args.stt_timeout_seconds,
                args.stt_offline_only,
            )
        if args.command in ("stop", "cleanup"):
            print(cleanup_voice_processes())
            return 0
        if args.command == "session":
            return run_session_tmux(
                raw_args,
                opener,
                args.post_speech_delay,
                args.post_tts_recovery,
                args.tts_drain_timeout,
                args.timeout_seconds,
                args.tts_stream,
                args.tts_backend,
                working_dir,
            )
        return run_session(
            opener,
            args.post_speech_delay,
            args.post_tts_recovery,
            args.tts_drain_timeout,
            args.timeout_seconds,
            args.empty_retries,
            args.empty_listen_delay,
            args.tts_stream,
            args.tts_backend,
            args.stt_backend,
            args.stt_timeout_seconds,
            args.stt_offline_only,
            working_dir,
        )
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        print(f"stts: {exc}", file=sys.stderr)
        return 1
    finally:
        cleanup_tts_helpers()


if __name__ == "__main__":
    raise SystemExit(main())
