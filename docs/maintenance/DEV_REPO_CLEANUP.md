# Dev Repo Cleanup

This repo is normally built inside a larger dev workspace such as
`<workspace>/pixel9`. Cleanup must preserve build speed and active release
debuggability.

## Retention Policy

- Keep the canonical public repo working tree.
- Keep all build and dist artifacts for the active release family until that
  family is signed off.
- Keep the latest known-good build and dist artifact from the previous
  successful release family.
- Keep shared build accelerators:
  - `codex-cargo-cache`
  - `codex-cli-voice-android-public/android-toolchain`
- Delete older build families, superseded rebuilds from the previous family,
  and obsolete update worktrees only after a manifest review.

For example, while `v0.137.*` is active and `v0.136.0-ccva.6` is the previous
known-good build, keep:

```text
codex-build-rust-v0.137.0-*
codex-build-rust-v0.136.0-ccva.6
codex-cli-voice-android-public/dist/v0.137.0-*
codex-cli-voice-android-public/dist/v0.136.0-ccva.6
```

## Cleanup Rules

- Use a manifest with explicit `KEEP`, `DELETE`, `DELETE_IF_CLEAN`, and
  `DEFER` actions.
- Treat `DELETE_IF_CLEAN` git repos as removable only when
  `git status --short` is empty.
- Skip dirty repos; do not decide on their contents during cleanup.
- Do not delete retained build families merely because there are more than two
  directories. Multiple rebuilds of the active family are expected during
  unresolved release work.
- Do not delete `codex-cargo-cache` if future Android build speed matters. It
  is the shared Cargo target dir used by `build.sh`.
- Do not delete `android-toolchain` unless re-downloading the JDK, Gradle, and
  Android SDK is acceptable.

## Post-Cleanup Checks

After applying a cleanup manifest, verify:

```sh
test -e ../codex-cargo-cache/aarch64-linux-android/release/codex
test -d android-toolchain
test -d dist/<active-release-tag>
test -d dist/<previous-known-good-tag>
git status --short --branch
```

Then record the remaining build and dist families:

```sh
find .. -maxdepth 1 -type d -name 'codex-build-rust-v*' -printf '%f\n' | sort
find dist -maxdepth 1 -mindepth 1 -type d -printf '%f\n' | sort
```

## First Cleanup Record

The first family-aware cleanup on 2026-06-05 reduced the broader dev workspace
from about `37G` to `22G` while preserving:

- active `v0.137.*` build artifacts
- previous known-good `v0.136.0-ccva.6`
- `codex-cargo-cache`
- `android-toolchain`

The one-off manifest is stored at:

```text
tmp/dev-repo-cleanup-manifest-20260605.md
```
