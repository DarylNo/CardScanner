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
)


# ── helpers ───────────────────────────────────────────────────────────────────

def _seed_index(index_dir, rows):
    """Insert (scryfall_id, name, set, num, artist, hash_hex) rows directly."""
    db = index_dir / "art_index.sqlite"
    db.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db)
    conn.executescript(
        "CREATE TABLE IF NOT EXISTS art_hashes ("
        " scryfall_id TEXT PRIMARY KEY, name TEXT NOT NULL, set_code TEXT NOT NULL,"
        " collector_number TEXT NOT NULL, artist TEXT NOT NULL DEFAULT '',"
        " hash_hex TEXT NOT NULL);"
        "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);"
    )
    conn.executemany(
        "INSERT INTO art_hashes VALUES (?, ?, ?, ?, ?, ?)", rows
    )
    conn.commit()
    conn.close()


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


def test_should_index_rejects_digital():
    assert not _should_index(_entry(digital=True))


def test_should_index_rejects_oversized():
    assert not _should_index(_entry(oversized=True))


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


# ── identify: hamming, dedupe, ordering, top_n ────────────────────────────────

def test_identify_orders_by_hamming_distance(tmp_path, monkeypatch):
    _seed_index(tmp_path, [
        ("id1", "Exact Match",  "aaa", "1", "A", f"{0x0123456789ABCDEF:016x}"),
        ("id2", "One Bit Off",  "bbb", "2", "B", f"{0x0123456789ABCDEE:016x}"),
        ("id3", "Far Away",     "ccc", "3", "C", f"{0xFEDCBA9876543210:016x}"),
    ])
    idx = ArtIndex(index_dir=tmp_path)
    monkeypatch.setattr(ArtIndex, "_hash_frame", lambda self, f: 0x0123456789ABCDEF)

    results = idx.identify(np.zeros((10, 10, 3), np.uint8), top_n=3)

    assert [r["name"] for r in results] == ["Exact Match", "One Bit Off", "Far Away"]
    assert results[0]["distance"] == 0
    assert results[1]["distance"] == 1
    assert results[2]["distance"] == bin(0x0123456789ABCDEF ^ 0xFEDCBA9876543210).count("1")


def test_identify_dedupes_by_name_keeping_best(tmp_path, monkeypatch):
    _seed_index(tmp_path, [
        ("id1", "Same Card", "aaa", "1", "A", f"{0x0:016x}"),          # d=0
        ("id2", "Same Card", "bbb", "2", "A", f"{0xFF:016x}"),         # d=8, same name
        ("id3", "Other",     "ccc", "3", "B", f"{0x7:016x}"),          # d=3
    ])
    idx = ArtIndex(index_dir=tmp_path)
    monkeypatch.setattr(ArtIndex, "_hash_frame", lambda self, f: 0x0)

    results = idx.identify(np.zeros((10, 10, 3), np.uint8), top_n=5)

    names = [r["name"] for r in results]
    assert names == ["Same Card", "Other"]          # dupe collapsed
    assert results[0]["distance"] == 0              # best distance kept
    assert results[0]["scryfall_id"] == "id1"


def test_identify_respects_top_n(tmp_path, monkeypatch):
    _seed_index(tmp_path, [
        (f"id{i}", f"Card {i}", "set", str(i), "", f"{i:016x}") for i in range(10)
    ])
    idx = ArtIndex(index_dir=tmp_path)
    monkeypatch.setattr(ArtIndex, "_hash_frame", lambda self, f: 0x0)

    assert len(idx.identify(np.zeros((10, 10, 3), np.uint8), top_n=4)) == 4


def test_identify_raises_when_unbuilt(tmp_path):
    idx = ArtIndex(index_dir=tmp_path / "nope")
    assert not idx.is_built()
    with pytest.raises(ArtIndexError, match="art_index build"):
        idx.identify(np.zeros((10, 10, 3), np.uint8))


def test_identify_raises_when_empty(tmp_path):
    _seed_index(tmp_path, [])
    idx = ArtIndex(index_dir=tmp_path)
    with pytest.raises(ArtIndexError, match="empty"):
        idx.identify(np.zeros((10, 10, 3), np.uint8))


def test_count_and_is_built(tmp_path):
    _seed_index(tmp_path, [("id1", "X", "s", "1", "", f"{0:016x}")])
    idx = ArtIndex(index_dir=tmp_path)
    assert idx.count() == 1
    assert idx.is_built()


# ── builder: filtering, resume, limit ─────────────────────────────────────────

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
        _entry(id="n2", name="Digital Only", digital=True,
               image_uris={"small": "https://img/n2.jpg"}),
        _entry(id="n3", name="A Token", layout="token",
               image_uris={"small": "https://img/n3.jpg"}),
        _entry(id="n4", name="Front // Back", image_uris=None,
               card_faces=[{"image_uris": {"small": "https://img/n4.jpg"}}]),
    ]
    builder, session = _builder_fixture(tmp_path, entries)
    builder.build()

    idx = ArtIndex(index_dir=tmp_path)
    assert idx.count() == 2  # normal + DFC; digital + token filtered
    with sqlite3.connect(idx.db_path) as conn:
        names = {r[0] for r in conn.execute("SELECT name FROM art_hashes")}
    assert names == {"Normal One", "Front // Back"}
    assert "https://img/n2.jpg" not in session.fetched
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
    """A frame that IS the indexed artwork must identify at distance ~0."""
    import cv2
    from mtg_card_scanner.visual_match import crop_art_region
    import imagehash
    from PIL import Image

    # Build a synthetic "card": deterministic random noise is hash-stable.
    rng = np.random.default_rng(42)
    card = rng.integers(0, 255, size=(204, 146, 3), dtype=np.uint8)
    pil = Image.fromarray(card[:, :, ::-1])  # BGR→RGB
    hash_hex = str(imagehash.phash(crop_art_region(pil)))
    _seed_index(tmp_path, [("rt1", "Round Trip", "rt", "1", "", hash_hex)])

    idx = ArtIndex(index_dir=tmp_path)
    # Bypass card detection (synthetic image has no card quad): hash directly.
    monkeypatch.setattr(
        ArtIndex, "_hash_frame",
        lambda self, f: int(str(imagehash.phash(crop_art_region(f))), 16),
    )
    results = idx.identify(card, top_n=1)
    assert results[0]["name"] == "Round Trip"
    assert results[0]["distance"] == 0
