#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/release_validate_device.sh v0.139.0-ccva.2 [options]

Runs the local release checks and deploys the CLI package to the configured
Termux target for non-billable validation.

Options:
  --fresh              Set ALLOW_FRESH_INSTALL=1 for first install.
  --target NAME        Human-readable report label only; does not select SSH.
  --adb-serial SERIAL  Capture ADB permission/runtime snapshots after deploy.
  --skip-deploy        Do not run deploy_termux_package.sh.
  -h, --help           Show this help.

Device SSH settings come from DEPLOY.md, the environment/.env, and
deploy_termux_package.sh. Confirm PIXEL_HOST and PIXEL_USER before deployment;
--target changes only the report filename and heading.
Android UI checks, Codex sign-in, Bridge APK install approval, STTS widgets,
Wake Word, and billable Realtime remain explicit user validation steps.
EOF
}

die() {
    echo "release_validate_device: $*" >&2
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

fresh="0"
target="termux-target"
adb_serial=""
skip_deploy="0"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --fresh)
            fresh="1"
            shift
            ;;
        --target)
            [[ $# -ge 2 ]] || die "--target requires a value"
            target="$2"
            shift 2
            ;;
        --adb-serial)
            [[ $# -ge 2 ]] || die "--adb-serial requires a value"
            adb_serial="$2"
            shift 2
            ;;
        --skip-deploy)
            skip_deploy="1"
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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
dist_dir="$REPO_DIR/dist/$release_tag"
upstream_semver="${release_tag#v}"
upstream_semver="${upstream_semver%%-ccva.*}"
iteration="${release_tag##*-ccva.}"
package_version="rust-v${upstream_semver}-ccva.${iteration}"
cli_asset="$dist_dir/codex-cli-voice-android-${package_version}.tar.gz"
cli_sha="$cli_asset.sha256"
report_dir="$REPO_DIR/tmp/release-validation"
stamp="$(date +%Y%m%d-%H%M%S)"
report="$report_dir/${stamp}-${release_tag}-${target}.md"

mkdir -p "$report_dir"

{
    echo "# CCVA Release Validation"
    echo
    echo "- Release: $release_tag"
    echo "- Target: $target"
    echo "- Started: $stamp"
    echo "- Commit: $(git -C "$REPO_DIR" rev-parse HEAD)"
    echo "- Branch: $(git -C "$REPO_DIR" branch --show-current)"
    echo
} > "$report"

"$REPO_DIR/scripts/release_doctor.sh" "$release_tag" | tee -a "$report"

if [[ "$skip_deploy" != "1" ]]; then
    [[ -f "$cli_asset" ]] || die "missing CLI asset: $cli_asset"
    [[ -f "$cli_sha" ]] || die "missing CLI sha: $cli_sha"
    if [[ "$fresh" == "1" ]]; then
        ALLOW_FRESH_INSTALL=1 "$REPO_DIR/scripts/deploy_termux_package.sh" "$cli_asset" "$cli_sha" | tee -a "$report"
    else
        "$REPO_DIR/scripts/deploy_termux_package.sh" "$cli_asset" "$cli_sha" | tee -a "$report"
    fi
    if [[ -f "$REPO_DIR/.env" ]]; then
        set -a
        # shellcheck disable=SC1091
        . "$REPO_DIR/.env"
        set +a
    fi
    pixel_host="${PIXEL_HOST:-}"
    pixel_user="${PIXEL_USER:-}"
    pixel_port="${PIXEL_PORT:-8022}"
    ssh_config="${SSH_CONFIG:-/dev/null}"
    [[ -n "$pixel_host" && -n "$pixel_user" ]] ||
        die "PIXEL_HOST and PIXEL_USER are required for installed-binary smoke"
    ssh_opts=(-F "$ssh_config" -p "$pixel_port")
    if [[ -n "${PIXEL_IDENTITY:-}" ]]; then
        ssh_opts+=(-o "IdentityFile=$PIXEL_IDENTITY" -o IdentitiesOnly=yes)
    fi
    ssh "${ssh_opts[@]}" "${pixel_user}@${pixel_host}" 'sh -s' <<'REMOTE_TLS_GUARD' | tee -a "$report"
set -eu
binary="$PREFIX/libexec/codex-cli-voice-android/codex.bin"
if strings "$binary" | grep -F \
    -e "rustls-platform-verifier" \
    -e "Expect rustls-platform-verifier" >/dev/null; then
    echo "Forbidden rustls platform verifier string in installed binary" >&2
    exit 1
fi
echo "installed_android_tls_guard=ok"
REMOTE_TLS_GUARD
else
    echo "deploy=skipped" | tee -a "$report"
fi

if [[ -n "$adb_serial" ]]; then
    "$REPO_DIR/scripts/android_permission_snapshot.sh" --serial "$adb_serial" | tee -a "$report"
    "$REPO_DIR/scripts/android_runtime_snapshot.sh" --serial "$adb_serial" --logcat-since "30 minutes ago" | tee -a "$report"
fi

cat <<'EOF' | tee -a "$report"

## Manual Validation Required

- Install or update Codex Bridge APK and verify service/listening state.
- Grant microphone, notifications, Termux Run Command, and widget overlay permissions as needed.
- Tap Codex once and complete sign-in on fresh installs.
- Verify ~/codex_notes exists and points to shared Documents when shared storage is available.
- Verify STTS: Start + Talk.
- Verify a simple STTS/Codex note request writes under ~/codex_notes.
- Verify STTS: Wake Word if WWS is in scope.
- Verify Realtime only with explicit billable approval.
EOF

echo
echo "release_validate_device=ok"
echo "report=$report"
