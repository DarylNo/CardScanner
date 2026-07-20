# MTG Card Scanner

A live, one-card-at-a-time Magic: The Gathering card scanner. A local vision LLM (via Ollama) reads the card like a human, then Scryfall provides the canonical printing and price. No local card-image library needed.

---

## Web app (phone camera + desktop control)

The scanner runs as a **local web app** with two roles open at the same time:

- **Phone** (`/phone`) is the **camera** — point at a card, tap, and it uploads the image.
- **Desktop** (`/`) is the **control surface** — it shows the card's printings **ranked by
  artwork**, you **pick the exact printing**, it fetches **Face to Face Games** pricing for
  that printing, and you **review/filter all scans** and **export** the ones you want.

Because a card's art is shared across many printings, art-matching intentionally returns a
*ranked set of candidates* rather than one guess — you choose the correct printing (set +
collector number), which is what downstream pricing and export depend on.

### Run it

```bash
pip install -r requirements.txt      # includes fastapi/uvicorn
./run_server.sh                      # serves HTTPS on :8443 (generates a self-signed cert)
```

Then open, on the same LAN:

- Desktop: `https://<this-machine-ip>:8443/`
- Phone:   `https://<this-machine-ip>:8443/phone`

> **HTTPS is required for the phone camera.** Browsers only allow camera access
> (`getUserMedia`) in a secure context — `https://` or `localhost`. `run_server.sh` generates a
> self-signed certificate; accept the one-time security warning on the phone. (On the desktop
> alone you could use plain `http://localhost`, but the phone needs HTTPS.)

Ollama must be running on the machine that serves the app (it has the GPU). Config via env:
`OLLAMA_ENDPOINT`, `VISION_MODEL`, `PORT`, `SCAN_DB`.

### Face to Face pricing

`mtg_card_scanner/facetoface.py` looks up live prices from Face to Face Games' public Shopify
JSON endpoints (no API key) for the exact printing you select, by condition and foil. It's shown
as **decision support** while you review — a transient F2F outage just shows "no listing", never
breaks a scan.

### Export to Mana Exchange

The **Export** button downloads a text file in Mana Exchange's admin **mass-entry** format —
one line per card, `Qty SetCode CollectorNumber Condition Finish` (e.g. `2 OTJ 200 NM Foil`).
Paste it into Mana Exchange → Admin → Add Cards. Mana Exchange derives name, images, and its own
pricing from Scryfall via set + collector number, so only these five columns are needed.

### Testing without a phone or GPU

The pipeline and API are covered by `pytest` (Face to Face matching, art-ranked candidates, the
SQLite store, the export format, and the full API path via a mocked vision model). You can also
`curl` an image straight at `POST /api/scan` to drive a real scan without the phone.

---

## Architecture (vision pipeline)

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
