# CCAT Agent Contract Status

## Host Identity

- Hostname: `localhost`
- Working directory: `/data/data/com.termux/files/home`
- Timestamp: recorded during local validation
- Android model: `Pixel 9`
- Android release: `16`

## Confirmed Role

- `CCAT_AGENT_CONTRACT.md` identifies Pixel 9 as the testing, refinement,
  hardening, and regression device.
- Local Android properties match Pixel 9.

## Files Inspected

- `AGENTS.md`
- `dev/AGENTS.md`
- `dev/CCAT_AGENT_CONTRACT.md`
- `dev/CCAT_AGENT_CONTRACT_APPLY.md`

## Files Changed

- Created `dev/CCAT_AGENT_CONTRACT.md`.
- Created `dev/CCAT_AGENT_CONTRACT_APPLY.md`.
- Created `dev/CCAT_AGENT_CONTRACT_STATUS.md`.
- Updated `AGENTS.md` with a concise pointer to the contract.
- Updated `dev/AGENTS.md` with a concise pointer to the contract.

## Git Status

- Repo root: `/data/data/com.termux/files/home`
- Branch status: local working tree had pre-existing changes
- Relevant changed files after update:
  - `M AGENTS.md`
  - `M dev/AGENTS.md`
  - `?? dev/CCAT_AGENT_CONTRACT.md`
  - `?? dev/CCAT_AGENT_CONTRACT_APPLY.md`
  - `?? dev/CCAT_AGENT_CONTRACT_STATUS.md`

## Discrepancies

- Hostname is generic `localhost`, so Android model evidence is required to
  distinguish this Pixel 9 from Pixel 6a.
- The working tree has other pre-existing changes outside this contract work;
  those were not modified or staged.

## Recommended Next Action

- If this contract should become canonical, commit or otherwise hand off the
  Pixel 9 instruction updates and contract files intentionally.
- If the workstation repo should have a top-level `AGENTS.md`, create it
  explicitly and rerun the local `CCAT_AGENT_CONTRACT_APPLY.md`.
- If Pixel 6a should have home-level agent instructions, create or identify the
  correct `AGENTS.md` there and rerun
  the local `CCAT_AGENT_CONTRACT_APPLY.md`.
