#!/data/data/com.termux/files/usr/bin/sh
set -eu

usage() {
    cat <<'EOF'
Usage: codex-install-agent-assets [--dry-run|--apply] [--skills-only|--instructions]

Installs packaged Codex agent support assets with no-clobber semantics.

Defaults:
  --dry-run
  --skills-only

Behavior:
  - Missing skills may be installed in --apply mode.
  - Identical targets are skipped.
  - Differing targets are backed up and incoming content is written beside the
    target; live files are not replaced.
  - Instruction files are opt-in and require --instructions.
EOF
}

MODE=dry-run
SCOPE=skills
while [ "$#" -gt 0 ]; do
    case "$1" in
        --dry-run)
            MODE=dry-run
            ;;
        --apply)
            MODE=apply
            ;;
        --skills-only)
            SCOPE=skills
            ;;
        --instructions)
            SCOPE=instructions
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

PREFIX_DIR=${PREFIX:-/data/data/com.termux/files/usr}
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." 2>/dev/null && pwd || true)

if [ -n "${CODEX_AGENT_SUPPORT_SRC:-}" ]; then
    SUPPORT_SRC=$CODEX_AGENT_SUPPORT_SRC
elif [ -n "$REPO_DIR" ] && [ -d "$REPO_DIR/support/termux-skills" ]; then
    SUPPORT_SRC=$REPO_DIR/support
else
    SUPPORT_SRC=$PREFIX_DIR/libexec/codex-cli-voice-android/support
fi

ASSETS_SRC=$SUPPORT_SRC/termux-agent-assets
TS=$(date +%Y%m%d-%H%M%S)

detect_device_key() {
    if [ -n "${CODEX_AGENT_DEVICE:-}" ]; then
        case "$CODEX_AGENT_DEVICE" in
            pixel6a|6a|p6a)
                echo pixel6a
                return
                ;;
            pixel9|9|p9)
                echo pixel9
                return
                ;;
            *)
                echo "$CODEX_AGENT_DEVICE"
                return
                ;;
        esac
    fi

    model=$(getprop ro.product.model 2>/dev/null || true)
    case "$model" in
        *"Pixel 6a"*)
            echo pixel6a
            ;;
        *"Pixel 9"*)
            echo pixel9
            ;;
        *)
            echo pixel9
            ;;
    esac
}

copy_dir() {
    src=$1
    dest=$2
    [ -d "$src" ] || {
        echo "missing_source=$src"
        return 1
    }
    parent=$(dirname "$dest")
    name=$(basename "$dest")
    if [ ! -e "$dest" ]; then
        if [ "$MODE" = dry-run ]; then
            echo "would_install=$dest"
            return 0
        fi
        mkdir -p "$parent" "$dest"
        (cd "$src" && tar -cf - .) | (cd "$dest" && tar -xf -)
        [ ! -d "$dest/scripts" ] || find "$dest/scripts" -type f -exec chmod 700 {} \;
        echo "installed=$dest"
        return 0
    fi
    diff_excludes="-x .local -x __pycache__ -x *.pyc"
    # shellcheck disable=SC2086
    if diff -qr $diff_excludes "$src" "$dest" >/dev/null 2>&1; then
        echo "skip_identical=$dest"
        return 0
    fi
    if [ "$MODE" = dry-run ]; then
        echo "would_conflict=$dest"
        return 0
    fi
    backup=$HOME/agent-assets-backup-${name}-$TS.tar.gz
    tar -czf "$backup" -C "$parent" "$name"
    tar -tzf "$backup" >/dev/null
    incoming=$dest.incoming.$TS
    mkdir -p "$incoming"
    (cd "$src" && tar -cf - .) | (cd "$incoming" && tar -xf -)
    [ ! -d "$incoming/scripts" ] || find "$incoming/scripts" -type f -exec chmod 700 {} \;
    echo "conflict_preserved=$dest"
    echo "backup=$backup"
    echo "incoming=$incoming"
}

copy_file() {
    src=$1
    dest=$2
    [ -f "$src" ] || {
        echo "missing_source=$src"
        return 1
    }
    parent=$(dirname "$dest")
    name=$(basename "$dest")
    if [ ! -e "$dest" ]; then
        if [ "$MODE" = dry-run ]; then
            echo "would_install=$dest"
            return 0
        fi
        mkdir -p "$parent"
        cp "$src" "$dest"
        chmod 600 "$dest"
        echo "installed=$dest"
        return 0
    fi
    if cmp -s "$src" "$dest"; then
        echo "skip_identical=$dest"
        return 0
    fi
    if [ "$MODE" = dry-run ]; then
        echo "would_conflict=$dest"
        return 0
    fi
    backup=$HOME/agent-assets-backup-${name}-$TS
    cp "$dest" "$backup"
    incoming=$dest.incoming.$TS
    cp "$src" "$incoming"
    chmod 600 "$incoming"
    echo "conflict_preserved=$dest"
    echo "backup=$backup"
    echo "incoming=$incoming"
}

echo "mode=$MODE"
echo "scope=$SCOPE"
echo "support_src=$SUPPORT_SRC"

if [ "$SCOPE" = skills ]; then
    copy_dir "$SUPPORT_SRC/termux-skills/termux-agent-ops" "$HOME/.codex/skills/termux-agent-ops"
    copy_dir "$SUPPORT_SRC/termux-skills/obsidian-notes-maintainer" "$HOME/.codex/skills/obsidian-notes-maintainer"
    copy_dir "$SUPPORT_SRC/termux-skills/codex-overview" "$HOME/.codex/skills/codex-overview"
    copy_dir "$SUPPORT_SRC/termux-skills/tmux-support" "$HOME/.codex/skills/tmux-support"
    exit 0
fi

DEVICE_KEY=$(detect_device_key)
DEVICE_INSTRUCTIONS=$ASSETS_SRC/instructions/$DEVICE_KEY

copy_file "$DEVICE_INSTRUCTIONS/AGENTS.md" "$HOME/AGENTS.md"
[ ! -f "$DEVICE_INSTRUCTIONS/dev_AGENTS.md" ] || copy_file "$DEVICE_INSTRUCTIONS/dev_AGENTS.md" "$HOME/dev/AGENTS.md"
[ ! -f "$DEVICE_INSTRUCTIONS/gitignore.reference" ] || copy_file "$DEVICE_INSTRUCTIONS/gitignore.reference" "$HOME/.gitignore"
copy_file "$ASSETS_SRC/instructions/common/CCAT_AGENT_CONTRACT.md" "$HOME/dev/CCAT_AGENT_CONTRACT.md"
copy_file "$ASSETS_SRC/instructions/common/CCAT_AGENT_CONTRACT_APPLY.md" "$HOME/dev/CCAT_AGENT_CONTRACT_APPLY.md"
