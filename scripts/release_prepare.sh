#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/release_prepare.sh rust-v0.137.0 [options]

Prepares a CCVA release branch for a new upstream Codex tag.

Options:
  --iteration N        CCVA iteration number. Default: 1.
  --branch NAME        Branch to create/switch to. Default: release/vX.Y.Z-ccva.N.
  --no-branch          Do not create or switch branches.
  --skip-preflight     Do not run upstream patch/lock preflight.
  --allow-dirty        Allow a dirty worktree before preparing.
  -h, --help           Show this help.

This updates local version references only. It does not build, publish, or
change releases/stable.json.
EOF
}

die() {
    echo "release_prepare: $*" >&2
    exit 1
}

codex_tag="${1:-}"
if [[ -z "$codex_tag" || "$codex_tag" == "-h" || "$codex_tag" == "--help" ]]; then
    usage
    [[ -n "$codex_tag" ]] && exit 0
    exit 2
fi
shift

[[ "$codex_tag" == rust-v* ]] || die "expected upstream tag like rust-v0.137.0"

iteration="1"
branch=""
create_branch="1"
skip_preflight="0"
allow_dirty="0"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --iteration)
            [[ $# -ge 2 ]] || die "--iteration requires a value"
            iteration="$2"
            shift 2
            ;;
        --branch)
            [[ $# -ge 2 ]] || die "--branch requires a value"
            branch="$2"
            shift 2
            ;;
        --no-branch)
            create_branch="0"
            shift
            ;;
        --skip-preflight)
            skip_preflight="1"
            shift
            ;;
        --allow-dirty)
            allow_dirty="1"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

[[ "$iteration" =~ ^[0-9]+$ ]] || die "--iteration must be numeric"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
semver="${codex_tag#rust-v}"
release_tag="v${semver}-ccva.${iteration}"
package_version="${codex_tag}-ccva.${iteration}"
branch="${branch:-release/${release_tag}}"

cd "$REPO_DIR"

if [[ "$allow_dirty" != "1" ]] && [[ -n "$(git status --porcelain)" ]]; then
    die "worktree is dirty; commit, stash, or rerun with --allow-dirty"
fi

current_default="$(sed -n 's/^CODEX_TAG="${CODEX_TAG:-\(rust-v[^}]*\)}"/\1/p' build.sh)"
[[ -n "$current_default" ]] || die "could not read default CODEX_TAG from build.sh"
current_semver="${current_default#rust-v}"
current_release="$(python3 - <<'PY'
import json
from pathlib import Path
p = Path("releases/stable.json")
if p.exists():
    print(json.loads(p.read_text()).get("version", ""))
PY
)"
current_release="${current_release:-v${current_semver}-ccva.1}"
current_iteration="${current_release##*-ccva.}"
current_package="${current_default}-ccva.${current_iteration}"

if [[ "$create_branch" == "1" ]]; then
    if git rev-parse --verify --quiet "$branch" >/dev/null; then
        git switch "$branch"
    else
        git switch -c "$branch"
    fi
fi

if [[ "$skip_preflight" != "1" ]]; then
    "$REPO_DIR/scripts/preflight_upstream_bump.sh" "$codex_tag" --write-lock-patch
fi

python3 - "$current_default" "$codex_tag" "$current_release" "$release_tag" "$current_package" "$package_version" <<'PY'
import sys
from pathlib import Path

old_tag, new_tag, old_release, new_release, old_package, new_package = sys.argv[1:]

replacements = {
    old_tag: new_tag,
    old_release: new_release,
    old_package: new_package,
}

files = [
    Path("build.sh"),
    Path("BUILD.md"),
    Path("README.md"),
    Path("AGENT_BUILD_CCVA.md"),
    Path("DEPLOY.md"),
]

for path in files:
    text = path.read_text()
    updated = text
    for old, new in replacements.items():
        updated = updated.replace(old, new)
    if updated != text:
        path.write_text(updated)
PY

cat <<EOF
release_prepare=ok
upstream_codex=$codex_tag
release_tag=$release_tag
package_version=$package_version
branch=$(git branch --show-current)

Next:
  scripts/release_build.sh $release_tag
EOF
