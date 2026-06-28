"""Scryfall API client with rate-limiting and a 3-tier lookup strategy."""

import time
from typing import Any

import requests

SCRYFALL_BASE = "https://api.scryfall.com"
USER_AGENT = "MTGCardScanner/1.0 (contact: your-email@example.com)"
_MIN_DELAY = 0.11  # ~9 req/s, safely under the 10 req/s limit


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

    def lookup_by_set_collector(self, set_code: str, collector_number: str) -> dict[str, Any]:
        """Exact lookup by set code + collector number."""
        url = f"{SCRYFALL_BASE}/cards/{set_code.lower()}/{collector_number}"
        return self._get(url)

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

    def lookup(self, set_code: str, collector_number: str, name: str) -> dict[str, Any]:
        """
        3-tier lookup strategy:

        1. Exact set + collector_number.  If it returns a DIFFERENT card name than
           the model read, we treat it as a digit-misread and fall through.
        2. Exact name within the set (set_code + name search).  Survives digit
           misreads in the collector number.
        3. Global fuzzy name search — last resort.  May return a different printing.

        Raises ScryfallError if all three tiers fail.
        """
        # Tier 1 — exact set + collector
        if set_code and collector_number:
            try:
                card = self.lookup_by_set_collector(set_code, collector_number)
                scryfall_name = card.get("name", "").strip().lower()
                model_name    = name.strip().lower() if name else ""
                if not model_name or scryfall_name == model_name:
                    return card
                # Names don't match — digit misread likely.  Log and fall through.
                print(
                    f"  [scryfall] Tier-1 name mismatch: got '{card.get('name')}', "
                    f"expected '{name}' — trying set+name search"
                )
            except ScryfallError as exc:
                print(f"  [scryfall] Tier-1 failed: {exc}")

        # Tier 2 — exact name within set
        if set_code and name:
            try:
                card = self.lookup_by_set_name(set_code, name)
                print(
                    f"  [scryfall] Tier-2 hit: {card.get('name')} "
                    f"({set_code.upper()} #{card.get('collector_number')})"
                )
                return card
            except ScryfallError as exc:
                print(f"  [scryfall] Tier-2 failed: {exc}")

        # Tier 3 — global fuzzy name
        if name:
            print(f"  [scryfall] Tier-3: global fuzzy search for '{name}'")
            return self.lookup_by_name(name)

        raise ScryfallError(
            "Cannot look up card: set_code/collector_number missing and no name available."
        )
