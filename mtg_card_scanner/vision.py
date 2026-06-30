"""Vision model interface — sends card image(s) to a local Ollama vision LLM."""

import base64
import json
import re
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Literal

import cv2
import numpy as np

if TYPE_CHECKING:
    from openai import OpenAI as _OpenAI

ConditionGrade = Literal["NM", "LP", "MP", "HP", "DMG"]

# Prompt for pre-2003 cards that have no collector number / set code text
OLD_CARD_PROMPT = """\
You are scanning a VINTAGE Magic: The Gathering card from the 1990s.

IMPORTANT: These old cards have NO collector number and NO set code text printed on them.
Do NOT invent or guess a collector number. Do NOT invent a set code. Return null for both.

One image shows the full perspective-corrected card.

OLD CARD LAYOUT (top to bottom):
  1. Dark title bar at very top — CARD NAME in large text
  2. Card illustration (artwork)
  3. Type line band — e.g. "Creature" or "Instant"
  4. Tan/brown rules text box
  5. Bottom strip — artist name bottom-left, power/toughness bottom-right

HOW TO READ THE CARD NAME:
  - First look at the top dark banner — the name is in large text there.
  - If the top banner is blurry or cropped, find the name INSIDE the rules text box:
    old cards often say "When [CARD NAME] comes into play..." or reference the name in abilities.
  - Do NOT use the type line (e.g. "Creature — Zombie" or "Instant") as the name — they are different fields.

HOW TO READ THE ARTIST:
  - Look at the bottom-left strip below the text box. The artist name is printed there.

Return ONLY valid JSON, no markdown, no extra text:
{
  "name": "<exact card name, required — never null>",
  "artist": "<artist name as printed, or null if unreadable>",
  "type_line": "<type line text, e.g. 'Creature' or 'Instant', or null>",
  "foil": false,
  "language": "en",
  "condition_estimate": "<NM|LP|MP|HP|DMG>",
  "condition_reason": "<one sentence>",
  "confidence": "<high|medium|low>"
}"""

CARD_READ_PROMPT = """\
You are a Magic: The Gathering card scanner. TRANSCRIBE exactly what is printed — do NOT guess.

Three images are provided:
  1. Perspective-corrected full card (use for foil detection and overall condition)
  2. Title-bar close-up — the card name in large text at the top
  3. Bottom-left collector info close-up — small text with collector number, set code, language

READ image 2 for the card name. READ image 3 for the collector number and set code.

COLLECTOR INFO LINE FORMAT (image 3):
  The bottom-left of a Magic card prints:  {RARITY_LETTER} {collector_number}
                                           {SET_CODE} • {LANG}  {artist_symbol}  {ARTIST}
  The two text lines look like:
      [RARITY_LETTER] [NUMBER]
      [SETCODE] • [LANG]  [symbol]  [ARTIST NAME]
  Transcribe the actual printed values from image 3 — do NOT copy these placeholder labels.

IMPORTANT — common OCR confusions to watch for in the small collector text:
  • 6 and 8 look very similar — read carefully
  • 0 (zero) and O (letter O) — in set codes these are usually letters
  • 1 and I — in numbers these are digits
  • B and 8 — rare but possible

If a field is genuinely unreadable, return null — do NOT fabricate a value.

ARTIST LINE: every Magic card prints the artist name at the bottom of the card.
  Modern cards (post-2003): paintbrush icon followed by artist name, bottom of text box.
  Old cards (pre-2003): "Illus. [ARTIST NAME]" printed at the bottom-left strip.
Read this from image 1 (full card) — it is always there.

Return ONLY valid JSON, no markdown, no extra text:
{
  "name": "<exact title from image 2, or null>",
  "set_code": "<3-4 char lowercase code from image 3, or null>",
  "collector_number": "<digits only, no leading zeros, no slash/total, or null>",
  "foil": <true|false>,
  "language": "<2-3 char code or null>",
  "condition_estimate": "<NM|LP|MP|HP|DMG>",
  "condition_reason": "<one sentence>",
  "confidence": "<high|medium|low>",
  "artist": "<artist name from bottom of card, or null>"
}"""


@dataclass
class CardRead:
    name: str
    set_code: str
    collector_number: str
    foil: bool
    language: str
    condition_estimate: str
    condition_reason: str
    artist: str = ""           # populated for old (pre-collector-number) cards
    is_old_card: bool = False  # True when old-card path was used
    raw_response: str = field(default="", repr=False)


_MODEL_TIMEOUT_S = 25.0   # ceiling per model call — a hung Ollama must fail fast, never block a scan forever
_MODEL_MAX_RETRIES = 1    # one retry on a transient failure; burst consensus already gives redundancy


class VisionModel:
    def __init__(
        self,
        endpoint: str = "http://localhost:11434/v1",
        model: str = "qwen2.5vl:7b",
    ) -> None:
        self.model = model
        try:
            from openai import OpenAI
        except ImportError as exc:
            raise ImportError(
                "The 'openai' package is required for live scanning. "
                "Install it with: pip install openai"
            ) from exc
        # Explicit timeout — without one, a stalled Ollama call (model busy,
        # GPU contention, etc.) can hang the SDK's default multi-minute
        # timeout, which looks to the user like the scan is permanently stuck.
        self.client = OpenAI(
            base_url=endpoint, api_key="ollama",
            timeout=_MODEL_TIMEOUT_S, max_retries=_MODEL_MAX_RETRIES,
        )

    @staticmethod
    def _looks_like_old_card(read: "CardRead") -> bool:
        """
        Return True when the model response suggests a pre-Exodus card.

        Real MTG set codes always contain at least one letter (e.g. "ice", "m10",
        "mom").  If the model returned no set code, or a purely numeric one
        (hallucinated from P/T or rules-text numbers), the card has no collector
        line — i.e. it predates Exodus (pre-1998).
        """
        return not (read.set_code and re.search(r'[a-zA-Z]', read.set_code))

    def read_card(self, frame: np.ndarray) -> CardRead:
        """
        Detect the card in the frame, perspective-warp it, build focused crops,
        send to the vision LLM, and return a CardRead.

        If the first pass returns no valid collector info (old pre-Exodus card),
        automatically retries with OLD_CARD_PROMPT targeting name + artist.
        """
        from mtg_card_scanner.card_detect import extract_card, card_sub_crops

        card, detected = extract_card(frame)
        if not detected:
            print("  [vision] No card quad found — using centre-crop fallback")

        crops = card_sub_crops(card)

        response = self.client.chat.completions.create(
            model=self.model,
            messages=[{
                "role": "user",
                "content": [
                    {"type": "image_url",
                     "image_url": {"url": f"data:image/jpeg;base64,{self._encode_image(card)}"}},
                    {"type": "image_url",
                     "image_url": {"url": f"data:image/jpeg;base64,{self._encode_image(crops['title_bar'])}"}},
                    {"type": "image_url",
                     "image_url": {"url": f"data:image/jpeg;base64,{self._encode_image(crops['bottom_left_collector'])}"}},
                    {"type": "text", "text": CARD_READ_PROMPT},
                ],
            }],
            temperature=0.0,
            max_tokens=512,
        )

        raw = response.choices[0].message.content or ""
        result = self._parse_response(raw)

        if self._looks_like_old_card(result):
            print("  [vision] Old card detected — retrying with vintage card prompt")
            result = self._read_old_card(card)

        return result

    def _read_old_card(self, card: np.ndarray) -> "CardRead":
        """Second-pass read for pre-Exodus cards using OLD_CARD_PROMPT."""
        response = self.client.chat.completions.create(
            model=self.model,
            messages=[{
                "role": "user",
                "content": [
                    {"type": "image_url",
                     "image_url": {"url": f"data:image/jpeg;base64,{self._encode_image(card)}"}},
                    {"type": "text", "text": OLD_CARD_PROMPT},
                ],
            }],
            temperature=0.0,
            max_tokens=512,
        )
        raw = response.choices[0].message.content or ""
        return self._parse_old_card_response(raw)

    @staticmethod
    def _parse_old_card_response(text: str) -> "CardRead":
        clean = re.sub(r"^```(?:json)?\s*", "", text.strip(), flags=re.MULTILINE)
        clean = re.sub(r"\s*```\s*$", "", clean.strip(), flags=re.MULTILINE)
        match = re.search(r"\{.*\}", clean, re.DOTALL)
        if not match:
            raise ValueError(f"No JSON in old-card model response:\n{text!r}")
        data = json.loads(match.group())
        return CardRead(
            name=str(data.get("name") or "").strip(),
            set_code="",
            collector_number="",
            foil=bool(data.get("foil", False)),
            language=str(data.get("language") or "en").strip().lower(),
            condition_estimate=str(data.get("condition_estimate") or "LP").strip().upper(),
            condition_reason=str(data.get("condition_reason") or "").strip(),
            artist=str(data.get("artist") or "").strip(),
            is_old_card=True,
            raw_response=text,
        )

    @staticmethod
    def _encode_image(frame: np.ndarray, quality: int = 95) -> str:
        _, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, quality])
        return base64.b64encode(buf.tobytes()).decode("utf-8")

    @staticmethod
    def _parse_response(text: str) -> CardRead:
        """
        Robustly extract a CardRead from model output that may contain markdown
        fences, preamble text, or trailing commentary.
        """
        clean = re.sub(r"^```(?:json)?\s*", "", text.strip(), flags=re.MULTILINE)
        clean = re.sub(r"\s*```\s*$", "", clean.strip(), flags=re.MULTILINE)

        match = re.search(r"\{.*\}", clean, re.DOTALL)
        if not match:
            raise ValueError(f"No JSON object found in model response:\n{text!r}")

        data = json.loads(match.group())

        raw_num = str(data.get("collector_number") or "").strip()
        # Strip leading zeros and "/NNN" set-size suffix
        collector_number = raw_num.split("/")[0].strip().lstrip("0") or raw_num.split("/")[0].strip()

        raw_set = str(data.get("set_code") or "").strip().lower()
        # Model sometimes writes the string "null" or "none" instead of JSON null
        set_code = "" if raw_set in ("null", "none", "n/a", "unknown") else raw_set

        return CardRead(
            name=str(data.get("name") or "").strip(),
            set_code=set_code,
            collector_number=collector_number,
            foil=bool(data.get("foil", False)),
            language=str(data.get("language") or "en").strip().lower(),
            condition_estimate=str(data.get("condition_estimate") or "LP").strip().upper(),
            condition_reason=str(data.get("condition_reason") or "").strip(),
            artist=str(data.get("artist") or "").strip(),
            raw_response=text,
        )
