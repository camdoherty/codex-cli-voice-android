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

if command -v termux-open >/dev/null 2>&1; then
    termux-open "$DEST"
else
    echo "Open $DEST from Android Downloads to install the shim APK."
fi
