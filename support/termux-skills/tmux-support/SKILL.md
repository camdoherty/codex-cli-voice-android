---
name: tmux-support
description: Inspect and assist with user-selected tmux panes on this Termux phone using the stable Codex handoff file. Use when the user asks for tmux pane troubleshooting, sensitive prompt support, password/passphrase workflows where the user must type secrets directly, or read/send/read assistance for an interactive shell, SSH session, router console, installer, editor, or REPL running inside tmux.
---

# Tmux Support

## Contract

Use the handoff file written by the Termux `copy` extra key:

```text
/data/data/com.termux/files/usr/tmp/codex-tmux-pane-context.txt
```

The first line is backward-compatible:

```text
<tmux-socket>,<server-pid>,<client-index> <pane-id>
```

Later lines may include `timestamp`, `epoch`, `socket`, `pane`, `target`, `command`, `pane_dead`, and `pane_in_mode`.

Prefer the bundled helper for parsing and tmux access:

```sh
skill="$HOME/.codex/skills/tmux-support"
"$skill/scripts/tmux_context.sh" info
"$skill/scripts/tmux_context.sh" capture -80
```

## Workflow

1. Read `info` first to confirm the socket, pane, current command, dead state, and copy-mode state.
2. Capture recent output with `capture -80` or a larger negative start such as `capture -200` when context is thin.
3. Explain what the pane appears to need before sending anything.
4. If a secret is requested, do not send keys. Tell the user to type the secret directly in the tmux pane, then press the Termux `copy` key again.
5. If a non-secret command is appropriate and the user asked for assistance, send it with:

```sh
"$skill/scripts/tmux_context.sh" send 'non-secret command here'
"$skill/scripts/tmux_context.sh" capture -80
```

## Sensitive Rules

Treat these as secrets: passwords, passphrases, sudo prompts, SSH key unlocks, API keys, tokens, cookies, recovery codes, OTPs, private keys, router credentials, and account logins.

Never ask the user to paste a secret into chat. Never send a guessed secret with `send`. For sensitive prompts, use this pattern:

```text
The pane is asking for <secret type>. Type it directly in the tmux pane, press Enter, then press the copy key again so I can inspect the next screen.
```

## User Input Rules

For any interactive prompt, first classify it before sending keys:

- Secret auth or private input: do not send it. Tell the user to type it directly in the tmux pane, press Enter, then press the Termux `copy` key again.
- Non-secret confirmation or selection: explain what the prompt is asking and send a response only when the user already asked for that action or the safe answer is unambiguous.
- Ambiguous prompts: ask the user to choose in chat or type directly in the pane, then press the Termux `copy` key again.

## Sending Rules

Only send commands when they are non-secret and the action is clear from the user request.

Prefer read-only probes first:

```sh
pwd
whoami
date
ls
git status --short
ip addr
ip route
```

For mutating commands, confirm the user asked for that mutation in the current conversation. Avoid broad destructive actions.

## Stale Or Invalid Context

If the context file is missing or the socket no longer exists, the helper will try to create the standard shared pane by running:

```sh
/data/data/com.termux/files/home/scripts/tmux-codex-shared-pane
```

If the context file is stale, references a dead pane, or cannot be refreshed automatically, ask the user to press the Termux `copy` key from the target tmux pane again.

The standard shared pane is named with pane title `codex-shared-shell` in tmux session `codex`, window `codex`, and starts a minimal `zsh -f` shell with color-related environment variables disabled for cleaner captures.

If `pane_in_mode=1`, the pane may be in copy-mode or another tmux mode. Capture can still work, but sending shell commands may not go to the shell. Tell the user what you see and ask them to exit the mode or press `copy` again from the active prompt.

## Direct Tmux Commands

If the helper needs adjustment, the underlying pattern is:

```sh
tmux -S "$socket" display-message -p -t "$pane" '#{pane_id} #{pane_current_command}'
tmux -S "$socket" capture-pane -p -t "$pane" -S -80
tmux -S "$socket" send-keys -t "$pane" -- "$command" Enter
```
