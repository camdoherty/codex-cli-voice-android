# Troubleshooting

## `codex-voice` Exits Immediately

This is intentional unless realtime billing is explicitly allowed:

```sh
codex-voice --allow-realtime
```

or:

```sh
CODEX_VOICE_ALLOW_REALTIME=1 codex-voice
```

## Missing API Key

Set `OPENAI_API_KEY` or create a key file:

```sh
printf '%s\n' 'OPENAI_API_KEY=REDACTED' > "$HOME/.oaienv"
chmod 600 "$HOME/.oaienv"
```

You can use another path with `OPENAI_API_KEY_FILE`.

## `WARNING: flock unsupported on Android`

The packaged `codex-api` and `codex-voice` launchers filter this known Android warning. If it appears during launch, check that your shell is resolving the expected launcher:

```sh
command -v codex-voice
readlink "$HOME/scripts/codex-voice"
codex --version
```

Redeploy the latest package if an old launcher or old install path is still shadowing the current one.

## Shim Not Connecting

Check the Android app is installed, microphone permission is granted, and the service is listening on:

```text
ws://127.0.0.1:8765/v1/audio
```

Then launch:

```sh
CODEX_ANDROID_AUDIO_WS_URL=ws://127.0.0.1:8765/v1/audio codex-voice --allow-realtime
```

## SSH Deploy Fails

Confirm `.env` contains the right Termux SSH target:

```text
PIXEL_HOST=android-device-host-or-ip
PIXEL_USER=termux-ssh-user
PIXEL_PORT=8022
```

If you use a dedicated private key, set:

```text
PIXEL_IDENTITY=$HOME/.ssh/id_ed25519
```

The deploy script uses `SSH_CONFIG=/dev/null` by default so host machine aliases do not leak into public automation.
