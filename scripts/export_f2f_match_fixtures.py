"""
Export the F2F MATCHING golden fixture for the Android app's JVM tests.

The phone prices with a port of mtg_card_scanner/facetoface.py's
FaceToFaceClient.get_price (io.github.darylno.cardscanner.f2f.F2fPricer). Its
pacing has its own fixture (export_f2f_pacing_fixture.py); this one pins the
MATCHING — which product a printing resolves to — so the two cannot drift:

  * function tables: the REAL _parse_title_brackets / _norm_collector /
    _sku_matches_set / _norm_name / _title_names_card / _title_is_meld_half /
    _side_letter_of over every product title, collector and SKU in the captured
    storefront answers and the declared synthetic bodies (plus a few
    hand-written edge shapes, listed in EXTRA_* below — inputs only, the
    answers are the reference's). _title_names_card and _title_is_meld_half run
    on FULL titles (brackets and all: the port's cut at "[" is part of what is
    pinned), one per distinct name part, against every name;
  * sessions: the REAL get_price replayed over storefront answers captured live
    on 2026-11-04 and 2026-11-05 (tests/fixtures/facetoface/captured/, one file
    per URL, trimmed to the fields the client reads: the List investigation, then
    the meld halves and lettered promos of the owner's 1.1.17 report — Midnight
    Scavengers EMN 96, Graf Rats EMN 91) and the older m10/m11 Bolt fixture — every
    URL requested, in order, which body answered it, and the result (handle +
    conditions, or null). Where a ladder rung was never captured, the session
    DECLARES a stand-in (another captured answer) and the fixture lists the URL
    under "stand_ins": those answers are real F2F bodies, just for another query.
    Where no capture shows the shape a rule needs (a collector-less promo's
    product, a decoy whose name merely starts with the card's), the session
    DECLARES a SYNTHETIC body (SYNTHETIC below: hand-written, an id that is
    never a URL, a note saying why) — for a suggest rung through "stand_ins",
    for a product fetch through "products" (handle → body id); the fixture
    lists the URLs a synthetic body answered under the session's "synthetic"
    and every synthetic body used, with its note, under the top-level
    "synthetic". Any undeclared uncaptured URL fails the export.

Writes android/app/src/test/resources/f2f/match.json; F2fMatchParityTest replays
(a) through F2fPricer's companion functions and (b) through F2fPricer over a fake
transport answering from the same bodies. Change facetoface.py first, then
regenerate — never hand-edit the fixture.

    python scripts/export_f2f_match_fixtures.py          # (re)write
    python scripts/export_f2f_match_fixtures.py --check  # exit 1 if stale (CI)
"""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from mtg_card_scanner import facetoface as m  # noqa: E402

OUT = ROOT / "android" / "app" / "src" / "test" / "resources" / "f2f" / "match.json"
FIX = ROOT / "tests" / "fixtures" / "facetoface"
CAPTURED = FIX / "captured"
LEGACY_SUGGEST = "tests/fixtures/facetoface/suggest_lightning_bolt.json"   # body id

# Hand-written shapes the captures don't cover (inputs only).
EXTRA_TITLES = [
    "Lightning Bolt [Rulebook Showcase] [Some Set] [Foil]",
    "Card [12★] [Set] [Foil]",
    "Card [1†] [Set] [Foil]",
    "Card [185j] [Core Set 2019] [Non-Foil]",
    "Card [M19-185j] [The List] [Non-Foil]",
    "Card [TAFR-1] [The List] [Foil]",
    "Card [ABCDEF-1] [The List] [Foil]",
    "Card [ABCDEFG-1] [The List] [Foil]",
    "Card [A-B-1] [The List] [Foil]",
    "Card [-1] [The List] [Foil]",
    "Card [1ab] [Set] [Foil]",
    "Card [ 141 ] [Set] [ Foil ]",
    "Card [١٤١] [Set] [Foil]",          # Arabic-Indic digits: not a collector
    "Card [2019 Judge Promo] [Promos] [Foil]",
    "Card [30th Anniversary Edition] [Set] [Non-Foil]",
    "Card [Promos] [Foil]",
    "Card [Foil]",
    "No brackets at all",
    "",
    "Lim-Dul's Vault [Alliances Promos] [Non-Foil]",
    "Fire // Ice [Promo] [Foil]",
    "Fire [Promo] [Foil]",
    "Ice [Promo] [Foil]",
    "_____ [Unhinged Promos] [Foil]",
    "Aether Vial [Promo] [Foil]",
    # The set-name fallback's name check: the name part must EQUAL the front
    # face, whole or cut at the first title-side suffix (" //", " -", " —", " (").
    "Shockwave [Promo] [Foil]",
    "Shock [Promo] [Foil]",
    "Mountain Goat [Promo] [Foil]",
    "Fog Bank [Promo] [Foil]",
    "Fireball - French [Promo] [Foil]",
    "Lightning Bolt (Retro) [Promo] [Foil]",
    "Lightning Bolt  -  French [Promo] [Foil]",
    "Lightning Bolt—Foil [Promo] [Foil]",
    "Lightning Bolt-French [Promo] [Foil]",
    "Lightning Bolt (Retro) - French // x [Promo] [Foil]",
    "Erase (Not the Urza's Legacy One) [Unglued] [Non-Foil]",
    "B.F.M. (Big Furry Monster) [Unglued] [Non-Foil]",
    " - Lightning Bolt [Promo] [Foil]",
    " (Retro) [Promo] [Foil]",
    "// [Promo] [Foil]",
    "Fire // Ice // Extra [Promo] [Foil]",
    # The collector is the first collector-shaped bracket BEFORE the set name
    # (never the set name or the foil label; the first of two wins).
    "Card [Promo] [123] [Foil]",
    "Card [Promo] [Set] [123]",
    "Card [Label] [96a] [97b] [Set] [Foil]",
    "Card [Label] [Other Label] [12★] [Set] [Foil]",
    "Card [Label] [١٢] [Set] [Foil]",
    # _title_is_meld_half: "<the card> // <another face>" before the first "[".
    "Midnight Scavengers //  [96a] [B] [Eldritch Moon] [Non-Foil]",
    "Midnight Scavengers//Chittering Host [96a] [Set] [Foil]",
    "Midnight Scavengers // Chittering Host // x [96a] [Set] [Foil]",
    "Chittering Host [96b] // Midnight Scavengers [Set] [Foil]",
    "Lorien Revealed // x [60a] [Set] [Foil]",
    "_____ // x [1a] [Set] [Foil]",
    " // Midnight Scavengers [96a] [Set] [Foil]",
    "Delver of Secrets // Insectile Aberration [51a] [Innistrad] [Non-Foil]",
]
EXTRA_COLLECTORS = [
    "PZ2-38", "pz2-038", "CLB-187", "STH-66", "JUD-78†", "M19-185j", "0", "00", "000", "",
    "-", "0-0", "A-0", "007a", " 0083 ", "TAFR-01", "1-", "-01", "A25-0141",
]
SET_CODES = ["plst", "plist", "mb1", "fmb1", "ulst", "uplist", "ust", "pust", "cmb1", "cmb2",
             "nopw", "mb2", "clb", "a25", "tmp", "m10", "m11", "PLST", "jgp", "g05", ""]
EXTRA_SKUS = [
    "SIN-MTG-CMB2-102-ENG-NM-NF", "SIN-MTG-A25-141-ENG-NM-NF", "M-M11-Lightning_-149-NM-NF",
    "SIN-MTG-CLB-309-ENG-NM-NF", "m-cmb1-nopw-x-102-nm-nf", "M-CMB1-NOPWX-x-102-NM-NF", "",
    "PLST", "plist",
]
EXTRA_NAMES = ["Fire // Ice", "Lim-Dûl's Vault", "Æther Vial", "Aether Vial", "_____", "Sol Ring",
               "Jötun Grunt", "İstanbul", "Dandân", "ﬁre", "Emeritus of Conflict",
               "Fire", "Fireball", "Shock", "Shockwave", "Mountain", "Mountain Goat", "Fog", "Fog Bank",
               "Erase", "Erase (Not the Urza's Legacy One)", "B.F.M. (Big Furry Monster)", "Lim",
               "Lightning", "Lightning Axe", "Ancient Tomb", "Minamo, School at Water's Edge",
               "Academy on the Bay", "Counterspell // x", "Lórien Revealed",
               "Delver of Secrets // Insectile Aberration", "Delver of Secrets", ""]
# _side_letter_of beyond the generated pairs (every collector seen against
# itself, itself minus its last character, and itself plus "a" / "A" / "★").
EXTRA_SIDE_LETTER = [
    ("96a", "96"), ("91A", "91"), ("096a", "96"), ("163a", "0163"), ("A25-141a", "A25-141"),
    ("96ab", "96"), ("96★", "96"), ("96†", "96"), ("9a", "96"), ("196a", "96"), ("96a", "96a"),
    ("96b", "96a"), ("96a", ""), ("a", ""), ("", ""), ("96a", "9٦"), ("96ä", "96"), ("96ı", "96"),
    ("0a", "0"), (" 96a ", "96"), ("96-a", "96"), ("96İ", "96"),
]

_JGP_BOLT = "lightning-bolt-judge-gift-cards-1998-foil"   # a real handle (the "Lightning Bolt" capture)
_G05_FIREBALL = "fireball-judge-gift-cards-2005-foil"
_MS_96A = "midnight-scavengers-chittering-host-96a-b-eldritch-moon-non-foil"   # real handles
_MS_96A_FOIL = "midnight-scavengers-chittering-host-96a-b-eldritch-moon-foil"
_MS_96_EXACT = "midnight-scavengers-96-eldritch-moon-non-foil"                 # made up
_MS_96A_SOI = "midnight-scavengers-chittering-host-96a-b-eldritch-moon-non-foil-soi"

# Hand-written bodies for shapes no capture shows (see the docstring). Ids are
# never URLs, so an uncaptured URL can only reach one through a declaration.
SYNTHETIC: dict[str, dict] = {
    f"synthetic:product/{_JGP_BOLT}": {
        "note": "the product behind the captured title 'Lightning Bolt [Judge Gift Cards 1998] "
                "[Foil]' (not captured): an old-format SKU naming JGP, prices made up",
        "body": {"product": {"title": "Lightning Bolt [Judge Gift Cards 1998] [Foil]", "handle": _JGP_BOLT,
                             "variants": [
                                 {"sku": "M-JGP-Lightning_-NM-F", "title": "NM", "option1": "NM",
                                  "price": "49.99"},
                                 {"sku": "M-JGP-Lightning_-PL-F", "title": "PL", "option1": "PL",
                                  "price": "39.99"}]}},
    },
    "synthetic:suggest/Fire // Ice": {
        "note": "a suggest for Fire // Ice offering Fireball's collector-less G05 promo (same set "
                "name, foil) beside Fire // Ice's own numbered APC printings",
        "body": {"resources": {"results": {"products": [
            {"title": "Fireball [Judge Gift Cards 2005] [Foil]", "handle": _G05_FIREBALL},
            {"title": "Fire // Ice [128] [Apocalypse] [Foil]", "handle": "fire-ice-128-apocalypse-foil"},
            {"title": "Fire // Ice [128] [Apocalypse] [Non-Foil]", "handle": "fire-ice-128-apocalypse-non-foil"},
        ]}}},
    },
    f"synthetic:product/{_G05_FIREBALL}": {
        "note": "Fireball's G05 promo: an SKU that confirms g05, so only the name check keeps its "
                "price off a Fire // Ice",
        "body": {"product": {"title": "Fireball [Judge Gift Cards 2005] [Foil]", "handle": _G05_FIREBALL,
                             "variants": [
                                 {"sku": "M-G05-Fireball-NM-F", "title": "NM", "option1": "NM",
                                  "price": "19.99"}]}},
    },
    # The meld side letter (_side_letter_of + _title_is_meld_half): shapes no
    # capture shows, every one beside Midnight Scavengers' real number.
    "synthetic:suggest/Midnight Scavengers decoys": {
        "note": "one more letter than 96 but never Midnight Scavengers' half: ANOTHER card's half "
                "at its number, a serial-numbered and a single-faced title, another number, a half "
                "with no other face — and the real half in the other finish",
        "body": {"resources": {"results": {"products": [
            {"title": f"{t} [Eldritch Moon] [{f}]", "handle": h} for t, f, h in [
                ("Graf Rats // Chittering Host [96a] [A]", "Non-Foil", "decoy-graf-rats-96a"),
                ("Midnight Scavengers - Serial Numbered [96z]", "Non-Foil", "decoy-serial-96z"),
                ("Midnight Scavengers [96a]", "Non-Foil", "decoy-single-faced-96a"),
                ("Midnight Scavengers // Chittering Host [196a] [B]", "Non-Foil", "decoy-196a"),
                ("Midnight Scavengers //  [96a] [B]", "Non-Foil", "decoy-no-other-face-96a"),
                ("Midnight Scavengers // Chittering Host [96a] [B]", "Foil", _MS_96A_FOIL)]]}}},
    },
    "synthetic:suggest/Midnight Scavengers two halves": {
        "note": "two side-lettered halves of the card's number (96a, 96b): ambiguous — none is taken",
        "body": {"resources": {"results": {"products": [
            {"title": "Midnight Scavengers // Chittering Host [96a] [B] [Eldritch Moon] [Non-Foil]",
             "handle": _MS_96A},
            {"title": "Midnight Scavengers // Chittering Host [96b] [B] [Eldritch Moon] [Non-Foil]",
             "handle": "midnight-scavengers-chittering-host-96b-b-eldritch-moon-non-foil"}]}}},
    },
    "synthetic:suggest/Midnight Scavengers half and exact": {
        "note": "the real half AFTER a title carrying the exact 96: the exact collector wins",
        "body": {"resources": {"results": {"products": [
            {"title": "Midnight Scavengers // Chittering Host [96a] [B] [Eldritch Moon] [Non-Foil]",
             "handle": _MS_96A},
            {"title": "Midnight Scavengers [96] [Eldritch Moon] [Non-Foil]", "handle": _MS_96_EXACT}]}}},
    },
    f"synthetic:product/{_MS_96_EXACT}": {
        "note": "the exact-collector product beside the half (EMN SKU, prices made up)",
        "body": {"product": {"title": "Midnight Scavengers [96] [Eldritch Moon] [Non-Foil]",
                             "handle": _MS_96_EXACT, "variants": [
                                 {"sku": "M-EMN-Midnight_S-96-NM-NF", "title": "NM", "option1": "NM",
                                  "price": "1.11"}]}},
    },
    "synthetic:suggest/Midnight Scavengers half of another set": {
        "note": "the half's title, its product filed under another set's SKU: the SKU still refuses it",
        "body": {"resources": {"results": {"products": [
            {"title": "Midnight Scavengers // Chittering Host [96a] [B] [Eldritch Moon] [Non-Foil]",
             "handle": _MS_96A_SOI}]}}},
    },
    f"synthetic:product/{_MS_96A_SOI}": {
        "note": "a half product whose SKU names SOI, not EMN (prices made up)",
        "body": {"product": {"title": "Midnight Scavengers // Chittering Host [96a] [B] [Eldritch Moon] "
                                      "[Non-Foil]", "handle": _MS_96A_SOI, "variants": [
                                 {"sku": "M-SOI-Midnight_S-96a-NM-NF", "title": "NM", "option1": "NM",
                                  "price": "2.22"}]}},
    },
}

# Each session: get_price's arguments, stand-ins for ladder rungs that were
# never captured (query text → the captured query, or a SYNTHETIC id, whose
# answer stands in) and declared answers for product fetches (handle → body id).
SESSIONS: list[dict] = [
    {"id": "bolt_plst_a25_141", "note": "List, PLIST-wave SKU (SIN-MTG-PLIST-429)",
     "card": ["Lightning Bolt", "plst", "A25-141", False, "The List"]},
    {"id": "tomb_plst_tmp_315", "note": "List, PLIST-wave SKU (SIN-MTG-PLIST-574)",
     "card": ["Ancient Tomb", "plst", "TMP-315", False, "The List"]},
    {"id": "minamo_plst_chk_279_foil", "note": "List foil, FMB1-wave SKU (M-FMB1-Minamo_Sc-118)",
     "card": ["Minamo, School at Water's Edge", "plst", "CHK-279", True, "The List"]},
    {"id": "rhystic_plst_pcy_45", "note": "List, MB1-wave SKU (M-MB1-Rhystic_St-468)",
     "card": ["Rhystic Study", "plst", "PCY-45", False, "The List"]},
    {"id": "bolt_plst_clb_187_unstocked", "note": "not stocked: every other List Bolt has its own "
     "collector, so no candidate and no product fetch",
     "card": ["Lightning Bolt", "plst", "CLB-187", False, "The List"]},
    {"id": "mortuary_plst_sth_66", "note": "not stocked: the suggest offers Buried Alive [WTH-63] "
     "(PLST SKU) — never filed on a Mortuary",
     "card": ["Mortuary", "plst", "STH-66", False, "The List"],
     "stand_ins": {"Mortuary The List": "Mortuary STH-66 The List",
                   "Mortuary": "Mortuary STH-66 The List"}},
    {"id": "ancient_excavation_plst_pz2_38", "note": "F2F zero-pads [PZ2-038]",
     "card": ["Ancient Excavation", "plst", "PZ2-38", False, "The List"],
     "stand_ins": {"Ancient Excavation PZ2-38 The List": "Ancient Tomb The List"}},
    {"id": "bolt_clb_401", "note": "variant title: [401] [Rulebook Showcase] [CLB] [Non-Foil]",
     "card": ["Lightning Bolt", "clb", "401", False, "Commander Legends: Battle for Baldur's Gate"]},
    {"id": "bolt_clb_187", "note": "plain title",
     "card": ["Lightning Bolt", "clb", "187", False, "Commander Legends: Battle for Baldur's Gate"]},
    {"id": "bolt_clb_401_on_the_187_answer", "note": "#187 first in suggest order — the collector "
     "decides, not the order",
     "card": ["Lightning Bolt", "clb", "401", False, "Commander Legends: Battle for Baldur's Gate"],
     "stand_ins": {"Lightning Bolt 401 Commander Legends: Battle for Baldur's Gate":
                   "Lightning Bolt 187 Commander Legends: Battle for Baldur's Gate"}},
    {"id": "urzas_saga_mb2_114", "note": "variant title: [114] [White Border] [Mystery Booster 2]",
     "card": ["Urza's Saga", "mb2", "114", False, "Mystery Booster 2"]},
    {"id": "counterspell_mb2_158", "note": "variant title: [158] [Future Frame] [Mystery Booster 2]",
     "card": ["Counterspell", "mb2", "158", False, "Mystery Booster 2"]},
    {"id": "slivdrazi_cmb1_102", "note": "cmb1 SKU M-CMB1-Slivdrazi_-102; cmb1 vetoes NOPW",
     "card": ["Slivdrazi Monstrosity", "cmb1", "102", False, "Mystery Booster Playtest Cards 2019"],
     "stand_ins": {"Slivdrazi Monstrosity 102 Mystery Booster Playtest Cards 2019": "Slivdrazi Monstrosity"}},
    {"id": "slivdrazi_cmb2_102", "note": "cmb2 is filed as M-CMB1-NOPW-Slivdrazi_-102",
     "card": ["Slivdrazi Monstrosity", "cmb2", "102", False, "Mystery Booster Playtest Cards 2021"],
     "stand_ins": {"Slivdrazi Monstrosity 102 Mystery Booster Playtest Cards 2021": "Slivdrazi Monstrosity"}},
    {"id": "earl_ulst_108_foil", "note": "ulst: SKU SIN-MTG-UPLIST-35, title [108] [UST Set Symbol] "
     "[The List: Unfinity Foil Edition] [Foil]",
     "card": ["Earl of Squirrel", "ulst", "108", True, "The List (Unfinity Foil Edition)"],
     "stand_ins": {"Earl of Squirrel 108 The List (Unfinity Foil Edition) foil": "Earl of Squirrel"}},
    {"id": "bolt_m10_146", "note": "the original fixture (tests/fixtures/facetoface): plain titles",
     "card": ["Lightning Bolt", "m10", "146", False, "Magic 2010"],
     "stand_ins": {"Lightning Bolt 146 Magic 2010": LEGACY_SUGGEST}},
    {"id": "bolt_m11_149", "note": "the original fixture: same name, another set",
     "card": ["Lightning Bolt", "m11", "149", False, "Magic 2011"],
     "stand_ins": {"Lightning Bolt 149 Magic 2011": LEGACY_SUGGEST}},
    # The set-name fallback's name check (collector-less titles of the set name):
    {"id": "axe_jgp_on_the_bolt_answer", "note": "Lightning Axe's JGP promo over the captured Bolt "
     "answer, which holds 'Lightning Bolt [Judge Gift Cards 1998] [Foil]' — another card's promo, "
     "never fetched (a name-blind check would file Bolt's price: the declared product)",
     "card": ["Lightning Axe", "jgp", "", True, "Judge Gift Cards 1998"],
     "stand_ins": {"Lightning Axe Judge Gift Cards 1998 foil": "Lightning Bolt",
                   "Lightning Axe foil": "Lightning Bolt",
                   "Lightning Axe": "Lightning Bolt"},
     "products": {_JGP_BOLT: f"synthetic:product/{_JGP_BOLT}"}},
    {"id": "bolt_jgp_collectorless_promo", "note": "the card's own collector-less promo is found "
     "through the set-name fallback (the captured Bolt answer; the product is synthetic)",
     "card": ["Lightning Bolt", "jgp", "", True, "Judge Gift Cards 1998"],
     "stand_ins": {"Lightning Bolt Judge Gift Cards 1998 foil": "Lightning Bolt",
                   "Lightning Bolt foil": "Lightning Bolt"},
     "products": {_JGP_BOLT: f"synthetic:product/{_JGP_BOLT}"}},
    {"id": "fire_ice_g05_fireball_decoy", "note": "a decoy whose name merely STARTS WITH the card's "
     "front face (Fireball vs Fire // Ice, same set name, an SKU confirming g05) — never fetched",
     "card": ["Fire // Ice", "g05", "", True, "Judge Gift Cards 2005"],
     "stand_ins": {"Fire // Ice Judge Gift Cards 2005 foil": "synthetic:suggest/Fire // Ice",
                   "Fire // Ice foil": "synthetic:suggest/Fire // Ice",
                   "Fire // Ice": "synthetic:suggest/Fire // Ice"},
     "products": {_G05_FIREBALL: f"synthetic:product/{_G05_FIREBALL}"}},
    # Meld halves (captured 2026-11-05, the owner's report): F2F's collector
    # carries a side letter Scryfall's doesn't — 1.1.17 priced none of them.
    {"id": "midnight_scavengers_emn_96", "note": "the owner's report: 'Midnight Scavengers // "
     "Chittering Host [96a] [B] [Eldritch Moon] [Non-Foil]' for Scryfall's #96",
     "card": ["Midnight Scavengers", "emn", "96", False, "Eldritch Moon"]},
    {"id": "graf_rats_emn_91", "note": "the owner's report: '[91a] [A]'",
     "card": ["Graf Rats", "emn", "91", False, "Eldritch Moon"]},
    {"id": "midnight_scavengers_emn_96_foil", "note": "the same half in foil (the answer holds both "
     "finishes: one per finish)",
     "card": ["Midnight Scavengers", "emn", "96", True, "Eldritch Moon"]},
    {"id": "bruna_emn_15", "note": "meld half '[15a] [B]'",
     "card": ["Bruna, the Fading Light", "emn", "15", False, "Eldritch Moon"]},
    {"id": "gisela_emn_28", "note": "meld half '[28a] [A]' ($49.99)",
     "card": ["Gisela, the Broken Blade", "emn", "28", False, "Eldritch Moon"]},
    {"id": "hanweir_garrison_emn_130", "note": "meld half '[130a] [B]'",
     "card": ["Hanweir Garrison", "emn", "130", False, "Eldritch Moon"]},
    {"id": "hanweir_battlements_emn_204", "note": "meld half '[204a] [A]'",
     "card": ["Hanweir Battlements", "emn", "204", False, "Eldritch Moon"]},
    {"id": "phyrexian_dragon_engine_bro_163", "note": "BRO's shape '[163a] [The Brothers' War]' — "
     "an SKU with no letter (SIN-MTG-BRO-163)",
     "card": ["Phyrexian Dragon Engine", "bro", "163", False, "The Brothers' War"]},
    {"id": "mightstone_bro_238", "note": "BRO meld half '[238a]'",
     "card": ["The Mightstone and Weakstone", "bro", "238", False, "The Brothers' War"]},
    {"id": "urza_bro_225_no_letter", "note": "a BRO meld half F2F files with no letter: exact",
     "card": ["Urza, Lord Protector", "bro", "225", False, "The Brothers' War"]},
    {"id": "bruna_pemn_15s_foil", "note": "'[15as] [B] [Eldritch Moon Promos]' is no collector: the "
     "set-name fallback still takes the card's own title",
     "card": ["Bruna, the Fading Light", "pemn", "15s", True, "Eldritch Moon Promos"]},
    {"id": "chittering_host_emn_96b", "note": "the meld RESULT has no product of its own: its 96b "
     "ends in a letter, so no half is taken (1.1.16 filed Midnight Scavengers' [96a])",
     "card": ["Chittering Host", "emn", "96b", False, "Eldritch Moon"]},
    {"id": "tymaret_mul_148_foil", "note": "'- Serial Numbered [148z]' is one letter more but no meld "
     "half (the real foil is labelled [Halo Foil]): none",
     "card": ["Tymaret, Chosen from Death", "mul", "148", True, "Multiverse Legends"]},
    {"id": "wurmcoil_brr_126_foil", "note": "'- Serial Numbered [126z]' first in order; the exact "
     "[126] is the card",
     "card": ["Wurmcoil Engine", "brr", "126", True, "The Brothers' War Retro Artifacts"]},
    {"id": "midnight_scavengers_decoys", "note": "decoys one letter past 96 (another card's half, "
     "serial-numbered, single-faced, another number, no other face, the other finish): none fetched",
     "card": ["Midnight Scavengers", "emn", "96", False, "Eldritch Moon"],
     "stand_ins": {q: "synthetic:suggest/Midnight Scavengers decoys" for q in (
         "Midnight Scavengers 96 Eldritch Moon", "Midnight Scavengers Eldritch Moon", "Midnight Scavengers")}},
    {"id": "midnight_scavengers_two_halves", "note": "96a and 96b: ambiguous, none fetched",
     "card": ["Midnight Scavengers", "emn", "96", False, "Eldritch Moon"],
     "stand_ins": {q: "synthetic:suggest/Midnight Scavengers two halves" for q in (
         "Midnight Scavengers 96 Eldritch Moon", "Midnight Scavengers Eldritch Moon", "Midnight Scavengers")}},
    {"id": "midnight_scavengers_exact_beats_half", "note": "a title with the exact 96 wins over the "
     "half listed before it",
     "card": ["Midnight Scavengers", "emn", "96", False, "Eldritch Moon"],
     "stand_ins": {"Midnight Scavengers 96 Eldritch Moon": "synthetic:suggest/Midnight Scavengers half and exact"},
     "products": {_MS_96_EXACT: f"synthetic:product/{_MS_96_EXACT}"}},
    {"id": "midnight_scavengers_half_of_another_set", "note": "the half's product under an SOI SKU: "
     "fetched on every rung, refused every time",
     "card": ["Midnight Scavengers", "emn", "96", False, "Eldritch Moon"],
     "stand_ins": {q: "synthetic:suggest/Midnight Scavengers half of another set" for q in (
         "Midnight Scavengers 96 Eldritch Moon", "Midnight Scavengers Eldritch Moon", "Midnight Scavengers")},
     "products": {_MS_96A_SOI: f"synthetic:product/{_MS_96A_SOI}"}},
    # The collector after a variant bracket: same name, same set, one SKU code
    # — only the collector tells the versions apart, whatever F2F's order.
    {"id": "lier_pmid_59s_foil", "note": "'[Prerelease] [59s] [Innistrad: Midnight Hunt Promos]'",
     "card": ["Lier, Disciple of the Drowned", "pmid", "59s", True, "Innistrad: Midnight Hunt Promos"]},
    {"id": "lier_pmid_59p_foil", "note": "'[Promo Pack] [59p] [Innistrad: Midnight Hunt Promos]'",
     "card": ["Lier, Disciple of the Drowned", "pmid", "59p", True, "Innistrad: Midnight Hunt Promos"]},
    {"id": "lier_59p_on_the_59s_answer", "note": "59s first in order — 1.1.17 filed its $14.99 on the 59p",
     "card": ["Lier, Disciple of the Drowned", "pmid", "59p", True, "Innistrad: Midnight Hunt Promos"],
     "stand_ins": {"Lier, Disciple of the Drowned 59p Innistrad: Midnight Hunt Promos foil":
                   "Lier, Disciple of the Drowned 59s Innistrad: Midnight Hunt Promos foil"}},
    {"id": "lier_59s_on_the_59p_answer", "note": "59p first in order",
     "card": ["Lier, Disciple of the Drowned", "pmid", "59s", True, "Innistrad: Midnight Hunt Promos"],
     "stand_ins": {"Lier, Disciple of the Drowned 59s Innistrad: Midnight Hunt Promos foil":
                   "Lier, Disciple of the Drowned 59p Innistrad: Midnight Hunt Promos foil"}},
    {"id": "hymn_fem_38a", "note": "'[Susan Van Camp] [38a] [Fallen Empires]'",
     "card": ["Hymn to Tourach", "fem", "38a", False, "Fallen Empires"]},
    {"id": "hymn_fem_38c", "note": "'[Quinton Hoover] [38c] [Fallen Empires]'",
     "card": ["Hymn to Tourach", "fem", "38c", False, "Fallen Empires"]},
    {"id": "hymn_38c_on_the_38a_answer", "note": "38a first in order",
     "card": ["Hymn to Tourach", "fem", "38c", False, "Fallen Empires"],
     "stand_ins": {"Hymn to Tourach 38c Fallen Empires": "Hymn to Tourach 38a Fallen Empires"}},
    {"id": "hymn_38a_on_the_38c_answer", "note": "38c first in order",
     "card": ["Hymn to Tourach", "fem", "38a", False, "Fallen Empires"],
     "stand_ins": {"Hymn to Tourach 38a Fallen Empires": "Hymn to Tourach 38c Fallen Empires"}},
    {"id": "factory_atq_80c", "note": "'[Autumn] [80c] [Antiquities]' ($449.99)",
     "card": ["Mishra's Factory", "atq", "80c", False, "Antiquities"]},
    {"id": "factory_atq_80b", "note": "'[Summer] [80b] [Antiquities]' ($349.99)",
     "card": ["Mishra's Factory", "atq", "80b", False, "Antiquities"]},
    {"id": "factory_80b_on_the_80c_answer", "note": "80c first in order — 1.1.17 filed its $449.99 on the 80b",
     "card": ["Mishra's Factory", "atq", "80b", False, "Antiquities"],
     "stand_ins": {"Mishra's Factory 80b Antiquities": "Mishra's Factory 80c Antiquities"}},
    {"id": "factory_80c_on_the_80b_answer", "note": "80b first in order",
     "card": ["Mishra's Factory", "atq", "80c", False, "Antiquities"],
     "stand_ins": {"Mishra's Factory 80c Antiquities": "Mishra's Factory 80b Antiquities"}},
]


def _keep(d: dict, keys) -> dict:
    return {k: d[k] for k in keys if k in d}


def _trim(body: dict) -> dict:
    """The fields the client reads (the captured files are already trimmed)."""
    if "product" in body:
        p = body["product"]
        return {"product": {**_keep(p, ("title", "handle")),
                            "variants": [_keep(v, ("sku", "title", "option1", "price", "available"))
                                         for v in p.get("variants", [])]}}
    prods = body["resources"]["results"]["products"]
    return {"resources": {"results": {"products": [_keep(p, ("title", "handle", "available"))
                                                    for p in prods]}}}


def _query_of(url: str) -> str:
    return unquote(url.split("?q=", 1)[1].split("&", 1)[0])


def load_bodies() -> tuple[dict, dict]:
    """(body id → body, suggest query text → body id). Captured ids are their
    URLs; SYNTHETIC ids are not, so only a declaration reaches one."""
    bodies: dict = {}
    by_query: dict = {}
    for path in sorted(CAPTURED.glob("*.json")):
        d = json.loads(path.read_text(encoding="utf-8"))
        bodies[d["url"]] = _trim(d["body"])
        if "/search/suggest.json" in d["url"]:
            by_query[_query_of(d["url"])] = d["url"]
    bodies[LEGACY_SUGGEST] = _trim(json.loads((ROOT / LEGACY_SUGGEST).read_text(encoding="utf-8")))
    for path in sorted(FIX.glob("product_*.json")):
        handle = path.stem[len("product_"):]
        bodies[f"{m._BASE}/products/{handle}.json"] = _trim(json.loads(path.read_text(encoding="utf-8")))
    for body_id, spec in SYNTHETIC.items():
        assert body_id.startswith("synthetic:") and body_id not in bodies, body_id
        bodies[body_id] = _trim(spec["body"])
    return bodies, by_query


def _handle_of(url: str) -> str:
    return url.rsplit("/products/", 1)[1][:-len(".json")]


def run_session(spec: dict, bodies: dict, by_query: dict) -> dict:
    stand_ins = spec.get("stand_ins", {})
    products = spec.get("products", {})
    requests: list[str] = []
    answers: dict[str, str] = {}

    def get_json(url: str):
        if "/search/suggest.json" in url and _query_of(url) in stand_ins:
            target = stand_ins[_query_of(url)]
            body_id = target if target in bodies else by_query[target]
        elif "/products/" in url and _handle_of(url) in products:
            body_id = products[_handle_of(url)]
        elif url in bodies:
            body_id = url
        else:
            raise SystemExit(f"session {spec['id']}: uncaptured URL with no stand-in: {url}")
        requests.append(url)
        answers[url] = body_id
        return copy.deepcopy(bodies[body_id])

    name, set_code, collector, foil, set_name = spec["card"]
    price = m.FaceToFaceClient(get_json=get_json).get_price(name, set_code, collector, foil, set_name)
    return {
        "id": spec["id"],
        "note": spec["note"],
        "card": {"name": name, "set_code": set_code, "collector_number": collector,
                 "foil": foil, "set_name": set_name},
        "requests": requests,
        "answers": answers,
        "stand_ins": [u for u in requests if answers[u] != u],
        "synthetic": [u for u in requests if answers[u] in SYNTHETIC],
        "result": None if price is None else {"handle": price.handle, "conditions": price.conditions},
    }


def _titles(bodies: dict) -> list[str]:
    seen: list[str] = []
    for body in bodies.values():
        if "product" in body:
            titles = [body["product"].get("title", "")]
        else:
            titles = [p.get("title", "") for p in body["resources"]["results"]["products"]]
        for t in titles:
            if t not in seen:
                seen.append(t)
    return sorted(seen) + [t for t in EXTRA_TITLES if t not in seen]


def _skus(bodies: dict) -> list[str]:
    out: list[str] = []
    for body in bodies.values():
        for v in (body.get("product") or {}).get("variants", []):
            if v.get("sku") and v["sku"] not in out:
                out.append(v["sku"])
    return sorted(out) + [s for s in EXTRA_SKUS if s not in out]


def _side_letter_pairs(collectors: list[str]) -> list[tuple[str, str]]:
    """Every collector seen (titles, sessions, EXTRA_COLLECTORS) against itself,
    itself minus its last character, and itself plus "a" / "A" / "★" — then
    EXTRA_SIDE_LETTER. De-duplicated, in order."""
    pairs: list[tuple[str, str]] = []
    for c in collectors:
        pairs += [(c, c), (c, c[:-1]), (c + "a", c), (c + "A", c), (c + "★", c)]
    return list(dict.fromkeys(pairs + EXTRA_SIDE_LETTER))


def _table(columns: list[str], rows: list[list]) -> dict:
    return {"columns": columns, "rows": rows}


def build() -> bytes:
    bodies, by_query = load_bodies()
    sessions = [run_session(s, bodies, by_query) for s in SESSIONS]
    used = sorted({b for s in sessions for b in s["answers"].values()})

    titles = _titles(bodies)
    parsed = [m._parse_title_brackets(t) for t in titles]
    collectors = list(dict.fromkeys([p["collector"] for p in parsed]
                                    + [s["card"]["collector_number"] for s in sessions]
                                    + EXTRA_COLLECTORS))
    names = list(dict.fromkeys([s["card"]["name"] for s in sessions] + EXTRA_NAMES))
    # _title_names_card reads only the text before the first "[": one FULL title
    # (brackets and all) per distinct name part covers every input it can see.
    first_title: dict[str, str] = {}
    for t in titles:
        first_title.setdefault(t.split("[", 1)[0], t)
    name_parts = list(first_title)
    tables = {
        "parse_title": _table(["title", "collector", "set_name", "foil_label"],
                              [[t, p["collector"], p["set_name"], p["foil_label"]]
                               for t, p in zip(titles, parsed)]),
        "norm_collector": _table(["in", "out"], [[c, m._norm_collector(c)] for c in collectors]),
        "sku_matches_set": _table(["sku", "set", "match"],
                                  [[sku, code, m._sku_matches_set(sku, code)]
                                   for sku in _skus(bodies) for code in SET_CODES]),
        "norm_name": _table(["in", "out"], [[n, m._norm_name(n)] for n in names + name_parts]),
        # Both read only the text before the first "[": one table, one row per
        # (name part, name), an answer column each.
        "title_name": _table(["title", "name", "names_card", "meld_half"],
                             [[t, n, m._title_names_card(t, n), m._title_is_meld_half(t, n)]
                              for t in first_title.values() for n in names]),
        "side_letter_of": _table(["title_collector", "collector", "match"],
                                 [[t, c, m._side_letter_of(t, c)] for t, c in _side_letter_pairs(collectors)]),
    }
    doc = {
        "about": "Generated by scripts/export_f2f_match_fixtures.py from the real facetoface.py over "
                 "F2F answers captured 2026-11-04 (tests/fixtures/facetoface/captured) and the declared "
                 "synthetic bodies listed under \"synthetic\" — do not hand-edit.",
        "base": m._BASE,
        "bodies": {b: bodies[b] for b in used},
        "sessions": sessions,
        "synthetic": {b: SYNTHETIC[b]["note"] for b in used if b in SYNTHETIC},
    }
    # One table row per line keeps the file small and its diffs readable.
    text = json.dumps(doc, indent=1, sort_keys=True, ensure_ascii=False)
    rows = ",\n".join(
        f' {json.dumps(name)}: {{"columns": {json.dumps(t["columns"])}, "rows": [\n'
        + ",\n".join("  " + json.dumps(r, ensure_ascii=False) for r in t["rows"]) + "\n ]}"
        for name, t in sorted(tables.items()))
    text = text[:-2] + ",\n" + rows + "\n}"
    json.loads(text)                       # still one valid document
    return (text + "\n").encode("utf-8")


def main() -> int:
    data = build()
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_bytes() != data:
            print("f2f match fixture is stale — run: python scripts/export_f2f_match_fixtures.py")
            return 1
        print("f2f match fixture up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
