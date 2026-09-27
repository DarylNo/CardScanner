"""
Generate the Android app's release signing key — once, ever.

    python scripts/make_android_keystore.py

Writes cardscanner-release.p12 (PKCS12: RSA 3072, self-signed CN=CardScanner,
valid 30 years, alias "cardscanner") to the CURRENT directory, then prints the
three GitHub secrets release.yml signs the APK with, and where to paste them.

Why it matters: Android installs an update only when it is signed with the
SAME key as the installed app. Lose this file (or its password) and every
phone has to uninstall — losing its pairing and settings — to take the next
release. Keep a backup somewhere safe (password manager / encrypted drive);
never commit it (*.p12 is gitignored).

Pure `cryptography` (already a dependency) — no keytool or openssl needed.
Refuses to overwrite an existing keystore.
"""

from __future__ import annotations

import base64
import os
import secrets
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

FILENAME = "cardscanner-release.p12"
ALIAS = "cardscanner"
KEY_BITS = 3072
YEARS = 30
REPO = "DarylNo/CardScanner"


def build_keystore(password: str) -> tuple[bytes, x509.Certificate]:
    """PKCS12 bytes + the certificate, encrypted with `password`.

    Explicit PBES2/AES-256 + HMAC-SHA256 (not the legacy 3DES/RC2 PKCS12
    defaults): JDK 17 keytool, AGP's signer and apksigner all read it.
    The key password equals the store password — the PKCS12 convention, and
    what android/app/build.gradle.kts passes for both.
    """
    key = rsa.generate_private_key(public_exponent=65537, key_size=KEY_BITS)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "CardScanner")])
    now = datetime.now(timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name).issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=365 * YEARS + YEARS // 4))
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()),
                       critical=False)
        .sign(key, hashes.SHA256())
    )
    enc = (serialization.PrivateFormat.PKCS12.encryption_builder()
           .kdf_rounds(100_000)
           .key_cert_algorithm(pkcs12.PBES.PBESv2SHA256AndAES256CBC)
           .hmac_hash(hashes.SHA256())
           .build(password.encode()))
    p12 = pkcs12.serialize_key_and_certificates(ALIAS.encode(), key, cert, None, enc)
    return p12, cert


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if argv and argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    out = Path.cwd() / FILENAME
    if out.exists():
        print(f"{out} already exists - refusing to overwrite a signing key.\n"
              "If it is the key your installed app was signed with, KEEP it: a new key\n"
              "means every phone must uninstall before it can take the next release.",
              file=sys.stderr)
        return 1

    password = secrets.token_urlsafe(24)       # 32 chars, ~192 bits, shell/paste safe
    p12, cert = build_keystore(password)
    # "x" = exclusive create: never clobber a key that appeared meanwhile.
    with open(out, "xb") as f:
        os.chmod(out, 0o600)                   # owner-only before the key lands
        f.write(p12)

    b64 = base64.b64encode(p12).decode()
    sha256 = cert.fingerprint(hashes.SHA256()).hex()   # apksigner --print-certs format
    rule = "-" * 72
    print(f"""
Wrote {out}  (alias "{ALIAS}", RSA {KEY_BITS}, valid until {cert.not_valid_after_utc:%Y-%m-%d})
Certificate SHA-256: {sha256}

The password is shown ONCE - it is not stored anywhere else:

    {password}

BACK UP NOW: copy {FILENAME} AND the password to a password manager or other
safe place. Losing either means phones must uninstall to take future updates.
Never commit the file (it is gitignored) and don't leave it in a synced folder.

Add three repository secrets on GitHub:
  1. Open https://github.com/{REPO}/settings/secrets/actions
     (repo page -> Settings -> Secrets and variables -> Actions).
  2. Click "New repository secret", Name: ANDROID_KEYSTORE_B64
     Secret: the single base64 line between the rules below -> "Add secret".
  3. "New repository secret", Name: ANDROID_KEYSTORE_PASSWORD
     Secret: the password above -> "Add secret".
  4. "New repository secret", Name: ANDROID_KEY_ALIAS
     Secret: {ALIAS} -> "Add secret".
The next release (merge a version bump) builds mtg-card-scanner-android.apk
signed with this key; the release job summary shows the certificate SHA-256,
which must match the one printed above.

ANDROID_KEYSTORE_B64:
{rule}
{b64}
{rule}
""")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
