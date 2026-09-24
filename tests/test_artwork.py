"""Artwork split: which candidates are a DIFFERENT artwork past a clean break."""

from mtg_card_scanner.artwork import flag_other_art, other_art_ids


def _cands(*dists):
    return [{"id": f"p{i}", "multi_distance": d} for i, d in enumerate(dists)]


def test_fling_splits_at_the_artwork_break():
    # Measured on a live Fling scan: 4 same-art prints, then other artworks.
    c = _cands(78, 80, 80, 84, 146, 146, 150, 154, 154, 160, 160, 168)
    assert other_art_ids(c) == {f"p{i}" for i in range(4, 12)}


def test_wide_same_art_spread_splits_nothing():
    # Bone Splinters: same art Δ124–190, alt art Δ208 — no clean jump.
    assert other_art_ids(_cands(124, 150, 170, 190, 208)) == set()


def test_poor_best_match_splits_nothing():
    assert other_art_ids(_cands(150, 155, 230)) == set()


def test_missing_distance_splits_nothing():
    c = _cands(78, 80, 160)
    c[1]["multi_distance"] = None
    assert other_art_ids(c) == set()


def test_single_candidate_splits_nothing():
    assert other_art_ids(_cands(78)) == set()


def test_ocr_confirmed_is_never_split_off():
    c = _cands(78, 80, 160)
    c[2]["ocr_confirmed"] = True
    assert other_art_ids(c) == set()


def test_order_does_not_matter():
    # An OCR promotion moves a print to the front, out of distance order.
    assert other_art_ids(_cands(84, 78, 160, 80)) == {"p2"}


def test_flag_other_art_sets_every_candidate():
    scan = {"candidates": _cands(78, 80, 160)}
    flag_other_art(scan)
    assert [c["other_art"] for c in scan["candidates"]] == [False, False, True]
    assert flag_other_art({"candidates": None}) == {"candidates": None}
