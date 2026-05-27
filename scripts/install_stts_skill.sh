#!/data/data/com.termux/files/usr/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." 2>/dev/null && pwd || true)
PREFIX_DIR=${PREFIX:-/data/data/com.termux/files/usr}

if [ -n "${CODEX_STTS_SKILL_SRC:-}" ]; then
    SRC=$CODEX_STTS_SKILL_SRC
elif [ -n "$REPO_DIR" ] && [ -d "$REPO_DIR/support/termux-skills/stts" ]; then
    SRC=$REPO_DIR/support/termux-skills/stts
else
    SRC=$PREFIX_DIR/libexec/codex-cli-voice-android/support/termux-skills/stts
fi

[ -d "$SRC" ] || {
    echo "Missing stts skill source: $SRC" >&2
    exit 1
}

DEST=${CODEX_STTS_SKILL_DEST:-$HOME/.codex/skills/stts}
DEST_PARENT=$(dirname "$DEST")
mkdir -p "$DEST_PARENT"

if [ -e "$DEST" ]; then
    BACKUP=$HOME/stts-skill-backup-$(date +%Y%m%d-%H%M%S).tar.gz
    tar -czf "$BACKUP" -C "$DEST_PARENT" "$(basename "$DEST")"
    tar -tzf "$BACKUP" >/dev/null
    rm -rf "$DEST"
    echo "backup=$BACKUP"
fi

mkdir -p "$DEST"
(cd "$SRC" && tar -cf - .) | (cd "$DEST" && tar -xf -)
find "$DEST/scripts" -type f -exec chmod 700 {} \;

SCRIPTS_DIR="$HOME/scripts"
BIN_DIR="$PREFIX_DIR/bin"
mkdir -p "$SCRIPTS_DIR" "$BIN_DIR"

write_launcher() {
    path=$1
    target=$2
    cat > "$path" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$target" "\$@"
EOF
    chmod 700 "$path"
}

for name in stts stts-stop stts-status stts-diag stts-talk stts-loop wake-voice-start wake-voice-stop wake-voice-status wake-voice-doctor; do
    case "$name" in
        stts)
            target="$DEST/scripts/stts-session.sh"
            ;;
        *)
            target="$DEST/scripts/$name"
            ;;
    esac
    if [ -e "$target" ]; then
        rm -f "$SCRIPTS_DIR/$name" "$BIN_DIR/$name"
        write_launcher "$SCRIPTS_DIR/$name" "$target"
        write_launcher "$BIN_DIR/$name" "$target"
    fi
done

OLD_SLUG="tts""-stt"
OLD_DEST="$HOME/.codex/skills/$OLD_SLUG"
if [ -e "$OLD_DEST" ]; then
    rm -rf "$OLD_DEST"
    echo "removed_old=$OLD_DEST"
fi
rm -f \
    "$SCRIPTS_DIR/codex-install-tts-stt" \
    "$BIN_DIR/codex-install-tts-stt" \
    "$SCRIPTS_DIR/${OLD_SLUG}-start" \
    "$SCRIPTS_DIR/${OLD_SLUG}-stop" \
    "$SCRIPTS_DIR/${OLD_SLUG}-status" \
    "$SCRIPTS_DIR/${OLD_SLUG}-diag" \
    "$SCRIPTS_DIR/${OLD_SLUG}-talk" \
    "$SCRIPTS_DIR/stts-start" \
    "$BIN_DIR/stts-start" \
    "$BIN_DIR/${OLD_SLUG}-start" \
    "$BIN_DIR/${OLD_SLUG}-stop" \
    "$BIN_DIR/${OLD_SLUG}-status" \
    "$BIN_DIR/${OLD_SLUG}-diag" \
    "$BIN_DIR/${OLD_SLUG}-talk"

echo "installed=$DEST"
