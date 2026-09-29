"""
Export collector-line OCR + identification-decision parity fixtures for the
Android app's JVM tests (android/core: OcrMatchParityTest).

The phone ports two things from the server and must give THE SAME ANSWERS:

  ocr_id.py    _canon (confusion classes), match_printing (compound
               collectors win outright, UNIQUE set-code hit, collector
               tiebreak, ambiguity = None), and read_bottom_strip's strip
               crop + two preprocessing variants (the OCR ENGINE itself is
               swapped for ML Kit on the phone; its text feeds the same
               matcher).
  pipeline.py  _confidence_for, _is_confident (110, or <=140 with a >=20 lead
               over the 2nd NAME), _art_agrees / _OCR_ART_SLACK,
               _apply_ocr_hint, _mark_art_decisive, _identify's multi-frame
               retry and scan_candidates' result shape (blank guard, 210
               no-card rule, best-guess grid, error strings), plus the
               server's scan-time auto-pick grounds (server/app.py).

Every expected answer below is produced by CALLING THE REAL PYTHON FUNCTIONS
(scan_candidates runs on a Pipeline whose art index, blank check, sharpness
and ranking are faked — the same seams tests/test_scan_candidates.py uses).
The one exception is the auto-pick predicate, which is an inline expression
in server/app.py: it is mirrored here verbatim and the exporter FAILS if that
expression no longer appears in app.py, so the copy cannot drift.

Inputs (committed; --refresh-inputs rewrites them, needs network):
  printings.json   real Scryfall printings (ScryfallClient.get_all_printings)
                   of Diabolic Edict, Lightning Bolt, Sol Ring, Fling,
                   Shivan Dragon, Counterspell — the candidate lists
  strip/*.png      synthetic cards with a drawn collector line, lossless PNG

    python scripts/export_ocr_fixtures.py                   # rewrite expected
    python scripts/export_ocr_fixtures.py --check           # exit 1 if stale (CI)
    python scripts/export_ocr_fixtures.py --refresh-inputs  # re-record inputs
"""

from __future__ import annotations

import contextlib
import copy
import hashlib
import io
import json
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from mtg_card_scanner import art_index, ocr_id, pipeline  # noqa: E402
from mtg_card_scanner.art_index import ArtIndexError  # noqa: E402
from mtg_card_scanner.ocr_id import _canon, canon_words, match_printing  # noqa: E402

OUT = ROOT / "android" / "core" / "src" / "test" / "resources" / "ocr"
CARD_NAMES = ["Diabolic Edict", "Lightning Bolt", "Sol Ring", "Fling",
              "Shivan Dragon", "Counterspell"]
KEEP = ("id", "name", "set", "set_name", "collector_number", "rarity",
        "released_at", "artist")
STRIP_SIZES = [(630, 880), (317, 443), (745, 1040), (100, 140)]


# ── deterministic PRNG (no dependence on Python's random module) ────────────

class Rng:
    def __init__(self, seed: int) -> None:
        self.s = (seed * 0x9E3779B97F4A7C15 + 1) & 0xFFFFFFFFFFFFFFFF

    def next(self) -> int:              # xorshift64*
        x = self.s
        x ^= (x >> 12)
        x ^= (x << 25) & 0xFFFFFFFFFFFFFFFF
        x ^= (x >> 27)
        self.s = x
        return (x * 0x2545F4914F6CDD1D) & 0xFFFFFFFFFFFFFFFF

    def below(self, n: int) -> int:
        return (self.next() >> 11) % n

    def chance(self, num: int, den: int) -> bool:
        return self.below(den) < num

    def choice(self, seq):
        return seq[self.below(len(seq))]


# ── inputs ──────────────────────────────────────────────────────────────────

def _png(img: np.ndarray) -> bytes:
    ok, b = cv2.imencode(".png", img)
    assert ok
    return b.tobytes()


def _synthetic_card(w: int, h: int, line1: str, line2: str) -> np.ndarray:
    """A card-shaped image: dark frame, lighter art/text boxes, and a white
    collector line in the bottom-left strip (where the real one sits)."""
    img = np.zeros((h, w, 3), np.uint8)
    img[:] = (28, 30, 34)
    yy, xx = np.mgrid[0:h, 0:w]
    img[..., 0] = (28 + (xx * 40) // max(1, w)).astype(np.uint8)
    cv2.rectangle(img, (int(w * .08), int(h * .11)), (int(w * .92), int(h * .55)),
                  (70, 120, 160), -1)
    cv2.rectangle(img, (int(w * .08), int(h * .62)), (int(w * .92), int(h * .86)),
                  (200, 214, 222), -1)
    scale = h / 880 * 0.62
    th = max(1, int(round(h / 880 * 1.4)))
    cv2.putText(img, line1, (int(w * .045), int(h * .915)), cv2.FONT_HERSHEY_SIMPLEX,
                scale, (235, 235, 235), th, cv2.LINE_AA)
    cv2.putText(img, line2, (int(w * .045), int(h * .965)), cv2.FONT_HERSHEY_SIMPLEX,
                scale, (235, 235, 235), th, cv2.LINE_AA)
    cv2.putText(img, "TM & (c) 2019 Wizards", (int(w * .62), int(h * .95)),
                cv2.FONT_HERSHEY_SIMPLEX, scale * .8, (220, 220, 220), th, cv2.LINE_AA)
    return img


def refresh_inputs() -> None:
    from mtg_card_scanner.scryfall import ScryfallClient
    client = ScryfallClient()
    rec = {}
    with contextlib.redirect_stdout(io.StringIO()):
        for name in CARD_NAMES:
            rec[name] = [{k: p.get(k, "") for k in KEEP}
                         for p in client.get_all_printings(name)]
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "printings.json").write_text(
        json.dumps(rec, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    sd = OUT / "strip"
    sd.mkdir(exist_ok=True)
    for p in sd.glob("*.png"):
        p.unlink()
    for w, h in STRIP_SIZES:
        img = _synthetic_card(w, h, "0087/254 U", "MH1 * EN  Illus. John Avon")
        (sd / f"card_{w}x{h}.png").write_bytes(_png(img))
    print(f"recorded {sum(len(v) for v in rec.values())} printings + "
          f"{len(STRIP_SIZES)} strip cards")


# ── ocr_id: canon / match_printing ──────────────────────────────────────────

CONF_SPELLINGS = {
    "1": ["1", "I", "L", "|", "l", "i"],
    "0": ["0", "O", "Q", "D", "o", "q", "d"],
    "5": ["5", "S", "s"],
    "2": ["2", "Z", "z"],
    "8": ["8", "B", "b"],
    "6": ["6", "G", "g"],
}


def _confuse(s: str, rng: Rng, num: int = 1, den: int = 2) -> str:
    out = []
    for ch in s:
        cls = ocr_id._CONFUSION.get(ch.upper())
        if cls is not None and rng.chance(num, den):
            out.append(rng.choice(CONF_SPELLINGS[cls]))
        else:
            out.append(ch)
    return "".join(out)


RARITY = {"common": "C", "uncommon": "U", "rare": "R", "mythic": "M",
          "special": "S", "bonus": "T"}


def _line(p: dict, style: int) -> str:
    """A plausible bottom-left collector strip for printing *p*."""
    cn, sc = p["collector_number"], p["set"].upper()
    r = RARITY.get(p.get("rarity", ""), "C")
    artist = p.get("artist", "")
    if style == 0:        # 2015–2019 frame: 087/254 R / MH1 • EN
        num = cn.zfill(3) if cn.isdigit() else cn
        return f"{num}/254 {r}\n{sc} • EN — {artist}"
    if style == 1:        # 2020+ frame: R 0087 / MH1 • EN
        num = cn.zfill(4) if cn.isdigit() else cn
        return f"{r} {num}\n{sc} • EN  Illus. {artist}"
    if style == 2:        # just the set line
        return f"{sc} EN"
    return f"Illus. {artist} ™ & © 1997 Wizards"     # old frame: no code


def build_canon() -> list:
    samples = [
        "", " ", "mhI+ Fn", "MH1 FN", "2S4", "254", "087/254 R", "MH1 • EN",
        "A25-85", "a25–85", "PLST", "pmei 2019-2", "0017314MH1FN",
        "IL|il1", "OQDoqd0", "Ss5", "Zz2", "Bb8", "Gg6", "abcdefghijklmnopqrstuvwxyz",
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", "!@#$%^&*()_+-=[]{};:'\",.<>/?`~\\",
        "été Æther", "straße", "ﬁne", "x² ½ Ⅷ",
        "٠١٢", "日本語", "\U0001F600ok", "tab\tnew\nline",
        "ıİ", "ŉ", "µ", "ẞ", "①②", "Ａ１",
        "ªº", "Lim-Dûl", "™©®",
    ]
    return [[s, _canon(s)] for s in samples]


def build_canon_words() -> list:
    """canon_words over the same samples plus word-boundary shapes."""
    samples = [r[0] for r in build_canon()] + [
        "087/254 R\nMH1 • EN", "UMA•EN", "  lead  and   trail  ", "a-b_c.d",
        "You MAy rest", "0017314 MH1FN", "ﬁ ne", "x² y", "٠١ ٢"]
    return [[s, canon_words(s)] for s in samples]


def _cands(recs: list, extra_distance=None) -> list:
    return [{"id": p["id"], "name": p["name"], "set": p["set"],
             "set_name": p["set_name"], "collector_number": p["collector_number"]}
            for p in recs]


def build_match(printings: dict) -> tuple[dict, list]:
    lists: dict[str, list] = {}
    rows: list = []

    def add(blob: str, key: str, note: str) -> None:
        # [blob, list key, expected id] — notes only document the generator
        rows.append([blob, key, match_printing(blob, lists[key])])

    # Hand-built lists (tests/test_ocr_id.py) and edge shapes.
    def _c(sid, s, n):
        return {"id": sid, "set": s, "collector_number": n}
    lists["unit"] = [_c("a25", "a25", "85"), _c("plst", "plst", "A25-85"),
                     _c("mh1", "mh1", "87"), _c("j22", "j22", "67"),
                     _c("tmp", "tmp", "128"), _c("pmei-a", "pmei", "2019-2"),
                     _c("pmei-b", "pmei", "2024-5")]
    lists["empty"] = []
    lists["short"] = [_c("x", "x", "1"), _c("y", "y1", "2"), _c("z", "", ""),
                      {"id": "nokeys"}]
    lists["twin_compound"] = [_c("p1", "plst", "A25-85"), _c("p2", "plst", "A25-85"),
                              _c("a", "a25", "85")]
    lists["short_compound"] = [_c("c1", "sld", "1-2"), _c("c2", "sld", "12-3"),
                               _c("c3", "pz2", "A-1")]
    lists["noid"] = [{"set": "mh1", "collector_number": "87"},
                     _c("tmp", "tmp", "128")]
    lists["dupe_set_num"] = [_c("m1", "m10", "146"), _c("m2", "m10", "146")]
    lists["confusable_sets"] = [_c("s1", "sld", "5"), _c("s2", "5ld", "6"),
                                _c("o1", "o10", "7"), _c("o2", "01o", "8"),
                                _c("b1", "bbd", "9"), _c("b2", "8bd", "10")]
    for blob in ["", "0017314MH1FN", "XYZQQQ", "PMEI", "PMEI 20192", "pmei 2024-5",
                 "A25-85 PW", "A25-85", "a2s-8s", "A25 85", "A2585", "85 A25",
                 "MH1 J22", "mhi", "mh|", "MHL", "TMP 128", "tmp", "TMP128J22",
                 "PLST", "PLST A25-85", "PMEI 2019-2 2024-5", "2019-2", "20192 20245",
                 "zo19-z", "087/254 R MH1 • EN", "J22 67", "j2z", "a25",
                 "A25", "A25-8S", "A25-86", "a25 85 plst", "émh1é"]:
        add(blob, "unit", "unit")
    for blob in ["", "X", "XX", "Y1", "Y1 2", "YI", "Z", "1", "11"]:
        add(blob, "short", "short/empty set codes")
    for blob in ["A25-85", "A25-85 PLST", "PLST"]:
        add(blob, "twin_compound", "two identical compound collectors")
    for blob in ["1-2", "12", "123", "12-3", "SLD 12-3", "A-1", "A1", "PZ2 A1",
                 "SLD 1-2", "SLD"]:
        add(blob, "short_compound", "compound shorter than 4")
    for blob in ["MH1", "MH1 TMP", "TMP"]:
        add(blob, "noid", "hit without an id")
    for blob in ["M10", "M10 146", "146"]:
        add(blob, "dupe_set_num", "same set + same number twice")
    for blob in ["SLD", "5LD", "O10", "01O", "010", "BBD", "8BD", "88D", "SLD 5",
                 "SLD 6", "BBD 10", "BBD 9"]:
        add(blob, "confusable_sets", "set codes equal under confusion")
    for blob in ["", "anything"]:
        add(blob, "empty", "no candidates")
    # A set code only counts as a WHOLE word (+ a glued language tag):
    # 2026-09-29 an AVR Emancipation Angel, no code printed, "confirmed" as
    # UMA from letters that met across words; artists MSCHF / Milivoj begin
    # with MSC / M11.
    lists["cross_word"] = [_c("avr", "avr", "19"), _c("uma", "uma", "15")]
    for blob in ["19/244 Illus. Scott Chou", "You MAy rest", "CHOUMA 19/244",
                 "0uma", "015/254 R UMA • EN", "UMA•EN", "R 0015 umaen", "UMA AVR",
                 "hUMAn AVR 19", "UMAX 15", "UMAFN", "umade", "AVR-EN"]:
        add(blob, "cross_word", "set code must be a whole word")
    lists["artist_prefix"] = [_c("slz", "slz", "63"), _c("msc", "msc", "806"),
                              _c("m11", "m11", "149")]
    for blob in ["C 0063 SLZ • EN Illus. MSCHF", "Illus. MSCHF", "Illus. Milivoj Ćeran",
                 "M11 149", "MSCEN 806", "SLZEN M11"]:
        add(blob, "artist_prefix", "an artist name is never a set code")

    # Real printings: every printing's strip in several styles and noises.
    rng = Rng(20260928)
    for name, recs in printings.items():
        slug = name.lower().replace(" ", "_")
        full = _cands(recs)
        lists[f"{slug}:all"] = full
        lists[f"{slug}:top12"] = full[-12:]                  # newest 12
        lists[f"{slug}:old12"] = full[:12]
        for i, p in enumerate(recs):
            for style in range(4):
                base = _line(p, style)
                variants = [
                    ("clean", base),
                    ("lower", base.lower()),
                    ("confused", _confuse(base, rng)),
                    ("confused-all", _confuse(base, rng, 1, 1)),
                    ("nospace", "".join(base.split())),
                    ("noise", base + " " + rng.choice(["·", "|", "~", "*", "..."])
                     + rng.choice(["EN", "FR", "JP", "DE"])),
                ]
                if style < 2:
                    variants.append(("cn-only", p["collector_number"]))
                    sc = p["set"]
                    variants.append(("drop-first-set-char", base.replace(
                        sc.upper(), sc.upper()[1:], 1)))
                    pos = rng.below(max(1, len(base)))
                    variants.append(("subst", base[:pos] + rng.choice("XKWVYHN#")
                                     + base[pos + 1:]))
                for kind, blob in variants:
                    keys = [f"{slug}:all"]
                    if i >= len(recs) - 12:
                        keys.append(f"{slug}:top12")
                    if i < 12 and style == 0:
                        keys.append(f"{slug}:old12")
                    for key in keys:
                        add(blob, key, f"{slug} #{i} {p['set']} {p['collector_number']} "
                                       f"style{style} {kind}")
    return lists, rows


# ── ocr_id: strip crop + preprocessing ──────────────────────────────────────

def build_strip() -> tuple[dict, dict]:
    files: dict[str, bytes] = {}
    out = {"strip_y": list(ocr_id._STRIP_Y), "strip_x": list(ocr_id._STRIP_X),
           "rects": [], "cards": {}}
    for w in (1, 2, 7, 50, 99, 100, 317, 630, 631, 745, 1000, 1499, 2000):
        for h in (1, 3, 9, 100, 140, 443, 880, 881, 1040, 1397, 2800):
            out["rects"].append([w, h, int(h * ocr_id._STRIP_Y[0]), int(h * ocr_id._STRIP_Y[1]),
                                 int(w * ocr_id._STRIP_X[0]), int(w * ocr_id._STRIP_X[1])])
    for p in sorted((OUT / "strip").glob("card_*.png")):
        card = cv2.imread(str(p), cv2.IMREAD_COLOR)
        assert card is not None
        h, w = card.shape[:2]
        strip = card[int(h * ocr_id._STRIP_Y[0]):int(h * ocr_id._STRIP_Y[1]),
                     int(w * ocr_id._STRIP_X[0]):int(w * ocr_id._STRIP_X[1])]
        gray = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
        t, otsu = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        v = [cv2.resize(strip, None, fx=2.5, fy=2.5, interpolation=cv2.INTER_CUBIC),
             cv2.resize(otsu, None, fx=4, fy=4, interpolation=cv2.INTER_CUBIC)]
        # the SAME variants read_bottom_strip feeds its engine: capture them
        seen: list[np.ndarray] = []

        def fake_engine(img):
            seen.append(img.copy())
            return [[None, f"v{len(seen)}", 1.0]], None
        old = ocr_id._ocr_engine
        ocr_id._ocr_engine = fake_engine
        try:
            blob = ocr_id.read_bottom_strip(card)
        finally:
            ocr_id._ocr_engine = old
        assert blob == canon_words("v1 v2") and len(seen) == 2
        for a, b in zip(seen, v):
            assert a.shape == b.shape and np.array_equal(a, b), "variant drift"
        stem = p.stem
        entry = {"otsu_threshold": float(t), "variants": []}
        for j, img in enumerate(v):
            fn = f"{stem}.v{j}.png"
            files[fn] = _png(img)
            entry["variants"].append({"file": fn, "shape": list(img.shape),
                                      "sha256": hashlib.sha256(
                                          np.ascontiguousarray(img).tobytes()).hexdigest()})
        out["cards"][p.name] = entry
    return out, files


def _v0_close(a: bytes, b: bytes) -> bool:
    """The ×2.5 cubic variant (v0) differs by ±1 LSB on a handful of pixels
    across OpenCV builds AND across CPUs (SIMD dispatch: CI runners flip it
    run to run). The same tolerance OcrMatchParityTest applies: max |Δ| ≤ 1
    on ≤ 0.5% of bytes. v1 (grey + Otsu + ×4) stays byte-exact."""
    x = cv2.imdecode(np.frombuffer(a, np.uint8), cv2.IMREAD_UNCHANGED)
    y = cv2.imdecode(np.frombuffer(b, np.uint8), cv2.IMREAD_UNCHANGED)
    if x is None or y is None or x.shape != y.shape:
        return False
    d = np.abs(x.astype(np.int16) - y.astype(np.int16))
    return int(d.max(initial=0)) <= 1 and np.count_nonzero(d) * 200 <= d.size


def _drop_v0_sha(blob: bytes) -> dict:
    j = json.loads(blob)
    for c in j.get("strip", {}).get("cards", {}).values():
        c["variants"][0].pop("sha256", None)
    return j


def _same(name: str, have: bytes, want: bytes) -> bool:
    if have == want:
        return True
    if name.endswith(".v0.png"):
        return _v0_close(have, want)
    if name == "expected.json":
        return _drop_v0_sha(have) == _drop_v0_sha(want)
    return False


# ── pipeline decisions ──────────────────────────────────────────────────────

EDGES = sorted({0, 1, 50, 89, 90, 91, 109, 110, 111, 119, 120, 121, 129, 130, 131,
                139, 140, 141, 150, 170, 209, 210, 211, 236, 4096})


def build_confidence() -> list:
    return [[d, pipeline._confidence_for(d)] for d in list(range(0, 301)) + [4096]]


def build_is_confident() -> list:
    rows = [{"distances": [], "expect": pipeline._is_confident([])}]
    for d0 in EDGES:
        rows.append({"distances": [d0],
                     "expect": pipeline._is_confident([{"distance": d0}])})
        for gap in (0, 1, 19, 20, 21, 30, 100):
            ds = [d0, d0 + gap]
            rows.append({"distances": ds, "expect": pipeline._is_confident(
                [{"distance": d} for d in ds])})
            ds3 = [d0, d0 + gap, d0 + gap + 1]
            rows.append({"distances": ds3, "expect": pipeline._is_confident(
                [{"distance": d} for d in ds3])})
    return rows


def _md(ds):
    return [{"id": f"c{i}"} if d is None else {"id": f"c{i}", "multi_distance": d}
            for i, d in enumerate(ds)]


def build_art_agrees() -> list:
    rows = []
    shapes = [[124, 156, 208], [100, 159, 160, 161], [None, 150, 90], [None, None],
              [], [0], [140, 200, 201], [100.5, 160.5, 161], [300, 100]]
    for ds in shapes:
        cands = _md(ds)
        hits = cands + [{"id": "absent"}, {"id": "far", "multi_distance": 1000},
                        {"id": "neg", "multi_distance": -5}]
        for hit in hits:
            for slack in (None, 0, 59, 60, 61):
                exp = (pipeline._art_agrees(hit, cands) if slack is None
                       else pipeline._art_agrees(hit, cands, slack))
                rows.append({"hit": hit, "cands": cands, "slack": slack, "expect": exp})
    return rows


def build_mark_art_decisive() -> list:
    rows = []
    shapes = [[], [100], [None, 200], [100, None]]
    for d0 in (0, 90, 110, 139, 140, 141, 170):
        for gap in (-5, 0, 29, 30, 31, 90):
            shapes.append([d0, d0 + gap])
            shapes.append([d0, d0 + gap, d0 + gap + 1])
    shapes += [[139.5, 169.5], [140.0, 170.0], [140.5, 170.5]]
    for ds in shapes:
        for pre in (None, False, True, 0, 1, ""):
            cands = _md(ds)
            if pre is not None and cands:
                cands[0]["ocr_confirmed"] = pre
            before = copy.deepcopy(cands)
            pipeline._mark_art_decisive(cands)
            rows.append({"in": before, "out": cands})
    return rows


@contextlib.contextmanager
def _patched(obj, **attrs):
    old = {k: getattr(obj, k) for k in attrs}
    for k, v in attrs.items():
        setattr(obj, k, v)
    try:
        yield
    finally:
        for k, v in old.items():
            setattr(obj, k, v)


def build_apply_ocr_hint(printings: dict) -> list:
    from mtg_card_scanner import card_detect
    rows = []
    edict = _cands(printings["Diabolic Edict"])
    bolt = _cands(printings["Lightning Bolt"])
    rng = Rng(7)

    def run(key, blob, detected):
        cands = copy.deepcopy(lists[key])
        pl = pipeline.Pipeline(None, None)
        with _patched(card_detect, extract_card=lambda f: (f, detected)), \
                _patched(ocr_id, read_bottom_strip=lambda img: blob), \
                contextlib.redirect_stdout(io.StringIO()):
            got = pl._apply_ocr_hint(np.zeros((4, 4, 3), np.uint8), cands)
        # The output is the input's dicts, reordered, with at most an
        # ocr_confirmed flag added — assert that, then store it compactly.
        by_id = {c["id"]: c for c in lists[key]}
        for c in got:
            assert {k: v for k, v in c.items() if k != "ocr_confirmed"} == by_id[c["id"]]
        rows.append({"list": key, "blob": blob, "detected": detected,
                     "out_ids": [c["id"] for c in got],
                     "out_confirmed": [c.get("ocr_confirmed") for c in got]})

    def with_d(cands, ds):
        out = copy.deepcopy(cands)
        for c, d in zip(out, ds):
            if d is not None:
                c["multi_distance"] = d
        return out

    lists = {
        "edict-same-art": with_d(edict, [120 + (i * 7) % 50 for i in range(len(edict))]),
        "edict-one-far": with_d(edict, [120] + [200] * (len(edict) - 1)),
        "edict-no-dist": copy.deepcopy(edict),
        "edict-some-dist": with_d(edict, [None, 130, None, 191, 190, 250]),
        "bolt-12": with_d(bolt[-12:], [100 + 5 * i for i in range(12)]),
        "bolt-mixed": with_d(bolt[:12], [100, 105, 170, 159, 160, 161, 162, 100, 99,
                                         98, None, 300]),
        "one": with_d(edict[:1], [100]),
        "empty": [],
    }
    for key, cands in lists.items():
        blobs = ["", "XYZ", "A25-85", "MH1", "PMEI", "PMEI 2019-2", "TMP 128", "J22",
                 "M10 146", "A25 141", "2XM", "STA 42", "CLB", "LEA", "SLD"]
        for c in cands:
            blobs.append(_line(c | {"rarity": "common", "artist": "x"}, 0))
            blobs.append(_confuse(f"{c['set']} {c['collector_number']}", rng))
        for blob in blobs:
            for detected in (True, False):
                if not detected and blob not in ("MH1", "A25-85"):
                    continue
                run(key, blob, detected)
    return {"lists": lists, "rows": rows}


def build_sort_ranked() -> list:
    shapes = [
        [{"id": "a", "multi_distance": 5}, {"id": "b", "multi_distance": 3},
         {"id": "c", "multi_distance": 3}, {"id": "d", "multi_distance": 1}],
        [{"id": "a", "phash_distance": 9}, {"id": "b", "multi_distance": 4},
         {"id": "c"}, {"id": "d", "multi_distance": None, "phash_distance": 2},
         {"id": "e", "multi_distance": 9}, {"id": "f"}],
        [{"id": "x", "multi_distance": 2.5}, {"id": "y", "multi_distance": 2},
         {"id": "z", "phash_distance": 2}],
        [],
    ]
    rows = []
    for s in shapes:
        # the exact sort pipeline._ranked_candidates applies
        got = sorted(s, key=lambda p: p["multi_distance"] if p.get("multi_distance")
                     is not None else p.get("phash_distance", 1 << 30))
        rows.append({"in": s, "out": [p["id"] for p in got]})
    return rows


# server/app.py scan-time auto-pick, mirrored verbatim; build() asserts the
# source still reads exactly like this.
_AUTO_PICK_SRC = """        cands = result.get("candidates") or []
        if result["identified"] and cands and (
                len(cands) == 1 or cands[0].get("ocr_confirmed")
                or cands[0].get("art_decisive")):"""


def _server_auto_pick(result: dict) -> bool:
    cands = result.get("candidates") or []
    if result["identified"] and cands and (
            len(cands) == 1 or cands[0].get("ocr_confirmed")
            or cands[0].get("art_decisive")):
        return True
    return False


def build_auto_pick() -> list:
    src = (ROOT / "server" / "app.py").read_text(encoding="utf-8")
    assert _AUTO_PICK_SRC in src, \
        "server/app.py's auto-pick predicate changed — update the mirror + Kotlin port"
    rows = []
    cand_sets = [None, [], [{"id": "a"}], [{"id": "a"}, {"id": "b"}],
                 [{"id": "a", "ocr_confirmed": True}, {"id": "b"}],
                 [{"id": "a", "art_decisive": True}, {"id": "b"}],
                 [{"id": "a", "ocr_confirmed": False, "art_decisive": False}, {"id": "b"}],
                 [{"id": "a"}, {"id": "b", "ocr_confirmed": True}],
                 [{"id": "a", "ocr_confirmed": True}],
                 [{"id": "a", "art_decisive": 1}, {"id": "b"}],
                 [{"id": "a", "ocr_confirmed": ""}, {"id": "b"}]]
    for identified in (True, False):
        for cs in cand_sets:
            r = {"identified": identified}
            if cs is not None:
                r["candidates"] = cs
            rows.append({"result": r, "expect": _server_auto_pick(r)})
    return rows


class _FakeIndex:
    def __init__(self, per_frame, raise_exc=None):
        self.per_frame, self.raise_exc, self.calls = per_frame, raise_exc, []

    def identify(self, frame, top_n=5):
        k = int(frame[0, 0, 0])
        self.calls.append(k)
        if self.raise_exc is not None:
            raise self.raise_exc
        return copy.deepcopy(self.per_frame[k])


def _m(name, d, sid="sid", s="m10", cn="146", artist="Christopher Moeller"):
    return {"name": name, "scryfall_id": sid, "set": s, "collector_number": cn,
            "artist": artist, "distance": d}


def build_scan(printings: dict) -> list:
    rows = []
    bolt12 = _cands(printings["Lightning Bolt"])[-12:]
    for i, c in enumerate(bolt12):
        c["multi_distance"] = 90 + 4 * i
    bolt12[0]["ocr_confirmed"] = True

    def run(note, frames, index=True, raise_exc=None, ranked="ok"):
        """frames: list of {sharp, blank, matches}."""
        arrs = [np.full((2, 2, 3), k, np.uint8) for k in range(len(frames))]
        ranked_calls = []

        def fake_ranked(frame, name, top_n):
            ranked_calls.append([int(frame[0, 0, 0]), name, top_n])
            if ranked == "raise":
                raise RuntimeError("HTTP 503 from Scryfall — try later")
            if ranked == "raise-key":
                raise KeyError("image_uris")
            return copy.deepcopy(bolt12)
        idx = _FakeIndex([f["matches"] for f in frames], raise_exc) if index else None
        pl = pipeline.Pipeline(idx, None)
        pl._ranked_candidates = fake_ranked
        with _patched(pipeline,
                      frame_sharpness=lambda a: frames[int(a[0, 0, 0])]["sharp"],
                      extract_card=lambda a: (a, True),
                      is_blank_surface=lambda img, detected=True:
                          frames[int(img[0, 0, 0])]["blank"]), \
                contextlib.redirect_stdout(io.StringIO()):
            res = pl.scan_candidates(arrs if len(arrs) != 1 else arrs[0])
        auto = _server_auto_pick(res)
        if res.get("candidates") == bolt12:
            res = dict(res, candidates="@ranked")      # = "ranked" beside "rows"
        rows.append({"note": note, "frames": frames, "index": index,
                     "raise": None if raise_exc is None else
                     [type(raise_exc).__name__, str(raise_exc)],
                     "ranked": ranked, "out": res,
                     "identify_calls": [] if idx is None else idx.calls,
                     "ranked_calls": ranked_calls,
                     "auto_pick": auto})

    def F(matches, sharp=1.0, blank=False):
        return {"sharp": sharp, "blank": blank, "matches": matches}

    bolt = "Lightning Bolt"
    run("no index", [F([_m(bolt, 50)])], index=False)
    run("all blank", [F([_m(bolt, 50)], blank=True), F([], 2.0, True)])
    run("one blank of two", [F([_m(bolt, 50)], 1.0, True), F([_m(bolt, 60)], 2.0)])
    run("ArtIndexError", [F([])], raise_exc=ArtIndexError("index not built — run build"))
    run("other exception", [F([])], raise_exc=ValueError("bad é frame"))
    run("no matches", [F([])])
    for d in (0, 50, 89, 90, 91, 109, 110, 111, 139, 140, 141, 209, 210, 211, 400):
        for gap in (None, 19, 20, 21):
            ms = [_m(bolt, d)]
            if gap is not None:
                ms += [_m("Chain Lightning", d + gap, "sid2", "sth", "80", "Randy Gallegos"),
                       _m("Burst Lightning", d + gap + 7, "sid3", "zen", "119", "Vance Kovacs")]
            for ranked in ("ok", "raise"):
                run(f"d={d} gap={gap} ranked={ranked}", [F(ms)], ranked=ranked)
    run("ranked raises KeyError", [F([_m(bolt, 60)])], ranked="raise-key")
    run("non-ascii names", [F([_m("Æther Vial", 115),
                               _m("Lim-Dûl's Vault", 150),
                               _m("Emoji \U0001F600 Card", 151)])])
    run("non-ascii confident", [F([_m("Jötun Grunt", 70)])], ranked="raise")
    # _identify's multi-frame retry: sharpest first, retry while unconfident,
    # a retry replaces only when strictly better.
    run("retry better", [F([_m(bolt, 130)], 5.0), F([_m("Shock", 100)], 3.0)])
    run("retry worse", [F([_m(bolt, 130)], 5.0), F([_m("Shock", 131)], 3.0)])
    run("retry equal", [F([_m(bolt, 130)], 5.0), F([_m("Shock", 130)], 3.0)])
    run("retry stops when confident", [F([_m(bolt, 150)], 1.0), F([_m("Shock", 100)], 9.0),
                                       F([_m("Opt", 10)], 4.0)])
    run("retry empty first", [F([], 9.0), F([_m("Shock", 170)], 1.0)])
    run("retry empty later", [F([_m(bolt, 170)], 9.0), F([], 1.0)])
    run("sharpness ties keep order", [F([_m("A", 150)], 2.0), F([_m("B", 140)], 2.0),
                                      F([_m("C", 130)], 2.0)])
    run("margin via retry", [F([_m(bolt, 145), _m("X", 150)], 3.0),
                             F([_m("Shock", 125), _m("Y", 145)], 2.0)])
    return {"ranked": bolt12, "rows": rows}


# ── bundle ──────────────────────────────────────────────────────────────────

def build() -> dict[str, bytes]:
    printings = json.loads((OUT / "printings.json").read_text(encoding="utf-8"))
    lists, match_rows = build_match(printings)
    strip, files = build_strip()
    expected = {
        "constants": {
            "high_confidence": art_index._HIGH_CONFIDENCE_DISTANCE,
            "max_confident": art_index._MAX_CONFIDENT_DISTANCE,
            "margin_confident": art_index._MARGIN_CONFIDENT_DISTANCE,
            "margin_min_gap": art_index._MARGIN_MIN_GAP,
            "no_card": art_index._NO_CARD_DISTANCE,
            "ocr_art_slack": pipeline._OCR_ART_SLACK,
            "art_decisive_gap": pipeline._ART_DECISIVE_GAP,
            "art_decisive_ceiling": pipeline._ART_DECISIVE_CEILING,
            "confusion": ocr_id._CONFUSION,
        },
        "canon": build_canon(),
        "canon_words": build_canon_words(),
        "lists": lists,
        "match": match_rows,
        "strip": strip,
        "confidence_for": build_confidence(),
        "is_confident": build_is_confident(),
        "art_agrees": build_art_agrees(),
        "mark_art_decisive": build_mark_art_decisive(),
        "apply_ocr_hint": build_apply_ocr_hint(printings),
        "sort_ranked": build_sort_ranked(),
        "auto_pick": build_auto_pick(),
        "scan": build_scan(printings),
    }
    files["expected.json"] = (json.dumps(expected, indent=None, sort_keys=True,
                                         ensure_ascii=True, separators=(",", ":"))
                              .replace('],["', '],\n["') + "\n").encode()
    return files


def main() -> int:
    if "--refresh-inputs" in sys.argv:
        refresh_inputs()
    files = build()
    if "--check" in sys.argv:
        stale = [n for n, b in files.items()
                 if not (OUT / n).exists() or not _same(n, (OUT / n).read_bytes(), b)]
        if stale:
            print("ocr fixtures are stale — run: python scripts/export_ocr_fixtures.py")
            for n in stale:
                print("  ", n)
            return 1
        n_match = len(json.loads(files["expected.json"])["match"])
        print(f"ocr fixtures up to date ({len(files)} files, {n_match} match rows)")
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    for n, b in files.items():
        (OUT / n).write_bytes(b)
    print(f"wrote {', '.join(sorted(files))} to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
