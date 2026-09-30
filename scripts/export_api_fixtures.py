"""
Export golden request/response fixtures for the phone's scan API (Stage 3b).

Stage 3 of docs/PHONE_ONLY_PLAN.md moves the server onto the phone: the SAME
review pages (phone.html / desktop.html) talk to a Kotlin server, so every
endpoint must answer with the JSON the Python server gives. This script drives
the REAL server/app.py (FastAPI TestClient over a real ScanStore) through a
scripted session — list, detail, pick, edit, delete, clear — and records every
request and response. The Kotlin PhoneApi replays the same seeds + requests
and must produce equal JSON (android/core/src/test/.../PhoneApiParityTest.kt).

Two things are pinned so the run is deterministic:
  * the store's clock: every created_at / updated_at is "T0001", "T0002", …
    in call order (the Kotlin store takes the same counter);
  * FILE steps POST /api/scan with a canned identification (a fake pipeline
    returns it) — the server's real filing: no_card, retry replacement and the
    scan-time auto-pick; the phone files the same result via PhoneApi.fileScan;
  * the pricing sweep is marked ACTIVE for the whole run: the phone is always
    the ONE F2F consumer (CLAUDE.md "Pricing sweep"), so a pick or a finish
    change never prices inline — it clears f2f and leaves it to the sweep,
    exactly the Python server's sweep-active path.

    python scripts/export_api_fixtures.py           # (re)write expected.json
    python scripts/export_api_fixtures.py --check   # exit 1 if stale (CI)
"""

from __future__ import annotations

import copy
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import cv2  # noqa: E402
import numpy as np  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import server.store as store_mod  # noqa: E402
from server.app import create_app  # noqa: E402
from server.store import ScanStore  # noqa: E402

OUT = ROOT / "android" / "core" / "src" / "test" / "resources" / "api"
EXPECTED = OUT / "expected.json"
SWEEP_EXPECTED = OUT / "sweep.json"


def _cand(cid, name, set_code, cn, dist, *, ocr=False, pop=None, set_name=None):
    c = {"id": cid, "name": name, "set": set_code,
         "set_name": set_name or set_code.upper() + " set", "collector_number": cn,
         "finishes": ["nonfoil", "foil"], "image_small": f"https://img/{cid}-s.jpg",
         "image_normal": f"https://img/{cid}-n.jpg", "multi_distance": dist}
    if ocr:
        c["ocr_confirmed"] = True
    if pop is not None:
        c["popularity"] = pop
    return c


POP = {"tier": "staple", "label": "Staple", "rank": 158, "percentile": 0.5}

# Seeds: shapes the pages meet — an OCR-confirmed pick-list, a Fling-style
# clean artwork break (other_art on read), a single printing, a best-guess
# error row, a no-card-ish row with no candidates, and a wide same-art spread
# (Bone Splinters: nothing splits).
SEEDS = [
    dict(identified=True, card_read={"name": "Lightning Bolt", "condition_estimate": "lp"},
         confidence={"name": "high", "set": "high", "collector": "medium"},
         candidates=[_cand("b1", "Lightning Bolt", "m10", "146", 70, ocr=True, pop=POP),
                     _cand("b2", "Lightning Bolt", "2x2", "117", 82, pop=POP),
                     _cand("b3", "Lightning Bolt", "sta", "42", 150, pop=POP)]),
    dict(identified=True, card_read={"name": "Fling"},
         confidence={"name": "medium"},
         candidates=[_cand("f1", "Fling", "akh", "132", 78),
                     _cand("f2", "Fling", "m20", "140", 84),
                     _cand("f3", "Fling", "sld", "999", 146),
                     _cand("f4", "Fling", "ptk", "1", 190)]),
    dict(identified=True, card_read={"name": "Opt"}, confidence={"name": "high"},
         candidates=[_cand("o1", "Opt", "dom", "60", 60)]),
    dict(identified=False, card_read={"name": "Mountain"}, confidence={"name": "low"},
         candidates=[_cand("m1", "Mountain", "m21", "271", 148),
                     _cand("m2", "Mountain", "dmu", "276", 150)],
         error="No confident art match (best: 'Mountain' d=148). Use manual search."),
    dict(identified=False, card_read={}, confidence={}, candidates=[]),
    dict(identified=True, card_read={"name": "Bone Splinters"}, confidence={"name": "medium"},
         candidates=[_cand("s1", "Bone Splinters", "ala", "67", 124),
                     _cand("s2", "Bone Splinters", "m20", "92", 160),
                     _cand("s3", "Bone Splinters", "jmp", "207", 190)]),
]

def _result(identified, name, cands, *, error=None, no_card=False):
    r = {"identified": identified, "card_read": {"name": name} if name else {},
         "confidence": {"name": "high" if identified else "low"}, "candidates": cands}
    if error:
        r["error"] = error
    if no_card:
        r["no_card"] = True
    return r


# What the identifier hands /api/scan (pipeline.scan_candidates), one per FILE
# step. Filing: no_card → no row; a retry replaces a still-unpicked row; the
# auto-pick grounds (one printing / OCR-confirmed / art-decisive top).
_decisive = _cand("d1", "Counterspell", "7ed", "67", 40)
_decisive["art_decisive"] = True
FILED = [
    (_result(False, None, [], error="No card detected.", no_card=True), 0),
    (_result(True, "Sol Ring", [_cand("r1", "Sol Ring", "c21", "263", 55)]), 0),
    (_result(True, "Swords", [_cand("w1", "Swords to Plowshares", "sta", "10", 60, ocr=True),
                              _cand("w2", "Swords to Plowshares", "ema", "27", 64)]), 0),
    (_result(True, "Counterspell", [_decisive, _cand("d2", "Counterspell", "tmp", "57", 120)]), 0),
    (_result(True, "Murder", [_cand("u1", "Murder", "m20", "109", 70),
                              _cand("u2", "Murder", "tsr", "123", 72)]), 0),
    (_result(False, "Shock", [_cand("h1", "Shock", "m19", "156", 150),
                              _cand("h2", "Shock", "sta", "44", 152)],
             error="No confident art match (best: 'Shock' d=150). Use manual search."), 0),
    (_result(True, "Shock", [_cand("h1", "Shock", "m19", "156", 90, ocr=True)]), 11),   # retry → replaces 11
    (_result(True, "Opt", [_cand("o1", "Opt", "dom", "60", 60)]), 7),                  # 7 is picked → kept
]

B2 = SEEDS[0]["candidates"][1]
F1 = SEEDS[1]["candidates"][0]

# (method, path, json body or None). Ids: seeds are 1..6 in creation order.
STEPS = [
    ("GET", "/api/scans", None),
    ("GET", "/api/scans/2", None),
    ("GET", "/api/scans/999", None),
    # picks
    ("POST", "/api/scans/1/select", {"printing": B2, "condition": "mp", "finish": "Foil",
                                     "quantity": "2"}),
    ("POST", "/api/scans/2/select", {"printing": F1}),            # no condition → NM
    ("POST", "/api/scans/4/select", {"printing": {"id": "m9", "name": "Mountain", "set": "m21",
                                                  "collector_number": "271"},
                                     "finish": "nonfoil", "quantity": 0}),   # error cleared
    ("POST", "/api/scans/3/select", {"printing": {"set": "dom"}}),          # 400
    ("POST", "/api/scans/3/select", {"printing": {"collector_number": "60"}}),  # 400
    ("POST", "/api/scans/999/select", {"printing": B2}),                     # 404
    ("POST", "/api/scans/6/select", {"printing": SEEDS[5]["candidates"][0],
                                     "condition": "hp", "quantity": 3.7, "finish": "Etched"}),
    # edits
    ("PATCH", "/api/scans/1", {"quantity": 0}),
    ("PATCH", "/api/scans/1", {"condition": "dmg"}),
    ("PATCH", "/api/scans/1", {"finish": "Non-Foil"}),      # sweep active → f2f cleared
    ("PATCH", "/api/scans/1", {"included": False}),
    ("PATCH", "/api/scans/1", {"included": 1, "quantity": "4", "finish": "Foil"}),
    ("PATCH", "/api/scans/3", {"condition": "LP", "quantity": 2}),   # pending: never invents one
    ("PATCH", "/api/scans/3", {"included": 0}),
    ("PATCH", "/api/scans/3", {}),                                   # no-op, no clock tick
    ("PATCH", "/api/scans/999", {"included": True}),                 # 404
    # re-pick
    ("POST", "/api/scans/1/select", {"printing": SEEDS[0]["candidates"][0], "condition": "nm"}),
    ("GET", "/api/scans/1", None),
    # filing (POST /api/scan with the canned identification FILED[i])
    *[("FILE", "/api/scan", {"filed": i}) for i in range(len(FILED))],
    ("GET", "/api/scans", None),
    # deletes
    ("DELETE", "/api/scans/5", None),
    ("DELETE", "/api/scans/5", None),
    ("GET", "/api/scans", None),
    ("POST", "/api/scans/delete-all", {"only": "Unselected"}),
    ("GET", "/api/scans", None),
    ("POST", "/api/scans/delete-all", None),
    ("GET", "/api/scans", None),
]


def build() -> dict:
    ticks = [0]

    def clock() -> str:
        ticks[0] += 1
        return f"T{ticks[0]:04d}"

    real_now = store_mod._now
    store_mod._now = clock
    try:
        with tempfile.TemporaryDirectory() as tmp:
            store = ScanStore(Path(tmp) / "s.db")
            queue: list = []

            class CannedPipeline:
                def scan_candidates(self, frames):
                    return copy.deepcopy(queue.pop(0))

            ok, jpg = cv2.imencode(".jpg", np.full((88, 63, 3), 128, np.uint8))
            assert ok
            try:
                app = create_app(pipeline_factory=lambda: CannedPipeline(), store=store, f2f=object(),
                                 scan_images_dir=Path(tmp) / "img", auto_sweep_interval=None)
                app.state.sweep["active"] = True      # the phone: always the ONE F2F consumer
                client = TestClient(app)
                for s in SEEDS:
                    store.create_scan(**copy.deepcopy(s))
                steps = []
                for method, path, body in STEPS:
                    if method == "FILE":
                        result, replace = FILED[body["filed"]]
                        queue.append(result)
                        r = client.post(path, files=[("files", ("c.jpg", jpg.tobytes(), "image/jpeg"))],
                                        data={"replace_scan_id": str(replace)})
                        steps.append({"method": method, "path": path, "result": result,
                                      "replace_scan_id": replace,
                                      "status": r.status_code, "response": r.json()})
                        continue
                    kw = {} if body is None else {"json": body}
                    r = client.request(method, path, **kw)
                    steps.append({"method": method, "path": path, "body": body,
                                  "status": r.status_code, "response": r.json()})
            finally:
                store.close()
    finally:
        store_mod._now = real_now
    return {"clock": "T%04d", "seeds": SEEDS, "steps": steps}


# ── session 2: pricing (Stage 3c) ────────────────────────────────────────────
# A fake F2F answers from PRICES by (set, collector, foil): conditions →
# F2FPrice, "none" → None (a confirmed miss), "unavailable" → F2FUnavailable
# Error. HOOKS apply a store write WHILE a given lookup runs — a pick landing
# mid-sweep — so the before-fetch recheck and write-if-current are exercised.

def _p(conds, handle):
    return {"conditions": conds, "handle": handle}


PRICES = {
    "m10|146|0": _p({"NM": 3.49, "PL": 2.79}, "lightning-bolt-m10"),
    "2x2|117|1": _p({"NM": 9.99}, "lightning-bolt-2x2-foil"),
    "akh|132|0": _p({"NM": 0.35, "PL": 0.25}, "fling-akh"),
    "m20|140|0": "none",
    "dom|60|0": _p({"NM": 0.25}, "opt-dom"),
    "xln|65|0": _p({"NM": 0.3}, "opt-xln"),
    "c21|263|0": _p({"NM": 1.5}, "sol-ring-c21"),
    "sta|10|0": "unavailable",
}
for _i in range(6):
    PRICES[f"brk|{_i}|0"] = "unavailable"
HOOKS = {
    # while Fling AKH (scan 4's first print) is priced, scan 4 gets PICKED →
    # its remaining print target (M20) is skipped without a fetch, and the
    # AKH answer is not written onto the now-selected scan's candidates
    "akh|132|0": {"scan": 4, "fields": {"status": "selected", "selection": {
        "scryfall_id": "f1", "name": "Fling", "set": "akh", "set_name": "AKH set",
        "collector_number": "132", "condition": "NM", "finish": "Non-Foil", "quantity": 1,
        "foil": False, "image_normal": "", "popularity": None}}},
    # while Bolt M10 (scan 3's selection) is priced, scan 3 is RE-PICKED to 2X2
    # foil → the stale M10 price must not be written; 2X2 foil gets priced later
    "m10|146|0": {"scan": 3, "fields": {"selection": {
        "scryfall_id": "b2", "name": "Lightning Bolt", "set": "2x2", "set_name": "2X2 set",
        "collector_number": "117", "condition": "NM", "finish": "Foil", "quantity": 1,
        "foil": True, "image_normal": "", "popularity": None}}},
}


def _sel(c, finish="Non-Foil"):
    return {"scryfall_id": c["id"], "name": c["name"], "set": c["set"], "set_name": c["set_name"],
            "collector_number": c["collector_number"], "condition": "NM", "finish": finish,
            "quantity": 1, "foil": finish != "Non-Foil", "image_normal": c["image_normal"],
            "popularity": None}


_BRK = [_cand(f"k{i}", "Breaker", "brk", str(i), 60 + i) for i in range(6)]
_BOLT = SEEDS[0]["candidates"]
_OPT = [_cand("o1", "Opt", "dom", "60", 60), _cand("o2", "Opt", "xln", "65", 64)]
# Seeds: (create_scan kwargs, selection or None). Ids 1.. in order. The sweep
# walks newest first, so the breaker scan (6 unavailable prints) is scan 1 —
# processed LAST, after everything else has been priced.
SWEEP_SEEDS = [
    (dict(identified=True, card_read={"name": "Breaker"}, confidence={}, candidates=_BRK), None),
    (dict(identified=True, card_read={"name": "Swords"}, confidence={},
          candidates=[_cand("w1", "Swords to Plowshares", "sta", "10", 60)]), None),
    (dict(identified=True, card_read={"name": "Lightning Bolt"}, confidence={}, candidates=_BOLT),
     _sel(_BOLT[0])),
    (dict(identified=True, card_read={"name": "Fling"}, confidence={},
          candidates=SEEDS[1]["candidates"]), None),
    (dict(identified=True, card_read={"name": "Opt"}, confidence={}, candidates=_OPT), None),
    (dict(identified=True, card_read={"name": "Sol Ring"}, confidence={},
          candidates=[_cand("r1", "Sol Ring", "c21", "263", 55)]), None),
    (dict(identified=True, card_read={"name": "Nameless"}, confidence={},
          candidates=[{"id": "n1", "set": "x", "collector_number": "1"}]), None),
]
SWEEP_STEPS = [
    ("GET", "/api/price-status", None),
    ("POST", "/api/scans/6/price", None),            # pending, top candidate found
    ("POST", "/api/scans/2/price", None),            # unavailable → f2f_search, nothing written
    ("POST", "/api/scans/5/price", None),            # pending: top print found, written to f2f
    ("POST", "/api/scans/7/price", None),            # nothing to price → 400
    ("POST", "/api/scans/99/price", None),           # 404
    ("POST", "/api/price-sweep/stop", None),         # idle → not stopping (sets the pause)
    ("POST", "/api/scans/price-missing", None),      # clears the pause; runs; breaker at the end
    ("GET", "/api/scans", None),
    ("GET", "/api/price-status", None),
    ("POST", "/api/scans/3/price-check", None),      # 2X2 foil selection, unpriced after the re-pick
    ("GET", "/api/scans/3", None),
    ("POST", "/api/scans/4/price-check", None),      # Fling, picked mid-sweep: its selection
    ("POST", "/api/scans/6/price-check", None),      # Sol Ring: nothing left to search
    ("POST", "/api/scans/99/price-check", None),     # 404
    ("POST", "/api/scans/price-missing", None),      # overrides the cooldown; breaker again
    ("GET", "/api/price-status", None),
    ("GET", "/api/scans", None),
]


def build_sweep() -> dict:
    from mtg_card_scanner.facetoface import F2FPrice, F2FUnavailableError
    ticks = [0]

    def clock() -> str:
        ticks[0] += 1
        return f"T{ticks[0]:04d}"

    real_now = store_mod._now
    store_mod._now = clock
    lookups: list = []
    try:
        with tempfile.TemporaryDirectory() as tmp:
            store = ScanStore(Path(tmp) / "s.db")

            class FakeF2F:
                def get_price(self, name, set_code, collector_number, foil=False, set_name=""):
                    key = f"{set_code}|{collector_number}|{int(bool(foil))}"
                    lookups.append(key)
                    hook = HOOKS.get(key)
                    if hook:
                        store.update_scan(hook["scan"], **copy.deepcopy(hook["fields"]))
                    ans = PRICES.get(key, "none")
                    if ans == "unavailable":
                        raise F2FUnavailableError(key)
                    if ans == "none":
                        return None
                    return F2FPrice(name=name, set_code=set_code, collector_number=collector_number,
                                    foil=bool(foil), handle=ans["handle"],
                                    url=f"https://facetofacegames.com/products/{ans['handle']}",
                                    conditions=dict(ans["conditions"]))

            try:
                app = create_app(pipeline_factory=lambda: None, store=store, f2f=FakeF2F(),
                                 scan_images_dir=Path(tmp) / "img", auto_sweep_interval=None)
                client = TestClient(app)
                for kwargs, sel in SWEEP_SEEDS:
                    row = store.create_scan(**copy.deepcopy(kwargs))
                    if sel:
                        store.update_scan(row["id"], status="selected", selection=copy.deepcopy(sel))
                steps = []
                for method, path, body in SWEEP_STEPS:
                    before = len(lookups)
                    kw = {} if body is None else {"json": body}
                    r = client.request(method, path, **kw)
                    steps.append({"method": method, "path": path, "body": body,
                                  "status": r.status_code, "response": r.json(),
                                  "lookups": lookups[before:]})
            finally:
                store.close()
    finally:
        store_mod._now = real_now
    return {"clock": "T%04d", "prices": PRICES, "hooks": HOOKS,
            "seeds": [{"create": k, "selection": s} for k, s in SWEEP_SEEDS], "steps": steps}


def _dumps(obj) -> str:
    return json.dumps(obj, indent=1, ensure_ascii=False) + "\n"


def main() -> int:
    outputs = {EXPECTED: _dumps(build()), SWEEP_EXPECTED: _dumps(build_sweep())}
    if "--check" in sys.argv:
        for path, text in outputs.items():
            current = path.read_text(encoding="utf-8") if path.exists() else None
            if current != text:
                print(f"api fixtures are stale ({path.name}) — run: python scripts/export_api_fixtures.py")
                return 1
        print(f"api fixtures up to date ({len(STEPS)} + {len(SWEEP_STEPS)} steps)")
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    for path, text in outputs.items():
        path.write_text(text, encoding="utf-8")
    print(f"wrote {EXPECTED.relative_to(ROOT)} ({len(STEPS)} steps), "
          f"{SWEEP_EXPECTED.relative_to(ROOT)} ({len(SWEEP_STEPS)} steps)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
