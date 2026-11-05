"""Drive the REAL desktop.html in headless Chromium against a stub server and check
the scan list's per-row delete (owner, 2026-11-05: "I want delete options here in
the list"; desktop d54, d55 — CLAUDE.md → "Roles" and "Desktop row delete"):

  * as admin every row carries a 🗑 button, hidden until the row is hovered or the
    button has keyboard focus, always shown on a touch screen;
  * a click (or Enter on the focused button) never opens the row: the open card stays;
  * the row leaves the list at once and an Undo bar shows; after 5 s exactly ONE
    DELETE /api/scans/{id} reaches the server and the bar goes;
  * Undo within 5 s sends nothing and the row comes back;
  * several rows can wait at once ("2 scans deleted"); Undo restores them all;
  * deleting the card open in the right pane clears the pane;
  * leaving the page with rows still waiting sends their DELETEs (keepalive);
  * the list's polling never brings a waiting row back;
  * a DELETE the server refuses (full access turned off meanwhile → 403) shows its row
    again, with a toast that says WHY (never "try again" — it cannot succeed), and the
    page asks /api/me again so the 🗑 buttons go; a refusal while the tab was hidden
    is told when it comes back;
  * as a restricted guest (Settings → Guests get full access off) there is no button;
  * d55 (review of d54): with the list scrolled to its end, the last row's 🗑 is never
    under the Undo bar; Enter on a 🗑 moves focus to Undo, and Undo puts it back on the
    restored row's 🗑; Undo after deleting the OPEN card opens it again; Export sends
    the waiting DELETEs before the builder's preview (what is counted is what exports);
    the detail pane's "Delete this scan" and Clear unpicked / Clear all say "Couldn't
    delete" on a refusal (they said "Scan deleted" / "Deleted undefined scans");
  * phone.html (v57): a refused swipe delete, "Delete this scan" or Clear says so on
    the page (it put the scan back silently / said "Deleted undefined scans").

    pip install playwright        # Chromium: /opt/pw-browsers in a cloud session, or set CHROME
    python scripts/check_row_delete_ui.py
Not part of CI (it needs a browser); run it after any edit to the scan list rows.
"""
import glob, json, os, re, sys, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path

from playwright.sync_api import sync_playwright

STATIC = Path(__file__).resolve().parents[1] / "server" / "static"
VERSION = "d56"
PHONE_VERSION = "v58"


def scan(i, name, sel=False):
    c = dict(id=f"c{i}", name=name, set="m10", set_name="M10", collector_number=str(i), rarity="common",
             finishes=["nonfoil"], multi_distance=70, image_small="/x.png", image_normal="/x.png")
    return dict(id=i, status="selected" if sel else "pending", identified=True, card_read={"name": name},
                confidence="high", candidates=[c], included=True, flagged=False,
                selection=dict(c, scryfall_id=c["id"], condition="NM", finish="Non-Foil", quantity=1)
                if sel else None, error=None, f2f={"conditions": {"NM": 1.5}} if sel else None,
                created_at="2026-11-05T00:00:00")


NAMES = {1: "Forest", 2: "Opt", 3: "Lightning Bolt", 4: "Counterspell", 5: "Sol Ring"}
STATE = {"role": "admin", "scans": [], "deletes": [], "log": [], "status": {}, "hang": set(), "lock": threading.Lock()}


def reset(role="admin", n=5):
    with STATE["lock"]:
        STATE["role"] = role
        STATE["scans"] = [scan(i, NAMES.get(i, f"Card {i}"), sel=i % 2 == 1) for i in range(n, 0, -1)]
        STATE["deletes"] = []
        STATE["log"] = []
        STATE["status"] = {}       # id → the status its DELETE answers (401 / 500 …)
        STATE["hang"] = set()      # ids whose DELETE answers only after 8 s (the phone gone from the LAN)


LAYOUT = {"columns": [{"field": "name", "header": "Name"}], "header": True, "delimiter": "comma", "combine": True}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body, ctype="application/json"):
        if not isinstance(body, bytes): body = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store"); self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        p = self.path.split("?")[0]
        with STATE["lock"]: STATE["log"].append(("GET", p))
        if p == "/": return self.send(200, (STATIC / "desktop.html").read_bytes(), "text/html")
        if p == "/phone": return self.send(200, (STATIC / "phone.html").read_bytes(), "text/html")
        if p == "/api/export/layout":
            return self.send(200, {"fields": [{"field": "name", "header": "Name"}], "default": LAYOUT, "layout": LAYOUT})
        if p == "/api/me": return self.send(200, {"role": STATE["role"]})
        if p == "/api/version": return self.send(200, {"version": "stub"})
        if p == "/api/price-status": return self.send(200, {"active": False, "done": 0, "total": 0, "cooldown_s": 0})
        if p == "/api/health": return self.send(200, {"ocr": {"available": True}})
        if p == "/api/update-check": return self.send(200, {"update_available": False})
        if p == "/api/setup/status": return self.send(200, {"index_built": True})
        with STATE["lock"]:
            if p == "/api/scans": return self.send(200, STATE["scans"])
            m = re.fullmatch(r"/api/scans/(\d+)", p)
            if m:
                s = next((x for x in STATE["scans"] if x["id"] == int(m.group(1))), None)
                return self.send(200 if s else 404, s or {"error": "not found"})
        return self.send(404, {"error": "stub"})

    def do_DELETE(self):
        m = re.fullmatch(r"/api/scans/(\d+)", self.path.split("?")[0])
        if not m: return self.send(404, {"error": "stub"})
        i = int(m.group(1))
        with STATE["lock"]:
            STATE["deletes"].append(i)
            STATE["log"].append(("DELETE", self.path))
            forced, hang = STATE["status"].get(i), i in STATE["hang"]
        if hang: time.sleep(8)
        if forced: return self.send(forced, {"error": f"stub {forced}"})
        with STATE["lock"]:
            if STATE["role"] != "admin": return self.send(403, {"error": "guest"})
            before = len(STATE["scans"])
            STATE["scans"] = [s for s in STATE["scans"] if s["id"] != i]
            gone = len(STATE["scans"]) < before
        self.send(200 if gone else 404, {"deleted": i} if gone else {"error": "not found"})

    def do_POST(self):
        p = self.path.split("?")[0]
        n = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(n) or b"{}")
        with STATE["lock"]:
            STATE["log"].append(("POST", p))
            if p == "/api/scans/delete-all":
                if STATE["role"] != "admin":
                    return self.send(403, {"error": "only the scanner's owner can do that — flag it for deletion instead"})
                keep = (lambda s: s["status"] == "selected") if body.get("only") == "unselected" else (lambda s: False)
                before = len(STATE["scans"])
                STATE["scans"] = [s for s in STATE["scans"] if keep(s)]
                return self.send(200, {"deleted": before - len(STATE["scans"])})
            if p == "/api/export/preview":
                live = [s for s in STATE["scans"] if s["status"] == "selected" and s["included"]]
                return self.send(200, {"csv": "Name\r\n" + "".join(s["card_read"]["name"] + "\r\n" for s in live),
                                       "rows": len(live)})
        self.send(200, {})

    def do_PATCH(self): self.send(200, {})


srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{srv.server_port}"
EXE = os.environ.get("CHROME") or next(iter(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome")), None)

fails = []
def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond: fails.append(msg)


def deletes():
    with STATE["lock"]: return list(STATE["deletes"])


def ids(pg):
    return pg.evaluate("[...document.querySelectorAll('#list .row')].map(r => +r.dataset.id)")


def btn(pg, i):
    return pg.locator(f"#list .row[data-id='{i}'] [data-del]")


def opacity(pg, i):
    return pg.evaluate(f"getComputedStyle(document.querySelector(\"#list .row[data-id='{i}'] [data-del]\")).opacity")


def open_card(pg):
    """The scan open in the right pane (its name), or None when the pane is empty."""
    return pg.evaluate("document.querySelector('#right .dname')?.textContent || null")


def undo_shown(pg):
    return pg.evaluate("getComputedStyle(document.querySelector('#undoBar')).display !== 'none'")


def focused(pg):
    """What has keyboard focus: '#undoBtn', 'del:<id>' for a row's 🗑, else the tag name."""
    return pg.evaluate("""(() => { const a = document.activeElement;
        if (!a) return null; if (a.id) return '#' + a.id;
        if (a.dataset && a.dataset.del) return 'del:' + a.dataset.del; return a.tagName; })()""")


def toast_text(pg):
    return pg.evaluate("document.querySelector('#toast').classList.contains('show') ? document.querySelector('#toast').textContent : ''")


def set_hidden(pg, hidden):
    """Emulate the tab going to the background / coming back (Playwright pages are always visible)."""
    pg.evaluate("""h => { Object.defineProperty(document, 'hidden', { value: h, configurable: true });
        Object.defineProperty(document, 'visibilityState', { value: h ? 'hidden' : 'visible', configurable: true });
        document.dispatchEvent(new Event('visibilitychange')); }""", hidden)


def load(ctx):
    pg = ctx.new_page(); errs = []
    pg.on("pageerror", lambda e: errs.append(str(e)))
    pg.goto(BASE + "/")
    pg.wait_for_selector("#list .row[data-id='1']")
    pg.wait_for_timeout(400)                       # /api/me answered, the first polls rendered
    return pg, errs


with sync_playwright() as pw:
    b = pw.chromium.launch(executable_path=EXE) if EXE else pw.chromium.launch()
    ctx = b.new_context(viewport={"width": 1500, "height": 950})

    print("desktop.html — admin")
    reset()
    pg, errs = load(ctx)
    check(pg.evaluate("UI_VERSION") == VERSION, f"banner {VERSION}")
    check(ids(pg) == [5, 4, 3, 2, 1], f"five rows ({ids(pg)})")
    check(pg.locator("#list .row [data-del]").count() == 5, "every row has a delete button")
    check(all(btn(pg, i).get_attribute("aria-label") for i in (5, 4, 3, 2, 1)), "each button has an aria-label")
    pg.mouse.move(1400, 900)                       # off the list
    pg.wait_for_timeout(200)
    check(opacity(pg, 4) == "0", f"hidden until hovered (opacity {opacity(pg, 4)})")
    pg.locator("#list .row[data-id='4']").hover(); pg.wait_for_timeout(200)
    check(opacity(pg, 4) == "1", f"shown on hover (opacity {opacity(pg, 4)})")
    pg.mouse.move(1400, 900); pg.wait_for_timeout(200)
    btn(pg, 2).focus(); pg.wait_for_timeout(200)
    check(opacity(pg, 2) == "1", f"shown with keyboard focus (opacity {opacity(pg, 2)})")
    pg.evaluate("document.activeElement.blur()")

    # open scan 3, then delete scan 4 from its row: scan 3 stays open, 4 leaves the list
    pg.locator("#list .row[data-id='3'] .nm").click()
    pg.wait_for_function("document.querySelector('#list .row.active')?.dataset.id === '3'")
    check(open_card(pg) == "Lightning Bolt", f"scan 3 open ({open_card(pg)})")
    pg.locator("#list .row[data-id='4']").hover()
    btn(pg, 4).click()
    pg.wait_for_timeout(150)
    check(4 not in ids(pg), f"the row leaves the list at once ({ids(pg)})")
    check(open_card(pg) == "Lightning Bolt", f"the click never opened the row: scan 3 still open ({open_card(pg)})")
    check(pg.evaluate("document.querySelector('#list .row.active')?.dataset.id") == "3", "row 3 still the active one")
    check(undo_shown(pg), "the Undo bar shows")
    check(pg.locator("#undoMsg").text_content() == "Scan deleted", f"it says so ({pg.locator('#undoMsg').text_content()})")
    check(deletes() == [], f"no DELETE before the Undo window runs out ({deletes()})")
    pg.wait_for_timeout(3200)                      # a couple of polls inside the window
    check(4 not in ids(pg) and deletes() == [], f"polling never brings it back, nothing sent yet ({ids(pg)}, {deletes()})")
    pg.wait_for_timeout(2600)                      # past 5 s
    check(deletes() == [4], f"exactly one DELETE /api/scans/4 after 5 s ({deletes()})")
    check(not undo_shown(pg), "the Undo bar goes")
    pg.wait_for_timeout(1800)
    check(ids(pg) == [5, 3, 2, 1] and deletes() == [4], f"gone for good, nothing sent twice ({ids(pg)}, {deletes()})")

    # Undo within 5 s: nothing sent, the row back
    pg.locator("#list .row[data-id='2']").hover(); btn(pg, 2).click(); pg.wait_for_timeout(150)
    check(2 not in ids(pg), "row 2 waiting")
    pg.wait_for_timeout(1500)
    pg.locator("#undoBtn").click(); pg.wait_for_timeout(200)
    check(ids(pg) == [5, 3, 2, 1], f"Undo brings it back ({ids(pg)})")
    check(not undo_shown(pg), "the bar goes on Undo")
    pg.wait_for_timeout(5500)
    check(deletes() == [4], f"Undo sends nothing ({deletes()})")
    check(ids(pg) == [5, 3, 2, 1], f"still there after the window ({ids(pg)})")

    # several at once; Undo restores them all
    pg.locator("#list .row[data-id='2']").hover(); btn(pg, 2).click()
    pg.locator("#list .row[data-id='1']").hover(); btn(pg, 1).click(); pg.wait_for_timeout(150)
    check(ids(pg) == [5, 3], f"two rows waiting ({ids(pg)})")
    check(pg.locator("#undoMsg").text_content() == "2 scans deleted", f"'2 scans deleted' ({pg.locator('#undoMsg').text_content()})")
    pg.locator("#undoBtn").click(); pg.wait_for_timeout(200)
    check(ids(pg) == [5, 3, 2, 1], f"Undo restores every waiting row ({ids(pg)})")

    # Enter on the focused button: deletes, never opens — and focus moves to Undo (d55: the list
    # re-render dropped it to <body>, and Undo was dozens of Tabs away inside the 5 s)
    btn(pg, 2).focus(); pg.keyboard.press("Enter"); pg.wait_for_timeout(150)
    check(2 not in ids(pg) and open_card(pg) == "Lightning Bolt", f"Enter on the button deletes without opening ({ids(pg)}, {open_card(pg)})")
    check(focused(pg) == "#undoBtn", f"keyboard focus is on Undo ({focused(pg)})")
    pg.keyboard.press("Enter"); pg.wait_for_timeout(200)
    check(2 in ids(pg), f"Enter again = Undo: the row is back ({ids(pg)})")
    check(focused(pg) == "del:2", f"focus is back on the restored row's 🗑 ({focused(pg)})")
    if 2 not in ids(pg): pg.locator("#undoBtn").click(); pg.wait_for_timeout(200)     # (an older page: carry on)
    pg.evaluate("document.activeElement.blur()")
    # a mouse click never moves focus to the bar (only a keyboard press does) — nor does a mouse Undo
    # put it on the restored row's 🗑 (Chrome focuses a clicked button: the 🗑 then stayed shown and a
    # later Space deleted the row again)
    pg.locator("#list .row[data-id='2']").hover(); btn(pg, 2).click(); pg.wait_for_timeout(150)
    check(focused(pg) != "#undoBtn", f"a click leaves focus alone ({focused(pg)})")
    pg.locator("#undoBtn").click(); pg.wait_for_timeout(200)
    check(focused(pg) != "del:2", f"a mouse Undo leaves focus off the restored 🗑 ({focused(pg)})")
    pg.evaluate("document.activeElement.blur()")

    # deleting the open card clears the right pane
    pg.locator("#list .row[data-id='3']").hover(); btn(pg, 3).click(); pg.wait_for_timeout(200)
    check(open_card(pg) is None, f"deleting the open card clears the detail ({open_card(pg)})")
    check(pg.evaluate("!!document.querySelector('#right .empty')"), "the right pane is empty")
    pg.wait_for_timeout(1700)
    check(open_card(pg) is None, f"a poll never re-opens it ({open_card(pg)})")
    pg.locator("#undoBtn").click(); pg.wait_for_timeout(200)
    check(3 in ids(pg), "Undo brings the row back")
    check(open_card(pg) == "Lightning Bolt", f"… and opens it again in the right pane ({open_card(pg)})")
    check(pg.evaluate("document.querySelector('#list .row.active')?.dataset.id") == "3", "row 3 active again")
    # Undo after the pane moved on to another card leaves that card open
    pg.locator("#list .row[data-id='3']").hover(); btn(pg, 3).click(); pg.wait_for_timeout(150)
    pg.locator("#list .row[data-id='2'] .nm").click(); pg.wait_for_timeout(250)
    pg.locator("#undoBtn").click(); pg.wait_for_timeout(200)
    check(open_card(pg) == "Opt", f"Undo never takes over a card opened meanwhile ({open_card(pg)})")
    pg.locator("#list .row[data-id='3'] .nm").click(); pg.wait_for_timeout(250)

    # two rows waiting, then leave the page: both DELETEs go (keepalive)
    pg.locator("#list .row[data-id='5']").hover(); btn(pg, 5).click()
    pg.locator("#list .row[data-id='1']").hover(); btn(pg, 1).click(); pg.wait_for_timeout(150)
    check(deletes() == [4], f"nothing sent while waiting ({deletes()})")
    check(errs == [], f"no page errors ({errs})")
    pg.goto("about:blank")
    t0 = time.time()
    while time.time() - t0 < 4 and len(deletes()) < 3: time.sleep(0.05)
    check(sorted(deletes()) == [1, 4, 5], f"leaving the page sends both waiting DELETEs ({deletes()})")
    pg.close()

    print("desktop.html — touch screen")
    reset()
    tctx = b.new_context(viewport={"width": 1280, "height": 900}, has_touch=True, is_mobile=True)
    pg, errs = load(tctx)
    check(pg.evaluate("matchMedia('(hover: none)').matches"), "the touch context reports (hover: none)")
    check(opacity(pg, 4) == "1", f"always shown on a touch screen (opacity {opacity(pg, 4)})")
    btn(pg, 4).tap(); pg.wait_for_timeout(200)
    check(4 not in ids(pg) and open_card(pg) == "Sol Ring", f"a tap deletes without opening ({ids(pg)}, {open_card(pg)})")
    pg.locator("#undoBtn").tap(); pg.wait_for_timeout(200)
    check(ids(pg) == [5, 4, 3, 2, 1], f"Undo by tap ({ids(pg)})")
    check(errs == [], f"no page errors ({errs})")
    pg.close(); tctx.close()

    print("desktop.html — the owner turns full access off while a guest has the page open")
    reset()
    pg, errs = load(ctx)
    with STATE["lock"]: STATE["role"] = "guest"      # the server now refuses; the page asked /api/me at load
    pg.locator("#list .row[data-id='2']").hover(); btn(pg, 2).click(); pg.wait_for_timeout(150)
    check(2 not in ids(pg), "row 2 waiting")
    pg.wait_for_timeout(5600)
    check(deletes() == [2], f"the DELETE was sent ({deletes()})")
    pg.wait_for_function("document.querySelector(\"#list .row[data-id='2']\")", timeout=5000)
    check(2 in ids(pg), f"refused (403): the row is back ({ids(pg)})")
    t = pg.locator("#toast").text_content() or ""
    check("Couldn't delete 1 scan" in t, f"and a toast says so ({t})")
    check("try again" not in t and "full access" in t, f"… and why — a retry cannot succeed ({t})")
    pg.wait_for_timeout(1000)
    check(pg.locator("#list .row [data-del]").count() == 0, "the page asked /api/me again: no 🗑 buttons now")
    check(errs == [], f"no page errors ({errs})")
    pg.close()

    print("desktop.html — other answers: 401 (no longer signed in), 500 (refused)")
    reset()
    pg, errs = load(ctx)
    with STATE["lock"]: STATE["status"] = {2: 401, 4: 500}
    pg.locator("#list .row[data-id='2']").hover(); btn(pg, 2).click(); pg.wait_for_timeout(150)
    pg.evaluate("commitDeletes(false)"); pg.wait_for_timeout(500)
    t = toast_text(pg)
    check("no longer signed in" in t and "try again" not in t, f"401: the session is gone, a retry cannot succeed ({t})")
    pg.locator("#list .row[data-id='4']").hover(); btn(pg, 4).click(); pg.wait_for_timeout(150)
    pg.evaluate("commitDeletes(false)"); pg.wait_for_timeout(500)
    t = toast_text(pg)
    check("HTTP 500" in t and "didn't answer" not in t, f"500: the phone answered — it refused ({t})")
    pg.wait_for_function("document.querySelectorAll(\"#list .row[data-id='2'], #list .row[data-id='4']\").length === 2", timeout=5000)
    check(True, "both rows are back")
    check(errs == [], f"no page errors ({errs})")
    pg.close()

    print("desktop.html — Export never waits long on a DELETE that does not answer")
    reset()
    pg, errs = load(ctx)
    with STATE["lock"]: STATE["hang"] = {5}
    pg.locator("#list .row[data-id='5']").hover(); btn(pg, 5).click(); pg.wait_for_timeout(150)
    t0 = time.time()
    pg.locator("#exportBtn").click()
    pg.wait_for_selector("#csvModal.open", timeout=7000)
    took = time.time() - t0
    check(took < 4.5, f"the builder opens within ~3 s anyway ({took:.1f} s)")
    pg.wait_for_timeout(5500)                        # let the slow DELETE answer before the page goes
    check(errs == [], f"no page errors ({errs})")
    pg.close()

    print("desktop.html — refused while the tab was hidden: told on return")
    reset()
    pg, errs = load(ctx)
    with STATE["lock"]: STATE["role"] = "guest"
    pg.locator("#list .row[data-id='2']").hover(); btn(pg, 2).click(); pg.wait_for_timeout(150)
    set_hidden(pg, True)                             # the tab goes to the background: the DELETE goes now
    t0 = time.time()
    while time.time() - t0 < 3 and deletes() != [2]: time.sleep(0.05)
    check(deletes() == [2], f"hiding the tab sent the DELETE ({deletes()})")
    pg.wait_for_timeout(2500)                        # the refusal lands while hidden (and the toast would lapse)
    set_hidden(pg, False)
    pg.wait_for_timeout(200)
    t = toast_text(pg)
    check("Couldn't delete 1 scan" in t, f"coming back says the delete was refused ({t})")
    pg.wait_for_function("document.querySelector(\"#list .row[data-id='2']\")", timeout=5000)
    check(2 in ids(pg), f"the row is back ({ids(pg)})")
    check(errs == [], f"no page errors ({errs})")
    pg.close()

    print("desktop.html — the detail's Delete this scan and Clear, refused")
    reset()
    pg, errs = load(ctx)
    pg.on("dialog", lambda d: d.accept())
    pg.locator("#list .row[data-id='3'] .nm").click()
    pg.wait_for_function("document.querySelector('#list .row.active')?.dataset.id === '3'")
    with STATE["lock"]: STATE["role"] = "guest"
    pg.locator("#delBtn").click(); pg.wait_for_timeout(600)
    t = toast_text(pg)
    check(deletes() == [3], f"the DELETE went ({deletes()})")
    check("Couldn't delete" in t and "Scan deleted" not in t, f"the detail's delete says it was refused ({t})")
    check(open_card(pg) == "Lightning Bolt", f"the card stays open ({open_card(pg)})")
    check(3 in ids(pg), f"and in the list ({ids(pg)})")
    pg.close()
    reset()
    pg, errs = load(ctx)
    pg.on("dialog", lambda d: d.accept())
    with STATE["lock"]: STATE["role"] = "guest"
    pg.locator("#clearJunkBtn").click(); pg.wait_for_timeout(600)
    t = toast_text(pg)
    check("undefined" not in t and "Couldn't delete" in t, f"Clear unpicked refused says so ({t})")
    check(ids(pg) == [5, 4, 3, 2, 1], f"nothing left the list ({ids(pg)})")
    pg.close()
    reset()
    pg, errs = load(ctx)
    pg.on("dialog", lambda d: d.accept())
    pg.locator("#clearJunkBtn").click(); pg.wait_for_timeout(600)
    t = toast_text(pg)
    check(t == "Deleted 2 scans", f"Clear unpicked allowed: 'Deleted 2 scans' ({t})")
    check(ids(pg) == [5, 3, 1], f"the unpicked rows went ({ids(pg)})")
    check(errs == [], f"no page errors ({errs})")
    pg.close()

    print("desktop.html — Export sends the waiting DELETEs first")
    reset()
    pg, errs = load(ctx)
    check("<b>3</b> to export" in pg.locator("#stats").inner_html(), f"3 to export ({pg.locator('#stats').inner_text()})")
    pg.locator("#list .row[data-id='5']").hover(); btn(pg, 5).click(); pg.wait_for_timeout(150)
    check("<b>2</b> to export" in pg.locator("#stats").inner_html(), f"the waiting row is not counted ({pg.locator('#stats').inner_text()})")
    with STATE["lock"]: STATE["log"] = []
    pg.locator("#exportBtn").click()
    pg.wait_for_selector("#csvModal.open")
    pg.wait_for_function("/ row/.test(document.querySelector('#csvPrevH').textContent)", timeout=3000)
    with STATE["lock"]: log = [x for x in STATE["log"] if x[0] in ("DELETE", "POST")]
    check(log[:1] == [("DELETE", "/api/scans/5")], f"the waiting DELETE went before anything else ({log})")
    check(("POST", "/api/export/preview") in log, f"then the preview ({log})")
    check(not undo_shown(pg), "the Undo bar is gone (the delete is no longer undoable)")
    check("2 of 2 rows" in pg.locator("#csvPrevH").text_content(), f"the preview counts what the list counted ({pg.locator('#csvPrevH').text_content()})")
    check(errs == [], f"no page errors ({errs})")
    pg.close()

    print("desktop.html — 30 scans: the last row's 🗑 is never under the Undo bar")
    reset(n=30)
    pg, errs = load(ctx)
    pg.locator("#list .row[data-id='29']").hover(); btn(pg, 29).click(); pg.wait_for_timeout(150)
    check(undo_shown(pg), "the Undo bar shows")
    pg.evaluate("document.querySelector('#left').scrollTop = 1e6"); pg.wait_for_timeout(150)
    hit = pg.evaluate("""(() => { const b = document.querySelector("#list .row[data-id='1'] [data-del]");
        const r = b.getBoundingClientRect(); const e = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
        return e && (e.closest('[data-del]')?.dataset.del || e.id || e.tagName); })()""")
    check(hit == "1", f"the point on the last row's 🗑 is that 🗑, not the bar ({hit})")
    gap = pg.evaluate("""document.querySelector('#undoBar').getBoundingClientRect().top
        - document.querySelector("#list .row[data-id='1']").getBoundingClientRect().bottom""")
    check(gap >= 0, f"the whole last row sits above the bar ({gap:.0f} px)")
    btn(pg, 1).click(); pg.wait_for_timeout(150)
    check(1 not in ids(pg) and 29 not in ids(pg), f"so a second delete deletes (no Undo by mistake) ({len(ids(pg))} rows)")
    check(pg.locator("#undoMsg").text_content() == "2 scans deleted", f"'2 scans deleted' ({pg.locator('#undoMsg').text_content()})")
    pg.locator("#undoBtn").click(); pg.wait_for_timeout(200)
    check(errs == [], f"no page errors ({errs})")
    pg.close()

    print("desktop.html — restricted guest")
    reset(role="guest")
    pg, errs = load(ctx)
    check(pg.evaluate("document.body.classList.contains('guest')"), "the page knows it is a guest")
    check(pg.locator("#list .row [data-del]").count() == 0, "no delete button on any row")
    pg.locator("#list .row[data-id='4']").hover(); pg.wait_for_timeout(200)
    check(pg.locator("#list .row [data-del]").count() == 0, "not even on hover")
    check(errs == [], f"no page errors ({errs})")
    pg.close()

    print("desktop.html — guest with full access (/api/me says admin)")
    reset(role="admin")
    pg, errs = load(ctx)
    check(pg.locator("#list .row [data-del]").count() == 5, "the Share-code guest with full access gets the buttons")
    pg.close()

    print("phone.html — refused deletes say so")
    pctx = b.new_context(viewport={"width": 411, "height": 914}, is_mobile=True, has_touch=True)
    reset()
    pg = pctx.new_page(); errs = []
    pg.on("pageerror", lambda e: errs.append(str(e)))
    pg.on("dialog", lambda d: d.accept())
    pg.goto(BASE + "/phone?panel=1"); pg.wait_for_selector(".srow[data-sid='2']"); pg.wait_for_timeout(400)
    check(pg.evaluate("UI_VERSION") == PHONE_VERSION, f"phone banner {PHONE_VERSION}")
    flash = lambda: pg.evaluate("document.querySelector('#flash').classList.contains('show') ? document.querySelector('#flash').textContent : ''")
    status = lambda: pg.evaluate("document.querySelector('#status').textContent")
    with STATE["lock"]: STATE["role"] = "guest"
    pg.evaluate("queueDelete(2); commitDeletes(false)"); pg.wait_for_timeout(700)
    check(deletes() == [2], f"the swipe's DELETE went ({deletes()})")
    check("Couldn't delete 1 scan" in flash(), f"a refused swipe delete says so ({flash()})")
    pg.wait_for_function("document.querySelector(\".srow[data-sid='2']\")", timeout=5000)
    check(True, "and the row is back")
    pg.wait_for_function("role === 'guest'", timeout=3000)
    check(pg.evaluate("document.body.classList.contains('guest')"), "the page asked /api/me again: a guest now (a swipe flags)")
    with STATE["lock"]: STATE["role"] = "admin"
    pg.evaluate("refreshRole()"); pg.wait_for_function("role === 'admin'", timeout=3000)
    pg.evaluate("openDetail(3)"); pg.wait_for_selector("#p-delete")
    with STATE["lock"]: STATE["role"] = "guest"
    pg.locator("#p-delete").click(); pg.wait_for_timeout(700)
    check(deletes() == [2, 3], f"the detail's DELETE went ({deletes()})")
    check("Couldn't delete" in flash(), f"a refused Delete this scan says so ({flash()})")
    check(pg.evaluate("document.querySelector('#detailPanel').classList.contains('open')"), "the card stays open")
    on_top = pg.evaluate("""(() => { const f = document.querySelector('#flash'), r = f.getBoundingClientRect();
        f.style.pointerEvents = 'auto';            // it never takes taps: hit-test it for this probe only
        const e = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
        f.style.pointerEvents = ''; return !!e && e.id === 'flash'; })()""")
    check(on_top, "the notice is visible over the open card (it sat in the list, under the detail)")
    pg.evaluate("closeDetail()"); pg.wait_for_timeout(200)
    with STATE["lock"]: STATE["role"] = "admin"
    pg.evaluate("refreshRole()"); pg.wait_for_function("role === 'admin'", timeout=3000)
    with STATE["lock"]: STATE["role"] = "guest"
    pg.evaluate("clearScans('unselected')"); pg.wait_for_timeout(700)
    check("undefined" not in flash() and "Couldn't delete" in flash(), f"a refused Clear says so on the list ({flash()})")
    check("Couldn't delete" in status(), f"… and on the camera page's status line ({status()})")
    with STATE["lock"]: STATE["role"] = "admin"
    pg.evaluate("clearScans('unselected')"); pg.wait_for_timeout(700)
    check(flash() == "Deleted 2 scans.", f"an allowed Clear: 'Deleted 2 scans.' on the list ({flash()})")
    # A notice is up (a refusal reads "try again" for 5 s) and the user swipes again: the Undo bar's
    # button must be tappable — the notice sat right over it (v57 review)
    pg.evaluate("flash('Couldn\\'t delete 1 scan — the phone didn\\'t answer; try again', 5000, true)")
    check(pg.evaluate("scansPanel.classList.contains('open')"), "(the review screen shows the scan list)")
    pg.wait_for_timeout(200)
    live = pg.evaluate("liveScans().map(s => s.id)")
    pg.evaluate(f"queueDelete({live[0]})"); pg.wait_for_timeout(200)
    hit = pg.evaluate("""(() => { const b = document.querySelector('#undoBtn').getBoundingClientRect();
        const e = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2); return e ? (e.id || e.tagName) : null; })()""")
    check(hit == "undoBtn", f"the point on Undo is Undo, not the notice ({hit})")
    clear = pg.evaluate("""(() => { const f = document.querySelector('#flash'), u = document.querySelector('#undoBar');
        if (!f.classList.contains('show')) return true;
        const a = f.getBoundingClientRect(), b = u.getBoundingClientRect(); return a.bottom <= b.top || a.top >= b.bottom; })()""")
    check(clear, "the notice never covers the Undo bar")
    pg.locator("#undoBtn").tap(); pg.wait_for_timeout(200)
    check(pg.evaluate("pendingDel.size") == 0, "a tap on Undo undoes")
    check(errs == [], f"no page errors ({errs})")
    pg.close(); pctx.close()
    b.close()

srv.shutdown()
print("\n" + ("ALL OK" if not fails else f"{len(fails)} FAILED"))
sys.exit(1 if fails else 0)
