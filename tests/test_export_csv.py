"""CSV export: the owner's column layout (desktop "Export to CSV" builder)."""

import csv
import io

import pytest
from fastapi.testclient import TestClient

from server.app import create_app
from server.export import (CSV_FIELDS, DEFAULT_LAYOUT, LayoutStore, build_csv,
                           f2f_price, normalize_layout)
from server.store import ScanStore


def _scan(sid, set_="otj", num="200", cond="NM", finish="Non-Foil", qty=1,
          name="Card", conditions=None, **sel):
    s = {"set": set_, "collector_number": num, "condition": cond, "finish": finish,
         "quantity": qty, "name": name, "set_name": "Set " + set_.upper(),
         "scryfall_id": f"id-{sid}"}
    s.update(sel)
    return {"id": sid, "created_at": f"2026-09-28T00:00:{sid:02d}+00:00", "selection": s,
            "f2f": {"conditions": conditions, "url": "/products/x"} if conditions else None}


def _rows(text, delim=","):
    return list(csv.reader(io.StringIO(text), delimiter=delim))


def _layout(fields, **kw):
    return normalize_layout({"columns": [{"field": f} for f in fields], **kw})


def test_columns_follow_the_layout_order_and_headers():
    lay = normalize_layout({"columns": [{"field": "collector_number", "header": "No."},
                                        {"field": "name"}, {"field": "quantity", "header": ""}]})
    out = _rows(build_csv([_scan(1, name="Opt", num="59")], lay))
    assert out == [["No.", "Name", "Quantity"], ["59", "Opt", "1"]]


def test_combine_sums_identical_printings_keeping_newest_first_order():
    scans = [_scan(3, num="1", qty=1), _scan(2, num="2"), _scan(1, num="1", qty=2),
             _scan(4, num="1", finish="Foil")]
    lay = _layout(["quantity", "collector_number", "finish", "scan_id"])
    assert _rows(build_csv(scans, lay))[1:] == [["3", "1", "Non-Foil", "3"],
                                                 ["1", "2", "Non-Foil", "2"],
                                                 ["1", "1", "Foil", "4"]]
    per_scan = _layout(["quantity", "collector_number"], combine=False)
    assert len(_rows(build_csv(scans, per_scan))) == 5


def test_price_uses_the_condition_fallback_and_total_multiplies():
    s = _scan(1, cond="LP", qty=3, conditions={"NM": 4.0, "PL": 2.5})
    assert f2f_price(s) == 2.5
    assert f2f_price(_scan(2, cond="DMG", conditions={"NM": 4.0})) == 4.0
    assert f2f_price(_scan(3)) is None
    out = _rows(build_csv([s, _scan(4, num="9")], _layout(["price", "total"])))
    assert out[1:] == [["2.50", "7.50"], ["", ""]]


def test_header_toggle_delimiters_and_quoting():
    lay = _layout(["name", "set_name"], header=False, delimiter="semicolon")
    text = build_csv([_scan(1, name="Borborygmos, Enraged")], lay)
    assert text == "Borborygmos, Enraged;Set OTJ\r\n"
    tab = _layout(["name"], delimiter="tab", header=False)
    assert build_csv([_scan(1, name='Say "Hi"')], tab) == '"Say ""Hi"""\r\n'


def test_formula_like_cells_are_neutralised():
    lay = _layout(["name"], header=False)
    assert build_csv([_scan(1, name="=HYPERLINK(1)")], lay) == "'=HYPERLINK(1)\r\n"


def test_every_field_renders_for_a_bare_selection():
    lay = _layout(list(CSV_FIELDS))
    out = _rows(build_csv([{"id": 1, "selection": {"set": "a", "collector_number": "1"}}], lay))
    assert len(out[1]) == len(CSV_FIELDS)


@pytest.mark.parametrize("bad", [None, {}, {"columns": []}, {"columns": [{"field": "nope"}]},
                                 {"columns": [{"field": "name"}], "delimiter": "pipe"}])
def test_bad_layouts_are_rejected(bad):
    with pytest.raises(ValueError):
        normalize_layout(bad)


def test_layout_store_round_trip_and_corrupt_file(tmp_path):
    p = tmp_path / "export_layout.json"
    st = LayoutStore(p)
    assert st.load() == DEFAULT_LAYOUT
    lay = _layout(["name"], header=False)
    st.save(lay)
    assert LayoutStore(p).load() == lay
    p.write_text("{not json")
    assert LayoutStore(p).load() == DEFAULT_LAYOUT


def test_endpoints_save_preview_and_download(tmp_path):
    store = ScanStore(tmp_path / "s.db")
    for num, name in (("1", "Lórien Revealed"), ("2", "Opt")):
        sid = store.create_scan(identified=True, card_read={}, confidence={}, candidates=[])["id"]
        store.update_scan(sid, status="selected", selection={
            "name": name, "set": "ltr", "collector_number": num, "condition": "NM",
            "finish": "Non-Foil", "quantity": 1})
    app = create_app(pipeline_factory=lambda: None, store=store, f2f=object(),
                     scan_images_dir=tmp_path / "img", auto_sweep_interval=None)
    c = TestClient(app)
    got = c.get("/api/export/layout").json()
    assert got["layout"] == DEFAULT_LAYOUT and len(got["fields"]) == len(CSV_FIELDS)
    assert c.put("/api/export/layout", json={"layout": {"columns": [{"field": "x"}]}}).status_code == 400
    lay = {"columns": [{"field": "name", "header": "Card"}], "header": True, "delimiter": "comma",
           "combine": True}
    assert c.put("/api/export/layout", json={"layout": lay}).json()["layout"] == lay
    assert (tmp_path / "export_layout.json").exists()
    assert c.get("/api/export/layout").json()["layout"] == lay
    pv = c.post("/api/export/preview", json={"layout": lay}).json()
    assert pv == {"csv": "Card\r\nOpt\r\nLórien Revealed\r\n", "rows": 2}
    r = c.get("/api/export.csv")
    assert r.headers["content-type"].startswith("text/csv")
    assert "cards.csv" in r.headers["content-disposition"]
    assert r.content == "\ufeffCard\r\nOpt\r\nLórien Revealed\r\n".encode("utf-8")
