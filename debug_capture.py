"""
Headless diagnostic script — capture, detect card, crop, run vision, Scryfall.

Usage:
    python debug_capture.py [--camera 0] [--model qwen2.5vl:7b]
"""

import argparse
import os
import sys
import time
from pathlib import Path

import cv2
import numpy as np
from dotenv import load_dotenv

load_dotenv()

DEBUG_DIR = Path("debug")
DEBUG_DIR.mkdir(exist_ok=True)

sys.path.insert(0, str(Path(__file__).parent))
from mtg_card_scanner.card_detect import extract_card, card_sub_crops, find_card_quad
from mtg_card_scanner.vision import VisionModel
from mtg_card_scanner.scryfall import ScryfallClient, ScryfallError
from mtg_card_scanner.pipeline import _artist_matches


def capture_frame(camera_index: int, width: int = 1920, height: int = 1080) -> np.ndarray:
    cap = cv2.VideoCapture(camera_index)
    if not cap.isOpened():
        raise RuntimeError(f"Cannot open camera {camera_index}")
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
    actual_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    actual_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    print(f"  Camera: {actual_w}x{actual_h}")
    for _ in range(10):
        cap.read()
    ret, frame = cap.read()
    cap.release()
    if not ret or frame is None:
        raise RuntimeError("Failed to capture frame")
    return frame


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--camera",   type=int, default=int(os.getenv("CAMERA_INDEX", "0")))
    ap.add_argument("--model",    default=os.getenv("VISION_MODEL", "qwen2.5vl:7b"))
    ap.add_argument("--endpoint", default=os.getenv("OLLAMA_ENDPOINT", "http://localhost:11434/v1"))
    args = ap.parse_args()

    run_id = time.strftime("%H%M%S")
    print(f"\n=== Debug capture {run_id} ===")
    print(f"  Model: {args.model} @ {args.endpoint}\n")

    print("[1] Capturing frame...")
    frame = capture_frame(args.camera)
    cv2.imwrite(str(DEBUG_DIR / f"{run_id}_0_full_frame.jpg"), frame, [cv2.IMWRITE_JPEG_QUALITY, 95])
    print(f"  Saved {run_id}_0_full_frame.jpg")

    print("\n[2] Detecting card...")
    card, detected = extract_card(frame)
    method = "perspective-warp" if detected else "FALLBACK centre-crop"
    print(f"  Card detection: {method}")
    cv2.imwrite(str(DEBUG_DIR / f"{run_id}_1_card_warped.jpg"), card, [cv2.IMWRITE_JPEG_QUALITY, 95])
    print(f"  Saved {run_id}_1_card_warped.jpg ({card.shape[1]}x{card.shape[0]})")

    if detected:
        quad = find_card_quad(frame)
        if quad is not None:
            ann = frame.copy()
            cv2.polylines(ann, [quad.astype(int)], True, (0, 255, 0), 3)
            cv2.imwrite(str(DEBUG_DIR / f"{run_id}_0_full_annotated.jpg"), ann, [cv2.IMWRITE_JPEG_QUALITY, 90])
            print(f"  Saved {run_id}_0_full_annotated.jpg (detection outline)")

    print("\n[3] Building crops from warped card...")
    crops = card_sub_crops(card)
    for crop_name, img in crops.items():
        p = DEBUG_DIR / f"{run_id}_2_{crop_name}.jpg"
        cv2.imwrite(str(p), img, [cv2.IMWRITE_JPEG_QUALITY, 95])
        print(f"  Saved {p.name}  ({img.shape[1]}x{img.shape[0]})")

    print(f"\n[4] Running vision model ({args.model})...")
    t0 = time.monotonic()
    model = VisionModel(endpoint=args.endpoint, model=args.model)
    read = model.read_card(frame)
    elapsed = time.monotonic() - t0
    print(f"  Model responded in {elapsed:.1f}s")
    print(f"  RAW OUTPUT: {read.raw_response[:200]!r}")
    print(f"  name={read.name!r}  set={read.set_code!r}  num={read.collector_number!r}")
    print(f"  foil={read.foil}  lang={read.language}  condition={read.condition_estimate}")
    print(f"  artist={read.artist!r}  is_old_card={read.is_old_card}")

    print("\n[5] Scryfall lookup...")
    sf = ScryfallClient()
    try:
        if read.is_old_card:
            print(f"  Routing to lookup_old_card (artist={read.artist!r})")
            card_data = sf.lookup_old_card(read.name, read.artist)
        else:
            card_data = sf.lookup(read.set_code, read.collector_number, read.name)
            # Artist-mismatch fallback: if Scryfall returned a different set AND wrong artist
            if (read.artist
                    and card_data.get("set", "").lower() != read.set_code.lower()
                    and not _artist_matches(read.artist, card_data.get("artist", ""))):
                print(f"  Set/artist mismatch — retrying as old-frame card")
                try:
                    card_data = sf.lookup_old_card(read.name, read.artist)
                except ScryfallError as exc:
                    print(f"  Old-frame fallback failed: {exc}")

        prices = card_data.get("prices", {})
        sf_name = card_data.get("name", "")
        print(f"\n  Result: {sf_name}")
        print(f"  Set: {card_data.get('set_name')} #{card_data.get('collector_number')}")
        print(f"  Frame: {card_data.get('frame')}  Artist: {card_data.get('artist')}")
        print(f"  Rarity: {card_data.get('rarity')}")
        print(f"  Prices: USD {prices.get('usd')}  Foil {prices.get('usd_foil')}")

        model_name = read.name.strip().lower()
        if model_name and model_name != sf_name.lower():
            print(f"  *** NAME MISMATCH: model={read.name!r}  scryfall={sf_name!r} ***")

    except ScryfallError as e:
        print(f"  Scryfall error: {e}")

    print(f"\nAll debug images -> {DEBUG_DIR.resolve()}")


if __name__ == "__main__":
    main()
