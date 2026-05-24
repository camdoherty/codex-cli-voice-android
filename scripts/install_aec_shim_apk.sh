#!/data/data/com.termux/files/usr/bin/sh
set -eu

APK=${1:-codex-aec-shim-debug.apk}
if [ ! -f "$APK" ]; then
    echo "APK not found: $APK" >&2
    echo "Download the release APK first, or pass its path." >&2
    exit 1
fi

if [ ! -d "$HOME/storage/downloads" ]; then
    echo "Shared storage is not set up. Run: termux-setup-storage" >&2
    exit 1
fi

DEST="$HOME/storage/downloads/codex-aec-shim-debug.apk"
cp "$APK" "$DEST"
echo "staged=$DEST"
echo "Install this APK from Android Downloads if the package installer does not open."

if command -v termux-open >/dev/null 2>&1; then
    termux-open "$DEST"
else
    echo "Open $DEST from Android Downloads to install the shim APK."
fi

cat <<'EOF'
After installing, open the shim app from Android and grant microphone permission.
Then verify the service from Termux:

python3 - <<'PY'
import socket
s = socket.socket()
s.settimeout(2)
try:
    s.connect(("127.0.0.1", 8765))
    print("port-open")
finally:
    s.close()
PY
EOF
