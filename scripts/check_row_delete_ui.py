"""Drive the REAL desktop.html in headless Chromium against a stub server and check
the scan list's per-row delete (owner, 2026-11-05: "I want delete options here in
the list"; desktop d54 — CLAUDE.md → "Roles" and "Desktop row delete"):

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
    again, with a toast;
  * as a restricted guest (Settings → Guests get full access off) there is no button.

    pip install playwright        # Chromium: /opt/pw-browsers in a cloud session, or set CHROME
    python scripts/check_row_delete_ui.py
Not part of CI (it needs a browser); run it after any edit to the scan list rows.
"""
import glob, json, os, re, sys, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path

from playwright.sync_api import sync_playwright

STATIC = Path(__file__).resolve().parents[1] / "server" / "static"
VERSION = "d54"


def scan(i, name, sel=False):
    c = dict(id=f"c{i}", name=name, set="m10", set_name="M10", collector_number=str(i), rarity="common",
             finishes=["nonfoil"], multi_distance=70, image_small="/x.png", image_normal="/x.png")
    return dict(id=i, status="selected" if sel else "pending", identified=True, card_read={"name": name},
                confidence="high", candidates=[c], included=True, flagged=False,
                selection=dict(c, scryfall_id=c["id"], condition="NM", finish="Non-Foil", quantity=1)
                if sel else None, error=None, f2f={"conditions": {"NM": 1.5}} if sel else None,
                created_at="2026-11-05T00:00:00")


NAMES = {1: "Forest", 2: "Opt", 3: "Lightning Bolt", 4: "Counterspell", 5: "Sol Ring"}
STATE = {"role": "admin", "scans": [], "deletes": [], "lock": threading.Lock()}


def reset(role="admin"):
    with STATE["lock"]:
        STATE["role"] = role
        STATE["scans"] = [scan(i, NAMES[i], sel=i % 2 == 1) for i in (5, 4, 3, 2, 1)]
        STATE["deletes"] = []


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
            if STATE["role"] != "admin": return self.send(403, {"error": "guest"})
            before = len(STATE["scans"])
            STATE["scans"] = [s for s in STATE["scans"] if s["id"] != i]
            gone = len(STATE["scans"]) < before
        self.send(200 if gone else 404, {"deleted": i} if gone else {"error": "not found"})

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

    # Enter on the focused button: deletes, never opens
    btn(pg, 2).focus(); pg.keyboard.press("Enter"); pg.wait_for_timeout(150)
    check(2 not in ids(pg) and open_card(pg) == "Lightning Bolt", f"Enter on the button deletes without opening ({ids(pg)}, {open_card(pg)})")
    pg.locator("#undoBtn").click(); pg.wait_for_timeout(200)

    # deleting the open card clears the right pane
    pg.locator("#list .row[data-id='3']").hover(); btn(pg, 3).click(); pg.wait_for_timeout(200)
    check(open_card(pg) is None, f"deleting the open card clears the detail ({open_card(pg)})")
    check(pg.evaluate("!!document.querySelector('#right .empty')"), "the right pane is empty")
    pg.wait_for_timeout(1700)
    check(open_card(pg) is None, f"a poll never re-opens it ({open_card(pg)})")
    pg.locator("#undoBtn").click(); pg.wait_for_timeout(200)
    check(3 in ids(pg), "Undo brings the row back")

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
    check("Couldn't delete 1 scan" in (pg.locator("#toast").text_content() or ""),
          f"and a toast says so ({pg.locator('#toast').text_content()})")
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
    b.close()

srv.shutdown()
print("\n" + ("ALL OK" if not fails else f"{len(fails)} FAILED"))
sys.exit(1 if fails else 0)
