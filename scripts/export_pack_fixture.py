"""
Export the art-pack parity fixture for the Android app's JVM tests.

Builds a tiny art_index.sqlite IN-SCRIPT with the builder's real schema
(art_index._connect) — unicode names, a DFC, an Alchemy "A-" twin, a v1 row
with no fine hash (must be left out, exactly as ArtIndex._load leaves it out),
and a digital representative flagged from a mini unique_artwork bulk file —
exports it with the SERVER's own writer (mtg_card_scanner.art_pack), and
writes to android/core/src/test/resources/artpack/:

    fixture.bin.gz   the pack, gzipped exactly as CI publishes it
    expected.json    what the reader must produce, row by row

ArtPackTest.kt must read the pack back to expected.json; CI re-runs this with
--check and fails on any byte of drift, so the Kotlin reader is always held
to the writer that ships.

    python scripts/export_pack_fixture.py          # (re)write fixtures
    python scripts/export_pack_fixture.py --check  # exit 1 if stale
"""

from __future__ import annotations

import gzip
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from mtg_card_scanner import art_pack  # noqa: E402
from mtg_card_scanner.art_index import _connect  # noqa: E402

OUT = ROOT / "android" / "core" / "src" / "test" / "resources" / "artpack"
BUILD_TIME = 1790000000                     # fixed: fixtures must be byte-stable
BULK_UPDATED_AT = "2026-09-27T09:02:28.829+00:00"

# (scryfall_id, name, set, number, artist, hash_hex, hash256_hex, digital)
# Inserted deliberately OUT of id order — the pack sorts by id.
ROWS = [
    ("f0e1d2c3-0000-4000-8000-000000000005", "Shivan Dragon", "m15", "281", "Donato Giancola",
     "ffffffffffffffff", "f" * 64, True),
    ("0a1b2c3d-0000-4000-8000-000000000001", "Æther Vial", "dst", "91", "Karl Kopinski",
     "8000000000000001", "8000000000000000" "0000000000000001" "7fffffffffffffff" "0123456789abcdef", False),
    ("5e6f7a8b-0000-4000-8000-000000000003", "Delver of Secrets // Insectile Aberration", "isd", "51",
     "Matt Stewart", "0123456789abcdef", "fedcba9876543210" * 4, False),
    ("3c4d5e6f-0000-4000-8000-000000000002", "Lim-Dûl's Vault", "all", "184a", "Rob Alexander",
     "0000000000000000", "0" * 64, False),
    ("9a8b7c6d-0000-4000-8000-000000000004", "A-Return Upon the Tide", "ymid", "A-1", "",
     "deadbeefcafef00d", "deadbeefcafef00d" "0badc0de00000000" "ffffffff00000000" "123456789abcdef0", True),
    ("77777777-0000-4000-8000-000000000006", "Forest", "lea", "294", "Christopher Rush",
     "1111111111111111", "2222222222222222" * 4, False),
]
V1_ROW = ("11111111-0000-4000-8000-00000000000f", "Old Row", "lea", "1", "x",
          "abcdefabcdefabcd", None)             # no fine hash → not in the pack


def build_sqlite(index_dir: Path) -> tuple[Path, Path]:
    """The tiny index + its bulk file; returns (db_path, bulk_path)."""
    db = index_dir / "art_index.sqlite"
    conn = _connect(db)
    conn.executemany("INSERT INTO art_hashes VALUES (?, ?, ?, ?, ?, ?, ?)",
                     [r[:7] for r in ROWS] + [V1_ROW])
    conn.execute("INSERT INTO meta (key, value) VALUES ('bulk_updated_at', ?)", (BULK_UPDATED_AT,))
    conn.commit()
    conn.close()
    bulk = index_dir / "unique-artwork.json"
    bulk.write_text("\n".join(json.dumps({"id": r[0], "digital": r[7]}) for r in ROWS) + "\n")
    return db, bulk


def build() -> dict[str, bytes]:
    with tempfile.TemporaryDirectory() as d:
        db, bulk = build_sqlite(Path(d))
        out = Path(d) / "fixture.bin.gz"
        manifest = art_pack.export_pack(db, out, bulk_path=bulk, build_time=BUILD_TIME)
        pack = out.read_bytes()
    decoded = art_pack.decode_pack(pack)
    expected = {
        "format_version": decoded.format_version,
        "build_time": decoded.build_time,
        "bulk_updated_at": decoded.bulk_updated_at,
        "known_flags": decoded.known_flags,
        "pack_sha256": manifest["pack_sha256"],
        "rows": [{"scryfall_id": r.scryfall_id, "name": r.name, "set_code": r.set_code,
                  "collector_number": r.collector_number, "artist": r.artist,
                  "hash_hex": r.hash_hex, "hash256_hex": r.hash256_hex, "flags": r.flags}
                 for r in decoded.rows()],
    }
    return {"fixture.bin.gz": pack,
            "expected.json": (json.dumps(expected, indent=1, sort_keys=True,
                                         ensure_ascii=False) + "\n").encode("utf-8")}


def _content(name: str, data: bytes) -> bytes:
    """What --check compares: a .gz by its decompressed pack — zlib builds may
    legitimately differ in compressed bytes; the pack itself may not."""
    if name.endswith(".gz"):
        return gzip.decompress(data)
    # A Windows checkout may have rewritten LF as CRLF; the content is the same.
    return data.replace(b"\r\n", b"\n")


def main() -> int:
    files = build()
    if "--check" in sys.argv:
        stale = [n for n, b in files.items()
                 if not (OUT / n).exists() or _content(n, (OUT / n).read_bytes()) != _content(n, b)]
        extra = [p.name for p in OUT.glob("*") if p.name not in files] if OUT.exists() else []
        if stale or extra:
            print("art-pack fixtures are stale — run: python scripts/export_pack_fixture.py")
            for n in stale + extra:
                print("  ", n)
            return 1
        print(f"art-pack fixtures up to date ({len(files)} files)")
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    for p in OUT.glob("*"):
        if p.name not in files:
            p.unlink()
    for n, b in files.items():
        (OUT / n).write_bytes(b)
    print(f"wrote {len(files)} art-pack fixtures to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
