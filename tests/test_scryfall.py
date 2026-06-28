"""Unit tests for ScryfallClient — all HTTP calls are mocked."""

import pytest
from unittest.mock import patch, MagicMock

from mtg_card_scanner.scryfall import ScryfallClient, ScryfallError

_SAMPLE_CARD = {
    "name": "Lightning Bolt",
    "set": "m10",
    "set_name": "Magic 2010",
    "collector_number": "146",
    "type_line": "Instant",
    "rarity": "common",
    "prices": {"usd": "0.50", "usd_foil": "2.00"},
    "scryfall_uri": "https://scryfall.com/card/m10/146/lightning-bolt",
}


def _ok(data: dict) -> MagicMock:
    r = MagicMock()
    r.status_code = 200
    r.json.return_value = data
    r.raise_for_status = MagicMock()
    r.headers = {"content-type": "application/json"}
    return r


def _not_found(detail: str = "Card not found.") -> MagicMock:
    r = MagicMock()
    r.status_code = 404
    r.json.return_value = {"object": "error", "details": detail}
    r.headers = {"content-type": "application/json"}
    r.raise_for_status = MagicMock()
    return r


class TestLookupBySetCollector:
    def test_success(self):
        client = ScryfallClient()
        with patch.object(client._session, "get", return_value=_ok(_SAMPLE_CARD)):
            result = client.lookup_by_set_collector("m10", "146")
        assert result["name"] == "Lightning Bolt"

    def test_404_raises_scryfall_error(self):
        client = ScryfallClient()
        with patch.object(client._session, "get", return_value=_not_found()):
            with pytest.raises(ScryfallError):
                client.lookup_by_set_collector("xxx", "999")


class TestLookupByName:
    def test_success(self):
        client = ScryfallClient()
        with patch.object(client._session, "get", return_value=_ok(_SAMPLE_CARD)):
            result = client.lookup_by_name("Lightning Bolt")
        assert result["name"] == "Lightning Bolt"

    def test_404_raises_scryfall_error(self):
        client = ScryfallClient()
        with patch.object(client._session, "get", return_value=_not_found()):
            with pytest.raises(ScryfallError):
                client.lookup_by_name("Nonexistent Card XYZ")


class TestLookupFallback:
    def test_falls_back_through_all_tiers_to_name(self):
        # Tier-1 (set+collector) and Tier-2 (set+name search) both 404;
        # Tier-3 (global fuzzy) succeeds.
        client = ScryfallClient()
        with patch.object(
            client._session, "get",
            side_effect=[_not_found(), _not_found(), _ok(_SAMPLE_CARD)],
        ):
            result = client.lookup("xxx", "999", "Lightning Bolt")
        assert result["name"] == "Lightning Bolt"

    def test_tier1_name_mismatch_falls_to_tier2(self):
        # Tier-1 returns a DIFFERENT card; tier-2 (set+name) returns correct one.
        wrong_card = {**_SAMPLE_CARD, "name": "Counterspell"}
        search_result = {"data": [_SAMPLE_CARD], "total_cards": 1, "has_more": False}
        client = ScryfallClient()
        with patch.object(
            client._session, "get",
            side_effect=[_ok(wrong_card), _ok(search_result)],
        ):
            result = client.lookup("m10", "146", "Lightning Bolt")
        assert result["name"] == "Lightning Bolt"

    def test_skips_set_lookup_when_set_code_empty(self):
        # No set_code → skip tier-1 and tier-2, go straight to tier-3.
        client = ScryfallClient()
        with patch.object(client._session, "get", return_value=_ok(_SAMPLE_CARD)) as mock_get:
            result = client.lookup("", "", "Lightning Bolt")
        assert mock_get.call_count == 1   # only tier-3 fired
        assert result["name"] == "Lightning Bolt"

    def test_raises_when_no_set_or_name(self):
        client = ScryfallClient()
        with pytest.raises(ScryfallError, match="no name available"):
            client.lookup("", "", "")

    def test_raises_when_all_tiers_fail(self):
        # All three tiers 404.
        client = ScryfallClient()
        with patch.object(
            client._session, "get",
            side_effect=[_not_found(), _not_found(), _not_found()],
        ):
            with pytest.raises(ScryfallError):
                client.lookup("xxx", "999", "Bad Card Name")
