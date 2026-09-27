"""
Printing identification from the card's own text — the collector-line OCR.

pHash ranking separates frame ERAS but is measurably blind to same-art,
same-frame reprints (validated on a real scan: 256-bit region hashes and
set-symbol template matching both produced pure noise across 11 printings of
Diabolic Edict). The printing's identity is, however, PRINTED ON THE CARD:
modern cards carry "087/254 · MH1 · EN" in the bottom strip. This module
OCRs that strip (pip-only RapidOCR — no system tesseract, keeps the
one-command install story) and matches candidate SET CODES against it with
an OCR-confusion-tolerant comparator. Old frames have no such line, OCR
finds nothing, and ranking stays purely visual — exactly the cases where
visual ranking already works.

Everything degrades silently: missing dependency, unreadable strip, or an
ambiguous match leaves the art ranking untouched.
"""

from __future__ import annotations

import re
from typing import Any, Optional

# OCR confusion classes: two characters compare equal when they share a class.
_CONFUSION = {
    "1": "1", "I": "1", "L": "1", "|": "1",
    "0": "0", "O": "0", "Q": "0", "D": "0",
    "5": "5", "S": "5",
    "2": "2", "Z": "2",
    "8": "8", "B": "8",
    "6": "6", "G": "6",
}


def _canon(s: str) -> str:
    """Uppercase, alphanumeric-only, confusion classes collapsed."""
    out = []
    for ch in s.upper():
        if ch.isalnum():
            out.append(_CONFUSION.get(ch, ch))
    return "".join(out)


_STRIP_Y = (0.88, 0.99)     # bottom strip of the 630x880 warp
_STRIP_X = (0.02, 0.60)     # collector + set-code lines live bottom-left

_ocr_engine = None
_ocr_status: Optional[dict] = None


def ocr_status() -> dict:
    """
    {"available": bool, "error": str | None} — whether the OCR engine imports.

    read_bottom_strip swallows every failure (a missing optional engine must
    never break a scan), which also made a MISSING engine indistinguishable
    from "read nothing": a git-checkout rig updated by `git pull` never got
    rapidocr-onnxruntime installed when it became a dependency, and every
    printing silently went unconfirmed (the 2026-09 Fling / AKH #132 miss).
    /api/health surfaces this and the desktop warns; the retro-OCR pass waits
    for it instead of marking scans done.

    Checks that the package is INSTALLED (find_spec) rather than importing it:
    importing loads the ONNX runtime, whose native thread pool aborted the
    process at interpreter exit (CI: exit 134 after every test passed) — a
    health check must not start a 100 MB engine. An engine that is installed
    but fails to load (e.g. a Windows DLL error) is recorded by
    read_bottom_strip the first time it tries, via _mark_unavailable.
    """
    global _ocr_status
    if _ocr_status is None:
        import importlib.util
        try:
            found = importlib.util.find_spec("rapidocr_onnxruntime") is not None
        except (ImportError, ValueError):
            found = False
        if found:
            _ocr_status = {"available": True, "error": None}
        else:
            _mark_unavailable("rapidocr_onnxruntime is not installed")
    return _ocr_status


def _mark_unavailable(error: str) -> None:
    global _ocr_status
    _ocr_status = {"available": False, "error": error}
    print(f"  [ocr] collector-line OCR unavailable — {error} (fix: pip install -e .)")


def read_bottom_strip(card_bgr) -> str:
    """
    OCR the card's bottom-left strip and return one canonicalized blob.
    Runs two preprocessing variants (measured on a real rig scan: color
    upscale reads the digits best, Otsu binarization reads the set-code
    line best) and unions the text. Returns "" when OCR is unavailable
    or finds nothing.
    """
    global _ocr_engine
    try:
        import cv2
        if _ocr_engine is None:
            try:
                from rapidocr_onnxruntime import RapidOCR
                _ocr_engine = RapidOCR()
            except Exception as exc:          # missing package or native load failure
                _mark_unavailable(f"{type(exc).__name__}: {exc}")
                return ""

        h, w = card_bgr.shape[:2]
        strip = card_bgr[int(h * _STRIP_Y[0]):int(h * _STRIP_Y[1]),
                         int(w * _STRIP_X[0]):int(w * _STRIP_X[1])]
        gray = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
        _, otsu = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        variants = [
            cv2.resize(strip, None, fx=2.5, fy=2.5, interpolation=cv2.INTER_CUBIC),
            cv2.resize(otsu, None, fx=4, fy=4, interpolation=cv2.INTER_CUBIC),
        ]
        texts: list[str] = []
        for img in variants:
            result, _ = _ocr_engine(img)
            for row in (result or []):
                texts.append(str(row[1]))
        return _canon(" ".join(texts))
    except Exception:
        return ""


def _find(blob: str, token: str) -> bool:
    """True if canonical *token* appears in canonical *blob*."""
    t = _canon(token)
    return len(t) >= 2 and t in blob


def match_printing(blob: str, candidates: list[dict[str, Any]]) -> Optional[str]:
    """
    Return the scryfall id of the ONE candidate whose set code appears in the
    OCR blob — or None when zero or multiple match (ambiguity never guesses).

    The List nuance: List cards print their ORIGINAL set's code inside the
    collector number ("A25-85"), so a plain set-code hit would misattribute
    a List copy to the original set. When the blob also contains a matched
    candidate's code followed by "-<digits>" that equals another candidate's
    collector number, that other candidate wins.
    """
    if not blob:
        return None
    blob = _canon(blob)      # idempotent — accept raw or canonical input

    # 1. Compound collector numbers ("A25-85", "2019-2") are the most
    #    specific token a card prints — an exact hit wins outright. This is
    #    what keeps a List copy (prints its ORIGINAL set's code) from being
    #    misattributed to the original set.
    compound = [c for c in candidates
                if "-" in str(c.get("collector_number", ""))
                and len(_canon(c.get("collector_number", ""))) >= 4
                and _canon(c.get("collector_number", "")) in blob]
    if len(compound) == 1:
        return compound[0].get("id")

    # 2. Unique set-code hit.
    hits = [c for c in candidates if _find(blob, c.get("set", ""))]
    if len(hits) == 1:
        return hits[0].get("id")

    # 3. Several set hits (or same set twice) — collector number decides.
    if len(hits) > 1:
        coll_hits = [c for c in hits
                     if len(_canon(c.get("collector_number", ""))) >= 2
                     and _canon(c.get("collector_number", "")) in blob]
        if len(coll_hits) == 1:
            return coll_hits[0].get("id")
    return None
