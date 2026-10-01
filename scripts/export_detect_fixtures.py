"""
Export card-detection parity fixtures for the Android app's JVM tests.

For every synthetic scene in tests/card_scenes.py this writes the scene as a
lossless PNG plus the SERVER's answers (mtg_card_scanner.card_detect and the
executable phone spec tests/phone_flatten_ref.py) to
android/core/src/test/resources/detect/. The Kotlin port of find_card_quad
and the on-phone flattening must reproduce them; CI re-runs this script and
fails on any diff, so the fixtures can never silently drift from the server.

    python scripts/export_detect_fixtures.py          # (re)write fixtures
    python scripts/export_detect_fixtures.py --check  # exit 1 if stale
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from mtg_card_scanner.card_detect import (PHOTO_FILL, PHOTO_H,  # noqa: E402
                                          PHOTO_MARGIN_X, PHOTO_MARGIN_Y,
                                          PHOTO_W, _texture_metrics,
                                          extract_card, find_card_quad,
                                          frame_sharpness, is_blank_surface,
                                          scan_photo)
from tests import card_scenes  # noqa: E402
from tests.phone_flatten_ref import (CARD_H, CARD_W, MARGINS, SCALE,  # noqa: E402
                                     choose_margin, phone_flatten)

OUT = ROOT / "android" / "core" / "src" / "test" / "resources" / "detect"
THUMB = 4          # flattened outputs are stored at 1/4 size to keep the repo small


def build() -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    expected = {"margins": list(MARGINS), "scale": SCALE,
                "card_w": CARD_W, "card_h": CARD_H, "thumb": THUMB,
                # The scan photo (card_detect.scan_photo): the card at PHOTO_FILL of
                # a PHOTO_W×PHOTO_H photo, inset by the margins.
                "photo_fill": PHOTO_FILL, "photo_margin": [PHOTO_MARGIN_X, PHOTO_MARGIN_Y],
                "photo_size": [PHOTO_W, PHOTO_H], "scenes": {}}

    def photo_entry(frame, stem):
        """scan_photo([frame]) → {detected, size} + a thumbnail of the photo when detected."""
        photo, detected = scan_photo([frame])
        entry = {"detected": bool(detected), "size": [photo.shape[1], photo.shape[0]]}
        if detected:
            small = cv2.resize(photo, (photo.shape[1] // THUMB, photo.shape[0] // THUMB),
                               interpolation=cv2.INTER_AREA)
            ok, png = cv2.imencode(".png", small)
            assert ok
            files[f"{stem}.photo.png"] = png.tobytes()
        return entry

    all_scenes = card_scenes.scenes()
    # pick_sharpest over every scene (the multi-frame fallback picks the photo's frame).
    expected["sharpest_scene"] = max(all_scenes, key=lambda n: frame_sharpness(all_scenes[n][0]))
    for name, (img, has_card) in all_scenes.items():
        ok, png = cv2.imencode(".png", img)
        assert ok
        files[f"{name}.png"] = png.tobytes()
        q = find_card_quad(img)
        h, w = img.shape[:2]
        entry = {"has_card": has_card, "width": w, "height": h,
                 "quad": None if q is None else [[round(float(x), 3), round(float(y), 3)]
                                                  for x, y in q],
                 "margin": None, "sharpness": round(frame_sharpness(img), 3)}
        # The pipeline's blank-surface guard (Stage 2 on-phone identifier):
        # extract_card (quad warp, else centre crop) + is_blank_surface.
        card, detected = extract_card(img)
        std, edge = _texture_metrics(cv2.cvtColor(card, cv2.COLOR_BGR2GRAY))
        entry["extract_detected"] = bool(detected)
        entry["blank"] = bool(is_blank_surface(card, detected=detected))
        entry["blank_metrics"] = [round(std, 6), round(edge, 6)]
        # The stored scan photo, from the frame as uploaded …
        entry["photo"] = photo_entry(img, name)
        if q is not None:
            m = choose_margin(q, w, h)
            entry["margin"] = m
            if m is not None:
                flat = phone_flatten(img, q, m)
                small = cv2.resize(flat, (flat.shape[1] // THUMB, flat.shape[0] // THUMB),
                                   interpolation=cv2.INTER_AREA)
                ok, png = cv2.imencode(".png", small)
                assert ok
                files[f"{name}.flat.png"] = png.tobytes()
                entry["flat_size"] = [flat.shape[1], flat.shape[0]]
                # … and from the phone's flattened upload (what the phone files).
                entry["flat_photo"] = photo_entry(flat, f"{name}.flat")
        expected["scenes"][name] = entry
    files["expected.json"] = (json.dumps(expected, indent=1, sort_keys=True) + "\n").encode()
    return files


def main() -> int:
    files = build()
    if "--check" in sys.argv:
        stale = [n for n, b in files.items()
                 if not (OUT / n).exists() or (OUT / n).read_bytes() != b]
        extra = [p.name for p in OUT.glob("*") if p.name not in files] if OUT.exists() else []
        if stale or extra:
            print("detect fixtures are stale — run: python scripts/export_detect_fixtures.py")
            for n in stale + extra:
                print("  ", n)
            return 1
        print(f"detect fixtures up to date ({len(files)} files)")
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
