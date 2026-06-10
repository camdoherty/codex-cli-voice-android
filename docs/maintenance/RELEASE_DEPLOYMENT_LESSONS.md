# Release Deployment Lessons

Use this note after a candidate build and before the next Android deployment.
It records durable process corrections, not device secrets or full command
logs.

## Proven v0.139.0 Flow

The `v0.139.0-ccva.1` CLI package passed checksum, release-doctor, Android TLS,
billing-guard, CLI, managed-skill presence, and STTS status checks on Pixel 6a
and Pixel 9. The user subsequently completed broader testing on both devices,
including Bridge smoke validation, with no observed issues. Realtime remains a
separate explicitly billable validation item unless recorded independently.

## Findings

1. `release_validate_device.sh --target NAME` labels the report only. It does
   not select an SSH host. Set and verify `PIXEL_HOST`, `PIXEL_USER`,
   `PIXEL_PORT`, and `SSH_CONFIG` before running it.
2. Select the device explicitly for every SSH and SCP command. The local
   `pixel-ssh` and `pixel-scp` helpers support `--device 6a` and
   `--device pixel9`. Do not rely on their default target during multi-device
   validation.
3. `termux-open` proves only that an installer launch was attempted. Completion
   requires user confirmation plus Bridge loopback and audible smoke evidence.
4. No-clobber conflicts are review work, not automatic failures. Compare each
   `.incoming.<timestamp>` tree with the live target and preserve the version
   with the required behavior.
5. Device-local managed assets can be newer than the packaged repo source.
   During this deployment:
   - Pixel 6a `termux-agent-ops` had an additional Android-camera routing rule.
   - Pixel 9 STTS had full shared-text attachment guidance.
   - Pixel 9 `codex-overview` had fleet-state support.
   - Pixel 9's Realtime shortcut intentionally selected the `realtime-demo`
     profile.
   Review and promote generally useful changes into source before the next
   release; do not silently replace them during deployment.
6. Keep three evidence states distinct:
   - automated CLI/package checks passed;
   - APK staged or installer launch attempted;
   - Android install, Bridge service, and audible smoke confirmed by the user.
7. The release doctor previously compared only committed history with the
   artifact source commit. It could pass while tracked or untracked source
   changes existed after the build. It now rejects a dirty source worktree.
8. Same-upstream iteration preparation previously used the stable manifest to
   identify the old candidate. When stable lagged the active release branch,
   this produced mixed `.1` and `.2` documentation paths. Preparation now uses
   the source release branch when its upstream version matches.

## Next Deployment Checklist

1. Confirm the target with `pixel-ssh --device DEVICE` and record `whoami`,
   model, current Codex version, and free space.
2. Run `release_doctor` once before mutation.
3. Export the verified target settings, then run
   `release_validate_device.sh`; remember `--target` is report metadata.
4. Review every no-clobber conflict before accepting either side.
5. Copy the APK with `pixel-scp --device DEVICE`, verify its SHA-256 on-device,
   and attempt the installer launch.
6. Record user confirmation for installation, Bridge port, `/v1/text-voice`,
   and audible TTS separately.
7. Run Realtime only after explicit billable approval.
8. Promote approved device-local managed-asset improvements back into the repo,
   run a privacy scan, rebuild if packaged content changed, and then test a
   clean install.

Documentation or deployment-script fixes made after a build do not invalidate
the device evidence already collected for that binary, but they do make the
artifact stale for publication. Commit the intended fixes and produce a new
candidate iteration before publishing.

## Automation Priorities

- Add first-class SSH target selection to the release validation wrapper so
  report labeling and transport targeting cannot diverge.
- Emit structured validation status for CLI, APK staging, APK installation,
  Bridge loopback, audible TTS, wake word, and Realtime.
- Add a managed-asset drift report that compares packaged hashes with each
  device before deployment.
- Keep public release docs generic; retain device-specific evidence in ignored
  validation reports or a sanitized release summary.
