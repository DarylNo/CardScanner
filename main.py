#!/usr/bin/env python3
"""MTG Card Scanner — CLI entry point (demo/diagnostics only).

Scanning happens through the web app: run ``./run_server.sh`` and open
``https://<this-machine-ip>:8443/phone`` on the phone (camera) and
``https://<this-machine-ip>:8443/`` on the desktop (control UI).
"""

import argparse
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()


def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="mtg-scanner",
        description="MTG card scanner: art-hash identification + Scryfall lookup.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python main.py --demo                    # test Scryfall + output pipeline
  ./run_server.sh                          # launch the web app (the real scanner)
  python -m mtg_card_scanner.art_index build   # one-time art-index build
        """,
    )
    p.add_argument(
        "--demo",
        action="store_true",
        required=True,
        help="Demo mode — Scryfall + output pipeline against a sample card.",
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

    pipeline = Pipeline(index=None, scryfall=ScryfallClient(),
                        writer=OutputWriter(args.output))
    try:
        result = pipeline.run_demo()
    except Exception as exc:
        print(f"Demo failed: {exc}", file=sys.stderr)
        sys.exit(1)
    print(format_listing(result))
    print(f"\nAppended to {args.output}")


if __name__ == "__main__":
    main()
