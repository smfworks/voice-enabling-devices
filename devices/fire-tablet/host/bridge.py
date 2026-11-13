#!/usr/bin/env python3
"""Fire HD 8 voice bridge on the host: Paula and Liam (Hermes personas).

The xAI key is read in place from ~/.hermes/.env (XAI_API_KEY) and never logged.
This process does not refresh Hermes auth and does not write that file.
Routing is slot-based (sarah->paula, james->liam) with a spoken-name override.

The tablet app tries http://127.0.0.1:8766/talk?who=sarah|james first (USB adb reverse),
then http://<HOST_IP>:8766 over Wi-Fi. who=liam is accepted as an alias for the Liam slot.
The process binds 127.0.0.1 and <HOST_IP> (not all interfaces) and allowlists those two
client IPs: 127.0.0.1 and the tablet at <HOST_IP>.
"""

from __future__ import annotations

import base64
import json
import os
import re
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST = os.environ.get("TABLET_VOICE_HOST", "127.0.0.1")
LAN_HOST = os.environ.get("TABLET_VOICE_LAN", "")
PORT = int(os.environ.get("TABLET_VOICE_PORT", "8766"))
ALLOWED_IPS = {
    ip.strip()
    for ip in os.environ.get("TABLET_VOICE_ALLOW", "127.0.0.1,::1").split(",")
    if ip.strip()
}
STATE = Path.home() / ".local/state/tablet-voice"
ENV_FILE = Path(os.environ.get("TABLET_VOICE_KEY_FILE", str(Path.home() / ".hermes" / ".env")))
ENV_KEY = "XAI_API_KEY"
PAULA_HOME = Path.home() / ".hermes" / "profiles" / "paula"
LIAM_HOME = Path.home() / ".hermes" / "profiles" / "liam"
VOICE_MODEL = os.environ.get("TABLET_VOICE_MODEL", "grok-4-1-fast-non-reasoning")
WHISPER_MODEL = os.environ.get("TABLET_WHISPER_MODEL", "base.en")
CHAT_URL = "https://api.x.ai/v1/chat/completions"
TTS_URL = "https://api.x.ai/v1/tts"

AGENTS = {
    "paula": {"name": "Paula", "voice": os.environ.get("PAULA_TTS_VOICE", "ara"), "home": PAULA_HOME},
    "liam": {"name": "Liam", "voice": os.environ.get("LIAM_TTS_VOICE", "rex"), "home": LIAM_HOME},
}
# Left page (app's Paula / historical "sarah" slot) -> Paula; right page (Liam / "james") -> Liam.
SLOTS = {"sarah": "paula", "james": "liam", "paula": "paula", "liam": "liam"}
NAME_RE = re.compile(
    r"^\s*(?:(?:hey|hi|hello|ok|okay|yo)[\s,]+)?(liam|paula|pola|paola)\b[\s,.:;!?-]*",
    re.IGNORECASE,
)
VOICE_INSTRUCTION = (
    "You are being heard through a voice speaker: answer in one to three short "
    "spoken sentences, without lists, markdown, links or emoji. Do not say your name."
)
FALLBACK = "I can't reach my brain right now, try again in a minute."

_turn = threading.Lock()
_model = None
_model_error = ""
_model_lock = threading.Lock()
_key = ""
_key_mtime = 0.0


def load_model():
    global _model, _model_error
    with _model_lock:
        if _model is not None or _model_error:
            return
        try:
            from faster_whisper import WhisperModel
            _model = WhisperModel(WHISPER_MODEL, device="cpu", compute_type="int8")
        except Exception as exc:  # noqa: BLE001
            _model_error = str(exc)


def _wav_samples(wav_path: str):
    """Decode the tablet's PCM WAV without PyAV (the av/faster-whisper pairing breaks on open())."""
    import wave
    import numpy as np
    with wave.open(wav_path, "rb") as w:
        rate, chans, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if width != 2:
        raise RuntimeError("expected 16-bit PCM WAV")
    audio = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    if chans > 1:
        audio = audio.reshape(-1, chans).mean(axis=1)
    if rate != 16000:
        n = int(len(audio) * 16000 / rate)
        audio = np.interp(np.linspace(0, len(audio), n, endpoint=False), np.arange(len(audio)), audio).astype(np.float32)
    return audio


def transcribe(wav_path: str) -> str:
    load_model()
    if _model is None:
        raise RuntimeError(_model_error or "speech model is not ready")
    segments, _info = _model.transcribe(
        _wav_samples(wav_path), language="en", vad_filter=True, beam_size=1, best_of=1,
        condition_on_previous_text=False, temperature=0.0,
    )
    return " ".join(s.text.strip() for s in segments).strip()


def xai_key() -> str:
    """Read the host's own xAI key in place (read-only). Never logged or written anywhere."""
    global _key, _key_mtime
    mtime = ENV_FILE.stat().st_mtime
    if _key and mtime == _key_mtime:
        return _key
    value = ""
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("export "):
            line = line[7:].strip()
        if line.startswith(ENV_KEY + "="):
            value = line.split("=", 1)[1].strip().strip('"').strip("'")
    if not value:
        raise RuntimeError("xAI key is missing")
    _key, _key_mtime = value, mtime
    return value


def _post(url: str, body: dict, raw: bool = False):
    request = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Authorization": "Bearer " + xai_key(), "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read()
            return (data, response.headers.get("Content-Type") or "") if raw else json.loads(data.decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read()[:180].decode("utf-8", "replace")
        raise RuntimeError(f"xAI HTTP {exc.code}: {detail}") from exc


def _clip(path: Path, limit: int) -> str:
    try:
        return path.read_text(encoding="utf-8").strip()[:limit]
    except OSError:
        return ""


def _persona(agent: str) -> str:
    now = datetime.now().astimezone()
    hour = now.hour % 12 or 12
    when = f"{now:%A}, {now:%B} {now.day}, {now.year}, {hour}:{now:%M} {'AM' if now.hour < 12 else 'PM'}"
    parts = [VOICE_INSTRUCTION, f"Current local date and time: {when} ({now.tzname() or 'local time'})."]
    home = AGENTS[agent]["home"]
    parts += [_clip(home / "SOUL.md", 8000),
              _clip(home / "memories" / "USER.md", 1500),
              _clip(home / "memories" / "MEMORY.md", 1500)]
    return "\n\n".join(p for p in parts if p)


def _history(agent: str) -> list[dict]:
    try:
        data = json.loads((STATE / f"{agent}-transcript.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    kept = []
    for item in data[-12:] if isinstance(data, list) else []:
        if isinstance(item, dict) and item.get("role") in ("user", "assistant") and str(item.get("content") or "").strip():
            kept.append({"role": item["role"], "content": str(item["content"])[:800]})
    return kept


def ask(agent: str, heard: str) -> str:
    history = _history(agent)
    messages = [{"role": "system", "content": _persona(agent)}] + history + [{"role": "user", "content": heard}]
    payload = _post(CHAT_URL, {"model": VOICE_MODEL, "messages": messages, "max_tokens": 120, "temperature": 0.4})
    content = ((payload.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
    if isinstance(content, list):
        content = "".join(str(p.get("text") or "") if isinstance(p, dict) else str(p) for p in content)
    reply = str(content).strip()
    if not reply:
        raise RuntimeError("empty reply")
    history += [{"role": "user", "content": heard[:800]}, {"role": "assistant", "content": reply[:800]}]
    (STATE / f"{agent}-transcript.json").write_text(json.dumps(history[-12:]) + "\n", encoding="utf-8")
    return reply


def synthesize(text: str, voice_id: str, codec: str = "mp3") -> tuple[bytes, str]:
    fmt = {"codec": "mp3", "sample_rate": 24000, "bit_rate": 64000} if codec == "mp3" else {"codec": codec, "sample_rate": 24000}
    audio, mime = _post(TTS_URL, {"text": text, "voice_id": voice_id, "language": "en",
                                  "text_normalization": True, "output_format": fmt}, raw=True)
    if len(audio) < 200:
        raise RuntimeError("voice response was empty")
    if codec == "mp3" and not (audio.startswith(b"\xff") or audio.startswith(b"ID3")):
        raise RuntimeError("voice response was not mp3")
    return audio, (mime or "audio/mpeg").split(";", 1)[0]


def route(slot: str, heard: str) -> tuple[str, str]:
    agent = SLOTS.get(slot, "paula")
    match = NAME_RE.match(heard)
    if match:
        token = match.group(1).lower()
        agent = "liam" if token.startswith("liam") else "paula"
        rest = heard[match.end():].strip()
        heard = rest or heard
    return agent, heard


def turn(slot: str, heard: str) -> dict:
    agent, text = route(slot, heard)
    name = AGENTS[agent]["name"]
    t0 = time.perf_counter()
    reply = ask(agent, text)
    t1 = time.perf_counter()
    out = {"heard": f"(to {name}) {heard}", "reply": reply, "error": "", "audio": "", "audio_type": "",
           "who": slot, "agent": agent}
    try:
        audio, mime = synthesize(reply, AGENTS[agent]["voice"])
        out["audio"], out["audio_type"] = base64.b64encode(audio).decode("ascii"), mime
    except Exception as exc:  # noqa: BLE001
        print(f"[bridge] xAI voice failed, tablet will use Pico: {exc}", flush=True)
    t2 = time.perf_counter()
    print(f"[bridge] {slot}->{agent} reply {t1 - t0:.2f}s voice {t2 - t1:.2f}s", flush=True)
    return out



def _peer_ip(handler: BaseHTTPRequestHandler) -> str:
    ip = handler.client_address[0] if handler.client_address else ""
    if ip.startswith("::ffff:"):
        ip = ip[7:]
    return ip


def _allowed(handler: BaseHTTPRequestHandler) -> bool:
    return _peer_ip(handler) in ALLOWED_IPS


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        print(f"[bridge] {self.address_string()} {fmt % args}", flush=True)

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if not _allowed(self):
            print(f"[bridge] deny {_peer_ip(self)} GET {self.path}", flush=True)
            return self._json(403, {"error": "forbidden", "reply": ""})
        if self.path.split("?", 1)[0] != "/health":
            return self._json(404, {"error": "not found"})
        self._json(200, {"ok": True, "model": _model is not None, "model_error": _model_error,
                         "agents": {"sarah": "paula", "james": "liam"}})

    def do_POST(self) -> None:  # noqa: N802
        if not _allowed(self):
            print(f"[bridge] deny {_peer_ip(self)} POST {self.path}", flush=True)
            return self._json(403, {"error": "forbidden", "reply": ""})
        if self.path.split("?", 1)[0] != "/talk":
            return self._json(404, {"error": "not found"})
        slot = (urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("who") or ["sarah"])[0].strip().lower()
        slot = slot if slot in SLOTS else "sarah"
        length = int(self.headers.get("Content-Length") or "0")
        if length <= 44 or length > 8_000_000:
            return self._json(400, {"error": "empty or oversized recording", "reply": ""})
        audio = self.rfile.read(length)
        if not _turn.acquire(blocking=False):
            return self._json(409, {"error": "Still answering", "reply": "", "who": slot})
        try:
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as handle:
                handle.write(audio)
                wav_path = handle.name
            t0 = time.perf_counter()
            try:
                heard = transcribe(wav_path)
            finally:
                os.unlink(wav_path)
            print(f"[bridge] hear {time.perf_counter() - t0:.2f}s", flush=True)
            if not heard:
                return self._json(200, {"heard": "", "reply": "", "error": "", "who": slot})
            self._json(200, turn(slot, heard))
        except Exception as exc:  # noqa: BLE001
            print(f"[bridge] turn failed: {exc}", flush=True)
            self._json(200, {"heard": "", "reply": FALLBACK, "error": str(exc), "who": slot})
        finally:
            _turn.release()


def main() -> None:
    STATE.mkdir(parents=True, exist_ok=True)
    threading.Thread(target=load_model, daemon=True).start()
    servers = [ThreadingHTTPServer((HOST, PORT), Handler)]
    bound = [f"http://{HOST}:{PORT}"]
    if LAN_HOST and LAN_HOST not in (HOST, "127.0.0.1", "::1"):
        try:
            servers.append(ThreadingHTTPServer((LAN_HOST, PORT), Handler))
            bound.append(f"http://{LAN_HOST}:{PORT}")
        except OSError as exc:
            print(f"tablet-voice could not bind {LAN_HOST}:{PORT}: {exc}", flush=True)
    for extra in servers[1:]:
        threading.Thread(target=extra.serve_forever, daemon=True).start()
    print("tablet-voice listening on " + ", ".join(bound), flush=True)
    servers[0].serve_forever()


if __name__ == "__main__":
    main()
