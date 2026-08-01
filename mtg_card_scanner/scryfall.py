"""Scryfall API client with rate-limiting and a 3-tier lookup strategy."""

import time
from typing import Any

import requests

SCRYFALL_BASE = "https://api.scryfall.com"
USER_AGENT = "MTGCardScanner/1.0 (contact: your-email@example.com)"
_MIN_DELAY = 0.11  # ~9 req/s, safely under the 10 req/s limit

# Map common OCR language-code variants to Scryfall canonical codes.
_LANG_NORMALIZE: dict[str, str] = {
    "jp":  "ja",   # Japanese (common OCR output)
    "jap": "ja",
    "cn":  "zhs",  # Simplified Chinese
    "chi": "zhs",
    "tw":  "zht",  # Traditional Chinese
    "cht": "zht",
}


def _normalize_lang(lang: str) -> str:
    """Return the Scryfall-canonical language code for *lang*."""
    clean = (lang or "").lower().strip()
    return _LANG_NORMALIZE.get(clean, clean)


def _safe_print(s: str) -> str:
    """Return *s* with non-ASCII characters replaced so Windows console won't crash."""
    return str(s).encode("ascii", errors="replace").decode("ascii")


class ScryfallError(Exception):
    pass


class ScryfallClient:
    def __init__(self) -> None:
        self._session = requests.Session()
        self._session.headers.update({"User-Agent": USER_AGENT})
        self._last_request: float = 0.0

    def _get(self, url: str, **params: str) -> dict[str, Any]:
        elapsed = time.monotonic() - self._last_request
        if elapsed < _MIN_DELAY:
            time.sleep(_MIN_DELAY - elapsed)

        resp = self._session.get(url, params=params, timeout=10)
        self._last_request = time.monotonic()

        if resp.status_code == 404:
            body = resp.json() if resp.headers.get("content-type", "").startswith("application/json") else {}
            detail = body.get("details", "not found")
            raise ScryfallError(f"404 from Scryfall ({url}): {detail}")

        resp.raise_for_status()
        return resp.json()

    def lookup_by_set_collector(self, set_code: str, collector_number: str,
                                language: str = "") -> dict[str, Any]:
        """Exact lookup by set code + collector number.

        When *language* is provided and is not English, tries the language-specific
        endpoint (/cards/{set}/{num}/{lang}) first so prices and the Scryfall URI
        point to the correct printing.  Falls back to the default (English) endpoint
        on 404.
        """
        lang = _normalize_lang(language)
        if lang and lang != "en":
            try:
                return self._get(
                    f"{SCRYFALL_BASE}/cards/{set_code.lower()}/{collector_number}/{lang}"
                )
            except ScryfallError:
                pass  # language-specific printing not found — fall back to English
        return self._get(f"{SCRYFALL_BASE}/cards/{set_code.lower()}/{collector_number}")

    def lookup_by_set_name(self, set_code: str, name: str) -> dict[str, Any]:
        """
        Search by exact card name within a specific set.
        Handles collector-number digit misreads (e.g. 6 misread as 8).
        """
        data = self._get(
            f"{SCRYFALL_BASE}/cards/search",
            q=f'!"{name}" set:{set_code.lower()}',
        )
        cards = data.get("data", [])
        if not cards:
            raise ScryfallError(f"No card '{name}' in set '{set_code}'")
        return cards[0]

    def lookup_by_name(self, name: str) -> dict[str, Any]:
        """Global fuzzy name search — last resort, may return a different printing."""
        return self._get(f"{SCRYFALL_BASE}/cards/named", fuzzy=name)

    def get_all_printings(self, name: str) -> list[dict[str, Any]]:
        """
        Fetch all PAPER printings of a card by exact name, sorted oldest-first.

        Arena/MTGO-only printings are dropped: they can't be physically in the
        tray, aren't sellable, and were showing up in the pick-a-printing grid.
        (The identification INDEX deliberately keeps digital representatives —
        see art_index._SKIP_LAYOUTS — that concerns recognising artwork, not
        listing printings.)  Returns only cards that have image_uris (needed
        for pHash comparison).  Raises ScryfallError if the name is unknown.
        """
        print(f"  [scryfall] Fetching all printings of '{_safe_print(name)}'...")
        data = self._get(
            f"{SCRYFALL_BASE}/cards/search",
            q=f'!"{name}"',
            unique="prints",
            order="released",
            dir="asc",
            include_extras="true",
        )
        cards = data.get("data", [])
        # Missing `games` (old/fixture data) is treated as paper, not dropped.
        paper = [c for c in cards if "paper" in (c.get("games") or ["paper"])]
        # Double-faced cards keep their images on card_faces, not top-level —
        # graft the front face's image_uris on so pHash ranking and the UI
        # treat them like any other printing.
        for c in paper:
            if not c.get("image_uris"):
                faces = c.get("card_faces") or []
                if faces and faces[0].get("image_uris"):
                    c["image_uris"] = faces[0]["image_uris"]
        with_images = [c for c in paper if c.get("image_uris")]
        print(f"  [scryfall] {len(with_images)} paper printings with images "
              f"(of {len(cards)} total, {len(cards) - len(paper)} digital-only dropped)")
        return with_images

    def lookup(self, set_code: str, collector_number: str, name: str,
               language: str = "") -> dict[str, Any]:
        """
        3-tier lookup strategy:

        1. Exact set + collector_number (language-aware).  If it returns a DIFFERENT
           card name than the model read AND the card is English, we treat it as a
           digit-misread and fall through.  For non-English cards the localized name
           won't match the English oracle name, so we skip the name-match check.
        2. Exact name within the set (set_code + name search).  Survives digit
           misreads in the collector number.
        3. Global fuzzy name search — last resort.  May return a different printing.

        Raises ScryfallError if all three tiers fail.
        """
        lang = _normalize_lang(language)
        is_non_english = bool(lang and lang != "en")

        # Tier 1 — exact set + collector
        if set_code and collector_number:
            try:
                card = self.lookup_by_set_collector(set_code, collector_number, language)
                scryfall_name = card.get("name", "").strip().lower()
                model_name    = name.strip().lower() if name else ""
                # Accept if: no model name to compare, names match, or card is
                # non-English (localized name won't equal the English oracle name).
                if not model_name or scryfall_name == model_name or is_non_english:
                    return card
                # English names don't match — likely a digit misread.
                print(
                    f"  [scryfall] Tier-1 name mismatch: got '{card.get('name')}', "
                    f"expected '{name}' — trying set+name search"
                )
            except ScryfallError as exc:
                print(f"  [scryfall] Tier-1 failed: {exc}")

        # Tier 2 — exact name within set (English name only; non-English falls through)
        if set_code and name and not is_non_english:
            try:
                card = self.lookup_by_set_name(set_code, name)
                print(
                    f"  [scryfall] Tier-2 hit: {card.get('name')} "
                    f"({set_code.upper()} #{card.get('collector_number')})"
                )
                return card
            except ScryfallError as exc:
                print(f"  [scryfall] Tier-2 failed: {exc}")

        # Tier 3 — global fuzzy name (English name only; skip if name is localized)
        if name and not is_non_english:
            print(f"  [scryfall] Tier-3: global fuzzy search for '{name}'")
            return self.lookup_by_name(name)

        raise ScryfallError(
            "Cannot look up card: set_code/collector_number missing and no name available."
        )
