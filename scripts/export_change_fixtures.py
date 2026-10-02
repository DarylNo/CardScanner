"""
Export texture-change parity fixtures for the Android app's JVM tests.

The phone's `core/TextureChange.kt` is a port of the reference
mtg_card_scanner/change_signal.py (the shadow-proof "did the card change"
signal). This writes a handful of synthetic detection samples
(tests/change_scenes.py — 176×MH grey PNGs, ref + cur per scenario, on a
light and a dark tray) plus the REFERENCE's answers on them to
android/core/src/test/resources/change/expected.json: the watch polygon,
the padded mask's pixel count and index sum, the block count, every block's
value, logHP p75 and logGrad, the Gaussian kernel, and numpy.percentile on a
few arrays. TextureChangeParityTest must reproduce them (≤ 1e-6; the maths
is the same double arithmetic in the same order, so it lands ~1e-12). CI
re-runs this with --check and fails on any diff, so the fixtures can never
drift from the reference.

    python scripts/export_change_fixtures.py          # (re)write fixtures
    python scripts/export_change_fixtures.py --check  # exit 1 if stale
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from mtg_card_scanner import change_signal as sig  # noqa: E402
from tests import change_scenes as cs  # noqa: E402

OUT = ROOT / "android" / "core" / "src" / "test" / "resources" / "change"

# (tray, scenario name, which frame of the scenario) — one cur per scenario;
# refs are shared per (tray, ref frame). Enough to cover every stage on a
# light and a dark tray without swelling the repo.
PICK = [
    ("light", "static card", 0),
    ("light", "soft shadow edge moving f0.7", 4),
    ("light", "uniform shadow f0.5", 0),
    ("light", "hand-shadow blob passing", 4),
    ("light", "exposure x0.9", 0),
    ("light", "glare sheen sigma25", 0),
    ("light", "swap A->B", 0),
    ("light", "same card shifted 3px", 0),
    ("light", "lift (tray shows)", 0),
    ("light", "card placed in empty area", 0),
    ("dark", "soft shadow edge moving f0.7", 4),
    ("dark", "hand-shadow blob passing", 4),
    ("dark", "swap A->B", 0),
    ("dark", "lift (tray shows)", 0),
]

# Polygons: card A's outline (what CardOutline would find), one shifted and
# rotated (so the parity covers a slanted mask), and the whole-sample box.
POLYGONS = {
    "card_A": cs.watch_quad(),
    "card_A_tilted": cs.watch_quad(dx=6, dy=-4, ang=14),
}

PERCENTILE_CASES = [
    [3.0], [1.0, 2.0], [5.0, 1.0, 3.0], [4.0, 1.0, 3.0, 2.0], [0.5, 9.25, 3.0, 3.0, 7.75],
    [float(v) for v in np.linspace(0, 1, 7)], [float(v) for v in np.random.default_rng(1).normal(5, 2, 23)],
    [float(v) for v in np.random.default_rng(2).uniform(0, 50, 660)],
]


def _png(gray: np.ndarray) -> bytes:
    ok, png = cv2.imencode(".png", gray.astype(np.uint8))
    assert ok
    return png.tobytes()


def _slug(s: str) -> str:
    return "".join(c if c.isalnum() else "_" for c in s).strip("_").lower()


def build() -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    expected = {
        "sigma": sig.SIGMA, "log_offset": sig.LOG_OFFSET, "block": sig.BLOCK,
        "block_inside": sig.BLOCK_INSIDE, "pad_frac": sig.PAD_FRAC, "percentile": sig.PERCENTILE,
        "scale": sig.SCALE, "start_threshold": sig.START_THRESHOLD,
        "sample_w": cs.SW, "sample_h": cs.SH,
        # cv2.getGaussianKernel(round(8σ+1)|1, σ) — the taps the blur uses.
        "gaussian_kernel": [float(v) for v in cv2.getGaussianKernel(int(round(sig.SIGMA * 8 + 1)) | 1, sig.SIGMA)[:, 0]],
        "percentile_cases": [{"values": v, "p75": float(np.percentile(v, sig.PERCENTILE))} for v in PERCENTILE_CASES],
        "polygons": {k: [[float(x), float(y)] for x, y in v] for k, v in POLYGONS.items()},
        "cases": [],
    }
    masks = {k: sig.polygon_mask(q, cs.SW, cs.SH) for k, q in POLYGONS.items()}
    for k, m in masks.items():
        expected["polygons"][k] = {
            "quad": expected["polygons"][k],
            "padded": [[float(x), float(y)] for x, y in sig.pad_quad(POLYGONS[k])],
            "mask_pixels": int(m.sum()),
            "mask_index_sum": int(np.flatnonzero(m.ravel()).sum()),
            "blocks": len(sig.blocks_inside(m)),
        }
    seed = 7000
    rendered: dict[str, bytes] = {}

    def frame_file(tray: str, tag: str, fr: cs.Frame, s: int) -> tuple[str, np.ndarray]:
        name = f"{tray}_{tag}.png"
        g = cs.render(fr, s)
        if name not in rendered:
            rendered[name] = _png(g)
            files[name] = rendered[name]
        else:
            assert rendered[name] == _png(g), f"{name} rendered differently twice"
        return name, g

    for tray, sc_name, idx in PICK:
        sc = next(s for s in cs.scenarios(tray) if s.name == sc_name)
        ref_tag = "ref_empty" if not sc.ref.cards else "ref_card"
        ref_file, ref = frame_file(tray, ref_tag, sc.ref, 7000 + (0 if tray == "light" else 1) * 10 + (1 if ref_tag == "ref_empty" else 0))
        seed += 1
        cur_file, cur = frame_file(tray, _slug(sc_name), sc.frames[idx], seed)
        for poly_name, mask in masks.items():
            r = sig.measure(cur, ref, mask)
            vals = sig.block_values(cur, ref, mask, sig.blocks_inside(mask))
            case = {
                "tray": tray, "name": sc_name, "cls": sc.cls, "polygon": poly_name,
                "ref": ref_file, "cur": cur_file,
                "blocks": r.blocks, "mask_pixels": r.mask_pixels,
                # every block's value only on one pair per tray (660 doubles each;
                # the others carry the sum, which still catches any block going wrong)
                "block_sum": float(np.sum(vals)),
                "log_hp_p75": r.log_hp_p75, "log_grad": r.log_grad,
            }
            if sc_name == "swap A->B":
                case["block_values"] = vals
            expected["cases"].append(case)
    files["expected.json"] = (json.dumps(expected, indent=1, sort_keys=True) + "\n").encode()
    return files


def main() -> int:
    files = build()
    if "--check" in sys.argv:
        stale = [n for n, b in files.items()
                 if not (OUT / n).exists() or (OUT / n).read_bytes() != b]
        extra = [p.name for p in OUT.glob("*") if p.name not in files] if OUT.exists() else []
        if stale or extra:
            print("change fixtures are stale — run: python scripts/export_change_fixtures.py")
            for n in stale + extra:
                print("  ", n)
            return 1
        print(f"change fixtures up to date ({len(files)} files)")
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    for p in OUT.glob("*"):
        if p.name not in files:
            p.unlink()
    for n, b in files.items():
        (OUT / n).write_bytes(b)
    print(f"wrote {len(files)} files to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
