"""
Multi-frame consensus reading.

Capturing and reading N frames from a burst, then majority-voting the key
fields (name, set_code, collector_number) kills single-frame misreads caused
by motion blur, OCR instability, or transient model hallucinations.
"""

from collections import Counter
from dataclasses import dataclass, field

import cv2
import numpy as np

from mtg_card_scanner.vision import CardRead, VisionModel


# ── sharpness ─────────────────────────────────────────────────────────────────

def frame_sharpness(frame: np.ndarray) -> float:
    """Laplacian variance — higher means sharper (less blur)."""
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def pick_sharpest(frames: list[np.ndarray]) -> np.ndarray:
    """Return the frame with the highest Laplacian variance."""
    return max(frames, key=frame_sharpness)


# ── voting ────────────────────────────────────────────────────────────────────

def _majority(values: list[str]) -> tuple[str, str]:
    """
    Return ``(consensus_value, confidence)`` for a list of string values.

    confidence:
      - "high"   — unanimous (all non-empty values agree)
      - "medium" — strict majority (> half agree)
      - "low"    — no majority or all empty
    """
    non_empty = [v for v in values if v]
    if not non_empty:
        return "", "low"
    counter = Counter(non_empty)
    top_val, top_count = counter.most_common(1)[0]
    n = len(non_empty)  # denominator = successful reads only
    if top_count == n:
        return top_val, "high"
    if top_count > n / 2:
        return top_val, "medium"
    return top_val, "low"


# ── result ────────────────────────────────────────────────────────────────────

@dataclass
class ConsensusRead:
    """Output of multi-frame consensus."""
    card_read: CardRead           # consensus CardRead (majority-voted fields)
    sharpest_frame: np.ndarray    # sharpest frame — use this for visual match
    name_confidence: str          # "high" | "medium" | "low"
    set_confidence: str = "low"        # "high" | "medium" | "low"
    collector_confidence: str = "low"  # "high" | "medium" | "low"
    reads: list[CardRead] = field(default_factory=list)  # all individual reads


# ── main entry point ──────────────────────────────────────────────────────────

def consensus_read(
    frames: list[np.ndarray],
    model: VisionModel,
) -> ConsensusRead:
    """
    Run the vision model on every frame, majority-vote name / set_code /
    collector_number, and return a ``ConsensusRead`` with the sharpest frame.

    Failed frame reads are skipped; if ALL fail a RuntimeError is raised.
    """
    reads: list[CardRead] = []
    for i, frame in enumerate(frames):
        print(f"  [consensus] Frame {i + 1}/{len(frames)}...")
        try:
            reads.append(model.read_card(frame))
        except Exception as exc:
            print(f"  [consensus] Frame {i + 1} failed: {exc}")

    if not reads:
        raise RuntimeError("All consensus frames failed to read")

    name, name_conf = _majority([r.name for r in reads])
    set_code, set_conf = _majority([r.set_code for r in reads])
    collector_number, collector_conf = _majority([r.collector_number for r in reads])

    # Base: use the read whose name matches consensus (or first as fallback)
    base = next((r for r in reads if r.name == name), reads[0])

    voted = CardRead(
        name=name or base.name,
        set_code=set_code or base.set_code,
        collector_number=collector_number or base.collector_number,
        foil=base.foil,
        language=base.language,
        condition_estimate=base.condition_estimate,
        condition_reason=base.condition_reason,
        artist=base.artist,
        is_old_card=any(r.is_old_card for r in reads),
        raw_response=base.raw_response,
    )

    return ConsensusRead(
        card_read=voted,
        sharpest_frame=pick_sharpest(frames),
        name_confidence=name_conf,
        set_confidence=set_conf,
        collector_confidence=collector_conf,
        reads=reads,
    )
