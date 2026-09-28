"""
Exports: the Mana Exchange bulk-import text, and a CSV whose columns the
owner lays out in the desktop "Export to CSV" builder.

Mana Exchange bulk-import export.

Mana Exchange's admin mass-entry accepts pasted text, one card per line,
whitespace-delimited, no header:

    Qty SetCode CollectorNumber [Condition] [Finish]

e.g. ``2 OTJ 200 NM Foil``. It derives name, images, and pricing from Scryfall
via set+collector, so the export needs only these five columns. See
components/admin/mass-entry.tsx in the Mana Exchange repo.
"""

from __future__ import annotations

import csv
import io
import json
import os
import re
from pathlib import Path
from typing import Any, Optional

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


# ── CSV export: the owner lays out the columns (desktop "Export to CSV") ────


_MX_TO_F2F = {"NM": ("NM", "PL"), "LP": ("PL", "NM"), "MP": ("MP", "PL", "NM"),
              "HP": ("HP", "PL", "NM"), "DMG": ("DMG", "HP", "PL", "NM")}


def f2f_price(scan: dict[str, Any]) -> Optional[float]:
    """The F2F price for the selection's condition — the same fallback the
    pages' f2fBest() shows (and facetoface.price_for_mx_condition): the
    condition's own listing first, then the nearest, else the cheapest."""
    conds = ((scan.get("f2f") or {}).get("conditions")) or {}
    if not conds:
        return None
    cond = str((scan.get("selection") or {}).get("condition") or "NM").upper()
    for k in _MX_TO_F2F.get(cond, ("NM",)):
        if conds.get(k) is not None:
            return float(conds[k])
    return float(min(conds.values()))


def _money(v: Optional[float]) -> str:
    return "" if v is None else f"{v:.2f}"


def _finish(sel: dict[str, Any]) -> str:
    f = str(sel.get("finish") or "Non-Foil").strip()
    return {"NONFOIL": "Non-Foil", "NON-FOIL": "Non-Foil", "NON FOIL": "Non-Foil",
            "FOIL": "Foil", "ETCHED": "Etched"}.get(f.upper(), f)


def _pop(sel: dict[str, Any]) -> str:
    p = sel.get("popularity") or {}
    return str(p.get("label") or "") if isinstance(p, dict) else ""


# field → (default header, value(scan, selection, qty)). Order = the order the
# builder lists them in. Keys are the saved-layout contract: never rename one.
CSV_FIELDS: dict[str, tuple[str, Any]] = {
    "quantity":         ("Quantity", lambda s, sel, q: str(q)),
    "name":             ("Name", lambda s, sel, q: str(sel.get("name") or "")),
    "set_code":         ("Set Code", lambda s, sel, q: str(sel.get("set") or "").upper()),
    "set_name":         ("Set Name", lambda s, sel, q: str(sel.get("set_name") or "")),
    "collector_number": ("Collector Number", lambda s, sel, q: str(sel.get("collector_number") or "")),
    "condition":        ("Condition", lambda s, sel, q: str(sel.get("condition") or "NM").upper()),
    "finish":           ("Finish", lambda s, sel, q: _finish(sel)),
    "foil":             ("Foil", lambda s, sel, q: "Yes" if _finish(sel) != "Non-Foil" else "No"),
    "price":            ("Price", lambda s, sel, q: _money(f2f_price(s))),
    "total":            ("Total", lambda s, sel, q: _money(None if f2f_price(s) is None
                                                           else round(f2f_price(s) * q, 2))),
    "popularity":       ("Popularity", lambda s, sel, q: _pop(sel)),
    "scryfall_id":      ("Scryfall ID", lambda s, sel, q: str(sel.get("scryfall_id") or "")),
    "scan_id":          ("Scan #", lambda s, sel, q: str(s.get("id") or "")),
    "scanned_at":       ("Scanned At", lambda s, sel, q: str(s.get("created_at") or "")),
    "auto_picked":      ("Auto Picked", lambda s, sel, q: "Yes" if sel.get("auto_picked") else "No"),
    "f2f_url":          ("F2F Link", lambda s, sel, q: (
        ("https://www.facetofacegames.com" + u) if (u := str((s.get("f2f") or {}).get("url") or ""))
        and u.startswith("/") else u)),
}

DELIMITERS = {"comma": ",", "semicolon": ";", "tab": "\t"}

DEFAULT_LAYOUT: dict[str, Any] = {
    "columns": [{"field": f, "header": CSV_FIELDS[f][0]} for f in
                ("quantity", "name", "set_code", "set_name", "collector_number",
                 "condition", "finish", "price")],
    "header": True,
    "delimiter": "comma",
    "combine": True,
}


def normalize_layout(raw: Any) -> dict[str, Any]:
    """Validate a layout from the builder; raises ValueError with a message
    the page can show. Headers default to the field's own name."""
    if not isinstance(raw, dict):
        raise ValueError("layout must be an object")
    cols = raw.get("columns")
    if not isinstance(cols, list) or not cols:
        raise ValueError("add at least one column")
    if len(cols) > 40:
        raise ValueError("too many columns (40 max)")
    out_cols = []
    for c in cols:
        if not isinstance(c, dict) or not isinstance(c.get("field"), str) \
                or c["field"] not in CSV_FIELDS:
            raise ValueError(f"unknown column: {c.get('field') if isinstance(c, dict) else c!r}")
        header = str(c.get("header") if c.get("header") is not None else "").strip()[:80]
        out_cols.append({"field": c["field"], "header": header or CSV_FIELDS[c["field"]][0]})
    delim = raw.get("delimiter", "comma")
    if delim not in DELIMITERS:
        raise ValueError(f"unknown delimiter: {delim!r}")
    return {"columns": out_cols, "header": bool(raw.get("header", True)),
            "delimiter": delim, "combine": bool(raw.get("combine", True))}


_FORMULA = re.compile(r"^[=+@\t\r]")


def _cell(v: str) -> str:
    # Spreadsheet formula injection: a card field starting with = + @ would be
    # evaluated by Excel/Sheets. No real card field does; neutralise if one ever
    # does rather than trust it.
    return "'" + v if _FORMULA.match(v) else v


def build_csv(selected_scans: list[dict[str, Any]], layout: dict[str, Any],
              limit: Optional[int] = None) -> str:
    """Render selected scans with *layout* (normalize_layout'd).

    combine=True sums identical printing+condition+finish rows into one line
    (like the Mana Exchange export), keeping the first-seen (newest) scan's
    other fields; combine=False is one line per scan, in list order.
    """
    rows: list[tuple[dict, dict, int]] = []
    index: dict[tuple, int] = {}
    for scan in selected_scans:
        sel = scan.get("selection")
        if not sel:
            continue
        q = int(sel.get("quantity") or 1)
        if layout["combine"]:
            key = (str(sel.get("set") or "").upper(), str(sel.get("collector_number") or ""),
                   str(sel.get("condition") or "NM").upper(), _finish(sel))
            if key in index:
                s0, sel0, q0 = rows[index[key]]
                # Newest copy stays the row, but its price may not have landed
                # yet: take the first copy that HAS one (same printing, same
                # condition+finish, so the same price).
                if f2f_price(s0) is None and f2f_price(scan) is not None:
                    s0 = {**s0, "f2f": scan.get("f2f")}
                rows[index[key]] = (s0, sel0, q0 + q)
                continue
            index[key] = len(rows)
        rows.append((scan, sel, q))
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=DELIMITERS[layout["delimiter"]], lineterminator="\r\n")
    cols = layout["columns"]
    if layout["header"]:
        w.writerow([_cell(c["header"]) for c in cols])
    for scan, sel, q in rows[:limit] if limit is not None else rows:
        w.writerow([_cell(CSV_FIELDS[c["field"]][1](scan, sel, q)) for c in cols])
    return buf.getvalue()


class LayoutStore:
    """The saved CSV layout — one per server (the owner's), a JSON file
    beside the scan database so it survives restarts and updates. path=None
    (an in-memory database) keeps it in memory only."""

    def __init__(self, path: Optional[str | Path]) -> None:
        self.path = Path(path) if path else None
        self._mem: Optional[dict[str, Any]] = None

    def load(self) -> dict[str, Any]:
        if self.path is None:
            return json.loads(json.dumps(self._mem or DEFAULT_LAYOUT))
        try:
            return normalize_layout(json.loads(self.path.read_text(encoding="utf-8")))
        except (OSError, ValueError, TypeError):
            return json.loads(json.dumps(DEFAULT_LAYOUT))

    def save(self, layout: dict[str, Any]) -> None:
        if self.path is None:
            self._mem = layout
            return
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(layout, indent=1), encoding="utf-8")
        os.replace(tmp, self.path)
