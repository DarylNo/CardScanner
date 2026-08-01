"""Tests for the Face to Face pricing client, driven by captured JSON fixtures."""

import json
from pathlib import Path

import pytest

from mtg_card_scanner.facetoface import (
    FaceToFaceClient,
    F2FPrice,
    _parse_title_brackets,
    _sku_matches_set,
)

_FIX = Path(__file__).parent / "fixtures" / "facetoface"


def _fixture_get_json(url: str):
    """Serve captured fixtures by URL shape, mimicking the live endpoints."""
    if "/search/suggest.json" in url:
        return json.loads((_FIX / "suggest_lightning_bolt.json").read_text())
    if "/products/" in url:
        handle = url.rsplit("/products/", 1)[1].rsplit(".json", 1)[0]
        path = _FIX / f"product_{handle}.json"
        if not path.exists():
            raise FileNotFoundError(f"no product fixture for {handle}")
        return json.loads(path.read_text())
    raise AssertionError(f"unexpected url: {url}")


@pytest.fixture
def client():
    return FaceToFaceClient(get_json=_fixture_get_json)


def test_parse_title_brackets_full():
    parts = _parse_title_brackets("Lightning Bolt [149] [Magic 2011] [Non-Foil]")
    assert parts == {"collector": "149", "set_name": "Magic 2011", "foil_label": "Non-Foil"}


def test_parse_title_brackets_promo_without_collector():
    parts = _parse_title_brackets("Lightning Bolt [MagicFest 2019] [Foil]")
    assert parts["collector"] == ""
    assert parts["set_name"] == "MagicFest 2019"
    assert parts["foil_label"] == "Foil"


def test_sku_matches_set():
    # old format: set code at segment 2
    assert _sku_matches_set("M-M11-Lightning_-149-NM-NF", "m11")
    # current format: set code at segment 3
    assert _sku_matches_set("SIN-MTG-CLB-309-ENG-NM-NF", "clb")
    assert not _sku_matches_set("SIN-MTG-CLB-309-ENG-NM-NF", "m11")
    assert not _sku_matches_set("", "clb")
    assert not _sku_matches_set("SIN-MTG-CLB-309-ENG-NM-NF", "")


def test_get_price_matches_exact_printing_by_set_and_collector(client):
    # m10 #146 must resolve to the Magic 2010 product, not Magic 2011 (#149).
    price = client.get_price("Lightning Bolt", "m10", "146", foil=False)
    assert isinstance(price, F2FPrice)
    assert price.handle == "lightning-bolt-146-magic-2010-non-foil"
    assert price.conditions == {"NM": 3.49, "PL": 2.79}
    assert price.url.endswith("/products/lightning-bolt-146-magic-2010-non-foil")


def test_get_price_disambiguates_same_name_different_set(client):
    price = client.get_price("Lightning Bolt", "m11", "149", foil=False)
    assert price.handle == "lightning-bolt-149-magic-2011-non-foil"
    assert price.conditions["NM"] == 3.49


def test_price_for_mx_condition_mapping(client):
    price = client.get_price("Lightning Bolt", "m10", "146", foil=False)
    assert price.price_for_mx_condition("NM") == 3.49
    # LP maps to F2F "PL"
    assert price.price_for_mx_condition("LP") == 2.79
    # MP/HP/DMG fall back through PL when no exact grade exists
    assert price.price_for_mx_condition("MP") == 2.79


def test_get_price_foil_mismatch_returns_none(client):
    # There is no foil Magic 2010 #146 product in the fixture -> no match.
    price = client.get_price("Lightning Bolt", "m10", "146", foil=True)
    assert price is None


def test_get_price_unknown_name_returns_none():
    empty = FaceToFaceClient(get_json=lambda url: {"resources": {"results": {"products": []}}})
    assert empty.get_price("Nonexistent Card", "xxx", "1", foil=False) is None


def test_collector_collision_across_sets_does_not_return_wrong_set():
    """Collector #141 exists in both Masters 25 (m25) and Clue Edition (clu).

    A lookup for the clu printing must NOT return the m25 price just because the
    collector number + foil match — the SKU set code disproves it.
    """
    def get_json(url):
        if "/search/suggest.json" in url:
            return {"resources": {"results": {"products": [
                {"title": "Lightning Bolt [141] [Masters 25] [Non-Foil]",
                 "handle": "lightning-bolt-141-masters-25-non-foil"},
            ]}}}
        # only the m25 product exists on F2F
        return {"product": {"handle": "lightning-bolt-141-masters-25-non-foil",
                            "variants": [
                                {"option1": "NM", "price": "9.99", "sku": "M-M25-Lightning_-141-NM-NF"},
                            ]}}
    c = FaceToFaceClient(get_json=get_json)
    # clu #141 has no F2F listing -> must be None, not the m25 price
    assert c.get_price("Lightning Bolt", "clu", "141", foil=False) is None
    # m25 #141 resolves correctly
    m25 = c.get_price("Lightning Bolt", "m25", "141", foil=False)
    assert m25 is not None and m25.conditions == {"NM": 9.99}


def test_price_cache_expires_after_ttl(tmp_path, monkeypatch):
    """Cached prices previously lived forever; they must refetch after the TTL."""
    import hashlib
    import os
    import time as time_mod

    from mtg_card_scanner import facetoface as f2f_mod

    calls = []

    class FakeResp:
        status_code = 200
        def raise_for_status(self): pass
        def json(self): return {"fetch": len(calls)}

    class FakeSession:
        def __init__(self): self.headers = {}
        def get(self, url, timeout=None):
            calls.append(url)
            return FakeResp()

    monkeypatch.setattr(f2f_mod.requests, "Session", FakeSession)
    get_json = f2f_mod._default_get_json(tmp_path)

    assert get_json("http://x") == {"fetch": 1}
    assert get_json("http://x") == {"fetch": 1}     # fresh cache — no refetch
    assert len(calls) == 1

    # Age the cache file past the TTL — the next call must hit the network.
    key = hashlib.sha1(b"http://x").hexdigest()
    old = time_mod.time() - f2f_mod._CACHE_TTL - 10
    os.utime(tmp_path / f"{key}.json", (old, old))
    assert get_json("http://x") == {"fetch": 2}
    assert len(calls) == 2
