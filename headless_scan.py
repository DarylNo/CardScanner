#!/usr/bin/env python3
"""
Headless burst scan — mirrors the exact path of a spacebar trigger in preview.py.

  1. Open camera, flush until bright frame received.
  2. Capture 3-frame burst (80 ms apart) — same as _BURST_FRAMES / _BURST_INTERVAL_MS.
  3. Run consensus_read on all 3 frames (per-frame reads printed).
  4. Run full pipeline (visual match + tiebreakers + Scryfall + output).
  5. Save frame-0 as headless_scan_frame.jpg for visual verification.
  6. Print complete status block.
"""

import sys
import time

import cv2
import numpy as np

from mtg_card_scanner.vision import VisionModel
from mtg_card_scanner.scryfall import ScryfallClient
from mtg_card_scanner.pipeline import Pipeline
from mtg_card_scanner.output import format_listing
from mtg_card_scanner.consensus import frame_sharpness

_BURST_FRAMES      = 3
_BURST_INTERVAL_MS = 80
_CAMERA_INDEX      = 0
_FLUSH_FRAMES      = 80        # read this many frames before taking the burst
_BRIGHTNESS_MIN    = 12.0      # abort if every flush frame is below this

# ── camera warm-up ────────────────────────────────────────────────────────────
print("Opening camera...")
cap = cv2.VideoCapture(_CAMERA_INDEX)
cap.set(cv2.CAP_PROP_FRAME_WIDTH,  1280)
cap.set(cv2.CAP_PROP_FRAME_HEIGHT,  720)
cap.set(cv2.CAP_PROP_AUTOFOCUS, 1)

best_frame = None
best_mean  = 0.0

for i in range(_FLUSH_FRAMES):
    ok, f = cap.read()
    if not ok or f is None:
        time.sleep(0.05)
        continue
    m = float(f.mean())
    if m > best_mean:
        best_mean  = m
        best_frame = f
    if m >= _BRIGHTNESS_MIN:
        print(f"  Camera ready after {i+1} flush frames  (mean={m:.1f})")
        break
    time.sleep(0.03)

if best_frame is None or best_mean < _BRIGHTNESS_MIN:
    cap.release()
    print(f"ERROR: camera returned only dark frames (best mean={best_mean:.1f}).")
    sys.exit(1)

# ── burst capture ─────────────────────────────────────────────────────────────
print(f"Capturing {_BURST_FRAMES}-frame burst ({_BURST_INTERVAL_MS} ms apart)...")
frames = [best_frame]
for b in range(_BURST_FRAMES - 1):
    time.sleep(_BURST_INTERVAL_MS / 1000.0)
    ok, f = cap.read()
    if ok and f is not None:
        frames.append(f)
        print(f"  Burst frame {b+2}: mean={f.mean():.1f}  "
              f"sharpness={frame_sharpness(f):.0f}")

cap.release()

if len(frames) < _BURST_FRAMES:
    print(f"WARNING: only got {len(frames)} of {_BURST_FRAMES} burst frames")

# Save frame 0 for visual inspection
cv2.imwrite("headless_scan_frame.jpg", frames[0])
print(f"Frame-0 saved as headless_scan_frame.jpg "
      f"(sharpness={frame_sharpness(frames[0]):.0f})")
print(f"All sharpness scores: {[round(frame_sharpness(f)) for f in frames]}")

# ── pipeline ──────────────────────────────────────────────────────────────────
print("\nInitialising model + Scryfall client...")
model    = VisionModel()
scryfall = ScryfallClient()
pipeline = Pipeline(model=model, scryfall=scryfall)

result = pipeline.run_once(frames)

# ── final listing ─────────────────────────────────────────────────────────────
print("\n" + "=" * 60)
print("  FINAL RESULT")
print("=" * 60)
print(format_listing(result))
print(f"  printing_uncertain  = {result.printing_uncertain}")
print(f"  printing_candidates = {result.printing_candidates!r}")
print(f"  match_method        = {result.match_method!r}")
print(f"  phash_distance      = {result.phash_distance}")
print(f"  name_confidence     = {result.name_confidence!r}")
