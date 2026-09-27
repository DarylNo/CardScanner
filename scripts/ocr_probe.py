#!/usr/bin/env python
"""
Collector-line OCR probe — why did (or didn't) a scan get OCR-confirmed?

Runs the REAL production path (card_detect.extract_card ->
ocr_id.read_bottom_strip -> ocr_id.match_printing -> pipeline._art_agrees)
on one stored scan photo and explains every step, then shows what the
experimental preprocessing would have read on the same image.

    python scripts/ocr_probe.py --scan-id 912
    python scripts/ocr_probe.py scan_images/912.jpg --sets akh,plst:AKH-132,jmp,j25
    python scripts/ocr_probe.py photo.jpg --raw --sets akh,jmp --save /tmp/probe

--scan-id reads the candidates (sets, collector numbers, art distances) from
the scan DB ($SCAN_DB, default scans.db) and the photo from $SCAN_IMAGES_DIR
(default scan_images). --sets entries are `set` or `set:collector` and
override the DB candidates. Stored scan photos are already the 630x880 warp
when detection succeeded; a photo of any other size is treated as a RAW frame
and goes through extract_card first (like scan time). --save writes the
strip crops and OCR input variants as PNGs for eyeballing.

Nothing here writes to the DB or changes any setting.
"""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2                                                   # noqa: E402
import numpy as np                                           # noqa: E402

from mtg_card_scanner import ocr_id                          # noqa: E402
from mtg_card_scanner.card_detect import CARD_H, CARD_W, extract_card  # noqa: E402

_engine = None


def engine():
    """A PRIVATE RapidOCR instance (passing text_score to the shared one
    would change it for good — RapidOCR stores call kwargs on itself)."""
    global _engine
    if _engine is None:
        from rapidocr_onnxruntime import RapidOCR
        _engine = RapidOCR()
    return _engine


def ocr_rows(img, text_score: float = 0.0):
    """[(text, score)] for every row RapidOCR recognises, scores kept so the
    rows production DROPS (score < 0.5) are visible too."""
    result, _ = engine()(img, text_score=text_score)
    return [(str(r[1]), float(r[2])) for r in (result or [])]


# ── strip + variants ────────────────────────────────────────────────────────

def crop_strip(card, strip_y=ocr_id._STRIP_Y, strip_x=ocr_id._STRIP_X):
    h, w = card.shape[:2]
    return card[int(h * strip_y[0]):int(h * strip_y[1]),
                int(w * strip_x[0]):int(w * strip_x[1])]


def production_variants(strip):
    """Exactly what ocr_id.read_bottom_strip feeds the engine."""
    gray = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
    _, otsu = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    return [
        ("color x2.5", cv2.resize(strip, None, fx=2.5, fy=2.5,
                                  interpolation=cv2.INTER_CUBIC)),
        ("otsu x4", cv2.resize(otsu, None, fx=4, fy=4,
                               interpolation=cv2.INTER_CUBIC)),
    ]


def experimental_variants(strip):
    """Candidate fixes under evaluation (NOT used by the server):
    per-line crops (the det model sees one short line, not the rules-text
    / glare-filled strip), inverted-gray CLAHE (dark-on-light is what the
    recogniser was trained on)."""
    out = []
    gray = cv2.cvtColor(strip, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(2, 8)).apply(gray)
    inv = 255 - clahe
    out.append(("inv-clahe x3", cv2.resize(inv, None, fx=3, fy=3,
                                           interpolation=cv2.INTER_CUBIC)))
    for i, line in enumerate(split_lines(gray)):
        big = cv2.resize(255 - line, None, fx=4, fy=4,
                         interpolation=cv2.INTER_CUBIC)
        pad = cv2.copyMakeBorder(big, 24, 24, 24, 24, cv2.BORDER_CONSTANT,
                                 value=255)
        out.append((f"line{i} inv x4", pad))
    return out


def split_lines(gray, min_h=4):
    """Bright-text rows inside the black border, bottom-up: rows whose
    bright-pixel fraction peaks between dark gaps. Returns crops (with a
    2 px margin) of at most 3 lines, top to bottom."""
    h, w = gray.shape
    thr = max(60, int(np.percentile(gray, 90)) - 20)
    frac = (gray > thr).mean(axis=1)
    on = frac > 0.02
    runs, start = [], None
    for y, v in enumerate(on):
        if v and start is None:
            start = y
        if not v and start is not None:
            runs.append((start, y)); start = None
    if start is not None:
        runs.append((start, h))
    runs = [(a, b) for a, b in runs if b - a >= min_h]
    return [gray[max(0, a - 2):min(h, b + 2)] for a, b in runs[-3:]]


# ── match explanation ───────────────────────────────────────────────────────

def explain_match(blob, cands):
    """Mirror match_printing step by step, then assert it agrees."""
    c = ocr_id._canon
    lines = []
    comp = [x for x in cands
            if "-" in str(x.get("collector_number", ""))
            and len(c(x.get("collector_number", ""))) >= 4
            and c(x.get("collector_number", "")) in blob]
    for x in cands:
        cn, st = str(x.get("collector_number", "")), x.get("set", "")
        flags = []
        if x in comp:
            flags.append(f"COMPOUND {cn!r}->{c(cn)!r} in blob")
        if ocr_id._find(blob, st):
            flags.append(f"SET {st!r}->{c(st)!r} in blob")
        if len(c(cn)) >= 2 and c(cn) in blob:
            flags.append(f"collector {cn!r}->{c(cn)!r} in blob")
        d = x.get("multi_distance")
        lines.append(f"    {st.upper():6} #{cn:10} canon-set={c(st):6} "
                     f"Δ{d if d is not None else '-':>4}  "
                     f"{'; '.join(flags) or '-'}")
    sid = ocr_id.match_printing(blob, cands)
    if not blob:
        why = "blob empty -> no-op (nothing read)"
    elif len(comp) == 1:
        why = "step 1: unique compound collector hit wins"
    else:
        hits = [x for x in cands if ocr_id._find(blob, x.get("set", ""))]
        if len(hits) == 1:
            why = "step 2: unique set-code hit"
        elif not hits:
            why = "step 2: NO candidate set code in blob -> no-op"
        else:
            ch = [x for x in hits if len(c(x.get('collector_number', ''))) >= 2
                  and c(x.get('collector_number', '')) in blob]
            why = (f"step 3: {len(hits)} set hits, {len(ch)} collector hits "
                   + ("-> collector decides" if len(ch) == 1
                      else "-> ambiguous, no-op"))
    return sid, why, lines


def art_check(hit, cands):
    from mtg_card_scanner.pipeline import _OCR_ART_SLACK, _art_agrees
    dists = [x.get("multi_distance") for x in cands
             if x.get("multi_distance") is not None]
    ok = _art_agrees(hit, cands)
    return ok, (f"hit Δ{hit.get('multi_distance')} vs best Δ"
                f"{min(dists) if dists else '-'} + slack {_OCR_ART_SLACK}")


# ── inputs ──────────────────────────────────────────────────────────────────

def load_scan(scan_id, db_path):
    con = sqlite3.connect(db_path)
    row = con.execute("SELECT candidates, card_read, selection FROM scans "
                      "WHERE id=?", (scan_id,)).fetchone()
    if not row:
        sys.exit(f"scan {scan_id} not in {db_path}")
    return (json.loads(row[0] or "[]"), json.loads(row[1] or "{}"),
            json.loads(row[2]) if row[2] else None)


def parse_sets(spec):
    out = []
    for i, tok in enumerate(t.strip() for t in spec.split(",") if t.strip()):
        st, _, cn = tok.partition(":")
        out.append({"id": f"cand{i}:{st}:{cn}", "set": st.lower(),
                    "collector_number": cn, "multi_distance": None})
    return out


def probe(card, cands, save=None, verbose=True):
    """Run production + experimental reads on a 630x880 warp. Returns a dict
    (used by the synthetic benchmark too)."""
    strip = crop_strip(card)
    prod_blob = ocr_id.read_bottom_strip(card)          # the REAL function
    res = {"prod_blob": prod_blob,
           "prod_sid": ocr_id.match_printing(prod_blob, cands)}
    rows = {}
    for name, img in production_variants(strip) + experimental_variants(strip):
        rows[name] = ocr_rows(img)
        if save:
            cv2.imwrite(str(Path(save) / f"variant_{name.replace(' ', '_')}.png"), img)
    exp_texts = [t for n, rr in rows.items() for t, s in rr if s >= 0.5]
    exp_blob = ocr_id._canon(" ".join(exp_texts))
    res["exp_blob"] = exp_blob
    res["exp_sid"] = ocr_id.match_printing(exp_blob, cands)
    res["rows"] = rows
    if save:
        cv2.imwrite(str(Path(save) / "card.png"), card)
        cv2.imwrite(str(Path(save) / "strip.png"), strip)
    if not verbose:
        return res

    h, w = card.shape[:2]
    y0, y1 = (int(h * v) for v in ocr_id._STRIP_Y)
    x0, x1 = (int(w * v) for v in ocr_id._STRIP_X)
    print(f"strip: x {x0}-{x1}, y {y0}-{y1} of {w}x{h} "
          f"(_STRIP_X={ocr_id._STRIP_X}, _STRIP_Y={ocr_id._STRIP_Y})")
    print("\nOCR rows per variant (score < 0.50 = DROPPED by RapidOCR's "
          "text_score in production):")
    for name, rr in rows.items():
        tag = "prod" if name in ("color x2.5", "otsu x4") else "exp "
        print(f"  [{tag}] {name}:")
        for t, s in rr:
            print(f"        {s:.2f} {'   ' if s >= 0.5 else 'DROP'} {t!r}")
        if not rr:
            print("        (no text detected)")
    for label, blob in (("PRODUCTION", prod_blob), ("EXPERIMENTAL", exp_blob)):
        sid, why, lines = explain_match(blob, cands)
        print(f"\n{label} blob: {blob!r}")
        print("\n".join(lines))
        print(f"  decision: {why}")
        if sid:
            hit = next(x for x in cands if x.get("id") == sid)
            ok, detail = art_check(hit, cands)
            print(f"  -> {hit['set'].upper()} #{hit.get('collector_number')}; "
                  f"art agreement {'OK' if ok else 'FAILS'} ({detail})"
                  f"{'' if ok else ' -> ignored, no promotion'}")
        else:
            print("  -> no promotion")
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("image", nargs="?", help="scan photo (warp or raw frame)")
    ap.add_argument("--scan-id", type=int)
    ap.add_argument("--db", default=os.getenv("SCAN_DB", "scans.db"))
    ap.add_argument("--images-dir", default=os.getenv("SCAN_IMAGES_DIR", "scan_images"))
    ap.add_argument("--sets", help="comma list: set or set:collector")
    ap.add_argument("--raw", action="store_true",
                    help="force extract_card even on a 630x880 image")
    ap.add_argument("--save", help="directory for strip/variant PNGs")
    a = ap.parse_args()

    cands = []
    if a.scan_id is not None:
        cands, card_read, sel = load_scan(a.scan_id, a.db)
        a.image = a.image or str(Path(a.images_dir) / f"{a.scan_id}.jpg")
        print(f"scan #{a.scan_id}: {card_read.get('name')!r}, "
              f"{len(cands)} candidates, selection="
              f"{(sel or {}).get('set', '-')} ocr_retro_done="
              f"{card_read.get('ocr_retro_done', False)}; top is "
              f"{'OCR-confirmed' if cands and cands[0].get('ocr_confirmed') else 'NOT ocr_confirmed'}")
    if a.sets:
        cands = parse_sets(a.sets)
    if not a.image:
        ap.error("give an image path or --scan-id")
    if not cands:
        print("warning: no candidates (use --sets) — only the OCR read is shown")

    img = cv2.imread(a.image)
    if img is None:
        sys.exit(f"cannot read {a.image}")
    if a.save:
        Path(a.save).mkdir(parents=True, exist_ok=True)
    ih, iw = img.shape[:2]
    if (iw, ih) == (CARD_W, CARD_H) and not a.raw:
        print(f"{a.image}: {iw}x{ih} = stored WARP (detection succeeded at scan time)")
        card = img
    else:
        card, detected = extract_card(img)
        print(f"{a.image}: {iw}x{ih} RAW frame -> extract_card detected={detected}")
        if not detected:
            print("  !! quad NOT found: at scan time _apply_ocr_hint returns "
                  "BEFORE any OCR (centre-crop fallback). Probing the crop anyway.")
    probe(card, cands, save=a.save)


if __name__ == "__main__":
    main()
