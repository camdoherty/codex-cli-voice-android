#!/data/data/com.termux/files/usr/bin/sh
set -eu

APPLY=0
VERIFY=0
TRY_UNINSTALL_SHIM=0
PREFIX_DIR=${PREFIX:-/data/data/com.termux/files/usr}
HOME_DIR=${HOME:-}
SHIM_PACKAGE=${CCVA_SHIM_PACKAGE:-io.github.codex_cli_voice_android.aecshim}

usage() {
    cat <<'EOF'
Usage: sh scripts/clean_termux_ccva.sh [--apply] [--verify] [--try-uninstall-shim]

Dry-run is the default. This removes only known Codex CLI Voice Android files
from Termux. It does not remove Termux, Termux:API, shell config, SSH config,
Codex credentials, API keys, or user notes/projects.

Options:
  --apply               Delete the listed CCVA-owned files.
  --verify              Report remaining CCVA-owned files without deleting.
  --try-uninstall-shim  Best-effort Android shim uninstall. Usually blocked on
                        non-rooted Android; manual uninstall is expected.
  -h, --help            Show this help.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --apply)
            APPLY=1
            ;;
        --verify)
            VERIFY=1
            ;;
        --try-uninstall-shim)
            TRY_UNINSTALL_SHIM=1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

[ -n "$HOME_DIR" ] || {
    echo "HOME is not set" >&2
    exit 2
}

case "$PREFIX_DIR" in
    ""|"/")
        echo "refusing unsafe PREFIX: $PREFIX_DIR" >&2
        exit 2
        ;;
esac

is_owned_path() {
    case "$1" in
        "$HOME_DIR"/*|"$PREFIX_DIR"/*)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

exists_path() {
    [ -e "$1" ] || [ -L "$1" ]
}

remaining=0

verify_one() {
    if exists_path "$1"; then
        echo "present: $1"
        remaining=$((remaining + 1))
    else
        echo "absent:  $1"
    fi
}

remove_one() {
    path=$1
    is_owned_path "$path" || {
        echo "refusing non-CCVA path outside HOME/PREFIX: $path" >&2
        exit 2
    }
    if [ "$VERIFY" -eq 1 ]; then
        verify_one "$path"
        return
    fi
    if ! exists_path "$path"; then
        echo "absent:  $path"
        return
    fi
    if [ "$APPLY" -eq 1 ]; then
        rm -rf "$path"
        echo "removed: $path"
    else
        echo "would remove: $path"
    fi
}

remove_glob() {
    pattern=$1
    matched=0
    for path in $pattern; do
        if exists_path "$path"; then
            matched=1
            remove_one "$path"
        fi
    done
    if [ "$matched" -eq 0 ] && [ "$VERIFY" -eq 0 ]; then
        echo "absent:  $pattern"
    fi
}

stop_helpers() {
    if [ "$VERIFY" -eq 1 ]; then
        return
    fi
    if [ "$APPLY" -eq 0 ]; then
        echo "would run: stts cleanup"
        return
    fi
    if command -v stts >/dev/null 2>&1; then
        stts cleanup >/dev/null 2>&1 || true
        echo "ran: stts cleanup"
    elif [ -x "$HOME_DIR/.codex/skills/stts/scripts/stts-session.sh" ]; then
        sh "$HOME_DIR/.codex/skills/stts/scripts/stts-session.sh" cleanup >/dev/null 2>&1 || true
        echo "ran: stts-session cleanup"
    else
        echo "skip: no stts cleanup command found"
    fi
}

check_shim() {
    if ! command -v pm >/dev/null 2>&1; then
        echo "shim package check skipped: pm missing"
        return
    fi
    if pm path "$SHIM_PACKAGE" >/dev/null 2>&1; then
        echo "shim app installed: $SHIM_PACKAGE"
        if [ "$TRY_UNINSTALL_SHIM" -eq 1 ] && [ "$APPLY" -eq 1 ]; then
            if pm uninstall "$SHIM_PACKAGE"; then
                echo "shim uninstall requested successfully"
            else
                echo "shim uninstall failed; uninstall manually from Android Settings"
            fi
        else
            echo "manual step: uninstall the shim app from Android Settings if a fully clean device is required"
        fi
    else
        echo "shim app absent: $SHIM_PACKAGE"
    fi
}

echo "CCVA cleanup target:"
echo "  HOME=$HOME_DIR"
echo "  PREFIX=$PREFIX_DIR"
if [ "$VERIFY" -eq 1 ]; then
    echo "mode=verify"
elif [ "$APPLY" -eq 1 ]; then
    echo "mode=apply"
else
    echo "mode=dry-run"
fi

stop_helpers

remove_one "$PREFIX_DIR/bin/codex"
remove_one "$PREFIX_DIR/bin/codex-api"
remove_one "$PREFIX_DIR/bin/codex-voice"
remove_one "$PREFIX_DIR/bin/codex-install-stts"
remove_one "$PREFIX_DIR/bin/codex-install-tts-stt"
remove_one "$PREFIX_DIR/libexec/codex-cli-voice-android"
remove_one "$PREFIX_DIR/opt/codex-termux"

remove_one "$HOME_DIR/.codex/skills/stts"
remove_one "$HOME_DIR/.codex/skills/tts-stt"
remove_one "$HOME_DIR/.local/state/codex-stts"
remove_one "$HOME_DIR/.cache/ccva-installer"
remove_one "$HOME_DIR/.cache/ccva-wake-models"

remove_one "$HOME_DIR/scripts/codex-api"
remove_one "$HOME_DIR/scripts/codex-voice"
remove_one "$HOME_DIR/scripts/codex-install-stts"
remove_one "$HOME_DIR/scripts/codex-install-tts-stt"
remove_glob "$HOME_DIR/scripts/stts*"
remove_glob "$HOME_DIR/scripts/wake-voice-*"

remove_one "$HOME_DIR/.shortcuts/codex"
remove_one "$HOME_DIR/.shortcuts/Codex"
remove_one "$HOME_DIR/.shortcuts/Codex Resume Last"
remove_one "$HOME_DIR/.shortcuts/codex-voice"
remove_one "$HOME_DIR/.shortcuts/Realtime API Voice"
remove_one "$HOME_DIR/.shortcuts/Start API(\$) Realtime Voice Mode"
remove_one "$HOME_DIR/.shortcuts/STTS: Start + Talk"
remove_one "$HOME_DIR/.shortcuts/STTS: Attach Session"
remove_one "$HOME_DIR/.shortcuts/STTS: Stop"
remove_one "$HOME_DIR/.shortcuts/Start STTS Voice Mode"
remove_one "$HOME_DIR/.shortcuts/Open STTS Session"
remove_one "$HOME_DIR/.shortcuts/Stop STTS Voice Mode"
remove_one "$HOME_DIR/.shortcuts/STTS Loop"
remove_glob "$HOME_DIR/.shortcuts/stts*"
remove_glob "$HOME_DIR/.shortcuts/wake-voice-*"
remove_one "$HOME_DIR/.shortcuts/tts-stt-start"
remove_one "$HOME_DIR/.shortcuts/Start TTS STT Voice Mode"
remove_one "$HOME_DIR/.shortcuts/tts-stt-stop"
remove_one "$HOME_DIR/.shortcuts/tts-stt-status"
remove_one "$HOME_DIR/.shortcuts/tts-stt-diag"

remove_glob "$HOME_DIR/codex-aec-shim*.apk"
remove_glob "$HOME_DIR/codex-aec-shim*.apk.sha256"
remove_glob "$HOME_DIR/codex-cli-voice-android-*.tar.gz"
remove_glob "$HOME_DIR/codex-cli-voice-android-*.tar.gz.sha256"
remove_glob "$HOME_DIR/codex-cli-voice-android-*.tar.gz.metadata"
remove_glob "$HOME_DIR/codex-cli-voice-android-*.bundle"
remove_glob "$HOME_DIR/codex-backup-before-*.tar.gz"
remove_glob "$HOME_DIR/stts-skill-backup-*.tar.gz"
remove_glob "$HOME_DIR/tts-stt-skill-backup-*.tar.gz"
remove_glob "$HOME_DIR/codex-voice-guard*.out"
remove_glob "$HOME_DIR/codex_voice_guard*.out"
remove_glob "$HOME_DIR/storage/downloads/codex-aec-shim*.apk"
remove_glob "$HOME_DIR/storage/downloads/codex-aec-shim*.apk.sha256"
remove_glob "$HOME_DIR/storage/shared/Download/codex-aec-shim*.apk"
remove_glob "$HOME_DIR/storage/shared/Download/codex-aec-shim*.apk.sha256"

check_shim

if [ "$VERIFY" -eq 1 ]; then
    if [ "$remaining" -eq 0 ]; then
        echo "verify: no known CCVA-owned files remain"
        exit 0
    fi
    echo "verify: $remaining known CCVA-owned path(s) remain"
    exit 1
fi

if [ "$APPLY" -eq 0 ]; then
    echo "dry-run complete; rerun with --apply to remove these files"
else
    echo "cleanup complete; run with --verify to confirm"
fi
