#!/usr/bin/env bash
# Optional one-shot: plug the tablet into the host once (tap "Always allow"),
# then run this. It enables adb on port 5555 and starts the user services.
# Set TABLET_SERIAL first. Rooting and privileged install steps are out of scope.
set -u
ADB="${ADB:-adb}"
S="${TABLET_SERIAL:?set TABLET_SERIAL to the tablet adb serial}"
"$ADB" -s "$S" get-state || { echo "tablet not on USB / not authorized"; exit 1; }
[ "$("$ADB" -s "$S" shell getprop service.adb.tcp.port | tr -d "\r")" = 5555 ] || { "$ADB" -s "$S" tcpip 5555; sleep 5; }
systemctl --user enable --now tablet-voice.service tablet-voice-adb.service
sleep 3; journalctl --user -u tablet-voice-adb -n 3 --no-pager -o cat
