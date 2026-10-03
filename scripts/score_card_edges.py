"""Measure the review pages' "crop to the card's edge" (the card-edges block in
phone.html / desktop.html) against photos whose TRUE card is known.

    pip install playwright        # Chromium: /opt/pw-browsers in a cloud session, or set CHROME
    python scripts/score_card_edges.py [--heldout] [--detail]

Each photo is made by the reference chain, exactly as the phone files it: a
tests/card_scenes card face rendered on a tray (blur, sensor noise, frame JPEG) →
card_detect.find_card_quad on the scan Area → tests/phone_flatten_ref flatten →
JPEG q85 upload → card_detect.scan_photo → JPEG q90 (or, for an older scan, the
flattened upload kept as filed). The true card corners are carried through every
homography into the photo. The page's own findCardEdges then runs in headless
Chromium on the decoded photo, and each crop side's error is reported in mm of the
63x88 card: + = inside the card (card cut), - = outside (tray shows). The baseline
is the layout rect itself (the fixed zoom before 1.1.10).

Photos where find_card_quad locked onto something else (white cards on a light
tray, mostly) are an upstream miss, not a display question: counted, not scored.
Not part of CI (it needs a browser); run it after any change to the block.
"""
import functools, glob, http.server, io, itertools, json, os, statistics, sys, threading
from collections import defaultdict
from multiprocessing import Pool
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from mtg_card_scanner import card_detect as cd          # noqa: E402
from tests import card_scenes as cs                     # noqa: E402
from tests import phone_flatten_ref as pf               # noqa: E402

FW, FH = 1200, 1600              # the STANDARD analysis frame, upright portrait
SS = 2                           # supersampled render: anti-aliased card edges
MM = np.float32([[0, 0], [63, 0], [63, 88], [0, 88]])
TEX_UP = 4                       # the 315x440 card face, x4 (nearest: crisp print)
BORDERS = {"black": (18, 18, 18), "white": (235, 235, 235)}
SEEDS = {"black": 11, "white": 12}
TRAYS = {"light": (190, 190, 190), "dark": (40, 42, 48)}
PHOTO_DST = np.float32([[cd.PHOTO_MARGIN_X, cd.PHOTO_MARGIN_Y], [cd.PHOTO_MARGIN_X + cd.CARD_W, cd.PHOTO_MARGIN_Y],
                        [cd.PHOTO_MARGIN_X + cd.CARD_W, cd.PHOTO_MARGIN_Y + cd.CARD_H],
                        [cd.PHOTO_MARGIN_X, cd.PHOTO_MARGIN_Y + cd.CARD_H]])


def jpeg(img, q):
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, q]); assert ok
    return cv2.imdecode(buf, cv2.IMREAD_COLOR)


def apply(M, pts):
    return cv2.perspectiveTransform(np.asarray(pts, np.float64).reshape(-1, 1, 2), M).reshape(-1, 2)


_TEX = {}
def texture(border):
    if border not in _TEX:
        t = cs.card_texture(SEEDS[border], BORDERS[border])
        _TEX[border] = cv2.resize(t, (t.shape[1] * TEX_UP, t.shape[0] * TEX_UP), interpolation=cv2.INTER_NEAREST)
    return _TEX[border]


def _paste(canvas, img, H):
    h, w = img.shape[:2]
    c = cv2.perspectiveTransform(np.float32([[[0, 0]], [[w, 0]], [[w, h]], [[0, h]]]), H).reshape(4, 2)
    x0, y0 = np.maximum(np.floor(c.min(0)).astype(int) - 2, 0)
    x1, y1 = np.minimum(np.ceil(c.max(0)).astype(int) + 2, [canvas.shape[1], canvas.shape[0]])
    Hs = np.array([[1, 0, -x0], [0, 1, -y0], [0, 0, 1]], np.float64) @ H
    warped = cv2.warpPerspective(img, Hs, (x1 - x0, y1 - y0), flags=cv2.INTER_LINEAR)
    mask = cv2.warpPerspective(np.full((h, w), 255, np.uint8), Hs, (x1 - x0, y1 - y0), flags=cv2.INTER_NEAREST)
    region = canvas[y0:y1, x0:x1]; region[mask > 0] = warped[mask > 0]


def true_quad(card_h, pos):
    cw = card_h * 63 / 88
    cx = FW / 2 + 17 if pos == "centre" else 0.05 * cw + 6 + cw / 2
    return cs._card_quad(cx, FH / 2 - 23, card_h, 3.0, 0.02)


def render(card_h, blur, border, tray, sleeve, pos, seed):
    tq = true_quad(card_h, pos)
    canvas = np.empty((FH * SS, FW * SS, 3), np.uint8)
    tray_col = TRAYS[tray] if isinstance(tray, str) else (tray, tray, tray)
    canvas[:] = tray_col
    if sleeve:          # a clear sleeve: a band 1.5 mm round the card, a little brighter than the tray
        Hmm = cv2.getPerspectiveTransform(MM, tq * SS)
        sq = cv2.perspectiveTransform(np.float32([[[-1.5, -1.5]], [[64.5, -1.5]], [[64.5, 89.5]], [[-1.5, 89.5]]]), Hmm).reshape(4, 2)
        cv2.fillConvexPoly(canvas, np.round(sq * 16).astype(np.int32), tuple(int(min(255, c + sleeve)) for c in tray_col),
                           lineType=cv2.LINE_AA, shift=4)
    tex = texture(border); th, tw = tex.shape[:2]
    _paste(canvas, tex, cv2.getPerspectiveTransform(np.float32([[0, 0], [tw, 0], [tw, th], [0, th]]), tq * SS))
    frame = cv2.resize(canvas, (FW, FH), interpolation=cv2.INTER_AREA)
    if blur > 0:
        frame = cv2.GaussianBlur(frame, (0, 0), blur)
    rng = np.random.default_rng(1000 + seed)
    frame = np.clip(frame.astype(np.float32) + rng.normal(0, 2.0, frame.shape), 0, 255).astype(np.uint8)
    return jpeg(frame, 85), tq


def make_photo(args):
    """One stored photo + its true card corners in photo px (None if the chain filed no layout photo)."""
    seed, (card_h, blur, border, tray, sleeve, pos, kind) = args
    frame, tq = render(card_h, blur, border, tray, sleeve, pos, seed)
    x0, y0 = tq.min(0); x1, y1 = tq.max(0); cw, ch = x1 - x0, y1 - y0      # the scan Area: the card + 15 %
    ax0, ay0 = int(max(0, np.floor(x0 - .15 * cw))), int(max(0, np.floor(y0 - .15 * ch)))
    ax1, ay1 = int(min(FW, np.ceil(x1 + .15 * cw))), int(min(FH, np.ceil(y1 + .15 * ch)))
    q1 = cd.find_card_quad(frame[ay0:ay1, ax0:ax1])
    if q1 is None:
        return None
    qf = q1 + np.float32([ax0, ay0])
    m = pf.choose_margin(qf, FW, FH)
    if m is None:
        return None
    up = jpeg(pf.phone_flatten(frame, qf, m), 85)
    true_up = apply(pf._transform(qf, m).astype(np.float64), tq)
    if kind == "flat":
        photo, pts = up, true_up
    else:
        photo, det = cd.scan_photo([up])
        if not det:
            return None
        q2 = cd.find_card_quad(up)
        pts = apply(cv2.getPerspectiveTransform(q2.astype(np.float32), PHOTO_DST).astype(np.float64), true_up)
        photo = jpeg(photo, 90)
    ok, buf = cv2.imencode(".png", photo); assert ok
    return dict(png=buf.tobytes(), w=photo.shape[1], h=photo.shape[0], truth=pts.tolist(), kind=kind, border=border,
                tray=tray if isinstance(tray, str) else f"grey{tray}", sleeve=sleeve, blur=blur, card_h=card_h)


def tuning():
    out = []
    for h, b, bo, tr, sl in itertools.product([300, 450, 700, 1000], [0, 1.5, 3], BORDERS, TRAYS, [0, 20]):
        out.append((h, b, bo, tr, sl, "centre", "photo"))
    for h, b, g in itertools.product([450, 1000], [0, 3], [90, 120, 150]):
        out.append((h, b, "black", g, 0, "centre", "photo"))
    for h, b, sl in itertools.product([450, 700], [0, 2], [0, 20]):
        out.append((h, b, "black", "light", sl, "edge", "photo"))
    for h, b, bo in itertools.product([450, 1000], [0, 3], BORDERS):
        out.append((h, b, bo, "light", 45, "centre", "photo"))
    for h, b, bo, sl in itertools.product([300, 450, 700, 1000], [0, 3], BORDERS, [0, 20]):
        out.append((h, b, bo, "light", sl, "centre", "flat"))
    for h, b in itertools.product([450, 700], [0, 2]):
        out.append((h, b, "black", "light", 0, "edge", "flat"))
    return list(enumerate(out))


def heldout():
    out = []
    for h, b, bo, tr, sl in itertools.product([380, 600, 850], [0.7, 2.2], BORDERS, [170, 205, 60], [0, 30]):
        out.append((h, b, bo, tr, sl, "centre", "photo"))
    for h, b, bo, sl in itertools.product([380, 850], [0.7, 2.2], BORDERS, [0, 30]):
        out.append((h, b, bo, 205, sl, "centre", "flat"))
    return [(500 + i, c) for i, c in enumerate(out)]


def layout(_):
    """The card-edges block exactly as phone.html ships it (desktop.html carries the same)."""
    t = (ROOT / "server/static/phone.html").read_text()
    return t[t.index("/* ═══ card edges ═══"):t.index("/* ═══ end card edges ═══ */")]


def edges(it):
    (tlx, tly), (trx, try_), (brx, bry), (blx, bly) = it["truth"]
    return (tlx + blx) / 2, (tly + try_) / 2, (trx + brx) / 2, (bly + bry) / 2


def errors(it, crop, exp):
    L, T, R, B = edges(it)
    sx, sy = (R - L) / 63, (B - T) / 88
    L, T, R, B = max(L, 0), max(T, 0), min(R, it["w"]), min(B, it["h"])     # what the photo can show
    x0, y0, x1, y1 = (crop["x0"], crop["y0"], crop["x1"], crop["y1"]) if crop else exp
    return [(x0 - L) / sx, (y0 - T) / sy, (R - x1) / sx, (B - y1) / sy]


def summary(name, rows):
    es = [v for r in rows for v in r["e"]]; a = sorted(abs(v) for v in es)
    return (f"{name:10s} mean|e| {statistics.mean(a):.2f} mm  p90 {a[int(.9 * len(a))]:.2f}  "
            f"tray>0.25mm {sum(v < -0.25 for v in es) / len(es) * 100:5.1f}%  cut>0.5mm {sum(v > 0.5 for v in es) / len(es) * 100:5.1f}%  "
            f"worst cut {max(es):+.2f}  worst tray {min(es):+.2f}  ({len(rows)} photos)")


PAGE = """<!doctype html><meta charset=utf-8><body><script>%s
async function runAll(n){ const out = [];
  const cv = document.createElement('canvas'), cx = cv.getContext('2d', {willReadFrequently: true});
  for (let i = 0; i < n; i++){
    const img = new Image(); img.src = '/p/' + i + '.png'; await img.decode();
    cv.width = img.naturalWidth; cv.height = img.naturalHeight; cx.drawImage(img, 0, 0);
    const L = PHOTO_LAYOUT[img.naturalWidth + 'x' + img.naturalHeight];
    const exp = {x0: L[0], y0: L[1], x1: L[2], y1: L[3]}, t0 = performance.now();
    const r = findCardEdges({data: cx.getImageData(0, 0, cv.width, cv.height).data, width: cv.width, height: cv.height}, exp);
    out.push({r, exp: L, ms: performance.now() - t0}); }
  return out; }
</script>"""


def main():
    from playwright.sync_api import sync_playwright
    cfgs = heldout() if "--heldout" in sys.argv else tuning()
    with Pool(max(1, (os.cpu_count() or 2) - 1)) as p:
        photos = [x for x in p.map(make_photo, cfgs, chunksize=2) if x]
    def valid(it):        # the chain photographed THE card (within 8 mm of the layout)
        L, T, R, B = edges(it)
        lay = {(664, 926): (17, 23, 647, 903), (914, 1276): (63, 88, 851, 1188), (882, 1232): (47, 66, 835, 1166),
               (852, 1188): (32, 44, 820, 1144)}[(it["w"], it["h"])]
        sx, sy = (lay[2] - lay[0]) / 63, (lay[3] - lay[1]) / 88
        return max(abs(L - lay[0]) / sx, abs(R - lay[2]) / sx, abs(T - lay[1]) / sy, abs(B - lay[3]) / sy) < 8
    scored = [it for it in photos if valid(it)]
    print(f"{len(cfgs)} scenes → {len(photos)} layout photos, {len(scored)} scored, {len(photos) - len(scored)} upstream misses")

    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            if self.path == "/":
                body, ct = (PAGE % layout(None)).encode(), "text/html"
            elif not self.path.startswith("/p/"):
                self.send_response(404); self.end_headers(); return
            else:
                body, ct = scored[int(self.path.split("/")[-1].split(".")[0])]["png"], "image/png"
            self.send_response(200); self.send_header("Content-Type", ct); self.send_header("Content-Length", str(len(body)))
            self.end_headers(); self.wfile.write(body)
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    exe = os.environ.get("CHROME") or next(iter(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome")), None)
    with sync_playwright() as pw:
        b = pw.chromium.launch(executable_path=exe) if exe else pw.chromium.launch()
        pg = b.new_page(); pg.goto(f"http://127.0.0.1:{srv.server_port}/")
        res = pg.evaluate(f"runAll({len(scored)})")
        b.close()
    srv.shutdown()
    base = [dict(it=it, e=errors(it, None, r["exp"])) for it, r in zip(scored, res)]
    page = [dict(it=it, e=errors(it, r["r"], r["exp"])) for it, r in zip(scored, res)]
    print(summary("before", base)); print(summary("edges", page))
    print(f"edges: median {statistics.median(r['ms'] for r in res):.1f} ms per photo (desktop Chromium; the phone's WebView ~4x)")
    if "--detail" in sys.argv:
        g = defaultdict(list)
        for r in page:
            it = r["it"]; g[(it["kind"], it["border"], it["tray"], "sleeve" if it["sleeve"] else "bare")].append(r)
        for k in sorted(g):
            es = [v for r in g[k] for v in r["e"]]
            print(f"  {'/'.join(k):32s} n={len(g[k]):3d}  mean {statistics.mean(es):+.2f}  worst cut {max(es):+.2f}  worst tray {min(es):+.2f}")


if __name__ == "__main__":
    main()
