"""Modern play data (mtg_card_scanner/modern_meta.py): the read-time join, the
pack format and the CI builder — all on fakes, no network."""
from __future__ import annotations

import datetime as dt
import json
import os
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from mtg_card_scanner import modern_meta as mm
from mtg_card_scanner.popularity import summarize
from server.app import create_app
from server.store import ScanStore

BOLT = "11111111-0000-0000-0000-000000000001"
SEIZE = "11111111-0000-0000-0000-000000000002"
PUSH = "11111111-0000-0000-0000-000000000003"
ISLAND = "11111111-0000-0000-0000-000000000004"
TITHE = "11111111-0000-0000-0000-000000000005"
FABLE = "11111111-0000-0000-0000-000000000006"
BOLT2 = "11111111-0000-0000-0000-000000000007"
SPIDEY = "11111111-0000-0000-0000-000000000008"
LOTUS = "11111111-0000-0000-0000-000000000009"


def _card(oid, name, modern="legal", type_line="Instant", layout="normal", faces=None):
    c = {"oracle_id": oid, "name": name, "layout": layout, "type_line": type_line,
         "legalities": {"modern": modern}}
    if faces:
        c["card_faces"] = [{"name": f} for f in faces]
    return c


ORACLE = [
    _card(BOLT, "Lightning Bolt"),
    _card(SEIZE, "Thoughtseize", type_line="Sorcery"),
    _card(PUSH, "Fatal Push"),
    _card(ISLAND, "Island", type_line="Basic Land — Island"),
    _card(TITHE, "Smothering Tithe", type_line="Enchantment"),
    _card(FABLE, "Fable of the Mirror-Breaker // Reflection of Kiki-Jiki", type_line="Enchantment — Saga",
          layout="transform", faces=["Fable of the Mirror-Breaker", "Reflection of Kiki-Jiki"]),
    # another oracle card with a face named exactly like the classic: the
    # read-time name fallback must never pick one of them
    _card(BOLT2, "Lightning Bolt // Prepared Strike", modern="not_legal", layout="prepare",
          faces=["Lightning Bolt", "Prepared Strike"]),
    _card(SPIDEY, "Superior Spider-Man", type_line="Legendary Creature"),
    _card(LOTUS, "Black Lotus", modern="not_legal", type_line="Artifact"),
    {"oracle_id": "tok", "name": "Goblin", "layout": "token", "type_line": "Token Creature"},
    {"oracle_id": "front", "name": "Inferno", "layout": "front_card", "type_line": "Card"},
]


def _event(date, decks, fmt="Modern"):
    return {"Tournament": {"Date": date, "Name": "x", "Formats": fmt},
            "Decks": [{"Date": f"{date}T13:00:00+00:00", "Mainboard": [{"Count": 4, "CardName": n} for n in main],
                       "Sideboard": [{"Count": 1, "CardName": n} for n in side]} for main, side in decks]}


def _write(root: Path, rel: str, doc) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(doc), encoding="utf-8")


def _pack(**over):
    doc = {"kind": mm.PACK_KIND, "version": 1, "format": "modern", "as_of": "2026-11-10",
           "window_days": 60, "built_at": "2026-11-10T07:41:00Z", "decks": 16831, "events": 256,
           "sources": {"mtgo": {"decks": 6146, "events": 177, "weighted": True},
                       "paper": {"decks": 10685, "events": 79, "weighted": True}},
           "unmatched_names": 0, "unmatched_entries": 0,
           "cards": {SEIZE: [2689, 9], PUSH: [210, 120], FABLE: [199, 140], BOLT: [2560, 12], SPIDEY: [1, 2400]},
           "basic": [ISLAND],
           "names": {"thoughtseize": SEIZE, "fatal push": PUSH, "island": ISLAND,
                     "fable of the mirror-breaker // reflection of kiki-jiki": FABLE,
                     "fable of the mirror-breaker": FABLE, "reflection of kiki-jiki": FABLE,
                     "superior spider-man": SPIDEY},
           "ambiguous": ["lightning bolt"]}
    doc.update(over)
    return doc


PACK = mm.parse_pack(json.dumps(_pack()))


# ── tiers and parsing ─────────────────────────────────────────────────────────

def test_tiers_are_absolute_shares_of_decks():
    assert [mm.tier_for_bp(b) for b in (1, 199, 200, 999, 1000, 9999)] == \
        ["fringe", "fringe", "played", "played", "staple", "staple"]


@pytest.mark.parametrize("text,reason", [
    ("{nope", "not JSON"),
    ("[]", "not a Modern play data file"),
    (json.dumps({"kind": "something else"}), "not a Modern play data file"),
    (json.dumps(_pack(version=2)), "unsupported version"),
    (json.dumps(_pack(version=1.0)), "unsupported version"),
    (json.dumps(_pack(version="1")), "unsupported version"),
    (json.dumps(_pack(cards={SEIZE: [0, 1]})), "malformed"),          # a played card is ≥ 1 bp
    (json.dumps(_pack(cards={SEIZE: [2689]})), "malformed"),
    (json.dumps(_pack(cards={SEIZE: [True, 1]})), "malformed"),       # a bool is not a count
    (json.dumps(_pack(basic="island")), "malformed"),
    (json.dumps(_pack(names={"x": 1})), "malformed"),
    (json.dumps(_pack(decks="many")), "malformed"),
    (json.dumps(_pack(sources={"mtgo": {"decks": 1}})), "malformed"),
])
def test_a_bad_file_says_why(text, reason):
    with pytest.raises(mm.ModernMetaError) as e:
        mm.parse_pack(text)
    assert str(e.value) == reason


# ── the read-time answer ─────────────────────────────────────────────────────

def pop(oid=None, modern=None):
    p = {"tier": "solid", "label": "Popular"}
    if oid:
        p["oracle_id"] = oid
    if modern:
        p["formats"] = {"modern": modern}
    return p


def test_by_oracle_id():
    assert mm.modern_for(pop(SEIZE, "legal"), "Thoughtseize", PACK) == {"tier": "staple", "bp": 2689, "rank": 9}
    assert mm.modern_for(pop(PUSH, "legal"), "x", PACK) == {"tier": "played", "bp": 210, "rank": 120}
    assert mm.modern_for(pop(SPIDEY, "legal"), "x", PACK)["tier"] == "fringe"
    assert mm.modern_for(pop(ISLAND, "legal"), "Island", PACK) == {"tier": "basic"}


def test_in_no_deck_is_an_answer_read_off_the_scans_own_legality():
    assert mm.modern_for(pop(TITHE, "legal"), "Smothering Tithe", PACK) == {"tier": "unplayed"}
    assert mm.modern_for(pop(TITHE, "restricted"), "x", PACK) == {"tier": "unplayed"}
    assert mm.modern_for(pop(LOTUS, "not_legal"), "Black Lotus", PACK) == {"tier": "not_legal"}
    assert mm.modern_for(pop("zz", "banned"), "Hogaak", PACK) == {"tier": "banned"}
    # no legality recorded (a scan from before the formats) → not known
    assert mm.modern_for(pop(TITHE), "Smothering Tithe", PACK) is None


def test_pack_membership_beats_a_stale_legality_snapshot():
    assert mm.modern_for(pop(SEIZE, "not_legal"), "Thoughtseize", PACK)["tier"] == "staple"


def test_old_scans_fall_back_to_the_name_never_an_ambiguous_one():
    assert mm.modern_for(pop(modern="legal"), "Thoughtseize", PACK)["bp"] == 2689
    assert mm.modern_for(pop(modern="legal"), "THOUGHTSEIZE ", PACK)["bp"] == 2689
    assert mm.modern_for(pop(modern="legal"), "Fable of the Mirror-Breaker // Reflection of Kiki-Jiki", PACK)["bp"] == 199
    assert mm.modern_for(None, "Island", PACK) == {"tier": "basic"}
    # Lightning Bolt is in the pack by oracle_id, but by NAME it is ambiguous
    assert mm.modern_for(pop(modern="legal"), "Lightning Bolt", PACK) is None
    assert mm.modern_for(pop(BOLT, "legal"), "Lightning Bolt", PACK)["bp"] == 2560
    # a legal card no deck runs, found by name only
    assert mm.modern_for(pop(modern="legal"), "Smothering Tithe", PACK) == {"tier": "unplayed"}


def test_nothing_to_go_on_or_no_pack_is_none():
    assert mm.modern_for(pop(modern="legal"), None, PACK) is None
    assert mm.modern_for(pop(modern="legal"), "  ", PACK) is None
    assert mm.modern_for(pop(SEIZE, "legal"), "Thoughtseize", None) is None


def test_the_scan_answer_reads_the_same_printing_as_the_popularity_pill():
    scan = {"candidates": [{"name": "Thoughtseize", "popularity": pop(SEIZE, "legal")},
                           {"name": "Fatal Push", "popularity": pop(PUSH, "legal")}]}
    assert mm.scan_modern(scan, PACK)["bp"] == 2689
    scan["selection"] = {"name": "Fatal Push", "popularity": pop(PUSH, "legal")}
    assert mm.scan_modern(scan, PACK)["bp"] == 210
    # a selection without popularity (filed before the feature): the pill falls
    # back to the top candidate's popularity, and so does this
    scan["selection"] = {"name": "Fatal Push", "popularity": None}
    assert mm.scan_modern(scan, PACK)["bp"] == 2689
    # nothing but the read
    assert mm.scan_modern({"candidates": [], "card_read": {"name": "Thoughtseize"}}, PACK)["bp"] == 2689
    assert mm.scan_modern({"candidates": []}, PACK) is None


def test_flag_adds_a_key_only_while_there_is_data():
    scan = {"candidates": [{"name": "Thoughtseize", "popularity": pop(SEIZE, "legal")}]}
    assert "modern" not in mm.flag_modern(dict(scan), None)
    assert mm.flag_modern(dict(scan), PACK)["modern"]["tier"] == "staple"
    unknown = mm.flag_modern({"candidates": []}, PACK)
    assert "modern" in unknown and unknown["modern"] is None


def test_status():
    assert mm.pack_status(None) == {"loaded": False}
    st = mm.pack_status(PACK)
    assert st == {"loaded": True, "as_of": "2026-11-10", "window_days": 60, "decks": 16831, "events": 256,
                  "sources": {"mtgo": {"decks": 6146, "events": 177, "weighted": True},
                              "paper": {"decks": 10685, "events": 79, "weighted": True}},
                  "played": 5}


def test_summarize_carries_the_oracle_id_reversible_cards_included():
    assert summarize({"oracle_id": SEIZE, "edhrec_rank": 4442})["oracle_id"] == SEIZE
    rev = {"layout": "reversible_card", "edhrec_rank": 1, "card_faces": [{"oracle_id": "sol"}, {"oracle_id": "sol"}]}
    assert summarize(rev)["oracle_id"] == "sol"
    assert "oracle_id" not in summarize({"edhrec_rank": 1})


# ── the file the reference server reads ──────────────────────────────────────

def test_the_file_source_rereads_on_change_and_keeps_the_last_good_pack(tmp_path):
    f = tmp_path / "modern-meta.json"
    src = mm.ModernMetaFile(f)
    assert src.current() is None
    f.write_text(json.dumps(_pack()), encoding="utf-8")
    assert src.current()["decks"] == 16831
    f.write_text("{broken", encoding="utf-8")
    os.utime(f, ns=(time.time_ns() + 10**9, time.time_ns() + 10**9))
    assert src.current()["decks"] == 16831 and src.error == "not JSON"
    f.write_text(json.dumps(_pack(decks=20000)), encoding="utf-8")
    os.utime(f, ns=(time.time_ns() + 2 * 10**9, time.time_ns() + 2 * 10**9))
    assert src.current()["decks"] == 20000 and src.error is None


def test_the_server_is_off_without_a_file(monkeypatch):
    monkeypatch.delenv("MODERN_META_FILE", raising=False)
    assert create_app.__globals__["_default_modern_meta"]() is None


class _Fixed:
    def __init__(self, pack):
        self.pack = pack

    def current(self):
        return self.pack


def test_scan_reads_carry_modern_evaluated_at_read_time(tmp_path):
    store = ScanStore(str(tmp_path / "s.db"))
    sid = store.create_scan(identified=True, card_read={"name": "Thoughtseize"}, confidence={},
                            candidates=[{"id": "p1", "name": "Thoughtseize",
                                         "popularity": pop(SEIZE, "legal")}])["id"]
    src = _Fixed(None)
    app = create_app(pipeline_factory=lambda: None, store=store, f2f=object(),
                     scan_images_dir=tmp_path, auto_sweep_interval=None, modern_meta=src)
    c = TestClient(app)
    assert "modern" not in c.get("/api/scans").json()[0]
    assert "modern" not in c.get(f"/api/scans/{sid}").json()
    assert c.get("/api/modern-meta").json() == {"loaded": False}
    src.pack = PACK
    assert c.get("/api/scans").json()[0]["modern"] == {"tier": "staple", "bp": 2689, "rank": 9}
    assert c.get(f"/api/scans/{sid}").json()["modern"]["rank"] == 9
    assert c.get("/api/modern-meta").json()["decks"] == 16831
    # never stored
    assert "modern" not in store.get_scan(sid)


# ── the builder (CI) ─────────────────────────────────────────────────────────

AS_OF = dt.date(2026, 11, 10)


def _sources(tmp_path, mtgo_decks, paper_decks):
    mtgo, paper = tmp_path / "modo", tmp_path / "fbet"
    for i, (date, decks) in enumerate(mtgo_decks):
        _write(mtgo, f"Tournaments/MTGO/{date[:4]}/{date[5:7]}/{date[8:]}/modern-challenge-{i}.json", _event(date, decks))
    for i, (date, decks, site) in enumerate(paper_decks):
        _write(paper, f"Tournaments/{site}/{date[:4]}/{date[5:7]}/{date[8:]}/event-{i}.json", _event(date, decks))
    return mtgo, paper


def _build(mtgo, paper, **kw):
    kw.setdefault("min_pack_decks", 1)
    kw.setdefault("min_pack_cards", 1)
    return mm.build_pack(mm.OracleIndex(ORACLE), mtgo, paper, AS_OF, built_at="B", **kw)


def test_builder_equal_weight_rounding_ranks_and_window(tmp_path, monkeypatch):
    monkeypatch.setattr(mm, "MIN_SOURCE_DECKS", 1)
    mtgo, paper = _sources(tmp_path, [
        ("2026-11-10", [(["Thoughtseize", "Island"], ["Fatal Push"]), (["Lightning Bolt"], [])]),
        ("2026-09-11", [(["Thoughtseize"], [])]),          # day 60: in (start exclusive = 09-11? no: 11-10 − 60 = 09-11)
        ("2026-11-11", [(["Thoughtseize"], [])]),          # after as_of: out
    ], [
        ("2026-10-01", [(["Thoughtseize"], []), (["Fatal Push"], []), (["Fatal Push"], ["Thoughtseize"])], "MTGmelee"),
        ("2026-10-02", [(["Fable of the Mirror-Breaker"], [])], "Topdeck"),
    ])
    pack = _build(mtgo, paper)
    assert pack["sources"] == {"mtgo": {"decks": 2, "events": 1, "weighted": True},
                               "paper": {"decks": 4, "events": 2, "weighted": True}}
    assert pack["decks"] == 6 and pack["events"] == 3
    c = pack["cards"]
    # Thoughtseize: MTGO 1/2, paper 2/4 → 50 % → 5000 bp, rank 1
    assert c[SEIZE] == [5000, 1]
    # Fatal Push: MTGO 1/2 (sideboard counts), paper 2/4 → 5000 bp, tied at rank 1
    assert c[PUSH] == [5000, 1]
    # Lightning Bolt: MTGO 1/2, paper 0 → 2500; Fable: paper 1/4 → 1250 (face name resolves)
    assert c[BOLT] == [2500, 3] and c[FABLE] == [1250, 4]
    assert ISLAND not in c and pack["basic"] == [ISLAND]
    assert "lightning bolt" in pack["ambiguous"] and "lightning bolt" not in pack["names"]
    assert pack["names"]["island"] == ISLAND and pack["names"]["reflection of kiki-jiki"] == FABLE


def test_the_window_edges(tmp_path, monkeypatch):
    monkeypatch.setattr(mm, "MIN_SOURCE_DECKS", 1)
    mtgo, paper = _sources(tmp_path, [("2026-09-11", [(["Thoughtseize"], [])]),       # = as_of − 60: out
                                      ("2026-09-12", [(["Fatal Push"], [])]),         # first day in
                                      ("2026-11-10", [(["Fatal Push"], [])])], [])
    pack = _build(mtgo, None)
    assert SEIZE not in pack["cards"] and pack["cards"][PUSH] == [10000, 1] and pack["decks"] == 2


def test_rounding_is_half_up_and_a_seen_card_is_never_zero(tmp_path, monkeypatch):
    monkeypatch.setattr(mm, "MIN_SOURCE_DECKS", 1)
    # 1 deck in 8 = 1250 bp exactly; 1 in 3 = 3333.33 → 3333; 2 in 3 = 6666.67 → 6667
    decks = [(["Thoughtseize"], [])] + [(["Fatal Push"], [])] * 2
    mtgo, _ = _sources(tmp_path, [("2026-11-01", decks)], [])
    c = _build(mtgo, None)["cards"]
    assert c[SEIZE] == [3333, 2] and c[PUSH] == [6667, 1]
    many = [(["Fatal Push"], [])] * 20001 + [(["Thoughtseize"], [])]
    mtgo2, _ = _sources(tmp_path / "b", [("2026-11-01", many)], [])
    assert _build(mtgo2, None)["cards"][SEIZE] == [1, 2]        # 0.49 bp → never 0


def test_a_thin_source_is_left_out_of_the_mean(tmp_path, monkeypatch):
    monkeypatch.setattr(mm, "MIN_SOURCE_DECKS", 3)
    mtgo, paper = _sources(tmp_path, [("2026-11-01", [(["Thoughtseize"], [])] * 3)],
                           [("2026-11-01", [(["Fatal Push"], [])], "MTGmelee")])
    pack = _build(mtgo, paper)
    assert pack["sources"]["paper"] == {"decks": 1, "events": 1, "weighted": False}
    assert pack["decks"] == 3 and PUSH not in pack["cards"] and pack["cards"][SEIZE] == [10000, 1]


def test_too_few_decks_or_cards_refuses_to_build(tmp_path, monkeypatch):
    monkeypatch.setattr(mm, "MIN_SOURCE_DECKS", 1)
    mtgo, _ = _sources(tmp_path, [("2026-11-01", [(["Thoughtseize"], [])])], [])
    with pytest.raises(mm.ModernMetaError, match="only 1 decks"):
        _build(mtgo, None, min_pack_decks=2)
    with pytest.raises(mm.ModernMetaError, match="only 1 cards"):
        _build(mtgo, None, min_pack_cards=2)


def test_only_modern_events_and_only_these_folders(tmp_path, monkeypatch):
    monkeypatch.setattr(mm, "MIN_SOURCE_DECKS", 1)
    mtgo, paper = _sources(tmp_path, [("2026-11-01", [(["Thoughtseize"], [])])], [])
    _write(mtgo, "Tournaments/MTGO/2026/11/01/premodern-league-1.json", _event("2026-11-01", [(["Fatal Push"], [])], "Premodern"))
    _write(mtgo, "Tournaments/MTGO/2026/11/01/legacy-league-1.json", _event("2026-11-01", [(["Fatal Push"], [])], "Modern"))
    _write(paper, "Tournaments/MTGO/2026/11/01/modern-x.json", _event("2026-11-01", [(["Fatal Push"], [])]))
    _write(paper, "Tournaments/MTGmelee/2026/11/01/premod.json", _event("2026-11-01", [(["Fatal Push"], [])], "Premodern"))
    pack = _build(mtgo, paper)
    assert PUSH not in pack["cards"] and pack["decks"] == 1


def test_the_build_lookup_takes_only_an_exact_printed_name(tmp_path, monkeypatch):
    monkeypatch.setattr(mm, "MIN_SOURCE_DECKS", 1)
    mtgo, _ = _sources(tmp_path, [("2026-11-01", [(["Kavaero, Mind-Bitten", "Thoughtseize"], ["Agujas"])])], [])
    asked = []

    def lookup(name):
        asked.append(name)
        if name == "Kavaero, Mind-Bitten":
            return {"name": "Superior Spider-Man", "printed_name": "Kavaero, Mind-Bitten", "oracle_id": SPIDEY}
        return {"name": "Fatal Push", "oracle_id": PUSH}        # a fuzzy guess: refused
    pack = _build(mtgo, None, lookup=lookup)
    assert asked == ["Kavaero, Mind-Bitten", "Agujas"]
    assert pack["cards"][SPIDEY] == [10000, 1] and PUSH not in pack["cards"]
    assert pack["unmatched_names"] == 1 and pack["unmatched_entries"] == 1


def test_front_cards_and_tokens_never_shadow_a_real_card():
    idx = mm.OracleIndex(ORACLE)
    assert idx.resolve("Fable of the Mirror-Breaker") == FABLE
    assert idx.resolve("Lightning Bolt") == BOLT                # exact full name wins
    assert idx.resolve("Goblin") is None and idx.resolve("Inferno") is None


def test_a_name_two_cards_share_resolves_to_the_modern_legal_one():
    idx = mm.OracleIndex(ORACLE + [_card("pyp-mkm", "Pick Your Poison"),
                                   _card("pyp-cmb2", "Pick Your Poison", modern="not_legal")])
    assert idx.resolve("Pick Your Poison") == "pyp-mkm"


def test_the_file_is_byte_stable_and_parses(tmp_path, monkeypatch):
    monkeypatch.setattr(mm, "MIN_SOURCE_DECKS", 1)
    mtgo, _ = _sources(tmp_path, [("2026-11-01", [(["Thoughtseize"], ["Island"])])], [])
    a, b = mm.dump_pack(_build(mtgo, None)), mm.dump_pack(_build(mtgo, None))
    assert a == b and a.endswith("\n")
    p = mm.parse_pack(a)
    assert p["cards"][SEIZE] == (10000, 1) and ISLAND in p["basic"]
    assert mm.modern_for(pop(modern="legal"), "Island", p) == {"tier": "basic"}


def test_oracle_bulk_in_both_formats(tmp_path):
    import gzip
    arr = tmp_path / "a.json"
    arr.write_text(json.dumps(ORACLE[:2]), encoding="utf-8")
    gz = tmp_path / "b.jsonl.gz"
    with gzip.open(gz, "wt", encoding="utf-8") as fh:
        for c in ORACLE[:2]:
            fh.write(json.dumps(c) + "\n")
    assert [c["name"] for c in mm.load_oracle_entries(arr)] == ["Lightning Bolt", "Thoughtseize"]
    assert [c["name"] for c in mm.load_oracle_entries(gz)] == ["Lightning Bolt", "Thoughtseize"]
