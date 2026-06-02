#!/data/data/com.termux/files/usr/bin/sh
set -eu

usage() {
    cat <<'EOF'
Usage: codex-install-stts [--apply|--dry-run] [--force]

Installs the packaged stts skill with no-clobber semantics.

Defaults:
  --apply

Behavior:
  - Missing skill and launcher files are installed.
  - Identical targets are skipped.
  - Differing targets are backed up and incoming content is written beside the
    target unless --force is supplied.
  - Old tts-stt paths are reported but not removed.
EOF
}

MODE=apply
FORCE=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --apply)
            MODE=apply
            ;;
        --dry-run)
            MODE=dry-run
            ;;
        --force)
            FORCE=1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." 2>/dev/null && pwd || true)
PREFIX_DIR=${PREFIX:-/data/data/com.termux/files/usr}
TS=$(date +%Y%m%d-%H%M%S)

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

copy_skill_dir() {
    if [ ! -e "$DEST" ]; then
        if [ "$MODE" = dry-run ]; then
            echo "would_install=$DEST"
            return
        fi
        mkdir -p "$DEST_PARENT" "$DEST"
        (cd "$SRC" && tar -cf - .) | (cd "$DEST" && tar -xf -)
        find "$DEST/scripts" -type f -exec chmod 700 {} \;
        echo "installed=$DEST"
        return
    fi

    if diff -qr "$SRC" "$DEST" >/dev/null 2>&1; then
        echo "skip_identical=$DEST"
        return
    fi

    if [ "$MODE" = dry-run ]; then
        echo "would_conflict=$DEST"
        return
    fi

    BACKUP=$HOME/stts-skill-backup-$TS.tar.gz
    tar -czf "$BACKUP" -C "$DEST_PARENT" "$(basename "$DEST")"
    tar -tzf "$BACKUP" >/dev/null
    echo "backup=$BACKUP"

    if [ "$FORCE" = 1 ]; then
        rm -rf "$DEST"
        mkdir -p "$DEST"
        (cd "$SRC" && tar -cf - .) | (cd "$DEST" && tar -xf -)
        find "$DEST/scripts" -type f -exec chmod 700 {} \;
        echo "replaced=$DEST"
        return
    fi

    INCOMING=$DEST.incoming.$TS
    mkdir -p "$INCOMING"
    (cd "$SRC" && tar -cf - .) | (cd "$INCOMING" && tar -xf -)
    find "$INCOMING/scripts" -type f -exec chmod 700 {} \;
    echo "conflict_preserved=$DEST"
    echo "incoming=$INCOMING"
}

write_launcher() {
    path=$1
    target=$2
    tmp=${TMPDIR:-/tmp}/stts-launcher.$$.tmp
    cat > "$tmp" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$target" "\$@"
EOF
    if [ ! -e "$path" ]; then
        if [ "$MODE" = dry-run ]; then
            echo "would_install=$path"
            rm -f "$tmp"
            return
        fi
        cp "$tmp" "$path"
        chmod 700 "$path"
        echo "installed=$path"
        rm -f "$tmp"
        return
    fi
    if cmp -s "$tmp" "$path"; then
        echo "skip_identical=$path"
        rm -f "$tmp"
        return
    fi
    if [ "$MODE" = dry-run ]; then
        echo "would_conflict=$path"
        rm -f "$tmp"
        return
    fi
    backup=$path.backup.$TS
    cp "$path" "$backup"
    if [ "$FORCE" = 1 ]; then
        cp "$tmp" "$path"
        chmod 700 "$path"
        echo "replaced=$path"
    else
        incoming=$path.incoming.$TS
        cp "$tmp" "$incoming"
        chmod 700 "$incoming"
        echo "conflict_preserved=$path"
        echo "backup=$backup"
        echo "incoming=$incoming"
    fi
    rm -f "$tmp"
}

copy_skill_dir

SCRIPTS_DIR="$HOME/scripts"
BIN_DIR="$PREFIX_DIR/bin"
[ "$MODE" = dry-run ] || mkdir -p "$SCRIPTS_DIR" "$BIN_DIR"

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
        write_launcher "$SCRIPTS_DIR/$name" "$target"
        write_launcher "$BIN_DIR/$name" "$target"
    fi
done

OLD_SLUG="tts""-stt"
OLD_DEST="$HOME/.codex/skills/$OLD_SLUG"
if [ -e "$OLD_DEST" ]; then
    echo "old_skill_present=$OLD_DEST"
fi

echo "complete=$DEST"
