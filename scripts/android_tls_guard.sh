#!/usr/bin/env bash
set -euo pipefail

die() {
    echo "android_tls_guard: $*" >&2
    exit 1
}

reject_verifier_strings() {
    if strings "$1" | grep -F \
        -e "rustls-platform-verifier" \
        -e "Expect rustls-platform-verifier" >/dev/null; then
        die "forbidden rustls platform verifier string found in $1"
    fi
}

mode="${1:-}"
subject="${2:-}"
[ -n "$subject" ] || die "usage: $0 {graph|binary|package} PATH"

case "$mode" in
    graph)
        [ -d "$subject" ] || die "missing Cargo workspace: $subject"
        if (
            cd "$subject"
            cargo tree --locked --target aarch64-linux-android --package codex-cli
        ) | grep -F "rustls-platform-verifier" >/dev/null; then
            die "forbidden rustls-platform-verifier dependency found in Android graph"
        fi
        ;;
    binary)
        [ -f "$subject" ] || die "missing binary: $subject"
        reject_verifier_strings "$subject"
        ;;
    package)
        [ -f "$subject" ] || die "missing package: $subject"
        member="./libexec/codex-cli-voice-android/codex.bin"
        tar -tzf "$subject" | grep -Fx "$member" >/dev/null ||
            die "missing packaged binary: $member"
        if tar -xOzf "$subject" "$member" | strings | grep -F \
            -e "rustls-platform-verifier" \
            -e "Expect rustls-platform-verifier" >/dev/null; then
            die "forbidden rustls platform verifier string found in $subject"
        fi
        ;;
    *)
        die "usage: $0 {graph|binary|package} PATH"
        ;;
esac

echo "android_tls_guard=ok mode=$mode"
