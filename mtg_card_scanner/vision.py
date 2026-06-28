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
You are an expert Magic: The Gathering card grader and identifier.
Two images are provided:
  1. The full card
  2. A cropped close-up of the bottom strip (where the collector info line is printed)

Use the bottom-strip image to read the set code and collector number accurately.

Return ONLY a valid JSON object — no markdown fences, no explanation, no extra text.

Required fields:
  "name"               — Full card name exactly as printed (string)
  "set_code"           — 3-4 character set code from the collector info line, lowercase
                         (e.g. "mkm", "one", "dmu", "m10", "lea")
  "collector_number"   — Collector number digits only, no set-size suffix
                         (e.g. "042" not "042/300")
  "foil"               — true if the card has a foil/holographic finish (boolean)
  "language"           — ISO language code: "en","fr","de","es","it","pt","ja","ko","ru","zhs","zht"
  "condition_estimate" — Exactly one of: "NM", "LP", "MP", "HP", "DMG"
  "condition_reason"   — One sentence explaining the condition grade

Condition grading guide:
  NM  Near Mint     — No visible wear. Corners sharp. Surface pristine.
  LP  Lightly Played— Minor edge/corner wear or faint surface scratches. Still looks great.
  MP  Moderately Played — Noticeable wear, light creases, scuffs, or edge whitening.
  HP  Heavily Played — Heavy wear, significant creases, border whitening, surface damage.
  DMG Damaged        — Tears, deep bends, water damage, writing, holes, or severe warping.

Collector info line format (bottom of card):
  {collector_number}/{set_size} ★ {LANGUAGE} {SET_CODE}
  Example: "042/300 ★ EN MOM"  →  set_code="mom", collector_number="042", language="en"

Return ONLY the JSON object, starting with { and ending with }.
Example output:
{
  "name": "Lightning Bolt",
  "set_code": "m10",
  "collector_number": "146",
  "foil": false,
  "language": "en",
  "condition_estimate": "NM",
  "condition_reason": "No visible wear; corners are sharp and surface is pristine."
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
        """Send the frame (plus a bottom-strip crop) to the vision LLM and return a CardRead."""
        full_b64 = self._encode_image(frame)
        strip_b64 = self._encode_image(self._crop_bottom(frame))

        response = self.client.chat.completions.create(
            model=self.model,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {"url": f"data:image/jpeg;base64,{full_b64}"},
                        },
                        {
                            "type": "image_url",
                            "image_url": {"url": f"data:image/jpeg;base64,{strip_b64}"},
                        },
                        {"type": "text", "text": CARD_READ_PROMPT},
                    ],
                }
            ],
            temperature=0.05,
            max_tokens=512,
        )

        raw = response.choices[0].message.content or ""
        return self._parse_response(raw)

    @staticmethod
    def _encode_image(frame: np.ndarray, quality: int = 92) -> str:
        """Encode a numpy BGR frame as a base64 JPEG string."""
        _, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, quality])
        return base64.b64encode(buf.tobytes()).decode("utf-8")

    @staticmethod
    def _crop_bottom(frame: np.ndarray, fraction: float = 0.18) -> np.ndarray:
        """Return the bottom `fraction` of the frame (the collector info strip)."""
        h = frame.shape[0]
        cut = int(h * (1.0 - fraction))
        return frame[cut:, :]

    @staticmethod
    def _parse_response(text: str) -> CardRead:
        """
        Robustly extract a CardRead from model output that may contain markdown
        fences, preamble text, or trailing commentary.
        """
        # Strip markdown code fences
        clean = re.sub(r"^```(?:json)?\s*", "", text.strip(), flags=re.MULTILINE)
        clean = re.sub(r"\s*```\s*$", "", clean.strip(), flags=re.MULTILINE)

        # Grab the first complete JSON object
        match = re.search(r"\{.*\}", clean, re.DOTALL)
        if not match:
            raise ValueError(f"No JSON object found in model response:\n{text!r}")

        data = json.loads(match.group())

        # Normalise collector number — strip trailing "/NNN" if the model included it
        raw_num = str(data.get("collector_number", "")).strip()
        collector_number = raw_num.split("/")[0].strip()

        return CardRead(
            name=str(data.get("name", "")).strip(),
            set_code=str(data.get("set_code", "")).strip().lower(),
            collector_number=collector_number,
            foil=bool(data.get("foil", False)),
            language=str(data.get("language", "en")).strip().lower(),
            condition_estimate=str(data.get("condition_estimate", "LP")).strip().upper(),
            condition_reason=str(data.get("condition_reason", "")).strip(),
            raw_response=text,
        )
