# Build

These instructions assume a Linux build host and an Android/Termux ARM64 target.

For an agent-guided source build and deploy flow, see
[AGENT_BUILD_CCVA.md](AGENT_BUILD_CCVA.md).

## Prerequisites

- Rust stable with the `aarch64-linux-android` target available.
- Android NDK r29 or a compatible NDK exported as `ANDROID_NDK_HOME`.
- `cargo-ndk` on `PATH`; `build.sh` installs it if missing.
- Git, curl, unzip, sha256sum, and a POSIX shell environment.

For the Android AEC shim APK, run:

```bash
scripts/setup_android_toolchain.sh
```

That downloads a local JDK, Gradle, and Android SDK into `android-toolchain/`.

## Codex CLI Package

Patch-only preflight:

```bash
ANDROID_NDK_HOME=/path/to/android-ndk-r29 \
CHECK_PATCHES_ONLY=1 \
./build.sh
```

Full build:

```bash
ANDROID_NDK_HOME=/path/to/android-ndk-r29 ./build.sh
```

By default the script builds upstream `rust-v0.133.0` and writes:

```text
codex-cli-voice-android-rust-v0.133.0.tar.gz
codex-cli-voice-android-rust-v0.133.0.tar.gz.sha256
codex-cli-voice-android-rust-v0.133.0.tar.gz.metadata
```

Cargo output is cached outside the source clone through:

```bash
CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-../codex-cargo-cache}
```

Override the upstream tag for preflight or future bumps:

```bash
CODEX_TAG=rust-v0.133.0 WORK_DIR=/tmp/codex-cli-preflight CHECK_PATCHES_ONLY=1 ./build.sh
```

## AEC Shim APK

```bash
scripts/setup_android_toolchain.sh
scripts/build_aec_shim_apk.sh
```

The debug APK is produced at:

```text
android-aec-shim/app/build/outputs/apk/debug/app-debug.apk
```
