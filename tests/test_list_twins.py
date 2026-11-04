"""The List's twins (list_twins.py) against real Scryfall shapes (2026-11-04)."""

from mtg_card_scanner.list_twins import (
    LIST_SETS,
    has_twin_among,
    is_list_printing,
    is_list_twin,
    twins,
    with_twins,
)


def _p(s, n, sid=None):
    return {"id": sid or f"{s}:{n}", "set": s, "collector_number": n}


def test_the_list_is_plst_only():
    # Scryfall folded mb1/fmb1 into plst (/sets/mb1 answers plst).
    assert LIST_SETS == frozenset({"plst"})
    assert is_list_printing(_p("plst", "A25-141"))
    assert is_list_printing(_p("PLST", "A25-141"))
    assert not is_list_printing(_p("a25", "141"))
    assert not is_list_printing(_p("mb1", "1"))
    assert not is_list_printing({"id": "x"})


def test_a_list_card_twins_its_original_printing():
    # plst A25-141 Lightning Bolt prints "141/249 U" / "A25 • EN".
    assert is_list_twin(_p("plst", "A25-141"), _p("a25", "141"))
    assert is_list_twin(_p("plst", "PF19-1"), _p("pf19", "1"))
    assert is_list_twin(_p("plst", "2XM-3"), _p("2xm", "3"))          # Ancestral Blade
    assert is_list_twin(_p("plst", "POR-57s"), _p("por", "57s"))      # Horned Turtle
    assert is_list_twin(_p("plst", "TD0-A80"), _p("td0", "A80"))      # Moment's Peace
    assert is_list_twin(_p("plst", "ECL-1"), _p("ecl", "1"))          # Changeling Wayfinder


def test_the_head_is_the_whole_set_code():
    # plst TAFR-1 (an Angel token) reproduces tafr 1, not afr 1.
    assert is_list_twin(_p("plst", "TAFR-1"), _p("tafr", "1"))
    assert not is_list_twin(_p("plst", "TAFR-1"), _p("afr", "1"))
    assert not is_list_twin(_p("plst", "A25-141"), _p("a2", "5-141"))


def test_dagger_and_j_suffixes():
    # JUD-78† ↔ jud 78 (Arcane Teachings); M19-185j ↔ m19 185 (Gigantosaurus).
    assert is_list_twin(_p("plst", "JUD-78†"), _p("jud", "78"))
    assert is_list_twin(_p("plst", "M19-185j"), _p("m19", "185"))
    # only appended to the original's number, never stripped from it
    assert not is_list_twin(_p("plst", "JUD-78"), _p("jud", "78†"))
    assert not is_list_twin(_p("plst", "M19-185x"), _p("m19", "185"))


def test_no_twin_when_the_numbers_differ():
    # CHK-160 Brothers Yamazaki: the originals are chk 160a / 160b — no twin.
    assert not is_list_twin(_p("plst", "CHK-160"), _p("chk", "160a"))
    assert not is_list_twin(_p("plst", "CHK-160"), _p("chk", "160b"))
    assert not is_list_twin(_p("plst", "A25-141"), _p("a25", "142"))
    assert not is_list_twin(_p("plst", "A25-141"), _p("a25", "14"))
    assert not is_list_twin(_p("plst", "A25-141"), _p("m10", "141"))


def test_exact_strings_never_ocr_confusions():
    # The OCR canon folds I/1, O/0, S/5 — a twin never does.
    assert not is_list_twin(_p("plst", "A25-141"), _p("a2s", "141"))
    assert not is_list_twin(_p("plst", "M10-146"), _p("mi0", "146"))
    assert not is_list_twin(_p("plst", "TD0-A80"), _p("tdo", "A80"))
    assert not is_list_twin(_p("plst", "TD0-A80"), _p("td0", "a80"))


def test_set_codes_compare_case_insensitively():
    assert is_list_twin(_p("plst", "a25-141"), _p("A25", "141"))
    assert is_list_twin(_p("PLST", "A25-141"), _p("a25", "141"))


def test_direction_and_symmetry():
    t, x = _p("plst", "A25-141"), _p("a25", "141")
    assert not is_list_twin(x, t)
    assert twins(t, x) and twins(x, t)
    # two List printings, or two originals, are never twins
    assert not twins(t, _p("plst", "A25-141", "other"))
    assert not twins(x, _p("a25", "141", "other"))
    # degenerate shapes
    assert not is_list_twin(_p("plst", "A25141"), _p("a25", "141"))
    assert not is_list_twin(_p("plst", "-141"), _p("", "141"))
    assert not is_list_twin(_p("plst", "A25-"), _p("a25", ""))
    assert not is_list_twin({"set": "plst"}, {})
    assert not twins({}, {})


def test_has_twin_among():
    cands = [_p("a25", "141"), _p("m10", "146"), _p("plst", "A25-141")]
    assert has_twin_among(cands[0], cands)
    assert has_twin_among(cands[2], cands)
    assert not has_twin_among(cands[1], cands)


def test_with_twins_inserts_a_partner_ranked_below_the_slice():
    r = [_p("m10", "146"), _p("a25", "141"), _p("2x2", "117"), _p("mm2", "122"),
         _p("plst", "A25-141"), _p("plst", "M10-146"), _p("e01", "54")]
    got = [p["id"] for p in with_twins(r, 3)]
    assert got == ["m10:146", "plst:M10-146", "a25:141", "plst:A25-141", "2x2:117"]
    # a partner already inside the slice stays where it is
    got = [p["id"] for p in with_twins(r, 5)]
    assert got == ["m10:146", "plst:M10-146", "a25:141", "2x2:117", "mm2:122",
                   "plst:A25-141"]
    # nothing to add: the slice exactly
    assert with_twins(r, 7) == r
    assert with_twins(r, 99) == r
    assert with_twins([], 12) == []
    assert with_twins(r, 0) == []


def test_with_twins_list_card_in_the_slice_pulls_its_original():
    r = [_p("plst", "CLB-187"), _p("sta", "105"), _p("clb", "187")]
    assert [p["id"] for p in with_twins(r, 1)] == ["plst:CLB-187", "clb:187"]


def test_with_twins_inserts_each_partner_once_in_ranked_order():
    # m19 185 has two List twins at Scryfall (M19-185 and M19-185j).
    r = [_p("m19", "185"), _p("plst", "M19-185j"), _p("x", "1"),
         _p("plst", "M19-185"), _p("m19", "186")]
    assert [p["id"] for p in with_twins(r, 1)] == [
        "m19:185", "plst:M19-185j", "plst:M19-185"]
    # the partner of two slice members is inserted after the FIRST only
    r = [_p("plst", "M19-185j"), _p("plst", "M19-185"), _p("y", "2"), _p("m19", "185")]
    assert [p["id"] for p in with_twins(r, 2)] == [
        "plst:M19-185j", "m19:185", "plst:M19-185"]


def test_with_twins_keeps_the_same_objects():
    r = [_p("a25", "141"), _p("x", "1"), _p("plst", "A25-141")]
    out = with_twins(r, 1)
    assert out[0] is r[0] and out[1] is r[2]
