"""Face to Face pricing — thin client over the ManaExchange price proxy.

The scanner does NOT scrape Face to Face itself. It asks the operator's own
ManaExchange backend, which runs the (Redis-cached) scraper server-side and
returns per-condition prices:

    GET {MX_URL}/api/scanner/f2f-price?name=&set=&collector=&finish=&setName=
      → {found, conditions:{NM:..,PL:..}, url, ...}

Keeping the scrape server-side means the scraping METHOD never ships in this
(public) repo, only a URL. Config via env:
    MX_URL            base URL of the ManaExchange app (default manaexchange.ca)
    SCANNER_F2F_TOKEN sent as x-scanner-token; the backend gates on it, so only
                      the owner's configured scanner can price.

Locally we still keep a 24h disk cache (repeat sets never re-hit the proxy),
the F2FUnavailableError ≠ "no listing" distinction (a 5xx/timeout stays
retryable; a clean "not found" records an empty marker), an interruptible
wait so Stop works, and the recent-request debug log — none of which reveal
anything about how prices are obtained.
"""

from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Optional

import requests

_DEFAULT_MX_URL = "https://www.manaexchange.ca"
_TIMEOUT = 15              # the proxy may scrape F2F live on a cache miss
_MIN_DELAY = 0.2           # be gentle to our OWN backend; cache hits are instant
_CACHE_TTL = 24 * 3600     # seconds — repeat sets never re-hit the proxy
_DEFAULT_CACHE_DIR = Path.home() / ".cache" / "mtg-card-scanner" / "facetoface"

# F2F condition code -> Mana Exchange condition code (coarser grades). Used only
# to pick ONE price for a chosen MX condition; the UI shows every condition.
F2F_TO_MX_CONDITION: dict[str, str] = {
    "NM": "NM", "PL": "LP", "LP": "LP", "MP": "MP", "HP": "HP", "DMG": "DMG",
}
_MX_CONDITION_FALLBACK: dict[str, list[str]] = {
    "NM": ["NM", "PL"],
    "LP": ["PL", "NM"],
    "MP": ["MP", "PL", "NM"],
    "HP": ["HP", "PL", "NM"],
    "DMG": ["DMG", "HP", "PL", "NM"],
}


class FaceToFaceError(Exception):
    pass


class F2FUnavailableError(Exception):
    """
    The price backend could not be reached / errored — the answer is UNKNOWN,
    not "no listing". Callers that persist results must catch this and leave
    the row UNSEARCHED so a later sweep retries; recording an empty marker
    here would freeze a transient blip into a permanent "not listed".
    """


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


def _default_get_json(cache_dir: Path,
                      interrupt: Optional[threading.Event] = None) -> Callable[..., Any]:
    """
    Build the price fetcher: disk-cached by URL, gentle pacing to our own
    backend, interruptible. Returns a callable get_json(url, headers) that
    yields parsed JSON, or raises requests exceptions on transport failure
    (get_price maps those to F2FUnavailableError).
    """
    session = requests.Session()
    session.headers["User-Agent"] = "MTGCardScanner/1.0"
    state = {"last": 0.0}
    lock = threading.Lock()
    cache_dir.mkdir(parents=True, exist_ok=True)
    events: deque = deque(maxlen=80)

    def _record(url: str, status: str) -> None:
        events.append({"t": time.time(), "url": url.split("/api/")[-1][:90],
                       "status": status, "wait_s": 0.0, "delay_s": _MIN_DELAY})

    def _wait(seconds: float) -> bool:
        if interrupt is not None:
            return interrupt.wait(seconds)
        time.sleep(seconds)
        return False

    def get_json(url: str, headers: Optional[dict] = None) -> Any:
        key = hashlib.sha1(url.encode()).hexdigest()
        cached = cache_dir / f"{key}.json"
        try:
            if cached.exists() and time.time() - cached.stat().st_mtime < _CACHE_TTL:
                data = json.loads(cached.read_text(encoding="utf-8"))
                _record(url, "cache")
                return data
        except (OSError, ValueError):
            pass                          # torn/corrupt → refetch below

        if interrupt is not None and interrupt.is_set():
            raise F2FUnavailableError("interrupted")
        with lock:
            elapsed = time.monotonic() - state["last"]
            if elapsed < _MIN_DELAY and _wait(_MIN_DELAY - elapsed):
                raise F2FUnavailableError("interrupted")
            state["last"] = time.monotonic()

        resp = session.get(url, headers=headers or {}, timeout=_TIMEOUT)
        resp.raise_for_status()
        data = resp.json()
        _record(url, str(resp.status_code))
        try:
            tmp = cached.with_suffix(f".{os.getpid()}.{threading.get_ident()}.tmp")
            tmp.write_text(json.dumps(data), encoding="utf-8")
            os.replace(tmp, cached)
        except OSError:
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass
        return data

    get_json.recent_events = lambda: list(events)      # UI: request-level debug
    return get_json


class FaceToFaceClient:
    """Fetch F2F prices via the ManaExchange proxy (no local scraping)."""

    def __init__(
        self,
        get_json: Optional[Callable[..., Any]] = None,
        cache_dir: Path = _DEFAULT_CACHE_DIR,
        base_url: Optional[str] = None,
        token: Optional[str] = None,
    ) -> None:
        self.base_url = (base_url or os.getenv("MX_URL") or _DEFAULT_MX_URL).rstrip("/")
        self.token = token if token is not None else os.getenv("SCANNER_F2F_TOKEN", "")
        # Cooperative cancel: set() aborts in-flight waits so Stop is instant.
        self.interrupt = threading.Event()
        self._get_json = get_json or _default_get_json(cache_dir, self.interrupt)

    def pacing_delay(self) -> Optional[float]:
        return _MIN_DELAY

    def recent_requests(self) -> list[dict[str, Any]]:
        fn = getattr(self._get_json, "recent_events", None)
        return fn() if fn else []

    def get_price(
        self,
        name: str,
        set_code: str,
        collector_number: str,
        foil: bool = False,
        set_name: str = "",
    ) -> Optional[F2FPrice]:
        """
        Ask the ManaExchange proxy for this printing's per-condition prices.

        Returns an F2FPrice, or None when the backend CONFIRMS no listing.
        Raises F2FUnavailableError when the backend can't be reached / errored
        (5xx, timeout, connection) — that answer is unknown, not empty, and
        must stay retryable.
        """
        if not name:
            return None

        from urllib.parse import urlencode
        params = urlencode({
            "name": name, "set": set_code, "collector": collector_number,
            "finish": "foil" if foil else "nonfoil", "setName": set_name,
        })
        url = f"{self.base_url}/api/scanner/f2f-price?{params}"
        headers = {"x-scanner-token": self.token} if self.token else {}

        try:
            data = self._get_json(url, headers)
        except F2FUnavailableError:
            raise
        except requests.HTTPError as exc:
            status = getattr(exc.response, "status_code", 0)
            if status in (400, 404):
                return None                # backend confirms: no such listing
            raise F2FUnavailableError(f"proxy HTTP {status}") from exc
        except (requests.RequestException, ValueError) as exc:
            raise F2FUnavailableError(f"proxy unreachable: {exc}") from exc

        conditions = {k.upper(): float(v)
                      for k, v in (data.get("conditions") or {}).items()}
        if not data.get("found") or not conditions:
            return None                    # confirmed unlisted
        return F2FPrice(
            name=name, set_code=set_code, collector_number=collector_number,
            foil=foil, handle="", url=data.get("url", ""), conditions=conditions,
        )
