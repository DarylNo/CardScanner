"""Measure the compare's "fit the scan onto the printing" (alignToPrint / cardAlign in the
card-edges block of phone.html / desktop.html) against photos whose TRUE card is known.

    pip install playwright        # Chromium: /opt/pw-browsers in a cloud session, or set CHROME
    python scripts/score_card_align.py [--diff | --wrong] [--worst]

  (default)  each card's photo fitted onto ITS OWN printing's picture
  --diff     onto another printing of the same art (WAR vs CMM Flux Channeler: other frame)
  --wrong    onto ANOTHER card's picture (a candidate that is not the card): how often a fit
             is taken, and where those land

Real card faces (Scryfall "large" images, downloaded once into ~/.cache/cardscanner-align)
are rendered the way the rig sees them — on a light / white / dark tray, sharp or blurred,
with or without a lighting gradient and a glare patch, sensor noise, frame JPEG — then
warped into the 664x926 ScanPhoto layout from a finder quad that is the true card's
(+-0.5 mm) or up to 4 mm too big per side (a sleeve, a shadow, "a much bigger card"), JPEG
q90. The true corners are carried through. The page's own findCardEdges + cardCrop sanity
rules give the edge crop, alignToPrint fits it onto the printing's picture (488x680, as the
page shows image_normal), and each side's error is reported in mm of the 63x88 card:
+ = card cut, - = tray shows. Not part of CI (it needs a browser and Scryfall).
"""
import glob, http.server, itertools, os, statistics, sys, threading, time, urllib.request, json
from multiprocessing import Pool
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT)); sys.path.insert(0, str(ROOT / "scripts"))
import score_card_edges as sc                           # noqa: E402  (its paste / jpeg / errors)
from tests import card_scenes as cs                     # noqa: E402

CACHE = Path(os.environ.get("ALIGN_CACHE", Path.home() / ".cache" / "cardscanner-align"))
CARDS = ["war/52", "cmm/847", "m19/1", "dom/1", "neo/50", "mh2/1", "lea/161", "2xm/1", "znr/1", "khm/1", "eld/1", "m10/1"]
FW, FH, PW, PH = 1200, 1600, 664, 926
LAY = np.float32([[17, 23], [647, 23], [647, 903], [17, 903]])
TRAYS = [(200, 200, 205), (235, 235, 235), (45, 45, 50)]


def fetch_cards():
    CACHE.mkdir(parents=True, exist_ok=True)
    out = {}
    for q in CARDS:
        f = CACHE / (q.replace("/", "_") + ".jpg")
        if not f.exists():
            hdr = {"User-Agent": "CardScanner-score/1.0", "Accept": "application/json"}
            d = json.load(urllib.request.urlopen(urllib.request.Request(f"https://api.scryfall.com/cards/{q}", headers=hdr)))
            uris = d.get("image_uris") or d["card_faces"][0]["image_uris"]
            f.write_bytes(urllib.request.urlopen(urllib.request.Request(uris["large"], headers=hdr)).read())
            time.sleep(0.15)
        out[q] = str(f)
    return out


def scene(args):
    seed, (card, other, card_h, blur, tray, light, quaderr) = args
    rng = np.random.default_rng(seed)
    tq = cs._card_quad(FW / 2 + rng.uniform(-40, 40), FH / 2 + rng.uniform(-40, 40), card_h, rng.uniform(-4, 4), rng.uniform(0, .03))
    SS = 2
    canvas = np.empty((FH * SS, FW * SS, 3), np.uint8); canvas[:] = tray
    tex = cv2.resize(cv2.imread(card), (1260, 1760), interpolation=cv2.INTER_AREA); th, tw = tex.shape[:2]
    sc._paste(canvas, tex, cv2.getPerspectiveTransform(np.float32([[0, 0], [tw, 0], [tw, th], [0, th]]), tq * SS))
    fr = cv2.resize(canvas, (FW, FH), interpolation=cv2.INTER_AREA).astype(np.float32)
    if light:          # a lighting gradient and a glare patch somewhere over the card
        yy, xx = np.mgrid[0:FH, 0:FW].astype(np.float32)
        fr *= (0.75 + 0.35 * (xx / FW * rng.uniform(.3, 1) + yy / FH * rng.uniform(-.5, .5)))[..., None]
        cx, cy = tq.mean(0) + rng.uniform(-150, 150, 2)
        g = np.exp(-(((xx - cx) / rng.uniform(60, 200)) ** 2 + ((yy - cy) / rng.uniform(20, 60)) ** 2))[..., None]
        fr = fr * (1 - .7 * g) + 255 * .7 * g
    if blur:
        fr = cv2.GaussianBlur(fr, (0, 0), blur)
    fr = sc.jpeg(np.clip(fr + rng.normal(0, 2.5, fr.shape), 0, 255).astype(np.uint8), 85)
    # the finder's quad: each side out by up to `quaderr` mm, corners jittered
    e = rng.uniform(-0.5, quaderr, 4); j = rng.normal(0, 0.4, (4, 2))
    mmq = np.float32([[-e[0], -e[1]], [63 + e[2], -e[1]], [63 + e[2], 88 + e[3]], [-e[0], 88 + e[3]]]) + j
    fq = cv2.perspectiveTransform(mmq.reshape(-1, 1, 2).astype(np.float32), cv2.getPerspectiveTransform(sc.MM, tq)).reshape(4, 2)
    M = cv2.getPerspectiveTransform(fq.astype(np.float32), LAY)
    photo = sc.jpeg(cv2.warpPerspective(fr, M, (PW, PH), flags=cv2.INTER_LINEAR, borderValue=(0, 0, 0)), 90)
    truth = cv2.perspectiveTransform(tq.reshape(-1, 1, 2).astype(np.float64), M).reshape(4, 2)
    pr = cv2.resize(cv2.imread(other), (488, 680), interpolation=cv2.INTER_AREA)
    _, b1 = cv2.imencode(".png", photo); _, b2 = cv2.imencode(".jpg", pr, [cv2.IMWRITE_JPEG_QUALITY, 88])
    return dict(png=b1.tobytes(), print=b2.tobytes(), w=PW, h=PH, truth=truth.tolist(), card=Path(card).stem,
                other=Path(other).stem, light=light, quaderr=quaderr, tray=tray[0])


def configs(cards, mode):
    if mode == "diff":
        pairs = [(cards["war/52"], cards["cmm/847"]), (cards["cmm/847"], cards["war/52"])]
    elif mode == "wrong":
        ks = list(cards); pairs = [(cards[a], cards[ks[(i + 3) % len(ks)]]) for i, a in enumerate(ks)]
    else:
        pairs = [(c, c) for c in cards.values()]
    out = [(c, o, h, b, tr, li, qe) for (c, o), h, b, tr, li, qe
           in itertools.product(pairs, [600, 1000], [0, 2], TRAYS, [0, 1], [0.5, 4])]
    return [(3000 + i, c) for i, c in enumerate(out)]


PAGE = """<!doctype html><meta charset=utf-8><body><script>%s
async function load(u){ const i = new Image(); i.src = u; await i.decode(); return cardPixels(i); }
async function runAll(n){ const out = [];
  for (let i = 0; i < n; i++){
    const ph = await load('/p/' + i + '.png'), pr = await load('/q/' + i + '.jpg');
    const L = PHOTO_LAYOUT[ph.width + 'x' + ph.height];
    let crop = {x0: L[0], y0: L[1], x1: L[2], y1: L[3]};
    const r = findCardEdges(ph, crop);          // cardCrop's own sanity rules
    if (r && r.x0 >= 0 && r.y0 >= 0 && r.x1 <= ph.width && r.y1 <= ph.height
        && r.x1 - r.x0 > 0.8 * (L[2] - L[0]) && r.y1 - r.y0 > 0.8 * (L[3] - L[1])) {
      const a = (r.x1 - r.x0) / (r.y1 - r.y0) / (63 / 88); if (a > 0.88 && a < 1.12) crop = r; }
    const t0 = performance.now(), al = alignToPrint(ph, crop, pr), ms = performance.now() - t0;
    const taken = alignTaken(al);                  // cardAlign's rule
    out.push({crop, al, taken, ms}); }
  return out; }
</script>"""


def main():
    from playwright.sync_api import sync_playwright
    mode = "diff" if "--diff" in sys.argv else "wrong" if "--wrong" in sys.argv else "same"
    cfgs = configs(fetch_cards(), mode)
    with Pool(max(1, (os.cpu_count() or 2) - 1)) as p:
        items = p.map(scene, cfgs, chunksize=2)
    t = (ROOT / "server/static/phone.html").read_text()
    block = t[t.index("/* ═══ card edges ═══"):t.index("/* ═══ end card edges ═══ */")]

    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            if self.path == "/":
                body, ct = (PAGE % block).encode(), "text/html"
            elif self.path[:3] in ("/p/", "/q/"):
                it = items[int(self.path[3:].split(".")[0])]
                body, ct = (it["png"], "image/png") if self.path[1] == "p" else (it["print"], "image/jpeg")
            else:
                self.send_response(404); self.end_headers(); return
            self.send_response(200); self.send_header("Content-Type", ct); self.send_header("Content-Length", str(len(body)))
            self.end_headers(); self.wfile.write(body)
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    exe = os.environ.get("CHROME") or next(iter(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome")), None)
    with sync_playwright() as pw:
        b = pw.chromium.launch(executable_path=exe) if exe else pw.chromium.launch()
        pg = b.new_page(); pg.goto(f"http://127.0.0.1:{srv.server_port}/")
        res = pg.evaluate(f"runAll({len(items)})")
        b.close()
    srv.shutdown()

    taken = sum(r["taken"] for r in res)
    print(f"{mode}: {len(items)} photos, fit taken on {taken} ({taken / len(items) * 100:.1f} %)")
    scores = sorted(r["al"]["score"] for r in res if r["al"])
    print(f"fit score min {scores[0]:.3f}  median {statistics.median(scores):.3f}  max {scores[-1]:.3f}")
    print(f"fit ms: median {statistics.median(r['ms'] for r in res):.0f}, max {max(r['ms'] for r in res):.0f} "
          "(desktop Chromium; the phone's WebView ~4x)")
    rows = {"layout": [], "edges": [], "compare": []}
    for it, r in zip(items, res):
        rows["layout"].append(dict(it=it, e=sc.errors(it, None, (17, 23, 647, 903))))
        rows["edges"].append(dict(it=it, e=sc.errors(it, r["crop"], None)))
        rows["compare"].append(dict(it=it, e=sc.errors(it, r["al"] if r["taken"] else r["crop"], None), r=r))
    for k, v in rows.items():
        print(sc.summary(k, v))
    for name in ("edges", "compare"):
        g = {}
        for r in rows[name]:
            g.setdefault((f"light {r['it']['light']}", f"quad +{r['it']['quaderr']} mm"), []).extend(r["e"])
        for k in sorted(g):
            a = sorted(abs(v) for v in g[k])
            print(f"  {name:8s} {k[0]}, {k[1]:12s} mean|e| {statistics.mean(a):.2f}  p90 {a[int(.9 * len(a))]:.2f}  max {a[-1]:.2f}")
    if "--worst" in sys.argv:
        for r in sorted(rows["compare"], key=lambda r: -max(map(abs, r["e"])))[:8]:
            it, al = r["it"], r["r"]["al"] or {}
            print(f"  {it['card']} on {it['other']} light {it['light']} quad +{it['quaderr']} tray {it['tray']}: "
                  f"{[round(v, 2) for v in r['e']]} score {al.get('score', 0):.3f} base {al.get('base', 0):.3f}")


if __name__ == "__main__":
    main()
