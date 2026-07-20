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
