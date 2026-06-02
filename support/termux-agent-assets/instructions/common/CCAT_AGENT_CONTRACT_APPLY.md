# Apply CCAT Agent Contract

You are a host-local Codex agent applying a shared CCAT operating contract.

Read `CCAT_AGENT_CONTRACT.md` from the same directory as this prompt before
making changes.

## Scope

Your task is limited to instruction alignment and status reporting.

Allowed:

- Inspect the current host and workspace.
- Find relevant `AGENTS.md` files.
- Add a concise pointer to `CCAT_AGENT_CONTRACT.md` where appropriate.
- Write or update a local `CCAT_AGENT_CONTRACT_STATUS.md` report.

Not allowed without separate explicit user approval:

- Build packages.
- Install or deploy artifacts.
- Publish branches, tags, releases, manifests, or package feeds.
- Run paid or quota-sensitive tests.
- Delete files outside obvious temporary files created by this task.
- Change Android settings, permissions, accessibility state, notification
  policy, or audio volume.

## Required Confirmation

Before editing anything, gather and report:

- `hostname`
- `pwd`
- `date -Iseconds`
- `git rev-parse --show-toplevel`, if inside a Git repo
- `git status --short --branch`, if inside a Git repo
- `git remote -v`, if inside a Git repo
- On Android/Termux, `getprop ro.product.model` and
  `getprop ro.build.version.release`, if `getprop` exists
- Relevant top-level `AGENTS.md` files found near the working directory

If you cannot confidently identify the correct instruction surface, write the
status report and stop without editing instructions.

## Instruction Update Pattern

Prefer a small pointer over duplicating the full contract.

Suggested text:

```markdown
## CCAT Multi-Host Contract

- For CCAT/CCVA work across Devbox, Pixel 6a, and Pixel 9, follow
  `CCAT_AGENT_CONTRACT.md` in this workspace when it does not conflict with
  closer repository-specific `AGENTS.md` instructions.
- Confirm the current host, repo root, branch, dirty status, and relevant live
  deployment or skill paths before changing source, instructions, deployments,
  or installed artifacts.
```

Use the closest appropriate relative path if the contract lives outside the
directory containing the `AGENTS.md` file.

Do not add this block repeatedly. If a pointer already exists, update it rather
than adding a duplicate.

## Status Report

Create or update `CCAT_AGENT_CONTRACT_STATUS.md` next to this prompt.

Include:

- Host identity.
- Confirmed role from `CCAT_AGENT_CONTRACT.md`, or note uncertainty.
- Files inspected.
- Files changed.
- Git status before and after, when available.
- Any discrepancies between local evidence and the contract.
- Recommended next action, if any.

## Stop Conditions

Stop and report instead of editing if:

- You are not sure which `AGENTS.md` controls the target workspace.
- The working tree has instruction changes that appear unrelated and risky to
  merge with.
- The contract appears stale compared with clear local evidence.
- The requested action would require a build, install, deployment, publication,
  deletion, paid test, or disruptive Android setting change.
