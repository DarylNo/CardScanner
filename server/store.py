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


# The one-time re-price (PRAGMA user_version 0 → 1, release 1.1.17): EVERY F2F
# answer recorded before it is dropped once, and the sweep prices again from the
# scanner's own connection (the phone's, in the app) at its usual pacing — a
# one-time catch-up; new scans still jump the queue.
# The owner chose all, not just The List's sets: the 1.1.17 matcher change
# (facetoface.py) reaches every set. The old set-name fallback was collector- and
# name-blind in EVERY set — an unstocked card filed another card of the same
# set's price (Mortuary [STH-66] → Buried Alive's $4.99) — and a title with
# variant brackets ("Lightning Bolt [401] [Rulebook Showcase] [Commander
# Legends: Battle for Baldur's Gate] [Non-Foil]", about a third of F2F's titles)
# never matched its own collector, so a showcase / borderless / Secret Lair
# printing often holds the regular printing's price. The List's prints were
# never found under their own SKUs at all (plst = MB1 / FMB1 / PLIST / PLST,
# ulst = UPLIST, cmb2 = CMB1 + NOPW). And the sweep never re-asks an answer it
# recorded, so without this none of that would reach a card already scanned.


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _reprice_all(conn: sqlite3.Connection) -> tuple[int, int, int]:
    """Drop every F2F answer recorded, in place: a scan's ``f2f`` column →
    NULL (a selection's price, owed again; an unpicked scan's own top-match
    price from scan time or the desktop's "Search F2F price" — the sweep reads
    only its candidates, so that one is owed nowhere), and every candidate's
    ``f2f_conditions`` key → removed (the sweep reads a missing key exactly
    like None — owed on an unpicked scan; a picked scan prices only its
    selection). Writes only those two columns, only
    on rows that change: updated_at, status, the selection and everything else
    stay as they were. A row whose JSON does not decode is left alone. Returns
    (selections, unpicked scans' own prices, candidate prints) reset. The
    caller holds the transaction."""
    n_sel = n_top = n_cand = 0
    rows = conn.execute("SELECT id, selection, f2f, candidates FROM scans").fetchall()
    for sid, sel_raw, f2f_raw, cands_raw in rows:
        try:
            sel = json.loads(sel_raw) if sel_raw not in (None, "") else None
            cands = json.loads(cands_raw) if cands_raw not in (None, "") else None
        except ValueError:
            continue
        if f2f_raw is not None:
            conn.execute("UPDATE scans SET f2f = NULL WHERE id = ?", (sid,))
            if isinstance(sel, dict) and sel:
                n_sel += 1
            else:
                n_top += 1
        if isinstance(cands, list):
            dropped = 0
            for c in cands:
                if isinstance(c, dict) and "f2f_conditions" in c:
                    del c["f2f_conditions"]
                    dropped += 1
            if dropped:
                conn.execute("UPDATE scans SET candidates = ? WHERE id = ?",
                             (json.dumps(cands), sid))
                n_cand += dropped
    return n_sel, n_top, n_cand


# The one-time re-ask (PRAGMA user_version 1 → 2, release 1.1.18): every "no F2F
# listing" answer recorded is dropped once, so the sweep asks again with the
# fixed matcher; every PRICE recorded stays. 1.1.17's matcher (and its
# re-price, above) priced no meld half: F2F titles one "Midnight Scavengers //
# Chittering Host [96a] [B] [Eldritch Moon] [Non-Foil]" — a side letter
# Scryfall's #96 doesn't carry — so the owner's Midnight Scavengers EMN 96 and
# Graf Rats EMN 91 were recorded as "no listing" (owner, 2026-11-05: "my price
# checks of cards are failing"), and the sweep never re-asks a recorded miss.
# A promo whose collector follows a label ("[Prerelease] [59s]") was found
# before 1.1.18 too, but by F2F's order, so a price it recorded may be another
# version's — those are not touched here (a price is never dropped by this
# migration; the owner asked for the misses).


def _reask_misses(conn: sqlite3.Connection) -> tuple[int, int, int]:
    """Drop every recorded F2F MISS, in place: a scan's ``f2f`` that is the
    sweep's "no listing" marker (a dict whose ``conditions`` is empty —
    ``{"conditions": {}, "searched": true}``) → NULL (a selection's: owed
    again), and every candidate's ``f2f_conditions`` that is ``{}`` → the key
    removed (the sweep reads a missing key exactly like None — owed on an
    unpicked scan; a picked scan prices only its selection). Every price
    (non-empty conditions) stays. Writes only those two columns, only on rows
    that change: updated_at, status, the selection and everything else stay
    as they were. A row whose JSON does not decode is left alone. Returns
    (selections, unpicked scans' own answers, candidate prints) reset. The
    caller holds the transaction."""
    n_sel = n_top = n_cand = 0
    rows = conn.execute("SELECT id, selection, f2f, candidates FROM scans").fetchall()
    for sid, sel_raw, f2f_raw, cands_raw in rows:
        try:
            sel = json.loads(sel_raw) if sel_raw not in (None, "") else None
            f2f = json.loads(f2f_raw) if f2f_raw not in (None, "") else None
            cands = json.loads(cands_raw) if cands_raw not in (None, "") else None
        except ValueError:
            continue
        if isinstance(f2f, dict) and f2f.get("conditions") == {}:
            conn.execute("UPDATE scans SET f2f = NULL WHERE id = ?", (sid,))
            if isinstance(sel, dict) and sel:
                n_sel += 1
            else:
                n_top += 1
        if isinstance(cands, list):
            dropped = 0
            for c in cands:
                if isinstance(c, dict) and c.get("f2f_conditions", None) == {}:
                    del c["f2f_conditions"]
                    dropped += 1
            if dropped:
                conn.execute("UPDATE scans SET candidates = ? WHERE id = ?",
                             (json.dumps(cands), sid))
                n_cand += dropped
    return n_sel, n_top, n_cand


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
        empty table and is stamped too), in order: a 1.1.16 store runs the
        re-price, commits, then the re-ask. A failure rolls back that one and
        leaves the version as it was — the later ones wait, all retried on the
        next open; the store still opens."""
        def version() -> int:
            return self._conn.execute("PRAGMA user_version").fetchone()[0]

        # Looked up at each open (module globals), never bound at import.
        steps = (
            (1, "re-price", _reprice_all,
             "{0} selection(s) and {2} candidate print(s) dropped their F2F answer, "
             "{1} unpicked scan(s) their top-match price"),
            (2, "re-ask", _reask_misses,
             "{0} selection(s) and {2} candidate print(s) dropped their \"no F2F listing\" "
             "answer, {1} unpicked scan(s) their own — asked again"),
        )
        for stamp, what, run, done in steps:
            if version() >= stamp:
                continue
            try:
                # IMMEDIATE + re-read: a second process opening the same file
                # waits, then sees the stamp and does nothing.
                self._conn.execute("BEGIN IMMEDIATE")
                counts = (0, 0, 0)
                if version() < stamp:
                    counts = run(self._conn)
                    self._conn.execute(f"PRAGMA user_version = {stamp}")
                self._conn.commit()
            except sqlite3.Error as exc:
                if self._conn.in_transaction:
                    self._conn.rollback()
                print(f"  [store] {what} failed, retried on the next open: {exc}")
                return
            if any(counts):
                print(f"  [store] {what}: " + done.format(*counts))

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
