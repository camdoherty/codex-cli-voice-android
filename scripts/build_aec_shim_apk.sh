#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLCHAIN_DIR="${ANDROID_TOOLCHAIN_DIR:-$REPO_DIR/android-toolchain}"
ANDROID_HOME="${ANDROID_HOME:-$TOOLCHAIN_DIR/android-sdk}"
JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN_DIR/jdk-21.0.10+7}"
GRADLE_BIN="${GRADLE_BIN:-$TOOLCHAIN_DIR/gradle-9.3.1/bin/gradle}"

[ -x "$JAVA_HOME/bin/java" ] || { echo "Missing Java. Run scripts/setup_android_toolchain.sh first."; exit 1; }
[ -x "$GRADLE_BIN" ] || { echo "Missing Gradle. Run scripts/setup_android_toolchain.sh first."; exit 1; }
[ -d "$ANDROID_HOME/platforms/android-36" ] || { echo "Missing Android SDK platform 36. Run scripts/setup_android_toolchain.sh first."; exit 1; }

export JAVA_HOME ANDROID_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$REPO_DIR/android-aec-shim"
"$GRADLE_BIN" --no-daemon :app:assembleDebug

APK="$PWD/app/build/outputs/apk/debug/app-debug.apk"
(cd "$(dirname "$APK")" && sha256sum "$(basename "$APK")" > "$(basename "$APK").sha256")
echo "Built $APK"
