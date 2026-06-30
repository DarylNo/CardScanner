#!/usr/bin/env python3
"""
Quick pHash distance test: capture a frame, read the card name, fetch all
printings from Scryfall, and report pHash distances for every printing.
"""
import sys
import cv2

def main():
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        print("ERROR: Cannot open camera 0", file=sys.stderr)
        sys.exit(1)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)

    print("Warming up camera (10 frames)...")
    for _ in range(10):
        cap.read()

    ret, frame = cap.read()
    cap.release()
    if not ret or frame is None:
        print("ERROR: Failed to capture frame", file=sys.stderr)
        sys.exit(1)

    print("Frame captured. Running vision model...")
    from mtg_card_scanner.vision import VisionModel
    model = VisionModel()
    card_read = model.read_card(frame)
    print(f"  Read: name={card_read.name!r}  set={card_read.set_code!r}  "
          f"artist={card_read.artist!r}  is_old={card_read.is_old_card}")

    if not card_read.name:
        print("ERROR: No card name read — point camera at a card and retry")
        sys.exit(1)

    print(f"\nFetching all Scryfall printings of {card_read.name!r}...")
    from mtg_card_scanner.scryfall import ScryfallClient
    scryfall = ScryfallClient()
    printings = scryfall.get_all_printings(card_read.name)
    print(f"  {len(printings)} printings with images found\n")

    if not printings:
        print("No printings with images — cannot run pHash comparison")
        sys.exit(1)

    from mtg_card_scanner.visual_match import ArtMatcher
    matcher = ArtMatcher()
    best, ranked, near_tie = matcher.best_match(frame, printings)

    def _safe(s):
        return str(s).encode("ascii", errors="replace").decode("ascii")

    print("pHash distances (lowest = best match):")
    for i, p in enumerate(ranked[:10]):
        marker = " <-- BEST" if i == 0 else ("  <-- #2 (NEAR-TIE)" if i == 1 and near_tie else "")
        print(f"  [{i+1}] {_safe(p.get('set','').upper()):6s} #{_safe(p.get('collector_number','')):>4s}  "
              f"dist={p.get('phash_distance'):3d}  "
              f"frame={_safe(p.get('frame',''))}  "
              f"artist={_safe(p.get('artist',''))!r}{marker}")

    if near_tie:
        print(f"\nWARNING: Near-tie detected (gap <= 8 between #1 and #2)")

    if best:
        print(f"\nSelected: {_safe(best.get('name',''))} ({_safe(best.get('set','').upper())} "
              f"#{_safe(best.get('collector_number',''))}) @ ${best.get('prices',{}).get('usd','?')}")

if __name__ == "__main__":
    main()
