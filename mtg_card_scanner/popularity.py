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

FORMATS BEYOND COMMANDER
------------------------
Scryfall ships no play-rate ranking for Modern, Standard, Pioneer, Legacy or
Pauper, and there is no free API for one (measured 2026-09-19: MTGDecks and
Moxfield both 403 a non-browser client; MTGGoldfish and MTGTop8 are HTML only).
What the same payload DOES carry is `legalities` — where a card may legally be
played — and that is what `format_legality` surfaces.  Legality is not
popularity and is never mixed into a tier; it answers a different and genuinely
useful question, and it explains an UNRANKED tier at a glance (Black Lotus is
"banned" in Commander, which is exactly why EDHREC has no rank for it).

`penny_rank` is the ONE other play-rate ranking in the payload, and measurement
says to keep it firmly secondary:
  * It is absent on essentially every card worth money — Lightning Bolt,
    Thoughtseize, Sol Ring, Ragavan and Black Lotus all come back None, because
    Penny Dreadful's card pool is DEFINED as cards worth under a tix on MTGO.
    A scanner pricing cards to sell mostly sees cards it says nothing about.
  * It goes stale against its own legality: Counterspell reports penny_rank 8
    while `legalities.penny` reads "not_legal" (the format rotates on MTGO
    price, and the rank outlives the rotation).
So it is reported ONLY when the printing is currently penny-legal, and never
as a tier — a stale rank is worse than no rank.  Do not promote it.
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


# The paper formats that actually drive singles demand, in the order a seller
# thinks about them (newest-to-oldest constructed, then Pauper, then Commander).
# Scryfall reports 23 formats; the rest are digital-only (historic, timeless,
# alchemy, the Brawls) or vanishingly niche for a buylist, and listing them all
# would bury the three that matter.  Commander stays in despite having its own
# tier because a "banned" here is what explains an UNRANKED tier.
TRACKED_FORMATS: tuple[tuple[str, str], ...] = (
    ("standard",  "Standard"),
    ("pioneer",   "Pioneer"),
    ("modern",    "Modern"),
    ("legacy",    "Legacy"),
    ("vintage",   "Vintage"),
    ("pauper",    "Pauper"),
    ("commander", "Commander"),
)

# Scryfall's legality values.  Anything unrecognised is treated as not_legal
# rather than shown raw, so a new value can never render as a mystery chip.
_LEGALITY_VALUES = frozenset({"legal", "not_legal", "restricted", "banned"})


def format_legality(printing: dict[str, Any]) -> dict[str, str]:
    """Where this printing may legally be played, for TRACKED_FORMATS only.

    Always returns every tracked key, so the UI can distinguish "not legal in
    Standard" from "we have no legality data at all" (an empty dict).
    """
    legalities = printing.get("legalities") or {}
    if not legalities:
        return {}
    out: dict[str, str] = {}
    for key, _label in TRACKED_FORMATS:
        value = legalities.get(key)
        out[key] = value if value in _LEGALITY_VALUES else "not_legal"
    return out


def penny_rank(printing: dict[str, Any]) -> Optional[int]:
    """Penny Dreadful rank, but ONLY while the printing is actually penny-legal.

    The rank outlives the format's rotation — Counterspell reports rank 8 with
    `legalities.penny` reading "not_legal" — so an ungated read would show a
    number that no longer means anything.  See the module docstring.
    """
    if (printing.get("legalities") or {}).get("penny") != "legal":
        return None
    rank = printing.get("penny_rank")
    return int(rank) if isinstance(rank, (int, float)) and rank else None


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
    formats = format_legality(printing)
    if formats:
        out["formats"] = formats
    # No percentile for the penny rank: Scryfall's search syntax exposes no
    # `pennyrank` filter, so the size of the ranked pool cannot be measured the
    # way the EDHREC pool was.  A raw rank it is, rather than an invented one.
    penny = penny_rank(printing)
    if penny is not None:
        out["penny_rank"] = penny
    return out
