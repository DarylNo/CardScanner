"""Drive the REAL desktop.html / phone.html in headless Chromium against a stub
server and check that an UNPICKED card's price — in its row and under the
price filter — is the price it will have once picked (owner, 2026-10-29: a card
was hidden by a Min $0.99 floor until a printing was chosen, then showed).

The pick files the card at the condition read off it (card_read.condition_estimate,
else NM) and the row then shows that condition's F2F price (f2fBest: NM → PL, …).
A pending card must be judged on the SAME number per printing, not on the
cheapest condition F2F happens to list (a $0.95 Played copy of a $1.20 card).

Then the play data (1.1.20, desktop d60 / phone v61; CLAUDE.md → "Play data",
"List filters"), on both pages, against a stub that also serves /api/play-data
(loaded / not loaded / loaded without Pioneer) and scans carrying popularity and
`play` in every state:
  * the Commander pill's percentile ("★ Staple · top 0.49%", rank 1 → "top 0.01%",
    a rank past the reference's pool → "top 100%", never 101; none for Unranked or a
    stub without top_percent) and fmtTop / fmtShare;
  * the row's play chip: the best format, "+N" counting only 2 %+ formats, its tier
    colour, after the list tags; none when nothing is played / basics / null / absent;
  * the "Played in" floor with "Any format" and one format, in ANY and ALL (absent /
    null keep a card in ANY and hide it in ALL; unplayed / basic / not_legal fail),
    with a Min $ beside it, its hints, its reload, Wanted and "check" rows never
    hidden, and "Exclude filtered" PATCHing exactly the priced cards that fail it;
  * desktop's "Played in" line and phone's head row on the open card;
  * desktop's rows at the 400 px column: the short pills never break inside, and a long
    external list tag (free text, or one long word) stays inside the meta column — it
    wraps inside its own pill instead of running under the price and the include box.

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


# ── the play data ─────────────────────────────────────────────────────────────────
FMTS = ["standard", "pioneer", "modern", "legacy", "vintage", "pauper", "premodern"]
# decks in the 60 days to 2026-11-10: (all, MTGO, paper) — Pioneer's paper too thin to weigh
DECKS = {"standard": (6726, 2849, 3877), "pioneer": (1437, 1437, 130), "modern": (16830, 6146, 10684),
         "legacy": (5768, 3038, 2730), "vintage": (2631, 1479, 1152), "pauper": (8275, 3196, 5079),
         "premodern": (3289, 1419, 1870)}                                # 44,956 decks in all


def play(**kw):
    """{format: answer} — (bp, rank) = in decks, a string = that tier, None = not known;
    a format not named is not_legal."""
    out = {}
    for f in FMTS:
        v = kw.get(f, "not_legal")
        if v is None:
            out[f] = None
        elif isinstance(v, tuple):
            out[f] = {"tier": "staple" if v[0] >= 1000 else "played" if v[0] >= 200 else "fringe",
                      "bp": v[0], "rank": v[1]}
        else:
            out[f] = {"tier": v}
    return out


def pop(tier, label, rank=None, top=None):
    p = {"tier": tier, "label": label, "edhrec_rank": rank, "top_percent": top, "game_changer": False, "reserved": False}
    if rank is None: p["unranked_reason"] = "EDHREC ranks only Commander-legal cards"
    return p


UNSET = object()
def pscan(i, name, pop_, play_=UNSET, price=None, tags=None, pending=False, problem=False):
    c = cand(f"x{i}", name, None if price is None else {"NM": price})
    c["popularity"] = pop_
    s = scan(i, name, [] if problem else [c], selected=None if pending or problem else (c, "NM"))
    if problem: s["error"] = "not identified"
    if play_ is not UNSET: s["play"] = play_
    if tags is not None: s["list_tags"] = tags
    return s


PLAY_SCANS = [
    pscan(40, "Mystery", None, play(modern=(500, 100)), problem=True),             # a "check" row (bare head)
    pscan(39, "Wanted Unplayed", pop("fringe", "Fringe", 25000, 77.4), play(**{f: "unplayed" for f in FMTS}),
          0.25, tags=["wanted"]),
    pscan(38, "One Deck", pop("fringe", "Fringe", 30000, 92.9),
          play(modern=(1, 2400), pioneer="unplayed", legacy="unplayed", vintage="unplayed"), 0.30),
    pscan(37, "Thoughtseize", pop("solid", "Popular", 4442, 13.8),
          play(pioneer=(1500, 30), modern=(2689, 9), legacy=(3100, 4), vintage="unplayed", pauper="unplayed"),
          15.00, tags=["vip"]),
    pscan(36, "Lightning Bolt", pop("staple", "Staple", 158, 0.489),
          play(modern=(2560, 12), legacy=(1200, 30), vintage=(150, 300), pauper=(3500, 3), premodern=(4200, 2)), 1.00),
    pscan(35, "Smothering Tithe", pop("staple", "Staple", 1, 0.003),
          play(modern="unplayed", legacy="unplayed", vintage="unplayed"), 25.00),
    pscan(34, "Forest", pop("unranked", "Unranked"), play(**{f: "basic" for f in FMTS}), 0.10),
    pscan(33, "Old Scan", {"tier": "played", "label": "Played", "rank": 900, "percentile": 3},   # a pre-1.1.20 stub
          play(**{f: None for f in FMTS}), pending=True),
    pscan(32, "No Play Key", pop("fringe", "Fringe", 20000, 61.9), price=2.00),
    pscan(31, "Legacy Only", pop("played", "Played", 9000, 27.9),
          play(legacy=(50, 400), modern="unplayed", vintage="unplayed", premodern="unplayed"), 0.40),
    pscan(30, "Black Lotus", pop("unranked", "Unranked"), play(vintage=(9500, 1), legacy="banned"), 9999.00),
    pscan(29, "Fable of the Mirror-Breaker", pop("solid", "Popular", 3000, 9.3),
          play(pioneer=(1200, 20), modern=(232, 297), legacy=(180, 150), vintage="unplayed"), 4.00),
    pscan(28, "Null Play", pop("fringe", "Fringe", 16200, 50.2), None, 3.00),
]
# the rows' popularity pill and play chip (text, tier) — desktop's words; phone drops the "★ "
WANT_PILL = {39: "Fringe · top 77%", 38: "Fringe · top 93%", 37: "Popular · top 14%",
             36: "★ Staple · top 0.49%", 35: "★ Staple · top 0.01%", 34: "Unranked", 33: "Played",
             32: "Fringe · top 62%", 31: "Played · top 28%", 30: "Unranked", 29: "Popular · top 9.3%",
             28: "Fringe · top 50%"}
WANT_CHIP = {40: ("Modern 5%", "played"), 38: ("Modern <0.1%", "fringe"), 37: ("★ Legacy 31% +2", "staple"),
             36: ("★ Premodern 42% +3", "staple"), 31: ("Legacy 0.5%", "fringe"), 30: ("★ Vintage 95%", "staple"),
             29: ("★ Pioneer 12% +1", "staple")}
EVERY = [s["id"] for s in PLAY_SCANS]

STATE = {"scans": SCANS, "meta": "loaded", "as_of": "2026-11-10", "patches": []}


def play_meta():
    """GET /api/play-data: "absent" = an older server (404), "not" = no data yet, "nopioneer"
    = a week whose file left Pioneer out (too few decks)."""
    if STATE["meta"] == "absent": return None
    if STATE["meta"] == "not": return {"loaded": False}
    fmts = [f for f in FMTS if not (STATE["meta"] == "nopioneer" and f == "pioneer")]
    return {"loaded": True, "as_of": STATE["as_of"], "window_days": 60, "formats": {f: {
        "decks": DECKS[f][0], "events": 100, "played": 1000,
        "sources": {"mtgo": {"decks": DECKS[f][1], "events": 60, "weighted": True},
                    "paper": {"decks": DECKS[f][2], "events": 40, "weighted": DECKS[f][2] >= 200}}} for f in fmts}}


def served():
    """The scans as the server answers them: no play data → no `play` key on any scan; a week
    without Pioneer → no pioneer answer."""
    out = []
    for s in STATE["scans"]:
        s = dict(s)
        if STATE["meta"] in ("not", "absent"):
            s.pop("play", None)
        elif STATE["meta"] == "nopioneer" and isinstance(s.get("play"), dict):
            s["play"] = {k: v for k, v in s["play"].items() if k != "pioneer"}
        out.append(s)
    return out


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
        if p == "/api/play-data":
            meta = play_meta()
            return self.send(200, meta) if meta is not None else self.send(404, {"error": "stub"})
        if p == "/api/scans": return self.send(200, served())
        m = re.fullmatch(r"/api/scans/(\d+)", p)
        if m:
            s = next((x for x in served() if x["id"] == int(m.group(1))), None)
            return self.send(200 if s else 404, s or {"error": "not found"})
        return self.send(404, {"error": "stub"})

    def do_POST(self): self.send(200, {})
    def do_PATCH(self):
        n = int(self.headers.get("Content-Length") or 0)
        STATE["patches"].append((self.path, json.loads(self.rfile.read(n) or b"{}")))
        self.send(200, {})


srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{srv.server_port}"
EXE = os.environ.get("CHROME") or next(iter(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome")), None)

fails = []
def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond: fails.append(msg)


DESK_HINT_C = ("⚠ Cards not played in {} (Commander staples, basic lands, cards not legal there) are hidden — "
               "set a Min $ or a popularity floor so valuable ones still show.")
PHONE_HINT_C = "⚠ Cards not played in {} (Commander staples, basics) are hidden — set a Min $ so valuable ones still show."
DESK_HINT_ALL = ("⚠ “match all” requires every bar to be met, so cards not yet priced (or with no popularity / play "
                 "data) are hidden too. Switch to “any” to keep them.")
PHONE_HINT_ALL = "⚠ “match all” hides cards not yet priced, or with no popularity / play data. Switch to “any” to keep them."
HINT_D_ANY = "Play data isn't on the scanner yet — the Played-in bar keeps every card until it arrives."
HINT_D_ALL = "Play data isn't on the scanner yet — with “match all” the Played-in bar hides every card until it arrives."
HINT_E = "No Pioneer data this week (too few decks) — the Played-in bar {} every card."


def play_checks(b, page):
    """The play data on one page, driven through its own controls."""
    d = page == "desktop.html"
    print(f"{page} — play data")
    url = "/" if d else "/phone?panel=1"
    row, attr = ("#list .row", "id") if d else (".srow", "sid")
    sel = {"min": ("#pmin", "#p-min"), "playmin": ("#playmin", "#p-playmin"), "playfmt": ("#playfmt", "#p-playfmt"),
           "fmode": ("#fmode", "#p-fmode"), "excl": ("#exclBtn", "#p-excl")}
    q = lambda k: sel[k][0 if d else 1]
    star = (lambda t: t) if d else (lambda t: t.replace("★ ", ""))
    hint_c, hint_all = (DESK_HINT_C, DESK_HINT_ALL) if d else (PHONE_HINT_C, PHONE_HINT_ALL)
    STATE.update(scans=PLAY_SCANS, meta="loaded", as_of="2026-11-10", patches=[])
    ctx = b.new_context(viewport={"width": 1400, "height": 950} if d else {"width": 411, "height": 914})
    pg = ctx.new_page(); errs = []
    pg.on("pageerror", lambda e: errs.append(str(e)))

    def settle(): pg.wait_for_timeout(300)
    def ids(): return pg.evaluate(f"[...document.querySelectorAll({row!r})].map(r => +r.dataset.{attr})")
    def hint(): return pg.evaluate("(() => { const e = document.querySelector('#fhint'); "
                                   "return e.style.display === 'none' ? '' : e.textContent; })()")
    def bars():
        if not d and "open" not in (pg.locator("#fltPanel").get_attribute("class") or ""):
            pg.locator("#fltBtn").click()
    def load():
        pg.goto(BASE + url); pg.wait_for_selector(f"{row}[data-{attr}='40']")
        if STATE["meta"] != "absent":
            pg.wait_for_function("playMeta !== null", timeout=8000)
        settle(); bars(); settle()
    def mode(want):
        if pg.evaluate("filterMode") != want: pg.locator(q("fmode")).click(); settle()

    load()
    # ── the Commander pill's percentile + the row's play chip ──
    got = pg.evaluate(f"""() => Object.fromEntries([...document.querySelectorAll({row!r})].map(r => {{
        const p = r.querySelector('.pop'), c = r.querySelector('.play');
        return [r.dataset.{attr}, [p ? p.textContent : null, c ? c.textContent : null, c ? c.className : null,
                c && c.previousElementSibling ? c.previousElementSibling.className : null]]; }}))""")
    for sid in EVERY:
        pill, chip, cls, prev = got[str(sid)]
        want = WANT_PILL.get(sid)
        check(pill == (star(want) if want else None), f"row {sid}: pill {pill!r} (want {star(want) if want else None!r})")
        wc = WANT_CHIP.get(sid)
        if wc:
            check(chip == star(wc[0]) and cls.split()[-2:] == ["play", wc[1]],
                  f"row {sid}: play chip {chip!r} [{cls}] (want {star(wc[0])!r} [{wc[1]}])")
        else:
            check(chip is None, f"row {sid}: no play chip (got {chip!r})")
    check("ltag" in (got["37"][3] or ""), f"the play chip sits after the list tags ({got['37'][3]!r})")
    check("pop" in (got["36"][3] or ""), f"…and right after the popularity pill when there are none ({got['36'][3]!r})")
    junk = pg.evaluate(f"document.querySelector({'#list' if d else '#scansList'!r}).textContent")
    check(not re.search(r"undefined|NaN|top null|null%", junk), "no row reads undefined / NaN / null")
    # past the measured pool (a rank above popularity.RANKED_CARD_COUNT: Spinny Ride, 32,710, is
    # stored as 101.3) the pill still reads "top 100%", never "top 101%"
    tops = pg.evaluate("[0.003, 0.489, 0.5, 0.56, 0.999, 4.4, 5.0, 9.96, 34.4, 99.6, 100, 101.3, 250].map(fmtTop)")
    check(tops == ["0.01", "0.49", "0.5", "0.56", "1", "4.4", "5", "10", "34", "100", "100", "100", "100"],
          f"fmtTop {tops}")
    shares = pg.evaluate("[2689, 1000, 232, 178, 25, 1, 9500].map(fmtShare)")
    check(shares == ["27%", "10%", "2.3%", "1.8%", "0.3%", "<0.1%", "95%"], f"fmtShare {shares}")
    best = pg.evaluate("[37, 36, 29, 34, 33, 32, 28].map(i => (bestPlay(scans.find(s => s.id === i)) || {}).key || null)")
    check(best == ["legacy", "premodern", "pioneer", None, None, None, None], f"bestPlay {best}")

    # ── the open card ──
    if d:
        title = pg.locator("#list .row[data-id='37'] .play").get_attribute("title")
        want = ("Legacy 31% of decks (#4) · Modern 27% (#9) · Pioneer 15% (#30) — not played: Vintage, Pauper"
                " — the 60 days to Nov 10, MTGO + paper")
        check(title == want, f"the chip's title lists every format played, highest first ({title!r})")
        def open_(i):
            pg.locator(f"#list .row[data-id='{i}']").click(); pg.wait_for_timeout(400)
            return pg.evaluate("""() => { const l = document.querySelector('#right .dplay');
                return l && [l.textContent.replace(/\\s+/g, ' ').trim(),
                             [...l.querySelectorAll('.pill.play')].map(e => e.textContent + '|' + e.className.split(' ').pop())]; }""")
        line = open_(37)
        check(line and line[1] == ["Legacy 31% · #4|staple", "Modern 27% · #9|staple", "Pioneer 15% · #30|staple"],
              f"Played in: the formats in order, each with its rank and tier ({line and line[1]})")
        check(line and "not played: Vintage, Pauper" in line[0]
              and line[0].endswith("· 44,956 decks, MTGO + paper, the 60 days to Nov 10"),
              f"…then not played, then what the data is ({line and line[0]!r})")
        pop_line = pg.evaluate("document.querySelector('#right .dpop').textContent")
        check("EDHREC rank 4,442, top 14% of Commander-played cards" in pop_line, f"popularity line ({pop_line!r})")
        STATE["as_of"] = "2026-11-17"
        pg.evaluate("loadPlayMeta()"); settle()
        t2 = pg.locator("#list .row[data-id='37'] .play").get_attribute("title")
        l2 = pg.evaluate("document.querySelector('#right .dplay').textContent")
        check(t2.endswith("to Nov 17, MTGO + paper") and l2.endswith("to Nov 17"),
              f"a new week's answer re-renders the chips and the open card ({t2[-30:]!r} / {l2[-20:]!r})")
        STATE["as_of"] = "2026-11-10"; pg.evaluate("loadPlayMeta()"); settle()
        line = open_(34)
        check(line and line[0] == "Played in: a basic land — every deck plays them, so not ranked" and not line[1],
              f"a basic land: no number, says why ({line})")
        line = open_(35)
        check(line and not line[1] and "in no recent deck — not played: Modern, Legacy, Vintage" in line[0],
              f"played nowhere: the formats it is legal in and in no deck ({line})")
        line = open_(36)
        bolt = pg.evaluate("document.querySelector('#right .dpop').textContent")
        check("top 0.49% of Commander-played cards" in bolt and line[1][0] == "Premodern 42% · #2|staple"
              and line[1][-1] == "Vintage 1.5% · #300|fringe", f"Bolt: popularity + Played in, fringe last ({line[1]})")
        for i in (33, 32, 28):
            check(open_(i) is None, f"scan {i} (every answer null / no key / null play): no Played-in line")
    else:
        def head(i, bare=False):
            pg.evaluate(f"openDetail({i})")
            pg.wait_for_selector("#cmp-bare" if bare else "#cmp", timeout=8000); pg.wait_for_timeout(300)
            r = pg.evaluate("""() => { const h = document.querySelector('.cmptitle .pplay');
                const t = document.querySelector('.cmptitle .ltag');
                return h && [[...h.children].map(e => e.className + '|' + e.textContent),
                             t ? !!(h.compareDocumentPosition(t.parentElement) & Node.DOCUMENT_POSITION_FOLLOWING) : null]; }""")
            pg.evaluate("closeDetail()"); pg.wait_for_timeout(200)
            return r
        h = head(37)
        check(h and h[0] == ["spill pop solid|Popular · top 14%", "spill play staple|Legacy 31%",
                             "spill play staple|Modern 27%", "spill play staple|Pioneer 15%"],
              f"head row: the pill, then every format played, highest first ({h and h[0]})")
        check(h and h[1] is True, "…placed before the tag-chip row")
        h = head(34)
        check(h and h[0] == ["spill pop unranked|Unranked"], f"a basic land: the pill alone ({h and h[0]})")
        h = head(40, bare=True)
        check(h and h[0] == ["spill play played|Modern 5%"], f"the bare head (no candidates) too ({h and h[0]})")
        h = head(32)
        check(h and h[0] == ["spill pop fringe|Fringe · top 62%"], f"no play key: the pill alone ({h and h[0]})")

    # ── the "Played in" floor ──
    vis = pg.evaluate(f"""() => [{q('playfmt')!r}, {q('playmin')!r}].map(s => {{
        const r = document.querySelector(s).getBoundingClientRect(); return [r.width > 40, r.left >= 0, r.right]; }})""")
    edge = 400 if d else 411
    check(all(w and l and r <= edge for w, l, r in vis), f"both selects shown inside the {'list column' if d else 'screen'} ({vis})")
    pg.locator(q("playmin")).select_option("played"); settle()
    check(ids() == [40, 39, 37, 36, 33, 32, 30, 29, 28],
          f"2%+ of decks, any format, ANY: unplayed / basic / fringe hidden; absent / null kept ({ids()})")
    check(hint() == hint_c.format("any format"), f"hint (c) ({hint()!r})")
    if not d:
        check(pg.locator("#fcount").inner_text().strip() == "1", "the Filters chip counts the Played-in bar")
    STATE["patches"].clear()
    pg.locator(q("excl")).click(); pg.wait_for_timeout(900)
    hit = sorted(int(path.rsplit("/", 1)[1]) for path, body in STATE["patches"] if body == {"included": False})
    check(hit == [31, 34, 35, 38] and len(STATE["patches"]) == 4,
          f"Exclude filtered PATCHes exactly the priced cards the floor hides ({hit})")
    mode("all")
    check(ids() == [40, 39, 37, 36, 30, 29], f"ALL: absent / null play hidden too ({ids()})")
    check(hint() == hint_all, f"hint (a) ({hint()!r})")
    mode("any")
    pg.locator(q("playmin")).select_option("fringe"); settle()
    check(ids() == [40, 39, 38, 37, 36, 33, 32, 31, 30, 29, 28], f"In any deck, any format: the Legacy-only card passes ({ids()})")
    pg.locator(q("playfmt")).select_option("modern"); settle()
    check(ids() == [40, 39, 38, 37, 36, 33, 32, 29, 28], f"In any deck, Modern: the Legacy-only card fails ({ids()})")
    check(hint() == hint_c.format("Modern"), f"hint (c) names the format ({hint()!r})")
    mode("all")
    check(ids() == [40, 39, 38, 37, 36, 29], f"In any deck, Modern, ALL ({ids()})")
    pg.locator(q("playmin")).select_option("staple"); settle()
    check(ids() == [40, 39, 37, 36], f"Staples, Modern, ALL: the Wanted card and the check row still show ({ids()})")
    mode("any")
    pg.locator(q("playmin")).select_option("played"); pg.locator(q("playfmt")).select_option("any")
    pg.locator(q("min")).fill("5"); settle()
    check(ids() == [40, 39, 37, 36, 35, 33, 32, 30, 29, 28],
          f"Min $5 beside it, ANY: a priced card in band shows though it fails the floor ({ids()})")
    check(hint() == "", f"no hint with a Min $ set ({hint()!r})")
    if not d:
        check(pg.locator("#fcount").inner_text().strip() == "2", "the Filters chip counts both bars")
    pg.locator(q("min")).fill(""); pg.locator(q("playfmt")).select_option("modern"); settle()
    load()
    got = pg.evaluate(f"[document.querySelector({q('playmin')!r}).value, document.querySelector({q('playfmt')!r}).value]")
    check(got == ["played", "modern"] and ids() == [40, 39, 37, 36, 33, 32, 29, 28],
          f"the floor and its format survive a reload ({got}, {ids()})")
    # ── hints (d) and (e) ──
    for meta in ("not", "absent"):
        STATE["meta"] = meta; load()
        check(ids() == EVERY and hint() == HINT_D_ANY, f"no play data ({meta}), ANY: every card kept, hint (d) ({ids()}, {hint()!r})")
        mode("all")
        check(ids() == [40, 39] and hint() == HINT_D_ALL, f"no play data ({meta}), ALL: hint (d) ({ids()}, {hint()!r})")
        mode("any")
    STATE["meta"] = "nopioneer"; load()
    pg.locator(q("playfmt")).select_option("pioneer"); pg.locator(q("playmin")).select_option("fringe"); settle()
    check(ids() == EVERY and hint() == HINT_E.format("keeps"), f"no Pioneer this week, ANY: hint (e) ({ids()}, {hint()!r})")
    mode("all")
    check(ids() == [40, 39] and hint() == HINT_E.format("hides"), f"no Pioneer this week, ALL: hint (e) ({ids()}, {hint()!r})")
    mode("any")
    STATE["meta"] = "loaded"
    check(not errs, f"no page errors ({errs[:3]})")
    ctx.close()
    STATE.update(scans=SCANS, patches=[])


LONG_TAG = "reserved: hold for a customer pickup on Friday afternoon, paid"
ONE_WORD_TAG = "reserved-for-a-customer-pickup-on-friday-afternoon-paid"
LAYOUT_SCANS = [
    pscan(53, "Long Tag", pop("fringe", "Fringe", 25000, 77.4), play(legacy=(50, 400)), 1.00, tags=[LONG_TAG]),
    pscan(52, "One Word Tag", pop("fringe", "Fringe", 25000, 77.4), UNSET, 1.00, tags=[ONE_WORD_TAG]),
    pscan(51, "Lightning Bolt", pop("staple", "Staple", 158, 0.489),
          play(modern=(2560, 12), legacy=(1200, 30), vintage=(150, 300), pauper=(3500, 3), premodern=(4200, 2)),
          1.00, tags=["hold me", "wanted"]),
    # ranked past the pool the reference measured (popularity.RANKED_CARD_COUNT 32,296 — Spinny Ride
    # is 32,710: top_percent 101.3)
    pscan(50, "Spinny Ride", pop("fringe", "Fringe", 32710, 101.3), play(modern=(199, 400)), 1.00),
]


def row_layout(b):
    """Desktop's list rows at the 400 px column: the short pills (selected, ×q, the popularity
    pill, the play chip) wrap BETWEEN pills, never inside one ("Fringe · top / 77%"), and an
    external list tag — free text — stays inside the row's meta column: a long one wraps inside
    its own pill instead of running under the price and the include box (d60 drew it on one
    line from x 64 to 403)."""
    print("desktop.html — row layout")
    STATE.update(scans=LAYOUT_SCANS, meta="loaded", as_of="2026-11-10", patches=[])
    ctx = b.new_context(viewport={"width": 1400, "height": 950})
    pg = ctx.new_page(); errs = []
    pg.on("pageerror", lambda e: errs.append(str(e)))
    pg.goto(BASE + "/"); pg.wait_for_selector("#list .row[data-id='53']")
    pg.wait_for_function("playMeta !== null", timeout=8000); pg.wait_for_timeout(300)
    rows = pg.evaluate("""() => [...document.querySelectorAll('#list .row')].map(r => {
        const box = e => { const q = e.getBoundingClientRect(); return [q.left, q.right, q.top, q.bottom]; };
        return { id: +r.dataset.id, meta: box(r.querySelector('.meta')), right: box(r.querySelector('.right')),
                 list: box(document.querySelector('#list')),
                 pills: [...r.querySelectorAll('.meta .pill')].map(p => ({ cls: p.className, text: p.textContent,
                                                                         box: box(p), title: p.title || "" })) }; })""")
    one_line = min(p["box"][3] - p["box"][2] for r in rows for p in r["pills"])
    for r in rows:
        for p in r["pills"]:
            l, rt, t, btm = p["box"]
            inside = l >= r["meta"][0] - 0.5 and rt <= r["meta"][1] + 0.5 and rt <= r["right"][0] and rt <= r["list"][1]
            check(inside, f"row {r['id']}: {p['text'][:40]!r} inside the meta column "
                          f"(x {l:.0f}–{rt:.0f}; meta ends {r['meta'][1]:.0f}, price column from {r['right'][0]:.0f})")
            if "ltag" not in p["cls"] or len(p["text"]) < 20:
                check(btm - t <= one_line + 1, f"row {r['id']}: {p['text']!r} on one line ({btm - t:.0f} px, one line {one_line:.0f})")
    by = {r["id"]: r for r in rows}
    pill = next(p for p in by[50]["pills"] if "pop" in p["cls"])
    check(pill["text"] == "Fringe · top 100%" and "top 100% of Commander-played cards" in pill["title"],
          f"ranked past the pool: \"top 100%\", never 101 ({pill['text']!r} / {pill['title'][:70]!r})")
    check(not errs, f"no page errors ({errs[:3]})")
    pg.evaluate("localStorage.clear()")
    ctx.close()
    STATE.update(scans=SCANS, patches=[])


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
    for page in ("desktop.html", "phone.html"):
        play_checks(b, page)
    row_layout(b)
    b.close()

srv.shutdown()
print(f"\n{'ALL PASSED' if not fails else f'{len(fails)} FAILED'}")
sys.exit(1 if fails else 0)
