#!/usr/bin/env python3
"""Serve spoken Spot replies to the Echo Spot.

Charlie's path is unchanged: watches ~/charlie-voice/inbox for
<session>-<turn>.txt or .done written by the Echo Dot voice routine,
serves GET /reply/<session>/<turn>, and publishes to ntfy unless
~/charlie-voice/spot-reply-lan-only exists.

Other bots: watches ~/spot-voice/<bot>/inbox and ~/spot-voice/<bot>/replies
for the same filename, and serves GET /<bot>/reply/<session>/<turn>.
Charlie's URLs and inbox are not used for those bots.

Does not start charlie-voice or Hermes, and does not read config.env.
"""

import hashlib
import os
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

CHARLIE_HOME = Path.home() / "charlie-voice"
CHARLIE_INBOX = CHARLIE_HOME / "inbox"
CHARLIE_MARKS = CHARLIE_HOME / "spot-posted"
LAN_ONLY = CHARLIE_HOME / "spot-reply-lan-only"
SPOT_HOME = Path.home() / "spot-voice"
BOTS = ("grace", "mary", "wilson", "patrick", "peyton", "brett", "derek")
# Bind on all interfaces by default so the Spot can reach this host over the LAN.
# Set SPOT_REPLY_BIND to a specific address (the value you put in the app's files/reply-host) if you prefer.
BIND = os.environ.get("SPOT_REPLY_BIND", "0.0.0.0")
PORT = int(os.environ.get("SPOT_REPLY_PORT", "8794"))
# Public ntfy.sh base URL. Per-turn topics are sha256(session-turn) and are never logged.
NTFY = os.environ.get("SPOT_NTFY_URL", "https://ntfy.sh/")


def parse_name(name):
    suffix = None
    for candidate in (".txt", ".done"):
        if name.endswith(candidate):
            suffix = candidate
            break
    if suffix is None or "-" not in name:
        return None
    stem = name[: -len(suffix)]
    session, turn = stem.rsplit("-", 1)
    if len(session) != 32 or not turn.isdigit():
        return None
    if any(c not in "0123456789abcdef" for c in session):
        return None
    return session, turn


def topic_for(session, turn):
    return hashlib.sha256(f"{session}-{turn}".encode("utf-8")).hexdigest()


def bot_dirs(bot):
    root = SPOT_HOME / bot
    return [root / "inbox", root / "replies"]


def bot_marks(bot):
    return SPOT_HOME / bot / "posted"


def inboxes_for(bot):
    if bot == "charlie":
        return [CHARLIE_INBOX]
    if bot in BOTS:
        return bot_dirs(bot)
    return []


def read_reply(bot, session, turn):
    for folder in inboxes_for(bot):
        for suffix in (".txt", ".done"):
            path = folder / f"{session}-{turn}{suffix}"
            try:
                if not path.is_file():
                    continue
                text = path.read_text(encoding="utf-8", errors="replace").strip()
            except OSError:
                continue
            if text:
                return text[:500]
    return None


def post_ntfy(bot, session, turn, text):
    if LAN_ONLY.exists():
        return
    topic = topic_for(session, turn)
    title = "Charlie" if bot == "charlie" else bot.capitalize()
    request = urllib.request.Request(
        NTFY + topic,
        data=text.encode("utf-8"),
        method="POST",
        headers={
            "Title": title,
            "Content-Type": "text/plain; charset=utf-8",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        if response.status < 200 or response.status >= 300:
            raise OSError("ntfy status " + str(response.status))


def publish(bot, session, turn, text):
    marks = CHARLIE_MARKS if bot == "charlie" else bot_marks(bot)
    marks.mkdir(mode=0o700, parents=True, exist_ok=True)
    mark = marks / f"{session}-{turn}.sent"
    if mark.exists():
        return
    try:
        post_ntfy(bot, session, turn, text)
    except Exception:
        print("ntfy failed", bot, "chars", len(text), flush=True)
        return
    mark.write_text("ok\n", encoding="utf-8")
    os.chmod(mark, 0o600)
    print("publish", bot, len(text), flush=True)


def watch():
    while True:
        jobs = [("charlie", CHARLIE_INBOX)]
        for bot in BOTS:
            for folder in bot_dirs(bot):
                jobs.append((bot, folder))
        for bot, folder in jobs:
            try:
                names = os.listdir(folder)
            except FileNotFoundError:
                continue
            for name in names:
                parsed = parse_name(name)
                if parsed is None:
                    continue
                session, turn = parsed
                text = read_reply(bot, session, turn)
                if text:
                    publish(bot, session, turn, text)
        time.sleep(0.5)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self._send(200, b"ok")
            return
        parts = [part for part in path.split("/") if part]
        # Charlie (unchanged): /reply/<session>/<turn>
        if len(parts) == 3 and parts[0] == "reply":
            parsed = parse_name(parts[1] + "-" + parts[2] + ".txt")
            if parsed is not None:
                text = read_reply("charlie", parsed[0], parsed[1])
                if text is not None:
                    self._send(200, text.encode("utf-8"))
                    return
        # Other bots: /<bot>/reply/<session>/<turn>
        if len(parts) == 4 and parts[1] == "reply" and parts[0] in BOTS:
            parsed = parse_name(parts[2] + "-" + parts[3] + ".txt")
            if parsed is not None:
                text = read_reply(parts[0], parsed[0], parsed[1])
                if text is not None:
                    self._send(200, text.encode("utf-8"))
                    return
        self._send(404, b"")

    def _send(self, code, body):
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        return


def main():
    CHARLIE_INBOX.mkdir(parents=True, exist_ok=True)
    for bot in BOTS:
        for folder in bot_dirs(bot):
            folder.mkdir(parents=True, exist_ok=True)
    threading.Thread(target=watch, daemon=True).start()
    server = ThreadingHTTPServer((BIND, PORT), Handler)
    print("listen", BIND, PORT, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
