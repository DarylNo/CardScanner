"""Tests for the Mana Exchange export format."""

import pytest

from server.export import selection_to_line, build_mx_export, MX_CONDITIONS


def _sel(**kw):
    base = dict(set="otj", collector_number="200", condition="NM",
                finish="Foil", quantity=2)
    base.update(kw)
    return base


def test_selection_to_line_basic():
    assert selection_to_line(_sel()) == "2 OTJ 200 NM Foil"


def test_selection_to_line_defaults():
    line = selection_to_line({"set": "mkm", "collector_number": "123"})
    assert line == "1 MKM 123 NM Non-Foil"


def test_selection_to_line_normalizes_finish():
    assert selection_to_line(_sel(finish="nonfoil")).endswith(" Non-Foil")
    assert selection_to_line(_sel(finish="foil")).endswith(" Foil")
    # a finish with an internal space must collapse to a single token
    assert selection_to_line(_sel(finish="Non Foil")).endswith(" Non-Foil")


def test_selection_to_line_uppercases_set_and_condition():
    line = selection_to_line(_sel(set="dmu", condition="lp"))
    assert line == "2 DMU 200 LP Foil"


def test_selection_to_line_requires_set_and_collector():
    with pytest.raises(ValueError):
        selection_to_line({"collector_number": "5"})
    with pytest.raises(ValueError):
        selection_to_line({"set": "mkm"})


def test_build_mx_export_skips_rows_without_selection():
    scans = [
        {"selection": _sel(set="otj", collector_number="200")},
        {"selection": None},
        {"selection": _sel(set="mh3", collector_number="88", condition="MP", finish="Etched", quantity=3)},
    ]
    out = build_mx_export(scans)
    assert out == "2 OTJ 200 NM Foil\n3 MH3 88 MP Etched\n"


def test_build_mx_export_empty():
    assert build_mx_export([]) == ""


def test_conditions_match_mx():
    assert MX_CONDITIONS == {"NM", "LP", "MP", "HP", "DMG"}


def test_export_lines_are_parseable_like_mana_exchange():
    # Mimic MX parser: split(/[\s\t]+/), require >= 3 tokens.
    out = build_mx_export([{"selection": _sel()}])
    for line in out.strip().splitlines():
        parts = line.split()
        assert len(parts) >= 3
        assert parts[0].isdigit()  # qty
