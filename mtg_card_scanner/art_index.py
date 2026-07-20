"""
Global art-hash index — LLM-free card identification.

Builds a local index of the perceptual hash (pHash) of the ART REGION of every
unique Magic card artwork, from Scryfall's `unique_artwork` bulk data.  A
scanned frame is then identified by warping the card (card_detect), hashing the
same art region, and finding the nearest hashes in the index — no vision model,
no network at scan time.

Hash geometry MUST match visual_match.py exactly (same crop fractions, same
imagehash.phash defaults) so that scan-side and index-side hashes are
comparable.  Index images use Scryfall's `small` size (146×204, same 63:88
aspect as the 630×880 warp) — pHash downsamples to 32×32 internally, so
`small` is plenty and keeps the one-time download to ~0.5 GB.

Build (one-time, ~90–110 min, resumable — safe to Ctrl-C and re-run):
    python -m mtg_card_scanner.art_index build [--limit N] [--force]
    python -m mtg_card_scanner.art_index status
    python -m mtg_card_scanner.art_index query photo.jpg   # debug: identify a file

Storage layout (~/.cache/mtg-card-scanner/art_index/):
    art_index.sqlite   — one row per unique artwork (id, name, set, #, artist, hash)
    unique-artwork.json — Scryfall bulk file (kept for resume/incremental rebuild)
    images/{id}.jpg    — downloaded `small` images (kept so re-hashing after a
                         crop-tunable change needs no re-download)
"""

import argparse
import json
import sqlite3
import time
from pathlib import Path
from typing import Any, Optional

import numpy as np
import requests

SCRYFALL_BULK_MANIFEST = "https://api.scryfall.com/bulk-data"
_BULK_TYPE = "unique_artwork"
_USER_AGENT = "MTGCardScanner/1.0 art-index (contact: your-email@example.com)"

_DEFAULT_INDEX_DIR = Path.home() / ".cache" / "mtg-card-scanner" / "art_index"

# Identification uses TWO pHashes of the art region per artwork — coarse
# (64-bit, robust to photo conditions) and fine (256-bit, discriminative
# across ~50k artworks) — combined as  score = 4*d64 + d256  (equal scales).
# The scan side hashes a small grid of jittered crops (shifts/insets) and
# takes the per-artwork minimum, which absorbs warp misalignment from the
# card-quad detection.  Measured on real rig photos: correct artworks score
# ~110-125, the noise floor starts ~150.
#
# Thresholds are applied by the PIPELINE, not by identify() itself —
# identify() always returns scores so the CLI `query` helper can be used to
# tune these constants against real rig photos.
_MAX_CONFIDENT_DISTANCE = 140   # combined score: above this → manual search
_HIGH_CONFIDENCE_DISTANCE = 125  # combined score: at/below this → "high"

# Scan-side crop jitter grid (fractions of the card): ±shift on both axes plus
# inward AND outward insets.  This absorbs two distinct error sources: (a)
# imperfect card-quad warps, and (b) different printings cropping the SAME
# artwork differently (frame eras shift/zoom the art window — e.g. a 7ED
# artwork rescanned from an ORI-frame card needs an outset to line up).
# ~80 in-bounds variants ≈ 0.2 s per identify() over the full index.
_JITTER_SHIFTS = (-0.06, -0.03, 0.0, 0.03, 0.06)
_JITTER_INSETS = (-0.06, 0.0, 0.06, 0.12)

_REQUEST_DELAY = 0.12          # seconds — stays under Scryfall CDN ~10 req/s limit
_IMAGE_FETCH_TIMEOUT = 8       # seconds — fail fast on a stalled download
_COMMIT_EVERY = 200            # rows per sqlite commit during build
_PROGRESS_EVERY = 100          # rows between progress prints

# Non-sellable card objects that would pollute matches.  NOTE: digital and
# oversized entries are deliberately NOT filtered — `unique_artwork` picks ONE
# representative printing per artwork, and when that representative happens to
# be an Arena/MTGO or oversized printing, skipping it would silently drop a
# real paper artwork from the index (this actually happened: the M15 Shivan
# Dragon art was only represented by a digital printing).
_SKIP_LAYOUTS = frozenset({"token", "double_faced_token", "emblem", "art_series"})
_SKIP_IMAGE_STATUS = frozenset({"missing", "placeholder"})

_SCHEMA = """
CREATE TABLE IF NOT EXISTS art_hashes (
    scryfall_id      TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    set_code         TEXT NOT NULL,
    collector_number TEXT NOT NULL,
    artist           TEXT NOT NULL DEFAULT '',
    hash_hex         TEXT NOT NULL,
    hash256_hex      TEXT
);
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);
"""


class ArtIndexError(Exception):
    pass


def _connect(db_path: Path) -> sqlite3.Connection:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    conn.executescript(_SCHEMA)
    # Migrate a v1 (64-bit-only) table in place: the build loop treats rows
    # with a NULL hash256_hex as not-yet-indexed and re-hashes them from the
    # image cache without re-downloading.
    cols = {r[1] for r in conn.execute("PRAGMA table_info(art_hashes)")}
    if "hash256_hex" not in cols:
        conn.execute("ALTER TABLE art_hashes ADD COLUMN hash256_hex TEXT")
    return conn


def _split_u64(hash_hex: str, words: int) -> list[int]:
    """Split a hex hash string into big-endian 64-bit words."""
    v = int(hash_hex, 16)
    return [(v >> (64 * i)) & 0xFFFFFFFFFFFFFFFF for i in reversed(range(words))]


def _image_url(entry: dict[str, Any]) -> Optional[str]:
    """Resolve the `small` image URL — top-level first, then front face (DFCs)."""
    uris = entry.get("image_uris")
    if uris and uris.get("small"):
        return uris["small"]
    faces = entry.get("card_faces") or []
    if faces:
        uris = faces[0].get("image_uris") or {}
        if uris.get("small"):
            return uris["small"]
    return None


def _should_index(entry: dict[str, Any]) -> bool:
    """Filter bulk entries down to real, imaged card artworks (see _SKIP_LAYOUTS
    note — digital/oversized representatives are kept on purpose)."""
    if entry.get("layout", "") in _SKIP_LAYOUTS:
        return False
    if entry.get("image_status", "") in _SKIP_IMAGE_STATUS:
        return False
    return _image_url(entry) is not None


class ArtIndex:
    """Query side: load hashes once per process, identify frames by art alone."""

    def __init__(self, index_dir: Path = _DEFAULT_INDEX_DIR) -> None:
        self.index_dir = Path(index_dir)
        self.db_path = self.index_dir / "art_index.sqlite"
        self._h64: Optional[np.ndarray] = None      # uint64[N]
        self._h256: Optional[np.ndarray] = None     # uint64[N, 4]
        self._meta: list[tuple[str, str, str, str, str]] = []  # (id, name, set, num, artist)

    def is_built(self) -> bool:
        return self.db_path.exists() and self.count() > 0

    def count(self) -> int:
        if not self.db_path.exists():
            return 0
        with sqlite3.connect(self.db_path) as conn:
            try:
                return conn.execute(
                    "SELECT COUNT(*) FROM art_hashes WHERE hash256_hex IS NOT NULL"
                ).fetchone()[0]
            except sqlite3.OperationalError:
                return 0

    def _load(self) -> None:
        if self._h64 is not None:
            return
        if not self.db_path.exists():
            raise ArtIndexError(
                "Art index not built — run: python -m mtg_card_scanner.art_index build"
            )
        with sqlite3.connect(self.db_path) as conn:
            try:
                rows = conn.execute(
                    "SELECT scryfall_id, name, set_code, collector_number, artist,"
                    " hash_hex, hash256_hex FROM art_hashes"
                    " WHERE hash256_hex IS NOT NULL"
                ).fetchall()
            except sqlite3.OperationalError:
                rows = []
        if not rows:
            raise ArtIndexError(
                "Art index is empty — run: python -m mtg_card_scanner.art_index build"
            )
        self._meta = [(r[0], r[1], r[2], r[3], r[4]) for r in rows]
        self._h64 = np.array([int(r[5], 16) for r in rows], dtype=np.uint64)
        self._h256 = np.array(
            [_split_u64(r[6], 4) for r in rows], dtype=np.uint64
        )
        print(f"  [art_index] loaded {len(rows)} art hashes")

    @staticmethod
    def _warp(frame: np.ndarray) -> np.ndarray:
        """Warp the card out of a raw frame (mirrors ArtMatcher._warp_frame)."""
        try:
            from mtg_card_scanner.card_detect import extract_card
            warped, detected = extract_card(frame)
            if detected:
                return warped
        except Exception:
            pass
        return frame

    def _hash_variants(self, frame: np.ndarray) -> list[tuple[int, list[int]]]:
        """Return (h64, h256-words) for each jittered art crop of the card."""
        import imagehash
        from mtg_card_scanner.visual_match import (
            _crop_region, _ART_Y0, _ART_Y1, _ART_X0, _ART_X1,
        )
        card = self._warp(frame)
        art_h, art_w = _ART_Y1 - _ART_Y0, _ART_X1 - _ART_X0
        variants: list[tuple[int, list[int]]] = []
        for dy in _JITTER_SHIFTS:
            for dx in _JITTER_SHIFTS:
                for inset in _JITTER_INSETS:
                    y0 = _ART_Y0 + dy + inset * art_h / 2
                    y1 = _ART_Y1 + dy - inset * art_h / 2
                    x0 = _ART_X0 + dx + inset * art_w / 2
                    x1 = _ART_X1 + dx - inset * art_w / 2
                    if y0 < 0 or x0 < 0 or y1 > 1 or x1 > 1:
                        continue
                    crop = _crop_region(card, y0, y1, x0, x1)
                    h64 = int(str(imagehash.phash(crop)), 16)
                    h256 = _split_u64(str(imagehash.phash(crop, hash_size=16)), 4)
                    variants.append((h64, h256))
        return variants

    def identify(self, frame: np.ndarray, top_n: int = 5) -> list[dict[str, Any]]:
        """
        Identify the card in *frame* by art alone.

        Hashes a grid of jittered art crops, scores every indexed artwork with
        the combined coarse+fine distance (min over crops), and returns up to
        *top_n* matches deduped by card name (best score per name), ascending:
            [{"name", "scryfall_id", "set", "collector_number", "artist",
              "distance"}, ...]

        No thresholding here — callers decide what score is "confident"
        (see _MAX_CONFIDENT_DISTANCE).
        """
        self._load()
        assert self._h64 is not None and self._h256 is not None
        best = np.full(len(self._h64), 4096, dtype=np.uint16)
        for h64, h256 in self._hash_variants(frame):
            d = 4 * np.bitwise_count(self._h64 ^ np.uint64(h64)).astype(np.uint16)
            d += np.bitwise_count(
                self._h256 ^ np.array(h256, dtype=np.uint64)
            ).sum(axis=1).astype(np.uint16)
            np.minimum(best, d, out=best)
        order = np.argsort(best, kind="stable")

        results: list[dict[str, Any]] = []
        seen_names: set[str] = set()
        for idx in order:
            sid, name, set_code, num, artist = self._meta[idx]
            if name in seen_names:
                continue
            seen_names.add(name)
            results.append({
                "name": name,
                "scryfall_id": sid,
                "set": set_code,
                "collector_number": num,
                "artist": artist,
                "distance": int(best[idx]),
            })
            if len(results) >= top_n:
                break
        return results


class ArtIndexBuilder:
    """Build side: bulk manifest → bulk JSON → throttled image fetch → hash → sqlite."""

    def __init__(
        self,
        index_dir: Path = _DEFAULT_INDEX_DIR,
        request_delay: float = _REQUEST_DELAY,
        session: Optional[requests.Session] = None,
    ) -> None:
        self.index_dir = Path(index_dir)
        self.db_path = self.index_dir / "art_index.sqlite"
        self.bulk_path = self.index_dir / "unique-artwork.json"
        self.images_dir = self.index_dir / "images"
        self._session = session or requests.Session()
        self._session.headers["User-Agent"] = _USER_AGENT
        self._delay = request_delay
        self._last_request: float = 0.0

    # ── network helpers ───────────────────────────────────────────────────────

    def _throttle(self) -> None:
        elapsed = time.monotonic() - self._last_request
        if elapsed < self._delay:
            time.sleep(self._delay - elapsed)

    def _fetch_manifest(self) -> dict[str, Any]:
        resp = self._session.get(SCRYFALL_BULK_MANIFEST, timeout=30)
        resp.raise_for_status()
        for entry in resp.json().get("data", []):
            if entry.get("type") == _BULK_TYPE:
                return entry
        raise ArtIndexError(f"Scryfall bulk manifest has no '{_BULK_TYPE}' entry")

    def _download_bulk(self, manifest: dict[str, Any], force: bool) -> None:
        """Download the bulk JSON unless a copy from this bulk revision exists."""
        updated_at = manifest.get("updated_at", "")
        size_mb = manifest.get("size", 0) / 1e6
        with _connect(self.db_path) as conn:
            row = conn.execute(
                "SELECT value FROM meta WHERE key='bulk_updated_at'"
            ).fetchone()
        have = row[0] if row else ""
        if self.bulk_path.exists() and have == updated_at and not force:
            print(f"  [art_index] bulk file current ({updated_at}) — skipping download")
            return

        print(f"  [art_index] downloading {_BULK_TYPE} bulk ({size_mb:.0f} MB, {updated_at}) ...")
        resp = self._session.get(manifest["download_uri"], stream=True, timeout=60)
        resp.raise_for_status()
        done = 0
        with open(self.bulk_path, "wb") as fh:
            for chunk in resp.iter_content(chunk_size=1 << 20):
                fh.write(chunk)
                done += len(chunk)
                if done % (50 << 20) < (1 << 20):
                    print(f"  [art_index] downloaded {done / 1e6:.0f}/{size_mb:.0f} MB")
        with _connect(self.db_path) as conn:
            conn.execute(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('bulk_updated_at', ?)",
                (updated_at,),
            )
        print(f"  [art_index] bulk file saved ({done / 1e6:.0f} MB)")

    def _fetch_image(self, url: str, scryfall_id: str) -> Any:
        """Return a PIL Image for the artwork — disk-cached like ArtMatcher."""
        from PIL import Image as PILImage
        self.images_dir.mkdir(parents=True, exist_ok=True)
        cached = self.images_dir / f"{scryfall_id}.jpg"
        if cached.exists():
            return PILImage.open(cached).convert("RGB")
        self._throttle()
        resp = self._session.get(url, timeout=_IMAGE_FETCH_TIMEOUT)
        self._last_request = time.monotonic()
        resp.raise_for_status()
        cached.write_bytes(resp.content)
        return PILImage.open(cached).convert("RGB")

    # ── public ────────────────────────────────────────────────────────────────

    def status(self) -> dict[str, Any]:
        with _connect(self.db_path) as conn:
            indexed = conn.execute(
                "SELECT COUNT(*) FROM art_hashes WHERE hash256_hex IS NOT NULL"
            ).fetchone()[0]
            row = conn.execute(
                "SELECT value FROM meta WHERE key='bulk_updated_at'"
            ).fetchone()
        return {
            "indexed": indexed,
            "bulk_file_present": self.bulk_path.exists(),
            "bulk_updated_at": row[0] if row else None,
            "index_dir": str(self.index_dir),
        }

    def build(self, limit: Optional[int] = None, force: bool = False) -> None:
        """
        Build (or resume) the index.  Idempotent: already-indexed artworks are
        skipped, so an interrupted build continues where it left off and a
        re-run after a bulk refresh only fetches new artworks.
        """
        import imagehash
        from mtg_card_scanner.visual_match import crop_art_region

        manifest = self._fetch_manifest()
        self._download_bulk(manifest, force)

        print("  [art_index] parsing bulk JSON (large — one-off memory spike is expected) ...")
        with open(self.bulk_path, encoding="utf-8") as fh:
            entries = json.load(fh)

        candidates = [e for e in entries if _should_index(e)]
        skipped_filter = len(entries) - len(candidates)
        del entries

        conn = _connect(self.db_path)
        try:
            # A row counts as done only when BOTH hashes are present — v1 rows
            # (64-bit only) are re-processed, which is cheap because their
            # images are already in the on-disk cache.
            have: set[str] = {
                r[0] for r in conn.execute(
                    "SELECT scryfall_id FROM art_hashes WHERE hash256_hex IS NOT NULL"
                )
            }
            todo = [e for e in candidates if e["id"] not in have]
            print(
                f"  [art_index] {len(candidates)} indexable artworks "
                f"({skipped_filter} filtered out) — {len(have)} already indexed, "
                f"{len(todo)} to fetch"
            )
            if limit is not None:
                todo = todo[:limit]
                print(f"  [art_index] --limit {limit}: building {len(todo)} this run")

            started = time.monotonic()
            new_rows = 0
            failures = 0
            for entry in todo:
                sid = entry["id"]
                url = _image_url(entry)
                try:
                    img = self._fetch_image(url, sid)
                    crop = crop_art_region(img)
                    hash_hex = str(imagehash.phash(crop))
                    hash256_hex = str(imagehash.phash(crop, hash_size=16))
                except Exception as exc:
                    failures += 1
                    print(f"  [art_index] skip {sid}: {exc}")
                    continue
                conn.execute(
                    "INSERT OR REPLACE INTO art_hashes "
                    "(scryfall_id, name, set_code, collector_number, artist,"
                    " hash_hex, hash256_hex) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (
                        sid,
                        entry.get("name", ""),
                        entry.get("set", ""),
                        entry.get("collector_number", ""),
                        entry.get("artist", ""),
                        hash_hex,
                        hash256_hex,
                    ),
                )
                new_rows += 1
                if new_rows % _COMMIT_EVERY == 0:
                    conn.commit()
                if new_rows % _PROGRESS_EVERY == 0:
                    elapsed = time.monotonic() - started
                    rate = new_rows / elapsed if elapsed > 0 else 0.0
                    remaining = len(todo) - new_rows
                    eta_min = remaining / rate / 60 if rate > 0 else 0.0
                    print(
                        f"  [art_index] {len(have) + new_rows}/{len(candidates)} hashed"
                        f"  ({rate:.1f}/s, ETA {eta_min:.0f} min)"
                    )
            conn.commit()
        finally:
            conn.close()
        print(
            f"  [art_index] build done: {new_rows} new rows, {failures} failures, "
            f"{self.status()['indexed']} total indexed"
        )


# ── CLI ───────────────────────────────────────────────────────────────────────

def _cli() -> None:
    parser = argparse.ArgumentParser(
        prog="python -m mtg_card_scanner.art_index",
        description="Build and query the global art-hash identification index.",
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_build = sub.add_parser("build", help="build or resume the index (one-time, ~90-110 min)")
    p_build.add_argument("--limit", type=int, default=None,
                         help="stop after N newly indexed artworks (smoke testing)")
    p_build.add_argument("--force", action="store_true",
                         help="re-download the bulk JSON even if current")

    sub.add_parser("status", help="show index row count and bulk revision")

    p_query = sub.add_parser("query", help="identify a card photo (debug/threshold tuning)")
    p_query.add_argument("image", help="path to a photo of a card")
    p_query.add_argument("--top", type=int, default=5)

    args = parser.parse_args()

    if args.cmd == "build":
        ArtIndexBuilder().build(limit=args.limit, force=args.force)
    elif args.cmd == "status":
        for k, v in ArtIndexBuilder().status().items():
            print(f"  {k}: {v}")
    elif args.cmd == "query":
        import cv2
        frame = cv2.imread(args.image)
        if frame is None:
            raise SystemExit(f"cannot read image: {args.image}")
        for m in ArtIndex().identify(frame, top_n=args.top):
            marker = "  <-- confident" if m["distance"] <= _MAX_CONFIDENT_DISTANCE else ""
            line = (
                f"  s={m['distance']:3d}  {m['name']}  "
                f"[{m['set'].upper()} #{m['collector_number']}]  {m['artist']}{marker}"
            )
            # Windows consoles may not be UTF-8 — never crash on an accent.
            print(line.encode("ascii", errors="replace").decode("ascii"))


if __name__ == "__main__":
    _cli()
