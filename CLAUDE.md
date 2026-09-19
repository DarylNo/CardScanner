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
                              popularity.py     EDHREC rank → tier (no extra fetch)
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

**Scryfall search is PAGED at 175** (`scryfall._search_all`): every
`/cards/search` caller must follow `has_more`/`next_page`. Reading page 1
only truncated heavily-reprinted cards to their OLDEST 175 printings — a
Forest has 865 paper printings, so no modern basic land could be ranked
*or* found by manual search. `visual_match._cap_candidates` still bounds
ranking work at 120 (oldest 60 + newest 60); that cap is deliberate.

**pHash limits — measured, don't retry:** 256-bit region hashes and
set-symbol template matching are pure noise for same-art same-frame reprints
(photo-vs-CDN noise floor ~20 bits/64). That's what `ocr_id.py` is for:
RapidOCR (pip-only, never system tesseract) reads the bottom strip;
confusion-tolerant (I≈1, S≈5…) UNIQUE set-code match; compound collectors
("A25-85") win outright so List copies don't misattribute; ambiguity = no-op.
An OCR promotion also requires ART AGREEMENT (within _OCR_ART_SLACK of the
best candidate) — a misread code can never promote or auto-pick a printing
that doesn't look like the scan.

**Auto-pick grounds** (server, scan time): exactly one printing exists, OR
the top candidate is OCR-confirmed — both file NM/Non-Foil/×1 with the
`auto_picked` flag (⚠ in the UIs) and auto-merge duplicates into quantity.
Unconfirmed multi-candidate scans always wait for a human. The auto-sweep
tick also runs retro passes: strip stale art-series candidates, retro-OCR
pending scans from their stored photos (once each, budgeted), auto-pick
newly-single/confirmed ones.

**Popularity (`popularity.py`) costs NOTHING — keep it that way.** Every card
object `/cards/search` returns already carries `edhrec_rank`, and
`get_all_printings` already fetches them, so the tier is pure projection: no
extra request, no added latency, nothing off the F2F rate budget. Do not add an
EDHREC API client to "improve" it without asking. Three measured rules:
- **No rank ≠ unpopular.** EDHREC ranks only Commander-legal cards, so Black
  Lotus, Forest and Wastes all come back `None`. They are UNRANKED with the
  reason shown — never bucketed as "fringe".
- **Reprint count is NOT popularity.** The plausible "WotC reprints what sells"
  idea dies on the data: Grizzly Bears has 25 printings at rank 8,181, Ragavan
  12 at rank 280. `print_count` is displayed as its own fact and never moves a
  tier.
- **Read the rank off the PRINTING, not the name.** One name can span two oracle
  cards — `!"Lightning Bolt"` is 68 printings at rank 158 plus 3 of the SOS
  `prepare` card at 7,965. A min/mode across the name attributes the wrong
  card's popularity. `reversible_card` printings (SLD Sol Ring, REX Forest)
  carry `oracle_id` only on `card_faces`, same as their images — hence
  `oracle_key()`.
Tiers are percentiles of the ranked pool (32,296 cards), not raw ranks, so they
survive the pool growing. The selection carries its own copy: after a re-pick
`candidates[0]` is not necessarily what was chosen.

**Formats beyond Commander — what exists and what doesn't.** Scryfall ships NO
play-rate ranking for Modern/Standard/Pioneer/Legacy/Pauper, and there is no
free API for one (measured 2026-09-19: MTGDecks and Moxfield both 403 a
non-browser client; MTGGoldfish and MTGTop8 are HTML only). Two things in the
same payload are worth having, and neither is popularity:
- **`legalities`** → `format_legality()` surfaces 7 paper formats that drive
  singles demand (the other 16 are digital-only or buylist-irrelevant).
  Legality is WHERE a card may be played, never mixed into a tier — and it
  explains an UNRANKED tier at a glance: Black Lotus reads "banned" in
  Commander, which is exactly why EDHREC has no rank for it.
- **`penny_rank`** is the only other play-rate number, and it stays secondary:
  it is absent on essentially every card worth money (Penny Dreadful's pool is
  DEFINED as cards under a tix on MTGO — Bolt, Thoughtseize, Sol Ring, Ragavan
  and Black Lotus are all `None`; of 8 live cards probed only bulk Shivan
  Dragon and Murder had one), and it goes stale against its own legality
  (Counterspell reports rank 8 while `legalities.penny` reads `not_legal`).
  It is shown ONLY when the printing is currently penny-legal. Don't promote it.
The two live-data dead ends, so nobody retries them: **MTGO tix price is not a
play-rate proxy** (it tracks demand against MTGO supply — Smothering Tithe
$55.81/0.02 tix separates Commander-chase from constructed beautifully, then
Lightning Bolt lands at 0.02 tix and Black Lotus at 61.08), and the open
tournament dataset everyone cites, `Badaro/MTGODecklistCache`, **shut down in
June 2025** (live successors: `fbettega/MTG_decklistcache`, and
`davidfischer/modometa-mtgo-data` whose names are already Scryfall-normalized).

**F2F client (`facetoface.py`) — each scanner prices from ITS OWN IP.** No
proxy, no shared backend: the client reads the storefront's public Shopify
JSON (`/search/suggest.json` → `/products/<handle>.json`) directly. A brief
2026-08 detour routed pricing through the ManaExchange backend; it was
reverted 2026-08-15 — there is no reason to put every scanner's pricing
load on the store, and the rate limit is per-IP anyway, so clients spread
naturally. **Do not reintroduce the proxy without asking.** What makes it
work, all measured — don't loosen any of it:
- **curl_cffi Chrome TLS impersonation** (`_new_session`). Shopify
  fingerprints the TLS handshake, not just the UA: plain `requests` draws a
  tiny bucket (429 by the 3rd rapid call), a real Chrome handshake gets the
  generous one. Falls back to `requests` if curl_cffi is missing.
- **Browser UA**, never an honest bot UA (identified bots are throttled hard).
- **Adaptive pacing** (slow start 2s → AIMD, floor 0.5s, ceil 10s, idle
  reset 120s). Fixed rates all failed: 6.7/s tripped the bucket instantly,
  2/s kept it tripped, 1/s still saw scattered 429s on a warm IP.
- **Most-specific-first query ladder** ("name collector setname [foil]"),
  so the usual card costs 1 suggest + 1 product fetch.
- **SKU set-code confirmation** before trusting a match — collector numbers
  collide across sets, and a wrong-set price is worse than no price.
- 24h disk cache, interruptible waits (Stop), request debug log.
`F2FUnavailableError` ≠ "no listing" — transport failure raises (stays
retryable), a confirmed miss returns None (records an empty marker).

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
- **The repo MUST stay PUBLIC — every distribution path is anonymous.**
  Found private on 2026-08-14: `install.sh`'s curl one-liner, the update
  tarball, and `releases/latest` all 404'd, so `/api/update-check` swallowed
  the error and reported `available: null` — no banner ever appeared for
  anyone, however many releases were tagged, and the Update button's
  `subprocess.call` ignored its 404 and claimed success. Tagging releases
  does nothing while the repo is private. Verify with an UNAUTHENTICATED
  request (a `git push` succeeding proves nothing — 404, not 403, is what
  GitHub returns anonymously for a private repo).
- **ALWAYS end a push session by bumping `version` in
  pyproject.toml and tagging a release** — script installs (uv/pipx)
  identify as the package version and their update banner compares against
  the latest RELEASE tag; master-only commits are invisible to them.
  Bump BEFORE tagging: installs come from master, so an install made in the
  gap between bump and tag reports a version AHEAD of the latest release.
  `_is_newer_release` compares version tuples so that only means "no update"
  — it used to be string inequality, which nagged forever and offered a
  downgrade.
- The ManaExchange store is the user's own project (`DarylNo/v0-ManaExchange`);
  an MX-inventory integration was built and REVERTED — ask before rebuilding.

## Testing

`pytest tests/` — ~350 tests, all fakes (no camera/network needed), runs on
3-OS CI per push. Real-scan validation artifacts live in `scan_images/`
(e.g. scan 837 = the Diabolic Edict OCR proof). When tuning detection or
ranking, test against real scans before shipping — every threshold in this
repo was set by measurement, and several "obvious improvements" (geometry
gates, hi-res region hashes) failed empirically first.
