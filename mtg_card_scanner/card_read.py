"""CardRead — what identification produced for one scanned card.

Historically this was filled in by a vision LLM; it is now produced by the
art-hash index (name/set/collector/artist) plus fixed defaults for the fields
a human confirms in the desktop UI (condition, finish, language).
"""

from dataclasses import dataclass, field


@dataclass
class CardRead:
    name: str
    set_code: str
    collector_number: str
    foil: bool
    language: str
    condition_estimate: str      # NM | LP | MP | HP | DMG — UI default, user-editable
    condition_reason: str
    artist: str = ""
    # Runner-up name guesses from the art index: [{"name": str, "distance": int}, ...]
    alternates: list = field(default_factory=list)
