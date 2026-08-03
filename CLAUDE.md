# CardScanner — Claude Code Guide

## What this is

A hands-free MTG card scanner: a phone (browser page) on a mount over a tray is
the camera; a desktop browser page is the review/control surface; a local
FastAPI server identifies cards by **perceptual-hash artwork matching** (no
LLM, no cloud vision), confirms the exact printing by **OCR of the card's
collector line**, prices against Face to Face Games, and exports to the
user's Mana Exchange store.

## Run / develop

```bash
pip install -e ".[test]"
pytest tests/ -q                # the correctness gate — keep it green
.venv/Scripts/mtg-card-scanner --no-browser    # dev launch (Windows venv)
```

The entry point is a **supervisor** (`mtg_card_scanner/launch.py`): it spawns
itself with `--serve`, and exit code 42 from the server means "update me and
relaunch" (the browser's Update button). The rig runs with
`SCAN_DB=scans.db SCAN_IMAGES_DIR=scan_images` env so data stays in the repo
dir; fresh installs default to `~/.mtg-card-scanner`.

**Deploy = restart.** Kill the listener on :8443 and relaunch; the version
banner in both UIs (`d<N> · <git hash>`) is how you verify what's running.
**Bump `UI_VERSION`** in `phone.html` / `desktop.html` on every edit to them —
stale-page debugging burned a full session before the banners existed.

## Architecture

```
phone.html ──POST /api/scan──▶ card_detect.py   find+warp card (texture-gated)
                                    │
                              art_index.py      pHash art → name (~49k artworks, SQLite)
                                    │
                              scryfall.py       all PAPER printings of that name
                                    │
                              visual_match.py   rank printings (art×4 + title + textbox)
                                    │
                              ocr_id.py         collector-line OCR → exact printing to #1
                                    │
              desktop.html    pick/review ──▶ facetoface.py price ──▶ export.py
server/app.py                 FastAPI: scan store (SQLite), price sweeps, setup UI
```

## Invariants — learned the hard way, do not regress

**Detection (phone):** the trigger is *occupancy + stillness only*
(mask coverage >2%, 2 steady samples, exposure-drift-cancelled diff). Three
separate attempts at card-shaped geometry gates (ratio/density/texture
windows) each rejected real sleeved cards. The SERVER judges card content
(texture-gated quads, blank-surface guard). The user-set scan Area (ROI)
crops sampling and capture.

**Identification thresholds** (`art_index.py`): confident ≤110, or ≤140 with
a ≥20-point lead over the 2nd name (validated on live data: the gap rule
separated right from wrong exactly). Blank guard before hashing. Digital
printings stay IN the identification index (an artwork's only representative
may be digital — M15 Shivan Dragon) but OUT of candidates/prefetch.

**pHash limits — measured, don't retry:** 256-bit region hashes and
set-symbol template matching are pure noise for same-art same-frame reprints
(photo-vs-CDN noise floor ~20 bits/64). That's what `ocr_id.py` is for:
RapidOCR (pip-only, never system tesseract) reads the bottom strip;
confusion-tolerant (I≈1, S≈5…) UNIQUE set-code match; compound collectors
("A25-85") win outright so List copies don't misattribute; ambiguity = no-op.

**Auto-pick grounds** (server, scan time): exactly one printing exists, OR
the top candidate is OCR-confirmed — both file NM/Non-Foil/×1 with the
`auto_picked` flag (⚠ in the UIs) and auto-merge duplicates into quantity.
Unconfirmed multi-candidate scans always wait for a human. The auto-sweep
tick also runs retro passes: strip stale art-series candidates, retro-OCR
pending scans from their stored photos (once each, budgeted), auto-pick
newly-single/confirmed ones.

**F2F client (`facetoface.py`):**
- `curl_cffi` Chrome TLS impersonation is THE 429 fix — Shopify fingerprints
  the TLS handshake, not the UA (same URL: python-requests 429'd, Chrome
  handshake 6/6 200s). Keep the browser UA + exact-first query ladder
  ("name collector setname [foil]") ported from the ManaExchange store.
- Adaptive pacing: slow-start 2s, ×0.9 per success, ×2 per 429, idle reset.
- `F2FUnavailableError` ≠ "no listing". Only a completed search may record
  the empty marker; failures stay unsearched and retryable. Never conflate.
- All waits are interruptible (`threading.Event`) so Stop works mid-backoff.

**Pricing sweep (`server/app.py`):** ONE F2F consumer while active (all other
pricers stand down); selected scans price ONLY their selection; unpicked
scans price EVERY candidate print (the price filter may hide a pending card
only on full knowledge); targets are rechecked before each fetch (picks land
mid-sweep); circuit breaker after 5 consecutive unavailable → 10-min
cooldown; manual start overrides.

**Concurrency:** `select_lock` serializes every selection read-modify-write
(merge quantity bumps were losing physical cards); sweep start is an atomic
claim under `sweep_lock`; ALL f2f writes go through `_write_price_if_current`
(stale printing/foil results must never overwrite fresher ones). No awaits
while holding a lock.

**UI rendering:** both pages diff by signature before touching the DOM —
every field a row renders MUST be in its signature or edits go stale.
`/api/scans` returns newest-first. HTML is served `Cache-Control: no-store`.

## Release / distribution

- `git tag vX.Y.Z && git push --tags` → 4 binaries (workflow needs
  `permissions: contents: write`; `macos-13` label is DEAD, use
  `macos-15-intel`; publish runs `if: always()`; PyInstaller needs
  `--paths . --copy-metadata mtg-card-scanner`).
- Repo is PRIVATE by user choice — distribution is hand-out-files (option 3).
  The in-app update banner needs a public repo to detect.
- The ManaExchange store is the user's own project (`DarylNo/v0-ManaExchange`);
  an MX-inventory integration was built and REVERTED — ask before rebuilding.

## Testing

`pytest tests/` — ~290 tests, all fakes (no camera/network needed), runs on
3-OS CI per push. Real-scan validation artifacts live in `scan_images/`
(e.g. scan 837 = the Diabolic Edict OCR proof). When tuning detection or
ranking, test against real scans before shipping — every threshold in this
repo was set by measurement, and several "obvious improvements" (geometry
gates, hi-res region hashes) failed empirically first.
