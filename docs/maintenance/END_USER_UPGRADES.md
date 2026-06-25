# End-User Upgrade Recommendations

This note records the recommended shape for easier CCVA end-user upgrades after
a release is published.

## Recommendation

Support one public in-place upgrade command, backed by release assets rather
than by a git clone:

```sh
curl -fsSLO https://raw.githubusercontent.com/camdoherty/codex-cli-voice-android/main/install.sh
sh install.sh --upgrade
```

After the first install, also provide a local wrapper:

```sh
ccva-upgrade
```

`ccva-upgrade` should rerun the same public installer in upgrade mode. The
installer remains the canonical implementation so fresh install and upgrade
logic do not drift.

## Required Release Surface

Every stable release should publish:

- CLI Termux tarball.
- CLI SHA-256 checksum.
- Codex Bridge APK.
- Bridge APK SHA-256 checksum.
- Stable release manifest, for example `releases/stable.json`.
- Public installer/upgrade script.

The stable manifest should include at least:

- CCVA version.
- Upstream Codex tag.
- CLI tarball URL and SHA-256.
- Bridge APK URL and SHA-256.
- Minimum supported installer version.
- Release channel, initially `stable`.

Candidate support can be added later with `--channel candidate` or
`--version VERSION`, but the first implementation should keep the ordinary user
path stable-only.

## Installer Behavior

`install.sh --upgrade` should:

- Detect Termux and `aarch64`.
- Fetch and validate the stable manifest.
- Compare installed and available versions.
- Back up the current CCVA install before mutation.
- Download and verify the CLI tarball.
- Replace the managed CLI package and wrappers in place.
- Preserve Codex auth, `~/.codex/config.toml`, `~/codex_notes`, local notes, and
  user/device-local state.
- Refresh managed skills with the existing no-clobber policy: install missing,
  skip identical, preserve local conflicts, and write incoming copies for
  review.
- Stage the Bridge APK into `~/storage/downloads` when the APK changed.
- Attempt to open the APK installer with Termux/Android APIs.
- Print exact manual fallback steps if Android does not show the installer UI.
- Run non-billable smoke checks and clearly list remaining manual checks.

On non-root Android devices the Bridge APK cannot be silently updated. The
script can stage and open the APK, but the user must approve Android's package
installer UI.

## Build And Release Impact

The release build still produces the same core artifacts, but the manifest and
installer become first-class release outputs.

Build validation should fail if:

- Required release assets are missing.
- Manifest URLs are missing or malformed.
- Manifest checksums do not match generated assets.
- The manifest points at a different release version than the artifact set.
- The installer version is older than the manifest's minimum supported version.
- `release_doctor` finds dirty source, tracked artifacts, private paths, IPs, or
  secret-looking tokens.

Maintainer deployment scripts can remain separate. They are for unpublished
candidate validation. The public installer is the end-user path and must be
tested before publishing stable.

## Test Matrix

Add an explicit upgrade test before publishing stable:

1. Build candidate artifacts.
2. Validate with maintainer scripts on staging devices as today.
3. Stage or publish candidate assets in a way the installer can consume.
4. On Pixel 6a, install previous stable, then run the upgrade path.
5. Verify the upgraded CLI version, wrapper commands, STTS, managed skills,
   notes preservation, Codex auth preservation, and no-clobber conflict output.
6. Stage/open the Bridge APK and verify Android install approval separately.
7. On Pixel 9, run the upgrade path over the lived-in device state.
8. Verify Bridge loopback, `/v1/text-voice`, audible TTS, STTS start/status/stop,
   and Codex CLI smoke checks.
9. Run Realtime only with explicit billable approval.
10. Publish or update `releases/stable.json` only after the public upgrade path
    passes.

Fresh install testing remains separate and should still be performed on a clean
or reset staging device before final sign-off.

## Practical Rollout

Implement in this order:

1. Define the stable manifest schema and add manifest validation to release
   checks.
2. Convert the current install/deploy logic into an end-user-safe
   `install.sh --upgrade` path.
3. Install `ccva-upgrade` as a thin local wrapper around the public installer.
4. Add candidate-channel support only after stable upgrades are proven.
5. Add structured validation output for CLI upgrade, APK staged, APK installed,
   Bridge loopback, audible TTS, STTS, wake word, and Realtime.

The goal is to make ordinary upgrades one command plus Android APK approval,
while preserving the stricter maintainer workflow for candidate builds and
multi-device validation.
