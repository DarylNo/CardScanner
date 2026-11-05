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
    _title_names_card,
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


# ── The set-name fallback's name check: EQUAL, not "starts with" ─────────────
#
# The fallback compared the title's name part with startswith, so a collector-
# less promo of ANOTHER card whose name merely begins with this card's front
# face was filed: Fire // Ice took Fireball's price, Shock Shockwave's,
# Mountain Mountain Goat's, Fog Fog Bank's. The name part must now EQUAL the
# front face — as a whole, or cut at the first title-side suffix: " //" (the
# back face), " -" / " —" (a language or note), " (" (a variant).


@pytest.mark.parametrize("title, name, want", [
    # another card whose name starts with this one's front face
    ("Fireball [Judge Gift Cards 2005] [Foil]", "Fire // Ice", False),
    ("Shockwave [Promo] [Foil]", "Shock", False),
    ("Mountain Goat [Promo] [Foil]", "Mountain", False),
    ("Fog Bank [Promo] [Foil]", "Fog", False),
    ("Fireball - French [Promo] [Foil]", "Fire // Ice", False),
    ("Ice [Promo] [Foil]", "Fire // Ice", False),
    # the card itself, plain or with a title-side suffix
    ("Lightning Bolt [Judge Gift Cards 1998] [Foil]", "Lightning Bolt", True),
    ("Fire // Ice [Promo] [Foil]", "Fire // Ice", True),
    ("Sol Ring // Sol Ring [1512] [Borderless] [Raining Cats and Dogs] [Secret Lair Drop] [Foil]",
     "Sol Ring", True),
    ("Counterspell - French [54] [Foreign Black Border: French] [Non-Foil]", "Counterspell", True),
    ("Slivdrazi Monstrosity - Symbol [102] [Mystery Booster Playtest Cards 2019] [Non-Foil]",
     "Slivdrazi Monstrosity", True),
    ("Minamo, School at Water's Edge — Foil — ID 20487", "Minamo, School at Water's Edge", True),
    ("Lightning Bolt (Retro) [Promo] [Foil]", "Lightning Bolt", True),
    # a card name that itself holds " (" matches uncut; a hyphen inside a word
    # is no suffix; accents are folded
    ("Erase (Not the Urza's Legacy One) [Unglued] [Non-Foil]", "Erase (Not the Urza's Legacy One)", True),
    ("Lim-Dul's Vault [Alliances Promos] [Non-Foil]", "Lim-Dûl's Vault", True),
    ("Lim-Dul's Vault [Alliances Promos] [Non-Foil]", "Lim", False),
    # an empty front face never matches
    ("_____ [Unhinged Promos] [Foil]", "_____", False),
])
def test_title_names_card_wants_the_front_face_exactly(title, name, want):
    assert _title_names_card(title, name) is want


@pytest.mark.parametrize("name, decoy", [
    ("Fire // Ice", "Fireball"),
    ("Shock", "Shockwave"),
    ("Mountain", "Mountain Goat"),
    ("Fog", "Fog Bank"),
])
def test_set_name_fallback_never_files_a_longer_name_that_starts_with_the_card(name, decoy):
    """The suggest offers only ANOTHER card's collector-less promo of the same
    set name, its SKU confirming the set: nothing may be fetched or filed."""
    set_name = "Judge Gift Cards 2005"
    handle = decoy.lower().replace(" ", "-") + "-judge-gift-cards-2005-foil"
    fetched = []

    def get_json(url):
        if "/search/suggest.json" in url:
            return {"resources": {"results": {"products": [
                {"title": f"{decoy} [{set_name}] [Foil]", "handle": handle}]}}}
        fetched.append(url)
        return {"product": {"handle": handle, "variants": [
            {"option1": "NM", "price": "9.99", "sku": "M-G05-Decoy-NM-F"}]}}

    assert FaceToFaceClient(get_json=get_json).get_price(
        name, "g05", "", foil=True, set_name=set_name) is None
    assert fetched == []


@pytest.mark.parametrize("name, title", [
    ("Fire // Ice", "Fire // Ice [Judge Gift Cards 2005] [Foil]"),
    ("Counterspell", "Counterspell - French [Judge Gift Cards 2005] [Foil]"),
    ("Lightning Bolt", "Lightning Bolt (Retro) [Judge Gift Cards 2005] [Foil]"),
])
def test_set_name_fallback_still_takes_the_cards_own_suffixed_promo(name, title):
    def get_json(url):
        if "/search/suggest.json" in url:
            return {"resources": {"results": {"products": [{"title": title, "handle": "own-promo"}]}}}
        return {"product": {"handle": "own-promo", "variants": [
            {"option1": "NM", "price": "9.99", "sku": "M-G05-Own-NM-F"}]}}

    p = FaceToFaceClient(get_json=get_json).get_price(
        name, "g05", "", foil=True, set_name="Judge Gift Cards 2005")
    assert p is not None and p.handle == "own-promo"


# ── Meld halves, and the collector after a variant bracket (1.1.18) ──────────
#
# Owner, 2026-11-05: "my price checks of cards are failing — Midnight
# Scavengers EMN 96, Graf Rats EMN 91". F2F titles a meld half "Midnight
# Scavengers // Chittering Host [96a] [B] [Eldritch Moon] [Non-Foil]": its
# collector carries a side letter Scryfall's "96" doesn't, so 1.1.17's exact
# collector match missed it, and its set-name fallback (collector-LESS titles
# only) refused it — 1.1.16 had found it only through its name-blind fallback.
# Measured live over 86 printings (both matchers on the same F2F answers):
# 9 regressions, all meld halves (EMN [96a] [B] / [91a] [A] / [15a] [B] /
# [28a] [A] / [130a] [B] / [204a] [A], both finishes; BRO [163a] / [238a]).
# The rule: when no title carries the card's exact collector, ONE title whose
# collector is the card's plus one side letter (the card's ending in a digit)
# and whose name part is "<the card's name> // <another face>" (the card itself
# single-faced) is the candidate; the SKU still confirms the set. Meld-shaped
# on purpose: "one more letter" alone took "Tymaret, Chosen from Death - Serial
# Numbered [148z]" at $299.99 (the real foil is labelled [Halo Foil]).
#
# And the collector is the first bracket BEFORE THE SET NAME that looks like
# one — "[Prerelease] [59s]", "[Promo Pack] [59p]", "[Susan Van Camp] [38a]",
# "[Autumn] [80c]": read as collector-less, those fell to the set-name fallback,
# which admits every same-name version of the set (one SKU code for all), so the
# first one in F2F's order won — right only while F2F listed the queried one
# first (6 of 6 measured cards filed another version with the answer reversed).


def _first_rung_stand_ins(first_rung: str, name: str, set_name: str, foil: bool) -> dict:
    """Only each card's first rung was captured: 1.1.17 found nothing there and
    walked on, so its first-rung answer stands in for the looser rungs."""
    suffix = " foil" if foil else ""
    return {f"{name} {set_name}{suffix}": first_rung, f"{name}{suffix}": first_rung, name: first_rung}


@pytest.mark.parametrize("card, handle, nm", [
    # the owner's two cards
    (("Midnight Scavengers", "emn", "96", False, "Eldritch Moon"),
     "midnight-scavengers-chittering-host-96a-b-eldritch-moon-non-foil", 0.49),
    (("Graf Rats", "emn", "91", False, "Eldritch Moon"),
     "graf-rats-chittering-host-91a-a-eldritch-moon-non-foil", 0.49),
    # every other regression the measurement found: EMN "[Na] [A|B]", both finishes …
    (("Midnight Scavengers", "emn", "96", True, "Eldritch Moon"),
     "midnight-scavengers-chittering-host-96a-b-eldritch-moon-foil", 0.75),
    (("Bruna, the Fading Light", "emn", "15", False, "Eldritch Moon"),
     "bruna-the-fading-light-brisela-voice-of-nightmares-15a-b-eldritch-moon-non-foil", 6.99),
    (("Gisela, the Broken Blade", "emn", "28", False, "Eldritch Moon"),
     "gisela-the-broken-blade-brisela-voice-of-nightmares-28a-a-eldritch-moon-non-foil", 49.99),
    (("Hanweir Garrison", "emn", "130", False, "Eldritch Moon"),
     "hanweir-garrison-hanweir-the-writhing-township-130a-b-eldritch-moon-non-foil", 3.49),
    (("Hanweir Battlements", "emn", "204", False, "Eldritch Moon"),
     "hanweir-battlements-hanweir-the-writhing-township-204a-a-eldritch-moon-non-foil", 3.49),
    # … and BRO's "[163a] [The Brothers' War]" (its SKU has no letter: SIN-MTG-BRO-163)
    (("Phyrexian Dragon Engine", "bro", "163", False, "The Brothers' War"),
     "phyrexian-dragon-engine-mishra-lost-to-phyrexia-163a-the-brothers-war-non-foil", 2.49),
    (("The Mightstone and Weakstone", "bro", "238", False, "The Brothers' War"),
     "the-mightstone-and-weakstone-urza-planeswalker-238a-the-brothers-war-non-foil", 6.99),
])
def test_meld_half_is_priced_from_its_side_lettered_product(card, handle, nm):
    name, set_code, collector, foil, set_name = card
    first = f"{name} {collector} {set_name}" + (" foil" if foil else "")
    replay = _Replay(stand_ins=_first_rung_stand_ins(first, name, set_name, foil))
    got = _price(replay, *card)
    assert got is not None and got[0] == handle
    assert got[1]["NM"] == nm
    assert replay.queries == [first]               # one suggest + one product fetch
    assert replay.products == [handle]


def test_the_meld_result_never_takes_a_halfs_product():
    """Chittering Host (emn 96b) has no product of its own; 1.1.16 filed
    Midnight Scavengers' [96a] on it. Scryfall's 96b ends in a letter, so no
    side letter applies, and the half's title has a collector."""
    replay = _Replay()
    assert _price(replay, "Chittering Host", "emn", "96b", False, "Eldritch Moon") is None
    assert replay.products == []


def test_a_serial_numbered_twin_is_never_a_side_letter():
    """mul 148 foil: the real product is "[148] … [Halo Foil]" (another finish
    label), so nothing carries 148 — "Tymaret, Chosen from Death - Serial
    Numbered [148z] … [Foil]" ($299.99) is one letter more but no meld half."""
    replay = _Replay()
    assert _price(replay, "Tymaret, Chosen from Death", "mul", "148", True,
                  "Multiverse Legends") is None
    assert replay.products == []


def test_the_exact_collector_beats_a_serial_numbered_twin():
    """brr 126 foil: "Wurmcoil Engine - Serial Numbered [126z]" comes FIRST in
    F2F's answer; the exact [126] is the card (1.1.16 filed the $799.99 126z)."""
    replay = _Replay()
    assert _price(replay, "Wurmcoil Engine", "brr", "126", True,
                  "The Brothers' War Retro Artifacts") == (
        "wurmcoil-engine-126-retro-frame-schematic-the-brothers-war-retro-artifacts-foil",
        {"NM": 32.99, "PL": 26.39})
    assert replay.products == ["wurmcoil-engine-126-retro-frame-schematic-the-brothers-war-retro-artifacts-foil"]


@pytest.mark.parametrize("card, handle", [
    # a meld half F2F files with no letter: the exact collector, as before
    (("Urza, Lord Protector", "bro", "225", False, "The Brothers' War"),
     "urza-lord-protector-urza-planeswalker-225-the-brothers-war-non-foil"),
    # a meld promo's "[15as]" is no collector (two letters): the set-name
    # fallback still takes the card's own collector-less title
    (("Bruna, the Fading Light", "pemn", "15s", True, "Eldritch Moon Promos"),
     "bruna-the-fading-light-brisela-voice-of-nightmares-15as-b-eldritch-moon-promos-foil"),
])
def test_meld_controls_keep_their_product(card, handle):
    replay = _Replay()
    got = _price(replay, *card)
    assert got is not None and got[0] == handle
    assert replay.products == [handle]


def test_collector_is_the_first_collector_bracket_before_the_set_name():
    parse = _parse_title_brackets
    assert parse("Lier, Disciple of the Drowned [Prerelease] [59s] "
                 "[Innistrad: Midnight Hunt Promos] [Foil]")["collector"] == "59s"
    assert parse("Lier, Disciple of the Drowned [Promo Pack] [59p] "
                 "[Innistrad: Midnight Hunt Promos] [Foil]")["collector"] == "59p"
    assert parse("Hymn to Tourach [Susan Van Camp] [38a] [Fallen Empires] [Non-Foil]") == {
        "collector": "38a", "set_name": "Fallen Empires", "foil_label": "Non-Foil"}
    assert parse("Mishra's Factory [Autumn] [80c] [Antiquities] [Non-Foil]")["collector"] == "80c"
    assert parse("Mana Confluence [Buy a Box] [721] [Commander Legends] [Foil]")["collector"] == "721"
    # the first bracket still wins when it is one
    assert parse("Midnight Scavengers // Chittering Host [96a] [B] [Eldritch Moon] [Non-Foil]"
                 )["collector"] == "96a"
    assert parse("Lightning Bolt [0083] [Full Art] [Mountain, Go] [Secret Lair Drop] [Foil]"
                 )["collector"] == "0083"
    # never the set name or the foil label, and still narrow: World Championship
    # numbers and a meld promo's "15as" are no collectors
    assert parse("Card [Promo] [123] [Foil]")["collector"] == ""
    assert parse("Card [Promo] [Set] [123]")["collector"] == ""
    assert parse("Ancient Tomb [mlp315] [Mark Le Pine] [World Championship Decks 1999] "
                 "[Non-Foil]")["collector"] == ""
    assert parse("Bruna, the Fading Light // Brisela, Voice of Nightmares [15as] [B] "
                 "[Eldritch Moon Promos] [Foil]")["collector"] == ""


_LIER_59S = "Lier, Disciple of the Drowned 59s Innistrad: Midnight Hunt Promos foil"
_LIER_59P = "Lier, Disciple of the Drowned 59p Innistrad: Midnight Hunt Promos foil"
_HYMN_38A = "Hymn to Tourach 38a Fallen Empires"
_HYMN_38C = "Hymn to Tourach 38c Fallen Empires"
_FACTORY_80B = "Mishra's Factory 80b Antiquities"
_FACTORY_80C = "Mishra's Factory 80c Antiquities"


@pytest.mark.parametrize("card, answer, handle, nm", [
    # each on its own answer (the queried version happens to come first) …
    (("Lier, Disciple of the Drowned", "pmid", "59s", True, "Innistrad: Midnight Hunt Promos"),
     _LIER_59S, "lier-disciple-of-the-drowned-prerelease-59s-innistrad-midnight-hunt-promos-foil", 14.99),
    (("Lier, Disciple of the Drowned", "pmid", "59p", True, "Innistrad: Midnight Hunt Promos"),
     _LIER_59P, "lier-disciple-of-the-drowned-promo-pack-59p-innistrad-midnight-hunt-promos-foil", 4.99),
    (("Hymn to Tourach", "fem", "38a", False, "Fallen Empires"),
     _HYMN_38A, "hymn-to-tourach-susan-van-camp-38a-fallen-empires-non-foil", 0.99),
    (("Mishra's Factory", "atq", "80c", False, "Antiquities"),
     _FACTORY_80C, "mishras-factory-autumn-80c-antiquities-non-foil", 449.99),
    # … and on the OTHER version's answer, where another version comes first
    (("Lier, Disciple of the Drowned", "pmid", "59p", True, "Innistrad: Midnight Hunt Promos"),
     _LIER_59S, "lier-disciple-of-the-drowned-promo-pack-59p-innistrad-midnight-hunt-promos-foil", 4.99),
    (("Lier, Disciple of the Drowned", "pmid", "59s", True, "Innistrad: Midnight Hunt Promos"),
     _LIER_59P, "lier-disciple-of-the-drowned-prerelease-59s-innistrad-midnight-hunt-promos-foil", 14.99),
    (("Hymn to Tourach", "fem", "38c", False, "Fallen Empires"),
     _HYMN_38A, "hymn-to-tourach-quinton-hoover-38c-fallen-empires-non-foil", 1.99),
    (("Hymn to Tourach", "fem", "38a", False, "Fallen Empires"),
     _HYMN_38C, "hymn-to-tourach-susan-van-camp-38a-fallen-empires-non-foil", 0.99),
    (("Mishra's Factory", "atq", "80b", False, "Antiquities"),
     _FACTORY_80C, "mishras-factory-summer-80b-antiquities-non-foil", 349.99),
    (("Mishra's Factory", "atq", "80c", False, "Antiquities"),
     _FACTORY_80B, "mishras-factory-autumn-80c-antiquities-non-foil", 449.99),
])
def test_promo_and_variant_versions_are_told_apart_by_collector_not_order(card, answer, handle, nm):
    """Same name, same set, one SKU code (PMID / FEM / ATQ): only the collector
    after the variant bracket tells them apart. Read as collector-less they fell
    to the set-name fallback, and the first version in F2F's order won
    (Lier 59p filed the 59s's $14.99, Mishra's Factory 80b the 80c's $449.99)."""
    name, set_code, collector, foil, set_name = card
    first = f"{name} {collector} {set_name}" + (" foil" if foil else "")
    replay = _Replay(stand_ins={first: answer})
    got = _price(replay, *card)
    assert got is not None and got[0] == handle
    assert got[1]["NM"] == nm
    assert replay.products == [handle]


def _synthetic_get_json(titles: list, skus: dict, fetched: list):
    """One suggest answer of *titles* for every rung (each handle its title,
    slugged); a product's SKU from *skus* (by title), NM $9.99. Every product
    fetch is recorded in *fetched* (by title)."""
    def handle(t):
        return "".join(ch if ch.isalnum() else "-" for ch in t.lower())

    by_handle = {handle(t): t for t in titles}

    def get_json(url):
        if "/search/suggest.json" in url:
            return {"resources": {"results": {"products": [
                {"title": t, "handle": handle(t)} for t in titles]}}}
        h = url.rsplit("/products/", 1)[1][:-len(".json")]
        fetched.append(by_handle[h])
        return {"product": {"title": by_handle[h], "handle": h, "variants": [
            {"option1": "NM", "price": "9.99", "sku": skus[by_handle[h]]}]}}
    return get_json


def _emn(title_part: str) -> str:
    return f"{title_part} [Eldritch Moon] [Non-Foil]"


@pytest.mark.parametrize("titles", [
    # ANOTHER card's half at the card's number + a letter
    [_emn("Graf Rats // Chittering Host [96a] [A]")],
    # one letter more, but no meld half: a serial-numbered or a single-faced title
    [_emn("Midnight Scavengers - Serial Numbered [96z]")],
    [_emn("Midnight Scavengers [96a]")],
    # another number (a "[96ab]" is no collector at all: the set-name fallback's,
    # like pemn's "[15as]")
    [_emn("Midnight Scavengers // Chittering Host [196a] [B]")],
    [_emn("Midnight Scavengers // Chittering Host [9a] [B]")],
    # a half with no other face
    [_emn("Midnight Scavengers //  [96a] [B]")],
    # TWO lettered halves of the number: ambiguous, never a guess
    [_emn("Midnight Scavengers // Chittering Host [96a] [B]"),
     _emn("Midnight Scavengers // Chittering Host [96b] [B]")],
])
def test_side_letter_takes_only_the_cards_own_unambiguous_half(titles):
    fetched: list = []
    get_json = _synthetic_get_json(titles, {t: "M-EMN-X-96a-NM-NF" for t in titles}, fetched)
    assert FaceToFaceClient(get_json=get_json).get_price(
        "Midnight Scavengers", "emn", "96", False, "Eldritch Moon") is None
    assert fetched == []


def test_the_exact_collector_beats_a_lettered_half():
    half = _emn("Midnight Scavengers // Chittering Host [96a] [B]")
    exact = _emn("Midnight Scavengers [96]")
    fetched: list = []
    get_json = _synthetic_get_json([half, exact], {half: "M-EMN-X-96a-NM-NF", exact: "M-EMN-X-96-NM-NF"},
                                   fetched)
    p = FaceToFaceClient(get_json=get_json).get_price(
        "Midnight Scavengers", "emn", "96", False, "Eldritch Moon")
    assert p is not None and fetched == [exact]


def test_the_sku_still_confirms_a_lettered_half():
    half = _emn("Midnight Scavengers // Chittering Host [96a] [B]")
    fetched: list = []
    get_json = _synthetic_get_json([half], {half: "M-SOI-X-96a-NM-NF"}, fetched)
    assert FaceToFaceClient(get_json=get_json).get_price(
        "Midnight Scavengers", "emn", "96", False, "Eldritch Moon") is None
    assert fetched == [half] * 3                   # asked on each rung, refused by the SKU


def test_a_two_faced_card_never_takes_a_side_letter():
    """A transform / split card's own name holds " // " — a letter after its
    number is another face, never this side of a meld."""
    title = "Delver of Secrets // Insectile Aberration [51a] [Innistrad] [Non-Foil]"
    fetched: list = []
    get_json = _synthetic_get_json([title], {title: "M-ISD-X-51a-NM-NF"}, fetched)
    assert FaceToFaceClient(get_json=get_json).get_price(
        "Delver of Secrets // Insectile Aberration", "isd", "51", False, "Innistrad") is None
    assert fetched == []


@pytest.mark.parametrize("title_collector, collector, want", [
    ("96a", "96", True), ("91A", "91", True), ("096a", "96", True), ("163a", "0163", True),
    ("A25-141a", "A25-141", True),
    ("96", "96", False),                 # equal is the exact match, not a side letter
    ("96ab", "96", False), ("96★", "96", False), ("96†", "96", False), ("9a", "96", False),
    ("196a", "96", False), ("96a", "96a", False), ("96b", "96a", False),
    ("96a", "", False), ("a", "", False),
    ("96a", "9٦", False),                # a Unicode digit is no ASCII digit
])
def test_side_letter_of(title_collector, collector, want):
    from mtg_card_scanner import facetoface as f2fmod
    assert f2fmod._side_letter_of(title_collector, collector) is want


@pytest.mark.parametrize("title, name, want", [
    ("Midnight Scavengers // Chittering Host [96a] [B] [Eldritch Moon] [Non-Foil]",
     "Midnight Scavengers", True),
    ("Phyrexian Dragon Engine // Mishra, Lost to Phyrexia [163a] [The Brothers' War] [Foil]",
     "Phyrexian Dragon Engine", True),
    ("Lorien Revealed // x [60a] [Set] [Foil]", "Lórien Revealed", True),       # accents folded
    ("Graf Rats // Chittering Host [91a] [A] [Eldritch Moon] [Non-Foil]", "Midnight Scavengers", False),
    ("Midnight Scavengers [96a] [Eldritch Moon] [Non-Foil]", "Midnight Scavengers", False),
    ("Midnight Scavengers - Serial Numbered [96z] [Set] [Foil]", "Midnight Scavengers", False),
    ("Midnight Scavengers //  [96a] [Set] [Foil]", "Midnight Scavengers", False),   # no other face
    ("Midnight Scavengers//Chittering Host [96a] [Set] [Foil]", "Midnight Scavengers", False),
    ("Delver of Secrets // Insectile Aberration [51a] [Innistrad] [Non-Foil]",
     "Delver of Secrets // Insectile Aberration", False),                        # the card is two-faced
    ("Delver of Secrets // Insectile Aberration [51a] [Innistrad] [Non-Foil]", "Delver of Secrets", True),
    ("_____ // x [1a] [Set] [Foil]", "_____", False),                            # an empty name
    ("Midnight Scavengers // Chittering Host // x [96a] [Set] [Foil]", "Midnight Scavengers", True),
    ("Chittering Host [96b] // Midnight Scavengers [Set] [Foil]", "Chittering Host", False),
])
def test_title_is_meld_half(title, name, want):
    from mtg_card_scanner import facetoface as f2fmod
    assert f2fmod._title_is_meld_half(title, name) is want
