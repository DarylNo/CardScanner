"""Unit tests for ScryfallClient — all HTTP calls are mocked."""

import pytest
from unittest.mock import patch, MagicMock

from mtg_card_scanner.scryfall import ScryfallClient, ScryfallError, _normalize_lang

_OLD_CARD_ICE_AGE = {
    "name": "Kjeldoran Dead",
    "set": "ice",
    "set_name": "Ice Age",
    "collector_number": "143",
    "type_line": "Creature — Zombie",
    "rarity": "common",
    "frame": "1993",
    "artist": "Anson Maddocks",
    "prices": {"usd": "0.25", "usd_foil": None},
    "scryfall_uri": "https://scryfall.com/card/ice/143/kjeldoran-dead",
}

_OLD_CARD_REPRINT = {
    **_OLD_CARD_ICE_AGE,
    "set": "v14",
    "set_name": "From the Vault: Annihilation",
    "collector_number": "4",
    "frame": "2015",
    "artist": "Anson Maddocks",
    "prices": {"usd": "1.00", "usd_foil": "3.00"},
}

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


class TestLookupOldCard:
    def _search_response(self, *cards) -> MagicMock:
        r = MagicMock()
        r.status_code = 200
        r.json.return_value = {"data": list(cards), "total_cards": len(cards), "has_more": False}
        r.raise_for_status = MagicMock()
        r.headers = {"content-type": "application/json"}
        return r

    def test_picks_old_frame_over_reprint(self):
        # Returns reprint first, Ice Age second — should prefer old frame
        client = ScryfallClient()
        with patch.object(
            client._session, "get",
            return_value=self._search_response(_OLD_CARD_ICE_AGE, _OLD_CARD_REPRINT),
        ):
            result = client.lookup_old_card("Kjeldoran Dead")
        assert result["frame"] == "1993"
        assert result["set"] == "ice"

    def test_artist_match_wins_over_frame_order(self):
        # Two old-frame cards; artist match picks the right one
        other_old = {**_OLD_CARD_ICE_AGE, "set": "drk", "artist": "Christopher Rush", "frame": "1993"}
        client = ScryfallClient()
        with patch.object(
            client._session, "get",
            return_value=self._search_response(other_old, _OLD_CARD_ICE_AGE),
        ):
            result = client.lookup_old_card("Kjeldoran Dead", artist="Anson Maddocks")
        assert result["artist"] == "Anson Maddocks"

    def test_no_artist_falls_back_to_oldest_old_frame(self):
        client = ScryfallClient()
        with patch.object(
            client._session, "get",
            return_value=self._search_response(_OLD_CARD_ICE_AGE, _OLD_CARD_REPRINT),
        ):
            result = client.lookup_old_card("Kjeldoran Dead", artist="")
        assert result["set"] == "ice"

    def test_partial_artist_name_matches(self):
        # Camera reads "Anson" but real name is "Anson Maddocks" — partial match ok
        client = ScryfallClient()
        with patch.object(
            client._session, "get",
            return_value=self._search_response(_OLD_CARD_ICE_AGE),
        ):
            result = client.lookup_old_card("Kjeldoran Dead", artist="anson")
        assert result["artist"] == "Anson Maddocks"

    def test_raises_when_not_found(self):
        client = ScryfallClient()
        with patch.object(client._session, "get", return_value=_not_found()):
            with pytest.raises(ScryfallError):
                client.lookup_old_card("Nonexistent Card XYZ")


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


class TestNormalizeLang:
    def test_jp_becomes_ja(self):
        assert _normalize_lang("jp") == "ja"

    def test_JP_uppercase(self):
        assert _normalize_lang("JP") == "ja"

    def test_jap_becomes_ja(self):
        assert _normalize_lang("jap") == "ja"

    def test_en_unchanged(self):
        assert _normalize_lang("en") == "en"

    def test_empty_unchanged(self):
        assert _normalize_lang("") == ""

    def test_cn_becomes_zhs(self):
        assert _normalize_lang("cn") == "zhs"

    def test_tw_becomes_zht(self):
        assert _normalize_lang("tw") == "zht"

    def test_unknown_code_passthrough(self):
        assert _normalize_lang("de") == "de"


class TestLanguageAwareLookup:
    _JA_CARD = {
        "name": "Lightning Bolt",
        "set": "m11",
        "lang": "ja",
        "collector_number": "149",
        "prices": {"usd": "1.00", "usd_foil": None},
        "scryfall_uri": "https://scryfall.com/card/m11/149/ja/lightning-bolt",
    }

    def test_jp_normalised_to_ja_hits_ja_endpoint(self):
        # When lookup is called with lang='jp', it should try the /ja/ endpoint.
        client = ScryfallClient()
        with patch.object(client._session, "get", return_value=_ok(self._JA_CARD)) as mock_get:
            result = client.lookup_by_set_collector("m11", "149", language="jp")
        assert result["lang"] == "ja"
        called_url = mock_get.call_args[0][0]
        assert called_url.endswith("/ja"), f"Expected /ja endpoint, got: {called_url}"

    def test_en_skips_language_endpoint(self):
        # English lookup should go directly to /cards/{set}/{num} (no /en suffix).
        client = ScryfallClient()
        with patch.object(client._session, "get", return_value=_ok(_SAMPLE_CARD)) as mock_get:
            result = client.lookup_by_set_collector("m10", "146", language="en")
        called_url = mock_get.call_args[0][0]
        assert not called_url.endswith("/en"), f"EN should not use lang endpoint, got: {called_url}"

    def test_non_english_skips_name_mismatch_check(self):
        # For non-English cards, tier-1 should be accepted even though the
        # localized name != the oracle English name.
        client = ScryfallClient()
        ja_card = {**self._JA_CARD, "name": "Lightning Bolt"}  # Scryfall returns English name
        with patch.object(client._session, "get", return_value=_ok(ja_card)):
            # Passing a Japanese name (would normally fail name-match check for English)
            result = client.lookup("m11", "149", "稲妻", language="jp")
        assert result["name"] == "Lightning Bolt"

    def test_ja_endpoint_fallback_when_not_found(self):
        # /ja endpoint 404 → should fall back to English endpoint.
        client = ScryfallClient()
        with patch.object(
            client._session, "get",
            side_effect=[_not_found(), _ok(_SAMPLE_CARD)],
        ):
            result = client.lookup_by_set_collector("m10", "146", language="jp")
        assert result["name"] == "Lightning Bolt"
