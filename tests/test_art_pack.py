"""Art pack (mtg_card_scanner.art_pack): sqlite → compact pack → reader."""

import gzip
import hashlib
import json
import sqlite3
import struct

import numpy as np
import pytest

from mtg_card_scanner import art_pack
from mtg_card_scanner.art_index import ArtIndex, ArtIndexBuilder, _split_u64
from mtg_card_scanner.art_pack import ArtPackError
from scripts import export_pack_fixture as fx


@pytest.fixture
def index(tmp_path):
    d = tmp_path / "idx"
    d.mkdir()
    db, bulk = fx.build_sqlite(d)
    return db, bulk


def _sqlite_rows(db):
    with sqlite3.connect(db) as conn:
        return sorted(conn.execute(
            "SELECT scryfall_id, name, set_code, collector_number, artist, hash_hex,"
            " hash256_hex FROM art_hashes WHERE hash256_hex IS NOT NULL").fetchall())


def test_round_trip_equals_sqlite_rows(index, tmp_path):
    db, bulk = index
    out = tmp_path / "pack.bin.gz"
    m = art_pack.export_pack(db, out, bulk_path=bulk, build_time=1_700_000_000)
    p = art_pack.read_pack(out)
    got = [(r.scryfall_id, r.name, r.set_code, r.collector_number, r.artist,
            r.hash_hex, r.hash256_hex) for r in p.rows()]
    assert got == _sqlite_rows(db)                  # v1 row (no fine hash) left out
    assert len(p) == m["rows"] == len(fx.ROWS)
    assert p.format_version == art_pack.FORMAT_VERSION
    assert p.build_time == 1_700_000_000
    assert p.bulk_updated_at == fx.BULK_UPDATED_AT
    digital = {r[0] for r in fx.ROWS if r[7]}
    assert {r.scryfall_id for r in p.rows() if r.flags & art_pack.FLAG_DIGITAL} == digital
    assert p.known_flags & art_pack.FLAG_DIGITAL
    assert m["sha256"] == hashlib.sha256(out.read_bytes()).hexdigest()
    assert m["size"] == out.stat().st_size


def test_arrays_match_what_art_index_loads(index, tmp_path, monkeypatch):
    """The pack's h64/h256 must be bit-identical to ArtIndex's own arrays
    (same _split_u64 word order), row for row."""
    db, _ = index
    out = tmp_path / "p.bin"
    art_pack.export_pack(db, out, build_time=0)
    p = art_pack.read_pack(out)
    ai = ArtIndex(db.parent)
    ai._load()
    by_id = {m[0]: i for i, m in enumerate(ai._meta)}
    for i, meta in enumerate(p.meta):
        j = by_id[meta[0]]
        assert meta == ai._meta[j]
        assert p.h64[i] == ai._h64[j]
        assert list(p.h256[i]) == list(ai._h256[j])
    assert p.h64.dtype == np.uint64 and p.h256.shape == (len(p), 4)


def test_digital_flag_unknown_without_bulk(index, tmp_path):
    db, _ = index
    out = tmp_path / "p.bin"
    art_pack.export_pack(db, out, build_time=0)
    p = art_pack.read_pack(out)
    assert p.known_flags == 0 and not p.flags.any()


def test_deterministic_bytes(index, tmp_path):
    db, bulk = index
    a, b = tmp_path / "a.bin.gz", tmp_path / "b.bin.gz"
    art_pack.export_pack(db, a, bulk_path=bulk, build_time=5)
    # Same rows inserted in a different order must not change a byte.
    with sqlite3.connect(db) as conn:
        rows = conn.execute("SELECT * FROM art_hashes").fetchall()
        conn.execute("DELETE FROM art_hashes")
        conn.executemany("INSERT INTO art_hashes VALUES (?,?,?,?,?,?,?)", rows[::-1])
    art_pack.export_pack(db, b, bulk_path=bulk, build_time=5)
    assert a.read_bytes() == b.read_bytes()


def test_build_time_from_source_date_epoch(index, tmp_path, monkeypatch):
    db, _ = index
    monkeypatch.setenv("SOURCE_DATE_EPOCH", "1234")
    art_pack.export_pack(db, tmp_path / "p.bin")
    assert art_pack.read_pack(tmp_path / "p.bin").build_time == 1234


def _raw(index, tmp_path):
    db, bulk = index
    out = tmp_path / "raw.bin"
    art_pack.export_pack(db, out, bulk_path=bulk, build_time=0)
    return bytearray(out.read_bytes())


@pytest.mark.parametrize("where", [70, -40, -1])   # h64 area, string blob, trailer
def test_checksum_rejects_corruption(index, tmp_path, where):
    raw = _raw(index, tmp_path)
    raw[where] ^= 0x01
    with pytest.raises(ArtPackError, match="checksum"):
        art_pack.decode_pack(bytes(raw))
    with pytest.raises(ArtPackError, match="checksum"):
        art_pack.decode_pack(gzip.compress(bytes(raw)))


def test_truncation_rejected(index, tmp_path):
    raw = _raw(index, tmp_path)
    with pytest.raises(ArtPackError):
        art_pack.decode_pack(bytes(raw[:-1]))


def test_version_check(index, tmp_path):
    raw = _raw(index, tmp_path)
    raw[5] = art_pack.FORMAT_VERSION + 1
    body = bytes(raw[:-32])
    resigned = body + hashlib.sha256(body).digest()   # valid checksum, future version
    with pytest.raises(ArtPackError, match="version"):
        art_pack.decode_pack(resigned)


def test_bad_magic(index, tmp_path):
    raw = _raw(index, tmp_path)
    raw[0:5] = b"NOPE!"
    with pytest.raises(ArtPackError, match="magic"):
        art_pack.decode_pack(bytes(raw))


def test_header_layout_is_as_documented(index, tmp_path):
    raw = bytes(_raw(index, tmp_path))
    n = len(fx.ROWS)
    magic, ver, hsize, bt, bulk, rows, s_len, known, res = struct.unpack_from("<5sBHq32sIIII", raw)
    assert (magic, ver, hsize, rows, res) == (b"MTGAP", 1, 64, n, 0)
    assert len(raw) == 64 + 61 * n + 4 + s_len + 32
    # h64[0] sits right after the header; row 0 is the smallest scryfall_id.
    first = min(fx.ROWS, key=lambda r: r[0])
    assert struct.unpack_from("<Q", raw, 64)[0] == int(first[5], 16)
    assert list(struct.unpack_from("<4Q", raw, 64 + 8 * n)) == _split_u64(first[6], 4)


def test_empty_index_is_an_error(tmp_path):
    from mtg_card_scanner.art_index import _connect
    db = tmp_path / "art_index.sqlite"
    _connect(db).close()
    with pytest.raises(ArtPackError):
        art_pack.export_pack(db, tmp_path / "p.bin")


def test_cli_export_info_and_manifest(index, tmp_path, capsys):
    db, _ = index
    out, man = tmp_path / "art-pack.bin.gz", tmp_path / "art-pack.json"
    # --bulk defaults to unique-artwork.json beside the sqlite.
    assert art_pack._cli(["export", "--db", str(db), "--out", str(out),
                          "--build-time", "9", "--manifest", str(man)]) == 0
    m = json.loads(man.read_text())
    assert m["rows"] == len(fx.ROWS) and m["digital_flags"] is True
    assert m["sha256"] == hashlib.sha256(out.read_bytes()).hexdigest()
    capsys.readouterr()
    assert art_pack._cli(["info", str(out)]) == 0
    info = json.loads(capsys.readouterr().out)
    assert info["checksum"] == "ok" and info["digital_rows"] == 2
    bad = tmp_path / "bad.bin"
    bad.write_bytes(b"garbage" * 30)
    assert art_pack._cli(["info", str(bad)]) == 1


def test_limit_build_then_export(tmp_path):
    """The workflow's cheap dry path: `art_index build --limit N` then export —
    exercised here with the network faked, never with the real 90-min build."""
    from tests.test_art_index import FakeResponse, _jpeg_bytes

    entries = [
        {"id": f"id-{i}", "name": f"Card {i}", "set": "tst", "collector_number": str(i),
         "artist": "A", "layout": "normal", "digital": i == 2,
         "image_uris": {"small": f"https://img/{i}.jpg"}}
        for i in range(5)
    ]

    class Session:
        headers: dict = {}

        def get(self, url, **kw):
            if url == "https://api.scryfall.com/bulk-data":
                return FakeResponse(json_data={"data": [{
                    "type": "unique_artwork", "download_uri": "https://bulk/ua.json",
                    "updated_at": "2026-09-27T00:00:00.000+00:00", "size": 1}]})
            if url == "https://bulk/ua.json":
                return FakeResponse(content=json.dumps(entries).encode())
            return FakeResponse(content=_jpeg_bytes())

    idx = tmp_path / "idx"
    ArtIndexBuilder(index_dir=idx, request_delay=0, session=Session()).build(limit=3)
    out = tmp_path / "art-pack.bin.gz"
    assert art_pack._cli(["export", "--db", str(idx / "art_index.sqlite"),
                          "--out", str(out), "--build-time", "1"]) == 0
    p = art_pack.read_pack(out)
    assert [m[0] for m in p.meta] == ["id-0", "id-1", "id-2"]
    assert p.bulk_updated_at == "2026-09-27T00:00:00.000+00:00"
    assert [int(f) for f in p.flags] == [0, 0, 1]


def test_fixture_script_is_current():
    """Same gate CI runs: the Kotlin fixture was exported by this writer."""
    files = fx.build()
    for name, data in files.items():
        on_disk = (fx.OUT / name).read_bytes()
        assert fx._content(name, on_disk) == fx._content(name, data), name
