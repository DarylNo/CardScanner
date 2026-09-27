"""
Art pack — the artwork fingerprint index as one compact, versioned file.

The phone can't build the index itself (~90 min, ~0.5 GB of images), so CI
builds it with `art_index build` and publishes this pack (`art-pack.yml` →
the rolling `art-pack` release). The pack holds exactly what
`ArtIndex._load()` reads from `art_index.sqlite` — every row whose fine hash
is present — so identification on the phone sees the same index the rig does.

    python -m mtg_card_scanner.art_pack export --out art-pack.bin.gz \
        [--db PATH] [--bulk PATH | --no-bulk] [--build-time EPOCH] \
        [--manifest art-pack.json]
    python -m mtg_card_scanner.art_pack info art-pack.bin.gz

`--out` ending in `.gz` writes the gzip-wrapped distribution file (written
with mtime 0 and no file name, so it is byte-deterministic too); anything
else writes the raw pack. Readers (this module, `ArtPack.kt`) accept both.

Layout, format version 1 — all integers little-endian:

    offset      size      field
    0           5         magic  b"MTGAP"
    5           1         u8   format version (= 1)
    6           2         u16  header size in bytes (= 64)
    8           8         i64  build time, unix seconds UTC
    16          32        bulk_updated_at: Scryfall `unique_artwork` revision
                          the index was built from (ISO-8601 ASCII, NUL padded;
                          empty when unknown)
    48          4         u32  n = row count
    52          4         u32  S = string blob size in bytes
    56          4         u32  known-flags mask: which `flags` bits carry
                          information in THIS pack (bit 0 set → the digital
                          bit was derived from the bulk file; clear → every
                          digital bit is 0 because nobody knew)
    60          4         u32  reserved (= 0)
    64          8n        h64[n]   u64 — the 64-bit art pHash (`hash_hex`)
    64+8n       32n       h256[4n] u64 — row i's 256-bit pHash in words
                          4i..4i+3, word 0 = the most significant 64 bits of
                          `hash256_hex` (art_index._split_u64 order)
    64+40n      n         flags[n] u8 — bit 0: digital-only printing
                          (Arena/MTGO); bits 1-7 reserved (= 0)
    64+41n      4(5n+1)   offsets[5n+1] u32 into the blob; field k of row i
                          is blob[offsets[5i+k] : offsets[5i+k+1]], fields
                          k = 0 scryfall_id, 1 name, 2 set_code,
                          3 collector_number, 4 artist; offsets[5n] = S
    64+61n+4    S         blob — UTF-8 strings, back to back
    end-32      32        SHA-256 of every preceding byte (header included)

Rows are sorted by scryfall_id, and nothing in the file depends on sqlite row
order or the clock (build time is an INPUT: `--build-time`, else
$SOURCE_DATE_EPOCH, else now), so identical inputs give identical bytes.

Digital flag: the sqlite does not record it (digital representatives are
kept IN the identification index on purpose — see art_index._should_index —
and candidates are later filtered to paper by the Scryfall printings query,
not by the index). The exporter derives it from the `unique_artwork` bulk
file that sits beside the sqlite when that file is available; identification
must keep ignoring it — it exists so later stages can tell a digital-only
representative apart without a network lookup.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import gzip
import hashlib
import io
import json
import os
import sqlite3
from contextlib import closing
import struct
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Optional, Sequence, Union

import numpy as np

MAGIC = b"MTGAP"
FORMAT_VERSION = 1
HEADER_SIZE = 64
FLAG_DIGITAL = 0x01
_HEADER = struct.Struct("<5sBHq32sIIII")      # 64 bytes
assert _HEADER.size == HEADER_SIZE
_FIELDS = 5                                    # id, name, set, number, artist
_SHA_LEN = 32
_MAX_ROWS = 10_000_000                         # sanity bound for readers

_DEFAULT_DB = Path.home() / ".cache" / "mtg-card-scanner" / "art_index" / "art_index.sqlite"


class ArtPackError(Exception):
    pass


@dataclass(frozen=True)
class PackRow:
    scryfall_id: str
    name: str
    set_code: str
    collector_number: str
    artist: str
    hash_hex: str          # 16 hex digits, as stored in art_index.sqlite
    hash256_hex: str       # 64 hex digits
    flags: int = 0


@dataclass
class ArtPack:
    format_version: int
    build_time: int
    bulk_updated_at: str
    known_flags: int
    h64: np.ndarray                  # uint64[n]
    h256: np.ndarray                 # uint64[n, 4]
    flags: np.ndarray                # uint8[n]
    meta: list[tuple[str, str, str, str, str]] = field(default_factory=list)

    def __len__(self) -> int:
        return len(self.meta)

    def rows(self) -> list[PackRow]:
        out = []
        for i, (sid, name, set_code, num, artist) in enumerate(self.meta):
            words = self.h256[i]
            h256 = 0
            for w in words:
                h256 = (h256 << 64) | int(w)
            out.append(PackRow(sid, name, set_code, num, artist,
                               f"{int(self.h64[i]):016x}", f"{h256:064x}",
                               int(self.flags[i])))
        return out


# ── write ─────────────────────────────────────────────────────────────────────

def read_sqlite_rows(db_path: Path) -> tuple[list[PackRow], str]:
    """Every row ArtIndex would load (fine hash present) + the bulk revision."""
    if not Path(db_path).exists():
        raise ArtPackError(f"no art index at {db_path} — run art_index build first")
    with closing(sqlite3.connect(db_path)) as conn:
        rows = conn.execute(
            "SELECT scryfall_id, name, set_code, collector_number, artist,"
            " hash_hex, hash256_hex FROM art_hashes"
            " WHERE hash256_hex IS NOT NULL"
        ).fetchall()
        try:
            r = conn.execute("SELECT value FROM meta WHERE key='bulk_updated_at'").fetchone()
        except sqlite3.OperationalError:
            r = None
    return ([PackRow(*(x if x is not None else "" for x in row)) for row in rows],
            (r[0] or "") if r else "")


def digital_ids_from_bulk(bulk_path: Path) -> set[str]:
    """Scryfall ids flagged `digital` in a unique_artwork bulk file (any format)."""
    from mtg_card_scanner.art_index import ArtIndexBuilder
    return {e["id"] for e in ArtIndexBuilder._load_bulk_entries(Path(bulk_path))
            if e.get("digital") and e.get("id")}


def encode_pack(rows: Iterable[PackRow], *, build_time: int,
                bulk_updated_at: str = "", known_flags: int = 0) -> bytes:
    """Serialize *rows* (any order) into a raw version-1 pack."""
    rows = sorted(rows, key=lambda r: r.scryfall_id)
    n = len(rows)
    ids = [r.scryfall_id for r in rows]
    if len(set(ids)) != n:
        raise ArtPackError("duplicate scryfall_id in pack rows")
    bulk = (bulk_updated_at or "").encode("ascii")
    if len(bulk) > 32:
        raise ArtPackError(f"bulk_updated_at too long for the header: {bulk_updated_at!r}")

    h64 = np.empty(n, dtype="<u8")
    h256 = np.empty((n, 4), dtype="<u8")
    flags = np.empty(n, dtype=np.uint8)
    offsets = np.empty(_FIELDS * n + 1, dtype="<u4")
    blob = bytearray()
    for i, r in enumerate(rows):
        if len(r.hash_hex) != 16 or len(r.hash256_hex) != 64:
            raise ArtPackError(f"{r.scryfall_id}: unexpected hash length")
        h64[i] = int(r.hash_hex, 16)
        v = int(r.hash256_hex, 16)
        h256[i] = [(v >> (64 * k)) & 0xFFFFFFFFFFFFFFFF for k in (3, 2, 1, 0)]
        flags[i] = r.flags & 0xFF
        for k, s in enumerate((r.scryfall_id, r.name, r.set_code,
                               r.collector_number, r.artist)):
            offsets[_FIELDS * i + k] = len(blob)
            blob += s.encode("utf-8")
    offsets[_FIELDS * n] = len(blob)
    if len(blob) >= 1 << 32:
        raise ArtPackError("string blob exceeds 4 GiB")

    header = _HEADER.pack(MAGIC, FORMAT_VERSION, HEADER_SIZE, int(build_time),
                          bulk.ljust(32, b"\0"), n, len(blob), known_flags, 0)
    body = b"".join((header, h64.tobytes(), h256.tobytes(), flags.tobytes(),
                     offsets.tobytes(), bytes(blob)))
    return body + hashlib.sha256(body).digest()


def gzip_bytes(raw: bytes) -> bytes:
    """Deterministic gzip: no file name, mtime 0."""
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0) as gz:
        gz.write(raw)
    return buf.getvalue()


def _resolve_build_time(build_time: Optional[int]) -> int:
    if build_time is not None:
        return int(build_time)
    env = os.environ.get("SOURCE_DATE_EPOCH")
    return int(env) if env else int(time.time())


def export_pack(db_path: Path, out_path: Path, *, bulk_path: Optional[Path] = None,
                build_time: Optional[int] = None,
                manifest_path: Optional[Path] = None) -> dict:
    """sqlite → pack file (gzipped when *out_path* ends in .gz). Returns the manifest."""
    rows, bulk_updated_at = read_sqlite_rows(Path(db_path))
    if not rows:
        raise ArtPackError(f"art index at {db_path} has no complete rows")
    known = 0
    if bulk_path is not None:
        digital = digital_ids_from_bulk(bulk_path)
        rows = [PackRow(**{**r.__dict__, "flags": r.flags | (FLAG_DIGITAL if r.scryfall_id in digital else 0)})
                for r in rows]
        known |= FLAG_DIGITAL
    bt = _resolve_build_time(build_time)
    raw = encode_pack(rows, build_time=bt, bulk_updated_at=bulk_updated_at, known_flags=known)
    data = gzip_bytes(raw) if str(out_path).endswith(".gz") else raw
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(data)
    manifest = {
        "format_version": FORMAT_VERSION,
        "build_time": bt,
        "build_date": _dt.datetime.fromtimestamp(bt, _dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "bulk_updated_at": bulk_updated_at,
        "rows": len(rows),
        "digital_flags": bool(known & FLAG_DIGITAL),
        "file": out_path.name,
        "size": len(data),                                   # bytes as downloaded
        "sha256": hashlib.sha256(data).hexdigest(),          # of the file as downloaded
        "raw_size": len(raw),
        "pack_sha256": raw[-_SHA_LEN:].hex(),                # the in-file trailer
    }
    if manifest_path is not None:
        Path(manifest_path).write_text(json.dumps(manifest, indent=1, sort_keys=True) + "\n")
    return manifest


# ── read ──────────────────────────────────────────────────────────────────────

def decode_pack(data: bytes) -> ArtPack:
    """Parse a pack (raw or gzipped); verifies magic, version and checksum."""
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    if len(data) < HEADER_SIZE + _SHA_LEN:
        raise ArtPackError("art pack truncated")
    (magic, version, header_size, build_time, bulk, n, s_len,
     known_flags, _reserved) = _HEADER.unpack_from(data, 0)
    if magic != MAGIC:
        raise ArtPackError("not an art pack (bad magic)")
    if version != FORMAT_VERSION:
        raise ArtPackError(f"unsupported art pack format version {version} "
                           f"(this reader understands {FORMAT_VERSION})")
    if header_size != HEADER_SIZE or n > _MAX_ROWS:
        raise ArtPackError("corrupt art pack header")
    expected_len = HEADER_SIZE + 61 * n + 4 + s_len + _SHA_LEN
    if len(data) != expected_len:
        raise ArtPackError(f"art pack size {len(data)} != expected {expected_len}")
    body, trailer = data[:-_SHA_LEN], data[-_SHA_LEN:]
    if hashlib.sha256(body).digest() != trailer:
        raise ArtPackError("art pack checksum mismatch (corrupt download?)")

    pos = HEADER_SIZE
    h64 = np.frombuffer(data, dtype="<u8", count=n, offset=pos).astype(np.uint64)
    pos += 8 * n
    h256 = np.frombuffer(data, dtype="<u8", count=4 * n, offset=pos).astype(np.uint64).reshape(n, 4)
    pos += 32 * n
    flags = np.frombuffer(data, dtype=np.uint8, count=n, offset=pos).copy()
    pos += n
    offsets = np.frombuffer(data, dtype="<u4", count=_FIELDS * n + 1, offset=pos).astype(np.int64)
    pos += 4 * (_FIELDS * n + 1)
    blob = data[pos:pos + s_len]
    if offsets[0] != 0 or offsets[-1] != s_len or np.any(np.diff(offsets) < 0):
        raise ArtPackError("corrupt art pack string offsets")
    strings = [blob[offsets[j]:offsets[j + 1]].decode("utf-8") for j in range(_FIELDS * n)]
    meta = [tuple(strings[_FIELDS * i:_FIELDS * i + _FIELDS]) for i in range(n)]
    return ArtPack(version, build_time, bulk.rstrip(b"\0").decode("ascii"),
                   known_flags, h64, h256, flags, meta)  # type: ignore[arg-type]


def read_pack(path: Union[str, Path]) -> ArtPack:
    return decode_pack(Path(path).read_bytes())


# ── CLI ───────────────────────────────────────────────────────────────────────

def _cli(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m mtg_card_scanner.art_pack",
        description="Export / inspect the compact art fingerprint pack.")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_exp = sub.add_parser("export", help="art_index.sqlite → pack (.gz = gzipped)")
    p_exp.add_argument("--out", required=True, type=Path)
    p_exp.add_argument("--db", type=Path, default=_DEFAULT_DB)
    g = p_exp.add_mutually_exclusive_group()
    g.add_argument("--bulk", type=Path, default=None,
                   help="unique_artwork bulk file for the digital flag "
                        "(default: unique-artwork.json beside the sqlite, if present)")
    g.add_argument("--no-bulk", action="store_true", help="leave the digital flag unknown")
    p_exp.add_argument("--build-time", type=int, default=None,
                       help="unix seconds to stamp (default $SOURCE_DATE_EPOCH, else now)")
    p_exp.add_argument("--manifest", type=Path, default=None,
                       help="also write a JSON manifest (size, sha256, rows, ...)")
    p_info = sub.add_parser("info", help="verify a pack and print its header")
    p_info.add_argument("pack", type=Path)
    args = parser.parse_args(argv)

    try:
        if args.cmd == "export":
            bulk = args.bulk
            if bulk is None and not args.no_bulk:
                beside = args.db.parent / "unique-artwork.json"
                bulk = beside if beside.exists() else None
            m = export_pack(args.db, args.out, bulk_path=bulk,
                            build_time=args.build_time, manifest_path=args.manifest)
            print(json.dumps(m, indent=1, sort_keys=True))
        else:
            data = args.pack.read_bytes()
            p = decode_pack(data)
            info = {
                "format_version": p.format_version,
                "build_date": _dt.datetime.fromtimestamp(p.build_time, _dt.timezone.utc)
                .strftime("%Y-%m-%dT%H:%M:%SZ"),
                "bulk_updated_at": p.bulk_updated_at,
                "rows": len(p),
                "digital_flags": bool(p.known_flags & FLAG_DIGITAL),
                "digital_rows": int(np.count_nonzero(p.flags & FLAG_DIGITAL)),
                "file_size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "checksum": "ok",
            }
            print(json.dumps(info, indent=1))
    except ArtPackError as exc:
        print(f"art_pack: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(_cli())
