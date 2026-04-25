#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
if [ -f "$REPO_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$REPO_DIR/.env"
    set +a
fi

APK="${1:-$REPO_DIR/android-aec-shim/app/build/outputs/apk/debug/app-debug.apk}"
REMOTE_NAME="${REMOTE_NAME:-codex-aec-shim-debug.apk}"
FTP_HOST="${FTP_HOST:-}"
FTP_USER="${FTP_USER:-}"
FTP_PASSWORD="${FTP_PASSWORD:-}"
FTP_PATH="${FTP_PATH:-dev/$REMOTE_NAME}"

[ -f "$APK" ] || { echo "APK not found: $APK"; exit 1; }
[ -n "$FTP_HOST" ] || { echo "FTP_HOST is required. Copy .env.example to .env or export it." >&2; exit 2; }
[ -n "$FTP_USER" ] || { echo "FTP_USER is required. Copy .env.example to .env or export it." >&2; exit 2; }

FTP_URL="${FTP_URL:-ftp://$FTP_HOST/$FTP_PATH}"

if [ -n "$FTP_PASSWORD" ]; then
    curl --fail --ftp-create-dirs --user "$FTP_USER:$FTP_PASSWORD" -T "$APK" "$FTP_URL"
else
    curl --fail --ftp-create-dirs --user "$FTP_USER" -T "$APK" "$FTP_URL"
fi
echo "Uploaded $APK to $FTP_URL"
