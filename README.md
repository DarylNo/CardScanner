# MTG Card Scanner

A live, one-card-at-a-time Magic: The Gathering card scanner. A local vision LLM (via Ollama) reads the card like a human, then Scryfall provides the canonical printing and price. No local card-image library needed.

## Architecture

```
Webcam → capture.py → vision.py (Ollama/Qwen2.5-VL) → scryfall.py → output.py
                                       ↓
                              pipeline.py / main.py
```

- **capture.py** — OpenCV webcam frame capture (on-demand keypress or auto-capture when steady)
- **vision.py** — Sends frame(s) to a local vision LLM; parses strict JSON response
- **scryfall.py** — Scryfall API lookup by set + collector number, falls back to fuzzy name search
- **output.py** — Builds `ScanResult`, pretty-prints listing, appends to CSV/JSON
- **pipeline.py** — Orchestrates the full flow; includes demo mode for testing without a camera/model

---

## Prerequisites

- Python 3.10+
- [Ollama](https://ollama.com) installed and running
- An NVIDIA GPU with enough VRAM (RTX 3090 24 GB handles Qwen2.5-VL-7B easily)
- A webcam

---

## Setup

### 1. Clone and install dependencies

```bash
cd mtg-card-scanner
python -m venv .venv
# Windows
.venv\Scripts\activate
# macOS/Linux
source .venv/bin/activate

pip install -r requirements.txt
```

### 2. Pull the vision model in Ollama

```bash
# Recommended — good balance of speed and accuracy on a 3090
# NOTE: Ollama uses qwen2.5vl (no hyphen between 2.5 and vl)
ollama pull qwen2.5vl:7b

# Higher accuracy (needs ~20 GB VRAM)
ollama pull qwen2.5vl:32b

# Lighter option
ollama pull qwen2.5vl:3b
```

If Ollama is running in Docker:
```bash
docker exec ollama ollama pull qwen2.5vl:7b
```

Verify Ollama is serving at `http://localhost:11434`:

```bash
ollama list
curl http://localhost:11434/api/tags
```

### 3. Configure environment

```bash
cp .env.example .env
# Edit .env with your settings if needed
```

Default `.env` values work if Ollama is running locally with `qwen2.5-vl:7b`.

---

## Usage

### Demo mode (no camera or model needed — tests Scryfall + output pipeline)

```bash
python main.py --demo
```

### Live scanning — press ENTER to capture each card

```bash
python main.py
```

### Auto-capture — captures automatically when the card is held steady

```bash
python main.py --auto
```

### Options

```
--demo              Run demo mode (sample card read → Scryfall → listing)
--camera INT        Webcam index (default: 0)
--auto              Auto-capture when image is steady
--once              Capture one card then exit
--model STR         Ollama model name (default: qwen2.5-vl:7b)
--endpoint URL      Ollama API endpoint (default: http://localhost:11434/v1)
--output PATH       Output file — .csv or .json (default: scan_results.csv)
```

### Examples

```bash
# Scan to JSON output
python main.py --output results.json

# Use a different model
python main.py --model qwen2.5-vl:32b

# Use camera index 1, auto-capture, output to JSON
python main.py --auto --camera 1 --output scan_results.json

# One-shot scan then exit
python main.py --once
```

---

## Output

Each scanned card produces a console listing and an appended row/entry in the output file:

```
────────────────────────────────────────────────────────────
  Lightning Bolt
  Magic 2010  #146  (EN)
  Instant  ·  Common
  Condition: LP  —  Minor edge wear on two corners.
  Price (USD): $0.50
  Scryfall: https://scryfall.com/card/m10/146/lightning-bolt
────────────────────────────────────────────────────────────
```

CSV fields: `timestamp, name, set_code, collector_number, foil, language, condition, condition_reason, scryfall_name, scryfall_set_name, scryfall_type, scryfall_rarity, price_usd, price_usd_foil, scryfall_uri`

---

## Running tests

```bash
pytest
```

Tests cover: JSON parsing (with markdown fences, extra text), Scryfall lookup + fallback, result building, and listing formatting — all without requiring a camera or model.

---

## Swapping models

Edit `.env` (or pass `--model`):

```env
# Lighter — faster, less accurate
VISION_MODEL=qwen2.5vl:3b

# Heavier — slower, more accurate
VISION_MODEL=qwen2.5vl:32b

# Any other Ollama vision model
VISION_MODEL=llava:13b
```

The endpoint can point at any OpenAI-compatible vision API:

```env
OLLAMA_ENDPOINT=http://192.168.1.100:11434/v1
```
