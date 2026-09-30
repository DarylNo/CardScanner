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
