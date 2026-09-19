"""Tests for the EDHREC-popularity summary (mtg_card_scanner/popularity.py).

The tier boundaries here are pinned to cards whose real ranks were measured
against the live Scryfall API on 2026-09-19 (see the module docstring), so a
threshold change that would silently re-label Sol Ring or Shivan Dragon fails
here instead of on the rig.
"""

import pytest

from mtg_card_scanner.popularity import (
    RANKED_CARD_COUNT,
    TRACKED_FORMATS,
    print_counts_by_oracle,
    summarize,
    tier_for,
    top_percent,
)


def _printing(**over):
    p = {"id": "p1", "name": "Lightning Bolt", "oracle_id": "oracle-a",
         "edhrec_rank": 158}
    p.update(over)
    return p


# ── tiers ────────────────────────────────────────────────────────────────────

@pytest.mark.parametrize("rank,tier", [
    (1,     "staple"),   # Sol Ring
    (16,    "staple"),   # Counterspell
    (280,   "staple"),   # Ragavan, Nimble Pilferer
    (850,   "strong"),   # Murder
    (2329,  "solid"),    # Thalia, Guardian of Thraben
    (6321,  "played"),   # Nine Lives
    (11124, "played"),   # Shivan Dragon
    (20113, "fringe"),   # Kozilek's Pathfinder
])
def test_measured_cards_land_in_their_tier(rank, tier):
    assert tier_for(rank)[0] == tier


def test_tiers_are_monotonic_in_rank():
    """A worse rank can never land in a better tier."""
    order = ["staple", "strong", "solid", "played", "fringe"]
    seen = [order.index(tier_for(r)[0]) for r in range(1, RANKED_CARD_COUNT, 97)]
    assert seen == sorted(seen)


# ── the two rules measurement settled ────────────────────────────────────────

def test_absent_rank_is_unranked_not_fringe():
    """Black Lotus, Forest and Wastes have no EDHREC rank — that is not 'fringe'."""
    out = summarize(_printing(edhrec_rank=None))
    assert out["tier"] == "unranked"
    assert out["label"] == "Unranked"
    assert out["edhrec_rank"] is None
    assert out["top_percent"] is None
    assert "unranked_reason" in out and out["unranked_reason"]


def test_unranked_reason_is_absent_when_ranked():
    assert "unranked_reason" not in summarize(_printing())


def test_print_count_never_moves_the_tier():
    """Grizzly Bears: 25 printings at rank 8,181 — reprints are not popularity."""
    bears = summarize(_printing(edhrec_rank=8181), print_count=25)
    ragavan = summarize(_printing(edhrec_rank=280), print_count=12)
    assert bears["tier"] == "played"
    assert ragavan["tier"] == "staple"
    assert bears["print_count"] == 25 and ragavan["print_count"] == 12
    # Same rank, wildly different print counts → identical tier.
    assert (summarize(_printing(edhrec_rank=8181), print_count=1)["tier"]
            == summarize(_printing(edhrec_rank=8181), print_count=900)["tier"])


# ── per-printing, not per-name ───────────────────────────────────────────────

def test_print_count_is_scoped_to_the_oracle_card():
    """`!"Lightning Bolt"` spans two oracle cards — each keeps its own count."""
    printings = ([{"oracle_id": "oracle-a"}] * 67) + ([{"oracle_id": "oracle-b"}] * 3)
    counts = print_counts_by_oracle(printings)
    assert counts == {"oracle-a": 67, "oracle-b": 3}


def test_printings_without_oracle_id_still_get_a_count():
    counts = print_counts_by_oracle([{"id": "a"}, {"id": "b"}])
    assert counts[""] == 2


def test_two_oracle_cards_sharing_a_name_keep_their_own_ranks():
    bolt = summarize(_printing(edhrec_rank=158), print_count=67)
    prepare = summarize(_printing(edhrec_rank=7977, oracle_id="oracle-b"), print_count=3)
    assert bolt["tier"] == "staple"
    assert prepare["tier"] == "played"


# ── percentile + flags ───────────────────────────────────────────────────────

def test_top_percent_keeps_resolution_at_the_very_top():
    """Sol Ring is top 0.003% — one decimal would flatten it to 0.0."""
    assert top_percent(1) == round(100.0 / RANKED_CARD_COUNT, 3)
    assert top_percent(1) > 0
    assert top_percent(11124) == 34.4


def test_top_percent_of_missing_or_bogus_rank_is_none():
    assert top_percent(None) is None
    assert top_percent(0) is None
    assert top_percent(-5) is None


def test_flags_are_carried_and_default_false():
    plain = summarize(_printing())
    assert plain["game_changer"] is False and plain["reserved"] is False
    flagged = summarize(_printing(game_changer=True, reserved=True))
    assert flagged["game_changer"] is True and flagged["reserved"] is True


def test_print_count_omitted_when_not_supplied():
    assert "print_count" not in summarize(_printing())


def test_summary_is_json_serialisable():
    """It is stored in the scans.db candidates/selection JSON columns."""
    import json
    json.loads(json.dumps(summarize(_printing(), print_count=70)))


# ── reversible cards keep no top-level oracle_id ─────────────────────────────

def test_reversible_card_oracle_id_is_read_off_its_faces():
    """Measured: Secret Lair's double-sided Sol Ring (sld 1512) and the Jurassic
    World Forest (rex 25) carry oracle_id only on card_faces.  Without the
    fallback each forms its own group and reports "1 paper printing"."""
    from mtg_card_scanner.popularity import oracle_key
    reversible = {"layout": "reversible_card", "edhrec_rank": 1,
                  "card_faces": [{"oracle_id": "oracle-solring", "name": "Sol Ring"},
                                 {"oracle_id": "oracle-solring", "name": "Sol Ring"}]}
    assert oracle_key(reversible) == "oracle-solring"

    printings = ([{"oracle_id": "oracle-solring"}] * 138) + [reversible]
    counts = print_counts_by_oracle(printings)
    assert counts == {"oracle-solring": 139}


def test_oracle_key_falls_back_to_empty_string():
    from mtg_card_scanner.popularity import oracle_key
    assert oracle_key({}) == ""
    assert oracle_key({"card_faces": [{"name": "no oracle here"}]}) == ""


# ── format legality (a different question from popularity) ───────────────────

def _legal(**over):
    base = {k: "not_legal" for k, _ in TRACKED_FORMATS}
    base["penny"] = "not_legal"
    base.update(over)
    return {"edhrec_rank": 158, "legalities": base}


def test_tracked_formats_are_all_reported():
    """Every tracked key is present, so the UI can tell 'not legal in Standard'
    apart from 'no legality data at all'."""
    out = summarize(_legal(modern="legal"))
    assert set(out["formats"]) == {k for k, _ in TRACKED_FORMATS}
    assert out["formats"]["modern"] == "legal"
    assert out["formats"]["standard"] == "not_legal"


def test_untracked_formats_are_dropped():
    """Scryfall reports 23 formats; the digital-only ones would bury the rest."""
    out = summarize(_legal(modern="legal", alchemy="legal", timeless="legal"))
    assert "alchemy" not in out["formats"]
    assert "timeless" not in out["formats"]


def test_banned_and_restricted_are_preserved():
    """Black Lotus: restricted in Vintage, banned in Commander — and that ban is
    exactly why EDHREC has no rank for it."""
    out = summarize({"edhrec_rank": None,
                     "legalities": {**_legal()["legalities"],
                                    "vintage": "restricted", "commander": "banned"}})
    assert out["formats"]["vintage"] == "restricted"
    assert out["formats"]["commander"] == "banned"
    assert out["tier"] == "unranked"


def test_unknown_legality_value_degrades_to_not_legal():
    """A new Scryfall value must never render as a mystery chip."""
    out = summarize(_legal(modern="some_future_value"))
    assert out["formats"]["modern"] == "not_legal"


def test_formats_absent_when_the_payload_has_no_legalities():
    assert "formats" not in summarize({"edhrec_rank": 158})


def test_legality_never_moves_the_tier():
    banned = summarize(_legal(commander="banned"))
    legal = summarize(_legal(commander="legal"))
    assert banned["tier"] == legal["tier"] == "staple"


# ── penny_rank is gated on current penny legality ────────────────────────────

def test_penny_rank_is_suppressed_when_not_penny_legal():
    """Measured: Counterspell reports penny_rank 8 while legalities.penny reads
    'not_legal' — the format rotates on MTGO price and the rank outlives it.
    A stale rank is worse than no rank."""
    out = summarize({"edhrec_rank": 16, "penny_rank": 8,
                     "legalities": {**_legal()["legalities"], "penny": "not_legal"}})
    assert "penny_rank" not in out


def test_penny_rank_is_reported_when_penny_legal():
    out = summarize({"edhrec_rank": 11124, "penny_rank": 11261,
                     "legalities": {**_legal()["legalities"], "penny": "legal"}})
    assert out["penny_rank"] == 11261


def test_penny_legal_without_a_rank_reports_nothing():
    """Forest is penny-legal but carries no penny_rank."""
    out = summarize({"edhrec_rank": None, "penny_rank": None,
                     "legalities": {**_legal()["legalities"], "penny": "legal"}})
    assert "penny_rank" not in out


def test_penny_rank_never_becomes_a_tier():
    """It is absent on essentially every card worth money, so it can never be
    the headline signal."""
    out = summarize({"edhrec_rank": None, "penny_rank": 6,
                     "legalities": {**_legal()["legalities"], "penny": "legal"}})
    assert out["tier"] == "unranked"
    assert out["label"] == "Unranked"
