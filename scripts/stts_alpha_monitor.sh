#!/usr/bin/env bash
set -euo pipefail

TARGET=${SSH_TARGET:-pixel6a-lan}
SSH_CONFIG=${SSH_CONFIG:-$HOME/.ssh/config}
BATCH=${BATCH:-manual}
INTERVAL_SECONDS=${INTERVAL_SECONDS:-5}
OUT_DIR=${OUT_DIR:-/tmp/ccva-stts-alpha}

usage() {
  cat <<'USAGE'
Usage: scripts/stts_alpha_monitor.sh [--target SSH_ALIAS] [--ssh-config PATH] [--batch NAME] [--interval SECONDS]

Records periodic STTS state from a Termux device during live alpha testing.
Stop with Ctrl-C after the test batch finishes.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --target)
      TARGET=${2:?missing target}
      shift 2
      ;;
    --ssh-config)
      SSH_CONFIG=${2:?missing ssh config}
      shift 2
      ;;
    --batch)
      BATCH=${2:?missing batch}
      shift 2
      ;;
    --interval)
      INTERVAL_SECONDS=${2:?missing interval}
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

mkdir -p "$OUT_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
LOG_PATH="$OUT_DIR/${STAMP}-${BATCH}.log"

echo "monitor_log=$LOG_PATH"
echo "target=$TARGET"
echo "batch=$BATCH"
echo "interval_seconds=$INTERVAL_SECONDS"

snapshot() {
  ssh -F "$SSH_CONFIG" "$TARGET" 'set -u
printf "time: "
date
printf "status: "
stts status 2>&1 || true
printf "doctor:\n"
timeout 20 stts doctor 2>&1 || true
printf "processes:\n"
ps -ef | grep -E "stts_loop|termux-speech-to-text|termux-tts-speak|termux-api|codex exec" | grep -v grep || true
printf "state_files:\n"
STATE="$HOME/.local/state/codex-stts"
find "$STATE" -maxdepth 1 -type f -printf "%TY-%Tm-%Td %TH:%TM:%TS %s %p\n" 2>/dev/null | sort | tail -20 || true
if [ -f "$STATE/last-session.txt" ]; then
  LAST=$(cat "$STATE/last-session.txt")
  printf "last_session: %s\n" "$LAST"
  [ -f "$LAST" ] && tail -40 "$LAST" || true
fi
'
}

trap 'echo "stopping monitor"; echo "monitor_log=$LOG_PATH"' INT TERM

while true; do
  {
    echo "===== snapshot $(date +%Y-%m-%dT%H:%M:%S%z) ====="
    snapshot
    echo
  } 2>&1 | tee -a "$LOG_PATH"
  sleep "$INTERVAL_SECONDS"
done

