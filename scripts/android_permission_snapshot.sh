#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$REPO_DIR/tmp/android-permission-snapshots"
SERIAL="${ANDROID_SERIAL:-}"
PRINT_REPORT=0

PACKAGES=(
  "com.termux"
  "com.termux.api"
  "io.github.codex_cli_voice_android.aecshim"
)

usage() {
  cat <<'USAGE'
Usage: scripts/android_permission_snapshot.sh [--serial ADB_SERIAL] [--out-dir DIR]

Captures a development-only Android permission/appops snapshot for the CCVA
Termux, Termux:API, and Codex Bridge packages. Requires adb access to the
target device.

Options:
  --serial ADB_SERIAL  Use a specific adb device serial. Defaults to
                       ANDROID_SERIAL, or the only connected device.
  --out-dir DIR        Output directory. Defaults to tmp/android-permission-snapshots.
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
    printf '\n### %s\n\n' "$title"
    printf '```text\n'
    "$@" 2>&1 || true
    printf '```\n'
  } >> "$OUT_FILE"
}

append_package_permissions() {
  local pkg=$1
  {
    printf '\n### requested permissions\n\n'
    printf '```text\n'
    adb_target shell dumpsys package "$pkg" 2>&1 | awk '
      /requested permissions:/ {flag=1; print; next}
      /install permissions:/ {flag=0}
      flag {print}
    ' || true
    printf '```\n'

    printf '\n### install permissions\n\n'
    printf '```text\n'
    adb_target shell dumpsys package "$pkg" 2>&1 | awk '
      /install permissions:/ {flag=1; print; next}
      /User [0-9]+:/ {flag=0}
      flag {print}
    ' | awk '!seen[$0]++' || true
    printf '```\n'

    printf '\n### runtime permissions\n\n'
    printf '```text\n'
    adb_target shell dumpsys package "$pkg" 2>&1 | sed -n '/runtime permissions:/,/^$/p' || true
    printf '```\n'

    printf '\n### granted=true permissions\n\n'
    printf '```text\n'
    adb_target shell dumpsys package "$pkg" 2>&1 | grep 'granted=true' | awk '!seen[$0]++' || true
    printf '```\n'
  } >> "$OUT_FILE"
}

require_adb
resolve_serial

mkdir -p "$OUT_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT_FILE="$OUT_DIR/${STAMP}-android-permissions-${SERIAL//[:\/]/_}.md"

device_model=$(adb_target shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)
android_release=$(adb_target shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || true)
android_sdk=$(adb_target shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)

{
  echo "# Android Permission Snapshot"
  echo
  echo "Development-only CCVA permission/appops snapshot."
  echo
  echo "- Captured: $(date)"
  echo "- ADB serial: $SERIAL"
  echo "- Device model: ${device_model:-unknown}"
  echo "- Android release: ${android_release:-unknown}"
  echo "- Android SDK: ${android_sdk:-unknown}"
  echo
  echo "Target packages:"
  for pkg in "${PACKAGES[@]}"; do
    echo "- $pkg"
  done
  echo
  echo "Notes:"
  echo
  echo "- This report is for development diagnostics, not normal user setup."
  echo "- Android settings UI screenshots remain useful because package/appops"
  echo "  output can omit user-facing permission labels."
} > "$OUT_FILE"

for pkg in "${PACKAGES[@]}"; do
  echo "capturing $pkg"
  {
    printf '\n## %s\n' "$pkg"
  } >> "$OUT_FILE"

  if ! adb_target shell pm path "$pkg" >/dev/null 2>&1; then
    {
      echo
      echo "Package not installed or not visible to adb."
    } >> "$OUT_FILE"
    continue
  fi

  append_package_permissions "$pkg"
  append_command "appops" adb_target shell cmd appops get "$pkg"
done

{
  printf '\n## deviceidle allowlist matches\n\n'
  printf '```text\n'
  for pkg in "${PACKAGES[@]}"; do
    printf '%s\n' "$pkg"
    adb_target shell dumpsys deviceidle 2>&1 | grep -F "$pkg" || true
  done
  printf '```\n'
} >> "$OUT_FILE"

echo "wrote $OUT_FILE"
if [ "$PRINT_REPORT" = "1" ]; then
  echo
  cat "$OUT_FILE"
fi
