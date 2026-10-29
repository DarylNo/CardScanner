"""Drive the REAL desktop.html / phone.html in headless Chromium against a stub
server and check that an UNPICKED card's price — in its row and under the
price filter — is the price it will have once picked (owner, 2026-10-29: a card
was hidden by a Min $0.99 floor until a printing was chosen, then showed).

The pick files the card at the condition read off it (card_read.condition_estimate,
else NM) and the row then shows that condition's F2F price (f2fBest: NM → PL, …).
A pending card must be judged on the SAME number per printing, not on the
cheapest condition F2F happens to list (a $0.95 Played copy of a $1.20 card).

    pip install playwright        # Chromium: /opt/pw-browsers in a cloud session, or set CHROME
    python scripts/check_price_filter_ui.py
Not part of CI (it needs a browser); run it after any edit to the price filter.
"""
import glob, json, os, re, sys, threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path

from playwright.sync_api import sync_playwright

STATIC = Path(__file__).resolve().parents[1] / "server" / "static"


def cand(cid, name, conds):
    return dict(id=cid, name=name, set="m10", set_name="M10", collector_number=cid[-1], rarity="common",
                finishes=["nonfoil"], multi_distance=70, image_small="/x.png", image_normal="/x.png",
                f2f_conditions=conds)


def scan(i, name, cands, read_cond=None, selected=None):
    s = dict(id=i, status="pending", identified=True, confidence="high", candidates=cands, included=True,
             flagged=False, selection=None, error=None, f2f=None, created_at="2026-10-29T00:00:00",
             card_read={"name": name, **({"condition_estimate": read_cond} if read_cond else {})})
    if selected:
        c, cond = selected
        s.update(status="selected", f2f={"conditions": c["f2f_conditions"]},
                 selection=dict(c, scryfall_id=c["id"], condition=cond, finish="Non-Foil", quantity=1))
    return s


NM_PL = {"NM": 1.20, "PL": 0.95}
SCANS = [
    # one print of the art priced $0.50, the other searched but NOT LISTED at F2F: its price
    # is unknown, so the card must stay (owner: hide only with every print of the art priced)
    scan(7, "One Unlisted", [cand("n1", "One Unlisted", {"NM": 0.50}), cand("n2", "One Unlisted", {})]),
    scan(6, "Two Prints", [cand("t1", "Two Prints", {"NM": 0.50}), cand("t2", "Two Prints", NM_PL)]),
    scan(5, "Cheap", [cand("c1", "Cheap", {"NM": 0.50, "PL": 0.40})]),
    scan(4, "Read as LP", [cand("l1", "Read as LP", NM_PL)], read_cond="lp"),
    scan(3, "Only HP listed", [cand("h1", "Only HP listed", {"HP": 1.10})]),
    scan(2, "Picked NM", [cand("p1", "Picked NM", NM_PL)], selected=(cand("p1", "Picked NM", NM_PL), "NM")),
    scan(1, "Unpicked NM", [cand("u1", "Unpicked NM", NM_PL)]),
]
# What a pick would show, so what the filter must use: 1 → NM $1.20 (in), 2 → $1.20 (in),
# 3 → only HP listed, $1.10 (in), 4 → read LP: PL $0.95 (out), 5 → $0.50 (out),
# 6 → $0.50 / $1.20 — one print in band (in).
WANT_SHOWN = [7, 6, 3, 2, 1]
WANT_RANGE = {1: "$1.20", 4: "$0.95", 6: "$0.50–$1.20"}


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
        if p == "/api/scans": return self.send(200, SCANS)
        m = re.fullmatch(r"/api/scans/(\d+)", p)
        if m:
            s = next((x for x in SCANS if x["id"] == int(m.group(1))), None)
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


with sync_playwright() as pw:
    b = pw.chromium.launch(executable_path=EXE) if EXE else pw.chromium.launch()
    for page, url, row, idattr, minsel in (("desktop.html", "/", "#list .row", "id", "#pmin"),
                                           ("phone.html", "/phone?panel=1", ".srow", "sid", "#p-min")):
        print(page)
        ctx = b.new_context(viewport={"width": 1400, "height": 950} if page == "desktop.html" else
                            {"width": 411, "height": 914})
        pg = ctx.new_page(); errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        pg.goto(BASE + url); pg.wait_for_selector(f"{row}[data-{idattr}='1']")
        ranges = pg.evaluate("Object.fromEntries(scans.map(s => [s.id, priceRange(s)]))")
        for sid, want in WANT_RANGE.items():
            check(ranges[str(sid)] == want, f"scan {sid}: row price {ranges[str(sid)]!r} = what a pick shows ({want})")
        if page == "phone.html":
            pg.locator("#fltBtn").click()
        pg.locator(minsel).fill("0.99"); pg.wait_for_timeout(300)
        ids = pg.evaluate(f"[...document.querySelectorAll({row!r})].map(r => +r.dataset.{idattr})")
        check(ids == WANT_SHOWN, f"Min $0.99 shows {ids} (want {WANT_SHOWN}): an unpicked NM $1.20 card is not hidden")
        # the compare caption ("scan over print", "scan", "SET #n") sits under the picture, never on it
        for mode in ("blend", "wipe", "side"):
            pg.evaluate(f"localStorage.setItem('cmpMode', '{mode}')")
            pg.reload(); pg.wait_for_selector(f"{row}[data-{idattr}='1']")
            if page == "desktop.html":
                pg.locator("#list .row[data-id='1']").click()
            else:
                pg.evaluate("openDetail(1)")
            pg.wait_for_selector("#cmp .cmpcap", timeout=8000); pg.wait_for_timeout(300)
            over = pg.evaluate("""() => [...document.querySelectorAll('#cmp .cmpcap')].map(c => {
                const r = c.getBoundingClientRect();
                return [c.textContent, [...document.querySelectorAll('#cmp .cmpbox')].some(b => {
                  const q = b.getBoundingClientRect();
                  return r.left < q.right && r.right > q.left && r.top < q.bottom && r.bottom > q.top; })]; })""")
            check(over and all(not o for _, o in over) and all(t for t, _ in over),
                  f"{mode}: every caption is shown and clear of the pictures {over}")
            if page == "phone.html":
                pg.evaluate("closeDetail()")
        check(not errs, f"no page errors ({errs[:3]})")
        pg.evaluate("localStorage.clear()")
        ctx.close()
    b.close()

srv.shutdown()
print(f"\n{'ALL PASSED' if not fails else f'{len(fails)} FAILED'}")
sys.exit(1 if fails else 0)
