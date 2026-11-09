# CardScanner — Claude Code Guide

## What this is

A hands-free MTG card scanner that runs on **one Android phone**, mounted over
a tray. The app (`android/`) captures each card, identifies it ON the phone by
**perceptual-hash artwork matching** (no LLM, no cloud vision), confirms the
exact printing by **OCR of the card's collector line**, prices it against
Face to Face Games from the phone's own connection, keeps the scans in its own
SQLite store, and serves the review pages — to its own review screen and to an
optional paired computer's browser. Releases ship the APK only.

The **Python code** (`server/`, `mtg_card_scanner/`) is the **reference
implementation**, not a product: every Kotlin port is golden/differentially
tested against it. **Change behaviour in the Python reference first** (and its
fixture exporter), then port — never re-tune in Kotlin. The review pages
`server/static/phone.html` / `desktop.html` are the ONE review UI: the Python
server serves them for tests, the phone serves the same files (copied into the
APK at build).

## Layout

```
android/core/        pure Kotlin (JVM-testable): AutoScanner/Detection (the
                     trigger), CardQuad/Flatten, ScanPhoto, CardOutline +
                     OutlineTracker (the live outline / card-shape gate),
                     TextureChange (shadow mode), CameraChoice (the Camera
                     setting), FocusPoint (where AF meters), ZoomFit +
                     ZoomCoordinator + ZoomSnapshot ("Zoom to fit the Area")
                     + ZoomProbe/ZoomProbeRun/ZoomDetail (its probe),
                     PHash/ArtHasher/ArtMatcher (bit-exact
                     fingerprint), IdentifyPipeline, PrintingRanker,
                     OcrMatch, ListTwins, Popularity, CardList (the external
                     list), PrintEvidence (the printed-detail fallback),
                     RefocusPolicy + CardFocusState (card focus), ScreenDim,
                     DebugLog, and core/server/ — ScanStore, PhoneApi (the /api
                     surface), PriceSweep/PriceWorker, Export, DeviceApi,
                     DebugApi, ScanServer (routing + admin-only rules)
android/app/         Android: MainActivity (package root = the scan screen),
                     camera/ (CameraX, CameraController, FrameRing, ScanAnalyzer,
                     FocusLog + FrameMetaRing, CameraCatalog, ZoomProbeGlue +
                     ProbeMeasure), capture/, ident/ (ArtPackStore,
                     PhoneIdentifier, LocalIdentify, ScryfallHttp), ocr/ (ML Kit:
                     MlKitOcrEngine), f2f/ (F2fPricer, F2fFetcher, Cronet /
                     OkHttp transports), net/UploadQueue, phoneserver/
                     (PhoneServer, PhoneBackend, LocalScanUploader,
                     SqliteScanStore, PhoneF2f, ExternalCardList, DeviceBridge),
                     gateway/ (GatewayServer, GatewayService, AdminPairing,
                     JoinCode), update/UpdateLock, ui/ (PanelActivity = review
                     WebView, SettingsActivity, ShareActivity,
                     DiagnosticsActivity, OverlayView, AppSettings,
                     MeasureSession, ScreenAwake)
server/              reference FastAPI app, store.py, export.py, static pages
mtg_card_scanner/    reference pipeline: pipeline (scan_candidates,
                     _ranked_candidates, _mark_art_decisive), card_detect,
                     art_index, art_pack, scryfall, visual_match, ocr_id,
                     list_twins, artwork, popularity, facetoface, card_list,
                     change_signal, launch
scripts/             export_*_fixture(s).py (generate the Kotlin fixtures from the
                     reference; all 12 `--check`ed in CI), check_*_ui.py (drive
                     the real pages in headless Chromium — NOT in CI, run by
                     hand), score_card_edges / score_card_align (measurement),
                     make_android_keystore.py
tests/               pytest (642 at 1.1.19, all fakes — no camera/network)
.github/workflows/   ci (pytest 3-OS, fixture --checks, android), release,
                     auto-tag, art-pack (weekly card database), pages
```

## Build / test

```bash
# Android (from android/; JDK 17, Node on PATH for the detection differential test)
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# Reference + fixtures
pip install -e ".[test]"
pytest tests/ -q
python scripts/export_api_fixtures.py --check     # and every other scripts/export_*_fixture*.py
                                                  # (12, incl. export_pack_fixture.py and
                                                  # export_f2f_pacing_fixture.py — the list ci.yml runs)

# The review pages — NOT in CI; run every one after ANY phone.html / desktop.html edit
pip install playwright                            # Chromium is preinstalled in cloud sessions
for s in check_row_delete_ui check_compare_ui check_card_list_ui check_device_ui check_price_filter_ui; do
  python scripts/$s.py; done                      # 219 / 92 / 44 / 135 / 16 checks at 1.1.19
```

`pytest tests/` includes `tests/test_static_pages.py`, which pins page code
(the compare block byte-identical in both pages, the Wanted exemption, the
delete / Undo / refusal guards): an edit to one page alone fails CI.

Run the reference server locally with `mtg-card-scanner --no-browser` (a
supervisor, `mtg_card_scanner/launch.py`, serving HTTPS on :8443) — for
reproducing reference behaviour only.

**Bump `UI_VERSION`** in `phone.html` / `desktop.html` on every edit to them
(desktop.html's banner reads `d<N> · <version>`, phone.html's `v<N> · <version>`;
the phone's server adds " (phone)" to the version — d59 / v60 at 1.1.19; the
`scripts/check_*_ui.py` drivers pin the current value, so update them with
every bump; stale-page debugging burned a full session before the banners
existed).

## Pipeline

```
scan screen (Tray trigger — refused with no card outline and no printed detail — or shutter tap)
  → CapturePipeline: sharpest of the last 3 frames → CardQuad → Flatten WITH A
    MARGIN (+ the 3 raw ROI crops as fallbacks)
  → UploadQueue (persistent, retried) → LocalScanUploader = the phone's POST /api/scan
  → PhoneIdentifier / IdentifyPipeline:
      re-detect → art pHash → nearest of ~50k artworks (the art pack) → NAME
      → Scryfall: all PAPER printings → PrintingRanker (art×4 + title + textbox)
      → collector-line OCR (ML Kit) → exact printing to #1 → auto-pick?
  → ScanPhoto: the sharpest frame's card straightened at 95% of a 664×926
    photo (no edges found → that frame exactly as uploaded)
  → PhoneApi.fileScan (store row + photo) → PriceSweep prices it
  → review: PanelActivity (phone.html) / paired computer (desktop.html)
```

## Invariants — learned the hard way, do not regress

**Detection:** the Tray trigger is *occupancy + stillness only* (mask
coverage >2%, 2 steady samples, exposure-drift-cancelled diff) — phone.html's
detection, ported to `AutoScanner`. Three separate attempts at card-shaped
geometry gates on the DIFF MASK (ratio/density/texture windows) each rejected
real sleeved cards. The identification pipeline judges card content
(texture-gated quads, blank-surface guard), never the trigger. Since 1.1.7
the app refuses a Tray trigger when the live outline (`CardQuad.find` on the
detection sample — found edges, not the mask) sees no card shape AND (since
1.1.14) the trigger box shows no printed detail (`PrintEvidence`), and asks
again on the next steady ticks (the card-shape gate and its printed-detail
fallback, below). The scan Area
(ROI) crops sampling and capture.

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
confusion-tolerant (I≈1, S≈5…) UNIQUE set-code match, the code a WHOLE
WORD (+ a glued language tag, "MH1FN") — never across words (an AVR
Emancipation Angel, no code printed, forged "UMA" that way) nor the start of
one (artists MSCHF/Milivoj read as MSC…/M11…); scored on ~11k generated
strips: wrong confirmations 142 → 9. The collector number is NOT required
(the rig read 087/254 as "0017314" but MH1 cleanly); compound collectors
("2019-2", "IFIYW-7") win outright; ambiguity = no-op.
An OCR promotion also requires ART AGREEMENT (within _OCR_ART_SLACK of the
best candidate) — a misread code can never promote or auto-pick a printing
that doesn't look like the scan.
**The List (plst) — the collector line CANNOT tell a List card from its
original (1.1.17, owner: "trouble with list cards"; measured on 19 Scryfall
scans across every frame + RapidOCR):** a List card prints EXACTLY its
original's line ("141/249 U · A25 • EN") plus a small planeswalker icon at the
far left — never "PLST", never the compound. The old rule (a compound
"A25-141" wins) rested on the opposite premise, so every 2015-frame List card
(67 % of plst) was OCR-confirmed and AUTO-PICKED as its original, and an
original #1 whose "•" read as "1" ("ECL 1EN") as its List copy. Now
(`list_twins.py` / `ListTwins.kt`): a plst printing is the twin of the
printing whose `SET.upper()-number` it carries (exact strings; "†" / "j"
suffixes; 5,739 of 5,741 pair); step 1 skips List compounds; ANY hit with a
twin among the candidates → None; `_ranked_candidates` inserts a twin from
the FULL ranking right after its partner (a 12-slice left Sol Ring's at #17);
`_mark_art_decisive` never picks one of a tied pair. Scored (generated strips,
every printing a candidate): List cards 0 right / 194 WRONG → 0 / 0; the cost,
originals that have a twin, 187 right → 0 (they wait for a pick: ~6 % of paper
printings, 20–35 % of 2015–2019 sets). Getting those back needs the ICON —
a tight-window match separated it on synthetic photos only; the old
List-corner pHash (`visual_match._list_corner_decision`) never runs in
production and measured 21/60 — measure on rig photos before building it.

**Auto-pick grounds** (server, scan time): exactly one printing exists, OR
the top candidate is OCR-confirmed — both file NM/Non-Foil/×1 with the
`auto_picked` flag (⚠ in the UIs). **Every scan keeps its OWN row** — a
repeat copy is never merged into another row's quantity (user picks, scan-time
and retro auto-picks alike), because the owner finds cards by scan order; the
Mana Exchange export (`server/export.py`) sums identical
printing+condition+finish rows into one line, in first-seen order.
The desktop "Export to CSV" builder (`build_csv`) lays columns out per the
owner's saved layout (`export_layout.json` beside the DB); its default also
sums identical printings, "one per scan" is the opt-in. The preview comes
from the same server code as the download, never a client re-render.
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

**External card list (`card_list.py`, owner 2026-10-12) — generic, READ-ONLY.**
Not the reverted MX-inventory integration: no store branding, nothing written
back, one plain GET of a URL the owner pastes (Settings → External card list,
`AppSettings.cardListUrl`; the reference server reads `CARD_LIST_URL`). The URL
lives ONLY in settings (the repo is public), any key in its query string; it is
never logged, never in `status()`, and Settings shows its host only. Empty =
off: nothing fetched, NO `list_tags` key on any scan (the pages read "no key" as
"feature off" and hide the chips and "Tagged only"). Contract and setup:
README → External card list. Rules that must hold:
- **Match by NAME, any printing:** `normalize_name` (NFD minus Mn marks, curly
  quotes → straight, en/em dash → "-", lowercase, " // " between faces,
  whitespace collapsed — the whitespace set is SPELLED OUT, not `\s`, so the
  Kotlin port matches) on both sides; full name OR any face, both sides
  (`name_keys`). The scan's name: its pick, else its top candidate, else the read.
- **Evaluated at READ time** (`flag_list_tags` beside `flag_other_art` on
  `/api/scans` + `/api/scans/{id}`), never stored — a refreshed list re-tags
  every existing scan. Chips (Wanted / Sold out / any other tag as raw text) sit
  right after the popularity chip; yes/no only, never mixed into a tier.
- **Never block scanning, never lose the list:** fetched at app start and on
  "Refresh now" on its own low-priority thread; the last GOOD list is cached on
  disk (keyed by URL — another URL's cache is not this list); offline / HTTP
  error / bad JSON keep the cache and record the error ("unreachable", "HTTP n",
  "not JSON", "no cards list"), shown in Settings and Diagnostics. https only
  (the network security config allows cleartext to loopback only).
- **"Tagged only" is a VIEW chip, not a filter vote** — it narrows the list like
  All / Pending (a "check" row still shows), so `passesFilters` and "Exclude
  filtered" never drop an untagged card from the export. Persisted in
  `listFilters.tagged`.
- **A Wanted card ALWAYS shows (owner, 2026-10-17: "I need to have wanted cards
  always show"; phone v54, desktop d47).** `isWanted` (the `wanted` tag, exact,
  as the chip reads it) is exempt in `passesFilters` exactly like a "check"
  row: neither bar hides it, in ANY or ALL, so "Exclude filtered" never drops
  it from the export. The view chips (All / Needs pick / …), "Tagged only" and
  the name search still narrow it like any row — they are where you LOOK, the
  bars are what gets thrown away. Other tags (`sold_out`, raw text) get no
  exemption. `check_card_list_ui.py` drives it on both pages;
  `test_static_pages.py` guards it in CI.
Golden: `scripts/export_card_list_fixtures.py` (normalize/keys/parse/tags, a
`CardListSource` session with a pinned clock, and the REAL app.py's reads) →
`CardListParityTest`; `check_card_list_ui.py` drives both pages.

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
  collide across sets, and a wrong-set price is worse than no price. F2F's
  SKU codes are not always Scryfall's (1.1.17, measured on live products):
  plst = MB1 / FMB1 / PLIST / PLST by release wave (titles "Name [A25-141]
  [The List] [Non-Foil]"), ulst = UPLIST, cmb2 = CMB1 + NOPW (and cmb1 refuses
  NOPW) — before, every List card priced None or ANOTHER card's price.
- **Titles:** the collector is the first bracket BEFORE the set name that
  looks like one (`_COLLECTOR_RE`) — a third of titles carry variant brackets
  ("[401] [Rulebook Showcase] [Set]"), which brackets[-3] read as the collector,
  and some put a label first ("[Prerelease] [59s]", "[Autumn] [80c]": read from
  brackets[0] only, 1.1.17 took whichever version F2F listed first); zero
  padding is ignored per hyphen segment (PZ2-038 = PZ2-38). **Meld halves**
  (1.1.18, owner: "price checks failing — Midnight Scavengers EMN 96, Graf Rats
  EMN 91"): F2F titles them "Midnight Scavengers // Chittering Host [96a] [B]
  [Eldritch Moon]" — a side letter Scryfall's "96" lacks — so 1.1.17 priced
  every EMN half and BRO 163/238 None (1.1.16 found them by luck through its
  name-blind fallback). Only when no title carries the exact collector, ONE
  title may match whose collector is the card's + "a" (every measured half;
  a "[225s]" prerelease is a promo, not a half) and whose name part is "<this
  card> // <another face>" (the card single-faced); two such titles → none;
  the SKU still confirms the set. Measured live on 86 printings (meld,
  transform, MDFC, split, adventure, flip, promos, Secret Lair, variants, old
  sets, UB, controls), old vs new on the same answers, plus every answer
  reversed: 1.1.16 76 right / 5 wrong / 8 order-dependent, 1.1.17 71 / 0 / 6,
  now 80 / 0 / 0 (the 6 still None were None before too: Chittering Host has
  no product; Etched / Halo / Surge Foil labels; PNEO 266p's SKU says NEO).
  The set-name fallback takes only
  collector-LESS titles of the card's own name: the title's name part EQUALS
  the front face (accents folded), whole or cut at a title-side suffix (" //",
  " -", " —", " (") — name-blind, it filed Buried Alive's $4.99 for a
  Mortuary; with "starts with", Fireball's for a Fire // Ice. Golden-tested:
  `scripts/export_f2f_match_fixtures.py` (function tables + recorded get_price
  sessions over `tests/fixtures/facetoface/captured/`) → `F2fMatchParityTest`.
- 24h disk cache (raw bodies, never match results), interruptible waits
  (Stop), request debug log.
`F2FUnavailableError` ≠ "no listing" — transport failure raises (stays
retryable), a confirmed miss returns None (records an empty marker).
On the phone the same client is `F2fPricer` over `F2fFetcher` on **Cronet**
(Chrome's own TLS — it drew the generous bucket from the N200, 0×429), with
the pacing table golden-tested against facetoface.py.

**Pricing sweep (`server/app.py`):** ONE F2F consumer while active (all other
pricers stand down); selected scans price ONLY their selection; unpicked
scans price EVERY candidate print that shares the scanned ARTWORK (the price
filter may hide a pending card only on full knowledge). A different artwork
past a clean distance break (`artwork.py`: best ≤140, neighbour jump ≥40 —
Fling's same-art Δ78–84 vs Δ146+, while Bone Splinters' wide same-art
Δ124–190 splits nothing) is never the card: `other_art` is derived on every
`/api/scans` read, never stored; the sweep skips it; both pages keep it out
of the range, the filter and "searched", and the picker folds it; targets are rechecked before each fetch (picks land
mid-sweep); circuit breaker after 5 consecutive unavailable → 10-min
cooldown; manual start overrides. On the phone `PriceSweep` is the port
(golden-tested) and `PriceWorker` drives it — not a 60 s timed sweep but
"if something is owed, price it": a check every 5 s while anything is owed,
asleep until the next scan/pick/edit when nothing is (owner). Every filed
scan is price-checked at once (it jumps the queue). A confirmed miss is never
re-asked, so a matcher fix reaches old scans only through a store migration:
1.1.17's (`PRAGMA user_version` 0 → 1, both stores, once, in one transaction)
drops EVERY stored F2F answer and the sweep prices them all again (owner,
2026-11-04, "every stored price": the old fallback was collector- and
name-blind in every set and variant-bracket titles never matched their own
collector, so any stored price may be another card's or printing's — a
one-time catch-up from the phone's own connection at the usual pacing; new
scans still jump the queue). 1.1.18's v2 (1 → 2, its own transaction and
stamp; a v0 store runs v1, commits, then v2) re-asks what 1.1.17's matcher may
have got wrong: every EMPTY answer ("no F2F listing" — the meld halves) and
every answer of a printing whose collector ends in an ASCII letter (59s, 38a,
80c, 266P — picked by F2F's listing order); every other price is kept.

**Concurrency:** `select_lock` serializes every selection read-modify-write —
picks vs PATCH edits vs retro auto-picks (born when repeat copies merged into
a quantity and concurrent bumps lost physical cards; merging is gone, those
races are not); sweep start is an atomic
claim under `sweep_lock`; ALL f2f writes go through `_write_price_if_current`
(stale printing/foil results must never overwrite fresher ones). No awaits
while holding a lock.

**UI rendering:** both pages diff by signature before touching the DOM —
every field a row renders MUST be in its signature or edits go stale.
`/api/scans` returns newest-first. HTML is served `Cache-Control: no-store`.

**List filters (both pages):** two bars — a price band and a popularity floor —
combined by a match ANY/ALL toggle, ANY being the default. Each bar votes
**TRI-STATE**: pass, fail, or NULL = not known yet (unpriced, prints not all
searched, or a scan carrying no popularity because it predates the feature).
That third state is load-bearing — collapsing it to "pass" is the bug that made
ALL barely differ from ANY (an unpriced card "satisfied" a Min $ it had never
been measured against). Rules that must hold:
- **A "check" row is never filtered away**, by any bar, in either mode — nor
  is a card the external list tags Wanted (above, External card list).
- **ANY (the default) never hides on ignorance.** Show on any pass; with no pass
  but something still NULL, show anyway. Hide only when every active bar
  actively fails. This is what keeps an unpriced Black Lotus on screen.
- **ALL is the explicit opt-out.** Every active bar must be affirmatively
  satisfied, so NULL does not count and anything unpriced or without popularity
  is hidden. The `#fhint` line says so whenever ALL is on.
- **`unranked` is an ANSWER, not a NULL.** It sorts at the BOTTOM of the tier
  order (basic lands and Commander-banned cards report it), so a popularity
  floor ALONE hides Black Lotus along with the bulk — the price floor ORed
  beside it is the rescue, and `#fhint` warns in that configuration too.
- **An unpicked card is judged on the price its PICK would show** (1.1.16,
  owner: a card hidden by Min $0.99 showed the moment its printing was picked):
  each print counts at `condPrice(f2f_conditions, pickCond(s))` — the condition
  read off the card, else NM, in `f2fBest`'s order — never the cheapest listed
  condition (a $0.95 Played copy of a $1.20 NM card). Row range and filter
  alike; `scripts/check_price_filter_ui.py` drives both pages.
- **A price bar may hide an unpicked card only when EVERY print of the scanned
  artwork has a price** (owner, 2026-10-29: "to hide it should have all the
  prices available for the print of that art"): searched is not enough — a print
  F2F has no listing for is an unknown price, so it can keep the card (NULL)
  but never hide it (`printInfo().priced`). Any print in band still passes.
- **"Exclude filtered" still requires a known price** even when only the
  popularity bar is on — never drop a card from the export on a price that was
  never looked up.
The bars, the mode and the view chip persist per viewer in localStorage
(`listFilters`, owner 2026-09-30: the app's review screen reloads the page
every time it opens); the name search box is not kept.
Verified by driving the real page in headless Chromium against stubbed
`/api/scans` fixtures — not by reasoning about the predicates, which is how the
ALL bug survived review in the first place.


## The Android app — rules

Build, sideload and the device checklist: `android/README.md`. Target device:
OnePlus Nord N200 5G (camera id 0 by default; Settings → Camera picks another
lens on testers' phones), minSdk 29, arm64-v8a.

- **Detection is a PORT, proven — never re-tuned in Kotlin.**
  `DetectionDifferentialTest` runs phone.html's REAL detection code under Node
  (`core/src/test/resources/phone_harness.js`) against the Kotlin
  `Detection`/`AutoScanner`. Change detection in phone.html first, then port.
- **The scan photo is the reference's** (`card_detect.scan_photo` → core
  `ScanPhoto`, parity-tested on the detect fixtures): the sharpest frame's
  card straightened so it fills 95% of a 664×926 photo — a deliberate
  buffer of tray around it (owner, 2026-10-01: "tight on the card plus some
  buffer — make it 95%"); no card edges → that frame exactly as uploaded
  ("keep photo"). Photos already filed are never re-cropped. Before 1.1.3
  the phone filed the upload itself (card ≈ 64–75% of a flattened upload,
  ≈ 30% of a raw one). The retro-OCR pass reads the card out of the photo
  (`photo_card`), so the strip bounds stay in card space.
- **Flatten contract:** `tests/phone_flatten_ref.py` is the executable spec
  (margins 0.08/0.06/0.04, Python banker's rounding); the parity fixtures in
  `android/core/src/test/resources/detect/` come from the reference's own code
  via `scripts/export_detect_fixtures.py`. Regenerate them, never hand-edit.
- **Art fingerprint is a bit-exact PORT** (`PHash`/`ArtHasher`/`ArtMatcher`):
  Pillow's fixed-point L + LANCZOS and pocketfft's DCT-II are transliterated,
  not re-derived — a textbook DCT or fdlibm twiddles moved values by ~1e-11 and
  flipped `> median`. `HashParityTest` checks every stage against
  `resources/arthash/expected.json` (`scripts/export_hash_fixtures.py`). Never
  loosen it to "close enough": the 110/140+20/210 thresholds only transfer on
  equal bits. **Pillow is pinned < 13** (2026-10-17): Pillow 13.0.0 moved the
  8-bit LANCZOS output for sources TALLER than wide (41 of the 168 resize-sweep
  digests), so CI's hash `--check` went red the day it shipped. Every art crop
  is wider than tall — 160 real crops (the arthash cards × 8 scales) hashed
  bit-identically — but the port follows Pillow 12 and the weekly art pack is
  built with whatever `pip install .` resolves, so the pin keeps the pack, CI
  and the phone on one LANCZOS. Lift it only by porting 13's change and
  regenerating the fixtures.
- **Golden-tested server:** `scripts/export_api_fixtures.py` drives the REAL
  app.py through scripted sessions (`api/expected.json`, `sweep.json` — every
  body AND the exact F2F lookup sequence, `export.json` — every status,
  header and the TXT/CSV text byte for byte); `PhoneApiParityTest` and the
  app's SQLite replay must match every step. The phone's store is
  server/store.py's table verbatim (migrations on both).
- **The card database** is the art pack: `art-pack.yml` rebuilds it weekly and
  publishes `art-pack.bin.gz` + `art-pack.json` to the rolling prerelease tag
  `art-pack` (it pushes the tag with git first — `gh release create --target`
  is refused 403 for the GITHUB_TOKEN). `ArtPackStore` fetches it at first
  launch on any network, then weekly on Wi-Fi; a queued scan with no pack
  re-fetches it at most once a minute (`LocalIdentify.ensurePack`).
- **The upload queue files into the phone.** `UploadQueue` (persistent, one
  worker, backoff) → `LocalScanUploader`: identify, then `PhoneApi.fileScan`.
  no_card / not identified → the 3 raw frames with `replace_scan_id` (the
  reference's multi-frame retry). No phone-side card gates. Idempotent per
  upload id (the answer is saved right after filing; a failed save never
  throws — that would file the card twice); the id is a per-job random nonce
  (a job counter restarts each launch and handed a fresh scan an OLD card's
  reply). No pack / Scryfall unreachable (`ScryfallUnreachableException`) →
  IOException, the job waits and retries; a Scryfall 404 is an answer; an
  undecodable capture → 422, given up.
- **Two modes (owner): Tray and Tap to scan** (stored as `AppSettings.auto`;
  `/api/device` `mode: "tray" | "tap"`; a fresh install starts in Tap to
  scan). Tray = hands-free, double-tap re-learns the empty tray; switching to
  Tray on the phone asks for a box around where the card will sit (Cancel
  keeps the current Area). Tap to scan = the shutter, and that scan OPENS
  once identified (`/phone?panel=1&detail=<id>`: the page prices it at once,
  prices land live, Back returns to the camera; no Keep/Discard — it's filed
  like any scan). **Picking a printing closes the detail onto the scan
  list** (owner, 2026-10-01) with a "Picked …" status, in every flow;
  condition/finish/quantity edits stay on the card.
- **Compare block on the card (phone.html, owner 2026-10-02):** the scan
  photo beside the compared printing (default), or overlaid — *Wipe* (a
  divider on the slider: crisp pixels of each side, how a different frame /
  set symbol / collector line shows) or *Blend* (the top layer's opacity),
  with either image as the base layer; ‹ › or a horizontal swipe on the
  pictures (≥50 px, mostly sideways) step through the candidates without
  picking, "Pick SET #n" picks the compared one. Mode and base layer
  persist in localStorage (`cmpMode`, `cmpTop`).
  **Just the card — cropped to the card's OWN edges, on BOTH pages (owner,
  2026-10-03: "a tight look on the phone and desktop … almost the same display
  on both", then "can we just make it crop the card directly to the edge of
  the card?"; phone v51, desktop d44).** A photo with a KNOWN layout has its
  card near a known rect (`PHOTO_LAYOUT`): 664×926 ScanPhoto → x 17..647, y
  23..903; a flattened upload filed as it came (914×1276 / 882×1232 /
  852×1188 — before 1.1.3, or no edges re-found since) → the 788×1100 card in
  the 8 / 6 / 4 % margin of `tests/phone_flatten_ref.py`. But the phone's
  finder outlines a card 0.3–2.5 mm OUTSIDE its true edge (the 5×5 dilation,
  blur, a sleeve) — or on a black border's INNER edge (black card, dark tray)
  — so a fixed zoom left tray on 78 % of sides and cut 22 % (up to 2.5 mm).
  The page now finds the card's edges in the photo itself (`findCardEdges`,
  tray-vs-card segmentation per side, central 56 % of each side for the
  rounded corners), ONCE per photo (`cardCrop`, cached by URL), and maps them
  exactly onto the 5:7 box (`cardCropTransform`: translate + per-axis scale —
  a crop with one side kept loose is stretched a few %, never cut on the
  others); boxes have card-shaped corners (`border-radius: 5% / 3.6%`).
  Measured by `scripts/score_card_edges.py` (the reference chain's own photos,
  the true card known, the page's own code in Chromium; chosen from four
  designs on a held-out set): tray > 0.25 mm on 3.2 % of sides (was 78.2 %),
  cut > 0.5 mm on 0 % (was 21.8 %), mean error 0.09 mm (0.98); held-out 5.6 %
  / 0 % / 0.14 mm. It is built to go LOOSE (tray shows, never a cut) whenever
  the ring around the card could be the card's own border: a dark tray round
  a white card, a black mat, a sleeve the colour of the border, textured
  trays. The hard case is a WHITE border with a DARK frame on a white tray:
  the finder locks onto the frame, so even the fixed zoom cut into the white
  border on every side, and a black BORDER on a light tray looks the same up
  close — told apart per card (rule 6b: a black border is a 2–3.8 mm band all
  round, a frame is narrower at the sides). `--frames` measures it: cut
  > 0.5 mm on 6.6 % of sides (fixed zoom 100 %), but 36 of 576 sides still
  cut deeper than the fixed zoom did (worst +2.9 mm). Any
  other photo (a raw crop, an old plain warp) shows as it is; a click / tap
  still opens the WHOLE photo. The block is byte-identical in both pages
  (`tests/test_static_pages.py`, CI) — change it in both, then re-run the
  scorer and put the numbers in the commit. Synthetic photos only: not yet
  measured on real rig photos.
  **In a compare the scan is FITTED onto the compared printing's picture
  (owner, 2026-10-10, 1.1.11 — phone v52, desktop d45: "better cropped so I can
  do the blend to see exact diff"; on the rig a Flux Channeler showed tray down
  one side and the bottom).** The edge crop is only as good as the quad the photo
  was warped from: a quad a few mm too BIG puts the card off its layout rect and
  rule 7 keeps the layout rect (tray). `alignToPrint` (in the shared block) fits
  x0/y0/x1/y1 by normalised cross-correlation of luma GRADIENTS (white balance,
  lighting gradients and glare move colour, not frame lines), coarse to fine
  (36×50 → 72×100 → 144×200), a pattern search that moves one side, the whole
  box or its size, from the edge crop ±4 % / ±8 %; `cardAlign` runs it once per
  photo × printing after the printing loads (a second CORS read — Scryfall's CDN
  allows any origin; a tainted canvas just means no fit) and `fitScan` refits
  when it lands (`data-fill="aligned"`). Taken only when clear (`alignTaken`:
  score ≥ 0.6, ≥ the edge crop's own, 63:88 within 10 %) — else the edge crop.
  Measured by `scripts/score_card_align.py` (REAL Scryfall faces rendered
  rig-like: 3 trays, blur, lighting gradient + glare, finder quads ±0.5 mm or up
  to 4 mm too big): same printing mean 0.11 mm (edges 0.39), tray > 0.25 mm on
  1.9 % of sides (8.0 %), worst tray 0.70 mm where a fit was taken (6.6), fit on
  99.7 %; another printing of the same art (WAR vs CMM frame) 0.14 mm;
  ANOTHER card's picture fits 25 % of the time, never cutting > 0.54 mm (frames
  share their geometry). ~110 ms per fit in desktop Chromium (phone est. 4×,
  off the first paint). `check_compare_ui.py` scan 10 drives it on both pages.
  Not the thumbnail or the desktop's ⇆ lightbox (no printing beside them).
  **The desktop's "Pick the printing" is the same compare block (d44):** the
  same modes, ‹ › (and ← →), Scan size and "Pick / Keep SET #n"; its head
  is the compared printing (set · SET #n · ✓ picked ×q · k of N · its price)
  with its badges. The desktop keeps its own top area (F2F box, controls —
  the top thumbnail is just the card too) and the thumbnail grid below it
  (click = compare, double-click = pick); the ⇆ full-screen compare shows
  just the card. A re-render of the same scan keeps the compared printing
  and the scroll; a NEW pick from anywhere (the block, ⇆, a name search, an
  auto-pick) moves the compare to the pick, and a name-search pick (another
  card) goes back to the top. The scan photo `<img>` is KEPT and moved
  between renders (`placeScanImg`): the phone serves it no-store, so a
  rebuilt one re-downloaded it and showed black on every ‹ › step. `sigOf`
  carries the candidates' order and OCR marks only; prices landing on the
  printings refresh the compare head in place (`refreshCompare`), so a sweep
  never wipes a typed name search. A row click renders once (it used to
  blank `detailSig`, so the next poll rendered it again). The compare's
  storage reads are guarded (`lsGet`) — blocked site data must not kill the
  page.
  Verified by `scripts/check_compare_ui.py` (headless Chromium against a stub
  phone server: magenta-tray photos at the exact layout show 0.00 % tray;
  realistic blurred photos with the card INSIDE the layout land within
  0.06 mm of the true edge, measured geometrically on both pages).
  **The compare block IS the head of the card (owner, 2026-10-02: "get rid of
  the top area and rework this section now that I have the top print side by
  side", phone.html v47):** no photo row above it; its title shows the
  COMPARED printing — name, set name · SET #n, "✓ picked · ×q" when it is
  the pick, Δ, k/N — its price (the pick's = the scan's own `#p-headprice`,
  live-polled; another printing's = its sweep price, `data-price-for`, shown
  on a picked scan only when one exists), the scan error, and ITS "plays in"
  chips (`formatChipsHtml(scan, c.popularity)`). A scan with no candidates
  gets a bare head (name, error, the photo alone; a price only when picked).
  **"Scan size"** (owner: "size either card to match the size of the other",
  v48): a 70–130 % slider scales the SCAN layer around its centre in every
  mode (folded into the edge crop by `applyScanScale()`), the % button
  resets; persisted per viewer (`cmpScale`). Manual on purpose — the photo's
  card is at a known 95 % only when the quad was right (a sleeve edge makes
  it read larger).
  **The compare captions ("scan over print", "scan", "SET #n") sit UNDER the
  pictures, never on them** (owner, 2026-10-29: the bottom-left pill covered the
  card — exactly where the collector line is); `check_price_filter_ui.py` checks
  every caption is clear of every box, in all three modes, on both pages.
  Scan-screen Share is the standard share glyph (`drawable/ic_share`,
  `ScanChrome.iconChip`, named "Share" for TalkBack).
- **The Camera setting (owner, 2026-10-02: "thinking about when people are
  brought on to test. Different phones with different options"; 1.1.9).**
  `AppSettings.cameraId` (a Camera2 id; null = Automatic). Core `CameraChoice`
  decides: Automatic is the rule the app always had — camera "0" facing back,
  else the first back camera, else the first external (on the N200, id 3 is the
  2 MP fixed-focus module that must never be the default); a saved id the phone
  doesn't offer falls back to Automatic. FRONT cameras are never offered (the
  preview of a front camera is mirrored, its analysis frames are not, so a
  drawn Area would land on the wrong side). `camera/CameraCatalog` reads the
  lenses from Camera2 without opening one (BACKWARD_COMPATIBLE only — no depth
  / IR sensors), labelled "Camera 3 · back · 2.0 MP · fixed focus · 2.2 mm".
  `CameraController.setCamera` rebinds on a change (which re-learns the tray);
  the choice and the fallback are logged under `camera`, and Diagnostics shows
  the setting and every offered lens. Settings → Camera is a single-choice
  dialog; `/api/device` carries `camera` ("auto" | id) and `cameras`
  [{id, label}], PATCH refuses an id this phone doesn't offer, and the
  desktop 📱 Scanner panel has a Lens dropdown (d40; hidden for an older phone
  app with no `cameras`). **The camera pill** (owner: "a chooser at the top of
  the scan page where you see the live view … a pill that you swipe around"):
  top-right of the top bar, "‹  Auto · 13 MP  ›" / "‹  Cam 3 · 2.0 MP fixed  ›"
  (`CameraChoice.short`); a horizontal swipe ≥ `CAMERA_SWIPE_DP` (24 dp) steps
  left = next / right = previous through `CameraChoice.choices` (Automatic
  first, wrapping, `CameraChoice.step`), a tap steps forward; each step saves
  `cameraId` and rebinds at once. GONE when the phone offers ≤ 1 lens — the
  one deliberate exception to "no buttons in the top bar". Tested in code (CameraChoiceTest, CameraCatalogTest
  on Robolectric's camera manager, DeviceApiTest, the Settings chooser in
  ScreensSmokeTest, scripts/check_device_ui.py) — not on a second phone yet.
- Tap to scan always has an Area: with none set the app draws
  `HandheldGuide.defaultArea` (a centred card at 60% of the limiting side +
  15% pad — back from the card so the phone's shadow stays off it; a starting
  point, not measured). A drawn Area is never replaced; in Tap to scan the
  Area button draws, it never clears. Mount focus: locks on the Area centre,
  then on the first card's own centre (below); tap the preview to lock elsewhere.
- **Focus — "is it focusing?" (owner on 1.1.10: "No zoom? Focus working?";
  1.1.14; periodic refocus 1.1.18, below).** What the code does: every bind /
  camera re-open / new Area / zoom change locks AF (CameraX AUTO + one trigger, the lens then stays put) at the
  AREA CENTRE on the first frame — there is no card yet (the Tray ↔ Tap to scan
  switch, `CameraController.setAuto`, arms nothing); Tray then holds the
  FIRST card's capture (≤ `FOCUS_WAIT_NS` 1.5 s) for a pass metered ON THE
  CARD: the Trigger box's centre mapped out of the sample (`core/FocusPoint`,
  `FocusRequest` from the analyzer; before 1.1.14 that pass metered the Area
  centre too, and the card-box branch was dead code). A failed pass retries on
  the next card, 3 in all (`CardFocusState.TRIES`), then gives up until a tap /
  new Area / camera re-open / zoom change / the soft-capture watchdog, or
  (1.1.18, unless Refocus on a card is Never or paused by a tap) the next
  periodic refocus one interval after the give-up (a flattened Tray capture < 0.5 × the median of the
  last ≥ 5 re-arms the card pass — it never catches a session soft from the
  start). Taps lock where tapped. CameraX completes a pass that never
  converges NOT focused after 5 s (`FocusMeteringControl.AUTO_FOCUS_TIMEOUT_DURATION`,
  1.5.3 bytecode) — longer than the 1.5 s hold. **Nothing of this reached the
  log before 1.1.14; now, tag `focus`:** each pass numbered, start
  ("#n why → Area/card centre (u, v) = sensor x,y of W×H · now AF state ·
  lens N dpt (≈ cm when the lens is calibrated)") and result ("#n why:
  focused / NOT focused / timed out / cancelled / superseded in N ms · AF … ·
  lens …", `FocusLog`); the analyzer's hold ("Trigger held", "focus answered N
  ms after the Trigger — capture #k shot from the 3 frames after it", "focus
  hold timed out after 1.5 s — no focus answer; capture #k shot from the last
  frames", a late answer re-taking the scene — dropped if the screen goes away
  first, owed or still due (owner, 1.1.17: a card swapped during a Scans
  round trip was taken into the scanned scene and never filed; the accepted
  cost is a still card filed twice), a hold cancelled by a re-learn /
  new Area / camera restart); every watchdog re-arm with its value and the
  median; the 3-tries give-up. Each capture's `done #k` line carries the
  three frames' sharpness ("sharpness a / [b] / c", the bracketed one used —
  `CaptureResult.sharpnessText`) and the CHOSEN frame's own AF state, lens
  position, exposure and ISO (`FrameMetaRing`: per-frame CaptureResults by
  SENSOR_TIMESTAMP, looked up when the burst is handed over). **Diagnostics'
  camera block is LIVE** (`ui/CameraDiagnostics`): read from the scan screen's
  controller whenever Diagnostics opens or a report is built (off the main
  thread it waits ≤ 1 s, else the snapshot), snapshotted at bind, after every
  capture and when the screen lets go — shown with its age when the scan
  screen is gone. Before 1.1.14 it was a bind-time copy, so `focus`, `last
  frame`, `analyzer` and `last capture` were stale. New line `focus:` = passes
  this session (focused / NOT focused / timed out / cancelled), the last
  result and its age, watchdog re-arms, the AF state and lens now, and whether
  a card pass is due (the old note is `focus note:`). The device /
  characteristics lines are read once per bind. Tested in code, not on the
  phone; dioptres → cm only when LENS_INFO_FOCUS_DISTANCE_CALIBRATION is not
  UNCALIBRATED.
  **Refocus on a card (owner, 2026-11-05: "Need it to focus every once in a
  while"; 1.1.18).** Before it the card pass ran only as above, so a lens that
  drifts (heat, a nudged mount, a thicker sleeve) stayed soft all session — a
  slow drift moves the watchdog's median too, so it never fires. Now, at a Tray
  trigger with no pass armed, `core/RefocusPolicy` arms one ("periodic: every
  N min — M min since the last focus on a card") once Settings → Camera →
  **Refocus on a card** (`AppSettings.refocusMin`; Every card 0 / 2 / 5 / 10 /
  30 min / Never −1; **default 5 min, PROVISIONAL** — a judgement until the
  rig's `focus` lines say how often a periodic pass moves the lens) has passed,
  and the same pass → retry / give-up runs. **The clock** counts from the later
  of the last card pass (or tap lock) that ANSWERED — focused or NOT focused: a
  lens that will not converge is not re-asked on every card — and the END of
  the last chain (focused or given up): the next chain comes one interval after
  the chain ends, answered or not (review of 1.1.18: a chain that never
  answered — the N200's fixed-focus camera 3 fails every AF-only pass at once —
  re-armed on the very next card, forever). No answer (cancelled, an error)
  does not move the answer clock; a fresh bind forgets both. In order: Never →
  never due; Every card → always due (a bind's first card is armed anyway); an
  interval with nothing since the bind → not due (the bind arms the first card
  itself — a periodic arm there would arm twice), else due after the interval.
  **Cut-off passes end the chain:** a pass still out when the next card's
  starts (the 1.5 s first-card hold gave up, CameraX answers a pass that never
  converges only after 5 s) is CUT OFF — CameraX cancels it — and counts as
  ended with no answer, a failure toward the 3-tries give-up (`passStarted`
  counts it, its own late end is `CUT_OFF`: nothing twice, and it never
  releases the newer hold); before, its answer was dropped uncounted, the chain
  never ended and every card was held. The pass that cut off the last try still
  runs on its card, counting for no chain (`OUTSIDE`, like a pass whose chain a
  new Area replaced) — starting it is what cancels the one still sweeping, so not
  starting it would shoot that card mid-sweep; its log says so (re-review L3a:
  the give-up line read "no more card passes" and the pass then logged the stale
  "retry 3 of 3 …"): the give-up is "card focus failed 3 times in a row — the
  last try was cut off by this card's pass, which still runs (counted for no
  chain); after it, no more card passes until …", the pass's why (`Pass.why`,
  fixed at its start) "the pass that cut off try 3 of 3 — the chain gave up; it
  still focuses this card, counted for no chain". **No pass starts when none is
  due any more** (re-review L4, a pre-existing race): the analyzer's request
  reaches the main thread after the hold began, so a tap in between (or an older
  pass's answer ending the chain) left `focusOnCard` to start a card pass that
  replaced the tapped point; now `passStarted` answers null and the controller
  releases the hold and starts nothing. **A periodic hold waits for the camera's answer**
  (`CardFocusState.Hold.PERIODIC`, the chain's retries and Every card too, via
  `ScanAnalyzer.focusHold` and `FocusRequest.hold`): ≤ `PERIODIC_FOCUS_WAIT_NS`
  5.5 s — just above CameraX's 5 s AF timeout, + the answer's hop and 3 frames —
  so it never shoots a moving lens (at 1.5 s it did, and the late answer's
  rebase could swallow a swapped card); one that times out anyway owes NO
  late-focus rebase (the lens was locked on a card before the pass; a re-take
  could swallow a card swapped meanwhile). The first-card / watchdog hold and
  their retries stay 1.5 s with the rebase, exactly as 1.1.14 (`Hold.CARD`).
  **A tap pauses it** (review of 1.1.18: a periodic pass threw the owner's tap
  lock away 5 min later): `CardFocusState.tapped` disarms the card pass and
  pauses the periodic one until a new Area or a camera re-open — leaving the
  scan screen and coming back (Scans, Settings) — also a zoom change or the zoom
  probe (they re-lock elsewhere: `arm(…, resumesRefocus = true)`). NOT a Tray ↔
  Tap to scan switch (re-review L1: the words promised "a mode switch", but that
  switch is `CameraController.setAuto`, which arms nothing — `setScanMode`'s arm
  is Mount ↔ Handheld and never runs; switching to Tray on the phone asks for an
  Area, and a drawn one is a new Area). The watchdog still arms its one pass on a
  soft capture but does not lift the pause. Logged once ("refocus paused — you
  tapped to focus; no periodic pass until a new Area or a camera re-open
  (leaving the scan screen and coming back) — Refocus on a card: Every 5 min",
  "refocus on a card resumed — first card in the new Area"); Diagnostics'
  `focus:` line reads "refocus paused — you tapped to focus (every 5 min)", and
  a give-up during the pause says "… — Refocus on a card (Every 5 min) is
  paused: you tapped to focus" instead of naming a periodic refocus that will
  not come (re-review L3b). **Never drops an armed periodic chain** (re-review
  L2): before, the setter only changed the interval and `holdFor` kept
  answering PERIODIC for the chain already armed, so a pinned Never (the zoom
  steps), a PATCH or Settings still held the next card ≤ 5.5 s, retries
  included; now `minutes = Never` clears it (a pass of it still out ends
  `OUTSIDE`), logged once ("periodic refocus dropped — Refocus on a card is
  Never now: the next card shoots at once (no hold, no retry)", after the
  change's own line); a first-card / watchdog chain and their retries are left
  alone. The arming state is
  `core/CardFocusState` (moved out of CameraController): every call under its
  monitor, so the analysis thread's check-then-arm in `holdFor` (asked through
  `ScanAnalyzer.focusHold`, once per trigger — it may ARM) is one step against
  the main thread's arms, passes and answers and the capture thread's watchdog.
  The shutter (Tap to scan) never consults it — a manual capture is never held
  for focus; the zoom probe's own AF never counts. **The measurement session
  pins it to Never** in the zoom steps (A1, probe, B, A2 — a periodic pass in
  one arm and not another would confound the A/B), pushed to the camera at the
  step change like AE lock and Zoom to fit; the owner's own is the original's
  5th field ("aeLock,vibration,checkMs,zoomFit,refocusMin"), put back at Stop /
  Finish; a 1.1.17 session (4 fields) saves it as the 5th before its first
  pinning step, a 1.1.16 one (3) gets Zoom to fit as the 4th first. Log (tag
  `focus`, one line per periodic pass): "periodic refocus: every 5 min — 7 min
  since the last focus on a card — this card is held for a focus pass on it"
  ("… since the last card focus gave up" when the clock counts from a give-up);
  the hold "card focus: Trigger held (≤ 5.5 s, a periodic pass — it waits for
  the camera's answer)"; the analyzer's hold lines start "card focus:" (they
  said "first card:" for every hold); a change of the setting on the camera
  ("refocus on a card: Every 5 min → Every card"); the give-up names the next
  periodic refocus only when there is one (`FocusLog.givenUpLine(tries,
  refocusMin, paused, cutOff)`: Never has none, Every card starts a fresh chain
  on the next card, a tap's pause has none). Diagnostics' `focus:` line ends "… · refocus every 5 min · last card
  focus 3 min ago" ("none yet"; "periodic passes N"), the header line "refocus
  on a card every 5 min". Applied live like the other camera settings (the
  resume catch-up, a PATCH from the computer, deferred past a zoom probe);
  `/api/device` `refocus_min` + `refocus_choices` [{min, label}] (the desktop
  d52's dropdown shows the phone's own list; d53's hint says a card waits for the
  camera's answer and a tap pauses it). `RefocusPolicyTest`,
  `CardFocusStateTest` (the give-up waits an interval, cut-off passes end the
  chain and the last one's why, the tap's pause, Never drops an armed periodic
  chain only, no pass after a tap / an ended chain), `ScanAnalyzerTest` (a Tray
  trigger 4 min 59 s after the last card focus shoots at once, at 5 min it is
  held; no double arm on top of a new Area's pass; a periodic hold waits 4.5 s
  for its answer, and one timing out at 5.5 s owes no rebase — a swapped card
  scans; a tap while the request is on its way starts no pass and the card
  shoots), `FocusLogTest` (every give-up line),
  `MeasureSessionTest` + `ScreensSmokeTest` (the zoom steps' pin, 3/4/5-field
  originals; the Settings row at the N200's size; live + on return),
  `DeviceApiTest`, `check_device_ui.py`. Tested in code, not on the phone.
- **Zoom to fit the Area (owner on 1.1.10: "No zoom?" → "Go"; 1.1.14).**
  Settings → Camera → "Zoom to fit the Area", **OFF by default in this build**
  (the owner turns it on to try it), both Tray and Tap to scan, also from the
  desktop 📱 Scanner panel (`zoom_fit`). The camera zooms so the drawn Area fills
  the view — no new control, the zoom comes from the box already drawn.
  **Coordinate spaces:** the stored `roi` (AppSettings, `/api/device`) is BASE,
  1× fractions — no zoom was ever applied before, so every stored Area already
  is one: no migration. CameraX zooms about the frame centre, so the VIEW Area =
  ½ + (b − ½)·z per axis (`core/ZoomFit.toView`, computed at use, EXACT identity
  at z == 1.0 — MainActivity compares Areas by equality); it keeps the aspect,
  so MH never changes. Everything that works on frames uses the view Area: the
  detection sample, the capture crop + margins (the full frame is the zoomed
  frame), the overlay, the focus points (`areaCentre(viewRoi)`, the card box's
  `FocusRequest.roi`), the watch window. The Tap default Area is base like any
  stored Area. **The ratio** (`ZoomFit.choose`): z = floor-to-0.05 of min(0.95 ·
  fit(A), lossless, the lens's maxZoomRatio, `MAX_ZOOM` 2.0 — provisional until
  the rig's zoom A/B, below), fit(A) = 1 / (2 · max|edge − ½|) (so the Area never
  leaves the view), lossless = the sensor active array ÷ the analysis stream
  (per side, the smaller), and z = 1 below 1.10×, with the switch off, while an
  Area is drawn, or with no Area. The default Tap Area gives 1.20×.
  **The lifecycle** (`core/ZoomCoordinator`, pure, `ZoomCoordinatorTest`; the
  CameraX glue is `CameraController`): every camera change CLOSES the
  analyzer's settle gate first (`ScanAnalyzer.closeZoomGate`: no tick, no ring
  copy, no snapshot, no capture; a pending shutter waits; a held first-card
  capture is dropped and the still card asked again via `triggerRefused`),
  calls `setZoomRatio`, and maps with the ratio READ BACK from the capture
  results (CONTROL_ZOOM_RATIO on API 30+, else active array ÷ crop width —
  CameraX's ZoomState moves when asked, not when frames carry it; a read-back
  within 1 % IS the request, so a re-apply maps bit-identically). The gate
  opens after 2 more frames are dropped and the ring is emptied — no burst mixes
  zooms. CameraX resets zoom to 1.0 on EVERY detach (`ZoomControl.setActive(false)`
  → `resetZoom`, 1.5.3 bytecode: every Scans / Settings / Tap-detail round
  trip), so the gate closes at ON_STOP and on CLOSING/CLOSED (OPENING too while a
  zoom is in use) and the zoom is re-applied on OPEN, at ON_START and when frames
  reach a closed gate (`onZoomResync`, once per closure). "Camera is not active"
  is retried 5 × 200 ms, then the mapping falls back to 1× and scanning carries
  on (the next start asks again); a refused ratio is not asked again in a loop;
  no answer in 2 s maps what a result NEWER than the request reports (a
  result counter in the controller; the read-back is cleared at ON_STOP and
  CLOSING/CLOSED — the last pre-stop frame says what the camera WAS), else the
  request, and a LATE answer that differs re-maps behind the gate (review of
  1.1.14: a slow reopen after a Settings round trip mapped the stale 1× and
  dropped the camera's 1.9× answer — the Area stayed mapped at the wrong zoom;
  `aLateAnswerAfterTheTimeoutReMaps…`). **The tray is re-learned
  only when (z, view Area) changed** (`ScanAnalyzer.zoomMapped` = setRoi's
  re-learn + the ring cleared on a new ratio): a re-apply after the camera comes
  back keeps the empty tray, and a still card across a real change is learned
  into the new tray, never scanned twice. Focus re-arms after an applied change
  (Area-centre lock at the new view + the first-card pass). **Drawing an Area on
  the phone** zooms OUT to 1× first and arms the drag only once the camera is
  there ("Zooming out to the whole frame…"); release stores the BASE Area and
  zooms in (one re-learn, at its own zoom); Cancel zooms back with no re-learn
  (the analyzer is left alone while drawing — its gate stays closed, but the
  browser's `snapshot.jpg` is served at the camera's 1× via
  `ScanAnalyzer.snapshotsAt`, and `/api/device` reports the camera's 1× with
  `drawing: true`, not "1.90× settling…"; before the review it answered 503
  for the whole drawing). **With the switch off none of
  this runs** — no zoom call, no gate, no settle: the Area reaches the analyzer
  as `zoomMapped(1.0, Area, settle = false)`, the pre-zoom setRoi path event for
  event (`zoomOffIsTheOldPathEventForEvent`). **The aimer (owner, 2026-11-05:
  "if in zoom mode I want a aimer for card centre"; 1.1.19):** with the switch on
  (`overlay.aimer = zoomFit && !drawing`, `MainActivity.updateAimer` — on every
  Area change, onZoom, drawing start / Cancel and wherever `setZoomFit` is
  pushed), Tray and Tap to scan alike, `OverlayView` draws a crosshair at the
  centre of the Area it draws — the VIEW Area, the frame centre with none (four
  12 dp arms, a 5 dp gap, white 2 dp over a black-α110 4 dp halo, so it reads on
  a white or a dark tray) — and a 3.5 dp dot in the shape's state colour at the
  drawn card's centre (the mean of its 4 corners; a SOLID shape only, never the
  dashed watch window), so the card is lined up by eye. Hidden while an Area is
  drawn (from the request, also while zooming out) and under ANY capture ✓ (the
  hold, and the plain CAPTURED state — the ✓ sits at the card's centre).
  `Plan.aim` / `Plan.cardCentre` are the pure decision (`OverlayViewTest`;
  `ScreensSmokeTest` at the N200's size: the view Area's centre after
  `onZoom(1.55…)`, on screen, gone while drawing, back on Cancel, none with the
  switch off). Display only — nothing reaches the analyzer, detection, focus,
  the logs or `/api/device`. Tested in code, not on the phone. **What shows it:** the Area chip
  reads "Area ✓ · 1.55×" while zoomed; the capture line (`done #k`) carries "… of FW×FH @ 1.55× · …" right after the
  card size, before the chosen frame's AF / lens and the timings; tag
  `zoom` logs each request ("→ 1.55× requested (why) · 1.55× (limited by the
  Area fit)"), what the camera reports (CONTROL_ZOOM_RATIO, the crop region and
  whether it is centred), the gate ("analyzer paused — why" / "resumed at
  1.55× — n frame(s) dropped"), each re-learn, any failure, and the POST-FIT
  CHECK (`ZoomFit.PostFit`: the first Tray capture at a new zoom compares the
  card's height in frame px with the median at the previous zoom — it should
  scale with z; beyond ±3 % the centred-crop maths does not hold on that
  phone, e.g. a logical multi-camera). Diagnostics: "zoom to fit the Area: on ·
  target … (limited by …) · applied … · Area fit … · lossless … · lens max … ·
  MAX_ZOOM …" plus the read-back; the analyzer line counts changes, gate
  closures and frames dropped. **The desktop:** `/api/device` carries
  `zoom_fit` and `zoom {ratio, target, limit, fit, lossless, max, settling, drawing}`
  (null with the scan screen closed); `roi` stays base, and `snapshot.jpg` stays
  a BASE-space picture while zoomed (`core/ZoomSnapshot`: the zoomed frame
  scaled by 1/z, centred on grey — marks land at their base fractions ±1 px at
  1, 1.6 and 2×, `ZoomSnapshotTest`), so drawing the Area there stays 1:1. The
  📱 Scanner panel (d48) has the checkbox and a zoom line (hidden for an older
  phone app) and re-takes the picture after a zoom / Area / lens PATCH. A PATCH
  is answered only after the scan screen's UI thread applied it
  (`DeviceBridge.write` waits ≤ 500 ms on the screen's future — before, the
  answer's zoom predated the change: "Zoom to fit is off" right after ticking
  it); the panel still shows "applying the change on the phone…" for a zoom /
  Area / lens PATCH and reads `/api/device` again 600 ms later, so an older or
  busy phone never shows a contradicting line —
  `scripts/check_device_ui.py` drives it in headless Chromium. Tested in code
  (ZoomFitTest, ZoomCoordinatorTest, ZoomSnapshotTest, the ScanAnalyzerTest
  gate tests, DeviceApiTest, ScreensSmokeTest at the N200's size for the
  Settings switch and the "Area ✓ · 1.55×" chip), NOT on the phone: whether the
  N200 reports CONTROL_ZOOM_RATIO, the lossless knee, the frames at the wrong
  zoom after a settle, and whether the photos get sharper are all rig
  questions — 1.1.17 builds the tools that answer them (tested in code, not on
  the phone):
  **The off-centre hint** (`ZoomFit.offCentre`, read-only, whatever the switch
  says): CameraX zooms about the frame centre, so an Area off to one side zooms
  less than the same Area centred (fit 1 / max(w, h)) — often 1× with no word
  why. Both go through `choose` with the same caps; it speaks only when the Area
  fit binds now (also below 1.10×) and centring gains ≥ `OFF_CENTRE_GAIN` 0.10×
  (two steps — PROVISIONAL, a judgement), never before the first bind (no lens
  caps yet). Whole % and ratios, no cm: Diagnostics' zoom line, the `zoom` log
  (once per change of the words, and why it goes), the status after an Area
  save (kept on the Tray lines until the first capture or another Area; switch
  off: "centred, Zoom to fit would zoom 1.90× (here 1.00×)"), the A1 step,
  `/api/device` `zoom.would` {z, limit, fit} + `zoom.off_centre` {now, centred,
  dx, dy, centred_roi, words}, and the desktop (d51): off at 1× "Off — this Area
  would zoom N× (limit)" + "Off-centre: …" (never the literal "Zoom to fit is
  off" — `check_device_ui.py`'s stale-answer check watches for it) and a dashed
  ghost box where the same Area would sit centred.
  **Camera view** (`snapshot.jpg?view=camera`): the upright analysis frame as
  the camera delivers it — zoomed, no grey ring, long side ≤ 2048 — with
  `X-Zoom-Ratio` (THAT frame's zoom) and `X-Frame-Size`; no query / `?t=` /
  `view=base` stay the base picture byte for byte, any other view is 400, no
  frame in 2 s is 503 (naming the zoom only while `zoom.settling`). The
  desktop's "Whole frame | Camera view" toggle shows only when `/api/device`
  lists `snapshot_views` (an older phone answers base as if zoomed), opens on
  Whole frame, draws the Area at ½ + (b − ½)·z from the HEADER (never the polled
  ratio), never draws or clears the Area there, and refreshes every 2 s while
  the probe runs.
  **The zoom A/B** (`ui/MeasureSession` + `MeasureAb`): the SAME 10 cards at 1×
  (A1, the Area drawn tight), zoomed (B), at 1× again (A2) — in the full session
  between Foil and AE lock, or alone from Settings → Diagnostics → "Run the zoom
  steps (about 10 min)" (`Plan.ZOOM`: A1, probe, B, A2, done). Every step PINS
  Zoom to fit (set-up, A1, probe, A2, AE lock off; B on) and the four zoom steps
  AE lock off and Refocus on a card Never (1.1.18 review), so B never leaks,
  Back included; each step pushes AE lock, Zoom to fit, Refocus on a card and
  the detail flag to the camera (before, only AE lock went — B would have run
  at 1×) and logs the coordinator's `describe()` under `test`. The owner's
  original is 5 fields ("aeLock,vibration,checkMs,zoomFit,refocusMin"), all
  put back at Stop / Finish even if the computer changed them; a 1.1.16
  session (3 fields) saves the switch as the 4th before its first pinning
  step, a 1.1.17 one (4) Refocus on a card as the 5th. Steps
  resume by id (`measureStepId`). Each Tray arm capture (shutter ones left out)
  records zoom, card long side px, strip detail (Laplacian of the OCR strip on
  the 630×880 identification raster — `CapturePipeline.measureDetail`, on in arm
  steps only, after "total": primary and timing keys byte-identical), ISO and
  the outcome rank (3 OCR ✓ · 2 auto-filed · 1 needs a pick · 0 none); cards
  pair by name, else by place; "stable" = A1 and A2 agree. **Rules, written
  before the data:** (1) B worse on no stable card; (2) better − worse ≥ 4
  (sign test p ≤ 0.0625); (3) no extra B captures, no more no-card / no-match
  than A1 or A2; (4) B ≥ 1.10× with A at 1×, else VOID ("draw a tighter Area");
  plus the post-fit ±3 %. A tie stays OFF; "zoom has no job at this mount
  height" when A1 alone OCR-confirms too many for rule 2 to pass; a near miss
  means another run with 10 other cards, never a lower bar.
  **The zoom probe** (the probe step between A1 and B: Run probe on the card A1
  left, ~30 s hands off, then again on a DIFFERENT card; core `ZoomProbe` /
  `ZoomProbeRun` / `ZoomDetail`, app `camera/ZoomProbeGlue` + `ProbeMeasure`): a
  ladder 1.0…2.0 by 0.1, then 0.2 to a top = min(3.0, lens max, 0.95 · fit of
  the card's box +6 %) — past MAX_ZOOM and lossless on purpose; refused below
  1.50× with the off-centre numbers. AE/AWB locked for the run, ONE AF lock on
  the card at 1× plus a refocus check at the top, 6 frames a rung (4 at High
  res) after the settle drops, a 90 s budget. **Never files, no needless
  re-learn:** the probe is a MODE of `ZoomCoordinator` (one gate owner) —
  `beginProbe` closes the analyzer's gate for every rung (no tick, ring copy or
  `fire()`), frames come from a separate tap served only INSIDE the closed gate,
  `canProbe` (asked after the gate closed) wants Tray in AWAIT_NEXT (any card in
  Tap to scan), no shutter pending and no late-focus rebase owed, and `endProbe`
  maps the IDENTICAL (ratio, view). Its RESTORE (the scanner's ratio, AE given
  back) checks the same card in the same place (size 1.5 %, centre 1 %, 64×88
  thumbnail NCC ≥ 0.90) → a rebase owed to that reopening's first sample only
  (`probeRebaseNext`; a later stop / gate / rebind drops it); otherwise none, so
  a swapped card scans. A lifecycle abort (stop, opening, bind, new Area, the
  switch, drawing) stops at once — no restore, no rebase: today's round trip.
  **Touch policy:** while it runs EVERY control on the scan screen (Scans, ⚙,
  Share, the Back key and the update lock's buttons too) only cancels it,
  through the verified restore; a scan identified meanwhile does not open; a
  PATCH or a pause is stored and applied after the end. **What it reports —
  facts only:** geometry (card size on H = a·z + c within 1 % — the centred crop
  the app maps with?), centring (base centre ≤ 1 %), settle (frames still at the
  old zoom after the 2 drops on > 10 % of rungs → SETTLE_DROP_FRAMES must
  rise), CONTROL_ZOOM_RATIO reported or not, the AF lens across zooms, and
  z_pipe = 880 ÷ H(1×) (past it the 630×880 warp gains no pixels — never a
  recommendation). A run is valid with the 1× pass FOCUSED, the repeats agreeing
  and ≥ 80 % of rungs clean; the probe half wants two valid runs on DIFFERENT
  cards, counted on the newest run's camera + stream only. **The detail metric
  FAILED its pre-registered bar** (coherence of each rung vs the top; the true
  knee within ±0.10×, |bias| ≤ 0.05×, in ≥ 9 of 10 synthetic cameras,
  sharpening on): 0 of 10 — past the knee coherence keeps rising (+0.7…+1.4× on
  binned / sharp sensors), below it it rises too slowly (−0.15…−0.5×); grid mean
  +0.092×; the Laplacian (reported only) within 0.10× in 4 of 10. Not tuned:
  `ZoomProbe.DETAIL_VALIDATED = false` (pinned by the acceptance test), so no
  knee and NO MAX_ZOOM from the probe — the A/B's identification decides.
  **The guided verdict** (`zoom VERDICT:` before "session END", and the done
  card): the A/B rules + the probe half (2 cards, centred, settle; the knee only
  a note) → "Zoom to fit ON by default: YES / NO — …" — logged and shown, NEVER
  applied: MAX_ZOOM 2.0 and the default change only in a follow-up PR, by these
  rules. Shown: tag `probe` (≤ 30 lines a run), Diagnostics "zoom probe: 31 Oct
  14:03 · camera 0 · 1600×1200 — <findings>" (`zoomProbeLast`, also with the
  screen closed), `/api/device` `zoom_probe` + `zoom.probing` / `zoom.probe`
  (read-only), the desktop "Zoom probe running on the phone — …" / "Last zoom
  probe (…): …".
- **The review WebView shows the page's `confirm()`/`alert()`** (`ui/JsDialogs`,
  a `WebChromeClient`): an Android WebView drops JavaScript dialogs unless the
  app shows them, so `confirm()` returned false and phone.html's "Clear all" /
  "Clear unpicked" / "Delete flagged" silently did nothing on the phone
  (owner, 2026-10-01: "need a way to delete all scans"). Settings → Scans →
  **Delete all scans** is the native way (count shown, one confirmation, no
  backup — `PhoneServer.deleteAllScansAsOwner` → the API's own
  `POST /api/scans/delete-all`, so photos go and the price worker hears).
- **Swipe-to-delete holds the DELETE for a 5 s Undo.** Inside the app the
  WebView is destroyed when its screen closes (no pagehide, timers die), so
  the page reports its pending ids to `CardScannerApp.pendingDeletes` and
  `PanelActivity.onPause` deletes them itself (`PhoneServer.deleteScansAsOwner`)
  — without that, swiped scans came back.
- **The desktop list deletes per row too, with the same 5 s Undo (owner,
  2026-11-05: "I want delete options here in the list"; desktop d54).** A 🗑
  on each scan row (`.rdel`, `admin-only` and rendered for `role === "admin"`
  only — a restricted guest never sees it): shown on hover or keyboard focus
  (opacity, so it stays in the tab order), always on a touch screen
  (`hover: none`); its click stops propagation and the row handler ignores
  `[data-del]`, so it never opens the row. phone.html's swipe, ported: the row
  leaves the list at once (`liveScans()` — `visibleScans`, the stats and the
  clear counts all read it, so the row signature drops it with no new field),
  an Undo bar over the list column ("Scan deleted" / "N scans deleted") holds
  the DELETE for `UNDO_MS` 5000; several can wait, Undo restores every one
  still waiting; `pagehide` / a hidden tab send what waits (`keepalive`);
  "Clear …" sends it first. Deleting the card open on the right clears the
  pane. A SENT id stays hidden until a poll STARTED after its DELETE answered
  is applied (`settleDeletes`; `poll` now drops an answer older than the newest
  applied), so no in-flight poll flashes it back; a refused DELETE (403 — full
  access turned off meanwhile) shows the row again with a toast.
  **Review of d54 (desktop d55, phone v57):** the list gets bottom room while the
  bar shows (`#list.undoing` — the bar sat over the last row's 🗑, so a second
  delete near the bottom hit Undo); Enter / Space on a 🗑 (a click with
  `detail` 0) moves focus to Undo — the re-render dropped it to `<body>`, Undo
  dozens of Tabs away — and a keyboard Undo puts it back on the restored row's
  🗑; Undo re-opens the card that was open when its row went (`reopenId`, never
  over a card opened meanwhile); Export sends what waits FIRST (the stats leave
  it out, the server would still export it); EVERY delete path says why it did
  not happen (`deleteFailMsg`): a 403 is "no longer has full access (the phone's
  owner turned it off)" — never "try again" — and re-asks `/api/me`
  (`refreshRole`), so the controls go; no answer is "try again". Before, the
  detail's "Delete this scan" toasted "Scan deleted" on a 403, Clear read
  "Deleted undefined scans", phone.html put a swiped scan back silently, and a
  refusal while the tab was hidden was never told (now kept in `deferredMsg`
  until the tab is back). phone.html's `#flash` sits above every panel
  (`position: fixed`, z 30 — it was in the list, under an open card) with a
  `.bad` colour, and Clear says its result there too (the status line is hidden
  in the app). `scripts/check_row_delete_ui.py` drives all of it in Chromium
  (admin, touch, keyboard, 30 rows, refusals on every path, a hidden tab,
  Export, a restricted guest, phone.html) — 19 of its checks fail on d54/v56;
  `test_static_pages.py` guards it in CI. **Re-review (desktop d56, phone v58):**
  a 401 (the session is gone — a new guest code, "Forget paired computers") reads
  "no longer signed in … reload the page to join again", another status "the
  phone refused it (HTTP n); try again", only no answer "didn't answer"
  (`worstStatus` picks a batch's words: 403, 401, any status, none); a MOUSE
  Undo leaves focus alone (`e.detail === 0`, as on the 🗑 — Chrome focuses a
  clicked button, so the restored 🗑 stayed shown and the next Space deleted it
  again); Export waits ≤ 3 s for those DELETEs (one the phone never answers left
  Export dead); phone.html's `#flash` takes no taps and is lifted above the Undo
  bar while it shows (`body.undoing`) — a 5 s "try again" notice sat exactly on
  Undo, so a tap there did nothing and the swiped scan went for good.
  **Final review (desktop d57, phone v59):** a 🗑's click with `e.detail > 1` is
  ignored — the 2nd click of a double-click landed on the row that slid up under
  the pointer and deleted it too (Enter / Space are detail 0, a 1st click 1). The
  Undo bar is 316 px wide (x 16–332): the 🗑s sit at x 356–387 of the 400 px
  column at every window width — from x ~340 with a classic 16–17 px scrollbar
  (Windows Chrome; headless Chromium hides scrollbars, so the check runs a pass
  with them shown) — and at 368 px (to x 384) a second delete at the
  same spot MID-list — where d55's room at the end does nothing — hit Undo. The
  toast lifts to `bottom: 76px` while the bar shows (`body.undoing`; the bar is
  y 890–934 at 950 px high, a long refusal sat at 868–930 over it at ≤ 1280 px
  wide) and sits above the 🐞 panel (z 65 > 60: its Copy report / Download words
  were behind it). A keyboard delete left to run hands focus, as the bar goes, to
  the 🗑 of the row that took its place (`kbNext`: below, else above), else the
  first 🗑, else the name filter (it fell to `<body>`), and a list re-render keeps
  a focused 🗑 focused (`preventScroll`). Every admin-only call (`ScanServer.adminOnly`)
  goes through `adminFetch` on both pages — `failMsg(what, status, detail)` is
  `deleteFailMsg`'s words for any action (403 full access, 401 signed in, a
  400 / 422 the phone's own words, HTTP n, no answer), a 403 re-asks `/api/me`:
  the builder opens only on its layout (a 403 threw on `r.fields` and nothing
  said why), a layout save / the preview / Download CSV / TXT / the 🐞 report
  say why in place (the downloads are fetched then saved under the server's
  Content-Disposition name — navigating left the page for the error JSON),
  the include box is put back, ▶ Sweep / ■ Stop / Price missing no longer say
  "Nothing needs pricing" / "stopping", Exclude filtered stops at the first
  refusal and says how far it got, the 📱 panel says why (its 403 body read "flag
  it for deletion instead"). Both pages re-read the role (above, "Roles") and
  re-render only when it CHANGES (a 30 s re-render of the open card would wipe a
  typed name search). phone.html takes desktop's delete model: committed ids move
  from `pendingDel` to `sentDel` (Undo clears only `pendingDel` — it "restored"
  a scan whose DELETE was in flight, and the bar counted it); a sent id stays
  hidden until a refresh STARTED after its DELETE answered is applied
  (`settleDeletes`, `pollSeq` / `pollApplied` — Chromium holds a second GET of
  `/api/scans` behind the first, so the stale list still flashed the row for
  ~5 ms before the fresh one, longer on a LAN); the app hears every id still owed
  (waiting, or sent with no answer). Clear counts the list as shown
  (`liveScans()`) and sends what waits first (≤ 3 s) — the question counted
  swiped rows and the bar stayed up after the clear; desktop's Clear waits for
  them too, so "Deleted n" answers "Delete n?". `check_row_delete_ui.py`: 54
  checks fail on d56/v58 (+ the 2 banner pins), all 199 pass on d57/v59.
  **Its review (desktop d58, phone v60):** with no session the gateway answers a
  GET with its join page (200 text/html), and d57's fetched downloads took that
  for the file — Download TXT / CSV and the 🐞 Download saved the join page as
  cards.txt / cards.csv / cardscanner-debug.txt and said nothing (d56 navigated
  to the join page). `adminFetch` now reads an `/api` answer in HTML as a lost
  session (401: "no longer signed in … reload the page to join again") on both
  pages, so no admin call ever takes the join page for an answer; the check's
  stub has a "session gone" mode (4 checks fail on d57); 206 + the scrollbar
  pass pass on d58 / v60. **Its review (d59):** every raw admin GET asks the same
  `lostSession(url, res)` — the 📱 panel opening ("aren't available" → "no longer
  signed in"), its load and Camera view, and the 🐞 App log poll (it threw a JSON
  error every 1.5 s and froze silently; now it says so once and stops); the stub's
  session-gone mode answers PUT 401 too (the layout save is what Download CSV
  meets first). 4 checks fail on d58.
- **The live log** (`core/DebugLog.global`, a 3000-line ring, mirrored to
  logcat as `CardScanner`): triggers (box, mask %), captures (`done #k`:
  flattened or not, card px, the three frames' sharpness, the chosen frame's
  AF state / lens dpt / exposure / ISO, timings), every focus pass and hold
  (tag `focus`, above), each identification (name, printings, top, OCR ✓, stage
  ms), "capture #k → job n (nonce8) queued" and the job's outcome under the
  same name (1.1.10 printed every job as `00000000`: the first 8 of 12 digits
  of "%012d"), retry errors, F2F requests, server start/stop/idle
  and joins, card-database and update checks, remote setting changes, crashes
  (an uncaught exception writes the log to `last-crash.txt`; the next report
  carries it). Read it live on the paired computer (🐞 → App log, admin-only
  `GET /api/debug/log?since=`), as a report (`/api/debug/report.txt`, 🐞 →
  Copy report / Download; Settings → Diagnostics → Share debug report), or
  with `adb logcat -s CardScanner`. **Never log a secret** — no join/admin
  codes, cookies or tokens (`joinsAreLoggedWithoutTheCode`). The page's copy
  falls back to a textarea: the LAN page is plain HTTP, where browsers give no
  `navigator.clipboard`.
- **Keep awake, dim when idle (owner, 2026-10-17: "stop the phone from going
  fully asleep. Allow some dimming?"; 1.1.15).** `ui/ScreenAwake` (a lifecycle
  observer on EVERY app screen — scan, review, Settings, Share, network test)
  keeps the display on (FLAG_KEEP_SCREEN_ON; before 1.1.15 only the scan screen
  did, so the phone slept on the review/Settings/Share screens, which stops the
  camera and, 30 min later, the phone server) and, after Settings → Screen →
  "Dim when idle" (`AppSettings.dimAfterS`: 30 s / 1 / 2 / 5 min / Never,
  default 1 min) with no activity, drops the WINDOW's brightness to
  `core/ScreenDim.level`: 10 %, but at most half the phone's own brightness —
  `SCREEN_BRIGHTNESS` over the range the phone DECLARES
  (`config_screenBrightnessSettingMaximum` read by name from the framework's
  resources: 255 in AOSP, 1023–4095 on some OnePlus/Xiaomi; a reading above the
  declared range is read against 4095 — dimmer than meant, never brighter) — and
  NOT AT ALL when that half is under 1 % (already about as dark as it goes; the
  review found a 0..2047 phone at night would otherwise be BRIGHTENED 4×).
  `screen_brightness_float` is not used: it is gone from Settings.System on
  Android 15. Activity = the activity's `onUserInteraction` (any touch), and on
  the scan screen `onCaptureStarted` (trigger or shutter — a dimmed screen lights
  for the ✓); a card merely sitting there does NOT count, or the screen would
  never dim with a card on the tray. **Only while the screen has window focus**
  (review of 1.1.15): a dialog (the pairing QR + one-time code, a text field, the
  page's confirm()), the notification shade or the other split-screen half takes
  it and their touches never reach `onUserInteraction` — so focus loss brightens
  and stops the clock, focus gain restarts it (each activity forwards
  `onWindowFocusChanged`). Leaving a screen restores the brightness; a choice
  made on the Settings screen applies there at once (`reload`). Every
  kept-awake screen also counts for the server's idle stop
  (`GatewayService.screenVisible`, moved here from MainActivity / PanelActivity —
  before, the server stopped after 30 min under a Share screen still showing its
  code). Display only otherwise. Log tag `screen`: "dimmed to N % — 1 min with
  no touch or scan (phone brightness S/MAX, where)", "bright again —
  touch|scan|shutter|a dialog or another window is on top (where)", "not
  dimming — the screen is already that dark (…)" once per visit; Diagnostics:
  "screen kept on · dims after 1 min idle · phone brightness S/MAX" (the rig's
  range shows there). Not in `/api/device` (phone only). Tested in code
  (ScreenDimTest; ScreenAwakeTest — each of its rules fails its test when
  removed; the Settings row at the N200's size), not on the phone.
- **The scan signal** is ONLY the blue ✓ on the card (a centre ✓ badge with no
  shape), held for Settings/📱 Scanner "Check mark time" (250–10000 ms, default
  2500, `check_ms`), plus two short buzzes (Vibration switch).
- **Live card outline + blue capture + card-shaped watch window (owner,
  2026-10-02: "see it draw an outline around the card, then have it go blue on
  image capture … then watch that area for new cards"). `Detection.kt`,
  `AutoScanner.kt` and phone.html's detection are untouched; the outline is
  display, the watch window, and (since 1.1.7) the card-shape gate's evidence —
  nothing else.** `core/CardOutline` runs the reference port `CardQuad.find`
  (unmodified) on the scanner's existing 176×MH detection sample — never a full
  frame (est. 65–90 ms on the N200 vs ~4–5 ms; measured 1.3 ms on x86) — and
  pulls each side in by 5·(1−1/k) sample px (k = frame px per sample px: the
  finder's dilation swells every side ~5 px at whatever resolution it runs;
  CardOutlineTest holds it within 2 sample px of the full-resolution quad on the
  detect fixtures). `ScanAnalyzer` finds it right after `AutoScanner.tick()` and
  BEFORE the trigger decision (the gate needs it), inside runCatching — a finder
  that throws counts as unknown and the capture still fires, so `fire()` /
  `captureDone()` can never be skipped (a skipped captureDone leaves Tray dead
  until a double-tap; `anOutlineFinderThatThrowsNeverSkipsTheCapture`). `OverlayView`
  draws it in place of the mask rectangle (green occupied / amber settling), the
  rectangle when there is none; `beginHold` at `onCaptureStarted` turns it blue
  with the ✓ and FREEZES it (it used to sit on the live box, which grew when a
  hand came in, and went blue → grey → blue), `holdCheck(ms, quad)` snaps it to
  `CaptureResult.quad` — now carried through `Captured` with the frame size —
  for `check_ms`, `cancelHold` on a failed capture. The WATCH WINDOW is the
  padded (15 %) card polygon in sample px (the live outline at the Trigger
  tick, else the mask box, replaced by the capture's quad when it arrives —
  tagged with the burst id, ignored after a newer trigger), drawn grey dashed
  while awaiting the next card. `OverlayView.plan()` is the pure decision the
  Robolectric tests assert.
  **The card is ALWAYS highlighted, the outline SMOOTHED (owner, 2026-10-02:
  "always highlight the card … average its position and throw away outliers.
  Right now the card tracing is 98% successful. Sometimes it thinks it's a much
  bigger card").** The outline runs on every tick a card may be sitting there
  (occupied, Trigger, AwaitingNext, a different card settling); `core/OutlineTracker`
  keeps the last 5 raw finds (sample px), takes the per-coordinate median,
  drops any find whose farthest corner is > 0.12·√area of the median away
  (the "much bigger card"), and shows the MEAN of the rest — so 1–2 bad finds
  in 5 never move it, and a new card is adopted once it is the majority (3 of 5
  ticks, ~600 ms), as a jump, never a slide. A missed tick holds the last
  outline for 2 ticks, then the window empties (reset on card removed / tray
  re-learned / new Area, `clearWatch`). The smoothed outline is what the
  overlay draws (grey, solid, in AWAIT_NEXT too — the dashed watch window only
  when there is none) AND what the watch window is armed from at the trigger.
  The analyzer line reports "N outlier(s) dropped".
- **Scan-screen layout** (owner, 2026-10-02): top bar = version · server state
  (+ the camera pill on a phone with more than one lens), cards/min, the upload
  indicator, the measurement card; bottom = status pill, Tray|Tap · ⚙, then
  Share (icon) over Scans · shutter · Area. **1.1.7 shipped with that bottom row INVISIBLE** (owner's screenshot): the
  mode row's balancing spacer was a bare `View` with WRAP_CONTENT height, which
  a bare View measures as "all the height offered", so the row filled the
  screen and Share/Scans/shutter/Area got 0 px. Fixed in 1.1.8 (spacer height
  0). `mainActivity_everyControlIsOnScreen` lays the screen out at the N200's
  size and asserts every control has a size and sits on screen — the old
  "is it in the bottom bar" test passed with all of them at 0 px.
- **THE CARD-SHAPE GATE (owner, 2026-10-02, a video of the Tray "trying to
  scan nothing" — "Card detected — hold still…" and a small amber box
  wandering over an empty white tray: "should try to find a shape that
  matches the ratio of a magic card").** The occupancy + stillness trigger is
  UNTOUCHED (`Detection`/`AutoScanner`/phone.html unchanged, the differential
  test still holds); the gate sits AFTER it, in `ScanAnalyzer`, and only says
  no: a Tray Trigger with no live outline (none this tick, none held by the
  tracker from the last two) is REFUSED — nothing shot, `AutoScanner.triggerRefused()`
  (app-only, like `rebaseScene`) puts the scanner back to WATCHING with
  stillness counted afresh, so it asks again 2 steady ticks later (never
  AWAIT_NEXT: that waits for the scene to change, and a still card the finder
  missed once would never be scanned). After `RELEARN_AFTER_REFUSALS` = 5
  refusals of one still scene (~2 s) `adoptEmpty()` makes it the empty tray —
  exactly what the wasted no_card scan used to do via `onNoCard`. The outline
  is the card judge CardQuad already is (ratio 1.15–1.75, rectangular,
  printed interior — the earlier geometry gates that failed were on the DIFF
  MASK, not on found edges). A finder that THREW is unknown, not "no card":
  the capture goes ahead (`anOutlineFinderThatThrowsNeverSkipsTheCapture`);
  with OpenCV not loaded every outline is null and Tray would refuse
  everything — the `detect` log says "TRIGGER refused" once per scene. The
  shutter / Tap to scan is never gated. The UI says "Something in the Area —
  no card shape yet…" instead of "Card detected" while the mask is occupied
  without an outline; the Diagnostics line counts "triggers refused".
  **THE PRINTED-DETAIL FALLBACK (1.1.14; owner on 1.1.10: "Old bordered cards
  didn't see the change of cards. White bordered cards didn't get picked up as
  cards").** A HEDGE, not a diagnosed fix: synthetic white-bordered cards are
  found by the outline at 176 px, so the rig's cause is unknown. If the finder
  misses a real card, that card is refused and after 5 refusals becomes "the
  tray", so the next card is judged against it — both reports at once. Before
  refusing, the gate now asks the SAME trigger box for a card's printed detail
  (`core/PrintEvidence`, app-only; read-only `AutoScanner.emptyGradient()`, a
  copy): in the box's CORE (inset 25 % per side — a flat patch's edges, a glare
  flank, a hand's outline stay out) the share of pixels textured now (gradient
  > `PRINT_GRAD` 21 = 1.5 × GRAD_THR) that were smooth in the learned tray
  (≤ 14) must be ≥ 6 %, the box long/short 1.15–1.9, ≥ 3 % of the Area. Yes →
  the capture fires without an outline; it can only ever say yes MORE often.
  Measured (`PrintEvidenceTest`, synthetic 176×235 samples through the real
  AutoScanner, table printed): 900 cards (black / white / white + dark frame ×
  trays 190 / 215 / 240 / dark 40 / textured × sleeved / bare × blur 0/1/2 px ×
  4 sizes incl. filling the Area × upright / 10° / sideways) all accepted, print
  ≥ 18 %, box 1.21–1.42, ≥ 8 % of the Area; 200 non-card scenes (glare ±40–80,
  shadows −30–60, exposure steps, smooth hand blobs, σ 2.5 noise, a textured
  tray), 93 of which the occupancy trigger fires on — none accepted, print
  ≤ 1 %. Why 21, not GRAD_THR: at 14 noise on a textured tray read 11 % vs a
  card minimum of 26 %; at 21, 1 % vs 18 %. Why the core: with the whole box a
  defocused smooth fist on a dark tray read 21 %. The outline itself found
  869/900 — the 31 misses are cards that FILL the Area (an Area drawn tight on
  the card); it also took 24 of 30 smooth hand blobs for cards (pre-existing,
  not the fallback's doing). Real art is smoother than the synthetic blocks —
  hence the low 6 %; not measured on the rig. **Stale references (review of
  1.1.14):** "new texture vs the learned tray" is print only while the learned
  tray is TRUE — on a textured tray (playmat, wood) a tray learned with a card
  on it (rebind / new Area / zoom change / double-tap), learned slightly out of
  focus, lit brighter under AE lock or nudged reads as print over a box that is
  the whole Area (always card-shaped, always big enough): the fallback shot 142
  of 348 such empty trays and lost the 1.1.7 self-heal (3 real cards → 7
  bursts). Two more tests, independent of how fresh the reference is: the
  learned tray must be SMOOTH in the box and its core (≤ 4 % above
  `TRAY_GRAD` 7 = GRAD_THR / 2), and the print must stand out from a ring just
  outside the box (ring texture ≤ print ÷ 2; no ring when the box fills the
  Area). Measured: smooth-tray cards 720/720 still accepted (tray texture ≤
  0.5 %, print ≥ 3.5 × ring); stale empty trays 0/348 accepted (the tray test
  refuses all 142, 8–88 % texture); on a textured tray the fallback stands
  aside for every card (the outline found 173/180 there — 1.1.10's
  behaviour). Limits: sample noise above σ ≈ 1.6 makes a flat tray read
  textured (the fallback then stands aside), and a textured tray learned > 2
  sample px out of focus reads smooth (est. beyond the lens at the mount).
  `ScanAnalyzerTest` holds the lifted-card / AE-lock cases at analyzer level
  and that each real card is shot once after a stale view is adopted. **Log lines (tag `detect`, ≤ 1 per
  event):** a refusal states its evidence once per scene ("TRIGGER refused — no
  card shape · no printed detail · print 0% (need 6%) · box 1.38 (1.15–1.90) ·
  20% of the Area (≥ 3%) · box … mask …"); "TRIGGER accepted without an outline
  — printed detail N% …"; the adoption repeats the last evidence and the
  scene's peak print; every NextCard is "next card: removed — mask N%" or
  "next card: swapped — change N% vs the scanned card (needs > 6%)" (the
  analyzer's line replaced MainActivity's); and EVERY Tray wait (shadow mode or
  not) ends with one "wait over" line: the swap test's own number — the max
  change vs the scanned card over occupied ticks, and over the ticks a card sat
  still (where a swap could fire) — the still ticks, and the mask's minimum, so
  a missed swap shows why (two passes over the sample per waiting tick). The
  Diagnostics line counts "accepted on printed detail" (and any detect line
  that failed to build — `detectLog` never throws into the decisions).
- **SHADOW MODE — the shadow-proof "texture change" signal, computed and
  logged, NEVER acting** (owner: "Can't trigger on silly things like shadows";
  a shadow is a brief, smooth, multiplicative change, a new card changes the
  fine print and stays). Reference `mtg_card_scanner/change_signal.py` →
  `core/TextureChange` (pure Kotlin, held bit-close by TextureChangeParityTest
  on `scripts/export_change_fixtures.py`'s fixtures in
  `core/src/test/resources/change/`; change the reference first). logHP p75 =
  the 75th percentile over 8×8 blocks (≥75 % inside the window) of mean
  |Δ(ln(Y+8) − blur_σ4)|×100 between the sample and the scanner's scanned frame;
  logGrad (mean Sobel difference of ln Y ×100) is the second opinion. Measured
  on synthetic scenes at sample resolution: shadows / glare / exposure ≤ 4.8
  (the assessment's summary quotes ≤ 6.3), real changes ≥ 8.6; the gap narrows
  with noise (σ 2.5: 5.4 vs 9.7; σ 4: 7.9 vs 10.6), so `START_THRESHOLD = 8` is
  provisional until the rig's numbers are in. While the scanner awaits the next
  card, `ScanAnalyzer` measures it every tick inside the watch window and logs
  EVENTS only under tag `shadow` (≤ ~1 line/s, 3000-line ring): the maximum per
  wait when the wait ends (with its tick count — since 1.1.14 it rides the
  `detect` "wait over" line), every crossing of 8 with its
  duration and logGrad, and "WOULD re-arm (texture)" when ≥ 8 holds 2 ticks —
  plus one `outline` line per capture (live outline vs capture quad, card
  height in frame px) and the Diagnostics analyzer line's avg outline / texture
  ms (the scanned frame is prepared ONCE per wait, `TextureChange.prepare`:
  4.7 ms per tick warm on x86 for 176×235, est. 15–20 ms on the N200 — only
  while a scanned card sits there). `shadowModeNeverChangesTheScannersDecisions` feeds the same frames with
  it on and off and asserts identical outcomes. Diagnostics also reports the
  camera's zoom range and sensor facts (the auto-zoom question). Re-arming on
  the signal is NOT built: that is phone.html first, after the rig data.
- **The guided measurement session** (owner, 2026-10-02: "a test mode that
  prompts me to do all these tests"): Settings → Diagnostics → **Run the
  measurement session** (`ui/MeasureSession`, state in `AppSettings` so it
  survives leaving the screen) shows a prompt card in the scan screen's top
  scrim that walks the rig session step by step (set-up + outline check,
  250 ms check time, 20 swaps, 10 lift-and-replace, hand passes, lean-ins,
  lights, foil, the zoom A/B — A1, probe, B, A2 — AE lock on, done: 14
  steps), counts the captures during each step, applies the settings a step
  needs (AE lock / vibration / check time / Zoom to fit / Refocus on a card —
  Never in the zoom steps) and restores the owner's own at the end, asks Yes/No where a step is a visual check, and
  writes a `test` marker into the debug log at every transition (`step k/N
  START/END … n capture(s)`, an arm's `captures #a–#b`, answers, `session
  START/END`) so the `shadow` / `outline` / `capture` / `zoom` / `probe` lines
  read against what was being done. Finish logs the A/B summary and the `zoom
  VERDICT` before `session END`, then opens the share sheet with the debug
  report. **"Run the zoom steps (about 10 min)"** (1.1.17) runs only the zoom
  block + done (hidden while a session is on — Resume covers it). Plan, step
  id, A/B records and probe runs live in `AppSettings` (`measurePlan`,
  `measureStepId`, `measureAb`, `zoomProbeRuns`), cleared at the end; the A/B,
  the probe and their rules: Zoom to fit, above. Display, settings and logging
  only — the probe moves the zoom but files nothing; the cards put down are
  filed like any scan (the done step says to delete them).
- **The update lock (owner): a newer release stops scanning.**
  `update/UpdateLock` reads GitHub `releases/latest` at app start and when the
  scan screen opens (≤ every 30 min). Once a release newer than the build is
  out WITH the APK, the scan screen shows "Update required" (Download update /
  Scans) and stops capturing (detection paused, shutter + Retry refused).
  Scans, review, export, the phone server and queued identification never
  stop — the phone holds the only copy of the scans. Offline changes nothing,
  but a newer release once seen stays locked; installing the update unlocks
  (the lock is "latest seen > this build"). Prereleases (the `art-pack` tag),
  drafts, releases without the APK and unparseable tags never lock. The phone
  server's `/api/update-check` reports it (the desktop's update banner).
  Robolectric tests never ask GitHub.
- **The phone server.** `GatewayService` (foreground) hosts `PhoneServer` on
  :8090 while the app runs; its notification carries Stop and hides the guest
  code on the lock screen. It stops itself after 30 min with no app screen
  visible and no non-loopback request (`shouldIdleStop`). The LAN leg is plain
  HTTP (the pairing dialog says to pair on a trusted network). **The store
  never opens or is read on the main thread** (1.1.17: its open runs the
  one-time re-price — seconds on a big store, an ANR, and a kill mid-way rolls
  it back to run again): `newServer` hands the gateway `LocalUpstream { backend }`
  (the first request waits, on its own thread), the worker's start / stop run in
  order on one "store-open" thread (a failed start retried every 30 s while
  serving, `servingGen` drops a stale retry), Settings counts on its io thread
  ("…" until then, one Delete-all dialog at a time); the debug log says
  "store … opened in N ms".
- **Roles.** A computer pairs as ADMIN with a one-time 8-digit code (10 min,
  single use, its own budget of 10 wrong tries, behind the same lockout and
  pause as the Share code, retired when a pause starts) → a long-lived
  `cs_admin` cookie (only its SHA-256 on disk; "Forget paired computers"
  revokes). The phone's own review screen is admin
  via `AdminPairing.ownerToken` (in memory, never persisted or counted,
  untouched by Forget). The 6-digit Share code's role is a SETTING —
  **"Guests get full access"** (Settings → This phone's server,
  `AppSettings.guestsFullAccess`, **ON by default**; owner, 2026-11-05, a
  screenshot of the desktop page with the "guest" pill and no delete: "I want
  delete options here in the list. Any guest to the web site to pick cards has
  full admin access"; 1.1.19): ON, a Share-code session is proxied as ADMIN —
  delete, clear, export, PATCH `included`, the sweep, `/api/device`, the app
  log; OFF, it is a GUEST: review/pick/edit/flag, refused (403) the rest
  (`ScanServer.adminOnly`). `GatewayServer.roleOf` asks the setting on EVERY
  request (a provider from `PhoneServer.newServer`; a throwing one = off), so
  a flip reaches sessions already open at once — no re-join; an open page
  re-reads `/api/me` every 30 s (`ROLE_REFRESH_MS`) and when its tab comes
  back, so it gains or loses its admin controls within 30 s, no reload, either
  way (d57 / v59 — before, a page loaded as a guest never gained them); a
  control used before then is refused by the server and says why (every admin
  call goes through `adminFetch`; a 403 re-asks `/api/me` at once). A flip is logged once under `server` (never a code); Settings
  says plainly what ON gives away (delete is for good — the phone holds the
  only copy), the Share screen's amber card follows the setting, and
  Diagnostics has a `guests (Share code):` line. The setting never opens the
  door: no session = the code page, as before. The gateway sets
  `x-cardscanner-role` on every proxied request and DROPS a client's own (no
  header = guest, fail closed). Pages start as guest (`<body class="guest">`)
  until `/api/me` answers; a guest's swipe flags, the admin gets "Delete
  flagged (n)" (only the ids it confirmed). The gateway answers LAN/hotspot
  peers only (socket address, not headers) and locks an IP at its 8th bad code
  in 5 min (for 5 min: 429 "Too many wrong codes from this device").
  **The join pause (final review of 1.1.19, a probe that joined after ~0.4–2.4 M
  guesses, never locked):** the 20th bad code in 5 min from ALL addresses (6-
  or 8-digit) rotates the code, retires a waiting admin code and PAUSES joining
  for every address — a right code is refused like any other (429 "Too many
  wrong codes on this network — joining is paused for N min", Retry-After) —
  until the oldest of the 20 is 5 min old. Before, that 20th RESET every count,
  so 3 addresses sending 7 / 7 / 6 in turn (IPv4 aliases, fe80::/fc00:: — all
  `isLocalClient`) never locked and guessed at the request rate (~10⁶ requests,
  1–3 h — and a Share-code session is admin by default). Now nothing but time
  (and the owner's New guest code, `stopSharing`) empties the count: ≤ 20
  codes compared in any 5 min, ≈ 10⁶ / 20 × 5 min ≈ 174 days expected (≈ 0.6 %
  per day of non-stop guessing). The cost: while bad codes keep coming nobody
  NEW joins or pairs (the attacker takes each slot as it frees, and each slot
  it fills starts a pause again — the code changes with it: 221 times in the
  test's hour at 50/s); once they stop, ≤ 5 min. Sessions already in, the
  paired computer (`cs_admin`) and the phone's own review screen (owner token)
  never need a code and carry on. **The code also changes every 20 wrong codes
  since it last changed, at any pace** (re-review: a search paced just UNDER the
  pause — one code every 15.79 s over 3 addresses — never filled the window, so
  the code never changed and it walked 10⁶ against ONE code, ≈ 91 days average;
  a probe joined at guess 132 708): no code faces more than 20 guesses, so the
  ≈ 174 days holds at any pace; one line per change ("… since the guest code
  last changed — guest code rotated (… no pause)"), and the Share screen shows the
  new code at once — it FOLLOWS the gateway while on screen (`GatewayPort.changes`
  = the service's status flow, collected under `repeatOnLifecycle(STARTED)`; its
  final review: it drew only in onCreate / onResume / its buttons, so it kept the
  OLD code and QR on the mount — a guest scanning it was refused — and "New guest
  code" drew before the service had rotated; `shareActivity_followsACode…` at the
  N200's size). **A clock set back** (NTP, the owner) stretched a pause by the whole
  step (a day → 1445 min): entries stamped after now count as now and no lockout
  runs past 5 min from now. A lockout and a pause are each logged ONCE
  under `server`, when they start ("8 wrong join codes from <ip> within 5 min —
  that address is locked out 5 min", "20 wrong join codes within 5 min — guest
  code rotated, joining paused 5 min"); a refused join is not logged (one device
  hammering /join used to push the 3000-line log out). Tested: `GatewayServerTest`
  (the role header per request, flipped live, a client's own dropped, no cookie
  refused; the 7 / 7 / 6 attack — 100 000 tries at one instant compare 20 and
  join none, where the old rule joined; an hour at 50/s from 5 addresses — 240
  compared, busiest 5 min 20; 64 parallel addresses; the pause's words, Retry-After
  and one log line; the pause lapsing; sessions, the paired computer and the
  owner through a pause; an address's count surviving a rotation; a search paced
  under the pause — 2 000 codes meet 100 codes, ≤ 20 each, where one code took
  all 2 000; a clock a day back — ≤ 5 min of pause / lockout), `PhoneServerTest` (end to
  end on the real API), `AppSettingsTest`, `ScreensSmokeTest` (the switch row
  at the N200's size, the log line, the live provider, Diagnostics, the Share
  card) — not on the phone.
- **Scanner settings from the browser** (`core/server/DeviceApi`, admin only):
  GET/PATCH `/api/device` (mode, roi, torch, vibration, high_res, ae_lock,
  check_ms, camera — with the phone's `cameras` listed — zoom_fit, with the
  live `zoom`, and refocus_min — with the phone's `refocus_choices`, 1.1.18,
  desktop d52) + `/api/device/snapshot.jpg` (the current UPRIGHT analysis frame —
  the space the Area fractions live in; while zoomed, the zoomed frame placed
  on a grey base-size picture, so it is still base space; `?view=camera` and the
  read-only `zoom_probe`: Zoom to fit, above). `DeviceBridge` writes `AppSettings`
  and the scan screen applies a change live — ONLY what changed (1.1.17): the
  mode reaches the camera only when it differs from the last one handed
  (`applyMode(pushAuto)`, `appliedAuto`; before, every PATCH — a torch, a check
  time, the zoom box — ran `setAuto(true)`, which put a card already scanned in
  AWAIT_NEXT back to WATCHING and filed it twice two ticks later:
  `deviceApi_aPatchThatKeepsTheModeNeverReArmsTray`; the update lock lifting
  follows the same rule, `updateLock_liftingItNeverReArmsTray`, and puts back
  the line "Update required" covered — the scanner it only paused may still sit
  in AWAIT_NEXT — `updateLock_liftingItPutsBackTheScannersLine`), a new Area only when it
  changed (it re-learns the tray), the status line only when the mode or Area
  changed or a drawing was cancelled. A PATCH while the scan screen is STOPPED
  (no screen on the bridge — Tap to scan stops it after every scan, Scans and
  Settings do too) reaches AppSettings only and is caught up on return
  (`onResume` → `applyStoredModeAndArea`, only what moved; a plain return
  touches neither). During the zoom probe a PATCH is stored at once, cancels
  the probe and is applied after its end. The desktop "📱 Scanner" panel shows
  only when `/api/device` answers.
- **Storage:** no backups, no pruning; at 10,000 scans Settings shows size +
  free space as a warning (owner).
- **versionName/versionCode come from pyproject.toml** (1.2.3 → 10203).
- **The release key lives ONLY in repo secrets** (`ANDROID_KEYSTORE_B64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`; made once by
  `scripts/make_android_keystore.py`) — never committed (`*.p12`/`*.jks`/
  `*.keystore` are gitignored). There is **no debug-key fallback**: without
  them `release.yml` FAILS and publishes nothing (a differently-signed APK
  installs only after an uninstall, which wipes the scans). `auto-tag.yml`
  must keep `secrets: inherit`.

## Working with the owner

- **Releases are the owner's call.** Build on the branch, open a DRAFT PR
  (version already bumped), report, and merge ONLY on an explicit "merge"
  (or "merge when ready" → merge once CI is green). The owner likes several
  requests batched into one release and to "talk it through" first.
- The owner tests on the real rig (OnePlus Nord N200 on a mount over a tray;
  the Windows PC is only a browser) and reports by photo/paste. Settings →
  Diagnostics → Copy diagnostics is the paste worth asking for. Never claim a
  device behaviour works until they've tried it — say "tested in code, not on
  the phone".
- Measure, then change: when a rule is in doubt, score the candidates against
  ground truth (the OCR rule was chosen from a scored table over generated
  strips, and a first attempt was rejected by it), and put the numbers in the
  commit message.
- Verify every UI change by driving the real page in headless Chromium
  (Playwright, `executable_path` = `/opt/pw-browsers/chromium-*/…/chrome` in
  cloud sessions) and every Android change with the gradle line above. A
  scan-screen layout change also needs a test at the N200's size
  (`@Config(qualifiers = "w411dp-h914dp-xxhdpi")`) that asserts each control
  has a size and sits on screen — "the view exists" is not enough (1.1.7).
- Bug sweeps: parallel reviewers (one Android, one web/server) find real bugs;
  verify each finding before fixing, add a test that fails without the fix.
- Decisions that stand: export is "Export to CSV" (column builder) +
  "Download TXT" — no Mana Exchange branding in the UI; swipe a scan row to
  delete (5 s Undo; on the desktop the row's 🗑, same Undo); cards/min + battery estimate on the scan screen. The
  ManaExchange store is the owner's own project (`DarylNo/v0-ManaExchange`);
  an MX-inventory integration was built and REVERTED — ask before rebuilding.

Open threads:
- **State at the last handoff (2026-11-09):** v1.1.19 is released (2026-11-06,
  `mtg-card-scanner-android.apk` published; PR #44 squash-merged as
  `74cd69f`), so every older install is update-locked until it updates. No PR
  is open. Every `claude/*` branch on the remote is merged (squash) except two
  closed drafts, `claude/change-fixtures-deterministic` (#41) and
  `claude/pin-pillow-12` (#38, its pin landed another way); deleting branches is
  the owner's call. Nothing is scheduled for this repo. The owner's last asks
  were all shipped in 1.1.19; the one question left open is below (delete
  Undo in the desktop's detail pane).
- **Owner to confirm on the rig (1.1.15–1.1.19, all tested in code, not on the
  phone):** 1.1.15 Dim when idle on the N200's real brightness range
  (Diagnostics' "phone brightness S/MAX"); 1.1.17 / 1.1.18 the one-time F2F
  re-price migrations (stored prices came back; "store … opened in N ms"; List
  cards and the meld halves Midnight Scavengers EMN 96 / Graf Rats EMN 91 now
  priced); 1.1.18 Refocus on a card (a "periodic refocus" `focus` line after
  5 min, a tap pausing it); 1.1.19 Guests get full access ON by default (a
  Share-code browser can delete; turned off, its controls go within 30 s and a
  refused action says why), the desktop row 🗑 + 5 s Undo (hover, keyboard,
  touch; the last row's 🗑 clear of the bar with Windows' scrollbar), the join
  pause / the code changing after 20 wrong codes and the Share screen showing
  the new code at once, and the Zoom-to-fit aimer's visibility on the real tray.
- **Awaiting the owner:** the desktop detail pane's "Delete this scan" still
  deletes at once (no 5 s Undo, unlike the row 🗑) — offered at 1.1.19, not
  answered; build it only if they say so.
- Owner to confirm on the rig: no phantom scans (incl. after visiting other
  screens), the scan acknowledgement, the Tap-to-scan default Area's size
  (shadows vs collector-line OCR).
- Owner to confirm on the rig (1.1.5 — all of it tested in code, not on the
  phone; every timing above is x86 measured, N200 estimated): the live outline
  on real sleeved cards (the TRIGGER line's "outline found" / "no outline"),
  the ✓ going blue at the trigger and snapping to the capture's corners with
  no grey in between (also at a 250 ms check time), the grey dashed
  card-shaped watch window while waiting, the `outline` line per capture (card
  height in frame px — a shutter scan's window comes from the capture quad),
  the analyzer line's outline / texture ms on the N200, and the `shadow`
  lines (≤ 1/s, ≤ 10 per wait; the per-wait maximum now rides the `detect`
  "wait over" line). The camera-zoom question moved to the zoom-steps item
  below.
- Owner to confirm on the rig (1.1.7–1.1.9, all tested in code, not on the
  phone): the bottom row (Share over Scans · shutter · Area) is back on 1.1.8+;
  the outline stays on the card after a capture and ignores the odd "much
  bigger card" (Diagnostics' "outlier(s) dropped"); the card-shape gate — no
  scans of an empty tray / glare, real sleeved, dark and foil cards still scan
  (watch the `detect` log for "TRIGGER refused" and "adopted the view as the
  empty tray"; WHITE-BORDERED cards were answered on 1.1.10 — NOT picked up —
  and are now the printed-detail fallback's DEBUG REPORT item below); the camera pill (shows
  only if Android exposes >1 back camera on the N200; is a 24 dp swipe
  comfortable?); the compare block's Scan size slider; and the Camera setting
  on a tester's phone.
- Owner to send a DEBUG REPORT from the current build (1.1.19; any build from
  1.1.14 on has these `detect` lines, and the update lock stops older ones
  scanning) (Settings → Diagnostics → Share debug report) of a Tray session with white-bordered and old-bordered cards,
  including card SWAPS without lifting: the `detect` lines say whether the
  outline found each card ("TRIGGER box … outline found") or the printed-detail
  fallback took it ("accepted without an outline — printed detail N% … learned
  tray smooth there (texture N%) · ring round the box N%"; a refusal on a
  textured tray says "the learned tray is textured there"), and
  each wait's "wait over" line says why a swap was or wasn't seen. That report
  decides the real fix (the fallback is a hedge, tested in code, not on the
  phone); also say how tightly the Area is drawn round the card (an Area the
  card fills is where the outline measurably gives up).
- Owner to confirm on the rig (1.1.14, tested in code, not on the phone): "is
  it focusing?" — read the `focus` lines of a Tray session (does the first-card
  pass answer FOCUSED inside the 1.5 s hold, or time out? how far does the lens
  move — the dpt before vs after?), the `done #k` lines' three sharpness values
  and AF state (a soft burst, a burst shot mid-sweep in ACTIVE_SCAN), and that
  Diagnostics' camera block now says "live" with a `focus:` line. That data
  decides the Phase-3 focus work (a better watchdog). The periodic Tray re-lock
  shipped in 1.1.18 ("Refocus on a card", default 5 min PROVISIONAL): from a
  long Tray session's report, read each "periodic refocus" pass — did it move
  the lens (dpt before vs after), did the next captures get sharper, how long
  the camera took to answer (a periodic card waits for it, ≤ 5.5 s) — and set
  the default by that.
- Owner to run the zoom steps on the rig (1.1.17, all tested in code, not on
  the phone): Settings → Diagnostics → **Run the zoom steps** (about 10 min) —
  A1 (Tray, the Area drawn tight round where the cards land; 10 cards with 2+
  printings and a printed set code + collector number, no foils), the probe
  step (Run probe on the card A1 left, hands off ~30 s, then again on a
  DIFFERENT card), B (Zoom to fit on — did the card left stay un-scanned?), A2,
  Done → send the debug report it shares (then delete the 30 test scans). The
  report's `zoom VERDICT` decides Zoom to fit ON by default and, with the A/B's
  outcomes and strip detail, whether `MAX_ZOOM` 2.0 stands (the probe measures
  no knee); the `probe` lines answer the old rig questions — does the N200
  report CONTROL_ZOOM_RATIO, is the zoom the centred crop (H fit, centre
  drift), frames still at the old zoom after the drops (SETTLE_DROP_FRAMES),
  does the AF lens hold across zooms. Also from the session: the post-fit
  residual, no phantom scans / re-learns after Scans and Settings round trips
  while zoomed, the desktop's Camera view at B's zoom (focus at the stream's
  own pixels), the off-centre hint over the real mount. A follow-up PR then
  sets MAX_ZOOM (everywhere: ZoomFit, `describe`, ZoomFitTest's bound, the
  desktop's and `settings_zoomfit_desc`'s "up to 2×"), SETTLE_DROP_FRAMES and
  the default strictly by those rules; z_pipe (880 ÷ the 1× card) says where
  the 630×880 identification warp stops gaining pixels — raising it is a
  reference change, the owner's call. "Fit to cards" (suggesting an Area from
  where cards land) stays proposed, not approved.
- Ranking time for heavily reprinted names: candidate images are already
  disk-cached (reference `visual_match` card_images cache; phone
  `CachedImageSource`, a 4000-file / 80 MB LRU); what remains is the first
  uncached fetch of up to 120 images (`_cap_candidates`). Measure it on the rig
  before building more.
- The List (1.1.17, tested in code, not on the phone — ML Kit's reading of a
  List strip is inferred from the shared rule): scans filed BEFORE 1.1.17
  that were auto-picked (⚠) from a 2015-frame collector line may be the List
  copy filed as its original — nothing re-checks them. To win back the
  auto-picks the twin rule costs, a planeswalker-icon test needs rig photos of
  5–10 List cards and their originals (2015 and old frames). Not reachable
  today: List prints of names past the 120-printing cap (basic lands) and
  older List prints in the manual search (newest 40); ulst twins (plain
  numbers — would need illustration_id). Store side (owner's repo): the
  consignor mass-entry drops "PLST A25-141" lines and reads "Non-Foil" as foil
  (admin mass-entry imports them correctly).
- Proposed, awaiting the owner: a measured card-detection goal — score the
  current detector from real debug reports (phantom triggers, captures with no
  card quad, first-try identification) before changing anything; the trigger
  stays occupancy + stillness, improvements go to the card judge. The
  instrumentation is in (1.1.5): every capture logs the card's size in frame
  px and whether a live outline was found, the analyzer line reports outline /
  texture ms, and shadow mode logs what the texture-change signal would have
  done — one rig session (swaps, lift-and-replace, hand passes, leaning in,
  lights changing) decides `START_THRESHOLD` and whether "watch the card shape
  for a new card" ships (phone.html first).

## Release / distribution

- A release is `mtg-card-scanner-android.apk` only; `publish` runs only when
  the release-signed APK built. `workflow_dispatch` on `release.yml` takes an
  existing tag (the way back after a failed release: add the secrets, re-run).
- **Releasing = merging a version bump.** `auto-tag.yml` watches master: when
  pyproject.toml's `version` has no `v<version>` tag, it tags that commit and
  calls `release.yml` (a GITHUB_TOKEN tag push never triggers `push: tags`).
  Claude Code cloud sessions can push ONLY their own branch — tag pushes are
  refused 403 — so bump the version in a PR and merge it. Never bump without
  meaning to release: the merge IS the release, and the update lock then
  stops every older install scanning until it updates.
- **ALWAYS end a push session with a `version` bump in pyproject.toml** —
  master-only commits reach nobody. The one exception is a docs-only change
  (CLAUDE.md / READMEs, nothing in the APK): merge it without a bump, because a
  bump would make the update lock stop every phone scanning for a release
  with nothing new in it.
- **The repo MUST stay PUBLIC** — the APK download, the art pack and the
  update lock's `releases/latest` are all anonymous. Verify with an
  UNAUTHENTICATED request (GitHub answers 404, not 403, for a private repo;
  a successful `git push` proves nothing).

## Testing

`pytest tests/` (642 at 1.1.19, fakes only, 3-OS CI) + the 12 fixture
`--check`s + the gradle line (CI job `android`) — CI runs on pushes to master
and on every PR. The five `scripts/check_*_ui.py` Chromium drivers are NOT in
CI: run them by hand after any page edit (Build / test, above). Real scans:
`scan_images/` (the reference server's photo folder) is gitignored, so a fresh
clone has none; the committed ones are
`android/core/src/test/resources/ranker/scans/` (e.g. `edict_pal01.jpg`,
Diabolic Edict — the OCR proof); ask the owner for rig photos or a debug report
when you need more. When tuning detection or
ranking, test against real scans before shipping — every threshold here was
set by measurement, and several "obvious improvements" (geometry gates,
hi-res region hashes) failed empirically first.
