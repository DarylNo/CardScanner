"""Tests for the Face to Face pricing client (thin proxy over ManaExchange)."""

import pytest
import requests

from mtg_card_scanner.facetoface import (
    F2FPrice, F2FUnavailableError, FaceToFaceClient,
)


def _client(responder):
    """FaceToFaceClient whose transport is a fake get_json(url, headers)."""
    return FaceToFaceClient(get_json=lambda url, headers=None: responder(url, headers))


def test_returns_conditions_on_found():
    def r(url, headers):
        assert "/api/scanner/f2f-price" in url
        return {"found": True, "conditions": {"NM": 3.49, "PL": 2.79},
                "url": "http://f2f/x"}
    p = _client(r).get_price("Lightning Bolt", "m10", "146", False, "Magic 2010")
    assert isinstance(p, F2FPrice)
    assert p.conditions == {"NM": 3.49, "PL": 2.79}
    assert p.url == "http://f2f/x"


def test_not_found_returns_none():
    p = _client(lambda u, h: {"found": False, "conditions": {}}).get_price(
        "Nope", "xxx", "1")
    assert p is None


def test_backend_error_raises_unavailable_not_none():
    """A 5xx / connection failure is UNKNOWN, not 'no listing' — it must raise
    so the sweep keeps the card retryable rather than marking it unlisted."""
    def r(url, headers):
        raise requests.ConnectionError("backend down")
    with pytest.raises(F2FUnavailableError):
        _client(r).get_price("Mold Folk", "clb", "133")


def test_http_404_is_confirmed_unlisted():
    def r(url, headers):
        resp = requests.Response(); resp.status_code = 404
        raise requests.HTTPError(response=resp)
    assert _client(r).get_price("Ghost", "zzz", "9") is None


def test_http_500_is_unavailable():
    def r(url, headers):
        resp = requests.Response(); resp.status_code = 500
        raise requests.HTTPError(response=resp)
    with pytest.raises(F2FUnavailableError):
        _client(r).get_price("Blip", "zzz", "9")


def test_sends_token_when_configured():
    seen = {}
    def r(url, headers):
        seen["headers"] = headers
        return {"found": True, "conditions": {"NM": 1.0}, "url": ""}
    c = FaceToFaceClient(get_json=r, token="s3cret")
    c.get_price("X", "s", "1")
    assert seen["headers"]["x-scanner-token"] == "s3cret"


def test_query_encodes_finish_and_setname():
    seen = {}
    def r(url, headers):
        seen["url"] = url
        return {"found": True, "conditions": {"NM": 1.0}, "url": ""}
    _client(r).get_price("A B", "iko", "211", True, "Ikoria: Lair of Behemoths")
    assert "finish=foil" in seen["url"]
    assert "setName=Ikoria" in seen["url"]


def test_price_for_mx_condition_fallback():
    p = F2FPrice("X", "m10", "146", False, "", "", {"NM": 3.49, "PL": 2.79})
    assert p.price_for_mx_condition("NM") == 3.49
    assert p.price_for_mx_condition("LP") == 2.79     # LP → PL
    assert p.price_for_mx_condition("MP") == 2.79     # falls through to PL


def test_empty_name_returns_none():
    assert _client(lambda u, h: {}).get_price("", "m10", "146") is None


def test_client_exposes_pace_and_debug():
    c = FaceToFaceClient(get_json=lambda u, h=None: {"found": False})
    assert c.pacing_delay() is not None
    assert isinstance(c.recent_requests(), list)
