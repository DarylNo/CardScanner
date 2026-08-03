"""Tests for the collector-line OCR matcher (pure logic — no OCR engine)."""

from mtg_card_scanner.ocr_id import _canon, match_printing


def _c(sid, set_code, num):
    return {"id": sid, "set": set_code, "collector_number": num}


CANDS = [
    _c("a25", "a25", "85"),
    _c("plst", "plst", "A25-85"),
    _c("mh1", "mh1", "87"),
    _c("j22", "j22", "67"),
    _c("tmp", "tmp", "128"),
    _c("pmei-a", "pmei", "2019-2"),
    _c("pmei-b", "pmei", "2024-5"),
]


def test_canon_collapses_ocr_confusions():
    assert _canon("mhI+ Fn") == _canon("MH1 FN")
    assert _canon("2S4") == _canon("254")


def test_real_scan_blob_matches_mh1():
    # exact blob read from the live Diabolic Edict scan (id 837)
    assert match_printing("0017314MH1FN", CANDS) == "mh1"


def test_no_line_no_match():
    assert match_printing("", CANDS) is None
    assert match_printing("XYZQQQ", CANDS) is None


def test_ambiguous_set_hits_never_guess():
    # both PMEI promos share a set code; without a collector hit → None
    assert match_printing("PMEI", CANDS) is None


def test_ambiguous_resolved_by_collector():
    assert match_printing("PMEI 20192", CANDS) == "pmei-a"


def test_list_copy_wins_over_original_set():
    # a List card prints "A25-85": compound collector beats the bare set hit
    assert match_printing("A25-85 PW", CANDS) == "plst"


def test_art_double_check_blocks_disagreeing_promotions():
    """A misread set code naming an alt-art printing must be ignored: the
    OCR'd candidate's art distance has to sit within the same-art band."""
    from mtg_card_scanner.pipeline import _art_agrees
    cands = [{"id": "a", "multi_distance": 124},
             {"id": "b", "multi_distance": 156},
             {"id": "c", "multi_distance": 208}]
    assert _art_agrees(cands[0], cands) is True    # the best itself
    assert _art_agrees(cands[1], cands) is True    # same-art band (+32)
    assert _art_agrees(cands[2], cands) is False   # alt-art (+84) — blocked
    assert _art_agrees({"id": "d"}, cands) is True # no art data — don't block
