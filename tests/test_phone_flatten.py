"""
The Android app flattens the card on the phone before upload
(tests/phone_flatten_ref.py is the executable spec). These tests hold the
server to its side of that contract: it must still find the card in such an
upload, run its own checks, and end up no further from the true card face
than when it warps the raw frame itself.
"""

import cv2
import imagehash
import numpy as np
import pytest
from PIL import Image

from tests import card_scenes as cs
from tests.phone_flatten_ref import (CARD_H, CARD_W, MARGINS, _layout,
                                      choose_margin, phone_flatten)
from mtg_card_scanner.card_detect import (extract_card, find_card_quad,
                                          is_blank_surface, warp_card)

# scene -> (texture seed, border colour) of the card it contains
CARD_OF = {
    "white_tray_black_border": (11, (18, 18, 18)),
    "dark_tray_white_border": (12, (235, 235, 235)),
    "sideways_card": (13, (18, 18, 18)),
    "holder_decoy_beside_card": (14, (18, 18, 18)),
    "small_card_full_frame": (15, (18, 18, 18)),
    "card_near_frame_edge": (16, (18, 18, 18)),
    "glare_gradient_tray": (17, (18, 18, 18)),
    "portrait_frame": (18, (18, 18, 18)),
}
SCENES = cs.scenes()


def _phash(img):
    return imagehash.phash(Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB)))


def _bits_from_truth(img, truth):
    # 180° is geometrically ambiguous (card_detect._orient_corners); the art
    # index retries upside-down, so compare against the better orientation.
    return min(_phash(truth) - _phash(r) for r in (img, cv2.rotate(img, cv2.ROTATE_180)))


def test_scene_answers_match_the_server_detector():
    for name, (img, has_card) in SCENES.items():
        assert (find_card_quad(img) is not None) == has_card, name


@pytest.mark.parametrize("name", sorted(CARD_OF))
def test_server_redetects_and_is_no_worse(name):
    img, _ = SCENES[name]
    q = find_card_quad(img)
    h, w = img.shape[:2]
    m = choose_margin(q, w, h)
    if m is None:
        # The phone uploads the frame as-is here — nothing flattened to check.
        assert name == "card_near_frame_edge"
        return
    up = phone_flatten(img, q, m)
    assert up.shape[:2] == (_layout(m)[3], _layout(m)[2])
    card, detected = extract_card(up)
    assert detected, "server must find the card's edges in the flattened upload"
    assert not is_blank_surface(card, detected)
    seed, border = CARD_OF[name]
    truth = cv2.resize(cs.card_texture(seed, border), (630, 880), interpolation=cv2.INTER_AREA)
    direct = _bits_from_truth(warp_card(img, q), truth)
    via_phone = _bits_from_truth(card, truth)
    assert via_phone <= direct + 2, (name, direct, via_phone)


def test_card_at_frame_edge_is_not_flattened():
    img, _ = SCENES["card_near_frame_edge"]
    h, w = img.shape[:2]
    assert choose_margin(find_card_quad(img), w, h) is None


@pytest.mark.parametrize("m", MARGINS)
def test_every_margin_fits_the_detectors_window(m):
    # 0.015 ≤ card area / frame ≤ 0.92 (card_detect: _MIN_AREA_FRAC, max_area)
    _, _, out_w, out_h = _layout(m)
    assert 0.5 < CARD_W * CARD_H / (out_w * out_h) < 0.92
