"""
Export the golden fixtures for the external card list's Kotlin port
(core/CardList.kt, CardListSource.kt; PhoneApi's list_tags).

Everything comes from the REFERENCE (mtg_card_scanner/card_list.py, server/app.py):
  * normalize / keys / parse / tags / scan_name — input → the reference's answer;
  * source — a scripted CardListSource session (fake fetch answers, a pinned
    clock, a cache file, restarts) with its status() after every step;
  * api — the REAL app.py: seeds, then GET /api/scans and /api/scans/{id} with
    the feature off, with a list, after a pick, and after a refreshed list.

    python scripts/export_card_list_fixtures.py           # (re)write expected.json
    python scripts/export_card_list_fixtures.py --check   # exit 1 if stale (CI)
"""

from __future__ import annotations

import copy
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from fastapi.testclient import TestClient  # noqa: E402

import server.store as store_mod  # noqa: E402
from mtg_card_scanner import card_list as cl  # noqa: E402
from server.app import create_app  # noqa: E402
from server.store import ScanStore  # noqa: E402

OUT = ROOT / "android" / "core" / "src" / "test" / "resources" / "cardlist" / "expected.json"

NAMES = [
    "Lightning Bolt", "  Jötun   Grunt ", "Lim-Dûl’s Vault", "“Ach! Hans, Run!”", "Fire//Ice",
    "Fire  //   Ice", "Ætherize", "Smith – Steel — Hammer", "A B C", "Séance",
    "Delver of Secrets // Insectile Aberration", "Bonecrusher Giant // Stomp", "Kongming, ‘Sleeping Dragon’",
    "Rhox Faithmender\t", "ÉÈÊË àáâãäå çñ", "Circle of Protection: Red", "Will-o’-the-Wisp", "",
    "   ", "//", "A // ", " // B", "Dandân", "Mishra’s Factory", "Bösium Strip", "Ifh-Bíff Efreet",
    "Ｆｕｌｌｗｉｄｔｈ", "İstanbul", "Straße", "Name  /  Not a face", "Who // What // When // Where // Why",
]

LIST_GOOD = json.dumps({"generated_at": "2026-10-12T09:00:00Z", "extra": {"x": 1}, "cards": [
    {"name": "Lightning Bolt", "tags": ["wanted"]},
    {"name": "Jötun Grunt", "tags": ["sold_out", "wanted"]},
    {"name": "Fire // Ice", "tags": ["wanted"]},
    {"name": "Delver of Secrets", "tags": ["vip", "wanted", "\u00a0vip\u2003", "  "], "qty": 4},
    {"name": "Lightning Bolt", "tags": ["sold_out", 7, None]},
    {"name": "Lim-Dul's Vault", "tags": "wanted"},
    {"name": "Insectile Aberration", "tags": [" restock "]},
    {"name": "", "tags": ["wanted"]},
    {"name": 5, "tags": ["wanted"]},
    {"tags": ["wanted"]},
    "junk", None, 3,
]}, ensure_ascii=False)

PARSE = [
    LIST_GOOD,
    '{"cards": []}',
    '{"generated_at": 5, "cards": [{"name": "Opt"}]}',
    '{"generated_at": null, "cards": [{"name": "Opt", "tags": []}, {"name": "opt", "tags": ["wanted"]}]}',
    "{not json", "", "[]", '{"cards": {}}', '{"card": []}', "null", '"cards"', "  \n ",
]

TAG_NAMES = ["Lightning Bolt", "LIGHTNING  BOLT", "Jotun Grunt", "Fire // Ice", "Fire", "Ice",
             "Delver of Secrets // Insectile Aberration", "Insectile Aberration", "Lim-Dûl’s Vault",
             "Opt", "", None]

SCANS = [
    {"selection": {"name": "A"}, "candidates": [{"name": "B"}], "card_read": {"name": "C"}},
    {"selection": None, "candidates": [{"name": "B"}], "card_read": {"name": "C"}},
    {"candidates": [], "card_read": {"name": "C"}},
    {"candidates": [], "card_read": {}},
    {"selection": {"name": "  "}, "candidates": [{"name": ""}], "card_read": {"name": "C"}},
    {"selection": {"name": "\u00a0\u3000"}, "candidates": [{"name": "\tB "}]},
    {"selection": {"name": 5}, "candidates": [{"id": "x"}], "card_read": {"name": None}},
    {},
]

# A CardListSource session. fetch answers: [code, body] or "down". "restart" builds a
# new source on the same cache file with the given url.
SOURCE_STEPS = [
    {"op": "new", "url": ""},
    {"op": "refresh"},
    {"op": "set_url", "url": " https://lists.example/cards.json?key=s3cret "},
    {"op": "refresh", "answer": [200, LIST_GOOD]},
    {"op": "refresh", "answer": "down"},
    {"op": "refresh", "answer": [500, "oops"]},
    {"op": "refresh", "answer": [200, "{bad"]},
    {"op": "refresh", "answer": [200, "[]"]},
    {"op": "restart", "url": "https://lists.example/cards.json?key=s3cret"},
    {"op": "restart", "url": "https://lists.example/other.json"},
    {"op": "refresh", "answer": [404, ""]},
    {"op": "set_url", "url": "https://lists.example/cards.json?key=s3cret"},
    {"op": "refresh", "answer": [200, '{"generated_at": "G2", "cards": [{"name": "Opt", "tags": ["wanted"]}]}']},
    {"op": "set_url", "url": ""},
    {"op": "restart", "url": "https://lists.example/cards.json?key=s3cret"},
]


def _source_session() -> dict:
    ticks = [0]

    def clock():
        ticks[0] += 1
        return f"C{ticks[0]:04d}"

    pending: list = []

    def fetch(url):
        fetched.append(url)
        a = pending.pop(0)
        if a == "down":
            raise OSError("down")
        return a[0], a[1]

    steps = []
    with tempfile.TemporaryDirectory() as tmp:
        cache = Path(tmp) / "card_list_cache.json"
        src = None
        for st in SOURCE_STEPS:
            fetched: list = []
            got = None
            if st["op"] in ("new", "restart"):
                src = cl.CardListSource(st["url"], cache, fetch, clock)
            elif st["op"] == "set_url":
                src.set_url(st["url"])
            else:
                if "answer" in st:
                    pending.append(st["answer"])
                got = src.refresh()
            cur = src.current()
            steps.append({**st, "returned": got, "fetched": fetched, "status": src.status(),
                          "current_count": None if cur is None else cur["count"]})
    return {"clock": "C%04d", "steps": steps}


SEEDS = [
    dict(identified=True, card_read={"name": "Lightning Bolt"}, confidence={},
         candidates=[{"id": "b1", "name": "Lightning Bolt", "set": "m10", "collector_number": "146",
                      "multi_distance": 70},
                     {"id": "b2", "name": "Lightning Bolt", "set": "2x2", "collector_number": "117",
                      "multi_distance": 80}]),
    dict(identified=True, card_read={"name": "Delver of Secrets"}, confidence={},
         candidates=[{"id": "d1", "name": "Delver of Secrets // Insectile Aberration", "set": "isd",
                      "collector_number": "51", "multi_distance": 60},
                     {"id": "o1", "name": "Opt", "set": "dom", "collector_number": "60", "multi_distance": 150}]),
    dict(identified=False, card_read={"name": "Jötun Grunt"}, confidence={}, candidates=[],
         error="not identified"),
    dict(identified=False, card_read={}, confidence={}, candidates=[]),
]

API_STEPS = [
    ("GET", "/api/scans", None),
    ("LIST", None, {"url": "https://lists.example/cards.json", "answer": [200, LIST_GOOD]}),
    ("GET", "/api/scans", None),
    ("GET", "/api/scans/2", None),
    ("POST", "/api/scans/2/select", {"printing": SEEDS[1]["candidates"][1]}),   # picked: now "Opt"
    ("GET", "/api/scans/2", None),
    ("LIST", None, {"url": "https://lists.example/cards.json",
                    "answer": [200, '{"cards": [{"name": "Opt", "tags": ["sold_out", "wanted"]}]}']}),
    ("GET", "/api/scans", None),
    ("LIST", None, {"url": "https://lists.example/cards.json", "answer": "down"}),   # keeps the list
    ("GET", "/api/scans/1", None),
    ("LIST", None, {"url": "", "answer": None}),                                      # off again
    ("GET", "/api/scans", None),
]


def _api_session() -> dict:
    ticks = [0]

    def clock():
        ticks[0] += 1
        return f"T{ticks[0]:04d}"

    lticks = [0]

    def lclock():
        lticks[0] += 1
        return f"C{lticks[0]:04d}"

    pending: list = []

    def fetch(url):
        a = pending.pop(0)
        if a == "down":
            raise OSError("down")
        return a[0], a[1]

    real_now = store_mod._now
    store_mod._now = clock
    steps = []
    try:
        with tempfile.TemporaryDirectory() as tmp:
            store = ScanStore(Path(tmp) / "s.db")
            src = cl.CardListSource("", Path(tmp) / "cl.json", fetch, lclock)
            try:
                app = create_app(pipeline_factory=lambda: None, store=store, f2f=object(),
                                 scan_images_dir=Path(tmp) / "img", auto_sweep_interval=None, card_list=src)
                app.state.sweep["active"] = True       # the phone: never prices inline
                client = TestClient(app)
                for s in SEEDS:
                    store.create_scan(**copy.deepcopy(s))
                for method, path, body in API_STEPS:
                    if method == "LIST":
                        src.set_url(body["url"])
                        if body["answer"] is not None:
                            pending.append(body["answer"])
                            src.refresh()
                        steps.append({"method": method, "body": body, "status": src.status()})
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


def build() -> dict:
    parsed = []
    for text in PARSE:
        try:
            parsed.append({"text": text, "result": cl.parse_list(text)})
        except cl.CardListError as e:
            parsed.append({"text": text, "error": str(e)})
    index = cl.parse_list(LIST_GOOD)["index"]
    return {
        "known_tags": list(cl.KNOWN_TAGS),
        "normalize": [{"in": n, "out": cl.normalize_name(n)} for n in NAMES],
        "keys": [{"in": n, "out": cl.name_keys(n)} for n in NAMES],
        "parse": parsed,
        "tags": [{"name": n, "tags": cl.tags_for(n, index)} for n in TAG_NAMES + NAMES],
        "scan_name": [{"scan": s, "name": cl.scan_card_name(s)} for s in SCANS],
        "source": _source_session(),
        "api": _api_session(),
    }


def main() -> int:
    text = json.dumps(build(), indent=1, ensure_ascii=False) + "\n"
    if "--check" in sys.argv:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else None
        if current != text:
            print("card-list fixtures are stale — run: python scripts/export_card_list_fixtures.py")
            return 1
        print("card-list fixtures up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
