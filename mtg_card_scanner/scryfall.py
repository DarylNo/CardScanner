"""Scryfall API client with rate-limiting and fuzzy-name fallback."""

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
        """Exact lookup — the most reliable path when set_code + collector_number are known."""
        url = f"{SCRYFALL_BASE}/cards/{set_code.lower()}/{collector_number}"
        return self._get(url)

    def lookup_by_name(self, name: str) -> dict[str, Any]:
        """Fuzzy name search — fallback for older cards or when collector info is unreadable."""
        return self._get(f"{SCRYFALL_BASE}/cards/named", fuzzy=name)

    def lookup(self, set_code: str, collector_number: str, name: str) -> dict[str, Any]:
        """
        Try exact set+collector lookup first; fall back to fuzzy name search.
        Raises ScryfallError if both attempts fail.
        """
        if set_code and collector_number:
            try:
                return self.lookup_by_set_collector(set_code, collector_number)
            except ScryfallError as exc:
                print(f"  Set/collector lookup failed ({exc}), trying name search…")

        if name:
            return self.lookup_by_name(name)

        raise ScryfallError(
            "Cannot look up card: set_code/collector_number missing and no name available."
        )
