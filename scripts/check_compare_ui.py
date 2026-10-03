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
from PIL import Image, ImageDraw, ImageFilter
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
# Photos as the phone really leaves them: the finder's quad a little OUTSIDE the card,
# so the card sits INSIDE the layout rect (by `inset` px), blurred, on a grey tray —
# kind -> (w, h, true card x0, y0, x1, y1, tray, border, blur px)
REAL = {
    "real_black": (664, 926, 29, 36, 635, 890, (186, 186, 182), (20, 20, 24), 1.6),
    "real_white": (664, 926, 26, 33, 638, 893, (186, 186, 182), (240, 238, 232), 1.2),
    # a dark ring around a white card could be the card's own black border (the finder on
    # its INNER edge): ambiguous, so the edge finder keeps it loose — never a cut
    "real_white_dark": (664, 926, 26, 33, 638, 893, (60, 62, 66), (232, 230, 225), 1.2),
    "real_flat": (914, 1276, 78, 104, 836, 1172, (190, 190, 186), (20, 20, 24), 2.0),
}


def photo_png(kind):
    if kind in REAL:
        w, h, x0, y0, x1, y1, tray, border, blur = REAL[kind]
        im = Image.new("RGB", (w, h), tray)
        d = ImageDraw.Draw(im)
        d.rectangle([x0, y0, x1 - 1, y1 - 1], fill=border)
        bx, by = (x1 - x0) * 0.05, (y1 - y0) * 0.04
        d.rectangle([x0 + bx, y0 + by, x1 - bx, y1 - by * 2], fill=(200, 170, 120))     # frame
        d.rectangle([x0 + bx * 1.6, y0 + by * 2.5, x1 - bx * 1.6, y0 + (y1 - y0) * 0.55], fill=(60, 90, 160))
        for k in range(6):                                                                # text lines
            yy = y0 + (y1 - y0) * (0.62 + k * 0.045)
            d.rectangle([x0 + bx * 2, yy, x1 - bx * 3, yy + 6], fill=(40, 40, 40))
        im = im.filter(ImageFilter.GaussianBlur(blur))
        b = io.BytesIO(); im.save(b, "JPEG", quality=90); return b.getvalue()
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
        6: scan(6, "real_black", [cand("b1", "bbb", 1, 90, "red"), cand("b2", "ccc", 2, 130, "green")]),
        7: scan(7, "real_white", [cand("w1", "www", 1, 90, "red"), cand("w2", "xxx", 2, 130, "green")]),
        8: scan(8, "real_flat", [cand("p1", "ppp", 1, 90, "red"), cand("p2", "qqq", 2, 130, "green")]),
        9: scan(9, "real_white_dark", [cand("d1", "ddd", 1, 90, "red"), cand("d2", "eee", 2, 130, "green")]),
    }


STATE = {"role": "admin", "scans": make_scans(), "selects": [], "img_gets": {}}


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
        if p == "/api/search":
            other = cand("s1", "zzz", 9, 0, "blue"); other["name"] = "Other Card"
            return self.send(200, {"candidates": [other]})
        m = re.fullmatch(r"/api/scans/(\d+)/image", p)
        if m:
            sid = int(m.group(1)); STATE["img_gets"][sid] = STATE["img_gets"].get(sid, 0) + 1
            return self.send(200, photo_png(STATE["scans"][sid]["photo"]), "image/png")
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


def edge_errors_mm(page, locator, kind):
    """Where the TRUE card edges land in the rendered box, mm of the 63x88 card:
    + = inside the box edge (tray shows), - = beyond it (card cut). From the img's own
    transform: translate(tx%, ty%) scale(sx, sy) about the box centre, the photo drawn
    object-fit: contain."""
    w, h, x0, y0, x1, y1 = REAL[kind][:6]
    g = locator.evaluate("""el => { const img = el.querySelector('img'), b = el.getBoundingClientRect();
        return {W: b.width, H: b.height, tf: img.style.transform}; }""")
    m = re.fullmatch(r"translate\(([-\d.]+)%, ([-\d.]+)%\) scale\(([\d.]+), ([\d.]+)\)", g["tf"])
    assert m, g["tf"]
    tx, ty, sx, sy = map(float, m.groups())
    W, H = g["W"], g["H"]
    r = min(W / w, H / h); ox, oy = (W - w * r) / 2, (H - h * r) / 2
    X = lambda x: W / 2 + tx / 100 * W + sx * (ox + r * x - W / 2)
    Y = lambda y: H / 2 + ty / 100 * H + sy * (oy + r * y - H / 2)
    mmx, mmy = (X(x1) - X(x0)) / 63, (Y(y1) - Y(y0)) / 88
    return [X(x0) / mmx, Y(y0) / mmy, (W - X(x1)) / mmx, (H - Y(y1)) / mmy]

def edge_block(name):
    t = (STATIC / name).read_text()
    return t[t.index("/* ═══ card edges ═══"):t.index("/* ═══ end card edges ═══ */")]
check(edge_block("phone.html") == edge_block("desktop.html"), "the card-edges block is identical in both pages")

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
    pg.wait_for_function("document.querySelector('#dver').textContent.startsWith('d44')")
    check(pg.locator("#dver").text_content().startswith("d44"), "banner reads d44")

    def focus(i):
        pg.locator(f'.row[data-id="{i}"]').click()
        pg.wait_for_timeout(400)

    for sid, s in STATE["scans"].items():
        if s["photo"] in REAL: continue       # measured geometrically below
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

    # photos as the phone leaves them: the card INSIDE the layout rect, blurred → cropped to ITS edges
    for sid in (6, 7, 8, 9):
        focus(sid)
        kind = STATE["scans"][sid]["photo"]
        for where, loc in (("compare", pg.locator("#cmp .cmpbody.side .cmpbox").first), ("thumbnail", pg.locator("#scanShot"))):
            e = edge_errors_mm(pg, loc, kind)
            if kind == "real_white_dark":
                check(all(v >= -0.5 for v in e), f"scan {sid} ({kind}) {where}: ambiguous ring kept loose, never cut, mm {[round(v, 2) for v in e]}")
            else:
                check(all(-0.5 <= v <= 0.25 for v in e), f"scan {sid} ({kind}) {where}: edges at the box edge, mm L/T/R/B {[round(v, 2) for v in e]}")

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
    scl = lambda: [float(v) for v in re.search(r"scale\(([\d.]+), ([\d.]+)\)", pg.locator("#cmp img.cmpscan").evaluate("e => e.style.transform")).groups()]
    base = scl()
    check(all(1.03 < v < 1.08 for v in base), f"cropped to the card's edges: scale {base}")
    pg.locator("#cmpScale").fill("110")
    tf = scl()
    check(all(abs(t / b - 1.1) < 1e-3 for t, b in zip(tf, base)), f"Scan size 110% × the crop → {tf}")
    check(pg.locator("#cmpScaleReset").inner_text().strip() == "110%", "reset button shows 110%")
    pg.reload(); pg.wait_for_selector("#cmp"); focus(5)
    check(pg.locator("#cmpScale").input_value() == "110", "Scan size remembered across a reload")
    check(pg.locator("#cmp .cmpbody.side").count() == 1, "mode remembered across a reload (side)")
    pg.wait_for_function("document.querySelector('#cmp img.cmpscan').dataset.fill")
    pg.locator("#cmpScaleReset").click()
    tf = scl()
    check(all(abs(t - b) < 1e-3 for t, b in zip(tf, base)), f"reset → 100% (just the crop): {tf}")

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

    # a pick made OUTSIDE the compare block (the ⇆ chip's lightbox) moves the compare to it
    focus(5)
    check("AAA #1" in head() and "✓ picked" in head(), "scan 5 opens on its pick (AAA #1)")
    pg.locator('#cands .card[data-pick="a2"]').hover()
    pg.locator('#cands .card[data-pick="a2"] .cmp').click(); pg.wait_for_timeout(300)
    pg.locator("#lbPickBtn").click(); pg.wait_for_timeout(900)
    check(STATE["selects"][-1] == (5, "a2"), "⇆ lightbox pick filed a2")
    check("BBB #2" in head() and "✓ picked" in head() and pg.locator("#cmpPick").inner_text().startswith("Keep"),
          f"after a ⇆ pick the compare shows the pick, not the old printing ({head()[:60]!r})")

    # stepping / mode changes never re-download the photo (the phone serves it no-store)
    n0 = STATE["img_gets"].get(5, 0)
    for _ in range(4):
        pg.locator("#cmpNext").click()
        ok = pg.locator("#cmp img.cmpscan").evaluate("e => e.complete && e.naturalWidth > 0 && !!e.style.transform")
        if not ok: break
    check(ok, "right after a step the scan photo is already loaded and fitted (no black, no whole-photo frame)")
    pg.locator('[data-cmpmode="wipe"]').click(); pg.locator("#cmpSwap").click(); pg.locator('[data-cmpmode="side"]').click()
    pg.keyboard.press("ArrowRight"); pg.wait_for_timeout(300)
    check(STATE["img_gets"].get(5, 0) == n0, f"4 steps + 3 mode changes + a key: no photo re-download ({STATE['img_gets'].get(5, 0) - n0})")
    fr = pg.evaluate("""async () => { renderDetail(); await new Promise(r => requestAnimationFrame(r));
        return [document.querySelector('#cmp img.cmpscan').style.transform, document.querySelector('#scanShot img').dataset.fill]; }""")
    check(bool(fr[0]) and bool(fr[1]), f"the frame after a full re-render already shows just the card ({fr})")
    check(STATE["img_gets"].get(5, 0) == n0, "a full re-render doesn't re-download the photo either")

    # another scan — even one with no printings — and back: starts again at the pick
    pg.locator("#cmpNext").click()
    focus(2); focus(5)
    check("BBB #2" in head() and "✓ picked" in head(), f"5 → 2 (no printings) → 5 reopens on the pick ({head()[:40]!r})")

    # a price landing elsewhere refreshes the head in place, never wiping a typed search
    focus(3)
    check("…" in head(), "pending printing not searched yet: …")
    pg.locator("#msearch").fill("typed")
    STATE["scans"][3]["candidates"][0]["f2f_conditions"] = {}
    STATE["scans"][3]["candidates"][1]["f2f_conditions"] = {}
    pg.wait_for_timeout(3300)
    check("no listing" in head(), f"a searched-no-listing result shows in place ({head()[:60]!r})")
    check(pg.locator("#msearch").input_value() == "typed", "…and the typed name search survives")

    # a name-search pick (another card) goes back to the top, where it now shows
    focus(4)
    pg.locator("#msearch").fill("other"); pg.locator("#msearch").press("Enter"); pg.wait_for_timeout(500)
    pg.locator("#right").evaluate("e => e.scrollTop = e.scrollHeight")
    pg.locator('#msearchResults .card[data-pick="s1"] img').click(); pg.wait_for_timeout(900)
    check(STATE["selects"][-1] == (4, "s1"), "search result picked")
    check(pg.locator("#right").evaluate("e => e.scrollTop") == 0 and "Other Card" in pg.locator(".dname").inner_text(),
          "a name-search pick returns to the top showing the new card")

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

    # site storage blocked: the page still works (the compare just doesn't remember)
    ctx = b.new_context(viewport={"width": 1600, "height": 1000})
    ctx.add_init_script("Object.defineProperty(window, 'localStorage', { get(){ throw new DOMException('blocked', 'SecurityError'); } });")
    pg = ctx.new_page(); errs4 = []
    pg.on("pageerror", lambda e: errs4.append(str(e)))
    pg.goto(BASE + "/"); pg.wait_for_timeout(1500)
    check(pg.locator(".row").count() == len(STATE["scans"]) and pg.locator("#cmp").count() == 1, "storage blocked: the list and the compare still render")
    pg.locator('[data-cmpmode="blend"]').click()
    check(pg.locator("#cmp .cmpbody.overlay").count() == 1, "storage blocked: the modes still work")
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
    want = "v51"; check(pg.evaluate("UI_VERSION") == want, f"phone banner {want}")
    for sid, s in STATE["scans"].items():
        if s["photo"] in REAL: continue       # measured geometrically below
        pg.evaluate(f"openDetail({sid})"); pg.wait_for_timeout(500)
        kind = s["photo"]
        sel = "#cmp .cmpbody.side .cmpbox" if s["candidates"] else "#cmp-bare .cmpbox"
        share = magenta_share(pg, pg.locator(sel).first)
        if kind in TIGHT:
            check(share < 0.003, f"phone scan {sid} ({kind}): just the card (tray {share:.2%})")
        else:
            check(share > 0.05, f"phone scan {sid} ({kind}): raw crop shown as it is (tray {share:.2%})")
        pg.evaluate("closeDetail()"); pg.wait_for_timeout(200)
    for sid in (6, 7, 8, 9):
        pg.evaluate(f"openDetail({sid})"); pg.wait_for_timeout(600)
        e = edge_errors_mm(pg, pg.locator("#cmp .cmpbody.side .cmpbox").first, STATE["scans"][sid]["photo"])
        lo, hi = (-0.5, 99) if STATE["scans"][sid]["photo"] == "real_white_dark" else (-0.5, 0.25)
        check(all(lo <= v <= hi for v in e), f"phone scan {sid}: edges at the box edge (loose allowed only when ambiguous), mm L/T/R/B {[round(v, 2) for v in e]}")
        pg.evaluate("closeDetail()"); pg.wait_for_timeout(200)
    pg.evaluate("openDetail(3)"); pg.wait_for_timeout(500)
    pg.locator("#cmp-scale").fill("90")
    tf = [float(v) for v in re.search(r"scale\(([\d.]+), ([\d.]+)\)", pg.locator("#cmp img.cmpscan").evaluate("e => e.style.transform")).groups()]
    check(all(abs(v - 882 / 788 * 0.9) < 0.02 for v in tf), f"phone: Scan size 90% × the crop → {tf}")
    if SHOTS: SHOTS.mkdir(parents=True, exist_ok=True); pg.screenshot(path=str(SHOTS / "phone_compare.png"))
    check(not errs3, f"phone: no page errors ({errs3[:3]})")
    ctx.close()
    b.close()

srv.shutdown()
print(f"\n{'ALL PASSED' if not fails else f'{len(fails)} FAILED'}")
sys.exit(1 if fails else 0)
