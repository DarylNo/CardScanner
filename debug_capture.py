"""
Headless diagnostic script — capture, detect card, crop, run vision, Scryfall.

Usage:
    python debug_capture.py [--camera 0] [--model qwen2.5vl:7b]
"""

import argparse
import json
import os
import re
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


PROMPT = """\
You are a Magic: The Gathering card scanner. TRANSCRIBE — do NOT guess.

Three images provided:
  1. Perspective-corrected full card
  2. Title-bar close-up (top of card — large card name text)
  3. Bottom-left collector info close-up (small text: collector number, set code)

TRANSCRIBE exactly what you can read from images 2 and 3.
If a field is genuinely unreadable, return null — do NOT invent a plausible value.

Bottom collector line format:  {number}/{total} ★ {LANG} {SETCODE}
Example: "042/300 ★ EN MOM" → set_code="mom", collector_number="042", language="en"

Return ONLY valid JSON, no markdown, no extra text:
{
  "name": "<exact title text from image 2, or null>",
  "set_code": "<3-4 char lowercase code from image 3, or null>",
  "collector_number": "<digits only from image 3, no slash/total, or null>",
  "foil": <true|false>,
  "language": "<2-3 char code or null>",
  "condition_estimate": "<NM|LP|MP|HP|DMG>",
  "condition_reason": "<one sentence>",
  "confidence": "<high|medium|low>",
  "unreadable_fields": ["fields you could not clearly read"]
}"""


def capture_frame(camera_index: int, width: int = 1920, height: int = 1080) -> np.ndarray:
    cap = cv2.VideoCapture(camera_index)
    if not cap.isOpened():
        raise RuntimeError(f"Cannot open camera {camera_index}")
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
    actual_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    actual_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    print(f"  Camera: {actual_w}x{actual_h}")
    for _ in range(10):   # let auto-exposure settle
        cap.read()
    ret, frame = cap.read()
    cap.release()
    if not ret or frame is None:
        raise RuntimeError("Failed to capture frame")
    return frame


def run_vision(endpoint: str, model: str,
               card: np.ndarray, crops: dict[str, np.ndarray]) -> tuple[str, dict]:
    import base64
    from openai import OpenAI
    client = OpenAI(base_url=endpoint, api_key="ollama")

    def enc(img):
        _, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 95])
        return "data:image/jpeg;base64," + base64.b64encode(buf.tobytes()).decode()

    content = [
        {"type": "image_url", "image_url": {"url": enc(card)}},
        {"type": "image_url", "image_url": {"url": enc(crops["title_bar"])}},
        {"type": "image_url", "image_url": {"url": enc(crops["bottom_left_collector"])}},
        {"type": "text", "text": PROMPT},
    ]
    t0 = time.monotonic()
    resp = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": content}],
        temperature=0.0,
        max_tokens=512,
    )
    elapsed = time.monotonic() - t0
    raw = resp.choices[0].message.content or ""
    print(f"  Model responded in {elapsed:.1f}s")

    clean = re.sub(r"^```(?:json)?\s*", "", raw.strip(), flags=re.MULTILINE)
    clean = re.sub(r"\s*```\s*$", "", clean.strip(), flags=re.MULTILINE)
    m = re.search(r"\{.*\}", clean, re.DOTALL)
    parsed = json.loads(m.group()) if m else {}
    return raw, parsed


def scryfall_lookup(parsed: dict) -> dict | None:
    import requests
    s = requests.Session()
    s.headers["User-Agent"] = "MTGCardScannerDebug/1.0"

    set_code = (parsed.get("set_code") or "").strip().lower()
    num      = (parsed.get("collector_number") or "").strip().split("/")[0]
    name     = (parsed.get("name") or "").strip()

    if set_code and num:
        r = s.get(f"https://api.scryfall.com/cards/{set_code}/{num}", timeout=10)
        if r.ok:
            print(f"  Scryfall exact: {set_code}/{num}")
            return r.json()
        print(f"  Scryfall {set_code}/{num} -> {r.status_code} (falling back to name)")

    if name:
        r = s.get("https://api.scryfall.com/cards/named", params={"fuzzy": name}, timeout=10)
        if r.ok:
            print(f"  Scryfall fuzzy '{name}'")
            return r.json()
        print(f"  Scryfall fuzzy '{name}' → {r.status_code}")

    return None


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

    # Annotate the full frame to show where we detected the card
    if detected:
        from mtg_card_scanner.card_detect import find_card_quad
        quad = find_card_quad(frame)
        if quad is not None:
            ann = frame.copy()
            cv2.polylines(ann, [quad.astype(int)], True, (0, 255, 0), 3)
            cv2.imwrite(str(DEBUG_DIR / f"{run_id}_0_full_annotated.jpg"), ann, [cv2.IMWRITE_JPEG_QUALITY, 90])
            print(f"  Saved {run_id}_0_full_annotated.jpg (detection outline)")

    print("\n[3] Building crops from warped card...")
    crops = card_sub_crops(card)
    for name, img in crops.items():
        p = DEBUG_DIR / f"{run_id}_2_{name}.jpg"
        cv2.imwrite(str(p), img, [cv2.IMWRITE_JPEG_QUALITY, 95])
        print(f"  Saved {p.name}  ({img.shape[1]}x{img.shape[0]})")

    print(f"\n[4] Running vision model ({args.model})...")
    raw, parsed = run_vision(args.endpoint, args.model, card, crops)

    print("\n--- RAW MODEL OUTPUT ---")
    print(raw)
    print("\n--- PARSED ---")
    print(json.dumps(parsed, indent=2))

    print("\n[5] Scryfall lookup...")
    sf = scryfall_lookup(parsed)
    if sf:
        prices = sf.get("prices", {})
        sf_name = sf.get("name", "")
        model_name = (parsed.get("name") or "").strip()
        match = model_name.lower() == sf_name.lower()
        print(f"\n  Scryfall: {sf_name} | {sf.get('set_name')} #{sf.get('collector_number')} | {sf.get('rarity')}")
        print(f"  Prices: USD {prices.get('usd')}  Foil {prices.get('usd_foil')}")
        print(f"  Name match: {match}")
        if not match:
            print(f"  *** MISMATCH: model='{model_name}'  scryfall='{sf_name}' ***")
    else:
        print("  No Scryfall result.")

    print(f"\nAll debug images -> {DEBUG_DIR.resolve()}")


if __name__ == "__main__":
    main()
