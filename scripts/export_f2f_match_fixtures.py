"""
Export the F2F MATCHING golden fixture for the Android app's JVM tests.

The phone prices with a port of mtg_card_scanner/facetoface.py's
FaceToFaceClient.get_price (io.github.darylno.cardscanner.f2f.F2fPricer). Its
pacing has its own fixture (export_f2f_pacing_fixture.py); this one pins the
MATCHING — which product a printing resolves to — so the two cannot drift:

  * function tables: the REAL _parse_title_brackets / _norm_collector /
    _sku_matches_set / _norm_name / _title_names_card over every product title
    and SKU in the captured storefront answers (plus a few hand-written edge
    shapes, listed in EXTRA_* below — inputs only, the answers are the
    reference's);
  * sessions: the REAL get_price replayed over storefront answers captured live
    on 2026-11-04 (tests/fixtures/facetoface/captured/, one file per URL, trimmed
    to the fields the client reads) and the older m10/m11 Bolt fixture — every
    URL requested, in order, which body answered it, and the result (handle +
    conditions, or null). Where a ladder rung was never captured, the session
    DECLARES a stand-in (another captured answer) and the fixture lists the URL
    under "stand_ins": those answers are real F2F bodies, just for another query.
    Any undeclared uncaptured URL fails the export.

Writes android/app/src/test/resources/f2f/match.json; F2fMatchParityTest replays
(a) through F2fPricer's companion functions and (b) through F2fPricer over a fake
transport answering from the same bodies. Change facetoface.py first, then
regenerate — never hand-edit the fixture.

    python scripts/export_f2f_match_fixtures.py          # (re)write
    python scripts/export_f2f_match_fixtures.py --check  # exit 1 if stale (CI)
"""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from mtg_card_scanner import facetoface as m  # noqa: E402

OUT = ROOT / "android" / "app" / "src" / "test" / "resources" / "f2f" / "match.json"
FIX = ROOT / "tests" / "fixtures" / "facetoface"
CAPTURED = FIX / "captured"
LEGACY_SUGGEST = "tests/fixtures/facetoface/suggest_lightning_bolt.json"   # body id

# Hand-written shapes the captures don't cover (inputs only).
EXTRA_TITLES = [
    "Lightning Bolt [Rulebook Showcase] [Some Set] [Foil]",
    "Card [12★] [Set] [Foil]",
    "Card [1†] [Set] [Foil]",
    "Card [185j] [Core Set 2019] [Non-Foil]",
    "Card [M19-185j] [The List] [Non-Foil]",
    "Card [TAFR-1] [The List] [Foil]",
    "Card [ABCDEF-1] [The List] [Foil]",
    "Card [ABCDEFG-1] [The List] [Foil]",
    "Card [A-B-1] [The List] [Foil]",
    "Card [-1] [The List] [Foil]",
    "Card [1ab] [Set] [Foil]",
    "Card [ 141 ] [Set] [ Foil ]",
    "Card [١٤١] [Set] [Foil]",          # Arabic-Indic digits: not a collector
    "Card [2019 Judge Promo] [Promos] [Foil]",
    "Card [30th Anniversary Edition] [Set] [Non-Foil]",
    "Card [Promos] [Foil]",
    "Card [Foil]",
    "No brackets at all",
    "",
    "Lim-Dul's Vault [Alliances Promos] [Non-Foil]",
    "Fire // Ice [Promo] [Foil]",
    "Fire [Promo] [Foil]",
    "Ice [Promo] [Foil]",
    "_____ [Unhinged Promos] [Foil]",
    "Aether Vial [Promo] [Foil]",
]
EXTRA_COLLECTORS = [
    "PZ2-38", "pz2-038", "CLB-187", "STH-66", "JUD-78†", "M19-185j", "0", "00", "000", "",
    "-", "0-0", "A-0", "007a", " 0083 ", "TAFR-01", "1-", "-01", "A25-0141",
]
SET_CODES = ["plst", "plist", "mb1", "fmb1", "ulst", "uplist", "ust", "pust", "cmb1", "cmb2",
             "nopw", "mb2", "clb", "a25", "tmp", "m10", "m11", "PLST", ""]
EXTRA_SKUS = [
    "SIN-MTG-CMB2-102-ENG-NM-NF", "SIN-MTG-A25-141-ENG-NM-NF", "M-M11-Lightning_-149-NM-NF",
    "SIN-MTG-CLB-309-ENG-NM-NF", "m-cmb1-nopw-x-102-nm-nf", "M-CMB1-NOPWX-x-102-NM-NF", "",
    "PLST", "plist",
]
EXTRA_NAMES = ["Fire // Ice", "Lim-Dûl's Vault", "Æther Vial", "Aether Vial", "_____", "Sol Ring",
               "Jötun Grunt", "İstanbul", "Dandân", "ﬁre", "Emeritus of Conflict"]

# Each session: get_price's arguments, and stand-ins for ladder rungs that were
# never captured (query text → the captured query whose answer stands in).
SESSIONS: list[dict] = [
    {"id": "bolt_plst_a25_141", "note": "List, PLIST-wave SKU (SIN-MTG-PLIST-429)",
     "card": ["Lightning Bolt", "plst", "A25-141", False, "The List"]},
    {"id": "tomb_plst_tmp_315", "note": "List, PLIST-wave SKU (SIN-MTG-PLIST-574)",
     "card": ["Ancient Tomb", "plst", "TMP-315", False, "The List"]},
    {"id": "minamo_plst_chk_279_foil", "note": "List foil, FMB1-wave SKU (M-FMB1-Minamo_Sc-118)",
     "card": ["Minamo, School at Water's Edge", "plst", "CHK-279", True, "The List"]},
    {"id": "rhystic_plst_pcy_45", "note": "List, MB1-wave SKU (M-MB1-Rhystic_St-468)",
     "card": ["Rhystic Study", "plst", "PCY-45", False, "The List"]},
    {"id": "bolt_plst_clb_187_unstocked", "note": "not stocked: every other List Bolt has its own "
     "collector, so no candidate and no product fetch",
     "card": ["Lightning Bolt", "plst", "CLB-187", False, "The List"]},
    {"id": "mortuary_plst_sth_66", "note": "not stocked: the suggest offers Buried Alive [WTH-63] "
     "(PLST SKU) — never filed on a Mortuary",
     "card": ["Mortuary", "plst", "STH-66", False, "The List"],
     "stand_ins": {"Mortuary The List": "Mortuary STH-66 The List",
                   "Mortuary": "Mortuary STH-66 The List"}},
    {"id": "ancient_excavation_plst_pz2_38", "note": "F2F zero-pads [PZ2-038]",
     "card": ["Ancient Excavation", "plst", "PZ2-38", False, "The List"],
     "stand_ins": {"Ancient Excavation PZ2-38 The List": "Ancient Tomb The List"}},
    {"id": "bolt_clb_401", "note": "variant title: [401] [Rulebook Showcase] [CLB] [Non-Foil]",
     "card": ["Lightning Bolt", "clb", "401", False, "Commander Legends: Battle for Baldur's Gate"]},
    {"id": "bolt_clb_187", "note": "plain title",
     "card": ["Lightning Bolt", "clb", "187", False, "Commander Legends: Battle for Baldur's Gate"]},
    {"id": "bolt_clb_401_on_the_187_answer", "note": "#187 first in suggest order — the collector "
     "decides, not the order",
     "card": ["Lightning Bolt", "clb", "401", False, "Commander Legends: Battle for Baldur's Gate"],
     "stand_ins": {"Lightning Bolt 401 Commander Legends: Battle for Baldur's Gate":
                   "Lightning Bolt 187 Commander Legends: Battle for Baldur's Gate"}},
    {"id": "urzas_saga_mb2_114", "note": "variant title: [114] [White Border] [Mystery Booster 2]",
     "card": ["Urza's Saga", "mb2", "114", False, "Mystery Booster 2"]},
    {"id": "counterspell_mb2_158", "note": "variant title: [158] [Future Frame] [Mystery Booster 2]",
     "card": ["Counterspell", "mb2", "158", False, "Mystery Booster 2"]},
    {"id": "slivdrazi_cmb1_102", "note": "cmb1 SKU M-CMB1-Slivdrazi_-102; cmb1 vetoes NOPW",
     "card": ["Slivdrazi Monstrosity", "cmb1", "102", False, "Mystery Booster Playtest Cards 2019"],
     "stand_ins": {"Slivdrazi Monstrosity 102 Mystery Booster Playtest Cards 2019": "Slivdrazi Monstrosity"}},
    {"id": "slivdrazi_cmb2_102", "note": "cmb2 is filed as M-CMB1-NOPW-Slivdrazi_-102",
     "card": ["Slivdrazi Monstrosity", "cmb2", "102", False, "Mystery Booster Playtest Cards 2021"],
     "stand_ins": {"Slivdrazi Monstrosity 102 Mystery Booster Playtest Cards 2021": "Slivdrazi Monstrosity"}},
    {"id": "earl_ulst_108_foil", "note": "ulst: SKU SIN-MTG-UPLIST-35, title [108] [UST Set Symbol] "
     "[The List: Unfinity Foil Edition] [Foil]",
     "card": ["Earl of Squirrel", "ulst", "108", True, "The List (Unfinity Foil Edition)"],
     "stand_ins": {"Earl of Squirrel 108 The List (Unfinity Foil Edition) foil": "Earl of Squirrel"}},
    {"id": "bolt_m10_146", "note": "the original fixture (tests/fixtures/facetoface): plain titles",
     "card": ["Lightning Bolt", "m10", "146", False, "Magic 2010"],
     "stand_ins": {"Lightning Bolt 146 Magic 2010": LEGACY_SUGGEST}},
    {"id": "bolt_m11_149", "note": "the original fixture: same name, another set",
     "card": ["Lightning Bolt", "m11", "149", False, "Magic 2011"],
     "stand_ins": {"Lightning Bolt 149 Magic 2011": LEGACY_SUGGEST}},
]


def _keep(d: dict, keys) -> dict:
    return {k: d[k] for k in keys if k in d}


def _trim(body: dict) -> dict:
    """The fields the client reads (the captured files are already trimmed)."""
    if "product" in body:
        p = body["product"]
        return {"product": {**_keep(p, ("title", "handle")),
                            "variants": [_keep(v, ("sku", "title", "option1", "price", "available"))
                                         for v in p.get("variants", [])]}}
    prods = body["resources"]["results"]["products"]
    return {"resources": {"results": {"products": [_keep(p, ("title", "handle", "available"))
                                                    for p in prods]}}}


def _query_of(url: str) -> str:
    return unquote(url.split("?q=", 1)[1].split("&", 1)[0])


def load_bodies() -> tuple[dict, dict]:
    """(body id → body, suggest query text → body id). Captured ids are their URLs."""
    bodies: dict = {}
    by_query: dict = {}
    for path in sorted(CAPTURED.glob("*.json")):
        d = json.loads(path.read_text(encoding="utf-8"))
        bodies[d["url"]] = _trim(d["body"])
        if "/search/suggest.json" in d["url"]:
            by_query[_query_of(d["url"])] = d["url"]
    bodies[LEGACY_SUGGEST] = _trim(json.loads((ROOT / LEGACY_SUGGEST).read_text(encoding="utf-8")))
    for path in sorted(FIX.glob("product_*.json")):
        handle = path.stem[len("product_"):]
        bodies[f"{m._BASE}/products/{handle}.json"] = _trim(json.loads(path.read_text(encoding="utf-8")))
    return bodies, by_query


def run_session(spec: dict, bodies: dict, by_query: dict) -> dict:
    stand_ins = spec.get("stand_ins", {})
    requests: list[str] = []
    answers: dict[str, str] = {}

    def get_json(url: str):
        if "/search/suggest.json" in url and _query_of(url) in stand_ins:
            target = stand_ins[_query_of(url)]
            body_id = target if target in bodies else by_query[target]
        elif url in bodies:
            body_id = url
        else:
            raise SystemExit(f"session {spec['id']}: uncaptured URL with no stand-in: {url}")
        requests.append(url)
        answers[url] = body_id
        return copy.deepcopy(bodies[body_id])

    name, set_code, collector, foil, set_name = spec["card"]
    price = m.FaceToFaceClient(get_json=get_json).get_price(name, set_code, collector, foil, set_name)
    return {
        "id": spec["id"],
        "note": spec["note"],
        "card": {"name": name, "set_code": set_code, "collector_number": collector,
                 "foil": foil, "set_name": set_name},
        "requests": requests,
        "answers": answers,
        "stand_ins": [u for u in requests if answers[u] != u],
        "result": None if price is None else {"handle": price.handle, "conditions": price.conditions},
    }


def _titles(bodies: dict) -> list[str]:
    seen: list[str] = []
    for body in bodies.values():
        if "product" in body:
            titles = [body["product"].get("title", "")]
        else:
            titles = [p.get("title", "") for p in body["resources"]["results"]["products"]]
        for t in titles:
            if t not in seen:
                seen.append(t)
    return sorted(seen) + [t for t in EXTRA_TITLES if t not in seen]


def _skus(bodies: dict) -> list[str]:
    out: list[str] = []
    for body in bodies.values():
        for v in (body.get("product") or {}).get("variants", []):
            if v.get("sku") and v["sku"] not in out:
                out.append(v["sku"])
    return sorted(out) + [s for s in EXTRA_SKUS if s not in out]


def _table(columns: list[str], rows: list[list]) -> dict:
    return {"columns": columns, "rows": rows}


def build() -> bytes:
    bodies, by_query = load_bodies()
    sessions = [run_session(s, bodies, by_query) for s in SESSIONS]
    used = sorted({b for s in sessions for b in s["answers"].values()})

    titles = _titles(bodies)
    parsed = [m._parse_title_brackets(t) for t in titles]
    collectors = list(dict.fromkeys([p["collector"] for p in parsed]
                                    + [s["card"]["collector_number"] for s in sessions]
                                    + EXTRA_COLLECTORS))
    names = list(dict.fromkeys([s["card"]["name"] for s in sessions] + EXTRA_NAMES))
    # _title_names_card reads only the text before the first "[".
    name_parts = list(dict.fromkeys(t.split("[", 1)[0] for t in titles))
    tables = {
        "parse_title": _table(["title", "collector", "set_name", "foil_label"],
                              [[t, p["collector"], p["set_name"], p["foil_label"]]
                               for t, p in zip(titles, parsed)]),
        "norm_collector": _table(["in", "out"], [[c, m._norm_collector(c)] for c in collectors]),
        "sku_matches_set": _table(["sku", "set", "match"],
                                  [[sku, code, m._sku_matches_set(sku, code)]
                                   for sku in _skus(bodies) for code in SET_CODES]),
        "norm_name": _table(["in", "out"], [[n, m._norm_name(n)] for n in names + name_parts]),
        "title_names_card": _table(["title", "name", "match"],
                                   [[t, n, m._title_names_card(t, n)] for t in name_parts for n in names]),
    }
    doc = {
        "about": "Generated by scripts/export_f2f_match_fixtures.py from the real facetoface.py over "
                 "F2F answers captured 2026-11-04 (tests/fixtures/facetoface/captured) — do not hand-edit.",
        "base": m._BASE,
        "bodies": {b: bodies[b] for b in used},
        "sessions": sessions,
    }
    # One table row per line keeps the file small and its diffs readable.
    text = json.dumps(doc, indent=1, sort_keys=True, ensure_ascii=False)
    rows = ",\n".join(
        f' {json.dumps(name)}: {{"columns": {json.dumps(t["columns"])}, "rows": [\n'
        + ",\n".join("  " + json.dumps(r, ensure_ascii=False) for r in t["rows"]) + "\n ]}"
        for name, t in sorted(tables.items()))
    text = text[:-2] + ",\n" + rows + "\n}"
    json.loads(text)                       # still one valid document
    return (text + "\n").encode("utf-8")


def main() -> int:
    data = build()
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_bytes() != data:
            print("f2f match fixture is stale — run: python scripts/export_f2f_match_fixtures.py")
            return 1
        print("f2f match fixture up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
