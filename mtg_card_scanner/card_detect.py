"""
Card detection and perspective correction.

find_card_quad()  — locates the largest card-shaped quadrilateral in a frame
warp_card()       — perspective-warps the detected quad to a standard 630×880 px upright card
extract_card()    — convenience wrapper: detect + warp, with fallback to a centre crop
frame_sharpness() / pick_sharpest() — Laplacian-variance blur scoring for burst frames
"""

import cv2
import numpy as np
from typing import Optional

# Standard card output size (63:88 aspect ratio)
CARD_W, CARD_H = 630, 880


# A real card is 63:88 → long/short ≈ 1.40. Quads outside this window are not
# cards, and warping them would stretch whatever they contain into card shape.
_MIN_CARD_RATIO, _MAX_CARD_RATIO = 1.15, 1.75
# Contour must actually fill its own bounding rectangle to be a card.
_MIN_RECTANGULARITY = 0.75

# A card's interior is full of printed detail — title text, rules text, art
# edges — while a card-SHAPED uniform surface (a black card holder, a deck-box
# lid, the bare tray) has almost none. Geometry alone cannot tell them apart,
# and picking the largest quad meant a black holder in frame beat the actual
# card, whose dark uniform crop then pHash-matched dark artworks as confident
# nonsense. Both bars must clear: std catches flat surfaces, edge fraction
# catches smooth glare gradients (which inflate std but produce no edges).
# Measured at 64×88 thumbnail scale: holders/tray score std ≈ 3-9 with edge
# fraction ≈ 0.000-0.005; real cards — even dark-art black-border ones — sit
# well above both bars.
_MIN_INTERIOR_STD = 12.0
_MIN_INTERIOR_EDGE_FRAC = 0.015
# Stricter edge bar for the CENTRE-CROP FALLBACK (quad detection failed): that
# crop contains the object plus tray, so a black holder's outline alone yields
# some edges (measured ≤ 0.055 across holder sizes/darkness) while a real
# card's text and art dominate its crop (measured ≥ 0.142, median 0.184).
# 0.09 splits the two with ~2× margin on both sides.
_MIN_FALLBACK_EDGE_FRAC = 0.09


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


def _orient_corners(box: np.ndarray) -> np.ndarray:
    """
    Order a rotated rectangle's 4 points as [TL, TR, BR, BL] with the card's
    LONG axis running vertically.

    Two separate things go wrong without this. ``cv2.boxPoints`` does not
    guarantee a starting corner or winding direction, so ordering by walking
    the array rotates the card by 90° half the time; and a card lying sideways
    warped into the portrait output squashes its 88 mm axis into 63 mm of
    pixels. Ordering positionally first, then rotating only when the rectangle
    really is landscape, fixes both.

    A 180° ambiguity is left over (nothing in the geometry says which end is
    the top) — ArtIndex.identify retries upside-down when unsure.
    """
    pts = _order_corners(box)                       # positional TL,TR,BR,BL
    w_edge = float(np.linalg.norm(pts[1] - pts[0]))
    h_edge = float(np.linalg.norm(pts[2] - pts[1]))
    if w_edge > h_edge:                             # landscape → stand it up
        pts = np.roll(pts, -1, axis=0)
    return pts.astype(np.float32)


def _candidate_from_contour(cnt: np.ndarray, min_area: float, max_area: float):
    """Return (corners, area) if *cnt* is card-shaped, else None."""
    area = cv2.contourArea(cnt)
    if area < min_area or area > max_area:
        return None
    rect = cv2.minAreaRect(cnt)
    (rw, rh) = rect[1]
    if rw <= 1 or rh <= 1:
        return None
    long_side, short_side = max(rw, rh), min(rw, rh)
    ratio = long_side / short_side
    if not (_MIN_CARD_RATIO <= ratio <= _MAX_CARD_RATIO):
        return None
    if area / (rw * rh) < _MIN_RECTANGULARITY:
        return None
    return _orient_corners(cv2.boxPoints(rect)), area


def _texture_metrics(gray_card: np.ndarray) -> tuple[float, float]:
    """(std, edge_frac) of a grayscale card image's interior at 64×88 scale."""
    small = cv2.resize(gray_card, (64, 88), interpolation=cv2.INTER_AREA)
    inner = small[6:82, 5:59]          # drop the border / edge transition
    edges = cv2.Canny(inner, 30, 90)
    return float(inner.std()), float(np.count_nonzero(edges)) / edges.size


def _has_card_texture(gray: np.ndarray, corners: np.ndarray) -> bool:
    """True when the quad's warped interior contains printed-card detail."""
    patch = warp_card(gray, corners, 64, 88)
    std, edge_frac = _texture_metrics(patch)
    return std >= _MIN_INTERIOR_STD and edge_frac >= _MIN_INTERIOR_EDGE_FRAC


def is_blank_surface(card_img: np.ndarray, detected: bool = True) -> bool:
    """
    True when *card_img* holds no printed card content — a black card holder,
    an empty tray, a lens cap. Used by the pipeline to reject such scans
    outright: their near-featureless pHashes can land within confident (or at
    least junk-row) distance of dark low-contrast artworks, which is exactly
    how a holder got scanned as a real card.

    Pass detected=False for a centre-crop fallback — the object's own outline
    against the tray produces edges a warped card interior wouldn't have, so
    that path needs the stricter bar (see _MIN_FALLBACK_EDGE_FRAC).
    """
    gray = cv2.cvtColor(card_img, cv2.COLOR_BGR2GRAY) if card_img.ndim == 3 else card_img
    std, edge_frac = _texture_metrics(gray)
    bar = _MIN_INTERIOR_EDGE_FRAC if detected else _MIN_FALLBACK_EDGE_FRAC
    return std < _MIN_INTERIOR_STD or edge_frac < bar


def find_card_quad(frame: np.ndarray, min_area_frac: float = 0.04) -> Optional[np.ndarray]:
    """
    Find the largest card-shaped quadrilateral in *frame*.

    Returns an ordered (4, 2) float32 array of corners — arranged so the card's
    long axis maps to the output height, i.e. sideways cards come out upright —
    or None when nothing card-shaped is found.

    Several edge maps are tried because one threshold cannot cover both a
    black-bordered card on a white tray and a white-bordered one; a rotated
    minAreaRect is used rather than requiring a clean 4-vertex polygon, which
    is fragile when a corner is blurred or a border blends into the tray.
    """
    h, w = frame.shape[:2]
    min_area = h * w * min_area_frac
    # A contour covering essentially the whole frame is the frame itself, not a
    # detected card — accepting it warps the full photo (margins included) into
    # card shape, which shifts every hash region off the real card.
    max_area = h * w * 0.92

    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (7, 7), 0)
    kernel = np.ones((5, 5), np.uint8)

    edge_maps = [
        cv2.Canny(blurred, 25, 85),
        cv2.Canny(blurred, 50, 150),
        # Otsu split — catches cards whose border barely differs from the tray.
        cv2.morphologyEx(
            cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)[1],
            cv2.MORPH_CLOSE, kernel,
        ),
    ]

    best_corners, best_area = None, 0.0
    for edges in edge_maps:
        closed = cv2.dilate(edges, kernel, iterations=2)
        contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for cnt in contours:
            found = _candidate_from_contour(cnt, min_area, max_area)
            # The texture gate runs last (it warps a patch, the geometry checks
            # are cheaper) and rejects card-shaped uniform surfaces — without
            # it, a black card holder in frame out-areas the actual card.
            if found and found[1] > best_area and _has_card_texture(gray, found[0]):
                best_corners, best_area = found

    return best_corners


def warp_card(frame: np.ndarray, corners: np.ndarray,
              out_w: int = CARD_W, out_h: int = CARD_H) -> np.ndarray:
    """Perspective-warp frame to a standard upright card image."""
    dst = np.array([[0, 0], [out_w, 0], [out_w, out_h], [0, out_h]], dtype=np.float32)
    M   = cv2.getPerspectiveTransform(corners, dst)
    return cv2.warpPerspective(frame, M, (out_w, out_h))


def _centre_crop_fallback(frame: np.ndarray) -> np.ndarray:
    """
    Detection failed — take the largest centred region that already has the
    card's aspect ratio and scale it, WITHOUT stretching.

    The previous version cropped a fixed 55%×80% window and resized it to
    630×880 regardless of its shape, distorting whatever it contained; a
    mis-detected frame then produced both garbage hashes and a warped review
    photo. Preserving aspect keeps a failed detection merely off-centre
    rather than geometrically wrong.
    """
    h, w = frame.shape[:2]
    target = CARD_W / CARD_H
    crop_h = min(h, int(w / target))
    crop_w = int(crop_h * target)
    x0 = max(0, (w - crop_w) // 2)
    y0 = max(0, (h - crop_h) // 2)
    crop = frame[y0:y0 + crop_h, x0:x0 + crop_w]
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


# ── sharpness ─────────────────────────────────────────────────────────────────

def frame_sharpness(frame: np.ndarray) -> float:
    """Laplacian variance — higher means sharper (less blur)."""
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def pick_sharpest(frames: list[np.ndarray]) -> np.ndarray:
    """Return the frame with the highest Laplacian variance."""
    return max(frames, key=frame_sharpness)
