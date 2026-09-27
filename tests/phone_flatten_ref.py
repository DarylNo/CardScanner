"""
Reference (executable spec) for the Android app's on-phone card flattening.

The app finds the card with a port of card_detect.find_card_quad, then
uploads the card FLATTENED with a margin of tray around it instead of the
whole scan-area frame. The server is still the judge: it re-runs its own
detection on that upload, and the margin is what lets it find the card's
edges again (a card filling >92% of the frame is rejected as "the frame
itself" — card_detect.find_card_quad's max_area).

Measured on the synthetic scenes (tests/card_scenes.py) against the TRUE card
face: flattening on the phone matched or beat the server warping the raw
frame in 7/8 scenes (a small card: 10 → 4 pHash bits from truth, since the
server's 5×5 dilation inflates a small quad by ~3%). The 8th — a card
touching the frame edge — got WORSE (4 → 12): margin pixels outside the
frame had to be invented (BORDER_REPLICATE smeared the black card border
outward) and the re-detected quad came out wide. Hence margin_fits(): the
phone flattens only when the whole margin lies inside the frame, otherwise
it uploads the frame exactly as before. On the rig the scan area is drawn
tight around the tray, so the margin is taken from the FULL camera frame
(the quad is found in the scan-area crop, as the server would, then offset
into full-frame coordinates) and steps down 8% → 6% → 4% to fit.

The Kotlin port must match these numbers; the constants are the contract.
"""

from __future__ import annotations

import cv2
import numpy as np

# Largest margin that fits wins; below the last one the frame goes up as-is.
# 4% was the smallest measured: the server re-detected every scene at 0.04
# (card = 84% of the upload, under its 92% "that's the frame itself" cut).
MARGINS = (0.08, 0.06, 0.04)   # of the card's own width/height, each side
SCALE = 1.25                   # flattened card = 1.25 × the server's 630×880 warp
CARD_W, CARD_H = round(630 * SCALE), round(880 * SCALE)          # 788 × 1100


def _layout(margin: float) -> tuple[int, int, int, int]:
    """(mx, my, out_w, out_h) for a margin fraction."""
    mx, my = round(CARD_W * margin), round(CARD_H * margin)
    return mx, my, CARD_W + 2 * mx, CARD_H + 2 * my


def _transform(quad: np.ndarray, margin: float) -> np.ndarray:
    mx, my, _, _ = _layout(margin)
    dst = np.float32([[mx, my], [mx + CARD_W, my],
                      [mx + CARD_W, my + CARD_H], [mx, my + CARD_H]])
    return cv2.getPerspectiveTransform(quad.astype(np.float32), dst)


def margin_fits(quad: np.ndarray, frame_w: int, frame_h: int, margin: float) -> bool:
    """True when every pixel of the flattened output maps inside the frame."""
    _, _, out_w, out_h = _layout(margin)
    inv = np.linalg.inv(_transform(quad, margin))
    out = np.float32([[[0, 0]], [[out_w, 0]], [[out_w, out_h]], [[0, out_h]]])
    src = cv2.perspectiveTransform(out, inv).reshape(4, 2)
    return bool((src[:, 0] >= 0).all() and (src[:, 0] <= frame_w - 1).all()
                and (src[:, 1] >= 0).all() and (src[:, 1] <= frame_h - 1).all())


def choose_margin(quad: np.ndarray, frame_w: int, frame_h: int):
    """The largest MARGINS entry that fits, or None (upload the frame as-is)."""
    return next((m for m in MARGINS if margin_fits(quad, frame_w, frame_h, m)), None)


def phone_flatten(frame: np.ndarray, quad: np.ndarray, margin: float) -> np.ndarray:
    """Card + margin, perspective-flattened (BGR). *quad* is in *frame* pixels."""
    _, _, out_w, out_h = _layout(margin)
    return cv2.warpPerspective(frame, _transform(quad, margin), (out_w, out_h),
                               flags=cv2.INTER_LINEAR,
                               borderMode=cv2.BORDER_REPLICATE)
