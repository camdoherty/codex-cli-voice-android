#!/usr/bin/env bash
set -euo pipefail

die() {
    echo "android_code_mode_guard: $*" >&2
    exit 1
}

codex_rs="${1:-}"
[ -n "$codex_rs" ] || die "usage: $0 /path/to/codex-rs"

tools_mod="$codex_rs/core/src/tools/mod.rs"
[ -f "$tools_mod" ] || die "missing tool registry source: $tools_mod"

rg -Uq '#\[cfg\(target_os = "android"\)\]\nfn effective_tool_mode\(_turn_context: &TurnContext\) -> ToolMode \{\n    ToolMode::Direct\n\}' \
    "$tools_mod" || die "Android must force direct tool mode"

rg -Uq '#\[cfg\(not\(target_os = "android"\)\)\]\nfn effective_tool_mode\(turn_context: &TurnContext\) -> ToolMode \{' \
    "$tools_mod" || die "non-Android tool mode implementation is not isolated"

echo "android_code_mode_guard=ok"
