"""Modern play data: what share of recent Modern decks run each card.

Owner, 2026-11-10: "I want to build in a system for identifying modern cards
that are popular" — then "You make the call on the choices".

EDHREC rank (popularity.py) is a COMMANDER number and says almost nothing about
Modern.  Measured on 30,340 Modern decks, 2026-07-13..11-10: Thoughtseize is
EDHREC 4,442 but in 27 % of Modern decks; Murktide Regent 15,429 and 6 %;
Smothering Tithe 68 and in none.  Scryfall ships no Modern play rate and there
is no free API for one (CLAUDE.md, "Formats beyond Commander"), but two public
decklist caches are live and updated daily:

* MTGO — ``davidfischer/modometa-mtgo-data`` (MIT): every published Modern
  Challenge (its top 32/64) and League 5-0 sample, Scryfall-normalised names.
* paper — ``fbettega/MTG_decklistcache`` (its MTGmelee + Topdeck folders):
  EVERY deck of each event, not just the winners.  The repository is GPL-3: it
  is READ at build time in CI and only aggregate shares are published — no file
  of it is copied.  (Its own MTGO folder is not read; modometa is the MTGO source.)

A weekly CI job (.github/workflows/modern-meta.yml) reads the last
``WINDOW_DAYS`` of both and publishes ``modern-meta.json`` (~120 KB) to the
rolling ``modern-meta`` prerelease; the phone downloads it like the art pack and
the reference server reads it from a file.  Per scan it costs NOTHING: no
request, no added latency — a dictionary lookup at READ time.

Measured on the 60-day window ending 2026-11-10 (16,830 decks / 234 events:
MTGO 6,146 · paper 10,684):

* tier counts barely move with the window (30 / 60 / 90 days: ≥10 % of decks
  90 / 87 / 88 cards, ≥2 % 293 / 310 / 312) — shares are already normalised,
  so the thresholds are absolute, not percentiles;
* MTGO and paper agree (top-100 Jaccard 0.83), and month to month the ranking
  is steady (top-100 Jaccard 0.72; 12 of 2,446 cards move two bands);
* only ~2,450 of 23,012 Modern-legal cards appear in any deck — "in no deck"
  is an ANSWER about the card, never "not known".

The rules, each measured or forced by the data:

* **One number: the share of decks running ≥ 1 copy, main OR side**, as the
  EQUAL-WEIGHT mean of the sources with ≥ ``MIN_SOURCE_DECKS`` decks, so one
  1,228-deck paper event never swamps MTGO.  Stored as integer basis points
  (1 bp = 0.01 % of decks), rounded half up in integer arithmetic, and never 0
  for a card seen at all.
* **Keyed by oracle_id, never by name alone.**  13 Modern-legal names name two
  oracle cards (Lightning Bolt among them — popularity.py's rule).  Scans filed
  before this feature carry no oracle_id; they fall back to the name, and only
  when no oracle card shares it (``ambiguous``).
* **Basic lands are their own answer** (``basic``): Island is in 31 % of decks
  and worth nothing — never a chip, never passes a floor.
* **Evaluated at READ time, never stored** (``flag_modern``, beside
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

PACK_KIND = "cardscanner-modern-meta"
PACK_VERSION = 1
PACK_FILE = "modern-meta.json"
# The rolling DATA prerelease the weekly job publishes to (never `v*`, never
# latest: releases/latest is how the app's update check finds APP releases).
PACK_TAG = "modern-meta"
PACK_URL = f"https://github.com/DarylNo/CardScanner/releases/download/{PACK_TAG}/{PACK_FILE}"

WINDOW_DAYS = 60
# A source with fewer decks than this in the window is left out of the mean
# (one thin week of paper data must not count as half the signal).
MIN_SOURCE_DECKS = 200
# The builder refuses to write a pack from fewer decks than this — the last
# good pack stays published (measured: 60 days = 16,830 decks; 30 days 4,167).
MIN_PACK_DECKS = 2_000
MIN_PACK_CARDS = 500

# Tier thresholds in basis points of decks (see the module docstring).
STAPLE_BP = 1_000      # ≥ 10 % of decks — measured ~87 cards
PLAYED_BP = 200        # ≥ 2 %          — ~310 cards in all
TIERS = ("staple", "played", "fringe", "unplayed", "basic", "banned", "not_legal")

# Oracle-bulk layouts that are not cards a deck can hold.
# `front_card` (2026: Jumpstart theme / front cards, sets like fj22 / fmsc) is not
# a deck card either, and shares names with real ones ("Inferno", "Blink").
_SKIP_LAYOUTS = frozenset({"token", "double_faced_token", "emblem", "art_series",
                           "vanguard", "scheme", "planar", "front_card"})
SOURCES = ("mtgo", "paper")
FUZZY_LOOKUP_LIMIT = 200


class ModernMetaError(ValueError):
    """A pack that could not be used; str() is the short reason shown to the owner."""


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


def parse_pack(text: str) -> dict[str, Any]:
    """The published file → the pack used for lookups.

    Raises ModernMetaError("not JSON" | "not a Modern play data file" |
    "unsupported version" | "malformed"). Entries of the wrong shape are a
    malformed file, not skipped: the file is machine-made, and a half-read one
    must never replace the last good one."""
    try:
        doc = json.loads(text)
    except (ValueError, TypeError):
        raise ModernMetaError("not JSON") from None
    if not isinstance(doc, dict) or doc.get("kind") != PACK_KIND:
        raise ModernMetaError("not a Modern play data file")
    # An integer and exactly ours: a JSON 1.0 or "1" is not version 1.
    if not (_is_int(doc.get("version")) and doc["version"] == PACK_VERSION):
        raise ModernMetaError("unsupported version")
    cards = doc.get("cards")
    basic = doc.get("basic")
    names = doc.get("names")
    ambiguous = doc.get("ambiguous")
    sources = doc.get("sources")
    if not (isinstance(cards, dict) and isinstance(basic, list) and isinstance(names, dict)
            and isinstance(ambiguous, list) and isinstance(sources, dict)
            and isinstance(doc.get("as_of"), str) and _is_int(doc.get("window_days"))
            and _is_int(doc.get("decks")) and _is_int(doc.get("events"))):
        raise ModernMetaError("malformed")
    parsed_cards: dict[str, tuple[int, int]] = {}
    for oid, v in cards.items():
        if not (isinstance(v, list) and len(v) == 2 and _is_int(v[0]) and _is_int(v[1])
                and v[0] >= 1 and v[1] >= 1):
            raise ModernMetaError("malformed")
        parsed_cards[oid] = (v[0], v[1])
    if not all(isinstance(x, str) for x in basic) or not all(isinstance(x, str) for x in ambiguous) \
            or not all(isinstance(v, str) for v in names.values()):
        raise ModernMetaError("malformed")
    src: dict[str, dict[str, Any]] = {}
    for key, s in sources.items():
        if not (isinstance(s, dict) and _is_int(s.get("decks")) and _is_int(s.get("events"))
                and isinstance(s.get("weighted"), bool)):
            raise ModernMetaError("malformed")
        src[key] = {"decks": s["decks"], "events": s["events"], "weighted": s["weighted"]}
    return {
        "as_of": doc["as_of"],
        "window_days": doc["window_days"],
        "built_at": doc.get("built_at") if isinstance(doc.get("built_at"), str) else None,
        "decks": doc["decks"],
        "events": doc["events"],
        "sources": src,
        "cards": parsed_cards,
        "basic": frozenset(basic),
        "names": dict(names),
        "ambiguous": frozenset(ambiguous),
    }


def modern_for(popularity: Optional[dict[str, Any]], name: Optional[str],
               pack: Optional[dict[str, Any]]) -> Optional[dict[str, Any]]:
    """The Modern answer for one card, or None when it cannot be known.

    *popularity* is the stored popularity summary (its ``oracle_id`` — scans
    from 1.1.20 on — and its scan-time ``formats``); *name* is the card's name
    for scans that carry no oracle_id."""
    if pack is None:
        return None
    pop = popularity if isinstance(popularity, dict) else {}
    oid = pop.get("oracle_id") if isinstance(pop.get("oracle_id"), str) else ""
    keys = name_keys(name) if isinstance(name, str) else []
    if not oid:
        for k in keys:
            if k in pack["ambiguous"]:
                return None                      # another oracle card shares this name
            hit = pack["names"].get(k)
            if hit:
                oid = hit
                break
    if oid:
        if oid in pack["basic"]:
            return {"tier": "basic"}
        hit = pack["cards"].get(oid)
        if hit:                                  # in a deck: membership beats a stale snapshot
            bp, rank = hit
            return {"tier": tier_for_bp(bp), "bp": bp, "rank": rank}
    elif not keys:
        return None                              # nothing to go on
    formats = pop.get("formats")
    legal = formats.get("modern") if isinstance(formats, dict) else None
    if legal in ("legal", "restricted"):
        return {"tier": "unplayed"}
    if legal == "banned":
        return {"tier": "banned"}
    if legal == "not_legal":
        return {"tier": "not_legal"}
    return None                                  # old scan: legality never recorded


def _named(src: Any) -> Optional[str]:
    if isinstance(src, dict) and isinstance(src.get("name"), str) and src["name"].strip():
        return src["name"]
    return None


def scan_modern(scan: dict[str, Any], pack: Optional[dict[str, Any]]) -> Optional[dict[str, Any]]:
    """The Modern answer for the card the scan IS — read off the same printing
    the pages' popularity pill reads (popOf: the selection's popularity, else the
    top candidate's), so the two chips always describe the same card."""
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
        return modern_for(src["popularity"], name, pack)
    name = _named(sel) or _named(top) or _named(scan.get("card_read"))
    return modern_for(None, name, pack)


def flag_modern(scan: dict[str, Any], pack: Optional[dict[str, Any]]) -> dict[str, Any]:
    """Set scan["modern"] from the current pack, in place. *pack* None (no data
    yet) leaves the scan untouched — no key, so the pages show nothing; with a
    pack every scan carries the key (null = not known for this card)."""
    if pack is not None:
        scan["modern"] = scan_modern(scan, pack)
    return scan


def pack_status(pack: Optional[dict[str, Any]]) -> dict[str, Any]:
    """GET /api/modern-meta: what the pages say about the data behind the chip."""
    if pack is None:
        return {"loaded": False}
    return {
        "loaded": True,
        "as_of": pack["as_of"],
        "window_days": pack["window_days"],
        "decks": pack["decks"],
        "events": pack["events"],
        "sources": {k: dict(v) for k, v in sorted(pack["sources"].items())},
        "played": len(pack["cards"]),
    }


class ModernMetaFile:
    """The reference server's pack: a file on disk, re-read when it changes.

    The phone downloads the same file (ModernMetaStore); here the owner runs
    ``python -m mtg_card_scanner.modern_meta fetch`` (or the server is pointed
    at a file with MODERN_META_FILE). A missing file = no data (no key on any
    scan); a bad file keeps the last good pack and records why."""

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
            except ModernMetaError as e:
                self.error = str(e)
            except OSError:
                self.error = "unreadable"
            return self._pack


def default_pack_path() -> Path:
    env = os.environ.get("MODERN_META_FILE", "").strip()
    if env:
        return Path(env)
    return Path.home() / ".cache" / "mtg-card-scanner" / PACK_FILE


# ─────────────────────────────── building (CI) ──────────────────────────────

class OracleIndex:
    """Scryfall oracle_cards → name keys, Modern legality and basic lands."""

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

    def resolve(self, name: str) -> Optional[str]:
        """A decklist name → oracle_id: the exact full name, else a face, else the
        full name among any card's faces — each when it names exactly ONE card,
        or exactly one MODERN-LEGAL card (these are Modern decklists: "Pick Your
        Poison" is MKM's card, not the Mystery Booster 2 playtest card of that
        name). None = try the build-time lookup."""
        keys = name_keys(name)
        if not keys:
            return None
        for ids in [self.full.get(keys[0])] + [self.any_key.get(k) for k in keys[1:] + keys[:1]]:
            if not ids:
                continue
            if len(ids) == 1:
                return next(iter(ids))
            legal = [i for i in ids
                     if ((self.cards[i].get("legalities") or {}).get("modern")) in ("legal", "restricted")]
            if len(legal) == 1:
                return legal[0]
        return None

    def ambiguous_keys(self) -> list[str]:
        return sorted(k for k, ids in self.any_key.items() if len(ids) > 1)


def _event_files(root: Optional[Path], source: str) -> list[Path]:
    if root is None or not Path(root).is_dir():
        return []
    root = Path(root)
    if source == "mtgo":
        base = root / "Tournaments" / "MTGO"
        files = [p for p in base.rglob("*.json") if p.name.lower().startswith("modern-")] \
            if base.is_dir() else []
    else:
        files = []
        for site in ("MTGmelee", "Topdeck"):
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
               min_pack_decks: int = MIN_PACK_DECKS, min_pack_cards: int = MIN_PACK_CARDS,
               log: Callable[[str], None] = lambda s: None) -> dict[str, Any]:
    """Read the decklists and return the pack document (see the module docstring).

    *lookup(name)* is the build-time Scryfall `/cards/named?fuzzy=` answer for a
    name the oracle index cannot resolve (None = no answer). Raises
    ModernMetaError when the window holds too few decks or cards to publish."""
    start = as_of - dt.timedelta(days=window_days)
    resolved: dict[str, Optional[str]] = {}
    lookups = 0
    unmatched_entries = 0

    def resolve(raw: str) -> Optional[str]:
        nonlocal lookups
        if raw in resolved:
            return resolved[raw]
        oid = oracle.resolve(raw)
        if oid is None and lookup is not None and lookups < FUZZY_LOOKUP_LIMIT:
            lookups += 1
            want = normalize_name(raw)
            card = lookup(raw)
            if isinstance(card, dict):
                names = [card.get("name"), card.get("printed_name")]
                for f in card.get("card_faces") or []:
                    if isinstance(f, dict):
                        names += [f.get("name"), f.get("printed_name")]
                if any(isinstance(n, str) and normalize_name(n) == want for n in names):
                    got = oracle_key(card)
                    oid = got if got in oracle.cards else None
        resolved[raw] = oid
        return oid

    counts = {s: {} for s in SOURCES}          # source -> {oid: decks}
    decks = {s: 0 for s in SOURCES}
    events = {s: 0 for s in SOURCES}
    for source, root in (("mtgo", mtgo_root), ("paper", paper_root)):
        for path in _event_files(root, source):
            try:
                doc = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                log(f"skipped unreadable {path}")
                continue
            t = doc.get("Tournament") if isinstance(doc, dict) else None
            if not isinstance(t, dict) or t.get("Formats") != "Modern":
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
                        oid = resolve(e["CardName"])
                        if oid is None:
                            unmatched_entries += 1
                        else:
                            bucket.add(oid)
                if not main:
                    continue
                decks[source] += 1
                counted = True
                for oid in main | side:
                    counts[source][oid] = counts[source].get(oid, 0) + 1
            if counted:
                events[source] += 1

    weighted = [s for s in SOURCES if decks[s] >= MIN_SOURCE_DECKS]
    total = sum(decks[s] for s in weighted)
    if not weighted or total < min_pack_decks:
        raise ModernMetaError(
            f"only {total} decks in the {window_days} days to {as_of} "
            f"(MTGO {decks['mtgo']}, paper {decks['paper']}) — need {min_pack_decks}")
    seen: set[str] = set()
    for s in weighted:
        seen.update(counts[s])
    bp: dict[str, int] = {}
    for oid in seen:
        share = sum((Fraction(counts[s].get(oid, 0), decks[s]) for s in weighted), Fraction(0))
        share /= len(weighted)
        bp[oid] = max(1, _round_half_up(share * 10_000))
    ranked = sorted((b for oid, b in bp.items() if oid not in oracle.basic), reverse=True)
    rank_of: dict[int, int] = {}
    for i, b in enumerate(ranked):
        rank_of.setdefault(b, i + 1)
    cards = {oid: [b, rank_of[b]] for oid, b in bp.items() if oid not in oracle.basic}
    if len(cards) < min_pack_cards:
        raise ModernMetaError(f"only {len(cards)} cards seen — need {min_pack_cards}")
    basic = sorted(oracle.basic)
    ambiguous = oracle.ambiguous_keys()
    amb = set(ambiguous)
    names: dict[str, str] = {}
    for oid in sorted(set(cards) | set(basic)):
        for k in name_keys(oracle.cards[oid]["name"]):
            if k not in amb:
                names[k] = oid
    unmatched = sorted(n for n, oid in resolved.items() if oid is None)
    if unmatched:
        log(f"{len(unmatched)} decklist name(s) matched no card ({unmatched_entries} entries): "
            + ", ".join(unmatched[:20]))
    return {
        "kind": PACK_KIND,
        "version": PACK_VERSION,
        "format": "modern",
        "as_of": as_of.isoformat(),
        "window_days": window_days,
        "built_at": built_at,
        "decks": total,
        "events": sum(events[s] for s in weighted),
        "sources": {s: {"decks": decks[s], "events": events[s], "weighted": s in weighted}
                    for s in SOURCES},
        "unmatched_names": len(unmatched),
        "unmatched_entries": unmatched_entries,
        "cards": {oid: cards[oid] for oid in sorted(cards)},
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


# ─────────────────────────────── CLI ────────────────────────────────────────

_UA = "MTGCardScanner/1.0 modern-meta (+https://github.com/DarylNo/CardScanner)"


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
        raise ModernMetaError(f"oracle_cards manifest has no download URL ({sorted(manifest)})")
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
    ap = argparse.ArgumentParser(prog="python -m mtg_card_scanner.modern_meta",
                                 description="Modern play data: build the weekly pack, or fetch the published one.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build", help="read decklists and write the pack (CI)")
    b.add_argument("--mtgo", type=Path, help="a modometa-mtgo-data checkout")
    b.add_argument("--paper", type=Path, help="an MTG_decklistcache checkout (MTGmelee + Topdeck read)")
    b.add_argument("--oracle", type=Path, help="Scryfall oracle_cards bulk (downloaded when absent)")
    b.add_argument("--as-of", help="last day of the window, YYYY-MM-DD (default: today, UTC)")
    b.add_argument("--no-lookup", action="store_true", help="never ask Scryfall about unmatched names")
    b.add_argument("--out", type=Path, required=True)
    f = sub.add_parser("fetch", help="download the published pack for the reference server")
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
        except ModernMetaError as e:
            print(f"fetch refused: {e}", file=sys.stderr)
            return 1
        out.parent.mkdir(parents=True, exist_ok=True)
        tmp = out.with_suffix(".tmp")
        tmp.write_text(r.text, encoding="utf-8")
        tmp.replace(out)
        print(f"{out}: as of {pack['as_of']} · {pack['decks']:,} decks · {len(pack['cards']):,} cards played")
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
                          log=lambda s: print(f"  [modern_meta] {s}"))
    except ModernMetaError as e:
        # Too thin to publish: the last good file stays up (CI fails visibly).
        print(f"refused: {e}", file=sys.stderr)
        return 2
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(dump_pack(pack), encoding="utf-8")
    src = pack["sources"]
    print(f"{args.out}: as of {pack['as_of']} · {pack['window_days']} days · {pack['decks']:,} decks "
          f"from {pack['events']} events (MTGO {src['mtgo']['decks']:,}{'' if src['mtgo']['weighted'] else ' — left out'}, "
          f"paper {src['paper']['decks']:,}{'' if src['paper']['weighted'] else ' — left out'}) · "
          f"{len(pack['cards']):,} cards played · {pack['unmatched_names']} names unmatched")
    return 0


if __name__ == "__main__":
    sys.exit(main())
