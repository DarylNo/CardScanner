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
  Example bottom-left:
      U 0196
      MOM • EN  ⚘ FILIPE PAGLIUSO
  → set_code="mom", collector_number="196", language="en"

IMPORTANT — common OCR confusions to watch for in the small collector text:
  • 6 and 8 look very similar — read carefully
  • 0 (zero) and O (letter O) — in set codes these are usually letters
  • 1 and I — in numbers these are digits
  • B and 8 — rare but possible

If a field is genuinely unreadable, return null — do NOT fabricate a value.

Return ONLY valid JSON, no markdown, no extra text:
{
  "name": "<exact title from image 2, or null>",
  "set_code": "<3-4 char lowercase code from image 3, or null>",
  "collector_number": "<digits only, no leading zeros, no slash/total, or null>",
  "foil": <true|false>,
  "language": "<2-3 char code or null>",
  "condition_estimate": "<NM|LP|MP|HP|DMG>",
  "condition_reason": "<one sentence>",
  "confidence": "<high|medium|low>"
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
    raw_response: str = field(default="", repr=False)


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
        self.client = OpenAI(base_url=endpoint, api_key="ollama")

    def read_card(self, frame: np.ndarray) -> CardRead:
        """
        Detect the card in the frame, perspective-warp it, build focused crops,
        send to the vision LLM, and return a CardRead.
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
        return self._parse_response(raw)

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

        return CardRead(
            name=str(data.get("name") or "").strip(),
            set_code=str(data.get("set_code") or "").strip().lower(),
            collector_number=collector_number,
            foil=bool(data.get("foil", False)),
            language=str(data.get("language") or "en").strip().lower(),
            condition_estimate=str(data.get("condition_estimate") or "LP").strip().upper(),
            condition_reason=str(data.get("condition_reason") or "").strip(),
            raw_response=text,
        )
