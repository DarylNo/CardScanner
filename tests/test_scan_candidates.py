"""Tests for Pipeline.scan_candidates() with a fake art index + art matcher."""

import numpy as np
import pytest

from mtg_card_scanner.art_index import ArtIndexError
from mtg_card_scanner.pipeline import Pipeline
from mtg_card_scanner.scryfall import ScryfallError


class FakeIndex:
    """Returns canned identify() matches; optionally different per call."""

    def __init__(self, matches=None, per_call=None, raise_exc=None):
        self._matches = matches or []
        self._per_call = list(per_call) if per_call else None
        self._raise = raise_exc
        self.calls = 0

    def identify(self, frame, top_n=5):
        self.calls += 1
        if self._raise:
            raise self._raise
        if self._per_call is not None:
            return self._per_call.pop(0) if self._per_call else []
        return self._matches


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


def _match(name="Lightning Bolt", distance=4, **over):
    m = {
        "name": name,
        "scryfall_id": "sid-1",
        "set": "m10",
        "collector_number": "146",
        "artist": "Christopher Moeller",
        "distance": distance,
    }
    m.update(over)
    return m


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


def _pipeline(index, scryfall):
    p = Pipeline(index=index, scryfall=scryfall)
    p._cached_art_matcher = FakeArtMatcher()  # avoid real network/pHash
    return p


# Textured noise, not zeros: scan_candidates now rejects blank-surface frames
# (black card holder / empty tray) before ever consulting the index, so a
# frame that should REACH the index must contain card-like detail.
FRAME = np.random.default_rng(42).integers(0, 256, (192, 138, 3), dtype=np.uint8)


def test_scan_candidates_returns_ranked_candidates():
    printings = [
        _printing("id-m10", "m10", "146", "Magic 2010"),
        _printing("id-m11", "m11", "149", "Magic 2011"),
    ]
    idx = FakeIndex([_match(distance=80), _match(name="Chain Lightning", distance=170)])
    p = _pipeline(idx, FakeScryfall(printings))

    out = p.scan_candidates(FRAME)

    assert out["identified"] is True
    assert out["error"] is None
    assert out["card_read"]["name"] == "Lightning Bolt"
    assert out["card_read"]["condition_estimate"] == "NM"
    assert out["card_read"]["foil"] is False
    assert out["card_read"]["artist"] == "Christopher Moeller"
    assert out["confidence"]["name"] == "high"  # s=80 <= 90
    assert [c["set"] for c in out["candidates"]] == ["m10", "m11"]
    # ranked best-first by pHash distance
    assert out["candidates"][0]["phash_distance"] == 0
    assert out["candidates"][0]["image_normal"] == "http://img/id-m10-n.jpg"
    assert "finishes" in out["candidates"][0]


def test_scan_candidates_medium_confidence():
    idx = FakeIndex([_match(distance=100)])
    p = _pipeline(idx, FakeScryfall([_printing("id", "m10", "146", "Magic 2010")]))
    out = p.scan_candidates(FRAME)
    assert out["identified"] is True
    assert out["confidence"]["name"] == "medium"  # 90 < s=100 <= 110


def test_scan_candidates_alternates_populated():
    idx = FakeIndex([
        _match(distance=80),
        _match(name="Chain Lightning", distance=168),
        _match(name="Firebolt", distance=175),
        _match(name="Shock", distance=190),
    ])
    p = _pipeline(idx, FakeScryfall([_printing("id", "m10", "146", "Magic 2010")]))
    out = p.scan_candidates(FRAME)
    assert out["card_read"]["alternates"] == [
        {"name": "Chain Lightning", "distance": 168},
        {"name": "Firebolt", "distance": 175},
    ]


def test_scan_candidates_over_threshold_not_identified():
    idx = FakeIndex([_match(distance=150), _match(name="Chain Lightning", distance=160)])
    p = _pipeline(idx, FakeScryfall([]))

    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert out["candidates"] == []
    assert "No confident art match" in out["error"]
    assert "Lightning Bolt" in out["error"]   # guesses surfaced for the user
    assert "manual search" in out["error"].lower()
    # nearest guess still exposed for the UI
    assert out["card_read"]["name"] == "Lightning Bolt"
    assert out["confidence"]["name"] == "low"


def test_scan_candidates_multi_frame_retry():
    """First (sharpest) frame over threshold, second frame confident → identified."""
    first = [_match(distance=200)]
    second = [_match(distance=85)]
    idx = FakeIndex(per_call=[first, second])
    p = _pipeline(idx, FakeScryfall([_printing("id", "m10", "146", "Magic 2010")]))

    out = p.scan_candidates([FRAME, FRAME.copy()])
    assert idx.calls == 2
    assert out["identified"] is True
    assert out["confidence"]["name"] == "high"


def test_scan_candidates_no_retry_when_confident():
    idx = FakeIndex([_match(distance=75)])
    p = _pipeline(idx, FakeScryfall([_printing("id", "m10", "146", "Magic 2010")]))
    p.scan_candidates([FRAME, FRAME.copy(), FRAME.copy()])
    assert idx.calls == 1  # confident on the first frame — no retries


def test_scan_candidates_index_not_built():
    idx = FakeIndex(raise_exc=ArtIndexError(
        "Art index not built — run: python -m mtg_card_scanner.art_index build"))
    p = _pipeline(idx, FakeScryfall([]))

    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert "art_index build" in out["error"]


def test_scan_candidates_index_unexpected_failure():
    idx = FakeIndex(raise_exc=RuntimeError("sqlite exploded"))
    p = _pipeline(idx, FakeScryfall([]))

    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert "Art identification failed" in out["error"]


def test_scan_candidates_no_index_configured():
    p = _pipeline(None, FakeScryfall([]))
    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert "No art index" in out["error"]


def test_scan_candidates_empty_matches():
    idx = FakeIndex([])
    p = _pipeline(idx, FakeScryfall([]))
    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert "no matches" in out["error"]


def test_scan_candidates_no_printings_found():
    idx = FakeIndex([_match(name="Fake Card", distance=80)])
    p = _pipeline(idx, FakeScryfall(raise_exc=ScryfallError("not found")))

    out = p.scan_candidates(FRAME)
    assert out["identified"] is True
    assert out["candidates"] == []
    assert "Printings lookup failed" in out["error"]


def test_search_candidates_sorts_newest_first():
    printings = [
        {**_printing("old", "lea", "161", "Limited Edition Alpha"), "released_at": "1993-08-05"},
        {**_printing("new", "m11", "149", "Magic 2011"), "released_at": "2010-07-16"},
    ]
    p = _pipeline(FakeIndex([]), FakeScryfall(printings))
    out = p.search_candidates("Lightning Bolt")
    assert [c["set"] for c in out] == ["m11", "lea"]  # newest first


def test_scan_candidates_no_card_detected():
    """Scores above the no-card bar mean an empty frame — reject outright."""
    idx = FakeIndex([_match(distance=240)])
    p = _pipeline(idx, FakeScryfall([]))
    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert out.get("no_card") is True
    assert "No card detected" in out["error"]


def test_scan_candidates_unconfident_is_not_no_card():
    """A real-but-glared card (over confident bar, under no-card bar) is kept."""
    idx = FakeIndex([_match(distance=170)])
    p = _pipeline(idx, FakeScryfall([]))
    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert out.get("no_card") is None
    assert "manual search" in out["error"].lower()


def test_scan_candidates_margin_accepts_lone_leader():
    """
    Live case: correct card at d=116 (over the 110 bar) with the runner-up 22
    points back was kicked to manual search. A lone leader below the noise
    floor with a wide gap must be accepted.
    """
    idx = FakeIndex([_match(distance=116),
                     _match(name="Eternal Isolation", distance=138)])
    p = _pipeline(idx, FakeScryfall([_printing("id", "m10", "146", "Magic 2010")]))
    out = p.scan_candidates(FRAME)
    assert out["identified"] is True
    assert out["candidates"]


def test_scan_candidates_tight_cluster_stays_manual():
    """Over the bar with a close runner-up is noise — manual search."""
    idx = FakeIndex([_match(distance=116),
                     _match(name="Other", distance=124)])
    p = _pipeline(idx, FakeScryfall([]))
    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert "manual search" in out["error"].lower()


def test_unconfident_scan_still_offers_best_guess_candidates():
    """Refused-band scans (e.g. d=116 gap 12) attach the top guess's ranked
    printings so the fix is one tap — while identified stays False."""
    idx = FakeIndex([_match(distance=116), _match(name="Oculus", distance=128)])
    p = _pipeline(idx, FakeScryfall([_printing("id", "m10", "146", "Magic 2010")]))
    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert out["candidates"]
    assert "manual search" in out["error"].lower()


def test_noise_floor_gets_no_best_guess_grid():
    """Above the margin ceiling the guess is junk — no grid, just the error."""
    idx = FakeIndex([_match(distance=150)])
    p = _pipeline(idx, FakeScryfall([_printing("id", "m10", "146", "Magic 2010")]))
    out = p.scan_candidates(FRAME)
    assert out["identified"] is False
    assert out["candidates"] == []


def test_scan_candidates_margin_cannot_rescue_noise_floor():
    """A huge gap does not make a noise-floor score (>140) trustworthy."""
    idx = FakeIndex([_match(distance=150),
                     _match(name="Other", distance=200)])
    p = _pipeline(idx, FakeScryfall([]))
    out = p.scan_candidates(FRAME)
    assert out["identified"] is False


def test_scan_candidates_blank_surface_is_no_card():
    """
    A card-shaped uniform object (black card holder on the tray) must be
    rejected BEFORE identification — its near-featureless hash can land close
    enough to a dark artwork to store junk (a holder scanned as a real card).
    """
    frame = np.full((480, 640, 3), 235, np.uint8)   # white tray...
    frame[100:380, 220:420] = 22                    # ...with a black holder on it
    idx = FakeIndex([_match(distance=80)])          # a would-be "confident" match
    p = _pipeline(idx, FakeScryfall([]))
    out = p.scan_candidates(frame)
    assert out.get("no_card") is True
    assert idx.calls == 0                           # index never consulted


def test_scan_candidates_empty_tray_is_no_card_without_index():
    """A bare tray frame is rejected by the blank guard, not by index distance."""
    idx = FakeIndex([_match(distance=80)])
    p = _pipeline(idx, FakeScryfall([]))
    out = p.scan_candidates(np.full((480, 640, 3), 235, np.uint8))
    assert out.get("no_card") is True
    assert idx.calls == 0
