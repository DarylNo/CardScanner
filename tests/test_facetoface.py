"""Tests for the Face to Face pricing client, driven by captured JSON fixtures."""

import copy
import json
from pathlib import Path
from urllib.parse import unquote

import pytest

from mtg_card_scanner.facetoface import (
    FaceToFaceClient,
    F2FPrice,
    F2FUnavailableError,
    _norm_collector,
    _parse_title_brackets,
    _sku_matches_set,
)

_FIX = Path(__file__).parent / "fixtures" / "facetoface"
# Live storefront answers captured 2026-11-04 (the List investigation),
# trimmed to the fields the client reads; one file per URL: {"url", "body"}.
_CAPTURED = _FIX / "captured"


def _fixture_get_json(url: str):
    """Serve captured fixtures by URL shape, mimicking the live endpoints."""
    if "/search/suggest.json" in url:
        return json.loads((_FIX / "suggest_lightning_bolt.json").read_text())
    if "/products/" in url:
        handle = url.rsplit("/products/", 1)[1].rsplit(".json", 1)[0]
        path = _FIX / f"product_{handle}.json"
        if not path.exists():
            raise FileNotFoundError(f"no product fixture for {handle}")
        return json.loads(path.read_text())
    raise AssertionError(f"unexpected url: {url}")


@pytest.fixture
def client():
    return FaceToFaceClient(get_json=_fixture_get_json)


def test_parse_title_brackets_full():
    parts = _parse_title_brackets("Lightning Bolt [149] [Magic 2011] [Non-Foil]")
    assert parts == {"collector": "149", "set_name": "Magic 2011", "foil_label": "Non-Foil"}


def test_parse_title_brackets_promo_without_collector():
    parts = _parse_title_brackets("Lightning Bolt [MagicFest 2019] [Foil]")
    assert parts["collector"] == ""
    assert parts["set_name"] == "MagicFest 2019"
    assert parts["foil_label"] == "Foil"


def test_sku_matches_set():
    # old format: set code at segment 2
    assert _sku_matches_set("M-M11-Lightning_-149-NM-NF", "m11")
    # current format: set code at segment 3
    assert _sku_matches_set("SIN-MTG-CLB-309-ENG-NM-NF", "clb")
    assert not _sku_matches_set("SIN-MTG-CLB-309-ENG-NM-NF", "m11")
    assert not _sku_matches_set("", "clb")
    assert not _sku_matches_set("SIN-MTG-CLB-309-ENG-NM-NF", "")


def test_get_price_matches_exact_printing_by_set_and_collector(client):
    # m10 #146 must resolve to the Magic 2010 product, not Magic 2011 (#149).
    price = client.get_price("Lightning Bolt", "m10", "146", foil=False)
    assert isinstance(price, F2FPrice)
    assert price.handle == "lightning-bolt-146-magic-2010-non-foil"
    assert price.conditions == {"NM": 3.49, "PL": 2.79}
    assert price.url.endswith("/products/lightning-bolt-146-magic-2010-non-foil")


def test_get_price_disambiguates_same_name_different_set(client):
    price = client.get_price("Lightning Bolt", "m11", "149", foil=False)
    assert price.handle == "lightning-bolt-149-magic-2011-non-foil"
    assert price.conditions["NM"] == 3.49


def test_price_for_mx_condition_mapping(client):
    price = client.get_price("Lightning Bolt", "m10", "146", foil=False)
    assert price.price_for_mx_condition("NM") == 3.49
    # LP maps to F2F "PL"
    assert price.price_for_mx_condition("LP") == 2.79
    # MP/HP/DMG fall back through PL when no exact grade exists
    assert price.price_for_mx_condition("MP") == 2.79


def test_get_price_foil_mismatch_returns_none(client):
    # There is no foil Magic 2010 #146 product in the fixture -> no match.
    price = client.get_price("Lightning Bolt", "m10", "146", foil=True)
    assert price is None


def test_get_price_unknown_name_returns_none():
    empty = FaceToFaceClient(get_json=lambda url: {"resources": {"results": {"products": []}}})
    assert empty.get_price("Nonexistent Card", "xxx", "1", foil=False) is None


def test_collector_collision_across_sets_does_not_return_wrong_set():
    """Collector #141 exists in both Masters 25 (m25) and Clue Edition (clu).

    A lookup for the clu printing must NOT return the m25 price just because the
    collector number + foil match — the SKU set code disproves it.
    """
    def get_json(url):
        if "/search/suggest.json" in url:
            return {"resources": {"results": {"products": [
                {"title": "Lightning Bolt [141] [Masters 25] [Non-Foil]",
                 "handle": "lightning-bolt-141-masters-25-non-foil"},
            ]}}}
        # only the m25 product exists on F2F
        return {"product": {"handle": "lightning-bolt-141-masters-25-non-foil",
                            "variants": [
                                {"option1": "NM", "price": "9.99", "sku": "M-M25-Lightning_-141-NM-NF"},
                            ]}}
    c = FaceToFaceClient(get_json=get_json)
    # clu #141 has no F2F listing -> must be None, not the m25 price
    assert c.get_price("Lightning Bolt", "clu", "141", foil=False) is None
    # m25 #141 resolves correctly
    m25 = c.get_price("Lightning Bolt", "m25", "141", foil=False)
    assert m25 is not None and m25.conditions == {"NM": 9.99}


def test_price_cache_expires_after_ttl(tmp_path, monkeypatch):
    """Cached prices previously lived forever; they must refetch after the TTL."""
    import hashlib
    import os
    import time as time_mod

    from mtg_card_scanner import facetoface as f2f_mod

    calls = []

    class FakeResp:
        status_code = 200
        def raise_for_status(self): pass
        def json(self): return {"fetch": len(calls)}

    class FakeSession:
        def __init__(self): self.headers = {}
        def get(self, url, timeout=None):
            calls.append(url)
            return FakeResp()

    monkeypatch.setattr(f2f_mod, "_new_session", lambda: FakeSession())
    get_json = f2f_mod._default_get_json(tmp_path)

    assert get_json("http://x") == {"fetch": 1}
    assert get_json("http://x") == {"fetch": 1}     # fresh cache — no refetch
    assert len(calls) == 1

    # Age the cache file past the TTL — the next call must hit the network.
    key = hashlib.sha1(b"http://x").hexdigest()
    old = time_mod.time() - f2f_mod._CACHE_TTL - 10
    os.utime(tmp_path / f"{key}.json", (old, old))
    assert get_json("http://x") == {"fetch": 2}
    assert len(calls) == 2


def test_unreachable_storefront_raises_not_none():
    """A failed fetch must raise F2FUnavailableError — returning None meant
    'confirmed unlisted' and froze transient blips into permanent no-listing
    markers (a live Mold Folk listing was marked not-found forever)."""
    import pytest as _pytest
    from mtg_card_scanner.facetoface import F2FUnavailableError
    down = FaceToFaceClient(get_json=lambda url: None)
    with _pytest.raises(F2FUnavailableError):
        down.get_price("Mold Folk", "clb", "133", foil=False)


def test_429_backs_off_and_recovers(tmp_path, monkeypatch):
    """Shopify's rate limit must be waited out, not treated as a failure —
    persistent 429s made whole cards 'unavailable' every sweep."""
    from mtg_card_scanner import facetoface as f2f_mod

    sleeps = []
    monkeypatch.setattr(f2f_mod.time, "sleep", lambda s: sleeps.append(s))
    calls = []

    class Resp:
        def __init__(self, code):
            self.status_code = code
            self.headers = {}
        def raise_for_status(self): pass
        def json(self): return {"ok": True}

    class Sess:
        def __init__(self): self.headers = {}
        def get(self, url, timeout=None):
            calls.append(url)
            return Resp(429 if len(calls) < 2 else 200)

    monkeypatch.setattr(f2f_mod, "_new_session", lambda: Sess())
    get_json = f2f_mod._default_get_json(tmp_path)
    assert get_json("http://x") == {"ok": True}
    assert len(calls) == 2                      # one 429 (waited out), then success
    assert any(s >= 2.0 for s in sleeps)        # real backoff, not the 0.8s blip retry


def test_adaptive_pacing_slow_start_speedup_and_backoff(tmp_path, monkeypatch):
    """Slow start at 1/2s; successes speed the pace up toward the floor;
    a 429 storm slows it multiplicatively toward the ceiling."""
    from mtg_card_scanner import facetoface as m
    monkeypatch.setattr(m.time, "sleep", lambda s: None)
    codes = {"value": 200}

    class Resp:
        def __init__(self):
            self.status_code = codes["value"]
            self.headers = {}
        def raise_for_status(self): pass
        def json(self): return {"ok": True}

    class Sess:
        def __init__(self): self.headers = {}
        def get(self, url, timeout=None): return Resp()

    monkeypatch.setattr(m, "_new_session", lambda: Sess())
    get_json = m._default_get_json(tmp_path)
    assert get_json.current_delay() == m._START_DELAY

    for i in range(5):                       # distinct URLs to dodge the cache
        get_json(f"http://ok/{i}")
    sped_up = get_json.current_delay()
    assert sped_up < m._START_DELAY

    codes["value"] = 429                     # storm: every attempt 429s
    assert get_json("http://throttled") is None
    assert get_json.current_delay() > sped_up
    assert get_json.current_delay() <= m._CEIL_DELAY


def test_interrupt_aborts_fetch_without_touching_network(tmp_path, monkeypatch):
    """A fired interrupt makes get_json bail before any request — this is
    what lets Stop cut through minutes of 429 backoff instantly."""
    import threading as _threading
    from mtg_card_scanner import facetoface as m
    calls = []

    class Sess:
        def __init__(self): self.headers = {}
        def get(self, url, timeout=None):
            calls.append(url)
            raise AssertionError("must not fetch after interrupt")

    monkeypatch.setattr(m, "_new_session", lambda: Sess())
    evt = _threading.Event()
    evt.set()
    get_json = m._default_get_json(tmp_path, evt)
    assert get_json("http://x") is None
    assert calls == []


# ── The List and variant titles (live F2F answers, 2026-11-04) ────────────────
#
# A List card is filed by F2F as "Name [A25-141] [The List] [Non-Foil]", but its
# SKU's set segment depends on the release wave: MB1 (2019-11), FMB1 (2020-03
# foils), PLIST (2020-09 → 2024-02), PLST (2024-08+). ~33 % of titles carry
# variant brackets between the collector and the set name, and the set-name
# fallback used to admit ANY product of that set name — other cards included.


class _Replay:
    """get_json over the captured bodies: suggests by QUERY text, products by
    handle. ``stand_ins`` answers an uncaptured query with a captured query's
    body (declared per test); anything else uncaptured fails the test, or —
    with ``missing_product_unavailable`` — answers None (storefront down)."""

    def __init__(self, stand_ins=None, missing_product_unavailable=False):
        self.by_query: dict = {}
        self.by_handle: dict = {}
        for path in sorted(_CAPTURED.glob("*.json")):
            d = json.loads(path.read_text(encoding="utf-8"))
            url, body = d["url"], d["body"]
            if "/search/suggest.json" in url:
                self.by_query[unquote(url.split("?q=", 1)[1].split("&", 1)[0])] = body
            else:
                self.by_handle[url.rsplit("/products/", 1)[1][:-len(".json")]] = body
        self.stand_ins = stand_ins or {}
        self.missing_product_unavailable = missing_product_unavailable
        self.queries: list = []
        self.products: list = []

    def __call__(self, url):
        if "/search/suggest.json" in url:
            q = unquote(url.split("?q=", 1)[1].split("&", 1)[0])
            self.queries.append(q)
            q = self.stand_ins.get(q, q)
            assert q in self.by_query, f"uncaptured suggest {q!r}"
            return copy.deepcopy(self.by_query[q])
        handle = url.rsplit("/products/", 1)[1][:-len(".json")]
        self.products.append(handle)
        if handle not in self.by_handle and self.missing_product_unavailable:
            return None
        assert handle in self.by_handle, f"uncaptured product {handle!r}"
        return copy.deepcopy(self.by_handle[handle])


def _price(replay, *args, **kw):
    p = FaceToFaceClient(get_json=replay).get_price(*args, **kw)
    return None if p is None else (p.handle, p.conditions)


def test_list_skus_of_every_release_wave_confirm_plst():
    for sku in ("SIN-MTG-PLST-AFC-50-ENG-NM-NF",      # 2024-08+
                "SIN-MTG-PLIST-429-ENG-NM-NF",         # 2020-09 → 2024-02
                "M-PLIST-Lightning_-142-NM-NF",
                "M-MB1-Rhystic_St-468-NM-NF",          # 2019-11 wave
                "M-FMB1-Minamo_Sc-118-NM-F"):          # 2020-03 foils
        assert _sku_matches_set(sku, "plst"), sku
    assert not _sku_matches_set("SIN-MTG-A25-141-ENG-NM-NF", "plst")
    assert not _sku_matches_set("SIN-MTG-UPLIST-35-ENG-NM-F", "plst")   # ulst's wave
    # one-way: the other codes keep their exact segment
    assert not _sku_matches_set("SIN-MTG-PLST-AFC-50-ENG-NM-NF", "mb1")
    assert _sku_matches_set("SIN-MTG-CLB-309-ENG-NM-NF", "clb")


def test_ulst_is_the_uplist_sku():
    assert _sku_matches_set("SIN-MTG-UPLIST-35-ENG-NM-F", "ulst")
    assert not _sku_matches_set("M-UST-Earl_of_Sq-108-NM-F", "ulst")
    assert not _sku_matches_set("MP-Earl_of_-PUST-108-NM-F", "ulst")


def test_cmb2_is_the_cmb1_nopw_sku_and_cmb1_rejects_it():
    symbol, no_symbol = "M-CMB1-Slivdrazi_-102-NM-NF", "M-CMB1-NOPW-Slivdrazi_-102-NM-NF"
    assert _sku_matches_set(symbol, "cmb1")
    assert not _sku_matches_set(no_symbol, "cmb1")
    assert _sku_matches_set(no_symbol, "cmb2")
    assert not _sku_matches_set(symbol, "cmb2")
    assert _sku_matches_set("SIN-MTG-CMB2-102-ENG-NM-NF", "cmb2")   # a plain cmb2 segment


def test_collector_is_the_first_bracket_of_a_variant_title():
    assert _parse_title_brackets(
        "Lightning Bolt [401] [Rulebook Showcase] [Commander Legends: Battle for Baldur's Gate] [Non-Foil]"
    ) == {"collector": "401", "set_name": "Commander Legends: Battle for Baldur's Gate",
          "foil_label": "Non-Foil"}
    assert _parse_title_brackets(
        "Earl of Squirrel [108] [UST Set Symbol] [The List: Unfinity Foil Edition] [Foil]"
    )["collector"] == "108"
    assert _parse_title_brackets(
        "Lightning Bolt [0083] [Full Art] [Mountain, Go] [Secret Lair Drop] [Foil]"
    ) == {"collector": "0083", "set_name": "Secret Lair Drop", "foil_label": "Foil"}
    assert _parse_title_brackets("Lightning Bolt [A25-141] [The List] [Non-Foil]")["collector"] == "A25-141"
    # A first bracket that is not a collector number leaves it empty (narrow
    # on purpose: World Championship numbers like mlp315 included).
    assert _parse_title_brackets(
        "Ancient Tomb [mlp315] [Mark Le Pine] [World Championship Decks 1999] [Non-Foil]"
    )["collector"] == ""
    assert _parse_title_brackets("Lightning Bolt [Rulebook Showcase] [Some Set] [Foil]")["collector"] == ""


def test_collector_zero_padding_is_ignored_per_segment():
    # F2F files Ancient Excavation as [PZ2-038]; Scryfall's number is PZ2-38.
    assert _norm_collector("PZ2-038") == _norm_collector("PZ2-38") == "pz2-38"
    assert _norm_collector("0083") == _norm_collector("83") == "83"
    assert _norm_collector("A25-141") == "a25-141"
    assert _norm_collector("000") == "0"
    assert _norm_collector("TAFR-01") == "tafr-1"


@pytest.mark.parametrize("card, want", [
    (("Lightning Bolt", "plst", "A25-141", False, "The List"),        # PLIST wave
     ("lightning-bolt-a25-141-the-list-non-foil", {"NM": 3.49, "PL": 2.79})),
    (("Ancient Tomb", "plst", "TMP-315", False, "The List"),          # PLIST wave
     ("ancient-tomb-tmp-315-the-list-non-foil", {"NM": 199.99, "PL": 179.99})),
    (("Minamo, School at Water's Edge", "plst", "CHK-279", True, "The List"),   # FMB1
     ("minamo-school-at-waters-edge-chk-279-the-list-foil", {"NM": 99.99, "PL": 79.99})),
    (("Rhystic Study", "plst", "PCY-45", False, "The List"),          # MB1 wave
     ("rhystic-study-pcy-45-the-list-non-foil", {"NM": 99.99, "PL": 84.99})),
])
def test_list_card_is_priced_through_its_wave_sku(card, want):
    replay = _Replay()
    assert _price(replay, *card) == want
    assert replay.products == [want[0]]          # one suggest + one product fetch
    assert len(replay.queries) == 1


def test_ulst_card_is_priced_through_the_uplist_sku():
    # Only the bare-name suggest was captured; it answers the first rung too.
    replay = _Replay(stand_ins={
        "Earl of Squirrel 108 The List (Unfinity Foil Edition) foil": "Earl of Squirrel"})
    assert _price(replay, "Earl of Squirrel", "ulst", "108", True,
                  "The List (Unfinity Foil Edition)") == (
        "earl-of-squirrel-108-ust-set-symbol-the-list-unfinity-foil-edition-foil",
        {"NM": 7.99, "PL": 6.39})


def test_cmb2_card_is_priced_through_the_nopw_sku_and_cmb1_keeps_its_own():
    stand = {"Slivdrazi Monstrosity 102 Mystery Booster Playtest Cards 2021": "Slivdrazi Monstrosity",
             "Slivdrazi Monstrosity 102 Mystery Booster Playtest Cards 2019": "Slivdrazi Monstrosity"}
    assert _price(_Replay(stand), "Slivdrazi Monstrosity", "cmb2", "102", False,
                  "Mystery Booster Playtest Cards 2021") == (
        "slivdrazi-monstrosity-no-symbol-102-mystery-booster-playtest-cards-2021-non-foil",
        {"NM": 15.99, "PL": 13.59})
    assert _price(_Replay(stand), "Slivdrazi Monstrosity", "cmb1", "102", False,
                  "Mystery Booster Playtest Cards 2019") == (
        "slivdrazi-monstrosity-symbol-102-mystery-booster-playtest-cards-2019-non-foil",
        {"NM": 54.99, "PL": 46.74})


def test_mortuary_never_files_another_list_cards_price():
    """F2F has no Mortuary [STH-66]; its suggest offers Buried Alive [WTH-63]
    [The List] (a PLST SKU). The set-name fallback filed Buried Alive's $4.99
    on the Mortuary. Only the first rung was captured; its bait answers the
    looser rungs too."""
    replay = _Replay(stand_ins={"Mortuary The List": "Mortuary STH-66 The List",
                                "Mortuary": "Mortuary STH-66 The List"})
    assert _price(replay, "Mortuary", "plst", "STH-66", False, "The List") is None
    assert replay.products == []


def test_unstocked_list_bolt_is_none_without_a_product_fetch():
    """Bolt [CLB-187] on the List is not stocked; every other List Bolt carries
    its own collector, so nothing is a candidate — no product is fetched (an
    alias alone filed PF19-1's $23.99 here)."""
    replay = _Replay()
    assert _price(replay, "Lightning Bolt", "plst", "CLB-187", False, "The List") is None
    assert replay.products == []
    assert replay.queries == ["Lightning Bolt CLB-187 The List", "Lightning Bolt The List",
                              "Lightning Bolt"]


def test_variant_title_collector_picks_the_printing_not_the_suggest_order():
    """Bolt CLB #401 is "[401] [Rulebook Showcase] [CLB] [Non-Foil]": read as
    collector "Rulebook Showcase", it fell to the set-name fallback, which took
    the first CLB Bolt in suggest order — #187 on this (real) answer."""
    replay = _Replay(stand_ins={
        "Lightning Bolt 401 Commander Legends: Battle for Baldur's Gate":
            "Lightning Bolt 187 Commander Legends: Battle for Baldur's Gate"})
    assert _price(replay, "Lightning Bolt", "clb", "401", False,
                  "Commander Legends: Battle for Baldur's Gate") == (
        "lightning-bolt-401-rulebook-showcase-commander-legends-battle-for-baldurs-gate-non-foil",
        {"NM": 3.49, "PL": 2.39})


def test_zero_padded_list_collector_matches_and_no_other_card_is_filed():
    """Ancient Excavation is [PZ2-038] on F2F, PZ2-38 on Scryfall; unmatched,
    the fallback filed Ancient Cornucopia [BIG-16] (a PLST SKU) instead."""
    replay = _Replay(stand_ins={"Ancient Excavation PZ2-38 The List": "Ancient Tomb The List"})
    assert _price(replay, "Ancient Excavation", "plst", "PZ2-38", False, "The List") == (
        "ancient-excavation-pz2-038-the-list-non-foil", {"NM": 1.99, "PL": 1.59})
    assert replay.products == ["ancient-excavation-pz2-038-the-list-non-foil"]


def test_another_cards_variant_title_is_never_fetched_for_a_collector():
    """Uro [93] [White Border] [MB2]: on the Urza's Saga answer the fallback
    took Urza's Saga [114] (same set name, first in order). Now only Uro's own
    product is asked for (not captured: answered as storefront-down)."""
    replay = _Replay(stand_ins={"Uro, Titan of Nature's Wrath 93 Mystery Booster 2":
                                "Urza's Saga 114 Mystery Booster 2"},
                     missing_product_unavailable=True)
    with pytest.raises(F2FUnavailableError):
        FaceToFaceClient(get_json=replay).get_price(
            "Uro, Titan of Nature's Wrath", "mb2", "93", False, "Mystery Booster 2")
    assert replay.products == ["uro-titan-of-natures-wrath-93-white-border-mystery-booster-2-non-foil"]


def test_collectorless_promo_of_the_right_name_still_matches_via_set_name():
    """The fallback is for promos with no collector in the title — it must
    still find them, but only for the card's own name (a decoy of another
    name, first in order, used to win)."""
    products = {
        "lightning-axe-judge-gift-cards-1998-foil": "M-JGP-Lightning_A-NM-F",
        "lightning-bolt-judge-gift-cards-1998-foil": "M-JGP-Lightning_-NM-F",
    }

    def get_json(url):
        if "/search/suggest.json" in url:
            return {"resources": {"results": {"products": [
                {"title": "Lightning Axe [Judge Gift Cards 1998] [Foil]",
                 "handle": "lightning-axe-judge-gift-cards-1998-foil"},
                {"title": "Lightning Bolt [5] [Judge Gift Cards 1998] [Foil]",
                 "handle": "lightning-bolt-5-judge-gift-cards-1998-foil"},
                {"title": "Lightning Bolt [Judge Gift Cards 1998] [Foil]",
                 "handle": "lightning-bolt-judge-gift-cards-1998-foil"},
            ]}}}
        handle = url.rsplit("/products/", 1)[1][:-len(".json")]
        return {"product": {"handle": handle, "variants": [
            {"option1": "NM", "price": "49.99", "sku": products[handle]}]}}

    p = FaceToFaceClient(get_json=get_json).get_price(
        "Lightning Bolt", "jgp", "", foil=True, set_name="Judge Gift Cards 1998")
    assert p is not None and p.handle == "lightning-bolt-judge-gift-cards-1998-foil"
