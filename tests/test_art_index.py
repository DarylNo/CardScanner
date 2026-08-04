"""Tests for the global art-hash index (no network — sessions are faked)."""

import io
import json
import sqlite3

import numpy as np
import pytest

from mtg_card_scanner.art_index import (
    ArtIndex,
    ArtIndexBuilder,
    ArtIndexError,
    _image_url,
    _should_index,
    _split_u64,
)

_Z256 = "0" * 64  # 256-bit zero hash (hex)


# ── helpers ───────────────────────────────────────────────────────────────────

def _seed_index(index_dir, rows):
    """Insert (scryfall_id, name, set, num, artist, hash_hex, hash256_hex) rows."""
    db = index_dir / "art_index.sqlite"
    db.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db)
    conn.executescript(
        "CREATE TABLE IF NOT EXISTS art_hashes ("
        " scryfall_id TEXT PRIMARY KEY, name TEXT NOT NULL, set_code TEXT NOT NULL,"
        " collector_number TEXT NOT NULL, artist TEXT NOT NULL DEFAULT '',"
        " hash_hex TEXT NOT NULL, hash256_hex TEXT);"
        "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);"
    )
    conn.executemany(
        "INSERT INTO art_hashes VALUES (?, ?, ?, ?, ?, ?, ?)", rows
    )
    conn.commit()
    conn.close()


def _fake_variants(h64, h256_words=None):
    """Patchable _hash_variants stand-in returning a single crop variant."""
    words = h256_words or [0, 0, 0, 0]
    return lambda self, frame, params=None, card_pil=None: [(h64, words)]


def _jpeg_bytes(color=(120, 40, 200), size=(146, 204)):
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, format="JPEG")
    return buf.getvalue()


class FakeResponse:
    def __init__(self, *, json_data=None, content=b"", chunks=None):
        self._json = json_data
        self.content = content
        self._chunks = chunks or [content]
        self.status_code = 200

    def json(self):
        return self._json

    def raise_for_status(self):
        pass

    def iter_content(self, chunk_size=1):
        yield from self._chunks


class FakeSession:
    """Maps URL → FakeResponse; records what was fetched."""

    def __init__(self, responses):
        self.responses = responses
        self.headers = {}
        self.fetched = []

    def get(self, url, **kwargs):
        self.fetched.append(url)
        if url not in self.responses:
            raise AssertionError(f"unexpected URL fetched: {url}")
        return self.responses[url]


# ── filtering / URL resolution ────────────────────────────────────────────────

def _entry(**over):
    base = {
        "id": "aaa",
        "name": "Lightning Bolt",
        "set": "lea",
        "collector_number": "161",
        "artist": "Christopher Rush",
        "layout": "normal",
        "digital": False,
        "oversized": False,
        "image_status": "highres_scan",
        "image_uris": {"small": "https://img/aaa.jpg"},
    }
    base.update(over)
    return base


def test_should_index_accepts_normal_card():
    assert _should_index(_entry())


def test_should_index_keeps_digital_and_oversized():
    # unique_artwork may pick a digital/oversized printing as the ONLY
    # representative of a real paper artwork — they must stay indexable.
    assert _should_index(_entry(digital=True))
    assert _should_index(_entry(oversized=True))


@pytest.mark.parametrize("layout", ["token", "double_faced_token", "emblem", "art_series"])
def test_should_index_rejects_non_sellable_layouts(layout):
    assert not _should_index(_entry(layout=layout))


@pytest.mark.parametrize("status", ["missing", "placeholder"])
def test_should_index_rejects_bad_images(status):
    assert not _should_index(_entry(image_status=status))


def test_should_index_rejects_no_image_anywhere():
    assert not _should_index(_entry(image_uris=None))


def test_image_url_falls_back_to_card_face_for_dfc():
    e = _entry(
        image_uris=None,
        card_faces=[{"image_uris": {"small": "https://img/face0.jpg"}}, {}],
    )
    assert _image_url(e) == "https://img/face0.jpg"
    assert _should_index(e)


def test_split_u64_roundtrip():
    h = "ab" * 32  # 256-bit
    words = _split_u64(h, 4)
    assert len(words) == 4
    joined = 0
    for w in words:
        joined = (joined << 64) | w
    assert joined == int(h, 16)


# ── identify: combined scoring, dedupe, ordering, top_n ───────────────────────

FRAME = np.zeros((10, 10, 3), np.uint8)


def test_identify_orders_by_combined_distance(tmp_path, monkeypatch):
    _seed_index(tmp_path, [
        ("id1", "Exact Match",  "aaa", "1", "A", f"{0x0123456789ABCDEF:016x}", _Z256),
        ("id2", "One Bit Off",  "bbb", "2", "B", f"{0x0123456789ABCDEE:016x}", _Z256),
        ("id3", "Far Away",     "ccc", "3", "C", f"{0xFEDCBA9876543210:016x}", _Z256),
    ])
    idx = ArtIndex(index_dir=tmp_path)
    monkeypatch.setattr(ArtIndex, "_hash_variants", _fake_variants(0x0123456789ABCDEF))

    results = idx.identify(FRAME, top_n=3)

    assert [r["name"] for r in results] == ["Exact Match", "One Bit Off", "Far Away"]
    # zero 256-bit hashes on both sides → score = 4 * d64
    assert results[0]["distance"] == 0
    assert results[1]["distance"] == 4
    assert results[2]["distance"] == 4 * bin(0x0123456789ABCDEF ^ 0xFEDCBA9876543210).count("1")


def test_identify_fine_hash_contributes(tmp_path, monkeypatch):
    _seed_index(tmp_path, [
        ("id1", "Fine Diff", "aaa", "1", "A", f"{0:016x}", f"{0xFF:064x}"),
    ])
    idx = ArtIndex(index_dir=tmp_path)
    monkeypatch.setattr(ArtIndex, "_hash_variants", _fake_variants(0x0))
    results = idx.identify(FRAME, top_n=1)
    assert results[0]["distance"] == 8  # 4*0 + popcount(0xFF)


def test_identify_takes_min_over_variants(tmp_path, monkeypatch):
    _seed_index(tmp_path, [
        ("id1", "Card", "aaa", "1", "A", f"{0x0:016x}", _Z256),
    ])
    idx = ArtIndex(index_dir=tmp_path)
    # Two variants: a bad one (d64=8) and a perfect one — min wins.
    monkeypatch.setattr(
        ArtIndex, "_hash_variants",
        lambda self, f, params=None, card_pil=None: [(0xFF, [0, 0, 0, 0]), (0x0, [0, 0, 0, 0])],
    )
    assert idx.identify(FRAME, top_n=1)[0]["distance"] == 0


def test_identify_dedupes_by_name_keeping_best(tmp_path, monkeypatch):
    _seed_index(tmp_path, [
        ("id1", "Same Card", "aaa", "1", "A", f"{0x0:016x}", _Z256),   # s=0
        ("id2", "Same Card", "bbb", "2", "A", f"{0xFF:016x}", _Z256),  # s=32, same name
        ("id3", "Other",     "ccc", "3", "B", f"{0x7:016x}", _Z256),   # s=12
    ])
    idx = ArtIndex(index_dir=tmp_path)
    monkeypatch.setattr(ArtIndex, "_hash_variants", _fake_variants(0x0))

    results = idx.identify(FRAME, top_n=5)

    assert [r["name"] for r in results] == ["Same Card", "Other"]
    assert results[0]["distance"] == 0
    assert results[0]["scryfall_id"] == "id1"


def test_identify_respects_top_n(tmp_path, monkeypatch):
    _seed_index(tmp_path, [
        (f"id{i}", f"Card {i}", "set", str(i), "", f"{i:016x}", _Z256) for i in range(10)
    ])
    idx = ArtIndex(index_dir=tmp_path)
    monkeypatch.setattr(ArtIndex, "_hash_variants", _fake_variants(0x0))
    assert len(idx.identify(FRAME, top_n=4)) == 4


def test_identify_raises_when_unbuilt(tmp_path):
    idx = ArtIndex(index_dir=tmp_path / "nope")
    assert not idx.is_built()
    with pytest.raises(ArtIndexError, match="art_index build"):
        idx.identify(FRAME)


def test_identify_raises_when_empty(tmp_path):
    _seed_index(tmp_path, [])
    idx = ArtIndex(index_dir=tmp_path)
    with pytest.raises(ArtIndexError, match="empty"):
        idx.identify(FRAME)


def test_identify_ignores_v1_rows_without_fine_hash(tmp_path, monkeypatch):
    _seed_index(tmp_path, [
        ("id1", "V1 Row", "aaa", "1", "A", f"{0x0:016x}", None),
        ("id2", "V2 Row", "bbb", "2", "B", f"{0x1:016x}", _Z256),
    ])
    idx = ArtIndex(index_dir=tmp_path)
    monkeypatch.setattr(ArtIndex, "_hash_variants", _fake_variants(0x0))
    names = [r["name"] for r in idx.identify(FRAME, top_n=5)]
    assert names == ["V2 Row"]          # v1 row invisible until re-hashed
    assert idx.count() == 1


def test_count_and_is_built(tmp_path):
    _seed_index(tmp_path, [("id1", "X", "s", "1", "", f"{0:016x}", _Z256)])
    idx = ArtIndex(index_dir=tmp_path)
    assert idx.count() == 1
    assert idx.is_built()


# ── builder: filtering, resume, migration, limit ──────────────────────────────

_MANIFEST_URL = "https://api.scryfall.com/bulk-data"


def _builder_fixture(tmp_path, entries):
    bulk_url = "https://bulk/unique-artwork.json"
    manifest = FakeResponse(json_data={"data": [{
        "type": "unique_artwork",
        "updated_at": "2026-07-19T00:00:00Z",
        "size": 1000,
        "download_uri": bulk_url,
    }]})
    bulk = FakeResponse(content=json.dumps(entries).encode())
    responses = {_MANIFEST_URL: manifest, bulk_url: bulk}
    jpeg = _jpeg_bytes()
    for e in entries:
        url = _image_url(e)
        if url:
            responses[url] = FakeResponse(content=jpeg)
    session = FakeSession(responses)
    builder = ArtIndexBuilder(index_dir=tmp_path, request_delay=0, session=session)
    return builder, session


def test_build_indexes_filters_and_dfc(tmp_path):
    entries = [
        _entry(id="n1", name="Normal One", image_uris={"small": "https://img/n1.jpg"}),
        _entry(id="n2", name="Digital Rep", digital=True,
               image_uris={"small": "https://img/n2.jpg"}),
        _entry(id="n3", name="A Token", layout="token",
               image_uris={"small": "https://img/n3.jpg"}),
        _entry(id="n4", name="Front // Back", image_uris=None,
               card_faces=[{"image_uris": {"small": "https://img/n4.jpg"}}]),
    ]
    builder, session = _builder_fixture(tmp_path, entries)
    builder.build()

    idx = ArtIndex(index_dir=tmp_path)
    assert idx.count() == 3  # normal + digital rep + DFC; token filtered
    with sqlite3.connect(idx.db_path) as conn:
        names = {r[0] for r in conn.execute("SELECT name FROM art_hashes")}
        h256 = [r[0] for r in conn.execute("SELECT hash256_hex FROM art_hashes")]
    assert names == {"Normal One", "Digital Rep", "Front // Back"}
    assert all(h and len(h) == 64 for h in h256)
    assert "https://img/n3.jpg" not in session.fetched


def test_build_resumes_without_refetching(tmp_path):
    entries = [
        _entry(id="r1", name="One", image_uris={"small": "https://img/r1.jpg"}),
        _entry(id="r2", name="Two", image_uris={"small": "https://img/r2.jpg"}),
    ]
    builder, session = _builder_fixture(tmp_path, entries)
    builder.build()
    first_fetch_count = len(session.fetched)

    # Second build: same bulk revision — no bulk re-download, no image fetches.
    builder2, session2 = _builder_fixture(tmp_path, entries)
    builder2.build()
    image_fetches = [u for u in session2.fetched if u.startswith("https://img/")]
    assert image_fetches == []          # all rows already indexed
    assert ArtIndex(index_dir=tmp_path).count() == 2
    assert first_fetch_count >= 4       # manifest + bulk + 2 images the first time


def test_build_migrates_v1_rows_from_image_cache(tmp_path):
    """A v1 row (no hash256) is re-hashed from the cached image — no download."""
    entries = [_entry(id="m1", name="Old Row", image_uris={"small": "https://img/m1.jpg"})]
    _seed_index(tmp_path, [("m1", "Old Row", "lea", "161", "A", f"{0xAB:016x}", None)])
    (tmp_path / "images").mkdir()
    (tmp_path / "images" / "m1.jpg").write_bytes(_jpeg_bytes())

    builder, session = _builder_fixture(tmp_path, entries)
    builder.build()

    assert ArtIndex(index_dir=tmp_path).count() == 1
    image_fetches = [u for u in session.fetched if u.startswith("https://img/")]
    assert image_fetches == []          # image came from cache
    with sqlite3.connect(tmp_path / "art_index.sqlite") as conn:
        h64, h256 = conn.execute(
            "SELECT hash_hex, hash256_hex FROM art_hashes WHERE scryfall_id='m1'"
        ).fetchone()
    assert h256 and len(h256) == 64
    assert h64 != f"{0xAB:016x}"        # 64-bit hash recomputed from the real image


def test_build_limit(tmp_path):
    entries = [
        _entry(id=f"l{i}", name=f"Card {i}",
               image_uris={"small": f"https://img/l{i}.jpg"})
        for i in range(5)
    ]
    builder, _ = _builder_fixture(tmp_path, entries)
    builder.build(limit=2)
    assert ArtIndex(index_dir=tmp_path).count() == 2

    # Resume finishes the rest.
    builder2, _ = _builder_fixture(tmp_path, entries)
    builder2.build()
    assert ArtIndex(index_dir=tmp_path).count() == 5


def test_build_survives_single_image_failure(tmp_path):
    entries = [
        _entry(id="f1", name="Good", image_uris={"small": "https://img/f1.jpg"}),
        _entry(id="f2", name="Bad",  image_uris={"small": "https://img/f2.jpg"}),
    ]
    builder, session = _builder_fixture(tmp_path, entries)

    class BoomResponse(FakeResponse):
        def raise_for_status(self):
            raise RuntimeError("boom 500")

    session.responses["https://img/f2.jpg"] = BoomResponse()
    builder.build()

    idx = ArtIndex(index_dir=tmp_path)
    assert idx.count() == 1
    with sqlite3.connect(idx.db_path) as conn:
        assert conn.execute("SELECT name FROM art_hashes").fetchone()[0] == "Good"


def test_status_reports_counts(tmp_path):
    entries = [_entry(id="s1", name="One", image_uris={"small": "https://img/s1.jpg"})]
    builder, _ = _builder_fixture(tmp_path, entries)
    builder.build()
    st = builder.status()
    assert st["indexed"] == 1
    assert st["bulk_file_present"] is True
    assert st["bulk_updated_at"] == "2026-07-19T00:00:00Z"


# ── real hash round-trip (PIL only, no network) ───────────────────────────────

def test_hash_frame_roundtrip_matches_indexed_image(tmp_path, monkeypatch):
    """A frame that IS the indexed artwork must identify at distance 0."""
    import imagehash
    from mtg_card_scanner.visual_match import crop_art_region
    from PIL import Image
    from mtg_card_scanner.art_index import _split_u64

    rng = np.random.default_rng(42)
    card = rng.integers(0, 255, size=(204, 146, 3), dtype=np.uint8)
    pil = Image.fromarray(card[:, :, ::-1])  # BGR→RGB
    crop = crop_art_region(pil)
    h64 = str(imagehash.phash(crop))
    h256 = str(imagehash.phash(crop, hash_size=16))
    _seed_index(tmp_path, [("rt1", "Round Trip", "rt", "1", "", h64, h256)])

    idx = ArtIndex(index_dir=tmp_path)
    # Bypass warp/jitter (synthetic image has no card quad): hash the base crop.
    monkeypatch.setattr(
        ArtIndex, "_hash_variants",
        lambda self, f, params=None, card_pil=None: [(
            int(str(imagehash.phash(crop_art_region(f))), 16),
            _split_u64(str(imagehash.phash(crop_art_region(f), hash_size=16)), 4),
        )],
    )
    results = idx.identify(card, top_n=1)
    assert results[0]["name"] == "Round Trip"
    assert results[0]["distance"] == 0


# ── prefetch-printings ────────────────────────────────────────────────────────

def test_prefetch_printings_downloads_and_resumes(tmp_path, monkeypatch):
    import mtg_card_scanner.visual_match as vm
    cache = tmp_path / "card_images"
    monkeypatch.setattr(vm, "_DEFAULT_CACHE_DIR", cache)

    entries = [
        {"id": "p1", "image_uris": {"normal": "https://img/p1.jpg"}},
        {"id": "p2", "image_uris": {"large": "https://img/p2.jpg"}},   # no normal → large
        {"id": "p3", "image_uris": None,
         "card_faces": [{"image_uris": {"normal": "https://img/p3.jpg"}}]},  # DFC
        {"id": "p4"},                                                  # no image at all
    ]
    bulk_url = "https://bulk/default-cards.json"
    manifest = FakeResponse(json_data={"data": [{
        "type": "default_cards", "updated_at": "2026-07-20T00:00:00Z",
        "size": 1000, "download_uri": bulk_url,
    }]})
    responses = {_MANIFEST_URL: manifest,
                 bulk_url: FakeResponse(content=json.dumps(entries).encode())}
    jpeg = _jpeg_bytes()
    for e in entries[:3]:
        for holder in (e, *(e.get("card_faces") or [])[:1]):
            uris = holder.get("image_uris") or {}
            u = uris.get("normal") or uris.get("large")
            if u:
                responses[u] = FakeResponse(content=jpeg)
    session = FakeSession(responses)
    builder = ArtIndexBuilder(index_dir=tmp_path, request_delay=0, session=session)
    builder.prefetch_printings()

    assert sorted(p.name for p in cache.glob("*.jpg")) == ["p1.jpg", "p2.jpg", "p3.jpg"]

    # Resume: nothing re-fetched.
    builder2, session2 = ArtIndexBuilder(index_dir=tmp_path, request_delay=0,
                                         session=FakeSession(responses)), None
    builder2.prefetch_printings()
    # only manifest hit again (bulk file + all images already present)
    assert [u for u in builder2._session.fetched if u.startswith("https://img/")] == []


def test_identify_canonicalizes_alchemy_names(tmp_path, monkeypatch):
    """Arena rebalances ("A-Name") share the paper card's art — the A- twin
    must merge into the paper name, never surface as its own card (its
    printings are all digital, which the paper filter empties into a
    0-candidate check row — observed live with A-Return Upon the Tide)."""
    _seed_index(tmp_path, [
        ("idA", "A-Return Upon the Tide", "ykhm", "A-106", "X", f"{0x0:016x}", _Z256),
        ("idP", "Return Upon the Tide",   "khm",  "106",   "X", f"{0x0:016x}", _Z256),
        ("id3", "Fly",                    "akh",  "1",     "Y", f"{0xFF:016x}", _Z256),
    ])
    idx = ArtIndex(index_dir=tmp_path)
    monkeypatch.setattr(ArtIndex, "_hash_variants", _fake_variants(0x0))

    results = idx.identify(FRAME, top_n=5)

    names = [r["name"] for r in results]
    assert "A-Return Upon the Tide" not in names
    assert names[0] == "Return Upon the Tide"      # single merged entry
    assert names.count("Return Upon the Tide") == 1


# ── 2026+ Scryfall bulk format: jsonl_download_uri, gzipped JSONL ─────────────

def test_build_with_jsonl_gz_manifest(tmp_path):
    """Scryfall dropped download_uri for jsonl_download_uri (gzipped JSONL);
    the builder must accept the new manifest and file format end to end."""
    import gzip
    entries = [
        _entry(id="j1", name="Jsonl One", image_uris={"small": "https://img/j1.jpg"}),
        _entry(id="j2", name="Jsonl Two", image_uris={"small": "https://img/j2.jpg"}),
    ]
    bulk_url = "https://bulk/unique-artwork.jsonl.gz"
    manifest = FakeResponse(json_data={"data": [{
        "type": "unique_artwork",
        "updated_at": "2026-08-03T00:00:00Z",
        "compressed_size": 1000,                      # no "size" either
        "jsonl_download_uri": bulk_url,               # no "download_uri"
    }]})
    jsonl = "\n".join(json.dumps(e) for e in entries).encode()
    responses = {_MANIFEST_URL: manifest, bulk_url: FakeResponse(content=gzip.compress(jsonl))}
    jpeg = _jpeg_bytes()
    for e in entries:
        responses[_image_url(e)] = FakeResponse(content=jpeg)
    builder = ArtIndexBuilder(index_dir=tmp_path, request_delay=0,
                              session=FakeSession(responses))
    builder.build()
    assert ArtIndex(index_dir=tmp_path).count() == 2


def test_load_bulk_entries_sniffs_all_formats(tmp_path):
    import gzip
    entries = [{"id": "a"}, {"id": "b"}]
    legacy = tmp_path / "legacy.json"
    legacy.write_text(json.dumps(entries), encoding="utf-8")
    plain_jsonl = tmp_path / "plain.jsonl"
    plain_jsonl.write_text("\n".join(json.dumps(e) for e in entries), encoding="utf-8")
    gz = tmp_path / "bulk.jsonl.gz"
    gz.write_bytes(gzip.compress(b'{"id": "a"}\n{"id": "b"}\n'))
    for p in (legacy, plain_jsonl, gz):
        assert ArtIndexBuilder._load_bulk_entries(p) == entries, p.name


def test_manifest_without_any_download_url_is_clear_error(tmp_path):
    manifest = FakeResponse(json_data={"data": [{
        "type": "unique_artwork", "updated_at": "x", "compressed_size": 1,
    }]})
    builder = ArtIndexBuilder(index_dir=tmp_path, request_delay=0,
                              session=FakeSession({_MANIFEST_URL: manifest}))
    with pytest.raises(ArtIndexError, match="no download URL"):
        builder.build()


def test_build_reports_progress(tmp_path):
    """build(progress=cb) must report download and hash stages with totals."""
    entries = [
        _entry(id="p1", name="Prog One", image_uris={"small": "https://img/p1.jpg"}),
        _entry(id="p2", name="Prog Two", image_uris={"small": "https://img/p2.jpg"}),
    ]
    bulk_url = "https://bulk/unique-artwork.json"
    manifest = FakeResponse(json_data={"data": [{
        "type": "unique_artwork", "updated_at": "2026-08-03", "size": 1000,
        "download_uri": bulk_url,
    }]})
    responses = {_MANIFEST_URL: manifest,
                 bulk_url: FakeResponse(content=json.dumps(entries).encode())}
    jpeg = _jpeg_bytes()
    for e in entries:
        responses[_image_url(e)] = FakeResponse(content=jpeg)
    builder = ArtIndexBuilder(index_dir=tmp_path, request_delay=0,
                              session=FakeSession(responses))
    seen = []
    builder.build(progress=seen.append)
    stages = {p["stage"] for p in seen}
    assert "download" in stages and "hash" in stages
    hash_reports = [p for p in seen if p["stage"] == "hash"]
    assert hash_reports[0] == {"stage": "hash", "done": 0, "total": 2}
