# CardScanner Android app

A native capture app for the phone on the mount. It replaces the Chrome
`/phone` page as the **camera**: same tuned detection, faster capture, uploads
that survive a dropped connection, scanning away from home over Tailscale, and
a guest gateway. It does **not** replace the server — identification, pricing
and review all stay where they are. The review screens (scan list, filters,
printing picker, price check) are the server's own `/phone?panel=1` page shown
in a WebView, so there is one review UI to maintain, not two.

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
   │  Trigger (Mount, auto)  or  Scan tap (Mount manual / Handheld)
   ▼
CapturePipeline             last 3 ring frames → sharpest (Laplacian variance)
   │                        → CardQuad (port of card_detect.find_card_quad)
   │                        → Flatten with a margin → JPEG = PRIMARY
   │                        + the 3 raw ROI crops = FALLBACKS
   ▼
UploadQueue                 persistent FIFO on disk, one worker, retried forever
   │  POST /api/scan  primary;  if no_card / not identified →
   │  POST /api/scan  the 3 raw frames with replace_scan_id (the server's own
   │                  multi-frame retry, reproduced)
   ▼
ServerClient                pinned TLS, address failover (LAN → Tailscale)
   ▼
outcome → status line / haptic / PanelActivity (WebView of /phone?panel=1…)
```

Modules and packages (`io.github.darylno.cardscanner`):

| Where | What |
|---|---|
| `:core` (pure JVM) | `Detection`, `AutoScanner` (phone.html port), `CardQuad` (server `card_detect` port), `Flatten`, `GraySampler`, `Sharpness`, `Rotation`, `RoiFrac`, `Nv21Frame`, `Nv21Bgr`. OpenCV is `compileOnly`; tests load `org.openpnp:opencv` natives. |
| `app/camera` | `CameraController` (bind, focus, torch, AE/AWB lock, diagnostics), `FrameRing`, `ScanAnalyzer` (the analysis-thread loop; all `AutoScanner` access is confined to it). |
| `app/capture` | `CapturePipeline` (sharpest frame → quad → flatten → JPEG). |
| `app/net` | `Pin`, `PinnedTls`, `ServerConfig`/`PrefsConfigStore`, `ServerClient` (failover), `Pairing`, `UploadQueue`. |
| `app/gateway` | `GatewayServer` (NanoHTTPD reverse proxy), `JoinCode`, `LocalAddresses`, `GatewayService` (foreground service), `QrBitmap`. |
| `app/f2f` | Stage 1c measurement only: `F2fFetcher` (port of `facetoface._default_get_json` pacing/retry, parity-tested against `app/src/test/resources/f2f/pacing.json` from `scripts/export_f2f_pacing_fixture.py`), `F2fPricer` (query ladder + SKU confirmation), `OkHttpTransport` / `CronetTransport`, `F2fProbe`. |
| `app/ui` | `MainActivity`, `OverlayView`, `SetupActivity` (pairing), `PanelActivity` (review WebView), `ShareActivity`, `SettingsActivity`, `AppSettings`. |

Rules the app keeps (see also CLAUDE.md → "Android app"):

- **Detection is a port, proven — not a re-tune.** `DetectionDifferentialTest`
  runs phone.html's REAL detection code under Node
  (`core/src/test/resources/phone_harness.js`) and compares it with the Kotlin
  port step by step. The trigger is occupancy + stillness only; no geometry
  gates.
- **The server stays the judge.** The phone finds and flattens the card (with
  a margin, so the server can re-detect it), but the server re-runs detection
  and decides whether there is a card at all.
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
`mtg-card-scanner-android.apk` on the GitHub Release next to the desktop
binaries.

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
4. Open **Card Scanner**, allow the **Camera** permission (and notifications on
   Android 13+ — the guest gateway's notification needs it).

**Over USB (ADB)**, from a computer with platform-tools:

1. Settings → About phone → tap **Build number** 7 times (Developer options).
2. Settings → System → Developer options → enable **USB debugging**; plug in
   and accept the RSA prompt on the phone.
3. `adb install -r mtg-card-scanner-android.apk`

**Updates** install over the old app (pairing and settings kept) only when both
APKs are signed with the same key — see [Signing](#signing-setup). A
debug-signed APK needs **uninstall → install**, then pairing again.

## Pairing

The app talks only to your scanner server, over HTTPS with the server's
self-signed certificate **pinned** (SHA-256 of the certificate).

1. On the desktop, open the scanner UI and show the **Phone** QR.
2. In the app's setup screen, scan that QR. It carries
   `https://<lan-ip>:8443/phone#pin=<sha256>` — the desktop screen vouches for
   the certificate, so pairing needs no "trust this?" question.
3. The app calls `/api/version`, then `/api/addresses`, and saves every address
   the server answers on (home LAN, Tailscale name, Tailscale IPs), best first.

Typing an address instead works too; the app then shows the certificate
fingerprint (`AB:CD:…`) and asks you to compare it with the one the desktop
shows before trusting it.

A **pin mismatch** is never "retried on another address": it means the server's
certificate changed (new install, deleted `.certs/`) or something is
impersonating it. Re-pair from the desktop QR.

## Mount vs Handheld

**Mount** — the phone on the mount over the tray. Auto on: the tuned
phone.html behaviour — learn the empty tray, wait for a card, wait for it to be
still, capture, file, wait for the next card. Draw the scan **Area** to crop
sampling and capture to the tray; double-tap the preview to re-learn the empty
tray. Focus locks on the centre of the scan Area (the screen centre when no
Area is set) as soon as the camera starts, and again when the Area changes;
if the empty tray is too plain to focus on, it re-locks there when the first
card arrives. Tap the preview to lock somewhere else. Auto-mode
scans are hands-free; a manual Scan (Auto off) that needs a pick opens the
review panel on that scan, as phone.html does.

**Handheld** — the walk-around **price check**. Continuous autofocus, a card
guide, no tray learning; tap **Scan**. When the result arrives the app opens
the review panel at `/phone?panel=1&detail=<id>&pricecheck=1`: the page asks
the server to price that card at the FRONT of the pricing queue and shows each
printing's price live as it lands. **Keep** leaves the scan in your list;
**Discard** deletes it (Back = Keep). No match → "✗ No match — Retry?" with a
Retry button. Offline or Tailscale down → the scan is queued and the price
check opens when it uploads (if the app is in the foreground; otherwise find it
under Scans).

## Away from home: Tailscale failover

1. Install Tailscale on the server machine and on the phone, same tailnet.
2. **Pair at home** (or anywhere Tailscale is up) so `/api/addresses` hands the
   app the Tailscale addresses along with the LAN one.
3. Away, keep Tailscale connected on the phone. The app tries the last address
   that worked first, then the rest in order.

It moves to the next address only on **connect-phase** failures (host
unknown, connection refused, no route, connect timeout, handshake failure) —
never once a request may have reached the server, so a scan can't be filed
twice. Uploads wait in a persistent on-disk queue, in order, retried with
backoff (1, 2, 4, 8, 16, 30, 30… s) — a dropped connection or a killed app
loses nothing. If the server's addresses change (new Tailscale name), re-pair
or edit the list in Settings.

Android allows one VPN at a time: another VPN app displaces Tailscale.

## Guest gateway (Share)

**Share** lets people without Tailscale — at a shop, on your hotspot — use the
review pages through your phone. The phone serves them on port 8080 of its
local network and relays everything to the scanner server over its own
connection (Wi-Fi or Tailscale).

1. Tap **Share**. The screen shows a QR, a **6-digit code** and the phone's
   local addresses; a notification stays up while sharing (with **Stop**).
2. Guests join the same Wi-Fi (or your hotspot — the Share screen hints at it
   when the phone has no Wi-Fi address), then scan the QR, or open
   `http://<phone-ip>:8080` and type the code.
3. **Stop** ends sharing, rotates the code and signs every guest out.

> **Guests have FULL access** — the same as you: they can pick, edit, delete,
> clear all scans, export, and trigger a server update. This was a deliberate
> decision (no half-permissioned second UI to maintain). **Only share the code
> with people you trust**, and stop sharing when done.

Also know: guest ↔ phone traffic is plain HTTP on the local network (the
phone → server leg stays pinned HTTPS); 8 wrong codes from one device within
5 minutes lock it out for 5 minutes.

## Signing setup

Android installs an update only if it is signed with the **same key** as the
installed app. Until the release key exists, release APKs are signed with a
throwaway **debug key** (a different one every build — the release job summary
says so loudly), so **every update means uninstall + reinstall + re-pair**.

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

The switch from a debug-signed install to the release key needs one last
uninstall; from then on updates install in place. Secrets reach both release
paths — a hand-pushed tag, and `auto-tag.yml` (it calls `release.yml` with
`secrets: inherit`).

## Compare mode (Stage 2)

The phone can now identify cards itself — the server's pipeline ported to
`:core` (`IdentifyPipeline`: blank guard → art index → Scryfall printings →
printing ranking → collector-line OCR → the same decisions), end-to-end
parity-tested against the real Python pipeline (`IdentifyParityTest`,
fixtures from `scripts/export_identify_fixtures.py`). Until Stage 4 it runs in
**shadow**: the server stays the judge, and nothing the phone concludes is
filed, picked or uploaded.

**Turn it on:** Settings → long-press **DIAGNOSTICS** → *On-phone identify
(Stage 2)* → **Compare mode** (default OFF). The phone needs the art pack
(the `art-pack` release CI publishes weekly; a few MB): it downloads on
Wi-Fi when Compare mode is switched on or the app starts, re-checks at most
weekly, or now with **Check for pack**. "No art pack published yet" just
means the first CI build hasn't finished. Scryfall lookups (Cronet) and
printing images (OkHttp, small LRU cache) use whatever network is up.

With it on, after the server's final answer to each capture arrives, the
phone identifies the **same primary photo** in the background (one at a
time, low priority) and logs a row. **Test on last capture** does one run on
demand (and logs it too when the server's answer for that capture is known).

**The report** (summary card; **Copy report** = compact JSON of the last 200):
- *compared* — rows logged (jobs captured while Compare mode was on);
- *name agree* — same top card name (both "no card" counts as agreeing);
- *printing agree* — same top printing: the server's auto-picked selection,
  else its `candidates[0]`, vs the phone's `candidates[0]`;
- *auto-pick agree* — the server auto-filed the scan ⇔ the phone's result
  meets the same grounds (one printing / OCR-confirmed / art-decisive);
- *phone total ms* — median / p95 of decode + identify + printings + ranking
  + OCR on this phone (the per-stage split is on every row);
- *OCR-confirmed* — share of rows whose #1 was OCR-confirmed, phone (ML Kit)
  vs server (RapidOCR);
- *server used raw-frame retry* — rows where the server needed the 3 raw
  frames; the phone only ever sees the primary, so those compare different
  inputs.

The list shows each disagreement (what differs, both answers with
confidence / auto / ocr flags, timings). Small distance differences are
expected even on identical input: the server re-warps the card with cv2 and
the phone with OpenCV 4.10, whose `warpPerspective` pixels differ slightly
(measured in `IdentifyParityTest`) — name/printing disagreements are what
matter.

## F2F network test (Stage 1c)

A hidden screen that answers "does Face to Face Games throttle the phone?"
(docs/PHONE_ONLY_PLAN.md). **Settings → long-press the DIAGNOSTICS header →
Network test** (below the Stage 2 section). It prices ~30 real cards on facetofacegames.com at the rig's
exact pacing over **Cronet** (cronet-embedded: Chromium's stack, a real Chrome
TLS handshake) and over **plain OkHttp**, and counts 429s. *Run both* goes
Cronet first (the per-IP bucket is cold for the stack we'd ship), then waits
out a cooldown (default 5 min) before OkHttp. **Copy results** puts a JSON
report on the clipboard. Only this test and Compare mode's Scryfall lookups use Cronet.

## Battery settings (OnePlus)

OxygenOS kills background apps aggressively; a killed gateway drops guests
and a frozen queue stops uploading.

- Settings → Battery → **Battery optimization** → (All apps) **Card Scanner** →
  **Don't optimize**.
- Open **Recents**, tap the **⋮** menu on Card Scanner's card (or long-press
  it) → **Lock**, so "clear all" leaves it alone.
- While scanning on the mount, keep the phone on a charger; the app keeps the
  screen on.

## Device test checklist

Run on the Nord N200 after installing a new build:

- [ ] Installs; the top bar shows `app <versionName> · server <version>`.
- [ ] Pair by scanning the desktop Phone QR — no trust prompt; the address
      list shows the LAN URL and the Tailscale name/IPs.
- [ ] Typed-address pairing shows a fingerprint that matches the desktop's.
- [ ] Mount, Auto on: "waiting for a steady view" → "watching for a card" →
      card in → "hold still" → capture (vibration) → filed; next card works.
- [ ] Draw an Area; detection and capture stay inside it. Double-tap re-learns.
- [ ] Sleeved card, dark card, foil under glare all trigger (no geometry
      rejections).
- [ ] Empty tray / hand in frame → nothing filed as a card (server judges).
- [ ] Mount, Auto off, manual Scan of a multi-printing card → panel opens on
      that scan for a pick.
- [ ] Handheld: Scan → price-check panel opens, prices appear live; Keep keeps
      it, Discard removes it from the desktop list; Back keeps it.
- [ ] Handheld: a non-card → "✗ No match — Retry?"; Retry works.
- [ ] Airplane mode: scan 3 cards → "queued"; reconnect → they upload in order.
- [ ] Force-stop the app with jobs queued → relaunch → they upload.
- [ ] Away (Wi-Fi off, Tailscale on): scanning works; nothing is filed twice.
- [ ] Share: a second phone joins via QR and via typed code; wrong code 8× →
      locked out; Stop → the guest is signed out and the old code fails.
- [ ] Share with the screen off for 10 minutes: guests still get through.
- [ ] Settings → Diagnostics shows camera 0, hardware level, analysis size and
      capture timings; Copy works.
- [ ] Update to the next release-signed APK installs over the old one with
      pairing intact.

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
- The review UI needs the server reachable — the app queues captures offline,
  but prices and picks come from the server.
- The guest gateway is plain HTTP on the local network and gives full access
  (see above).
