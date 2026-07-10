#!/usr/bin/env bash
# build.sh — Idempotent Codex CLI + Voice (Android) build pipeline
set -euo pipefail

PROJECT_NAME="Codex CLI + Voice (Android)"
ARTIFACT_PREFIX="codex-cli-voice-android"
INSTALL_DIR="libexec/codex-cli-voice-android"
CODEX_TAG="${CODEX_TAG:-rust-v0.144.1}"
NDK_VERSION="${NDK_VERSION:-r29}"
API_LEVEL="${API_LEVEL:-29}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCHES_DIR="$SCRIPT_DIR/patches"
WORK_DIR="${WORK_DIR:-$SCRIPT_DIR/../codex-build-${CODEX_TAG}}"
OUTPUT_DIR="${OUTPUT_DIR:-$SCRIPT_DIR}"
CHECK_PATCHES_ONLY="${CHECK_PATCHES_ONLY:-0}"
RESET_UPSTREAM_WORK_DIR="${RESET_UPSTREAM_WORK_DIR:-0}"

reset_upstream_work_dir() {
    work_dir_real="$(cd "$WORK_DIR" && pwd -P)"
    repo_top="$(git -C "$WORK_DIR" rev-parse --show-toplevel)"
    case "$(basename "$WORK_DIR")" in
        codex-build-*) ;;
        *)
            echo "❌ Refusing to reset non-dedicated WORK_DIR: $WORK_DIR" >&2
            exit 1
            ;;
    esac
    [ "$work_dir_real" = "$repo_top" ] || {
        echo "❌ Refusing to reset nested or mismatched WORK_DIR: $WORK_DIR" >&2
        exit 1
    }
    [ "$work_dir_real" != "$SCRIPT_DIR" ] || {
        echo "❌ Refusing to reset public source directory" >&2
        exit 1
    }
    [ "$(git -C "$WORK_DIR" remote get-url origin)" = "https://github.com/openai/codex.git" ] || {
        echo "❌ Refusing to reset WORK_DIR with unexpected origin: $WORK_DIR" >&2
        exit 1
    }
    echo "🧹 Resetting dedicated upstream worktree..."
    git -C "$WORK_DIR" reset --hard "$CODEX_TAG"
    git -C "$WORK_DIR" clean -fd
}

check_public_release_source() {
    dirty="$(
        git -C "$SCRIPT_DIR" status --porcelain --untracked-files=all -- \
            . ':(exclude)dist/**' ':(exclude)tmp/**'
    )"
    [ -z "$dirty" ] || {
        echo "❌ Public release source is dirty; commit or stash these paths:" >&2
        printf '%s\n' "$dirty" >&2
        exit 1
    }
}

echo "========================================"
echo " Building $PROJECT_NAME"
echo " Tag: $CODEX_TAG"
echo " API: $API_LEVEL"
echo "========================================"

# -- Prerequisites check --
if command -v pkg-config >/dev/null; then
    echo "⚠️  WARNING: pkg-config is installed. This often breaks cross-compilation."
    echo "   Consider removing it: sudo apt remove pkg-config"
    sleep 2
fi

if ! command -v cargo-ndk >/dev/null; then
    echo "📦 Installing cargo-ndk..."
    cargo install cargo-ndk
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -d "$HOME/opt/android-ndk-r29" ]; then
    export ANDROID_NDK_HOME="$HOME/opt/android-ndk-r29"
fi
[ -n "${ANDROID_NDK_HOME:-}" ] || { echo "❌ ERROR: ANDROID_NDK_HOME environment variable not set"; exit 1; }

# -- Source --
if [ ! -e "$WORK_DIR/.git" ]; then
    echo "📥 Cloning upstream Codex..."
    git clone --depth=1 --branch "$CODEX_TAG" https://github.com/openai/codex.git "$WORK_DIR"
else
    echo "🔄 Updating upstream Codex..."
    if [ "$RESET_UPSTREAM_WORK_DIR" = "1" ]; then
        git -C "$WORK_DIR" fetch origin tag "$CODEX_TAG"
        reset_upstream_work_dir
        git -C "$WORK_DIR" checkout "$CODEX_TAG"
    else
        (cd "$WORK_DIR" && git fetch origin tag "$CODEX_TAG" && git checkout "$CODEX_TAG")
    fi
fi

# -- Apply patches (idempotent) --
echo "🩹 Applying patches..."
(cd "$WORK_DIR" && for p in "$PATCHES_DIR"/*.patch; do
    if git apply --check "$p" 2>/dev/null; then
        git apply "$p" && echo "  ✅ Applied: $(basename "$p")"
    elif git apply --reverse --check "$p" 2>/dev/null; then
        echo "  ⏭️  Already applied: $(basename "$p")"
    else
        echo "  ❌ Patch conflict: $(basename "$p")"
        exit 1
    fi
done)

"$SCRIPT_DIR/scripts/android_tls_guard.sh" graph "$WORK_DIR/codex-rs"

if [ "$CHECK_PATCHES_ONLY" = "1" ]; then
    echo "🔎 Verifying locked Cargo metadata..."
    (cd "$WORK_DIR/codex-rs" && cargo metadata --locked --format-version 1 >/dev/null)
    echo "✅ Patch and lockfile preflight complete"
    exit 0
fi

# -- Build --
echo "🏗️  Building for Android (aarch64-linux-android)..."
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$SCRIPT_DIR/../codex-cargo-cache}"
echo "   Cargo target cache: $CARGO_TARGET_DIR"
(cd "$WORK_DIR/codex-rs" && \
    cargo ndk -t arm64-v8a --platform "$API_LEVEL" -- build --package codex-cli --release)
(cd "$SCRIPT_DIR/support/codex-realtime-adapter" && \
    cargo ndk -t arm64-v8a --platform "$API_LEVEL" -- build --release)

# -- Stage --
check_public_release_source
echo "📦 Staging files..."
STAGE=$(mktemp -d)
trap "rm -rf $STAGE" EXIT
mkdir -p "$STAGE/$INSTALL_DIR" "$STAGE/bin"
mkdir -p "$STAGE/$INSTALL_DIR/support/termux-skills"

cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/codex" \
    "$STAGE/$INSTALL_DIR/codex.bin"
cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/codex-realtime-adapter" \
    "$STAGE/$INSTALL_DIR/codex-realtime-adapter"
cp "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" \
    "$STAGE/$INSTALL_DIR/"
"$SCRIPT_DIR/scripts/android_tls_guard.sh" binary "$STAGE/$INSTALL_DIR/codex.bin"
cp -R "$SCRIPT_DIR/support/termux-skills/stts" \
    "$STAGE/$INSTALL_DIR/support/termux-skills/"
cp -R "$SCRIPT_DIR/support/termux-skills/termux-agent-ops" \
    "$STAGE/$INSTALL_DIR/support/termux-skills/"
cp -R "$SCRIPT_DIR/support/termux-skills/obsidian-notes-maintainer" \
    "$STAGE/$INSTALL_DIR/support/termux-skills/"
cp -R "$SCRIPT_DIR/support/termux-skills/codex-overview" \
    "$STAGE/$INSTALL_DIR/support/termux-skills/"
cp -R "$SCRIPT_DIR/support/termux-skills/tmux-support" \
    "$STAGE/$INSTALL_DIR/support/termux-skills/"
cp -R "$SCRIPT_DIR/support/termux-agent-assets" \
    "$STAGE/$INSTALL_DIR/support/"
find "$STAGE/$INSTALL_DIR/support/termux-skills" -type d -name __pycache__ -exec rm -rf {} +
find "$STAGE/$INSTALL_DIR/support/termux-skills" -type f -name '*.pyc' -delete

# -- Wrapper script --
cat > "$STAGE/bin/codex" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
LIBEXEC_DIR="${PREFIX}/libexec/codex-cli-voice-android"

# Safe only for this process tree
export LD_LIBRARY_PATH="${LIBEXEC_DIR}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export SSL_CERT_FILE="${PREFIX}/etc/tls/cert.pem"
export SSL_CERT_DIR="${PREFIX}/etc/tls/certs"
export CODEX_SANDBOX_MODE="danger-full-access"
export CODEX_SELF_EXE="${LIBEXEC_DIR}/codex.bin"

exec "${LIBEXEC_DIR}/codex.bin" "$@"
EOF
chmod +x "$STAGE/bin/codex"

cp "$SCRIPT_DIR/scripts/termux-codex-api" "$STAGE/bin/codex-api"
cp "$SCRIPT_DIR/scripts/termux-codex-voice" "$STAGE/bin/codex-voice"
cat > "$STAGE/bin/codex-realtime-adapter" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
LIBEXEC_DIR="${PREFIX}/libexec/codex-cli-voice-android"

export LD_LIBRARY_PATH="${LIBEXEC_DIR}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export SSL_CERT_FILE="${PREFIX}/etc/tls/cert.pem"
export SSL_CERT_DIR="${PREFIX}/etc/tls/certs"

exec "${LIBEXEC_DIR}/codex-realtime-adapter" "$@"
EOF
cp "$SCRIPT_DIR/scripts/install_stts_skill.sh" "$STAGE/bin/codex-install-stts"
cp "$SCRIPT_DIR/scripts/install_termux_agent_assets.sh" "$STAGE/bin/codex-install-agent-assets"
cp "$SCRIPT_DIR/scripts/ccva-tmux-run" "$STAGE/bin/ccva-tmux-run"
cp "$SCRIPT_DIR/scripts/ccva-realtime-stop" "$STAGE/bin/ccva-realtime-stop"
chmod +x "$STAGE/bin/codex-api" "$STAGE/bin/codex-voice" "$STAGE/bin/codex-realtime-adapter" \
    "$STAGE/bin/codex-install-stts" "$STAGE/bin/codex-install-agent-assets" \
    "$STAGE/bin/ccva-tmux-run" "$STAGE/bin/ccva-realtime-stop"

# -- Package --
VERSION=$(cd "$WORK_DIR" && git describe --tags --exact-match 2>/dev/null || echo "$CODEX_TAG")
PACKAGE_VERSION="${CCVA_PACKAGE_VERSION:-$VERSION}"
mkdir -p "$OUTPUT_DIR"
TARBALL="$OUTPUT_DIR/${ARTIFACT_PREFIX}-${PACKAGE_VERSION}.tar.gz"
TARBALL_NAME="$(basename "$TARBALL")"

echo "🗜️  Creating tarball..."
tar -czf "$TARBALL" -C "$STAGE" .
(cd "$OUTPUT_DIR" && sha256sum "$TARBALL_NAME" > "${TARBALL_NAME}.sha256")

META="$TARBALL.metadata"
{
    echo "project_name=$PROJECT_NAME"
    echo "artifact_prefix=$ARTIFACT_PREFIX"
    echo "install_dir=$INSTALL_DIR"
    echo "codex_tag=$VERSION"
    echo "package_version=$PACKAGE_VERSION"
    echo "source_commit=$(cd "$WORK_DIR" && git rev-parse HEAD)"
    echo "ccva_source_commit=$(git -C "$SCRIPT_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "ndk_version=$NDK_VERSION"
    echo "api_level=$API_LEVEL"
    echo "sha256=$(cut -d ' ' -f1 "${TARBALL}.sha256")"
    echo "codex_bin_size_bytes=$(stat -c %s "$STAGE/$INSTALL_DIR/codex.bin")"
    echo "codex_realtime_adapter_size_bytes=$(stat -c %s "$STAGE/$INSTALL_DIR/codex-realtime-adapter")"
} > "$META"

echo "✅ Build complete! Artifact: $TARBALL"
