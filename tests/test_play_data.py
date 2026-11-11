"""Play data (mtg_card_scanner/play_data.py): the read-time join, the file
format and the CI builder — all on fakes, no network."""
from __future__ import annotations

import datetime as dt
import gzip
import json
import os
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from mtg_card_scanner import play_data as pd
from mtg_card_scanner.popularity import TRACKED_FORMATS, summarize
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


def _card(oid, name, type_line="Instant", layout="normal", faces=None, **legal):
    leg = {k: "legal" for k in pd.FORMAT_KEYS}
    leg.update(legal)
    c = {"oracle_id": oid, "name": name, "layout": layout, "type_line": type_line, "legalities": leg}
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
    _card(BOLT2, "Lightning Bolt // Prepared Strike", layout="prepare",
          faces=["Lightning Bolt", "Prepared Strike"], **{k: "not_legal" for k in pd.FORMAT_KEYS}),
    _card(SPIDEY, "Superior Spider-Man", type_line="Legendary Creature"),
    _card(LOTUS, "Black Lotus", type_line="Artifact", vintage="restricted", modern="not_legal"),
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


def _fmt(cards, decks=16831):
    return {"decks": decks, "events": 256,
            "sources": {"mtgo": {"decks": 6146, "events": 177, "weighted": True},
                        "paper": {"decks": 10685, "events": 79, "weighted": True}},
            "cards": cards}


def _doc(**over):
    doc = {"kind": pd.PACK_KIND, "version": 1, "as_of": "2026-11-10", "window_days": 60,
           "built_at": "2026-11-10T07:41:00Z", "skipped": {}, "unmatched_names": 0, "unmatched_entries": 0,
           "formats": {"modern": _fmt({SEIZE: [2689, 9], PUSH: [210, 120], FABLE: [199, 140],
                                       BOLT: [2560, 12], SPIDEY: [1, 2400]}),
                       "legacy": _fmt({SEIZE: [3100, 4], BOLT: [2200, 15]}, decks=5768)},
           "basic": [ISLAND],
           "names": {"thoughtseize": SEIZE, "fatal push": PUSH, "island": ISLAND,
                     "fable of the mirror-breaker // reflection of kiki-jiki": FABLE,
                     "fable of the mirror-breaker": FABLE, "reflection of kiki-jiki": FABLE,
                     "superior spider-man": SPIDEY},
           "ambiguous": ["lightning bolt"]}
    doc.update(over)
    return doc


PACK = pd.parse_pack(json.dumps(_doc()))


def legal(**over):
    f = {k: "legal" for k, _ in TRACKED_FORMATS}
    f.update(over)
    return f


def pop(oid=None, formats=None):
    p = {"tier": "solid", "label": "Popular"}
    if oid:
        p["oracle_id"] = oid
    if formats is not None:
        p["formats"] = formats
    return p


S = {"tier": "staple", "bp": 2689, "rank": 9}


# ── tiers and parsing ─────────────────────────────────────────────────────────

def test_tiers_are_absolute_shares_of_decks():
    assert [pd.tier_for_bp(b) for b in (1, 199, 200, 999, 1000, 9999)] == \
        ["fringe", "fringe", "played", "played", "staple", "staple"]


def test_the_formats_follow_the_legality_chips_order_and_premodern_is_tracked():
    tracked = [k for k, _ in TRACKED_FORMATS]
    assert "premodern" in tracked
    assert [k for k in tracked if k in pd.FORMAT_KEYS] == list(pd.FORMAT_KEYS)


def _bad_fmt(**over):
    f = _fmt({SEIZE: [2689, 9]})
    f.update(over)
    return json.dumps(_doc(formats={"modern": f}))


@pytest.mark.parametrize("text,reason", [
    ("{nope", "not JSON"),
    ("[]", "not a play data file"),
    (json.dumps({"kind": "cardscanner-modern-meta"}), "not a play data file"),
    (json.dumps(_doc(version=2)), "unsupported version"),
    (json.dumps(_doc(version=1.0)), "unsupported version"),
    (json.dumps(_doc(version="1")), "unsupported version"),
    (json.dumps(_doc(formats={})), "no formats"),
    (json.dumps(_doc(formats={"commander": _fmt({})})), "no formats"),   # unknown formats are ignored
    (_bad_fmt(cards={SEIZE: [0, 1]}), "malformed"),                     # a played card is ≥ 1 bp
    (_bad_fmt(cards={SEIZE: [2689]}), "malformed"),
    (_bad_fmt(cards={SEIZE: [True, 1]}), "malformed"),                  # a bool is not a count
    (_bad_fmt(cards={SEIZE: [2689.0, 9]}), "malformed"),                # nor is a float
    (_bad_fmt(decks="many"), "malformed"),
    (_bad_fmt(sources={"mtgo": {"decks": 1}}), "malformed"),
    (json.dumps(_doc(basic="island")), "malformed"),
    (json.dumps(_doc(names={"x": 1})), "malformed"),
    (json.dumps(_doc(formats=[])), "malformed"),
])
def test_a_bad_file_says_why(text, reason):
    with pytest.raises(pd.PlayDataError) as e:
        pd.parse_pack(text)
    assert str(e.value) == reason


def test_unknown_formats_in_a_newer_file_are_ignored():
    p = pd.parse_pack(json.dumps(_doc(formats={"modern": _fmt({SEIZE: [1, 1]}), "brawl": {"x": 1}})))
    assert list(p["formats"]) == ["modern"]


# ── the read-time answer ─────────────────────────────────────────────────────

def test_by_oracle_id_every_format_in_the_file():
    assert pd.play_for(pop(SEIZE, legal()), "Thoughtseize", PACK) == \
        {"modern": S, "legacy": {"tier": "staple", "bp": 3100, "rank": 4}}
    assert pd.play_for(pop(PUSH, legal()), "x", PACK) == \
        {"modern": {"tier": "played", "bp": 210, "rank": 120}, "legacy": {"tier": "unplayed"}}
    assert pd.play_for(pop(SPIDEY, legal()), "x", PACK)["modern"]["tier"] == "fringe"
    assert pd.play_for(pop(ISLAND, legal()), "Island", PACK) == {"modern": {"tier": "basic"}, "legacy": {"tier": "basic"}}


def test_in_no_deck_is_an_answer_read_off_the_scans_own_legality():
    assert pd.play_for(pop(TITHE, legal(legacy="restricted")), "x", PACK) == \
        {"modern": {"tier": "unplayed"}, "legacy": {"tier": "unplayed"}}
    assert pd.play_for(pop(LOTUS, legal(modern="not_legal", legacy="banned")), "Black Lotus", PACK) == \
        {"modern": {"tier": "not_legal"}, "legacy": {"tier": "banned"}}
    # legality not recorded for a format (a scan from before the formats) → not known
    assert pd.play_for(pop(TITHE), "Smothering Tithe", PACK) == {"modern": None, "legacy": None}
    assert pd.play_for(pop(TITHE, {"modern": "legal"}), "x", PACK) == {"modern": {"tier": "unplayed"}, "legacy": None}


def test_membership_beats_a_stale_legality_snapshot():
    assert pd.play_for(pop(SEIZE, legal(modern="not_legal")), "Thoughtseize", PACK)["modern"] == S


def test_old_scans_fall_back_to_the_name_never_an_ambiguous_one():
    assert pd.play_for(pop(formats=legal()), "Thoughtseize", PACK)["modern"] == S
    assert pd.play_for(pop(formats=legal()), "THOUGHTSEIZE ", PACK)["modern"] == S
    assert pd.play_for(pop(formats=legal()), "Fable of the Mirror-Breaker // Reflection of Kiki-Jiki",
                       PACK)["modern"]["bp"] == 199
    assert pd.play_for(None, "Island", PACK) == {"modern": {"tier": "basic"}, "legacy": {"tier": "basic"}}
    # Lightning Bolt is in the file by oracle_id, but by NAME it is ambiguous
    assert pd.play_for(pop(formats=legal()), "Lightning Bolt", PACK) == {"modern": None, "legacy": None}
    assert pd.play_for(pop(BOLT, legal()), "Lightning Bolt", PACK)["modern"]["bp"] == 2560
    # a legal card no deck runs, found by name only
    assert pd.play_for(pop(formats=legal()), "Smothering Tithe", PACK) == \
        {"modern": {"tier": "unplayed"}, "legacy": {"tier": "unplayed"}}


def test_nothing_to_go_on_or_no_file():
    assert pd.play_for(pop(formats=legal()), None, PACK) == {"modern": None, "legacy": None}
    assert pd.play_for(pop(formats=legal()), "  ", PACK) == {"modern": None, "legacy": None}
    assert pd.play_for(pop(SEIZE, legal()), "Thoughtseize", None) is None


def test_the_scan_answer_reads_the_same_printing_as_the_popularity_pill():
    scan = {"candidates": [{"name": "Thoughtseize", "popularity": pop(SEIZE, legal())},
                           {"name": "Fatal Push", "popularity": pop(PUSH, legal())}]}
    assert pd.scan_play(scan, PACK)["modern"]["bp"] == 2689
    scan["selection"] = {"name": "Fatal Push", "popularity": pop(PUSH, legal())}
    assert pd.scan_play(scan, PACK)["modern"]["bp"] == 210
    # a selection without popularity (filed before the feature): the pill falls
    # back to the top candidate's popularity, and so does this
    scan["selection"] = {"name": "Fatal Push", "popularity": None}
    assert pd.scan_play(scan, PACK)["modern"]["bp"] == 2689
    # nothing but the read: the card is known by name, its legality is not
    assert pd.scan_play({"candidates": [], "card_read": {"name": "Thoughtseize"}}, PACK)["modern"] == S
    assert pd.scan_play({"candidates": []}, PACK) == {"modern": None, "legacy": None}


def test_flag_adds_a_key_only_while_there_is_data():
    scan = {"candidates": [{"name": "Thoughtseize", "popularity": pop(SEIZE, legal())}]}
    assert "play" not in pd.flag_play(dict(scan), None)
    assert pd.flag_play(dict(scan), PACK)["play"]["modern"]["tier"] == "staple"


def test_status():
    assert pd.pack_status(None) == {"loaded": False}
    st = pd.pack_status(PACK)
    assert st["loaded"] is True and st["as_of"] == "2026-11-10" and st["window_days"] == 60
    assert list(st["formats"]) == ["modern", "legacy"]
    assert st["formats"]["modern"] == {"decks": 16831, "events": 256, "played": 5,
                                       "sources": {"mtgo": {"decks": 6146, "events": 177, "weighted": True},
                                                   "paper": {"decks": 10685, "events": 79, "weighted": True}}}


def test_summarize_carries_the_oracle_id_and_premodern_legality():
    out = summarize({"oracle_id": SEIZE, "edhrec_rank": 4442, "legalities": {"premodern": "legal"}})
    assert out["oracle_id"] == SEIZE and out["formats"]["premodern"] == "legal"
    rev = {"layout": "reversible_card", "edhrec_rank": 1, "card_faces": [{"oracle_id": "sol"}, {"oracle_id": "sol"}]}
    assert summarize(rev)["oracle_id"] == "sol"
    assert "oracle_id" not in summarize({"edhrec_rank": 1})
    assert list(summarize({"oracle_id": "x"}))[-1] == "oracle_id"


# ── the file the reference server reads ──────────────────────────────────────

def test_the_file_source_rereads_on_change_and_keeps_the_last_good_one(tmp_path):
    f = tmp_path / "play-data.json"
    src = pd.PlayDataFile(f)
    assert src.current() is None
    f.write_text(json.dumps(_doc()), encoding="utf-8")
    assert src.current()["as_of"] == "2026-11-10"
    f.write_text("{broken", encoding="utf-8")
    os.utime(f, ns=(time.time_ns() + 10**9, time.time_ns() + 10**9))
    assert src.current()["as_of"] == "2026-11-10" and src.error == "not JSON"
    f.write_text(json.dumps(_doc(as_of="2026-11-17")), encoding="utf-8")
    os.utime(f, ns=(time.time_ns() + 2 * 10**9, time.time_ns() + 2 * 10**9))
    assert src.current()["as_of"] == "2026-11-17" and src.error is None


def test_the_server_is_off_without_a_file(monkeypatch):
    monkeypatch.delenv("PLAY_DATA_FILE", raising=False)
    assert create_app.__globals__["_default_play_data"]() is None


class _Fixed:
    def __init__(self, pack):
        self.pack = pack

    def current(self):
        return self.pack


def test_scan_reads_carry_play_evaluated_at_read_time(tmp_path):
    store = ScanStore(str(tmp_path / "s.db"))
    sid = store.create_scan(identified=True, card_read={"name": "Thoughtseize"}, confidence={},
                            candidates=[{"id": "p1", "name": "Thoughtseize",
                                         "popularity": pop(SEIZE, legal())}])["id"]
    src = _Fixed(None)
    app = create_app(pipeline_factory=lambda: None, store=store, f2f=object(),
                     scan_images_dir=tmp_path, auto_sweep_interval=None, play_data=src)
    c = TestClient(app)
    assert "play" not in c.get("/api/scans").json()[0]
    assert "play" not in c.get(f"/api/scans/{sid}").json()
    assert c.get("/api/play-data").json() == {"loaded": False}
    src.pack = PACK
    assert c.get("/api/scans").json()[0]["play"]["modern"] == S
    assert c.get(f"/api/scans/{sid}").json()["play"]["legacy"]["rank"] == 4
    assert c.get("/api/play-data").json()["formats"]["legacy"]["decks"] == 5768
    # never stored
    assert "play" not in store.get_scan(sid)


# ── the builder (CI) ─────────────────────────────────────────────────────────

AS_OF = dt.date(2026, 11, 10)


def _sources(tmp_path, mtgo_decks, paper_decks, fmt="Modern"):
    mtgo, paper = tmp_path / "modo", tmp_path / "fbet"
    for i, (date, decks) in enumerate(mtgo_decks):
        _write(mtgo, f"Tournaments/MTGO/{date[:4]}/{date[5:7]}/{date[8:]}/{fmt.lower()}-challenge-{i}.json",
               _event(date, decks, fmt))
    for i, (date, decks, site) in enumerate(paper_decks):
        _write(paper, f"Tournaments/{site}/{date[:4]}/{date[5:7]}/{date[8:]}/event-{fmt}-{i}.json",
               _event(date, decks, fmt))
    return mtgo, paper


def _build(mtgo, paper, **kw):
    kw.setdefault("min_format_decks", 1)
    kw.setdefault("min_format_cards", 1)
    return pd.build_pack(pd.OracleIndex(ORACLE), mtgo, paper, AS_OF, built_at="B", **kw)


def test_builder_equal_weight_rounding_ranks_and_window(tmp_path, monkeypatch):
    monkeypatch.setattr(pd, "MIN_SOURCE_DECKS", 1)
    mtgo, paper = _sources(tmp_path, [
        ("2026-11-10", [(["Thoughtseize", "Island"], ["Fatal Push"]), (["Lightning Bolt"], [])]),
        ("2026-11-11", [(["Thoughtseize"], [])]),          # after as_of: out
    ], [
        ("2026-10-01", [(["Thoughtseize"], []), (["Fatal Push"], []), (["Fatal Push"], ["Thoughtseize"])], "MTGmelee"),
        ("2026-10-02", [(["Fable of the Mirror-Breaker"], [])], "Topdeck"),
    ])
    pack = _build(mtgo, paper)
    m = pack["formats"]["modern"]
    assert m["sources"] == {"mtgo": {"decks": 2, "events": 1, "weighted": True},
                            "paper": {"decks": 4, "events": 2, "weighted": True}}
    assert m["decks"] == 6 and m["events"] == 3
    c = m["cards"]
    # Thoughtseize: MTGO 1/2, paper 2/4 → 50 % → 5000 bp, rank 1
    assert c[SEIZE] == [5000, 1]
    # Fatal Push: MTGO 1/2 (sideboard counts), paper 2/4 → 5000 bp, tied at rank 1
    assert c[PUSH] == [5000, 1]
    # Lightning Bolt: MTGO 1/2, paper 0 → 2500; Fable: paper 1/4 → 1250 (face name resolves)
    assert c[BOLT] == [2500, 3] and c[FABLE] == [1250, 4]
    assert ISLAND not in c and pack["basic"] == [ISLAND]
    assert "lightning bolt" in pack["ambiguous"] and "lightning bolt" not in pack["names"]
    assert pack["names"]["island"] == ISLAND and pack["names"]["reflection of kiki-jiki"] == FABLE
    # every other format had no decks: left out, and said so
    assert list(pack["formats"]) == ["modern"]
    assert pack["skipped"]["legacy"] == {"decks": 0, "reason": "too few decks"}


def test_each_format_counts_its_own_decks(tmp_path, monkeypatch):
    monkeypatch.setattr(pd, "MIN_SOURCE_DECKS", 1)
    root = tmp_path / "modo"
    _write(root, "Tournaments/MTGO/2026/11/01/modern-league-1.json",
           _event("2026-11-01", [(["Thoughtseize"], []), (["Fatal Push"], [])], "Modern"))
    _write(root, "Tournaments/MTGO/2026/11/01/legacy-league-1.json",
           _event("2026-11-01", [(["Thoughtseize"], [])], "Legacy"))
    _write(root, "Tournaments/MTGO/2026/11/01/premodern-league-1.json",
           _event("2026-11-01", [(["Lightning Bolt"], [])], "Premodern"))
    _write(root, "Tournaments/MTGO/2026/11/01/commander-1.json",
           _event("2026-11-01", [(["Smothering Tithe"], [])], "Commander"))      # not a format here
    pack = _build(root, None)
    assert list(pack["formats"]) == ["modern", "legacy", "premodern"]
    assert pack["formats"]["modern"]["cards"] == {PUSH: [5000, 1], SEIZE: [5000, 1]}
    assert pack["formats"]["legacy"]["cards"] == {SEIZE: [10000, 1]}
    assert pack["formats"]["premodern"]["cards"] == {BOLT: [10000, 1]}
    assert TITHE not in pack["names"].values()


def test_the_window_edges(tmp_path, monkeypatch):
    monkeypatch.setattr(pd, "MIN_SOURCE_DECKS", 1)
    mtgo, _ = _sources(tmp_path, [("2026-09-11", [(["Thoughtseize"], [])]),       # = as_of − 60: out
                                  ("2026-09-12", [(["Fatal Push"], [])]),         # first day in
                                  ("2026-11-10", [(["Fatal Push"], [])])], [])
    m = _build(mtgo, None)["formats"]["modern"]
    assert SEIZE not in m["cards"] and m["cards"][PUSH] == [10000, 1] and m["decks"] == 2


def test_rounding_is_half_up_and_a_seen_card_is_never_zero(tmp_path, monkeypatch):
    monkeypatch.setattr(pd, "MIN_SOURCE_DECKS", 1)
    # 1 in 3 = 3333.33 → 3333; 2 in 3 = 6666.67 → 6667
    decks = [(["Thoughtseize"], [])] + [(["Fatal Push"], [])] * 2
    mtgo, _ = _sources(tmp_path, [("2026-11-01", decks)], [])
    c = _build(mtgo, None)["formats"]["modern"]["cards"]
    assert c[SEIZE] == [3333, 2] and c[PUSH] == [6667, 1]
    many = [(["Fatal Push"], [])] * 20001 + [(["Thoughtseize"], [])]
    mtgo2, _ = _sources(tmp_path / "b", [("2026-11-01", many)], [])
    assert _build(mtgo2, None)["formats"]["modern"]["cards"][SEIZE] == [1, 2]        # 0.49 bp → never 0
    half = [(["Thoughtseize"], [])] + [(["Fatal Push"], [])] * 7999        # 1/8000 = 1.25 bp → 1
    mtgo3, _ = _sources(tmp_path / "c", [("2026-11-01", half)], [])
    assert _build(mtgo3, None)["formats"]["modern"]["cards"][SEIZE][0] == 1
    half2 = [(["Thoughtseize"], [])] * 3 + [(["Fatal Push"], [])] * 19997  # 3/20000 = 1.5 bp → 2 (half up)
    mtgo4, _ = _sources(tmp_path / "d", [("2026-11-01", half2)], [])
    assert _build(mtgo4, None)["formats"]["modern"]["cards"][SEIZE][0] == 2


def test_a_thin_source_is_left_out_of_that_formats_mean(tmp_path, monkeypatch):
    monkeypatch.setattr(pd, "MIN_SOURCE_DECKS", 3)
    mtgo, paper = _sources(tmp_path, [("2026-11-01", [(["Thoughtseize"], [])] * 3)],
                           [("2026-11-01", [(["Fatal Push"], [])], "MTGmelee")])
    m = _build(mtgo, paper)["formats"]["modern"]
    assert m["sources"]["paper"] == {"decks": 1, "events": 1, "weighted": False}
    assert m["decks"] == 3 and PUSH not in m["cards"] and m["cards"][SEIZE] == [10000, 1]


def test_thin_formats_are_left_out_and_nothing_left_refuses(tmp_path, monkeypatch):
    monkeypatch.setattr(pd, "MIN_SOURCE_DECKS", 1)
    mtgo, _ = _sources(tmp_path, [("2026-11-01", [(["Thoughtseize"], [])])], [])
    with pytest.raises(pd.PlayDataError, match="no format has 2 decks"):
        _build(mtgo, None, min_format_decks=2)
    with pytest.raises(pd.PlayDataError):
        _build(mtgo, None, min_format_cards=2)


def test_only_known_formats_and_only_these_folders(tmp_path, monkeypatch):
    monkeypatch.setattr(pd, "MIN_SOURCE_DECKS", 1)
    mtgo, paper = _sources(tmp_path, [("2026-11-01", [(["Thoughtseize"], [])])], [])
    _write(mtgo, "Tournaments/MTGO/2026/11/01/duel-commander-1.json",
           _event("2026-11-01", [(["Fatal Push"], [])], "Duel Commander"))
    _write(paper, "Tournaments/MTGO/2026/11/01/modern-x.json", _event("2026-11-01", [(["Fatal Push"], [])]))
    _write(paper, "Tournaments/Manatrader/2026/11/01/modern-y.json", _event("2026-11-01", [(["Fatal Push"], [])]))
    pack = _build(mtgo, paper)
    assert PUSH not in pack["formats"]["modern"]["cards"] and pack["formats"]["modern"]["decks"] == 1


def test_the_build_lookup_takes_only_an_exact_printed_name_and_asks_once(tmp_path, monkeypatch):
    monkeypatch.setattr(pd, "MIN_SOURCE_DECKS", 1)
    root = tmp_path / "modo"
    deck = [(["Kavaero, Mind-Bitten", "Thoughtseize"], ["Agujas"])]
    _write(root, "Tournaments/MTGO/2026/11/01/modern-1.json", _event("2026-11-01", deck, "Modern"))
    _write(root, "Tournaments/MTGO/2026/11/01/legacy-1.json", _event("2026-11-01", deck, "Legacy"))
    asked = []

    def lookup(name):
        asked.append(name)
        if name == "Kavaero, Mind-Bitten":
            return {"name": "Superior Spider-Man", "printed_name": "Kavaero, Mind-Bitten", "oracle_id": SPIDEY}
        return {"name": "Fatal Push", "oracle_id": PUSH}        # a fuzzy guess: refused
    pack = _build(root, None, lookup=lookup)
    assert asked == ["Kavaero, Mind-Bitten", "Agujas"]          # once each, across formats
    assert pack["formats"]["legacy"]["cards"][SPIDEY] == [10000, 1]
    assert all(PUSH not in f["cards"] for f in pack["formats"].values())
    assert pack["unmatched_names"] == 1 and pack["unmatched_entries"] == 2


def test_front_cards_and_tokens_never_shadow_a_real_card():
    idx = pd.OracleIndex(ORACLE)
    assert idx.resolve("Fable of the Mirror-Breaker", "modern") == FABLE
    assert idx.resolve("Lightning Bolt", "modern") == BOLT              # exact full name wins
    assert idx.resolve("Goblin", "modern") is None and idx.resolve("Inferno", "modern") is None


def test_a_name_two_cards_share_resolves_to_the_one_legal_in_the_decks_format():
    idx = pd.OracleIndex(ORACLE + [_card("pyp-mkm", "Pick Your Poison"),
                                   _card("pyp-cmb2", "Pick Your Poison", **{k: "not_legal" for k in pd.FORMAT_KEYS})])
    assert idx.resolve("Pick Your Poison", "modern") == "pyp-mkm"


def test_the_file_is_byte_stable_and_parses(tmp_path, monkeypatch):
    monkeypatch.setattr(pd, "MIN_SOURCE_DECKS", 1)
    mtgo, _ = _sources(tmp_path, [("2026-11-01", [(["Thoughtseize"], ["Island"])])], [])
    a, b = pd.dump_pack(_build(mtgo, None)), pd.dump_pack(_build(mtgo, None))
    assert a == b and a.endswith("\n")
    p = pd.parse_pack(a)
    assert p["formats"]["modern"]["cards"][SEIZE] == (10000, 1) and ISLAND in p["basic"]
    assert pd.play_for(pop(formats=legal()), "Island", p) == {"modern": {"tier": "basic"}}
    assert "Modern 1" in pd.summary_line(_build(mtgo, None))


def test_oracle_bulk_in_both_formats(tmp_path):
    arr = tmp_path / "a.json"
    arr.write_text(json.dumps(ORACLE[:2]), encoding="utf-8")
    gz = tmp_path / "b.jsonl.gz"
    with gzip.open(gz, "wt", encoding="utf-8") as fh:
        for c in ORACLE[:2]:
            fh.write(json.dumps(c) + "\n")
    assert [c["name"] for c in pd.load_oracle_entries(arr)] == ["Lightning Bolt", "Thoughtseize"]
    assert [c["name"] for c in pd.load_oracle_entries(gz)] == ["Lightning Bolt", "Thoughtseize"]
