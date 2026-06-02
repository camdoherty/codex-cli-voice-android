# CCAT Agent Contract

## Purpose

This document gives Codex agents a shared, non-strict operating model for CCAT
work across Devbox, Pixel 6a, and Pixel 9.

It is a contract in the practical sense: agents should use it to align on host
roles, confirmation steps, approval boundaries, and evidence handoff. It does
not replace repository-specific `AGENTS.md` files. The closest `AGENTS.md`
still controls local conventions.

## Project Name

- CCAT is the current project name.
- CCVA is the older project name and can appear in paths, packages, hostnames,
  scripts, notes, and release artifacts.
- Do not rename historical files only to normalize naming.

## Host Roles

### Devbox

Primary source, build, and release-candidate workstation.

- Owns source-first development, package builds, release-candidate assembly,
  patch queues, and public-ready cleanup.
- Treats generated artifacts, deployment bundles, and release notes as build
  outputs that must be traceable to source.
- Publishes evidence and candidate branches when automation is approved.
- Does not publish stable releases, tags, release manifests, or public assets
  without explicit human approval.

### Pixel 6a

Primary Android development and staging device.

- Owns first-device Android validation, install/deploy verification, non-paid
  smoke tests, and behavior checks that need real Android hardware.
- Can run reversible staging installs and diagnostics when approved by the
  active instructions.
- Should report installed package versions, live skill paths, bridge status,
  logs, and device-specific observations back to Devbox when relevant.
- Should not be assumed to contain source repositories unless the agent confirms
  them on that host.

### Pixel 9

Testing, refinement, hardening, and regression device.

- Owns reversible experiments, edge-case validation, Android/Termux hardening,
  local agent workflow refinement, and evidence collection.
- Treats local code changes as candidate patches or notes unless the user
  explicitly says Pixel 9 is the source of truth for a specific repo.
- Should document test setup, exact commands, observed behavior, and rollback
  paths before feeding changes back to Devbox or Pixel 6a.
- Should avoid irreversible Android settings changes unless explicitly asked.

## Confirmation Rules

Before changing code, instructions, deployments, or installed artifacts, confirm
the current host and work surface.

Minimum confirmation:

- Host identity: `hostname`, `pwd`, and any known device property if on Android.
- Repo identity: `git rev-parse --show-toplevel` when inside a Git repository.
- Branch and dirtiness: `git status --short --branch`.
- Remotes: `git remote -v` when a repo appears to be publishable.
- Relevant live paths: installed package path, skill path, launcher path, or
  deployment target path for the task.

If those checks conflict with this contract, local evidence wins and the agent
should pause or record the discrepancy rather than forcing the contract.

## Git And Source Policy

- Do not assume every host has the same repositories.
- Prefer source changes on Devbox when the change belongs in a buildable repo.
- Prefer test notes, reproducible scripts, and candidate patches on Pixel 9.
- Prefer deployment verification and Android evidence on Pixel 6a.
- Never use broad `git add .` from shared or home-level repos.
- Stage only intentional files.
- Do not revert unrelated user or agent changes.
- Do not rewrite history, delete artifacts, or clean large directories unless
  explicitly approved.

## Approval Boundaries

Agents may normally perform non-destructive inspection, local status checks,
documentation drafts, and reversible validation commands.

Require explicit approval before:

- Publishing to public remotes, release pages, package feeds, or stable manifests.
- Creating tags or marking a release stable.
- Deploying to Pixel 9 as a stable target.
- Running paid or quota-sensitive tests.
- Deleting outside a task-specific scratch or known rollback path.
- Changing disruptive Android settings, permissions, accessibility state,
  notification policy, or audio volume.
- Treating a local experiment as canonical source without a clear handoff.

## Evidence Handoff

When handing work between hosts, include enough detail for another agent to
reproduce or reject it quickly.

Useful evidence:

- Host, date, repo root, branch, commit, dirty status.
- Exact command lines.
- Package or artifact name, version, hash, and install path.
- Relevant logs or short error excerpts.
- Android device model and version when behavior is device-specific.
- Rollback instructions or location of backups.
- Clear status: `candidate`, `validated on Pixel 6a`, `hardened on Pixel 9`,
  `needs Devbox source change`, or `blocked`.

## Self-Update Model

This contract is copied to each host as a local reference. The companion
`CCAT_AGENT_CONTRACT_APPLY.md` file is a prompt for `codex exec` that asks a
host-local agent to:

- Inspect that host.
- Confirm where its durable instruction files live.
- Add a short pointer to this contract if appropriate.
- Write a status report with what it changed or why it stopped.

The apply prompt is intentionally conservative. It must not build, install,
deploy, publish, delete, or make irreversible changes unless a separate user
approval says so.
