"""Unit tests for consensus module — no real vision model or camera needed."""

import numpy as np
import pytest
from unittest.mock import MagicMock

from mtg_card_scanner.vision import CardRead
from mtg_card_scanner.consensus import (
    _majority, frame_sharpness, pick_sharpest, consensus_read, ConsensusRead,
)


def _card_read(name="Lightning Bolt", set_code="m10", collector_number="146") -> CardRead:
    return CardRead(
        name=name, set_code=set_code, collector_number=collector_number,
        foil=False, language="en",
        condition_estimate="NM", condition_reason="Fine.",
    )


def _blank(h=10, w=10) -> np.ndarray:
    return np.zeros((h, w, 3), dtype=np.uint8)


# ── _majority ─────────────────────────────────────────────────────────────────

class TestMajority:
    def test_unanimous_high(self):
        val, conf = _majority(["a", "a", "a"])
        assert val == "a" and conf == "high"

    def test_strict_majority_medium(self):
        val, conf = _majority(["a", "a", "b"])
        assert val == "a" and conf == "medium"

    def test_no_majority_low(self):
        _, conf = _majority(["a", "b", "c"])
        assert conf == "low"

    def test_all_empty_low(self):
        val, conf = _majority(["", "", ""])
        assert val == "" and conf == "low"

    def test_empty_excluded_from_vote(self):
        # Two agree, one is empty — all non-empty agree → high
        val, conf = _majority(["a", "", "a"])
        assert val == "a" and conf == "high"

    def test_single_value_high(self):
        val, conf = _majority(["x"])
        assert val == "x" and conf == "high"

    def test_returns_most_common(self):
        val, _ = _majority(["b", "a", "b", "b", "a"])
        assert val == "b"


# ── frame_sharpness ───────────────────────────────────────────────────────────

class TestFrameSharpness:
    def test_uniform_is_low(self):
        uniform = np.full((80, 80, 3), 128, dtype=np.uint8)
        assert frame_sharpness(uniform) < 1.0

    def test_striped_is_high(self):
        striped = np.zeros((80, 80, 3), dtype=np.uint8)
        striped[::2, :] = 255
        assert frame_sharpness(striped) > 100.0

    def test_returns_float(self):
        assert isinstance(frame_sharpness(_blank()), float)


# ── pick_sharpest ─────────────────────────────────────────────────────────────

class TestPickSharpest:
    def test_picks_sharpest(self):
        blurry = np.full((80, 80, 3), 128, dtype=np.uint8)
        sharp = np.zeros((80, 80, 3), dtype=np.uint8)
        sharp[::2, :] = 255
        assert np.array_equal(pick_sharpest([blurry, sharp, blurry]), sharp)

    def test_single_frame_returned(self):
        f = _blank()
        assert np.array_equal(pick_sharpest([f]), f)


# ── consensus_read ────────────────────────────────────────────────────────────

class TestConsensusRead:
    def _model(self, reads):
        m = MagicMock()
        m.read_card.side_effect = reads
        return m

    def test_unanimous_high_confidence(self):
        result = consensus_read([_blank()] * 3, self._model([_card_read()] * 3))
        assert result.card_read.name == "Lightning Bolt"
        assert result.name_confidence == "high"

    def test_majority_medium_confidence(self):
        reads = [_card_read("LB"), _card_read("LB"), _card_read("DR")]
        result = consensus_read([_blank()] * 3, self._model(reads))
        assert result.card_read.name == "LB"
        assert result.name_confidence == "medium"

    def test_no_majority_low_confidence(self):
        reads = [_card_read("A"), _card_read("B"), _card_read("C")]
        result = consensus_read([_blank()] * 3, self._model(reads))
        assert result.name_confidence == "low"

    def test_sharpest_frame_selected(self):
        blurry = np.full((80, 80, 3), 128, dtype=np.uint8)
        sharp = np.zeros((80, 80, 3), dtype=np.uint8)
        sharp[::2, :] = 255
        result = consensus_read([blurry, sharp, blurry], self._model([_card_read()] * 3))
        assert np.array_equal(result.sharpest_frame, sharp)

    def test_all_reads_stored(self):
        result = consensus_read([_blank()] * 3, self._model([_card_read()] * 3))
        assert len(result.reads) == 3

    def test_returns_consensus_read_dataclass(self):
        result = consensus_read([_blank()], self._model([_card_read()]))
        assert isinstance(result, ConsensusRead)

    def test_failed_frame_skipped(self):
        model = MagicMock()
        model.read_card.side_effect = [
            RuntimeError("noise"),
            _card_read("Lightning Bolt"),
            _card_read("Lightning Bolt"),
        ]
        result = consensus_read([_blank()] * 3, model)
        assert result.card_read.name == "Lightning Bolt"
        assert len(result.reads) == 2

    def test_all_frames_fail_raises(self):
        model = MagicMock()
        model.read_card.side_effect = RuntimeError("total failure")
        with pytest.raises(RuntimeError, match="All consensus frames failed"):
            consensus_read([_blank()] * 2, model)

    def test_is_old_card_true_when_any_frame_old(self):
        old = _card_read()
        old_read = CardRead(
            name="Dark Ritual", set_code="", collector_number="",
            foil=False, language="en", condition_estimate="LP",
            condition_reason="", is_old_card=True,
        )
        result = consensus_read([_blank()] * 2, self._model([_card_read(), old_read]))
        assert result.card_read.is_old_card is True


# ── set_confidence / collector_confidence consensus voting ───────────────────
# Used by pipeline.py to gate the collector-number-first lookup: a low-agreement
# read on the collector number must not be trusted as ground truth.

def _collector_read(set_code, collector_number, name="Choking Tethers"):
    return CardRead(
        name=name, set_code=set_code, collector_number=collector_number,
        foil=False, language="en", condition_estimate="NM", condition_reason="x",
    )


class TestConsensusCollectorConfidence:
    def _model(self, reads):
        m = MagicMock()
        m.read_card.side_effect = reads
        return m

    def test_unanimous_set_and_collector_high_confidence(self):
        reads = [_collector_read("kld", "66")] * 3
        result = consensus_read([_blank()] * 3, self._model(reads))
        assert result.card_read.set_code == "kld"
        assert result.card_read.collector_number == "66"
        assert result.set_confidence == "high"
        assert result.collector_confidence == "high"

    def test_majority_collector_number_medium_confidence(self):
        reads = [_collector_read("kld", "66"), _collector_read("kld", "66"),
                  _collector_read("kld", "68")]  # one frame misread 6->8
        result = consensus_read([_blank()] * 3, self._model(reads))
        assert result.card_read.collector_number == "66"
        assert result.collector_confidence == "medium"

    def test_no_majority_collector_number_low_confidence(self):
        reads = [_collector_read("kld", "66"), _collector_read("kld", "68"),
                  _collector_read("kld", "60")]
        result = consensus_read([_blank()] * 3, self._model(reads))
        assert result.collector_confidence == "low"

    def test_all_empty_collector_number_low_confidence(self):
        reads = [_collector_read("", ""), _collector_read("", "")]
        result = consensus_read([_blank()] * 2, self._model(reads))
        assert result.collector_confidence == "low"
