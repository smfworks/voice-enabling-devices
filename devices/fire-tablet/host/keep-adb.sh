#!/usr/bin/env bash
# Keep a Fire HD 8 reachable from the host for optional USB adb reverse.
# - Tablet on USB: reverse tcp:8766 over USB, and make sure adbd also
#   listens on 5555 for later Wi-Fi adb.
# - Tablet off USB: the host may connect out to <TABLET_IP>:5555 for adb
#   only. The app talks to the bridge directly at <HOST_IP>:8766; Wi-Fi
#   mode does not use adb reverse.
#
# Set TABLET_SERIAL to the tablet's adb serial and TABLET_WIFI to
# <TABLET_IP>:5555. There are no baked-in addresses.
# Never adb kill-server (other device bridges may share this adb server).
# Always pass -s. Do not run `adb reverse --list`.
set -u
ADB="${ADB:-adb}"
USB="${TABLET_SERIAL:?set TABLET_SERIAL to the tablet adb serial}"
WIFI="${TABLET_WIFI:?set TABLET_WIFI to <TABLET_IP>:5555}"
PORT="${TABLET_VOICE_PORT:-8766}"
state() { "$ADB" -s "$1" get-state 2>/dev/null; }
last=""
say() { [ "$1" != "$last" ] && echo "$1"; last="$1"; }
while true; do
  if [ "$(state "$USB")" = device ]; then
    [ "$(state "$WIFI")" = device ] && "$ADB" disconnect "$WIFI" >/dev/null 2>&1
    if [ "$("$ADB" -s "$USB" shell getprop service.adb.tcp.port 2>/dev/null | tr -d "\r")" != 5555 ]; then
      "$ADB" -s "$USB" tcpip 5555 >/dev/null 2>&1; sleep 5
    fi
    "$ADB" -s "$USB" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1 && say "tunnel up over USB" || say "USB reverse failed"
  else
    [ "$(state "$WIFI")" = device ] || { "$ADB" connect "$WIFI" >/dev/null 2>&1; sleep 2; }
    if [ "$(state "$WIFI")" = device ]; then
      say "adb over Wi-Fi ($WIFI); app uses the LAN bridge (no reverse)"
    else
      say "tablet not reachable (USB absent, $WIFI not connecting; after a tablet reboot plug USB once)"
    fi
  fi
  sleep 10
done
