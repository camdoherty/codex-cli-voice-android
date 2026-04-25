#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLCHAIN_DIR="${ANDROID_TOOLCHAIN_DIR:-$REPO_DIR/android-toolchain}"
DOWNLOAD_DIR="$TOOLCHAIN_DIR/downloads"
ANDROID_HOME="${ANDROID_HOME:-$TOOLCHAIN_DIR/android-sdk}"

JDK_VERSION="21.0.10_7"
JDK_DIR="$TOOLCHAIN_DIR/jdk-21.0.10+7"
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.10%2B7/OpenJDK21U-jdk_x64_linux_hotspot_${JDK_VERSION}.tar.gz"
JDK_SHA_URL="${JDK_URL}.sha256.txt"

GRADLE_VERSION="9.3.1"
GRADLE_DIR="$TOOLCHAIN_DIR/gradle-$GRADLE_VERSION"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_SHA="b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06"

CMDLINE_TOOLS_VERSION="14742923"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
# The Android download page labels this as SHA-256, but publishes a 40-character
# digest for this archive. Verify it as SHA-1, and keep the stronger local
# sha256sum visible in setup logs when troubleshooting.
CMDLINE_TOOLS_SHA1="48833c34b761c10cb20bcd16582129395d121b27"

mkdir -p "$DOWNLOAD_DIR" "$ANDROID_HOME/cmdline-tools"

download() {
    local url="$1"
    local dest="$2"
    if [ ! -f "$dest" ]; then
        curl -L --fail --retry 3 -o "$dest" "$url"
    fi
}

verify_sha() {
    local expected="$1"
    local file="$2"
    printf '%s  %s\n' "$expected" "$file" | sha256sum -c -
}

verify_sha1() {
    local expected="$1"
    local file="$2"
    printf '%s  %s\n' "$expected" "$file" | sha1sum -c -
}

if [ ! -x "$JDK_DIR/bin/java" ]; then
    JDK_ARCHIVE="$DOWNLOAD_DIR/$(basename "$JDK_URL")"
    JDK_SHA_FILE="$JDK_ARCHIVE.sha256.txt"
    download "$JDK_URL" "$JDK_ARCHIVE"
    download "$JDK_SHA_URL" "$JDK_SHA_FILE"
    expected="$(awk '{print $1}' "$JDK_SHA_FILE")"
    verify_sha "$expected" "$JDK_ARCHIVE"
    rm -rf "$JDK_DIR"
    mkdir -p "$JDK_DIR"
    tar -xzf "$JDK_ARCHIVE" -C "$JDK_DIR" --strip-components 1
fi

if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
    GRADLE_ARCHIVE="$DOWNLOAD_DIR/gradle-${GRADLE_VERSION}-bin.zip"
    download "$GRADLE_URL" "$GRADLE_ARCHIVE"
    verify_sha "$GRADLE_SHA" "$GRADLE_ARCHIVE"
    rm -rf "$GRADLE_DIR"
    unzip -q "$GRADLE_ARCHIVE" -d "$TOOLCHAIN_DIR"
fi

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    CMDLINE_ARCHIVE="$DOWNLOAD_DIR/$CMDLINE_TOOLS_ZIP"
    download "$CMDLINE_TOOLS_URL" "$CMDLINE_ARCHIVE"
    verify_sha1 "$CMDLINE_TOOLS_SHA1" "$CMDLINE_ARCHIVE"
    sha256sum "$CMDLINE_ARCHIVE"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
    unzip -q "$CMDLINE_ARCHIVE" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

export JAVA_HOME="$JDK_DIR"
export ANDROID_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$GRADLE_DIR/bin:$PATH"

set +o pipefail
yes | sdkmanager --licenses >/dev/null
set -o pipefail
sdkmanager \
    "platforms;android-36" \
    "build-tools;36.0.0" \
    "platform-tools"

cat <<EOF
Android toolchain ready:
  JAVA_HOME=$JAVA_HOME
  ANDROID_HOME=$ANDROID_HOME
  GRADLE=$GRADLE_DIR/bin/gradle
EOF
