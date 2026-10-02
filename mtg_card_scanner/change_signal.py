"""
The shadow-proof "texture change" signal: did the card inside a watch
polygon CHANGE, or did only the light on it change?

Reference implementation (the phone's `core/TextureChange.kt` is a port,
held to this by `scripts/export_change_fixtures.py` + TextureChangeParityTest).
Change this first, re-export, then port.

Why: the Tray trigger (occupancy + stillness, phone.html) sees a shadow
leaning over a scanned card as "something changed" — a per-pixel grey
difference cannot tell a hand's shadow from a new card. The owner's rule
(2026-10-02): "Can't trigger on silly things like shadows", and his
intuition is right in the frequency domain: a shadow is a smooth,
MULTIPLICATIVE change (every pixel under it scales by the same factor, with
a soft edge), while a different card changes the FINE PRINT — edges, text,
art — in every direction. So:

  L  = ln(Y + 8)                        per sample pixel: a multiplicative
                                        shadow becomes an additive offset
  HP = L − GaussianBlur(L, σ = 4 px)    high-pass: the offset (and any soft
                                        edge) is removed, the print stays
  per 8×8 block ≥ 75 % inside the polygon mask:
       mean |HP_cur − HP_ref| × 100
  logHP p75 = 75th percentile of the block values

The 75th percentile over blocks ignores the few blocks a shadow's EDGE or a
glare spot does disturb (a half-plane edge crosses a handful of blocks; a
new card disturbs most of them), and the block mean keeps sensor noise down.
`log_grad` — the mean difference of the Sobel gradient of L over the mask,
× 100 — is the second opinion: a gradient is also shadow-invariant, and it
reacts to a swapped card whose print has the same overall layout.

MEASURED (synthetic scenes at the app's 176×246 detection-sample
resolution — the 2026-10-02 shadow assessment, 96 scenarios on three
trays): with sensor noise σ ≈ 1.25 grey levels at sample resolution, every
shadow / glare / exposure case scored ≤ 4.8 on logHP p75 (the worst: a
hand's shadow passing over the card; the assessment's summary quotes
≤ 6.3), every real change ≥ 8.6 (the weakest: the same card shifted by
half a sample pixel). The gap narrows with noise (σ 2.5 → 5.4 vs 9.7;
σ 4 → 7.9 vs 10.6), so the N200's real noise decides the threshold. On the
committed scenes (tests/change_scenes.py, the card's outline padded by
PAD_FRAC as the mask, light and dark trays) light-only cases score ≤ 4.1
and card changes ≥ 12.7. START_THRESHOLD = 8 is the synthetic midpoint,
used ONLY in shadow mode (computed and logged, never acting) until the rig
confirms it.

Everything here is NOT a trigger: the Tray trigger stays occupancy +
stillness (CLAUDE.md). This signal is for the "watch the card shape for a
NEW card" step, after a scan, and only once measured on the rig.

Coordinates: a quad is 4 corners (TL, TR, BR, BL) in SAMPLE pixels with the
OpenCV convention that pixel (x, y)'s centre is the point (x, y) — the
space `card_detect.find_card_quad` / the phone's CardQuad return quads in.
The mask tests those integer pixel positions against the polygon directly.
"""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

SIGMA = 4.0              # Gaussian σ of the high-pass, in sample pixels
LOG_OFFSET = 8.0         # ln(Y + LOG_OFFSET): keeps black pixels finite and tames noise in the dark
BLOCK = 8                # block side, sample pixels
BLOCK_INSIDE = 0.75      # a block counts when ≥ this fraction of its pixels lie inside the mask
PAD_FRAC = 0.15          # the watch polygon = the card quad grown by this fraction of its size per side
PERCENTILE = 75.0
SCALE = 100.0            # the signals are reported × 100 (log units are small)
START_THRESHOLD = 8.0    # logHP p75 above this = the card changed. SYNTHETIC — confirm on the rig.


@dataclass(frozen=True)
class ChangeSignal:
    log_hp_p75: float     # 75th percentile over blocks of mean |ΔHP| × 100
    log_grad: float       # mean over the mask of |∇L_cur − ∇L_ref| × 100
    blocks: int           # blocks that were ≥ BLOCK_INSIDE inside the mask
    mask_pixels: int      # pixels inside the polygon


def pad_quad(quad, pad_frac: float = PAD_FRAC) -> np.ndarray:
    """*quad* grown about its centroid so each side moves out by *pad_frac*
    of the card's size in that direction (HandheldGuide.PAD's meaning)."""
    q = np.asarray(quad, np.float64).reshape(4, 2)
    c = q.mean(axis=0)
    return c + (q - c) * (1.0 + 2.0 * pad_frac)


def polygon_mask(quad, w: int, h: int, pad_frac: float = PAD_FRAC) -> np.ndarray:
    """Boolean (h, w) mask of the pixels whose integer position lies inside
    the padded convex quad (edges count as inside). Pure arithmetic in a
    fixed order, so the Kotlin port makes the identical mask."""
    p = pad_quad(quad, pad_frac)
    xs = np.arange(w, dtype=np.float64)[None, :]
    ys = np.arange(h, dtype=np.float64)[:, None]
    # Orientation of the polygon (shoelace sign): inside = every edge cross has that sign.
    area2 = 0.0
    for i in range(4):
        x0, y0 = p[i]; x1, y1 = p[(i + 1) % 4]
        area2 += x0 * y1 - x1 * y0
    sign = 1.0 if area2 >= 0.0 else -1.0
    inside = np.ones((h, w), bool)
    for i in range(4):
        x0, y0 = p[i]; x1, y1 = p[(i + 1) % 4]
        cross = (x1 - x0) * (ys - y0) - (y1 - y0) * (xs - x0)
        inside &= (sign * cross) >= 0.0
    return inside


def blocks_inside(mask: np.ndarray, block: int = BLOCK, inside: float = BLOCK_INSIDE) -> list[tuple[int, int]]:
    """(by, bx) of every whole block whose pixels are ≥ *inside* in the mask, row-major."""
    h, w = mask.shape
    out = []
    for by in range(0, h - block + 1, block):
        for bx in range(0, w - block + 1, block):
            if mask[by:by + block, bx:bx + block].mean() >= inside:
                out.append((by, bx))
    return out


def log_image(gray) -> np.ndarray:
    return np.log(np.asarray(gray, np.float64) + LOG_OFFSET)


def high_pass(L: np.ndarray, sigma: float = SIGMA) -> np.ndarray:
    """L − GaussianBlur(L, σ): cv2's kernel (ksize = round(8σ + 1) | 1, BORDER_REFLECT_101)."""
    return L - cv2.GaussianBlur(L, (0, 0), sigma)


def gradient(L: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Sobel 3×3 of L, /8 so it is the mean central difference."""
    gx = cv2.Sobel(L, cv2.CV_64F, 1, 0, ksize=3) / 8
    gy = cv2.Sobel(L, cv2.CV_64F, 0, 1, ksize=3) / 8
    return gx, gy


def block_values(cur, ref, mask: np.ndarray, blocks: list[tuple[int, int]]) -> list[float]:
    """Each block's mean |ΔHP| × 100 over its masked pixels, in block order."""
    dhp = np.abs(high_pass(log_image(cur)) - high_pass(log_image(ref)))
    b = BLOCK
    return [float(dhp[by:by + b, bx:bx + b][mask[by:by + b, bx:bx + b]].mean() * SCALE) for by, bx in blocks]


def log_grad(cur, ref, mask: np.ndarray) -> float:
    gxc, gyc = gradient(log_image(cur))
    gxr, gyr = gradient(log_image(ref))
    return float(np.hypot(gxc - gxr, gyc - gyr)[mask].mean() * SCALE)


def measure(cur, ref, mask: np.ndarray) -> ChangeSignal | None:
    """The signal between two grey samples inside *mask*; None when no block
    is inside it (nothing to watch)."""
    cur = np.asarray(cur); ref = np.asarray(ref)
    if cur.shape != ref.shape or cur.shape != mask.shape:
        raise ValueError(f"shape mismatch: cur {cur.shape} ref {ref.shape} mask {mask.shape}")
    blocks = blocks_inside(mask)
    if not blocks:
        return None
    vals = block_values(cur, ref, mask, blocks)
    return ChangeSignal(float(np.percentile(vals, PERCENTILE)), log_grad(cur, ref, mask),
                        len(blocks), int(mask.sum()))
