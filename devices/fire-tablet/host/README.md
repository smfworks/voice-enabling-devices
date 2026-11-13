# Fire HD 8 voice host (Paula and Liam)

Host side of the [Fire Tablet page](../README.md).

`bridge.py` is a small HTTP server that the SpotEyes-style Fire tablet app
calls. Hold-to-talk on the tablet posts a 16 kHz WAV to `POST /talk?who=...`.
The host transcribes with faster-whisper (`base.en` by default), answers
with `grok-4-1-fast-non-reasoning` using that agent's Hermes persona files,
and returns an xAI TTS recording (Paula: ara, Liam: rex) as base64 in the
JSON. `GET /health` reports readiness.

Place this directory at `~/tablet-voice` (or point the systemd unit at
wherever you put it). Create a virtualenv with `faster-whisper` and the
other imports `bridge.py` uses. Put `XAI_API_KEY` in `~/.hermes/.env`.
The bridge reads that key in place and never logs it.

Environment:

- `TABLET_VOICE_HOST` (default `127.0.0.1`) and `TABLET_VOICE_PORT` (default `8766`)
- `TABLET_VOICE_LAN` — optional extra bind address, the value you also write
  to the app's `files/bridge-host`
- `TABLET_VOICE_ALLOW` — comma-separated client IPs (default `127.0.0.1,::1`).
  Add the tablet's LAN address so Wi-Fi clients are accepted.
- `TABLET_VOICE_KEY_FILE` — override the path to the env file
- `PAULA_TTS_VOICE` / `LIAM_TTS_VOICE` — override ara / rex
- `TABLET_VOICE_MODEL` / `TABLET_WHISPER_MODEL`

`selftest.py` runs a short text and TTS check and writes `samples/paula.wav`
and `samples/liam.wav`. `keep-adb.sh` and `connect.sh` are optional USB/Wi-Fi
adb helpers; they require `TABLET_SERIAL` and `TABLET_WIFI` and do not
include device serials or addresses. Rooting is out of scope.
