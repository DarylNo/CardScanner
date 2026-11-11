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
    assert ('b.addEventListener("click", e => { e.stopPropagation(); if (e.detail > 1) return; '
            'queueDelete(Number(b.dataset.del), e.detail === 0); })') in page
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


def test_every_admin_control_says_why_and_the_role_follows_the_switch():
    """Final review of desktop d56 / phone v58 (d57 / v59): a 🗑 double-click deletes ONE row; the
    Undo bar stays left of the 🗑 column and lifts the toast; a keyboard delete left to run hands
    focus to the row that took its place; every admin-only call (ScanServer.adminOnly) goes through
    adminFetch, so a refusal says why instead of reading as an answer; both pages re-read /api/me
    every 30 s and when shown again; phone.html keeps a sent DELETE out of Undo (sentDel) and out
    of a refresh still in flight (settleDeletes), and Clear counts the list as shown. Driven for
    real in Chromium by scripts/check_row_delete_ui.py."""
    desk = (STATIC / "desktop.html").read_text(encoding="utf-8")
    phone = (STATIC / "phone.html").read_text(encoding="utf-8")
    for name, page in (("desktop.html", desk), ("phone.html", phone)):
        assert re.search(r"function failMsg\(what, status, detail\)", page), name
        assert re.search(r"async function adminFetch\(url, opts, what, say = ", page), name
        assert "if (status === 403) refreshRole();" in page, name
        # an /api answer in HTML is the gateway's join page: a lost session, never the file (d58 / v60)
        assert re.search(r"!!res && res\.ok && /\^\\/api\\//\.test\(url\) && /text\\/html/i\.test\(", page), name
        assert "const status = lost ? 401 : res ? res.status : 0;" in page, name
        assert re.search(r"const ROLE_REFRESH_MS = 30000;", page), name
        assert re.search(r"setInterval\(\(\) => \{ if \(!document\.hidden\) refreshRole\(\); \}, "
                         r"window\.__roleRefreshMs \|\| ROLE_REFRESH_MS\);", page), name
        assert "if (role === was) return;" in page, name          # a re-render only on a change
        vis = re.search(r"addEventListener\(['\"]visibilitychange['\"], \(\) => \{\n  if \(document\.hidden\) "
                        r"\{ commitDeletes\(true\); return; \}\n  refreshRole\(\);", page)
        assert vis, name
        # no admin route through the old helpers (they read a refusal as an answer) or by navigating to it
        assert not re.search(r"jsend\(\s*[`'\"]/api/(price-sweep/stop|scans/price-missing|export|device)", page), name
        assert not re.search(r"jget\(\s*[`'\"]/api/(export|device|debug)", page), name
        assert not re.search(r"jsend\([^)]*PATCH['\"], \{ included", page), name
        assert not re.search(r"window\.location = ['\"]/api/(export|debug)", page), name
        clear = re.search(r"async function clearScans\(only\) ?\{(.*?)\n\}", page, re.S)
        assert clear and "await Promise.race([commitDeletes(false), new Promise(r => setTimeout(r, 3000))]);" in clear.group(1), name
    assert "#undoBar { position: fixed; left: 16px; bottom: 16px; width: 316px;" in desk
    assert "body.undoing .toast { bottom: 76px; }" in desk
    assert 'document.body.classList.toggle("undoing", on);' in desk
    assert "kbNext = i < 0 ? [] : [...vis.slice(i + 1), ...vis.slice(0, i).reverse()];" in desk
    assert 'const onUndo = document.activeElement === $("#undoBtn");' in desk
    assert "?.focus({ preventScroll: true });" in desk
    assert "adminDownload(\"/api/export\", \"cards.txt\"" in desk and "adminDownload(\"/api/export.csv\", \"cards.csv\"" in desk
    # phone.html: desktop's delete model
    assert "const sentDel = new Map();" in phone
    assert "return pendingDel.size || sentDel.size ? scans.filter(s => !pendingDel.has(s.id) && !sentDel.has(s.id)) : scans;" in phone
    assert "const owed = [...pendingDel, ...[...sentDel].filter(([, at]) => at === Infinity).map(([id]) => id)];" in phone
    assert "if (mine < pollApplied) return;" in phone and "settleDeletes(mine);" in phone
    assert re.search(r"async function clearScans\(only\) \{\n(\s*//[^\n]*\n)*\s*const live = liveScans\(\);", phone)


def test_every_raw_admin_get_reads_the_join_page_as_a_lost_session():
    """Review of desktop d58: the 📱 panel's GETs and the 🐞 App log poll fetch /api routes
    directly; with no session the gateway answers them with its join page (200 text/html), which
    read as "settings aren't available" and as a JSON error every 1.5 s. d59 asks lostSession()."""
    desk = (STATIC / "desktop.html").read_text(encoding="utf-8")
    assert "function lostSession(url, res){" in desk
    assert "const lost = lostSession(url, res);" in desk                       # adminFetch
    assert 'if (lostSession("/api/debug/log", r)) {' in desk                      # the 🐞 App log poll
    assert 'if (!r.ok || lostSession("/api/device", r)) return null;' in desk     # devLoad
    assert 'if (lostSession("/api/device/snapshot.jpg", r))' in desk              # Camera view
    assert 'const status = lostSession("/api/device", res) ? 401 : res ? res.status : 0;' in desk   # openDev


PLAY_BEGIN, PLAY_END = "/* ═══ play data (shared) ═══", "/* ═══ end play data ═══ */"


def _play_block(name: str) -> str:
    text = (STATIC / name).read_text(encoding="utf-8")
    assert text.count(PLAY_BEGIN) == 1 and text.count(PLAY_END) == 1, name
    return text[text.index(PLAY_BEGIN):text.index(PLAY_END)]


def _fn(page: str, name: str) -> str:
    m = re.search(rf"\nfunction {name}\(.*?\n\}}", page, re.S)
    assert m, name
    return m.group(0)


def test_the_play_data_helpers_are_one_block_on_both_pages():
    """CLAUDE.md → "Play data" / "List filters" (1.1.20, desktop d60 / phone v61): the share
    formatting, the best format, the "Played in" floor's vote and the pill's percentile are ONE
    block, byte for byte in both pages — a fix to one page alone fails here. Driven for real in
    Chromium by scripts/check_price_filter_ui.py."""
    assert _play_block("phone.html") == _play_block("desktop.html")
    desk = (STATIC / "desktop.html").read_text(encoding="utf-8")
    phone = (STATIC / "phone.html").read_text(encoding="utf-8")
    for fn in ("fmtTop", "fmtShare", "bestPlay", "playPasses", "playVote"):
        assert _fn(desk, fn) == _fn(phone, fn), fn
        assert desk.count(f"\nfunction {fn}(") == 1 and phone.count(f"\nfunction {fn}(") == 1, fn
    vote = _fn(desk, "playVote")
    assert "if (P == null) return null;" in vote                 # absent / null = not known
    assert "if (m == null) return null;" in vote                 # a format not in this week's file
    assert "if (ms.some(m => m == null)) return null;" in vote and vote.rstrip().endswith("return false;\n}")
    assert 'const PLAY_ORDER = ["staple", "played", "fringe", "unplayed", "basic", "banned", "not_legal"];' in desk


def test_the_play_chip_sits_after_the_list_tags_and_the_floor_votes():
    """The row's play chip comes AFTER the external list's chips (check_card_list_ui.py asserts
    the first list chip follows the popularity pill); passesFilters pushes the Played-in vote
    and keeps the "check" / Wanted exemptions before any bar votes; the pill's percentile is
    printed only for a NUMBER (unranked cards and old scans: no "top undefined%")."""
    desk = (STATIC / "desktop.html").read_text(encoding="utf-8")
    phone = (STATIC / "phone.html").read_text(encoding="utf-8")
    assert "${popPill(s)}${tagChips(s)}${playChip(s)}" in desk
    assert "popPill(s) + tagChips(s) + playChip(s) +" in phone
    for name, page in (("desktop.html", desk), ("phone.html", phone)):
        body = re.search(r"function passesFilters\((\w+)\)\s*\{(.*?)\n\}", page, re.S)
        assert body, name
        arg, lines = body.group(1), body.group(2)
        first_vote = lines.index("activeFilters()")
        assert re.search(rf"isProblem\({arg}\)", lines[:first_vote]), name
        assert re.search(rf"isWanted\({arg}\)\)? return true;", lines[:first_vote]), name
        assert "if (!on.price && !on.pop && !on.play) return true;" in lines, name
        assert f"if (on.play) votes.push(playVote({arg}));" in lines, name
        assert "play: playMin != null" in _fn(page, "activeFilters"), name
        pill = _fn(page, "popPill")
        assert 'typeof p.top_percent === "number" ? ` · top ${fmtTop(p.top_percent)}%` : ""' in pill, name
        # every field the row renders is in its signature
        assert "playSig(s)" in page, name
        # Premodern's legality chip (popularity.TRACKED_FORMATS from 1.1.20), between Pauper and Commander
        assert re.search(r"pauper:\s*(['\"])Pauper\1,\s*premodern:\s*\1Premodern\1,\s*commander:", page), name
        # the floor persists like the popularity bar
        assert "play: playMin, playfmt: playFmt," in page, name
    assert "playSig(scan)" in _fn(desk, "sigOf")
    assert "'~' + playMin + '~' + playFmt + '~'" in phone
    assert "Vintage, Pauper, Premodern or Commander" in desk
