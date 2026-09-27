"""
Mana Exchange bulk-import export.

Mana Exchange's admin mass-entry accepts pasted text, one card per line,
whitespace-delimited, no header:

    Qty SetCode CollectorNumber [Condition] [Finish]

e.g. ``2 OTJ 200 NM Foil``. It derives name, images, and pricing from Scryfall
via set+collector, so the export needs only these five columns. See
components/admin/mass-entry.tsx in the Mana Exchange repo.
"""

from __future__ import annotations

from typing import Any

# Accepted by Mana Exchange mass-entry (uppercased there anyway).
MX_CONDITIONS = {"NM", "LP", "MP", "HP", "DMG"}
# Finish tokens must contain no internal spaces (parser splits on whitespace).
MX_FINISHES = {"Non-Foil", "Foil", "Etched", "Textured", "Surge", "Galaxy", "Gilded"}


def selection_to_line(selection: dict[str, Any]) -> str:
    """Render one selected printing as a Mana Exchange mass-entry line."""
    qty = int(selection.get("quantity") or 1)
    set_code = str(selection.get("set") or selection.get("set_code") or "").upper()
    collector = str(selection.get("collector_number") or "").strip()
    condition = str(selection.get("condition") or "NM").upper()
    finish = str(selection.get("finish") or "Non-Foil").strip().replace(" ", "-")

    if not set_code or not collector:
        raise ValueError("selection missing set_code or collector_number")

    # Normalise a couple of common finish spellings to MX's canonical tokens.
    finish_norm = {"NONFOIL": "Non-Foil", "NON-FOIL": "Non-Foil", "FOIL": "Foil",
                   "ETCHED": "Etched"}.get(finish.upper(), finish)

    return f"{qty} {set_code} {collector} {condition} {finish_norm}"


def build_mx_export(selected_scans: list[dict[str, Any]]) -> str:
    """
    Build the full export text from selected scan rows.

    Each scan must carry a ``selection`` dict. Rows without a valid selection are
    skipped. Returns text with a trailing newline (empty string if nothing to
    export).

    Rows for the SAME printing+condition+finish are summed into one line: every
    scan keeps its own row in the app (scan order matters to the owner), but
    the import file stays one line per distinct card, exactly as it was when
    repeat copies merged into a single row's quantity. Rows arrive newest-first
    (store.list_scans), so lines follow each card's NEWEST scan.
    """
    totals: dict[tuple[str, str, str, str], int] = {}
    for scan in selected_scans:
        selection = scan.get("selection")
        if not selection:
            continue
        try:
            qty, set_code, collector, condition, finish = selection_to_line(selection).split(" ")
        except ValueError:
            continue
        key = (set_code, collector, condition, finish)
        totals[key] = totals.get(key, 0) + int(qty)
    lines = [f"{qty} {' '.join(key)}" for key, qty in totals.items()]
    return ("\n".join(lines) + "\n") if lines else ""
