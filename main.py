#!/usr/bin/env python3
"""MTG Card Scanner — entry point."""

import argparse
import os
import sys
from pathlib import Path
from typing import Optional

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
  python main.py                           # live preview, SPACE/S to capture
  python main.py --auto                    # live preview, auto-capture on steady
  python main.py --output results.json     # save to JSON
  python main.py --model qwen2.5-vl:32b   # use a larger model
        """,
    )
    p.add_argument(
        "--demo",
        action="store_true",
        help="Demo mode — Scryfall + output pipeline against a sample card (no camera/model).",
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
        help="Auto-capture when the card image is steady.",
    )
    p.add_argument(
        "--model",
        default=os.getenv("VISION_MODEL", "qwen2.5vl:7b"),
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
    writer   = OutputWriter(args.output)

    # ── Demo mode ─────────────────────────────────────────────────────────────
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
    from mtg_card_scanner.preview import ScannerPreview

    model    = VisionModel(endpoint=args.endpoint, model=args.model)
    pipeline = Pipeline(model=model, scryfall=scryfall, writer=writer)

    print(f"MTG Card Scanner")
    print(f"  Model   : {args.model} @ {args.endpoint}")
    print(f"  Camera  : {args.camera}")
    print(f"  Output  : {args.output}")
    print(f"  Mode    : {'auto-capture (steady)' if args.auto else 'manual (S/SPACE)'}")
    print()

    def scan_callback(frame):
        """Called by the preview window when a capture is triggered."""
        try:
            result = pipeline.run_once(frame)
            # Print listing to console as well as showing it on the overlay
            print(format_listing(result))
            print(f"Appended to {args.output}\n")
            return result
        except Exception as exc:
            print(f"Scan error: {exc}", file=sys.stderr)
            return None

    preview = ScannerPreview(camera_index=args.camera)
    try:
        preview.run(callback=scan_callback, auto_mode=args.auto)
    except KeyboardInterrupt:
        pass
    except RuntimeError as exc:
        print(f"\nError: {exc}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
