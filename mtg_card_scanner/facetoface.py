"""Face to Face Games (Shopify) pricing client.

Maps a specific MTG printing (name, set_code, collector_number, foil) to Face to
Face Games' live price(s) by condition, using the store's public Shopify JSON
endpoints — no API key required:

    GET /search/suggest.json?q=<name>   -> candidate products (one per printing+foil)
    GET /products/<handle>.json         -> that product's variants (one per condition)

A Shopify "product" is one printing+foil, e.g. title
"Lightning Bolt [149] [Magic 2011] [Non-Foil]"; its variants are conditions
(NM, PL, ...), each with a price. The variant SKU encodes the set code +
collector number (e.g. ``M-M11-Lightning_-149-NM-NF``) which is authoritative for
matching against Scryfall's ``set_code`` / ``collector_number``.

Network access is injected via ``get_json`` so the client is trivially testable
against captured fixtures (see tests/fixtures/facetoface, tests/test_facetoface.py).
"""

from __future__ import annotations

import hashlib
import json
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Optional
from urllib.parse import quote

import requests

_BASE = "https://facetofacegames.com"
_USER_AGENT = "MTGCardScanner/1.0 facetoface (contact: your-email@example.com)"
_MIN_DELAY = 0.15          # ~6 req/s — polite to the storefront
_TIMEOUT = 8               # fail fast rather than hang a scan
_SUGGEST_LIMIT = 10
_DEFAULT_CACHE_DIR = Path.home() / ".cache" / "mtg-card-scanner" / "facetoface"
# Storefront prices move; the cache previously never expired, so a listing
# priced once was quoted at that price forever. A day keeps repeat scans of
# the same set cheap while staying honest for an active selling session.
_CACHE_TTL = 24 * 3600     # seconds

# F2F condition code -> Mana Exchange condition code. F2F's grades are coarser
# than MX's five; used only to pick ONE price for a chosen MX condition — the UI
# still shows every F2F condition verbatim.
F2F_TO_MX_CONDITION: dict[str, str] = {
    "NM": "NM", "PL": "LP", "LP": "LP", "MP": "MP", "HP": "HP", "DMG": "DMG",
}
# For a chosen MX condition, which F2F grades to try, best-first.
_MX_CONDITION_FALLBACK: dict[str, list[str]] = {
    "NM": ["NM", "PL"],
    "LP": ["PL", "NM"],
    "MP": ["MP", "PL", "NM"],
    "HP": ["HP", "PL", "NM"],
    "DMG": ["DMG", "HP", "PL", "NM"],
}


class FaceToFaceError(Exception):
    pass


@dataclass
class F2FPrice:
    """A matched Face to Face product and its per-condition prices."""
    name: str
    set_code: str
    collector_number: str
    foil: bool
    handle: str
    url: str
    conditions: dict[str, float] = field(default_factory=dict)  # {"NM": 3.49, "PL": 2.79}

    def price_for_mx_condition(self, mx_condition: str) -> Optional[float]:
        """Best available F2F price for a Mana Exchange condition code."""
        for f2f_cond in _MX_CONDITION_FALLBACK.get(mx_condition.upper(), ["NM"]):
            if f2f_cond in self.conditions:
                return self.conditions[f2f_cond]
        # Nothing mapped — fall back to the cheapest listed, if any.
        return min(self.conditions.values()) if self.conditions else None

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "set_code": self.set_code,
            "collector_number": self.collector_number,
            "foil": self.foil,
            "handle": self.handle,
            "url": self.url,
            "conditions": self.conditions,
        }


def _norm(s: str) -> str:
    """Lowercase alphanumeric-only, for tolerant set-name / string comparison."""
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def _norm_collector(s: str) -> str:
    """Normalise a collector number for comparison (strip leading zeros, lowercase)."""
    s = str(s or "").strip().lower()
    return s.lstrip("0") or s


def _parse_title_brackets(title: str) -> dict[str, str]:
    """
    Parse a Face to Face product title into its parts.

    Titles look like ``Name [collector] [set name] [foil]`` — but promos omit the
    collector: ``Name [set name] [foil]``. The last bracket is always the foil
    label, the second-last is the set name, and a third (when present) is the
    collector number.
    """
    brackets = re.findall(r"\[([^\]]+)\]", title)
    out = {"collector": "", "set_name": "", "foil_label": ""}
    if not brackets:
        return out
    out["foil_label"] = brackets[-1].strip()
    if len(brackets) >= 2:
        out["set_name"] = brackets[-2].strip()
    if len(brackets) >= 3:
        out["collector"] = brackets[-3].strip()
    return out


def _sku_matches_set(sku: str, set_code: str) -> bool:
    """True if *set_code* appears as a hyphen-separated segment of the SKU.

    F2F's SKU format has drifted over time — ``M-M11-Lightning_-149-NM-NF``
    (set at segment 2) vs ``SIN-MTG-CLB-309-ENG-NM-NF`` (set at segment 3) —
    so the set code is matched positionally-agnostic.  The other segments
    (SIN/MTG/ENG, conditions, finishes, collector digits) don't collide with
    real Scryfall set codes.
    """
    want = (set_code or "").lower()
    return bool(want) and want in (p.lower() for p in str(sku or "").split("-"))


def _variants_to_conditions(variants: list[dict[str, Any]]) -> dict[str, float]:
    """Map Shopify variants (one per condition) to ``{CONDITION: price}``."""
    conditions: dict[str, float] = {}
    for v in variants:
        cond = str(v.get("option1", "")).strip().upper()
        price_raw = v.get("price")
        if cond and price_raw is not None:
            try:
                conditions[cond] = float(price_raw)
            except (TypeError, ValueError):
                continue
    return conditions


def _default_get_json(cache_dir: Path) -> Callable[[str], Any]:
    """Build the real HTTP fetcher: throttled, UA'd, disk-cached by URL.

    Called concurrently from the auto-sweep daemon thread AND request
    threads, so: the throttle state sits under a lock (unsynchronized, two
    threads each saw a stale `last` and defeated the politeness cap), cache
    files are written atomically via temp+rename, and a corrupt/torn cache
    entry falls through to a refetch instead of raising JSONDecodeError out
    of get_price for 24 h straight.
    """
    import os
    import threading

    session = requests.Session()
    session.headers["User-Agent"] = _USER_AGENT
    state = {"last": 0.0}
    throttle_lock = threading.Lock()
    cache_dir.mkdir(parents=True, exist_ok=True)

    def _throttle() -> None:
        with throttle_lock:
            elapsed = time.monotonic() - state["last"]
            if elapsed < _MIN_DELAY:
                time.sleep(_MIN_DELAY - elapsed)
            state["last"] = time.monotonic()

    def get_json(url: str) -> Any:
        key = hashlib.sha1(url.encode()).hexdigest()
        cached = cache_dir / f"{key}.json"
        try:
            if cached.exists() and time.time() - cached.stat().st_mtime < _CACHE_TTL:
                return json.loads(cached.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            pass                       # torn/corrupt entry → refetch below
        # The storefront intermittently rejects a request (bot protection,
        # blips) — observed live returning fine on the very next attempt. One
        # retry turns most of those into a hit; failures are LOGGED, because a
        # silent None here reads as "card not listed" in the UI, which is
        # misleading when the card is in fact in stock.
        last_error = ""
        for attempt in (1, 2):
            _throttle()
            try:
                resp = session.get(url, timeout=_TIMEOUT)
                resp.raise_for_status()
                data = resp.json()
            except (requests.RequestException, ValueError) as exc:
                last_error = f"{type(exc).__name__}: {exc}"
                if attempt == 1:
                    time.sleep(0.8)
                continue
            tmp = cached.with_suffix(f".{os.getpid()}.{threading.get_ident()}.tmp")
            try:
                tmp.write_text(json.dumps(data), encoding="utf-8")
                os.replace(tmp, cached)
            except OSError:
                # Cache write is best-effort — but don't litter tmp files
                # (Windows os.replace can fail if a reader holds the dest).
                try:
                    tmp.unlink(missing_ok=True)
                except OSError:
                    pass
            return data
        # Pricing is a side feature: degrade to "no listing", never crash a
        # scan/select. Not cached, so a later reprice retries for real.
        print(f"  [facetoface] GET failed after retry: {url} ({last_error})")
        return None

    return get_json


class F2FUnavailableError(Exception):
    """
    The storefront could not be reached — the answer is UNKNOWN, not "no
    listing".  get_price previously returned None for BOTH cases, and the
    sweeper recorded the None as a permanent searched-empty marker: a
    transient bot-protection rejection froze "not found" onto cards with
    live listings (observed: Mold Folk, listed, marked no-listing forever).
    Callers that persist results must catch this and leave the row
    UNSEARCHED so a later sweep retries.
    """


class FaceToFaceClient:
    """Look up Face to Face Games pricing for a specific printing."""

    def __init__(
        self,
        get_json: Optional[Callable[[str], Any]] = None,
        cache_dir: Path = _DEFAULT_CACHE_DIR,
    ) -> None:
        self._get_json = get_json or _default_get_json(Path(cache_dir))

    # ── endpoint wrappers ──────────────────────────────────────────────────────

    def _suggest(self, name: str) -> list[dict[str, Any]]:
        url = (
            f"{_BASE}/search/suggest.json?q={quote(name)}"
            f"&resources%5Btype%5D=product"
            f"&resources%5Blimit%5D={_SUGGEST_LIMIT}"
            f"&resources%5Boptions%5D%5Bunavailable_products%5D=show"
        )
        data = self._get_json(url)
        if data is None:                   # fetch failed — unknown, not empty
            raise F2FUnavailableError(f"suggest fetch failed for {name!r}")
        return (
            data.get("resources", {})
            .get("results", {})
            .get("products", [])
        )

    def _product(self, handle: str) -> dict[str, Any]:
        data = self._get_json(f"{_BASE}/products/{handle}.json")
        if data is None:                   # fetch failed — unknown, not empty
            raise F2FUnavailableError(f"product fetch failed for {handle!r}")
        return data.get("product", {})

    # ── public ────────────────────────────────────────────────────────────────

    def get_price(
        self,
        name: str,
        set_code: str,
        collector_number: str,
        foil: bool = False,
        set_name: str = "",
    ) -> Optional[F2FPrice]:
        """
        Return the F2F price for the given printing, or ``None`` if not found.

        Strategy: predictive-search by name, then narrow to a confident set of
        candidate products by foil + collector number (or, for promos that carry
        no collector number in the title, by set name). Confirm the exact printing
        via the variant SKU's set-code segment before reading per-condition prices.
        Returns ``None`` rather than guessing when the printing can't be confirmed.
        """
        if not name:
            return None

        foil_label = "foil" if foil else "non-foil"
        want_collector = _norm_collector(collector_number)
        want_set = _norm(set_code)
        want_set_name = _norm(set_name)

        # Predictive search caps at ~10 results, so a heavily-reprinted card's
        # target printing may not appear under a bare-name query. Try the name
        # first, then progressively more specific queries until we confirm a match.
        queries = [name]
        if set_name:
            queries.append(f"{name} {set_name}")
        if collector_number:
            queries.append(f"{name} {collector_number}")

        tried: set[str] = set()
        for q in queries:
            if q in tried:
                continue
            tried.add(q)
            match = self._match_printing(
                self._suggest(q), foil_label, want_collector, want_set, want_set_name
            )
            if match is None:
                continue
            best_product, best_variants = match
            conditions = _variants_to_conditions(best_variants)
            if not conditions:
                continue
            handle = best_product.get("handle", "")
            return F2FPrice(
                name=name,
                set_code=set_code,
                collector_number=collector_number,
                foil=foil,
                handle=handle,
                url=f"{_BASE}/products/{handle}",
                conditions=conditions,
            )
        return None

    def _match_printing(
        self,
        products: list[dict[str, Any]],
        foil_label: str,
        want_collector: str,
        want_set: str,
        want_set_name: str,
    ) -> Optional[tuple[dict[str, Any], list[dict[str, Any]]]]:
        """Return ``(product, variants)`` for the confirmed printing, or None."""
        if not products:
            return None

        # 1) Filter by foil.
        foil_matches: list[dict[str, Any]] = []
        for p in products:
            parts = _parse_title_brackets(p.get("title", ""))
            if _norm(parts["foil_label"]) != _norm(foil_label):
                continue
            foil_matches.append({**p, "_parts": parts})
        if not foil_matches:
            return None

        # 2) Narrow to confident candidates: prefer collector-number matches;
        #    for promos without a collector in the title, fall back to set-name.
        #    If neither identifies a candidate, don't guess.
        candidates = [
            p for p in foil_matches
            if p["_parts"]["collector"]
            and _norm_collector(p["_parts"]["collector"]) == want_collector
        ]
        if not candidates and want_set_name:
            candidates = [
                p for p in foil_matches
                if _norm(p["_parts"]["set_name"]) == want_set_name
            ]
        if not candidates:
            return None

        # 3) Confirm the exact printing via the variant SKU set code.
        for cand in candidates[:5]:
            variants = self._product(cand.get("handle", "")).get("variants", []) or []
            if not variants:
                continue
            if not want_set or _sku_matches_set(variants[0].get("sku", ""), want_set):
                return cand, variants

        # 4) Collector/set-name matched but the SKU set code disagreed. Collector
        #    numbers collide across sets (e.g. Masters 25 #141 vs Ravnica: Clue
        #    Edition #141), so a collector match alone is NOT enough — returning a
        #    wrong-set price is worse than none. Only trust a single unambiguous
        #    candidate when there is no set code to verify against.
        if not want_set and len(candidates) == 1:
            variants = self._product(candidates[0].get("handle", "")).get("variants", []) or []
            if variants:
                return candidates[0], variants
        return None
