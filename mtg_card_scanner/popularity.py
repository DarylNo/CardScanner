"""How widely played a card is — derived from data Scryfall already sends us.

Every card object returned by `/cards/search` carries `edhrec_rank`: the card's
position in EDHREC's play-frequency ranking across all Commander decks, 1 being
the most played.  The scanner already fetches every printing of an identified
card (`scryfall.get_all_printings`), so the rank costs NO extra request, no
extra latency, and nothing from the Face to Face rate budget.

Measured against the live API on 2026-09-19:

    Sol Ring                   1      Nine Lives             6,321
    Counterspell              16      Grizzly Bears          8,181
    Llanowar Elves            58      Shivan Dragon         11,124
    Sensei's Divining Top    229      Kozilek's Pathfinder  20,113
    Ragavan                  280
    Murder                   850      32,296 cards ranked in total

Three things measurement settled, all of which must stay settled:

1.  **An absent rank does NOT mean unpopular.**  EDHREC only ranks cards that
    are legal in Commander, so Black Lotus (banned), Forest and Wastes (basic
    lands) all come back with `edhrec_rank = None`.  Labelling those "fringe"
    would be the most visibly wrong thing this module could do, so an absent
    rank is reported as UNRANKED with the reason, never bucketed.

2.  **Reprint count is NOT a popularity signal.**  The obvious-looking idea —
    "Wizards reprints what sells" — dies on contact with the data: Grizzly
    Bears has 25 paper printings at rank 8,181 while Ragavan has 12 at rank
    280.  `print_count` is carried and displayed as its own fact; it never
    moves a card's tier.

3.  **The rank must be read off the PRINTING, not the name.**  `edhrec_rank` is
    per-oracle-card, but one card name can span two oracle cards: `!"Lightning
    Bolt"` returns 67 printings at rank 158 plus 3 printings of the Secrets of
    Strixhaven "prepare"-layout card at rank 7,977.  Taking a min or a mode
    across the name would attribute the wrong card's popularity, so each
    candidate is summarised from its own fields and `print_count` counts only
    the printings sharing that candidate's `oracle_id`.

EDHREC recomputes its ranks continuously (Shivan Dragon moved 11,114 -> 11,124
between two probes minutes apart), so what a scan stores is a scan-time
snapshot.  That is fine for "is this worth a second look?", and is why nothing
downstream treats the number as stable.
"""

from __future__ import annotations

from typing import Any, Optional

# Cards carrying an EDHREC rank: measured 2026-09-19 via
# `/cards/search?q=edhrecrank<=999999&unique=cards` -> 32,296.  Only used to
# turn a rank into a percentile, so drifting a few hundred stale costs nothing.
RANKED_CARD_COUNT = 32_296

# (top-percentile ceiling, tier id, label).  Percentiles rather than raw ranks,
# so the buckets keep their meaning as the ranked pool grows.  Checked against
# the cards measured above: "staple" catches Sol Ring, Counterspell, Llanowar
# Elves, Sensei's Divining Top and Ragavan; "fringe" starts past Shivan Dragon.
_TIERS: tuple[tuple[float, str, str], ...] = (
    (1.0,  "staple", "Staple"),
    (5.0,  "strong", "Very popular"),
    (15.0, "solid",  "Popular"),
    (40.0, "played", "Played"),
)
_FRINGE = ("fringe", "Fringe")
_UNRANKED = ("unranked", "Unranked")

# Why a Commander-legal-only ranking has nothing to say about this card.  Shown
# in the UI so an absent rank never reads as "nobody plays this".
_UNRANKED_REASON = "not ranked by EDHREC — banned in Commander, a basic land, or too new"


def top_percent(rank: Optional[int]) -> Optional[float]:
    """Where *rank* falls as a "top N%" of all ranked cards, or None."""
    if not rank or rank < 1:
        return None
    pct = 100.0 * rank / RANKED_CARD_COUNT
    # Two decimals below 1% keeps the very top legible (Sol Ring is top 0.003%,
    # which rounds to a flat 0.0 at one decimal).
    return round(pct, 3 if pct < 1 else 1)


def tier_for(rank: Optional[int]) -> tuple[str, str]:
    """(tier id, human label) for *rank*.  An absent rank is UNRANKED, not fringe."""
    pct = top_percent(rank)
    if pct is None:
        return _UNRANKED
    for ceiling, tier, label in _TIERS:
        if pct <= ceiling:
            return tier, label
    return _FRINGE


def oracle_key(printing: dict[str, Any]) -> str:
    """The printing's oracle_id, reaching into card_faces when it has to.

    `reversible_card` printings (Secret Lair's double-sided Sol Ring, the
    Jurassic World Forest) carry NO top-level oracle_id — it sits on each face
    instead, exactly like the image_uris that scryfall.get_all_printings already
    grafts up.  Without the fallback those printings form their own group and a
    Secret Lair Sol Ring reports "1 paper printing" instead of 138.

    Returns "" when there is genuinely no oracle_id (old fixtures, trimmed
    payloads) so such printings still group together rather than vanish.
    """
    oid = printing.get("oracle_id")
    if oid:
        return str(oid)
    for face in printing.get("card_faces") or []:
        if face.get("oracle_id"):
            return str(face["oracle_id"])
    return ""


def print_counts_by_oracle(printings: list[dict[str, Any]]) -> dict[str, int]:
    """Map oracle_id -> number of printings, for the per-printing print count."""
    counts: dict[str, int] = {}
    for p in printings:
        key = oracle_key(p)
        counts[key] = counts.get(key, 0) + 1
    return counts


def summarize(printing: dict[str, Any], print_count: Optional[int] = None) -> dict[str, Any]:
    """Popularity facts for one Scryfall printing, ready to hand to the UI.

    *print_count* is how many paper printings share this card's oracle_id —
    a separate fact, deliberately NOT folded into the tier (see module docstring).
    """
    rank = printing.get("edhrec_rank")
    rank = int(rank) if isinstance(rank, (int, float)) and rank else None
    tier, label = tier_for(rank)
    out: dict[str, Any] = {
        "edhrec_rank": rank,
        "top_percent": top_percent(rank),
        "tier": tier,
        "label": label,
        "game_changer": bool(printing.get("game_changer", False)),
        "reserved": bool(printing.get("reserved", False)),
    }
    if rank is None:
        out["unranked_reason"] = _UNRANKED_REASON
    if print_count is not None:
        out["print_count"] = int(print_count)
    return out
