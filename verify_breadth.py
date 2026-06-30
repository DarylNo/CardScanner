#!/usr/bin/env python3
"""
Breadth verification: non-English, foil, modern card, and robustness.
Downloads real Scryfall card images, runs the full pipeline, reports results.
No camera required — loads images directly.
"""
import sys
import cv2
import numpy as np
import requests

from mtg_card_scanner.vision import VisionModel
from mtg_card_scanner.scryfall import ScryfallClient, ScryfallError
from mtg_card_scanner.pipeline import Pipeline
from mtg_card_scanner.output import format_listing, ScanResult, build_result
from mtg_card_scanner.vision import CardRead

SCRYFALL_API = "https://api.scryfall.com"
UA = "MTGCardScanner/1.0 verify-breadth"


def fetch_json(url: str, **params) -> dict:
    r = requests.get(url, params=params, headers={"User-Agent": UA}, timeout=15)
    r.raise_for_status()
    return r.json()


def download_card_image(image_url: str) -> np.ndarray:
    r = requests.get(image_url, headers={"User-Agent": UA}, timeout=30)
    r.raise_for_status()
    arr = np.frombuffer(r.content, np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError(f"Could not decode image from {image_url}")
    return img


def add_black_border(img: np.ndarray, border: int = 80) -> np.ndarray:
    """Add black padding so the card-detector can find the card quad in a Scryfall image."""
    return cv2.copyMakeBorder(img, border, border, border, border,
                               cv2.BORDER_CONSTANT, value=(0, 0, 0))


def run_pipeline_on_image(img: np.ndarray, model, scryfall) -> ScanResult:
    pipeline = Pipeline(model=model, scryfall=scryfall)
    return pipeline.run_once(img)


def sep(title: str) -> None:
    print(f"\n{'='*60}")
    print(f"  {title}")
    print(f"{'='*60}")


def report(label: str, result: ScanResult, expected_lang: str = "",
           expected_set: str = "", expected_foil: bool | None = None,
           expected_name: str = "", expected_not_promo: bool = False) -> bool:
    listing = format_listing(result)
    print(listing)
    ok = True
    checks = []
    if expected_lang and result.language.lower() != expected_lang.lower():
        checks.append(f"FAIL lang: got {result.language!r}, want {expected_lang!r}")
        ok = False
    else:
        checks.append(f"ok  lang={result.language}")
    # Use /set/ path segment to distinguish 'dmu' from 'pdmu'
    if expected_set and result.scryfall_uri:
        seg = f"/{expected_set.lower()}/"
        if seg not in result.scryfall_uri.lower():
            checks.append(f"FAIL set: want '{seg}' in URI {result.scryfall_uri!r}")
            ok = False
        else:
            checks.append(f"ok  set='{expected_set}'")
    if expected_foil is not None and result.foil != expected_foil:
        checks.append(f"FAIL foil: got {result.foil}, want {expected_foil}")
        ok = False
    elif expected_foil is not None:
        checks.append(f"ok  foil={result.foil}")
    if expected_name and expected_name.lower() not in result.scryfall_name.lower():
        checks.append(f"FAIL name: got {result.scryfall_name!r}, want '{expected_name}'")
        ok = False
    elif expected_name:
        checks.append(f"ok  name={result.scryfall_name!r}")
    if expected_not_promo and result.scryfall_uri:
        # Heuristic: promo URIs contain the promo set code (e.g. /pdmu/)
        import re
        if re.search(r'/p[a-z]{2,4}/', result.scryfall_uri.lower()):
            checks.append(f"FAIL promo: URI looks like a promo {result.scryfall_uri!r}")
            ok = False
        else:
            checks.append(f"ok  not-promo")
    for c in checks:
        print(f"  [{label}] {c}")
    return ok


# ── initialise shared objects ─────────────────────────────────────────────────

print("Initialising vision model and Scryfall client...")
model = VisionModel()
scryfall = ScryfallClient()
results: list[tuple[str, bool]] = []


# ══════════════════════════════════════════════════════════════════════════════
# TEST 1 — NON-ENGLISH (Japanese)
# Card: EMA #46 — Diminishing Returns (Japanese)
# EMA #46 is "Diminishing Returns"; the Japanese printing exists in EMA.
# Verifies: Japanese image feed, lang normalisation jp→ja, non-English lookup.
# ══════════════════════════════════════════════════════════════════════════════
sep("TEST 1 — Non-English (Japanese, EMA #46 = Diminishing Returns)")

try:
    card_meta = fetch_json(f"{SCRYFALL_API}/cards/ema/46/ja")
    img_url   = card_meta["image_uris"]["normal"]
    en_name   = card_meta.get("name", "Diminishing Returns")   # Scryfall English name
    print(f"Card (English oracle name): {en_name}")
    print(f"Downloading: {img_url}")
    img_ja = download_card_image(img_url)
    img_ja = add_black_border(img_ja)
    cv2.imwrite("verify_japanese.jpg", img_ja)
    print(f"Image (with border): {img_ja.shape[1]}x{img_ja.shape[0]} px")

    result_ja = run_pipeline_on_image(img_ja, model, scryfall)
    # After lang normalisation, result.language should be 'ja' (not 'jp')
    ok1 = report("JAPANESE", result_ja,
                 expected_lang="ja",
                 expected_set="ema",
                 expected_name=en_name)
except Exception as e:
    print(f"  ERROR: {e}")
    ok1 = False

results.append(("Non-English (Japanese)", ok1))


# ══════════════════════════════════════════════════════════════════════════════
# TEST 2 — FOIL handling
# Foil detection from a still image is inherently unreliable (glare-dependent),
# so we test the PRICE SELECTION branch synthetically, then verify a real scan
# correctly passes foil=False for a non-foil image.
# ══════════════════════════════════════════════════════════════════════════════
sep("TEST 2 — Foil price selection")

print("2a. Synthetic foil=True: foil price should appear in listing")
fake_foil = build_result(
    CardRead(name="Lightning Bolt", set_code="m11", collector_number="149",
             foil=True, language="en", condition_estimate="NM",
             condition_reason="No visible wear."),
    {"name": "Lightning Bolt", "set_name": "Magic 2011", "type_line": "Instant",
     "rarity": "common", "collector_number": "149",
     "prices": {"usd": "1.00", "usd_foil": "8.50"},
     "scryfall_uri": "https://scryfall.com/card/m11/149"},
)
listing_foil = format_listing(fake_foil)
assert "$8.50" in listing_foil,  f"Expected foil price $8.50 in listing:\n{listing_foil}"
assert "[FOIL]" in listing_foil, f"Expected [FOIL] tag in listing:\n{listing_foil}"
print("  [FOIL] ok  — foil price $8.50 shown correctly")

print("2b. Synthetic foil=True, no foil price: falls back to regular price")
fake_foil_no_fp = build_result(
    CardRead(name="Lightning Bolt", set_code="m11", collector_number="149",
             foil=True, language="en", condition_estimate="NM",
             condition_reason="No visible wear."),
    {"name": "Lightning Bolt", "prices": {"usd": "1.00", "usd_foil": None},
     "scryfall_uri": "https://scryfall.com/card/m11/149"},
)
listing_no_fp = format_listing(fake_foil_no_fp)
assert "$1.00" in listing_no_fp, f"Expected $1.00 fallback:\n{listing_no_fp}"
print("  [FOIL-noprice] ok  — falls back to $1.00")

print("2c. Download a card image and verify foil=False is read (non-foil image)")
try:
    card_meta2 = fetch_json(f"{SCRYFALL_API}/cards/m11/149")
    img_url2   = card_meta2["image_uris"]["normal"]
    img_foil_check = download_card_image(img_url2)
    img_foil_check = add_black_border(img_foil_check)
    cv2.imwrite("verify_foil_check.jpg", img_foil_check)
    result_foil = run_pipeline_on_image(img_foil_check, model, scryfall)
    ok2 = report("FOIL", result_foil, expected_foil=False, expected_name="Lightning Bolt")
    print(f"  Note: foil detection from stills is glare-dependent; "
          f"got foil={result_foil.foil} (expected False for non-foil Scryfall image)")
except Exception as e:
    print(f"  ERROR: {e}")
    ok2 = False

results.append(("Foil handling", ok2))


# ══════════════════════════════════════════════════════════════════════════════
# TEST 3 — CLEAN MODERN CARD (new 2015+ frame)
# Card: Sheoldred, the Apocalypse · Dominaria United (DMU) #107
# 2022 card, new-style frame, unique art, only one normal printing.
# Verifies: vision reads set='dmu' and collector number, Tier-1 lookup succeeds.
# Note: pHash may select a different printing (showcase/extended) — name check
# is sufficient to confirm the card identification is correct.
# ══════════════════════════════════════════════════════════════════════════════
sep("TEST 3 — Clean modern card (Sheoldred, the Apocalypse — DMU #107)")

try:
    card_meta3 = fetch_json(f"{SCRYFALL_API}/cards/dmu/107")
    img_url3   = card_meta3["image_uris"]["normal"]
    print(f"Downloading: {img_url3}")
    img_modern = download_card_image(img_url3)
    img_modern = add_black_border(img_modern)
    cv2.imwrite("verify_modern.jpg", img_modern)
    print(f"Image (with border): {img_modern.shape[1]}x{img_modern.shape[0]} px")

    result_modern = run_pipeline_on_image(img_modern, model, scryfall)
    # Expect main-set DMU, not the PDMU promo (promo-preference tiebreaker should apply).
    ok3 = report("MODERN", result_modern,
                 expected_lang="en",
                 expected_set="dmu",
                 expected_name="Sheoldred, the Apocalypse",
                 expected_not_promo=True)
except Exception as e:
    print(f"  ERROR: {e}")
    ok3 = False

results.append(("Modern card", ok3))


# ══════════════════════════════════════════════════════════════════════════════
# TEST 4 — ROBUSTNESS
# 4a. Random noise frame (should not crash, should return empty/partial result)
# 4b. Scryfall total miss (all 3 tiers fail — should not crash)
# ══════════════════════════════════════════════════════════════════════════════
sep("TEST 4 — Robustness")

print("4a. Random noise frame (640x480)")
try:
    rng = np.random.default_rng(42)
    noise = rng.integers(0, 256, (480, 640, 3), dtype=np.uint8)
    pipeline_noise = Pipeline(model=model, scryfall=scryfall)
    result_noise = pipeline_noise.run_once(noise)
    # Should not raise — even if result is empty/partial
    print(f"  ok  — no crash; scryfall_name={result_noise.scryfall_name!r}")
    ok4a = True
except Exception as e:
    print(f"  FAIL — crashed with: {e}")
    ok4a = False

print("4b. Scryfall miss (card name not on Scryfall — all tiers fail)")
try:
    bad_read = CardRead(
        name="ZZZZZ_NOTACARD_XYZ", set_code="zzz", collector_number="999",
        foil=False, language="en", condition_estimate="NM",
        condition_reason="Test.",
    )
    # Build a result directly from a failed Scryfall lookup
    result_miss = build_result(bad_read, {})
    listing_miss = format_listing(result_miss)
    assert "N/A" in listing_miss, "Price should be N/A when Scryfall data is empty"
    print(f"  ok  — graceful partial result, price=N/A")
    ok4b = True
except Exception as e:
    print(f"  FAIL — crashed with: {e}")
    ok4b = False

ok4 = ok4a and ok4b
results.append(("Robustness", ok4))


# ══════════════════════════════════════════════════════════════════════════════
# SUMMARY
# ══════════════════════════════════════════════════════════════════════════════
sep("SUMMARY")
all_pass = True
for name, passed in results:
    status = "PASS" if passed else "FAIL"
    print(f"  [{status}]  {name}")
    if not passed:
        all_pass = False

sys.exit(0 if all_pass else 1)
