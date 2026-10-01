"""Tests for card_detect helpers: frame sharpness scoring."""

import numpy as np

import cv2

from mtg_card_scanner.card_detect import (
    CARD_H, CARD_W, extract_card, find_card_quad, frame_sharpness,
    is_blank_surface, pick_sharpest,
)


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


def _scene(card_w, card_h, angle=0, bg=235, border=25):
    """Frame with a single card-like rectangle drawn on a plain background."""
    frame = np.full((600, 800, 3), bg, np.uint8)
    card = np.full((card_h, card_w, 3), 200, np.uint8)
    cv2.rectangle(card, (0, 0), (card_w - 1, card_h - 1), (border,) * 3, 6)
    # textured "art" block so the card isn't uniform
    rng = np.random.default_rng(3)
    art = rng.integers(40, 210, (card_h // 3, card_w - 30, 3), dtype=np.uint8)
    card[20:20 + card_h // 3, 15:card_w - 15] = art
    if angle:
        M = cv2.getRotationMatrix2D((card_w / 2, card_h / 2), angle, 1.0)
        card = cv2.warpAffine(card, M, (card_w, card_h), borderValue=(bg, bg, bg))
    y0 = (600 - card_h) // 2
    x0 = (800 - card_w) // 2
    frame[y0:y0 + card_h, x0:x0 + card_w] = card
    return frame


class TestFindCardQuad:
    def test_finds_portrait_card(self):
        quad = find_card_quad(_scene(220, 307))     # 63:88 portrait
        assert quad is not None
        assert quad.shape == (4, 2)

    def test_finds_sideways_card(self):
        """A card lying sideways must still be found (and oriented upright)."""
        quad = find_card_quad(_scene(307, 220))     # landscape
        assert quad is not None
        # long axis maps to the output height: |TL→TR| (width) < |TR→BR| (height)
        w_edge = np.linalg.norm(quad[1] - quad[0])
        h_edge = np.linalg.norm(quad[2] - quad[1])
        assert h_edge > w_edge

    def test_rejects_non_card_shapes(self):
        assert find_card_quad(_scene(300, 305)) is None      # ~square
        assert find_card_quad(_scene(560, 120)) is None      # far too wide

    def test_returns_none_on_blank_frame(self):
        assert find_card_quad(np.full((600, 800, 3), 235, np.uint8)) is None

    def test_rejects_uniform_card_shaped_object(self):
        """A black card holder is card-shaped but has no printed interior —
        the texture gate must reject it (it used to out-area the real card)."""
        frame = np.full((600, 800, 3), 235, np.uint8)
        frame[146:453, 290:510] = 22               # 220×307 uniform dark slab
        assert find_card_quad(frame) is None

    def test_prefers_real_card_over_bigger_holder(self):
        """With both in frame, the CARD wins even though the holder is larger."""
        frame = _scene(220, 307)                   # real textured card at x 290-510
        frame[120:500, 20:260] = 18                # bigger dark holder beside it
        quad = find_card_quad(frame)
        assert quad is not None
        assert quad[:, 0].min() > 260              # quad sits on the card, not the holder


class TestIsBlankSurface:
    def test_uniform_dark_slab_is_blank(self):
        assert is_blank_surface(np.full((CARD_H, CARD_W, 3), 22, np.uint8)) is True

    def test_empty_tray_crop_is_blank(self):
        assert is_blank_surface(np.full((CARD_H, CARD_W, 3), 235, np.uint8)) is True

    def test_card_like_content_is_not_blank(self):
        card, detected = extract_card(_scene(220, 307))
        assert detected is True
        assert is_blank_surface(card, detected=detected) is False

    def test_fallback_crop_of_holder_is_blank(self):
        """Centre-crop fallback: holder outline edges alone must not count as
        card content — the stricter fallback bar applies."""
        frame = np.full((600, 800, 3), 235, np.uint8)
        frame[146:453, 290:510] = 22
        img, detected = extract_card(frame)
        assert detected is False
        assert is_blank_surface(img, detected=detected) is True


class TestExtractCard:
    def test_detected_card_is_card_sized(self):
        img, detected = extract_card(_scene(220, 307))
        assert detected is True
        assert img.shape[:2] == (CARD_H, CARD_W)

    def test_fallback_preserves_aspect(self):
        """A failed detection must not stretch the frame into card shape."""
        blank = np.full((600, 800, 3), 235, np.uint8)
        # mark a circle; after an aspect-preserving crop it stays circular
        cv2.circle(blank, (400, 300), 80, (10, 10, 10), -1)
        img, detected = extract_card(blank)
        assert detected is False
        assert img.shape[:2] == (CARD_H, CARD_W)
        mask = (img[:, :, 0] < 60).astype(np.uint8)
        xs = mask.sum(axis=0).nonzero()[0]
        ys = mask.sum(axis=1).nonzero()[0]
        width, height = xs.max() - xs.min(), ys.max() - ys.min()
        # Uniform scaling keeps the circle a circle. The old fixed-window
        # fallback stretched x and y by different factors (~0.78 ratio here).
        assert 0.9 < width / height < 1.1


class TestScanPhoto:
    """The photo kept for review (owner, 2026-10-01): the card straightened and
    filling 95% of it, a buffer of tray around it; the frame as uploaded when
    the card's edges are not found; never a re-crop of an older photo."""

    def setup_method(self):
        from tests import card_scenes
        self.scenes = card_scenes.scenes()
        self.true_quad = card_scenes.true_card_quad

    def test_constants_place_the_card_at_95_percent(self):
        from mtg_card_scanner.card_detect import (PHOTO_FILL, PHOTO_H, PHOTO_MARGIN_X,
                                                  PHOTO_MARGIN_Y, PHOTO_W)
        assert (PHOTO_MARGIN_X, PHOTO_MARGIN_Y, PHOTO_W, PHOTO_H) == (17, 23, 664, 926)
        assert abs(CARD_W / PHOTO_W - PHOTO_FILL) < 0.002
        assert abs(CARD_H / PHOTO_H - PHOTO_FILL) < 0.002

    def _true_corners_in_photo(self, name):
        """Where the TRUE card corners land in scan_photo's output."""
        from mtg_card_scanner.card_detect import (PHOTO_MARGIN_X, PHOTO_MARGIN_Y, scan_photo)
        img, _ = self.scenes[name]
        photo, detected = scan_photo([img])
        assert detected, name
        q = find_card_quad(img)
        mx, my = PHOTO_MARGIN_X, PHOTO_MARGIN_Y
        dst = np.float32([[mx, my], [mx + CARD_W, my], [mx + CARD_W, my + CARD_H], [mx, my + CARD_H]])
        M = cv2.getPerspectiveTransform(q, dst)
        pts = self.true_quad(name).reshape(-1, 1, 2).astype(np.float32)
        return photo, cv2.perspectiveTransform(pts, M).reshape(-1, 2)

    def test_the_card_fills_95_percent_with_a_real_buffer(self):
        """Measured per scene (the TRUE card's span of the photo, raw frame in):
        91.8–93.1% of the width, 92.8–93.7% of the height for cards 390–717 px
        tall — the nominal 95% less the detector's 5×5 dilation (×2), which
        leaves ~4 px of tray inside the quad on each side; the 192 px card of
        small_card_full_frame pays that twice over (87.0% × 89.2%). On the
        phone the frame is flattened to 914 px first, so a small card is
        detected large: 92.6–93.9% × 93.4–94.3% over the 8 scenes."""
        from mtg_card_scanner.card_detect import PHOTO_H, PHOTO_W
        for name, (_, has_card) in self.scenes.items():
            if not has_card:
                continue
            photo, pts = self._true_corners_in_photo(name)
            assert photo.shape == (PHOTO_H, PHOTO_W, 3), name
            xs, ys = pts[:, 0], pts[:, 1]
            span_x, span_y = (xs.max() - xs.min()) / PHOTO_W, (ys.max() - ys.min()) / PHOTO_H
            lo = 0.86 if name == "small_card_full_frame" else 0.91
            assert lo <= span_x <= 0.96, (name, span_x)
            assert lo <= span_y <= 0.96, (name, span_y)
            # The buffer is real: every true corner sits inside the photo with room to spare.
            assert xs.min() >= 17 and ys.min() >= 23, (name, pts)
            assert xs.max() <= PHOTO_W - 17 and ys.max() <= PHOTO_H - 23, (name, pts)

    def test_the_sharpest_frame_is_the_photo(self):
        from mtg_card_scanner.card_detect import scan_photo
        img, _ = self.scenes["white_tray_black_border"]
        blurred = cv2.GaussianBlur(img, (9, 9), 0)
        photo, detected = scan_photo([blurred, img, blurred])
        alone, _ = scan_photo([img])
        assert detected and np.array_equal(photo, alone)

    def test_no_edges_keeps_the_frame_exactly_as_uploaded(self):
        """Owner: keep the photo — the real picture, never a distorted crop."""
        from mtg_card_scanner.card_detect import scan_photo
        for name in ("empty_tray", "black_holder_only"):
            frame, _ = self.scenes[name]
            photo, detected = scan_photo([frame])
            assert detected is False and photo is frame, name

    def test_photo_card_cuts_a_photo_back_to_its_card_and_nothing_else(self):
        from mtg_card_scanner.card_detect import (PHOTO_MARGIN_X, PHOTO_MARGIN_Y, photo_card,
                                                  scan_photo, warp_card)
        img, _ = self.scenes["white_tray_black_border"]
        photo, _ = scan_photo([img])
        card = photo_card(photo)
        assert card.shape == (CARD_H, CARD_W, 3)
        assert np.array_equal(card, photo[PHOTO_MARGIN_Y:PHOTO_MARGIN_Y + CARD_H,
                                          PHOTO_MARGIN_X:PHOTO_MARGIN_X + CARD_W])
        # The same pixels the plain warp reads (the OCR strip sees the card it always saw).
        plain = warp_card(img, find_card_quad(img))
        assert np.abs(card.astype(int) - plain.astype(int)).mean() < 1.0
        # An older plain warp, a frame kept as uploaded: untouched.
        for other in (plain, img):
            assert photo_card(other) is other
