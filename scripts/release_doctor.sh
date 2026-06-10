#!/usr/bin/env bash
set -euo pipefail

release_tag="${1:-}"
if [[ -z "$release_tag" || "$release_tag" == "-h" || "$release_tag" == "--help" ]]; then
    cat <<'EOF'
Usage: scripts/release_doctor.sh v0.139.0-ccva.1
EOF
    [[ -n "$release_tag" ]] && exit 0
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
dist_dir="$REPO_DIR/dist/$release_tag"

[ -d "$dist_dir" ] || { echo "Missing dist dir: $dist_dir" >&2; exit 1; }

case "$release_tag" in
    v[0-9]*.[0-9]*.[0-9]*-ccva.[0-9]*) ;;
    *) echo "Invalid release tag: $release_tag" >&2; exit 1 ;;
esac

upstream_semver="${release_tag#v}"
upstream_semver="${upstream_semver%%-ccva.*}"
ccva_iteration="${release_tag##*-ccva.}"
codex_tag="rust-v${upstream_semver}"
package_version="${codex_tag}-ccva.${ccva_iteration}"
cli_asset="codex-cli-voice-android-${package_version}.tar.gz"
shim_asset="codex-aec-shim-${release_tag}-debug.apk"
manifest="$dist_dir/${release_tag}.json"
metadata="$dist_dir/$cli_asset.metadata"

required=(
    "$cli_asset"
    "$cli_asset.sha256"
    "$cli_asset.metadata"
    "$shim_asset"
    "$shim_asset.sha256"
    "${release_tag}.json"
)
for file in "${required[@]}"; do
    [ -f "$dist_dir/$file" ] || { echo "Missing release file: $file" >&2; exit 1; }
done

(
    cd "$dist_dir"
    sha256sum -c "$cli_asset.sha256"
    sha256sum -c "$shim_asset.sha256"
    tar -tzf "$cli_asset" >/dev/null
)

"$REPO_DIR/scripts/android_tls_guard.sh" package "$dist_dir/$cli_asset"

grep -q "\"version\": \"$release_tag\"" "$manifest" || { echo "Manifest version mismatch" >&2; exit 1; }
grep -q "\"upstream_codex\": \"$codex_tag\"" "$manifest" || { echo "Manifest upstream mismatch" >&2; exit 1; }
grep -q "\"cli_tarball\": \"$cli_asset\"" "$manifest" || { echo "Manifest CLI asset mismatch" >&2; exit 1; }
grep -q "\"shim_apk\": \"$shim_asset\"" "$manifest" || { echo "Manifest shim asset mismatch" >&2; exit 1; }

metadata_value() {
    sed -n "s/^$1=//p" "$metadata" | sed -n '1p'
}

current_head="$(git -C "$REPO_DIR" rev-parse HEAD)"
ccva_source_commit="$(metadata_value ccva_source_commit)"
[ -n "$ccva_source_commit" ] || {
    echo "Missing ccva_source_commit in metadata; rebuild artifacts with current scripts" >&2
    exit 1
}
git -C "$REPO_DIR" merge-base --is-ancestor "$ccva_source_commit" "$current_head" || {
    echo "Artifact source commit is not an ancestor of current HEAD: $ccva_source_commit" >&2
    exit 1
}
if ! git -C "$REPO_DIR" diff --quiet "$ccva_source_commit"..HEAD -- . ':(exclude)releases/*.json'; then
    echo "Stale release artifact: repo changed after ccva_source_commit=$ccva_source_commit" >&2
    echo "Rebuild after committing these changes:" >&2
    git -C "$REPO_DIR" diff --name-only "$ccva_source_commit"..HEAD -- . ':(exclude)releases/*.json' >&2
    exit 1
fi
grep -q "\"ccva_source_commit\": \"$ccva_source_commit\"" "$manifest" || {
    echo "Manifest ccva_source_commit mismatch or missing" >&2
    exit 1
}

dirty="$(
    git -C "$REPO_DIR" status --porcelain --untracked-files=all -- \
        . ':(exclude)dist/**' ':(exclude)tmp/**'
)"
if [[ -n "$dirty" ]]; then
    echo "Release source worktree is dirty; artifacts are not publish-ready:" >&2
    printf '%s\n' "$dirty" >&2
    echo "Commit the intended changes and rebuild the candidate." >&2
    exit 1
fi

if git -C "$REPO_DIR" ls-files | grep -E '\.(apk|aab|tar\.gz|metadata|sha256)$' >/dev/null; then
    echo "Built artifacts are tracked by git" >&2
    git -C "$REPO_DIR" ls-files | grep -E '\.(apk|aab|tar\.gz|metadata|sha256)$' >&2
    exit 1
fi

if find "$REPO_DIR" \
    -path "$REPO_DIR/.git" -prune -o \
    -path "$REPO_DIR/android-toolchain" -prune -o \
    -path "$REPO_DIR/android-aec-shim/.gradle" -prune -o \
    -path "$REPO_DIR/android-aec-shim/build" -prune -o \
    -path "$REPO_DIR/android-aec-shim/app/build" -prune -o \
    -path "$REPO_DIR/dist" -prune -o \
    -path "$REPO_DIR/tmp" -prune -o \
    \( -name '*.pyc' -o -name __pycache__ \) -print | grep .; then
    echo "Generated Python files found" >&2
    exit 1
fi

if rg -n '(/home/[c]ad|100\.[6]4\.|100\.[9]7\.|192\.[1]68\.|[m]intpad|sk-[A-Za-z0-9_-]{20,})' \
    "$REPO_DIR" \
    --glob '!dist/**' \
    --glob '!android-toolchain/**' \
    --glob '!android-aec-shim/app/build/**' \
    --glob '!android-aec-shim/.gradle/**' \
    --glob '!codex-build-*/**' \
    --glob '!codex-cargo-cache/**' \
    --glob '!tmp/**'; then
    echo "Private path, host, IP, or secret-looking token found" >&2
    exit 1
fi

echo "release_doctor=ok"
