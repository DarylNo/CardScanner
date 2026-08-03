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
# Calibrated on real rig photos AFTER the dense two-stage search, which pulled
# correct matches down to 68-78 while leaving near-misses at 124+. The gap is
# wide, so the bar sits in the middle: a genuinely ambiguous card (e.g. an
# ORI-frame Shivan Dragon whose indexed artwork is the 7ED printing, red on
# red, under glare) now falls through to manual search instead of being
# returned as a confidently WRONG card.
_MAX_CONFIDENT_DISTANCE = 110   # combined score: above this → manual search
_HIGH_CONFIDENCE_DISTANCE = 90  # combined score: at/below this → "high"
# Margin acceptance: a best match somewhat over the absolute bar is still
# trustworthy when NOTHING else comes close — noise-floor matches cluster
# tightly, a lone leader does not. Observed live: the correct card at d=116
# (bar 110) with the runner-up 22 points back was kicked to manual search.
# Only applies below the noise floor (~144), so junk can't ride a fluke gap.
_MARGIN_CONFIDENT_DISTANCE = 140  # margin rule ceiling for the best score
_MARGIN_MIN_GAP = 20              # required lead over the second-best NAME
# Above this, the frame almost certainly contains NO card at all (empty tray /
# lens cap): measured empty scenes score ≥236 while even badly-glared real
# cards stay ≤~170. Scans in this band are rejected outright rather than
# stored as junk "unidentified" rows.
_NO_CARD_DISTANCE = 210

# Scan-side crop jitter grid (fractions of the card): ±shift on both axes plus
# inward AND outward insets.  This absorbs two distinct error sources: (a)
# imperfect card-quad warps, and (b) different printings cropping the SAME
# artwork differently (frame eras shift/zoom the art window — e.g. a 7ED
# artwork rescanned from an ORI-frame card needs an outset to line up).
#
# identify() is TIERED for speed: the small core grid runs first and the rest
# of the full grid only runs when the core result isn't confident (adding
# variants can only lower scores, so early-exit at the threshold is safe).
_JITTER_SHIFTS = (-0.06, -0.03, 0.0, 0.03, 0.06)
_JITTER_INSETS = (-0.06, 0.0, 0.06, 0.12)
_CORE_SHIFTS = (-0.03, 0.0, 0.03)
_CORE_INSETS = (0.0, 0.06)


def _variant_params(shifts, insets) -> list[tuple[float, float, float]]:
    return [(dy, dx, ins) for dy in shifts for dx in shifts for ins in insets]


_CORE_PARAMS = _variant_params(_JITTER_SHIFTS, _JITTER_INSETS)

# Stage 2 refinement grid. Scores proved sensitive to crop shifts as small as
# 1% — a cross-frame reprint (e.g. a 7ED artwork rescanned from an ORI-frame
# card) can sit right at the noise floor on the coarse grid yet separate
# cleanly a few hundredths away — so the shortlist is rescored on a much
# denser grid. Cheap because it only touches _SHORTLIST rows.
_DENSE_SHIFTS = (-0.06, -0.04, -0.02, 0.0, 0.02, 0.04, 0.06)
_DENSE_INSETS = (-0.10, -0.07, -0.04, -0.01, 0.02, 0.05, 0.08, 0.12)
_DENSE_PARAMS = _variant_params(_DENSE_SHIFTS, _DENSE_INSETS)
_SHORTLIST = 400

# Crops are taken from a half-size warp: pHash reduces to 32×32 internally, so
# hashes match the full-size ones to <1 bit on average while costing ~9× less.
_HASH_SCALE = 0.5


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

    def _card_pil(self, frame: np.ndarray):
        """Warp the card out of *frame* and return it as a half-size PIL image."""
        import cv2
        from PIL import Image as PILImage
        card = self._warp(frame)
        if _HASH_SCALE != 1.0:
            card = cv2.resize(
                card, (int(card.shape[1] * _HASH_SCALE), int(card.shape[0] * _HASH_SCALE)),
                interpolation=cv2.INTER_AREA)
        return PILImage.fromarray(cv2.cvtColor(card, cv2.COLOR_BGR2RGB))

    def _hash_variants(
        self,
        frame: np.ndarray,
        params: Optional[list[tuple[float, float, float]]] = None,
        card_pil=None,
    ) -> list[tuple[int, list[int]]]:
        """Return (h64, h256-words) for each jittered art crop of the card."""
        import imagehash
        from mtg_card_scanner.visual_match import (
            _crop_region, _ART_Y0, _ART_Y1, _ART_X0, _ART_X1,
        )
        if card_pil is None:
            card_pil = self._card_pil(frame)
        art_h, art_w = _ART_Y1 - _ART_Y0, _ART_X1 - _ART_X0
        variants: list[tuple[int, list[int]]] = []
        for dy, dx, inset in (params if params is not None else _CORE_PARAMS):
            y0 = _ART_Y0 + dy + inset * art_h / 2
            y1 = _ART_Y1 + dy - inset * art_h / 2
            x0 = _ART_X0 + dx + inset * art_w / 2
            x1 = _ART_X1 + dx - inset * art_w / 2
            if y0 < 0 or x0 < 0 or y1 > 1 or x1 > 1:
                continue
            crop = _crop_region(card_pil, y0, y1, x0, x1)
            h64 = int(str(imagehash.phash(crop)), 16)
            h256 = _split_u64(str(imagehash.phash(crop, hash_size=16)), 4)
            variants.append((h64, h256))
        return variants

    @staticmethod
    def _score(h64_arr: np.ndarray, h256_arr: np.ndarray, variants) -> np.ndarray:
        """Best (minimum) combined distance per row over all crop variants."""
        best = np.full(len(h64_arr), 4096, dtype=np.uint16)
        for h64, h256 in variants:
            d = 4 * np.bitwise_count(h64_arr ^ np.uint64(h64)).astype(np.uint16)
            d += np.bitwise_count(
                h256_arr ^ np.array(h256, dtype=np.uint64)
            ).sum(axis=1).astype(np.uint16)
            np.minimum(best, d, out=best)
        return best

    def identify(self, frame: np.ndarray, top_n: int = 5) -> list[dict[str, Any]]:
        """
        Identify the card in *frame* by art alone.

        Hashes a grid of jittered art crops, scores every indexed artwork with
        the combined coarse+fine distance (min over crops), and returns up to
        *top_n* matches deduped by card name (best score per name), ascending:
            [{"name", "scryfall_id", "set", "collector_number", "artist",
              "distance"}, ...]

        Two stages: the whole index is scored on a coarse crop grid, then the
        best _SHORTLIST rows are RE-scored on a much denser grid.  Scoring
        everything densely would be needlessly slow, and scoring everything
        coarsely is not accurate enough — a cross-frame reprint can sit at the
        noise floor on the coarse grid and separate cleanly a few hundredths
        of a crop away.  (An earlier version instead skipped work when the
        coarse pass "looked confident", which silently returned wrong cards.)

        No thresholding here — callers decide what score is "confident"
        (see _MAX_CONFIDENT_DISTANCE).
        """
        self._load()
        assert self._h64 is not None and self._h256 is not None
        card_pil = self._card_pil(frame)

        coarse = self._score(self._h64, self._h256,
                             self._hash_variants(frame, _CORE_PARAMS, card_pil))
        shortlist = np.argsort(coarse, kind="stable")[:_SHORTLIST]
        dense = self._score(self._h64[shortlist], self._h256[shortlist],
                            self._hash_variants(frame, _DENSE_PARAMS, card_pil))
        order = shortlist[np.argsort(dense, kind="stable")]
        score_by_row = dict(zip(shortlist.tolist(), dense.tolist()))

        results: list[dict[str, Any]] = []
        seen_names: set[str] = set()
        for idx in order:
            sid, name, set_code, num, artist = self._meta[idx]
            # Arena Alchemy rebalances ("A-Return Upon the Tide") share the
            # paper card's artwork under a digital-only name Scryfall prefixes
            # with "A-". Canonicalize to the paper name: otherwise the A- twin
            # can win the dedupe tie and its printings lookup finds only
            # digital cards, which the paper filter (correctly) empties.
            if name.startswith("A-"):
                name = name[2:]
            if name in seen_names:
                continue
            seen_names.add(name)
            results.append({
                "name": name,
                "scryfall_id": sid,
                "set": set_code,
                "collector_number": num,
                "artist": artist,
                "distance": int(score_by_row[int(idx)]),
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

    def _fetch_manifest(self, bulk_type: str = _BULK_TYPE) -> dict[str, Any]:
        resp = self._session.get(SCRYFALL_BULK_MANIFEST, timeout=30)
        resp.raise_for_status()
        for entry in resp.json().get("data", []):
            if entry.get("type") == bulk_type:
                return entry
        raise ArtIndexError(f"Scryfall bulk manifest has no '{bulk_type}' entry")

    def _download_bulk(
        self,
        manifest: dict[str, Any],
        force: bool,
        dest: Optional[Path] = None,
        meta_key: str = "bulk_updated_at",
    ) -> None:
        """Download a bulk JSON unless a copy from this bulk revision exists."""
        dest = dest or self.bulk_path
        bulk_type = manifest.get("type", _BULK_TYPE)
        updated_at = manifest.get("updated_at", "")
        # 2026-08: Scryfall replaced download_uri (raw JSON array) with
        # jsonl_download_uri (gzipped JSONL) and size with compressed_size.
        # Accept either; _load_bulk_entries sniffs the actual file format.
        url = manifest.get("download_uri") or manifest.get("jsonl_download_uri")
        if not url:
            raise ArtIndexError(
                f"Scryfall '{bulk_type}' manifest has no download URL "
                f"(fields: {sorted(manifest)})")
        size_mb = (manifest.get("size") or manifest.get("compressed_size", 0)) / 1e6
        with _connect(self.db_path) as conn:
            row = conn.execute(
                "SELECT value FROM meta WHERE key=?", (meta_key,)
            ).fetchone()
        have = row[0] if row else ""
        if dest.exists() and have == updated_at and not force:
            print(f"  [art_index] {bulk_type} bulk current ({updated_at}) — skipping download")
            return

        print(f"  [art_index] downloading {bulk_type} bulk ({size_mb:.0f} MB, {updated_at}) ...")
        resp = self._session.get(url, stream=True, timeout=60)
        resp.raise_for_status()
        done = 0
        with open(dest, "wb") as fh:
            for chunk in resp.iter_content(chunk_size=1 << 20):
                fh.write(chunk)
                done += len(chunk)
                if done % (50 << 20) < (1 << 20):
                    print(f"  [art_index] downloaded {done / 1e6:.0f}/{size_mb:.0f} MB")
        with _connect(self.db_path) as conn:
            conn.execute(
                "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
                (meta_key, updated_at),
            )
        print(f"  [art_index] bulk file saved ({done / 1e6:.0f} MB)")

    @staticmethod
    def _load_bulk_entries(path: Path) -> list[dict[str, Any]]:
        """Parse a bulk file in either Scryfall format, sniffed from the bytes:
        legacy raw JSON array, or the 2026+ (optionally gzipped) JSONL."""
        import gzip
        with open(path, "rb") as fh:
            magic = fh.read(2)
        opener = gzip.open if magic == b"\x1f\x8b" else open
        with opener(path, "rt", encoding="utf-8") as fh:      # type: ignore[operator]
            first = fh.read(1)
            fh.seek(0)
            if first == "[":
                return json.load(fh)
            return [json.loads(line) for line in fh if line.strip()]

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

        print("  [art_index] parsing bulk data (large — one-off memory spike is expected) ...")
        entries = self._load_bulk_entries(self.bulk_path)

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

    def prefetch_printings(self, limit: Optional[int] = None, force: bool = False) -> None:
        """
        Predownload the ranking image of EVERY printing into ArtMatcher's disk
        cache so rank_printings never waits on Scryfall at scan time.

        Uses the `default_cards` bulk (one entry per printing) and the same
        `small`-size URL preference as rank_printings.  ~100k paper images
        ≈ 2 GB; resumable (existing files are skipped) and
        incremental — re-run after new set releases to top up.
        """
        from mtg_card_scanner.visual_match import _DEFAULT_CACHE_DIR

        manifest = self._fetch_manifest("default_cards")
        bulk_dest = self.index_dir / "default-cards.json"
        self._download_bulk(manifest, force, dest=bulk_dest,
                            meta_key="printings_bulk_updated_at")

        print("  [art_index] parsing printings bulk (large — one-off memory spike is expected) ...")
        entries = self._load_bulk_entries(bulk_dest)

        def rank_url(e: dict[str, Any]) -> Optional[str]:
            # Mirror rank_printings: `small` — the ranking cache is hash-only
            # (pHash → 32×32) and the UI shows candidates from Scryfall's CDN,
            # so small suffices and cuts the full prefetch ~6× (~10 GB → ~2 GB).
            for holder in (e, *(e.get("card_faces") or [])[:1]):
                uris = holder.get("image_uris") or {}
                url = uris.get("small") or uris.get("normal") or uris.get("large")
                if url:
                    return url
            return None

        def _wanted(e: dict[str, Any]) -> bool:
            # Only prefetch images we could ever rank or show. Candidates are
            # PAPER printings excluding art-series/tokens (scryfall.get_all_
            # printings); a digital-only or art-series image would just eat
            # disk (~of the ~10 GB total) and never be looked at.
            if e.get("layout", "") in _SKIP_LAYOUTS:
                return False
            games = e.get("games") or ["paper"]     # missing → treat as paper
            return "paper" in games

        cache_dir = Path(_DEFAULT_CACHE_DIR)
        cache_dir.mkdir(parents=True, exist_ok=True)
        total = len(entries)
        wanted = [(e["id"], url) for e in entries
                  if _wanted(e) and (url := rank_url(e))]
        skipped = total - len(wanted)
        print(f"  [art_index] {skipped} non-paper/non-card printings skipped "
              f"(digital, tokens, art series — never ranked or shown)")
        del entries
        todo = [(sid, url) for sid, url in wanted
                if not (cache_dir / f"{sid}.jpg").exists()]
        print(
            f"  [art_index] {len(wanted)} printings with images — "
            f"{len(wanted) - len(todo)} already cached, {len(todo)} to fetch"
        )
        if limit is not None:
            todo = todo[:limit]
            print(f"  [art_index] --limit {limit}: fetching {len(todo)} this run")

        started = time.monotonic()
        fetched = 0
        failures = 0
        for sid, url in todo:
            dest = cache_dir / f"{sid}.jpg"
            try:
                self._throttle()
                resp = self._session.get(url, timeout=_IMAGE_FETCH_TIMEOUT)
                self._last_request = time.monotonic()
                resp.raise_for_status()
                dest.write_bytes(resp.content)
            except Exception as exc:
                failures += 1
                print(f"  [art_index] skip {sid}: {exc}")
                continue
            fetched += 1
            if fetched % 500 == 0:
                elapsed = time.monotonic() - started
                rate = fetched / elapsed if elapsed > 0 else 0.0
                eta_min = (len(todo) - fetched) / rate / 60 if rate > 0 else 0.0
                print(
                    f"  [art_index] prefetched {fetched}/{len(todo)}"
                    f"  ({rate:.1f}/s, ETA {eta_min:.0f} min)"
                )
        print(
            f"  [art_index] prefetch done: {fetched} fetched, {failures} failures, "
            f"cache now {sum(1 for _ in cache_dir.glob('*.jpg'))} images"
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

    p_pref = sub.add_parser(
        "prefetch-printings",
        help="predownload every paper printing's ranking image (~2 GB, resumable)",
    )
    p_pref.add_argument("--limit", type=int, default=None,
                        help="stop after N newly fetched images")
    p_pref.add_argument("--force", action="store_true",
                        help="re-download the bulk JSON even if current")

    sub.add_parser("status", help="show index row count and bulk revision")

    p_query = sub.add_parser("query", help="identify a card photo (debug/threshold tuning)")
    p_query.add_argument("image", help="path to a photo of a card")
    p_query.add_argument("--top", type=int, default=5)

    args = parser.parse_args()

    if args.cmd == "build":
        ArtIndexBuilder().build(limit=args.limit, force=args.force)
    elif args.cmd == "prefetch-printings":
        ArtIndexBuilder().prefetch_printings(limit=args.limit, force=args.force)
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
