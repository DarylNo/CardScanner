"""
Export printing-ranker parity fixtures for the Android app's JVM tests.

The phone ports visual_match (the step that ranks a
card's candidate printings against the scan) to Kotlin
(android/core/.../PrintingRanker.kt). PORT, never re-tune: the ranking is only
the same if every distance is the same, so this script runs the SERVER's own
code — visual_match.ArtMatcher.rank_printings / best_match, _cap_candidates,
_detect_border_color, the crop_*_region helpers, imagehash.phash on Pillow +
scipy, and pipeline._ranked_candidates' re-sort — over committed inputs and
writes every stage's answer to
android/core/src/test/resources/ranker/expected.json. PrintingRankerParityTest
must reproduce it exactly:

  decode     sha256 of each decoded image (PIL for candidates — what
             ArtMatcher._fetch_image does; cv2.imdecode for scans — what the
             /api/scan upload handler does)
  border     _detect_border_color's luminance mean (IEEE bits) and label, on
             every scan and on synthetic solid frames around both thresholds
  regions    crop boxes + phash of the art / title / textbox / list-corner
             region of every scan and every candidate image
  runs       rank_printings: the capped list, is_basic, every candidate's
             art / title / textbox / corner distance, multi_distance,
             phash_hash, the phash_distance order; the pipeline's
             multi_distance re-sort; best_match (best, near-tie, basic
             uncertainty + top sets, List-corner decision + distances), with
             and without a literal (set, collector number) read
  cap        _cap_candidates on Forest's full paper-printing list (871 ids)

Inputs (committed):
  printings.json  the SERVER's ScryfallClient.get_all_printings output for
                  each fixture card (oldest first, paged at 175), trimmed to
                  the fields the ranker reads (+ name / released_at for humans)
  images/*.jpg    each printing's Scryfall `small` image — the ORIGINAL JPEG
                  bytes, exactly what the server caches and ranks from. JPEG,
                  not decoded PNG, is deliberate: the ranker's input IS the
                  JPEG, and OpenCV's bundled libjpeg-turbo (Imgcodecs) was
                  measured to decode every one of these files to the same
                  pixels as Pillow's — the decode stage asserts it per file.
  scans/*.jpg     camera-like flattened cards at the server's 630×880 warp
                  size: a printing's `normal` image upscaled, off-square
                  warped, blurred, re-lit and JPEG-compressed, as the phone
                  uploads it (the `normal` source is not committed)
  forest_ids.json Forest's printing ids in get_all_printings order (cap stage)

The scans are ALREADY the warped card, so ArtMatcher._warp_frame (the
server's card_detect.extract_card) is replaced by the identity here, as
export_hash_fixtures.py does for ArtIndex._warp: the ranker port takes the
warped card; the warp itself is CardQuad's (CardQuadParityTest).

    python scripts/export_ranker_fixtures.py                   # rewrite expected.json
    python scripts/export_ranker_fixtures.py --check           # exit 1 if stale (CI)
    python scripts/export_ranker_fixtures.py --refresh-inputs  # re-download (network)

--check never touches the network.
"""

from __future__ import annotations

import hashlib
import io
import json
import struct
import sys
import tempfile
import time
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import imagehash  # noqa: E402
from PIL import Image  # noqa: E402

import mtg_card_scanner.visual_match as vm  # noqa: E402
from mtg_card_scanner.visual_match import ArtMatcher  # noqa: E402

OUT = ROOT / "android" / "core" / "src" / "test" / "resources" / "ranker"

# Cards whose printings are ranked. Chosen to exercise every branch: same-art
# reprint ties (black and white border), The List (PLST) in a tie, promos in a
# tie, borderless (border "unknown"), a basic land (art-only scoring +
# printing_uncertain), and distinct-art printings (no tie).
CARDS = ["Lightning Bolt", "Shivan Dragon", "Diabolic Edict", "Wastes"]
CAP_CARD = "Forest"                 # 871 paper printings: _cap_candidates

# Scans: (key, card, set, collector_number, seed). Each picked (seed included)
# because it drives a DIFFERENT branch of best_match — noted per line.
SCANS = [
    ("bolt_m10", "Lightning Bolt", "m10", "146", 1),          # black same-art tie; List corner → base
    ("bolt_plst", "Lightning Bolt", "plst", "CLB-187", 14),   # List corner → list
    ("bolt_4ed", "Lightning Bolt", "4ed", "208", 3),          # white-bordered tie
    ("bolt_sta", "Lightning Bolt", "sta", "42", 4),           # borderless, distinct art: no tie
    ("bolt_sld", "Lightning Bolt", "sld", "1638", 11),        # borderless tie: border unknown
    ("shivan_7ed", "Shivan Dragon", "7ed", "218", 5),         # white vs ★ black twin
    ("edict_pal01", "Diabolic Edict", "pal01", "10", 11),     # promo tops the tie → non-promo wins
    ("wastes_cmm", "Wastes", "cmm", "1056", 11),              # basic near-tie → printing_uncertain
    ("wastes_ogw", "Wastes", "ogw", "183", 8),                # basic, no tie
]
SCAN_SIZE = (630, 880)              # card_detect.CARD_W × CARD_H

# Extra runs: (label, scan key, list recipe, vision set, vision number)
#   recipe "card:<name>"            that card's printings
#   recipe "concat:<a>|<b>|..."     several cards' printings back to back
#                                   (> 120 → the cap runs inside rank_printings)
#   recipe "edge:<name>"            that card's printings with url-fallback /
#                                   skip / fetch-failure rows mixed in
EXTRA_RUNS = [
    ("bolt_m10+literal_p09", "bolt_m10", "card:Lightning Bolt", "P09", "146"),
    ("bolt_m10+literal_zeros", "bolt_m10", "card:Lightning Bolt", " a25 ", "0141"),
    ("bolt_m10+literal_miss", "bolt_m10", "card:Lightning Bolt", "lea", "161"),
    ("edict_pal01+literal_pmei", "edict_pal01", "card:Diabolic Edict", "pmei", "2019-2"),
    ("wastes_cmm+literal_ignored", "wastes_cmm", "card:Wastes", "cmm", "1056"),
    ("bolt_m10+cap", "bolt_m10", "concat:Lightning Bolt|Shivan Dragon|Diabolic Edict", "", ""),
    ("wastes_ogw+cap_basic", "wastes_ogw", "concat:Lightning Bolt|Shivan Dragon|Wastes", "", ""),
    ("bolt_4ed+edge", "bolt_4ed", "edge:Lightning Bolt", "", ""),
]

# Solid BGR frames for _detect_border_color: (h, w, (b, g, r))
BORDER_SYNTH = [
    (19, 40, (0, 0, 0)), (40, 19, (0, 0, 0)), (20, 20, (0, 0, 0)),
    (100, 70, (59, 60, 61)), (100, 70, (60, 60, 60)), (100, 70, (61, 59, 60)),
    (300, 200, (160, 160, 160)), (300, 200, (161, 160, 160)), (300, 200, (255, 255, 255)),
    (880, 630, (10, 200, 90)), (881, 631, (250, 30, 200)), (55, 440, (170, 170, 170)),
]

PRINTING_FIELDS = ("id", "name", "set", "collector_number", "released_at", "type_line",
                   "border_color", "promo", "set_type", "image_uris")
_UA = "MTGCardScanner/1.0 ranker-fixtures (+https://github.com/DarylNo/CardScanner)"


def _sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def _dhex(v: float) -> str:
    return struct.pack(">d", float(v)).hex()


# ── inputs (network; only with --refresh-inputs) ────────────────────────────

def _get(session, url: str):
    for attempt in range(4):
        r = session.get(url, timeout=30)
        if r.status_code == 429:
            time.sleep(1 + attempt)
            continue
        r.raise_for_status()
        time.sleep(0.12)           # ≥100 ms apart: well under Scryfall's ~10 req/s
        return r
    r.raise_for_status()


def _simulate_photo(card: np.ndarray, size: tuple[int, int], seed: int) -> bytes:
    """A flattened card as the phone uploads it (cf. export_hash_fixtures):
    slightly off-square warp, lens blur, exposure/white-balance drift, JPEG."""
    rng = np.random.default_rng(seed)
    w, h = size
    big = cv2.resize(card, (w, h), interpolation=cv2.INTER_CUBIC)
    src = np.float32([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]])
    dst = src + rng.uniform(-9, 9, size=(4, 2)).astype(np.float32)
    m = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(big, m, (w, h), flags=cv2.INTER_LINEAR,
                                 borderMode=cv2.BORDER_REPLICATE)
    blurred = cv2.GaussianBlur(warped, (5, 5), 1.1)
    gain = np.array([1.06, 0.98, 0.93]) * rng.uniform(0.9, 1.1)
    lit = np.clip(blurred.astype(np.float64) * gain + rng.uniform(-8, 8), 0, 255).astype(np.uint8)
    ok, jpg = cv2.imencode(".jpg", lit, [cv2.IMWRITE_JPEG_QUALITY, 80])
    assert ok
    return jpg.tobytes()


def _trim(p: dict) -> dict:
    out = {k: p[k] for k in PRINTING_FIELDS if k in p}
    uris = p.get("image_uris") or {}
    out["image_uris"] = {k: uris[k] for k in ("small", "normal", "large") if k in uris}
    return out


def refresh_inputs() -> None:
    import requests

    from mtg_card_scanner.scryfall import ScryfallClient
    client = ScryfallClient()
    s = requests.Session()
    s.headers.update({"User-Agent": _UA, "Accept": "image/jpeg,image/*;q=0.9,*/*;q=0.8"})
    for sub in ("images", "scans"):
        d = OUT / sub
        d.mkdir(parents=True, exist_ok=True)
        for p in d.glob("*.jpg"):
            p.unlink()
    printings = {}
    for name in CARDS:
        ps = [_trim(p) for p in client.get_all_printings(name)]
        printings[name] = ps
        for p in ps:
            (OUT / "images" / f"{p['id']}.jpg").write_bytes(_get(s, p["image_uris"]["small"]).content)
        print(f"  {name}: {len(ps)} printings")
    forest = [p["id"] for p in client.get_all_printings(CAP_CARD)]
    for key, card, set_code, cn, seed in SCANS:
        p = next(p for p in printings[card] if p["set"] == set_code and p["collector_number"] == cn)
        normal = cv2.imdecode(np.frombuffer(_get(s, p["image_uris"]["normal"]).content, np.uint8),
                              cv2.IMREAD_COLOR)
        (OUT / "scans" / f"{key}.jpg").write_bytes(_simulate_photo(normal, SCAN_SIZE, seed))
    (OUT / "printings.json").write_text(json.dumps(printings, indent=1, ensure_ascii=False) + "\n",
                                        encoding="utf-8")
    (OUT / "forest_ids.json").write_text(json.dumps(forest) + "\n", encoding="utf-8")


# ── expected answers (the SERVER's code) ────────────────────────────────────

REGIONS = {
    "art": (vm.crop_art_region, (vm._ART_Y0, vm._ART_Y1, vm._ART_X0, vm._ART_X1)),
    "title": (vm.crop_title_region, (vm._TITLE_Y0, vm._TITLE_Y1, vm._TITLE_X0, vm._TITLE_X1)),
    "textbox": (vm.crop_textbox_region,
                (vm._TEXTBOX_Y0, vm._TEXTBOX_Y1, vm._TEXTBOX_X0, vm._TEXTBOX_X1)),
    "corner": (vm.crop_list_corner_region,
               (vm._LIST_CORNER_Y0, vm._LIST_CORNER_Y1, vm._LIST_CORNER_X0, vm._LIST_CORNER_X1)),
}


def _regions(pil) -> dict:
    out = {}
    w, h = pil.size
    for name, (fn, (y0, y1, x0, x1)) in REGIONS.items():
        crop = fn(pil)
        box = [int(w * x0), int(h * y0), int(w * x1), int(h * y1)]
        assert list(crop.size) == [box[2] - box[0], box[3] - box[1]]
        out[name] = {"box": box, "hash": str(imagehash.phash(crop))}
    return out


def _border(bgr: np.ndarray) -> dict:
    """_detect_border_color, with its luminance mean exposed (same lines)."""
    label = vm._detect_border_color(bgr)
    h, w = bgr.shape[:2]
    if h < 20 or w < 20:
        return {"label": label, "mean": None}
    y0, y1 = h // 4, 3 * h // 4
    border_w = max(8, w // 55)
    samples = np.concatenate([bgr[y0:y1, :border_w], bgr[y0:y1, w - border_w:]], axis=1)
    mean = float(cv2.cvtColor(samples, cv2.COLOR_BGR2GRAY).mean())
    return {"label": label, "mean": _dhex(mean), "rows": [y0, y1], "border_w": border_w}


def _dist(a: str, b: str) -> int:
    return int(imagehash.hex_to_hash(a) - imagehash.hex_to_hash(b))


def _recipe(recipe: str, printings: dict) -> list[dict]:
    kind, arg = recipe.split(":", 1)
    if kind == "card":
        return [dict(p) for p in printings[arg]]
    if kind == "concat":
        return [dict(p) for name in arg.split("|") for p in printings[name]]
    assert kind == "edge"
    ps = [dict(p) for p in printings[arg]]
    ps[1] = dict(ps[1], image_uris={"normal": ps[1]["image_uris"]["normal"]})     # url: normal
    ps[2] = dict(ps[2], image_uris={"large": ps[2]["image_uris"]["large"],
                                    "small": ""})                                 # url: large
    ps[3] = dict(ps[3], image_uris=None)                                          # no url → skip
    ps[4] = dict(ps[4], id="")                                                    # no id → skip
    ps[5] = dict(ps[5], id="00000000-0000-4000-8000-00000000dead")                # fetch fails → skip
    del ps[6]["image_uris"]                                                       # no image_uris → skip
    return ps


def build() -> dict[str, bytes]:
    printings = json.loads((OUT / "printings.json").read_text(encoding="utf-8"))
    forest = json.loads((OUT / "forest_ids.json").read_text(encoding="utf-8"))

    def load_image(url: str, sid: str):
        path = OUT / "images" / f"{sid}.jpg"
        return Image.open(path).convert("RGB")          # ArtMatcher._fetch_image's decode

    # ── candidates: decode + regions ────────────────────────────────────────
    candidates = {}
    for name in CARDS:
        for p in printings[name]:
            pil = load_image("", p["id"])
            candidates[p["id"]] = {"size": list(pil.size),
                                   "rgb_sha256": _sha(np.asarray(pil).tobytes()),
                                   "regions": _regions(pil)}

    # ── scans: decode + border + regions ───────────────────────────────────
    scans = {}
    scan_bgr = {}
    for key, *_ in SCANS:
        bgr = cv2.imdecode(np.frombuffer((OUT / "scans" / f"{key}.jpg").read_bytes(), np.uint8),
                           cv2.IMREAD_COLOR)
        scan_bgr[key] = bgr
        pil = Image.fromarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
        scans[key] = {"size": [bgr.shape[1], bgr.shape[0]],
                      "bgr_sha256": _sha(bgr.tobytes()),
                      "border": _border(bgr),
                      "regions": _regions(pil)}

    border_synth = []
    for h, w, color in BORDER_SYNTH:
        img = np.zeros((h, w, 3), np.uint8)
        img[:, :] = color
        border_synth.append({"h": h, "w": w, "bgr": list(color), **_border(img)})

    # ── runs ────────────────────────────────────────────────────────────────
    # Never touches its cache dir (_fetch_image is replaced) — but __init__ creates it.
    tmp = tempfile.TemporaryDirectory(prefix="ranker-fixtures-")      # removed when build() returns
    matcher = ArtMatcher(cache_dir=Path(tmp.name), request_delay=0)

    def fetch(url: str, sid: str):
        if not (OUT / "images" / f"{sid}.jpg").exists():
            raise FileNotFoundError(sid)
        fetch.urls.setdefault(sid, url)
        return load_image(url, sid)
    fetch.urls = {}
    matcher._fetch_image = fetch
    matcher._warp_frame = lambda frame: frame           # scans are already the warped card

    runs_spec = [(key, key, f"card:{card}", "", "") for key, card, *_ in SCANS] + EXTRA_RUNS
    runs = []
    for label, scan_key, recipe, vset, vnum in runs_spec:
        ps = _recipe(recipe, printings)
        frame = scan_bgr[scan_key]
        fetch.urls = {}
        matcher._hash_cache.clear()     # every run hashes (and so fetches) from scratch
        ranked = matcher.rank_printings(frame, ps)
        urls = dict(fetch.urls)
        is_basic = matcher._last_scan_is_basic
        border = matcher._last_scan_border_color
        assert border == scans[scan_key]["border"]["label"]
        sregions = scans[scan_key]["regions"]
        rows = []
        for r in ranked:
            creg = candidates[r["id"]]["regions"]
            d = {k: _dist(sregions[k]["hash"], creg[k]["hash"]) for k in REGIONS}
            assert d["art"] == r["phash_distance"] and d["corner"] == r["corner_distance"]
            multi = d["art"] if is_basic else (d["art"] * vm._WEIGHT_ART + d["title"] * vm._WEIGHT_TITLE
                                                 + d["textbox"] * vm._WEIGHT_TEXTBOX)
            assert multi == r["multi_distance"]
            assert r["phash_hash"] == creg["art"]["hash"]
            rows.append({"id": r["id"], "url": urls[r["id"]], "art": d["art"], "title": d["title"],
                         "textbox": d["textbox"], "corner": d["corner"],
                         "multi": r["multi_distance"], "phash_hash": r["phash_hash"]})
        # pipeline._ranked_candidates' re-sort, verbatim
        resorted = sorted(ranked, key=lambda p: p["multi_distance"] if p.get("multi_distance") is not None
                          else p.get("phash_distance", 1 << 30))
        best, ranked2, near_tie = matcher.best_match(frame, ps, vset, vnum)
        assert [r["id"] for r in ranked2] == [r["id"] for r in ranked]
        runs.append({
            "label": label, "scan": scan_key, "recipe": recipe,
            "input_ids": [p.get("id", "") for p in ps],
            "capped_ids": [p.get("id", "") for p in vm._cap_candidates(ps)],
            "is_basic": is_basic, "border": border,
            "ranked": rows,
            "pipeline_order": [r["id"] for r in resorted],
            "best_match": {
                "vision_set": vset, "vision_number": vnum,
                "best": best["id"] if best else None,
                "near_tie": near_tie,
                "printing_uncertain": matcher._last_scan_printing_uncertain,
                "top_candidates": matcher._last_scan_top_candidates,
                "corner_decision": matcher._last_list_corner_decision,
                "corner_distances": matcher._last_list_corner_distances,
            },
        })

    expected = {
        "constants": {
            "regions": {k: list(v[1]) for k, v in REGIONS.items()},
            "weights": [vm._WEIGHT_ART, vm._WEIGHT_TITLE, vm._WEIGHT_TEXTBOX],
            "near_tie": vm.NEAR_TIE_DISTANCE,
            "border_thresholds": [vm._BORDER_BLACK_THRESHOLD, vm._BORDER_WHITE_THRESHOLD],
            "max_candidates": vm._MAX_CANDIDATES_PER_SCAN,
            "stamp_sets": sorted(vm._STAMP_SETS),
            "list_corner_margin": vm._LIST_CORNER_MARGIN,
        },
        "cap_forest": [p["id"] for p in vm._cap_candidates([{"id": i} for i in forest])],
        "candidates": candidates,
        "scans": scans,
        "border_synth": border_synth,
        "runs": runs,
    }
    return {"expected.json": (json.dumps(expected, indent=1, sort_keys=True, ensure_ascii=False)
                              + "\n").encode("utf-8")}


def main() -> int:
    if "--refresh-inputs" in sys.argv:
        refresh_inputs()
    import contextlib
    with contextlib.redirect_stdout(io.StringIO()):     # ArtMatcher's [tiebreak] chatter
        files = build()
    if "--check" in sys.argv:
        stale = [n for n, b in files.items()
                 if not (OUT / n).exists() or (OUT / n).read_bytes() != b]
        if stale:
            print("ranker fixtures are stale — run: python scripts/export_ranker_fixtures.py")
            for n in stale:
                print("  ", n)
            return 1
        print(f"ranker fixtures up to date ({len(files)} files)")
        return 0
    for n, b in files.items():
        (OUT / n).write_bytes(b)
    print(f"wrote {', '.join(files)} to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
