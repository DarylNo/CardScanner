"""
Card detection and perspective correction.

find_card_quad()  — locates the largest card-shaped quadrilateral in a frame
warp_card()       — perspective-warps the detected quad to a standard 630×880 px upright card
extract_card()    — convenience wrapper: detect + warp, with fallback to a centre crop
"""

import cv2
import numpy as np
from typing import Optional

# Standard card output size (63:88 aspect ratio)
CARD_W, CARD_H = 630, 880


def _order_corners(pts: np.ndarray) -> np.ndarray:
    """Return [top-left, top-right, bottom-right, bottom-left]."""
    pts = pts.reshape(4, 2).astype(np.float32)
    s = pts.sum(axis=1)
    d = np.diff(pts, axis=1).ravel()
    return np.array([
        pts[np.argmin(s)],   # TL — smallest sum
        pts[np.argmin(d)],   # TR — smallest diff
        pts[np.argmax(s)],   # BR — largest sum
        pts[np.argmax(d)],   # BL — largest diff
    ], dtype=np.float32)


def find_card_quad(frame: np.ndarray, min_area_frac: float = 0.04) -> Optional[np.ndarray]:
    """
    Find the largest quadrilateral in *frame* that plausibly contains a card.
    Returns an ordered (4, 2) float32 array of corner points, or None.
    """
    h, w = frame.shape[:2]
    min_area = h * w * min_area_frac

    gray    = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (7, 7), 0)
    edges   = cv2.Canny(blurred, 25, 85)
    kernel  = np.ones((5, 5), np.uint8)
    edges   = cv2.dilate(edges, kernel, iterations=2)

    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    best_quad, best_area = None, 0.0
    for cnt in contours:
        area = cv2.contourArea(cnt)
        if area < min_area:
            continue
        peri  = cv2.arcLength(cnt, True)
        approx = cv2.approxPolyDP(cnt, 0.025 * peri, True)
        if len(approx) == 4 and area > best_area:
            best_area = area
            best_quad = approx

    if best_quad is None:
        return None
    return _order_corners(best_quad)


def warp_card(frame: np.ndarray, corners: np.ndarray,
              out_w: int = CARD_W, out_h: int = CARD_H) -> np.ndarray:
    """Perspective-warp frame to a standard upright card image."""
    dst = np.array([[0, 0], [out_w, 0], [out_w, out_h], [0, out_h]], dtype=np.float32)
    M   = cv2.getPerspectiveTransform(corners, dst)
    return cv2.warpPerspective(frame, M, (out_w, out_h))


def _centre_crop_fallback(frame: np.ndarray) -> np.ndarray:
    """
    If card detection fails, crop the centre 55% × 80% of the frame as a
    best-effort card region and resize to the standard card size.
    """
    h, w = frame.shape[:2]
    x0 = int(w * 0.225); x1 = int(w * 0.775)
    y0 = int(h * 0.10);  y1 = int(h * 0.90)
    crop = frame[y0:y1, x0:x1]
    return cv2.resize(crop, (CARD_W, CARD_H), interpolation=cv2.INTER_CUBIC)


def extract_card(frame: np.ndarray, min_area_frac: float = 0.04) -> tuple[np.ndarray, bool]:
    """
    Detect, warp, and return the card region.

    Returns:
        (card_img, detected)  — detected=True if a quad was found,
                                False if the fallback centre-crop was used.
    """
    corners = find_card_quad(frame, min_area_frac)
    if corners is not None:
        return warp_card(frame, corners), True
    return _centre_crop_fallback(frame), False


def card_sub_crops(card: np.ndarray) -> dict[str, np.ndarray]:
    """
    Given a perspective-corrected card image (CARD_W × CARD_H),
    return named sub-crops with upscaling and sharpening applied.
    """
    h, w = card.shape[:2]

    def up_sharp(img: np.ndarray, scale: float = 2.5, sharp: float = 1.2) -> np.ndarray:
        up   = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
        blur = cv2.GaussianBlur(up, (0, 0), 3)
        return cv2.addWeighted(up, 1 + sharp, blur, -sharp, 0)

    # Title bar: top ~12% of the card (name text lives here)
    title = card[:int(h * 0.12), :]

    # Bottom strip: bottom ~16% (collector info line)
    strip = card[int(h * 0.84):, :]

    # Bottom-left collector number: bottom 14%, left 65%
    # (collector number + set code are on the left side of the bottom line)
    bl = card[int(h * 0.86):, :int(w * 0.65)]

    return {
        "title_bar":              up_sharp(title, scale=3.0),
        "bottom_strip":           up_sharp(strip, scale=2.5),
        "bottom_left_collector":  up_sharp(bl,    scale=4.0, sharp=1.8),
    }
