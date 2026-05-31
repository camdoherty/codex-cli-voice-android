#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_DIR"

echo "branch=$(git branch --show-current)"
echo "commit=$(git rev-parse --short HEAD)"
echo
echo "git_status:"
git status --short
echo
echo "stable_manifest:"
cat releases/stable.json
echo
echo "release_manifests:"
find releases -maxdepth 1 -type f -name 'v*.json' | sort
echo
echo "dist_artifacts:"
find dist -maxdepth 2 -type f 2>/dev/null | sort || true
