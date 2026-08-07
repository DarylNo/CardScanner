"""Unit tests for build_result, format_listing, and Pipeline.run_demo."""

import json
from dataclasses import replace
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from mtg_card_scanner.card_read import CardRead
from mtg_card_scanner.pipeline import Pipeline
from mtg_card_scanner.output import build_result, format_listing, OutputWriter, ScanResult


_SAMPLE_READ = CardRead(
    name="Lightning Bolt",
    set_code="m10",
    collector_number="146",
    foil=False,
    language="en",
    condition_estimate="NM",
    condition_reason="No visible wear; corners are sharp.",
)

_SAMPLE_SCRYFALL = {
    "name": "Lightning Bolt",
    "set": "m10",
    "set_name": "Magic 2010",
    "collector_number": "146",
    "type_line": "Instant",
    "rarity": "common",
    "prices": {"usd": "0.50", "usd_foil": "2.00"},
    "scryfall_uri": "https://scryfall.com/card/m10/146/lightning-bolt",
}


class TestBuildResult:
    def test_fields_populated(self):
        r = build_result(_SAMPLE_READ, _SAMPLE_SCRYFALL)
        assert r.scryfall_name == "Lightning Bolt"
        assert r.scryfall_set_name == "Magic 2010"
        assert r.condition == "NM"
        assert r.price_usd == "0.50"
        assert r.price_usd_foil == "2.00"
        assert r.foil is False

    def test_timestamp_not_empty(self):
        r = build_result(_SAMPLE_READ, _SAMPLE_SCRYFALL)
        assert r.timestamp  # non-empty string

    def test_missing_prices_are_none(self):
        no_prices = {**_SAMPLE_SCRYFALL, "prices": {}}
        r = build_result(_SAMPLE_READ, no_prices)
        assert r.price_usd is None
        assert r.price_usd_foil is None


class TestFormatListing:
    def test_contains_name(self):
        listing = format_listing(build_result(_SAMPLE_READ, _SAMPLE_SCRYFALL))
        assert "Lightning Bolt" in listing

    def test_contains_condition(self):
        listing = format_listing(build_result(_SAMPLE_READ, _SAMPLE_SCRYFALL))
        assert "NM" in listing

    def test_contains_usd_price(self):
        listing = format_listing(build_result(_SAMPLE_READ, _SAMPLE_SCRYFALL))
        assert "$0.50" in listing

    def test_foil_shows_foil_tag_and_foil_price(self):
        foil_read = replace(_SAMPLE_READ, foil=True)
        listing = format_listing(build_result(foil_read, _SAMPLE_SCRYFALL))
        assert "[FOIL]" in listing
        assert "$2.00" in listing

    def test_no_foil_tag_on_non_foil(self):
        listing = format_listing(build_result(_SAMPLE_READ, _SAMPLE_SCRYFALL))
        assert "[FOIL]" not in listing

    def test_na_when_no_price(self):
        no_prices = {**_SAMPLE_SCRYFALL, "prices": {}}
        listing = format_listing(build_result(_SAMPLE_READ, no_prices))
        assert "N/A" in listing


class TestPipelineDemo:
    def _make_pipeline(self):
        mock_scryfall = MagicMock()
        mock_scryfall.lookup.return_value = _SAMPLE_SCRYFALL
        return Pipeline(index=None, scryfall=mock_scryfall), mock_scryfall

    def test_returns_scan_result(self):
        pipeline, _ = self._make_pipeline()
        result = pipeline.run_demo(_SAMPLE_READ)
        assert isinstance(result, ScanResult)
        assert result.scryfall_name == "Lightning Bolt"

    def test_calls_scryfall_lookup_with_correct_args(self):
        pipeline, mock_scryfall = self._make_pipeline()
        pipeline.run_demo(_SAMPLE_READ)
        mock_scryfall.lookup.assert_called_once_with("m10", "146", "Lightning Bolt")

    def test_uses_default_demo_card_when_no_read_provided(self):
        pipeline, mock_scryfall = self._make_pipeline()
        pipeline.run_demo()
        # Default card is Lightning Bolt from M10
        args = mock_scryfall.lookup.call_args[0]
        assert args[0] == "m10"

    def test_writer_called_when_provided(self, tmp_path):
        mock_scryfall = MagicMock()
        mock_scryfall.lookup.return_value = _SAMPLE_SCRYFALL
        writer = OutputWriter(tmp_path / "out.csv")
        pipeline = Pipeline(index=None, scryfall=mock_scryfall, writer=writer)
        pipeline.run_demo(_SAMPLE_READ)
        assert (tmp_path / "out.csv").exists()


class TestOutputWriter:
    def test_csv_created_with_header(self, tmp_path):
        path = tmp_path / "out.csv"
        writer = OutputWriter(path)
        result = build_result(_SAMPLE_READ, _SAMPLE_SCRYFALL)
        writer.append(result)
        content = path.read_text(encoding="utf-8")
        assert "scryfall_name" in content
        assert "Lightning Bolt" in content

    def test_csv_appends_multiple_rows(self, tmp_path):
        path = tmp_path / "out.csv"
        writer = OutputWriter(path)
        result = build_result(_SAMPLE_READ, _SAMPLE_SCRYFALL)
        writer.append(result)
        writer.append(result)
        lines = path.read_text(encoding="utf-8").strip().splitlines()
        assert len(lines) == 3  # header + 2 data rows

    def test_json_output(self, tmp_path):
        path = tmp_path / "out.json"
        writer = OutputWriter(path)
        result = build_result(_SAMPLE_READ, _SAMPLE_SCRYFALL)
        writer.append(result)
        writer.append(result)
        records = json.loads(path.read_text(encoding="utf-8"))
        assert len(records) == 2
        assert records[0]["scryfall_name"] == "Lightning Bolt"


# ── art-decisive printing pick ────────────────────────────────────────────────

def _cd(id, multi):
    return {"id": id, "multi_distance": multi}


def test_art_decisive_marks_wide_gap():
    from mtg_card_scanner.pipeline import _mark_art_decisive
    cands = [_cd("a", 118), _cd("b", 174)]        # the Willow Faerie case
    _mark_art_decisive(cands)
    assert cands[0].get("art_decisive") is True


def test_art_decisive_rejects_narrow_gap_and_noise():
    from mtg_card_scanner.pipeline import _mark_art_decisive
    close = [_cd("a", 100), _cd("b", 108)]        # same-art cluster (gap 8 =
    _mark_art_decisive(close)                     # worst observed WRONG pick)
    assert "art_decisive" not in close[0]
    noisy = [_cd("a", 150), _cd("b", 200)]        # top above the ceiling
    _mark_art_decisive(noisy)
    assert "art_decisive" not in noisy[0]
    single = [_cd("a", 90)]
    _mark_art_decisive(single)                    # no runner-up, no crash
    assert "art_decisive" not in single[0]


def test_art_decisive_defers_to_ocr():
    from mtg_card_scanner.pipeline import _mark_art_decisive
    cands = [{"id": "a", "multi_distance": 100, "ocr_confirmed": True},
             _cd("b", 200)]
    _mark_art_decisive(cands)
    assert "art_decisive" not in cands[0]         # OCR already settled it
