#!/data/data/com.termux/files/usr/bin/sh
set -eu

REPO_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SCRIPTS_DIR="$HOME/scripts"
SHORTCUTS_DIR="$HOME/.shortcuts"

mkdir -p "$SCRIPTS_DIR" "$SHORTCUTS_DIR"

install -m 700 "$REPO_DIR/scripts/termux-codex-api" "$SCRIPTS_DIR/codex-api"
install -m 700 "$REPO_DIR/scripts/termux-codex-voice" "$SCRIPTS_DIR/codex-voice"
install -m 700 "$REPO_DIR/scripts/install_tts_stt_skill.sh" "$SCRIPTS_DIR/codex-install-tts-stt"

cat > "$SHORTCUTS_DIR/codex" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec codex
EOF

cat > "$SHORTCUTS_DIR/Codex" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec codex
EOF

cat > "$SHORTCUTS_DIR/Codex Resume Last" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec codex resume --last
EOF

cat > "$SHORTCUTS_DIR/codex-voice" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec "$HOME/scripts/codex-voice"
EOF

cat > "$SHORTCUTS_DIR/Start API($) Realtime Voice Mode" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec "$HOME/scripts/codex-voice" --allow-realtime
EOF

cat > "$SHORTCUTS_DIR/tts-stt-start" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" start
EOF

cat > "$SHORTCUTS_DIR/Start TTS STT Voice Mode" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" start
EOF

cat > "$SHORTCUTS_DIR/tts-stt-stop" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" stop
EOF

cat > "$SHORTCUTS_DIR/tts-stt-status" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
"$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" status
printf '\nPress enter to close... '
read _answer
EOF

cat > "$SHORTCUTS_DIR/tts-stt-diag" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
"$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" diag
printf '\nPress enter to close... '
read _answer
EOF

chmod 700 \
    "$SHORTCUTS_DIR/codex" \
    "$SHORTCUTS_DIR/Codex" \
    "$SHORTCUTS_DIR/Codex Resume Last" \
    "$SHORTCUTS_DIR/codex-voice" \
    "$SHORTCUTS_DIR/Start API($) Realtime Voice Mode" \
    "$SHORTCUTS_DIR/tts-stt-start" \
    "$SHORTCUTS_DIR/Start TTS STT Voice Mode" \
    "$SHORTCUTS_DIR/tts-stt-stop" \
    "$SHORTCUTS_DIR/tts-stt-status" \
    "$SHORTCUTS_DIR/tts-stt-diag"

rc="$HOME/.profile"
if [ -n "${ZSH_VERSION:-}" ] || [ "$(basename "${SHELL:-}")" = zsh ]; then
    rc="$HOME/.zshrc"
fi
touch "$rc"

if ! grep -qxF 'alias codex-api="$HOME/scripts/codex-api"' "$rc"; then
    {
        printf '\n# Codex API/voice launchers\n'
        printf 'alias codex-api="$HOME/scripts/codex-api"\n'
        printf 'alias codex-voice="$HOME/scripts/codex-voice"\n'
        printf 'alias codex-install-tts-stt="$HOME/scripts/codex-install-tts-stt"\n'
    } >> "$rc"
fi

printf 'Installed codex-api/codex-voice launchers and Termux:Widget shortcuts.\n'
printf 'Installed core shortcuts: Codex, Codex Resume Last.\n'
printf 'Installed voice shortcuts: Start TTS STT Voice Mode, Start API($) Realtime Voice Mode.\n'
printf 'Installed tts-stt shortcuts: tts-stt-start, tts-stt-stop, tts-stt-status, tts-stt-diag.\n'
