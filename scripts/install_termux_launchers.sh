#!/data/data/com.termux/files/usr/bin/sh
set -eu

REPO_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SCRIPTS_DIR="$HOME/scripts"
SHORTCUTS_DIR="$HOME/.shortcuts"

mkdir -p "$SCRIPTS_DIR" "$SHORTCUTS_DIR"

install -m 700 "$REPO_DIR/scripts/termux-codex-api" "$SCRIPTS_DIR/codex-api"
install -m 700 "$REPO_DIR/scripts/termux-codex-voice" "$SCRIPTS_DIR/codex-voice"
install -m 700 "$REPO_DIR/scripts/install_stts_skill.sh" "$SCRIPTS_DIR/codex-install-stts"
install -m 700 "$REPO_DIR/scripts/ccva-tmux-run" "$SCRIPTS_DIR/ccva-tmux-run"

cat > "$SHORTCUTS_DIR/Codex" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
exec "$HOME/scripts/ccva-tmux-run" codex -- codex
EOF

cat > "$SHORTCUTS_DIR/Codex Resume Last" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
exec "$HOME/scripts/ccva-tmux-run" resume -- codex resume --last
EOF

cat > "$SHORTCUTS_DIR/Realtime API Voice" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
exec "$HOME/scripts/ccva-tmux-run" realtime -- "$HOME/scripts/codex-voice" --allow-realtime
EOF

cat > "$SHORTCUTS_DIR/STTS: Start + Talk" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" talk
EOF

cat > "$SHORTCUTS_DIR/STTS: Attach Session" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" session
EOF

cat > "$SHORTCUTS_DIR/STTS: Stop" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/stts/scripts/stts-session.sh" stop
EOF

chmod 700 \
    "$SHORTCUTS_DIR/Codex" \
    "$SHORTCUTS_DIR/Codex Resume Last" \
    "$SHORTCUTS_DIR/Realtime API Voice" \
    "$SHORTCUTS_DIR/STTS: Start + Talk" \
    "$SHORTCUTS_DIR/STTS: Attach Session" \
    "$SHORTCUTS_DIR/STTS: Stop"

OLD_SLUG="tts""-stt"
rm -f \
    "$SCRIPTS_DIR/codex-install-tts-stt" \
    "$SCRIPTS_DIR/${OLD_SLUG}-start" \
    "$SCRIPTS_DIR/${OLD_SLUG}-stop" \
    "$SCRIPTS_DIR/${OLD_SLUG}-status" \
    "$SCRIPTS_DIR/${OLD_SLUG}-diag" \
    "$SCRIPTS_DIR/${OLD_SLUG}-talk" \
    "$SHORTCUTS_DIR/codex" \
    "$SHORTCUTS_DIR/codex-voice" \
    "$SHORTCUTS_DIR/Start API(\$) Realtime Voice Mode" \
    "$SHORTCUTS_DIR/Start STTS Voice Mode" \
    "$SHORTCUTS_DIR/Open STTS Session" \
    "$SHORTCUTS_DIR/Stop STTS Voice Mode" \
    "$SHORTCUTS_DIR/stts-start" \
    "$SHORTCUTS_DIR/stts-talk" \
    "$SHORTCUTS_DIR/stts-stop" \
    "$SHORTCUTS_DIR/stts-status" \
    "$SHORTCUTS_DIR/stts-diag" \
    "$SHORTCUTS_DIR/STTS Loop" \
    "$SHORTCUTS_DIR/wake-voice-start" \
    "$SHORTCUTS_DIR/wake-voice-stop" \
    "$SHORTCUTS_DIR/wake-voice-doctor" \
    "$SHORTCUTS_DIR/${OLD_SLUG}-start" \
    "$SHORTCUTS_DIR/Start TTS"" STT Voice Mode" \
    "$SHORTCUTS_DIR/${OLD_SLUG}-talk" \
    "$SHORTCUTS_DIR/${OLD_SLUG}-stop" \
    "$SHORTCUTS_DIR/${OLD_SLUG}-status" \
    "$SHORTCUTS_DIR/${OLD_SLUG}-diag"

rc="$HOME/.profile"
if [ -n "${ZSH_VERSION:-}" ] || [ "$(basename "${SHELL:-}")" = zsh ]; then
    rc="$HOME/.zshrc"
fi
touch "$rc"

old_alias="codex-install-tts""-stt"
sed "/alias ${old_alias}=/d" "$rc" > "$rc.tmp"
mv "$rc.tmp" "$rc"

for alias_line in \
    'alias codex-api="$HOME/scripts/codex-api"' \
    'alias codex-voice="$HOME/scripts/codex-voice"' \
    'alias codex-install-stts="$HOME/scripts/codex-install-stts"'
do
    if ! grep -qxF "$alias_line" "$rc"; then
        printf '%s\n' "$alias_line" >> "$rc"
    fi
done

printf 'Installed codex-api/codex-voice launchers and Termux:Widget shortcuts.\n'
printf 'Installed core shortcuts: Codex, Codex Resume Last.\n'
printf 'Installed voice shortcuts: STTS: Start + Talk, Realtime API Voice.\n'
printf 'Installed STTS control shortcuts: STTS: Attach Session, STTS: Stop.\n'
