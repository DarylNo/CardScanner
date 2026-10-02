# MTG Card Scanner

An Android phone on a mount over a tray becomes a Magic: The Gathering card
scanner. Put a card down: the phone photographs it, identifies it **on the
phone** by its artwork (a perceptual-hash fingerprint — no LLM, no cloud
vision, no API keys), confirms the exact printing by reading the card's
collector line, prices it against **Face to Face Games**, and keeps it in its
own scan list. A computer is optional: a bigger screen for reviewing and
exporting, served by the phone itself.

---

## Install

1. Download `mtg-card-scanner-android.apk` from the
   [latest release](https://github.com/DarylNo/CardScanner/releases/latest)
   and open it on the phone. Allow installs from your browser when Android asks.
2. Open **Card Scanner** and allow the camera, then notifications (the
   server's notification carries its **Stop** button).
3. The first launch downloads the **card database** (the art fingerprints of
   every Magic artwork, a few MB). After that it re-checks weekly on Wi-Fi.
   Scans taken before it arrives wait and are identified once it does.

Identifying needs internet for Scryfall (the list of a card's printings) and
pricing needs it for Face to Face; offline scans wait in the queue and finish
when the phone is back online.

## Scanning

The scan screen has two modes (a new install starts in Tap to scan):

- **Tray** — hands-free. The phone learns the empty tray, waits for a card,
  waits for it to be still, captures, and waits for the next card. Switching
  to Tray asks you to draw a box around where the card will sit. Double-tap
  the preview to re-learn the empty tray.
- **Tap to scan** — tap the shutter. Tap to scan always uses a scan **Area**;
  if none is set the app draws one for you (a card-shaped box in the middle,
  sized so the phone sits back far enough to keep its shadow off the card).
  When the card is identified its scan **opens**, with prices appearing live;
  Back returns to the camera.

**Area** — drag a box around the tray; detection and capture stay inside it.
With a scan taken, the only signal is a **blue ✓** on the card (held for the
**Check mark time**, 2.5 s by default) plus two short buzzes.

The top of the screen shows the version and the server state
(`serving at <ip>:8090`), cards/min and a battery estimate.

## Reviewing

**Scans** opens the scan list on the phone: each scan's printings ranked by
artwork, the printing picker, condition / finish / quantity, and live prices.
Picking a printing takes you back to the list. On a card, **Compare** shows
the scan photo beside the printing being considered, or overlaid — *Wipe*
drags a divider across the two, *Blend* fades one over the other, and the
base layer can be either; ‹ › or a swipe across the pictures steps through
the printings, *Pick* picks the one shown.

- A card is **auto-filed** (marked ⚠) when only one printing exists or when
  the collector line read off the card confirms the printing. Everything else
  waits for you to pick.
- Every scan keeps its own row, in scan order, with a photo of the card
  (straightened, filling 95% of the picture); swipe a row to delete it (5 s
  Undo). **Filters → Bulk actions** has *Clear unpicked* and *Clear all*;
  **Settings → Scans → Delete all scans** does the same from the app.
- **Filters:** a price band and a popularity floor, combined by *match any*
  (default) or *match all*. They stay set when you leave and come back.
- **Popularity** comes from EDHREC's Commander rank (free, from the same
  Scryfall data); cards EDHREC doesn't rank (basic lands, Commander-banned
  cards) show as *unranked*, not as unpopular. Format legality is shown too.

**Pricing** runs by itself: whenever something is unpriced the phone checks
Face to Face every few seconds, and sleeps when nothing is owed. Picked cards
price their printing; unpicked ones price every printing that shares the
scanned artwork.

## Using a computer (optional)

On the same Wi-Fi, or the phone's hotspot:

- **Admin:** Settings → *This phone's server* → **Pair a computer as admin**
  shows a QR and a one-time 8-digit code (10 minutes). Open the link on the
  computer. That browser is remembered as admin until **Forget paired
  computers**: it can review, pick, delete, clear, **export** (the CSV
  column builder, or TXT in Mana Exchange's mass-entry format) and change the
  phone's scanner settings (**📱 Scanner**: mode, scan Area drawn on a live
  picture of the tray, torch, resolution, exposure lock, vibration, check
  mark time).
- **Guests:** the **Share** screen shows a 6-digit code. Guests can review,
  pick, edit and **flag** cards for deletion; the admin sees "Delete flagged".
  **New guest code** ends every guest session.

The connection on your network is plain HTTP — pair on a network you trust.
The phone's server stops by itself after 30 minutes with the app in the
background and nobody connected; opening the app starts it again.

## Updates

When a new release is published, the app shows **Update required** and stops
scanning until the new APK is installed (**Download update** is on that
screen). Your scans are never locked away: the scan list, export and the
computer link keep working, and cards already scanned still finish
identifying. Updates install over the app and keep your scans.

## When something looks wrong

The phone keeps a live log of what it's doing — each trigger, capture,
identification (with its timings), price lookup and error. On the paired
computer, **🐞 → App log** shows it as it happens; **Copy report** /
**Download** give the whole thing plus the phone's diagnostics, ready to
paste into a bug report. On the phone: Settings → Diagnostics → **Share debug
report**. With USB or wireless debugging on, `adb logcat -s CardScanner` shows
the same lines.

## Your data

The scans live **only on the phone** — there are no backups, and uninstalling
the app deletes them. Export a CSV or TXT from the paired computer to keep a
copy.

---

## How it works

```
camera (Tray or Tap to scan) → flatten the card, with a margin
  → art fingerprint (pHash) → nearest of ~50k artworks → card NAME
  → Scryfall: every paper printing of that name
  → rank the printings against the photo (art + title + text box)
  → OCR the collector line → confirm the exact printing
  → file the scan with its photo (the card straightened, 95% of the frame)
  → price it on Face to Face → review / export
```

The card database (`art-pack` release) is rebuilt weekly by CI from
Scryfall's bulk data.

## Development

```
android/            the app — :core (pure Kotlin: detection, fingerprints,
                    identification, the phone server's API, pricing, export)
                    and :app (Android: camera, OCR, storage, UI, the server)
server/, mtg_card_scanner/
                    the Python reference implementation (FastAPI server +
                    identification pipeline). It is not shipped; every Kotlin
                    port is tested against it with generated fixtures.
server/static/      phone.html / desktop.html — the review pages, served by
                    the phone (copied into the APK at build)
scripts/            fixture exporters (--check runs in CI), keystore helper
tests/              pytest suite for the reference implementation
docs/index.html     the download page (GitHub Pages)
```

```bash
# Android (from android/; JDK 17 and Node on PATH)
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# Reference implementation + fixtures
pip install -e ".[test]"
pytest tests/ -q
python scripts/export_api_fixtures.py --check      # and the other export_*_fixtures.py
```

Change behaviour in the Python reference first, regenerate the fixtures, then
port it. Details for the app — build, sideload, device checklist — are in
[`android/README.md`](android/README.md).

**Releasing:** bump `version` in `pyproject.toml` (the APK's version comes
from it) and merge to `master`; CI tags `v<version>` and publishes the signed
APK. Releases need the signing secrets `ANDROID_KEYSTORE_B64`,
`ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS`; without them the release
fails rather than ship an APK that can't update an installed one.

License: MIT.
