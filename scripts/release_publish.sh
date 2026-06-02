#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/release_publish.sh v0.136.0-ccva.2 [options]

Stages release manifests and, with --execute, pushes the Git tag and GitHub
release assets.

Options:
  --stable             Update releases/stable.json to this release.
  --execute            Actually commit, tag, push, and create/upload release.
  --notes-file FILE    Release notes file for gh release create/edit.
  --title TITLE        GitHub release title. Default: release tag.
  -h, --help           Show this help.

Without --execute this is a dry-run/check mode.
EOF
}

die() {
    echo "release_publish: $*" >&2
    exit 1
}

release_tag="${1:-}"
if [[ -z "$release_tag" || "$release_tag" == "-h" || "$release_tag" == "--help" ]]; then
    usage
    [[ -n "$release_tag" ]] && exit 0
    exit 2
fi
shift

case "$release_tag" in
    v[0-9]*.[0-9]*.[0-9]*-ccva.[0-9]*) ;;
    *) die "invalid release tag: $release_tag" ;;
esac

stable="0"
execute="0"
notes_file=""
title="$release_tag"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --stable)
            stable="1"
            shift
            ;;
        --execute)
            execute="1"
            shift
            ;;
        --notes-file)
            [[ $# -ge 2 ]] || die "--notes-file requires a value"
            notes_file="$2"
            shift 2
            ;;
        --title)
            [[ $# -ge 2 ]] || die "--title requires a value"
            title="$2"
            shift 2
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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
dist_dir="$REPO_DIR/dist/$release_tag"
dist_manifest="$dist_dir/${release_tag}.json"
repo_manifest="$REPO_DIR/releases/${release_tag}.json"
stable_manifest="$REPO_DIR/releases/stable.json"

cd "$REPO_DIR"

"$REPO_DIR/scripts/release_doctor.sh" "$release_tag"
[[ -f "$dist_manifest" ]] || die "missing dist manifest: $dist_manifest"

assets=()
while IFS= read -r file; do
    assets+=("$file")
done < <(find "$dist_dir" -maxdepth 1 -type f \
    \( -name '*.tar.gz' -o -name '*.sha256' -o -name '*.metadata' -o -name '*.apk' -o -name "${release_tag}.json" \) \
    | sort)

[[ "${#assets[@]}" -gt 0 ]] || die "no release assets found in $dist_dir"

echo "release_publish_check=ok"
echo "release_tag=$release_tag"
echo "stable=$stable"
echo "execute=$execute"
echo "assets:"
printf '  %s\n' "${assets[@]}"

if [[ "$execute" != "1" ]]; then
    publish_cmd="scripts/release_publish.sh $release_tag"
    if [[ "$stable" == "1" ]]; then
        publish_cmd="$publish_cmd --stable"
    fi
    publish_cmd="$publish_cmd --execute"
    if [[ -n "$notes_file" ]]; then
        publish_cmd="$publish_cmd --notes-file '$notes_file'"
    fi
    cat <<EOF

Dry run only. To publish:
  $publish_cmd
EOF
    exit 0
fi

command -v gh >/dev/null || die "gh is required for --execute"

if [[ -n "$(git status --porcelain)" ]]; then
    die "worktree must be clean before --execute; commit or stash local changes first"
fi

cp "$dist_manifest" "$repo_manifest"

if [[ "$stable" == "1" ]]; then
    cat > "$stable_manifest" <<EOF
{
  "channel": "stable",
  "version": "$release_tag",
  "manifest": "releases/${release_tag}.json"
}
EOF
fi

git add "$repo_manifest"
if [[ "$stable" == "1" ]]; then
    git add "$stable_manifest"
fi

if ! git diff --cached --quiet; then
    git commit -m "release: stage ${release_tag} manifest"
fi

if ! git rev-parse --verify --quiet "refs/tags/$release_tag" >/dev/null; then
    git tag "$release_tag"
fi

git push origin HEAD:main
git push origin "$release_tag"

gh_args=(release create "$release_tag" "${assets[@]}" --title "$title")
if [[ -n "$notes_file" ]]; then
    gh_args+=(--notes-file "$notes_file")
else
    gh_args+=(--generate-notes)
fi

if gh release view "$release_tag" >/dev/null 2>&1; then
    gh release upload "$release_tag" "${assets[@]}" --clobber
else
    gh "${gh_args[@]}"
fi

echo "release_publish=ok"
