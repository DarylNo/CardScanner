"""Drive the REAL desktop.html / phone.html in headless Chromium against a stub
server and check the external card list's chips and "Tagged only" view
(CLAUDE.md → "External card list"):

  * feature off (no list_tags key on any scan): no chips, no "Tagged only";
  * on: Wanted / Sold out chips and a plain chip for an unknown tag, beside the
    popularity chip, in the list rows and the open card;
  * a refreshed list (new list_tags on the next poll) re-tags the rows;
  * "Tagged only" keeps tagged rows + "check" rows, survives a reload, and is
    NOT a filter vote: "Exclude filtered" still sees the untagged cards as passing.

    pip install playwright        # Chromium: /opt/pw-browsers in a cloud session, or set CHROME
    python scripts/check_card_list_ui.py
Not part of CI (it needs a browser); run it after any edit to the chips or the filter.
"""
import copy, glob, json, os, re, sys, threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path

from playwright.sync_api import sync_playwright

STATIC = Path(__file__).resolve().parents[1] / "server" / "static"
POP = {"tier": "staple", "label": "Staple", "rank": 158, "percentile": 0.5}


def scan(i, name, tags=None, sel=False, problem=False):
    c = dict(id=f"c{i}", name=name, set="m10", set_name="M10", collector_number=str(i), rarity="common",
             finishes=["nonfoil"], multi_distance=70, image_small="/x.png", image_normal="/x.png", popularity=POP)
    s = dict(id=i, status="selected" if sel else "pending", identified=not problem, card_read={"name": name},
             confidence="high", candidates=[] if problem else [c], included=True, flagged=False,
             selection=dict(c, scryfall_id=c["id"], condition="NM", finish="Non-Foil", quantity=1,
                            f2f=None) if sel else None,
             error="not identified" if problem else None, f2f={"conditions": {"NM": 2.5}} if sel else None,
             created_at="2026-10-12T00:00:00")
    if tags is not None:
        s["list_tags"] = tags
    return s


def tagged_scans():
    return [scan(5, "Lightning Bolt", ["wanted"], sel=True), scan(4, "Opt", []),
            scan(3, "Jötun Grunt", ["wanted", "sold_out", "vip"]), scan(2, "Mystery", [], problem=True),
            scan(1, "Forest", [], sel=True)]


STATE = {"scans": [scan(5, "Lightning Bolt", sel=True), scan(4, "Opt")]}   # off: no list_tags


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body, ctype="application/json"):
        if not isinstance(body, bytes): body = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store"); self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/": return self.send(200, (STATIC / "desktop.html").read_bytes(), "text/html")
        if p == "/phone": return self.send(200, (STATIC / "phone.html").read_bytes(), "text/html")
        if p == "/api/me": return self.send(200, {"role": "admin"})
        if p == "/api/version": return self.send(200, {"version": "stub"})
        if p == "/api/price-status": return self.send(200, {"active": False, "done": 0, "total": 0, "cooldown_s": 0})
        if p == "/api/health": return self.send(200, {"ocr": {"available": True}})
        if p == "/api/update-check": return self.send(200, {"update_available": False})
        if p == "/api/setup/status": return self.send(200, {"index_built": True})
        if p == "/api/scans": return self.send(200, STATE["scans"])
        m = re.fullmatch(r"/api/scans/(\d+)", p)
        if m:
            s = next((x for x in STATE["scans"] if x["id"] == int(m.group(1))), None)
            return self.send(200 if s else 404, s or {"error": "not found"})
        return self.send(404, {"error": "stub"})

    def do_POST(self): self.send(200, {})
    def do_PATCH(self): self.send(200, {})


srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{srv.server_port}"
EXE = os.environ.get("CHROME") or next(iter(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome")), None)

fails = []
def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond: fails.append(msg)


def chips(pg, row_sel):
    return pg.evaluate(f"""() => [...document.querySelectorAll({row_sel!r})].map(r =>
        [...r.querySelectorAll('.ltag')].map(e => e.className.replace(/.*ltag ?/, '') + ':' + e.textContent))""")


with sync_playwright() as pw:
    b = pw.chromium.launch(executable_path=EXE) if EXE else pw.chromium.launch()

    # ── desktop ──────────────────────────────────────────────────────────
    print("desktop.html")
    STATE["scans"] = [scan(5, "Lightning Bolt", sel=True), scan(4, "Opt")]
    ctx = b.new_context(viewport={"width": 1500, "height": 950})
    pg = ctx.new_page(); errs = []
    pg.on("pageerror", lambda e: errs.append(str(e)))
    pg.goto(BASE + "/"); pg.wait_for_selector(".row[data-id='5']")
    check(pg.evaluate("UI_VERSION") == "d46", "banner d46")
    check(pg.locator(".ltag").count() == 0, "feature off: no list chips")
    check(not pg.locator("#tagged").is_visible(), "feature off: no Tagged only")
    STATE["scans"] = tagged_scans()
    pg.wait_for_selector(".row[data-id='3']", timeout=8000); pg.wait_for_timeout(300)
    rows = dict(zip([5, 4, 3, 2, 1], chips(pg, "#list .row")))
    check(rows[5] == ["wanted:Wanted"], f"Wanted chip ({rows[5]})")
    check(rows[3] == ["wanted:Wanted", "sold_out:Sold out", "other:vip"], f"known chips first, unknown raw ({rows[3]})")
    check(rows[4] == [] and rows[1] == [], "untagged rows: no chips")
    near = pg.evaluate("""() => { const t = document.querySelector('.row[data-id="3"] .ltag');
        return t.previousElementSibling && t.previousElementSibling.classList.contains('pop'); }""")
    check(near, "the chips sit right after the popularity pill")
    check(pg.locator("#tagged").is_visible(), "feature on: Tagged only shown")
    pg.locator("#tagged").click(); pg.wait_for_timeout(200)
    ids = pg.evaluate("[...document.querySelectorAll('#list .row')].map(r => +r.dataset.id)")
    check(ids == [5, 3, 2], f"Tagged only: tagged rows + the check row ({ids})")
    check(pg.evaluate("passesFilters(scans.find(s => s.id === 4))"), "Tagged only is not a filter vote (export unaffected)")
    pg.reload(); pg.wait_for_selector(".row[data-id='3']"); pg.wait_for_timeout(300)
    ids = pg.evaluate("[...document.querySelectorAll('#list .row')].map(r => +r.dataset.id)")
    check(ids == [5, 3, 2] and "on" in (pg.locator("#tagged").get_attribute("class") or ""), f"Tagged only survives a reload ({ids})")
    # a refreshed list re-tags existing scans on the next poll
    new = tagged_scans(); new[1]["list_tags"] = ["sold_out"]; new[2]["list_tags"] = []
    STATE["scans"] = new
    pg.wait_for_function("[...document.querySelectorAll('#list .row')].map(r => +r.dataset.id).join() === '5,4,2'", timeout=8000)
    rows = dict(zip([5, 4, 2], chips(pg, "#list .row")))
    check(rows[4] == ["sold_out:Sold out"], f"a refreshed list re-tags the rows ({rows[4]})")
    pg.locator("#tagged").click(); pg.wait_for_timeout(200)
    pg.locator(".row[data-id='4']").click(); pg.wait_for_timeout(500)
    check("Sold out" in pg.locator("#right").inner_text(), "the open card shows its chip")
    check(not errs, f"no page errors ({errs[:3]})")
    ctx.close()

    # ── phone ────────────────────────────────────────────────────────────
    print("phone.html")
    STATE["scans"] = [scan(5, "Lightning Bolt", sel=True), scan(4, "Opt")]
    ctx = b.new_context(viewport={"width": 411, "height": 914}, device_scale_factor=2, is_mobile=True, has_touch=True)
    pg = ctx.new_page(); errs = []
    pg.on("pageerror", lambda e: errs.append(str(e)))
    pg.goto(BASE + "/phone?panel=1"); pg.wait_for_selector(".srow[data-sid='5']")
    check(pg.evaluate("UI_VERSION") == "v53", "banner v53")
    check(pg.locator(".ltag").count() == 0, "feature off: no list chips")
    pg.locator("#fltBtn").click()
    check(not pg.locator("#p-tagged").is_visible(), "feature off: no Tagged only")
    STATE["scans"] = tagged_scans()
    pg.wait_for_selector(".srow[data-sid='3']", timeout=8000); pg.wait_for_timeout(300)
    rows = dict(zip([5, 4, 3, 2, 1], chips(pg, ".srow")))
    check(rows[3] == ["wanted:Wanted", "sold_out:Sold out", "other:vip"], f"chips ({rows[3]})")
    near = pg.evaluate("""() => { const t = document.querySelector('.srow[data-sid="3"] .ltag');
        return t.previousElementSibling && t.previousElementSibling.classList.contains('pop'); }""")
    check(near, "the chips sit right after the popularity pill")
    check(pg.locator("#p-tagged").is_visible(), "feature on: Tagged only shown")
    pg.locator("#p-tagged").click(); pg.wait_for_timeout(200)
    ids = pg.evaluate("[...document.querySelectorAll('.srow')].map(r => +r.dataset.sid)")
    check(ids == [5, 3, 2], f"Tagged only: tagged rows + the check row ({ids})")
    check(pg.locator("#fcount").inner_text().strip() == "1", "the Filters chip counts it")
    check(pg.evaluate("passesFilters(scans.find(s => s.id === 4))"), "Tagged only is not a filter vote (export unaffected)")
    pg.reload(); pg.wait_for_selector(".srow[data-sid='3']"); pg.wait_for_timeout(300)
    ids = pg.evaluate("[...document.querySelectorAll('.srow')].map(r => +r.dataset.sid)")
    check(ids == [5, 3, 2], f"Tagged only survives a reload ({ids})")
    pg.evaluate("openDetail(3)"); pg.wait_for_timeout(600)
    check("Sold out" in pg.locator("#cmp").inner_text(), "the open card shows its chips")
    pg.evaluate("closeDetail()"); pg.wait_for_timeout(200)
    STATE["scans"] = [scan(5, "Lightning Bolt", sel=True), scan(4, "Opt")]       # turned off again
    pg.wait_for_function("document.querySelectorAll('.srow').length === 2", timeout=8000)
    st = pg.evaluate("[document.querySelectorAll('.srow .ltag').length, getComputedStyle(document.querySelector('#p-tagged')).display, document.querySelector('#fltPanel').className]")
    check(st[0] == 0 and st[1] == "none", f"turned off: chips and Tagged only gone, every row back ({st})")
    check(not errs, f"no page errors ({errs[:3]})")
    ctx.close()
    b.close()

srv.shutdown()
print(f"\n{'ALL PASSED' if not fails else f'{len(fails)} FAILED'}")
sys.exit(1 if fails else 0)
