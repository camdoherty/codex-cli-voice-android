#!/data/data/com.termux/files/usr/bin/sh
set -eu

CCVA_OWNER="${CCVA_OWNER:-camdoherty}"
CCVA_REPO="${CCVA_REPO:-codex-cli-voice-android}"
CCVA_CHANNEL="${CCVA_CHANNEL:-stable}"
CCVA_VERSION="${CCVA_VERSION:-latest}"
CCVA_RAW_REF="${CCVA_RAW_REF:-main}"
CCVA_PREFIX="${CCVA_PREFIX:-${PREFIX:-}}"
CCVA_CACHE_DIR="${CCVA_CACHE_DIR:-$HOME/.cache/ccva-installer}"
CCVA_DOWNLOAD_DIR="${CCVA_DOWNLOAD_DIR:-$HOME/storage/downloads}"
CCVA_WIDGET_DIR="${CCVA_WIDGET_DIR:-$HOME/.shortcuts}"
CCVA_INSTALL_SHIM="${CCVA_INSTALL_SHIM:-1}"
CCVA_INSTALL_WIDGETS="${CCVA_INSTALL_WIDGETS:-1}"
CCVA_RUN_SMOKE="${CCVA_RUN_SMOKE:-1}"
CCVA_VERIFY_ONLY="${CCVA_VERIFY_ONLY:-0}"
CCVA_RAW_BASE_URL="${CCVA_RAW_BASE_URL:-https://raw.githubusercontent.com/$CCVA_OWNER/$CCVA_REPO/$CCVA_RAW_REF}"
CCVA_RELEASE_BASE_URL="${CCVA_RELEASE_BASE_URL:-https://github.com/$CCVA_OWNER/$CCVA_REPO/releases/download}"

usage() {
    cat <<'EOF'
Usage: install.sh [options]

Options:
  --version VERSION      Install a specific release manifest.
  --channel CHANNEL      Install a channel manifest. Default: stable.
  --prefix DIR           Install CLI package into DIR. Default: $PREFIX.
  --cache-dir DIR        Download/cache directory.
  --download-dir DIR     Shared Android Downloads directory for APK staging.
  --no-shim              Do not download or stage the shim APK.
  --no-widgets           Do not install Termux:Widget shortcuts.
  --no-smoke             Do not run non-billable smoke checks.
  --verify-only          Download and verify assets without installing.
  -h, --help             Show this help.
EOF
}

log() {
    printf '%s\n' "ccva: $*" >&2
}

die() {
    printf '%s\n' "ccva: error: $*" >&2
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --version)
            [ "$#" -ge 2 ] || die "--version requires a value"
            CCVA_VERSION=$2
            shift 2
            ;;
        --channel)
            [ "$#" -ge 2 ] || die "--channel requires a value"
            CCVA_CHANNEL=$2
            shift 2
            ;;
        --prefix)
            [ "$#" -ge 2 ] || die "--prefix requires a value"
            CCVA_PREFIX=$2
            shift 2
            ;;
        --cache-dir)
            [ "$#" -ge 2 ] || die "--cache-dir requires a value"
            CCVA_CACHE_DIR=$2
            shift 2
            ;;
        --download-dir)
            [ "$#" -ge 2 ] || die "--download-dir requires a value"
            CCVA_DOWNLOAD_DIR=$2
            shift 2
            ;;
        --no-shim)
            CCVA_INSTALL_SHIM=0
            shift
            ;;
        --no-widgets)
            CCVA_INSTALL_WIDGETS=0
            shift
            ;;
        --no-smoke)
            CCVA_RUN_SMOKE=0
            shift
            ;;
        --verify-only)
            CCVA_VERIFY_ONLY=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

[ -n "$CCVA_PREFIX" ] || die "PREFIX is not set; run this inside Termux or pass --prefix"

case "$CCVA_CHANNEL" in
    stable) ;;
    *) die "unsupported channel: $CCVA_CHANNEL" ;;
esac

case "$CCVA_VERSION" in
    *[!A-Za-z0-9._-]*|""|.*) die "unsafe version: $CCVA_VERSION" ;;
esac

ensure_termux() {
    [ -d "$CCVA_PREFIX" ] || die "install prefix does not exist: $CCVA_PREFIX"
    case "$CCVA_PREFIX" in
        /data/data/com.termux/files/usr|*/com.termux/files/usr) ;;
        *) die "this installer is intended for Termux; unexpected prefix: $CCVA_PREFIX" ;;
    esac
    [ "$(uname -m)" = "aarch64" ] || die "unsupported architecture: $(uname -m); only aarch64 is supported"
}

install_missing_packages() {
    missing=""
    for cmd in bash curl sed tar sha256sum python; do
        if ! command -v "$cmd" >/dev/null 2>&1; then
            case "$cmd" in
                sha256sum) pkg_name=coreutils ;;
                python) pkg_name=python ;;
                *) pkg_name=$cmd ;;
            esac
            missing="$missing $pkg_name"
        fi
    done
    if ! command -v termux-volume >/dev/null 2>&1; then
        missing="$missing termux-api"
    fi
    if [ -n "$missing" ]; then
        command -v pkg >/dev/null 2>&1 || die "missing required tools and pkg is unavailable:$missing"
        log "installing missing Termux packages:$missing"
        pkg install -y $missing || die "pkg install failed; run pkg update and retry"
    fi
}

require_verify_tools() {
    for cmd in curl sed sha256sum; do
        command -v "$cmd" >/dev/null 2>&1 || {
            die "missing $cmd; install required tools or run without --verify-only"
        }
    done
}

json_string() {
    key=$1
    file=$2
    sed -n 's/.*"'$key'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$file" | sed -n '1p'
}

safe_asset_name() {
    name=$1
    case "$name" in
        *[!A-Za-z0-9._-]*|""|.*|*/*) return 1 ;;
        *) return 0 ;;
    esac
}

fetch() {
    url=$1
    out=$2
    log "fetching $url"
    curl -fsSL "$url" -o "$out"
}

verify_sha256() {
    file=$1
    expected=$2
    case "$expected" in
        ""|*[!0123456789abcdefABCDEF]*)
            die "invalid sha256 for $file"
            ;;
    esac
    [ "${#expected}" -eq 64 ] || die "invalid sha256 length for $file"
    actual=$(sha256sum "$file" | sed 's/[[:space:]].*//')
    [ "$actual" = "$expected" ] || die "checksum mismatch for $file"
}

resolve_manifest() {
    mkdir -p "$CCVA_CACHE_DIR"
    if [ "$CCVA_VERSION" = "latest" ]; then
        stable_file="$CCVA_CACHE_DIR/stable.json"
        fetch "$CCVA_RAW_BASE_URL/releases/$CCVA_CHANNEL.json" "$stable_file"
        manifest_path=$(json_string manifest "$stable_file")
        [ -n "$manifest_path" ] || die "stable manifest did not include manifest path"
        case "$manifest_path" in
            releases/*.json) ;;
            *) die "unsafe manifest path: $manifest_path" ;;
        esac
        manifest_url="$CCVA_RAW_BASE_URL/$manifest_path"
    else
        manifest_url="$CCVA_RAW_BASE_URL/releases/$CCVA_VERSION.json"
    fi
    manifest_file="$CCVA_CACHE_DIR/manifest.json"
    fetch "$manifest_url" "$manifest_file"
    printf '%s\n' "$manifest_file"
}

install_widgets() {
    scripts_dir="$HOME/scripts"
    shortcuts_dir="$CCVA_WIDGET_DIR"
    mkdir -p "$scripts_dir" "$shortcuts_dir"

    for name in codex-api codex-voice codex-install-tts-stt; do
        ln -sfn "$CCVA_PREFIX/bin/$name" "$scripts_dir/$name"
    done

    cat > "$shortcuts_dir/codex" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec codex
EOF
    cat > "$shortcuts_dir/Codex" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec codex
EOF
    cat > "$shortcuts_dir/Codex Resume Last" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec codex resume --last
EOF
    cat > "$shortcuts_dir/codex-voice" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec "$HOME/scripts/codex-voice"
EOF
    cat > "$shortcuts_dir/Start API($) Realtime Voice Mode" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec "$HOME/scripts/codex-voice" --allow-realtime
EOF
    cat > "$shortcuts_dir/tts-stt-start" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" start
EOF
    cat > "$shortcuts_dir/Start TTS STT Voice Mode" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" start
EOF
    cat > "$shortcuts_dir/tts-stt-stop" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" stop
EOF
    cat > "$shortcuts_dir/tts-stt-status" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
"$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" status
printf '\nPress enter to close... '
read _answer
EOF
    cat > "$shortcuts_dir/tts-stt-diag" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
"$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" diag
printf '\nPress enter to close... '
read _answer
EOF
    chmod 700 \
        "$shortcuts_dir/codex" \
        "$shortcuts_dir/Codex" \
        "$shortcuts_dir/Codex Resume Last" \
        "$shortcuts_dir/codex-voice" \
        "$shortcuts_dir/Start API($) Realtime Voice Mode" \
        "$shortcuts_dir/tts-stt-start" \
        "$shortcuts_dir/Start TTS STT Voice Mode" \
        "$shortcuts_dir/tts-stt-stop" \
        "$shortcuts_dir/tts-stt-status" \
        "$shortcuts_dir/tts-stt-diag"
}

remove_existing_install() {
    rm -rf "$CCVA_PREFIX/libexec/codex-cli-voice-android" "$CCVA_PREFIX/opt/codex-termux"
    rm -f \
        "$CCVA_PREFIX/bin/codex" \
        "$CCVA_PREFIX/bin/codex-api" \
        "$CCVA_PREFIX/bin/codex-voice" \
        "$CCVA_PREFIX/bin/codex-install-tts-stt"
}

ensure_storage_downloads() {
    if [ -d "$CCVA_DOWNLOAD_DIR" ]; then
        return
    fi
    if command -v termux-setup-storage >/dev/null 2>&1; then
        log "requesting shared storage access with termux-setup-storage"
        termux-setup-storage || die "termux-setup-storage failed"
        i=0
        while [ "$i" -lt 20 ]; do
            [ -d "$CCVA_DOWNLOAD_DIR" ] && return
            sleep 1
            i=$((i + 1))
        done
    fi
    die "shared Downloads directory not available: $CCVA_DOWNLOAD_DIR"
}

stage_shim_apk() {
    apk=$1
    ensure_storage_downloads
    mkdir -p "$CCVA_DOWNLOAD_DIR"
    dest="$CCVA_DOWNLOAD_DIR/codex-aec-shim-debug.apk"
    cp "$apk" "$dest"
    log "staged shim APK: $dest"
    if command -v termux-open >/dev/null 2>&1; then
        termux-open "$dest" || true
    fi
}

run_smoke() {
    log "running non-billable smoke checks"
    codex --version
    codex-api --version
    codex-install-tts-stt
    sh "$HOME/.codex/skills/tts-stt/scripts/tts-stt-session.sh" status

    set +e
    codex-voice >/dev/null 2>&1
    guard_exit=$?
    set -e
    [ "$guard_exit" -eq 2 ] || die "expected codex-voice billing guard exit 2, got $guard_exit"
}

print_next_steps() {
    cat <<EOF

Codex CLI Voice Android install complete.

Next steps:
1. Install the Termux:API Android app from F-Droid if you have not already.
EOF
    if [ "$CCVA_INSTALL_SHIM" = "1" ]; then
        cat <<EOF
2. Install the staged shim APK from Android Downloads:
   $CCVA_DOWNLOAD_DIR/codex-aec-shim-debug.apk
3. Open the shim app from Android and grant microphone permission.
4. Verify the shim from Termux:
   python -c 'import socket; s=socket.socket(); s.settimeout(2); s.connect(("127.0.0.1", 8765)); print("port-open"); s.close()'
5. Start local voice with:
   \$tts-stt start
EOF
    else
        cat <<'EOF'
2. Shim APK staging was skipped. Install the shim APK manually before voice testing.
3. Open the shim app from Android and grant microphone permission.
4. Start local voice with:
   $tts-stt start
EOF
    fi
    cat <<'EOF'

Optional billable Realtime check:
   codex-voice --allow-realtime

Realtime is not started by this installer.
EOF
}

ensure_termux
if [ "$CCVA_VERIFY_ONLY" = "1" ]; then
    require_verify_tools
else
    install_missing_packages
fi

manifest_file=$(resolve_manifest)
version=$(json_string version "$manifest_file")
upstream_codex=$(json_string upstream_codex "$manifest_file")
arch=$(json_string arch "$manifest_file")
release_tag=$(json_string release_tag "$manifest_file")
cli_tarball=$(json_string cli_tarball "$manifest_file")
cli_sha256=$(json_string cli_sha256 "$manifest_file")
shim_apk=$(json_string shim_apk "$manifest_file")
shim_sha256=$(json_string shim_sha256 "$manifest_file")

[ -n "$version" ] || die "manifest missing version"
[ -n "$upstream_codex" ] || die "manifest missing upstream_codex"
[ "$arch" = "aarch64" ] || die "manifest arch is unsupported: $arch"
[ -n "$release_tag" ] || die "manifest missing release_tag"
safe_asset_name "$cli_tarball" || die "unsafe cli asset name: $cli_tarball"
[ -n "$cli_sha256" ] || die "manifest missing cli_sha256"

log "selected $version ($upstream_codex)"

cli_path="$CCVA_CACHE_DIR/$cli_tarball"
fetch "$CCVA_RELEASE_BASE_URL/$release_tag/$cli_tarball" "$cli_path"
verify_sha256 "$cli_path" "$cli_sha256"

apk_path=""
if [ "$CCVA_INSTALL_SHIM" = "1" ]; then
    safe_asset_name "$shim_apk" || die "unsafe shim asset name: $shim_apk"
    [ -n "$shim_sha256" ] || die "manifest missing shim_sha256"
    apk_path="$CCVA_CACHE_DIR/$shim_apk"
    fetch "$CCVA_RELEASE_BASE_URL/$release_tag/$shim_apk" "$apk_path"
    verify_sha256 "$apk_path" "$shim_sha256"
fi

if [ "$CCVA_VERIFY_ONLY" = "1" ]; then
    log "verify-only complete"
    exit 0
fi

log "extracting CLI package into $CCVA_PREFIX"
tar -tzf "$cli_path" >/dev/null
remove_existing_install
tar -xzf "$cli_path" -C "$CCVA_PREFIX"

codex-install-tts-stt

if [ "$CCVA_INSTALL_WIDGETS" = "1" ]; then
    install_widgets
    log "installed Termux:Widget shortcuts in $CCVA_WIDGET_DIR"
fi

if [ "$CCVA_INSTALL_SHIM" = "1" ]; then
    stage_shim_apk "$apk_path"
fi

if [ "$CCVA_RUN_SMOKE" = "1" ]; then
    run_smoke
fi

print_next_steps
