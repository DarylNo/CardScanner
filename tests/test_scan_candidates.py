"""Tests for Pipeline.scan_candidates() with a mocked vision model + art matcher."""

import numpy as np
import pytest

from mtg_card_scanner.pipeline import Pipeline
from mtg_card_scanner.vision import CardRead
from mtg_card_scanner.scryfall import ScryfallError


class FakeModel:
    def __init__(self, card_read):
        self._cr = card_read

    def read_card(self, frame):
        return self._cr


class FakeScryfall:
    def __init__(self, printings=None, raise_exc=None):
        self._printings = printings or []
        self._raise = raise_exc

    def get_all_printings(self, name):
        if self._raise:
            raise self._raise
        return self._printings


class FakeArtMatcher:
    """Returns printings annotated with an increasing pHash distance."""
    def rank_printings(self, frame, printings):
        return [
            {**p, "phash_distance": i * 3, "multi_distance": i * 5}
            for i, p in enumerate(printings)
        ]


def _printing(pid, set_code, num, set_name):
    return {
        "id": pid,
        "name": "Lightning Bolt",
        "set": set_code,
        "set_name": set_name,
        "collector_number": num,
        "rarity": "common",
        "released_at": "2010-07-16",
        "border_color": "black",
        "frame": "2003",
        "promo": False,
        "finishes": ["nonfoil"],
        "image_uris": {"small": f"http://img/{pid}-s.jpg", "normal": f"http://img/{pid}-n.jpg"},
    }


def _pipeline(model, scryfall):
    p = Pipeline(model=model, scryfall=scryfall)
    p._cached_art_matcher = FakeArtMatcher()  # avoid real network/pHash
    return p


FRAME = np.zeros((10, 10, 3), dtype=np.uint8)


def test_scan_candidates_returns_ranked_candidates():
    cr = CardRead(name="Lightning Bolt", set_code="m10", collector_number="146",
                  foil=False, language="en", condition_estimate="NM", condition_reason="")
    printings = [
        _printing("id-m10", "m10", "146", "Magic 2010"),
        _printing("id-m11", "m11", "149", "Magic 2011"),
    ]
    p = _pipeline(FakeModel(cr), FakeScryfall(printings))

    out = p.scan_candidates(FRAME)

    assert out["identified"] is True
    assert out["error"] is None
    assert out["card_read"]["name"] == "Lightning Bolt"
    assert out["card_read"]["condition_estimate"] == "NM"
    assert [c["set"] for c in out["candidates"]] == ["m10", "m11"]
    # ranked best-first by pHash distance
    assert out["candidates"][0]["phash_distance"] == 0
    assert out["candidates"][0]["image_normal"] == "http://img/id-m10-n.jpg"
    assert "finishes" in out["candidates"][0]


def test_scan_candidates_no_name_read():
    cr = CardRead(name="", set_code="", collector_number="", foil=False,
                  language="en", condition_estimate="NM", condition_reason="")
    p = _pipeline(FakeModel(cr), FakeScryfall([]))

    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert out["candidates"] == []
    assert "No card name" in out["error"]


def test_scan_candidates_vision_failure():
    class Boom:
        def read_card(self, frame):
            raise RuntimeError("ollama down")
    p = _pipeline(Boom(), FakeScryfall([]))

    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert "Vision read failed" in out["error"]


def test_scan_candidates_no_printings_found():
    cr = CardRead(name="Fake Card", set_code="", collector_number="", foil=False,
                  language="en", condition_estimate="NM", condition_reason="")
    p = _pipeline(FakeModel(cr), FakeScryfall(raise_exc=ScryfallError("not found")))

    out = p.scan_candidates(FRAME)
    assert out["identified"] is True
    assert out["candidates"] == []
    assert "No printings" in out["error"]


def test_search_candidates_sorts_newest_first():
    printings = [
        {**_printing("old", "lea", "161", "Limited Edition Alpha"), "released_at": "1993-08-05"},
        {**_printing("new", "m11", "149", "Magic 2011"), "released_at": "2010-07-16"},
    ]
    p = _pipeline(FakeModel(None), FakeScryfall(printings))
    out = p.search_candidates("Lightning Bolt")
    assert [c["set"] for c in out] == ["m11", "lea"]  # newest first
