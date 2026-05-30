#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$REPO_DIR/tmp/android-runtime-snapshots"
SERIAL="${ANDROID_SERIAL:-}"
LOGCAT_SINCE="30 minutes ago"
PRINT_REPORT=0

PACKAGES=(
  "com.termux"
  "com.termux.api"
  "io.github.codex_cli_voice_android.aecshim"
)

usage() {
  cat <<'USAGE'
Usage: scripts/android_runtime_snapshot.sh [--serial ADB_SERIAL] [--out-dir DIR] [--logcat-since TIME]

Captures a development-only Android runtime snapshot for CCVA/STTS/WWS
diagnostics. Best run while WWS is armed, immediately after a screen-off test,
or immediately after a socket/audio/foreground-service issue.

Options:
  --serial ADB_SERIAL  Use a specific adb device serial. Defaults to
                       ANDROID_SERIAL, or the only connected device.
  --out-dir DIR        Output directory. Defaults to tmp/android-runtime-snapshots.
  --logcat-since TIME  Passed to 'adb logcat -T'. Default: '30 minutes ago'.
  --print              Print the full snapshot after writing it.
  -h, --help           Show this help.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial)
      SERIAL=${2:?missing serial}
      shift 2
      ;;
    --out-dir)
      OUT_DIR=${2:?missing output directory}
      shift 2
      ;;
    --logcat-since)
      LOGCAT_SINCE=${2:?missing logcat since value}
      shift 2
      ;;
    --print)
      PRINT_REPORT=1
      shift
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

require_adb() {
  if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found in PATH" >&2
    exit 1
  fi
}

resolve_serial() {
  if [ -n "$SERIAL" ]; then
    return
  fi

  mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
  case "${#devices[@]}" in
    0)
      echo "no connected adb device found" >&2
      echo "Run 'adb devices' and authorize the target device first." >&2
      exit 1
      ;;
    1)
      SERIAL=${devices[0]}
      ;;
    *)
      echo "multiple adb devices found; pass --serial" >&2
      printf '  %s\n' "${devices[@]}" >&2
      exit 1
      ;;
  esac
}

adb_target() {
  adb -s "$SERIAL" "$@"
}

append_command() {
  local title=$1
  shift
  {
    printf '\n## %s\n\n' "$title"
    printf '```text\n'
    "$@" 2>&1 || true
    printf '```\n'
  } >> "$OUT_FILE"
}

append_package_command() {
  local title=$1
  shift
  {
    printf '\n## %s\n\n' "$title"
    printf '```text\n'
    for pkg in "${PACKAGES[@]}"; do
      printf '\n### %s\n' "$pkg"
      "$@" "$pkg" 2>&1 || true
    done
    printf '```\n'
  } >> "$OUT_FILE"
}

append_filtered_shell() {
  local title=$1
  local command=$2
  local pattern=$3
  {
    printf '\n## %s\n\n' "$title"
    printf '```text\n'
    adb_target shell "$command" 2>&1 | grep -Ei "$pattern" || true
    printf '```\n'
  } >> "$OUT_FILE"
}

append_notification_snapshot() {
  {
    printf '\n## notification focused\n\n'
    printf '```text\n'
    adb_target shell dumpsys notification 2>&1 | awk '
      /NotificationRecord/ {
        keep = ($0 ~ /pkg=(com\.termux|com\.termux\.api|io\.github\.codex_cli_voice_android\.aecshim)/)
      }
      keep {
        print
      }
      /^    NotificationRecord/ && !/pkg=(com\.termux|com\.termux\.api|io\.github\.codex_cli_voice_android\.aecshim)/ {
        keep = 0
      }
    ' || true
    printf '```\n'
  } >> "$OUT_FILE"
}

require_adb
resolve_serial

mkdir -p "$OUT_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT_FILE="$OUT_DIR/${STAMP}-android-runtime-${SERIAL//[:\/]/_}.md"

device_model=$(adb_target shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)
android_release=$(adb_target shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || true)
android_sdk=$(adb_target shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)

{
  echo "# Android Runtime Snapshot"
  echo
  echo "Development-only CCVA/STTS/WWS runtime snapshot."
  echo
  echo "- Captured: $(date)"
  echo "- ADB serial: $SERIAL"
  echo "- Device model: ${device_model:-unknown}"
  echo "- Android release: ${android_release:-unknown}"
  echo "- Android SDK: ${android_sdk:-unknown}"
  echo "- Logcat since: $LOGCAT_SINCE"
  echo
  echo "Target packages:"
  for pkg in "${PACKAGES[@]}"; do
    echo "- $pkg"
  done
  echo
  echo "Notes:"
  echo
  echo "- Run this while WWS is armed, after a screen-off pass, or immediately"
  echo "  after a socket/audio/foreground-service issue."
  echo "- This is diagnostic evidence, not normal user setup."
} > "$OUT_FILE"

append_command "adb devices" adb devices

append_package_command "package paths" adb_target shell pm path
append_package_command "pids" adb_target shell pidof
append_package_command "standby buckets" adb_target shell am get-standby-bucket
append_package_command "appops" adb_target shell cmd appops get

append_filtered_shell "deviceidle focused" "dumpsys deviceidle" \
  'mScreenOn|mScreenLocked|mNetworkConnected|mCharging|mState=|mLightState=|mForceIdle|mDeviceIdle|mLightEnabled|mDeepEnabled|com\.termux|io\.github\.codex_cli_voice_android\.aecshim'
append_filtered_shell "power focused" "dumpsys power" \
  'mWakefulness|mIsPowered|mPlugType|mBatteryLevel|mWakeLockSummary|mHoldingWakeLock|mHoldingDisplay|mDeviceIdleMode|mLightDeviceIdleMode|mLowPowerStandbyActive|mScreen|mLastSleep|mLastWake|mDoze'
append_command "battery" adb_target shell dumpsys battery
append_command "activity services bridge" adb_target shell dumpsys activity services io.github.codex_cli_voice_android.aecshim
append_command "activity processes bridge" adb_target shell dumpsys activity processes io.github.codex_cli_voice_android.aecshim
append_filtered_shell "audio focused" "dumpsys audio" \
  'codex|aecshim|termux|stts|record|recorder|microphone|audio focus|wake|uid:10374|uid:10386|u0a374|u0a386'
append_filtered_shell "media audio policy focused" "dumpsys media.audio_policy" \
  'codex|aecshim|termux|stts|record|recorder|microphone|uid:10374|uid:10386|u0a374|u0a386'
append_filtered_shell "media audio flinger focused" "dumpsys media.audio_flinger" \
  'codex|aecshim|termux|stts|record|recorder|microphone|uid:10374|uid:10386|u0a374|u0a386'
append_notification_snapshot
append_command "logcat filtered" bash -c "adb -s '$SERIAL' logcat -d -T '$LOGCAT_SINCE' 2>&1 | grep -Ei 'codex|bridge|aec|stts|wake|speechrecognizer|audio|foreground|websocket|socket|termux|microphone|tts' || true"

echo "wrote $OUT_FILE"
if [ "$PRINT_REPORT" = "1" ]; then
  echo
  cat "$OUT_FILE"
fi
