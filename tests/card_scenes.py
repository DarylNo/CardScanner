"""
Deterministic synthetic tray scenes for card-detection parity tests.

The phone app ports find_card_quad (card_detect.py) and flattens the card
BEFORE upload; the server then re-detects the card on that upload. Both
claims need scenes with a known answer, and the repo carries no real scan
photos (scan_images/ lives on the rig), so these are drawn: a card with
printed-looking detail (title bar, art box, text lines, collector line)
perspective-placed on a tray, plus the decoys that shaped card_detect.py —
a featureless black holder, an empty tray, a card at the frame edge.

Everything is seeded (card faces) and drawn with fixed geometry, so the same scene
comes out byte-identical on every run; scripts/export_detect_fixtures.py
writes them as PNG (lossless — JPEG decoders differ between OpenCV builds)
with the server's own find_card_quad answer for the Android JVM tests.
"""

from __future__ import annotations

import cv2
import numpy as np

CARD_TEX_W, CARD_TEX_H = 315, 440          # 63:88, drawn then warped into place


def card_texture(seed: int, border=(18, 18, 18)) -> np.ndarray:
    """A card face with the kind of printed detail real cards have."""
    rng = np.random.default_rng(seed)
    w, h = CARD_TEX_W, CARD_TEX_H
    img = np.full((h, w, 3), border, np.uint8)
    frame_col = tuple(int(v) for v in rng.integers(120, 230, 3))
    cv2.rectangle(img, (14, 14), (w - 15, h - 15), frame_col, -1)
    # title bar + name "text"
    cv2.rectangle(img, (22, 22), (w - 23, 52), (235, 230, 220), -1)
    for i in range(6):
        x = 30 + i * 22
        cv2.rectangle(img, (x, 31), (x + 14, 43), (30, 30, 30), -1)
    # art box: blocky "painting"
    art = rng.integers(0, 255, (6, 8, 3)).astype(np.uint8)
    art = cv2.resize(art, (w - 46, 170), interpolation=cv2.INTER_NEAREST)
    img[58:228, 23:w - 23] = art
    # type line + rules text lines
    cv2.rectangle(img, (22, 234), (w - 23, 258), (235, 230, 220), -1)
    cv2.rectangle(img, (22, 264), (w - 23, h - 48), (240, 236, 228), -1)
    for row in range(8):
        y = 274 + row * 14
        n = int(rng.integers(5, 11))
        x = 30
        for _ in range(n):
            ww = int(rng.integers(8, 26))
            if x + ww > w - 32:
                break
            cv2.rectangle(img, (x, y), (x + ww, y + 6), (40, 40, 40), -1)
            x += ww + 6
    # collector line
    for i in range(9):
        cv2.rectangle(img, (26 + i * 9, h - 36), (31 + i * 9, h - 30), (220, 220, 220), -1)
    return img


def _place(canvas: np.ndarray, tex: np.ndarray, quad: np.ndarray) -> None:
    """Perspective-paste *tex* onto *canvas* at *quad* (TL,TR,BR,BL)."""
    h, w = tex.shape[:2]
    src = np.array([[0, 0], [w, 0], [w, h], [0, h]], np.float32)
    M = cv2.getPerspectiveTransform(src, quad.astype(np.float32))
    H, W = canvas.shape[:2]
    warped = cv2.warpPerspective(tex, M, (W, H), flags=cv2.INTER_LINEAR)
    mask = cv2.warpPerspective(np.full((h, w), 255, np.uint8), M, (W, H),
                               flags=cv2.INTER_NEAREST)
    canvas[mask > 0] = warped[mask > 0]


def _card_quad(cx, cy, card_h, angle_deg, tilt=0.0) -> np.ndarray:
    """Corners of a 63:88 card centred at (cx,cy), rotated, with keystone tilt."""
    hw, hh = card_h * 63 / 88 / 2, card_h / 2
    pts = np.array([[-hw, -hh], [hw, -hh], [hw, hh], [-hw, hh]], np.float64)
    pts[0, 0] += tilt * hw; pts[1, 0] -= tilt * hw          # top edge narrower
    a = np.deg2rad(angle_deg)
    R = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]])
    return (pts @ R.T + [cx, cy]).astype(np.float32)


def _tray(W, H, colour, gradient=False) -> np.ndarray:
    # No sensor noise: it sits far below Canny's 25/85 thresholds, so it never
    # changed an answer, but it made the exported PNG fixtures incompressible.
    img = np.full((H, W, 3), colour, np.float32)
    if gradient:                                            # soft glare falloff
        yy, xx = np.mgrid[0:H, 0:W]
        img += 40 * np.exp(-(((xx - W * 0.7) / (W * 0.35)) ** 2
                             + ((yy - H * 0.3) / (H * 0.35)) ** 2))[..., None]
    return np.clip(img, 0, 255).astype(np.uint8)


# name -> (builder, card_expected). card_expected=False means the correct
# answer is "no card" (find_card_quad returns None).
def _scene(W, H, tray, cards=(), holder=None, gradient=False):
    img = _tray(W, H, tray, gradient)
    if holder is not None:                                  # featureless decoy
        _place(img, np.full((440, 315, 3), (22, 22, 24), np.uint8), holder)
    for tex_seed, quad, border in cards:
        _place(img, card_texture(tex_seed, border), quad)
    return img


# The card in each scene that has one: (texture seed, border colour, quad
# arguments for _card_quad) — the TRUE card geometry the parity measurements
# (phone flatten, the scan photo's fill) are scored against.
_CARDS = {
    "white_tray_black_border": (11, (18, 18, 18), (480, 360, 560, 8, 0.04)),
    "dark_tray_white_border": (12, (235, 235, 235), (470, 370, 540, -6, 0.03)),
    "sideways_card": (13, (18, 18, 18), (480, 360, 520, 88)),
    "holder_decoy_beside_card": (14, (18, 18, 18), (690, 360, 420, 4)),
    "small_card_full_frame": (15, (18, 18, 18), (300, 250, 170, 12)),
    "card_near_frame_edge": (16, (18, 18, 18), (215, 330, 560, 3)),
    "glare_gradient_tray": (17, (18, 18, 18), (480, 360, 540, -10, 0.05)),
    "portrait_frame": (18, (18, 18, 18), (360, 480, 700, 2, 0.02)),
}


def true_card_quad(name: str) -> np.ndarray:
    """The TRUE corners (TL,TR,BR,BL of the drawn face) of the card in scene *name*."""
    return _card_quad(*_CARDS[name][2])


def _card(name: str):
    seed, border, args = _CARDS[name]
    return (seed, _card_quad(*args), border)


def scenes() -> dict[str, tuple[np.ndarray, bool]]:
    W, H = 960, 720
    return {
        "white_tray_black_border": (_scene(W, H, (225, 225, 220),
            cards=[_card("white_tray_black_border")]), True),
        "dark_tray_white_border": (_scene(W, H, (40, 42, 48),
            cards=[_card("dark_tray_white_border")]), True),
        "sideways_card": (_scene(W, H, (200, 205, 210),
            cards=[_card("sideways_card")]), True),
        "holder_decoy_beside_card": (_scene(W, H, (215, 215, 210),
            holder=_card_quad(250, 360, 600, 0),
            cards=[_card("holder_decoy_beside_card")]), True),
        "small_card_full_frame": (_scene(W, H, (210, 210, 205),
            cards=[_card("small_card_full_frame")]), True),
        "card_near_frame_edge": (_scene(W, H, (220, 220, 215),
            cards=[_card("card_near_frame_edge")]), True),
        "glare_gradient_tray": (_scene(W, H, (185, 190, 195), gradient=True,
            cards=[_card("glare_gradient_tray")]), True),
        "portrait_frame": (_scene(720, 960, (225, 225, 220),
            cards=[_card("portrait_frame")]), True),
        "empty_tray": (_scene(W, H, (225, 225, 220)), False),
        "black_holder_only": (_scene(W, H, (215, 215, 210),
            holder=_card_quad(480, 360, 560, 0)), False),
    }
