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

## Release Pipeline

For publishable releases, use the release scripts as the canonical path:

```bash
scripts/release_prepare.sh rust-v0.136.0 --iteration 1
scripts/release_build.sh v0.136.0-ccva.4
scripts/release_validate_device.sh v0.136.0-ccva.4 --fresh --target Pixel6a
scripts/release_publish.sh v0.136.0-ccva.4 --stable
```

`release_publish.sh` is dry-run/check-only by default. Add `--execute` only
after device validation and release notes are ready.

The pipeline stages are:

- `release_prepare.sh`: creates/switches a release branch, verifies upstream
  patch applicability, regenerates the Cargo.lock patch, and updates local
  version references. It does not build or publish.
- `release_build.sh`: builds the Android/Termux CLI tarball and Codex Bridge
  APK into `dist/<release-tag>/`, then runs `release_doctor.sh`.
- `release_validate_device.sh`: wraps release doctor and the SSH deploy helper,
  writes a validation report under `tmp/release-validation/`, and optionally
  captures ADB diagnostics.
- `release_publish.sh`: stages the release manifest and, with `--execute`,
  commits, tags, pushes, and uploads GitHub release assets.
- `release_status.sh`: summarizes branch, stable manifest, release manifests,
  and local dist artifacts.

Release artifacts record both the upstream Codex commit and the local
CCAT/CCVA source commit. `release_doctor.sh` rejects artifacts when source,
script, or docs changes exist after the recorded `ccva_source_commit`; commit
local changes before the final rebuild/publish path. Release manifest-only
commits under `releases/` are allowed after the artifact build.

Android UI approvals, Codex sign-in, Bridge microphone permission, widget
overlay permission, Wake Word human testing, and billable Realtime checks remain
explicit validation steps.

For `v0.136.0-ccva.4`, the release build also enforces the Android RMCP
OAuth/TLS regression guard. The graph, staged binary, and packaged CLI tarball
must not contain `rustls-platform-verifier`; Android RMCP OAuth bootstrap is
routed through Codex's shared HTTP client instead.

## Lower-Level Build Commands

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

By default the script builds upstream `rust-v0.136.0` and writes un-suffixed
local build artifacts:

```text
codex-cli-voice-android-rust-v0.136.0.tar.gz
codex-cli-voice-android-rust-v0.136.0.tar.gz.sha256
codex-cli-voice-android-rust-v0.136.0.tar.gz.metadata
```

For publishable CCVA release artifacts, use the release wrapper:

```bash
scripts/release_build.sh v0.136.0-ccva.4
```

That writes versioned assets under `dist/v0.136.0-ccva.4/`, for example:

```text
codex-cli-voice-android-rust-v0.136.0-ccva.4.tar.gz
codex-aec-shim-v0.136.0-ccva.4-debug.apk
```

Cargo output is cached outside the source clone through:

```bash
CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-../codex-cargo-cache}
```

Override the upstream tag for preflight or future bumps:

```bash
CODEX_TAG=rust-v0.136.0 WORK_DIR=/tmp/codex-cli-preflight CHECK_PATCHES_ONLY=1 ./build.sh
```

For upstream bumps, use the bump preflight helper first. It skips the stale
Cargo.lock patch, regenerates it, and verifies locked metadata:

```bash
scripts/preflight_upstream_bump.sh rust-v0.136.0 --write-lock-patch
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
