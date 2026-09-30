"""
Export name -> candidate-printings parity fixtures for the Android app's JVM tests.

The phone ports everything that turns a card NAME
into the candidate list the rig builds (scryfall.get_all_printings and its
paged _search_all, pipeline._candidate_dict / search_candidates /
_ranked_candidates' projection, popularity.py, artwork.other_art). The Kotlin
port must reproduce what the SERVER's own code answers for the same Scryfall
responses, so:

  * `--record` (network, run once by hand) drives the REAL ScryfallClient
    against api.scryfall.com for NAMES, following every page, and stores each
    response keyed by the exact URL `requests` sent — compacted to the fields
    the code reads — in printings/recorded.json.gz.
  * the default run replays those recorded pages through the SAME
    ScryfallClient (only its HTTP session is faked) and writes the server's
    answers to printings/expected.json.gz;
  * `--check` recomputes the answers from the committed recordings and
    exits 1 if they differ from the committed expectations (CI), so the
    fixtures can never drift from the server silently.

    python scripts/export_printings_fixtures.py --record  # re-record (network)
    python scripts/export_printings_fixtures.py           # (re)write expected
    python scripts/export_printings_fixtures.py --check   # exit 1 if stale

Art distances are NOT computed here (that's visual_match, a separate port):
the ranking scenarios use synthetic, deterministic distances grouped by the
recorded illustration_id, so same-art / other-art breaks look like the
measured ones (Fling Δ78-84 vs Δ146+, Bone Splinters Δ124-190).
"""

from __future__ import annotations

import contextlib
import copy
import gzip
import hashlib
import io
import json
import sys
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from mtg_card_scanner import artwork, popularity, scryfall  # noqa: E402
from mtg_card_scanner.pipeline import Pipeline, _candidate_dict  # noqa: E402
from mtg_card_scanner.popularity import oracle_key, print_counts_by_oracle  # noqa: E402
from mtg_card_scanner.scryfall import ScryfallClient, ScryfallError  # noqa: E402
from mtg_card_scanner.visual_match import _cap_candidates  # noqa: E402

OUT = ROOT / "android" / "core" / "src" / "test" / "resources" / "printings"
RECORDED = OUT / "recorded.json.gz"
EXPECTED = OUT / "expected.json.gz"

# Each name hits a rule (CLAUDE.md): paging, two oracle cards under one name,
# reversible_card oracle_key, unranked/banned, basic land, a digital-only
# printing dropped, other_art breaks, multi-face images on card_faces, penny
# rank vs legality, and a 404. Multi-face names are the FULL name, because
# that is what art_index hands get_all_printings.
NAMES = [
    "Forest",                                   # ~900 printings: 6 pages of 175
    "Lightning Bolt",                           # two oracle cards (SOS prepare)
    "Sol Ring",                                 # SLD reversible_card: oracle on faces
    "Black Lotus",                              # unranked, banned in Commander
    "Wastes",                                   # basic land, unranked
    "Shivan Dragon",                            # M15 digital representative dropped
    "Fling",                                    # other_art: clean break
    "Bone Splinters",                           # other_art: wide same-art, no split
    "Delver of Secrets // Insectile Aberration",  # transform DFC: images on faces
    "Fire // Ice",                              # split card
    "Bonecrusher Giant // Stomp",               # adventure
    "Counterspell",                             # penny_rank but penny not_legal
    "Definitely Not A Real Card Xyzzy",         # 404
]

# Served by _synthetic_responses (paging/filter/error edge cases).
SYNTHETIC_NAMES = ["Synthetic Page Cap", "Synthetic No Next Page", "Synthetic Html 404",
                   "Synthetic Server Error", "Synthetic Filters"]

USER_AGENT = "MTGCardScanner-fixtures/1.0 (github.com/DarylNo/CardScanner)"
ACCEPT = "application/json;q=0.9,*/*;q=0.8"

# Fields the ported code reads (+ illustration_id, which this script uses to
# group synthetic distances by artwork). Everything else is stripped at record
# time to keep the committed fixture small.
_IMAGE_KEYS = ("small", "normal", "large")
_LEGALITY_KEYS = tuple(k for k, _ in popularity.TRACKED_FORMATS) + ("penny",)
_CARD_KEYS = ("object", "id", "oracle_id", "name", "set", "set_name", "collector_number",
              "rarity", "released_at", "border_color", "frame", "promo", "finishes",
              "games", "layout", "edhrec_rank", "penny_rank", "game_changer", "reserved",
              "illustration_id")
_FACE_KEYS = ("oracle_id", "illustration_id")


def _compact_images(uris):
    if not isinstance(uris, dict):
        return uris
    return {k: uris[k] for k in _IMAGE_KEYS if k in uris}


def _compact_card(c: dict) -> dict:
    out = {k: c[k] for k in _CARD_KEYS if k in c}
    if "image_uris" in c:
        out["image_uris"] = _compact_images(c["image_uris"])
    if "legalities" in c:
        out["legalities"] = {k: v for k, v in c["legalities"].items() if k in _LEGALITY_KEYS}
    if "card_faces" in c:
        faces = []
        for f in c["card_faces"]:
            g = {k: f[k] for k in _FACE_KEYS if k in f}
            if "image_uris" in f:
                g["image_uris"] = _compact_images(f["image_uris"])
            faces.append(g)
        out["card_faces"] = faces
    return out


def _compact_body(body):
    if isinstance(body, dict) and isinstance(body.get("data"), list):
        keep = {k: body[k] for k in ("object", "total_cards", "has_more", "next_page") if k in body}
        keep["data"] = [_compact_card(c) for c in body["data"]]
        return keep
    return body


# ── HTTP layers under the REAL ScryfallClient ────────────────────────────────

class _RecordingSession:
    """Real network; records each response under the URL requests sent."""

    def __init__(self, responses: dict):
        self.responses = responses
        self.s = requests.Session()
        self.s.headers.update({"User-Agent": USER_AGENT, "Accept": ACCEPT})
        self.headers = self.s.headers
        self._last = 0.0

    def get(self, url, params=None, timeout=None):
        wait = 0.15 - (time.monotonic() - self._last)      # ≥100 ms between requests
        if wait > 0:
            time.sleep(wait)
        resp = self.s.get(url, params=params, timeout=timeout or 20)
        self._last = time.monotonic()
        ctype = resp.headers.get("content-type", "")
        body = resp.json() if ctype.startswith("application/json") else resp.text
        self.responses[resp.request.url] = {
            "status": resp.status_code, "content_type": ctype.split(";")[0],
            "body": _compact_body(body)}
        return resp


class _FakeResp:
    def __init__(self, url: str, rec: dict):
        self.url = url
        self.status_code = rec["status"]
        self.headers = {"content-type": rec["content_type"]}
        self._body = rec["body"]

    def json(self):
        return copy.deepcopy(self._body)            # requests parses afresh per call

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"{self.status_code} for url {self.url}")


class _ReplaySession:
    """Serves recorded responses by the exact URL requests would have sent."""

    def __init__(self, responses: dict, log: list):
        self.responses = responses
        self.log = log
        self.headers = {}

    def get(self, url, params=None, timeout=None):
        full = requests.Request("GET", url, params=params or {}).prepare().url
        self.log.append(full)
        if full not in self.responses:
            raise KeyError(f"no recorded response for {full}")
        return _FakeResp(full, self.responses[full])


def _client(responses: dict, log: list) -> ScryfallClient:
    c = ScryfallClient()
    c._session = _ReplaySession(responses, log)
    return c


# ── synthetic inputs (committed via expected.json.gz) ────────────────────────

def _synthetic_responses() -> dict:
    """Paging edge cases no real name produces: the 8-page cap and a
    has_more without next_page."""
    base = f"{scryfall.SCRYFALL_BASE}/cards/search"
    out: dict = {}

    def card(tag: str, i: int) -> dict:
        return {"object": "card", "id": f"{tag}-{i}", "oracle_id": f"{tag}-oracle",
                "name": tag, "set": "tst", "set_name": "Test", "collector_number": str(i),
                "released_at": f"2000-01-{1 + i % 28:02d}", "games": ["paper"],
                "layout": "normal", "image_uris": {"small": f"https://img/{tag}/{i}.jpg"}}

    def first(name: str) -> str:
        return requests.Request("GET", base, params={
            "q": f'!"{name}"', "unique": "prints", "order": "released",
            "dir": "asc", "include_extras": "true"}).prepare().url

    # 10 pages, each promising more: the client must stop after 8.
    name = "Synthetic Page Cap"
    urls = [first(name)] + [f"{base}?page={p}&q=synthetic-cap" for p in range(2, 11)]
    for p, u in enumerate(urls, start=1):
        out[u] = {"status": 200, "content_type": "application/json", "body": {
            "object": "list", "has_more": True,
            "next_page": urls[p] if p < len(urls) else f"{base}?page=11&q=synthetic-cap",
            "data": [card(name, 2 * p), card(name, 2 * p + 1)]}}
    # has_more without next_page: stops after page 1.
    name = "Synthetic No Next Page"
    out[first(name)] = {"status": 200, "content_type": "application/json", "body": {
        "object": "list", "has_more": True, "data": [card(name, 1)]}}
    # A 404 that is not JSON: detail falls back to "not found".
    name = "Synthetic Html 404"
    out[first(name)] = {"status": 404, "content_type": "text/html", "body": "<html>nope</html>"}
    # A 5xx: raise_for_status, not a ScryfallError.
    name = "Synthetic Server Error"
    out[first(name)] = {"status": 503, "content_type": "application/json",
                        "body": {"object": "error", "details": "down"}}
    # Filtering edge cases in one page: games missing / empty / digital-only,
    # skipped layouts, a DFC without face images, image_uris graft.
    name = "Synthetic Filters"
    cards = [
        {"id": "no-games", "name": name, "image_uris": {"small": "s"}},
        {"id": "empty-games", "name": name, "games": [], "image_uris": {"small": "s"}},
        {"id": "arena", "name": name, "games": ["arena"], "image_uris": {"small": "s"}},
        {"id": "mtgo-paper", "name": name, "games": ["mtgo", "paper"], "image_uris": {"small": "s"}},
        {"id": "token", "name": name, "games": ["paper"], "layout": "token", "image_uris": {"small": "s"}},
        {"id": "dfc-token", "name": name, "games": ["paper"], "layout": "double_faced_token",
         "card_faces": [{"image_uris": {"small": "f"}}]},
        {"id": "emblem", "name": name, "layout": "emblem", "image_uris": {"small": "s"}},
        {"id": "art", "name": name, "layout": "art_series", "image_uris": {"small": "s"}},
        {"id": "no-images", "name": name, "games": ["paper"]},
        {"id": "faces-no-images", "name": name, "games": ["paper"],
         "card_faces": [{"oracle_id": "o1"}, {"image_uris": {"small": "back"}}]},
        {"id": "faces-graft", "name": name, "games": ["paper"],
         "card_faces": [{"image_uris": {"small": "front", "large": "front-l"}, "oracle_id": "o2"},
                        {"image_uris": {"small": "back"}}]},
        {"id": "empty-top-images", "name": name, "games": ["paper"], "image_uris": {},
         "card_faces": [{"image_uris": {"normal": "front-n"}}]},
        {"id": "null-layout", "name": name, "games": ["paper"], "layout": None,
         "image_uris": {"normal": "", "large": "L"}},
    ]
    out[first(name)] = {"status": 200, "content_type": "application/json", "body": {
        "object": "list", "total_cards": len(cards), "has_more": False, "data": cards}}
    return out


def _synthetic_printings() -> list:
    """Popularity/legality edge cases for summarize()."""
    full = {k: "legal" for k in _LEGALITY_KEYS}
    return [
        {},
        {"edhrec_rank": 1, "game_changer": True, "reserved": 0},
        {"edhrec_rank": 322}, {"edhrec_rank": 323}, {"edhrec_rank": 324},
        {"edhrec_rank": 1614}, {"edhrec_rank": 1615}, {"edhrec_rank": 1616},
        {"edhrec_rank": 4844}, {"edhrec_rank": 4845}, {"edhrec_rank": 12918},
        {"edhrec_rank": 12919}, {"edhrec_rank": 32296}, {"edhrec_rank": 99999},
        {"edhrec_rank": 0}, {"edhrec_rank": -5}, {"edhrec_rank": None},
        {"edhrec_rank": 158.9}, {"edhrec_rank": 0.5}, {"edhrec_rank": True},
        {"edhrec_rank": False}, {"edhrec_rank": "158"},
        {"legalities": {}}, {"legalities": None},
        {"legalities": {"standard": "suspended", "modern": "banned", "vintage": "restricted",
                        "pauper": 3, "commander": "legal"}},
        {"legalities": dict(full), "penny_rank": 8},
        {"legalities": dict(full, penny="not_legal"), "penny_rank": 8},
        {"legalities": dict(full), "penny_rank": 0},
        {"legalities": dict(full), "penny_rank": 12.7},
        {"legalities": dict(full), "penny_rank": "8"},
        {"legalities": dict(full)},
        {"penny_rank": 5},
        {"game_changer": "yes", "reserved": [], "promo": 1},
        {"game_changer": None, "reserved": {"a": 1}},
        {"oracle_id": "", "card_faces": [{"oracle_id": None}, {"oracle_id": "face-2"}]},
        {"oracle_id": None, "card_faces": []},
    ]


def _synthetic_other_art() -> list:
    """Candidate lists for artwork.other_art_ids beyond the recorded names."""
    def cs(*ds, ocr=()):
        return [dict({"id": f"c{i}", "multi_distance": d}, **({"ocr_confirmed": True} if i in ocr else {}))
                for i, d in enumerate(ds)]
    return [
        cs(78, 80, 82, 84, 146, 150),               # Fling
        cs(124, 150, 170, 190, 208),                # Bone Splinters: no split
        cs(100, 140),                               # gap 40 exactly
        cs(100, 139),                               # gap 39
        cs(140, 180), cs(141, 200),                 # ceiling edges
        cs(84, 78, 200, 82, 150),                   # unsorted input
        cs(80, 200, 90, ocr=(1,)),                  # OCR-confirmed never split off
        cs(80, None, 200),                          # a missing distance: no split
        cs(80),                                     # single candidate
        [],
        cs(80, 120, 160, 300),                      # FIRST break wins
        cs(80, 80, 200, 200),                       # ties
        cs(80.5, 120.5, 20.25),                     # floats
        [{"multi_distance": 80}, {"multi_distance": 200}],   # no ids
        [{"id": "a", "multi_distance": 80}, {"id": "a", "multi_distance": 200}],  # dup ids
    ]


# ── the SERVER's answers ─────────────────────────────────────────────────────

_PROFILES = {
    # name: (same-art base, same-art span, other-art base, other-art span, multi None every N)
    "Bone Splinters": (124, 67, 208, 10, 0),
    "Sol Ring": (78, 8, 146, 60, 7),
}
_DEFAULT_PROFILE = (78, 8, 146, 60, 0)


def _h(s: str) -> int:
    return int.from_bytes(hashlib.sha1(s.encode()).digest()[:4], "big")


def _art_of(p: dict) -> str:
    if p.get("illustration_id"):
        return p["illustration_id"]
    for f in p.get("card_faces") or []:
        if f.get("illustration_id"):
            return f["illustration_id"]
    return p.get("id", "")


def _synthetic_distances(name: str, capped: list) -> dict:
    same_lo, same_span, other_lo, other_span, none_every = _PROFILES.get(name, _DEFAULT_PROFILE)
    scanned = _art_of(capped[len(capped) // 2])
    out = {}
    for i, p in enumerate(capped):
        h = _h(p["id"])
        multi = (same_lo + h % same_span) if _art_of(p) == scanned else (other_lo + h % other_span)
        phash = multi // 6
        out[p["id"]] = [phash, None if none_every and i % none_every == 3 else multi]
    return out


class _FakeArtMatcher:
    """Stands in for visual_match.ArtMatcher.rank_printings: the real cap, the
    real sort (by art pHash), synthetic distances."""

    def __init__(self, distances: dict):
        self.distances = distances

    def rank_printings(self, frame, printings):
        out = []
        for p in _cap_candidates(printings):
            ph, multi = self.distances[p["id"]]
            out.append({**p, "phash_distance": ph, "multi_distance": multi})
        out.sort(key=lambda x: x["phash_distance"])
        return out


def _name_entry(name: str, responses: dict) -> dict:
    log: list = []
    client = _client(responses, log)
    try:
        printings = client.get_all_printings(name)
    except ScryfallError as exc:
        return {"urls": log, "error": {"type": "ScryfallError", "message": str(exc)}}
    except requests.HTTPError as exc:
        return {"urls": log, "error": {"type": "HTTPError", "message": str(exc)}}
    entry: dict = {"urls": log, "printing_ids": [p["id"] for p in printings]}
    pipe = Pipeline(index=None, scryfall=_client(responses, []))
    entry["search_candidates_all"] = pipe.search_candidates(name, top_n=10 ** 6)
    capped = _cap_candidates(printings)
    entry["capped_ids"] = [p["id"] for p in capped]
    if not capped:
        return entry
    distances = _synthetic_distances(name, capped)
    matcher = _FakeArtMatcher(distances)
    ranked = matcher.rank_printings(None, printings)
    pipe._cached_art_matcher = matcher
    pipe._apply_ocr_hint = lambda frame, cands: cands     # OCR is a separate port
    cands = pipe._ranked_candidates(None, name, 12)
    flagged = artwork.flag_other_art({"candidates": copy.deepcopy(cands)})["candidates"]
    entry["ranking"] = {"distances": distances, "ranked_ids": [p["id"] for p in ranked],
                        "candidates": cands, "flagged": flagged}
    return entry


def build(responses: dict) -> dict:
    synth = _synthetic_responses()
    both = {**responses, **synth}
    names = {}
    with contextlib.redirect_stdout(io.StringIO()):
        for name in NAMES + SYNTHETIC_NAMES:
            names[name] = _name_entry(name, both)
    printings = _synthetic_printings()
    ranks = range(1, 40001)
    tiers, last = [], None
    for r in ranks:
        t = popularity.tier_for(r)[0]
        if t != last:
            tiers.append([r, t])
            last = t
    return {
        "constants": {"ranked_card_count": popularity.RANKED_CARD_COUNT,
                      "max_search_pages": scryfall._MAX_SEARCH_PAGES,
                      "min_delay": scryfall._MIN_DELAY,
                      "art_break_gap": artwork.ART_BREAK_GAP,
                      "art_break_ceiling": artwork.ART_BREAK_CEILING},
        "synthetic_responses": synth,
        "names": names,
        "summarize": [{"printing": p, "print_count": c, "out": popularity.summarize(p, c)}
                      for p in printings for c in (None, 3)],
        "oracle_keys": [oracle_key(p) for p in printings],
        "print_counts": print_counts_by_oracle(printings),
        "top_percent": [popularity.top_percent(r) for r in ranks],
        "tier_changes": tiers,
        "other_art": [{"candidates": cs,
                       "flagged": artwork.flag_other_art({"candidates": copy.deepcopy(cs)})["candidates"]}
                      for cs in _synthetic_other_art()],
        "candidate_dict": [_candidate_dict(dict(p, **extra), c)
                           for p, extra in [
                               ({}, {}),
                               ({"id": None, "promo": "x", "finishes": None, "image_uris": None}, {}),
                               ({"image_uris": {"small": "s", "normal": "", "large": "L"}},
                                {"phash_distance": 3, "multi_distance": 20}),
                               ({"image_uris": {"large": "L"}}, {"multi_distance": 1.5}),
                           ] for c in (None, 2)],
    }


def _dumps(obj) -> str:
    return json.dumps(obj, indent=1, ensure_ascii=False) + "\n"


def _gz(text: str) -> bytes:
    return gzip.compress(text.encode(), compresslevel=9, mtime=0)


def record() -> dict:
    responses: dict = {}
    c = ScryfallClient()
    c._session = _RecordingSession(responses)
    for name in NAMES:
        try:
            n = len(c.get_all_printings(name))
            print(f"  {name}: {n} paper printings")
        except ScryfallError as exc:
            print(f"  {name}: {exc}")
    # next_page is fetched verbatim by the Kotlin port: prove requests sends
    # it unchanged, or the recorded keys would not match what Kotlin asks for.
    for rec in responses.values():
        nxt = rec["body"].get("next_page") if isinstance(rec["body"], dict) else None
        if nxt:
            assert requests.Request("GET", nxt).prepare().url == nxt, nxt
    return responses


def main() -> int:
    if "--record" in sys.argv:
        responses = record()
        OUT.mkdir(parents=True, exist_ok=True)
        RECORDED.write_bytes(_gz(_dumps({
            "recorded_at": time.strftime("%Y-%m-%d", time.gmtime()),
            "names": NAMES, "responses": responses})))
        print(f"recorded {len(responses)} responses -> {RECORDED.relative_to(ROOT)}")
    recorded = json.loads(gzip.decompress(RECORDED.read_bytes()))
    text = _dumps(build(recorded["responses"]))
    if "--check" in sys.argv:
        current = gzip.decompress(EXPECTED.read_bytes()).decode() if EXPECTED.exists() else None
        if current != text:
            print("printings fixtures are stale — run: python scripts/export_printings_fixtures.py")
            return 1
        print("printings fixtures up to date")
        return 0
    EXPECTED.write_bytes(_gz(text))
    print(f"wrote {EXPECTED.relative_to(ROOT)} "
          f"({EXPECTED.stat().st_size // 1024} KB; recorded {RECORDED.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
