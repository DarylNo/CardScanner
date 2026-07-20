"""Orchestrates capture → vision → Scryfall → output for one card at a time."""

import dataclasses
from datetime import datetime
from typing import Optional, Union

import numpy as np

from mtg_card_scanner.vision import VisionModel, CardRead
from mtg_card_scanner.scryfall import ScryfallClient, ScryfallError, _normalize_lang
from mtg_card_scanner.output import ScanResult, OutputWriter, build_result, format_listing


def _artist_matches(read_artist: str, scryfall_artist: str) -> bool:
    """Case-insensitive substring match in either direction (handles partial reads)."""
    a = read_artist.lower().strip()
    b = scryfall_artist.lower().strip()
    return bool(a and b and (a in b or b in a))


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
        model: Optional[VisionModel],
        scryfall: ScryfallClient,
        writer: Optional[OutputWriter] = None,
    ) -> None:
        self.model = model
        self.scryfall = scryfall
        self.writer = writer
        self._cached_art_matcher = None

    @property
    def art_matcher(self):
        if self._cached_art_matcher is None:
            from mtg_card_scanner.visual_match import ArtMatcher
            self._cached_art_matcher = ArtMatcher()
        return self._cached_art_matcher

    def run_once(self, frames: Union[np.ndarray, list]) -> ScanResult:
        """
        Full pipeline: [consensus] → vision read → visual match → Scryfall → result.

        Accepts either a single frame (np.ndarray) or a list of frames for
        multi-frame consensus.  Single frame is equivalent to N=1 consensus.
        """
        if self.model is None:
            raise RuntimeError("No vision model configured. Use --demo to skip model.")

        # Normalise to list
        if isinstance(frames, np.ndarray):
            frames = [frames]

        ts = datetime.now().strftime("%H:%M:%S")
        print(f"\n{'='*60}")
        print(f"  SCAN  {ts}  ({len(frames)} frame{'s' if len(frames)!=1 else ''})")
        print(f"{'='*60}")

        # ── Step 1: read / consensus ──────────────────────────────────────────
        try:
            if len(frames) > 1:
                from mtg_card_scanner.consensus import consensus_read
                cr = consensus_read(frames, self.model)
                card_read = cr.card_read
                sharpest_frame = cr.sharpest_frame
                name_confidence = cr.name_confidence
                set_confidence = cr.set_confidence
                collector_confidence = cr.collector_confidence
                for i, r in enumerate(cr.reads):
                    print(f"  Frame {i+1}: {_safe(r.name)!r:28s}  "
                          f"set={_safe(r.set_code):6s}  #{_safe(r.collector_number):6s}  old={r.is_old_card}")
                print(f"  Consensus : {_safe(card_read.name)!r}  "
                      f"[conf={name_confidence}]  "
                      f"old={card_read.is_old_card}  "
                      f"artist={_safe(card_read.artist)!r}  "
                      f"set+# read: {_safe(card_read.set_code)!r} #{card_read.collector_number} "
                      f"[set conf={set_confidence} # conf={collector_confidence}]")
            else:
                # No burst to check agreement against — single-frame reads are
                # used by tests/demo/static-image flows, not the live camera
                # (which always bursts). Treated as confident since there's
                # nothing to disagree with, not because a single read is
                # inherently trustworthy.
                card_read = self.model.read_card(frames[0])
                sharpest_frame = frames[0]
                name_confidence = "high"
                set_confidence = "high"
                collector_confidence = "high"
                print(f"  Read      : {_safe(card_read.name)!r}  "
                      f"set={_safe(card_read.set_code)!r}  "
                      f"#{card_read.collector_number}  "
                      f"old={card_read.is_old_card}  "
                      f"artist={_safe(card_read.artist)!r}")
        except Exception as exc:
            print(f"  [pipeline] Vision model error: {_safe(str(exc))}")
            return build_result(
                CardRead(name="", set_code="", collector_number="",
                         foil=False, language="en",
                         condition_estimate="LP", condition_reason="Vision read failed."),
                {},
                name_confidence="low",
            )

        # Normalise language codes: model may return 'jp'; Scryfall uses 'ja', etc.
        canonical_lang = _normalize_lang(card_read.language)
        if canonical_lang != card_read.language:
            card_read = dataclasses.replace(card_read, language=canonical_lang)

        print(f"  Condition : {card_read.condition_estimate}  "
              f"foil={card_read.foil}  lang={card_read.language}")

        # ── Step 1.5: collector-number-first lookup (PRIMARY identification) ──
        # A confidently-read collector number + set code is ground truth from
        # the physical card — far more reliable than any visual heuristic, and
        # it also resolves List-vs-base for free: base-set cards have a plain
        # number (e.g. "66") and a direct set+collector lookup can only ever
        # land on that base set, never on 'plst' (a different set namespace
        # with prefixed numbers like "KLD-66"). Visual match becomes a FALLBACK,
        # only used when this doesn't yield a trusted result.
        #
        # The collector number is noisy: a single burst frame can misread a
        # digit, so it is NEVER trusted from one frame alone — only when the
        # burst frames AGREE (collector_confidence != "low", i.e. a majority).
        # Even then, the returned card's NAME must match the consensus name —
        # if it doesn't, the number was wrong (e.g. a digit misread pointing
        # at a different card in the same set) and we reject it outright and
        # fall back to the visual/art path, never to a coincidental Tier-2/3
        # name search that could mask the bad digit.
        scryfall_card = None
        match_method = "scryfall"
        phash_distance = None
        ranked: list = []
        printing_uncertain = False
        printing_candidates = ""

        is_non_english = _normalize_lang(card_read.language) not in ("", "en")

        def _name_matches_read(candidate_name: str) -> bool:
            # A localized name will never equal the English oracle name — skip
            # the check for non-English reads (mirrors ScryfallClient.lookup()).
            if not card_read.name or is_non_english:
                return True
            return candidate_name.strip().lower() == card_read.name.strip().lower()

        collector_number_trustworthy = (
            not card_read.is_old_card
            and bool(card_read.set_code)
            and bool(card_read.collector_number)
            and collector_confidence != "low"
        )
        set_and_name_trustworthy = (
            not card_read.is_old_card
            and bool(card_read.set_code)
            and bool(card_read.name)
            and set_confidence != "low"
            and name_confidence != "low"
        )

        if collector_number_trustworthy:
            try:
                candidate = self.scryfall.lookup_by_set_collector(
                    card_read.set_code, card_read.collector_number, card_read.language
                )
                if _name_matches_read(candidate.get("name", "")):
                    scryfall_card = candidate
                    match_method = "collector_number"
                    print(
                        f"  [pipeline] Collector-number-first hit: "
                        f"{_safe(candidate.get('set','').upper())} "
                        f"#{_safe(str(candidate.get('collector_number','')))} "
                        f"'{_safe(candidate.get('name',''))}' "
                        f"[# conf={collector_confidence}] — trusted, skipping visual match"
                    )
                else:
                    print(
                        f"  [pipeline] Collector-number lookup name mismatch "
                        f"(got {_safe(candidate.get('name',''))!r}, "
                        f"read {_safe(card_read.name)!r}) — number was wrong, "
                        f"rejecting and falling back to visual match"
                    )
            except ScryfallError as exc:
                print(
                    f"  [pipeline] Collector-number-first lookup failed "
                    f"({_safe(str(exc))}) — falling back to visual match"
                )
        elif set_and_name_trustworthy:
            # Number is missing/shaky (frames disagreed) but the set + name
            # ARE confidently agreed — resolve by exact name search within
            # that set rather than trusting a possibly-bad digit.
            try:
                candidate = self.scryfall.lookup_by_set_name(
                    card_read.set_code, card_read.name
                )
                scryfall_card = candidate
                match_method = "name_in_set"
                print(
                    f"  [pipeline] Name-within-set hit (shaky/missing number, "
                    f"confident name+set): {_safe(candidate.get('set','').upper())} "
                    f"#{_safe(str(candidate.get('collector_number','')))} "
                    f"'{_safe(candidate.get('name',''))}' — trusted, skipping visual match"
                )
            except ScryfallError as exc:
                print(
                    f"  [pipeline] Name-within-set lookup failed "
                    f"({_safe(str(exc))}) — falling back to visual match"
                )

        # ── Step 2: visual match (FALLBACK — only when Step 1.5 found nothing) ─
        if scryfall_card is None and card_read.name:
            try:
                printings = self.scryfall.get_all_printings(card_read.name)
                n_printings = len(printings)
                if printings:
                    best, ranked, is_near_tie = self.art_matcher.best_match(
                        sharpest_frame, printings,
                        vision_set_code=card_read.set_code,
                        vision_collector_number=card_read.collector_number,
                    )
                    if best:
                        scryfall_card  = best
                        phash_distance = best.get("phash_distance")
                        printing_uncertain = getattr(
                            self.art_matcher, "_last_scan_printing_uncertain", False
                        )
                        top_cands = getattr(
                            self.art_matcher, "_last_scan_top_candidates", []
                        )
                        printing_candidates = ", ".join(top_cands)
                        match_method = "phash"
                        # Print pHash candidate table (top 5)
                        border_detected = getattr(
                            self.art_matcher, "_last_scan_border_color", "unknown"
                        )
                        corner_decision = getattr(
                            self.art_matcher, "_last_list_corner_decision", "n/a"
                        )
                        corner_dists = getattr(
                            self.art_matcher, "_last_list_corner_distances", {}
                        )
                        corner_str = f"list-corner={corner_decision}{corner_dists or ''}"
                        print(f"\n  pHash vs {n_printings} printings"
                              f"  (scan border={border_detected}  {corner_str}):")
                        print(f"  {'#':<4} {'Set':<7} {'Num':>5}  {'Dist':>4}  "
                              f"{'Frame':<6}  {'Bdr':<6}  Artist")
                        print(f"  {'-'*4} {'-'*7} {'-'*5}  {'-'*4}  {'-'*6}  {'-'*5}  {'-'*22}")
                        for i, p in enumerate(ranked[:5]):
                            dist = p.get("phash_distance", "?")
                            is_best = (p is best)
                            tie_tag  = " *TIE*" if i == 1 and is_near_tie else ""
                            best_tag = " <best>" if is_best else ""
                            bdr = _safe(p.get("border_color", "?"))[:5]
                            print(f"  [{i+1}]  {_safe(p.get('set','').upper()):<7} "
                                  f"#{_safe(p.get('collector_number','')):>4}  "
                                  f"{dist:>4}  "
                                  f"{_safe(p.get('frame','')):>6}  "
                                  f"{bdr:<5}  "
                                  f"{_safe(p.get('artist',''))}"
                                  f"{best_tag}{tie_tag}")
                        if is_near_tie:
                            gap = ranked[1]["phash_distance"] - phash_distance if len(ranked) >= 2 else 0
                            print(f"  *** NEAR-TIE (gap={gap}) — low visual confidence ***")
            except Exception as exc:
                print(f"  [visual_match] Skipped ({_safe(str(exc))}) — falling back to Scryfall")

        # ── Step 3: Scryfall 3-tier fallback ─────────────────────────────────
        if scryfall_card is None:
            print("\n  [fallback] Scryfall 3-tier lookup...")
            try:
                if card_read.is_old_card:
                    scryfall_card = self.scryfall.lookup_old_card(
                        card_read.name, card_read.artist
                    )
                else:
                    scryfall_card = self.scryfall.lookup(
                        card_read.set_code, card_read.collector_number, card_read.name,
                        language=card_read.language,
                    )
                    if (
                        card_read.artist
                        and scryfall_card.get("set", "").lower() != card_read.set_code.lower()
                        and not _artist_matches(
                            card_read.artist, scryfall_card.get("artist", "")
                        )
                    ):
                        print(f"  [pipeline] Set/artist mismatch — retrying as old-frame card "
                              f"(read={_safe(card_read.artist)!r}, "
                              f"scryfall={_safe(scryfall_card.get('artist',''))!r})")
                        try:
                            scryfall_card = self.scryfall.lookup_old_card(
                                card_read.name, card_read.artist
                            )
                        except ScryfallError as exc:
                            print(f"  [pipeline] Old-frame fallback also failed: {exc}")
            except ScryfallError as exc:
                print(f"  [pipeline] Scryfall lookup failed: {_safe(str(exc))}")
                scryfall_card = {}  # return partial result with what vision read

        result = build_result(
            card_read, scryfall_card,
            match_method=match_method,
            phash_distance=phash_distance,
            name_confidence=name_confidence,
            printing_uncertain=printing_uncertain,
            printing_candidates=printing_candidates,
        )

        if self.writer:
            self.writer.append(result)

        return result

    def scan_candidates(
        self,
        frames: Union[np.ndarray, list],
        top_n: int = 12,
    ) -> dict:
        """
        Identify a card and return ART-RANKED printing CANDIDATES for the user to
        choose from — the web-app flow's core call.

        Unlike ``run_once`` (which auto-resolves to a single printing), this reads
        the card, fetches every printing of that name, ranks them by perceptual-hash
        art distance, and returns the ranked list so the desktop UI can present
        candidates for the user to pick the exact printing.

        Returns a JSON-serialisable dict:
            {
              "identified": bool,          # a card name was read
              "card_read": {name,set_code,collector_number,foil,language,
                            condition_estimate,condition_reason,artist,is_old_card},
              "confidence": {name,set,collector},
              "candidates": [ {id,name,set,set_name,collector_number,rarity,
                               released_at,border_color,frame,promo,finishes,
                               image_small,image_normal,phash_distance,
                               multi_distance}... ],
              "error": str | None,
            }
        """
        if self.model is None:
            raise RuntimeError("No vision model configured.")

        if isinstance(frames, np.ndarray):
            frames = [frames]

        # ── read / consensus ──────────────────────────────────────────────────
        try:
            if len(frames) > 1:
                from mtg_card_scanner.consensus import consensus_read
                cr = consensus_read(frames, self.model)
                card_read = cr.card_read
                sharpest_frame = cr.sharpest_frame
                confidence = {
                    "name": cr.name_confidence,
                    "set": cr.set_confidence,
                    "collector": cr.collector_confidence,
                }
            else:
                card_read = self.model.read_card(frames[0])
                sharpest_frame = frames[0]
                confidence = {"name": "high", "set": "high", "collector": "high"}
        except Exception as exc:
            return {
                "identified": False,
                "card_read": {},
                "confidence": {"name": "low", "set": "low", "collector": "low"},
                "candidates": [],
                "error": f"Vision read failed: {_safe(str(exc))}",
            }

        canonical_lang = _normalize_lang(card_read.language)
        if canonical_lang != card_read.language:
            card_read = dataclasses.replace(card_read, language=canonical_lang)

        read_dict = {
            "name": card_read.name,
            "set_code": card_read.set_code,
            "collector_number": card_read.collector_number,
            "foil": card_read.foil,
            "language": card_read.language,
            "condition_estimate": card_read.condition_estimate,
            "condition_reason": card_read.condition_reason,
            "artist": card_read.artist,
            "is_old_card": card_read.is_old_card,
        }

        if not card_read.name:
            return {
                "identified": False,
                "card_read": read_dict,
                "confidence": confidence,
                "candidates": [],
                "error": "No card name could be read.",
            }

        # ── fetch printings + rank by art ─────────────────────────────────────
        try:
            printings = self.scryfall.get_all_printings(card_read.name)
        except ScryfallError as exc:
            return {
                "identified": True,
                "card_read": read_dict,
                "confidence": confidence,
                "candidates": [],
                "error": f"No printings found for '{_safe(card_read.name)}': {_safe(str(exc))}",
            }

        ranked = self.art_matcher.rank_printings(sharpest_frame, printings)
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
        newest first) for when the vision read misidentified the card.
        """
        printings = self.scryfall.get_all_printings(name)
        printings = sorted(
            printings, key=lambda p: p.get("released_at", ""), reverse=True
        )
        return [_candidate_dict(p) for p in printings[:top_n]]

    def run_demo(self, sample_read: Optional[CardRead] = None) -> ScanResult:
        """
        Demo mode: skip camera + vision model, use a canned CardRead, and run
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
