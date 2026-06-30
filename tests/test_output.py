"""Unit tests for output.py — format_listing, build_result, foil price selection."""

import pytest

from mtg_card_scanner.output import ScanResult, build_result, format_listing
from mtg_card_scanner.vision import CardRead


def _card_read(**overrides) -> CardRead:
    defaults = dict(name="Lightning Bolt", set_code="m11", collector_number="149",
                    foil=False, language="en", condition_estimate="NM",
                    condition_reason="No visible wear.")
    return CardRead(**{**defaults, **overrides})


def _scryfall_card(**overrides) -> dict:
    defaults = dict(name="Lightning Bolt", set_name="Magic 2011", type_line="Instant",
                    rarity="common", collector_number="149",
                    prices={"usd": "1.00", "usd_foil": "8.50"},
                    scryfall_uri="https://scryfall.com/card/m11/149")
    return {**defaults, **overrides}


class TestFormatListingFoilPrice:
    def test_foil_uses_foil_price(self):
        result = build_result(_card_read(foil=True), _scryfall_card())
        listing = format_listing(result)
        assert "$8.50" in listing
        assert "[FOIL]" in listing

    def test_non_foil_uses_regular_price(self):
        result = build_result(_card_read(foil=False), _scryfall_card())
        listing = format_listing(result)
        assert "$1.00" in listing
        assert "[FOIL]" not in listing

    def test_foil_falls_back_to_regular_when_foil_price_none(self):
        card = _scryfall_card(prices={"usd": "1.00", "usd_foil": None})
        result = build_result(_card_read(foil=True), card)
        listing = format_listing(result)
        assert "$1.00" in listing
        assert "[FOIL]" in listing  # tag still shown, just no foil price

    def test_foil_falls_back_when_foil_price_absent(self):
        card = _scryfall_card(prices={"usd": "1.00"})
        result = build_result(_card_read(foil=True), card)
        listing = format_listing(result)
        assert "$1.00" in listing

    def test_no_price_shows_na(self):
        card = _scryfall_card(prices={})
        result = build_result(_card_read(), card)
        listing = format_listing(result)
        assert "N/A" in listing


class TestBuildResultLanguage:
    def test_language_stored_as_given(self):
        # Pipeline normalises before calling build_result; check it's passed through.
        result = build_result(_card_read(language="ja"), _scryfall_card())
        assert result.language == "ja"

    def test_empty_scryfall_card(self):
        # When Scryfall fails completely, build_result should not crash.
        result = build_result(_card_read(), {})
        assert result.scryfall_name == ""
        assert result.price_usd is None
        assert result.scryfall_uri == ""

    def test_partial_result_has_na_price(self):
        result = build_result(_card_read(), {})
        listing = format_listing(result)
        assert "N/A" in listing

    def test_collector_number_falls_back_to_scryfall(self):
        # If vision didn't read a collector number, use Scryfall's.
        result = build_result(_card_read(collector_number=""), _scryfall_card())
        assert result.collector_number == "149"

    def test_resolved_collector_number_overrides_misread(self):
        # The vision model misread "1234567890" (OCR garbage on a blurry
        # frame) but Scryfall successfully resolved the actual printing
        # (e.g. via visual match) — the listing must show the RESOLVED
        # printing's real collector number, not the garbled read.
        result = build_result(
            _card_read(collector_number="1234567890"),
            _scryfall_card(collector_number="289★s"),
        )
        assert result.collector_number == "289★s"

    def test_collector_number_uses_read_when_lookup_totally_failed(self):
        # Lookup found nothing at all (empty dict) — still show what was read
        # so the user can see what the scanner thought it saw.
        result = build_result(_card_read(collector_number="149"), {})
        assert result.collector_number == "149"
