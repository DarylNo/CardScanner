# CardScanner Android app

**The phone is the card scanner** (Stage 4, 1.1.0 — owner decision
2026-09-30: full cutover, the computer app is retired). The app photographs
each card (the tuned detection, ported from phone.html), identifies it ON the
phone (the server's art-fingerprint + collector-line OCR pipeline, ported to
`:core` and parity-tested against the Python server), files it in the phone's
own store, prices it against Face to Face from the phone's own connection, and
serves the review pages — to its own review screen and to any computer on the
LAN. A computer is an optional workstation: pair it once as admin.

The review screens (scan list, filters, printing picker, price check, the
desktop page with Export to CSV) are the server's own `phone.html` /
`desktop.html`, copied into the APK at build and served by the phone — one
review UI, not two.

Target device: **OnePlus Nord N200 5G** (Snapdragon 480, Android 11/12,
arm64). Rear camera id `0` (13 MP) only — the 2 MP fixed-focus camera id `3`
is never used. `minSdk` 29 (Android 10), `targetSdk` 35, arm64-v8a only.

## Architecture

```
CameraX Preview + ImageAnalysis (camera 0, 4:3, 1600x1200 or 2048x1536, EIS off)
   │  every frame (~90 ms): copy into FrameRing (4 preallocated NV21 slots)
   │  every ~200 ms: GraySampler → 176×MH luma sample
   ▼
AutoScanner.tick()          :core — phone.html's detection + state machine, ported
   │  Trigger (Auto on)  or  shutter tap (Auto off, or any time)
   ▼
CapturePipeline             last 3 ring frames → sharpest (Laplacian variance)
   │                        → CardQuad → Flatten with a margin → JPEG = PRIMARY
   │                        + the 3 raw ROI crops = FALLBACKS
   ▼
UploadQueue                 persistent FIFO on disk, one worker, retried with backoff
   │  primary;  if no_card / not identified → the 3 raw frames, replacing its row
   ▼
LocalScanUploader           the phone's "POST /api/scan": PhoneIdentifier
   │                        (IdentifyPipeline — art pack, Scryfall, ML Kit OCR)
   │                        → PhoneApi.fileScan (golden-tested against /api/scan)
   ▼
PhoneServer                 SQLite store + photos, PriceWorker/PriceSweep (F2F over
   │                        Cronet), Export, roles — served by GatewayServer on
   │                        :8090 (GatewayService, foreground) to the LAN and to
   ▼                        PanelActivity (WebView on 127.0.0.1, owner cookie)
outcome → status line / haptic / review screen
```

Modules and packages (`io.github.darylno.cardscanner`):

| Where | What |
|---|---|
| `:core` (pure JVM) | `Detection`, `AutoScanner` (phone.html port), `CardQuad` (server `card_detect` port), `Flatten`, `GraySampler`, `Sharpness`, `Rotation`, `RoiFrac`, `Nv21Frame`, `Nv21Bgr`. OpenCV is `compileOnly`; tests load `org.openpnp:opencv` natives. |
| `app/camera` | `CameraController` (bind, focus, torch, AE/AWB lock, diagnostics), `FrameRing`, `ScanAnalyzer` (the analysis-thread loop; all `AutoScanner` access is confined to it). |
| `app/capture` | `CapturePipeline` (sharpest frame → quad → flatten → JPEG). |
| `app/net` | `UploadQueue` (persistent FIFO, fallback retry, `ScanUploader`). |
| `app/ident` | `LocalIdentify` (the art pack — the phone's card database — and the one `PhoneIdentifier`), `ArtPackStore`, Scryfall over Cronet, `CachedImageSource`. |
| `app/phoneserver` | `PhoneServer` (the phone's server: `SqliteScanStore`, `PhotoDir`, `PhoneBackend` over `:core`'s `PhoneApi`/`PriceSweep`/`Export`/`DeviceApi`), `LocalScanUploader`, `LocalUpstream`, `PhoneF2f`, `DeviceBridge`. |
| `app/gateway` | `GatewayServer` (NanoHTTPD, roles: `AdminPairing`), `JoinCode`, `LocalAddresses`, `GatewayService` (foreground service that keeps the phone serving), `QrBitmap`. |
| `app/f2f` | `F2fFetcher` (port of `facetoface._default_get_json` pacing/retry, parity-tested against `app/src/test/resources/f2f/pacing.json`), `F2fPricer` (query ladder + SKU confirmation), `F2fCache`, `OkHttpTransport` / `CronetTransport`, `F2fProbe` (Stage 1c test). |
| `app/ui` | `MainActivity`, `OverlayView`, `PanelActivity` (review WebView of the phone's own pages), `ShareActivity` (guest code), `SettingsActivity` + `ServerSection` (this phone's server: pair a computer, card database, storage), `AppSettings`. |

Rules the app keeps (see also CLAUDE.md → "Android app"):

- **Detection is a port, proven — not a re-tune.** `DetectionDifferentialTest`
  runs phone.html's REAL detection code under Node
  (`core/src/test/resources/phone_harness.js`) and compares it with the Kotlin
  port step by step. The trigger is occupancy + stillness only; no geometry
  gates.
- **The identifier keeps the server's rules.** The phone flattens the card
  with a margin and the ported pipeline re-detects inside it, exactly as the
  server did (`IdentifyParityTest` against the real Python pipeline); no card
  gates are added on the phone.
- **Flatten contract**: `tests/phone_flatten_ref.py` is the executable spec;
  `scripts/export_detect_fixtures.py` writes the parity fixtures in
  `core/src/test/resources/detect/` from the server's own code, and
  `--check` fails CI if they drift.
- **versionName is pyproject.toml's `version`**; versionCode is derived from it
  (1.2.3 → 10203), so a release always updates the one before it.

## Build and test

Needs JDK 17, the Android SDK (`ANDROID_HOME`, or `sdk.dir` in
`android/local.properties`, which is gitignored), Node on `PATH` for the
differential test, and Python with the repo installed for the fixture check.

```bash
# from the repo root
pip install -e ".[test]"
python scripts/export_detect_fixtures.py --check   # parity fixtures match the server
pytest tests/ -q                                   # server + flatten spec

# from android/
./gradlew :core:test                  # detection/flatten parity (needs Node)
./gradlew :app:testDebugUnitTest      # net / gateway / queue / pipeline / ident (JVM)
./gradlew :app:lintDebug
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/ci.yml`, job `android`) runs exactly these on every push
and PR and uploads the debug APK as the `mtg-card-scanner-android-debug`
artifact. The release build (`release.yml`, job `apk`) publishes
`mtg-card-scanner-android.apk` on the GitHub Release — the only asset since
1.1.0, and only when signed with the release key.

A locally built **release** APK uses the release key only if these are set,
otherwise it falls back to the debug key:

```bash
export ANDROID_KEYSTORE_PATH=/safe/place/cardscanner-release.p12
export ANDROID_KEYSTORE_PASSWORD='…'
export ANDROID_KEY_ALIAS=cardscanner
./gradlew :app:assembleRelease
```

## Install (sideload) on the Nord N200

Download `mtg-card-scanner-android.apk` from the latest GitHub Release
(https://github.com/DarylNo/CardScanner/releases/latest).

**On the phone** (Android 11/12, OxygenOS):

1. Open the release page in Chrome on the phone, tap the APK, **Download**.
2. Tap the finished download. The first time, Android says the browser isn't
   allowed to install apps: tap **Settings** → enable **Allow from this
   source** → back.
   (Later: Settings → Apps & notifications → Special app access → Install
   unknown apps → Chrome.)
3. **Install**. If Play Protect warns about an unknown developer, choose
   **More details → Install anyway**.
4. Open **Card Scanner**, allow the **Camera** permission, then notifications
   (Android 13+, asked once: the server's notification carries its **Stop**).

**Over USB (ADB)**, from a computer with platform-tools:

1. Settings → About phone → tap **Build number** 7 times (Developer options).
2. Settings → System → Developer options → enable **USB debugging**; plug in
   and accept the RSA prompt on the phone.
3. `adb install -r mtg-card-scanner-android.apk`

**Updates** install over the old app (scans, pairings and settings kept) only
when both APKs are signed with the same key — see [Signing](#signing-setup).
An APK signed with another key installs only after an **uninstall, which
deletes every scan on the phone**.

## First run

Nothing to pair. The first launch downloads the **card database** (the art
pack CI publishes weekly to the `art-pack` release, a few MB) on any network;
after that the phone re-checks weekly on Wi-Fi (Settings → *This phone's
server* → **Check for a newer card database** does it now). Scans taken before
it lands wait in the queue ("waiting for the card database") and are
identified once it does. Identifying needs internet for Scryfall's printing
list; offline scans wait in the queue the same way. If that first download
fails (no connection), the queue retries it at most once a minute — no need
to restart the app.

## Auto on / Auto off

There is one mode (1.1.2 — Handheld is gone). **Auto on**: the tuned
phone.html behaviour — learn the empty tray, wait for a card, wait for it to be
still, capture, file, wait for the next card. Draw the scan **Area** to crop
sampling and capture to the tray; double-tap the preview to re-learn the empty
tray. Focus: when the camera starts it locks on the centre of the scan Area
(the screen centre with no Area) so the lens stops hunting; then the FIRST
card is focused on properly — its capture waits (≤1.5 s, once) until the
lens has focused at the Area centre with the card under it — and every card
after shoots instantly with focus held. It focuses again only when the Area
changes, the camera reopens, you tap the preview (locks where you tapped), or
a capture comes out far softer than the session's usual (bumped mount).
Auto-on
scans are hands-free.

**Auto off** — tap the shutter. Auto off always scans inside a scan Area: if
none is set, the app draws one for you — a card-shaped box in the middle of
the picture, sized so the phone sits back from the card (close enough to read
the collector line, far enough that its shadow stays off the card). Put the
card in the box, or tap **Area** and drag your own (with Auto off, Area always
draws; it never clears). When the scan is identified it OPENS: the phone
prices it at the front of the queue and shows each printing's price live;
Back returns to the camera. It is filed like any other scan (no Keep/Discard).
No match → Retry. Offline → the scan waits in the queue and opens when it is
identified (if the app is in the foreground; otherwise find it under Scans).

## A computer (optional workstation)

The phone serves your scans on port **8090** of its local network whenever
the app runs (a notification stays up; its **Stop** ends serving until the app
is opened again). With the app in the background and no computer or guest
using it for **30 minutes**, it stops by itself (no all-day battery drain);
opening the app starts it again. The guest code is hidden from the lock
screen. On the same Wi-Fi (or the phone's hotspot):

1. Settings → *This phone's server* → **Pair a computer as admin** shows a QR
   and a one-time 8-digit code (10 minutes, single use). Open the link on the
   computer (or scan the QR with it, or type the code on the join page).
2. That computer is remembered as the **admin** (a long-lived cookie) until
   **Forget paired computers**: it can review, pick, delete, clear, export
   (CSV / TXT), run pricing and change the phone's scanner settings
   (**📱 Scanner**).

Pair on a network you trust: the local leg is plain HTTP, so someone else on
the same network could copy the admin cookie.

## Guests (Share)

**Share** shows the **6-digit guest code**, a QR and the phone's addresses.
Guests on the same Wi-Fi (or your hotspot) open the link and enter the code:
they can review, pick and edit, and a swipe **flags** a scan for deletion — the
admin confirms with **Delete flagged (n)**. They can't delete, clear, export
or run pricing (the phone refuses them, not just the page). **New guest code**
ends every guest session; the server keeps running. 8 wrong codes from one
device within 5 minutes lock it out for 5 minutes.

## Signing setup

Android installs an update only if it is signed with the **same key** as the
installed app — and since 1.1.0 the phone holds the only copy of your scans,
which an uninstall deletes. So the release workflow **refuses to build without
the release key** (no debug-key fallback any more): until the three secrets
below exist, a tagged release fails with a summary saying so, and nothing is
published.

Do this once:

1. From a repo checkout with the package installed (`pip install -e .`), in a
   folder that is NOT synced or inside the repo:
   `python scripts/make_android_keystore.py`
   It writes `cardscanner-release.p12` (RSA 3072, 30 years, alias
   `cardscanner`) and prints the password **once**, the certificate SHA-256,
   and the base64 line.
2. GitHub → the repo → **Settings → Secrets and variables → Actions → New
   repository secret**, three times:
   - `ANDROID_KEYSTORE_B64` = the base64 line
   - `ANDROID_KEYSTORE_PASSWORD` = the password
   - `ANDROID_KEY_ALIAS` = `cardscanner`
3. **Back up** `cardscanner-release.p12` and the password (password manager /
   encrypted drive). Lose either and every phone has to uninstall to take the
   next release. Never commit it (`*.p12` is gitignored).
4. Cut the next release (merge a version bump). Check its `apk` job summary:
   the certificate SHA-256 must match the one the script printed.

The switch from an older debug-signed install to the release key needs one
last uninstall (before scanning anything you want to keep on the phone); from
then on updates install in place. Secrets reach both release
paths — a hand-pushed tag, and `auto-tag.yml` (it calls `release.yml` with
`secrets: inherit`).

## F2F network test (Stage 1c)

A hidden screen that answers "does Face to Face Games throttle the phone?"
(docs/PHONE_ONLY_PLAN.md). **Settings → long-press the DIAGNOSTICS header →
Network test**. It prices ~30 real cards on facetofacegames.com at the rig's
exact pacing over **Cronet** (cronet-embedded: Chromium's stack, a real Chrome
TLS handshake) and over **plain OkHttp**, and counts 429s. *Run both* goes
Cronet first (the per-IP bucket is cold for the stack we'd ship), then waits
out a cooldown (default 5 min) before OkHttp. **Copy results** puts a JSON
report on the clipboard. Cronet is also what the phone uses for Scryfall and
Face to Face every day.

## Battery settings (OnePlus)

OxygenOS kills background apps aggressively; a killed server drops the
computer and guests, and a frozen queue stops identifying.

- Settings → Battery → **Battery optimization** → (All apps) **Card Scanner** →
  **Don't optimize**.
- Open **Recents**, tap the **⋮** menu on Card Scanner's card (or long-press
  it) → **Lock**, so "clear all" leaves it alone.
- While scanning on the mount, keep the phone on a charger; the app keeps the
  screen on.

## Device test checklist

Run on the Nord N200 after installing a new build:

- [ ] Fresh install: no pairing screen; the top bar says "downloading the card
      database…", then `<versionName> · serving at <ip>:8090`.
- [ ] Mount, Auto on: "waiting for a steady view" → "watching for a card" →
      card in → "hold still" → capture (vibration) → filed; next card works.
- [ ] Draw an Area; detection and capture stay inside it. Double-tap re-learns.
- [ ] Sleeved card, dark card, foil under glare all trigger (no geometry
      rejections).
- [ ] Empty tray / hand in frame → nothing filed as a card.
- [ ] Auto off with no Area → a card-shaped Area appears in the middle; the
      card in it scans, reads its collector line, and no phone shadow on it
      (if the box is too big/small, say so — its size is a guess).
- [ ] Auto off: tap the shutter → the scan opens, prices appear live, Back
      goes straight to the camera, the scan stays in the list.
- [ ] Auto off: a non-card → "No match — Retry"; Retry works.
- [ ] Airplane mode: scan 3 cards → "waiting (no connection?)"; reconnect →
      they are identified in order, each filed once.
- [ ] Force-stop the app with jobs queued → relaunch → they are identified.
- [ ] Settings → Pair a computer as admin → the computer opens the list and
      can export CSV; 📱 Scanner changes the phone's torch/mode live.
- [ ] Share: a second phone joins via QR and via typed code as a guest (can
      flag, can't delete); wrong code 8× → locked out; New guest code → the
      guest is signed out and the old code fails, the computer stays in.
- [ ] Screen off for 10 minutes: the computer and guests still get through.
- [ ] Settings → Diagnostics shows camera 0, hardware level, analysis size and
      capture timings; Copy works.
- [ ] Update to the next release-signed APK installs over the old one with
      the scans and the paired computer intact.

## Known limits

- **No device was available while this was written.** Everything is proven by
  JVM tests (including the Node differential test against phone.html), lint and
  compilation; camera behaviour, focus and the luma sampling (Y-plane box
  average vs Chrome's RGB luma — equal on neutral greys) still need the device
  checklist above. The debug overlay shows the detection numbers for that.
- **arm64-v8a only** (keeps the OpenCV native libs to one ABI) — fine for the
  N200; it won't install on 32-bit-only phones or x86 emulators.
- **Google developer verification.** Google is rolling out mandatory developer
  verification for apps installed on certified Android devices (a few countries
  from late 2026, worldwide from 2027). Tapping a sideloaded APK from an
  unverified developer may then be blocked; **installing over ADB (`adb
  install`) stays possible**, and registering as a developer is the other way
  out.
- Identifying needs internet for Scryfall's printing list (and pricing for Face
  to Face): offline captures wait in the queue and are identified when the
  phone is back online.
- The local network leg (a computer, guests) is plain HTTP: pair a computer only
  on a network you trust. Guests can review and flag, not delete (see above).
