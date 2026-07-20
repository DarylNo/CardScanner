#!/usr/bin/env bash
# Launch the card-scanner web app.
#
# The phone browser is the camera, and browsers only allow camera access
# (getUserMedia) in a SECURE context — https:// or localhost. So to use your
# phone on the LAN this MUST be served over HTTPS. This script generates a
# self-signed cert on first run; accept the one-time browser warning on the phone.
#
#   Desktop UI : https://<this-machine-ip>:8443/
#   Phone camera: https://<this-machine-ip>:8443/phone
#
# Env overrides: PORT, SCAN_DB, HOST
set -euo pipefail
cd "$(dirname "$0")"

HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8443}"
CERT_DIR="${CERT_DIR:-.certs}"
KEY="$CERT_DIR/key.pem"
CRT="$CERT_DIR/cert.pem"

mkdir -p "$CERT_DIR"
if [ ! -f "$KEY" ] || [ ! -f "$CRT" ]; then
  echo "[run_server] generating self-signed TLS cert in $CERT_DIR ..."
  openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
    -keyout "$KEY" -out "$CRT" -subj "/CN=mtg-card-scanner" >/dev/null 2>&1
fi

echo "[run_server] Desktop:  https://<your-ip>:$PORT/"
echo "[run_server] Phone:    https://<your-ip>:$PORT/phone"
exec uvicorn server.app:app --host "$HOST" --port "$PORT" \
  --ssl-keyfile "$KEY" --ssl-certfile "$CRT"
