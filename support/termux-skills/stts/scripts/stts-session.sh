#!/data/data/com.termux/files/usr/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PYTHON_LOOP="$SCRIPT_DIR/stts_loop.py"

if [ ! -f "$PYTHON_LOOP" ]; then
    printf '%s\n' "stts-session: missing controller script: $PYTHON_LOOP" >&2
    exit 1
fi

exec /data/data/com.termux/files/usr/bin/python "$PYTHON_LOOP" "$@"
