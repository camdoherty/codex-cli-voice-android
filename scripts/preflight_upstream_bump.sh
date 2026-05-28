#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/preflight_upstream_bump.sh rust-v0.135.0 [--write-lock-patch]

Checks whether the Android patch set applies to a new upstream Codex tag. The
Cargo.lock patch is intentionally skipped, regenerated, and then verified.
EOF
}

codex_tag="${1:-}"
write_lock_patch="${2:-}"
if [[ -z "$codex_tag" || "$codex_tag" == "-h" || "$codex_tag" == "--help" ]]; then
    usage
    [[ -n "$codex_tag" ]] && exit 0
    exit 2
fi
if [[ "$codex_tag" != rust-v* ]]; then
    echo "Expected an upstream tag like rust-v0.135.0" >&2
    exit 2
fi
if [[ -n "$write_lock_patch" && "$write_lock_patch" != "--write-lock-patch" ]]; then
    usage >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PATCHES_DIR="$REPO_DIR/patches"
WORK_DIR="${WORK_DIR:-$REPO_DIR/../codex-build-${codex_tag}-preflight}"
lock_patch="$PATCHES_DIR/010-cargo-lock-${codex_tag}.patch"

git ls-remote --exit-code --tags https://github.com/openai/codex.git "refs/tags/$codex_tag" >/dev/null

if [[ ! -e "$WORK_DIR/.git" ]]; then
    git clone --depth=1 --branch "$codex_tag" https://github.com/openai/codex.git "$WORK_DIR"
else
    git -C "$WORK_DIR" fetch origin tag "$codex_tag"
    git -C "$WORK_DIR" checkout "$codex_tag"
    git -C "$WORK_DIR" reset --hard "$codex_tag"
    git -C "$WORK_DIR" clean -fd
fi

for patch in "$PATCHES_DIR"/*.patch; do
    case "$(basename "$patch")" in
        010-cargo-lock-*) continue ;;
    esac
    if git -C "$WORK_DIR" apply --check "$patch" 2>/dev/null; then
        git -C "$WORK_DIR" apply "$patch"
        echo "applied $(basename "$patch")"
    elif git -C "$WORK_DIR" apply --reverse --check "$patch" 2>/dev/null; then
        echo "already applied $(basename "$patch")"
    else
        echo "patch conflict: $(basename "$patch")" >&2
        exit 1
    fi
done

(
    cd "$WORK_DIR/codex-rs"
    cargo metadata --format-version 1 >/dev/null
    cargo metadata --locked --format-version 1 >/dev/null
)

if [[ "$write_lock_patch" == "--write-lock-patch" ]]; then
    rm -f "$PATCHES_DIR"/010-cargo-lock-*.patch
    git -C "$WORK_DIR" diff -- codex-rs/Cargo.lock > "$lock_patch"
    echo "wrote $lock_patch"
else
    echo "lock patch candidate: $lock_patch"
    echo "rerun with --write-lock-patch to replace the tracked lock patch"
fi

echo "upstream_bump_preflight=ok"
