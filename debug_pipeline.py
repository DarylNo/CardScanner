"""End-to-end pipeline test using the real project modules."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))

import cv2
from mtg_card_scanner.vision import VisionModel
from mtg_card_scanner.scryfall import ScryfallClient
from mtg_card_scanner.output import build_result, format_listing
from mtg_card_scanner.pipeline import Pipeline
from mtg_card_scanner.card_detect import extract_card

import os
from dotenv import load_dotenv
load_dotenv()

CAMERA  = int(os.getenv("CAMERA_INDEX", "0"))
MODEL   = os.getenv("VISION_MODEL", "qwen2.5vl:7b")
ENDPOINT = os.getenv("OLLAMA_ENDPOINT", "http://localhost:11434/v1")

print(f"=== Pipeline test ===")
print(f"  Model: {MODEL} @ {ENDPOINT}\n")

# Capture
cap = cv2.VideoCapture(CAMERA)
cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1920)
cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 1080)
for _ in range(10):
    cap.read()
ret, frame = cap.read()
cap.release()
print(f"[1] Captured frame: {frame.shape[1]}x{frame.shape[0]}")

# Detect card
card, detected = extract_card(frame)
print(f"[2] Card detect: {'warp' if detected else 'fallback'}")

# Vision
model = VisionModel(endpoint=ENDPOINT, model=MODEL)
print("[3] Running vision model...")
read = model.read_card(frame)
print(f"    name={read.name!r}  set={read.set_code}  num={read.collector_number}  foil={read.foil}  lang={read.language}")
print(f"    condition={read.condition_estimate}  confidence from raw: see below")
print(f"    raw: {read.raw_response[:120]!r}")

# Scryfall
sf = ScryfallClient()
print("[4] Scryfall lookup...")
card_data = sf.lookup(read.set_code, read.collector_number, read.name)

# Result
result = build_result(read, card_data)
print("\n" + format_listing(result))
