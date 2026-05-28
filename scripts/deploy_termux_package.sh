#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/deploy_termux_package.sh <package.tar.gz> [package.tar.gz.sha256]

Uploads a Codex CLI + Voice (Android) package to a Termux device over SSH, verifies
the remote SHA256, creates a rollback backup, extracts into $PREFIX, repairs
known launcher symlinks, installs the stts skill, and runs non-paid smoke
tests.

Environment:
  PIXEL_HOST      required unless set in .env
  PIXEL_USER      required unless set in .env
  PIXEL_PORT      optional, default: 8022
  PIXEL_IDENTITY  optional SSH private key path
  SSH_CONFIG      default: /dev/null
  ALLOW_FRESH_INSTALL  set to 1 to deploy when no previous Codex install exists
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
ssh "${SSH_OPTS[@]}" "$PIXEL_TARGET" 'sh -s' -- "$REMOTE_NAME" "$ALLOW_FRESH_INSTALL" <<'REMOTE_DEPLOY'
set -eu
remote_name="$1"
allow_fresh_install="$2"
artifact="$HOME/$remote_name"

tar -tzf "$artifact" >/dev/null

ts="$(date +%Y%m%d-%H%M%S)"
cd "$PREFIX"
set --
[ -e bin/codex ] && set -- "$@" bin/codex
[ -e bin/codex-api ] && set -- "$@" bin/codex-api
[ -e bin/codex-voice ] && set -- "$@" bin/codex-voice
[ -e bin/codex-install-stts ] && set -- "$@" bin/codex-install-stts
[ -e bin/ccva-tmux-run ] && set -- "$@" bin/ccva-tmux-run
[ -e bin/codex-install-tts-stt ] && set -- "$@" bin/codex-install-tts-stt
[ -e libexec/codex-cli-voice-android ] && set -- "$@" libexec/codex-cli-voice-android
[ -e opt/codex-termux ] && set -- "$@" opt/codex-termux
[ "$#" -gt 0 ] || [ "$allow_fresh_install" = "1" ] || {
    echo "No existing Codex install paths found to back up; aborting deploy" >&2
    echo "Set ALLOW_FRESH_INSTALL=1 for a clean first install." >&2
    exit 1
}

if [ "$#" -gt 0 ]; then
    backup="$HOME/codex-backup-before-${remote_name%.tar.gz}-$ts.tar.gz"
    tar -czf "$backup" "$@"
    tar -tzf "$backup" >/dev/null
    ln -sf "$backup" "$HOME/codex-backup-before-latest.tar.gz"
else
    backup=""
fi

rm -rf "$PREFIX/libexec/codex-cli-voice-android" "$PREFIX/opt/codex-termux"
rm -f "$PREFIX/bin/codex" "$PREFIX/bin/codex-api" "$PREFIX/bin/codex-voice" \
    "$PREFIX/bin/codex-install-stts" "$PREFIX/bin/ccva-tmux-run" \
    "$PREFIX/bin/codex-install-tts-stt" \
    "$PREFIX/bin/tts-stt-start" "$PREFIX/bin/tts-stt-stop" \
    "$PREFIX/bin/tts-stt-status" "$PREFIX/bin/tts-stt-diag" \
    "$PREFIX/bin/tts-stt-talk"
tar -xzf "$artifact" -C "$PREFIX"

codex-install-stts

mkdir -p "$HOME/scripts"
rm -f "$HOME/scripts/codex-install-tts-stt" \
    "$HOME/scripts/tts-stt-start" "$HOME/scripts/tts-stt-stop" \
    "$HOME/scripts/tts-stt-status" "$HOME/scripts/tts-stt-diag" \
    "$HOME/scripts/tts-stt-talk"
for name in codex-api codex-voice codex-install-stts ccva-tmux-run; do
    if [ -e "$HOME/scripts/$name" ] && [ ! -L "$HOME/scripts/$name" ]; then
        mkdir -p "$HOME/codex-script-backups-$ts"
        mv "$HOME/scripts/$name" "$HOME/codex-script-backups-$ts/$name"
    fi
    ln -sfn "$PREFIX/bin/$name" "$HOME/scripts/$name"
done

echo "backup=$backup"
REMOTE_DEPLOY

echo "Running non-paid smoke tests"
ssh "${SSH_OPTS[@]}" "$PIXEL_TARGET" 'sh -s' <<'REMOTE_SMOKE'
set -eu
codex --version
codex-api --version
codex-voice --allow-realtime --version
codex exec --help >/dev/null

set +e
codex-voice >/dev/null 2>&1
guard_exit="$?"
set -e
[ "$guard_exit" -eq 2 ] || {
    echo "Expected codex-voice billing guard exit 2, got $guard_exit" >&2
    exit 1
}

for name in codex-api codex-voice codex-install-stts ccva-tmux-run; do
    target="$(readlink "$HOME/scripts/$name" 2>/dev/null || true)"
    [ "$target" = "$PREFIX/bin/$name" ] || {
        echo "$HOME/scripts/$name does not point to $PREFIX/bin/$name" >&2
        exit 1
    }
done

[ -x "$HOME/.codex/skills/stts/scripts/stts-session.sh" ] || {
    echo "stts skill was not installed" >&2
    exit 1
}
timeout 8 sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" status >/dev/null

if strings "$PREFIX/libexec/codex-cli-voice-android/codex.bin" |
    grep -F "WARNING: flock unsupported" >/dev/null; then
    echo "Unexpected Android flock warning string in installed binary" >&2
    exit 1
fi

echo "Smoke tests passed"
REMOTE_SMOKE
