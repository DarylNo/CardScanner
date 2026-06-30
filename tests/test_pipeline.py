"""Unit tests for build_result, format_listing, and Pipeline.run_demo."""

import json
from dataclasses import replace
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from mtg_card_scanner.vision import CardRead
from mtg_card_scanner.pipeline import Pipeline, _artist_matches
from mtg_card_scanner.output import build_result, format_listing, OutputWriter, ScanResult
from mtg_card_scanner.scryfall import ScryfallError


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


class TestArtistMatches:
    def test_exact_match(self):
        assert _artist_matches("Rebecca Guay", "Rebecca Guay")

    def test_partial_read_in_full_name(self):
        assert _artist_matches("Rebecca", "Rebecca Guay")

    def test_partial_read_surname_only(self):
        # Camera reads just surname; Scryfall has full canonical name
        assert _artist_matches("Benson", "Melissa A. Benson")

    def test_case_insensitive(self):
        assert _artist_matches("rebecca guay", "Rebecca Guay")

    def test_no_match(self):
        assert not _artist_matches("Rebecca Guay", "Nino Is")

    def test_empty_read_artist_no_match(self):
        assert not _artist_matches("", "Rebecca Guay")

    def test_empty_scryfall_artist_no_match(self):
        assert not _artist_matches("Rebecca Guay", "")


class TestPipelineArtistMismatchFallback:
    """When the Scryfall result is from a different set AND wrong artist, use old-card path."""

    _WRONG_RESULT = {
        **_SAMPLE_SCRYFALL,
        "set": "spg",
        "set_name": "Marvel Commander",
        "artist": "Nino Is",
    }
    _CORRECT_RESULT = {
        **_SAMPLE_SCRYFALL,
        "set": "tmp",
        "set_name": "Tempest",
        "artist": "Rebecca Guay",
    }

    def _make_pipeline(self, lookup_result, old_card_result):
        mock_scryfall = MagicMock()
        mock_scryfall.lookup.return_value = lookup_result
        mock_scryfall.lookup_old_card.return_value = old_card_result
        # Return empty list so visual-match path is skipped, falling through to 3-tier
        mock_scryfall.get_all_printings.return_value = []
        mock_model = MagicMock()
        dark_ritual_read = CardRead(
            name="Dark Ritual", set_code="mom", collector_number="196",
            foil=False, language="en",
            condition_estimate="NM", condition_reason="Fine.",
            artist="Rebecca Guay",
        )
        mock_model.read_card.return_value = dark_ritual_read
        return Pipeline(model=mock_model, scryfall=mock_scryfall), mock_scryfall

    def test_artist_mismatch_triggers_old_card_lookup(self):
        pipeline, mock_scryfall = self._make_pipeline(
            self._WRONG_RESULT, self._CORRECT_RESULT
        )
        import numpy as np
        result = pipeline.run_once(np.zeros((10, 10, 3), dtype=np.uint8))
        mock_scryfall.lookup_old_card.assert_called_once_with("Dark Ritual", "Rebecca Guay")
        assert result.scryfall_set_name == "Tempest"

    def test_matching_set_skips_fallback(self):
        # When Scryfall returns same set as model expected, no old-card fallback
        matching_result = {**self._CORRECT_RESULT, "set": "mom"}
        pipeline, mock_scryfall = self._make_pipeline(matching_result, self._CORRECT_RESULT)
        import numpy as np
        pipeline.run_once(np.zeros((10, 10, 3), dtype=np.uint8))
        mock_scryfall.lookup_old_card.assert_not_called()


class TestCollectorNumberFirstOrdering:
    """
    A confidently-read collector number + set code is ground truth from the
    physical card — it must be tried via a direct Scryfall lookup BEFORE
    visual_match runs, and trusted outright when the returned card's NAME
    matches the consensus name. This also guarantees List-vs-base correctness
    for free: a plain collector number read off a base-set card (e.g. "66")
    can only ever resolve to that base set, never to 'plst' (a different set
    namespace with prefixed numbers).

    The number is noisy (a single misread digit can point at a wrong card),
    so it must never be trusted without: (a) burst-frame agreement
    (collector_confidence != "low"), and (b) the resolved name matching what
    was read. On either failure, fall back to visual_match — never silently
    accept a name-mismatched result.
    """

    _CHOKING_TETHERS_READ = CardRead(
        name="Choking Tethers", set_code="kld", collector_number="66",
        foil=False, language="en",
        condition_estimate="NM", condition_reason="Fine.",
        artist="Daniel Ljunggren",
    )

    _KLD_RESULT = {
        "name": "Choking Tethers", "set": "kld", "set_name": "Kaladesh",
        "collector_number": "66", "type_line": "Sorcery", "rarity": "common",
        "artist": "Daniel Ljunggren",
        "prices": {"usd": "0.10", "usd_foil": "0.25"},
        "scryfall_uri": "https://scryfall.com/card/kld/66/choking-tethers",
    }

    def _make_pipeline(self, set_collector_result=None, set_name_result=None, card_read=None):
        mock_scryfall = MagicMock()
        if set_collector_result is not None:
            mock_scryfall.lookup_by_set_collector.return_value = set_collector_result
        else:
            mock_scryfall.lookup_by_set_collector.side_effect = ScryfallError("not found")
        if set_name_result is not None:
            mock_scryfall.lookup_by_set_name.return_value = set_name_result
        else:
            mock_scryfall.lookup_by_set_name.side_effect = ScryfallError("not found")
        mock_scryfall.lookup.return_value = self._KLD_RESULT
        mock_scryfall.get_all_printings.return_value = []
        mock_model = MagicMock()
        mock_model.read_card.return_value = card_read or self._CHOKING_TETHERS_READ
        return Pipeline(model=mock_model, scryfall=mock_scryfall), mock_scryfall

    def test_confident_collector_number_trusted_skips_visual_match(self):
        pipeline, mock_scryfall = self._make_pipeline(set_collector_result=self._KLD_RESULT)
        import numpy as np
        result = pipeline.run_once(np.zeros((10, 10, 3), dtype=np.uint8))
        mock_scryfall.get_all_printings.assert_not_called()
        assert result.scryfall_set_name == "Kaladesh"
        assert result.match_method == "collector_number"

    def test_collector_lookup_called_with_set_and_number(self):
        # Single-frame reads are treated as confident (no consensus disagreement
        # possible) — the same ordering guarantee as the multi-frame burst path.
        pipeline, mock_scryfall = self._make_pipeline(set_collector_result=self._KLD_RESULT)
        import numpy as np
        pipeline.run_once(np.zeros((10, 10, 3), dtype=np.uint8))
        mock_scryfall.lookup_by_set_collector.assert_called_once_with("kld", "66", "en")

    def test_name_mismatch_rejected_falls_back_to_visual_match(self):
        # Tier-1 hit but the returned card's NAME doesn't match what was read —
        # the number pointed at a different (wrong) card. Must reject and fall
        # through to visual_match, NOT silently accept a wrong card.
        wrong_card = {**self._KLD_RESULT, "name": "Some Other Card"}
        pipeline, mock_scryfall = self._make_pipeline(set_collector_result=wrong_card)
        import numpy as np
        pipeline.run_once(np.zeros((10, 10, 3), dtype=np.uint8))
        mock_scryfall.get_all_printings.assert_called_once_with("Choking Tethers")

    def test_lookup_404_falls_back_to_visual_match(self):
        # Misread number doesn't even exist in that set — falls back.
        pipeline, mock_scryfall = self._make_pipeline(set_collector_result=None)
        import numpy as np
        pipeline.run_once(np.zeros((10, 10, 3), dtype=np.uint8))
        mock_scryfall.get_all_printings.assert_called_once_with("Choking Tethers")

    def test_old_card_skips_collector_number_first(self):
        # No printed collector number exists on vintage cards — must not even
        # attempt the collector-first lookup; goes straight to visual_match,
        # then lookup_old_card in the final fallback.
        old_read = CardRead(
            name="Llanowar Elves", set_code="", collector_number="",
            foil=False, language="en", condition_estimate="LP",
            condition_reason="Edge wear.", artist="Anson Maddocks",
            is_old_card=True,
        )
        pipeline, mock_scryfall = self._make_pipeline(card_read=old_read)
        mock_scryfall.lookup_old_card.return_value = {
            "name": "Llanowar Elves", "set": "ice", "set_name": "Ice Age",
        }
        import numpy as np
        pipeline.run_once(np.zeros((10, 10, 3), dtype=np.uint8))
        mock_scryfall.lookup_by_set_collector.assert_not_called()
        mock_scryfall.lookup_by_set_name.assert_not_called()

    def test_no_collector_number_falls_to_name_in_set_when_confident(self):
        # Number missing entirely, but name+set ARE confident (single-frame
        # reads are always treated as confident) — resolves via exact
        # name-within-set search rather than jumping straight to visual_match.
        no_number_read = CardRead(
            name="Choking Tethers", set_code="kld", collector_number="",
            foil=False, language="en",
            condition_estimate="NM", condition_reason="Fine.",
        )
        pipeline, mock_scryfall = self._make_pipeline(
            set_name_result=self._KLD_RESULT, card_read=no_number_read,
        )
        import numpy as np
        result = pipeline.run_once(np.zeros((10, 10, 3), dtype=np.uint8))
        mock_scryfall.lookup_by_set_name.assert_called_once_with("kld", "Choking Tethers")
        mock_scryfall.get_all_printings.assert_not_called()
        assert result.match_method == "name_in_set"

    def test_no_set_code_skips_both_shortcuts_falls_to_visual_match(self):
        # Neither set+number nor set+name available — straight to visual_match.
        no_set_read = CardRead(
            name="Choking Tethers", set_code="", collector_number="",
            foil=False, language="en",
            condition_estimate="NM", condition_reason="Fine.",
        )
        pipeline, mock_scryfall = self._make_pipeline(card_read=no_set_read)
        import numpy as np
        pipeline.run_once(np.zeros((10, 10, 3), dtype=np.uint8))
        mock_scryfall.lookup_by_set_collector.assert_not_called()
        mock_scryfall.lookup_by_set_name.assert_not_called()
        mock_scryfall.get_all_printings.assert_called_once_with("Choking Tethers")

    def test_name_in_set_lookup_failure_falls_back_to_visual_match(self):
        no_number_read = CardRead(
            name="Choking Tethers", set_code="kld", collector_number="",
            foil=False, language="en",
            condition_estimate="NM", condition_reason="Fine.",
        )
        pipeline, mock_scryfall = self._make_pipeline(
            set_name_result=None, card_read=no_number_read,
        )
        import numpy as np
        pipeline.run_once(np.zeros((10, 10, 3), dtype=np.uint8))
        mock_scryfall.get_all_printings.assert_called_once_with("Choking Tethers")


class TestCollectorConfidenceGating:
    """
    Burst-frame disagreement on the collector number must never be trusted —
    even if a single frame happened to read a plausible-looking number.
    """

    def _consensus_pipeline(self, reads, set_collector_result=None):
        mock_scryfall = MagicMock()
        if set_collector_result is not None:
            mock_scryfall.lookup_by_set_collector.return_value = set_collector_result
        else:
            mock_scryfall.lookup_by_set_collector.side_effect = ScryfallError("not found")
        mock_scryfall.lookup_by_set_name.side_effect = ScryfallError("not found")
        mock_scryfall.get_all_printings.return_value = []
        mock_model = MagicMock()
        mock_model.read_card.side_effect = reads
        return Pipeline(model=mock_model, scryfall=mock_scryfall), mock_scryfall

    def test_disagreeing_frames_never_trust_collector_number(self):
        import numpy as np
        reads = [
            CardRead(name="Choking Tethers", set_code="kld", collector_number="66",
                      foil=False, language="en", condition_estimate="NM", condition_reason="x"),
            CardRead(name="Choking Tethers", set_code="kld", collector_number="68",
                      foil=False, language="en", condition_estimate="NM", condition_reason="x"),
            CardRead(name="Choking Tethers", set_code="kld", collector_number="60",
                      foil=False, language="en", condition_estimate="NM", condition_reason="x"),
        ]
        kld_result = {"name": "Choking Tethers", "set": "kld", "set_name": "Kaladesh",
                       "collector_number": "66"}
        pipeline, mock_scryfall = self._consensus_pipeline(reads, set_collector_result=kld_result)
        frames = [np.zeros((10, 10, 3), dtype=np.uint8) for _ in range(3)]
        pipeline.run_once(frames)
        mock_scryfall.lookup_by_set_collector.assert_not_called()
        mock_scryfall.get_all_printings.assert_called_once_with("Choking Tethers")

    def test_agreeing_frames_trust_collector_number(self):
        import numpy as np
        reads = [
            CardRead(name="Choking Tethers", set_code="kld", collector_number="66",
                      foil=False, language="en", condition_estimate="NM", condition_reason="x"),
            CardRead(name="Choking Tethers", set_code="kld", collector_number="66",
                      foil=False, language="en", condition_estimate="NM", condition_reason="x"),
            CardRead(name="Choking Tethers", set_code="kld", collector_number="68",
                      foil=False, language="en", condition_estimate="NM", condition_reason="x"),
        ]
        kld_result = {"name": "Choking Tethers", "set": "kld", "set_name": "Kaladesh",
                       "collector_number": "66"}
        pipeline, mock_scryfall = self._consensus_pipeline(reads, set_collector_result=kld_result)
        frames = [np.zeros((10, 10, 3), dtype=np.uint8) for _ in range(3)]
        result = pipeline.run_once(frames)
        mock_scryfall.lookup_by_set_collector.assert_called_once_with("kld", "66", "en")
        assert result.match_method == "collector_number"


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
        return Pipeline(model=None, scryfall=mock_scryfall), mock_scryfall

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
        pipeline = Pipeline(model=None, scryfall=mock_scryfall, writer=writer)
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
