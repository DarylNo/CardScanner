"""
Replay the rig's stored scans through the SERVER's art identification and
record what it answered — the reference the phone's Kotlin port
(ArtMatcher, android/core) is replayed against in Stage 2 of
docs/PHONE_ONLY_PLAN.md. Run it ON THE RIG (it needs the built art index and
the real `scan_images/`); the output is plain JSON the Kotlin side can load.

What a stored scan is: server/app.py keeps `scan_images/<id>.jpg` = the
630×880 warp of the sharpest frame when the card's edges were found, else the
raw frame. For each file this records:

  identify_flat    ArtIndex.identify with the warp step BYPASSED — the image
                   is taken as the already-flattened card. This is exactly
                   what the phone computes (ArtMatcher.identify(cardBgr)), so
                   it is the one to replay bit-for-bit.
  identify_server  (only with --with-warp) ArtIndex.identify as the server
                   calls it, re-running card detection on the stored image —
                   informational; detection is not part of the hash port.
  confident / confidence   pipeline._is_confident / _confidence_for on
                   identify_flat (the thresholds the phone mirrors in
                   ArtThresholds).
  bgr_sha256       sha256 of the DECODED pixels (cv2.imread, BGR, row-major):
                   JPEG decoders can differ by a level here and there, so the
                   Kotlin replay checks this first and can tell "my decoder
                   differs" apart from "my hashes differ".

The header pins the index it ran against (row count, bulk revision and a
digest of every row's id + hashes, in load order) so the replay can refuse to
compare against a different fingerprint pack.

Usage (on the rig, from the repo root, with the rig's env):

    python scripts/replay_scans.py                       # scan_images/*.jpg → replay_scans.json
    python scripts/replay_scans.py --images scan_images --out replay.json --top 5
    python scripts/replay_scans.py --limit 50            # quick sample
    python scripts/replay_scans.py --index-dir ~/.cache/mtg-card-scanner/art_index --with-warp

Scans are processed in numeric id order (non-numeric names last, sorted).
A file that cannot be decoded is recorded with an "error" and skipped.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
import time
from pathlib import Path

import cv2

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from mtg_card_scanner.art_index import _DEFAULT_INDEX_DIR, ArtIndex  # noqa: E402
from mtg_card_scanner.pipeline import _confidence_for, _is_confident  # noqa: E402


def _order(p: Path):
    return (0, int(p.stem), "") if p.stem.isdigit() else (1, 0, p.name)


def _index_header(idx: ArtIndex) -> dict:
    idx._load()
    d = hashlib.sha256()
    for (sid, *_), h64, h256 in zip(idx._meta, idx._h64.tolist(), idx._h256.tolist()):
        d.update(f"{sid}:{h64:016x}:{''.join(f'{w:016x}' for w in h256)}\n".encode())
    bulk = None
    try:
        with sqlite3.connect(idx.db_path) as conn:
            row = conn.execute("SELECT value FROM meta WHERE key='bulk_updated_at'").fetchone()
            bulk = row[0] if row else None
    except sqlite3.Error:
        pass
    return {"path": str(idx.db_path), "rows": len(idx._meta), "bulk_updated_at": bulk,
            "rows_sha256": d.hexdigest()}


def _slim(matches: list[dict]) -> list[dict]:
    return [{k: m[k] for k in ("name", "scryfall_id", "set", "collector_number", "distance")}
            for m in matches]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--index-dir", type=Path, default=_DEFAULT_INDEX_DIR)
    ap.add_argument("--images", type=Path, default=Path("scan_images"))
    ap.add_argument("--out", type=Path, default=Path("replay_scans.json"))
    ap.add_argument("--top", type=int, default=5)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--with-warp", action="store_true",
                    help="also record identify() WITH the server's card re-detection")
    args = ap.parse_args()

    files = sorted(args.images.glob("*.jpg"), key=_order)
    if args.limit is not None:
        files = files[:args.limit]
    if not files:
        print(f"no *.jpg under {args.images}")
        return 1

    server = ArtIndex(args.index_dir)
    header = _index_header(server)
    # Same loaded arrays, warp bypassed: the input IS the flattened card.
    flat = ArtIndex(args.index_dir)
    flat._meta, flat._h64, flat._h256 = server._meta, server._h64, server._h256
    flat._warp = lambda frame: frame

    results = []
    t0 = time.monotonic()
    for i, path in enumerate(files, 1):
        entry: dict = {"file": path.name}
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if img is None:
            entry["error"] = "cannot decode"
            results.append(entry)
            continue
        entry["width"], entry["height"] = int(img.shape[1]), int(img.shape[0])
        entry["bgr_sha256"] = hashlib.sha256(img.tobytes()).hexdigest()
        matches = flat.identify(img, top_n=args.top)
        entry["identify_flat"] = _slim(matches)
        entry["confident"] = _is_confident(matches)
        entry["confidence"] = _confidence_for(matches[0]["distance"]) if matches else None
        if args.with_warp:
            entry["identify_server"] = _slim(server.identify(img, top_n=args.top))
        results.append(entry)
        if i % 25 == 0 or i == len(files):
            rate = i / max(time.monotonic() - t0, 1e-9)
            print(f"  {i}/{len(files)}  ({rate:.1f}/s)")

    out = {"index": header, "top": args.top, "with_warp": args.with_warp, "scans": results}
    args.out.write_text(json.dumps(out, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {len(results)} scans to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
