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
                     trigger), CardQuad/Flatten, PHash/ArtHasher/ArtMatcher
                     (bit-exact fingerprint), IdentifyPipeline, PrintingRanker,
                     OcrMatch, Popularity, and core/server/ — ScanStore,
                     PhoneApi (the /api surface), PriceSweep/PriceWorker,
                     Export, DeviceApi, ScanServer (routing + admin-only rules)
android/app/         Android: camera/ (CameraX, FrameRing, ScanAnalyzer),
                     capture/, ident/ (ArtPackStore, PhoneIdentifier, ML Kit
                     OCR), net/UploadQueue, phoneserver/ (PhoneServer,
                     PhoneBackend, LocalScanUploader, SqliteScanStore, PhoneF2f),
                     gateway/ (GatewayServer, GatewayService, AdminPairing),
                     update/UpdateLock, ui/ (MainActivity = scan screen,
                     PanelActivity = review WebView, Settings, Share)
server/              reference FastAPI app, store.py, export.py, static pages
mtg_card_scanner/    reference pipeline: card_detect, art_index, art_pack,
                     scryfall, visual_match, ocr_id, artwork, popularity,
                     facetoface
scripts/             export_*_fixtures.py (generate the Kotlin fixtures from the
                     reference; `--check` in CI), make_android_keystore.py
tests/               pytest (~450, all fakes — no camera/network)
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
python scripts/export_api_fixtures.py --check     # one per export_*_fixtures.py
```

Run the reference server locally with `mtg-card-scanner --no-browser` (a
supervisor, `mtg_card_scanner/launch.py`, serving HTTPS on :8443) — for
reproducing reference behaviour only.

**Bump `UI_VERSION`** in `phone.html` / `desktop.html` on every edit to them
(the banner reads `d<N> · <version> (phone)`; stale-page debugging burned a
full session before the banners existed).

## Pipeline

```
scan screen (Tray trigger or shutter tap)
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
detection sample — found edges, not the mask) sees no card shape, and asks
again on the next steady ticks (the card-shape gate, below). The scan Area
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
("A25-85") win outright so List copies don't misattribute; ambiguity = no-op.
An OCR promotion also requires ART AGREEMENT (within _OCR_ART_SLACK of the
best candidate) — a misread code can never promote or auto-pick a printing
that doesn't look like the scan.

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
scan is price-checked at once (it jumps the queue).

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
- **A "check" row is never filtered away**, by any bar, in either mode.
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
OnePlus Nord N200 5G (camera id 0 only), minSdk 29, arm64-v8a.

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
  equal bits.
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
  picking, "Pick SET #n" picks the compared one. A 664×926 photo is
  scaled 1.053 so just the card shows (ScanPhoto's 17/23 buffer); older
  photos show as they are. Mode and base layer persist in localStorage
  (`cmpMode`, `cmpTop`). Verified in headless Chromium (compare_ui.py).
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
  mode (folded into the 1.053 fill scale by `applyScanScale()`), the % button
  resets; persisted per viewer (`cmpScale`). Manual on purpose — the photo's
  card is at a known 95 % only when the quad was right (a sleeve edge makes
  it read larger).
  Scan-screen Share is the standard share glyph (`drawable/ic_share`,
  `ScanChrome.iconChip`, named "Share" for TalkBack).
- Tap to scan always has an Area: with none set the app draws
  `HandheldGuide.defaultArea` (a centred card at 60% of the limiting side +
  15% pad — back from the card so the phone's shadow stays off it; a starting
  point, not measured). A drawn Area is never replaced; in Tap to scan the
  Area button draws, it never clears. Mount focus: locks on the Area centre,
  then on the first card; tap the preview to lock elsewhere.
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
- **The live log** (`core/DebugLog.global`, a 3000-line ring, mirrored to
  logcat as `CardScanner`): triggers (box, mask %), captures (flattened or
  not, timings), each identification (name, printings, top, OCR ✓, stage
  ms), queue outcomes and retry errors, F2F requests, server start/stop/idle
  and joins, card-database and update checks, remote setting changes, crashes
  (an uncaught exception writes the log to `last-crash.txt`; the next report
  carries it). Read it live on the paired computer (🐞 → App log, admin-only
  `GET /api/debug/log?since=`), as a report (`/api/debug/report.txt`, 🐞 →
  Copy report / Download; Settings → Diagnostics → Share debug report), or
  with `adb logcat -s CardScanner`. **Never log a secret** — no join/admin
  codes, cookies or tokens (`joinsAreLoggedWithoutTheCode`). The page's copy
  falls back to a textarea: the LAN page is plain HTTP, where browsers give no
  `navigator.clipboard`.
- **The scan signal** is ONLY the blue ✓ on the card (a centre ✓ badge with no
  shape), held for Settings/📱 Scanner "Check mark time" (250–10000 ms, default
  2500, `check_ms`), plus two short buzzes (Vibration switch).
- **Live card outline + blue capture + card-shaped watch window (owner,
  2026-10-02: "see it draw an outline around the card, then have it go blue on
  image capture … then watch that area for new cards"). DISPLAY ONLY — the
  trigger, `Detection.kt`, `AutoScanner.kt` and phone.html's detection are
  untouched.** `core/CardOutline` runs the reference port `CardQuad.find`
  (unmodified) on the scanner's existing 176×MH detection sample — never a full
  frame (est. 65–90 ms on the N200 vs ~4–5 ms; measured 1.3 ms on x86) — and
  pulls each side in by 5·(1−1/k) sample px (k = frame px per sample px: the
  finder's dilation swells every side ~5 px at whatever resolution it runs;
  CardOutlineTest holds it within 2 sample px of the full-resolution quad on the
  detect fixtures). `ScanAnalyzer` finds it on occupied ticks AFTER the
  tick/trigger/capture handling and inside runCatching (a helper that threw
  before `fire()` would skip `captureDone()` and leave Tray dead until a
  double-tap; `anOutlineFinderThatThrowsNeverSkipsTheCapture`). `OverlayView`
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
  Scan-screen layout: Tray|Tap · ⚙ over Share-above-Scans · shutter · Area.
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
  wait when the wait ends (with its tick count), every crossing of 8 with its
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
  lights, foil, AE lock on, done), counts the captures during each step,
  applies the settings a step needs (AE lock / vibration / check time) and
  restores the owner's own at the end, asks Yes/No where a step is a visual
  check, and writes a `test` marker into the debug log at every transition
  (`step k/N START/END … n capture(s)`, answers, `session START/END`) so the
  `shadow` / `outline` / `capture` lines read against what was being done.
  Finish opens the share sheet with the debug report. Display + logging only.
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
  HTTP (the pairing dialog says to pair on a trusted network).
- **Roles.** A computer pairs as ADMIN with a one-time 8-digit code (10 min,
  single use, its own budget of 10 wrong tries, retired by a guest-code
  rotation) → a long-lived `cs_admin` cookie (only its SHA-256 on disk;
  "Forget paired computers" revokes). The 6-digit Share code makes a GUEST:
  review/pick/edit/flag — no delete, clear, export, PATCH `included`, sweep
  controls or `/api/device` (`ScanServer.adminOnly`, 403). The gateway sets
  `x-cardscanner-role` on every proxied request and DROPS a client's own (no
  header = guest, fail closed). The phone's own review screen is admin via
  `AdminPairing.ownerToken` (in memory, never persisted or counted, untouched
  by Forget). Pages start as guest (`<body class="guest">`) until `/api/me`
  answers; a guest's swipe flags, the admin gets "Delete flagged (n)" (only
  the ids it confirmed). The gateway answers LAN/hotspot peers only (socket
  address, not headers), locks an IP after 7 bad codes, and rotates the code
  after 20 bad codes from anywhere in 5 min.
- **Scanner settings from the browser** (`core/server/DeviceApi`, admin only):
  GET/PATCH `/api/device` (mode, roi, torch, vibration, high_res, ae_lock,
  check_ms) + `/api/device/snapshot.jpg` (the current UPRIGHT analysis frame —
  the space the Area fractions live in). `DeviceBridge` writes `AppSettings`
  and the scan screen applies a change live (a new Area only when it changed —
  it re-learns the tray). The desktop "📱 Scanner" panel shows only when
  `/api/device` answers.
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
  cloud sessions) and every Android change with the gradle line above.
- Bug sweeps: parallel reviewers (one Android, one web/server) find real bugs;
  verify each finding before fixing, add a test that fails without the fix.
- Decisions that stand: export is "Export to CSV" (column builder) +
  "Download TXT" — no Mana Exchange branding in the UI; swipe a scan row to
  delete (5 s Undo); cards/min + battery estimate on the scan screen. The
  ManaExchange store is the owner's own project (`DarylNo/v0-ManaExchange`);
  an MX-inventory integration was built and REVERTED — ask before rebuilding.

Open threads:
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
  the analyzer line's outline / texture ms on the N200, the zoom line in
  Diagnostics, and the `shadow` lines (≤ 1/s, ≤ 10 per wait + the "wait
  over" summary).
- Proposed, not approved: "Fit to cards + zoom" (auto-fit the scan Area).
- Ranking time for heavily reprinted names (cache candidate images).
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
  master-only commits reach nobody.
- **The repo MUST stay PUBLIC** — the APK download, the art pack and the
  update lock's `releases/latest` are all anonymous. Verify with an
  UNAUTHENTICATED request (GitHub answers 404, not 403, for a private repo;
  a successful `git push` proves nothing).

## Testing

`pytest tests/` (~450, fakes only, 3-OS CI) + the fixture `--check`s + the
gradle line (CI job `android`). Real-scan artifacts live in `scan_images/`
(e.g. scan 837 = the Diabolic Edict OCR proof). When tuning detection or
ranking, test against real scans before shipping — every threshold here was
set by measurement, and several "obvious improvements" (geometry gates,
hi-res region hashes) failed empirically first.
