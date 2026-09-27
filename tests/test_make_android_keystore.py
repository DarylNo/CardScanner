"""scripts/make_android_keystore.py — the one-time Android release key.

The key can never be regenerated (a new key forces every phone to uninstall),
so what the script writes must be exactly what release.yml and
android/app/build.gradle.kts expect: PKCS12, alias "cardscanner", key password
= store password, and a base64 line that decodes back to the file byte for
byte. It must also refuse to clobber an existing key.
"""

import base64
import importlib.util
import re
from datetime import timedelta
from pathlib import Path

from cryptography.hazmat.primitives.serialization import pkcs12

_SPEC = importlib.util.spec_from_file_location(
    "make_android_keystore",
    Path(__file__).resolve().parent.parent / "scripts" / "make_android_keystore.py")
mak = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(mak)


def _run(tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)
    rc = mak.main([])
    return rc, capsys.readouterr()


def test_keystore_matches_what_the_release_build_reads(tmp_path, monkeypatch, capsys):
    rc, out = _run(tmp_path, monkeypatch, capsys)
    assert rc == 0
    p12 = (tmp_path / "cardscanner-release.p12").read_bytes()

    password = re.search(r"^ {4}([A-Za-z0-9_-]{32})$", out.out, re.M).group(1)
    ks = pkcs12.load_pkcs12(p12, password.encode())
    assert ks.key is not None and ks.key.key_size == 3072
    assert ks.cert.friendly_name == b"cardscanner"          # keytool's alias
    cert = ks.cert.certificate
    assert cert.subject.rfc4514_string() == "CN=CardScanner"
    assert cert.subject == cert.issuer                     # self-signed
    life = cert.not_valid_after_utc - cert.not_valid_before_utc
    assert life >= timedelta(days=365 * 30)

    # The secret is ONE base64 line that round-trips to the exact file.
    b64 = out.out.split("ANDROID_KEYSTORE_B64:\n", 1)[1].splitlines()[1]
    assert base64.b64decode(b64, validate=True) == p12
    assert "ANDROID_KEYSTORE_PASSWORD" in out.out and "ANDROID_KEY_ALIAS" in out.out
    out.out.encode("ascii")          # a cp1252 console must never choke on it


def test_refuses_to_overwrite_an_existing_key(tmp_path, monkeypatch, capsys):
    existing = tmp_path / "cardscanner-release.p12"
    existing.write_bytes(b"the key the installed app was signed with")
    rc, out = _run(tmp_path, monkeypatch, capsys)
    assert rc == 1
    assert existing.read_bytes() == b"the key the installed app was signed with"
    assert "refusing to overwrite" in out.err
    assert out.out == ""             # no password / secret printed for a key not written
