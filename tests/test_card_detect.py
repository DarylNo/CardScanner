"""Tests for card_detect helpers: frame sharpness scoring."""

import numpy as np

from mtg_card_scanner.card_detect import frame_sharpness, pick_sharpest


def _blank():
    return np.full((80, 80, 3), 128, dtype=np.uint8)


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


class TestPickSharpest:
    def test_picks_sharpest(self):
        blurry = np.full((80, 80, 3), 128, dtype=np.uint8)
        sharp = np.zeros((80, 80, 3), dtype=np.uint8)
        sharp[::2, :] = 255
        assert np.array_equal(pick_sharpest([blurry, sharp, blurry]), sharp)

    def test_single_frame_returned(self):
        f = _blank()
        assert np.array_equal(pick_sharpest([f]), f)
