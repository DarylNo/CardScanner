"""Drive the REAL desktop.html in headless Chromium against a stub phone
server and check the 📱 Scanner panel's "Zoom to fit the Area" controls
(1.1.11, CLAUDE.md → "Zoom to fit the Area"):

- an older phone app (no `zoom_fit` in /api/device) shows no zoom controls;
- the checkbox reflects `zoom_fit` and PATCHes {"zoom_fit": true/false};
- the zoom line shows what the phone reports (ratio, target, what bound it,
  Area fit / lossless / lens max) and "settling…" while it settles;
- a PATCH answer whose zoom predates the change (an older phone app, or a
  busy one — the stub answers that way on purpose) is never shown: the line
  says "applying…" and the panel reads /api/device again (review of 1.1.11:
  "Zoom to fit is off" right after ticking the box);
- an Area being drawn on the phone (zoom.drawing) is said so;
- after a zoom / Area PATCH the panel takes a new snapshot once it settles;
- an Area drawn on the (base-space) snapshot while zoomed is PATCHed as the
  picture's own fractions — base fractions, 1:1, never divided by the zoom;
- the banner reads d46 and the page throws nothing.

    pip install playwright        # the browser: Chromium, see EXE below
    python scripts/check_device_ui.py

Not part of CI (it needs a browser); run it after any edit to the panel.
"""
import glob, io, json, os, sys, threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path

from PIL import Image, ImageDraw
from playwright.sync_api import sync_playwright

STATIC = Path(__file__).resolve().parents[1] / "server" / "static"
VERSION = "d46"

ZOOMED = {"ratio": 1.9, "target": 1.9, "limit": "the Area fit", "fit": 2.0, "lossless": 2.6, "max": 10.0, "settling": False}
UNZOOMED = {"ratio": 1.0, "target": 1.0, "limit": "Zoom to fit is off", "fit": 2.0, "lossless": 2.6, "max": 10.0, "settling": False}

STATE = {
    "dev": None,          # set per scenario
    "patches": [],
    "snap_gets": 0,
}


def device(zoom_fit=False, with_zoom=True):
    d = {"mode": "tray", "roi": {"x0": 0.25, "y0": 0.25, "x1": 0.75, "y1": 0.75}, "torch": False,
         "vibration": True, "high_res": False, "ae_lock": False, "check_ms": 2500, "camera": "auto",
         "cameras": [{"id": "0", "label": "Camera 0 · back · 13 MP · autofocus"}], "camera_live": True}
    if with_zoom:
        d["zoom_fit"] = zoom_fit
        d["zoom"] = dict(ZOOMED if zoom_fit else UNZOOMED)
    return d


def snapshot_png():
    # A base-space picture of a zoomed camera: grey ring, the zoomed view in the middle.
    w, h, z = 600, 800, STATE["dev"].get("zoom", {}).get("ratio", 1.0) if STATE["dev"] else 1.0
    im = Image.new("RGB", (w, h), (96, 96, 96))
    sw, sh = round(w / z), round(h / z)
    x0, y0 = round((w - sw) / 2), round((h - sh) / 2)
    ImageDraw.Draw(im).rectangle([x0, y0, x0 + sw - 1, y0 + sh - 1], fill=(200, 200, 190))
    b = io.BytesIO(); im.save(b, "PNG"); return b.getvalue()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store"); self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/": return self.send(200, (STATIC / "desktop.html").read_bytes(), "text/html")
        if p == "/api/me": return self.send(200, {"role": "admin"})
        if p == "/api/version": return self.send(200, {"version": "stub"})
        if p == "/api/price-status": return self.send(200, {"active": False, "done": 0, "total": 0, "cooldown_s": 0})
        if p == "/api/health": return self.send(200, {"ocr": {"available": True}})
        if p == "/api/update-check": return self.send(200, {"update_available": False})
        if p == "/api/setup/status": return self.send(200, {"index_built": True})
        if p == "/api/scans": return self.send(200, [])
        if p == "/api/device": return self.send(200, STATE["dev"])
        if p == "/api/device/snapshot.jpg":
            STATE["snap_gets"] += 1
            return self.send(200, snapshot_png(), "image/png")
        return self.send(404, {"error": "stub: no such route"})

    def do_PATCH(self):
        p = self.path.split("?")[0]
        n = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(n) or b"{}")
        if p != "/api/device": return self.send(404, {"error": "stub"})
        STATE["patches"].append(body)
        d = STATE["dev"]
        stale = json.loads(json.dumps(d.get("zoom")))
        for k, v in body.items():
            if k == "zoom_fit":
                if not isinstance(v, bool): return self.send(400, {"error": "zoom_fit must be true or false"})
                d["zoom_fit"] = v
                # What /api/device reads once the phone has applied it: the zoom settling.
                d["zoom"] = dict(ZOOMED if v else UNZOOMED, settling=True)
            elif k == "roi":
                d["roi"] = v
            else:
                d[k] = v
        # The ANSWER carries the zoom from before the change — what a busy phone (or 1.1.11
        # before its review) sends: the settings are new, the camera's zoom is not yet.
        answer = dict(d)
        if "zoom" in d: answer["zoom"] = stale
        return self.send(200, answer)


srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{srv.server_port}"
EXE = os.environ.get("CHROME") or next(iter(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome")), None)

fails = []
def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond: fails.append(msg)


def open_panel(pg):
    pg.goto(BASE + "/")
    pg.wait_for_function(f"document.querySelector('#dver').textContent.startsWith('{VERSION}')")
    pg.wait_for_selector("#devBtn", state="visible")
    pg.click("#devBtn")
    pg.wait_for_selector("#devModal.open")
    pg.wait_for_function("document.querySelector('#devSnap').naturalWidth > 0")


with sync_playwright() as pw:
    b = pw.chromium.launch(executable_path=EXE) if EXE else pw.chromium.launch()
    ctx = b.new_context(viewport={"width": 1400, "height": 1000})
    pg = ctx.new_page()
    errs = []
    pg.on("pageerror", lambda e: errs.append(str(e)))
    pg.on("console", lambda m: m.type == "error" and "404" not in m.text and errs.append(m.text))

    print("an older phone app (no zoom_fit)")
    STATE["dev"] = device(with_zoom=False)
    open_panel(pg)
    check(pg.locator("#dver").text_content().startswith(VERSION), f"banner reads {VERSION}")
    check(not pg.locator("#devZoomRow").is_visible(), "no zoom checkbox")
    check(not pg.locator("#devZoom").is_visible(), "no zoom line")
    pg.click("#devClose")

    print("zoom off")
    STATE["dev"] = device(zoom_fit=False)
    open_panel(pg)
    cb = pg.locator('#devZoomRow input[data-k="zoom_fit"]')
    check(pg.locator("#devZoomRow").is_visible(), "the zoom checkbox shows")
    check(not cb.is_checked(), "…unchecked")
    check(not pg.locator("#devZoom").is_visible(), "no zoom line while off at 1×")
    check(not pg.locator("#devZoomNote").is_visible(), "no zoomed-picture note")

    # Every text the zoom line shows, in order (a stale answer must never be on screen, even briefly).
    pg.evaluate("""() => { window.__zl = []; const el = document.querySelector('#devZoom');
        new MutationObserver(() => window.__zl.push(el.style.display === 'none' ? '' : el.textContent))
          .observe(el, {childList: true, subtree: true, characterData: true, attributes: true}); }""")

    print("turn it on")
    snaps_before = STATE["snap_gets"]
    pg.evaluate("window.__zl = []")
    cb.check()
    pg.wait_for_function("document.querySelector('#devZoom').style.display !== 'none'")
    check(STATE["patches"][-1] == {"zoom_fit": True}, f"PATCH {{zoom_fit: true}} (got {STATE['patches'][-1]})")
    check("applying" in pg.locator("#devZoom").inner_text(), f"the stale answer shows as applying: {pg.locator('#devZoom').inner_text()!r}")
    pg.wait_for_function("document.querySelector('#devZoom').textContent.includes('1.90×')", timeout=2000)
    line = pg.locator("#devZoom").inner_text()
    check("1.90×" in line and "settling" in line and "the Area fit" in line, f"…then the phone read again: the settling zoom: {line!r}")
    shown = pg.evaluate("window.__zl")
    check(not any("Zoom to fit is off" in t for t in shown), f"never 'Zoom to fit is off' after ticking it: {shown}")
    STATE["dev"]["zoom"]["settling"] = False                       # the phone settles…
    pg.wait_for_function("!document.querySelector('#devZoom').textContent.includes('settling')", timeout=8000)
    line = pg.locator("#devZoom").inner_text()
    check("Zoom 1.90×" in line and "lossless 2.60×" in line and "lens max 10.00×" in line and "Area fit 2.00×" in line,
          f"…then what it settled at: {line!r}")
    pg.wait_for_timeout(1800)
    check(STATE["snap_gets"] > snaps_before, f"a new snapshot after the zoom PATCH ({STATE['snap_gets'] - snaps_before})")
    check(pg.locator("#devZoomNote").is_visible(), "the zoomed-picture note shows")

    print("draw an Area on the base-space picture while zoomed")
    box = pg.locator("#devSnap").bounding_box()
    fx0, fy0, fx1, fy1 = 0.30, 0.22, 0.70, 0.78
    pg.mouse.move(box["x"] + box["width"] * fx0, box["y"] + box["height"] * fy0)
    pg.mouse.down()
    pg.mouse.move(box["x"] + box["width"] * 0.5, box["y"] + box["height"] * 0.5, steps=4)
    pg.mouse.move(box["x"] + box["width"] * fx1, box["y"] + box["height"] * fy1, steps=4)
    pg.mouse.up()
    pg.click("#devRoiSave")
    pg.wait_for_timeout(300)
    roi = STATE["patches"][-1].get("roi")
    ok = roi is not None and all(abs(roi[k] - v) < 0.01 for k, v in (("x0", fx0), ("y0", fy0), ("x1", fx1), ("y1", fy1)))
    check(ok, f"the Area is PATCHed as the picture's fractions (base, 1:1): {roi}")

    print("turn it off")
    pg.wait_for_timeout(2000)                                      # the Area PATCH's re-read is over
    pg.evaluate("window.__zl = []")
    cb.uncheck()
    pg.wait_for_timeout(300)
    check(STATE["patches"][-1] == {"zoom_fit": False}, f"PATCH {{zoom_fit: false}} (got {STATE['patches'][-1]})")
    pg.wait_for_function("document.querySelector('#devZoom').textContent.includes('Zoom to fit is off')", timeout=2000)
    shown = pg.evaluate("window.__zl")
    check(not any("target 1.90×" in t for t in shown), f"never the old 1.90× target after unticking: {shown}")
    STATE["dev"]["zoom"]["settling"] = False                       # settled at 1×: the line goes
    pg.wait_for_function("document.querySelector('#devZoom').style.display === 'none'", timeout=8000)
    check(not pg.locator("#devZoomNote").is_visible(), "no zoomed-picture note at 1×")

    print("an Area drawn on the phone while zoomed")
    STATE["dev"]["zoom_fit"] = True
    STATE["dev"]["zoom"] = dict(ZOOMED, ratio=1.0, drawing=True)
    pg.wait_for_function("document.querySelector('#devZoom').textContent.includes('drawn on the phone')", timeout=8000)
    line = pg.locator("#devZoom").inner_text()
    check("1.00×" in line and "settling" not in line, f"the line says the phone is drawing, at 1×: {line!r}")
    check(not pg.locator("#devZoomNote").is_visible(), "no zoomed-picture note while drawing (the camera is at 1×)")

    check(not errs, f"no page errors: {errs}")
    b.close()

print("FAILED: %d" % len(fails) if fails else "all checks passed")
sys.exit(1 if fails else 0)
