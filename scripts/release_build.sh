#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/release_build.sh v0.135.0-ccva.1

Builds a versioned CCVA release candidate into dist/<release-tag>/.
EOF
}

release_tag="${1:-}"
if [[ -z "$release_tag" || "$release_tag" == "-h" || "$release_tag" == "--help" ]]; then
    usage
    [[ -n "$release_tag" ]] && exit 0
    exit 2
fi

case "$release_tag" in
    v[0-9]*.[0-9]*.[0-9]*-ccva.[0-9]*) ;;
    *)
        echo "Invalid release tag: $release_tag" >&2
        echo "Expected form: v0.135.0-ccva.1" >&2
        exit 2
        ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

upstream_semver="${release_tag#v}"
upstream_semver="${upstream_semver%%-ccva.*}"
ccva_iteration="${release_tag##*-ccva.}"
codex_tag="rust-v${upstream_semver}"
package_version="${codex_tag}-ccva.${ccva_iteration}"
dist_dir="$REPO_DIR/dist/$release_tag"
work_dir="${WORK_DIR:-$REPO_DIR/../codex-build-${package_version}}"

mkdir -p "$dist_dir"

echo "release_tag=$release_tag"
echo "codex_tag=$codex_tag"
echo "package_version=$package_version"
echo "dist_dir=$dist_dir"
echo "work_dir=$work_dir"

(
    cd "$REPO_DIR"
    CODEX_TAG="$codex_tag" \
        WORK_DIR="$work_dir" \
        CHECK_PATCHES_ONLY=1 \
        ./build.sh
)

(
    cd "$REPO_DIR"
    CODEX_TAG="$codex_tag" \
        CCVA_PACKAGE_VERSION="$package_version" \
        WORK_DIR="$work_dir" \
        OUTPUT_DIR="$dist_dir" \
        ./build.sh
)

"$REPO_DIR/scripts/build_aec_shim_apk.sh"

shim_src="$REPO_DIR/android-aec-shim/app/build/outputs/apk/debug/app-debug.apk"
shim_asset="codex-aec-shim-${release_tag}-debug.apk"
cp "$shim_src" "$dist_dir/$shim_asset"
(
    cd "$dist_dir"
    sha256sum "$shim_asset" > "$shim_asset.sha256"
)

cli_asset="codex-cli-voice-android-${package_version}.tar.gz"
cli_sha="$(awk 'NF { print $1; exit }' "$dist_dir/$cli_asset.sha256")"
shim_sha="$(awk 'NF { print $1; exit }' "$dist_dir/$shim_asset.sha256")"
ccva_source_commit="$(git -C "$REPO_DIR" rev-parse HEAD)"
validation_status="${CCVA_VALIDATION_STATUS:-candidate}"
tested_devices_json="${CCVA_TESTED_DEVICES_JSON:-[]}"

cat > "$dist_dir/${release_tag}.json" <<EOF
{
  "version": "$release_tag",
  "upstream_codex": "$codex_tag",
  "arch": "aarch64",
  "release_tag": "$release_tag",
  "ccva_source_commit": "$ccva_source_commit",
  "validation_status": "$validation_status",
  "cli_tarball": "$cli_asset",
  "cli_sha256": "$cli_sha",
  "shim_apk": "$shim_asset",
  "shim_sha256": "$shim_sha",
  "tested_devices": $tested_devices_json
}
EOF

"$REPO_DIR/scripts/release_doctor.sh" "$release_tag"

echo "Release candidate staged in $dist_dir"
