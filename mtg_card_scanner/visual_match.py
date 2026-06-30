"""
Visual printing disambiguation using perceptual hashing of multiple card regions.

For every known printing of a card, three image regions are pHash'd and compared
against the scanned card image.  The printing with the lowest weighted Hamming
distance is returned as the best match.

Regions (as fractions of the warped card):
  art     (primary)   Y: 9–47%, X:  3–97%
  title   (secondary) Y:  0– 8%, X:  3–97%  — name bar colour, mana cost frame
  textbox (secondary) Y: 54–86%, X:  5–95%  — rules/flavour text background

Combined distance for non-basics:  art×4 + title×1 + textbox×1
For basic lands title/textbox are noise (empty or art): art×6 only.

Near-tie tiebreaker (applied in order when art-pHash gap ≤ NEAR_TIE_DISTANCE):
  -1. Literal vision read: an exact (set, collector_number) match to what the
      vision model read off the card wins outright (see pipeline.py — in
      practice the pipeline now resolves confidently-read cards via a direct
      Scryfall collector-number lookup BEFORE visual_match ever runs, so this
      tiebreaker mainly matters for cards that reach visual_match directly).
   0. Multi-region sort: rank tie candidates by combined distance.
   1. Border colour: prefer the candidate whose Scryfall border_color matches the
      detected card border (black vs white vs unknown).
   2. List-corner pHash (black-bordered ties only): The List (PLST/MB1/CMB1)
      replaces a card's normal expansion symbol with a small planeswalker
      symbol in the bottom-left corner — a base-set printing has its ordinary
      expansion symbol there instead. We pHash that exact corner region on the
      scan and compare it against the SAME corner region of the List candidate
      image(s) vs the base candidate image(s); whichever the scan's corner is
      clearly closer to wins. A bare "something is printed in that corner"
      heuristic is useless (every card has *some* symbol there) — only a clear
      margin in favour of the List candidates' corner promotes to the List;
      otherwise we default to the base-set printing.
   3. Promo preference: prefer non-promo printings over promos.

Basic lands receive a "printing_uncertain" flag when in a near-tie, surfacing
the top candidate sets so the user can choose; they are never confidently mislabelled.
"""

import re
import time
from pathlib import Path
from typing import Any, Optional

import cv2
import numpy as np
import requests

# ── region tunables ───────────────────────────────────────────────────────────
# Art region — excludes title bar (top ~8%) and type/text box (below ~47%).
_ART_Y0, _ART_Y1 = 0.09, 0.47
_ART_X0, _ART_X1 = 0.03, 0.97

# Title bar — card name, mana cost, frame colour strip.
_TITLE_Y0, _TITLE_Y1 = 0.00, 0.085
_TITLE_X0, _TITLE_X1 = 0.03, 0.97

# Text box — rules / flavour text; frame background colour differs by era.
_TEXTBOX_Y0, _TEXTBOX_Y1 = 0.54, 0.86
_TEXTBOX_X0, _TEXTBOX_X1 = 0.05, 0.95

# Weights for combined distance.  Art is dominant; title+textbox add secondary
# signal for same-art reprints that differ in frame era or copyright background.
_WEIGHT_ART     = 4
_WEIGHT_TITLE   = 1
_WEIGHT_TEXTBOX = 1
_WEIGHT_TOTAL   = _WEIGHT_ART + _WEIGHT_TITLE + _WEIGHT_TEXTBOX  # 6

# Hamming-distance gap between #1 and #2 candidates below which we call a tie.
NEAR_TIE_DISTANCE = 8

# Luminance thresholds for border-colour detection (on a perspective-corrected card).
_BORDER_BLACK_THRESHOLD = 60   # mean below this → black border
_BORDER_WHITE_THRESHOLD = 160  # mean above this → white border

_DEFAULT_CACHE_DIR = Path.home() / ".cache" / "mtg-card-scanner" / "card_images"
_USER_AGENT = "MTGCardScanner/1.0 visual-match (contact: your-email@example.com)"
_REQUEST_DELAY = 0.12   # seconds — stays under Scryfall CDN ~10 req/s limit
_IMAGE_FETCH_TIMEOUT = 8   # seconds — fail fast on a stalled download rather than hang the scan

# Hard cap on how many printings rank_printings() will download/hash per scan.
# Heavily-reprinted staples (Shivan Dragon, Lightning Bolt, ...) can have 50+
# printings; downloading and hashing every single one turns one scan into a
# multi-minute network crawl that feels like the scanner is permanently stuck.
# Now that pipeline.py resolves confidently-read cards via direct collector-
# number lookup BEFORE this ever runs, this fallback only needs to find a
# reasonable match, not exhaustively search every printing ever made — so we
# keep the oldest half (best for vintage/old-card identification) and newest
# half (best for recently-acquired modern cards) and drop the middle.
_MAX_CANDIDATES_PER_SCAN = 40


def _cap_candidates(printings: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if len(printings) <= _MAX_CANDIDATES_PER_SCAN:
        return printings
    half = _MAX_CANDIDATES_PER_SCAN // 2
    print(
        f"  [visual_match] {len(printings)} printings — capping to "
        f"{_MAX_CANDIDATES_PER_SCAN} (oldest {half} + newest {half}) to bound scan time"
    )
    return printings[:half] + printings[-half:]


def _require_deps() -> None:
    try:
        import imagehash  # noqa: F401
        from PIL import Image  # noqa: F401
    except ImportError as exc:
        raise ImportError(
            "visual_match requires 'imagehash' and 'Pillow'. "
            "Install with: pip install imagehash Pillow"
        ) from exc


# ── region crop helpers ───────────────────────────────────────────────────────

def _crop_region(img: Any, y0: float, y1: float, x0: float, x1: float) -> Any:
    """Crop *img* to the given fractional rectangle, returning a PIL RGB Image."""
    from PIL import Image as PILImage
    if isinstance(img, np.ndarray):
        pil = PILImage.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    else:
        pil = img.convert("RGB")
    w, h = pil.size
    return pil.crop((int(w * x0), int(h * y0), int(w * x1), int(h * y1)))


def crop_art_region(img: Any) -> Any:
    """Crop the art region from *img* (numpy BGR or PIL). Returns PIL Image."""
    return _crop_region(img, _ART_Y0, _ART_Y1, _ART_X0, _ART_X1)


def crop_title_region(img: Any) -> Any:
    """Crop the title bar (name + mana cost) from *img*. Returns PIL Image."""
    return _crop_region(img, _TITLE_Y0, _TITLE_Y1, _TITLE_X0, _TITLE_X1)


def crop_textbox_region(img: Any) -> Any:
    """Crop the text-box region (rules/flavour text) from *img*. Returns PIL Image."""
    return _crop_region(img, _TEXTBOX_Y0, _TEXTBOX_Y1, _TEXTBOX_X0, _TEXTBOX_X1)


def compute_phash(img: Any) -> Any:
    """Compute pHash of the art-crop region.  img may be numpy BGR or PIL."""
    _require_deps()
    import imagehash
    return imagehash.phash(crop_art_region(img))


def compute_multi_region_distance(
    scan_img: Any,
    cand_img: Any,
    is_basic: bool = False,
) -> int:
    """
    Weighted sum of per-region pHash Hamming distances.

    For non-basics: art×4 + title×1 + textbox×1
    For basics:     art×6  (title/textbox carry no distinguishing signal)

    Both *scan_img* and *cand_img* may be numpy BGR arrays or PIL Images.
    """
    _require_deps()
    import imagehash
    art_d = int(
        imagehash.phash(crop_art_region(scan_img)) -
        imagehash.phash(crop_art_region(cand_img))
    )
    if is_basic:
        return art_d * _WEIGHT_TOTAL
    title_d = int(
        imagehash.phash(crop_title_region(scan_img)) -
        imagehash.phash(crop_title_region(cand_img))
    )
    textbox_d = int(
        imagehash.phash(crop_textbox_region(scan_img)) -
        imagehash.phash(crop_textbox_region(cand_img))
    )
    return art_d * _WEIGHT_ART + title_d * _WEIGHT_TITLE + textbox_d * _WEIGHT_TEXTBOX


# ── List-corner detection (replaces old bright-blob "stamp" heuristic) ────────

# Scryfall set codes whose expansion-symbol slot is replaced by a planeswalker
# symbol (The List / Mystery Booster retro-frame reprints).
_STAMP_SETS: frozenset[str] = frozenset({"plst", "mb1", "cmb1"})

# Bottom-LEFT corner where the expansion symbol (or, for List printings, the
# planeswalker symbol that replaces it) sits.  NOTE: presence of *something*
# there is meaningless — every printing has a symbol in this slot.  What
# distinguishes List from base is the symbol's SHAPE, which we detect by
# pHash-comparing this exact corner crop against each candidate's own image
# (see ArtMatcher._list_corner_decision), never by a brightness/presence test.
_LIST_CORNER_Y0, _LIST_CORNER_Y1 = 0.84, 0.99
_LIST_CORNER_X0, _LIST_CORNER_X1 = 0.00, 0.20

# Minimum Hamming-distance advantage the List-candidates' corner match must
# have over the base-candidates' corner match before we promote to the List.
# Ties / weak margins default to the base-set printing.
_LIST_CORNER_MARGIN = 6


def crop_list_corner_region(img: Any) -> Any:
    """Crop the bottom-left expansion/planeswalker-symbol slot. Returns PIL Image."""
    return _crop_region(img, _LIST_CORNER_Y0, _LIST_CORNER_Y1, _LIST_CORNER_X0, _LIST_CORNER_X1)


# ── border-colour detection ───────────────────────────────────────────────────

def _detect_border_color(card_img: np.ndarray) -> str:
    """Classify card border as 'black', 'white', or 'unknown'."""
    h, w = card_img.shape[:2]
    if h < 20 or w < 20:
        return "unknown"
    y0, y1 = h // 4, 3 * h // 4
    border_w = max(8, w // 55)
    left  = card_img[y0:y1, :border_w]
    right = card_img[y0:y1, w - border_w:]
    samples = np.concatenate([left, right], axis=1)
    gray = cv2.cvtColor(samples, cv2.COLOR_BGR2GRAY) if samples.ndim == 3 else samples
    mean_lum = float(gray.mean())
    if mean_lum < _BORDER_BLACK_THRESHOLD:
        return "black"
    if mean_lum > _BORDER_WHITE_THRESHOLD:
        return "white"
    return "unknown"


# ── promo detection ───────────────────────────────────────────────────────────

def _is_promo(p: dict[str, Any]) -> bool:
    """Return True if this Scryfall printing is a promotional variant."""
    return (
        bool(p.get("promo"))
        or p.get("set_type", "") in ("promo", "promo_pack")
    )


# ── basic land detection ──────────────────────────────────────────────────────

_BASIC_LAND_NAMES: frozenset[str] = frozenset(
    {"plains", "island", "swamp", "mountain", "forest", "wastes"}
)


def _is_basic_land_printing(p: dict[str, Any]) -> bool:
    return "Basic Land" in p.get("type_line", "")


class ArtMatcher:
    """
    Downloads and caches Scryfall card images, then ranks printings by the
    weighted multi-region perceptual-hash distance vs the scanned card.
    """

    def __init__(
        self,
        cache_dir: Path = _DEFAULT_CACHE_DIR,
        request_delay: float = _REQUEST_DELAY,
    ) -> None:
        _require_deps()
        self.cache_dir = Path(cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self._session = requests.Session()
        self._session.headers["User-Agent"] = _USER_AGENT
        self._last_request: float = 0.0
        self._delay = request_delay
        # Populated by rank_printings / best_match for the pipeline to inspect.
        self._last_scan_border_color: str = "unknown"
        self._last_scan_is_basic: bool = False
        self._last_scan_printing_uncertain: bool = False
        self._last_scan_top_candidates: list[str] = []
        self._last_list_corner_decision: str = "n/a"       # "list" | "base" | "n/a" (no List candidate in tie)
        self._last_list_corner_distances: dict[str, int] = {}  # diagnostic: {"list": d, "base": d}

    # ── internals ─────────────────────────────────────────────────────────────

    def _warp_frame(self, frame: np.ndarray) -> np.ndarray:
        try:
            from mtg_card_scanner.card_detect import extract_card
            warped, detected = extract_card(frame)
            if detected:
                return warped
        except Exception:
            pass
        return frame

    def _throttle(self) -> None:
        elapsed = time.monotonic() - self._last_request
        if elapsed < self._delay:
            time.sleep(self._delay - elapsed)

    def _cache_path(self, scryfall_id: str) -> Path:
        return self.cache_dir / f"{scryfall_id}.jpg"

    def _fetch_image(self, url: str, scryfall_id: str) -> Any:
        """Return a PIL Image — served from disk cache when available."""
        from PIL import Image as PILImage
        cached = self._cache_path(scryfall_id)
        if cached.exists():
            return PILImage.open(cached).convert("RGB")
        self._throttle()
        resp = self._session.get(url, timeout=_IMAGE_FETCH_TIMEOUT)
        resp.raise_for_status()
        self._last_request = time.monotonic()
        cached.write_bytes(resp.content)
        return PILImage.open(cached).convert("RGB")

    # ── public ────────────────────────────────────────────────────────────────

    def rank_printings(
        self,
        card_img: np.ndarray,
        printings: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        """
        Download (or cache) each printing's image and compute the pHash distances.

        For non-basics: stores art pHash in ``phash_distance`` and the weighted
        combined distance (art×4 + title×1 + textbox×1) in ``multi_distance``.
        For basic lands: both fields equal the art pHash distance (×1 or ×6).
        Also stores ``corner_distance`` — the bottom-left expansion/planeswalker
        symbol-slot pHash distance — used by best_match() to decide List vs base.

        Returns printings sorted by art pHash distance (lowest first).
        """
        import imagehash
        from PIL import Image as PILImage

        printings = _cap_candidates(printings)

        warped = self._warp_frame(card_img)
        self._last_scan_border_color = _detect_border_color(warped)
        self._last_scan_printing_uncertain = False
        self._last_scan_top_candidates     = []
        self._last_list_corner_decision    = "n/a"
        self._last_list_corner_distances   = {}

        is_basic = any(_is_basic_land_printing(p) for p in printings)
        self._last_scan_is_basic = is_basic

        # Convert scan to PIL once, then compute region hashes.
        scan_pil = PILImage.fromarray(cv2.cvtColor(warped, cv2.COLOR_BGR2RGB))
        scan_art_hash = imagehash.phash(crop_art_region(scan_pil))
        scan_corner_hash = imagehash.phash(crop_list_corner_region(scan_pil))
        if not is_basic:
            scan_title_hash   = imagehash.phash(crop_title_region(scan_pil))
            scan_textbox_hash = imagehash.phash(crop_textbox_region(scan_pil))

        results: list[dict[str, Any]] = []
        for p in printings:
            image_uris = p.get("image_uris") or {}
            url = image_uris.get("normal") or image_uris.get("large")
            sid = p.get("id", "")
            if not url or not sid:
                continue
            try:
                cand_pil = self._fetch_image(url, sid)
                cand_art_hash = imagehash.phash(crop_art_region(cand_pil))
                art_d = int(scan_art_hash - cand_art_hash)
                corner_d = int(scan_corner_hash - imagehash.phash(crop_list_corner_region(cand_pil)))

                if is_basic:
                    multi_d = art_d  # title/textbox add noise for basics
                else:
                    title_d   = int(scan_title_hash   - imagehash.phash(crop_title_region(cand_pil)))
                    textbox_d = int(scan_textbox_hash - imagehash.phash(crop_textbox_region(cand_pil)))
                    multi_d   = (art_d   * _WEIGHT_ART
                                 + title_d   * _WEIGHT_TITLE
                                 + textbox_d * _WEIGHT_TEXTBOX)

                results.append({
                    **p,
                    "phash_distance": art_d,
                    "multi_distance": multi_d,
                    "corner_distance": corner_d,
                    "phash_hash": str(cand_art_hash),
                })
            except Exception as exc:
                print(f"  [visual_match] Skipping {sid}: {exc}")

        results.sort(key=lambda x: x["phash_distance"])
        return results

    @staticmethod
    def _find_literal_match(
        tie_candidates: list[dict[str, Any]],
        vision_set_code: str,
        vision_collector_number: str,
    ) -> Optional[dict[str, Any]]:
        """
        Return the tie candidate whose set + collector number exactly match the
        vision model's literal read of the card, or None if no clean read was
        given or none of the tie candidates match it.
        """
        if not vision_set_code or not vision_collector_number:
            return None
        vset = vision_set_code.strip().lower()
        vnum = vision_collector_number.strip().lstrip("0") or vision_collector_number.strip()
        for c in tie_candidates:
            cset = str(c.get("set", "")).strip().lower()
            raw_cnum = str(c.get("collector_number", "")).strip()
            cnum = raw_cnum.lstrip("0") or raw_cnum
            if cset == vset and cnum == vnum:
                return c
        return None

    @staticmethod
    def _list_corner_decision(
        border_matched: list[dict[str, Any]],
    ) -> tuple[list[dict[str, Any]], str, dict[str, int]]:
        """
        Decide List vs base-set using the bottom-left corner pHash distance
        (``corner_distance``, computed in rank_printings against the SAME
        corner crop of each candidate's own Scryfall image).

        Splits *border_matched* into List-set candidates (PLST/MB1/CMB1) vs
        base candidates.  Promotes to the List group only when its best
        corner_distance beats the base group's best corner_distance by at
        least ``_LIST_CORNER_MARGIN`` — a bare "is something printed there"
        signal is useless since every printing has a symbol in that slot; only
        a clear shape-match advantage counts.  Ties/no-clear-margin/missing
        group default to the base candidates.

        Returns ``(chosen_candidates, decision, distances)`` where decision is
        "list", "base", or "n/a" (no List candidate present in this tie group).
        """
        list_group = [c for c in border_matched if c.get("set", "").lower() in _STAMP_SETS]
        base_group = [c for c in border_matched if c.get("set", "").lower() not in _STAMP_SETS]

        if not list_group or not base_group:
            return border_matched, "n/a", {}

        list_best = min(c.get("corner_distance", 999) for c in list_group)
        base_best = min(c.get("corner_distance", 999) for c in base_group)
        distances = {"list": list_best, "base": base_best}

        if list_best <= base_best - _LIST_CORNER_MARGIN:
            return list_group, "list", distances
        return base_group, "base", distances

    def best_match(
        self,
        card_img: np.ndarray,
        printings: list[dict[str, Any]],
        vision_set_code: str = "",
        vision_collector_number: str = "",
    ) -> tuple[Optional[dict[str, Any]], list[dict[str, Any]], bool]:
        """
        Return ``(best_printing, ranked_list, is_near_tie)``.

        :param vision_set_code: The set code the vision model read directly off
            the card's collector line (``CardRead.set_code``).  Used as Level -1
            evidence: an exact match always wins, since visually-identical
            reprints (promos, precons, judge cards) cannot be told apart by any
            pixel heuristic.  In the normal pipeline this rarely fires, because
            a confidently-read collector number is now resolved via a direct
            Scryfall lookup BEFORE visual_match runs at all (see pipeline.py);
            this remains as a safety net for whatever does reach visual_match.
        :param vision_collector_number: The collector number read alongside
            ``vision_set_code``.

        When the art-pHash gap between top-1 and top-2 is ≤ NEAR_TIE_DISTANCE:
         -1. Exact (set, collector_number) match to the vision model's literal
             read wins outright (skips border/corner/promo entirely).
          0. Otherwise, sort tie candidates by multi-region distance (better frame/text discrimination).
          1. For basic lands: flag ``self._last_scan_printing_uncertain = True``.
          2. For non-basics: apply border-colour → List-corner-pHash → promo-preference filters.
        """
        ranked = self.rank_printings(card_img, printings)
        if not ranked:
            return None, [], False

        is_basic = getattr(self, "_last_scan_is_basic", False)

        near_tie = (
            len(ranked) >= 2
            and (ranked[1]["phash_distance"] - ranked[0]["phash_distance"]) <= NEAR_TIE_DISTANCE
        )

        best = ranked[0]

        if near_tie:
            tie_ceil      = ranked[0]["phash_distance"] + NEAR_TIE_DISTANCE
            tie_candidates = [c for c in ranked if c["phash_distance"] <= tie_ceil]

            if is_basic:
                # Best-effort art match; flag as uncertain for the caller.
                best = tie_candidates[0]
                top_sets = [p.get("set", "?").upper() for p in tie_candidates[:6]]
                self._last_scan_printing_uncertain = True
                self._last_scan_top_candidates     = top_sets
                print(
                    f"  [basic-land] near-tie across {len(tie_candidates)} printings — "
                    f"printing uncertain; top: {', '.join(top_sets[:5])}"
                )
            else:
                def _multi_key(c: dict[str, Any]) -> int:
                    return c.get("multi_distance", c["phash_distance"] * _WEIGHT_TOTAL)

                # ── Level -1: literal vision-read match ────────────────────────
                # When the vision model cleanly read the printed set code +
                # collector number off the card, that's ground truth from the
                # physical card itself — more reliable than any visual
                # heuristic, especially among printings that share identical
                # art (promos, precons, judge cards) where pHash/border/stamp
                # genuinely cannot tell them apart.
                literal_match = self._find_literal_match(
                    tie_candidates, vision_set_code, vision_collector_number
                )

                if literal_match is not None:
                    best = literal_match
                    if best is not ranked[0]:
                        print(
                            f"  [tiebreak] literal read "
                            f"'{vision_set_code.upper()} #{vision_collector_number}' "
                            f"-> {best.get('set','').upper()} #{best.get('collector_number','')}"
                        )
                else:
                    # ── Level 0: sort tie candidates by multi-region distance ──
                    tie_sorted = sorted(tie_candidates, key=_multi_key)

                    scan_border = self._last_scan_border_color

                    # ── Level 1: border-colour filter ────────────────────────
                    if scan_border in ("black", "white"):
                        border_matched = [
                            c for c in tie_sorted if c.get("border_color") == scan_border
                        ]
                    else:
                        border_matched = []

                    if border_matched:
                        # ── Level 2: List-corner pHash filter ────────────────
                        candidates, corner_decision, corner_dists = (
                            self._list_corner_decision(border_matched)
                        )
                        self._last_list_corner_decision  = corner_decision
                        self._last_list_corner_distances = corner_dists
                    else:
                        candidates = tie_sorted

                    # ── Level 3: promo preference ─────────────────────────────
                    non_promo = [c for c in candidates if not _is_promo(c)]
                    best = non_promo[0] if non_promo else candidates[0]

                    if best is not ranked[0]:
                        multi_gain = (
                            _multi_key(ranked[0]) - _multi_key(best)
                            if ranked[0] is not best else 0
                        )
                        corner_str = (
                            f" corner={self._last_list_corner_decision}"
                            f"{self._last_list_corner_distances or ''}"
                        )
                        gain_str  = f" (multi-dist diff={multi_gain})" if multi_gain > 0 else ""
                        print(
                            f"  [tiebreak] border={scan_border!r}{corner_str}"
                            f" -> {best.get('set','').upper()}"
                            f" #{best.get('collector_number','')}"
                            f"{gain_str}"
                        )

        return best, ranked, near_tie
