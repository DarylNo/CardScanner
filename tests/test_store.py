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


# ── the one-time re-price (PRAGMA user_version 0 → 1) ──────────────────────────
# The F2F matcher changed for EVERY set in 1.1.17 (the set-name fallback was
# collector- and name-blind in every set, and titles with variant brackets never
# matched their own collector); an answer the sweep recorded before it is never
# re-asked, so the store drops every F2F answer once on open (the owner's call).

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
    # 3: unpicked — the original priced, its List twin recorded as "no listing",
    #    and the scan's OWN top-match price (scan time / the desktop's "Search F2F
    #    price" button) — the old matcher's answer too
    dict(status="candidates", selection=None,
         f2f={"name": "Lightning Bolt", "set_code": "a25", "collector_number": "141",
              "foil": False, "handle": "bolt-a25", "url": "https://f2f/a",
              "conditions": {"NM": 3.49, "PL": 2.79}},
         candidates=[{**_BOLT_A25, "f2f_conditions": {"NM": 3.49, "PL": 2.79}},
                     {**_BOLT_PLST, "f2f_conditions": {}}]),
    # 4: a selected ordinary printing — the old matcher's answer as well
    dict(status="selected",
         selection={"scryfall_id": "m10-bolt", "name": "Lightning Bolt", "set": "m10",
                    "set_name": "Magic 2010", "collector_number": "146", **_SEL},
         f2f={"name": "Lightning Bolt", "set_code": "m10", "collector_number": "146",
              "foil": False, "handle": "bolt-m10", "url": "https://f2f/m",
              "conditions": {"NM": 2.99}},
         candidates=[{"id": "m10-bolt", "name": "Lightning Bolt", "set": "m10",
                      "collector_number": "146", "f2f_conditions": {"NM": 2.99}}]),
    # 5: a hand-edited upper-case set code — no set is special any more
    dict(status="selected",
         selection={"scryfall_id": "mb2-saga", "name": "Urza's Saga", "set": "MB2",
                    "set_name": "Mystery Booster 2", "collector_number": "114", **_SEL},
         f2f={"conditions": {"NM": 59.99}},
         candidates=[]),
    # 6: unpicked — every candidate's answer drops, whatever its set
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
    # 7: a scan with no answer anywhere (no card found) — untouched, byte for byte
    dict(status="candidates", selection=None, f2f=None, candidates=[]),
]


def _stripped(cands):
    """*cands* as the re-price leaves them: only the f2f_conditions key gone."""
    return [{k: v for k, v in c.items() if k != "f2f_conditions"} for c in cands]


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


def test_opening_an_old_store_drops_every_f2f_answer_once(tmp_path):
    import json
    path = tmp_path / "old.db"
    _write_old_db(path)
    before = _raw_rows(path)
    s = ScanStore(path)
    try:
        rows = {r["id"]: r for r in s.list_scans()}
        # every scan's own answer → NULL: a selection's (so the sweep prices it
        # again) and an unpicked scan's top-match price — in every set
        assert {sid: r["f2f"] for sid, r in rows.items()} == {i: None for i in range(1, 8)}
        # every candidate's answer → the key gone, in every set
        assert [sid for sid, r in sorted(rows.items())
                if any("f2f_conditions" in c for c in r["candidates"])] == []
        # the candidates re-written with ONLY that key gone: every other key, the
        # key order and the JSON spelling as they were (row 2 never had one)
        after = _raw_rows(path)
        for sid, old in enumerate(_OLD_ROWS, 1):
            assert after[sid]["candidates"] == json.dumps(_stripped(old["candidates"])), sid
        # nothing else moves: not updated_at, status, selection or the rest
        for sid, row in before.items():
            for col in row:
                if col in ("f2f", "candidates"):
                    continue
                assert after[sid][col] == row[col], (sid, col)
        assert after[2]["candidates"] == before[2]["candidates"]
        assert after[7] == before[7]                      # untouched row, byte for byte
        assert _user_version(path) == 1
    finally:
        s.close()


def test_the_reprice_runs_exactly_once(tmp_path):
    path = tmp_path / "old.db"
    _write_old_db(path)
    s = ScanStore(path)
    assert s.get_scan(4)["f2f"] is None                   # the open dropped m10's answer …
    new = {"name": "Lightning Bolt", "set_code": "plst", "collector_number": "A25-141",
           "foil": False, "handle": "bolt-plist", "url": "https://f2f/p",
           "conditions": {"NM": 3.49}}
    m10 = {"name": "Lightning Bolt", "set_code": "m10", "collector_number": "146",
           "foil": False, "handle": "bolt-m10", "url": "https://f2f/m",
           "conditions": {"NM": 3.25}}
    s.update_scan(1, f2f=new)
    s.update_scan(4, f2f=m10)                             # … and the NEW matcher priced it
    cands = s.get_scan(3)["candidates"]
    cands[1]["f2f_conditions"] = {}                       # the NEW matcher's miss
    s.update_scan(3, candidates=cands)
    s.close()
    s2 = ScanStore(path)                                  # re-open: no second reset
    try:
        assert s2.get_scan(1)["f2f"] == new
        assert s2.get_scan(4)["f2f"] == m10
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
                            candidates=[{**_BOLT_PLST, "f2f_conditions": {}},
                                        {**_BOLT_A25, "f2f_conditions": {"NM": 3.49}}])["id"]
        s.update_scan(sid, status="selected", selection={**_BOLT_PLST, "set": "plst"},
                      f2f={"conditions": {}, "searched": True})
    finally:
        s.close()
    s = ScanStore(path)                                   # a new answer is never re-dropped
    try:
        assert s.get_scan(sid)["f2f"] == {"conditions": {}, "searched": True}
        assert [c["f2f_conditions"] for c in s.get_scan(sid)["candidates"]] == [{}, {"NM": 3.49}]
    finally:
        s.close()


def test_the_sweep_reprices_exactly_what_the_reset_dropped(tmp_path):
    """End to end on the real app: every dropped answer the sweep owns is owed
    again (a missing f2f_conditions reads exactly like None) — the 4 selections
    and the 7 candidates of the 2 unpicked scans, nothing else. A picked scan's
    candidates were never the sweep's to price (only its selection is), and an
    unpicked scan's own top-match f2f is not either (the sweep reads only its
    candidates): dropping those owes nothing."""
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
    store = ScanStore(path)
    app = create_app(pipeline_factory=lambda: FakePipeline(), store=store, f2f=f2f,
                     scan_images_dir=tmp_path / "imgs", auto_sweep_interval=None)
    c = TestClient(app)
    assert c.post("/api/scans/price-missing").json()["queued"] == 11
    assert sorted(f2f.calls) == sorted([
        ("plst", "A25-141"), ("plst", "STH-66"), ("m10", "146"), ("MB2", "114"),  # selections
        ("a25", "141"), ("plst", "A25-141"),                                    # scan 3's prints
        ("ulst", "108"), ("ust", "108"), ("cmb1", "102"), ("cmb2", "102"),      # scan 6's prints
        ("mb2", "93")])
    assert c.post("/api/scans/price-missing").json()["queued"] == 0
    assert store.get_scan(3)["f2f"] is None               # no sweep re-asks the top match
    assert all(r["f2f"] is not None for r in store.list_scans() if r["selection"])


def test_a_failed_reprice_rolls_back_and_the_store_still_opens(tmp_path, monkeypatch):
    """The store holds the only copy of the scans: a re-price that fails part
    way leaves every row and the version as they were (retried next open)."""
    import sqlite3
    import server.store as store_mod
    path = tmp_path / "old.db"
    _write_old_db(path)
    before = _raw_rows(path)
    real = store_mod._reprice_all

    def half_then_fail(conn):
        real(conn)                                         # the UPDATEs ran …
        raise sqlite3.OperationalError("disk I/O error")   # … then the disk failed

    monkeypatch.setattr(store_mod, "_reprice_all", half_then_fail)
    s = ScanStore(path)
    try:
        assert s.get_scan(1)["f2f"] == {"conditions": {}, "searched": True}
        assert s.get_scan(4)["f2f"]["conditions"] == {"NM": 2.99}
    finally:
        s.close()
    assert _raw_rows(path) == before
    assert _user_version(path) == 0
    monkeypatch.setattr(store_mod, "_reprice_all", real)
    ScanStore(path).close()                                # the next open does it
    assert _raw_rows(path)[4]["f2f"] is None
    assert _user_version(path) == 1


def test_a_row_that_does_not_decode_is_left_alone(tmp_path):
    """One bad row never stops the re-price of the rest (nor the store opening)."""
    import sqlite3
    path = tmp_path / "old.db"
    _write_old_db(path)
    conn = sqlite3.connect(path)
    conn.execute("INSERT INTO scans (created_at, updated_at, candidates, selection, f2f)"
                 " VALUES ('t', 't', '[{\"set\": \"m10\", \"f2f_conditions\": {}', "
                 "'{\"set\": \"m10\"}', '{\"conditions\": {}}')")
    conn.commit()
    conn.close()
    before = _raw_rows(path)
    ScanStore(path).close()
    after = _raw_rows(path)
    assert after[8] == before[8]                          # torn JSON: untouched
    assert after[4]["f2f"] is None                        # the rest re-priced
    assert _user_version(path) == 1
