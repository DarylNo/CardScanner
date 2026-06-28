"""ScanResult dataclass, pretty-print listing, CSV/JSON output writer."""

import csv
import json
from dataclasses import dataclass, asdict, fields
from datetime import datetime
from pathlib import Path
from typing import Any

from mtg_card_scanner.vision import CardRead


@dataclass
class ScanResult:
    timestamp: str
    # Vision model fields
    name: str
    set_code: str
    collector_number: str
    foil: bool
    language: str
    condition: str
    condition_reason: str
    # Scryfall canonical fields
    scryfall_name: str
    scryfall_set_name: str
    scryfall_type: str
    scryfall_rarity: str
    price_usd: str | None
    price_usd_foil: str | None
    scryfall_uri: str


def build_result(card_read: CardRead, scryfall_card: dict[str, Any]) -> ScanResult:
    prices = scryfall_card.get("prices", {})
    return ScanResult(
        timestamp=datetime.now().isoformat(timespec="seconds"),
        name=card_read.name,
        set_code=card_read.set_code,
        collector_number=card_read.collector_number,
        foil=card_read.foil,
        language=card_read.language,
        condition=card_read.condition_estimate,
        condition_reason=card_read.condition_reason,
        scryfall_name=scryfall_card.get("name", ""),
        scryfall_set_name=scryfall_card.get("set_name", ""),
        scryfall_type=scryfall_card.get("type_line", ""),
        scryfall_rarity=scryfall_card.get("rarity", ""),
        price_usd=prices.get("usd"),
        price_usd_foil=prices.get("usd_foil"),
        scryfall_uri=scryfall_card.get("scryfall_uri", ""),
    )


def format_listing(result: ScanResult) -> str:
    foil_tag = " [FOIL]" if result.foil else ""
    effective_price = (
        result.price_usd_foil
        if result.foil and result.price_usd_foil
        else result.price_usd
    )
    price_str = f"${effective_price}" if effective_price else "N/A"
    lang = result.language.upper()

    lines = [
        "-" * 60,
        f"  {result.scryfall_name}{foil_tag}",
        f"  {result.scryfall_set_name}  |  #{result.collector_number}  ({lang})",
        f"  {result.scryfall_type}",
        f"  {result.scryfall_rarity.title()}",
        f"  Condition : {result.condition}  --  {result.condition_reason}",
        f"  Price (USD): {price_str}",
        f"  Scryfall  : {result.scryfall_uri}",
        "-" * 60,
    ]
    return "\n".join(lines)


_FIELDNAMES = [f.name for f in fields(ScanResult)]


class OutputWriter:
    """Appends ScanResult objects to a CSV or JSON file."""

    def __init__(self, output_path: Path) -> None:
        self.path = output_path

    def append(self, result: ScanResult) -> None:
        suffix = self.path.suffix.lower()
        if suffix == ".json":
            self._append_json(result)
        else:
            self._append_csv(result)

    def _append_csv(self, result: ScanResult) -> None:
        write_header = not self.path.exists()
        with open(self.path, "a", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=_FIELDNAMES)
            if write_header:
                writer.writeheader()
            writer.writerow(asdict(result))

    def _append_json(self, result: ScanResult) -> None:
        records: list = []
        if self.path.exists():
            with open(self.path, encoding="utf-8") as f:
                try:
                    records = json.load(f)
                except json.JSONDecodeError:
                    records = []
        records.append(asdict(result))
        with open(self.path, "w", encoding="utf-8") as f:
            json.dump(records, f, indent=2, ensure_ascii=False)
