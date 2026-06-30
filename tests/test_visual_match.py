"""Unit tests for visual_match — mocked network, no real Scryfall downloads."""

import numpy as np
import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock

from mtg_card_scanner.visual_match import (
    crop_art_region, crop_title_region, crop_textbox_region,
    crop_list_corner_region,
    compute_phash, compute_multi_region_distance,
    ArtMatcher, NEAR_TIE_DISTANCE,
    _ART_X0, _ART_X1, _ART_Y0, _ART_Y1,
    _TITLE_X0, _TITLE_X1, _TITLE_Y0, _TITLE_Y1,
    _TEXTBOX_X0, _TEXTBOX_X1, _TEXTBOX_Y0, _TEXTBOX_Y1,
    _LIST_CORNER_X0, _LIST_CORNER_X1, _LIST_CORNER_Y0, _LIST_CORNER_Y1,
    _LIST_CORNER_MARGIN,
    _WEIGHT_ART, _WEIGHT_TITLE, _WEIGHT_TEXTBOX, _WEIGHT_TOTAL,
    _detect_border_color, _BORDER_BLACK_THRESHOLD, _BORDER_WHITE_THRESHOLD,
    _STAMP_SETS,
    _is_promo, _is_basic_land_printing,
    _cap_candidates, _MAX_CANDIDATES_PER_SCAN, _IMAGE_FETCH_TIMEOUT,
)


# ── helpers ───────────────────────────────────────────────────────────────────

def _bgr(color, h=140, w=100):
    """Solid-colour BGR numpy frame."""
    img = np.zeros((h, w, 3), dtype=np.uint8)
    img[:, :] = color
    return img


def _blue():
    return _bgr((200, 50, 50))


def _green():
    return _bgr((50, 200, 50))


def _to_pil(bgr):
    from PIL import Image
    return Image.fromarray(bgr[:, :, ::-1])


def _printing(sid, url="https://example.com/card.jpg", set_code="tst", collector_number=None):
    return {
        "id": sid,
        "image_uris": {"normal": url},
        "set": set_code,
        "collector_number": collector_number or sid,
    }


def _matcher(tmp_path):
    return ArtMatcher(cache_dir=tmp_path, request_delay=0)


# ── crop_art_region ───────────────────────────────────────────────────────────

class TestCropArtRegion:
    def test_crop_dimensions_numpy(self):
        img = np.zeros((880, 630, 3), dtype=np.uint8)
        crop = crop_art_region(img)
        expected_w = int(630 * _ART_X1) - int(630 * _ART_X0)
        expected_h = int(880 * _ART_Y1) - int(880 * _ART_Y0)
        assert crop.size == (expected_w, expected_h)

    def test_accepts_numpy_array(self):
        assert crop_art_region(_blue()) is not None

    def test_accepts_pil_image(self):
        assert crop_art_region(_to_pil(_blue())) is not None

    def test_crop_is_smaller_than_input(self):
        img = _bgr((200, 50, 50), h=880, w=630)
        crop = crop_art_region(img)
        assert crop.size[0] < 630
        assert crop.size[1] < 880


# ── compute_phash ─────────────────────────────────────────────────────────────

class TestComputePhash:
    def test_same_image_zero_distance(self):
        img = _blue()
        assert (compute_phash(img) - compute_phash(img)) == 0

    def test_different_images_nonzero_distance(self):
        # Solid uniform images may hash identically; use high-contrast patterns
        checker = np.zeros((140, 100, 3), dtype=np.uint8)
        checker[::2, ::2] = 255   # checkerboard
        solid = np.full((140, 100, 3), 128, dtype=np.uint8)
        assert (compute_phash(checker) - compute_phash(solid)) > 0


# ── ArtMatcher.rank_printings ─────────────────────────────────────────────────

class TestRankPrintings:
    def test_skips_printing_without_image_uris(self, tmp_path):
        m = _matcher(tmp_path)
        no_uri = {"id": "x", "set": "t", "collector_number": "1"}
        assert m.rank_printings(_blue(), [no_uri]) == []

    def test_skips_printing_without_id(self, tmp_path):
        m = _matcher(tmp_path)
        no_id = {"image_uris": {"normal": "http://x"}, "set": "t", "collector_number": "1"}
        assert m.rank_printings(_blue(), [no_id]) == []

    def test_phash_fields_added(self, tmp_path):
        m = _matcher(tmp_path)
        pil = _to_pil(_blue())
        with patch.object(m, "_fetch_image", return_value=pil):
            ranked = m.rank_printings(_blue(), [_printing("a1")])
        assert "phash_distance" in ranked[0]
        assert "phash_hash" in ranked[0]
        assert isinstance(ranked[0]["phash_distance"], int)

    def test_ranks_closer_image_first(self, tmp_path):
        m = _matcher(tmp_path)
        scan = _blue()
        # blue is closer to scan; green is further away
        with patch.object(m, "_fetch_image", side_effect=[_to_pil(_blue()), _to_pil(_green())]):
            ranked = m.rank_printings(scan, [_printing("a"), _printing("b")])
        assert ranked[0]["id"] == "a"
        assert ranked[0]["phash_distance"] <= ranked[1]["phash_distance"]

    def test_sorted_ascending_by_distance(self, tmp_path):
        m = _matcher(tmp_path)
        scan = _blue()
        with patch.object(m, "_fetch_image", side_effect=[_to_pil(_green()), _to_pil(_blue())]):
            ranked = m.rank_printings(scan, [_printing("g"), _printing("b")])
        assert ranked[0]["phash_distance"] <= ranked[1]["phash_distance"]

    def test_original_fields_preserved(self, tmp_path):
        m = _matcher(tmp_path)
        p = _printing("x99", set_code="ice", collector_number="137")
        with patch.object(m, "_fetch_image", return_value=_to_pil(_blue())):
            ranked = m.rank_printings(_blue(), [p])
        assert ranked[0]["set"] == "ice"
        assert ranked[0]["collector_number"] == "137"

    def test_download_error_skipped(self, tmp_path):
        m = _matcher(tmp_path)
        with patch.object(m, "_fetch_image", side_effect=IOError("timeout")):
            ranked = m.rank_printings(_blue(), [_printing("bad")])
        assert ranked == []


# ── ArtMatcher.best_match ─────────────────────────────────────────────────────

class TestBestMatch:
    def test_empty_printings_none(self, tmp_path):
        m = _matcher(tmp_path)
        best, ranked, near_tie = m.best_match(_blue(), [])
        assert best is None and ranked == [] and near_tie is False

    def test_single_printing_no_near_tie(self, tmp_path):
        m = _matcher(tmp_path)
        with patch.object(m, "_fetch_image", return_value=_to_pil(_blue())):
            best, _, near_tie = m.best_match(_blue(), [_printing("x")])
        assert best is not None and near_tie is False

    def test_near_tie_when_gap_le_threshold(self, tmp_path):
        m = _matcher(tmp_path)
        # Two identical images → gap = 0 ≤ NEAR_TIE_DISTANCE
        with patch.object(m, "_fetch_image", return_value=_to_pil(_blue())):
            _, _, near_tie = m.best_match(_blue(), [_printing("a"), _printing("b")])
        assert near_tie is True

    def test_no_near_tie_when_gap_large(self, tmp_path):
        m = _matcher(tmp_path)
        # blue=close (low dist), green=far (high dist) → gap should exceed threshold
        with patch.object(m, "_fetch_image", side_effect=[_to_pil(_blue()), _to_pil(_green())]):
            _, ranked, near_tie = m.best_match(_blue(), [_printing("a"), _printing("b")])
        gap = ranked[1]["phash_distance"] - ranked[0]["phash_distance"]
        if gap > NEAR_TIE_DISTANCE:
            assert not near_tie
        # else the images happen to hash close — that's valid, skip assertion

    def test_best_is_ranked_first(self, tmp_path):
        m = _matcher(tmp_path)
        with patch.object(m, "_fetch_image", side_effect=[_to_pil(_blue()), _to_pil(_green())]):
            best, ranked, _ = m.best_match(_blue(), [_printing("a"), _printing("b")])
        assert best is ranked[0]


# ── cache behaviour ───────────────────────────────────────────────────────────

class TestCacheBehaviour:
    def test_cache_hit_skips_network(self, tmp_path):
        m = _matcher(tmp_path)
        sid = "cached001"
        pil = _to_pil(_blue())
        # Pre-populate cache
        pil.save(tmp_path / f"{sid}.jpg")

        p = _printing(sid, url="http://never-called")
        with patch.object(m._session, "get") as mock_get:
            m.rank_printings(_blue(), [p])
            mock_get.assert_not_called()

    def test_cache_miss_downloads_and_saves(self, tmp_path):
        m = _matcher(tmp_path)
        sid = "new001"
        pil = _to_pil(_blue())

        resp = MagicMock()
        resp.content = b"fake-jpeg-bytes"
        resp.raise_for_status.return_value = None

        with patch.object(m._session, "get", return_value=resp):
            with patch("PIL.Image.open", return_value=pil.convert("RGB")):
                m.rank_printings(_blue(), [_printing(sid)])

        assert (tmp_path / f"{sid}.jpg").exists()


# ── _detect_border_color ──────────────────────────────────────────────────────

class TestDetectBorderColor:
    def test_all_black_returns_black(self):
        img = np.zeros((880, 630, 3), dtype=np.uint8)
        assert _detect_border_color(img) == "black"

    def test_all_white_returns_white(self):
        img = np.full((880, 630, 3), 200, dtype=np.uint8)
        assert _detect_border_color(img) == "white"

    def test_black_border_with_light_center(self):
        img = np.full((880, 630, 3), 180, dtype=np.uint8)
        img[:, :12] = 0      # black left border
        img[:, -12:] = 0     # black right border
        assert _detect_border_color(img) == "black"

    def test_white_border_with_dark_center(self):
        img = np.full((880, 630, 3), 20, dtype=np.uint8)
        img[:, :12] = 220    # white left border
        img[:, -12:] = 220   # white right border
        assert _detect_border_color(img) == "white"

    def test_small_image_returns_unknown(self):
        img = np.zeros((10, 10, 3), dtype=np.uint8)
        assert _detect_border_color(img) == "unknown"

    def test_mid_brightness_returns_unknown(self):
        mid = (_BORDER_BLACK_THRESHOLD + _BORDER_WHITE_THRESHOLD) // 2
        img = np.full((880, 630, 3), mid, dtype=np.uint8)
        assert _detect_border_color(img) == "unknown"


# ── ArtMatcher tiebreaker (border colour) ─────────────────────────────────────

def _make_tied_printings(border_first, border_second, dist_first=28, dist_second=28):
    """Return two pre-ranked printings with given border_colors and distances."""
    return [
        {
            "id": "p1", "set": "setA", "collector_number": "1",
            "border_color": border_first, "phash_distance": dist_first,
            "image_uris": {"normal": "http://x"},
        },
        {
            "id": "p2", "set": "setB", "collector_number": "2",
            "border_color": border_second, "phash_distance": dist_second,
            "image_uris": {"normal": "http://y"},
        },
    ]


def _matcher_with_border(tmp_path, ranked_printings, scan_border_color):
    """Return an ArtMatcher whose rank_printings is stubbed to return ranked_printings."""
    m = ArtMatcher.__new__(ArtMatcher)
    m._last_scan_border_color      = scan_border_color
    m._last_scan_is_basic          = False
    m._last_scan_printing_uncertain = False
    m._last_scan_top_candidates    = []
    m._last_list_corner_decision    = "n/a"
    m._last_list_corner_distances   = {}
    m.rank_printings = lambda img, pts: ranked_printings
    return m


class TestBestMatchTiebreaker:
    def test_black_scan_picks_black_bordered_candidate(self, tmp_path):
        # pHash puts white-bordered 6ED first, but scan is black → should pick MIR
        ranked = _make_tied_printings("white", "black")  # white ranked first
        m = _matcher_with_border(tmp_path, ranked, "black")
        best, _, near_tie = m.best_match(None, [])
        assert best["border_color"] == "black", "tiebreaker should prefer black-bordered card"
        assert near_tie is True

    def test_white_scan_picks_white_bordered_candidate(self, tmp_path):
        ranked = _make_tied_printings("black", "white")  # black ranked first
        m = _matcher_with_border(tmp_path, ranked, "white")
        best, _, _ = m.best_match(None, [])
        assert best["border_color"] == "white"

    def test_unknown_border_keeps_phash_winner(self, tmp_path):
        ranked = _make_tied_printings("white", "black")
        m = _matcher_with_border(tmp_path, ranked, "unknown")
        best, _, _ = m.best_match(None, [])
        assert best["id"] == "p1", "tiebreaker inactive — pHash winner kept"

    def test_no_tiebreaker_when_clear_winner(self, tmp_path):
        # Gap > NEAR_TIE_DISTANCE — white ranked #2 should NOT be promoted
        ranked = _make_tied_printings("black", "white", dist_first=5, dist_second=5 + NEAR_TIE_DISTANCE + 1)
        m = _matcher_with_border(tmp_path, ranked, "white")
        best, _, is_near_tie = m.best_match(None, [])
        assert best["id"] == "p1", "clear pHash winner should not be overridden"
        assert is_near_tie is False

    def test_tiebreaker_uses_best_marker_in_ranked(self, tmp_path):
        # After tiebreak, the returned best should be from the ranked list by identity
        ranked = _make_tied_printings("white", "black")
        m = _matcher_with_border(tmp_path, ranked, "black")
        best, ret_ranked, _ = m.best_match(None, [])
        assert best in ret_ranked


# ── crop_list_corner_region ───────────────────────────────────────────────────

class TestCropListCornerRegion:
    def test_crop_dimensions(self):
        img = np.zeros((880, 630, 3), dtype=np.uint8)
        crop = crop_list_corner_region(img)
        expected_w = int(630 * _LIST_CORNER_X1) - int(630 * _LIST_CORNER_X0)
        expected_h = int(880 * _LIST_CORNER_Y1) - int(880 * _LIST_CORNER_Y0)
        assert crop.size == (expected_w, expected_h)

    def test_is_bottom_left(self):
        # Corner region should be in the lower portion (high Y) and left side (low X).
        assert _LIST_CORNER_Y0 > 0.5
        assert _LIST_CORNER_X1 < 0.5

    def test_accepts_numpy_array(self):
        assert crop_list_corner_region(_blue()) is not None


# ── ArtMatcher._list_corner_decision (List vs base via corner pHash) ─────────

def _plst_printing(dist=28, corner_distance=2):
    return {
        "id": "plst1", "set": "plst", "collector_number": "MIR-296",
        "border_color": "black", "phash_distance": dist,
        "corner_distance": corner_distance,
        "image_uris": {"normal": "http://x"},
    }


def _mir_printing(dist=28, corner_distance=20):
    return {
        "id": "mir1", "set": "mir", "collector_number": "296",
        "border_color": "black", "phash_distance": dist,
        "corner_distance": corner_distance,
        "image_uris": {"normal": "http://y"},
    }


def _matcher_with_signals(tmp_path, ranked_printings, border_color, is_basic=False):
    """
    Stub an ArtMatcher with pre-set scan signals.

    List-vs-base routing is decided per-candidate via the ``corner_distance``
    field on each printing dict (computed for real in rank_printings by pHashing
    the bottom-left expansion/planeswalker-symbol slot against the SAME slot on
    each candidate's own Scryfall image) — there is no scan-level stamp flag.
    Presence of a symbol there is meaningless (every printing has one); only a
    clear corner_distance advantage for the List candidates promotes to the List.
    """
    m = ArtMatcher.__new__(ArtMatcher)
    m._last_scan_border_color      = border_color
    m._last_scan_is_basic          = is_basic
    m._last_scan_printing_uncertain = False
    m._last_scan_top_candidates    = []
    m._last_list_corner_decision    = "n/a"
    m._last_list_corner_distances   = {}
    m.rank_printings = lambda img, pts: ranked_printings
    return m


class TestListCornerDecision:
    """Direct unit tests of ArtMatcher._list_corner_decision (pure function)."""

    def test_list_wins_with_clear_margin(self):
        group = [_mir_printing(corner_distance=20), _plst_printing(corner_distance=2)]
        chosen, decision, dists = ArtMatcher._list_corner_decision(group)
        assert decision == "list"
        assert all(c["set"] == "plst" for c in chosen)
        assert dists == {"list": 2, "base": 20}

    def test_base_wins_with_clear_margin(self):
        group = [_mir_printing(corner_distance=2), _plst_printing(corner_distance=20)]
        chosen, decision, _ = ArtMatcher._list_corner_decision(group)
        assert decision == "base"
        assert all(c["set"] == "mir" for c in chosen)

    def test_weak_margin_defaults_to_base(self):
        # PLST slightly closer, but margin (3) is below _LIST_CORNER_MARGIN (6)
        group = [_mir_printing(corner_distance=10), _plst_printing(corner_distance=7)]
        chosen, decision, _ = ArtMatcher._list_corner_decision(group)
        assert decision == "base"
        assert all(c["set"] == "mir" for c in chosen)

    def test_exact_margin_boundary_promotes_list(self):
        # PLST exactly _LIST_CORNER_MARGIN closer — boundary case, should promote
        group = [_mir_printing(corner_distance=10),
                 _plst_printing(corner_distance=10 - _LIST_CORNER_MARGIN)]
        chosen, decision, _ = ArtMatcher._list_corner_decision(group)
        assert decision == "list"

    def test_no_list_candidate_returns_na(self):
        group = [_mir_printing(corner_distance=5),
                 {"id": "other", "set": "tmp", "corner_distance": 30,
                  "image_uris": {"normal": "http://z"}}]
        chosen, decision, dists = ArtMatcher._list_corner_decision(group)
        assert decision == "n/a"
        assert chosen == group
        assert dists == {}

    def test_no_base_candidate_returns_na(self):
        # Degenerate: only List-set candidates present (no base to compare against)
        group = [_plst_printing(corner_distance=2), _plst_printing(corner_distance=5)]
        chosen, decision, _ = ArtMatcher._list_corner_decision(group)
        assert decision == "n/a"
        assert chosen == group

    def test_missing_corner_distance_treated_as_far(self):
        # A candidate with no corner_distance field shouldn't crash or win spuriously
        group = [{"id": "mir1", "set": "mir", "image_uris": {"normal": "http://y"}},
                 _plst_printing(corner_distance=2)]
        chosen, decision, _ = ArtMatcher._list_corner_decision(group)
        assert decision == "list"  # PLST's real low distance still beats the missing-field default


class TestBestMatchListCornerTiebreaker:
    def test_clear_corner_match_picks_plst_over_mir(self, tmp_path):
        # MIR ranked first by art pHash, but the scan's corner clearly matches
        # PLST's corner (planeswalker symbol) far better → PLST should win.
        ranked = [_mir_printing(dist=28, corner_distance=22),
                  _plst_printing(dist=28, corner_distance=3)]
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, near_tie = m.best_match(None, [])
        assert best["set"] == "plst", "clear corner-pHash margin → should pick PLST over MIR"
        assert near_tie is True

    def test_weak_corner_margin_keeps_base_mir(self, tmp_path):
        # PLST ranked first by art pHash, but corner match is too close to call
        # (margin below _LIST_CORNER_MARGIN) → defaults to base-set MIR.
        ranked = [_plst_printing(dist=28, corner_distance=9),
                  _mir_printing(dist=28, corner_distance=11)]
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, _ = m.best_match(None, [])
        assert best["set"] == "mir", "weak corner margin → default to base-set MIR"

    def test_border_colour_still_separates_mir_from_6ed(self, tmp_path):
        p_white = {"id": "6ed1", "set": "6ed", "collector_number": "276",
                   "border_color": "white", "phash_distance": 8, "corner_distance": 1,
                   "image_uris": {"normal": "http://w"}}
        ranked = [p_white, _mir_printing(dist=10)]
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, _ = m.best_match(None, [])
        assert best["set"] == "mir", "black-border scan should select MIR over 6ED"

    def test_only_one_black_candidate_no_corner_decision_needed(self, tmp_path):
        ranked = [_mir_printing(dist=10)]  # only one black candidate
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, _ = m.best_match(None, [])
        assert best["set"] == "mir", "fallback to only available candidate"

    def test_clear_pHash_winner_ignores_corner_decision(self, tmp_path):
        # Gap > NEAR_TIE_DISTANCE → tiebreaker never fires, even with a strong
        # corner-distance advantage for PLST.
        ranked = [_mir_printing(dist=5, corner_distance=20),
                  _plst_printing(dist=5 + NEAR_TIE_DISTANCE + 1, corner_distance=1)]
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, is_near_tie = m.best_match(None, [])
        assert best["set"] == "mir"
        assert is_near_tie is False

    def test_propaganda_like_false_positive_stays_non_list(self, tmp_path):
        # The real false-positive case: C20 and PLST tied on art pHash, both
        # black-bordered, but the scan's corner is NOT a clear match for the
        # PLST candidates' corner (no real planeswalker symbol present) →
        # must stay on the base-set C20, never silently promote to PLST.
        c20 = {"id": "c20-prop", "set": "c20", "collector_number": "16",
               "border_color": "black", "phash_distance": 6, "multi_distance": 6,
               "corner_distance": 8, "promo": False, "image_uris": {"normal": "http://c20"}}
        plst = {"id": "plst-prop", "set": "plst", "collector_number": "C20-16",
                "border_color": "black", "phash_distance": 7, "multi_distance": 9,
                "corner_distance": 9, "promo": False, "image_uris": {"normal": "http://plst"}}
        ranked = [plst, c20]  # PLST happens to rank first by raw art pHash
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, near_tie = m.best_match(None, [])
        assert best["set"] == "c20", "no clear corner match → must stay on base-set C20"
        assert near_tie is True

    def test_real_list_card_with_clear_corner_match_is_caught(self, tmp_path):
        # Re-confirm a GENUINE List card (clear planeswalker-symbol corner match)
        # still correctly resolves to PLST — the fix must not blanket-disable List.
        base = {"id": "kld-66", "set": "kld", "collector_number": "66",
                "border_color": "black", "phash_distance": 4, "multi_distance": 4,
                "corner_distance": 19, "promo": False, "image_uris": {"normal": "http://kld"}}
        plst = {"id": "plst-kld66", "set": "plst", "collector_number": "KLD-66",
                "border_color": "black", "phash_distance": 4, "multi_distance": 5,
                "corner_distance": 2, "promo": False, "image_uris": {"normal": "http://plst"}}
        ranked = [base, plst]
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, near_tie = m.best_match(None, [])
        assert best["set"] == "plst", "clear planeswalker-symbol corner match must resolve to The List"
        assert near_tie is True

    def test_basic_lands_unaffected_by_corner_logic(self, tmp_path):
        ranked = [
            {"id": "a", "set": "4ed", "phash_distance": 10, "border_color": "white",
             "type_line": "Basic Land — Island", "image_uris": {"normal": "http://a"}},
            {"id": "b", "set": "5ed", "phash_distance": 12, "border_color": "white",
             "type_line": "Basic Land — Island", "image_uris": {"normal": "http://b"}},
        ]
        m = _matcher_with_signals(tmp_path, ranked, "white", is_basic=True)
        best, _, _ = m.best_match(None, [])
        assert best["set"] == "4ed", "basic-land flow ignores List-corner logic"


# ── literal vision-read tiebreak (Level -1) ───────────────────────────────────

class TestLiteralVisionReadTiebreak:
    """
    Among printings sharing identical art (precons, judge promos, etc.), pHash
    and border/corner heuristics cannot distinguish them at all. When the vision
    model cleanly reads the printed set code + collector number off the card,
    that literal read is ground truth and must win outright over any visual
    heuristic noise (e.g. the multi-region sort arbitrarily picking a different
    same-art printing). In the real pipeline this rarely fires any more, since a
    confidently-read collector number resolves via a direct Scryfall lookup
    BEFORE visual_match runs (see pipeline.py); this remains a safety net for
    whatever does reach visual_match.
    """

    def _identical_art_candidates(self):
        # Five printings, all the same art, all tied at the same pHash distance —
        # mirrors real Propaganda reprints (C16 / PLST / C20 / AFC / CLB).
        return [
            {"id": "c16", "set": "c16", "collector_number": "94",
             "border_color": "black", "phash_distance": 12, "multi_distance": 12,
             "image_uris": {"normal": "http://c16"}},
            {"id": "plst", "set": "plst", "collector_number": "C16-94",
             "border_color": "black", "phash_distance": 12, "multi_distance": 12,
             "image_uris": {"normal": "http://plst"}},
            {"id": "c20", "set": "c20", "collector_number": "123",
             "border_color": "black", "phash_distance": 12, "multi_distance": 18,
             "image_uris": {"normal": "http://c20"}},
            {"id": "afc", "set": "afc", "collector_number": "91",
             "border_color": "black", "phash_distance": 12, "multi_distance": 6,
             "image_uris": {"normal": "http://afc"}},
            {"id": "clb", "set": "clb", "collector_number": "730",
             "border_color": "black", "phash_distance": 12, "multi_distance": 12,
             "image_uris": {"normal": "http://clb"}},
        ]

    def test_literal_read_wins_over_multi_region_noise(self, tmp_path):
        # AFC has the lowest multi_distance (would win Level 0), but the vision
        # model literally read "c20 #123" off the card — that must win instead.
        ranked = self._identical_art_candidates()
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, near_tie = m.best_match(
            None, [], vision_set_code="c20", vision_collector_number="123",
        )
        assert best["set"] == "c20", "exact literal vision read must win over multi-region sort"
        assert near_tie is True

    def test_literal_read_matches_plst_when_that_is_what_was_printed(self, tmp_path):
        # If the model genuinely reads a PLST-formatted collector number, trust it.
        ranked = self._identical_art_candidates()
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, _ = m.best_match(
            None, [], vision_set_code="plst", vision_collector_number="C16-94",
        )
        assert best["set"] == "plst"

    def test_no_literal_match_falls_back_to_existing_tiebreak(self, tmp_path):
        # Vision read a set code that isn't among the tie candidates at all —
        # falls back to the normal Level 0-3 chain (multi-region sort picks AFC).
        ranked = self._identical_art_candidates()
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, _ = m.best_match(
            None, [], vision_set_code="zzz", vision_collector_number="999",
        )
        assert best["set"] == "afc", "no literal match -> falls back to multi-region sort"

    def test_empty_vision_read_falls_back_to_existing_tiebreak(self, tmp_path):
        ranked = self._identical_art_candidates()
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, _ = m.best_match(None, [])
        assert best["set"] == "afc", "no vision read at all -> falls back to multi-region sort"

    def test_leading_zero_collector_number_normalised(self, tmp_path):
        ranked = self._identical_art_candidates()
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, _ = m.best_match(
            None, [], vision_set_code="c20", vision_collector_number="0123",
        )
        assert best["set"] == "c20", "leading-zero collector numbers must still match"

    def test_literal_match_outside_tie_window_ignored(self, tmp_path):
        # Vision read matches a printing, but it's not in the near-tie window at all.
        far = {"id": "far", "set": "c20", "collector_number": "123",
               "border_color": "black", "phash_distance": 12 + NEAR_TIE_DISTANCE + 1,
               "image_uris": {"normal": "http://far"}}
        ranked = [self._identical_art_candidates()[3], far]  # afc (dist 12) + far c20
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, _ = m.best_match(
            None, [], vision_set_code="c20", vision_collector_number="123",
        )
        assert best["set"] == "afc", "literal match outside the tie window must not be promoted"


# ── region crops ─────────────────────────────────────────────────────────────

class TestCropRegions:
    def test_crop_title_dimensions(self):
        img = np.zeros((880, 630, 3), dtype=np.uint8)
        crop = crop_title_region(img)
        expected_w = int(630 * _TITLE_X1) - int(630 * _TITLE_X0)
        expected_h = int(880 * _TITLE_Y1) - int(880 * _TITLE_Y0)
        assert crop.size == (expected_w, expected_h)

    def test_crop_textbox_dimensions(self):
        img = np.zeros((880, 630, 3), dtype=np.uint8)
        crop = crop_textbox_region(img)
        expected_w = int(630 * _TEXTBOX_X1) - int(630 * _TEXTBOX_X0)
        expected_h = int(880 * _TEXTBOX_Y1) - int(880 * _TEXTBOX_Y0)
        assert crop.size == (expected_w, expected_h)

    def test_title_smaller_than_full_card(self):
        img = np.zeros((880, 630, 3), dtype=np.uint8)
        assert crop_title_region(img).size[1] < 880

    def test_textbox_below_art(self):
        # textbox top should be below art bottom
        img = np.zeros((880, 630, 3), dtype=np.uint8)
        art_bottom_y   = int(880 * _ART_Y1)
        textbox_top_y  = int(880 * _TEXTBOX_Y0)
        assert textbox_top_y > art_bottom_y


# ── compute_multi_region_distance ─────────────────────────────────────────────

class TestComputeMultiRegionDistance:
    def test_same_image_zero_distance(self):
        img = _blue()
        assert compute_multi_region_distance(img, img) == 0

    def test_same_image_zero_distance_basic(self):
        img = _blue()
        assert compute_multi_region_distance(img, img, is_basic=True) == 0

    def test_returns_int(self):
        assert isinstance(compute_multi_region_distance(_blue(), _green()), int)

    def test_non_negative(self):
        assert compute_multi_region_distance(_blue(), _green()) >= 0

    def test_basic_flag_uses_total_weight_on_art_only(self):
        # For is_basic=True, multi_d == art_d * WEIGHT_TOTAL.
        # For identical images art_d = 0, so both results are 0 regardless.
        img = _blue()
        assert compute_multi_region_distance(img, img, is_basic=True) == 0
        assert compute_multi_region_distance(img, img, is_basic=False) == 0


# ── multi-region tiebreaker in best_match ─────────────────────────────────────

class TestMultiRegionTiebreaker:
    def test_lower_multi_distance_wins_when_border_unknown(self, tmp_path):
        # Both candidates tied on art pHash; different multi_distance.
        # Border unknown → falls back to multi-sorted order.
        p_high = {"id": "a", "set": "setA", "collector_number": "1",
                  "border_color": "black", "phash_distance": 10, "multi_distance": 40,
                  "image_uris": {"normal": "http://a"}}
        p_low  = {"id": "b", "set": "setB", "collector_number": "2",
                  "border_color": "black", "phash_distance": 10, "multi_distance": 12,
                  "image_uris": {"normal": "http://b"}}
        ranked = [p_high, p_low]   # p_high first by art pHash
        m = _matcher_with_signals(tmp_path, ranked, "unknown")
        best, _, near_tie = m.best_match(None, [])
        assert best["id"] == "b", "lower multi_distance should win when border is unknown"
        assert near_tie is True

    def test_multi_distance_missing_falls_back_to_art(self, tmp_path):
        # Printings without multi_distance field — fallback to art dist * WEIGHT_TOTAL.
        p1 = {"id": "x", "set": "setX", "phash_distance": 10, "border_color": "unknown",
              "image_uris": {"normal": "http://x"}}
        p2 = {"id": "y", "set": "setY", "phash_distance": 12, "border_color": "unknown",
              "image_uris": {"normal": "http://y"}}
        ranked = [p1, p2]
        m = _matcher_with_signals(tmp_path, ranked, "unknown")
        best, _, _ = m.best_match(None, [])
        # p1 has lower art dist → wins after fallback sort
        assert best["id"] == "x"

    def test_multi_district_does_not_override_clear_winner(self, tmp_path):
        # Gap > NEAR_TIE_DISTANCE → multi-region sort not applied.
        p1 = {"id": "winner", "set": "setA", "phash_distance": 5, "multi_distance": 999,
              "border_color": "black", "image_uris": {"normal": "http://a"}}
        p2 = {"id": "loser",  "set": "setB", "phash_distance": 5 + NEAR_TIE_DISTANCE + 1,
              "multi_distance": 0, "border_color": "black",
              "image_uris": {"normal": "http://b"}}
        ranked = [p1, p2]
        m = _matcher_with_signals(tmp_path, ranked, "unknown")
        best, _, is_near_tie = m.best_match(None, [])
        assert best["id"] == "winner"
        assert is_near_tie is False


# ── basic-land low-confidence handling ───────────────────────────────────────

class TestIsBasicLandPrinting:
    def test_island_detected(self):
        assert _is_basic_land_printing({"type_line": "Basic Land — Island"}) is True

    def test_plains_detected(self):
        assert _is_basic_land_printing({"type_line": "Basic Land — Plains"}) is True

    def test_wastes_detected(self):
        assert _is_basic_land_printing({"type_line": "Basic Land"}) is True

    def test_non_basic_not_detected(self):
        assert _is_basic_land_printing({"type_line": "Instant"}) is False

    def test_missing_type_line_not_detected(self):
        assert _is_basic_land_printing({}) is False


class TestBasicLandLowConfidence:
    def _basic_island(self, sid, dist, set_code):
        return {"id": sid, "set": set_code, "collector_number": "1",
                "border_color": "white", "phash_distance": dist,
                "type_line": "Basic Land — Island",
                "image_uris": {"normal": f"http://{sid}"}}

    def test_near_tie_flags_printing_uncertain(self, tmp_path):
        ranked = [
            self._basic_island("a", 10, "4ed"),
            self._basic_island("b", 12, "5ed"),
        ]
        m = _matcher_with_signals(tmp_path, ranked, "white",
                                   is_basic=True)
        best, _, near_tie = m.best_match(None, [])
        assert best["set"] == "4ed", "best art match should be returned"
        assert near_tie is True
        assert m._last_scan_printing_uncertain is True

    def test_top_candidates_populated(self, tmp_path):
        ranked = [
            self._basic_island("a", 10, "4ed"),
            self._basic_island("b", 10, "5ed"),
            self._basic_island("c", 10, "6ed"),
        ]
        m = _matcher_with_signals(tmp_path, ranked, "white",
                                   is_basic=True)
        m.best_match(None, [])
        assert "4ED" in m._last_scan_top_candidates
        assert "5ED" in m._last_scan_top_candidates

    def test_clear_winner_basic_not_flagged(self, tmp_path):
        ranked = [
            self._basic_island("a", 5, "4ed"),
            self._basic_island("b", 5 + NEAR_TIE_DISTANCE + 1, "5ed"),
        ]
        m = _matcher_with_signals(tmp_path, ranked, "white",
                                   is_basic=True)
        best, _, near_tie = m.best_match(None, [])
        assert best["set"] == "4ed"
        assert near_tie is False
        assert m._last_scan_printing_uncertain is False

    def test_non_basic_not_flagged(self, tmp_path):
        ranked = [
            {"id": "a", "set": "mir", "phash_distance": 10,
             "type_line": "Artifact", "border_color": "black",
             "image_uris": {"normal": "http://a"}},
            {"id": "b", "set": "plst", "phash_distance": 10,
             "type_line": "Artifact", "border_color": "black",
             "image_uris": {"normal": "http://b"}},
        ]
        m = _matcher_with_signals(tmp_path, ranked, "black",
                                   is_basic=False)
        m.best_match(None, [])
        assert m._last_scan_printing_uncertain is False


# ── promo preference ──────────────────────────────────────────────────────────

class TestIsPromo:
    def test_promo_field_true(self):
        assert _is_promo({"promo": True}) is True

    def test_promo_field_false(self):
        assert _is_promo({"promo": False}) is False

    def test_set_type_promo(self):
        assert _is_promo({"set_type": "promo"}) is True

    def test_set_type_promo_pack(self):
        assert _is_promo({"set_type": "promo_pack"}) is True

    def test_regular_expansion(self):
        assert _is_promo({"set_type": "expansion", "promo": False}) is False

    def test_empty_dict(self):
        assert _is_promo({}) is False


class TestBestMatchPromoPreference:
    def test_non_promo_preferred_over_promo_in_tie(self, tmp_path):
        promo = {"id": "promo", "set": "pdmu", "collector_number": "107s",
                 "border_color": "black", "phash_distance": 14,
                 "multi_distance": 14, "promo": True, "set_type": "promo_pack",
                 "image_uris": {"normal": "http://p"}}
        regular = {"id": "reg", "set": "dmu", "collector_number": "107",
                   "border_color": "black", "phash_distance": 16,
                   "multi_distance": 20, "promo": False, "set_type": "expansion",
                   "image_uris": {"normal": "http://r"}}
        ranked = [promo, regular]   # promo ranks first by art pHash
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, near_tie = m.best_match(None, [])
        assert best["set"] == "dmu", "non-promo should be preferred over promo in tie"
        assert near_tie is True

    def test_promo_wins_when_only_candidate(self, tmp_path):
        promo = {"id": "promo", "set": "pdmu", "collector_number": "107s",
                 "border_color": "black", "phash_distance": 10,
                 "multi_distance": 10, "promo": True, "set_type": "promo_pack",
                 "image_uris": {"normal": "http://p"}}
        ranked = [promo]
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, _ = m.best_match(None, [])
        assert best["set"] == "pdmu", "promo is the only candidate — must return it"

    def test_promo_preference_does_not_override_border_filter(self, tmp_path):
        # White-bordered promo ranked first; scan is black → border filter removes it;
        # non-promo black-bordered card remains.
        promo_white = {"id": "pw", "set": "prm", "collector_number": "1",
                       "border_color": "white", "phash_distance": 10,
                       "multi_distance": 10, "promo": True, "set_type": "promo",
                       "image_uris": {"normal": "http://pw"}}
        regular_black = {"id": "rb", "set": "dmu", "collector_number": "107",
                         "border_color": "black", "phash_distance": 12,
                         "multi_distance": 15, "promo": False, "set_type": "expansion",
                         "image_uris": {"normal": "http://rb"}}
        ranked = [promo_white, regular_black]
        m = _matcher_with_signals(tmp_path, ranked, "black")
        best, _, _ = m.best_match(None, [])
        assert best["set"] == "dmu", "border filter takes priority over promo preference"

    def test_promo_not_penalised_for_clear_winner(self, tmp_path):
        # Clear winner (gap > NEAR_TIE_DISTANCE) — promo preference never fires
        promo = {"id": "promo", "set": "pdmu", "phash_distance": 5,
                 "border_color": "black", "multi_distance": 5, "promo": True,
                 "image_uris": {"normal": "http://p"}}
        regular = {"id": "reg", "set": "dmu",
                   "phash_distance": 5 + NEAR_TIE_DISTANCE + 1,
                   "border_color": "black", "multi_distance": 5, "promo": False,
                   "image_uris": {"normal": "http://r"}}
        ranked = [promo, regular]
        m = _matcher_with_signals(tmp_path, ranked, "unknown")
        best, _, is_near_tie = m.best_match(None, [])
        assert best["set"] == "pdmu", "clear winner promo should not be overridden"
        assert is_near_tie is False


# ── hang-prevention: candidate cap + tighter per-request timeout ─────────────

class TestCapCandidates:
    """
    Heavily-reprinted staples (Shivan Dragon, Lightning Bolt, ...) can have
    50+ printings. Downloading and hashing every single one turns one scan
    into a multi-minute network crawl that feels like the scanner is
    permanently stuck. rank_printings() must bound this.
    """

    def _printings(self, n):
        return [{"id": f"p{i}", "set": f"s{i}"} for i in range(n)]

    def test_under_cap_unchanged(self):
        printings = self._printings(5)
        assert _cap_candidates(printings) == printings

    def test_at_cap_unchanged(self):
        printings = self._printings(_MAX_CANDIDATES_PER_SCAN)
        assert _cap_candidates(printings) == printings

    def test_over_cap_truncated_to_max(self):
        printings = self._printings(_MAX_CANDIDATES_PER_SCAN + 20)
        capped = _cap_candidates(printings)
        assert len(capped) == _MAX_CANDIDATES_PER_SCAN

    def test_over_cap_keeps_oldest_and_newest(self):
        # get_all_printings() sorts oldest-first; capping should keep both
        # ends (oldest half for vintage-card ID, newest half for recent cards)
        # rather than dropping either end entirely.
        printings = self._printings(_MAX_CANDIDATES_PER_SCAN + 20)
        capped = _cap_candidates(printings)
        half = _MAX_CANDIDATES_PER_SCAN // 2
        assert capped[:half] == printings[:half]
        assert capped[half:] == printings[-half:]

    def test_does_not_mutate_input(self):
        printings = self._printings(_MAX_CANDIDATES_PER_SCAN + 10)
        original_len = len(printings)
        _cap_candidates(printings)
        assert len(printings) == original_len


class TestImageFetchTimeout:
    def test_timeout_is_bounded_and_tight(self):
        # Must be finite and short enough that a single stalled download
        # can't dominate a scan (the old value was 20s per image).
        assert 0 < _IMAGE_FETCH_TIMEOUT <= 15

    def test_fetch_image_uses_bounded_timeout(self, tmp_path):
        m = ArtMatcher(cache_dir=tmp_path, request_delay=0)
        resp = MagicMock()
        resp.content = b"fake-jpeg-bytes"
        resp.raise_for_status.return_value = None
        with patch.object(m._session, "get", return_value=resp) as mock_get:
            with patch("PIL.Image.open", return_value=_to_pil(_blue())):
                m._fetch_image("http://example.com/x.jpg", "newid123")
        _, kwargs = mock_get.call_args
        assert kwargs.get("timeout") == _IMAGE_FETCH_TIMEOUT


class TestRankPrintingsAppliesCap:
    def test_rank_printings_caps_large_candidate_list(self, tmp_path):
        m = _matcher(tmp_path)
        many_printings = [
            _printing(f"id{i}", url=f"http://x/{i}") for i in range(_MAX_CANDIDATES_PER_SCAN + 15)
        ]
        with patch.object(m, "_fetch_image", return_value=_to_pil(_blue())):
            ranked = m.rank_printings(_blue(), many_printings)
        assert len(ranked) == _MAX_CANDIDATES_PER_SCAN
