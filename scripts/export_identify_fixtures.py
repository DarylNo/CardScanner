"""
Export end-to-end identification fixtures for the Android app's on-phone
identifier.

Runs the SERVER's real `Pipeline.scan_candidates` — card_detect's extract,
the blank guard, ArtIndex.identify, ScryfallClient.get_all_printings,
visual_match.rank_printings, _apply_ocr_hint, _mark_art_decisive — on the
committed ranker scans, and records the exact result dicts the server
returns. Only the edges are faked, all from committed inputs:

  - the art index = arthash/expected.json's rows + the index hash
    (ArtIndexBuilder's hashing) of every committed ranker `small` image;
  - Scryfall = the recorded /cards/search pages (printings/recorded.json.gz);
  - printing images = ranker/images (visual_match never touches the network);
  - OCR = a canned collector-line text per scenario, run through the same
    canonicalisation the phone's OcrStrip applies to its two engine reads.

The Kotlin IdentifyPipeline must reproduce every scenario's result
(IdentifyParityTest). CI re-runs this with --check.

    python scripts/export_identify_fixtures.py          # rewrite expected.json
    python scripts/export_identify_fixtures.py --check  # exit 1 if stale
"""

from __future__ import annotations

import gzip
import json
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import imagehash  # noqa: E402
from PIL import Image  # noqa: E402

import mtg_card_scanner.ocr_id as ocr_id  # noqa: E402
import mtg_card_scanner.visual_match as vm  # noqa: E402
from mtg_card_scanner.art_index import ArtIndex, _split_u64  # noqa: E402
from mtg_card_scanner.pipeline import Pipeline  # noqa: E402
from mtg_card_scanner.scryfall import ScryfallClient  # noqa: E402

RES = ROOT / "android" / "core" / "src" / "test" / "resources"
OUT = RES / "identify"

# (scenario key, ranker scan, canned OCR text or "" for nothing read)
SCENARIOS = [
    ("bolt_m10_ocr", "bolt_m10", "146/249 C\nM10 • EN CHRISTOPHER MOELLER"),
    ("bolt_m10_blind", "bolt_m10", ""),
    ("bolt_4ed_blind", "bolt_4ed", ""),
    ("bolt_plst_ocr", "bolt_plst", "CLB-187 PLST • EN"),
    ("shivan_7ed_blind", "shivan_7ed", ""),
    ("wastes_ogw_blind", "wastes_ogw", ""),
]


def _index_rows() -> list[list[str]]:
    rows = [list(r) for r in json.loads((RES / "arthash" / "expected.json").read_text())["index_rows"]]
    have = {r[0] for r in rows}
    printings = json.loads((RES / "ranker" / "printings.json").read_text())
    for name in sorted(printings):
        for p in printings[name]:
            if p["id"] in have:
                continue
            path = RES / "ranker" / "images" / f"{p['id']}.jpg"
            if not path.exists():
                continue
            # ArtIndexBuilder.build: the small image, art crop, phash 8 + 16.
            crop = vm.crop_art_region(Image.open(path).convert("RGB"))
            rows.append([p["id"], p["name"], p["set"], p["collector_number"], "",
                         str(imagehash.phash(crop)), str(imagehash.phash(crop, hash_size=16))])
            have.add(p["id"])
    return rows


def _index(rows: list[list[str]]) -> ArtIndex:
    idx = ArtIndex(index_dir=ROOT / ".no-such-index")
    idx._meta = [(r[0], r[1], r[2], r[3], r[4]) for r in rows]
    idx._h64 = np.array([int(r[5], 16) for r in rows], dtype=np.uint64)
    idx._h256 = np.array([_split_u64(r[6], 4) for r in rows], dtype=np.uint64)
    return idx                                   # the REAL _warp (extract_card)


class _Resp:
    def __init__(self, url: str, rec: dict):
        self.url = url
        self.status_code = rec["status"]
        self.headers = {"content-type": rec["content_type"]}
        self._body = rec["body"]

    def json(self):
        return json.loads(json.dumps(self._body))

    def raise_for_status(self):
        if self.status_code >= 400:
            import requests
            raise requests.HTTPError(f"{self.status_code} for url {self.url}")


class _Replay:
    def __init__(self, responses: dict):
        self.responses = responses
        self.headers = {}

    def get(self, url, params=None, timeout=None):
        import requests
        full = requests.Request("GET", url, params=params or {}).prepare().url
        if full not in self.responses:
            raise KeyError(f"no recorded response for {full}")
        return _Resp(full, self.responses[full])


def build() -> dict[str, bytes]:
    rows = _index_rows()
    responses = json.loads(gzip.decompress((RES / "printings" / "recorded.json.gz").read_bytes()))["responses"]
    scryfall = ScryfallClient()
    scryfall._session = _Replay(responses)

    tmp = tempfile.TemporaryDirectory(prefix="identify-fixtures-")
    matcher = vm.ArtMatcher(cache_dir=Path(tmp.name), request_delay=0)

    def fetch(url: str, sid: str):
        path = RES / "ranker" / "images" / f"{sid}.jpg"
        if not path.exists():
            raise FileNotFoundError(sid)
        return Image.open(path).convert("RGB")
    matcher._fetch_image = fetch

    pipeline = Pipeline(_index(rows), scryfall)
    pipeline._cached_art_matcher = matcher

    real_read = ocr_id.read_bottom_strip
    scenarios = {}
    try:
        for key, scan, text in SCENARIOS:
            # The phone's OcrStrip unions its engine's two reads into canonical words.
            canned = ocr_id.canon_words(" ".join([text, text]))
            ocr_id.read_bottom_strip = lambda card_bgr, _t=canned: _t
            frame = cv2.imdecode(np.frombuffer((RES / "ranker" / "scans" / f"{scan}.jpg").read_bytes(),
                                               np.uint8), cv2.IMREAD_COLOR)
            result = pipeline.scan_candidates([frame])
            scenarios[key] = {"scan": scan, "ocr_text": text, "result": result}
    finally:
        ocr_id.read_bottom_strip = real_read
        tmp.cleanup()

    expected = {"index_rows": rows, "scenarios": scenarios}
    return {"expected.json": (json.dumps(expected, indent=1, sort_keys=False, ensure_ascii=True) + "\n").encode()}


def main() -> int:
    files = build()
    if "--check" in sys.argv:
        stale = [n for n, b in files.items() if not (OUT / n).exists() or (OUT / n).read_bytes() != b]
        if stale:
            print("identify fixtures are stale — run: python scripts/export_identify_fixtures.py")
            for n in stale:
                print("  ", n)
            return 1
        print(f"identify fixtures up to date ({len(files)} files)")
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    for n, b in files.items():
        (OUT / n).write_bytes(b)
    print(f"wrote {len(files)} files to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
