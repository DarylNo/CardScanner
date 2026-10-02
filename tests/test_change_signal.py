"""
The texture-change signal (mtg_card_scanner/change_signal.py) must tell a
LIGHT change (shadow, glare, exposure) from a CARD change (swapped, moved,
lifted) inside the padded card polygon, on a light and a dark tray — the
rule behind "can't trigger on silly things like shadows". Synthetic scenes
(tests/change_scenes.py); the thresholds here are the synthetic margins,
to be confirmed on the rig before anything acts on the signal.
"""

import numpy as np
import pytest

from mtg_card_scanner import change_signal as sig
from tests import change_scenes as cs

LIGHT_MAX = 7.0     # every light-only case scores under this …
CARD_MIN = 8.0      # … and every card change over this (START_THRESHOLD sits between)


def _mask():
    return sig.polygon_mask(cs.watch_quad(), cs.SW, cs.SH)


def _peak(scenario, mask, seed):
    """The scenario's peak signal over its frames (a moving shadow is judged at its worst)."""
    ref = cs.render(scenario.ref, seed)
    best = None
    for i, fr in enumerate(scenario.frames):
        s = sig.measure(cs.render(fr, seed + 1 + i), ref, mask)
        if best is None or s.log_hp_p75 > best.log_hp_p75:
            best = s
    return best


@pytest.mark.parametrize("tray", sorted(cs.TRAYS))
def test_light_changes_score_low_and_card_changes_high(tray):
    mask = _mask()
    rows = []
    seed = 1000 if tray == "light" else 2000
    for n, sc in enumerate(cs.scenarios(tray)):
        s = _peak(sc, mask, seed + 100 * n)
        rows.append((sc.name, sc.cls, s.log_hp_p75, s.log_grad))
    light = [(name, p) for name, cls, p, _ in rows if cls == "light"]
    card = [(name, p) for name, cls, p, _ in rows if cls == "card"]
    table = "\n".join(f"  {name:34s} {cls:5s} logHP p75 {p:6.2f}  logGrad {g:6.2f}" for name, cls, p, g in rows)
    assert light and card
    assert all(p < LIGHT_MAX for _, p in light), f"a light change scored ≥ {LIGHT_MAX} ({tray}):\n{table}"
    assert all(p > CARD_MIN for _, p in card), f"a card change scored ≤ {CARD_MIN} ({tray}):\n{table}"
    # logGrad, the second opinion, separates them the same way (smaller numbers).
    assert max(g for _, cls, _, g in rows if cls == "light") < min(g for _, cls, _, g in rows if cls == "card")
    # START_THRESHOLD sits inside the gap.
    assert max(p for _, p in light) < sig.START_THRESHOLD < min(p for _, p in card)


def test_specific_cases_have_margin():
    """The named cases of the request, on both trays: shadows under 7, changes over 8."""
    mask = _mask()
    want = {
        "soft shadow edge moving f0.7": "light", "uniform shadow f0.5": "light",
        "hand-shadow blob passing": "light", "exposure x0.9": "light",
        "swap A->B": "card", "same card shifted 3px": "card", "lift (tray shows)": "card",
    }
    for tray in sorted(cs.TRAYS):
        by_name = {sc.name: sc for sc in cs.scenarios(tray)}
        for i, (name, cls) in enumerate(want.items()):
            assert by_name[name].cls == cls
            p = _peak(by_name[name], mask, 5000 + 100 * i).log_hp_p75
            if cls == "light":
                assert p < LIGHT_MAX, f"{tray}: {name} scored {p:.2f}"
            else:
                assert p > CARD_MIN, f"{tray}: {name} scored {p:.2f}"


def test_identical_frames_score_zero_and_noise_is_the_floor():
    mask = _mask()
    f = cs.render(cs.Frame("light", [cs.card_A()]), 7, noise=0.0)
    s = sig.measure(f, f, mask)
    assert s.log_hp_p75 == 0.0 and s.log_grad == 0.0
    a = cs.render(cs.Frame("dark", [cs.card_A()]), 8)
    b = cs.render(cs.Frame("dark", [cs.card_A()]), 9)
    s = sig.measure(a, b, mask)
    assert 0.0 < s.log_hp_p75 < 3.0 and 0.0 < s.log_grad < 2.0


def test_render_is_deterministic():
    fr = cs.Frame("light", [cs.card_A()], shadow=cs.edge_shadow(0.7, 8, 10))
    assert np.array_equal(cs.render(fr, 42), cs.render(fr, 42))
    assert not np.array_equal(cs.render(fr, 42), cs.render(fr, 43))


# ── the mask ───────────────────────────────────────────────────────────────

def test_pad_quad_grows_each_side_by_the_pad_of_the_card_size():
    q = np.array([[10.0, 20.0], [70.0, 20.0], [70.0, 104.0], [10.0, 104.0]])      # 60×84 card
    p = sig.pad_quad(q, 0.15)
    assert np.allclose(p.mean(axis=0), q.mean(axis=0))
    assert np.allclose(p[1] - p[0], [60 * 1.3, 0])        # 15 % of 60 on each side
    assert np.allclose(p[3] - p[0], [0, 84 * 1.3])
    assert np.allclose(sig.pad_quad(q, 0.0), q)


def test_mask_is_the_padded_polygon():
    q = np.array([[40.0, 50.0], [120.0, 50.0], [120.0, 160.0], [40.0, 160.0]])
    m = sig.polygon_mask(q, cs.SW, cs.SH)
    unpadded = sig.polygon_mask(q, cs.SW, cs.SH, pad_frac=0.0)
    # The unpadded mask is exactly the pixels on/inside the rectangle (edges inclusive).
    yy, xx = np.mgrid[0:cs.SH, 0:cs.SW]
    assert np.array_equal(unpadded, (xx >= 40) & (xx <= 120) & (yy >= 50) & (yy <= 160))
    # Padded by 15 % of 80×110: 12 px sideways, 16.5 px up/down.
    assert np.array_equal(m, (xx >= 28) & (xx <= 132) & (yy >= 33.5) & (yy <= 176.5))
    assert m.sum() > unpadded.sum()
    # A rotated quad: the mask is convex and symmetric about its centre.
    r = cs.watch_quad(ang=30)
    mr = sig.polygon_mask(r, cs.SW, cs.SH)
    c = r.mean(axis=0)
    assert mr[int(round(c[1])), int(round(c[0]))]
    assert not mr[0, 0] and not mr[-1, -1]


def test_pixels_outside_the_mask_are_ignored():
    """A change far outside the padded polygon (beyond the blur's 16-px reach)
    leaves the signal where it was; the same change inside it does not."""
    q = np.array([[60.0, 70.0], [116.0, 70.0], [116.0, 150.0], [60.0, 150.0]])
    mask = sig.polygon_mask(q, cs.SW, cs.SH)
    rng = np.random.default_rng(3)
    ref = rng.integers(60, 200, (cs.SH, cs.SW)).astype(np.int32)
    base = sig.measure(ref, ref, mask)
    assert base.log_hp_p75 == 0.0
    outside = ref.copy()
    outside[200:246, 0:176] = rng.integers(0, 255, (46, 176))      # ≥ 30 px below the padded polygon
    s = sig.measure(outside, ref, mask)
    assert s.log_hp_p75 == 0.0 and s.log_grad == 0.0
    inside = ref.copy()
    inside[80:140, 70:106] = rng.integers(0, 255, (60, 36))
    s = sig.measure(inside, ref, mask)
    assert s.log_hp_p75 > 8.0 and s.log_grad > 8.0
    # And a mask with no whole block inside it is "nothing to watch".
    tiny = sig.polygon_mask(np.array([[5.0, 5.0], [9.0, 5.0], [9.0, 10.0], [5.0, 10.0]]), cs.SW, cs.SH, pad_frac=0.0)
    assert sig.measure(ref, ref, tiny) is None


def test_blocks_need_three_quarters_inside():
    mask = np.zeros((cs.SH, cs.SW), bool)
    mask[0:8, 0:8] = True                 # whole block
    mask[8:14, 8:16] = True               # 48 of 64 = exactly 75 %
    mask[16:21, 16:24] = True             # 40 of 64
    assert sig.blocks_inside(mask) == [(0, 0), (8, 8)]


def test_measure_rejects_mismatched_shapes():
    mask = _mask()
    a = np.zeros((cs.SH, cs.SW), np.int32)
    with pytest.raises(ValueError):
        sig.measure(a, np.zeros((10, 10), np.int32), mask)
    with pytest.raises(ValueError):
        sig.measure(a, a, mask[:10, :10])
