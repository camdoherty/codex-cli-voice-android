#!/data/data/com.termux/files/usr/bin/sh
set -eu

REPO_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SCRIPTS_DIR="$HOME/scripts"
SHORTCUTS_DIR="$HOME/.shortcuts"

mkdir -p "$SCRIPTS_DIR" "$SHORTCUTS_DIR"

install -m 700 "$REPO_DIR/scripts/termux-codex-api" "$SCRIPTS_DIR/codex-api"
install -m 700 "$REPO_DIR/scripts/termux-codex-voice" "$SCRIPTS_DIR/codex-voice"

cat > "$SHORTCUTS_DIR/codex" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec codex
EOF

cat > "$SHORTCUTS_DIR/codex-voice" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec "$HOME/scripts/codex-voice"
EOF

chmod 700 "$SHORTCUTS_DIR/codex" "$SHORTCUTS_DIR/codex-voice"

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
    } >> "$rc"
fi

printf 'Installed codex-api/codex-voice launchers and Termux:Widget shortcuts.\n'
