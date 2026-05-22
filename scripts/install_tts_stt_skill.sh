#!/data/data/com.termux/files/usr/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." 2>/dev/null && pwd || true)
PREFIX_DIR=${PREFIX:-/data/data/com.termux/files/usr}

if [ -n "${CODEX_TTS_STT_SKILL_SRC:-}" ]; then
    SRC=$CODEX_TTS_STT_SKILL_SRC
elif [ -n "$REPO_DIR" ] && [ -d "$REPO_DIR/support/termux-skills/tts-stt" ]; then
    SRC=$REPO_DIR/support/termux-skills/tts-stt
else
    SRC=$PREFIX_DIR/libexec/codex-cli-voice-android/support/termux-skills/tts-stt
fi

[ -d "$SRC" ] || {
    echo "Missing tts-stt skill source: $SRC" >&2
    exit 1
}

DEST=${CODEX_TTS_STT_SKILL_DEST:-$HOME/.codex/skills/tts-stt}
DEST_PARENT=$(dirname "$DEST")
mkdir -p "$DEST_PARENT"

if [ -e "$DEST" ]; then
    BACKUP=$HOME/tts-stt-skill-backup-$(date +%Y%m%d-%H%M%S).tar.gz
    tar -czf "$BACKUP" -C "$DEST_PARENT" "$(basename "$DEST")"
    tar -tzf "$BACKUP" >/dev/null
    rm -rf "$DEST"
    echo "backup=$BACKUP"
fi

mkdir -p "$DEST"
(cd "$SRC" && tar -cf - .) | (cd "$DEST" && tar -xf -)
chmod 700 "$DEST/scripts/tts-stt-session.sh" "$DEST/scripts/tts_stt_loop.py"

echo "installed=$DEST"
