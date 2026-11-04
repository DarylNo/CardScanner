"""Tests for the SQLite scan store."""

import pytest

from server.store import ScanStore


@pytest.fixture
def store(tmp_path):
    s = ScanStore(tmp_path / "scans.db")
    yield s
    s.close()


def _mk(store, name="Lightning Bolt"):
    return store.create_scan(
        identified=True,
        card_read={"name": name, "condition_estimate": "NM"},
        confidence={"name": "high"},
        candidates=[{"id": "a", "set": "m10"}, {"id": "b", "set": "m11"}],
    )


def test_create_and_get(store):
    scan = _mk(store)
    assert scan["id"] >= 1
    assert scan["identified"] is True
    assert scan["status"] == "candidates"
    assert scan["included"] is True
    assert scan["card_read"]["name"] == "Lightning Bolt"
    assert scan["candidates"][0]["set"] == "m10"
    assert scan["selection"] is None

    fetched = store.get_scan(scan["id"])
    assert fetched["id"] == scan["id"]


def test_list_orders_newest_first(store):
    a = _mk(store, "A")
    b = _mk(store, "B")
    ids = [s["id"] for s in store.list_scans()]
    assert ids == [b["id"], a["id"]]


def test_update_selection_and_f2f(store):
    scan = _mk(store)
    selection = {"scryfall_id": "b", "set": "m11", "collector_number": "149",
                 "condition": "NM", "finish": "Non-Foil", "quantity": 1}
    updated = store.update_scan(
        scan["id"], status="selected", selection=selection,
        f2f={"conditions": {"NM": 3.49}},
    )
    assert updated["status"] == "selected"
    assert updated["selection"]["set"] == "m11"
    assert updated["f2f"]["conditions"]["NM"] == 3.49


def test_update_rejects_unknown_column(store):
    scan = _mk(store)
    with pytest.raises(ValueError):
        store.update_scan(scan["id"], bogus=1)


def test_included_selected_filters(store):
    s1 = _mk(store)
    s2 = _mk(store)
    store.update_scan(s1["id"], status="selected",
                      selection={"set": "m10", "collector_number": "146"})
    # s2 stays in 'candidates' -> excluded; s1 selected+included -> included
    sel = store.included_selected()
    assert [s["id"] for s in sel] == [s1["id"]]
    # excluding s1 removes it
    store.update_scan(s1["id"], included=False)
    assert store.included_selected() == []


def test_delete(store):
    scan = _mk(store)
    assert store.delete_scan(scan["id"]) is True
    assert store.get_scan(scan["id"]) is None
    assert store.delete_scan(9999) is False


def test_persists_across_reopen(tmp_path):
    path = tmp_path / "scans.db"
    s1 = ScanStore(path)
    scan = _mk(s1)
    s1.close()
    s2 = ScanStore(path)
    assert s2.get_scan(scan["id"])["card_read"]["name"] == "Lightning Bolt"
    s2.close()


def test_flag_defaults_off_and_round_trips(store):
    scan = _mk(store)
    assert scan["flagged"] is False
    assert store.update_scan(scan["id"], flagged=1)["flagged"] is True
    assert store.update_scan(scan["id"], flagged=False)["flagged"] is False


def test_a_database_from_before_the_flag_gains_the_column(tmp_path):
    import sqlite3
    path = tmp_path / "old.db"
    conn = sqlite3.connect(path)
    conn.executescript("""
        CREATE TABLE scans (
            id INTEGER PRIMARY KEY AUTOINCREMENT, created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'candidates',
            identified INTEGER NOT NULL DEFAULT 0, error TEXT,
            card_read TEXT NOT NULL DEFAULT '{}', confidence TEXT NOT NULL DEFAULT '{}',
            candidates TEXT NOT NULL DEFAULT '[]', selection TEXT, f2f TEXT,
            included INTEGER NOT NULL DEFAULT 1);
        INSERT INTO scans (created_at, updated_at) VALUES ('t', 't');
    """)
    conn.commit()
    conn.close()
    s = ScanStore(path)
    try:
        assert s.get_scan(1)["flagged"] is False          # old rows read as unflagged
        assert s.update_scan(1, flagged=True)["flagged"] is True
    finally:
        s.close()
    ScanStore(path).close()                               # re-opening doesn't re-add it


# ── the one-time List re-price (PRAGMA user_version 0 → 1) ─────────────────────
# The F2F matcher changed for The List and its kin in 1.1.17; answers the sweep
# recorded before it are never re-asked, so the store drops them once on open.

_SEL = {"condition": "NM", "finish": "Non-Foil", "quantity": 1, "foil": False}
_BOLT_A25 = {"id": "a25-bolt", "name": "Lightning Bolt", "set": "a25",
             "set_name": "Masters 25", "collector_number": "141"}
_BOLT_PLST = {"id": "plst-bolt", "name": "Lightning Bolt", "set": "plst",
              "set_name": "The List", "collector_number": "A25-141"}
_OLD_ROWS = [
    # 1: a selected List pick the old matcher never found → the empty marker
    dict(status="selected",
         selection={"scryfall_id": "plst-bolt", "name": "Lightning Bolt", "set": "plst",
                    "set_name": "The List", "collector_number": "A25-141", **_SEL},
         f2f={"conditions": {}, "searched": True},
         candidates=[{**_BOLT_PLST, "f2f_conditions": {}},
                     {**_BOLT_A25, "f2f_conditions": {"NM": 3.49}}]),
    # 2: a selected List pick filed ANOTHER card's price (the name-blind fallback)
    dict(status="selected",
         selection={"scryfall_id": "plst-mort", "name": "Mortuary", "set": "plst",
                    "set_name": "The List", "collector_number": "STH-66", **_SEL},
         f2f={"name": "Mortuary", "set_code": "plst", "collector_number": "STH-66",
              "foil": False, "handle": "buried-alive-tmp", "url": "https://f2f/b",
              "conditions": {"NM": 4.99, "PL": 3.99}},
         candidates=[{"id": "plst-mort", "name": "Mortuary", "set": "plst",
                      "collector_number": "STH-66"}]),
    # 3: unpicked — the original priced, its List twin recorded as "no listing"
    dict(status="candidates", selection=None, f2f=None,
         candidates=[{**_BOLT_A25, "f2f_conditions": {"NM": 3.49, "PL": 2.79}},
                     {**_BOLT_PLST, "f2f_conditions": {}}]),
    # 4: a selected ordinary printing — its price stays
    dict(status="selected",
         selection={"scryfall_id": "m10-bolt", "name": "Lightning Bolt", "set": "m10",
                    "set_name": "Magic 2010", "collector_number": "146", **_SEL},
         f2f={"name": "Lightning Bolt", "set_code": "m10", "collector_number": "146",
              "foil": False, "handle": "bolt-m10", "url": "https://f2f/m",
              "conditions": {"NM": 2.99}},
         candidates=[{"id": "m10-bolt", "name": "Lightning Bolt", "set": "m10",
                      "collector_number": "146", "f2f_conditions": {"NM": 2.99}}]),
    # 5: the set code compared lowercase (Scryfall's are; a hand-edited one may not be)
    dict(status="selected",
         selection={"scryfall_id": "mb2-saga", "name": "Urza's Saga", "set": "MB2",
                    "set_name": "Mystery Booster 2", "collector_number": "114", **_SEL},
         f2f={"conditions": {"NM": 59.99}},
         candidates=[]),
    # 6: unpicked — every other re-price set's candidate price drops, ust stays
    dict(status="candidates", selection=None, f2f=None,
         candidates=[{"id": "u1", "name": "Earl of Squirrel", "set": "ulst",
                      "collector_number": "108", "f2f_conditions": {"NM": 7.99}},
                     {"id": "u2", "name": "Earl of Squirrel", "set": "ust",
                      "collector_number": "108", "f2f_conditions": {"NM": 0.99}},
                     {"id": "c1", "name": "Slivdrazi Monstrosity", "set": "cmb1",
                      "collector_number": "102", "f2f_conditions": {"NM": 54.99}},
                     {"id": "c2", "name": "Slivdrazi Monstrosity", "set": "cmb2",
                      "collector_number": "102", "f2f_conditions": {"NM": 54.99}},
                     {"id": "m2", "name": "Uro, Titan of Nature's Wrath", "set": "mb2",
                      "collector_number": "93", "f2f_conditions": {"NM": 59.99}}]),
]


def _write_old_db(path, rows=_OLD_ROWS):
    """A store file as 1.1.16 and earlier left it: today's table, user_version 0."""
    import json
    import sqlite3
    from server.store import _SCHEMA
    conn = sqlite3.connect(path)
    conn.executescript(_SCHEMA)
    for i, r in enumerate(rows, 1):
        conn.execute(
            "INSERT INTO scans (created_at, updated_at, status, identified, card_read,"
            " confidence, candidates, selection, f2f) VALUES (?,?,?,?,?,?,?,?,?)",
            (f"2026-10-0{i}T10:00:00+00:00", f"2026-10-0{i}T11:00:00+00:00", r["status"], 1,
             json.dumps({"name": "x"}), json.dumps({"name": "high"}),
             json.dumps(r["candidates"]),
             None if r["selection"] is None else json.dumps(r["selection"]),
             None if r["f2f"] is None else json.dumps(r["f2f"])))
    conn.commit()
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 0
    conn.close()


def _raw_rows(path):
    import sqlite3
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    try:
        return {r["id"]: dict(r) for r in conn.execute("SELECT * FROM scans")}
    finally:
        conn.close()


def _user_version(path):
    import sqlite3
    conn = sqlite3.connect(path)
    try:
        return conn.execute("PRAGMA user_version").fetchone()[0]
    finally:
        conn.close()


def test_opening_an_old_store_drops_the_list_sets_f2f_answers_once(tmp_path):
    path = tmp_path / "old.db"
    _write_old_db(path)
    before = _raw_rows(path)
    s = ScanStore(path)
    try:
        rows = {r["id"]: r for r in s.list_scans()}
        # a selected re-price set's answer → NULL, so the sweep prices it again
        assert rows[1]["f2f"] is None                     # the empty marker
        assert rows[2]["f2f"] is None                     # Buried Alive's price
        assert rows[5]["f2f"] is None                     # "MB2", compared lowercase
        assert rows[4]["f2f"]["conditions"] == {"NM": 2.99}   # m10 kept
        # a re-price set's candidate answer → the key gone; every other kept
        assert "f2f_conditions" not in rows[1]["candidates"][0]
        assert rows[1]["candidates"][1]["f2f_conditions"] == {"NM": 3.49}
        assert rows[3]["candidates"][0]["f2f_conditions"] == {"NM": 3.49, "PL": 2.79}
        assert "f2f_conditions" not in rows[3]["candidates"][1]
        assert [("f2f_conditions" in c) for c in rows[6]["candidates"]] == \
            [False, True, False, False, False]
        assert rows[6]["candidates"][1]["f2f_conditions"] == {"NM": 0.99}
        assert rows[4]["candidates"][0]["f2f_conditions"] == {"NM": 2.99}
        # nothing else moves: not updated_at, status, selection or the rest
        after = _raw_rows(path)
        for sid, row in before.items():
            for col in row:
                if col in ("f2f", "candidates"):
                    continue
                assert after[sid][col] == row[col], (sid, col)
        assert after[4] == before[4]                      # untouched row, byte for byte
        assert _user_version(path) == 1
    finally:
        s.close()


def test_the_list_reprice_runs_exactly_once(tmp_path):
    path = tmp_path / "old.db"
    _write_old_db(path)
    s = ScanStore(path)
    new = {"name": "Lightning Bolt", "set_code": "plst", "collector_number": "A25-141",
           "foil": False, "handle": "bolt-plist", "url": "https://f2f/p",
           "conditions": {"NM": 3.49}}
    s.update_scan(1, f2f=new)
    cands = s.get_scan(3)["candidates"]
    cands[1]["f2f_conditions"] = {}                       # the NEW matcher's miss
    s.update_scan(3, candidates=cands)
    s.close()
    s2 = ScanStore(path)                                  # re-open: no second reset
    try:
        assert s2.get_scan(1)["f2f"] == new
        assert s2.get_scan(3)["candidates"][1]["f2f_conditions"] == {}
        assert _user_version(path) == 1
    finally:
        s2.close()


def test_a_fresh_store_is_stamped_without_a_reprice(tmp_path):
    path = tmp_path / "fresh.db"
    ScanStore(path).close()
    assert _user_version(path) == 1
    s = ScanStore(path)
    try:
        sid = s.create_scan(identified=True, card_read={}, confidence={},
                            candidates=[{**_BOLT_PLST, "f2f_conditions": {}}])["id"]
        s.update_scan(sid, status="selected", selection={**_BOLT_PLST, "set": "plst"},
                      f2f={"conditions": {}, "searched": True})
    finally:
        s.close()
    s = ScanStore(path)                                   # a new miss is never re-dropped
    try:
        assert s.get_scan(sid)["f2f"] == {"conditions": {}, "searched": True}
        assert s.get_scan(sid)["candidates"][0]["f2f_conditions"] == {}
    finally:
        s.close()


def test_the_sweep_reprices_exactly_what_the_reset_dropped(tmp_path):
    """End to end on the real app: the dropped answers are owed again (missing
    f2f_conditions reads exactly like None), the kept ones are not re-asked."""
    from fastapi.testclient import TestClient
    from server.app import create_app
    from tests.test_server import FakeF2F, FakePipeline

    class Recording(FakeF2F):
        def __init__(self):
            self.calls = []

        def get_price(self, name, set_code, cn, foil=False, set_name=""):
            self.calls.append((set_code, cn))
            return super().get_price(name, set_code, cn, foil, set_name)

    path = tmp_path / "old.db"
    _write_old_db(path)
    f2f = Recording()
    app = create_app(pipeline_factory=lambda: FakePipeline(), store=ScanStore(path), f2f=f2f,
                     scan_images_dir=tmp_path / "imgs", auto_sweep_interval=None)
    c = TestClient(app)
    assert c.post("/api/scans/price-missing").json()["queued"] == 8
    assert sorted(f2f.calls) == sorted([
        ("plst", "A25-141"), ("plst", "STH-66"), ("MB2", "114"),     # selections
        ("plst", "A25-141"),                                         # scan 3's twin
        ("ulst", "108"), ("cmb1", "102"), ("cmb2", "102"), ("mb2", "93")])
    assert c.post("/api/scans/price-missing").json()["queued"] == 0


def test_a_failed_reprice_rolls_back_and_the_store_still_opens(tmp_path, monkeypatch):
    """The store holds the only copy of the scans: a re-price that fails part
    way leaves every row and the version as they were (retried next open)."""
    import sqlite3
    import server.store as store_mod
    path = tmp_path / "old.db"
    _write_old_db(path)
    before = _raw_rows(path)
    real = store_mod._reprice_sets

    def half_then_fail(conn, sets):
        real(conn, sets)                                   # the UPDATEs ran …
        raise sqlite3.OperationalError("disk I/O error")   # … then the disk failed

    monkeypatch.setattr(store_mod, "_reprice_sets", half_then_fail)
    s = ScanStore(path)
    try:
        assert s.get_scan(1)["f2f"] == {"conditions": {}, "searched": True}
    finally:
        s.close()
    assert _raw_rows(path) == before
    assert _user_version(path) == 0
    monkeypatch.setattr(store_mod, "_reprice_sets", real)
    ScanStore(path).close()                                # the next open does it
    assert _raw_rows(path)[1]["f2f"] is None
    assert _user_version(path) == 1


def test_a_row_that_does_not_decode_is_left_alone(tmp_path):
    """One bad row never stops the re-price of the rest (nor the store opening)."""
    import sqlite3
    path = tmp_path / "old.db"
    _write_old_db(path)
    conn = sqlite3.connect(path)
    conn.execute("INSERT INTO scans (created_at, updated_at, candidates, selection, f2f)"
                 " VALUES ('t', 't', '[{\"set\": \"plst\", \"f2f_conditions\": {}', "
                 "'{\"set\": \"plst\"}', '{\"conditions\": {}}')")
    conn.commit()
    conn.close()
    before = _raw_rows(path)
    ScanStore(path).close()
    after = _raw_rows(path)
    assert after[7] == before[7]                          # torn JSON: untouched
    assert after[1]["f2f"] is None                        # the rest re-priced
    assert _user_version(path) == 1
