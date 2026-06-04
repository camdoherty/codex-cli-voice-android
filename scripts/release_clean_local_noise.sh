#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/release_clean_local_noise.sh [--apply] [--allow-dirty-tracked]

Prepares the public release tree for build/publish checks.

Default mode is dry-run. The script:
  - scans all tracked files that would be pushed for private markers;
  - reports modified tracked files;
  - classifies untracked files outside dist/ and tmp/ as local noise;
  - in --apply mode, moves only local-noise untracked files to
    ../local-artifacts/release-clean-<timestamp>/, preserving paths.

It never deletes files and never moves tracked files.
EOF
}

mode="dry-run"
allow_dirty_tracked="0"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply)
            mode="apply"
            shift
            ;;
        --allow-dirty-tracked)
            allow_dirty_tracked="1"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "release_clean_local_noise: unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PARENT_DIR="$(cd "$REPO_DIR/.." && pwd)"
timestamp="$(date +%Y%m%d-%H%M%S)"
move_root="$PARENT_DIR/local-artifacts/release-clean-$timestamp"

private_pattern='(/home/[c]ad|100\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|u0_[a][0-9]+|[m]intpad|gho_[A-Za-z0-9_]+|sk-[A-Za-z0-9_-]{20,}|Obsidian[V]ault1)'

cd "$REPO_DIR"

tracked_dirty="$(git status --porcelain --untracked-files=no)"
if [[ -n "$tracked_dirty" ]]; then
    echo "tracked_dirty=found"
    printf '%s\n' "$tracked_dirty"
else
    echo "tracked_dirty=none"
fi

echo "tracked_private_scan=running"
tracked_private_matches="$(
    git ls-files -z |
        xargs -0 -r rg -n --no-messages -e "$private_pattern" -- || true
)"
if [[ -n "$tracked_private_matches" ]]; then
    echo "tracked_private_scan=failed"
    printf '%s\n' "$tracked_private_matches"
    tracked_private_failed="1"
else
    echo "tracked_private_scan=ok"
    tracked_private_failed="0"
fi

mapfile -d '' untracked < <(git ls-files --others --exclude-standard -z)
allowed_untracked=()
suspicious_untracked=()

for path in "${untracked[@]}"; do
    case "$path" in
        dist/*|tmp/*)
            allowed_untracked+=("$path")
            ;;
        *)
            suspicious_untracked+=("$path")
            ;;
    esac
done

echo "allowed_untracked_count=${#allowed_untracked[@]}"
if [[ ${#allowed_untracked[@]} -gt 0 ]]; then
    printf 'allowed_untracked=%s\n' "${allowed_untracked[@]}"
fi

echo "local_noise_count=${#suspicious_untracked[@]}"
if [[ ${#suspicious_untracked[@]} -gt 0 ]]; then
    printf 'local_noise=%s\n' "${suspicious_untracked[@]}"
fi

untracked_private_failed="0"
if [[ ${#suspicious_untracked[@]} -gt 0 ]]; then
    echo "local_noise_private_scan=running"
    untracked_private_matches="$(
        printf '%s\0' "${suspicious_untracked[@]}" |
            xargs -0 -r rg -n --no-messages -e "$private_pattern" -- || true
    )"
    if [[ -n "$untracked_private_matches" ]]; then
        echo "local_noise_private_scan=found"
        printf '%s\n' "$untracked_private_matches"
        untracked_private_failed="1"
    else
        echo "local_noise_private_scan=ok"
    fi
else
    echo "local_noise_private_scan=skipped"
fi

if [[ "$mode" == "apply" && ${#suspicious_untracked[@]} -gt 0 ]]; then
    echo "move_root=$move_root"
    mkdir -p "$move_root"
    for path in "${suspicious_untracked[@]}"; do
        dest="$move_root/$path"
        mkdir -p "$(dirname "$dest")"
        mv -- "$path" "$dest"
        echo "moved=$path -> $dest"
    done
fi

if [[ "$tracked_private_failed" == "1" ]]; then
    echo "release_clean_local_noise=failed_tracked_private_markers" >&2
    exit 1
fi

if [[ -n "$tracked_dirty" && "$allow_dirty_tracked" != "1" ]]; then
    echo "release_clean_local_noise=failed_dirty_tracked" >&2
    echo "Commit tracked changes or rerun with --allow-dirty-tracked for reporting only." >&2
    exit 1
fi

if [[ "$mode" != "apply" && ${#suspicious_untracked[@]} -gt 0 ]]; then
    echo "release_clean_local_noise=failed_local_noise" >&2
    echo "Dry-run only. Rerun with --apply to move local-noise untracked files." >&2
    echo "planned_move_root=$move_root" >&2
    exit 1
fi

if [[ "$mode" == "apply" ]]; then
    remaining_noise_count="$(
        git ls-files --others --exclude-standard |
            awk 'BEGIN{n=0} !/^(dist|tmp)\// {n++} END{print n}'
    )"
    echo "remaining_local_noise_count=$remaining_noise_count"
    if [[ "$remaining_noise_count" != "0" ]]; then
        echo "release_clean_local_noise=failed_remaining_local_noise" >&2
        exit 1
    fi
fi

echo "release_clean_local_noise=ok"
