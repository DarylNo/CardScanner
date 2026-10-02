"""
Synthetic tray scenes at the app's DETECTION-SAMPLE resolution for the
texture-change signal (mtg_card_scanner/change_signal.py): a card on a tray
under shadows, glare and exposure steps (the light changed), and the same
tray with a different / moved / lifted card (the card changed).

SYNTHETIC — every number derived from these frames is a synthetic number,
to be confirmed on the rig. Deterministic: card faces are seeded
(tests/card_scenes.py), the geometry is fixed, and sensor noise comes from a
seeded generator, so a frame is byte-identical on every run (the exporter
writes them as PNG fixtures for the Kotlin parity test).

Geometry (from the app): Standard 1600×1200 analysis frames, rotation 90 →
upright 1200×1600. Tray mode crops the scan Area and GraySampler
box-averages the sensor luma into MW=176 × MH, MH = max(48, round(176·roiH/
roiW)). Assumed rig: the card ~60 % of the frame height and an Area drawn
around it with ~15 % pad → sample 176×246, card ~153×214 sample px,
1 sample px ≈ 4.5 analysis px ≈ 0.41 mm of card.

Rendering: the Area is drawn at K=4× the sample grid (704×984 RGB), lens
blur σ 2 render px, optional exposure gain, sensor noise σ NOISE per render
px (≈ NOISE/4 levels after the 4×4 box average), clip, BT.601 luma, then the
4×4 box average rounded to int — GraySampler's box average of Y.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np

from tests.card_scenes import _card_quad, _place, _tray, card_texture

SW, SH = 176, 246          # detection sample (MW × MH)
K = 4                      # render scale
NOISE = 5.0                # sensor noise σ per render px (≈ 1.25 at sample res)
RW, RH = SW * K, SH * K    # 704 × 984
CARD_H_S = 214             # card height in sample px
CARD_W_S = CARD_H_S * 63 / 88
CX, CY = RW / 2, RH / 2

TRAYS = {
    "light": (225, 225, 220),
    "dark": (45, 47, 52),
}


def quad_A(dx: float = 0.0, dy: float = 0.0, ang: float = 1.5, scale: float = 1.0) -> np.ndarray:
    """Card A's quad in RENDER px (pixel centres at integers), optionally
    shifted (sample px) / rotated (deg)."""
    return _card_quad(CX + dx * K, CY + dy * K, CARD_H_S * K * scale, ang, 0.02)


def watch_quad(dx: float = 0.0, dy: float = 0.0, ang: float = 1.5) -> np.ndarray:
    """Card A's quad in SAMPLE px, pixel centres at integers (the space
    CardQuad.find / find_card_quad answer in): render pixel r sits in sample
    pixel (r − (K−1)/2) / K."""
    return (quad_A(dx, dy, ang) - (K - 1) / 2) / K


@dataclass
class Card:
    tex: np.ndarray
    quad: np.ndarray            # render px


@dataclass
class Frame:
    tray: str
    cards: list = field(default_factory=list)       # list[Card], drawn in order
    shadow: np.ndarray | None = None                # render-res multiplicative field (H, W)
    hand: tuple | None = None                       # (mask render-res float 0..1, rgb)
    glare: np.ndarray | None = None                 # render-res additive-to-white field 0..1
    gain: float = 1.0


def card_A(dx: float = 0.0, dy: float = 0.0, ang: float = 1.5) -> Card:
    return Card(card_texture(11), quad_A(dx, dy, ang))


def card_B(seed: int = 21) -> Card:
    return Card(card_texture(seed), quad_A())


def render(fr: Frame, noise_seed: int, noise: float = NOISE) -> np.ndarray:
    """The detection sample of *fr*: int32 (SH, SW), 0..255."""
    rng = np.random.default_rng(noise_seed)
    img = _tray(RW, RH, TRAYS[fr.tray]).astype(np.uint8)
    for c in fr.cards:
        _place(img, c.tex, c.quad)
    img = img.astype(np.float32)
    if fr.glare is not None:                       # specular: towards white
        img = img + (255.0 - img) * fr.glare[..., None]
    if fr.shadow is not None:                      # falls on the tray/card, not on the hand
        img = img * fr.shadow[..., None]
    if fr.hand is not None:                        # occluding object above the tray
        m, rgb = fr.hand
        img = img * (1 - m[..., None]) + np.asarray(rgb, np.float32)[None, None, :] * m[..., None]
    img = cv2.GaussianBlur(img, (0, 0), 2.0)       # lens
    img = img * fr.gain                            # exposure
    # per-frame AE jitter ±0.5 % and sensor noise
    img = img * (1 + rng.uniform(-0.005, 0.005))
    if noise > 0:
        img = img + rng.normal(0, noise, img.shape).astype(np.float32)
    img = np.clip(img, 0, 255)
    y = 0.299 * img[..., 0] + 0.587 * img[..., 1] + 0.114 * img[..., 2]
    gray = np.floor(y.reshape(SH, K, SW, K).mean(axis=(1, 3)) + 0.5).astype(np.int32)
    return np.clip(gray, 0, 255)


# ── shadow / hand / glare fields (render res) ──────────────────────────────
_yy, _xx = np.mgrid[0:RH, 0:RW].astype(np.float32)


def _field(f: float, m: np.ndarray) -> np.ndarray:
    return (1 - (1 - f) * m).astype(np.float32)


def edge_shadow(f: float, sigma_s: float, pos: float, angle_deg: float = 20.0) -> np.ndarray:
    """Half-plane shadow (dark side = d < 0) with a soft edge of σ *sigma_s*
    sample px (a hard edge blurred — the erfc profile without scipy).
    *pos*: signed edge offset from the Area centre along the normal, sample px."""
    a = np.deg2rad(angle_deg)
    nx, ny = np.cos(a), np.sin(a)
    d = ((_xx - CX) * nx + (_yy - CY) * ny) / K - pos           # sample px
    m = (d < 0).astype(np.float32)
    m = cv2.GaussianBlur(m, (0, 0), sigma_s * K)
    return _field(f, m)


def blob_shadow(f: float, sigma_s: float, cx_s: float, cy_s: float, rx_s: float, ry_s: float) -> np.ndarray:
    m = (((_xx / K - cx_s) / rx_s) ** 2 + ((_yy / K - cy_s) / ry_s) ** 2 <= 1).astype(np.float32)
    m = cv2.GaussianBlur(m, (0, 0), sigma_s * K)
    return _field(f, m)


def full_shadow(f: float, gradient: float = 0.0) -> np.ndarray:
    """Uniform darkening by *f*; gradient > 0 adds a soft left-right falloff."""
    m = np.ones((RH, RW), np.float32)
    if gradient:
        m = m * (1 - gradient * (_xx / RW))
    return _field(f, m)


def hand_mask(cx_s: float, cy_s: float, sigma_s: float = 3.0, scale: float = 1.7) -> np.ndarray:
    """Palm ellipse + forearm to the right edge + fingers to the left, soft."""
    rx, ry = 48 * scale, 36 * scale
    m = (((_xx / K - cx_s) / rx) ** 2 + ((_yy / K - cy_s) / ry) ** 2 <= 1)
    arm = (np.abs(_yy / K - (cy_s + 8)) <= 26 * scale) & (_xx / K >= cx_s)
    for off in (-24, -8, 8, 22):
        fng = ((np.abs(_yy / K - (cy_s + off * scale)) <= 6 * scale)
               & (_xx / K >= cx_s - rx - 30 * scale) & (_xx / K <= cx_s))
        m = m | fng
    m = (m | arm).astype(np.float32)
    return cv2.GaussianBlur(m, (0, 0), sigma_s * K)


def hand_shadow(f: float, cx_s: float, cy_s: float) -> np.ndarray:
    return _field(f, hand_mask(cx_s, cy_s))


def card_mask_render(quad: np.ndarray) -> np.ndarray:
    m = np.zeros((RH, RW), np.uint8)
    cv2.fillPoly(m, [np.round(quad).astype(np.int32)], 1)
    return m


def glare_spot(quad: np.ndarray, u: float, v: float, sigma_s: float, a: float) -> np.ndarray:
    """Specular spot on the card at (u, v) card fractions; only on card pixels."""
    p = quad[0] + (quad[1] - quad[0]) * u + (quad[3] - quad[0]) * v
    g = a * np.exp(-(((_xx - p[0]) ** 2 + (_yy - p[1]) ** 2) / (2 * (sigma_s * K) ** 2)))
    return (g * card_mask_render(quad)).astype(np.float32)


# ── scenarios ──────────────────────────────────────────────────────────────
@dataclass(frozen=True)
class Scenario:
    name: str
    cls: str                 # "light" (the light changed) or "card" (the card changed)
    ref: Frame
    frames: tuple            # the frames to compare against ref; the signal is their PEAK
    # the watch polygon is card A's outline (watch_quad()) for every scenario


def scenarios(tray: str) -> list[Scenario]:
    """The light-vs-card cases the thresholds are set on, for one tray colour."""
    def C(**kw):
        return Frame(tray, [card_A()], **kw)

    def E(**kw):
        return Frame(tray, [], **kw)

    edge_positions = tuple(np.linspace(-150, 150, 9))
    hand_xs = tuple(np.linspace(300, -120, 8))
    c30, s30 = np.cos(np.deg2rad(30)), np.sin(np.deg2rad(30))
    return [
        # the noise floor: the same scene, another noise draw
        Scenario("static card", "light", C(), (C(), C())),
        # the light changed
        Scenario("soft shadow edge moving f0.7", "light", C(),
                 tuple(C(shadow=edge_shadow(0.7, 8, p)) for p in edge_positions)),
        Scenario("uniform shadow f0.5", "light", C(), (C(shadow=full_shadow(0.5)),)),
        Scenario("uniform shadow + gradient f0.5", "light", C(), (C(shadow=full_shadow(0.5, gradient=0.6)),)),
        Scenario("hand-shadow blob passing", "light", C(),
                 tuple(C(shadow=hand_shadow(0.5, x, 130)) for x in hand_xs)),
        Scenario("exposure x0.9", "light", C(), (C(gain=0.9),)),
        Scenario("exposure x1.1", "light", C(), (C(gain=1.1),)),
        Scenario("glare spot sigma6", "light", C(), (C(glare=glare_spot(quad_A(), 0.4, 0.3, 6, 0.9)),)),
        Scenario("glare sheen sigma25", "light", C(), (C(glare=glare_spot(quad_A(), 0.5, 0.5, 25, 0.6)),)),
        # the card changed
        Scenario("swap A->B", "card", C(), (Frame(tray, [card_B(21)]),)),
        Scenario("swap A->B seed24", "card", C(), (Frame(tray, [card_B(24)]),)),
        Scenario("same card shifted 3px", "card", C(), (Frame(tray, [card_A(3 * c30, 3 * s30)]),)),
        Scenario("lift (tray shows)", "card", C(), (E(),)),
        Scenario("card placed in empty area", "card", E(), (C(),)),
    ]
