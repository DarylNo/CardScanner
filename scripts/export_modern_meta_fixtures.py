"""Export the Modern play data golden fixtures for the Kotlin port.

    python scripts/export_modern_meta_fixtures.py          # write
    python scripts/export_modern_meta_fixtures.py --check  # CI: fail if stale

Writes android/core/src/test/resources/modernmeta/expected.json from the
REFERENCE code (mtg_card_scanner/modern_meta.py) and the REAL server/app.py:
the tiers, every parse answer (good file and each refusal), the read-time
answer for a table of popularity / name pairs, the per-scan answer and flag,
the status, and an API session (GET /api/scans, /api/scans/{id},
/api/modern-meta) with the data off, on, and replaced. ModernMetaParityTest
(core) must match every entry. The builder (CI) is not ported — the phone only
reads the published file.
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from fastapi.testclient import TestClient  # noqa: E402

import server.store as store_mod  # noqa: E402
from mtg_card_scanner import modern_meta as mm  # noqa: E402
from server.app import create_app  # noqa: E402
from server.store import ScanStore  # noqa: E402

OUT = ROOT / "android/core/src/test/resources/modernmeta/expected.json"

SEIZE = "00000000-0000-0000-0000-00000000000a"
PUSH = "00000000-0000-0000-0000-00000000000b"
FABLE = "00000000-0000-0000-0000-00000000000c"
SPIDEY = "00000000-0000-0000-0000-00000000000d"
BOLT = "00000000-0000-0000-0000-00000000000e"
ISLAND = "00000000-0000-0000-0000-00000000000f"
TITHE = "00000000-0000-0000-0000-000000000010"


def _doc(**over):
    doc = {
        "kind": mm.PACK_KIND, "version": mm.PACK_VERSION, "format": "modern",
        "as_of": "2026-11-10", "window_days": 60, "built_at": "2026-11-10T07:41:02Z",
        "decks": 16831, "events": 256,
        "sources": {"mtgo": {"decks": 6146, "events": 177, "weighted": True},
                    "paper": {"decks": 10685, "events": 79, "weighted": True}},
        "unmatched_names": 22, "unmatched_entries": 23,
        "cards": {SEIZE: [2689, 9], BOLT: [2560, 12], PUSH: [1000, 50], FABLE: [232, 297],
                  SPIDEY: [178, 325], "00000000-0000-0000-0000-000000000011": [1, 2400]},
        "basic": [ISLAND],
        "names": {"thoughtseize": SEIZE, "fatal push": PUSH,
                  "fable of the mirror-breaker // reflection of kiki-jiki": FABLE,
                  "fable of the mirror-breaker": FABLE, "reflection of kiki-jiki": FABLE,
                  "superior spider-man": SPIDEY, "island": ISLAND, "æther vial": "00000000-0000-0000-0000-000000000011"},
        "ambiguous": ["lightning bolt", "pick your poison"],
    }
    doc.update(over)
    return doc


PACK_TEXT = mm.dump_pack(_doc())
PACK = mm.parse_pack(PACK_TEXT)
PACK2_TEXT = mm.dump_pack(_doc(as_of="2026-11-17", decks=17000, cards={SEIZE: [2400, 11], TITHE: [3, 1900]}))


def _bad(**over):
    return json.dumps(_doc(**over))


def _without(key):
    d = _doc()
    del d[key]
    return json.dumps(d)


PARSE = [
    PACK_TEXT,
    PACK2_TEXT,
    json.dumps(_doc(built_at=None)),            # built_at optional
    json.dumps(_doc(built_at=7)),               # a non-string built_at reads as none
    json.dumps(_doc(extra_key="ignored", sources={"mtgo": {"decks": 1, "events": 1, "weighted": False, "x": 1}})),
    "{nope", "", "null", "[]", "42",
    json.dumps({"kind": "something else"}),
    _bad(version=2), _bad(version="1"), _bad(version=1.0),
    _bad(cards={SEIZE: [0, 1]}), _bad(cards={SEIZE: [1, 0]}), _bad(cards={SEIZE: [2689]}),
    _bad(cards={SEIZE: [2689, 9, 1]}), _bad(cards={SEIZE: [True, 1]}), _bad(cards={SEIZE: [2689.0, 9]}),
    _bad(cards={SEIZE: ["2689", 9]}), _bad(cards={SEIZE: None}), _bad(cards=[]),
    _bad(basic="island"), _bad(basic=[1]), _bad(names={"x": 1}), _bad(names=[]),
    _bad(ambiguous="x"), _bad(ambiguous=[None]),
    _bad(decks="many"), _bad(decks=True), _bad(events=None), _bad(window_days=60.5), _bad(as_of=20261110),
    _bad(sources={"mtgo": {"decks": 1}}), _bad(sources={"mtgo": {"decks": 1, "events": 1, "weighted": 1}}),
    _bad(sources=[]),
    _without("cards"), _without("as_of"), _without("sources"),
]


def _pack_json(p):
    """A parsed pack in a JSON shape both sides can compare."""
    return {"as_of": p["as_of"], "window_days": p["window_days"], "built_at": p["built_at"],
            "decks": p["decks"], "events": p["events"], "sources": p["sources"],
            "cards": {k: list(v) for k, v in sorted(p["cards"].items())},
            "basic": sorted(p["basic"]), "names": dict(sorted(p["names"].items())),
            "ambiguous": sorted(p["ambiguous"])}


def _parse_case(text):
    try:
        return {"text": text, "pack": _pack_json(mm.parse_pack(text))}
    except mm.ModernMetaError as e:
        return {"text": text, "error": str(e)}


def pop(oid=None, modern=None, **extra):
    p = {"tier": "solid", "label": "Popular", "top_percent": 7.4}
    if oid is not None:
        p["oracle_id"] = oid
    if modern is not None:
        p["formats"] = {"standard": "not_legal", "modern": modern}
    p.update(extra)
    return p


MODERN_FOR = [
    (pop(SEIZE, "legal"), "Thoughtseize"),
    (pop(PUSH, "legal"), "Fatal Push"),                    # exactly 10 %: staple
    (pop(FABLE, "legal"), "x"),
    (pop(SPIDEY, "legal"), None),
    (pop("00000000-0000-0000-0000-000000000011", "legal"), "Aether Vial"),
    (pop(ISLAND, "legal"), "Island"),
    (pop(ISLAND), None),
    (pop(TITHE, "legal"), "Smothering Tithe"),
    (pop(TITHE, "restricted"), "Smothering Tithe"),
    (pop(TITHE, "banned"), "Smothering Tithe"),
    (pop(TITHE, "not_legal"), "Smothering Tithe"),
    (pop(TITHE, "something new"), "Smothering Tithe"),
    (pop(TITHE), "Smothering Tithe"),
    (pop(SEIZE, "not_legal"), "Thoughtseize"),             # membership beats a stale snapshot
    (pop(None, "legal"), "Thoughtseize"),
    (pop(None, "legal"), "  THOUGHTSEIZE  "),
    (pop(None, "legal"), "Fable of the Mirror-Breaker // Reflection of Kiki-Jiki"),
    (pop(None, "legal"), "Fable of the Mirror-Breaker"),
    (pop(None, "legal"), "Reflection of Kiki-Jiki"),
    (pop(None, "legal"), "Æther Vial"),
    (pop(None, "legal"), "Æther Vial"),
    (pop(None, "legal"), "Lightning Bolt"),                # ambiguous by name
    (pop(None, "legal"), "Lightning Bolt // Something"),
    (pop(BOLT, "legal"), "Lightning Bolt"),
    (pop(None, "legal"), "Pick Your Poison"),
    (pop(None, "legal"), "Smothering Tithe"),
    (pop(None, "banned"), "Hogaak, Arisen Necropolis"),
    (pop(None), "Smothering Tithe"),
    (pop(None, "legal"), None),
    (pop(None, "legal"), ""),
    (pop(None, "legal"), "   "),
    (pop("", "legal"), "Thoughtseize"),                    # an empty oracle_id is no oracle_id
    (pop(7, "legal"), "Thoughtseize"),                     # nor is a non-string one
    ({"formats": "x"}, "Smothering Tithe"),
    ({}, "Thoughtseize"),
    (None, "Thoughtseize"),
    (None, "Smothering Tithe"),
    (None, None),
    ("not a dict", "Island"),
]


def _cand(name, popularity="none"):
    c = {"id": f"p-{name}", "name": name, "set": "tst", "collector_number": "1"}
    if popularity != "none":
        c["popularity"] = popularity
    return c


SCANS = [
    {"id": 1, "candidates": [_cand("Thoughtseize", pop(SEIZE, "legal")), _cand("Fatal Push", pop(PUSH, "legal"))]},
    {"id": 2, "candidates": [_cand("Thoughtseize", pop(SEIZE, "legal"))],
     "selection": {"name": "Fatal Push", "popularity": pop(PUSH, "legal")}},
    {"id": 3, "candidates": [_cand("Thoughtseize", pop(SEIZE, "legal"))],
     "selection": {"name": "Fatal Push", "popularity": None}},
    {"id": 4, "candidates": [_cand("Thoughtseize", {})],
     "selection": {"name": "Fatal Push", "popularity": {}}},
    {"id": 5, "candidates": [], "card_read": {"name": "Thoughtseize"}},
    {"id": 6, "candidates": []},
    {"id": 7, "candidates": [_cand("Island", pop(None, "legal"))]},
    {"id": 8, "candidates": [_cand("Smothering Tithe", pop(None, "legal"))]},
    {"id": 9, "candidates": [_cand("Smothering Tithe")], "card_read": {"name": "Island"}},
    {"id": 10, "candidates": [{"id": "x", "popularity": pop(None, "legal")}], "card_read": {"name": "Thoughtseize"}},
    {"id": 11, "candidates": [{"id": "x", "name": " ", "popularity": pop(None, "legal")}],
     "selection": {"name": "Fatal Push"}},
    {"id": 12, "candidates": "not a list", "selection": "not a dict", "card_read": {"name": "Island"}},
    {"id": 13, "candidates": [None, _cand("Thoughtseize", pop(SEIZE, "legal"))]},
    {"id": 14, "candidates": [_cand("Lightning Bolt", pop(None, "legal"))]},
]


def _api_session():
    """The REAL app.py: a fixed provider flipped between no data and two packs."""
    ticks = {"n": 0}

    def clock():
        ticks["n"] += 1
        return f"T{ticks['n']:04d}"

    old_now = store_mod._now
    store_mod._now = clock

    class Provider:
        pack = None

        def current(self):
            return self.pack

    provider = Provider()
    steps = []
    try:
        with tempfile.TemporaryDirectory() as td:
            store = ScanStore(str(Path(td) / "scans.db"))
            app = create_app(pipeline_factory=lambda: None, store=store, f2f=object(),
                             scan_images_dir=Path(td) / "img", auto_sweep_interval=None,
                             modern_meta=provider)
            app.state.sweep["active"] = True
            client = TestClient(app)
            seeds = [
                {"identified": True, "card_read": {"name": "Thoughtseize"}, "confidence": {},
                 "candidates": [_cand("Thoughtseize", pop(SEIZE, "legal"))]},
                {"identified": True, "card_read": {"name": "Smothering Tithe"}, "confidence": {},
                 "candidates": [_cand("Smothering Tithe", pop(TITHE, "legal"))]},
                {"identified": True, "card_read": {"name": "Island"}, "confidence": {},
                 "candidates": [_cand("Island", pop(None, "legal"))]},
                {"identified": False, "card_read": {"name": ""}, "confidence": {}, "candidates": [],
                 "error": "no card"},
            ]
            for s in seeds:
                store.create_scan(**s)
            script = [
                ("GET", "/api/scans", None), ("GET", "/api/scans/1", None), ("GET", "/api/modern-meta", None),
                ("DATA", PACK_TEXT, None),
                ("GET", "/api/scans", None), ("GET", "/api/scans/1", None), ("GET", "/api/scans/4", None),
                ("GET", "/api/scans/99", None), ("GET", "/api/modern-meta", None),
                ("POST", "/api/scans/2/select", {"printing": _cand("Thoughtseize", pop(SEIZE, "legal")),
                                                 "condition": "NM", "finish": "nonfoil", "quantity": 1}),
                ("GET", "/api/scans/2", None),
                ("DATA", PACK2_TEXT, None),
                ("GET", "/api/scans", None), ("GET", "/api/modern-meta", None),
                ("DATA", None, None),
                ("GET", "/api/scans/1", None), ("GET", "/api/modern-meta", None),
            ]
            for method, path, body in script:
                if method == "DATA":
                    provider.pack = mm.parse_pack(path) if path else None
                    steps.append({"op": "data", "text": path})
                    continue
                r = client.request(method, path, json=body) if body is not None else client.request(method, path)
                steps.append({"method": method, "path": path, "body": body,
                              "status": r.status_code, "response": r.json()})
            seeded = [{k: v for k, v in s.items()} for s in seeds]
    finally:
        store_mod._now = old_now
    return {"seeds": seeded, "steps": steps}


def build() -> dict:
    return {
        "constants": {"kind": mm.PACK_KIND, "version": mm.PACK_VERSION, "staple_bp": mm.STAPLE_BP,
                      "played_bp": mm.PLAYED_BP, "tiers": list(mm.TIERS), "file": mm.PACK_FILE,
                      "tag": mm.PACK_TAG, "window_days": mm.WINDOW_DAYS},
        "tier_for_bp": [[b, mm.tier_for_bp(b)] for b in (1, 2, 199, 200, 201, 999, 1000, 1001, 9999, 10000)],
        "parse": [_parse_case(t) for t in PARSE],
        "pack_text": PACK_TEXT,
        "modern_for": [{"popularity": p, "name": n, "expected": mm.modern_for(p, n, PACK)} for p, n in MODERN_FOR]
                      + [{"popularity": pop(SEIZE, "legal"), "name": "Thoughtseize", "no_pack": True,
                          "expected": mm.modern_for(pop(SEIZE, "legal"), "Thoughtseize", None)}],
        "scan_modern": [{"scan": s, "expected": mm.scan_modern(s, PACK)} for s in SCANS],
        "flag": [{"scan": s, "pack": with_pack,
                  "expected": mm.flag_modern(json.loads(json.dumps(s)), PACK if with_pack else None)}
                 for s in SCANS[:3] + SCANS[5:6] for with_pack in (False, True)],
        "status": {"none": mm.pack_status(None), "pack": mm.pack_status(PACK)},
        "api": _api_session(),
    }


def main() -> int:
    text = json.dumps(build(), indent=1, ensure_ascii=False) + "\n"
    if "--check" in sys.argv:
        have = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if have != text:
            print("modern-meta fixtures are stale — run: python scripts/export_modern_meta_fixtures.py")
            return 1
        print("modern-meta fixtures up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
