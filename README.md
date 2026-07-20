# MTG Card Scanner

A hands-free, one-card-at-a-time Magic: The Gathering card scanner. A **local
perceptual-hash art index** (built once from Scryfall bulk data) identifies each card by its
artwork — no LLM, no GPU, no cloud vision — then Scryfall provides the canonical printings
and price.

---

## Web app (phone camera + desktop control)

The scanner runs as a **local web app** with two roles open at the same time:

- **Phone** (`/phone`) is the **camera** — put it on a mount over a tray. Place a card and it
  **scans automatically**; swap the card to scan the next one.
- **Desktop** (`/`) is the **control surface** — it shows the card's printings **ranked by
  artwork**, you **pick the exact printing**, it fetches **Face to Face Games** pricing for
  that printing, and you **review/filter all scans** and **export** the ones you want.

Because a card's art is shared across many printings, art-matching intentionally returns a
*ranked set of candidates* rather than one guess — you choose the correct printing (set +
collector number), which is what downstream pricing and export depend on.

### Run it

```bash
pip install -r requirements.txt                # includes fastapi/uvicorn
python -m mtg_card_scanner.art_index build     # one-time art index build (see below)
./run_server.sh                                # serves HTTPS on :8443 (generates a self-signed cert)
```

Then open, on the same LAN:

- Desktop: `https://<this-machine-ip>:8443/`
- Phone:   `https://<this-machine-ip>:8443/phone`

> **HTTPS is required for the phone camera.** Browsers only allow camera access
> (`getUserMedia`) in a secure context — `https://` or `localhost`. `run_server.sh` generates a
> self-signed certificate; accept the one-time security warning on the phone. (On the desktop
> alone you could use plain `http://localhost`, but the phone needs HTTPS.)

Config via env: `PORT`, `SCAN_DB`, `HOST`.

### Auto-scan (hands-free)

The phone page watches the camera at 5 fps. When the scene changes (a card is placed or
swapped) and then holds still for ~1 second, it captures a burst and scans — no tap needed.
The empty tray is learned at startup, so *removing* a card never triggers a scan, and a card
is never rescanned until the scene changes again. The **Auto** toggle turns this off; the
**Scan Card** button always works manually. The page holds a screen wake-lock so a mounted
phone doesn't sleep.

Start the page with the tray **empty** — the first steady second seeds the "empty" reference.

### Face to Face pricing + price filter

`mtg_card_scanner/facetoface.py` looks up live prices from Face to Face Games' public Shopify
JSON endpoints (no API key) for the exact printing you select, by condition and foil. It's shown
as **decision support** while you review — a transient F2F outage just shows "no listing", never
breaks a scan.

The scan list has **Min $ / Max $** filters on the fetched F2F price. Cards outside the range
are hidden from the list; **Exclude filtered** marks them all as not-included in the export in
one click (reversible per card via its checkbox). Unpriced scans always stay visible.

### Export to Mana Exchange

The **Export** button downloads a text file in Mana Exchange's admin **mass-entry** format —
one line per card, `Qty SetCode CollectorNumber Condition Finish` (e.g. `2 OTJ 200 NM Foil`).
Paste it into Mana Exchange → Admin → Add Cards. Mana Exchange derives name, images, and its own
pricing from Scryfall via set + collector number, so only these five columns are needed.

### Testing without a phone

The pipeline and API are covered by `pytest` (Face to Face matching, art-ranked candidates, the
SQLite store, the export format, and the full API path via a fake pipeline). You can also
`curl` an image straight at `POST /api/scan` to drive a real scan without the phone.

---

## Architecture

```
Phone camera → POST /api/scan → card_detect.py (find + warp card)
                                       ↓
                              art_index.py  (pHash art region → nearest of ~47k artworks → NAME)
                                       ↓
                              scryfall.py   (all printings of that name)
                                       ↓
                              visual_match.py (rank printings by art distance)
                                       ↓
                              desktop UI: pick printing → facetoface.py price → export.py
```

- **card_detect.py** — finds the card quad in the frame, perspective-warps to 630×880; frame
  sharpness scoring for burst selection
- **art_index.py** — the identifier: a local SQLite index of the 64-bit pHash of every unique
  Magic artwork (Scryfall `unique_artwork` bulk data); nearest-neighbour Hamming query
- **scryfall.py** — Scryfall API client (printings by name, set+collector lookup, rate-limited)
- **visual_match.py** — ranks a name's printings against the scan by multi-region pHash
- **pipeline.py** — orchestrates identify → printings → rank; graceful "use manual search"
  result when the art match isn't confident
- **server/** — FastAPI app, SQLite scan store, F2F client, Mana Exchange export

---

## Prerequisites

- Python 3.10+
- ~2 GB free disk for the art index build (bulk JSON + cached images + index)
- No GPU, no Ollama, no API keys

---

## Setup

### 1. Clone and install dependencies

```bash
cd CardScanner
python -m venv .venv
# Windows
.venv\Scripts\activate
# macOS/Linux
source .venv/bin/activate

pip install -r requirements.txt
```

### 2. Build the art index (one-time)

```bash
python -m mtg_card_scanner.art_index build
```

This downloads Scryfall's `unique_artwork` bulk file (~265 MB) and a small image of every
unique artwork (~47k images, ~0.5 GB), hashing each into
`~/.cache/mtg-card-scanner/art_index/`. It is **throttled to Scryfall's rate limits**, takes
**1–3 hours**, and is **safe to interrupt — re-running resumes** where it left off. Re-run it
occasionally (e.g. after new set releases): only new artworks are fetched.

Useful subcommands:

```bash
python -m mtg_card_scanner.art_index build --limit 200   # quick smoke build
python -m mtg_card_scanner.art_index status              # row count / bulk revision
python -m mtg_card_scanner.art_index query photo.jpg     # identify a saved photo (tuning/debug)
```

The server runs fine before the index is built — scans just return "Art index not built" until
then.

### 3. Launch

```bash
./run_server.sh
```

---

## CLI

`main.py` is a demo/diagnostic entry point only (the web app is the scanner):

```bash
python main.py --demo                    # sample card → Scryfall → listing/CSV
python main.py --demo --output out.json  # JSON output
```

---

## Running tests

```bash
pytest
```

Tests cover: the art index (hashing, dedupe, resume, filtering), the pipeline (confidence
thresholds, multi-frame retry, error paths), Scryfall lookup + fallback, F2F price matching,
the scan store, the export format, and the full API path — all without network or a camera.

---

## Accuracy tuning

Identification confidence is a Hamming-distance threshold in `mtg_card_scanner/art_index.py`
(`_MAX_CONFIDENT_DISTANCE`, default 16 of 64 bits). If too many scans come back "no confident
match", raise it slightly; if wrong names are confidently matched, lower it. Use
`python -m mtg_card_scanner.art_index query <photo.jpg>` against saved photos from your rig to
see real distances. Foils, sleeves, and glare raise distances — the failure mode is always the
graceful manual-search path, never a silent wrong export.
