"""Unit tests for VisionModel._parse_response and old-card helpers — no model or camera needed."""

import json
import pytest
from unittest.mock import patch, MagicMock

from mtg_card_scanner.vision import (
    VisionModel, CardRead, CARD_READ_PROMPT, OLD_CARD_PROMPT,
    _MODEL_TIMEOUT_S, _MODEL_MAX_RETRIES,
)


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

class TestStringNullHandling:
    """Model sometimes writes literal "null" string instead of JSON null."""

    def test_string_null_set_code_becomes_empty(self):
        raw = json.dumps({
            "name": "Dark Ritual",
            "set_code": "null",
            "collector_number": "150",
            "foil": False, "language": "en",
            "condition_estimate": "LP", "condition_reason": "Fine.",
        })
        r = parse(raw)
        assert r.set_code == ""

    def test_string_null_is_then_detected_as_old_card(self):
        raw = json.dumps({
            "name": "Dark Ritual",
            "set_code": "null",
            "collector_number": "150",
            "foil": False, "language": "en",
            "condition_estimate": "LP", "condition_reason": "Fine.",
        })
        r = parse(raw)
        assert VisionModel._looks_like_old_card(r)


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


# ── Prompt hygiene ───────────────────────────────────────────────────────────

_COMBINED_PROMPTS = CARD_READ_PROMPT + "\n" + OLD_CARD_PROMPT

# Real card values that must NEVER appear in prompts (to prevent model parroting)
_BANNED_PROMPT_VALUES = [
    # The Kami of Whispered Hopes example that caused repeated mom/196 hallucinations
    "mom",        # real set code
    "0196",       # raw collector number from that card
    "Pagliuso",   # artist from that card
    "Kami",       # card name
    "Whispered",  # card name fragment
    # Other real card names used during development
    "Kjeldoran",
    "Dark Ritual",
    "Summon Dead",  # old type line that appeared in examples
]


class TestPromptHygiene:
    """Prompts must not contain real card examples — the model parrots them as hallucinations."""

    def test_no_real_set_code_mom_in_prompts(self):
        # "mom" as a standalone word — allow it as part of longer words (e.g. "common")
        import re
        assert not re.search(r'\bmom\b', _COMBINED_PROMPTS, re.IGNORECASE), \
            "Real set code 'mom' found in prompt — model will parrot it"

    def test_no_raw_collector_number_0196(self):
        assert "0196" not in _COMBINED_PROMPTS, \
            "Raw collector number '0196' from Kami of Whispered Hopes found in prompt"

    def test_no_real_artist_pagliuso(self):
        assert "Pagliuso" not in _COMBINED_PROMPTS and "pagliuso" not in _COMBINED_PROMPTS, \
            "Real artist name 'Pagliuso' found in prompt"

    def test_no_real_card_name_kami(self):
        assert "Kami" not in _COMBINED_PROMPTS, \
            "Real card name 'Kami' found in prompt"

    def test_no_summon_dead_type_line(self):
        assert "Summon Dead" not in _COMBINED_PROMPTS, \
            "'Summon Dead' type line example found in prompt — use generic 'Creature' instead"

    def test_placeholder_labels_not_real_values(self):
        # The format example must use placeholder-style text, not filled-in real values
        assert "[NUMBER]" in CARD_READ_PROMPT or "[RARITY" in CARD_READ_PROMPT, \
            "CARD_READ_PROMPT should use placeholder labels like [NUMBER], not filled-in examples"


# ── Old-card detection ────────────────────────────────────────────────────────

def _read(**kw) -> CardRead:
    defaults = dict(
        name="Test", set_code="", collector_number="",
        foil=False, language="en",
        condition_estimate="LP", condition_reason=""
    )
    defaults.update(kw)
    return CardRead(**defaults)


class TestLooksLikeOldCard:
    def test_numeric_set_code_is_old_card(self):
        assert VisionModel._looks_like_old_card(_read(set_code="300", collector_number="042"))

    def test_valid_modern_card_is_not_old(self):
        assert not VisionModel._looks_like_old_card(_read(set_code="ice", collector_number="196"))

    def test_both_empty_is_old_card(self):
        assert VisionModel._looks_like_old_card(_read(set_code="", collector_number=""))

    def test_empty_set_with_hallucinated_collector_is_old_card(self):
        # Model hallucinated "3196" from P/T "3/1" — no set code means old card
        assert VisionModel._looks_like_old_card(_read(set_code="", collector_number="3196"))

    def test_alphanumeric_set_with_number_is_not_old(self):
        assert not VisionModel._looks_like_old_card(_read(set_code="mh2", collector_number="138"))


# ── Old-card response parsing ─────────────────────────────────────────────────

def parse_old(text: str) -> CardRead:
    return VisionModel._parse_old_card_response(text)


class TestParseOldCardResponse:
    _payload = {
        "name": "Kjeldoran Dead",
        "artist": "Anson Maddocks",
        "type_line": "Summon Dead",
        "foil": False,
        "language": "en",
        "condition_estimate": "LP",
        "condition_reason": "Edge wear on corners.",
        "confidence": "high",
    }

    def test_name_parsed(self):
        r = parse_old(json.dumps(self._payload))
        assert r.name == "Kjeldoran Dead"

    def test_is_old_card_true(self):
        r = parse_old(json.dumps(self._payload))
        assert r.is_old_card is True

    def test_artist_parsed(self):
        r = parse_old(json.dumps(self._payload))
        assert r.artist == "Anson Maddocks"

    def test_set_code_and_collector_empty(self):
        r = parse_old(json.dumps(self._payload))
        assert r.set_code == ""
        assert r.collector_number == ""

    def test_condition_uppercased(self):
        r = parse_old(json.dumps(self._payload))
        assert r.condition_estimate == "LP"

    def test_no_json_raises(self):
        with pytest.raises((ValueError, json.JSONDecodeError)):
            parse_old("I cannot read this card.")

    def test_strips_markdown_fence(self):
        raw = f"```json\n{json.dumps(self._payload)}\n```"
        r = parse_old(raw)
        assert r.name == "Kjeldoran Dead"


class TestPromptNoSpecificCardExamples:
    """Prompts must not contain real card examples that the model would parrot."""

    def test_no_propaganda_in_prompt(self):
        assert "Propaganda" not in CARD_READ_PROMPT

    def test_no_c20_set_code_in_prompt(self):
        import re
        assert not re.search(r'\bc20\b', CARD_READ_PROMPT, re.IGNORECASE)


# ── hang-prevention: explicit timeout on the model client ────────────────────

class TestVisionModelTimeout:
    """
    Without an explicit timeout, a stalled Ollama call (model busy, GPU
    contention) can hang the OpenAI SDK's default multi-minute timeout,
    which looks to the user like the scan is permanently stuck. The model
    client must always be constructed with a bounded timeout + retry count.
    """

    def test_client_constructed_with_explicit_timeout(self):
        with patch("openai.OpenAI") as mock_openai:
            VisionModel()
            _, kwargs = mock_openai.call_args
            assert kwargs.get("timeout") == _MODEL_TIMEOUT_S
            assert kwargs["timeout"] is not None

    def test_client_constructed_with_bounded_retries(self):
        with patch("openai.OpenAI") as mock_openai:
            VisionModel()
            _, kwargs = mock_openai.call_args
            assert kwargs.get("max_retries") == _MODEL_MAX_RETRIES

    def test_timeout_is_finite_and_reasonable(self):
        # Must be bounded (not None/unlimited) and short enough that a hang
        # doesn't stall a scan for minutes.
        assert 0 < _MODEL_TIMEOUT_S <= 60
