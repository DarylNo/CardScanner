"""
Export art-fingerprint parity fixtures for the Android app's JVM tests.

The phone must compute THE SAME BITS as the server's art index — the
identification thresholds (110 / 140+20 / 210, art_index.py) were measured on
the server's hashes and only transfer if the bits match. This script runs the
SERVER's own code (art_index.ArtIndex._card_pil / _hash_variants / _score /
identify, visual_match._crop_region / crop_art_region, imagehash.phash on
Pillow + scipy) over a fixed set of input images and writes every stage's
answer to android/core/src/test/resources/arthash/expected.json; HashParityTest
(android/core) must reproduce it exactly:

  gray      Pillow "L" of the half-size card (sha256 of the bytes)
  resize    Pillow LANCZOS 32×32 / 64×64 of the plain art crop (bytes/sha256),
            sha256 over EVERY core crop's resize, a size sweep, and every
            fixed-point coefficient table for input extents 1..700 (read
            back from Pillow with impulse images in mode "I")
  dct       scipy.fftpack.dct 2-D, low 8×8 / 16×16, as IEEE-754 bit patterns,
            and the numpy median
  bits      phash64 / phash256 per crop
  variants  the full core grid (boxes + hashes, grid order, skips) and a
            digest of the dense grid
  identify  ArtIndex.identify top-5 (names, ids, distances) against an index
            of the fixture cards + near-miss decoys + an "A-" twin, and
            pipeline._is_confident / _confidence_for on the result

Inputs (committed, lossless PNG so both sides decode identical pixels):
  small/*.png   Scryfall `small` images — the index side, and scan inputs of
                an awkward size (half = 73×102: the 64×64 resize upsamples)
  scan/*.png    Scryfall `normal` images reduced to 244×340 — scan inputs
  photo/*.png   cards put through a small perspective + blur + exposure
                change + JPEG recompression with cv2 (camera-like flattened
                cards): one at the rig's 630×880 warp size (exact-2× INTER_AREA),
                one at 317×443 (INTER_AREA's general path)
  synth/*.png   gradients, flat colour, noise, checkerboard, near-ties
  cards.json    Scryfall metadata of the fixture cards

    python scripts/export_hash_fixtures.py                   # rewrite expected.json
    python scripts/export_hash_fixtures.py --check           # exit 1 if stale (CI)
    python scripts/export_hash_fixtures.py --refresh-inputs  # re-download + regenerate
                                                             # the input images (network)

--check never touches the network: it recomputes expected.json from the
committed inputs with the installed Pillow / scipy / numpy / cv2 and fails on
any byte of difference — a library upgrade that moves one hash bit shows up
here before it can silently shift the phone's thresholds.
"""

from __future__ import annotations

import base64
import hashlib
import json
import struct
import sys
import time
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import imagehash  # noqa: E402
import scipy.fftpack  # noqa: E402
from PIL import Image  # noqa: E402

import mtg_card_scanner.visual_match as vm  # noqa: E402
from mtg_card_scanner import art_index  # noqa: E402
from mtg_card_scanner.art_index import (_CORE_PARAMS, _DENSE_PARAMS, ArtIndex,  # noqa: E402
                                        _split_u64)
from mtg_card_scanner.pipeline import _confidence_for, _is_confident  # noqa: E402

OUT = ROOT / "android" / "core" / "src" / "test" / "resources" / "arthash"

# Varied frames / colours / borders. (set, collector_number)
CARDS = [
    ("m10", "146"),   # Lightning Bolt — modern black border
    ("lea", "161"),   # Lightning Bolt — Alpha: same name, different art
    ("akh", "132"),
    ("10e", "200"),
    ("mh2", "186"),
    ("m21", "1"),
    ("eld", "1"),
    ("dom", "1"),
    ("war", "1"),
    ("neo", "1"),
    ("10e", "380"),   # basic land
    ("znr", "278"),   # full-art basic
    ("neo", "406"),   # borderless showcase
    ("2xm", "353"),   # borderless
    ("leg", "1"),     # Legends — black-bordered old frame
]
# Scan inputs from `normal` (kept to a few, and reduced, to keep the repo small).
SCAN_CARDS = [("m10", "146"), ("mh2", "186"), ("neo", "406")]
SCAN_SIZE = (244, 340)          # (w, h); half = 122×170
# Camera-like flattened cards: one at the server's 630×880 warp size (the
# exact-2× INTER_AREA path), one at an odd size (the general INTER_AREA path).
PHOTOS = [("mh2", "186", (630, 880), 1), ("neo", "1", (317, 443), 2)]
_UA = "MTGCardScanner/1.0 hash-fixtures (+https://github.com/DarylNo/CardScanner)"

N_DECOYS_PER_CARD = 60
COEFF_MAX_IN = 700              # > the 630-px warp width: every crop extent there is
RESIZE_SWEEP = [(w, h) for w in (7, 13, 31, 32, 33, 47, 64, 65, 96, 129, 200, 263)
                for h in (5, 32, 38, 63, 64, 77, 140)]


# ── helpers ─────────────────────────────────────────────────────────────────

def _key(set_code: str, cn: str) -> str:
    return f"{set_code}_{cn}"


def _png(img: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".png", img, [cv2.IMWRITE_PNG_COMPRESSION, 9])
    assert ok
    return buf.tobytes()


def _dhex(values) -> str:
    """Doubles as concatenated big-endian IEEE-754 hex (16 chars each) — exact."""
    return "".join(struct.pack(">d", float(v)).hex() for v in np.asarray(values).ravel())


def _sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def _hex64(v: int) -> str:
    return f"{v:016x}"


def _hex256(words) -> str:
    return "".join(f"{int(w):016x}" for w in words)


# ── inputs (network / generated; only with --refresh-inputs) ────────────────

def _get(session, url: str, **kw):
    for attempt in range(4):
        r = session.get(url, timeout=30, **kw)
        if r.status_code == 429:
            time.sleep(1 + attempt)
            continue
        r.raise_for_status()
        time.sleep(0.12)           # stay well under Scryfall's ~10 req/s
        return r
    r.raise_for_status()


def _decode(content: bytes) -> np.ndarray:
    img = cv2.imdecode(np.frombuffer(content, np.uint8), cv2.IMREAD_COLOR)
    assert img is not None
    return img


def _simulate_photo(card: np.ndarray, size: tuple[int, int], seed: int) -> np.ndarray:
    """A flattened card as a camera delivers it: slightly off-square warp,
    lens blur, exposure/white balance drift, JPEG."""
    rng = np.random.default_rng(seed)
    w, h = size
    big = cv2.resize(card, (w, h), interpolation=cv2.INTER_CUBIC)
    src = np.float32([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]])
    dst = src + rng.uniform(-9, 9, size=(4, 2)).astype(np.float32)
    m = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(big, m, (w, h), flags=cv2.INTER_LINEAR,
                                 borderMode=cv2.BORDER_REPLICATE)
    blurred = cv2.GaussianBlur(warped, (5, 5), 1.1)
    gain = np.array([1.06, 0.98, 0.93]) * rng.uniform(0.9, 1.1)
    lit = np.clip(blurred.astype(np.float64) * gain + rng.uniform(-8, 8), 0, 255).astype(np.uint8)
    ok, jpg = cv2.imencode(".jpg", lit, [cv2.IMWRITE_JPEG_QUALITY, 82])
    assert ok
    return _decode(jpg.tobytes())


def _synthetic() -> dict[str, np.ndarray]:
    rng = np.random.default_rng(20260927)
    out: dict[str, np.ndarray] = {}
    w, h = 200, 280
    x = np.linspace(0, 255, w)[None, :].repeat(h, 0)
    y = np.linspace(0, 255, h)[:, None].repeat(w, 1)
    out["grad_h"] = np.dstack([x, x * 0.5, 255 - x]).astype(np.uint8)
    out["grad_v"] = np.dstack([y, y, y]).astype(np.uint8)
    out["grad_diag"] = np.dstack([(x + y) / 2, 255 - (x + y) / 2, x]).astype(np.uint8)
    out["flat_grey"] = np.full((h, w, 3), 127, np.uint8)
    out["flat_red"] = np.dstack([np.zeros((h, w)), np.zeros((h, w)), np.full((h, w), 200)]).astype(np.uint8)
    near = np.full((h, w, 3), 90, np.uint8)
    near[120, 100] = (91, 91, 91)            # one-level bump: AC terms ≈ 0, ties everywhere
    near[40, 30] = (89, 89, 89)
    out["near_flat"] = near
    cb = ((np.arange(w)[None, :] // 7 + np.arange(h)[:, None] // 7) % 2 * 255).astype(np.uint8)
    out["checker"] = np.dstack([cb, cb, cb])
    out["noise"] = rng.integers(0, 256, size=(140, 100, 3), dtype=np.uint8)
    out["noise_odd"] = rng.integers(0, 256, size=(57, 41, 3), dtype=np.uint8)
    stripes = np.zeros((h, w, 3), np.uint8)
    stripes[:, (np.arange(w) // 2) % 2 == 0] = 255   # 1-px stripes after halving: ringing + clip8
    out["stripes"] = stripes
    return out


def refresh_inputs() -> None:
    import requests
    s = requests.Session()
    s.headers.update({"User-Agent": _UA, "Accept": "application/json;q=0.9,*/*;q=0.8"})
    for sub in ("small", "scan", "photo", "synth"):
        d = OUT / sub
        d.mkdir(parents=True, exist_ok=True)
        for p in d.glob("*.png"):
            p.unlink()
    meta = []
    normals: dict[str, np.ndarray] = {}
    for set_code, cn in CARDS:
        card = _get(s, f"https://api.scryfall.com/cards/{set_code}/{cn}").json()
        uris = card.get("image_uris") or card["card_faces"][0]["image_uris"]
        k = _key(set_code, cn)
        small = _decode(_get(s, uris["small"]).content)
        normal = _decode(_get(s, uris["normal"]).content)
        normals[k] = normal
        (OUT / "small" / f"{k}.png").write_bytes(_png(small))
        if (set_code, cn) in SCAN_CARDS:
            scan = cv2.resize(normal, SCAN_SIZE, interpolation=cv2.INTER_AREA)
            (OUT / "scan" / f"{k}.png").write_bytes(_png(scan))
        meta.append({"key": k, "scryfall_id": card["id"], "name": card["name"],
                     "set": card["set"], "collector_number": card["collector_number"],
                     "artist": card.get("artist", "")})
        print(f"  {k}: {card['name']}")
    for set_code, cn, size, seed in PHOTOS:
        k = _key(set_code, cn)
        (OUT / "photo" / f"{k}.png").write_bytes(_png(_simulate_photo(normals[k], size, seed)))
    for name, img in _synthetic().items():
        (OUT / "synth" / f"{name}.png").write_bytes(_png(img))
    (OUT / "cards.json").write_text(json.dumps(meta, indent=1, ensure_ascii=False) + "\n",
                                    encoding="utf-8")


# ── expected answers (the SERVER's code) ────────────────────────────────────

def _read(path: Path) -> np.ndarray:
    img = cv2.imread(str(path), cv2.IMREAD_COLOR)
    assert img is not None, path
    return img


def _stages(crop) -> dict:
    """Every intermediate of imagehash.phash on one crop, cross-checked against
    imagehash itself so the stages can never disagree with the final bits."""
    out = {}
    for hs in (8, 16):
        size = hs * 4
        small = crop.convert("L").resize((size, size), Image.Resampling.LANCZOS)
        px = np.asarray(small)
        dct = scipy.fftpack.dct(scipy.fftpack.dct(px, axis=0), axis=1)
        low = dct[:hs, :hs]
        med = np.median(low)
        bits = "".join("1" if b else "0" for b in (low > med).ravel())
        ref = str(imagehash.phash(crop, hash_size=hs))
        assert int(bits, 2) == int(ref, 16), "stage recomputation drifted from imagehash"
        if size == 32:
            out["r32"] = base64.b64encode(px.tobytes()).decode()
        else:
            out["r64_sha256"] = _sha(px.tobytes())
        out[f"dct{hs}"] = _dhex(low)
        out[f"median{hs}"] = _dhex([med])
    return out


def _recording_crops():
    """Wrap visual_match._crop_region so _hash_variants reports its boxes."""
    boxes: list[list[int]] = []
    real = vm._crop_region

    def rec(img, y0, y1, x0, x1):
        w, h = img.size
        boxes.append([int(w * x0), int(h * y0), int(w * x1), int(h * y1)])
        return real(img, y0, y1, x0, x1)
    return boxes, rec, real


def _scan_entry(idx: ArtIndex, frame: np.ndarray) -> dict:
    card_pil = idx._card_pil(frame)
    gray = np.asarray(card_pil.convert("L"))
    boxes, rec, real = _recording_crops()
    vm._crop_region = rec
    try:
        core = idx._hash_variants(frame, _CORE_PARAMS, card_pil)
        core_boxes = list(boxes)
        boxes.clear()
        dense = idx._hash_variants(frame, _DENSE_PARAMS, card_pil)
        dense_boxes = list(boxes)
    finally:
        vm._crop_region = real
    r32 = hashlib.sha256()
    r64 = hashlib.sha256()
    for b in core_boxes:
        c = card_pil.crop(tuple(b)).convert("L")
        r32.update(np.asarray(c.resize((32, 32), Image.Resampling.LANCZOS)).tobytes())
        r64.update(np.asarray(c.resize((64, 64), Image.Resampling.LANCZOS)).tobytes())
    dense_hex = "\n".join(f"{b[0]},{b[1]},{b[2]},{b[3]}:{_hex64(h)}:{_hex256(w)}"
                          for b, (h, w) in zip(dense_boxes, dense))  # same line format as "core"
    base = vm.crop_art_region(card_pil)
    return {
        "half": [card_pil.size[0], card_pil.size[1]],
        "gray_sha256": _sha(gray.tobytes()),
        "base": _stages(base),
        # "x0,y0,x1,y1:h64:h256" per crop, grid order (skipped crops absent)
        "core": [f"{b[0]},{b[1]},{b[2]},{b[3]}:{_hex64(h)}:{_hex256(w)}"
                 for b, (h, w) in zip(core_boxes, core)],
        "core_r32_sha256": r32.hexdigest(),
        "core_r64_sha256": r64.hexdigest(),
        "dense_count": len(dense),
        "dense_sha256": _sha(dense_hex.encode()),
    }


def _index_hash(bgr: np.ndarray) -> tuple[str, str, dict]:
    """ArtIndexBuilder.build's hashing of one `small` image."""
    pil = Image.fromarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
    crop = vm.crop_art_region(pil)
    return str(imagehash.phash(crop)), str(imagehash.phash(crop, hash_size=16)), _stages(crop)


def _decoys(real_rows: list[dict]) -> list[dict]:
    """Near-miss rows (a few-to-many bits off a real artwork) so the 400-row
    shortlist actually cuts and dedupe/tie order is exercised. Randomness comes
    from sha256 of a counter — stable across numpy/Python versions, which
    --check on CI's freshly installed libraries depends on."""
    def rand(tag: str, i: int, j: int, n: int) -> int:
        return int.from_bytes(hashlib.sha256(f"{tag}:{i}:{j}".encode()).digest()[:8], "big") % n

    out = []
    for i in range(N_DECOYS_PER_CARD * len(real_rows)):
        src = real_rows[i % len(real_rows)]
        h64 = int(src["hash_hex"], 16)
        h256 = int(src["hash256_hex"], 16)
        flips = 6 + rand("flips", i, 0, 54)
        # XOR of (possibly repeated) positions: a repeat cancels — still deterministic.
        for j in range(max(1, flips // 4)):
            h64 ^= 1 << rand("b64", i, j, 64)
        for j in range(flips):
            h256 ^= 1 << rand("b256", i, j, 256)
        n = i % 97                                   # repeated decoy names → dedupe
        out.append({"scryfall_id": f"decoy-{i:05d}", "name": f"Decoy {n:02d}",
                    "set": "dcy", "collector_number": str(i), "artist": "",
                    "hash_hex": f"{h64:016x}", "hash256_hex": f"{h256:064x}"})
    return out


def _load_index(rows: list[dict]) -> ArtIndex:
    idx = ArtIndex(index_dir=ROOT / ".no-such-index")
    idx._meta = [(r["scryfall_id"], r["name"], r["set"], r["collector_number"], r["artist"])
                 for r in rows]
    idx._h64 = np.array([int(r["hash_hex"], 16) for r in rows], dtype=np.uint64)
    idx._h256 = np.array([_split_u64(r["hash256_hex"], 4) for r in rows], dtype=np.uint64)
    idx._warp = lambda frame: frame      # inputs are already flattened cards
    return idx


def build() -> dict[str, bytes]:
    cards = json.loads((OUT / "cards.json").read_text(encoding="utf-8"))
    idx = _load_index([{"scryfall_id": "x", "name": "x", "set": "x", "collector_number": "x",
                        "artist": "", "hash_hex": "0" * 16, "hash256_hex": "0" * 64}])

    index_side = {}
    real_rows = []
    for c in cards:
        h64, h256, stages = _index_hash(_read(OUT / "small" / f"{c['key']}.png"))
        index_side[c["key"]] = {"h64": h64, "h256": h256, "base": stages}
        real_rows.append({"scryfall_id": c["scryfall_id"], "name": c["name"], "set": c["set"],
                          "collector_number": c["collector_number"], "artist": c["artist"],
                          "hash_hex": h64, "hash256_hex": h256})
    # An Arena rebalance twin of the first card, BEFORE it so it wins the tie:
    # identify must report the paper name (the "A-" rule) with the twin's id.
    twin = dict(real_rows[0], scryfall_id="a-twin-0000", name="A-" + real_rows[0]["name"],
                set="yneo")
    rows = [twin] + real_rows + _decoys(real_rows)

    inputs = sorted(p.relative_to(OUT).as_posix()
                    for sub in ("small", "scan", "photo", "synth")
                    for p in (OUT / sub).glob("*.png"))
    scans = {}
    full = _load_index(rows)
    for rel in inputs:
        frame = _read(OUT / rel)
        entry = _scan_entry(idx, frame)
        matches = full.identify(frame, top_n=5)
        entry["identify"] = matches
        entry["confident"] = _is_confident(matches)
        entry["confidence"] = _confidence_for(matches[0]["distance"]) if matches else None
        scans[rel] = entry

    # Resize sweep: crops of every awkward size (up- and down-sampling, 1:1
    # on one axis) from the noise input tiled — noise[y % H, x % W].
    noise = np.asarray(Image.fromarray(cv2.cvtColor(_read(OUT / "synth" / "noise.png"),
                                                    cv2.COLOR_BGR2RGB)).convert("L"))
    sweep = []
    for w, h in RESIZE_SWEEP:
        src = np.ascontiguousarray(np.tile(noise, (h // noise.shape[0] + 1,
                                                   w // noise.shape[1] + 1))[:h, :w])
        crop = Image.fromarray(src, "L")
        r = {"w": w, "h": h, "src_sha256": _sha(src.tobytes())}
        for s in (32, 64):
            r[f"r{s}_sha256"] = _sha(np.asarray(crop.resize((s, s), Image.Resampling.LANCZOS)).tobytes())
        sweep.append(r)

    # Every Lanczos coefficient table the phone can hit (input extents up to
    # 700 px → 32 / 64), read back from Pillow itself: resizing an "I" image
    # whose row p is an impulse of 2^22 at column p returns, per output
    # pixel, round(weight · 2^22) — the exact fixed-point weight the 8-bit
    # path uses (ROUND_UP ≡ normalize_coeffs_8bpc's rounding). Digested in
    # blocks of 50 sizes (int32 little-endian, row-major, sizes ascending).
    coeff_blocks = []
    for out in (32, 64):
        for start in range(1, COEFF_MAX_IN + 1, 50):
            d = hashlib.sha256()
            for n in range(start, min(start + 50, COEFF_MAX_IN + 1)):
                if n == out:
                    continue                  # same size: Pillow copies, no weights
                eye = np.eye(n, dtype=np.int32) * (1 << 22)
                m = np.asarray(Image.fromarray(eye, "I").resize((out, n), Image.Resampling.LANCZOS))
                d.update(m.astype("<i4").tobytes())
            coeff_blocks.append({"out": out, "from": start,
                                 "to": min(start + 49, COEFF_MAX_IN), "sha256": d.hexdigest()})

    expected = {
        "lanczos_coeffs": coeff_blocks,
        "thresholds": {"max_confident": art_index._MAX_CONFIDENT_DISTANCE,
                       "high_confidence": art_index._HIGH_CONFIDENCE_DISTANCE,
                       "margin_confident": art_index._MARGIN_CONFIDENT_DISTANCE,
                       "margin_min_gap": art_index._MARGIN_MIN_GAP,
                       "no_card": art_index._NO_CARD_DISTANCE,
                       "shortlist": art_index._SHORTLIST},
        "art_crop": [vm._ART_Y0, vm._ART_Y1, vm._ART_X0, vm._ART_X1],
        "core_params": [list(p) for p in _CORE_PARAMS],
        "dense_params": [list(p) for p in _DENSE_PARAMS],
        # [scryfall_id, name, set, collector_number, artist, hash_hex, hash256_hex]
        "index_rows": [[r["scryfall_id"], r["name"], r["set"], r["collector_number"],
                        r["artist"], r["hash_hex"], r["hash256_hex"]] for r in rows],
        "index_side": index_side,
        "scans": scans,
        "resize_sweep": sweep,
    }
    # No library versions in the file: CI installs the latest releases, so a
    # version bump alone must not fail --check — only a changed ANSWER does.
    files = {"expected.json": (json.dumps(expected, indent=1, sort_keys=True, ensure_ascii=False)
                               + "\n").encode("utf-8")}
    return files


def main() -> int:
    if "--refresh-inputs" in sys.argv:
        refresh_inputs()
    files = build()
    if "--check" in sys.argv:
        stale = [n for n, b in files.items()
                 if not (OUT / n).exists() or (OUT / n).read_bytes() != b]
        if stale:
            print("hash fixtures are stale — run: python scripts/export_hash_fixtures.py")
            for n in stale:
                print("  ", n)
            return 1
        print(f"hash fixtures up to date ({len(files)} files)")
        return 0
    for n, b in files.items():
        (OUT / n).write_bytes(b)
    print(f"wrote {', '.join(files)} to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
