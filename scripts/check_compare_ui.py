"""Drive the REAL desktop.html / phone.html in headless Chromium against a stub
phone server, and check the compare views show JUST THE CARD (CLAUDE.md →
"Just the card, on BOTH pages") and the desktop's compare block behaves like
the phone's: modes, ‹ › / ← →, thumbnails, Scan size, pick, live price,
re-render keeping the compared printing and the scroll, guest.

    pip install playwright        # the browser: Chromium, see CHROME below
    python scripts/check_compare_ui.py [--shots DIR]

Not part of CI (it needs a browser); run it after any edit to the compare
views of either page.

Every synthetic scan photo paints the tray pure magenta, so "tight" is
measured, not eyeballed: the share of magenta pixels inside each rendered
photo box must be ~0 for the known layouts (664x926 ScanPhoto, 914/882/852
flattened uploads) and clearly >0 for a raw crop (shown as it is).
"""
import glob, io, json, os, re, sys, threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from playwright.sync_api import sync_playwright

STATIC = Path(__file__).resolve().parents[1] / "server" / "static"
SHOTS = Path(sys.argv[sys.argv.index("--shots") + 1]) if "--shots" in sys.argv else None
MAGENTA = (255, 0, 255)

# kind -> (photo w, h, card box x0, y0, x1, y1)
LAYOUTS = {
    "scanphoto": (664, 926, 17, 23, 647, 903),
    "flat08": (914, 1276, 63, 88, 851, 1188),
    "flat06": (882, 1232, 47, 66, 835, 1166),
    "flat04": (852, 1188, 32, 44, 820, 1144),
    "raw": (1000, 1300, 200, 260, 800, 1100),
}


def photo_png(kind):
    w, h, x0, y0, x1, y1 = LAYOUTS[kind]
    im = Image.new("RGB", (w, h), MAGENTA)
    d = ImageDraw.Draw(im)
    d.rectangle([x0, y0, x1 - 1, y1 - 1], fill=(20, 20, 24))            # black border
    bx, by = (x1 - x0) * 0.05, (y1 - y0) * 0.04
    d.rectangle([x0 + bx, y0 + by, x1 - bx, y1 - by * 2], fill=(230, 225, 210))  # white frame
    d.rectangle([x0 + bx * 1.6, y0 + by * 2.5, x1 - bx * 1.6, y0 + (y1 - y0) * 0.55], fill=(60, 90, 160))  # art
    b = io.BytesIO(); im.save(b, "PNG"); return b.getvalue()


def print_png(color):
    im = Image.new("RGB", (488, 680), color)
    ImageDraw.Draw(im).rectangle([20, 20, 467, 659], outline=(255, 255, 255), width=6)
    b = io.BytesIO(); im.save(b, "PNG"); return b.getvalue()


def cand(cid, setc, num, dist, color, **kw):
    return dict(id=cid, name="Test Card", set=setc, set_name=f"Set {setc.upper()}", collector_number=str(num),
                rarity="common", finishes=["nonfoil"], multi_distance=dist, phash_distance=dist,
                image_normal=f"/print/{color}.png", image_small=f"/print/{color}.png", **kw)


def make_scans():
    def scan(i, kind, cands, sel=None):
        return dict(id=i, status="selected" if sel else "pending", identified=bool(cands),
                    card_read={"name": "Test Card"}, confidence="high", candidates=cands,
                    selection=sel, error=None, f2f=None, included=True, flagged=False,
                    created_at="2026-10-03T00:00:00", photo=kind)
    c3 = [cand("a1", "aaa", 1, 90, "red"), cand("a2", "bbb", 2, 120, "green"), cand("a3", "ccc", 3, 150, "blue")]
    sel_b = dict(c3[1], scryfall_id="a2", condition="NM", finish="Non-Foil", quantity=1, auto_picked=False)
    return {
        1: scan(1, "raw", [cand("r1", "rrr", 1, 95, "red"), cand("r2", "sss", 2, 130, "green")]),
        2: scan(2, "flat04", []),
        3: scan(3, "flat06", [cand("f1", "fff", 1, 90, "red"), cand("f2", "ggg", 2, 125, "green")]),
        4: scan(4, "flat08", [dict(c) for c in c3], sel=sel_b),
        5: scan(5, "scanphoto", [dict(c) for c in c3]),
    }


STATE = {"role": "admin", "scans": make_scans(), "selects": []}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store"); self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def public(self, s):
        return {k: v for k, v in s.items() if k != "photo"}

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/": return self.send(200, (STATIC / "desktop.html").read_bytes(), "text/html")
        if p == "/phone": return self.send(200, (STATIC / "phone.html").read_bytes(), "text/html")
        if p.startswith("/print/"):
            return self.send(200, print_png({"red": (170, 40, 40), "green": (40, 140, 60), "blue": (40, 60, 170)}[p[7:-4]]), "image/png")
        if p == "/api/me": return self.send(200, {"role": STATE["role"]})
        if p == "/api/version": return self.send(200, {"version": "stub"})
        if p == "/api/price-status": return self.send(200, {"active": False, "done": 0, "total": 0, "cooldown_s": 0})
        if p == "/api/health": return self.send(200, {"ocr": {"available": True}})
        if p == "/api/update-check": return self.send(200, {"update_available": False})
        if p == "/api/setup/status": return self.send(200, {"index_built": True})
        if p == "/api/scans":
            return self.send(200, [self.public(s) for s in sorted(STATE["scans"].values(), key=lambda s: -s["id"])])
        m = re.fullmatch(r"/api/scans/(\d+)/image", p)
        if m: return self.send(200, photo_png(STATE["scans"][int(m.group(1))]["photo"]), "image/png")
        m = re.fullmatch(r"/api/scans/(\d+)", p)
        if m: return self.send(200, self.public(STATE["scans"][int(m.group(1))]))
        return self.send(404, {"error": "stub: no such route"})

    def do_POST(self):
        p = self.path.split("?")[0]
        n = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(n) or b"{}")
        m = re.fullmatch(r"/api/scans/(\d+)/select", p)
        if m:
            s = STATE["scans"][int(m.group(1))]
            pr = body["printing"]
            STATE["selects"].append((s["id"], pr["id"]))
            s["selection"] = dict(pr, scryfall_id=pr["id"], condition=body.get("condition", "NM"),
                                  finish=body.get("finish", "Non-Foil"), quantity=body.get("quantity", 1))
            s["status"] = "selected"
            return self.send(200, self.public(s))
        return self.send(404, {"error": "stub"})

    def do_PATCH(self):
        return self.send(200, {})


srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{srv.server_port}"
# A Claude Code cloud session has Chromium under /opt/pw-browsers; elsewhere set
# CHROME, or leave both unset for Playwright's own browser.
EXE = os.environ.get("CHROME") or next(iter(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome")), None)

fails = []
def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond: fails.append(msg)


def magenta_share(page, locator):
    png = locator.screenshot()
    a = np.asarray(Image.open(io.BytesIO(png)).convert("RGB")).astype(int)
    a = a[3:-3, 3:-3]                     # skip the rounded-corner / antialias rim
    m = (a[..., 0] > 200) & (a[..., 1] < 60) & (a[..., 2] > 200)
    return float(m.mean())


TIGHT = {"scanphoto", "flat08", "flat06", "flat04"}

with sync_playwright() as pw:
    b = pw.chromium.launch(executable_path=EXE) if EXE else pw.chromium.launch()

    # ── desktop ──────────────────────────────────────────────────────────
    print("desktop.html")
    ctx = b.new_context(viewport={"width": 1600, "height": 1000})
    pg = ctx.new_page()
    errs = []
    pg.on("pageerror", lambda e: errs.append(str(e)))
    pg.on("console", lambda m: m.type == "error" and "404" not in m.text and errs.append(m.text))
    pg.goto(BASE + "/")
    pg.wait_for_selector("#dver")
    pg.wait_for_function("document.querySelector('#dver').textContent.startsWith('d41')")
    check(pg.locator("#dver").text_content().startswith("d41"), "banner reads d41")

    def focus(i):
        pg.locator(f'.row[data-id="{i}"]').click()
        pg.wait_for_timeout(400)

    for sid, s in STATE["scans"].items():
        focus(sid)
        kind = s["photo"]
        shot = magenta_share(pg, pg.locator("#scanShot"))
        if kind in TIGHT:
            check(shot < 0.003, f"scan {sid} ({kind}): top thumbnail shows just the card (tray {shot:.2%})")
        else:
            check(shot > 0.05, f"scan {sid} ({kind}): raw photo shown as it is (tray {shot:.2%})")
        if not s["candidates"]:
            check(pg.locator("#cmp").count() == 0, f"scan {sid}: no candidates → no compare block")
            continue
        box = pg.locator("#cmp .cmpbody.side .cmpbox").first
        share = magenta_share(pg, box)
        if kind in TIGHT:
            check(share < 0.003, f"scan {sid} ({kind}): compare shows just the card (tray {share:.2%})")
        else:
            check(share > 0.05, f"scan {sid} ({kind}): raw crop in compare shown as it is (tray {share:.2%})")

    # interactions on scan 5 (pending, 3 printings)
    focus(5)
    head = lambda: pg.locator("#cmp .cmphead").inner_text()
    check("1 of 3" in head() and "AAA #1" in head(), "starts at the closest printing (1 of 3)")
    pg.locator("#cmpNext").click(); check("2 of 3" in head(), "› steps to 2 of 3")
    check(pg.locator('#cands .card.viewing').get_attribute("data-pick") == "a2", "thumbnail of the compared printing is highlighted")
    pg.keyboard.press("ArrowRight"); check("3 of 3" in head(), "→ key steps to 3 of 3")
    pg.keyboard.press("ArrowRight"); check("1 of 3" in head(), "→ wraps to 1 of 3")
    pg.keyboard.press("ArrowLeft"); check("3 of 3" in head(), "← wraps back to 3 of 3")
    pg.locator('#cands .card[data-pick="a2"] img').click(); check("2 of 3" in head(), "clicking a thumbnail loads it")
    check(pg.locator("#cmpPick").inner_text().strip() == "Pick BBB #2", "Pick button names the compared printing")

    # modes
    pg.locator('[data-cmpmode="wipe"]').click()
    check(pg.locator("#cmp .cmpbody.overlay").count() == 1, "Wipe overlays the two")
    top = pg.locator("#cmp .cmpbox img.over")
    cp = top.evaluate("e => e.style.clipPath")
    check("cmpscan" in top.get_attribute("class") and "50%" in cp, f"wipe: scan on top, divider at 50% ({cp})")
    pg.locator("#cmpSlider").fill("20")
    check("80%" in pg.locator("#cmp .cmpbox img.over").evaluate("e => e.style.clipPath"), "wipe slider moves the divider")
    check(pg.locator("#cmpOverCap").inner_text() == "scan over print", "overlay caption")
    pg.locator("#cmpSwap").click()
    check("cmpprint" in pg.locator("#cmp .cmpbox img.over").get_attribute("class"), "swap puts the printing on top")
    check(pg.evaluate("localStorage.getItem('cmpTop')") == "print", "top layer remembered")
    pg.locator('[data-cmpmode="blend"]').click()
    check(pg.locator("#cmp .cmpbox img.over").evaluate("e => e.style.opacity") == "0.2", "blend: top layer opacity follows the slider")
    check(pg.evaluate("localStorage.getItem('cmpMode')") == "blend", "mode remembered")
    share = magenta_share(pg, pg.locator("#cmp .cmpbox").first)
    check(share < 0.003, f"blend overlay: scan still just the card (tray {share:.2%})")
    pg.locator('[data-cmpmode="side"]').click()
    check(pg.locator("#cmp .cmpbody.side").count() == 1, "back to side by side")
    check("2 of 3" in head(), "mode changes keep the compared printing")

    # scan size
    pg.locator("#cmpScale").fill("110")
    tf = pg.locator("#cmp img.cmpscan").evaluate("e => e.style.transform")
    check(tf == "scale(1.1583)", f"Scan size 110% × 1.053 fill → {tf}")
    check(pg.locator("#cmpScaleReset").inner_text().strip() == "110%", "reset button shows 110%")
    pg.reload(); pg.wait_for_selector("#cmp"); focus(5)
    check(pg.locator("#cmpScale").input_value() == "110", "Scan size remembered across a reload")
    check(pg.locator("#cmp .cmpbody.side").count() == 1, "mode remembered across a reload (side)")
    pg.wait_for_function("document.querySelector('#cmp img.cmpscan').dataset.fill")
    pg.locator("#cmpScaleReset").click()
    tf = pg.locator("#cmp img.cmpscan").evaluate("e => e.style.transform")
    check(abs(float(tf[6:-1]) - 1.053) < 1e-4, f"reset → 100% (just the fill): {tf}")

    # a re-render of the same scan (a price lands) keeps the compared printing and the scroll
    pg.locator("#cmpNext").click(); pg.locator("#cmpNext").click()
    check("3 of 3" in head(), "on 3 of 3 before the price lands")
    pg.locator("#right").evaluate("e => e.scrollTop = 300")
    y0 = pg.locator("#right").evaluate("e => e.scrollTop")
    STATE["scans"][5]["candidates"][2]["f2f_conditions"] = {"NM": 4.25}
    STATE["scans"][5]["candidates"][0]["f2f_conditions"] = {"NM": 1.0}
    pg.wait_for_timeout(2600)
    check("$4.25 NM" in head(), f"the compared printing's price appears live ({head()!r})")
    check("3 of 3" in head(), "a re-render keeps the compared printing")
    y1 = pg.locator("#right").evaluate("e => e.scrollTop")
    check(abs(y1 - y0) <= 2 and y0 > 0, f"a re-render keeps the scroll ({y0} → {y1})")

    # pick from the compare + double-click a thumbnail
    pg.locator("#cmpPick").click(); pg.wait_for_timeout(700)
    check(STATE["selects"][-1] == (5, "a3"), f"Pick files the compared printing ({STATE['selects'][-1]})")
    check("✓ picked" in head() and pg.locator("#cmpPick").inner_text().startswith("Keep"), "picked: ✓ picked + Keep")
    pg.locator('#cands .card[data-pick="a1"]').dblclick(); pg.wait_for_timeout(700)
    check(STATE["selects"][-1] == (5, "a1"), "double-click on a thumbnail picks it")

    # selected scan opens on its pick
    focus(4)
    check("2 of 3" in head() and "✓ picked" in head(), "a picked scan opens on its pick")

    # ⇆ full-screen compare: the scan side is just the card too
    pg.locator('#cands .card[data-pick="a1"]').hover()
    pg.locator('#cands .card[data-pick="a1"] .cmp').click(); pg.wait_for_timeout(400)
    share = magenta_share(pg, pg.locator("#lbDuo .lbscan"))
    check(share < 0.003, f"⇆ compare: scan side just the card (tray {share:.2%})")
    pg.keyboard.press("Escape")
    # click to enlarge = the whole photo
    pg.locator("#scanShot").click(); pg.wait_for_timeout(400)
    share = magenta_share(pg, pg.locator("#lightboxImg"))
    check(share > 0.05, f"click to enlarge shows the WHOLE photo (tray {share:.2%})")
    pg.keyboard.press("Escape")

    # layout: no horizontal overflow at a smaller window; both boxes side by side
    pg.set_viewport_size({"width": 1280, "height": 720}); pg.wait_for_timeout(300)
    boxes = pg.locator("#cmp .cmpbody.side .cmpbox")
    bb = [boxes.nth(i).bounding_box() for i in range(2)]
    check(abs(bb[0]["y"] - bb[1]["y"]) < 1 and bb[0]["height"] <= 720 * 0.66, f"1280×720: two boxes side by side, {bb[0]['height']:.0f}px tall")
    ov = pg.locator("#right").evaluate("e => e.scrollWidth - e.clientWidth")
    check(ov <= 0, f"1280×720: no horizontal overflow in the right pane ({ov}px)")
    if SHOTS: SHOTS.mkdir(parents=True, exist_ok=True); pg.screenshot(path=str(SHOTS / "desktop_compare.png"))
    check(not errs, f"no page errors ({errs[:3]})")
    ctx.close()

    # guest
    STATE["role"] = "guest"
    ctx = b.new_context(viewport={"width": 1600, "height": 1000})
    pg = ctx.new_page(); errs2 = []
    pg.on("pageerror", lambda e: errs2.append(str(e)))
    pg.goto(BASE + "/"); pg.wait_for_selector("#cmp"); pg.wait_for_timeout(500)
    check(pg.evaluate("document.body.classList.contains('guest')"), "guest page")
    check(pg.locator("#cmpPick").is_visible(), "a guest can still compare and pick")
    check(not errs2, f"guest: no page errors ({errs2[:3]})")
    ctx.close()
    STATE["role"] = "admin"

    # ── phone ────────────────────────────────────────────────────────────
    print("phone.html")
    STATE["scans"] = make_scans()
    ctx = b.new_context(viewport={"width": 411, "height": 914}, device_scale_factor=2, is_mobile=True, has_touch=True)
    pg = ctx.new_page(); errs3 = []
    pg.on("pageerror", lambda e: errs3.append(str(e)))
    pg.goto(BASE + "/phone?panel=1"); pg.wait_for_timeout(1200)
    check(pg.evaluate("UI_VERSION") == "v49", "phone banner v49")
    for sid, s in STATE["scans"].items():
        pg.evaluate(f"openDetail({sid})"); pg.wait_for_timeout(500)
        kind = s["photo"]
        sel = "#cmp .cmpbody.side .cmpbox" if s["candidates"] else "#cmp-bare .cmpbox"
        share = magenta_share(pg, pg.locator(sel).first)
        if kind in TIGHT:
            check(share < 0.003, f"phone scan {sid} ({kind}): just the card (tray {share:.2%})")
        else:
            check(share > 0.05, f"phone scan {sid} ({kind}): raw crop shown as it is (tray {share:.2%})")
        pg.evaluate("closeDetail()"); pg.wait_for_timeout(200)
    pg.evaluate("openDetail(3)"); pg.wait_for_timeout(500)
    pg.locator("#cmp-scale").fill("90")
    tf = pg.locator("#cmp img.cmpscan").evaluate("e => e.style.transform")
    check(tf == f"scale({882/788*0.9:.4f})", f"phone: Scan size 90% × 1.119 fill → {tf}")
    if SHOTS: SHOTS.mkdir(parents=True, exist_ok=True); pg.screenshot(path=str(SHOTS / "phone_compare.png"))
    check(not errs3, f"phone: no page errors ({errs3[:3]})")
    ctx.close()
    b.close()

srv.shutdown()
print(f"\n{'ALL PASSED' if not fails else f'{len(fails)} FAILED'}")
sys.exit(1 if fails else 0)
