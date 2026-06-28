"""
Unit tests for preview.py drawing helpers.

ScannerPreview.run() requires a live camera and display — those aren't tested here.
We test the pure drawing/compositing logic that can run headlessly.
"""

import time
import numpy as np
import pytest

from mtg_card_scanner.preview import _compose, _result_panel, _ResultOverlay
from mtg_card_scanner.output import ScanResult


def _blank(h: int = 480, w: int = 640) -> np.ndarray:
    return np.zeros((h, w, 3), dtype=np.uint8)


def _make_result(**overrides) -> ScanResult:
    defaults = dict(
        timestamp="2025-01-01T12:00:00",
        name="Lightning Bolt",
        set_code="m10",
        collector_number="146",
        foil=False,
        language="en",
        condition="NM",
        condition_reason="No wear.",
        scryfall_name="Lightning Bolt",
        scryfall_set_name="Magic 2010",
        scryfall_type="Instant",
        scryfall_rarity="common",
        price_usd="0.50",
        price_usd_foil="2.00",
        scryfall_uri="https://scryfall.com/card/m10/146",
    )
    return ScanResult(**{**defaults, **overrides})


def _make_overlay(ttl: float = 5.0, **result_kwargs) -> _ResultOverlay:
    return _ResultOverlay(
        result=_make_result(**result_kwargs),
        expires_at=time.monotonic() + ttl,
    )


class TestCompose:
    """_compose should return a same-shape BGR array for all input states."""

    def test_returns_correct_shape(self):
        frame = _blank(480, 640)
        out = _compose(frame, False, 0, 0.0, False, None)
        assert out.shape == frame.shape
        assert out.dtype == np.uint8

    def test_does_not_modify_input(self):
        frame = _blank()
        original = frame.copy()
        _compose(frame, False, 0, 0.0, False, None)
        np.testing.assert_array_equal(frame, original)

    @pytest.mark.parametrize("auto_mode", [True, False])
    @pytest.mark.parametrize("is_steady", [True, False])
    def test_no_exception_for_all_state_combos(self, auto_mode, is_steady):
        frame = _blank()
        steady_count = 10 if is_steady else 3
        _compose(frame, is_steady, steady_count, 1.0, auto_mode, None)

    def test_with_result_overlay(self):
        frame = _blank()
        overlay = _make_overlay()
        out = _compose(frame, True, 10, 0.0, False, overlay)
        assert out.shape == frame.shape

    def test_expired_overlay_renders_without_error(self):
        frame = _blank()
        expired = _ResultOverlay(
            result=_make_result(),
            expires_at=time.monotonic() - 1.0,   # already expired
        )
        # Should not raise; just won't draw the panel
        out = _compose(frame, False, 0, 2.0, False, expired)
        assert out.shape == frame.shape

    def test_high_res_frame(self):
        frame = _blank(720, 1280)
        out = _compose(frame, False, 5, 1.5, True, None)
        assert out.shape == (720, 1280, 3)


class TestResultPanel:
    """_result_panel draws in-place; the frame should change."""

    def test_modifies_frame_in_place(self):
        img = _blank()
        original = img.copy()
        overlay = _make_overlay()
        _result_panel(img, overlay)
        assert not np.array_equal(img, original), "Expected in-place modification"

    def test_foil_overlay(self):
        img = _blank()
        overlay = _make_overlay(foil=True)
        _result_panel(img, overlay)   # should not raise

    def test_no_price(self):
        img = _blank()
        overlay = _make_overlay(price_usd=None, price_usd_foil=None)
        _result_panel(img, overlay)   # should not raise


class TestResultOverlay:
    def test_expires(self):
        overlay = _ResultOverlay(result=_make_result(), expires_at=time.monotonic() - 0.1)
        assert time.monotonic() >= overlay.expires_at

    def test_not_yet_expired(self):
        overlay = _ResultOverlay(result=_make_result(), expires_at=time.monotonic() + 10.0)
        assert time.monotonic() < overlay.expires_at
