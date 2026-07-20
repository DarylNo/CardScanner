"""Orchestrates identification → Scryfall → art ranking for one card at a time.

Identification is LLM-free: the global art-hash index (art_index.py) matches
the scanned card's art against every known Magic artwork, giving a card NAME;
Scryfall then returns every printing of that name and ArtMatcher ranks them by
per-printing art distance for the user to pick the exact printing in the UI.
"""

from typing import Any, Optional, Union

import numpy as np

from mtg_card_scanner.art_index import (
    ArtIndex,
    ArtIndexError,
    _HIGH_CONFIDENCE_DISTANCE,
    _MAX_CONFIDENT_DISTANCE,
)
from mtg_card_scanner.card_read import CardRead
from mtg_card_scanner.card_detect import frame_sharpness
from mtg_card_scanner.scryfall import ScryfallClient, ScryfallError
from mtg_card_scanner.output import ScanResult, OutputWriter, build_result, format_listing


def _safe(s: str) -> str:
    return str(s).encode("ascii", errors="replace").decode("ascii")


def _candidate_dict(p: dict) -> dict:
    """Project a Scryfall printing (+pHash fields) to a UI-friendly candidate."""
    images = p.get("image_uris") or {}
    return {
        "id": p.get("id", ""),
        "name": p.get("name", ""),
        "set": p.get("set", ""),
        "set_name": p.get("set_name", ""),
        "collector_number": p.get("collector_number", ""),
        "rarity": p.get("rarity", ""),
        "released_at": p.get("released_at", ""),
        "border_color": p.get("border_color", ""),
        "frame": p.get("frame", ""),
        "promo": bool(p.get("promo", False)),
        "finishes": p.get("finishes", []),  # e.g. ["nonfoil","foil","etched"]
        "image_small": images.get("small", ""),
        "image_normal": images.get("normal", "") or images.get("large", ""),
        "phash_distance": p.get("phash_distance"),
        "multi_distance": p.get("multi_distance"),
    }


def _confidence_for(distance: int) -> str:
    if distance <= _HIGH_CONFIDENCE_DISTANCE:
        return "high"
    if distance <= _MAX_CONFIDENT_DISTANCE:
        return "medium"
    return "low"


_DEMO_CARD = CardRead(
    name="Lightning Bolt",
    set_code="m10",
    collector_number="146",
    foil=False,
    language="en",
    condition_estimate="LP",
    condition_reason="Minor edge wear on two corners.",
)


class Pipeline:
    def __init__(
        self,
        index: Optional[ArtIndex],
        scryfall: ScryfallClient,
        writer: Optional[OutputWriter] = None,
    ) -> None:
        self.index = index
        self.scryfall = scryfall
        self.writer = writer
        self._cached_art_matcher = None

    @property
    def art_matcher(self):
        if self._cached_art_matcher is None:
            from mtg_card_scanner.visual_match import ArtMatcher
            self._cached_art_matcher = ArtMatcher()
        return self._cached_art_matcher

    # ── identification ────────────────────────────────────────────────────────

    def _identify(self, frames: list[np.ndarray]) -> list[dict[str, Any]]:
        """
        Identify via the art index, sharpest frame first.  If the best frame's
        match isn't confident, the remaining frames are tried too (they are
        already in hand — retries cost milliseconds and no network) and the
        best overall result set wins.
        """
        assert self.index is not None
        matches = self.index.identify(frames[0], top_n=5)
        for frame in frames[1:]:
            if matches and matches[0]["distance"] <= _MAX_CONFIDENT_DISTANCE:
                break
            retry = self.index.identify(frame, top_n=5)
            if retry and (not matches or retry[0]["distance"] < matches[0]["distance"]):
                matches = retry
        return matches

    # ── web-app flow ──────────────────────────────────────────────────────────

    def scan_candidates(
        self,
        frames: Union[np.ndarray, list],
        top_n: int = 12,
    ) -> dict:
        """
        Identify a card and return ART-RANKED printing CANDIDATES for the user
        to choose from — the web-app flow's core call.

        Identification is art-only (see _identify).  On a confident name match
        every printing of that name is fetched and ranked by per-printing art
        distance; on a non-confident match `identified` is False and `error`
        lists the nearest guesses so the desktop UI's manual search takes over.

        Returns a JSON-serialisable dict:
            {
              "identified": bool,
              "card_read": {name,set_code,collector_number,foil,language,
                            condition_estimate,condition_reason,artist,alternates},
              "confidence": {name,set,collector},   # from art distance
              "candidates": [ {id,name,set,set_name,collector_number,rarity,
                               released_at,border_color,frame,promo,finishes,
                               image_small,image_normal,phash_distance,
                               multi_distance}... ],
              "error": str | None,
            }
        """
        if isinstance(frames, np.ndarray):
            frames = [frames]
        frames = sorted(frames, key=frame_sharpness, reverse=True)

        low_conf = {"name": "low", "set": "low", "collector": "low"}
        if self.index is None:
            return {
                "identified": False, "card_read": {}, "confidence": low_conf,
                "candidates": [], "error": "No art index configured.",
            }

        try:
            matches = self._identify(frames)
        except ArtIndexError as exc:
            return {
                "identified": False, "card_read": {}, "confidence": low_conf,
                "candidates": [], "error": _safe(str(exc)),
            }
        except Exception as exc:
            return {
                "identified": False, "card_read": {}, "confidence": low_conf,
                "candidates": [],
                "error": f"Art identification failed: {_safe(str(exc))}",
            }

        if not matches:
            return {
                "identified": False, "card_read": {}, "confidence": low_conf,
                "candidates": [], "error": "Art index returned no matches.",
            }

        best = matches[0]
        distance = best["distance"]
        conf = _confidence_for(distance)
        confidence = {"name": conf, "set": conf, "collector": conf}
        guesses = "  ".join(
            f"'{_safe(m['name'])}' d={m['distance']}" for m in matches[:3]
        )
        print(f"  [pipeline] art-index: {guesses}")

        read_dict = {
            "name": best["name"],
            "set_code": best["set"],
            "collector_number": best["collector_number"],
            "foil": False,                      # user sets finish in the UI
            "language": "en",
            "condition_estimate": "NM",         # UI default — user confirms
            "condition_reason": "not assessed (no vision model)",
            "artist": best["artist"],
            "alternates": [
                {"name": m["name"], "distance": m["distance"]}
                for m in matches[1:3]
            ],
        }

        if distance > _MAX_CONFIDENT_DISTANCE:
            return {
                "identified": False,
                "card_read": read_dict,
                "confidence": confidence,
                "candidates": [],
                "error": f"No confident art match (best: {guesses}). Use manual search.",
            }

        # ── fetch printings + rank by art ─────────────────────────────────────
        try:
            printings = self.scryfall.get_all_printings(best["name"])
        except ScryfallError as exc:
            return {
                "identified": True,
                "card_read": read_dict,
                "confidence": confidence,
                "candidates": [],
                "error": f"No printings found for '{_safe(best['name'])}': {_safe(str(exc))}",
            }

        ranked = self.art_matcher.rank_printings(frames[0], printings)
        candidates = [_candidate_dict(p) for p in ranked[:top_n]]

        return {
            "identified": True,
            "card_read": read_dict,
            "confidence": confidence,
            "candidates": candidates,
            "error": None,
        }

    def search_candidates(self, name: str, top_n: int = 40) -> list[dict]:
        """
        Manual re-identification: return all printings of *name* (no art ranking,
        newest first) for when the art index misidentified the card.
        """
        printings = self.scryfall.get_all_printings(name)
        printings = sorted(
            printings, key=lambda p: p.get("released_at", ""), reverse=True
        )
        return [_candidate_dict(p) for p in printings[:top_n]]

    # ── demo ──────────────────────────────────────────────────────────────────

    def run_demo(self, sample_read: Optional[CardRead] = None) -> ScanResult:
        """
        Demo mode: skip camera + identification, use a canned CardRead, and run
        the Scryfall lookup + output stages to verify that part of the pipeline.
        """
        card_read = sample_read or _DEMO_CARD

        print(
            f"[DEMO] Sample card read: {card_read.name!r} "
            f"[{card_read.set_code.upper()} #{card_read.collector_number}]"
        )
        print("Looking up on Scryfall...")

        scryfall_card = self.scryfall.lookup(
            card_read.set_code, card_read.collector_number, card_read.name
        )

        result = build_result(card_read, scryfall_card)

        if self.writer:
            self.writer.append(result)

        return result
