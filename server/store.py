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
    included    INTEGER NOT NULL DEFAULT 1
);
"""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


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
            self._conn.commit()

    # ── serialization helpers ──────────────────────────────────────────────────

    @staticmethod
    def _row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
        d = dict(row)
        for f in _JSON_FIELDS:
            raw = d.get(f)
            d[f] = json.loads(raw) if raw not in (None, "") else None
        d["identified"] = bool(d["identified"])
        d["included"] = bool(d["included"])
        return d

    @staticmethod
    def _encode(field: str, value: Any) -> Any:
        if field in _JSON_FIELDS:
            return None if value is None else json.dumps(value)
        if field in ("identified", "included"):
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
            "candidates", "selection", "f2f", "included",
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
