"""Orchestrates capture → vision → Scryfall → output for one card at a time."""

from pathlib import Path
from typing import Optional

import numpy as np

from mtg_card_scanner.vision import VisionModel, CardRead
from mtg_card_scanner.scryfall import ScryfallClient
from mtg_card_scanner.output import ScanResult, OutputWriter, build_result, format_listing

_DEMO_CARD = CardRead(
    name="Lightning Bolt",
    set_code="m10",
    collector_number="146",
    foil=False,
    language="en",
    condition_estimate="LP",
    condition_reason="Minor edge wear on two corners.",
)




class Pipeline:
    def __init__(
        self,
        model: Optional[VisionModel],
        scryfall: ScryfallClient,
        writer: Optional[OutputWriter] = None,
    ) -> None:
        self.model = model
        self.scryfall = scryfall
        self.writer = writer

    def run_once(self, frame: np.ndarray) -> ScanResult:
        """Full pipeline: vision read → Scryfall lookup → build result."""
        if self.model is None:
            raise RuntimeError("No vision model configured. Use --demo to skip model.")

        print("Reading card with vision model…")
        card_read = self.model.read_card(frame)
        print(
            f"  → {card_read.name!r}  "
            f"[{card_read.set_code.upper()} #{card_read.collector_number}]  "
            f"foil={card_read.foil}  lang={card_read.language}  "
            f"condition={card_read.condition_estimate}"
        )

        print("Looking up on Scryfall…")
        scryfall_card = self.scryfall.lookup(
            card_read.set_code, card_read.collector_number, card_read.name
        )

        result = build_result(card_read, scryfall_card)

        if self.writer:
            self.writer.append(result)

        return result

    def run_demo(self, sample_read: Optional[CardRead] = None) -> ScanResult:
        """
        Demo mode: skip camera + vision model, use a canned CardRead, and run
        the Scryfall lookup + output stages to verify that part of the pipeline.
        """
        card_read = sample_read or _DEMO_CARD

        print(
            f"[DEMO] Sample card read: {card_read.name!r} "
            f"[{card_read.set_code.upper()} #{card_read.collector_number}]"
        )
        print("Looking up on Scryfall…")

        scryfall_card = self.scryfall.lookup(
            card_read.set_code, card_read.collector_number, card_read.name
        )

        result = build_result(card_read, scryfall_card)

        if self.writer:
            self.writer.append(result)

        return result
