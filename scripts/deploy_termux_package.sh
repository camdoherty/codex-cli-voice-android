#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/deploy_termux_package.sh <package.tar.gz> [package.tar.gz.sha256]

Uploads a Codex CLI + Voice (Android) package to a Termux device over SSH, verifies
the remote SHA256, creates a rollback backup, extracts into $PREFIX, repairs
known launcher symlinks, installs packaged skills with no-clobber checks, and
runs non-paid smoke tests.

Environment:
  PIXEL_HOST      required unless set in .env
  PIXEL_USER      required unless set in .env
  PIXEL_PORT      optional, default: 8022
  PIXEL_IDENTITY  optional SSH private key path
  SSH_CONFIG      default: /dev/null
  ALLOW_FRESH_INSTALL  set to 1 to deploy when no previous Codex install exists
  ALLOW_TERMUX_SETTINGS_CHANGE  set to 1 to update allow-external-apps
EOF
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
if [ -f "$REPO_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$REPO_DIR/.env"
    set +a
fi

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    usage
    exit 0
fi

TARBALL="${1:-}"
[ -n "$TARBALL" ] || { usage >&2; exit 2; }
[ -f "$TARBALL" ] || { echo "Package not found: $TARBALL" >&2; exit 1; }

SHA_FILE="${2:-$TARBALL.sha256}"
[ -f "$SHA_FILE" ] || { echo "Checksum file not found: $SHA_FILE" >&2; exit 1; }

REMOTE_NAME="$(basename "$TARBALL")"
case "$REMOTE_NAME" in
    *[!A-Za-z0-9._-]*|""|.*)
        echo "Invalid package basename: $REMOTE_NAME" >&2
        exit 1
        ;;
esac

EXPECTED_SHA="$(awk 'NF { print $1; exit }' "$SHA_FILE")"
if ! printf '%s' "$EXPECTED_SHA" | grep -Eq '^[0-9a-fA-F]{64}$'; then
    echo "Could not parse SHA256 from: $SHA_FILE" >&2
    exit 1
fi

PIXEL_HOST="${PIXEL_HOST:-}"
PIXEL_USER="${PIXEL_USER:-}"
PIXEL_PORT="${PIXEL_PORT:-8022}"
SSH_CONFIG="${SSH_CONFIG:-/dev/null}"
[ -n "$PIXEL_HOST" ] || { echo "PIXEL_HOST is required. Copy .env.example to .env or export it." >&2; exit 2; }
[ -n "$PIXEL_USER" ] || { echo "PIXEL_USER is required. Copy .env.example to .env or export it." >&2; exit 2; }
ALLOW_FRESH_INSTALL="${ALLOW_FRESH_INSTALL:-0}"
ALLOW_TERMUX_SETTINGS_CHANGE="${ALLOW_TERMUX_SETTINGS_CHANGE:-0}"
PIXEL_TARGET="${PIXEL_USER}@${PIXEL_HOST}"
SSH_OPTS=(-F "$SSH_CONFIG" -p "$PIXEL_PORT")
if [ -n "${PIXEL_IDENTITY:-}" ]; then
    SSH_OPTS+=(-o "IdentityFile=$PIXEL_IDENTITY" -o IdentitiesOnly=yes)
fi

echo "Uploading $REMOTE_NAME to $PIXEL_TARGET:~/"
ssh "${SSH_OPTS[@]}" "$PIXEL_TARGET" "cat > \"\$HOME/$REMOTE_NAME\"" < "$TARBALL"
printf '%s  %s\n' "$EXPECTED_SHA" "$REMOTE_NAME" |
    ssh "${SSH_OPTS[@]}" "$PIXEL_TARGET" "cat > \"\$HOME/$REMOTE_NAME.sha256\""

echo "Verifying remote checksum"
ssh "${SSH_OPTS[@]}" "$PIXEL_TARGET" 'sh -s' -- "$REMOTE_NAME" "$EXPECTED_SHA" <<'REMOTE_VERIFY'
set -eu
remote_name="$1"
expected_sha="$2"
cd "$HOME"
actual_sha="$(sha256sum "$remote_name" | awk '{ print $1 }')"
[ "$actual_sha" = "$expected_sha" ] || {
    echo "SHA mismatch: expected $expected_sha got $actual_sha" >&2
    exit 1
}
ls -lh "$HOME/$remote_name"
REMOTE_VERIFY

echo "Backing up current install and deploying"
ssh "${SSH_OPTS[@]}" "$PIXEL_TARGET" 'sh -s' -- "$REMOTE_NAME" "$ALLOW_FRESH_INSTALL" "$ALLOW_TERMUX_SETTINGS_CHANGE" <<'REMOTE_DEPLOY'
set -eu
remote_name="$1"
allow_fresh_install="$2"
allow_termux_settings_change="$3"
artifact="$HOME/$remote_name"

tar -tzf "$artifact" >/dev/null

ts="$(date +%Y%m%d-%H%M%S)"
cd "$PREFIX"
set --
[ -e bin/codex ] && set -- "$@" bin/codex
[ -e bin/codex-api ] && set -- "$@" bin/codex-api
[ -e bin/codex-voice ] && set -- "$@" bin/codex-voice
[ -e bin/codex-realtime-adapter ] && set -- "$@" bin/codex-realtime-adapter
[ -e bin/codex-install-stts ] && set -- "$@" bin/codex-install-stts
[ -e bin/codex-install-agent-assets ] && set -- "$@" bin/codex-install-agent-assets
[ -e bin/ccva-tmux-run ] && set -- "$@" bin/ccva-tmux-run
[ -e bin/ccva-realtime-stop ] && set -- "$@" bin/ccva-realtime-stop
[ -e bin/codex-install-tts-stt ] && set -- "$@" bin/codex-install-tts-stt
[ -e libexec/codex-cli-voice-android ] && set -- "$@" libexec/codex-cli-voice-android
[ -e opt/codex-termux ] && set -- "$@" opt/codex-termux
[ "$#" -gt 0 ] || [ "$allow_fresh_install" = "1" ] || {
    echo "No existing Codex install paths found to back up; aborting deploy" >&2
    echo "Set ALLOW_FRESH_INSTALL=1 for a clean first install." >&2
    exit 1
}

if [ "$#" -gt 0 ]; then
    install_backup="$HOME/codex-backup-before-${remote_name%.tar.gz}-$ts.tar.gz"
    tar -czf "$install_backup" "$@"
    tar -tzf "$install_backup" >/dev/null
    ln -sf "$install_backup" "$HOME/codex-backup-before-latest.tar.gz"
else
    install_backup=""
fi

rm -rf "$PREFIX/libexec/codex-cli-voice-android" "$PREFIX/opt/codex-termux"
rm -f "$PREFIX/bin/codex" "$PREFIX/bin/codex-api" "$PREFIX/bin/codex-voice" \
    "$PREFIX/bin/codex-realtime-adapter" \
    "$PREFIX/bin/codex-install-stts" "$PREFIX/bin/codex-install-agent-assets" \
    "$PREFIX/bin/ccva-tmux-run" "$PREFIX/bin/ccva-realtime-stop" \
    "$PREFIX/bin/codex-install-tts-stt" \
    "$PREFIX/bin/tts-stt-start" "$PREFIX/bin/tts-stt-stop" \
    "$PREFIX/bin/tts-stt-status" "$PREFIX/bin/tts-stt-diag" \
    "$PREFIX/bin/tts-stt-talk"
tar -xzf "$artifact" -C "$PREFIX"

codex-install-stts
codex-install-agent-assets --apply --skills-only

mkdir -p "$HOME/scripts"
for old_script in \
    "$HOME/scripts/codex-install-tts-stt" \
    "$HOME/scripts/tts-stt-start" "$HOME/scripts/tts-stt-stop" \
    "$HOME/scripts/tts-stt-status" "$HOME/scripts/tts-stt-diag" \
    "$HOME/scripts/tts-stt-talk"; do
    [ ! -e "$old_script" ] || echo "old_script_present=$old_script"
done
for name in codex-api codex-voice codex-realtime-adapter codex-install-stts codex-install-agent-assets ccva-tmux-run ccva-realtime-stop; do
    if [ -e "$HOME/scripts/$name" ] && [ ! -L "$HOME/scripts/$name" ]; then
        mkdir -p "$HOME/codex-script-backups-$ts"
        mv "$HOME/scripts/$name" "$HOME/codex-script-backups-$ts/$name"
    fi
    ln -sfn "$PREFIX/bin/$name" "$HOME/scripts/$name"
done

mkdir -p "$HOME/.shortcuts"
mkdir -p "$HOME/.termux"
termux_properties="$HOME/.termux/termux.properties"
if [ "$allow_termux_settings_change" = "1" ]; then
    if [ -f "$termux_properties" ] && grep -q '^allow-external-apps=' "$termux_properties"; then
        tmp="$termux_properties.tmp.$$"
        sed 's/^allow-external-apps=.*/allow-external-apps=true/' "$termux_properties" > "$tmp"
        mv "$tmp" "$termux_properties"
    else
        printf '%s\n' 'allow-external-apps=true' >> "$termux_properties"
    fi
    if command -v termux-reload-settings >/dev/null 2>&1; then
        termux-reload-settings >/dev/null 2>&1 || true
    fi
else
    echo "termux_settings_change=skipped"
fi

notes_link="$HOME/codex_notes"
notes_shared="$HOME/storage/shared/Documents/codex_notes"
notes_dir=""
if [ -d "$HOME/storage/shared" ]; then
    mkdir -p "$notes_shared"
    if [ -L "$notes_link" ] || [ ! -e "$notes_link" ]; then
        ln -sfn "$notes_shared" "$notes_link"
        notes_dir="$notes_shared"
    elif [ -d "$notes_link" ]; then
        notes_dir="$notes_link"
    else
        notes_dir="$notes_shared"
        echo "warning: $notes_link exists and is not a directory or symlink; using $notes_shared"
    fi
else
    notes_dir="$notes_link"
    mkdir -p "$notes_dir"
    echo "warning: shared storage is not available; Android note apps may not see $notes_dir"
fi
if [ -d "$notes_dir" ] && [ ! -e "$notes_dir/README.md" ]; then
    if [ -z "$(find "$notes_dir" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]; then
        cat > "$notes_dir/README.md" <<'EOF'
# Codex Notes

Default CCAT/STTS notes workspace for Markdown notes created, read, opened, or shared from Android.
EOF
    fi
fi
echo "notes_workspace=$notes_dir"

write_shortcut() {
    path=$1
    tmp="$path.tmp.$$"
    cat > "$tmp"
    if [ ! -e "$path" ]; then
        mv "$tmp" "$path"
        chmod 700 "$path"
        echo "shortcut_installed=$path"
        return
    fi
    if cmp -s "$tmp" "$path"; then
        rm -f "$tmp"
        chmod 700 "$path"
        echo "shortcut_skip_identical=$path"
        return
    fi
    shortcut_backup="$path.backup.$ts"
    incoming="$path.incoming.$ts"
    cp "$path" "$shortcut_backup"
    mv "$tmp" "$incoming"
    chmod 700 "$incoming"
    echo "shortcut_conflict_preserved=$path"
    echo "shortcut_backup=$shortcut_backup"
    echo "shortcut_incoming=$incoming"
}

write_shortcut "$HOME/.shortcuts/Codex" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
exec "$HOME/scripts/ccva-tmux-run" codex -- codex
EOF
write_shortcut "$HOME/.shortcuts/Codex Resume Last" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
exec "$HOME/scripts/ccva-tmux-run" resume -- codex resume --last
EOF
write_shortcut "$HOME/.shortcuts/Realtime API Voice" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
exec "$HOME/scripts/ccva-tmux-run" realtime -- "$HOME/scripts/codex-voice" --allow-realtime
EOF
write_shortcut "$HOME/.shortcuts/Realtime API Voice Stop" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
exec "$HOME/scripts/ccva-realtime-stop"
EOF
write_shortcut "$HOME/.shortcuts/STTS: Start + Talk" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" talk
EOF
write_shortcut "$HOME/.shortcuts/STTS: Wake Word" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" wake
EOF
write_shortcut "$HOME/.shortcuts/STTS: Attach Session" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" session
EOF
write_shortcut "$HOME/.shortcuts/STTS: Stop" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" stop
EOF
for old_shortcut in \
    "$HOME/.shortcuts/Realtime API Stop" \
    "$HOME/.shortcuts/Start API(\$) Realtime Voice Mode" \
    "$HOME/.shortcuts/Start STTS Voice Mode" \
    "$HOME/.shortcuts/Open STTS Session" \
    "$HOME/.shortcuts/Stop STTS Voice Mode" \
    "$HOME/.shortcuts/stts-start" \
    "$HOME/.shortcuts/stts-talk" \
    "$HOME/.shortcuts/stts-stop" \
    "$HOME/.shortcuts/stts-status" \
    "$HOME/.shortcuts/stts-diag" \
    "$HOME/.shortcuts/STTS Loop" \
    "$HOME/.shortcuts/STTS Wake Word" \
    "$HOME/.shortcuts/wake-voice-start" \
    "$HOME/.shortcuts/wake-voice-stop" \
    "$HOME/.shortcuts/wake-voice-doctor"; do
    [ ! -e "$old_shortcut" ] || echo "old_shortcut_present=$old_shortcut"
done

echo "install_backup=$install_backup"
REMOTE_DEPLOY

echo "Running non-paid smoke tests"
ssh "${SSH_OPTS[@]}" "$PIXEL_TARGET" 'sh -s' <<'REMOTE_SMOKE'
set -eu
codex --version
codex-api --version
# Wrapper/version smoke only. This does not start or prove functional Realtime.
codex-voice --allow-realtime --version
codex-realtime-adapter --version
codex exec --help >/dev/null

set +e
codex-voice >/dev/null 2>&1
guard_exit="$?"
set -e
if [ "$guard_exit" -ne 2 ]; then
    echo "Expected codex-voice billing guard exit 2, got $guard_exit" >&2
    exit 1
fi

for name in codex-api codex-voice codex-realtime-adapter codex-install-stts codex-install-agent-assets ccva-tmux-run ccva-realtime-stop; do
    target="$(readlink "$HOME/scripts/$name" 2>/dev/null || true)"
    if [ "$target" != "$PREFIX/bin/$name" ]; then
        echo "$HOME/scripts/$name does not point to $PREFIX/bin/$name" >&2
        exit 1
    fi
done

if [ ! -x "$HOME/.codex/skills/stts/scripts/stts-session.sh" ]; then
    echo "stts skill was not installed" >&2
    exit 1
fi
timeout 8 sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" status >/dev/null

if [ ! -f "$HOME/.codex/skills/termux-agent-ops/SKILL.md" ]; then
    echo "termux-agent-ops skill was not installed" >&2
    exit 1
fi
if [ ! -f "$HOME/.codex/skills/obsidian-notes-maintainer/SKILL.md" ]; then
    echo "obsidian-notes-maintainer skill was not installed" >&2
    exit 1
fi
if [ ! -f "$HOME/.codex/skills/codex-overview/SKILL.md" ]; then
    echo "codex-overview skill was not installed" >&2
    exit 1
fi
if [ ! -x "$HOME/.codex/skills/tmux-support/scripts/tmux_context.sh" ]; then
    echo "tmux-support skill was not installed" >&2
    exit 1
fi

if strings "$PREFIX/libexec/codex-cli-voice-android/codex.bin" | grep -F "WARNING: flock unsupported" >/dev/null; then
    echo "Unexpected Android flock warning string in installed binary" >&2
    exit 1
fi

echo "Smoke tests passed"
REMOTE_SMOKE
