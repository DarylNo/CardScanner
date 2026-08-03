#!/usr/bin/env bash
# MTG Card Scanner — one-line installer for macOS and Linux.
#
#   curl -fsSL https://raw.githubusercontent.com/DarylNo/CardScanner/master/install.sh | bash
#
# Installs the scanner as a `mtg-card-scanner` command and drops a
# double-clickable launcher (macOS: ~/Applications, Linux: app menu).
# Uses uv (installing it if needed — uv also provides Python itself when the
# machine has none), falling back to pipx if that's what's available.
set -euo pipefail

# Tarball, not git+https: works on machines without git installed.
REPO="mtg-card-scanner @ https://github.com/DarylNo/CardScanner/archive/refs/heads/master.tar.gz"

say() { printf '\033[1;36m%s\033[0m\n' "$*"; }

# ── 1. get an installer tool ──────────────────────────────────────────────────
if ! command -v uv >/dev/null 2>&1 && ! command -v pipx >/dev/null 2>&1; then
  say "Installing uv (manages Python + tools; one-time)…"
  curl -LsSf https://astral.sh/uv/install.sh | sh
  export PATH="$HOME/.local/bin:$PATH"
fi

# ── 2. install the scanner ────────────────────────────────────────────────────
if command -v uv >/dev/null 2>&1; then
  say "Installing MTG Card Scanner via uv…"
  uv tool install --force --python 3.12 "$REPO"
else
  say "Installing MTG Card Scanner via pipx…"
  pipx install --force "$REPO"
fi

BIN="$(command -v mtg-card-scanner || echo "$HOME/.local/bin/mtg-card-scanner")"

# ── 3. double-clickable launcher ──────────────────────────────────────────────
case "$(uname -s)" in
  Darwin)
    APP="$HOME/Applications/MTG Card Scanner.command"
    mkdir -p "$HOME/Applications"
    printf '#!/bin/bash\nexec "%s"\n' "$BIN" > "$APP"
    chmod +x "$APP"
    say "Launcher created: $APP  (double-click it in Finder)"
    ;;
  Linux)
    DESK="$HOME/.local/share/applications/mtg-card-scanner.desktop"
    mkdir -p "${DESK%/*}"
    cat > "$DESK" <<EOF
[Desktop Entry]
Type=Application
Name=MTG Card Scanner
Comment=Scan Magic cards with your phone camera
Exec=$BIN
Terminal=true
Categories=Utility;
EOF
    say "App-menu launcher installed: MTG Card Scanner"
    ;;
esac

# ── 4. make sure `mtg-card-scanner` works in THIS shell and future ones ───────
# uv/pipx install to ~/.local/bin, which a terminal opened BEFORE the install
# may not have on PATH ("command not found" right after a successful install).
case ":$PATH:" in
  *":$HOME/.local/bin:"*) ;;
  *) if ! grep -qs '\.local/bin' "$HOME/.bashrc" 2>/dev/null; then
       printf '\nexport PATH="$HOME/.local/bin:$PATH"\n' >> "$HOME/.bashrc"
     fi ;;
esac

say ""
say "Done. Start it with:  mtg-card-scanner"
if ! command -v mtg-card-scanner >/dev/null 2>&1; then
  say "(This terminal was opened before the install — close it and open a new"
  say " one first, or just launch \"MTG Card Scanner\" from the app menu.)"
fi
say "It opens the desktop UI and shows a QR code to connect your phone."
