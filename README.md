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
While a card sits in the Area the app draws its **outline**; when the picture
is taken the outline turns **blue with a ✓** and stays on the card (held for
the **Check mark time**, 2.5 s by default) plus two short buzzes — that is the
only signal. The card stays outlined (grey) while it waits for the next one;
the outline is averaged over the last few looks with outliers thrown away, so a
single bad find cannot jump it. When the card is lost, a grey dashed card shape
marks where it is watching. In Tray mode a scan only fires when that outline
is there: glare, a shadow or a hand that holds still is "Something in the
Area — no card shape yet…", never a scan, and after a couple of seconds the
app adopts the still view as the empty tray. The shutter always scans.

**The phone never sleeps while the app is open** — on the camera, the scan list,
Settings or Share. After a minute with no touch and no scan the screen dims
(scanning carries on); a touch or the next scan brings it back. Settings →
Screen → **Dim when idle** sets the time (30 s, 1, 2 or 5 min) or turns dimming
off.

The top of the screen shows the version and the server state
(`serving at <ip>:8090`), cards/min and a battery estimate. Every button is at
the bottom, within thumb reach: Tray|Tap · **⚙ Settings**, then **Share** over
**Scans** · shutter · **Area**.

**Camera** — the pill at the top of the live view shows which camera is
scanning ("Auto · 13 MP", "Cam 3 · 2.0 MP fixed"). Swipe it left or right, or
tap it, to step through Automatic and each of the phone's back cameras. It
shows only on a phone with more than one to choose from. Settings → Camera
picks the same thing from a list, for testers on phones other than the Nord
N200. Each lens is listed with what
tells it apart (megapixels, autofocus or fixed focus, focal length).
**Automatic** (the default) uses the main back camera. Changing it re-learns
the empty tray. A paired computer can change it too, from 📱 Scanner.

## Reviewing

**Scans** opens the scan list on the phone: each scan's printings ranked by
artwork, the printing picker, condition / finish / quantity, and live prices.
Picking a printing takes you back to the list. On a card, **Compare** shows
the scan photo beside the printing being considered, or overlaid — *Wipe*
drags a divider across the two, *Blend* fades one over the other, and the
base layer can be either; ‹ › or a swipe across the pictures steps through
the printings, *Pick* picks the one shown. **Scan size** scales the scan
photo so its card matches the printing's.

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

## External card list

You can point the scanner at a list of card **names** you care about, and every
scan of one of them gets a chip in the scan list and on the card. For example,
**Wanted** for cards you're looking for and **Sold out** for ones you've run out
of. The list is read-only: the scanner only downloads it and never sends
anything back. It matches by name, so any printing of a listed card counts.

**Setup**

1. Publish the list as JSON at an `https://` address the phone can reach. Any
   access key goes in the address itself (its query string), e.g.
   `https://example.com/cards.json?key=…`. Plain `http://` addresses are refused.
2. On the phone: **Settings → External card list → List URL**, paste the
   address exactly as given, **Save**.
3. The list is fetched when the app starts and when you tap **Refresh now**.
   Settings (and Diagnostics) show "N cards, updated <time>", or the last error.
   The last good list is kept on the phone, so being offline, a server error
   or a broken file never removes your chips, and scanning never waits for it.
4. To turn it off, open List URL and tap **Turn off** (an empty address = off):
   no downloads, no chips.

The address is stored only on the phone (never in this repository), and only
its host name is shown in Settings, so screenshots don't leak a key.

**Format**

```json
{
  "generated_at": "2026-10-12T09:00:00Z",
  "cards": [
    { "name": "Lightning Bolt", "tags": ["wanted"] },
    { "name": "Jötun Grunt", "tags": ["wanted", "sold_out"] },
    { "name": "Delver of Secrets // Insectile Aberration", "tags": ["restock"] }
  ]
}
```

- `cards` is required (a list). Each entry needs a `name`; `tags` is a list of
  strings. Entries without a usable name are skipped, and unknown fields are
  ignored, at the top level and in entries alike.
- Known tags: `wanted` → **Wanted**, `sold_out` → **Sold out**. Any other tag
  shows as a plain chip with its own text (`restock` above).
- Names match ignoring accents (*Jotun* = *Jötun*), letter case, curly vs straight
  quotes, en/em dashes vs `-`, extra spaces and the spacing around `//`. A
  double-faced, split or adventure card matches on its full name **or any face**
  (`Delver of Secrets` alone matches the card above).
- Chips are a yes/no fact beside the popularity chip; they never change a
  card's popularity tier. They are worked out each time the list is shown, so a
  refreshed list re-tags cards you scanned earlier.
- **Filters → Tagged only** (shown once a list is set) narrows the scan list to
  tagged cards (plus any that need checking). It only changes what you see:
  *Exclude filtered* and the export ignore it.
- **Wanted cards always show.** The price and popularity filters never hide a
  card tagged `wanted`, in *match any* or *match all*, so *Exclude filtered*
  never drops one from the export either. The All / Needs pick / Selected /
  Problems chips, *Tagged only* and the name search still apply.

## Using a computer (optional)

On the same Wi-Fi, or the phone's hotspot:

- **Admin:** Settings → *This phone's server* → **Pair a computer as admin**
  shows a QR and a one-time 8-digit code (10 minutes). Open the link on the
  computer. That browser is remembered as admin until **Forget paired
  computers**: it can review, pick, delete, clear, **export** (the CSV
  column builder, or TXT in Mana Exchange's mass-entry format) and change the
  phone's scanner settings (**📱 Scanner**: mode, scan Area drawn on a live
  picture of the tray, camera, torch, resolution, exposure lock, vibration, check
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
the same lines. **Run the measurement session** (same screen) walks you
through the rig tests step by step on the scan screen, counting captures and
marking the log, then shares the report at the end.

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
