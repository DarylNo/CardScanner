"""
SQLite-backed store for a scanning session.

Each scanned card is one row: the vision read, the art-ranked candidates, the
user's chosen printing, the Face to Face price, and an ``included`` flag for
export. Persisting to a file means an accidental server restart doesn't lose the
session. Pure stdlib (``sqlite3``) — no extra dependency.
"""

from __future__ import annotations

import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

# Columns whose values are dict/list and are stored as JSON text.
_JSON_FIELDS = ("card_read", "confidence", "candidates", "selection", "f2f")

_SCHEMA = """
CREATE TABLE IF NOT EXISTS scans (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'candidates',  -- candidates | selected
    identified  INTEGER NOT NULL DEFAULT 0,
    error       TEXT,
    card_read   TEXT NOT NULL DEFAULT '{}',
    confidence  TEXT NOT NULL DEFAULT '{}',
    candidates  TEXT NOT NULL DEFAULT '[]',
    selection   TEXT,                                 -- JSON or NULL
    f2f         TEXT,                                 -- JSON or NULL
    included    INTEGER NOT NULL DEFAULT 1,
    flagged     INTEGER NOT NULL DEFAULT 0            -- a guest asked for deletion
);
"""


# The one-time re-price (PRAGMA user_version 0 → 1, release 1.1.17). The F2F
# matcher changed for exactly these sets in that release (facetoface.py's
# _SKU_SET_RULES and the narrowed set-name fallback): The List (plst — Scryfall
# folded mb1/fmb1 into it), the Unfinity List (ulst), Mystery Booster 2 (mb2)
# and the Mystery Booster playtest cards (cmb1, cmb2). Before it a List print
# was never found (an empty "searched" marker for good) or filed ANOTHER card's
# price through the name-blind fallback (Mortuary [STH-66] → Buried Alive's
# $4.99), and the sweep never re-asks an answer it recorded. So those answers
# are dropped once and the sweep prices them again; every other set's answer
# came from the same matching before and after, and is kept.
REPRICE_SETS = frozenset({"plst", "ulst", "mb2", "cmb1", "cmb2"})


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _reprice_sets(conn: sqlite3.Connection, sets: frozenset) -> tuple[int, int]:
    """Drop the F2F answers recorded for printings of *sets*, in place: a
    selection's ``f2f`` column → NULL, a candidate's ``f2f_conditions`` key →
    removed (the sweep reads a missing key exactly like None — both are owed).
    Writes only those two columns, only on rows that change: updated_at,
    status, the selection and everything else stay as they were. A row whose
    JSON does not decode is left alone. Returns (selections, candidates)
    reset. The caller holds the transaction."""
    n_sel = n_cand = 0
    rows = conn.execute("SELECT id, selection, f2f, candidates FROM scans").fetchall()
    for sid, sel_raw, f2f_raw, cands_raw in rows:
        try:
            sel = json.loads(sel_raw) if sel_raw not in (None, "") else None
            cands = json.loads(cands_raw) if cands_raw not in (None, "") else None
        except ValueError:
            continue
        if (f2f_raw is not None and isinstance(sel, dict)
                and str(sel.get("set") or "").lower() in sets):
            conn.execute("UPDATE scans SET f2f = NULL WHERE id = ?", (sid,))
            n_sel += 1
        if isinstance(cands, list):
            dropped = 0
            for c in cands:
                if (isinstance(c, dict) and "f2f_conditions" in c
                        and str(c.get("set") or "").lower() in sets):
                    del c["f2f_conditions"]
                    dropped += 1
            if dropped:
                conn.execute("UPDATE scans SET candidates = ? WHERE id = ?",
                             (json.dumps(cands), sid))
                n_cand += dropped
    return n_sel, n_cand


class ScanStore:
    def __init__(self, db_path: str | Path = "scans.db") -> None:
        self.db_path = str(db_path)
        # check_same_thread=False + a lock: FastAPI runs sync handlers in a
        # threadpool, so the single connection may be touched by many threads.
        self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock:
            self._conn.executescript(_SCHEMA)
            # Databases made before the flag existed: add the column (NOT NULL
            # DEFAULT 0 fills every existing row as unflagged).
            cols = {r[1] for r in self._conn.execute("PRAGMA table_info(scans)")}
            if "flagged" not in cols:
                self._conn.execute(
                    "ALTER TABLE scans ADD COLUMN flagged INTEGER NOT NULL DEFAULT 0")
            self._conn.commit()
            self._migrate()

    def _migrate(self) -> None:
        """Data migrations by ``PRAGMA user_version``, each in ONE transaction
        with its stamp, so each runs exactly once (a fresh file runs them on an
        empty table and is stamped too). A failure rolls back and leaves the
        version as it was — retried on the next open; the store still opens."""
        def version() -> int:
            return self._conn.execute("PRAGMA user_version").fetchone()[0]

        if version() >= 1:
            return
        try:
            # IMMEDIATE + re-read: a second process opening the same file
            # waits, then sees the stamp and does nothing.
            self._conn.execute("BEGIN IMMEDIATE")
            n_sel = n_cand = 0
            if version() < 1:
                n_sel, n_cand = _reprice_sets(self._conn, REPRICE_SETS)
                self._conn.execute("PRAGMA user_version = 1")
            self._conn.commit()
        except sqlite3.Error as exc:
            if self._conn.in_transaction:
                self._conn.rollback()
            print(f"  [store] List re-price failed, retried on the next open: {exc}")
            return
        if n_sel or n_cand:
            print(f"  [store] List re-price: {n_sel} selection(s) and "
                  f"{n_cand} candidate print(s) owed a price again")

    # ── serialization helpers ──────────────────────────────────────────────────

    @staticmethod
    def _row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
        d = dict(row)
        for f in _JSON_FIELDS:
            raw = d.get(f)
            d[f] = json.loads(raw) if raw not in (None, "") else None
        d["identified"] = bool(d["identified"])
        d["included"] = bool(d["included"])
        d["flagged"] = bool(d["flagged"])
        return d

    @staticmethod
    def _encode(field: str, value: Any) -> Any:
        if field in _JSON_FIELDS:
            return None if value is None else json.dumps(value)
        if field in ("identified", "included", "flagged"):
            return int(bool(value))
        return value

    # ── CRUD ───────────────────────────────────────────────────────────────────

    def create_scan(
        self,
        *,
        identified: bool,
        card_read: dict,
        confidence: dict,
        candidates: list,
        error: Optional[str] = None,
    ) -> dict[str, Any]:
        now = _now()
        with self._lock:
            cur = self._conn.execute(
                """INSERT INTO scans
                   (created_at, updated_at, status, identified, error,
                    card_read, confidence, candidates)
                   VALUES (?,?,?,?,?,?,?,?)""",
                (
                    now, now, "candidates", int(identified), error,
                    json.dumps(card_read or {}),
                    json.dumps(confidence or {}),
                    json.dumps(candidates or []),
                ),
            )
            self._conn.commit()
            scan_id = cur.lastrowid
        return self.get_scan(scan_id)

    def list_scans(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM scans ORDER BY id DESC"
            ).fetchall()
        return [self._row_to_dict(r) for r in rows]

    def get_scan(self, scan_id: int) -> Optional[dict[str, Any]]:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM scans WHERE id = ?", (scan_id,)
            ).fetchone()
        return self._row_to_dict(row) if row else None

    def update_scan(self, scan_id: int, **fields: Any) -> Optional[dict[str, Any]]:
        """Update whitelisted columns; dict/list fields are JSON-encoded."""
        allowed = {
            "status", "identified", "error", "card_read", "confidence",
            "candidates", "selection", "f2f", "included", "flagged",
        }
        sets, values = [], []
        for key, val in fields.items():
            if key not in allowed:
                raise ValueError(f"cannot update column {key!r}")
            sets.append(f"{key} = ?")
            values.append(self._encode(key, val))
        if not sets:
            return self.get_scan(scan_id)
        sets.append("updated_at = ?")
        values.append(_now())
        values.append(scan_id)
        with self._lock:
            self._conn.execute(
                f"UPDATE scans SET {', '.join(sets)} WHERE id = ?", values
            )
            self._conn.commit()
        return self.get_scan(scan_id)

    def delete_scan(self, scan_id: int) -> bool:
        with self._lock:
            cur = self._conn.execute("DELETE FROM scans WHERE id = ?", (scan_id,))
            self._conn.commit()
            return cur.rowcount > 0

    def included_selected(self) -> list[dict[str, Any]]:
        """Scans that are selected AND flagged for export (for the export file)."""
        return [
            s for s in self.list_scans()
            if s["included"] and s["status"] == "selected" and s.get("selection")
        ]

    def close(self) -> None:
        with self._lock:
            self._conn.close()
