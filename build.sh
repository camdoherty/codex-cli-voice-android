#!/usr/bin/env bash
# build.sh — Idempotent Codex CLI + Voice (Android) build pipeline
set -euo pipefail

PROJECT_NAME="Codex CLI + Voice (Android)"
ARTIFACT_PREFIX="codex-cli-voice-android"
INSTALL_DIR="libexec/codex-cli-voice-android"
CODEX_TAG="${CODEX_TAG:-rust-v0.125.0}"
NDK_VERSION="${NDK_VERSION:-r29}"
API_LEVEL="${API_LEVEL:-29}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCHES_DIR="$SCRIPT_DIR/patches"
WORK_DIR="${WORK_DIR:-$SCRIPT_DIR/../codex-build-${CODEX_TAG}}"
OUTPUT_DIR="$SCRIPT_DIR"
CHECK_PATCHES_ONLY="${CHECK_PATCHES_ONLY:-0}"

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
    (cd "$WORK_DIR" && git fetch origin tag "$CODEX_TAG" && git checkout "$CODEX_TAG")
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

# -- Stage --
echo "📦 Staging files..."
STAGE=$(mktemp -d)
trap "rm -rf $STAGE" EXIT
mkdir -p "$STAGE/$INSTALL_DIR" "$STAGE/bin"

cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/codex" \
    "$STAGE/$INSTALL_DIR/codex.bin"
cp "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" \
    "$STAGE/$INSTALL_DIR/"

# -- Wrapper script --
cat > "$STAGE/bin/codex" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
LIBEXEC_DIR="$(dirname "$SCRIPT_DIR")/libexec/codex-cli-voice-android"

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
chmod +x "$STAGE/bin/codex-api" "$STAGE/bin/codex-voice"

# -- Package --
VERSION=$(cd "$WORK_DIR" && git describe --tags --exact-match 2>/dev/null || echo "$CODEX_TAG")
TARBALL="$OUTPUT_DIR/${ARTIFACT_PREFIX}-${VERSION}.tar.gz"
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
    echo "source_commit=$(cd "$WORK_DIR" && git rev-parse HEAD)"
    echo "ndk_version=$NDK_VERSION"
    echo "api_level=$API_LEVEL"
    echo "sha256=$(cut -d ' ' -f1 "${TARBALL}.sha256")"
    echo "codex_bin_size_bytes=$(stat -c %s "$STAGE/$INSTALL_DIR/codex.bin")"
} > "$META"

echo "✅ Build complete! Artifact: $TARBALL"
