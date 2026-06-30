#!/usr/bin/env python3
"""
Capture the live card and save zoomed crops of every distinguishing region:
  - full warped card
  - bottom-left collector strip (set code, collector number, language)
  - bottom-right copyright/year line
  - bottom-center (stamp area for The List / Mystery Booster)
  - top-right set symbol region
"""
import sys, time, cv2, numpy as np

def main():
    cap = cv2.VideoCapture(0)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
    for _ in range(12):
        cap.read()
    ret, frame = cap.read()
    cap.release()
    if not ret:
        sys.exit("ERROR: no frame")

    cv2.imwrite("gt_raw.jpg", frame)

    # Try card detection for a clean warp first
    try:
        from mtg_card_scanner.card_detect import extract_card
        card, detected = extract_card(frame)
        if not detected:
            print("No quad found — using centre crop")
    except Exception as e:
        print(f"card_detect error: {e}")
        h, w = frame.shape[:2]
        card = frame[h//6:5*h//6, w//6:5*w//6]

    cv2.imwrite("gt_card.jpg", card)
    h, w = card.shape[:2]
    print(f"Card size: {w}x{h}")

    # ── Region crops (fractions of warped card) ──────────────────────────────
    def crop(y0f, y1f, x0f, x1f, name, scale=4):
        y0, y1 = int(h*y0f), int(h*y1f)
        x0, x1 = int(w*x0f), int(w*x1f)
        region = card[y0:y1, x0:x1]
        # upscale for readability
        big = cv2.resize(region, (region.shape[1]*scale, region.shape[0]*scale),
                         interpolation=cv2.INTER_LANCZOS4)
        cv2.imwrite(f"gt_{name}.jpg", big)
        print(f"  Saved gt_{name}.jpg  ({x1-x0}x{y1-y0} -> {big.shape[1]}x{big.shape[0]})")

    # Bottom collector strip (set code + number, left side)
    crop(0.88, 0.96, 0.00, 0.60, "collector_left", scale=5)
    # Bottom right (copyright year, right side)
    crop(0.88, 0.96, 0.40, 1.00, "copyright_right", scale=5)
    # Bottom center (stamp area — The List / Mystery Booster stamp lives here)
    crop(0.84, 0.96, 0.30, 0.70, "stamp_center", scale=5)
    # Full bottom strip
    crop(0.85, 0.99, 0.00, 1.00, "bottom_full", scale=4)
    # Set symbol (top-right of card)
    crop(0.05, 0.18, 0.70, 1.00, "set_symbol", scale=5)
    # Title bar
    crop(0.00, 0.08, 0.00, 1.00, "title_bar", scale=4)

    print("\nAll crops saved.")

if __name__ == "__main__":
    main()
