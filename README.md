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

### Install & run (one command)

**macOS / Linux** — no Python required (the script installs everything, plus a
double-clickable launcher in ~/Applications / your app menu):

```bash
curl -fsSL https://raw.githubusercontent.com/DarylNo/CardScanner/master/install.sh | bash
mtg-card-scanner
```

**Windows** — grab `mtg-card-scanner-windows.exe` from
[Releases](https://github.com/DarylNo/CardScanner/releases) and double-click it,
or use the Python route below.

**Any platform with Python 3.11+** (or `uv`):

```bash
pipx install git+https://github.com/DarylNo/CardScanner   # or: uv tool install git+…
mtg-card-scanner
```

Prebuilt macOS/Linux binaries are also on the Releases page (macOS: the
binaries are unsigned — right-click → Open the first time).

The launcher does everything `run_server.sh` used to require by hand:

- generates the HTTPS certificate automatically (phones only allow camera
  access over HTTPS) — pure Python, no openssl needed;
- opens the desktop UI in your browser;
- prints the **phone URL with a QR code** — scan it with the phone camera
  (or click the "Phone:" link on the desktop for an on-screen QR), accept the
  one-time certificate warning, and mount the phone over a tray.

**First run:** the desktop shows a **Build card database** banner (~1 hour,
one-time, resumable — it downloads ~50k card artworks from Scryfall). Once it
finishes, scanning works end to end: place a card in the tray → it identifies,
auto-files single-printing cards, merges duplicates, and prices everything in
the background → pick printings on the desktop → export.

Data (scans, photos, certificates) lives in `~/.mtg-card-scanner/`; the art
index cache in `~/.cache/mtg-card-scanner/`.

Tips:
- On the phone page tap **Area** and drag a box around your tray once —
  detection and captures crop to it (couches and clutter stop mattering).
- Windows asks to allow Python through the firewall on first launch — allow
  it on private networks so the phone can reach the server.
- `mtg-card-scanner --help` for port/data-dir options.
- Linux: if launch fails with a `libGL` error, `sudo apt install libgl1`
  (or swap in `opencv-python-headless`).
- **Chromebook**: enable Linux mode (Settings → Advanced → Developers →
  Turn on Linux), run the macOS/Linux one-liner inside it (ARM Chromebooks:
  use the script, not the binary), then add a port forward for TCP 8443
  (Settings → Linux → Port forwarding) so the phone can reach the server.
  No install needed to use a Chromebook as just the control screen or the
  camera — both are plain browser pages served by a scanner running elsewhere.

### Updating

- **install.sh / pipx / uv users:** re-run the install one-liner (or
  `pipx upgrade mtg-card-scanner`) — it reinstalls the latest master.
- **Binary users:** download the newest release and replace the old file.
- Your data (`~/.mtg-card-scanner/`, art index cache) is untouched by updates.

<details>
<summary>Manual / development run (the old way)</summary>

```bash
pip install -r requirements.txt                # includes fastapi/uvicorn
python -m mtg_card_scanner.art_index build     # one-time art index build (see below)
./run_server.sh                                # serves HTTPS on :8443 (generates a self-signed cert)
```
</details>

Then open, on the same LAN:

- Desktop: `https://<this-machine-ip>:8443/`
- Phone:   `https://<this-machine-ip>:8443/phone`

> **HTTPS is required for the phone camera.** Browsers only allow camera access
> (`getUserMedia`) in a secure context — `https://` or `localhost`. The launcher generates a
> self-signed certificate; accept the one-time security warning on the phone. (On the desktop
> alone you could use plain `http://localhost`, but the phone needs HTTPS.)

Config via env: `PORT`, `SCAN_DB`, `HOST`.

### Auto-scan (hands-free)

The phone page watches the camera at 5 fps. It fires when **something occupies the scan
area and settles** (~half a second) — geometry doesn't matter; the server judges whether
it's a card. The empty tray is learned at startup and re-learned automatically, so
*removing* a card never triggers a scan, and a card is never rescanned until the scene
changes. Tap **Area** once and drag a box around your tray — detection and captures crop
to it, so clutter around the rig can't interfere. The page holds a screen wake-lock so a
mounted phone doesn't sleep.

Two modes: **Auto ON** is fully hands-free — cards identify, single-printing cards
auto-file (marked ⚠), duplicates merge into quantity, and printing picks happen on the
desktop. **Auto OFF + Scan Card** opens the printing picker right on the phone.

Start the page with the tray **empty** — the first steady second seeds the "empty" reference.

### Face to Face pricing + price filter

`mtg_card_scanner/facetoface.py` looks up live prices from Face to Face Games' public Shopify
JSON endpoints (no API key), by condition and foil. A background **sweep** prices everything
automatically (every 60s while work remains): selected scans get their exact printing priced;
unpicked scans get **every** candidate printing priced so the price filter can hide them only
on full knowledge. Fetching self-paces (slow start, speeds up on success, backs off on 429s,
circuit-breaks and cools down if the storefront objects) and distinguishes "confirmed not
listed" from "couldn't reach the store" — outages stay retryable and never break a scan.
The header shows live sweep progress, pace, and a countdown to the next check; the 🐞 button
opens a request-level log.

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
                              art_index.py  (pHash art region → nearest of ~49k artworks → NAME)
                                       ↓
                              scryfall.py   (all printings of that name)
                                       ↓
                              visual_match.py (rank printings by art distance)
                                       ↓
                              desktop UI: pick printing → facetoface.py price → export.py
```

- **card_detect.py** — finds the card quad in the frame, perspective-warps to 630×880; frame
  sharpness scoring for burst selection
- **art_index.py** — the identifier: a local SQLite index of dual-scale pHashes (64-bit
  coarse + 256-bit fine) of every unique Magic artwork (Scryfall `unique_artwork` bulk
  data); nearest-neighbour query over jittered scan crops, scored `4·d64 + d256`
- **scryfall.py** — Scryfall API client (printings by name, set+collector lookup, rate-limited)
- **visual_match.py** — ranks a name's printings against the scan by multi-region pHash
- **pipeline.py** — orchestrates identify → printings → rank; graceful "use manual search"
  result when the art match isn't confident
- **server/** — FastAPI app, SQLite scan store, F2F client, Mana Exchange export

---

## Prerequisites

- Python 3.11+
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
unique artwork (~49k images, ~0.5 GB), hashing each into
`~/.cache/mtg-card-scanner/art_index/`. It is **throttled to Scryfall's rate limits**, takes
**1–3 hours**, and is **safe to interrupt — re-running resumes** where it left off. Re-run it
occasionally (e.g. after new set releases): only new artworks are fetched.

Useful subcommands:

```bash
python -m mtg_card_scanner.art_index build --limit 200   # quick smoke build
python -m mtg_card_scanner.art_index status              # row count / bulk revision
python -m mtg_card_scanner.art_index query photo.jpg     # identify a saved photo (tuning/debug)
```

**Optional but recommended:** predownload every printing's ranking image so the first scan
of a new card name is as fast as a repeat scan (otherwise that first scan waits up to ~5 s
while the name's printing images download):

```bash
python -m mtg_card_scanner.art_index prefetch-printings   # ~100k paper images, ~2 GB, resumable
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

Identification confidence is a combined-score threshold in `mtg_card_scanner/art_index.py`
(`_MAX_CONFIDENT_DISTANCE`, default 140 on the `4·d64 + d256` scale; correct artworks measure
~110–125 on real rig photos, the noise floor starts ~150). If too many scans come back "no
confident match", raise it slightly; if wrong names are confidently matched, lower it. Use
`python -m mtg_card_scanner.art_index query <photo.jpg>` against saved photos from your rig to
see real scores. Foils, sleeves, and glare raise scores — the failure mode is always the
graceful manual-search path, never a silent wrong export.
