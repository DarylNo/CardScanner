"""Play data: what share of recent decks run each card, format by format.

Owner, 2026-11-10: "I want to build in a system for identifying modern cards
that are popular", then "You make the call on the choices", then (2026-11-11)
"All formats we can".

EDHREC rank (popularity.py) is a COMMANDER number and says almost nothing about
constructed play.  Measured on 30,340 Modern decks, 2026-07-13..11-10:
Thoughtseize is EDHREC 4,442 but in 27 % of Modern decks; Murktide Regent
15,429 and 6 %; Smothering Tithe 68 and in none.  Scryfall ships no play rate
for any constructed format and there is no free API for one (CLAUDE.md,
"Formats beyond Commander"), but two public decklist caches are live and
updated daily:

* MTGO — ``davidfischer/modometa-mtgo-data`` (MIT): every published Challenge
  (its top 32/64) and League 5-0 sample, Scryfall-normalised names.
* paper — ``fbettega/MTG_decklistcache`` (its MTGmelee + Topdeck folders):
  EVERY deck of each event, not just the winners.  The repository is GPL-3: it
  is READ at build time in CI and only aggregate shares are published — no file
  of it is copied.  (Its own MTGO folder is not read; modometa is the MTGO source.)

Decks in the 60 days to 2026-11-10 (MTGO · paper): Modern 6,146 · 10,685,
Pauper 3,196 · 5,079, Standard 2,849 · 3,877, Legacy 3,038 · 2,730, Premodern
1,419 · 1,870, Vintage 1,479 · 1,152, Pioneer 1,437 · 130 (paper too thin to
weigh: MTGO only).  Commander (391) and Duel Commander (66) are too thin and
EDHREC covers Commander far better — they are not FORMATS here.

A weekly CI job (.github/workflows/play-data.yml) reads the last
``WINDOW_DAYS`` of both and publishes ``play-data.json`` to the rolling
``play-data`` prerelease; the phone downloads it like the art pack and the
reference server reads it from a file.  Per scan it costs NOTHING: no request,
no added latency — a dictionary lookup at READ time.

Measured on Modern's 60-day window (16,831 decks / 256 events):

* tier counts barely move with the window (30 / 60 / 90 days: ≥10 % of decks
  90 / 87 / 88 cards, ≥2 % 293 / 310 / 312) — shares are already normalised,
  so the thresholds are absolute, not percentiles;
* MTGO and paper agree (top-100 Jaccard 0.83), and month to month the ranking
  is steady (top-100 Jaccard 0.72; 12 of 2,446 cards move two bands);
* only ~2,450 of 23,012 Modern-legal cards appear in any deck — "in no deck"
  is an ANSWER about the card, never "not known".

The rules, each measured or forced by the data:

* **One number per format: the share of decks running ≥ 1 copy, main OR side**,
  as the EQUAL-WEIGHT mean of the sources with ≥ ``MIN_SOURCE_DECKS`` decks of
  that format, so one 1,228-deck paper event never swamps MTGO.  Stored as
  integer basis points (1 bp = 0.01 % of decks), rounded half up in integer
  arithmetic, and never 0 for a card seen at all.  A format with fewer than
  ``MIN_FORMAT_DECKS`` decks is left out of the file (``skipped``).
* **Keyed by oracle_id, never by name alone.**  13 Modern-legal names name two
  oracle cards (Lightning Bolt among them — popularity.py's rule).  Scans
  filed before this feature carry no oracle_id; they fall back to the name, and
  only when no oracle card shares it (``ambiguous``).
* **Basic lands are their own answer** (``basic``): Island is in 31 % of Modern
  decks and worth nothing — never a chip, never passes a floor.
* **"Not played" needs the scan's own legality** (``popularity.formats``, which
  carries Premodern from 1.1.20 for exactly this): legal and in no deck →
  ``unplayed``; banned / not legal → those answers; no legality recorded → null.
* **Evaluated at READ time, never stored** (``flag_play``, beside
  ``flag_list_tags``): a new week's file re-tags every scan already filed.
* MTGO-only printed names (Through the Omenpaths, set ``om1``: "Kavaero,
  Mind-Bitten" is Superior Spider-Man) resolve through ONE exact
  ``printed_name`` check per name at build time.
"""
from __future__ import annotations

import argparse
import datetime as dt
import gzip
import json
import os
import sys
import threading
import time
from fractions import Fraction
from pathlib import Path
from typing import Any, Callable, Iterable, Optional

from .card_list import name_keys, normalize_name
from .popularity import oracle_key

PACK_KIND = "cardscanner-play-data"
PACK_VERSION = 1
PACK_FILE = "play-data.json"
# The rolling DATA prerelease the weekly job publishes to (never `v*`, never
# latest: releases/latest is how the app's update check finds APP releases).
PACK_TAG = "play-data"
PACK_URL = f"https://github.com/DarylNo/CardScanner/releases/download/{PACK_TAG}/{PACK_FILE}"

# (key, the decklists' Tournament.Formats, label) in the order a seller thinks
# about them — the same order as popularity.TRACKED_FORMATS.  The key is the
# Scryfall legality key the "not played" answer reads.
FORMATS: tuple[tuple[str, str, str], ...] = (
    ("standard", "Standard", "Standard"),
    ("pioneer", "Pioneer", "Pioneer"),
    ("modern", "Modern", "Modern"),
    ("legacy", "Legacy", "Legacy"),
    ("vintage", "Vintage", "Vintage"),
    ("pauper", "Pauper", "Pauper"),
    ("premodern", "Premodern", "Premodern"),
)
FORMAT_KEYS = tuple(k for k, _, _ in FORMATS)
_FORMAT_BY_NAME = {name: key for key, name, _ in FORMATS}

WINDOW_DAYS = 60
# A source with fewer decks of a format than this in the window is left out of
# that format's mean (one thin week of paper data must not count as half).
MIN_SOURCE_DECKS = 200
# A format with fewer decks than this is left out of the file entirely
# (measured: Pioneer 1,437 — MTGO only — is the thinnest kept).
MIN_FORMAT_DECKS = 1_000
MIN_FORMAT_CARDS = 200

# Tier thresholds in basis points of decks (see the module docstring).
STAPLE_BP = 1_000      # ≥ 10 % of decks — Modern: ~87 cards
PLAYED_BP = 200        # ≥ 2 %          — Modern: ~310 cards in all
TIERS = ("staple", "played", "fringe", "unplayed", "basic", "banned", "not_legal")

# Oracle-bulk layouts that are not cards a deck can hold.  `front_card` (2026:
# Jumpstart theme / front cards, sets like fj22 / fmsc) shares names with real
# ones ("Inferno", "Blink").
_SKIP_LAYOUTS = frozenset({"token", "double_faced_token", "emblem", "art_series",
                           "vanguard", "scheme", "planar", "front_card"})
SOURCES = ("mtgo", "paper")
FUZZY_LOOKUP_LIMIT = 300


class PlayDataError(ValueError):
    """A file that could not be used; str() is the short reason shown to the owner."""


# ─────────────────────────────── reading ────────────────────────────────────

def tier_for_bp(bp: int) -> str:
    """staple / played / fringe for a card seen in at least one deck."""
    if bp >= STAPLE_BP:
        return "staple"
    if bp >= PLAYED_BP:
        return "played"
    return "fringe"


def _is_int(v: Any) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _parse_format(f: Any) -> dict[str, Any]:
    if not (isinstance(f, dict) and _is_int(f.get("decks")) and _is_int(f.get("events"))
            and isinstance(f.get("sources"), dict) and isinstance(f.get("cards"), dict)):
        raise PlayDataError("malformed")
    cards: dict[str, tuple[int, int]] = {}
    for oid, v in f["cards"].items():
        if not (isinstance(v, list) and len(v) == 2 and _is_int(v[0]) and _is_int(v[1])
                and v[0] >= 1 and v[1] >= 1):
            raise PlayDataError("malformed")
        cards[oid] = (v[0], v[1])
    src: dict[str, dict[str, Any]] = {}
    for key, s in f["sources"].items():
        if not (isinstance(s, dict) and _is_int(s.get("decks")) and _is_int(s.get("events"))
                and isinstance(s.get("weighted"), bool)):
            raise PlayDataError("malformed")
        src[key] = {"decks": s["decks"], "events": s["events"], "weighted": s["weighted"]}
    return {"decks": f["decks"], "events": f["events"], "sources": src, "cards": cards}


def parse_pack(text: str) -> dict[str, Any]:
    """The published file → the pack used for lookups.

    Raises PlayDataError("not JSON" | "not a play data file" |
    "unsupported version" | "malformed" | "no formats"). Entries of the wrong
    shape are a malformed file, not skipped: the file is machine-made, and a
    half-read one must never replace the last good one. A format key this
    build does not know is ignored (a newer file may carry more)."""
    try:
        doc = json.loads(text)
    except (ValueError, TypeError):
        raise PlayDataError("not JSON") from None
    if not isinstance(doc, dict) or doc.get("kind") != PACK_KIND:
        raise PlayDataError("not a play data file")
    # An integer and exactly ours: a JSON 1.0 or "1" is not version 1.
    if not (_is_int(doc.get("version")) and doc["version"] == PACK_VERSION):
        raise PlayDataError("unsupported version")
    formats = doc.get("formats")
    basic = doc.get("basic")
    names = doc.get("names")
    ambiguous = doc.get("ambiguous")
    if not (isinstance(formats, dict) and isinstance(basic, list) and isinstance(names, dict)
            and isinstance(ambiguous, list) and isinstance(doc.get("as_of"), str)
            and _is_int(doc.get("window_days"))):
        raise PlayDataError("malformed")
    if not all(isinstance(x, str) for x in basic) or not all(isinstance(x, str) for x in ambiguous) \
            or not all(isinstance(v, str) for v in names.values()):
        raise PlayDataError("malformed")
    parsed = {}
    for key in FORMAT_KEYS:                       # our order; unknown keys ignored
        if key in formats:
            parsed[key] = _parse_format(formats[key])
    if not parsed:
        raise PlayDataError("no formats")
    return {
        "as_of": doc["as_of"],
        "window_days": doc["window_days"],
        "built_at": doc.get("built_at") if isinstance(doc.get("built_at"), str) else None,
        "formats": parsed,
        "basic": frozenset(basic),
        "names": dict(names),
        "ambiguous": frozenset(ambiguous),
    }


def play_for(popularity: Optional[dict[str, Any]], name: Optional[str],
             pack: Optional[dict[str, Any]]) -> Optional[dict[str, Any]]:
    """{format: answer | None} for one card, every format in the pack, in
    FORMATS order; None only when there is no pack.

    *popularity* is the stored popularity summary (its ``oracle_id`` — scans
    from 1.1.20 on — and its scan-time ``formats``); *name* is the card's name
    for scans that carry no oracle_id. An answer is
    {"tier": staple|played|fringe, "bp", "rank"} (in a deck of that format),
    {"tier": "basic"}, or, from the scan's own legality, {"tier": "unplayed" |
    "banned" | "not_legal"}; None = not known."""
    if pack is None:
        return None
    pop = popularity if isinstance(popularity, dict) else {}
    oid = pop.get("oracle_id") if isinstance(pop.get("oracle_id"), str) else ""
    keys = name_keys(name) if isinstance(name, str) else []
    known = bool(oid)                     # do we know WHICH card this is?
    if not oid:
        known = bool(keys)
        for k in keys:
            if k in pack["ambiguous"]:
                known = False             # another oracle card shares this name
                break
            hit = pack["names"].get(k)
            if hit:
                oid = hit
                break
    out: dict[str, Any] = {}
    formats = pop.get("formats") if isinstance(pop.get("formats"), dict) else {}
    for key, data in pack["formats"].items():
        if not known:
            out[key] = None
            continue
        if oid and oid in pack["basic"]:
            out[key] = {"tier": "basic"}
            continue
        hit = data["cards"].get(oid) if oid else None
        if hit:                            # in a deck: membership beats a stale snapshot
            bp, rank = hit
            out[key] = {"tier": tier_for_bp(bp), "bp": bp, "rank": rank}
            continue
        legal = formats.get(key)
        if legal in ("legal", "restricted"):
            out[key] = {"tier": "unplayed"}
        elif legal == "banned":
            out[key] = {"tier": "banned"}
        elif legal == "not_legal":
            out[key] = {"tier": "not_legal"}
        else:
            out[key] = None                # legality never recorded (old scan)
    return out


def _named(src: Any) -> Optional[str]:
    if isinstance(src, dict) and isinstance(src.get("name"), str) and src["name"].strip():
        return src["name"]
    return None


def scan_play(scan: dict[str, Any], pack: Optional[dict[str, Any]]) -> Optional[dict[str, Any]]:
    """The play answers for the card the scan IS — read off the same printing
    the pages' popularity pill reads (popOf: the selection's popularity, else the
    top candidate's), so the chips always describe the same card."""
    sel = scan.get("selection") if isinstance(scan.get("selection"), dict) else None
    cands = scan.get("candidates") if isinstance(scan.get("candidates"), list) else []
    top = cands[0] if cands and isinstance(cands[0], dict) else None
    src = None
    for s in (sel, top):
        if s is not None and isinstance(s.get("popularity"), dict) and s["popularity"]:
            src = s
            break
    if src is not None:
        name = _named(src) or _named(sel) or _named(top) or _named(scan.get("card_read"))
        return play_for(src["popularity"], name, pack)
    name = _named(sel) or _named(top) or _named(scan.get("card_read"))
    return play_for(None, name, pack)


def flag_play(scan: dict[str, Any], pack: Optional[dict[str, Any]]) -> dict[str, Any]:
    """Set scan["play"] from the current pack, in place. *pack* None (no data
    yet) leaves the scan untouched — no key, so the pages show nothing; with a
    pack every scan carries {format: answer | null} for every format in it."""
    if pack is not None:
        scan["play"] = scan_play(scan, pack)
    return scan


def pack_status(pack: Optional[dict[str, Any]]) -> dict[str, Any]:
    """GET /api/play-data: what the pages say about the data behind the chips."""
    if pack is None:
        return {"loaded": False}
    return {
        "loaded": True,
        "as_of": pack["as_of"],
        "window_days": pack["window_days"],
        "formats": {k: {"decks": f["decks"], "events": f["events"],
                        "sources": {s: dict(v) for s, v in sorted(f["sources"].items())},
                        "played": len(f["cards"])}
                    for k, f in pack["formats"].items()},
    }


class PlayDataFile:
    """The reference server's pack: a file on disk, re-read when it changes.

    The phone downloads the same file (PlayDataStore); here the owner runs
    ``python -m mtg_card_scanner.play_data fetch`` and points the server at it
    with PLAY_DATA_FILE. A missing file = no data (no key on any scan); a bad
    file keeps the last good pack and records why."""

    def __init__(self, path: Path):
        self.path = Path(path)
        self._lock = threading.Lock()
        self._stamp: Optional[tuple[int, int]] = None
        self._pack: Optional[dict[str, Any]] = None
        self.error: Optional[str] = None

    def current(self) -> Optional[dict[str, Any]]:
        try:
            st = self.path.stat()
            stamp = (st.st_mtime_ns, st.st_size)
        except OSError:
            stamp = None
        with self._lock:
            if stamp == self._stamp:
                return self._pack
            self._stamp = stamp
            if stamp is None:
                return self._pack                # gone: keep what was read
            try:
                self._pack = parse_pack(self.path.read_text(encoding="utf-8"))
                self.error = None
            except PlayDataError as e:
                self.error = str(e)
            except OSError:
                self.error = "unreadable"
            return self._pack


def default_pack_path() -> Path:
    env = os.environ.get("PLAY_DATA_FILE", "").strip()
    if env:
        return Path(env)
    return Path.home() / ".cache" / "mtg-card-scanner" / PACK_FILE


# ─────────────────────────────── building (CI) ──────────────────────────────

class OracleIndex:
    """Scryfall oracle_cards → name keys, legality and basic lands."""

    def __init__(self, entries: Iterable[dict[str, Any]]):
        self.cards: dict[str, dict[str, Any]] = {}
        self.full: dict[str, set[str]] = {}
        self.any_key: dict[str, set[str]] = {}
        for c in entries:
            if not isinstance(c, dict) or c.get("layout") in _SKIP_LAYOUTS:
                continue
            oid = oracle_key(c)
            if not oid or not isinstance(c.get("name"), str):
                continue
            self.cards[oid] = c
            keys = name_keys(c["name"])
            if keys:
                self.full.setdefault(keys[0], set()).add(oid)
            for k in keys:
                self.any_key.setdefault(k, set()).add(oid)
            for f in c.get("card_faces") or []:
                if isinstance(f, dict) and isinstance(f.get("name"), str):
                    for k in name_keys(f["name"]):
                        self.any_key.setdefault(k, set()).add(oid)
        self.basic = frozenset(
            oid for oid, c in self.cards.items()
            if "Basic" in (c.get("type_line") or "") and "Land" in (c.get("type_line") or ""))

    def resolve(self, name: str, fmt: str) -> Optional[str]:
        """A decklist name → oracle_id: the exact full name, else a face, else the
        full name among any card's faces — each when it names exactly ONE card,
        or exactly one card LEGAL in the deck's format *fmt* (a Modern deck's
        "Pick Your Poison" is MKM's card, not the Mystery Booster 2 playtest card
        of that name). None = try the build-time lookup."""
        keys = name_keys(name)
        if not keys:
            return None
        for ids in [self.full.get(keys[0])] + [self.any_key.get(k) for k in keys[1:] + keys[:1]]:
            if not ids:
                continue
            if len(ids) == 1:
                return next(iter(ids))
            legal = sorted(i for i in ids
                           if ((self.cards[i].get("legalities") or {}).get(fmt)) in ("legal", "restricted"))
            if len(legal) == 1:
                return legal[0]
        return None

    def ambiguous_keys(self) -> list[str]:
        return sorted(k for k, ids in self.any_key.items() if len(ids) > 1)


def _event_files(root: Optional[Path], source: str) -> list[Path]:
    if root is None or not Path(root).is_dir():
        return []
    root = Path(root)
    sites = ("MTGO",) if source == "mtgo" else ("MTGmelee", "Topdeck")
    files: list[Path] = []
    for site in sites:
        base = root / "Tournaments" / site
        if base.is_dir():
            files.extend(base.rglob("*.json"))
    return sorted(files)


def _deck_day(deck: dict[str, Any], tournament: dict[str, Any]) -> Optional[dt.date]:
    for raw in (deck.get("Date"), tournament.get("Date")):
        if isinstance(raw, str) and len(raw) >= 10:
            try:
                return dt.date.fromisoformat(raw[:10])
            except ValueError:
                continue
    return None


def _round_half_up(v: Fraction) -> int:
    return int((v * 2 + 1) // 2)


def build_pack(oracle: OracleIndex, mtgo_root: Optional[Path], paper_root: Optional[Path],
               as_of: dt.date, *, lookup: Optional[Callable[[str], Optional[dict[str, Any]]]] = None,
               built_at: Optional[str] = None, window_days: int = WINDOW_DAYS,
               min_format_decks: int = MIN_FORMAT_DECKS, min_format_cards: int = MIN_FORMAT_CARDS,
               log: Callable[[str], None] = lambda s: None) -> dict[str, Any]:
    """Read the decklists and return the pack document (see the module docstring).

    *lookup(name)* is the build-time Scryfall `/cards/named?fuzzy=` answer for a
    name the oracle index cannot resolve (None = no answer). A format below the
    deck / card minimums is left out (``skipped``); raises PlayDataError when
    no format is left to publish."""
    start = as_of - dt.timedelta(days=window_days)
    resolved: dict[tuple[str, str], Optional[str]] = {}
    looked: dict[str, Optional[str]] = {}
    unmatched_entries = 0

    def resolve(raw: str, fmt: str) -> Optional[str]:
        if (raw, fmt) in resolved:
            return resolved[(raw, fmt)]
        oid = oracle.resolve(raw, fmt)
        if oid is None and lookup is not None:
            if raw not in looked:
                found = None
                if len(looked) < FUZZY_LOOKUP_LIMIT:
                    want = normalize_name(raw)
                    card = lookup(raw)
                    if isinstance(card, dict):
                        names = [card.get("name"), card.get("printed_name")]
                        for f in card.get("card_faces") or []:
                            if isinstance(f, dict):
                                names += [f.get("name"), f.get("printed_name")]
                        if any(isinstance(n, str) and normalize_name(n) == want for n in names):
                            got = oracle_key(card)
                            found = got if got in oracle.cards else None
                looked[raw] = found
            oid = looked[raw]
        resolved[(raw, fmt)] = oid
        return oid

    # format -> source -> {oid: decks}; decks / events per format and source
    counts = {k: {s: {} for s in SOURCES} for k in FORMAT_KEYS}
    decks = {k: {s: 0 for s in SOURCES} for k in FORMAT_KEYS}
    events = {k: {s: 0 for s in SOURCES} for k in FORMAT_KEYS}
    for source, root in (("mtgo", mtgo_root), ("paper", paper_root)):
        for path in _event_files(root, source):
            try:
                doc = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                log(f"skipped unreadable {path}")
                continue
            t = doc.get("Tournament") if isinstance(doc, dict) else None
            fmt = _FORMAT_BY_NAME.get(t.get("Formats")) if isinstance(t, dict) else None
            if fmt is None:
                continue
            counted = False
            for deck in doc.get("Decks") or []:
                if not isinstance(deck, dict):
                    continue
                day = _deck_day(deck, t)
                if day is None or not (start < day <= as_of):
                    continue
                main: set[str] = set()
                side: set[str] = set()
                for zone, bucket in (("Mainboard", main), ("Sideboard", side)):
                    for e in deck.get(zone) or []:
                        if not isinstance(e, dict) or not isinstance(e.get("CardName"), str):
                            continue
                        oid = resolve(e["CardName"], fmt)
                        if oid is None:
                            unmatched_entries += 1
                        else:
                            bucket.add(oid)
                if not main:
                    continue
                decks[fmt][source] += 1
                counted = True
                for oid in main | side:
                    counts[fmt][source][oid] = counts[fmt][source].get(oid, 0) + 1
            if counted:
                events[fmt][source] += 1

    formats: dict[str, Any] = {}
    skipped: dict[str, Any] = {}
    for fmt in FORMAT_KEYS:
        weighted = [s for s in SOURCES if decks[fmt][s] >= MIN_SOURCE_DECKS]
        total = sum(decks[fmt][s] for s in weighted)
        sources = {s: {"decks": decks[fmt][s], "events": events[fmt][s], "weighted": s in weighted}
                   for s in SOURCES}
        if not weighted or total < min_format_decks:
            skipped[fmt] = {"decks": sum(decks[fmt].values()), "reason": "too few decks"}
            continue
        seen: set[str] = set()
        for s in weighted:
            seen.update(counts[fmt][s])
        bp: dict[str, int] = {}
        for oid in seen:
            if oid in oracle.basic:
                continue
            share = sum((Fraction(counts[fmt][s].get(oid, 0), decks[fmt][s]) for s in weighted), Fraction(0))
            share /= len(weighted)
            bp[oid] = max(1, _round_half_up(share * 10_000))
        if len(bp) < min_format_cards:
            skipped[fmt] = {"decks": total, "reason": "too few cards"}
            continue
        ranked = sorted(bp.values(), reverse=True)
        rank_of: dict[int, int] = {}
        for i, b in enumerate(ranked):
            rank_of.setdefault(b, i + 1)
        formats[fmt] = {
            "decks": total,
            "events": sum(events[fmt][s] for s in weighted),
            "sources": sources,
            "cards": {oid: [bp[oid], rank_of[bp[oid]]] for oid in sorted(bp)},
        }
        log(f"{fmt}: {total:,} decks (MTGO {decks[fmt]['mtgo']:,}{'' if 'mtgo' in weighted else ' left out'}, "
            f"paper {decks[fmt]['paper']:,}{'' if 'paper' in weighted else ' left out'}) · {len(bp):,} cards played")
    if not formats:
        raise PlayDataError(
            f"no format has {min_format_decks} decks in the {window_days} days to {as_of} — "
            + ", ".join(f"{k} {v['decks']}" for k, v in skipped.items()))
    played = set()
    for f in formats.values():
        played.update(f["cards"])
    basic = sorted(oracle.basic)
    ambiguous = oracle.ambiguous_keys()
    amb = set(ambiguous)
    names: dict[str, str] = {}
    for oid in sorted(played | set(basic)):
        for k in name_keys(oracle.cards[oid]["name"]):
            if k not in amb:
                names[k] = oid
    unmatched = sorted({raw for (raw, _f), oid in resolved.items() if oid is None})
    if unmatched:
        log(f"{len(unmatched)} decklist name(s) matched no card ({unmatched_entries} entries): "
            + ", ".join(unmatched[:20]))
    return {
        "kind": PACK_KIND,
        "version": PACK_VERSION,
        "as_of": as_of.isoformat(),
        "window_days": window_days,
        "built_at": built_at,
        "formats": formats,
        "skipped": skipped,
        "unmatched_names": len(unmatched),
        "unmatched_entries": unmatched_entries,
        "basic": basic,
        "names": {k: names[k] for k in sorted(names)},
        "ambiguous": ambiguous,
    }


def dump_pack(pack: dict[str, Any]) -> str:
    """Byte-stable text for a given pack (sorted keys, no whitespace)."""
    return json.dumps(pack, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n"


def load_oracle_entries(path: Path) -> list[dict[str, Any]]:
    """A Scryfall oracle_cards bulk file in either format (JSON array, or the
    2026+ optionally gzipped JSONL — the same sniffing as art_index)."""
    path = Path(path)
    with open(path, "rb") as fh:
        magic = fh.read(2)
    opener = gzip.open if magic == b"\x1f\x8b" else open
    with opener(path, "rt", encoding="utf-8") as fh:        # type: ignore[operator]
        first = fh.read(1)
        fh.seek(0)
        if first == "[":
            return json.load(fh)
        out = []
        for line in fh:
            line = line.strip().rstrip(",")
            if line and line not in ("[", "]"):
                out.append(json.loads(line))
        return out


def summary_line(pack: dict[str, Any]) -> str:
    """One line per build for the CI log / step summary."""
    parts = []
    for key, _name, label in FORMATS:
        f = pack["formats"].get(key)
        if f:
            parts.append(f"{label} {f['decks']:,}")
    total = sum(f["decks"] for f in pack["formats"].values())
    skipped = ", ".join(f"{k} ({v['decks']})" for k, v in sorted(pack.get("skipped", {}).items()))
    return (f"as of {pack['as_of']} · {pack['window_days']} days · {total:,} decks — " + " · ".join(parts)
            + (f" · left out: {skipped}" if skipped else "")
            + f" · {pack['unmatched_names']} names unmatched")


# ─────────────────────────────── CLI ────────────────────────────────────────

_UA = "MTGCardScanner/1.0 play-data (+https://github.com/DarylNo/CardScanner)"


def _http_session():
    import requests
    s = requests.Session()
    s.headers.update({"User-Agent": _UA, "Accept": "application/json"})
    return s


def _download_oracle(dest: Path) -> Path:
    s = _http_session()
    r = s.get("https://api.scryfall.com/bulk-data/oracle-cards", timeout=60)
    r.raise_for_status()
    manifest = r.json()
    url = manifest.get("download_uri") or manifest.get("jsonl_download_uri")
    if not url:
        raise PlayDataError(f"oracle_cards manifest has no download URL ({sorted(manifest)})")
    dest.parent.mkdir(parents=True, exist_ok=True)
    with s.get(url, stream=True, timeout=120) as resp:
        resp.raise_for_status()
        with open(dest, "wb") as fh:
            for chunk in resp.iter_content(chunk_size=1 << 20):
                fh.write(chunk)
    return dest


def _scryfall_lookup() -> Callable[[str], Optional[dict[str, Any]]]:
    s = _http_session()
    last = [0.0]

    def lookup(name: str) -> Optional[dict[str, Any]]:
        wait = 0.12 - (time.monotonic() - last[0])
        if wait > 0:
            time.sleep(wait)
        last[0] = time.monotonic()
        try:
            r = s.get("https://api.scryfall.com/cards/named", params={"fuzzy": name}, timeout=30)
        except OSError:
            return None
        if r.status_code != 200:
            return None
        try:
            return r.json()
        except ValueError:
            return None
    return lookup


def main(argv: Optional[list[str]] = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m mtg_card_scanner.play_data",
                                 description="Play data: build the weekly file, or fetch the published one.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build", help="read decklists and write the file (CI)")
    b.add_argument("--mtgo", type=Path, help="a modometa-mtgo-data checkout")
    b.add_argument("--paper", type=Path, help="an MTG_decklistcache checkout (MTGmelee + Topdeck read)")
    b.add_argument("--oracle", type=Path, help="Scryfall oracle_cards bulk (downloaded when absent)")
    b.add_argument("--as-of", help="last day of the window, YYYY-MM-DD (default: today, UTC)")
    b.add_argument("--no-lookup", action="store_true", help="never ask Scryfall about unmatched names")
    b.add_argument("--out", type=Path, required=True)
    f = sub.add_parser("fetch", help="download the published file for the reference server")
    f.add_argument("--url", default=PACK_URL)
    f.add_argument("--out", type=Path, default=None)
    args = ap.parse_args(argv)

    if args.cmd == "fetch":
        out = args.out or default_pack_path()
        r = _http_session().get(args.url, timeout=60)
        if r.status_code != 200:
            print(f"fetch failed: HTTP {r.status_code}", file=sys.stderr)
            return 1
        try:
            pack = parse_pack(r.text)
        except PlayDataError as e:
            print(f"fetch refused: {e}", file=sys.stderr)
            return 1
        out.parent.mkdir(parents=True, exist_ok=True)
        tmp = out.with_suffix(".tmp")
        tmp.write_text(r.text, encoding="utf-8")
        tmp.replace(out)
        print(f"{out}: as of {pack['as_of']} · {', '.join(pack['formats'])} — "
              f"run the server with PLAY_DATA_FILE={out}")
        return 0

    now = dt.datetime.now(dt.timezone.utc)
    as_of = dt.date.fromisoformat(args.as_of) if args.as_of else now.date()
    oracle_path = args.oracle
    if oracle_path is None or not oracle_path.exists():
        oracle_path = _download_oracle(oracle_path or Path("oracle-cards.jsonl.gz"))
    oracle = OracleIndex(load_oracle_entries(oracle_path))
    try:
        pack = build_pack(oracle, args.mtgo, args.paper, as_of,
                          lookup=None if args.no_lookup else _scryfall_lookup(),
                          built_at=now.strftime("%Y-%m-%dT%H:%M:%SZ"),
                          log=lambda s: print(f"  [play_data] {s}"))
    except PlayDataError as e:
        # Nothing to publish: the last good file stays up (CI fails visibly).
        print(f"refused: {e}", file=sys.stderr)
        return 2
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(dump_pack(pack), encoding="utf-8")
    print(f"{args.out}: {summary_line(pack)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
