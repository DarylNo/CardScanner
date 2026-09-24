"""
Which of a scan's candidate printings share the artwork in front of the camera.

A name's printings usually fall into the scanned artwork and OTHER artworks
far behind it — Fling: four same-art printings at Δ78–84, the next (a
different artwork) at Δ146. A different artwork is never the card, so it is
neither shown by default in the picker nor priced for an unpicked scan (the
sweep would otherwise spend F2F rate budget on prints the card cannot be, and
their prices would widen the pending card's range and sway the price filter).

The split applies only on a CLEAN break: the best match must be a good one
(≤ the art-decisive ceiling) and two neighbouring distances must jump by
≥ ART_BREAK_GAP. Same-art neighbours sit ≤8 apart (the measured wrong-#1 gaps
behind pipeline._ART_DECISIVE_GAP), so 40 leaves wide headroom; a wide
same-art spread (Bone Splinters Δ124–190, alt art Δ208) has no such jump and
nothing is split off. The OCR-confirmed printing is never split off.

Computed at serve time from the stored multi_distance, so it applies to every
scan already in the store with no migration.
"""

from __future__ import annotations

from typing import Any

ART_BREAK_GAP = 40
ART_BREAK_CEILING = 140     # = pipeline._ART_DECISIVE_CEILING


def other_art_ids(candidates: list[dict[str, Any]]) -> set[str]:
    """Scryfall ids of the candidates past a clean artwork break (else empty)."""
    ds = [c.get("multi_distance") for c in candidates]
    if len(candidates) < 2 or any(d is None for d in ds):
        return set()
    ordered = sorted(ds)
    if ordered[0] > ART_BREAK_CEILING:
        return set()
    cut = next((ordered[i - 1] for i in range(1, len(ordered))
                if ordered[i] - ordered[i - 1] >= ART_BREAK_GAP), None)
    if cut is None:
        return set()
    return {c.get("id") for c in candidates
            if c["multi_distance"] > cut and not c.get("ocr_confirmed")}


def flag_other_art(scan: dict[str, Any]) -> dict[str, Any]:
    """Set other_art=True/False on each of *scan*'s candidates, in place."""
    cands = scan.get("candidates") or []
    other = other_art_ids(cands)
    for c in cands:
        c["other_art"] = c.get("id") in other
    return scan
