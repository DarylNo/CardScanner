"""Unit tests for VisionModel._parse_response — no model or camera needed."""

import json
import pytest

from mtg_card_scanner.vision import VisionModel, CardRead


def parse(text: str) -> CardRead:
    return VisionModel._parse_response(text)


# ── Happy-path parsing ────────────────────────────────────────────────────────

class TestParseCleanJSON:
    def test_basic(self):
        raw = json.dumps({
            "name": "Lightning Bolt",
            "set_code": "m10",
            "collector_number": "146",
            "foil": False,
            "language": "en",
            "condition_estimate": "NM",
            "condition_reason": "No wear.",
        })
        r = parse(raw)
        assert r.name == "Lightning Bolt"
        assert r.set_code == "m10"
        assert r.collector_number == "146"
        assert r.foil is False
        assert r.language == "en"
        assert r.condition_estimate == "NM"

    def test_foil_true(self):
        raw = json.dumps({
            "name": "Ragavan, Nimble Pilferer",
            "set_code": "mh2",
            "collector_number": "138",
            "foil": True,
            "language": "en",
            "condition_estimate": "NM",
            "condition_reason": "Pristine.",
        })
        assert parse(raw).foil is True

    def test_set_code_uppercased_in_input_lowercased_in_output(self):
        raw = json.dumps({
            "name": "Counterspell",
            "set_code": "TMP",
            "collector_number": "069",
            "foil": False,
            "language": "en",
            "condition_estimate": "LP",
            "condition_reason": "Minor wear.",
        })
        assert parse(raw).set_code == "tmp"

    def test_condition_lowercased_in_input_uppercased_in_output(self):
        raw = json.dumps({
            "name": "Serra Angel",
            "set_code": "a25",
            "collector_number": "037",
            "foil": False,
            "language": "en",
            "condition_estimate": "mp",
            "condition_reason": "Some wear.",
        })
        assert parse(raw).condition_estimate == "MP"


# ── Markdown fence stripping ──────────────────────────────────────────────────

class TestParseMarkdownFences:
    _payload = {
        "name": "Black Lotus",
        "set_code": "lea",
        "collector_number": "232",
        "foil": False,
        "language": "en",
        "condition_estimate": "HP",
        "condition_reason": "Heavy creases.",
    }

    def test_json_fence(self):
        raw = f"```json\n{json.dumps(self._payload)}\n```"
        r = parse(raw)
        assert r.name == "Black Lotus"

    def test_plain_fence(self):
        raw = f"```\n{json.dumps(self._payload)}\n```"
        assert parse(raw).name == "Black Lotus"


# ── Extra surrounding text ────────────────────────────────────────────────────

class TestParseExtraText:
    _payload = {
        "name": "Counterspell",
        "set_code": "tmp",
        "collector_number": "069",
        "foil": True,
        "language": "en",
        "condition_estimate": "LP",
        "condition_reason": "Minor edge wear.",
    }

    def test_preamble_and_trailer(self):
        raw = f"Sure! Here is the result:\n{json.dumps(self._payload)}\nHope that helps!"
        r = parse(raw)
        assert r.name == "Counterspell"
        assert r.foil is True


# ── Collector number normalisation ────────────────────────────────────────────

class TestCollectorNumberNormalisation:
    def test_strips_set_size_suffix(self):
        raw = json.dumps({
            "name": "Island",
            "set_code": "bfz",
            "collector_number": "282/274",
            "foil": False,
            "language": "en",
            "condition_estimate": "NM",
            "condition_reason": "Pristine.",
        })
        assert parse(raw).collector_number == "282"

    def test_plain_number_unchanged(self):
        raw = json.dumps({
            "name": "Island",
            "set_code": "bfz",
            "collector_number": "282",
            "foil": False,
            "language": "en",
            "condition_estimate": "NM",
            "condition_reason": "Pristine.",
        })
        assert parse(raw).collector_number == "282"


# ── Error cases ───────────────────────────────────────────────────────────────

class TestParseErrors:
    def test_no_json_raises(self):
        with pytest.raises((ValueError, json.JSONDecodeError)):
            parse("Sorry, I cannot identify this card.")

    def test_empty_string_raises(self):
        with pytest.raises((ValueError, json.JSONDecodeError)):
            parse("")
