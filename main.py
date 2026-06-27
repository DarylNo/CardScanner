#!/usr/bin/env python3
"""MTG Card Scanner — entry point."""

import argparse
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()


def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="mtg-scanner",
        description="Live MTG card scanner: local vision LLM + Scryfall lookup.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python main.py --demo                    # test pipeline without camera/model
  python main.py                           # ENTER to capture each card
  python main.py --auto                    # auto-capture when card is steady
  python main.py --output results.json     # save to JSON instead of CSV
  python main.py --model qwen2.5-vl:32b   # use a larger model
        """,
    )
    p.add_argument(
        "--demo",
        action="store_true",
        help="Demo mode — runs Scryfall + output pipeline against a sample card read (no camera or model needed).",
    )
    p.add_argument(
        "--camera",
        type=int,
        default=int(os.getenv("CAMERA_INDEX", "0")),
        metavar="N",
        help="Webcam device index (default: %(default)s).",
    )
    p.add_argument(
        "--auto",
        action="store_true",
        help="Auto-capture when the card image is steady (no keypress needed).",
    )
    p.add_argument(
        "--once",
        action="store_true",
        help="Scan exactly one card then exit.",
    )
    p.add_argument(
        "--model",
        default=os.getenv("VISION_MODEL", "qwen2.5-vl:7b"),
        help="Ollama model name (default: %(default)s).",
    )
    p.add_argument(
        "--endpoint",
        default=os.getenv("OLLAMA_ENDPOINT", "http://localhost:11434/v1"),
        help="Ollama API endpoint (default: %(default)s).",
    )
    p.add_argument(
        "--output",
        type=Path,
        default=Path(os.getenv("OUTPUT_FILE", "scan_results.csv")),
        metavar="FILE",
        help="Output file — .csv or .json (default: %(default)s).",
    )
    return p


def main() -> None:
    args = _build_parser().parse_args()

    from mtg_card_scanner.scryfall import ScryfallClient
    from mtg_card_scanner.output import OutputWriter, format_listing
    from mtg_card_scanner.pipeline import Pipeline

    scryfall = ScryfallClient()
    writer = OutputWriter(args.output)

    # ── Demo mode ────────────────────────────────────────────────────────────
    if args.demo:
        pipeline = Pipeline(model=None, scryfall=scryfall, writer=writer)
        try:
            result = pipeline.run_demo()
        except Exception as exc:
            print(f"Demo failed: {exc}", file=sys.stderr)
            sys.exit(1)
        print(format_listing(result))
        print(f"\nAppended to {args.output}")
        return

    # ── Live mode ─────────────────────────────────────────────────────────────
    from mtg_card_scanner.vision import VisionModel
    from mtg_card_scanner.capture import capture_frame, wait_for_steady_frame

    model = VisionModel(endpoint=args.endpoint, model=args.model)
    pipeline = Pipeline(model=model, scryfall=scryfall, writer=writer)

    print(f"MTG Card Scanner")
    print(f"  Model   : {args.model} @ {args.endpoint}")
    print(f"  Camera  : {args.camera}")
    print(f"  Output  : {args.output}")
    if args.auto:
        print("  Mode    : auto-capture (hold card steady)")
    else:
        print("  Mode    : manual (press ENTER to capture)")
    print()

    while True:
        try:
            if args.auto:
                frame = wait_for_steady_frame(camera_index=args.camera)
            else:
                try:
                    input("Press ENTER to capture card (Ctrl-C to quit)… ")
                except EOFError:
                    break
                frame = capture_frame(camera_index=args.camera)

            result = pipeline.run_once(frame)
            print(format_listing(result))
            print(f"Appended to {args.output}\n")

            if args.once:
                break

        except KeyboardInterrupt:
            print("\nExiting.")
            break
        except Exception as exc:
            print(f"\nError: {exc}", file=sys.stderr)
            print("Press ENTER to try again, or Ctrl-C to quit.\n")


if __name__ == "__main__":
    main()
