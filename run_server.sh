#!/usr/bin/env bash
# DEV convenience wrapper. The supported way to run the scanner is the
# packaged launcher (`mtg-card-scanner`, or `.venv/Scripts/mtg-card-scanner`
# after `pip install -e .`) — it generates the TLS cert in pure Python,
# detects the LAN IP, prints a QR for the phone, and opens the desktop UI.
set -euo pipefail
cd "$(dirname "$0")"

if [ -x ".venv/Scripts/mtg-card-scanner.exe" ]; then      # Windows venv
  exec ".venv/Scripts/mtg-card-scanner.exe" "$@"
elif [ -x ".venv/bin/mtg-card-scanner" ]; then            # POSIX venv
  exec ".venv/bin/mtg-card-scanner" "$@"
elif command -v mtg-card-scanner >/dev/null 2>&1; then
  exec mtg-card-scanner "$@"
else
  echo "mtg-card-scanner not found. Install it first:  pip install -e ." >&2
  exit 1
fi
