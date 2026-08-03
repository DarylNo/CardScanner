"""
One-command launcher — everything a fresh machine needs to start scanning.

    mtg-card-scanner            # after `pipx install mtg-card-scanner`

Replaces run_server.sh and its sharp edges: the TLS certificate is generated
in pure Python (the openssl subprocess silently broke under Git Bash's path
mangling), the LAN IP is detected and printed with a QR code so the phone can
join by pointing its camera at the terminal or the desktop page, scan data
lives in a per-user directory instead of the CWD, and the desktop UI opens
itself.  The one-time card-database build is driven from the desktop UI on
first run (Build button with progress) — no CLI step.
"""

from __future__ import annotations

import argparse
import os
import shutil
import socket
import subprocess
import sys
import threading
import webbrowser
from datetime import datetime, timedelta, timezone
from pathlib import Path

_DEFAULT_DATA_DIR = Path.home() / ".mtg-card-scanner"

# The server child exits with this code to request "update me, then restart".
UPDATE_EXIT_CODE = 42
_TARBALL_SPEC = ("mtg-card-scanner @ "
                 "https://github.com/DarylNo/CardScanner/archive/refs/heads/master.tar.gz")


def _run_update() -> None:
    """Apply an update using whatever installed us."""
    repo_root = Path(__file__).resolve().parents[1]
    if (repo_root / ".git").exists():                     # dev checkout
        print("  [launch] updating via git pull …")
        subprocess.call(["git", "-C", str(repo_root), "pull", "--ff-only"])
    elif shutil.which("uv"):                              # install.sh path
        print("  [launch] updating via uv tool install …")
        subprocess.call(["uv", "tool", "install", "--force", _TARBALL_SPEC])
    else:                                                 # pip/pipx-ish venv
        print("  [launch] updating via pip …")
        subprocess.call([sys.executable, "-m", "pip", "install", "--quiet",
                         "--force-reinstall",
                         "https://github.com/DarylNo/CardScanner/archive/refs/heads/master.tar.gz"])


def ensure_certs(cert_dir: Path) -> tuple[Path, Path]:
    """Create (once) and return a self-signed key/cert pair.

    Phones only expose the camera to secure origins, so HTTPS is mandatory;
    a self-signed cert with a one-time browser warning is the simplest thing
    that works on a LAN.
    """
    key_path, crt_path = cert_dir / "key.pem", cert_dir / "cert.pem"
    if key_path.exists() and crt_path.exists():
        return key_path, crt_path

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "mtg-card-scanner")])
    now = datetime.now(timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name).issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=825))
        .sign(key, hashes.SHA256())
    )
    cert_dir.mkdir(parents=True, exist_ok=True)
    key_path.write_bytes(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption(),
    ))
    crt_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    print(f"  [launch] generated self-signed TLS cert in {cert_dir}")
    return key_path, crt_path


def lan_ip() -> str:
    """This machine's LAN address (the one the phone must dial)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))     # no packets sent — just picks a route
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def _print_qr(url: str) -> None:
    try:
        import segno
        print()
        segno.make(url).terminal(compact=True)
        print()
    except Exception:
        pass                            # terminal can't render it — URL is printed anyway


def _supervise(args) -> None:
    """
    Run the server as a child and restart it forever. This is what makes the
    browser's "Update & restart" button possible: the server exits with
    UPDATE_EXIT_CODE, we apply the update, and the loop relaunches the NEW
    code on the same port. Any other exit code ends the supervisor too.
    """
    passthrough = ["--serve", "--port", str(args.port), "--host", args.host,
                   "--data-dir", str(args.data_dir)]
    if args.no_browser:
        passthrough.append("--no-browser")
    first = True
    while True:
        if getattr(sys, "frozen", False):        # PyInstaller bundle
            cmd = [sys.executable, *passthrough]
        else:
            cmd = [sys.executable, "-m", "mtg_card_scanner.launch", *passthrough]
        # After an update-restart, don't pop a second browser tab.
        if not first and "--no-browser" not in cmd:
            cmd.append("--no-browser")
        rc = subprocess.call(cmd)
        if rc != UPDATE_EXIT_CODE:
            sys.exit(rc)
        print("  [launch] update requested from the browser …")
        _run_update()
        print("  [launch] restarting on the new version …")
        first = False


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        prog="mtg-card-scanner",
        description="Start the MTG card scanner (desktop UI + phone camera page).",
    )
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--data-dir", type=Path, default=_DEFAULT_DATA_DIR,
                        help="where scans, photos, and certs live (default: ~/.mtg-card-scanner)")
    parser.add_argument("--no-browser", action="store_true",
                        help="don't auto-open the desktop UI")
    parser.add_argument("--serve", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if not args.serve:
        _supervise(args)
        return

    data_dir: Path = args.data_dir
    data_dir.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("SCAN_DB", str(data_dir / "scans.db"))
    os.environ.setdefault("SCAN_IMAGES_DIR", str(data_dir / "scan_images"))

    key_path, crt_path = ensure_certs(data_dir / "certs")

    ip = lan_ip()
    desktop_url = f"https://{ip}:{args.port}/"
    phone_url = f"https://{ip}:{args.port}/phone"
    print()
    print(f"  Desktop (this computer):  {desktop_url}")
    print(f"  Phone camera:             {phone_url}")
    print("  Scan this with the phone camera to open the scanner")
    print("  (accept the one-time certificate warning on each device):")
    _print_qr(phone_url)

    if not args.no_browser:
        # Open after the server has had a beat to bind.
        threading.Timer(1.5, lambda: webbrowser.open(desktop_url)).start()

    import uvicorn
    from server.app import app
    uvicorn.run(app, host=args.host, port=args.port,
                ssl_keyfile=str(key_path), ssl_certfile=str(crt_path))


if __name__ == "__main__":
    main()
