"""
External card list — a read-only list of card NAMES with tags ("wanted",
"sold_out", …) that the owner points the scanner at (owner, 2026-10-12).

Generic and read-only on purpose: no store branding, nothing is ever written
back. The URL lives in the phone's settings (never in the repo, which is
public); any key lives in its query string. Empty URL = feature off: nothing
fetched, no `list_tags` on any scan, no chips.

Contract (README.md → "External card list"):

    {"generated_at": "<ISO>",
     "cards": [{"name": "<card name>", "tags": ["wanted", "sold_out"]}]}

Unknown fields are ignored; a tag is any non-empty string (the pages know
`wanted` and `sold_out`, anything else shows as a plain chip of its text).

Matching is by NAME, any printing: both sides go through normalize_name()
(accents stripped, curly quotes and en/em dashes made plain, lowercase, " // "
between faces, whitespace collapsed) and a scan matches an entry when the full
name OR any face matches the entry's full name or any of its faces.

Tags are evaluated at READ time (flag_list_tags on every /api/scans answer),
never stored on the scan, so a refreshed list re-tags every existing scan.
They are a yes/no fact beside the popularity chip and never move a tier.

CardListSource fetches (a plain GET of the URL exactly as entered), keeps the
last good list on disk, and on an error (offline, HTTP error, bad JSON) keeps
serving that cache and reports the error. The Kotlin port (core/CardList.kt)
is held to this file by CardListParityTest on
scripts/export_card_list_fixtures.py's fixtures.
"""

from __future__ import annotations

import json
import re
import threading
import unicodedata
from pathlib import Path
from typing import Any, Callable, Optional

# The tags the pages draw as their own chips, in display order; any other tag
# follows them in the order the list first gives it.
KNOWN_TAGS = ("wanted", "sold_out")

_QUOTES = {"‘": "'", "’": "'", "‚": "'", "‛": "'", "′": "'",
           "“": '"', "”": '"', "„": '"', "‟": '"', "″": '"'}
_DASHES = {"–": "-", "—": "-"}
_TRANSLATE = str.maketrans({**_QUOTES, **_DASHES})
# Whitespace spelled out (not Python's \s, not Java's) so the port matches exactly.
_WS = re.compile("[\t\n\u000b\u000c\r \u001c-\u001f\u0085   - "
                 "    　]+")
_EDGE_WS = re.compile(f"^{_WS.pattern}|{_WS.pattern}$")
_FACES = re.compile(" ?// ?")
FACE_SEP = " // "


def normalize_name(name: str) -> str:
    """The comparable form of a card name (see the module docstring)."""
    s = unicodedata.normalize("NFD", name)
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn")
    s = s.translate(_TRANSLATE).lower()
    s = _WS.sub(" ", s).strip(" ")
    return _FACES.sub(FACE_SEP, s).strip(" ")


def _strip(s: str) -> str:
    """Trim the spelled-out whitespace only (str.strip's idea of it is Python's own)."""
    return _EDGE_WS.sub("", s)


def name_keys(name: str) -> list[str]:
    """The full normalised name, then each face (a DFC / split / adventure card)."""
    full = normalize_name(name)
    if not full:
        return []
    keys = [full]
    for face in full.split(FACE_SEP):
        face = face.strip(" ")
        if face and face not in keys:
            keys.append(face)
    return keys


class CardListError(ValueError):
    """A list that could not be used; str() is the short reason shown to the owner."""


def parse_list(text: str) -> dict[str, Any]:
    """The list body → {"generated_at", "count", "index": {key: [tags]}}.

    Raises CardListError("not JSON" | "no cards list"). Entries without a usable
    name are skipped; `count` is the entries kept."""
    try:
        doc = json.loads(text)
    except (ValueError, TypeError):
        raise CardListError("not JSON") from None
    if not isinstance(doc, dict) or not isinstance(doc.get("cards"), list):
        raise CardListError("no cards list")
    gen = doc.get("generated_at")
    index: dict[str, list[str]] = {}
    count = 0
    for entry in doc["cards"]:
        if not isinstance(entry, dict) or not isinstance(entry.get("name"), str):
            continue
        keys = name_keys(entry["name"])
        if not keys:
            continue
        count += 1
        raw = entry.get("tags")
        tags = []
        for t in raw if isinstance(raw, list) else []:
            if isinstance(t, str) and _strip(t) and _strip(t) not in tags:
                tags.append(_strip(t))
        for k in keys:
            have = index.setdefault(k, [])
            for t in tags:
                if t not in have:
                    have.append(t)
    return {"generated_at": gen if isinstance(gen, str) else None, "count": count, "index": index}


def tags_for(name: Optional[str], index: dict[str, list[str]]) -> list[str]:
    """Every tag the list gives *name* (full name or any face), known tags first."""
    found: list[str] = []
    for k in name_keys(name or ""):
        for t in index.get(k, ()):
            if t not in found:
                found.append(t)
    return [t for t in KNOWN_TAGS if t in found] + [t for t in found if t not in KNOWN_TAGS]


def scan_card_name(scan: dict[str, Any]) -> Optional[str]:
    """The name the scan is: its pick, else its top candidate, else what was read."""
    for src in (scan.get("selection"), (scan.get("candidates") or [None])[0], scan.get("card_read")):
        if isinstance(src, dict) and isinstance(src.get("name"), str) and _strip(src["name"]):
            return src["name"]
    return None


def flag_list_tags(scan: dict[str, Any], lst: Optional[dict[str, Any]]) -> dict[str, Any]:
    """Set scan["list_tags"] from the current list, in place. *lst* None (the
    feature off) leaves the scan untouched — no key, so the pages show nothing."""
    if lst is not None:
        scan["list_tags"] = tags_for(scan_card_name(scan), lst["index"])
    return scan


class CardListSource:
    """The URL, the last good list (cached on disk) and the last error.

    fetch(url) -> (status_code, body_text) or raises OSError (unreachable).
    clock() -> the time to stamp, as text. Never raises from refresh()."""

    def __init__(self, url: str, cache_path: Path,
                 fetch: Callable[[str], tuple[int, str]],
                 clock: Callable[[], str]):
        self._lock = threading.Lock()
        self._fetch = fetch
        self._clock = clock
        self.cache_path = Path(cache_path)
        self.url = ""
        self.list: Optional[dict[str, Any]] = None
        self.updated_at: Optional[str] = None
        self.error: Optional[str] = None
        self.error_at: Optional[str] = None
        self.set_url(url)

    def set_url(self, url: str) -> None:
        """A new URL: the cache of another URL is not this list."""
        url = (url or "").strip()
        with self._lock:
            if url == self.url and (self.list is not None or not url):
                return
            self.url, self.list, self.updated_at, self.error, self.error_at = url, None, None, None, None
            if not url:
                return
            try:
                cached = json.loads(self.cache_path.read_text(encoding="utf-8"))
                if cached.get("url") == url:
                    self.list = parse_list(cached["body"])
                    self.updated_at = cached.get("fetched_at")
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                pass

    def current(self) -> Optional[dict[str, Any]]:
        """The list to tag with: None when off; an empty list until the first fetch lands."""
        with self._lock:
            if not self.url:
                return None
            return self.list or {"generated_at": None, "count": 0, "index": {}}

    def refresh(self) -> bool:
        """GET the URL; a good answer replaces the list and the cache, anything
        else keeps them and records the error. True when a new list landed."""
        url = self.url
        if not url:
            return False
        try:
            code, body = self._fetch(url)
            if code != 200:
                raise CardListError(f"HTTP {code}")
            lst = parse_list(body)
        except CardListError as e:
            return self._failed(url, str(e))
        except OSError:
            return self._failed(url, "unreachable")
        now = self._clock()
        with self._lock:
            if url != self.url:
                return False
            self.list, self.updated_at, self.error, self.error_at = lst, now, None, None
        try:
            self.cache_path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.cache_path.with_suffix(".tmp")
            tmp.write_text(json.dumps({"url": url, "fetched_at": now, "body": body}), encoding="utf-8")
            tmp.replace(self.cache_path)
        except OSError:
            pass        # the list is in memory; the next good fetch caches it
        return True

    def _failed(self, url: str, reason: str) -> bool:
        now = self._clock()
        with self._lock:
            if url == self.url:
                self.error, self.error_at = reason, now
        return False

    def status(self) -> dict[str, Any]:
        """For Settings / Diagnostics. Never the URL (it may carry a key)."""
        with self._lock:
            return {"enabled": bool(self.url),
                    "count": self.list["count"] if self.list else 0,
                    "generated_at": self.list["generated_at"] if self.list else None,
                    "updated_at": self.updated_at,
                    "error": self.error, "error_at": self.error_at}
