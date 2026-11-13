#!/bin/bash
# Prepare a per-bot webhook URL and key for SpotEyes.
# Usage: install-hook.sh <bot>
#   bot is one of: grace mary wilson patrick peyton brett derek
#   Charlie uses files/charlie-url and files/charlie-key in the app's
#   private storage; this script will not change those.
#
# Reads the URL and key, in this order, from:
#   1. HOOK_URL_FILE and HOOK_KEY_FILE (paths to files you will drop)
#   2. SPOT_HOOK_URL and SPOT_HOOK_KEY (environment)
#   3. ~/spot-voice/<bot>/incoming-url and incoming-key
#
# Validates them (length 8-2000, no newlines) and writes:
#   ~/spot-voice/<bot>/hooks/<bot>-url
#   ~/spot-voice/<bot>/hooks/<bot>-key
# mode 600. Then deletes the local incoming copies.
# Never prints the URL, key, or any secret value. Logs lengths only.
#
# Copy those two files into the SpotEyes app's private files/hooks/
# directory on the device as <bot>-url and <bot>-key, mode 0600,
# readable only by the app. The device must already be rooted;
# rooting and privileged copy steps are out of scope.
set -euo pipefail
set +x

BOT="${1:-}"
OK_BOTS="grace mary wilson patrick peyton brett derek"
ok=0
for b in $OK_BOTS; do
  if [ "$b" = "$BOT" ]; then
    ok=1
    break
  fi
done
if [ "$ok" -ne 1 ]; then
  echo "usage: install-hook.sh <bot>" >&2
  echo "  bot is one of: $OK_BOTS" >&2
  echo "  charlie hooks live at files/charlie-url and files/charlie-key; do not use this script for charlie." >&2
  exit 2
fi

HOME_DIR="${HOME:?}"
BOT_DIR="$HOME_DIR/spot-voice/$BOT"
INCOMING_URL="$BOT_DIR/incoming-url"
INCOMING_KEY="$BOT_DIR/incoming-key"
HOOK_DIR="$BOT_DIR/hooks"
mkdir -p "$BOT_DIR"
chmod 700 "$BOT_DIR" 2>/dev/null || true

url_src=""
key_src=""
url_is_temp=0
key_is_temp=0
stage_dir=""

cleanup() {
  if [ "$url_is_temp" -eq 1 ] && [ -n "${url_src:-}" ]; then
    rm -f -- "$url_src"
  fi
  if [ "$key_is_temp" -eq 1 ] && [ -n "${key_src:-}" ]; then
    rm -f -- "$key_src"
  fi
  if [ -n "${stage_dir:-}" ]; then
    rm -rf -- "$stage_dir"
  fi
}
trap cleanup EXIT

if [ -n "${HOOK_URL_FILE:-}" ] && [ -f "$HOOK_URL_FILE" ]; then
  url_src="$HOOK_URL_FILE"
elif [ -n "${SPOT_HOOK_URL:-}" ]; then
  umask 077
  url_src="$(mktemp "$BOT_DIR/.url.XXXXXX")"
  url_is_temp=1
  printf "%s" "$SPOT_HOOK_URL" > "$url_src"
  chmod 600 "$url_src"
elif [ -f "$INCOMING_URL" ]; then
  url_src="$INCOMING_URL"
else
  echo "no URL: set HOOK_URL_FILE, or SPOT_HOOK_URL, or $INCOMING_URL" >&2
  exit 2
fi

if [ -n "${HOOK_KEY_FILE:-}" ] && [ -f "$HOOK_KEY_FILE" ]; then
  key_src="$HOOK_KEY_FILE"
elif [ -n "${SPOT_HOOK_KEY:-}" ]; then
  umask 077
  key_src="$(mktemp "$BOT_DIR/.key.XXXXXX")"
  key_is_temp=1
  printf "%s" "$SPOT_HOOK_KEY" > "$key_src"
  chmod 600 "$key_src"
elif [ -f "$INCOMING_KEY" ]; then
  key_src="$INCOMING_KEY"
else
  echo "no key: set HOOK_KEY_FILE, or SPOT_HOOK_KEY, or $INCOMING_KEY" >&2
  exit 2
fi

stage_dir=$(mktemp -d "$BOT_DIR/.stage.XXXXXX")
chmod 700 "$stage_dir"

python3 - "$url_src" "$key_src" "$stage_dir" << "END_VALIDATE"
import sys
from pathlib import Path
url, key, dest = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
u = url.read_bytes().decode("utf-8").replace("\ufeff", "").strip()
k = key.read_bytes().decode("utf-8").replace("\ufeff", "").strip()
if "\n" in u or "\r" in u:
    sys.stderr.write("rejected url: contains a newline\n")
    sys.exit(2)
if "\n" in k or "\r" in k:
    sys.stderr.write("rejected key: contains a newline\n")
    sys.exit(2)
if not (8 <= len(u) <= 2000):
    sys.stderr.write("rejected url: length %d (need 8 to 2000)\n" % len(u))
    sys.exit(2)
if not (8 <= len(k) <= 2000):
    sys.stderr.write("rejected key: length %d (need 8 to 2000)\n" % len(k))
    sys.exit(2)
(dest / "url").write_text(u, encoding="utf-8")
(dest / "key").write_text(k, encoding="utf-8")
(dest / "url").chmod(0o600)
(dest / "key").chmod(0o600)
(dest / "lens").write_text("%d %d\n" % (len(u), len(k)), encoding="utf-8")
END_VALIDATE

url_len=$(awk '{print $1}' "$stage_dir/lens")
key_len=$(awk '{print $2}' "$stage_dir/lens")

mkdir -p "$HOOK_DIR"
chmod 700 "$HOOK_DIR"
cp "$stage_dir/url" "$HOOK_DIR/${BOT}-url"
cp "$stage_dir/key" "$HOOK_DIR/${BOT}-key"
chmod 600 "$HOOK_DIR/${BOT}-url" "$HOOK_DIR/${BOT}-key"
rm -rf -- "$stage_dir"
stage_dir=""

rm -f -- "$INCOMING_URL" "$INCOMING_KEY"
if [ "$url_is_temp" -eq 1 ]; then
  rm -f -- "$url_src"
  url_src=""
fi
if [ "$key_is_temp" -eq 1 ]; then
  rm -f -- "$key_src"
  key_src=""
fi
if [ -n "${HOOK_URL_FILE:-}" ] && [ -f "${HOOK_URL_FILE}" ]; then
  rm -f -- "$HOOK_URL_FILE"
fi
if [ -n "${HOOK_KEY_FILE:-}" ] && [ -f "${HOOK_KEY_FILE}" ]; then
  rm -f -- "$HOOK_KEY_FILE"
fi

echo "prepared $BOT hook files at $HOOK_DIR (url len=$url_len, key len=$key_len)"
echo "copy ${BOT}-url and ${BOT}-key into the app's private files/hooks/ directory, mode 0600"
