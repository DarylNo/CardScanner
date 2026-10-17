"""The review pages carry ONE "crop to the card's edge" block between them
(CLAUDE.md → "Just the card, on BOTH pages"): phone.html and desktop.html must
hold it byte for byte, and its layout table must say where the reference
pipeline really puts the card in each photo size. (The block's accuracy is
measured in a browser by scripts/score_card_edges.py.)"""
import json
import re
from pathlib import Path

from mtg_card_scanner import card_detect as cd
from tests import phone_flatten_ref as pf

STATIC = Path(__file__).resolve().parents[1] / "server" / "static"
BEGIN, END = "/* ═══ card edges ═══", "/* ═══ end card edges ═══ */"


def _block(name: str) -> str:
    text = (STATIC / name).read_text(encoding="utf-8")
    assert text.count(BEGIN) == 1 and text.count(END) == 1, name
    return text[text.index(BEGIN):text.index(END)]


def test_both_pages_carry_the_same_card_edges_block():
    assert _block("phone.html") == _block("desktop.html")


def test_the_layout_table_is_where_the_reference_puts_the_card():
    m = re.search(r"const PHOTO_LAYOUT = (\{.*?\});", _block("phone.html"), re.S)
    table = json.loads(re.sub(r"\s+", " ", m.group(1)))
    expected = {f"{cd.PHOTO_W}x{cd.PHOTO_H}": [cd.PHOTO_MARGIN_X, cd.PHOTO_MARGIN_Y,
                                               cd.PHOTO_MARGIN_X + cd.CARD_W, cd.PHOTO_MARGIN_Y + cd.CARD_H]}
    for margin in pf.MARGINS:                     # a flattened upload filed as it came
        mx, my, w, h = pf._layout(margin)
        expected[f"{w}x{h}"] = [mx, my, mx + pf.CARD_W, my + pf.CARD_H]
    assert table == expected


def test_both_pages_crop_through_the_block():
    for name in ("phone.html", "desktop.html"):
        page = (STATIC / name).read_text(encoding="utf-8")
        rest = page.replace(_block(name), "")
        assert "cardCrop(img)" in rest and "cardCropTransform(" in rest, name
        assert "PHOTO_FILL" not in page, name          # the fixed zoom it replaced


def test_a_wanted_card_is_never_filtered_away_on_either_page():
    """CLAUDE.md → "List filters": a card the external list tags "wanted" always
    shows — passesFilters lets it through before either bar votes, so neither
    bar (in ANY or ALL) nor "Exclude filtered" can drop it. Driven for real in
    Chromium by scripts/check_card_list_ui.py; this keeps CI honest about it."""
    for name in ("phone.html", "desktop.html"):
        page = (STATIC / name).read_text(encoding="utf-8")
        assert re.search(r"const isWanted = scan => listTags\(scan\)\.includes\((['\"])wanted\1\);", page), name
        body = re.search(r"function passesFilters\((\w+)\)\s*\{(.*?)\n\}", page, re.S)
        assert body, name
        arg, lines = body.group(1), body.group(2)
        first_vote = lines.index("activeFilters()")
        assert re.search(rf"isWanted\({arg}\)\)? return true;", lines[:first_vote]), name
