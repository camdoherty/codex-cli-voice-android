#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/ccat_device_ux_smoke.sh --adb-serial SERIAL --ssh-host HOST --target NAME [--capture-audio optional]

Runs a timestamped ADB/SSH-driven CCAT/STTS UX smoke harness.

Options:
  --adb-serial SERIAL      ADB serial for the Android device.
  --ssh-host HOST          SSH host/alias for Termux on the device.
  --target NAME            Target label, e.g. pixel6a.
  --capture-audio optional Capture optional devbox audio QA if pw-record source is available.
  -h, --help               Show help.
USAGE
}

die() {
  echo "ccat_device_ux_smoke: $*" >&2
  exit 1
}

ADB_SERIAL=""
SSH_HOST=""
TARGET=""
CAPTURE_AUDIO="0"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --adb-serial)
      ADB_SERIAL=${2:?missing serial}
      shift 2
      ;;
    --ssh-host)
      SSH_HOST=${2:?missing ssh host}
      shift 2
      ;;
    --target)
      TARGET=${2:?missing target}
      shift 2
      ;;
    --capture-audio)
      CAPTURE_AUDIO=${2:-optional}
      shift 2
      [[ "$CAPTURE_AUDIO" == "optional" ]] || die "--capture-audio currently accepts only 'optional'"
      CAPTURE_AUDIO="1"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ -n "$ADB_SERIAL" ]] || die "--adb-serial is required"
[[ -n "$SSH_HOST" ]] || die "--ssh-host is required"
[[ -n "$TARGET" ]] || die "--target is required"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="$REPO_DIR/tmp/ccat-device-ux-smoke/${STAMP}-${TARGET}"

mkdir -p "$RUN_DIR"/{adb,ssh,logs,shares,audio,screenshots,snapshots}

EVENTS="$RUN_DIR/events.jsonl"
SUMMARY="$RUN_DIR/summary.md"
RUN_JSON="$RUN_DIR/run.json"
: > "$EVENTS"

RESULT_PASS=0
RESULT_FAIL=0
RESULT_BLOCKED=0
RESULT_INCONCLUSIVE=0
CREATED_ITEMS=()
PREV_POINTER=""
PREV_MODE=""
PREV_WAS_WAKE="0"
FINAL_STATE="unknown"

adb_cmd() {
  adb -s "$ADB_SERIAL" "$@"
}

ssh_cmd() {
  ssh "$SSH_HOST" "$@"
}

json_escape() {
  python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'
}

event() {
  local test_id=$1
  local classification=$2
  local setup_state=$3
  local action=$4
  local expected=$5
  local observed=$6
  local result=$7
  local artifact=$8
  local suggested_fix=${9:-}

  python3 - "$EVENTS" "$test_id" "$classification" "$setup_state" "$action" "$expected" "$observed" "$result" "$artifact" "$suggested_fix" <<'PY'
import json, sys, time
path, test_id, classification, setup_state, action, expected, observed, result, artifact, fix = sys.argv[1:]
row = {
    "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    "test_id": test_id,
    "classification": classification,
    "setup_state": setup_state,
    "action": action,
    "expected": expected,
    "observed": observed,
    "result": result,
    "artifact": artifact,
    "suggested_fix_area": fix,
}
with open(path, "a", encoding="utf-8") as f:
    f.write(json.dumps(row, ensure_ascii=True) + "\n")
PY

  case "$result" in
    PASS) RESULT_PASS=$((RESULT_PASS + 1)) ;;
    FAIL) RESULT_FAIL=$((RESULT_FAIL + 1)) ;;
    BLOCKED) RESULT_BLOCKED=$((RESULT_BLOCKED + 1)) ;;
    INCONCLUSIVE) RESULT_INCONCLUSIVE=$((RESULT_INCONCLUSIVE + 1)) ;;
  esac
}

capture_ssh() {
  local name=$1
  shift
  ssh_cmd "$@" > "$RUN_DIR/ssh/$name.txt" 2>&1 || true
  echo "$RUN_DIR/ssh/$name.txt"
}

capture_adb() {
  local name=$1
  shift
  adb_cmd "$@" > "$RUN_DIR/adb/$name.txt" 2>&1 || true
  echo "$RUN_DIR/adb/$name.txt"
}

stts_status() {
  ssh_cmd 'stts status 2>&1 | head -n 20' 2>/dev/null || true
}

latest_log_path() {
  ssh_cmd 'cat ~/.local/state/codex-stts/last-session.txt 2>/dev/null || true' 2>/dev/null | tr -d '\r'
}

latest_log_tail() {
  local lines=${1:-120}
  ssh_cmd "last=\$(cat ~/.local/state/codex-stts/last-session.txt 2>/dev/null); tail -n $lines \"\$last\" 2>/dev/null || true" 2>/dev/null || true
}

tmux_snapshot() {
  ssh_cmd 'tmux ls 2>/dev/null || true; tmux list-panes -a -F "#{session_name}:#{window_index}.#{pane_index} #{pane_id} #{pane_current_command} active=#{pane_active}" 2>/dev/null || true' 2>/dev/null || true
}

count_ccva_sessions() {
  ssh_cmd 'tmux ls 2>/dev/null | grep -c "^ccva-stts:" || true' 2>/dev/null | tr -d '\r'
}

mode_now() {
  ssh_cmd 'cat ~/.local/state/codex-stts/session-mode.txt 2>/dev/null || true' 2>/dev/null | tr -d '\r'
}

stts_is_running() {
  stts_status | grep -F "running pid" >/dev/null
}

shell_quote() {
  python3 - "$1" <<'PY'
import shlex, sys
print(shlex.quote(sys.argv[1]))
PY
}

adb_share_text() {
  local activity=$1
  local payload=$2
  local out_file=$3
  local quoted
  quoted="$(shell_quote "$payload")"
  adb_cmd shell "am start -n io.github.codex_cli_voice_android.aecshim/.$activity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $quoted" > "$out_file" 2>&1 || true
}

run_runtime_failure_capture() {
  local test_id=$1
  capture_ssh "${test_id}-status" 'stts status; echo ---tmux---; tmux ls 2>/dev/null || true; tmux list-panes -a -F "#{session_name}:#{window_index}.#{pane_index} #{pane_id} #{pane_current_command} active=#{pane_active}" 2>/dev/null || true; echo ---last---; last=$(cat ~/.local/state/codex-stts/last-session.txt 2>/dev/null); echo "$last"; tail -n 220 "$last" 2>/dev/null || true; echo ---latest-share---; cat ~/.local/state/codex-stts/latest-share-manifest.txt 2>/dev/null || true'
  capture_adb "${test_id}-bridge-services" shell dumpsys activity services io.github.codex_cli_voice_android.aecshim
  capture_adb "${test_id}-notifications" shell dumpsys notification --noredact
  capture_adb "${test_id}-logcat" shell logcat -d -t 500
  "$REPO_DIR/scripts/android_runtime_snapshot.sh" --serial "$ADB_SERIAL" --out-dir "$RUN_DIR/snapshots" --logcat-since "30 minutes ago" > "$RUN_DIR/snapshots/${test_id}-runtime-command.txt" 2>&1 || true
}

wait_for_log() {
  local pattern=$1
  local seconds=${2:-20}
  local deadline=$((SECONDS + seconds))
  while (( SECONDS < deadline )); do
    if latest_log_tail 120 | grep -F "$pattern" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_mode() {
  local expected=$1
  local seconds=${2:-15}
  local deadline=$((SECONDS + seconds))
  while (( SECONDS < deadline )); do
    [[ "$(mode_now)" == "$expected" ]] && return 0
    sleep 1
  done
  return 1
}

wait_for_share_pointer_change() {
  local previous=$1
  local seconds=${2:-15}
  local deadline=$((SECONDS + seconds))
  local pointer
  while (( SECONDS < deadline )); do
    pointer="$(ssh_cmd 'cat ~/.local/state/codex-stts/latest-share-manifest.txt 2>/dev/null || true' | tr -d '\r')"
    if [[ -n "$pointer" && "$pointer" != "$previous" ]]; then
      printf '%s\n' "$pointer"
      return 0
    fi
    sleep 1
  done
  printf '%s\n' "$pointer"
  return 1
}

cleanup() {
  set +e
  for item in "${CREATED_ITEMS[@]:-}"; do
    [[ -n "$item" ]] || continue
    case "$item" in
      */codex_notes/inbox/*-android-share) ;;
      *)
        continue
        ;;
    esac
    ssh_cmd "rm -rf '$item'" >/dev/null 2>&1 || true
  done
  if [[ -n "$PREV_POINTER" ]]; then
    ssh_cmd "mkdir -p ~/.local/state/codex-stts; printf '%s\n' '$PREV_POINTER' > ~/.local/state/codex-stts/latest-share-manifest.txt" >/dev/null 2>&1 || true
  else
    ssh_cmd "rm -f ~/.local/state/codex-stts/latest-share-manifest.txt" >/dev/null 2>&1 || true
  fi
  if [[ "$PREV_WAS_WAKE" == "1" ]]; then
    ssh_cmd 'stts wake >/dev/null 2>&1 || true' >/dev/null 2>&1 || true
    FINAL_STATE="attempted_restore_wake"
  else
    FINAL_STATE="left_current_state"
  fi
}
trap cleanup EXIT

write_run_json() {
  python3 - "$RUN_JSON" "$RUN_DIR" "$ADB_SERIAL" "$SSH_HOST" "$TARGET" "$FINAL_STATE" "$RESULT_PASS" "$RESULT_FAIL" "$RESULT_BLOCKED" "$RESULT_INCONCLUSIVE" <<'PY'
import json, subprocess, sys, time
path, run_dir, adb_serial, ssh_host, target, final_state, passed, failed, blocked, inconclusive = sys.argv[1:]
def cmd(args):
    try:
        return subprocess.check_output(args, text=True).strip()
    except Exception:
        return ""
data = {
    "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    "run_dir": run_dir,
    "target": target,
    "adb_serial": adb_serial,
    "ssh_host": ssh_host,
    "repo_branch": cmd(["git", "branch", "--show-current"]),
    "repo_commit": cmd(["git", "rev-parse", "HEAD"]),
    "dirty_state": cmd(["git", "status", "--short"]),
    "final_state": final_state,
    "counts": {
        "PASS": int(passed),
        "FAIL": int(failed),
        "BLOCKED": int(blocked),
        "INCONCLUSIVE": int(inconclusive),
    },
}
with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
PY
}

write_summary() {
  write_run_json
  python3 - "$EVENTS" "$SUMMARY" "$RUN_JSON" <<'PY'
import json, sys
events_path, summary_path, run_json_path = sys.argv[1:]
run = json.load(open(run_json_path, encoding="utf-8"))
events = [json.loads(line) for line in open(events_path, encoding="utf-8") if line.strip()]
with open(summary_path, "w", encoding="utf-8") as f:
    f.write("# CCAT Device UX Smoke\n\n")
    f.write(f"- Target: {run['target']}\n")
    f.write(f"- ADB serial: {run['adb_serial']}\n")
    f.write(f"- SSH host: {run['ssh_host']}\n")
    f.write(f"- Branch: {run['repo_branch']}\n")
    f.write(f"- Commit: {run['repo_commit']}\n")
    f.write(f"- Run dir: {run['run_dir']}\n")
    f.write(f"- Final state: {run['final_state']}\n\n")
    f.write("## Counts\n\n")
    for key in ["PASS", "FAIL", "BLOCKED", "INCONCLUSIVE"]:
        f.write(f"- {key}: {run['counts'][key]}\n")
    f.write("\n## Test Table\n\n")
    f.write("| Test | Class | Result | Observed | Artifact |\n")
    f.write("|---|---|---:|---|---|\n")
    for ev in events:
        observed = ev["observed"].replace("\n", " ")[:160]
        f.write(f"| {ev['test_id']} | {ev['classification']} | {ev['result']} | {observed} | {ev['artifact']} |\n")
    blockers = [ev for ev in events if ev["result"] in {"FAIL", "BLOCKED"}]
    f.write("\n## Release Blockers\n\n")
    if blockers:
        for ev in blockers:
            f.write(f"- {ev['test_id']}: {ev['observed']} Fix area: {ev['suggested_fix_area'] or 'unknown'}\n")
    else:
        f.write("- None from automated harness.\n")
    f.write("\n## Non-Blocking Issues\n\n")
    inconclusive = [ev for ev in events if ev["result"] == "INCONCLUSIVE"]
    if inconclusive:
        for ev in inconclusive:
            f.write(f"- {ev['test_id']}: {ev['observed']}\n")
    else:
        f.write("- Physical UX checks still require user confirmation: audible TTS, real notification taps, real share sheet, Gemini/Assistant, locked/screen-off WWS.\n")
    f.write("\n## Minimal Manual Checklist\n\n")
    f.write("- Share a real URL/image via Android share sheet to Save to Inbox and Review Now.\n")
    f.write("- Tap notification Review and Wake Word buttons.\n")
    f.write("- Confirm TTS is audible and short.\n")
    f.write("- Say 'hey jarvis' then 'summarize that GitHub repo' after sharing a repo link.\n")
    f.write("- Confirm no duplicate Termux/tmux sessions are visible.\n")
PY
}

echo "run_dir=$RUN_DIR"

repo_branch="$(git -C "$REPO_DIR" branch --show-current)"
repo_commit="$(git -C "$REPO_DIR" rev-parse HEAD)"
git -C "$REPO_DIR" status --short > "$RUN_DIR/logs/git-status.txt"

if ! adb_cmd get-state > "$RUN_DIR/adb/get-state.txt" 2>&1; then
  event "preflight-adb" "diagnostic-only" "initial" "adb get-state" "device reachable" "ADB failed; see adb/get-state.txt" "BLOCKED" "adb/get-state.txt" "setup/permission"
  write_summary
  exit 1
fi
event "preflight-adb" "diagnostic-only" "initial" "adb get-state" "device reachable" "$(cat "$RUN_DIR/adb/get-state.txt")" "PASS" "adb/get-state.txt" ""

if ! ssh_cmd 'echo ssh-ok; whoami; hostname' > "$RUN_DIR/ssh/ssh-preflight.txt" 2>&1; then
  event "preflight-ssh" "diagnostic-only" "initial" "ssh echo/whoami/hostname" "ssh reachable" "SSH failed; see ssh/ssh-preflight.txt" "BLOCKED" "ssh/ssh-preflight.txt" "setup/permission"
  write_summary
  exit 1
fi
event "preflight-ssh" "diagnostic-only" "initial" "ssh echo/whoami/hostname" "ssh reachable" "$(tr '\n' ' ' < "$RUN_DIR/ssh/ssh-preflight.txt")" "PASS" "ssh/ssh-preflight.txt" ""

PREV_POINTER="$(ssh_cmd 'cat ~/.local/state/codex-stts/latest-share-manifest.txt 2>/dev/null || true' | tr -d '\r')"
PREV_MODE="$(mode_now)"
[[ "$PREV_MODE" == "wake" ]] && PREV_WAS_WAKE="1"

capture_adb "package-bridge" shell dumpsys package io.github.codex_cli_voice_android.aecshim >/dev/null
capture_adb "bridge-services-initial" shell dumpsys activity services io.github.codex_cli_voice_android.aecshim >/dev/null
capture_ssh "stts-initial" 'stts status; echo ---tmux---; tmux ls 2>/dev/null || true; tmux list-panes -a -F "#{session_name}:#{window_index}.#{pane_index} #{pane_id} #{pane_current_command} active=#{pane_active}" 2>/dev/null || true; echo ---latest-share---; cat ~/.local/state/codex-stts/latest-share-manifest.txt 2>/dev/null || true' >/dev/null

"$REPO_DIR/scripts/android_permission_snapshot.sh" --serial "$ADB_SERIAL" --out-dir "$RUN_DIR/snapshots" > "$RUN_DIR/snapshots/permission-command.txt" 2>&1 || true
"$REPO_DIR/scripts/android_runtime_snapshot.sh" --serial "$ADB_SERIAL" --out-dir "$RUN_DIR/snapshots" --logcat-since "30 minutes ago" > "$RUN_DIR/snapshots/runtime-command.txt" 2>&1 || true

local_hash="$(sha256sum "$REPO_DIR/support/termux-skills/stts/scripts/stts_loop.py" | awk '{print $1}')"
device_hash="$(ssh_cmd 'sha256sum ~/.codex/skills/stts/scripts/stts_loop.py 2>/dev/null | awk "{print \$1}"' | tr -d '\r')"
if [[ "$local_hash" == "$device_hash" ]]; then
  event "preflight-stts-hash" "diagnostic-only" "initial" "sha256 local/device stts_loop.py" "hashes match" "$local_hash" "PASS" "ssh/stts-initial.txt" ""
else
  event "preflight-stts-hash" "diagnostic-only" "initial" "sha256 local/device stts_loop.py" "hashes match" "local=$local_hash device=$device_hash" "FAIL" "ssh/stts-initial.txt" "deployment"
fi

# A. Bridge launcher should open UI only and not auto-arm WWS from stopped state.
ssh_cmd 'stts stop >/dev/null 2>&1 || true' >/dev/null 2>&1 || true
sleep 2
adb_cmd shell am start -n io.github.codex_cli_voice_android.aecshim/.MainActivity > "$RUN_DIR/adb/A-bridge-launch.txt" 2>&1 || true
sleep 2
A_status="$(stts_status)"
A_tmux_count="$(count_ccva_sessions)"
capture_adb "A-bridge-services" shell dumpsys activity services io.github.codex_cli_voice_android.aecshim >/dev/null
capture_ssh "A-stts-status" 'stts status; tmux ls 2>/dev/null || true; tmux list-panes -a -F "#{session_name}:#{window_index}.#{pane_index} #{pane_id} #{pane_current_command} active=#{pane_active}" 2>/dev/null || true' >/dev/null
if echo "$A_status" | grep -F "running pid" >/dev/null && echo "$A_status" | grep -F "mode wake" >/dev/null; then
  event "A-bridge-launcher" "ADB-approximated" "stopped STTS" "launch MainActivity" "Bridge starts without auto-arming WWS" "$A_status" "FAIL" "ssh/A-stts-status.txt" "Bridge launcher/WWS separation"
  run_runtime_failure_capture "A-bridge-launcher"
elif [[ "$A_tmux_count" == "0" || "$A_tmux_count" == "1" ]]; then
  event "A-bridge-launcher" "ADB-approximated" "stopped STTS" "launch MainActivity" "Bridge starts without auto-arming WWS" "$A_status" "PASS" "ssh/A-stts-status.txt" ""
else
  event "A-bridge-launcher" "ADB-approximated" "stopped STTS" "launch MainActivity" "No duplicate tmux sessions" "ccva-stts sessions=$A_tmux_count" "FAIL" "ssh/A-stts-status.txt" "tmux/session duplication"
fi

# B. Wake Word launcher.
adb_cmd shell am start -n io.github.codex_cli_voice_android.aecshim/.WakeWordActivity > "$RUN_DIR/adb/B-wake-launch.txt" 2>&1 || true
wait_for_mode wake 18 || true
sleep 2
B_status="$(stts_status)"
B_log="$(latest_log_tail 80)"
capture_ssh "B-stts-status" 'stts status; last=$(cat ~/.local/state/codex-stts/last-session.txt 2>/dev/null); tail -n 100 "$last" 2>/dev/null || true; tmux ls 2>/dev/null || true; tmux list-panes -a -F "#{session_name}:#{window_index}.#{pane_index} #{pane_id} #{pane_current_command} active=#{pane_active}" 2>/dev/null || true' >/dev/null
if echo "$B_status" | grep -F "mode wake" >/dev/null && echo "$B_log" | grep -F "wake word armed" >/dev/null && [[ "$(count_ccva_sessions)" == "1" ]]; then
  event "B-wake-launcher" "ADB-approximated" "Bridge available" "launch WakeWordActivity" "WWS armed, one tmux session" "$B_status" "PASS" "ssh/B-stts-status.txt" ""
else
  event "B-wake-launcher" "ADB-approximated" "Bridge available" "launch WakeWordActivity" "WWS armed, one tmux session" "$B_status" "FAIL" "ssh/B-stts-status.txt" "WWS arm/re-arm"
  run_runtime_failure_capture "B-wake-launcher"
fi

# C. Save to Inbox.
C_payload="CCAT UX smoke $STAMP save https://example.com/ccat-ux-smoke-$STAMP"
C_before_mode="$(mode_now)"
C_before_running="0"
stts_is_running && C_before_running="1"
adb_share_text "ShareSaveActivity" "$C_payload" "$RUN_DIR/adb/C-share-save.txt"
sleep 3
C_pointer="$(ssh_cmd 'cat ~/.local/state/codex-stts/latest-share-manifest.txt 2>/dev/null || true' | tr -d '\r')"
C_item="$(dirname "$C_pointer" 2>/dev/null || true)"
[[ -n "$C_item" ]] && CREATED_ITEMS+=("$C_item")
capture_ssh "C-share-item" "echo pointer='$C_pointer'; test -f '$C_pointer' && cat '$C_pointer'; echo ---payload---; test -n '$C_item' && cat '$C_item/payload.md' 2>/dev/null || true; stts status; tmux ls 2>/dev/null || true" >/dev/null
capture_adb "C-notifications" shell dumpsys notification --noredact >/dev/null
C_mode="$(mode_now)"
if [[ -n "$C_pointer" ]] && ssh_cmd "grep -F 'CCAT UX smoke $STAMP save' '$(dirname "$C_pointer")/payload.md' >/dev/null 2>&1" && grep -F "Inbox received shared item" "$RUN_DIR/adb/C-notifications.txt" >/dev/null && { [[ "$C_before_running" == "0" ]] || [[ "$C_mode" == "$C_before_mode" ]]; }; then
  event "C-save-to-inbox" "UX-equivalent" "prior_mode=${C_before_mode:-none}" "synthetic ACTION_SEND ShareSaveActivity" "Item saved, pointer updated, notification shown, WWS state preserved" "pointer=$C_pointer before=$C_before_mode after=$C_mode" "PASS" "ssh/C-share-item.txt" ""
else
  event "C-save-to-inbox" "UX-equivalent" "prior_mode=${C_before_mode:-none}" "synthetic ACTION_SEND ShareSaveActivity" "Item saved, pointer updated, notification shown, WWS state preserved" "pointer=$C_pointer before=$C_before_mode after=$C_mode" "FAIL" "ssh/C-share-item.txt" "Android share intake/notification"
  run_runtime_failure_capture "C-save-to-inbox"
fi

# D. Review Now from current state.
D_payload="CCAT UX smoke $STAMP review https://example.com/ccat-review-$STAMP"
D_previous_pointer="$(ssh_cmd 'cat ~/.local/state/codex-stts/latest-share-manifest.txt 2>/dev/null || true' | tr -d '\r')"
adb_share_text "ShareReviewActivity" "$D_payload" "$RUN_DIR/adb/D-share-review.txt"
D_pointer="$(wait_for_share_pointer_change "$D_previous_pointer" 15 || true)"
D_item="$(dirname "$D_pointer" 2>/dev/null || true)"
[[ -n "$D_item" ]] && CREATED_ITEMS+=("$D_item")
[[ -n "$D_pointer" ]] && wait_for_log "$D_pointer" 20 || true
wait_for_log "tts_complete" 75 || true
D_log="$(latest_log_tail 140)"
capture_ssh "D-review-log" 'stts status; last=$(cat ~/.local/state/codex-stts/last-session.txt 2>/dev/null); tail -n 180 "$last" 2>/dev/null || true; tmux ls 2>/dev/null || true; tmux list-panes -a -F "#{session_name}:#{window_index}.#{pane_index} #{pane_id} #{pane_current_command} active=#{pane_active}" 2>/dev/null || true' >/dev/null
if echo "$D_log" | grep -F "reviewing shared item" >/dev/null && echo "$D_log" | grep -E "tts(_complete|: tts complete|: tts dispatched)" >/dev/null && [[ "$(count_ccva_sessions)" == "1" ]]; then
  event "D-review-now" "UX-equivalent" "WWS active or idle" "synthetic ACTION_SEND ShareReviewActivity" "Review starts, short spoken response, TTS logged, no duplicate tmux" "$(echo "$D_log" | tail -n 10)" "PASS" "ssh/D-review-log.txt" ""
else
  event "D-review-now" "UX-equivalent" "WWS active or idle" "synthetic ACTION_SEND ShareReviewActivity" "Review starts, short spoken response, TTS logged, no duplicate tmux" "$(echo "$D_log" | tail -n 20)" "FAIL" "ssh/D-review-log.txt" "Review Now/TTS/tmux"
  run_runtime_failure_capture "D-review-now"
fi

# E. Review Now while WWS active.
adb_cmd shell am start -n io.github.codex_cli_voice_android.aecshim/.WakeWordActivity > "$RUN_DIR/adb/E-wake-launch.txt" 2>&1 || true
if ! wait_for_mode wake 18; then
  E_setup_status="$(stts_status)"
  capture_ssh "E-wake-setup-failed" 'stts status; last=$(cat ~/.local/state/codex-stts/last-session.txt 2>/dev/null); tail -n 120 "$last" 2>/dev/null || true; tmux ls 2>/dev/null || true' >/dev/null
  event "E-review-while-wake" "UX-equivalent" "WWS setup" "launch WakeWordActivity" "WWS arms before share review lifecycle test" "$E_setup_status" "FAIL" "ssh/E-wake-setup-failed.txt" "WWS arm/re-arm"
  run_runtime_failure_capture "E-review-while-wake"
else
E_payload="CCAT UX smoke $STAMP wake review https://github.com/camdoherty/fluxfce-simplified"
E_previous_pointer="$(ssh_cmd 'cat ~/.local/state/codex-stts/latest-share-manifest.txt 2>/dev/null || true' | tr -d '\r')"
adb_share_text "ShareReviewActivity" "$E_payload" "$RUN_DIR/adb/E-share-review-wake.txt"
E_pointer="$(wait_for_share_pointer_change "$E_previous_pointer" 15 || true)"
[[ -n "$E_pointer" ]] && wait_for_log "$E_pointer" 20 || true
wait_for_log "tts_complete" 75 || true
wait_for_log "returning to wake word" 60 || true
wait_for_mode wake 30 || true
E_item="$(dirname "$E_pointer" 2>/dev/null || true)"
[[ -n "$E_item" ]] && CREATED_ITEMS+=("$E_item")
E_log="$(latest_log_tail 180)"
E_status="$(stts_status)"
capture_ssh "E-review-wake-log" 'stts status; last=$(cat ~/.local/state/codex-stts/last-session.txt 2>/dev/null); tail -n 220 "$last" 2>/dev/null || true; tmux ls 2>/dev/null || true; tmux list-panes -a -F "#{session_name}:#{window_index}.#{pane_index} #{pane_id} #{pane_current_command} active=#{pane_active}" 2>/dev/null || true' >/dev/null
if echo "$E_log" | grep -F "returning to wake word" >/dev/null && echo "$E_log" | grep -F "wake word armed" >/dev/null && echo "$E_status" | grep -F "mode wake" >/dev/null && ! echo "$E_log" | grep -F "socket closed" >/dev/null && [[ "$(count_ccva_sessions)" == "1" ]]; then
  event "E-review-while-wake" "UX-equivalent" "WWS active" "synthetic ACTION_SEND ShareReviewActivity" "Review runs, TTS completes, returns to WWS, no socket disconnect" "$E_status" "PASS" "ssh/E-review-wake-log.txt" ""
else
  event "E-review-while-wake" "UX-equivalent" "WWS active" "synthetic ACTION_SEND ShareReviewActivity" "Review runs, TTS completes, returns to WWS, no socket disconnect" "$(echo "$E_log" | tail -n 30)" "FAIL" "ssh/E-review-wake-log.txt" "WWS re-arm/share lifecycle"
  run_runtime_failure_capture "E-review-while-wake"
fi
fi

# F. Latest Share Follow-Up Context via FIFO diagnostic.
F_seed_payload="CCAT UX smoke $STAMP github repo https://github.com/camdoherty/fluxfce-simplified"
adb_share_text "ShareSaveActivity" "$F_seed_payload" "$RUN_DIR/adb/F-share-save-context.txt"
sleep 3
F_pointer="$(ssh_cmd 'cat ~/.local/state/codex-stts/latest-share-manifest.txt 2>/dev/null || true' | tr -d '\r')"
F_item="$(dirname "$F_pointer" 2>/dev/null || true)"
[[ -n "$F_item" ]] && CREATED_ITEMS+=("$F_item")
ssh_cmd 'stts stop >/dev/null 2>&1 || true; stts talk summarize that GitHub repo' > "$RUN_DIR/ssh/F-talk-command.txt" 2>&1 || true
sleep 35
F_log="$(latest_log_tail 220)"
capture_ssh "F-follow-up-context" 'stts status; last=$(cat ~/.local/state/codex-stts/last-session.txt 2>/dev/null); tail -n 260 "$last" 2>/dev/null || true' >/dev/null
if echo "$F_log" | grep -F "user: summarize that GitHub repo" >/dev/null && ! echo "$F_log" | grep -Ei "socket closed|socket failed|which (github )?repo|which link|which item" >/dev/null && echo "$F_log" | grep -Ei "fluxfce|github|repository|repo" >/dev/null; then
  event "F-latest-share-follow-up" "diagnostic-only" "latest share known; WWS active" "queue talk summarize that GitHub repo via FIFO" "Codex uses latest shared repo context, not ask which repo" "$(echo "$F_log" | tail -n 35)" "PASS" "ssh/F-follow-up-context.txt" ""
else
  event "F-latest-share-follow-up" "diagnostic-only" "latest share known; WWS active" "queue talk summarize that GitHub repo via FIFO" "Codex uses latest shared repo context, not ask which repo" "$(echo "$F_log" | tail -n 45)" "FAIL" "ssh/F-follow-up-context.txt" "latest-share context"
  run_runtime_failure_capture "F-latest-share-follow-up"
fi

# G. Stop behavior.
ssh_cmd 'stts stop' > "$RUN_DIR/ssh/G-stop.txt" 2>&1 || true
sleep 2
G_status="$(stts_status)"
capture_ssh "G-stop-status" 'stts status; tmux ls 2>/dev/null || true; tmux list-panes -a -F "#{session_name}:#{window_index}.#{pane_index} #{pane_id} #{pane_current_command} active=#{pane_active}" 2>/dev/null || true' >/dev/null
if echo "$G_status" | grep -F "not running" >/dev/null; then
  event "G-stop-behavior" "diagnostic-only" "STTS running" "stts stop" "STTS stops cleanly and tmux/FIFO are clean" "$G_status" "PASS" "ssh/G-stop-status.txt" ""
else
  event "G-stop-behavior" "diagnostic-only" "STTS running" "stts stop" "STTS stops cleanly and tmux/FIFO are clean" "$G_status" "FAIL" "ssh/G-stop-status.txt" "stop behavior"
  run_runtime_failure_capture "G-stop-behavior"
fi

if [[ "$CAPTURE_AUDIO" == "1" ]]; then
  if command -v pw-record >/dev/null 2>&1; then
    event "audio-capture-capability" "diagnostic-only" "devbox" "check pw-record availability" "optional capture available" "pw-record found; live capture requires wrapping a specific audio action" "INCONCLUSIVE" "audio/" "audio QA harness extension"
  else
    event "audio-capture-capability" "diagnostic-only" "devbox" "check pw-record availability" "optional capture available" "pw-record not found" "INCONCLUSIVE" "audio/" "audio setup"
  fi
fi

write_summary
echo "summary=$SUMMARY"
echo "events=$EVENTS"
echo "run_json=$RUN_JSON"
