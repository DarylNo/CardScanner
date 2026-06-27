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
    def test_falls_back_to_name_on_404(self):
        client = ScryfallClient()
        with patch.object(
            client._session, "get", side_effect=[_not_found(), _ok(_SAMPLE_CARD)]
        ):
            result = client.lookup("xxx", "999", "Lightning Bolt")
        assert result["name"] == "Lightning Bolt"

    def test_skips_set_lookup_when_set_code_empty(self):
        client = ScryfallClient()
        with patch.object(client._session, "get", return_value=_ok(_SAMPLE_CARD)) as mock_get:
            result = client.lookup("", "", "Lightning Bolt")
        # Only one GET call should be made (name search)
        assert mock_get.call_count == 1
        assert result["name"] == "Lightning Bolt"

    def test_raises_when_no_set_or_name(self):
        client = ScryfallClient()
        with pytest.raises(ScryfallError, match="no set_code"):
            client.lookup("", "", "")

    def test_raises_when_both_attempts_fail(self):
        client = ScryfallClient()
        with patch.object(
            client._session, "get", side_effect=[_not_found(), _not_found()]
        ):
            with pytest.raises(ScryfallError):
                client.lookup("xxx", "999", "Bad Card Name")
