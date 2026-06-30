#!/usr/bin/env python3
"""
Verification script for multi-frame consensus + visual-match pipeline.

Captures a burst of 3 frames from the camera, saves the sharpest one to disk
so the tester can confirm ground truth, runs the full Pipeline.run_once(frames)
path, and prints every pHash candidate with distances.
"""
import sys
import time
import cv2
import numpy as np

BURST_N = 3
BURST_INTERVAL_S = 0.10  # 100 ms between frames
SHARPEST_PATH = "verify_sharpest.jpg"
FRAME_PREFIX = "verify_frame"


def _safe(s):
    return str(s).encode("ascii", errors="replace").decode("ascii")


SHARPNESS_MIN = 50.0   # Laplacian variance below this = no card / blurry scene
WAIT_TIMEOUT_S = 60    # give up after this many seconds


def _laplacian(frame):
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def capture_burst(n=BURST_N):
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        sys.exit("ERROR: Cannot open camera 0")
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
    print("Warming up camera (10 frames)...")
    for _ in range(10):
        cap.read()

    print(f"Waiting for a card (sharpness > {SHARPNESS_MIN})...")
    deadline = time.monotonic() + WAIT_TIMEOUT_S
    while time.monotonic() < deadline:
        ret, f = cap.read()
        if ret and f is not None:
            s = _laplacian(f)
            if s >= SHARPNESS_MIN:
                print(f"  Card detected (sharpness={s:.1f}) — capturing burst...")
                break
            print(f"  sharpness={s:.1f} — waiting...", end="\r")
        time.sleep(0.25)
    else:
        cap.release()
        sys.exit("TIMEOUT: No card detected within 60s — place a card and retry")

    frames = [f]
    for i in range(1, n):
        time.sleep(BURST_INTERVAL_S)
        ret, fr = cap.read()
        if ret and fr is not None:
            frames.append(fr)
            print(f"  Captured burst frame {i+1}/{n}")
    cap.release()
    if not frames:
        sys.exit("ERROR: No frames captured")
    return frames


def main():
    # ── 1. Burst capture ──────────────────────────────────────────────────────
    print("\n=== STEP 1: Burst capture ===")
    frames = capture_burst(BURST_N)
    print(f"  Captured {len(frames)} frames")

    # ── 2. Pick sharpest + save for ground-truth ──────────────────────────────
    print("\n=== STEP 2: Sharpest frame selection ===")
    from mtg_card_scanner.consensus import frame_sharpness, pick_sharpest

    sharpnesses = [frame_sharpness(f) for f in frames]
    for i, s in enumerate(sharpnesses):
        print(f"  Frame {i+1} Laplacian variance: {s:.1f}")

    sharpest = pick_sharpest(frames)
    cv2.imwrite(SHARPEST_PATH, sharpest)
    print(f"  Sharpest frame saved to: {SHARPEST_PATH}  (open this to confirm ground truth)")

    # ── 3. Consensus read ─────────────────────────────────────────────────────
    print("\n=== STEP 3: Multi-frame consensus read ===")
    from mtg_card_scanner.vision import VisionModel
    from mtg_card_scanner.consensus import consensus_read

    model = VisionModel()
    cr = consensus_read(frames, model)
    card_read = cr.card_read

    print(f"  Individual reads:")
    for i, r in enumerate(cr.reads):
        print(f"    Frame {i+1}: name={r.name!r}  set={r.set_code!r}  "
              f"collector={r.collector_number!r}  artist={r.artist!r}  "
              f"is_old={r.is_old_card}")
    print(f"  CONSENSUS: name={card_read.name!r}  set={card_read.set_code!r}  "
          f"collector={card_read.collector_number!r}  confidence={cr.name_confidence}")

    # ── 4. Get all Scryfall printings ─────────────────────────────────────────
    print("\n=== STEP 4: Scryfall all-printings fetch ===")
    from mtg_card_scanner.scryfall import ScryfallClient, ScryfallError

    scryfall = ScryfallClient()

    if not card_read.name:
        sys.exit("ERROR: Consensus produced no card name — point camera at a card and retry")

    try:
        printings = scryfall.get_all_printings(card_read.name)
        print(f"  {len(printings)} printings with images for '{_safe(card_read.name)}'")
    except ScryfallError as e:
        print(f"  get_all_printings failed: {e}")
        printings = []

    # ── 5. Visual match ───────────────────────────────────────────────────────
    print("\n=== STEP 5: pHash visual match ===")
    if not printings:
        print("  Skipping visual match — no printings fetched (will test via pipeline fallback)")
        best, ranked, near_tie = None, [], False
        elapsed = 0.0
    else:
        from mtg_card_scanner.visual_match import ArtMatcher
        matcher = ArtMatcher()
        t0 = time.monotonic()
        best, ranked, near_tie = matcher.best_match(sharpest, printings)
        elapsed = time.monotonic() - t0
    print(f"  Compared {len(ranked)} printings in {elapsed:.1f}s")
    print(f"  Near-tie: {near_tie}")
    print()
    print(f"  {'Rank':<5} {'Set':<7} {'#':<6} {'Dist':>5}  {'Frame':<6}  Artist")
    print(f"  {'-'*5} {'-'*7} {'-'*6} {'-'*5}  {'-'*6}  {'-'*20}")
    for i, p in enumerate(ranked[:15]):
        dist = p.get('phash_distance', '?')
        marker = " <-- BEST" if i == 0 else (" <-- #2 (NEAR-TIE)" if i == 1 and near_tie else "")
        print(f"  [{i+1:<3}] {_safe(p.get('set','').upper()):<7} "
              f"#{_safe(p.get('collector_number','')):>5}  {dist:>4}  "
              f"{_safe(p.get('frame','')):>6}  "
              f"{_safe(p.get('artist',''))}{marker}")

    # ── 6. Full pipeline run ──────────────────────────────────────────────────
    print("\n=== STEP 6: Full Pipeline.run_once(frames) ===")
    from mtg_card_scanner.pipeline import Pipeline
    from mtg_card_scanner.output import format_listing

    pipeline = Pipeline(model=model, scryfall=scryfall)
    result = pipeline.run_once(frames)

    print(format_listing(result))
    print(f"\n  match_method  : {result.match_method}")
    print(f"  phash_distance: {result.phash_distance}")
    print(f"  name_confidence (via consensus): {cr.name_confidence}")

    # ── 7. Summary ────────────────────────────────────────────────────────────
    print("\n=== SUMMARY ===")
    if best:
        print(f"  pHash winner : {_safe(best.get('set','').upper())} "
              f"#{_safe(best.get('collector_number',''))}  "
              f"dist={best.get('phash_distance')}  "
              f"artist={_safe(best.get('artist',''))}")
    print(f"  Pipeline picked: {_safe(result.scryfall_name)} "
          f"({_safe(result.scryfall_set_name)} #{_safe(result.collector_number)})")
    print(f"  Price: ${result.price_usd}")
    if near_tie and ranked:
        gap = ranked[1]['phash_distance'] - ranked[0]['phash_distance'] if len(ranked) >= 2 else 0
        print(f"  WARNING: Near-tie (gap={gap}) — low visual confidence")


if __name__ == "__main__":
    main()
