#!/data/data/com.termux/files/usr/bin/sh
set -eu

context_file="${CODEX_TMUX_CONTEXT:-/data/data/com.termux/files/usr/tmp/codex-tmux-pane-context.txt}"
ensure_script="${CODEX_TMUX_ENSURE:-/data/data/com.termux/files/home/scripts/tmux-codex-shared-pane}"

die() {
  printf '%s\n' "$*" >&2
  exit 1
}

first_value() {
  key="$1"
  sed -n "s/^${key}=//p" "$context_file" | sed -n '1p'
}

refresh_context() {
  old_socket="${socket:-}"
  old_target="$(first_value target 2>/dev/null || true)"
  target_window=""

  if [ -n "$old_target" ]; then
    target_window="$(printf '%s\n' "$old_target" | sed 's/\.[^.]*$//')"
  fi

  if [ -n "$old_socket" ] && [ -n "$target_window" ]; then
    CODEX_TMUX_SOCKET="$old_socket" "$ensure_script" "$target_window" >/dev/null 2>&1 || true
  elif [ -n "$old_socket" ]; then
    CODEX_TMUX_SOCKET="$old_socket" "$ensure_script" >/dev/null 2>&1 || true
  else
    "$ensure_script" >/dev/null 2>&1 || true
  fi
}

read_context() {
  if [ ! -r "$context_file" ] && [ -x "$ensure_script" ]; then
    socket=""
    refresh_context
  fi

  [ -r "$context_file" ] || die "tmux context file not readable: $context_file"

  first_line="$(sed -n '1p' "$context_file")"
  socket="$(first_value socket)"
  pane="$(first_value pane)"
  epoch="$(first_value epoch)"

  if [ -z "$socket" ] || [ -z "$pane" ]; then
    socket="$(printf '%s\n' "$first_line" | sed 's/,.*//')"
    pane="$(printf '%s\n' "$first_line" | awk '{print $NF}')"
  fi

  [ -n "$socket" ] || die "missing tmux socket in $context_file"
  [ -n "$pane" ] || die "missing tmux pane in $context_file"
  if [ ! -S "$socket" ] && [ -x "$ensure_script" ]; then
    refresh_context
    socket="$(first_value socket)"
    pane="$(first_value pane)"
  fi

  [ -S "$socket" ] || die "tmux socket does not exist: $socket"

  pane_dead="$(tmux -S "$socket" display-message -p -t "$pane" '#{pane_dead}' 2>/dev/null || printf 'missing')"
  if { [ "$pane_dead" = "missing" ] || [ "$pane_dead" = "1" ]; } && [ -x "$ensure_script" ]; then
    refresh_context
    socket="$(first_value socket)"
    pane="$(first_value pane)"
  fi

  pane_dead="$(tmux -S "$socket" display-message -p -t "$pane" '#{pane_dead}' 2>/dev/null || printf 'missing')"
  [ "$pane_dead" = "0" ] || die "tmux pane is not available: $pane"

  export context_file socket pane epoch
}

case "${1:-capture}" in
  path)
    printf '%s\n' "$context_file"
    ;;
  info)
    read_context
    tmux -S "$socket" display-message -p -t "$pane" 'socket=#{socket_path}
pane=#{pane_id}
target=#{session_name}:#{window_index}.#{pane_index}
command=#{pane_current_command}
pane_dead=#{pane_dead}
pane_in_mode=#{pane_in_mode}'
    ;;
  capture)
    read_context
    lines="${2:--80}"
    tmux -S "$socket" capture-pane -p -t "$pane" -S "$lines"
    ;;
  send)
    read_context
    shift
    [ "$#" -gt 0 ] || die "usage: $0 send <non-secret command>"
    tmux -S "$socket" send-keys -t "$pane" -- "$*" Enter
    ;;
  *)
    die "usage: $0 [path|info|capture [start-line]|send <command>]"
    ;;
esac
