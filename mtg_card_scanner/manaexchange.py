"""
Mana Exchange (the user's own storefront) inventory-price client.

Unlike the Face to Face client this needs no scraping, pacing, or caching
heroics: Mana Exchange is our own Next.js app with a purpose-built batched
endpoint (/api/scanner/price-lookup), so one request covers every candidate
printing of a scan at once and rate limits are a non-issue.

Answers, per Scryfall id and finish: cheapest listed price, available
quantity, and cheapest price per condition — i.e. "do I already stock this
exact printing, and at what price."
"""

from __future__ import annotations

import os
from typing import Any, Optional

import requests

_DEFAULT_BASE = "https://www.manaexchange.ca"
_TIMEOUT = 8
_MAX_IDS = 40


class ManaExchangeClient:
    def __init__(self, base_url: Optional[str] = None,
                 session: Optional[requests.Session] = None) -> None:
        self.base_url = (base_url or os.getenv("MX_URL") or _DEFAULT_BASE).rstrip("/")
        self._session = session or requests.Session()
        self._session.headers["User-Agent"] = "MTGCardScanner/1.0 manaexchange"

    def get_prices(self, scryfall_ids: list[str]) -> dict[str, Any]:
        """
        {scryfall_id: {"nonfoil": {"price", "quantity", "conditions"},
                       "foil": {...}}} for ids with listed stock; ids without
        stock are simply absent. Returns {} on any failure — MX data is
        decision support, never a reason to break a scan.
        """
        ids = [i for i in dict.fromkeys(scryfall_ids) if i][:_MAX_IDS]
        if not ids:
            return {}
        try:
            r = self._session.get(
                f"{self.base_url}/api/scanner/price-lookup",
                params={"ids": ",".join(ids)}, timeout=_TIMEOUT)
            r.raise_for_status()
            return r.json().get("results", {}) or {}
        except Exception as exc:
            print(f"  [manaexchange] price lookup failed: {exc}")
            return {}
