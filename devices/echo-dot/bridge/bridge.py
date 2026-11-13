#!/usr/bin/env python3
"""Charlie voice bridge (runs on the Linux host).

Echo Dot agent <-(adb forward over Wi-Fi adb)-> this bridge -> faster-whisper -> Charlie webhook
-> reply (inbox file or local HTTP POST) -> Piper TTS -> Dot playback.
All configuration comes from the environment (see config.env.example).
"""
import random
import json, os, queue, re, socket, struct, subprocess, sys, threading, time, uuid
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np

HOME = Path(__file__).resolve().parent.parent
E = os.environ.get
ADB = E("ADB", str(Path.home() / ".local/bin/adb"))
DOT = E("DOT_ADB", "DOT_HOST:5555")
PORT = int(E("BRIDGE_PORT", "8790"))          # agent port on the Dot (127.0.0.1)
LOCAL_PORT = int(E("LOCAL_PORT", "18790"))   # adb forward port on the Linux host
REPLY_BIND = E("REPLY_BIND", "127.0.0.1")
REPLY_PORT = int(E("REPLY_PORT", "8791"))
WEBHOOK = E("CHARLIE_WEBHOOK_URL", "").strip()
WEBHOOK_KEY = E("CHARLIE_WEBHOOK_KEY", "").strip()
STUB = (E("STUB_ECHO") or ("0" if WEBHOOK else "1")) == "1"
REPLY_TIMEOUT = float(E("REPLY_TIMEOUT", "120"))
PLAY_GAIN = float(E("PLAY_GAIN", "0.35"))
AT_GAIN = float(E("AT_GAIN", "0.8"))           # audiotrack: fraction of full scale after normalizing
PLAY_MODE = E("PLAY_MODE", "audiotrack")        # tinyplay (direct ALSA) | audiotrack (Dot agent)
TINY_DEVICE = int(E("TINY_DEVICE", "8"))      # card 0 device 8 = I2S0DL1 -> TLV320 -> speaker
TINY_GAIN = float(E("TINY_GAIN", "0.7"))      # fraction of full scale after normalizing
WHISPER_MODEL = E("WHISPER_MODEL", "base.en")
PIPER_MODEL = E("PIPER_MODEL", str(HOME / "voices/en_US-ryan-high.onnx"))
INBOX = Path(E("INBOX_DIR", str(HOME / "inbox")))
AGENT_DEX = E("AGENT_DEX", "/data/local/tmp/charlie/dotagent.dex")
RATE = 16000
BRAIN = E("BRAIN", "realtime").strip()            # fast = Grok Build CLI (SuperGrok login) | webhook = Charlie only
GROK_BIN = E("GROK_BIN", str(Path.home() / ".local/bin/grok"))
GROK_MODEL = E("GROK_MODEL", "grok-4.7-build-fast")
GROK_TIMEOUT = float(E("GROK_TIMEOUT", "25"))
GROK_TOOLS = ("run_terminal_command,read_file,search_replace,list_dir,grep,kill_command_or_subagent,todo_write,"
              "get_command_or_subagent_output,spawn_subagent,scheduler_create,scheduler_delete,scheduler_list,monitor,"
              "search_tool,use_tool,workflow,enter_plan_mode,exit_plan_mode,ask_user_question,send_feedback,web_search,"
              "web_fetch,image_gen,image_edit,image_to_video,reference_to_video,write")
CHARLIE_PERSONA = (
    "You are Charlie, Michael Gannotti's chief of staff for his fleet of Grok Bots, speaking out loud through an "
    "Echo Dot in a calm, friendly male voice. Answer in one to three short spoken sentences. Plain words only: no "
    "markdown, lists, emoji, URLs or code. You have no tools. Never name Michael's employer; if his job comes up, "
    "he is a Principal AI Solutions Engineer. If the request needs the real Charlie (doing a task, sending or "
    "checking anything, a status report, memory or notes, calendars, email, other bots, current or private facts "
    "you cannot know), begin your reply with the exact tag [HANDOFF] and write nothing after it. Stay silent. "
    "Do not say you are checking with the team, passing that to the team, waiting on the team, or that you will "
    "let Michael know later. Do not promise a follow-up. The bridge speaks Charlie's answer when it arrives.")
NO_UPDATE = "I don't have that update yet."
# Spoken lines that promise a follow-up. The bridge cuts these and waits for Charlie instead.
_HOLDING_RE = re.compile(
    r"(check(?:ing)? with the team"
    r"|pass(?:ing)? (?:that |it |this )?to the team"
    r"|(?:wait(?:ing)?|still waiting) on the team"
    r"|let you know"
    r"|one moment"
    r"|pull that together"
    r"|hang tight"
    r"|the team(?:'s| is) still"
    r"|comes back"
    r"|as soon as (?:it|they))",
    re.I)

def _looks_like_holding(text):
    """True for a short line that promises a team follow-up instead of answering."""
    t = " ".join((text or "").split())
    if not t or len(t) > 220:
        return False
    return _HOLDING_RE.search(t) is not None

def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)

def condition(pcm16, rate, gain):
    """Remove DC, normalize to gain*full scale (no clipping), resample to 48 kHz, 10 ms fades, pad."""
    x = pcm16.astype(np.float64); x -= x.mean()
    peak = float(np.max(np.abs(x))) or 1.0
    x *= 32000 * gain / peak
    if rate != 48000:
        n = int(len(x) * 48000 / rate)
        x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x)
    f = min(480, len(x) // 4)
    if f:
        r = np.linspace(0, 1, f); x[:f] *= r; x[-f:] *= r[::-1]
    x = np.concatenate([np.zeros(4800), x, np.zeros(4800)])
    return np.clip(x, -32767, 32767).astype("<i2")

# ---------------- Dot connection ----------------
class Dot:
    def __init__(self):
        self.sock = None
        self.lock = threading.Lock()
        self.playing_done = threading.Event()
        self.playing = False

    def send(self, t, payload=b""):
        with self.lock:
            if not self.sock:
                return False
            try:
                self.sock.sendall(t.encode() + struct.pack(">I", len(payload)) + payload)
                return True
            except OSError:
                return False

    def play_pcm(self, pcm16: np.ndarray, rate: int, wait=True):
        if PLAY_MODE == "tinyplay":
            return self.play_tinyplay(pcm16, rate)
        return self.play_audiotrack(pcm16, rate, wait)

    _tp_lock = threading.Lock()
    def play_tinyplay(self, pcm16: np.ndarray, rate: int):
        """Play straight to the ALSA device (card 0, I2S0DL1) - the only path proven audible
        on this Dot without Amazon's audio stack. Normalized, resampled to 48 kHz stereo."""
        import wave
        x = pcm16.astype(np.float32)
        peak = float(np.max(np.abs(x))) or 1.0
        x = x / peak * 32000 * TINY_GAIN
        n = int(len(x) * 48000 / rate)
        x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x)
        x = np.concatenate([np.zeros(2400), x, np.zeros(4800)])  # pad: avoid clipped start/end
        st = np.repeat(np.clip(x, -32768, 32767).astype("<i2"), 2)
        with self._tp_lock:
            tmp = "/tmp/charlie-play.wav"
            w = wave.open(tmp, "wb"); w.setnchannels(2); w.setsampwidth(2); w.setframerate(48000)
            w.writeframes(st.tobytes()); w.close()
            r1 = adb("push", tmp, "/data/local/tmp/charlie-play.wav", timeout=20)
            r2 = adb("shell", f"su -c 'tinyplay /data/local/tmp/charlie-play.wav -D 0 -d {TINY_DEVICE}'",
                     timeout=len(st) / 96000 + 15)
            ok = bool(r1 and r1.returncode == 0 and r2 and r2.returncode == 0)
            if len(pcm16) > 8000 or not ok:
                log(f"tinyplay {n/48000:.1f}s: {'ok' if ok else 'FAILED ' + ((r2.stdout + r2.stderr)[-200:] if r2 else 'timeout')}")
            return ok

    def play_audiotrack(self, pcm16: np.ndarray, rate: int, wait=True):
        pcm = condition(pcm16, rate, AT_GAIN); rate = 48000
        self.playing = True
        try:
            return self._send_play(pcm, rate, wait)
        finally:
            self.playing = False
            if current.get("sess"):
                current["sess"].last_audio = time.time()

    def _send_play(self, pcm, rate, wait):
        self.playing_done.clear()
        ok = self.send("P", struct.pack(">I", rate) + pcm.tobytes())
        if len(pcm) > 8000:  # don't log beeps
            log(f"play {len(pcm)/rate:.1f}s @ {rate} Hz -> Dot: {'sent' if ok else 'NOT CONNECTED'}")
        if ok and wait:
            if not self.playing_done.wait(len(pcm) / rate + 10):
                log("play: no done-ack from Dot")
        return ok

    def beep(self, hz=880, ms=150):
        t = np.arange(int(22050 * ms / 1000)) / 22050
        self.play_pcm((np.sin(2 * np.pi * hz * t) * 8000 * np.minimum(1, (len(t) - np.arange(len(t))) / 400)).astype(np.int16), 22050)

dot = Dot()

def adb(*args, timeout=15):
    try:
        return subprocess.run([ADB, "-s", DOT, *args], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return None

def supervisor():
    """Keep adb connected, the reverse tunnel up, and the agent running."""
    while True:
        try:
            subprocess.run([ADB, "connect", DOT], capture_output=True, timeout=10)
            r = subprocess.run([ADB, "forward", "--list"], capture_output=True, text=True, timeout=10)
            if f"{DOT} tcp:{LOCAL_PORT} tcp:{PORT}" not in r.stdout:
                adb("forward", f"tcp:{LOCAL_PORT}", f"tcp:{PORT}")
                log("adb forward set")
            # The speaker amp is held muted via the codec's MFP GPIO ("MFP Gpio Mute" On) unless
            # Amazon's audio stack releases it; we run without that stack, so release it ourselves.
            m = adb("shell", "su -c 'tinymix \"MFP Gpio Mute\"'")
            if m and m.returncode == 0 and ">On" in m.stdout.replace(" ", ""):
                adb("shell", "su -c 'tinymix \"MFP Gpio Mute\" Off'")
                log("speaker amp unmuted (MFP Gpio Mute -> Off)")
            ps = adb("shell", "su -c 'ps'")
            if ps and ps.returncode == 0 and "DotAgent" not in ps.stdout and "charlie-agent" not in ps.stdout:
                start = (f"su -c 'CLASSPATH={AGENT_DEX} nohup app_process /system/bin --nice-name=charlie-agent "
                         f"DotAgent {PORT} >/data/local/tmp/charlie/agent.log 2>&1 &'")
                adb("shell", start)
                log("agent started")
        except Exception as e:
            log("supervisor:", e)
        time.sleep(10)

# ---------------- STT / TTS ----------------
_whisper = None
def transcribe(audio_i16: np.ndarray) -> str:
    global _whisper
    if _whisper is None:
        from faster_whisper import WhisperModel
        _whisper = WhisperModel(WHISPER_MODEL, device="cpu", compute_type="int8")
    a = audio_i16.astype(np.float32)
    peak = float(np.max(np.abs(a))) or 1.0
    a = a / peak * 0.9  # auto gain
    segs, _ = _whisper.transcribe(a, language="en", vad_filter=False, beam_size=1)
    return " ".join(s.text.strip() for s in segs).strip()

_voices = {}
_vlock = threading.Lock()
def tts(text: str, model: str = None):
    """In-process Piper with the voice kept loaded (~0.1-0.4 s/sentence); subprocess fallback."""
    model = model or PIPER_MODEL
    try:
        from piper import PiperVoice
        with _vlock:
            v = _voices.get(model) or _voices.setdefault(model, PiperVoice.load(model))
            chunks = list(v.synthesize(text))
        if chunks:
            return np.frombuffer(b"".join(c.audio_int16_bytes for c in chunks), dtype="<i2"), chunks[0].sample_rate
    except Exception as e:
        log("in-process piper failed, using subprocess:", e)
    return tts_proc(text, model)

def tts_proc(text: str, model: str = None):
    model = model or PIPER_MODEL
    cfg = json.loads(Path(model + ".json").read_text())
    rate = cfg.get("audio", {}).get("sample_rate", 22050)
    piper = str(Path(sys.executable).parent / "piper")
    r = subprocess.run([piper, "-m", model, "--output-raw"], input=text.encode(), capture_output=True, timeout=120)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode()[-300:])
    return np.frombuffer(r.stdout, dtype="<i2"), rate

# ---------------- Charlie ----------------
replies = {}
waiting = set()   # (session_id, turn) a live Session is waiting on
reply_cv = threading.Condition()
play_lock = threading.Lock()

def speak(text, why="", model=None):
    """Synthesize and play now (serialized so replies never overlap)."""
    text = (text or "").strip()[:2000]
    if not text:
        return False
    try:
        pcm, rate = tts(text, model)
        with play_lock:
            log(f"speak{(' (' + why + ')') if why else ''}: {text[:80]}")
            return dot.play_pcm(pcm, rate)
    except Exception as e:
        log("tts/play failed:", e)
        return False

suppressed = set()   # (session_id, turn) answered by the fast brain; drop Charlie's echo reply

pending_handoffs = set()   # (session_id, turn) real requests whose answer has not been delivered yet

def mark_handoff(session_id, turn):
    pending_handoffs.add((str(session_id), int(turn)))

def take_handoff(key):
    """True once for a pending handoff; it is then considered delivered."""
    if key in pending_handoffs:
        pending_handoffs.discard(key); return True
    return False

def deliver_reply(session_id, turn, text):
    key = (str(session_id), int(turn))
    with reply_cv:
        if key in waiting and key not in suppressed:
            replies[key] = text
            reply_cv.notify_all()
            return
    # Nobody is waiting: speak only a late answer to a pending handoff.
    if take_handoff(key):
        threading.Thread(target=say_reply, args=(text, f"late handoff {key[0]}#{key[1]}"), daemon=True).start()
    else:
        log(f"charlie reply for {key[0]}#{key[1]} not spoken (no pending handoff): {str(text)[:80]}")

def inbox_watcher():
    """Speak inbox reply files that no live Session claims within a few seconds."""
    while True:
        try:
            for f in sorted(INBOX.glob("*.txt")):
                if time.time() - f.stat().st_mtime < 3:
                    continue
                stem = f.stem
                sid, _, turn = stem.rpartition("-")
                with reply_cv:
                    claimed = turn.isdigit() and (sid, int(turn)) in waiting
                if claimed:
                    continue
                text = f.read_text().strip()
                f.rename(f.with_suffix(".done"))
                if turn.isdigit() and take_handoff((sid, int(turn))):
                    say_reply(text, f"inbox {stem} (late handoff)")
                else:
                    log(f"inbox {stem} not spoken (no pending handoff): {text[:80]}")
        except Exception as e:
            log("inbox watcher:", e)
        time.sleep(1)

def post_webhook(body: dict):
    if STUB or not WEBHOOK:
        return
    req = urllib.request.Request(WEBHOOK, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    if WEBHOOK_KEY:
        req.add_header("Authorization", f"Bearer {WEBHOOK_KEY}")
    with urllib.request.urlopen(req, timeout=20) as r:
        log("webhook", r.status)

def wait_reply(session_id, turn):
    f = INBOX / f"{session_id}-{turn}.txt"
    deadline = time.time() + REPLY_TIMEOUT
    with reply_cv:
        waiting.add((session_id, turn))
        try:
            return _wait_reply(session_id, turn, f, deadline)
        finally:
            waiting.discard((session_id, turn))

def _wait_reply(session_id, turn, f, deadline):
    if True:
        while time.time() < deadline:
            if (session_id, turn) in replies:
                pending_handoffs.discard((str(session_id), int(turn)))
                return replies.pop((session_id, turn))
            if f.exists():
                text = f.read_text().strip()
                f.rename(f.with_suffix(".done"))
                pending_handoffs.discard((str(session_id), int(turn)))
                return text
            reply_cv.wait(0.5)
    return None

class ReplyHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path == "/resync":
            try:
                b = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0) or 0)) or b"{}")
            except ValueError:
                self.send_error(400, "bad json"); return
            ok = dot.send("R", b"\x01" if b.get("active") else b"\x00")
            self.send_response(200 if ok else 503); self.end_headers(); return
        if self.path == "/say":
            raw = self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
            try:
                b = json.loads(raw or b"{}")
                text = str(b.get("text", "")) if isinstance(b, dict) else str(b)
                v = b.get("voice") if isinstance(b, dict) else None
                model = str(HOME / "voices" / f"{Path(v).name}.onnx") if v else None
                if model and not Path(model).exists():
                    self.send_error(404, "unknown voice"); return
            except ValueError:
                text, model = raw.decode("utf-8", "replace"), None  # accept a plain-text body too
            if not text.strip():
                self.send_error(400, "empty text"); return
            if not dot.sock:
                self.send_error(503, "Dot not connected"); return
            threading.Thread(target=(say_reply if not model else speak), args=((text, "say") if not model else (text, "say", model)), daemon=True).start()
            self.send_response(202); self.end_headers(); self.wfile.write(b"queued"); return
        if self.path != "/reply":
            self.send_error(404); return
        try:
            b = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            deliver_reply(b["session_id"], b["turn"], b["text"])
            self.send_response(200); self.end_headers(); self.wfile.write(b"ok")
        except Exception as e:
            self.send_error(400, str(e))
    def log_message(self, *a):
        pass

# ---------------- Fast brain (Grok Build CLI, subscription login; never XAI_API_KEY) ----------------
_SENT = re.compile(r"(.+?[.!?])(\s+|$)", re.S)

class Speaker:
    """Speaks sentences in order as they stream in (TTS of sentence n+1 overlaps playback of n)."""
    def __init__(self, why, ts=None):
        self.q, self.why, self.first, self.ts = queue.Queue(), why, None, ts
        self.t = threading.Thread(target=self.run, daemon=True); self.t.start()
    def say(self, sentence):
        sentence = sentence.strip()
        if sentence:
            self.q.put(tts(sentence))
    def run(self):
        while True:
            item = self.q.get()
            if item is None:
                return
            with play_lock:
                if self.first is None:
                    self.first = time.time()
                if self.ts: self.ts.started.set()
                dot.play_pcm(*item)
    def close(self, wait=True):
        self.q.put(None)
        if wait:
            self.t.join(120)

# ---------------- Warm grok (ACP over `grok agent stdio`) + fillers ----------------
GROK_WARM = E("GROK_WARM", "1") == "1"
FILLER_DELAY = float(E("FILLER_DELAY", "1.6" if BRAIN == "local" else "1.0"))   # play a filler if no reply audio this long after speech ends
FILLER_TEXTS = ["Mm-hmm.", "Okay.", "Sure.", "Got it.", "Right."]
FILLERS = []          # pre-rendered (pcm, rate)
_REMIND = ("(Stay in character as Charlie: one to three short spoken sentences, plain words, no tools; "
           "if it needs the full Charlie, reply with only [HANDOFF] and no other words.)")

def load_fillers():
    for t in FILLER_TEXTS:
        try:
            FILLERS.append(tts(t))
        except Exception as e:
            log("filler render failed:", e)
    log(f"fillers cached: {len(FILLERS)}")

class TurnState:
    def __init__(self):
        self.started = threading.Event()   # reply (or filler) audio has begun
        self.cancel = threading.Event()

def filler_timer(ts, t_end):
    delay = max(0.0, (t_end or time.time()) + FILLER_DELAY - time.time())
    if ts.started.wait(delay) or ts.cancel.is_set() or not FILLERS:
        return
    pcm, rate = random.choice(FILLERS)
    with play_lock:
        if ts.started.is_set() or ts.cancel.is_set():
            return
        ts.started.set()
        log(f"filler at +{time.time()-(t_end or time.time()):.1f}s")
        dot.play_pcm(pcm, rate)

class WarmGrok:
    """One long-lived `grok agent stdio` process (ACP JSON-RPC); one grok session per voice session."""
    def __init__(self):
        self.p = None; self.n = 0; self.pending = {}; self.sink = None
        self.wlock = threading.Lock(); self.plock = threading.Lock()
        self.sessions = {}; self.spare = None; self.starts = 0

    def alive(self):
        return self.p is not None and self.p.poll() is None

    def start(self):
        if self.alive():
            return
        env = {k: v for k, v in os.environ.items() if k != "XAI_API_KEY"}
        self.p = subprocess.Popen([GROK_BIN, "agent", "-m", GROK_MODEL, "--reasoning-effort", "low", "stdio"],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  text=True, bufsize=1, env=env, cwd=str(Path.home() / ".cache"))
        self.pending.clear(); self.sessions.clear(); self.spare = None; self.starts += 1
        threading.Thread(target=self.reader, args=(self.p,), daemon=True).start()
        self.rpc("initialize", {"protocolVersion": 1, "clientCapabilities": {}}, 20)
        self.spare = self.new_session()
        log(f"warm grok ready (pid {self.p.pid}, start #{self.starts})")

    def send(self, obj):
        with self.wlock:
            self.p.stdin.write(json.dumps(obj) + "\n"); self.p.stdin.flush()

    def rpc(self, method, params, timeout=GROK_TIMEOUT):
        self.n += 1; i = self.n; q = queue.Queue(); self.pending[i] = q
        self.send({"jsonrpc": "2.0", "id": i, "method": method, "params": params})
        try:
            r = q.get(timeout=timeout)
        except queue.Empty:
            raise TimeoutError(method)
        finally:
            self.pending.pop(i, None)
        if "error" in r:
            raise RuntimeError(f"{method}: {r['error']}")
        return r.get("result")

    def reader(self, p):
        for line in p.stdout:
            try:
                m = json.loads(line)
            except ValueError:
                continue
            if "method" in m and "id" in m:          # agent -> client request (permission etc.): refuse
                res = {"outcome": {"outcome": "cancelled"}} if "permission" in m["method"] else None
                msg = {"jsonrpc": "2.0", "id": m["id"]}
                msg.update({"result": res} if res else {"error": {"code": -32601, "message": "not supported"}})
                try: self.send(msg)
                except Exception: pass
            elif "id" in m:
                q = self.pending.get(m["id"])
                if q: q.put(m)
            elif m.get("method") == "session/update" and self.sink:
                self.sink(m["params"].get("update", {}))
        for q in list(self.pending.values()):
            q.put({"error": "grok process exited"})

    def new_session(self):
        sid = self.rpc("session/new", {"cwd": str(Path.home() / ".cache"), "mcpServers": []}, 30)["sessionId"]
        try:
            self.rpc("session/set_model", {"sessionId": sid, "modelId": GROK_MODEL}, 10)
        except Exception:
            pass
        return sid

    def ask(self, key, text, history, on_text):
        """Run one prompt; on_text(delta) per chunk. Returns 'ok' or raises."""
        with self.plock:
            self.start()
            fresh = key not in self.sessions
            if fresh:
                self.sessions[key] = self.spare or self.new_session(); self.spare = None
            sid = self.sessions[key]
            if fresh:
                convo = "".join(f"Michael: {u}\nCharlie: {a}\n" for u, a in history[-8:])
                prompt = CHARLIE_PERSONA + "\n\n" + (f"Conversation so far:\n{convo}" if convo else "") + \
                         f"Michael just said: {text}\nReply as Charlie, out loud. Do not use tools or look anything up."
            else:
                prompt = f"Michael says: {text}\n{_REMIND}"
            bad = threading.Event()
            def sink(u):
                kind = u.get("sessionUpdate")
                if kind == "agent_message_chunk" and not bad.is_set():
                    on_text(u.get("content", {}).get("text", ""))
                elif kind in ("tool_call", "tool_call_update") and not bad.is_set():
                    bad.set()
                    try: self.send({"jsonrpc": "2.0", "method": "session/cancel", "params": {"sessionId": sid}})
                    except Exception: pass
            self.sink = sink
            try:
                self.rpc("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": prompt}]}, GROK_TIMEOUT)
            except TimeoutError:
                log("warm grok timed out; restarting it"); self.kill(); raise
            finally:
                self.sink = None
            if bad.is_set():
                self.sessions.pop(key, None)   # that session is polluted; start fresh next turn
                raise RuntimeError("model tried to use a tool")
            if self.spare is None:
                threading.Thread(target=self.prep_spare, daemon=True).start()
            return "ok"

    def prep_spare(self):
        with self.plock:
            try:
                if self.alive() and self.spare is None:
                    self.spare = self.new_session()
            except Exception as e:
                log("warm grok spare session failed:", e)

    def end(self, key):
        self.sessions.pop(key, None)

    def kill(self):
        try: self.p.kill()
        except Exception: pass

    def keeper(self):
        while True:
            try:
                if not self.alive():
                    with self.plock:
                        self.start()
            except Exception as e:
                log("warm grok start failed:", e); self.kill()
            time.sleep(10)

warm = WarmGrok()

class StreamSpeak:
    """Turns streamed text into spoken sentences. A [HANDOFF] reply is not spoken."""
    def __init__(self, why, ts, t0):
        self.why, self.ts, self.t0 = why, ts, t0
        self.buf = self.full = ""; self.spk = None; self.handoff = False
    def feed(self, d):
        self.full += d; self.buf += d
        lead = self.full.lstrip()
        if not self.handoff and lead.startswith("[HANDOFF]"):
            self.handoff = True; self.buf = self.buf.replace("[HANDOFF]", "", 1)
            return   # stay quiet; Charlie's answer is spoken when it arrives
        elif not self.handoff and lead and "[HANDOFF]"[:len(lead)] == lead:
            return
        if self.handoff:
            return
        while True:
            m = _SENT.match(self.buf)
            if not m or not m.group(2):
                break
            self._say(m.group(1)); self.buf = self.buf[m.end():]
    def _say(self, s):
        if self.spk is None:
            self.spk = Speaker(self.why, self.ts); log(f"fast brain first sentence after {time.time()-self.t0:.1f}s")
        self.spk.say(s)
    def reply(self):
        return self.full.replace("[HANDOFF]", "").strip()
    def finish(self):
        if self.handoff:
            self.buf = ""
            if self.spk: self.spk.close(False)
            return
        if self.buf.strip():
            self._say(self.buf); self.buf = ""
        if self.spk: self.spk.close()

LOCAL_LLM_URL = E("LOCAL_LLM_URL", "http://127.0.0.1:8080/v1/chat/completions")

def local_reply(history, text, why, t_end=None, ts=None):
    """BRAIN=local: llama.cpp server (OpenAI-compatible, streaming). Returns (reply, handoff) or (None, False)."""
    msgs = [{"role": "system", "content": CHARLIE_PERSONA}]
    for u, a in history[-8:]:
        msgs += [{"role": "user", "content": u}, {"role": "assistant", "content": a}]
    msgs.append({"role": "user", "content": text})
    t0 = time.time(); st = StreamSpeak(why, ts, t0)
    try:
        req = urllib.request.Request(LOCAL_LLM_URL, json.dumps({"messages": msgs, "stream": True, "max_tokens": 150,
                                     "temperature": 0.6}).encode(), {"Content-Type": "application/json"})
        for line in urllib.request.urlopen(req, timeout=GROK_TIMEOUT):
            line = line.decode().strip()
            if line.startswith("data: ") and not line.endswith("[DONE]"):
                st.feed(json.loads(line[6:])["choices"][0]["delta"].get("content") or "")
    except Exception as e:
        log("local brain failed:", e)
    if not st.reply() and not st.handoff:
        if st.spk: st.spk.close(False)
        return None, False
    st.finish()
    if t_end and st.spk and st.spk.first:
        log(f"fast brain (local): end of speech -> first audio {st.spk.first - t_end:.1f}s")
    log(f"fast brain reply (local, {time.time()-t0:.1f}s, handoff={st.handoff}): {st.reply()}")
    return st.reply(), st.handoff

def fast_reply(history, text, why, t_end=None, key=None, ts=None):
    """Warm grok first, then a cold `grok -p`; returns (reply, handoff) or (None, False) for the webhook path."""
    if BRAIN == "local":
        r = local_reply(history, text, why, t_end, ts)
        if r[0] is not None:
            return r
    if GROK_WARM:
        t0 = time.time(); st = StreamSpeak(why, ts, t0)
        try:
            warm.ask(key or why, text, history, st.feed)
        except Exception as e:
            log("warm grok failed:", e)
        if st.handoff or st.reply():
            st.finish()
            if t_end and st.spk and st.spk.first:
                log(f"fast brain (warm): end of speech -> first audio {st.spk.first - t_end:.1f}s")
            log(f"fast brain reply (warm, {time.time()-t0:.1f}s, handoff={st.handoff}): {st.reply()}")
            return st.reply(), st.handoff
        if st.spk: st.spk.close(False)
    return cold_reply(history, text, why, t_end, ts)

def cold_reply(history, text, why, t_end=None, ts=None):
    """Stream a reply from grok -p and speak it sentence by sentence. Returns (reply, handoff) or (None, False)."""
    convo = "".join(f"Michael: {u}\nCharlie: {a}\n" for u, a in history[-8:])
    prompt = (f"Conversation so far:\n{convo}" if convo else "") + f"Michael just said: {text}\nReply as Charlie, out loud. Do not use tools or look anything up."
    prompt = CHARLIE_PERSONA + "\n\n" + prompt
    env = {k: v for k, v in os.environ.items() if k != "XAI_API_KEY"}
    cmd = [GROK_BIN, "-p", prompt, "-m", GROK_MODEL,
           "--disallowed-tools", GROK_TOOLS, "--disable-web-search", "--no-subagents", "--no-plan",
           "--reasoning-effort", "low",
           "--output-format", "streaming-json", "--include-partial-messages"]
    t0 = time.time(); buf, full, spk, handoff = "", "", None, False
    try:
        pr = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                              text=True, env=env, cwd=str(Path.home() / ".cache"))
        killer = threading.Timer(GROK_TIMEOUT, pr.kill); killer.start()
        for line in pr.stdout:
            try:
                ev = json.loads(line)
            except ValueError:
                continue
            if ev.get("type") == "tool_call":
                break   # it must not use tools; stop and fall back
            if ev.get("type") != "text":
                continue
            d = ev.get("data", ""); full += d; buf += d
            lead = full.lstrip()
            if not handoff and lead.startswith("[HANDOFF]"):
                handoff = True; buf = buf.replace("[HANDOFF]", "", 1)
            elif not handoff and lead and "[HANDOFF]"[:len(lead)] == lead:
                continue  # tag still arriving
            if handoff:
                continue  # stay quiet; Charlie's answer is spoken when it arrives
            while True:
                m = _SENT.match(buf)
                if not m or not m.group(2):
                    break
                if spk is None:
                    spk = Speaker(why, ts); log(f"fast brain first sentence after {time.time()-t0:.1f}s")
                spk.say(m.group(1)); buf = buf[m.end():]
        killer.cancel()
        try: pr.wait(3)
        except subprocess.TimeoutExpired: pr.kill()
    except Exception as e:
        log("fast brain failed:", e)
    reply = full.replace("[HANDOFF]", "").strip()
    if handoff:
        if spk: spk.close(False)
        log(f"fast brain reply ({time.time()-t0:.1f}s, handoff=True, quiet): {reply}")
        return reply, True
    if not reply:
        if spk: spk.close(False)
        log(f"fast brain gave no reply ({time.time()-t0:.1f}s); falling back to webhook")
        return None, False
    if buf.strip():
        if spk is None: spk = Speaker(why, ts)
        spk.say(buf)
    spk.close()
    if t_end and spk.first:
        log(f"fast brain: end of speech -> first audio {spk.first - t_end:.1f}s")
    log(f"fast brain reply ({time.time()-t0:.1f}s, handoff={handoff}): {reply}")
    return reply, handoff

def charlie_followup(session_id, turn, body):
    """Background: give Charlie the turn; when handed off, speak Charlie's reply when it arrives."""
    if body.get("handoff"):
        mark_handoff(session_id, turn)
    try:
        post_webhook(body)
    except Exception as e:
        log("webhook (fast-brain copy) failed:", e)
        if body.get("handoff"):
            say_reply(NO_UPDATE, "handoff-fail")
        return
    if body.get("handoff"):
        r = wait_reply(session_id, turn)
        say_reply(r or NO_UPDATE, f"handoff {session_id}#{turn}")

# ---------------- Realtime speech-to-speech (xAI grok-voice) ----------------
RT_URL = E("RT_URL", "wss://api.x.ai/v1/realtime")
RT_MODEL = E("RT_MODEL", "grok-voice-3.0")
RT_VOICE = E("RT_VOICE", "rex")
RT_OUT_RATE = 48000
RT_GAIN = float(E("RT_GAIN", "1.0"))
RT_MAX_SECS = float(E("RT_MAX_SECS", "900"))      # cost guard: 15 min per session
RT_SILENCE_MS = E("RT_SILENCE_MS", "")             # optional server VAD end-of-turn silence (ms); default = server's
RT_PREBUF = 0.12                                    # seconds buffered before starting the Dot stream
RT_PERSONA = (
    "# Role\nYou are Charlie, Michael Gannotti's chief of staff for his fleet of Grok Bots. You talk with Michael "
    "out loud through an Echo Dot. You are a calm, warm, friendly man.\n"
    "# Style\nKeep every reply to one to three short spoken sentences. Plain conversational speech, no lists.\n"
    "# Rules\nNever name Michael's employer; if his job comes up, he is a Principal AI Solutions Engineer. "
    "Do not invent facts about Michael's calendar, email, notes, tasks or bots.\n"
    "# Tools\nWhen Michael asks for a real task (a status report, checking or changing his calendar or email, "
    "sending anything, notes or memory, his other bots, or private or current facts you cannot know), call "
    "handoff_to_charlie with his request and do not speak. Say nothing before the tool returns. Do not say you "
    "are checking with the team, passing that to the team, waiting on the team, or that you will let him know "
    "when you have it. Do not promise a follow-up. The bridge speaks Charlie's answer. If you speak at all before "
    "the tool result, the only allowed words are: One moment.")
RT_TOOLS = [{"type": "function", "name": "handoff_to_charlie",
             "description": "Pass Michael's request to the full Charlie and return Charlie's answer. Call this with no spoken words.",
             "parameters": {"type": "object", "properties": {"request": {"type": "string",
                            "description": "Michael's request, restated clearly."}}, "required": ["request"]}}]

def _dot_stream(t, payload=b""):
    return dot.send(t, payload)

class Realtime:
    """One xAI realtime WebSocket per voice session: mic in, streamed audio out to the Dot."""
    def __init__(self, sess, say_text=None):
        self.say_text = say_text; self.done = threading.Event(); self.last_out = 0; self.retried = False
        self.s = sess; self.ws = None; self.ok = False; self.dead = False; self.closing = False
        self.opened = None; self.t_stop = None; self.first_logged = False
        self.streaming = False; self.pend = b""; self.user_text = ""; self.bot_text = ""
        self.lock = threading.Lock(); self.calls = 0
        self.quiet_gen = self.accept_gen = 0   # drop model audio while these differ
        self.turn_handed = False                # this user turn already started a Charlie handoff
        self.delivering = False                 # verbatim Charlie answer is being spoken
        self.resp_epoch = 0                     # increments on each response.done
        self.partial = ""
        threading.Thread(target=self.run, daemon=True).start()

    # ----- Dot output -----
    def _out(self, b):
        if self.quiet_gen != self.accept_gen:
            return   # holding line: stay quiet until Charlie's answer is ready
        self.last_out = time.time()
        x = np.frombuffer(b, dtype="<i2")
        if RT_GAIN != 1.0:
            x = np.clip(x.astype(np.float32) * RT_GAIN, -32767, 32767).astype("<i2")
        b = x.tobytes()
        if not self.streaming:
            self.pend += b
            if len(self.pend) < int(RT_OUT_RATE * RT_PREBUF) * 2:
                return
            dot.playing = True; dot.playing_done.clear()
            _dot_stream("O", struct.pack(">I", RT_OUT_RATE)); self.streaming = True
            if self.t_stop and not self.first_logged:
                self.first_logged = True
                log(f"[{self.s.id}] realtime: end of speech -> first audio {time.time() - self.t_stop:.2f}s")
            b, self.pend = self.pend, b""
        for i in range(0, len(b), 9600):
            _dot_stream("C", b[i:i + 9600])

    def _finish(self, stop=False):
        if self.pend and not self.streaming and not stop:
            dot.playing = True; dot.playing_done.clear()
            _dot_stream("O", struct.pack(">I", RT_OUT_RATE)); self.streaming = True
            _dot_stream("C", self.pend)
        self.pend = b""
        if self.streaming:
            _dot_stream("X" if stop else "F"); self.streaming = False
            threading.Thread(target=self._await_done, daemon=True).start()

    def _await_done(self):
        dot.playing_done.wait(60); dot.playing = False
        self.s.last_audio = time.time()

    # ----- WebSocket -----
    def send(self, obj):
        try:
            with self.lock:
                self.ws.send(obj if isinstance(obj, bytes) else json.dumps(obj))
            return True
        except Exception as e:
            return False

    def audio(self, pcm):
        if self.ok:
            self.send(pcm.astype("<i2").tobytes())

    def run(self):
        for attempt in (1, 2):
            err = self._run_once()
            if err is None or self.closing:
                break
            if attempt == 1:
                log(f"[{self.s.id}] realtime connection problem ({err}); reconnecting once"); self.retried = True
                self._finish(stop=True); time.sleep(0.5); continue
            self.fail(err)
        self._finish(stop=self.closing and not self.say_text)
        if self.opened:
            log(f"[{self.s.id}] realtime closed after {time.time() - self.opened:.0f}s (billed ~{(time.time() - self.opened) / 60:.1f} min)")
        self.ok = False; self.done.set()

    def _run_once(self):
        try:
            from websockets.sync.client import connect
            key = E("XAI_VOICE_API_KEY", "").strip()
            if not key:
                raise RuntimeError("XAI_VOICE_API_KEY not set")
            t0 = time.time()
            self.ws = connect(f"{RT_URL}?model={RT_MODEL}", additional_headers={"Authorization": "Bearer " + key},
                              max_size=None, open_timeout=8)
            if self.say_text:     # speak-only session: no mic, no model turn, verbatim text in Rex's voice
                self.ws.send(json.dumps({"type": "session.update", "session": {"voice": RT_VOICE, "turn_detection": None,
                    "instructions": "You are Charlie.", "audio": {"output": {"format": {"type": "audio/pcm", "rate": RT_OUT_RATE}, "transport": "binary"}}}}))
                self.ws.send(json.dumps({"type": "conversation.item.create", "item": {"type": "force_message", "role": "assistant",
                    "interruptible": False, "content": [{"type": "output_text", "text": self.say_text}]}}))
                self.opened = self.opened or time.time(); self.ok = True
                log(f"[{self.s.id}] realtime speak-only session opened in {time.time() - t0:.2f}s")
                for m in self.ws:
                    if isinstance(m, bytes):
                        self._out(m); continue
                    e = json.loads(m)
                    if e.get("type") == "response.done":
                        self._finish(); dot.playing_done.wait(len(self.say_text) / 10 + 20); self.closing = True; break
                    if e.get("type") == "error":
                        log(f"[{self.s.id}] realtime error: {json.dumps(e)[:300]}")
                self.ws.close(); return None
            self.send({"type": "session.update", "session": {
                "voice": RT_VOICE, "instructions": RT_PERSONA, "reasoning": {"effort": "none"},
                "turn_detection": dict({"type": "server_vad"}, **({"silence_duration_ms": int(RT_SILENCE_MS)} if RT_SILENCE_MS else {})), "tools": RT_TOOLS,
                "audio": {"input": {"format": {"type": "audio/pcm", "rate": RATE}, "transport": "binary",
                                    "transcription": {"model": "grok-transcribe"}},
                          "output": {"format": {"type": "audio/pcm", "rate": RT_OUT_RATE}, "transport": "binary"}}}})
            first = self.opened is None
            self.opened = self.opened or time.time(); self.ok = True
            log(f"[{self.s.id}] realtime connected ({RT_MODEL}, voice {RT_VOICE}) in {time.time() - t0:.2f}s")
            if first:
                threading.Thread(target=self.guard, daemon=True).start()
            for m in self.ws:
                if isinstance(m, bytes):
                    self._out(m); continue
                self.event(json.loads(m))
            return None if self.closing else "server closed the connection"
        except Exception as e:
            self.ok = False
            return None if self.closing else f"{e}"

    def event(self, e):
        t = e.get("type", "")
        if t == "input_audio_buffer.speech_started":
            if self.streaming:
                log(f"[{self.s.id}] realtime: barge-in, stopping playback"); self._finish(stop=True)
        elif t == "input_audio_buffer.speech_stopped":
            self.t_stop = time.time() - 0.0; self.first_logged = False
            self.partial = ""
            if self.quiet_gen == self.accept_gen:   # a handoff in flight keeps the turn flag
                self.turn_handed = False; self.delivering = False
        elif t == "conversation.item.input_audio_transcription.completed":
            txt = (e.get("transcript") or "").strip()
            if txt and txt != self.user_text: log(f"[{self.s.id}] heard: {txt}")
            self.user_text = txt or self.user_text
        elif t == "response.output_audio_transcript.delta":
            self.partial += e.get("delta") or ""
            self._note_holding_audio(self.partial)
        elif t == "response.output_audio_transcript.done":
            self.bot_text = (e.get("transcript") or "").strip()
            self.partial = ""
            self._note_holding_audio(self.bot_text)
        elif t == "response.output_item.added" and (e.get("item") or {}).get("name") == "handoff_to_charlie":
            self._quiet_holding()
        elif t == "response.function_call_arguments.delta" and e.get("name") == "handoff_to_charlie":
            self._quiet_holding()
        elif t == "response.function_call_arguments.done" and e.get("name") == "handoff_to_charlie":
            self._start_handoff(e, wait_for_done=True)
        elif t == "response.done":
            holding = _looks_like_holding(self.bot_text) and not self.delivering
            if holding:
                self._note_holding_audio(self.bot_text)
            if self.quiet_gen != self.accept_gen and not self.delivering:
                self.pend = b""
                if self.streaming:
                    self._finish(stop=True)
            else:
                self._finish()
            self.resp_epoch += 1
            if holding and not self.turn_handed:
                log(f"[{self.s.id}] holding line with no tool call; handing off quietly")
                self._start_handoff(None, wait_for_done=False)
            elif self.quiet_gen != self.accept_gen and not self.turn_handed and not self.delivering:
                self.accept_gen = self.quiet_gen   # not a handoff; don't mute the rest of the session
            if self.bot_text or self.user_text:
                log(f"[{self.s.id}] charlie (realtime): {self.bot_text}")
                self.post_turn(self.user_text, self.bot_text)
            self.user_text = self.bot_text = ""
            self.partial = ""
        elif t == "error":
            log(f"[{self.s.id}] realtime error: {json.dumps(e)[:300]}")

    def post_turn(self, user, bot):
        self.s.turn += 1; turn = self.s.turn
        suppressed.add((self.s.id, turn))
        self.s.history.append((user, bot))
        body = {"session_id": self.s.id, "turn": turn, "text": user, "ended": False,
                "brain": "realtime", "fast_reply": bot, "handoff": False}
        threading.Thread(target=lambda: self._post(body), daemon=True).start()

    def _post(self, body):
        try: post_webhook(body)
        except Exception as ex: log("webhook (realtime copy) failed:", ex)

    def _quiet(self):
        """Drop model audio and cut anything already headed for the speaker."""
        self.quiet_gen += 1
        self.pend = b""
        if self.streaming:
            log(f"[{self.s.id}] handoff: cutting holding audio")
            self._finish(stop=True)
        return self.quiet_gen

    def _allow_audio(self, gen):
        if self.quiet_gen == gen:
            self.accept_gen = gen

    def _quiet_holding(self):
        """Stop a holding line as soon as the handoff tool shows up."""
        if self.delivering or self.turn_handed:
            return
        if self.quiet_gen == self.accept_gen:
            log(f"[{self.s.id}] holding line suppressed")
            self._quiet()

    def _note_holding_audio(self, text):
        if self.delivering or self.turn_handed:
            return
        if not _looks_like_holding(text):
            return
        self._quiet_holding()

    def _start_handoff(self, e, wait_for_done):
        if self.turn_handed:
            return
        self.turn_handed = True
        gen = self.quiet_gen if self.quiet_gen != self.accept_gen else self._quiet()
        epoch = self.resp_epoch
        threading.Thread(target=self.handoff, args=(e, gen, epoch, wait_for_done), daemon=True).start()

    def handoff(self, e, gen, epoch, wait_for_done):
        call_id = None
        req = self.user_text
        if e:
            call_id = e.get("call_id")
            try:
                req = json.loads(e.get("arguments") or "{}").get("request") or self.user_text
            except ValueError:
                req = self.user_text
        self.s.turn += 1; turn = self.s.turn
        log(f"[{self.s.id} #{turn}] realtime handoff to Charlie (quiet): {req}")
        body = {"session_id": self.s.id, "turn": turn, "text": req, "ended": False,
                "brain": "realtime", "fast_reply": "", "handoff": True}
        mark_handoff(self.s.id, turn)
        try:
            post_webhook(body)
        except Exception as ex:
            log("handoff webhook failed:", ex); reply = None
        else:
            reply = wait_reply(self.s.id, turn)
        reply = (reply or "").strip() or NO_UPDATE
        log(f"[{self.s.id} #{turn}] Charlie replied: {reply[:120]}")
        try:
            self._speak_verbatim(reply, gen, epoch, wait_for_done, call_id, turn)
        finally:
            if self.quiet_gen == gen and self.accept_gen != gen:
                self.accept_gen = gen
            self.delivering = False

    def _speak_verbatim(self, text, gen, epoch, wait_for_done, call_id, turn):
        """Speak Charlie's text as-is. Do not ask the model to rephrase it."""
        if wait_for_done:
            deadline = time.time() + 4
            while self.ok and self.resp_epoch == epoch and time.time() < deadline:
                time.sleep(0.05)
        if not self.ok:
            rt_say(text, f"handoff {self.s.id}#{turn}")
            return
        if call_id:
            self.send({"type": "conversation.item.create", "item": {"type": "function_call_output",
                       "call_id": call_id, "output": json.dumps({"charlie_reply": text})}})
        self.delivering = True
        self._allow_audio(gen)
        t_inj = time.time()
        self.send({"type": "conversation.item.create", "item": {"type": "force_message", "role": "assistant",
                   "interruptible": True, "content": [{"type": "output_text", "text": text}]}})
        while time.time() - t_inj < 6 and self.ok and self.last_out < t_inj:
            time.sleep(0.1)
        if not self.ok or self.last_out < t_inj:
            log(f"[{self.s.id} #{turn}] verbatim handoff produced no live audio; speak-only session")
            rt_say(text, f"handoff {self.s.id}#{turn}")
        else:
            log(f"[{self.s.id} #{turn}] handoff answer spoken verbatim")

    def guard(self):
        while self.ok and not self.closing:
            if time.time() - self.opened >= RT_MAX_SECS:
                log(f"[{self.s.id}] realtime cost guard: {RT_MAX_SECS/60:.0f} min reached, closing")
                self.s.rt_guarded = True
                self.send({"type": "conversation.item.create", "item": {"type": "force_message", "role": "assistant",
                           "interruptible": False, "content": [{"type": "output_text",
                           "text": "We've reached the fifteen minute limit for this conversation, so I'm closing it now. Mute and unmute me to start a fresh one."}]}})
                time.sleep(9); self.close(); return
            time.sleep(1)

    def fail(self, why):
        if self.dead: return
        self.dead = True; self.ok = False; self.s.rt_guarded = True
        log(f"[{self.s.id}] realtime unreachable after retry ({why}); voice line paused for this session")
        try: self.ws.close()
        except Exception: pass
        if not self.say_text:
            speak("Sorry, I can't reach my voice service right now. Please try again in a minute.", "realtime-down")

    def close(self):
        self.closing = True; self.ok = False
        try: self.ws.close()
        except Exception: pass

_say_lock = threading.Lock()
def rt_say(text, why=""):
    """Speak text verbatim in the realtime voice via a short speak-only session (a few seconds of billing)."""
    text = (text or "").strip()
    if not text:
        return False
    from types import SimpleNamespace
    with _say_lock:
        sess = SimpleNamespace(id=f"say {why}".strip(), turn=0, history=[], last_audio=0, rt_guarded=False)
        r = Realtime(sess, say_text=text)
        r.done.wait(len(text) / 8 + 40)
        if r.dead or not r.opened:
            log(f"[{sess.id}] realtime speak failed; Piper error notice only")
            speak("Sorry, I can't reach my voice service right now.", "realtime-down")
            return False
        return True

def say_reply(text, why=""):
    """Every spoken reply goes out in the realtime voice when BRAIN=realtime; Piper otherwise."""
    if BRAIN == "realtime" and not STUB:
        return rt_say(text, why)
    return speak(text, why)

# ---------------- Session / VAD ----------------
class Session:
    def __init__(self):
        self.id = time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6]
        self.turn = 0
        self.jobs = queue.Queue()
        self.floor = 30.0
        self.buf, self.pre = [], []
        self.speech = self.silence = 0
        self.in_utt = False
        self.history = []
        self.t_end = None
        self.stat_t, self.stat_n, self.stat_sq, self.stat_max = time.time(), 0, 0.0, 0
        self.started = self.last_audio = time.time()
        self.flat_secs = 0
        self.rt = None; self.rt_guarded = False
        if BRAIN == "realtime" and not STUB:
            self.rt = Realtime(self)
        threading.Thread(target=self.worker, daemon=True).start()

    FRAME = 480  # 30 ms

    def feed(self, pcm: np.ndarray):
        self.stat_n += len(pcm)
        self.stat_sq += float(np.sum(pcm.astype(np.float64) ** 2))
        self.stat_max = max(self.stat_max, int(np.max(np.abs(pcm.astype(np.int32)))) if len(pcm) else 0)
        self.last_audio = time.time()
        if time.time() - self.stat_t >= 1.0:
            rms = (self.stat_sq / self.stat_n) ** 0.5 if self.stat_n else 0.0
            thr = max(self.floor * 3.0, 45.0)
            log(f"[{self.id}] mic {self.stat_n/RATE:.2f}s rms={rms:.0f} peak={self.stat_max} floor={self.floor:.0f} thr={thr:.0f} utt={self.in_utt}")
            self.flat_secs = self.flat_secs + 1 if self.stat_max <= 2 else 0
            self.stat_t, self.stat_n, self.stat_sq, self.stat_max = time.time(), 0, 0.0, 0
        if self.rt_guarded:
            return                     # cost guard hit: stay silent until the next mute/unmute
        if self.rt and not self.rt.dead:
            self.rt.audio(pcm)         # realtime brain (audio before connect completes is dropped)
            return
        for i in range(0, len(pcm) - self.FRAME + 1, self.FRAME):
            f = pcm[i:i + self.FRAME]
            rms = float(np.sqrt(np.mean(f.astype(np.float32) ** 2)))
            loud = rms > max(self.floor * 3.0, 45.0)
            if not self.in_utt:
                self.floor = 0.95 * self.floor + 0.05 * rms if not loud else self.floor
                self.pre = (self.pre + [f])[-10:]
                self.speech = self.speech + 1 if loud else 0
                if self.speech >= 3:
                    self.in_utt, self.buf, self.silence = True, list(self.pre), 0
            else:
                self.buf.append(f)
                self.silence = 0 if loud else self.silence + 1
                if self.silence >= 27 or len(self.buf) > 667:  # ~0.8 s silence or 20 s max
                    self.flush()

    def flush(self):
        if self.in_utt and len(self.buf) > 15:
            self.t_end = time.time() - self.silence * 0.03   # when speech actually stopped
            self.jobs.put(("utt", np.concatenate(self.buf)))
        self.in_utt, self.buf, self.speech, self.silence = False, [], 0, 0

    def end(self):
        if self.rt: self.rt.close()
        self.flush()
        self.jobs.put(("end", None))

    def worker(self):
        while True:
            kind, audio = self.jobs.get()
            if kind == "end":
                try:
                    post_webhook({"session_id": self.id, "turn": self.turn + 1, "text": "", "ended": True})
                except Exception as e:
                    log("webhook end failed:", e)
                log(f"[{self.id}] ended"); warm.end(self.id)
                return
            t0 = time.time(); ts = TurnState()
            if BRAIN in ("fast", "local", "realtime") and not STUB:
                threading.Thread(target=filler_timer, args=(ts, self.t_end), daemon=True).start()
            text = transcribe(audio)
            if not text:
                ts.cancel.set()
                continue
            self.turn += 1
            log(f"[{self.id} #{self.turn}] heard ({time.time()-t0:.1f}s): {text}")
            body = {"session_id": self.id, "turn": self.turn, "text": text, "ended": False}
            if BRAIN in ("fast", "local", "realtime") and not STUB:
                reply, handoff = fast_reply(self.history, text, f"{self.id}#{self.turn}",
                                             t_end=self.t_end, key=self.id, ts=ts)
                if reply is not None:
                    self.history.append((text, "" if handoff else reply))
                    if not handoff:
                        suppressed.add((self.id, self.turn))
                    body.update({"brain": "fast", "fast_reply": "" if handoff else reply, "handoff": bool(handoff)})
                    threading.Thread(target=charlie_followup, args=(self.id, self.turn, body), daemon=True).start()
                    continue
            try:
                if STUB:
                    deliver_reply(self.id, self.turn, f"You said: {text}")
                else:
                    mark_handoff(self.id, self.turn)
                    post_webhook(body)
            except Exception as e:
                log("webhook failed:", e)
                deliver_reply(self.id, self.turn, "Sorry, I couldn't reach Charlie.")
            reply = wait_reply(self.id, self.turn)
            if reply is None:
                reply = NO_UPDATE
            log(f"[{self.id} #{self.turn}] reply: {reply}")
            self.history.append((text, reply))
            say_reply(reply, f"{self.id}#{self.turn}")

current = {"sess": None}
restart_req = threading.Event()

def kill_agent(why):
    log(f"restarting Dot agent: {why}")
    r = adb("shell", "su -c 'ps'")
    for line in (r.stdout.splitlines() if r else []):
        if "charlie-agent" in line or "DotAgent" in line:
            adb("shell", f"su -c 'kill {line.split()[1]}'")
    # supervisor relaunches it within ~10 s

def mic_watchdog():
    """Restart the agent (fresh AudioRecord) if a live session gets no audio or only flat/zero audio,
    or if mediaserver restarted under it."""
    last_media = None
    while True:
        time.sleep(1)
        try:
            s = current["sess"]
            if s and not dot.playing:
                now = time.time()
                if now - s.last_audio > 4 and now - s.started > 4:
                    s.last_audio = now; restart_req.set(); kill_agent("no mic audio for 4 s")
            if int(time.time()) % 10 == 0:
                r = adb("shell", "su -c 'ps'", timeout=8)
                pid = next((l.split()[1] for l in (r.stdout.splitlines() if r else []) if l.endswith("/system/bin/mediaserver")), None)
                if pid and last_media and pid != last_media and not current["sess"]:
                    kill_agent(f"mediaserver restarted ({last_media} -> {pid})")
                if pid:
                    last_media = pid
        except Exception as e:
            log("mic watchdog:", e)

def serve_dot():
    log(f"connecting to Dot agent via adb forward 127.0.0.1:{LOCAL_PORT}; stub={STUB}")
    while True:
        try:
            conn = socket.create_connection(("127.0.0.1", LOCAL_PORT), timeout=5)
            conn.settimeout(None)
        except OSError:
            time.sleep(2); continue
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        with dot.lock:
            dot.sock = conn
        log("Dot connected")
        sess = current["sess"] if restart_req.is_set() else None
        live_since = zero_run = 0
        f = conn.makefile("rb")
        try:
            while True:
                hdr = f.read(5)
                if len(hdr) < 5:
                    break
                t, n = chr(hdr[0]), struct.unpack(">I", hdr[1:])[0]
                p = f.read(n) if n else b""
                # The Dot's mute button is a HARDWARE mic cut: muted mics deliver exact digital zeros.
                # The agent is kept recording at all times (R 1) and the session follows the real mic
                # state: mic live (non-zero samples) = conversation on, exact zeros = conversation off.
                if t == "H":
                    log("hello", p.decode(errors="replace"))
                    dot.send("R", b"\x01")
                    if restart_req.is_set() and sess:
                        log(f"[{sess.id}] resumed after agent restart")
                    elif sess:
                        sess.end(); sess = None; current["sess"] = None
                    restart_req.clear(); live_since = zero_run = 0
                elif t in ("S", "E"):
                    log("mute button pressed")
                    dot.send("R", b"\x01")   # keep recording regardless of the agent's own toggle
                elif t == "A":
                    pcm = np.frombuffer(p, dtype="<i2")
                    if len(pcm) and not pcm.any():
                        zero_run += len(pcm); live_since = 0
                        if sess and zero_run >= RATE // 2:          # 0.5 s of exact zeros: mics muted
                            log(f"[{sess.id}] mic muted (hardware)")
                            sess.end(); sess = None; current["sess"] = None
                            threading.Thread(target=dot.beep, args=(440,), daemon=True).start()
                    else:
                        zero_run = 0; live_since += len(pcm)
                        if not sess and live_since >= RATE // 5:    # 0.2 s of real signal: mics live
                            sess = Session(); current["sess"] = sess
                            log(f"[{sess.id}] started (mic unmuted)")
                            threading.Thread(target=dot.beep, args=(880,), daemon=True).start()
                        if sess:
                            sess.feed(pcm)
                elif t == "D":
                    dot.playing_done.set()
        except OSError as e:
            log("Dot conn error:", e)
        conn.close()
        with dot.lock:
            dot.sock = None
        if sess and not restart_req.is_set():
            sess.end(); sess = None; current["sess"] = None
        log("Dot disconnected")
        time.sleep(2)

def main():
    INBOX.mkdir(parents=True, exist_ok=True)
    threading.Thread(target=supervisor, daemon=True).start()
    http = ThreadingHTTPServer((REPLY_BIND, REPLY_PORT), ReplyHandler)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    threading.Thread(target=inbox_watcher, daemon=True).start()
    threading.Thread(target=mic_watchdog, daemon=True).start()
    threading.Thread(target=load_fillers, daemon=True).start()
    if BRAIN in ("fast", "realtime") and GROK_WARM:
        threading.Thread(target=warm.keeper, daemon=True).start()
    log(f"reply endpoint http://{REPLY_BIND}:{REPLY_PORT}/reply ; inbox {INBOX}")
    serve_dot()

if __name__ == "__main__":
    main()
