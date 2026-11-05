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


def test_the_desktop_row_delete_is_admin_only_and_waits_for_undo():
    """CLAUDE.md → "Roles" / desktop row delete (owner, 2026-11-05: "I want delete options
    here in the list"): each scan row's 🗑 is admin-only, never opens the row, and holds the
    DELETE for the same 5 s Undo as phone.html's swipe, sent on pagehide / a hidden tab with
    keepalive. Driven for real in Chromium by scripts/check_row_delete_ui.py."""
    page = (STATIC / "desktop.html").read_text(encoding="utf-8")
    assert re.search(r'role === "admin" \? `<button type="button" class="rdel admin-only" data-del="\$\{s\.id\}"', page)
    assert 'b.addEventListener("click", e => { e.stopPropagation(); queueDelete(Number(b.dataset.del), e.detail === 0); })' in page
    assert "const UNDO_MS = 5000;" in page and "const UNDO_MS = 5000;" in (STATIC / "phone.html").read_text(encoding="utf-8")
    assert 'fetch(`/api/scans/${id}`, { method: "DELETE", keepalive })' in page
    assert 'addEventListener("pagehide", () => commitDeletes(true));' in page
    assert "if (document.hidden) { commitDeletes(true); return; }" in page
    assert re.search(r"function visibleScans\(\)\{\n  const q = filterText\.toLowerCase\(\);\n  return liveScans\(\)\.filter", page)


def test_a_refused_delete_says_why_on_every_path():
    """Review of desktop d54 (d55 / phone v57): every delete path checks the answer — a 403
    (full access turned off while the page was open) says "no longer has full access" and
    re-asks /api/me, never "try again" (it cannot succeed); the Undo bar leaves room for the
    last row's 🗑; a keyboard delete moves focus to Undo; Export sends the waiting DELETEs
    first. Driven for real in Chromium by scripts/check_row_delete_ui.py."""
    desk = (STATIC / "desktop.html").read_text(encoding="utf-8")
    phone = (STATIC / "phone.html").read_text(encoding="utf-8")
    for name, page in (("desktop.html", desk), ("phone.html", phone)):
        assert re.search(r"function deleteFailMsg\(status, n\)", page), name
        assert "no longer has full access" in page, name
        assert re.search(r"function refreshRole\(\)", page), name
        assert re.search(r"\.then\(r => r\.ok \|\| r\.status === 404 \? 0 : r\.status, \(\) => -1\)\)", page), name
        assert "typeof r.deleted !== 'number'" in page or 'typeof r.deleted !== "number"' in page, name
        assert "Deleted ${r.deleted}" in page, name
        assert "deferredMsg" in page, name
    assert "#list.undoing { padding-bottom: 76px; }" in desk
    assert 'if (viaKeyboard) $("#undoBtn").focus();' in desk
    assert "await Promise.race([commitDeletes(false), new Promise(r => setTimeout(r, 3000))]);" in desk
    assert re.search(r"#flash \{ position:fixed;[^}]*z-index:30;", phone)
    # review of d55 / v57: a 401 / another status get their own words; a mouse Undo never focuses a 🗑;
    # phone.html's notice never covers (or takes the taps of) the Undo bar
    for name, page in (("desktop.html", desk), ("phone.html", phone)):
        assert "status === 401 ?" in page and "the phone refused it (HTTP ${status})" in page, name
        assert "deleteFailMsg(worstStatus(bad), bad.length)" in page, name
    assert "fromBar = e.detail === 0;" in desk
    assert "#flash { pointer-events:none; }" in phone
    assert "body.undoing #flash { bottom:calc(84px + env(safe-area-inset-bottom)); }" in phone
