"""
The List's twins: a List printing and the printing it reproduces.

The List (Scryfall set ``plst``, 5,741 printings; Scryfall folded the old
Mystery Booster ``mb1``/``fmb1`` sets into it) reprints EXACTLY its original
printing — same art, same frame, and the same collector line: a List
Lightning Bolt ``plst A25-141`` prints "141/249 U" / "A25 • EN", never
"PLST" and never the compound number. Its only mark is a small planeswalker
icon at the bottom-left. Scryfall's plst collector number is the original's
set code, a dash, and the original's collector number
(``original.set.upper() + "-" + original.collector_number`` for 5,736 of the
5,741, measured 2026-11-04); the exceptions are JUD-78†/119†/127† (originals
jud 78/119/127), M19-185j (the Japanese twin of m19 185), and CHK-160 (the
originals are 160a/160b) and LCI-1 (a token), which stay unmatched.

So the collector line cannot tell a List copy from its original (OCR), and
neither can the art hash: a pair of twins is one printing as far as the
camera is concerned. Every decision that would pick one of them on that
evidence must treat the pair as ambiguous instead (ocr_id.match_printing,
pipeline._mark_art_decisive), and both must reach the candidate list
together (pipeline._ranked_candidates) so a human can choose.

Pure string logic, ported verbatim to the phone (core ListTwins.kt).
"""

from __future__ import annotations

from typing import Any

LIST_SETS = frozenset({"plst"})

# What may follow the original's collector number in the List's: nothing,
# or the "†" (JUD-78† ↔ jud 78) / "j" (M19-185j ↔ m19 185) Scryfall adds.
_SUFFIXES = ("", "†", "j")


def _field(c: dict[str, Any], key: str) -> str:
    return str(c.get(key, ""))


def is_list_printing(c: dict[str, Any]) -> bool:
    """True for a printing of a List set (``plst``, any case)."""
    return _field(c, "set").lower() in LIST_SETS


def is_list_twin(t: dict[str, Any], x: dict[str, Any]) -> bool:
    """
    True when List printing *t* reproduces printing *x*: *t* in a List set,
    *x* not; *t*'s collector number split at its FIRST "-" gives the original
    set code (case-insensitive) and number — *x*'s number exactly, or with the
    "†"/"j" Scryfall appends. An EXACT string compare, never through the OCR
    canonicalisation (a confusion class would pair 1 with I, 0 with O).
    """
    if not is_list_printing(t) or is_list_printing(x):
        return False
    head, sep, num = _field(t, "collector_number").partition("-")
    xs, xn = _field(x, "set"), _field(x, "collector_number")
    if not sep or not head or not xs or not xn:
        return False
    return head.upper() == xs.upper() and num in tuple(xn + s for s in _SUFFIXES)


def twins(a: dict[str, Any], b: dict[str, Any]) -> bool:
    """Symmetric: either one is the List twin of the other."""
    return is_list_twin(a, b) or is_list_twin(b, a)


def has_twin_among(c: dict[str, Any], candidates: list[dict[str, Any]]) -> bool:
    """True when some candidate is *c*'s List twin (either direction)."""
    return any(twins(c, o) for o in candidates)


def with_twins(ranked: list, top_n: int) -> list:
    """
    ``ranked[:top_n]``, plus every List twin partner of a candidate in that
    slice that ranked BELOW it: each inserted right after its partner, in
    ranked order, once. A partner already in the slice stays where it is; a
    partner not in *ranked* at all (the 120-printing ranking cap dropped it)
    is not added. The result may hold more than *top_n* printings.

    Same-art reprints compress into hash noise, so a twin can rank anywhere
    in a big same-art group (Sol Ring's plst C21-263 ranked #17 of 120) —
    outside the slice, the original's collector line would OCR-confirm the
    original with nothing beside it to make it ambiguous, and a human could
    not even pick the List copy.
    """
    n = max(0, min(top_n, len(ranked)))
    taken = [i < n for i in range(len(ranked))]
    out = []
    for i in range(n):
        out.append(ranked[i])
        for j in range(n, len(ranked)):
            if not taken[j] and twins(ranked[i], ranked[j]):
                taken[j] = True
                out.append(ranked[j])
    return out
