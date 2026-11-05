"""Drive the REAL desktop.html in headless Chromium against a stub phone
server and check the 📱 Scanner panel's "Zoom to fit the Area" controls
(1.1.14, CLAUDE.md → "Zoom to fit the Area"):

- an older phone app (no `zoom_fit` in /api/device) shows no zoom controls;
- the checkbox reflects `zoom_fit` and PATCHes {"zoom_fit": true/false};
- the zoom line shows what the phone reports (ratio, target, what bound it,
  Area fit / lossless / lens max) and "settling…" while it settles;
- a PATCH answer whose zoom predates the change (an older phone app, or a
  busy one — the stub answers that way on purpose) is never shown: the line
  says "applying…" and the panel reads /api/device again (review of 1.1.14:
  "Zoom to fit is off" right after ticking the box);
- an Area being drawn on the phone (zoom.drawing) is said so;
- after a zoom / Area PATCH the panel takes a new snapshot once it settles;
- an Area drawn on the (base-space) snapshot while zoomed is PATCHed as the
  picture's own fractions — base fractions, 1:1, never divided by the zoom;
- the off-centre hint (1.1.17): with the switch off at 1× the line says what
  this Area WOULD zoom ("Off — this Area would zoom …") and, when the phone
  sends `off_centre`, "Off-centre: <its words>"; a dashed ghost box sits where
  the same Area would be centred (the centred fractions, ±1 %), hidden while a
  new Area is drawn (here or on the phone); `would` null or absent (an older
  phone app) → the line stays hidden as before; ticking the box never shows
  "Zoom to fit is off"; with the switch OFF, an Area saved or cleared (or a
  lens / High resolution change) against a phone that sends the hint goes
  through "applying…" too — the stale answer's "would" / "Off-centre" words
  for the OLD Area and its ghost are never on screen, and the ghost needs an
  Area (review of 39ef7cf);
- Whole frame | Camera view (1.1.17, `snapshot.jpg?view=camera`): the toggle
  shows only when /api/device lists `snapshot_views` with "camera" (an older
  phone app: none); the panel opens on Whole frame, every time; Camera view
  GETs `view=camera` (the camera's full-bleed picture, no grey ring), says
  "Camera view at 1.90× — what the scanner sees (600×800 px)" and draws the
  Area where it sits in THAT frame — ½ + (b − ½)·z with z from the picture's
  own X-Zoom-Ratio header, never the polled zoom (roi 0.25..0.75 at 1.9 →
  0.025..0.975); a drag there PATCHes nothing and says to switch to Whole
  frame; ↻ and the re-take after a PATCH keep the view; it refreshes every
  2 s only while the zoom probe runs (`zoom.probing`); a 503 shows the
  phone's words; back to Whole frame is a base GET and drawing works 1:1;
- review of d0b65bf: back on Whole frame (the toggle, or the panel reopened
  WITHOUT a page reload) the camera picture's note, Area box and drag refusal
  are gone at once — while the base picture is still loading (the stale
  camera picture is out of sight, a drag draws nothing) and when it never
  comes (the scan screen closed: a 503); "Clear area" (the Area button, no
  longer "Full frame" beside "Whole frame") is not offered in Camera view and
  PATCHes nothing there, and a draft drawn on Whole frame survives a look at
  Camera view;
- the zoom probe (1.1.17), reported by the phone, never driven from here:
  while `zoom.probing` the zoom line reads "Zoom probe running on the phone
  — <the phone's words>. Hands off the tray." — at any rung, the switch on or
  off, and never "settling" (nor "Off — this Area would zoom") — and once it
  is over, top-level `zoom_probe` shows "Last zoom probe (31 Oct 14:03,
  1600×1200): <its words>", with the scan screen closed too; `zoom_probe`
  null or absent (an older phone app) shows no probe line at all;
- "Refocus on a card" (1.1.18, `refocus_min`): an older phone app (no
  `refocus_min`) shows no dropdown; else the dropdown lists the PHONE's
  choices in its words (`refocus_choices`), shows the phone's value, PATCHes
  {"refocus_min": <minutes>} as a number when changed, reflects the answer and
  later polls (another computer's change too, unless the dropdown has focus),
  and on a refused PATCH shows the phone's error and goes back to the phone's
  value;
- the banner reads d53 and the page throws nothing.

    pip install playwright        # the browser: Chromium, see EXE below
    python scripts/check_device_ui.py

Not part of CI (it needs a browser); run it after any edit to the panel.
"""
import glob, io, json, os, sys, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from PIL import Image, ImageDraw
from playwright.sync_api import sync_playwright

STATIC = Path(__file__).resolve().parents[1] / "server" / "static"
VERSION = "d53"

ZOOMED = {"ratio": 1.9, "target": 1.9, "limit": "the Area fit", "fit": 2.0, "lossless": 2.6, "max": 10.0, "settling": False}
UNZOOMED = {"ratio": 1.0, "target": 1.0, "limit": "Zoom to fit is off", "fit": 2.0, "lossless": 2.6, "max": 10.0, "settling": False}

# The off-centre hint (1.1.17), as the phone words it (DeviceApiTest.anOffCentreAreaIsReported):
# an Area hugging the right edge zooms 1× (its fit allows only 1.06×); centred, the same Area 1.90×.
EDGE_ROI = {"x0": 0.45, "y0": 0.25, "x1": 0.95, "y1": 0.75}
EDGE_WORDS = ("the Area's centre is 20 % right of the frame centre — centred, the same Area would zoom 1.90× "
              "(this Area: 1.00×, its fit allows only 1.06×)")
EDGE_HINT = {
    "would": {"z": 1.0, "limit": "below 1.10× (the Area fit)", "fit": 1.11},
    "off_centre": {"now": 1.0, "centred": 1.9, "dx": 0.2, "dy": 0.0,
                   "centred_roi": {"x0": 0.25, "y0": 0.25, "x1": 0.75, "y1": 0.75}, "words": EDGE_WORDS},
}
EDGE_OFF = dict(UNZOOMED, fit=1.11, **EDGE_HINT)
EDGE_ON = dict(UNZOOMED, limit="below 1.10× (the Area fit)", fit=1.11, **EDGE_HINT)

STATE = {
    "dev": None,          # set per scenario
    "patches": [],
    "snap_gets": 0,
    "cam_snap_gets": 0,   # snapshot.jpg?view=camera
    "cam_ratio": None,    # the camera picture's X-Zoom-Ratio (None: the phone's zoom.ratio)
    "cam_503": None,      # the camera view answers 503 with these words
    "base_503": None,     # the base picture answers 503 with these words (the scan screen closed)
    "base_delay": 0.0,    # seconds before the base picture is answered (a slow phone)
}
CAM_W, CAM_H = 600, 800
NO_PICTURE = "no picture within 2 s — the camera's zoom is changing; try again"
NO_CAMERA = "the camera isn't running — open the scan screen on the phone"


# "Refocus on a card" (1.1.18), as the phone sends it (DeviceApiTest.readsTheSettings).
REFOCUS_CHOICES = [{"min": 0, "label": "Every card"}, {"min": 2, "label": "Every 2 min"}, {"min": 5, "label": "Every 5 min"},
                   {"min": 10, "label": "Every 10 min"}, {"min": 30, "label": "Every 30 min"}, {"min": -1, "label": "Never"}]
REFOCUS_BAD = "refocus_min must be one of 0 (every card), 2, 5, 10, 30 or -1 (never) — minutes"


def device(zoom_fit=False, with_zoom=True, with_views=True, with_refocus=True):
    d = {"mode": "tray", "roi": {"x0": 0.25, "y0": 0.25, "x1": 0.75, "y1": 0.75}, "torch": False,
         "vibration": True, "high_res": False, "ae_lock": False, "check_ms": 2500, "camera": "auto",
         "cameras": [{"id": "0", "label": "Camera 0 · back · 13 MP · autofocus"}], "camera_live": True}
    if with_refocus:
        d["refocus_min"] = 5
        d["refocus_choices"] = json.loads(json.dumps(REFOCUS_CHOICES))
    if with_views:
        d["snapshot_views"] = ["base", "camera"]
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


def camera_png():
    # The camera view: the zoomed frame as the camera delivers it — full bleed, no grey ring.
    im = Image.new("RGB", (CAM_W, CAM_H), (150, 200, 120))
    ImageDraw.Draw(im).rectangle([CAM_W // 4, CAM_H // 4, 3 * CAM_W // 4, 3 * CAM_H // 4], outline=(40, 40, 40), width=6)
    b = io.BytesIO(); im.save(b, "PNG"); return b.getvalue()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body, ctype="application/json", headers=None):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store"); self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        u = urlsplit(self.path)
        p, q = u.path, parse_qs(u.query, keep_blank_values=True)
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
            view = q.get("view", [None])[-1]
            if view in (None, "base"):
                STATE["snap_gets"] += 1
                if STATE["base_delay"]: time.sleep(STATE["base_delay"])
                if STATE["base_503"]: return self.send(503, {"error": STATE["base_503"]})
                return self.send(200, snapshot_png(), "image/png")
            if view != "camera":
                return self.send(400, {"error": 'view must be "base" or "camera"'})
            STATE["cam_snap_gets"] += 1
            if STATE["cam_503"]: return self.send(503, {"error": STATE["cam_503"]})
            z = STATE["cam_ratio"] or (STATE["dev"].get("zoom") or {}).get("ratio") or 1.0
            return self.send(200, camera_png(), "image/png",
                             {"X-Zoom-Ratio": "%.3f" % z, "X-Frame-Size": f"{CAM_W}x{CAM_H}"})
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
                if STATE.get("edge"):
                    d["zoom"] = dict(EDGE_ON if v else EDGE_OFF, settling=True)
                else:
                    d["zoom"] = dict(ZOOMED if v else UNZOOMED, settling=True)
            elif k == "refocus_min":
                # The phone's rule (DeviceApi): one of its choices, a number; the stub can refuse on purpose.
                if STATE.get("refuse_refocus") or isinstance(v, bool) or not isinstance(v, (int, float)) \
                        or v not in [c["min"] for c in REFOCUS_CHOICES]:
                    return self.send(400, {"error": REFOCUS_BAD})
                d["refocus_min"] = int(v)
            elif k == "roi":
                d["roi"] = v
                # What /api/device reads once the phone has applied the new Area (its own hint).
                if STATE.get("roi_zoom"): d["zoom"] = STATE["roi_zoom"](v)
            else:
                d[k] = v
        # The ANSWER carries the zoom from before the change — what a busy phone (or 1.1.14
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


def reopen_panel(pg):
    # Close and open the panel again WITHOUT reloading the page: the page's own state carries over.
    pg.click("#devClose")
    pg.click("#devBtn")
    pg.wait_for_selector("#devModal.open")


with sync_playwright() as pw:
    b = pw.chromium.launch(executable_path=EXE) if EXE else pw.chromium.launch()
    # UTC: the last zoom probe's time ("31 Oct 14:03") is shown in the browser's own zone.
    ctx = b.new_context(viewport={"width": 1400, "height": 1000}, timezone_id="UTC")
    pg = ctx.new_page()
    errs = []
    pg.on("pageerror", lambda e: errs.append(str(e)))
    # The network's own console line for an answered 404 / the camera view's deliberate 503 / the refused
    # refocus_min's deliberate 400 is not a page error.
    pg.on("console", lambda m: m.type == "error" and "404" not in m.text and "status of 503" not in m.text
          and "status of 400" not in m.text and errs.append(m.text))

    print("an older phone app (no zoom_fit, no refocus_min)")
    STATE["dev"] = device(with_zoom=False, with_views=False, with_refocus=False)
    open_panel(pg)
    check(pg.locator("#dver").text_content().startswith(VERSION), f"banner reads {VERSION}")
    check(not pg.locator("#devRefocusRow").is_visible(), "no Refocus on a card dropdown")
    check(not pg.locator("#devZoomRow").is_visible(), "no zoom checkbox")
    check(not pg.locator("#devZoom").is_visible(), "no zoom line")
    check(not pg.locator("#devViewSeg").is_visible(), "no Whole frame | Camera view toggle")
    check(not pg.locator("#devProbe").is_visible(), "no zoom probe line")
    pg.click("#devClose")

    print("Refocus on a card (1.1.18)")
    STATE["dev"] = device(with_zoom=False, with_views=False)
    open_panel(pg)
    rf = pg.locator("#devRefocus")
    check(pg.locator("#devRefocusRow").is_visible(), "the Refocus on a card dropdown shows")
    opts = pg.evaluate("[...document.querySelectorAll('#devRefocus option')].map(o => [o.value, o.textContent])")
    check(opts == [[str(c["min"]), c["label"]] for c in REFOCUS_CHOICES], f"…with the phone's choices in its words: {opts}")
    check(rf.input_value() == "5", f"…on the phone's value, Every 5 min ({rf.input_value()!r})")
    n0 = len(STATE["patches"])
    rf.select_option("0")
    pg.wait_for_function("document.querySelector('#devMsg').textContent.startsWith('Saved')", timeout=4000)
    check(STATE["patches"][n0:] == [{"refocus_min": 0}], f"PATCH {{refocus_min: 0}}, a number (got {STATE['patches'][n0:]})")
    check(STATE["dev"]["refocus_min"] == 0 and rf.input_value() == "0", f"…reflected: Every card ({rf.input_value()!r})")
    rf.select_option("-1")
    pg.wait_for_function("document.querySelector('#devMsg').textContent.startsWith('Saved')", timeout=4000)
    check(STATE["patches"][-1] == {"refocus_min": -1} and rf.input_value() == "-1", f"Never → PATCH {{refocus_min: -1}} ({STATE['patches'][-1]})")
    print("…a change from elsewhere (the phone's own Settings) shows on the next poll")
    pg.evaluate("document.activeElement && document.activeElement.blur()")
    STATE["dev"]["refocus_min"] = 30
    pg.wait_for_function("document.querySelector('#devRefocus').value === '30'", timeout=8000)
    check(rf.input_value() == "30", "the poll moved it to Every 30 min")
    print("…a refused PATCH shows the phone's words and goes back to its value")
    STATE["refuse_refocus"] = True
    n0 = len(STATE["patches"])
    rf.select_option("2")
    pg.wait_for_function("document.querySelector('#devMsg').classList.contains('err')", timeout=4000)
    check(pg.locator("#devMsg").inner_text() == REFOCUS_BAD, f"the phone's error: {pg.locator('#devMsg').inner_text()!r}")
    check(STATE["patches"][n0:] == [{"refocus_min": 2}], f"the PATCH it refused ({STATE['patches'][n0:]})")
    check(rf.input_value() == "30" and STATE["dev"]["refocus_min"] == 30, f"back to the phone's value ({rf.input_value()!r})")
    STATE["refuse_refocus"] = False
    pg.click("#devClose")

    print("a 1.1.16 phone app (zoom, no snapshot_views), zoomed")
    STATE["dev"] = device(zoom_fit=True, with_views=False)
    open_panel(pg)
    check(not pg.locator("#devViewSeg").is_visible(), "no Whole frame | Camera view toggle")
    check(pg.locator("#devZoomNote").is_visible() and "grey ring" in pg.locator("#devZoomNote").inner_text(),
          "the zoomed whole-frame note as before")
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

    def fracs(sel):
        g = pg.locator(sel).bounding_box(); s = pg.locator("#devSnap").bounding_box()
        if not g or not s or not pg.locator(sel).is_visible(): return (-1.0, -1.0, -1.0, -1.0)   # not shown
        return ((g["x"] - s["x"]) / s["width"], (g["y"] - s["y"]) / s["height"],
                (g["x"] + g["width"] - s["x"]) / s["width"], (g["y"] + g["height"] - s["y"]) / s["height"])

    def near(a, b, tol=0.01):
        return all(abs(x - y) <= tol for x, y in zip(a, b))

    def active_view():
        return pg.evaluate("[...document.querySelectorAll('#devViewSeg [data-view]')]"
                           ".filter(b => !b.classList.contains('ghost')).map(b => b.dataset.view).join(',')")

    def drag(f0, f1):
        bb = pg.locator("#devSnap").bounding_box()
        pg.mouse.move(bb["x"] + bb["width"] * f0[0], bb["y"] + bb["height"] * f0[1])
        pg.mouse.down()
        pg.mouse.move(bb["x"] + bb["width"] * 0.5, bb["y"] + bb["height"] * 0.5, steps=4)
        pg.mouse.move(bb["x"] + bb["width"] * f1[0], bb["y"] + bb["height"] * f1[1], steps=4)
        pg.mouse.up()

    def wait_count(key, n, timeout_s):
        end = time.time() + timeout_s
        while time.time() < end and STATE[key] < n: pg.wait_for_timeout(100)
        return STATE[key] >= n

    BASE_NOTE = pg.locator("#devZoomNote").inner_text()
    CAM_NOTE = "Camera view at 1.90× — what the scanner sees (600×800 px)"

    print("Camera view (1.1.17)")
    check(pg.locator("#devViewSeg").is_visible(), "the Whole frame | Camera view toggle shows")
    check(active_view() == "base", f"…on Whole frame ({active_view()!r})")
    cam0, base0, n0 = STATE["cam_snap_gets"], STATE["snap_gets"], len(STATE["patches"])
    pg.click("#devViewSeg [data-view=camera]")
    pg.wait_for_function("document.querySelector('#devZoomNote').textContent.startsWith('Camera view at')", timeout=4000)
    check(STATE["cam_snap_gets"] == cam0 + 1 and STATE["snap_gets"] == base0,
          f"a view=camera GET, no base one ({STATE['cam_snap_gets'] - cam0} / {STATE['snap_gets'] - base0})")
    check(active_view() == "camera", "Camera view is the active button")
    px = pg.evaluate("""() => { const i = document.querySelector('#devSnap'), c = document.createElement('canvas');
        c.width = i.naturalWidth; c.height = i.naturalHeight; const x = c.getContext('2d'); x.drawImage(i, 0, 0);
        return Array.from(x.getImageData(2, 2, 1, 1).data).slice(0, 3); }""")
    check(px != [96, 96, 96] and abs(px[0] - 150) <= 2, f"the camera's own picture, no grey ring (corner {px})")
    note = pg.locator("#devZoomNote").inner_text() if pg.locator("#devZoomNote").is_visible() else ""
    check(note == CAM_NOTE, f"the note: {note!r}")
    rf = fracs("#devRoi")
    check(near(rf, (0.025, 0.025, 0.975, 0.975)), f"the Area where it sits in the 1.90× frame: {tuple(round(v, 4) for v in rf)}")
    check(not pg.locator("#devRoiCentred").is_visible(), "no off-centre ghost in the camera view")
    drag((0.30, 0.30), (0.70, 0.70))
    pg.wait_for_timeout(300)
    check(len(STATE["patches"]) == n0, "a drag on the camera view PATCHes nothing")
    msg = pg.locator("#devMsg").inner_text()
    check("Switch to Whole frame to draw the Area" in msg and "zoomed (1.90×)" in msg, f"…and says why: {msg!r}")
    check(not pg.locator("#devRoiSave").is_visible() and pg.evaluate("!document.querySelector('#devRoi').classList.contains('draft')"),
          "…no draft, no Save")
    check(near(fracs("#devRoi"), (0.025, 0.025, 0.975, 0.975)), "…the Area box untouched")

    print("Camera view: the picture's own zoom, never the polled one; ↻ keeps the view")
    STATE["cam_ratio"] = 1.5                                       # the frame says 1.5× while the poll says 1.9×
    c1 = STATE["cam_snap_gets"]
    pg.click("#devShot")
    pg.wait_for_function("document.querySelector('#devZoomNote').textContent.startsWith('Camera view at 1.50×')", timeout=4000)
    check(STATE["cam_snap_gets"] == c1 + 1 and STATE["snap_gets"] == base0, "↻ took a camera picture again, no base one")
    rf = fracs("#devRoi")
    check(near(rf, (0.125, 0.125, 0.875, 0.875)), f"the Area mapped at the header's 1.50×: {tuple(round(v, 4) for v in rf)}")
    STATE["cam_ratio"] = None
    pg.click("#devShot")
    pg.wait_for_function(f"document.querySelector('#devZoomNote').textContent === {json.dumps(CAM_NOTE)}", timeout=4000)

    print("Camera view: the re-take after a PATCH keeps the view")
    c2, b2 = STATE["cam_snap_gets"], STATE["snap_gets"]
    pg.locator('#devCtl input[data-k="high_res"]').check()
    check(wait_count("cam_snap_gets", c2 + 1, 4), f"a camera picture after the PATCH ({STATE['cam_snap_gets'] - c2})")
    pg.wait_for_timeout(300)
    check(STATE["snap_gets"] == b2, "…and no base one")
    pg.locator('#devCtl input[data-k="high_res"]').uncheck()
    wait_count("cam_snap_gets", c2 + 2, 4)
    pg.wait_for_timeout(300)

    print("Camera view: refreshed every 2 s while the zoom probe runs, only then")
    c3 = STATE["cam_snap_gets"]
    pg.wait_for_timeout(4500)
    check(STATE["cam_snap_gets"] == c3, f"no refresh without the probe ({STATE['cam_snap_gets'] - c3})")
    STATE["dev"]["zoom"]["probing"] = True
    check(wait_count("cam_snap_gets", c3 + 2, 10), f"refreshed while probing ({STATE['cam_snap_gets'] - c3} in ≤ 10 s)")
    STATE["dev"]["zoom"]["probing"] = False
    pg.wait_for_timeout(3600)                                      # the panel's next poll reads it
    c4 = STATE["cam_snap_gets"]
    pg.wait_for_timeout(4500)
    check(STATE["cam_snap_gets"] <= c4 + 1, f"…and stops once it ends ({STATE['cam_snap_gets'] - c4})")
    check(STATE["snap_gets"] == b2, "never a base picture meanwhile")

    print("Camera view: a 503 shows the phone's words")
    STATE["cam_503"] = NO_PICTURE
    pg.click("#devShot")
    pg.wait_for_selector("#devNoCam", state="visible", timeout=4000)
    t = pg.locator("#devNoCam").inner_text()
    check(NO_PICTURE in t, f"the 503's error in place of the picture: {t!r}")
    check(not pg.locator("#devStage").is_visible() and not pg.locator("#devZoomNote").is_visible(), "…no picture, no note")
    STATE["cam_503"] = None
    pg.click("#devShot")
    pg.wait_for_selector("#devStage", state="visible", timeout=4000)
    check(not pg.locator("#devNoCam").is_visible() and pg.locator("#devZoomNote").inner_text() == CAM_NOTE, "↻ again: the picture is back")

    print("Camera view: the Area button is not offered there (review of d0b65bf)")
    check(pg.locator("#devRoiClear").inner_text() == "Clear area",
          f"the Area button says what it does, not 'Full frame' beside 'Whole frame': {pg.locator('#devRoiClear').inner_text()!r}")
    check(not pg.locator("#devRoiClear").is_visible(), "no Clear area in Camera view")
    n1 = len(STATE["patches"])
    pg.evaluate("document.querySelector('#devRoiClear').click()")   # even if it were reached
    pg.wait_for_timeout(400)
    check(len(STATE["patches"]) == n1, f"…and it PATCHes nothing there ({STATE['patches'][n1:]})")
    check(near(fracs("#devRoi"), (0.025, 0.025, 0.975, 0.975)), "…the Area box untouched")

    print("back to Whole frame")
    b5 = STATE["snap_gets"]
    pg.click("#devViewSeg [data-view=base]")
    pg.wait_for_function(f"document.querySelector('#devZoomNote').textContent === {json.dumps(BASE_NOTE)}", timeout=4000)
    pg.wait_for_selector("#devSnap", state="visible", timeout=4000)
    pg.wait_for_selector("#devRoi", state="visible", timeout=4000)
    check(STATE["snap_gets"] == b5 + 1, "a base GET")
    check(active_view() == "base", "Whole frame is the active button")
    rf = fracs("#devRoi")
    check(near(rf, (0.25, 0.25, 0.75, 0.75)), f"the Area at its base fractions again: {tuple(round(v, 4) for v in rf)}")
    px = pg.evaluate("""() => { const i = document.querySelector('#devSnap'), c = document.createElement('canvas');
        c.width = i.naturalWidth; c.height = i.naturalHeight; const x = c.getContext('2d'); x.drawImage(i, 0, 0);
        return Array.from(x.getImageData(2, 2, 1, 1).data).slice(0, 3); }""")
    check(px == [96, 96, 96], f"the base picture's grey ring (corner {px})")
    check(pg.locator("#devRoiClear").is_visible(), "Clear area is offered again on Whole frame")
    pg.evaluate("document.querySelector('#devMsg').textContent = ''")

    print("a draft drawn on Whole frame survives a look at Camera view")
    n2 = len(STATE["patches"])
    drag((0.20, 0.30), (0.60, 0.80))
    check(pg.locator("#devRoiSave").is_visible(), "a draft: Save shows")
    pg.click("#devViewSeg [data-view=camera]")
    pg.wait_for_function("document.querySelector('#devZoomNote').textContent.startsWith('Camera view at')", timeout=4000)
    check(not pg.locator("#devRoiSave").is_visible() and not pg.locator("#devRoiClear").is_visible(),
          "Camera view: no Save, no Clear area")
    pg.evaluate("document.querySelector('#devRoiClear').click()")
    pg.wait_for_timeout(300)
    pg.click("#devViewSeg [data-view=base]")
    pg.wait_for_selector("#devRoi", state="visible", timeout=4000)
    check(len(STATE["patches"]) == n2, f"nothing PATCHed ({STATE['patches'][n2:]})")
    check(pg.locator("#devRoiSave").is_visible() and near(fracs("#devRoi"), (0.20, 0.30, 0.60, 0.80)),
          f"back on Whole frame the draft is still there: {tuple(round(v, 4) for v in fracs('#devRoi'))}")
    pg.evaluate("document.querySelector('#devRoiUndo').click()")
    pg.evaluate("document.querySelector('#devMsg').textContent = ''")

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
    check(not pg.locator("#devRoiCentred").is_visible(), "no off-centre ghost without the phone's off_centre")
    print("the panel opens on Whole frame, whatever it was left on")
    pg.click("#devViewSeg [data-view=camera]")
    pg.wait_for_function("document.querySelector('#devZoomNote').textContent.startsWith('Camera view at')", timeout=4000)
    b6, c6 = STATE["snap_gets"], STATE["cam_snap_gets"]
    reopen_panel(pg)                                               # no page reload: the page's state carries over
    pg.wait_for_selector("#devSnap", state="visible", timeout=4000)
    pg.wait_for_timeout(300)
    check(active_view() == "base", f"Whole frame is active on reopening ({active_view()!r})")
    check(STATE["snap_gets"] == b6 + 1 and STATE["cam_snap_gets"] == c6, "…a base GET, no camera one")
    pg.click("#devClose")

    # Review of d0b65bf: what the picture shows (devPic) followed only a base picture that LOADED,
    # so back on Whole frame the camera picture's note, its view-space Area box and its drag refusal
    # stayed while the base picture loaded — and for good when it never came (the screen closed).
    def zoomed_camera_view():
        STATE["dev"] = device(zoom_fit=True)
        STATE["base_503"], STATE["base_delay"] = None, 0.0
        open_panel(pg)
        pg.click("#devViewSeg [data-view=camera]")
        pg.wait_for_function(f"document.querySelector('#devZoomNote').textContent === {json.dumps(CAM_NOTE)}", timeout=4000)

    def note_now():
        return pg.locator("#devZoomNote").inner_text() if pg.locator("#devZoomNote").is_visible() else ""

    print("reopened after Camera view, the base picture slow (1.5 s)")
    zoomed_camera_view()
    STATE["base_delay"] = 1.5
    n3 = len(STATE["patches"])
    reopen_panel(pg)
    pg.wait_for_timeout(300)                                       # the base picture is on its way
    check(note_now() != CAM_NOTE and not note_now().startswith("Camera view"),
          f"while it loads: not the camera picture's note ({note_now()!r})")
    check(not pg.locator("#devSnap").is_visible(), "…the stale camera picture is out of sight")
    check(not pg.locator("#devRoi").is_visible(), "…no Area box drawn on it")
    pg.evaluate("document.querySelector('#devMsg').textContent = ''")
    st = pg.locator("#devStage").bounding_box()
    if st:
        pg.mouse.move(st["x"] + st["width"] * 0.3, st["y"] + st["height"] * 0.3); pg.mouse.down()
        pg.mouse.move(st["x"] + st["width"] * 0.7, st["y"] + st["height"] * 0.7, steps=4); pg.mouse.up()
    msg = pg.locator("#devMsg").inner_text()
    check("Switch to Whole frame" not in msg, f"…a drag is not refused as if on Camera view: {msg!r}")
    check(not pg.locator("#devRoiSave").is_visible(), "…and draws no draft on the stale picture")
    pg.wait_for_selector("#devRoi", state="visible", timeout=4000)
    check(note_now() == BASE_NOTE, f"once loaded: the whole frame's note ({note_now()!r})")
    check(near(fracs("#devRoi"), (0.25, 0.25, 0.75, 0.75)), f"…the Area at its base fractions: {tuple(round(v, 4) for v in fracs('#devRoi'))}")
    check(len(STATE["patches"]) == n3, "nothing PATCHed")
    STATE["base_delay"] = 0.0
    pg.click("#devClose")

    print("reopened after Camera view, the scan screen closed (the base picture 503s)")
    zoomed_camera_view()
    pg.click("#devClose")
    STATE["dev"]["camera_live"] = False; STATE["dev"]["zoom"] = None
    STATE["base_503"] = NO_CAMERA
    pg.click("#devBtn"); pg.wait_for_selector("#devModal.open")
    pg.wait_for_selector("#devNoCam", state="visible", timeout=4000)
    pg.wait_for_timeout(1500)
    check(active_view() == "base", f"Whole frame is active ({active_view()!r})")
    check(note_now() == "", f"no camera-view note — nothing zoomed to speak of ({note_now()!r})")
    check("Open the scan screen on the phone" in pg.locator("#devNoCam").inner_text(), "…the closed-screen words")
    check(not pg.locator("#devRoi").is_visible(), "…no Area box")
    pg.wait_for_timeout(3500)                                      # a poll in between changes nothing
    check(note_now() == "", f"…still none after a poll ({note_now()!r})")
    pg.click("#devClose")

    print("Camera view, then the scan screen closes and Whole frame is clicked")
    zoomed_camera_view()
    STATE["dev"]["camera_live"] = False; STATE["dev"]["zoom"] = None
    STATE["base_503"] = NO_CAMERA
    pg.click("#devViewSeg [data-view=base]")
    pg.wait_for_selector("#devNoCam", state="visible", timeout=4000)
    pg.wait_for_timeout(500)
    check(note_now() == "", f"no camera-view note ({note_now()!r})")
    check("Open the scan screen on the phone" in pg.locator("#devNoCam").inner_text(), "…the closed-screen words")
    STATE["base_503"] = None
    pg.click("#devClose")

    def ghost_fracs():
        g = pg.locator("#devRoiCentred").bounding_box(); s = pg.locator("#devSnap").bounding_box()
        return ((g["x"] - s["x"]) / s["width"], (g["y"] - s["y"]) / s["height"],
                (g["x"] + g["width"] - s["x"]) / s["width"], (g["y"] + g["height"] - s["y"]) / s["height"])

    print("the off-centre hint: zoom off, `would` null or absent")
    STATE["dev"] = device(zoom_fit=False)
    STATE["dev"]["roi"] = dict(EDGE_ROI)
    STATE["dev"]["zoom"] = dict(EDGE_OFF, would=None, off_centre=None)
    open_panel(pg)
    check(not pg.locator("#devZoom").is_visible(), "`would` null: the line stays hidden at 1× as before")
    check(not pg.locator("#devRoiCentred").is_visible(), "…and no ghost")
    pg.click("#devClose")

    print("the off-centre hint: zoom off, a centred Area (would, no off_centre)")
    STATE["dev"] = device(zoom_fit=False)
    STATE["dev"]["zoom"] = dict(UNZOOMED, would={"z": 1.9, "limit": "the Area fit", "fit": 2.0}, off_centre=None)
    open_panel(pg)
    line = pg.locator("#devZoom").inner_text() if pg.locator("#devZoom").is_visible() else ""
    check(line == "Off — this Area would zoom 1.90× (the Area fit)", f"the line says what this Area would zoom: {line!r}")
    check("Off-centre" not in line and not pg.locator("#devRoiCentred").is_visible(), "no off-centre line or ghost for a centred Area")
    pg.click("#devClose")

    print("the off-centre hint: zoom off, an Area hugging the right edge")
    STATE["dev"] = device(zoom_fit=False)
    STATE["dev"]["roi"] = dict(EDGE_ROI)
    STATE["dev"]["zoom"] = dict(EDGE_OFF)
    STATE["edge"] = True
    open_panel(pg)
    pg.wait_for_selector("#devRoiCentred", state="visible")
    line = pg.locator("#devZoom").inner_text() if pg.locator("#devZoom").is_visible() else ""
    check("Off — this Area would zoom 1.00× — below 1.10× (the Area fit)" in line, f"the would line: {line!r}")
    check(f"Off-centre: {EDGE_WORDS}" in line, f"…and the phone's off-centre words: {line!r}")
    check("Zoom to fit is off" not in line, "never the stale-text literal")
    gf = ghost_fracs()
    check(all(abs(a - b) <= 0.01 for a, b in zip(gf, (0.25, 0.25, 0.75, 0.75))),
          f"the ghost box sits where the same Area would be centred: {tuple(round(v, 4) for v in gf)}")
    check("centred: 1.90×" in pg.locator("#devRoiCentred").inner_text(), "…labelled with what it would zoom")
    rb = pg.locator("#devRoi").bounding_box(); sb = pg.locator("#devSnap").bounding_box()
    check(abs((rb["x"] - sb["x"]) / sb["width"] - 0.45) <= 0.01, "the Area itself stays where it is drawn")

    print("an Area drawn on the phone with the switch off: neither the line nor the ghost")
    STATE["dev"]["zoom"]["drawing"] = True
    pg.wait_for_function("document.querySelector('#devZoom').style.display === 'none'", timeout=8000)
    check(not pg.locator("#devRoiCentred").is_visible(), "no ghost while the phone draws a new Area")
    STATE["dev"]["zoom"]["drawing"] = False
    pg.wait_for_selector("#devRoiCentred", state="visible", timeout=8000)
    check("Off-centre: " in pg.locator("#devZoom").inner_text(), "…both back once it is drawn")

    print("draw a new Area: the ghost goes while drafting")
    box = pg.locator("#devSnap").bounding_box()
    pg.mouse.move(box["x"] + box["width"] * 0.2, box["y"] + box["height"] * 0.2)
    pg.mouse.down()
    pg.mouse.move(box["x"] + box["width"] * 0.6, box["y"] + box["height"] * 0.6, steps=4)
    check(not pg.locator("#devRoiCentred").is_visible(), "no ghost while a new Area is drawn here")
    pg.mouse.up()
    pg.click("#devRoiUndo")
    check(pg.locator("#devRoiCentred").is_visible(), "Cancel: the ghost is back")

    print("tick the box with an off-centre Area")
    pg.evaluate("""() => { window.__zl = []; const el = document.querySelector('#devZoom');
        new MutationObserver(() => window.__zl.push(el.style.display === 'none' ? '' : el.textContent))
          .observe(el, {childList: true, subtree: true, characterData: true, attributes: true}); }""")
    pg.locator('#devZoomRow input[data-k="zoom_fit"]').check()
    pg.wait_for_function("document.querySelector('#devZoom').textContent.includes('Area fit 1.11×')", timeout=3000)
    shown = pg.evaluate("window.__zl")
    check(any("applying" in t for t in shown), f"the stale answer showed as applying: {shown}")
    check(not any("Zoom to fit is off" in t for t in shown), f"never 'Zoom to fit is off' after ticking it: {shown}")
    line = pg.locator("#devZoom").inner_text()
    check("Off-centre: " + EDGE_WORDS in line and "below 1.10× (the Area fit)" in line,
          f"switched on, the Area still zooms 1× and the hint stays: {line!r}")
    check(pg.locator("#devRoiCentred").is_visible(), "the ghost is back once the phone answered")
    STATE["edge"] = False

    # Review of 39ef7cf: with the switch OFF at 1×, a camera-change PATCH skipped "applying…", so
    # the stale answer's hint for the OLD Area was drawn beside the NEW Area (and after "Full
    # frame", the ghost stayed with no Area at all) until the 600 ms re-read.
    def zoom_for(roi):
        # The phone's own zoom once it applied a new Area, the switch off.
        if roi is None:
            return dict(UNZOOMED, fit=1.0, would={"z": 1.0, "limit": "no scan Area — the full frame is scanned", "fit": 1.0},
                        off_centre=None)
        return dict(UNZOOMED, fit=2.5, would={"z": 2.0, "limit": "MAX_ZOOM", "fit": 2.5}, off_centre=None)

    def watch_line_and_ghost():
        # Every zoom-line text and every ghost transition (sampled each frame), with the time.
        pg.evaluate("""() => { window.__zl = []; window.__gl = []; let last = null;
            const el = document.querySelector('#devZoom'), g = document.querySelector('#devRoiCentred');
            new MutationObserver(() => window.__zl.push(el.style.display === 'none' ? '' : el.textContent))
              .observe(el, {childList: true, subtree: true, characterData: true, attributes: true});
            (function f(){ const v = getComputedStyle(g).display !== 'none' ? 'shown ' + g.style.left : 'hidden';
              if (v !== last) { window.__gl.push([performance.now(), v]); last = v; } requestAnimationFrame(f); })(); }""")

    def stale(texts):
        return [t for t in texts if "would zoom 1.00×" in t or "Off-centre" in t]

    def edge_panel():
        STATE["dev"] = device(zoom_fit=False)
        STATE["dev"]["roi"] = dict(EDGE_ROI)
        STATE["dev"]["zoom"] = dict(EDGE_OFF)
        STATE["roi_zoom"] = zoom_for
        open_panel(pg)
        pg.wait_for_selector("#devRoiCentred", state="visible")
        watch_line_and_ghost()

    print("the switch off, an off-centre Area: Clear area with a stale answer")
    edge_panel()
    pg.evaluate("window.__zl = []")
    t0 = pg.evaluate("performance.now()")
    pg.click("#devRoiClear")
    pg.wait_for_timeout(250)                                       # the answer is in, the re-read is not
    check(STATE["patches"][-1] == {"roi": None}, f"PATCH {{roi: null}} (got {STATE['patches'][-1]})")
    check(not pg.locator("#devRoiCentred").is_visible(), "no ghost once the answer is in (no Area, the hint not re-read)")
    pg.wait_for_timeout(1000)                                      # …and after the re-read
    zl, gl = pg.evaluate("window.__zl"), [g for g in pg.evaluate("window.__gl") if g[0] >= t0]
    check(not stale(zl), f"the old Area's words never shown: {zl}")
    hid = [i for i, g in enumerate(gl) if g[1] == "hidden"]
    check(bool(hid) and all(g[1] == "hidden" for g in gl[hid[0]:]), f"the ghost went and never came back: {gl}")
    check(not pg.locator("#devZoom").is_visible() and not pg.locator("#devRoiCentred").is_visible(),
          "read again: no Area, no line and no ghost")
    pg.click("#devClose")

    print("the switch off, an off-centre Area: Save a centred one with a stale answer")
    edge_panel()
    box = pg.locator("#devSnap").bounding_box()
    pg.mouse.move(box["x"] + box["width"] * 0.30, box["y"] + box["height"] * 0.30)
    pg.mouse.down()
    pg.mouse.move(box["x"] + box["width"] * 0.70, box["y"] + box["height"] * 0.70, steps=4)
    pg.mouse.up()
    check(not pg.locator("#devRoiCentred").is_visible(), "no ghost while the new Area is a draft")
    pg.evaluate("window.__zl = []")
    t0 = pg.evaluate("performance.now()")
    pg.click("#devRoiSave")
    pg.wait_for_timeout(250)
    roi = STATE["patches"][-1].get("roi")
    check(roi is not None and abs(roi["x0"] - 0.30) < 0.01 and abs(roi["x1"] - 0.70) < 0.01, f"PATCH the new Area: {roi}")
    check(not pg.locator("#devRoiCentred").is_visible(), "no ghost at the OLD Area's centred spot once the answer is in")
    pg.wait_for_function("document.querySelector('#devZoom').textContent.includes('would zoom 2.00×')", timeout=3000)
    pg.wait_for_timeout(300)
    zl, gl = pg.evaluate("window.__zl"), [g for g in pg.evaluate("window.__gl") if g[0] >= t0]
    check(not stale(zl), f"the old Area's would / off-centre words never shown beside the new Area: {zl}")
    check(any("applying" in t for t in zl), f"…the stale answer showed as applying: {zl}")
    check(all(g[1] == "hidden" for g in gl), f"the ghost never showed after Save: {gl}")
    line = pg.locator("#devZoom").inner_text()
    check(line == "Off — this Area would zoom 2.00× (MAX_ZOOM)", f"read again: what the NEW Area would zoom: {line!r}")
    pg.click("#devClose")

    print("the switch off, an off-centre Area: High resolution goes through applying too")
    edge_panel()
    pg.evaluate("window.__zl = []")
    pg.locator('#devCtl input[data-k="high_res"]').check()
    pg.wait_for_timeout(1200)
    zl = pg.evaluate("window.__zl")
    check(STATE["patches"][-1] == {"high_res": True}, f"PATCH {{high_res: true}} (got {STATE['patches'][-1]})")
    check(any("applying" in t for t in zl), f"a lens-side change with the switch off shows applying: {zl}")
    check("Off-centre: " + EDGE_WORDS in pg.locator("#devZoom").inner_text(), "…then the phone's own hint, read again")
    check(pg.locator("#devRoiCentred").is_visible(), "…and its ghost")
    STATE["roi_zoom"] = None

    # The zoom probe (1.1.17): reported by the phone (DeviceApiTest.theProbeIsReportedReadOnly), never driven here.
    # The phone stores what the runs FOUND — no "Next" / "Run again" off the phone (MeasureSession.lastProbe, ZoomProbe.Combined.findings).
    LAST_WORDS = "run 1 valid (one more card needed)"
    LAST = {"at": 1793455380000, "camera": "0", "stream": "1600×1200", "run": 1, "runs": 1, "valid_cards": 1,
            "words": LAST_WORDS,
            "probe_half": "✗ 1 valid run(s) on different cards (needs 2) ✓ centred ✓ settle · knee: not measured in this build (MAX_ZOOM stays 2.00×)"}
    LAST_LINE = f"Last zoom probe (31 Oct 14:03, 1600×1200): {LAST_WORDS}"

    def probe_line():
        return pg.locator("#devProbe").inner_text() if pg.locator("#devProbe").is_visible() else ""

    print("the zoom probe running on the phone, the switch off")
    STATE["dev"] = device(zoom_fit=False)
    STATE["dev"]["zoom"] = dict(UNZOOMED, ratio=1.6, probing=True, probe="rung 7/15 · 1.60× · card 979 px at 1.50×",
                                would={"z": 1.9, "limit": "the Area fit", "fit": 2.0}, off_centre=None)
    STATE["dev"]["zoom_probe"] = None
    open_panel(pg)
    pg.evaluate("""() => { window.__zl = []; const el = document.querySelector('#devZoom');
        new MutationObserver(() => window.__zl.push(el.style.display === 'none' ? '' : el.textContent))
          .observe(el, {childList: true, subtree: true, characterData: true, attributes: true}); }""")
    line = pg.locator("#devZoom").inner_text() if pg.locator("#devZoom").is_visible() else ""
    check(line == "Zoom probe running on the phone — rung 7/15 · 1.60× · card 979 px at 1.50×. Hands off the tray.",
          f"the zoom line says the probe runs, in the phone's words: {line!r}")
    check(probe_line() == "", f"no last-probe line while one runs: {probe_line()!r}")
    print("…at a 1.00× rung: still the probe, not what the Area would zoom")
    STATE["dev"]["zoom"].update(ratio=1.0, probe="rung 9/15 · 1.00× · card 612 px at 1.00×")
    pg.wait_for_function("document.querySelector('#devZoom').textContent.includes('rung 9/15')", timeout=8000)
    line = pg.locator("#devZoom").inner_text()
    check(line.startswith("Zoom probe running on the phone — rung 9/15") and "would zoom" not in line, f"{line!r}")
    print("…the switch on, the phone saying settling (it never does while probing): never 'settling'")
    STATE["dev"]["zoom_fit"] = True
    STATE["dev"]["zoom"] = dict(ZOOMED, ratio=2.2, settling=True, probing=True, probe="rung 12/15 · 2.20× · card 1349 px at 2.00×")
    pg.wait_for_function("document.querySelector('#devZoom').textContent.includes('rung 12/15')", timeout=8000)
    shown = pg.evaluate("window.__zl")
    check(not any("settling" in t for t in shown), f"never 'settling' while the probe runs: {shown}")
    check(not any("Off — this Area would zoom" in t for t in shown), f"never the switch-off line while it runs: {shown}")
    print("…it ends: the last probe's verdict")
    STATE["dev"]["zoom_fit"] = False
    STATE["dev"]["zoom"] = dict(UNZOOMED, would={"z": 1.9, "limit": "the Area fit", "fit": 2.0}, off_centre=None, probing=False, probe=None)
    STATE["dev"]["zoom_probe"] = dict(LAST)
    pg.wait_for_selector("#devProbe", state="visible", timeout=8000)
    check(probe_line() == LAST_LINE, f"the last probe, when, which stream and its words: {probe_line()!r}")
    line = pg.locator("#devZoom").inner_text() if pg.locator("#devZoom").is_visible() else ""
    check("Zoom probe running" not in line and line.startswith("Off — this Area would zoom 1.90×"), f"the zoom line is the Area's again: {line!r}")
    check(not any("settling" in t for t in pg.evaluate("window.__zl") if "Zoom probe" in t), "the probe line never said settling")
    pg.click("#devClose")

    print("the last zoom probe with the scan screen closed")
    STATE["dev"] = device(zoom_fit=False)
    STATE["dev"]["camera_live"] = False; STATE["dev"]["zoom"] = None
    STATE["dev"]["zoom_probe"] = dict(LAST)
    STATE["base_503"] = NO_CAMERA
    pg.goto(BASE + "/")
    pg.wait_for_function(f"document.querySelector('#dver').textContent.startsWith('{VERSION}')")
    pg.wait_for_selector("#devBtn", state="visible")
    pg.click("#devBtn"); pg.wait_for_selector("#devModal.open")
    pg.wait_for_selector("#devProbe", state="visible", timeout=4000)
    check(probe_line() == LAST_LINE, f"there with no scan screen: {probe_line()!r}")
    check(not pg.locator("#devZoom").is_visible(), "…and no zoom line (no camera)")
    STATE["base_503"] = None
    pg.click("#devClose")

    print("zoom_probe null: no probe line")
    STATE["dev"] = device(zoom_fit=False)
    STATE["dev"]["zoom_probe"] = None
    open_panel(pg)
    pg.wait_for_timeout(300)
    check(probe_line() == "", f"never run: no line ({probe_line()!r})")
    pg.click("#devClose")

    check(not errs, f"no page errors: {errs}")
    b.close()

print("FAILED: %d" % len(fails) if fails else "all checks passed")
sys.exit(1 if fails else 0)
