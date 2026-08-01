"""
FastAPI server for the MTG card scanner web app.

Two browsers talk to this one server on the LAN:
  * the PHONE (``/phone``) is the camera — it captures a card and POSTs the image;
  * the DESKTOP (``/``) is the control UI — it polls scans, shows art-ranked
    printing candidates to pick from, fetches Face to Face prices, and exports.

The art-matching pipeline (`Pipeline`) is built once and reused.  Dependencies
are injectable via ``create_app`` so the server is testable without a camera or
a built art index (inject a fake pipeline).
"""

from __future__ import annotations

import os
import subprocess
import threading
import time
from pathlib import Path
from typing import Any, Callable, Optional

import cv2
import numpy as np
from fastapi import BackgroundTasks, Body, FastAPI, File, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles

from server.export import build_mx_export
from server.store import ScanStore

_STATIC = Path(__file__).parent / "static"


def _git_version() -> str:
    """Short commit hash of the running code, or 'unknown' outside a checkout."""
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=Path(__file__).parent, text=True, timeout=5,
            stderr=subprocess.DEVNULL,
        ).strip()
    except Exception:
        return "unknown"


# Resolved once at import — identifies the code THIS process is running, which
# is exactly what the phone UI displays to prove it isn't a stale cached page
# talking to an old server (a real debugging dead-end that burned us).
APP_VERSION = _git_version()

# The HTML pages must never be cached: the phone kept running a weeks-old
# phone.html after a fix, which was invisible until a version number existed.
_NO_STORE = {"Cache-Control": "no-store"}


def _decode_image(data: bytes) -> Optional[np.ndarray]:
    """Decode uploaded image bytes to a BGR uint8 frame (same shape as a webcam frame)."""
    if not data:
        return None
    arr = np.frombuffer(data, np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    return img


def _default_pipeline_factory() -> Callable[[], Any]:
    """Build the real art-index+Scryfall pipeline lazily (defers index load)."""
    def factory():
        from mtg_card_scanner.art_index import ArtIndex
        from mtg_card_scanner.scryfall import ScryfallClient
        from mtg_card_scanner.pipeline import Pipeline
        return Pipeline(index=ArtIndex(), scryfall=ScryfallClient())
    return factory


def _foil_from_finish(finish: str) -> bool:
    return str(finish or "").strip().lower() not in ("non-foil", "nonfoil", "")


def create_app(
    pipeline_factory: Optional[Callable[[], Any]] = None,
    store: Optional[ScanStore] = None,
    f2f: Optional[Any] = None,
    scan_images_dir: Optional[Path] = None,
    auto_sweep_interval: Optional[float] = 60.0,
) -> FastAPI:
    app = FastAPI(title="MTG Card Scanner")

    store = store or ScanStore(os.getenv("SCAN_DB", "scans.db"))
    # Warped photo of each physical scan, kept so the user can compare their
    # actual card against the candidate printings while reviewing.
    scan_images_dir = Path(scan_images_dir or os.getenv("SCAN_IMAGES_DIR", "scan_images"))
    if f2f is None:
        from mtg_card_scanner.facetoface import FaceToFaceClient
        f2f = FaceToFaceClient()
    pipeline_factory = pipeline_factory or _default_pipeline_factory()

    _pipeline: dict[str, Any] = {"instance": None}

    def get_pipeline() -> Any:
        if _pipeline["instance"] is None:
            _pipeline["instance"] = pipeline_factory()
        return _pipeline["instance"]

    # ── pages + static ─────────────────────────────────────────────────────────
    app.mount("/static", StaticFiles(directory=str(_STATIC)), name="static")

    @app.get("/")
    def desktop():
        return FileResponse(_STATIC / "desktop.html", headers=_NO_STORE)

    @app.get("/phone")
    def phone():
        return FileResponse(_STATIC / "phone.html", headers=_NO_STORE)

    @app.get("/api/health")
    def health():
        return {"ok": True, "version": APP_VERSION}

    @app.get("/api/version")
    def version():
        return {"version": APP_VERSION}

    # ── scan (phone → server) ──────────────────────────────────────────────────
    @app.post("/api/scan")
    async def scan(background_tasks: BackgroundTasks, files: list[UploadFile] = File(...)):
        frames = []
        for f in files:
            img = _decode_image(await f.read())
            if img is not None:
                frames.append(img)
        if not frames:
            return JSONResponse({"error": "no decodable image uploaded"}, status_code=400)
        result = await run_in_threadpool(get_pipeline().scan_candidates, frames)
        if result.get("no_card"):
            # Empty tray / nothing card-like — report it but never store a row.
            return {"no_card": True, "identified": False,
                    "error": result.get("error", "No card detected.")}
        scan = store.create_scan(
            identified=result["identified"],
            card_read=result["card_read"],
            confidence=result["confidence"],
            candidates=result["candidates"],
            error=result.get("error"),
        )
        # Keep the warped photo of the physical card for later review.  A save
        # failure must never break the scan itself.
        try:
            from mtg_card_scanner.card_detect import extract_card, pick_sharpest
            sharpest = pick_sharpest(frames)
            card_img, detected = extract_card(sharpest)
            # Only store the warped card when the edges were actually found —
            # otherwise keep the untouched frame, so a failed detection shows
            # the real photo instead of a distorted crop of it.
            scan_images_dir.mkdir(parents=True, exist_ok=True)
            cv2.imwrite(str(scan_images_dir / f"{scan['id']}.jpg"),
                        card_img if detected else sharpest,
                        [cv2.IMWRITE_JPEG_QUALITY, 90])
        except Exception as exc:
            print(f"  [server] could not save scan image for #{scan['id']}: {exc}")

        # Single-printing auto-pick: when the identified card has exactly ONE
        # printing there is nothing to choose — select it (NM / Non-Foil / x1)
        # with auto_picked set, which the UIs render as a small warning so the
        # user knows it was filed for them and can glance-confirm. Auto-merge
        # applies, so a repeat copy lands as +1 quantity.
        cands = result.get("candidates") or []
        if result["identified"] and len(cands) == 1:
            scan = await _apply_selection(scan["id"], cands[0], "NM", "Non-Foil", 1, auto=True)
            if scan.get("merged_into"):
                return scan

        # Price the TOP-RANKED printing in the background so the review lists
        # can show an F2F low-high range before a printing is even picked.
        # Selecting a printing later overwrites this with the exact price.
        if result["identified"] and cands:
            top = cands[0]

            def _price_top(scan_id: int, c: dict) -> None:
                try:
                    p = _safe_get_price(c.get("name", ""), c.get("set", ""),
                                        c.get("collector_number", ""), False,
                                        c.get("set_name", ""))
                    if p:
                        # Guarded: if the user picked a printing while this
                        # fetch ran, their exact price must not be clobbered
                        # by the top CANDIDATE's price.
                        _write_price_if_current(scan_id, {"selected": False},
                                                p.to_dict())
                except Exception as exc:
                    print(f"  [server] top-candidate pricing failed for #{scan_id}: {exc}")

            background_tasks.add_task(_price_top, scan["id"], top)
        return scan

    # ── pricing ────────────────────────────────────────────────────────────────
    @app.post("/api/scans/{scan_id}/price")
    async def price_now(scan_id: int):
        """
        Price ONE scan on demand (the desktop's "Search F2F price" button).
        Synchronous — the UI shows a searching state until this returns.
        Prices the selected printing, else the top-ranked candidate.  A failed
        search never clears an existing price; it returns the scan with a
        transient ``f2f_search: "not_found"`` flag for the UI instead.
        """
        scan = store.get_scan(scan_id)
        if not scan:
            return JSONResponse({"error": "not found"}, status_code=404)
        printing = scan.get("selection") or (scan.get("candidates") or [None])[0]
        if not printing or not printing.get("name"):
            return JSONResponse(
                {"error": "nothing to price — no selection or candidates"},
                status_code=400,
            )
        was_selected = bool(scan.get("selection"))
        pid = printing.get("scryfall_id") or printing.get("id") or ""
        from mtg_card_scanner.facetoface import F2FUnavailableError
        try:
            price = await run_in_threadpool(
                f2f.get_price, printing.get("name", ""), printing.get("set", ""),
                printing.get("collector_number", ""), bool(printing.get("foil", False)),
                printing.get("set_name", ""),
            )
        except F2FUnavailableError:
            scan = store.get_scan(scan_id)
            if not scan:
                return JSONResponse({"error": "not found"}, status_code=404)
            scan["f2f_search"] = "unavailable"    # unreachable ≠ not listed
            return scan
        if price:
            expect = ({"selected": True, "scryfall_id": pid,
                       "foil": bool(printing.get("foil", False))}
                      if was_selected else {"selected": False})
            updated = _write_price_if_current(scan_id, expect, price.to_dict())
            if updated:
                return updated
        scan = store.get_scan(scan_id)
        if not scan:                      # deleted while the fetch was in flight
            return JSONResponse({"error": "not found"}, status_code=404)
        scan["f2f_search"] = "not_found"
        return scan

    # ── batch pricing ──────────────────────────────────────────────────────────
    # Live progress of the background price sweep — which card is being
    # searched right now, how far along, how fast. The sweep was previously
    # invisible; the UIs poll /api/price-status to display it.
    sweep = {"active": False, "total": 0, "done": 0, "current": "", "started": 0.0,
             "cancel": False, "manual_stop_at": None}
    # Serializes sweep start (check-and-set) — without it, price_missing's
    # active check and the 60s auto-tick can both pass and run two sweeps
    # concurrently, whose finally-blocks then make the survivor uncancellable.
    sweep_lock = threading.Lock()
    # Serializes every selection read-modify-write (merge quantity bumps,
    # select claims, PATCH edits): two concurrent selects of the same printing
    # could otherwise both read qty 1 and both write qty 2 — a physical card
    # silently vanishing from the export.
    select_lock = threading.Lock()

    @app.get("/api/price-status")
    def price_status():
        elapsed = time.monotonic() - sweep["started"] if sweep["active"] else 0.0
        rate = sweep["done"] / elapsed if sweep["active"] and elapsed > 0 else None
        remaining = max(0, sweep["total"] - sweep["done"])
        return {
            "active": sweep["active"],
            "total": sweep["total"],
            "done": sweep["done"],
            "current": sweep["current"],
            "cancelling": bool(sweep.get("cancel")),
            "rate_per_s": round(rate, 2) if rate else None,
            "eta_s": round(remaining / rate) if rate else None,
        }

    @app.post("/api/price-sweep/stop")
    def price_sweep_stop():
        """Cancel the running sweep (it stops within one card) and pause the
        auto-sweeper for 10 minutes so it doesn't immediately undo the stop.
        POSTing price-missing restarts right away and clears the pause."""
        sweep["manual_stop_at"] = time.monotonic()
        if sweep["active"]:
            sweep["cancel"] = True
            return {"stopping": True}
        return {"stopping": False}

    def _collect_price_targets() -> list[tuple[str, int, dict, bool]]:
        """
        Everything the sweeper still owes a price:
          - scans with a chosen printing but no completed search → the selection
          - UNPICKED scans → EVERY candidate printing not yet searched, so the
            price filter may hide a pending card only once every print it
            could possibly be is known to be out of range.
        A search that found no listing is recorded (empty conditions) so it is
        not re-searched every sweep; the manual per-scan button still forces.
        """
        targets: list[tuple[str, int, dict, bool]] = []
        for s in store.list_scans():
            if s.get("selection"):
                if s.get("f2f") is None and s["selection"].get("name"):
                    targets.append(("scan", s["id"], s["selection"],
                                    bool(s["selection"].get("foil", False))))
            else:
                for c in (s.get("candidates") or []):
                    if c.get("f2f_conditions") is None and c.get("name"):
                        targets.append(("print", s["id"], c, False))
        return targets

    def _claim_sweep(targets: list) -> bool:
        """Atomically flip the sweep to active. Claiming happens at SCHEDULE
        time (not run time) so the auto-tick can't start a second sweep in
        the gap between price_missing's response and its background task."""
        with sweep_lock:
            if sweep["active"]:
                return False
            sweep.update(active=True, total=len(targets), done=0, current="",
                         cancel=False, started=time.monotonic())
            return True

    def _run_sweep(items: list[tuple[str, int, dict, bool]]) -> None:
        """Requires a successful _claim_sweep by the caller."""
        try:
            for kind, sid, c, foil in items:
                if sweep.get("cancel"):
                    print(f"  [server] price sweep cancelled at {sweep['done']}/{sweep['total']}")
                    break
                sweep["current"] = c.get("name", "")
                try:
                    p = f2f.get_price(c.get("name", ""), c.get("set", ""),
                                      c.get("collector_number", ""), foil,
                                      c.get("set_name", ""))
                    if kind == "scan":
                        with select_lock:
                            # Recheck: the selection (printing AND foil) may
                            # have changed since collect time — a finish flip
                            # already repriced correctly, and a re-pick means
                            # this price belongs to the WRONG printing.
                            row = store.get_scan(sid)
                            sel = (row or {}).get("selection") or {}
                            if (row and row.get("f2f") is None
                                    and sel.get("scryfall_id") == c.get("scryfall_id")
                                    and bool(sel.get("foil", False)) == foil):
                                store.update_scan(sid, f2f=p.to_dict() if p
                                                  else {"conditions": {}, "searched": True})
                    else:
                        with select_lock:
                            scan_row = store.get_scan(sid)
                            # A pick may have landed mid-sweep — don't
                            # resurrect candidate data on a now-selected scan.
                            if scan_row and not scan_row.get("selection"):
                                cands = scan_row.get("candidates") or []
                                for cc in cands:
                                    if cc.get("id") == c.get("id"):
                                        cc["f2f_conditions"] = p.conditions if p else {}
                                store.update_scan(sid, candidates=cands)
                except Exception as exc:
                    # Includes F2FUnavailableError: an unreachable storefront
                    # skips ALL writes, so the target stays unsearched and the
                    # next sweep retries it. Only a real fetch that returned
                    # no match records the searched-empty marker above —
                    # get_price returning None now MEANS "confirmed unlisted".
                    print(f"  [server] pricing failed for #{sid}: {exc}")
                finally:
                    sweep["done"] += 1
        finally:
            with sweep_lock:
                sweep["active"] = False
                sweep["current"] = ""
                sweep["cancel"] = False

    def _retro_fix_scans() -> None:
        """
        Repair pending rows created before newer rules existed:
          - drop art-series candidates (the layout filter now stops them at
            fetch time, but rows scanned earlier stored them — observed: an
            'Art Series' printing sitting in a pick grid);
          - then auto-pick identified scans left with exactly ONE printing,
            same as scan-time auto-pick (never unconfident best-guess rows).
        Runs every auto-sweep tick; no-ops once the backlog is clean.
        """
        for s in store.list_scans():
            if s.get("selection"):
                continue
            cands = s.get("candidates") or []
            kept = [c for c in cands
                    if "art series" not in (c.get("set_name") or "").lower()]
            if len(kept) != len(cands):
                with select_lock:
                    row = store.get_scan(s["id"])
                    if row and not row.get("selection"):
                        store.update_scan(s["id"], candidates=kept)
            if s.get("identified") and len(kept) == 1:
                _apply_selection_core(s["id"], kept[0], "NM", "Non-Foil", 1, auto=True)

    # Auto-sweep: any unpriced work is picked up every minute without the user
    # pressing anything. A manual stop pauses it for 10 minutes; a manual
    # start clears the pause.
    def _auto_sweep_loop(interval: float) -> None:
        while True:
            time.sleep(interval)
            try:
                if sweep["active"]:
                    continue
                _retro_fix_scans()
                stopped_at = sweep.get("manual_stop_at")
                if stopped_at is not None and time.monotonic() - stopped_at < 600:
                    continue
                items = _collect_price_targets()
                if items and _claim_sweep(items):
                    print(f"  [server] auto-sweep: {len(items)} prices to fetch")
                    _run_sweep(items)
            except Exception as exc:
                print(f"  [server] auto-sweep error: {exc}")

    if auto_sweep_interval:
        threading.Thread(target=_auto_sweep_loop, args=(auto_sweep_interval,),
                         daemon=True, name="price-auto-sweep").start()

    @app.post("/api/scans/price-missing")
    async def price_missing(background_tasks: BackgroundTasks):
        """
        Start a sweep over everything _collect_price_targets still owes —
        selected scans' chosen printings and every unsearched candidate print
        of unpicked scans.  Runs in the background; the UIs follow progress
        via /api/price-status.  Also clears a manual-stop pause.
        """
        sweep["manual_stop_at"] = None
        targets = _collect_price_targets()
        if not targets:
            return {"queued": 0, "already_running": sweep["active"]}
        if not _claim_sweep(targets):
            return {"queued": 0, "already_running": True}
        background_tasks.add_task(_run_sweep, targets)
        return {"queued": len(targets)}

    @app.get("/api/scans/{scan_id}/image")
    def scan_image(scan_id: int):
        path = scan_images_dir / f"{scan_id}.jpg"
        if not path.exists():
            return JSONResponse({"error": "no image"}, status_code=404)
        return FileResponse(path, media_type="image/jpeg")

    # ── review (desktop) ───────────────────────────────────────────────────────
    @app.get("/api/scans")
    def list_scans():
        return store.list_scans()

    @app.get("/api/scans/{scan_id}")
    def get_scan(scan_id: int):
        scan = store.get_scan(scan_id)
        if not scan:
            return JSONResponse({"error": "not found"}, status_code=404)
        return scan

    def _apply_selection_core(scan_id: int, printing: dict, condition: str,
                              finish: str, quantity: int,
                              auto: bool = False) -> dict:
        """
        Select *printing* on scan *scan_id* — the one path for user picks,
        scan-time auto-picks, AND the retro repair thread.  Auto-merge: bulk
        lots contain several copies of the same card (measured: 17% of a real
        session), so selecting the EXACT printing+condition+finish an existing
        selected row already holds folds this scan into it as added quantity
        instead of a duplicate row.  Synchronous and price-free so it is
        callable from plain threads; _apply_selection adds pricing for user
        picks.  Auto picks carry auto_picked so the UIs can flag them.
        """
        foil = _foil_from_finish(finish)
        selection = {
            "scryfall_id": printing.get("id", ""),
            "name": printing.get("name", ""),
            "set": printing.get("set", ""),
            "set_name": printing.get("set_name", ""),
            "collector_number": printing.get("collector_number", ""),
            "condition": condition,
            "finish": finish,
            "quantity": quantity,
            "foil": foil,
            "image_normal": printing.get("image_normal", ""),
        }
        if auto:
            selection["auto_picked"] = True
        # The merge decision and the claim must be one atomic step: with the
        # price fetch inside this window (as before), two concurrent selects
        # of the same printing both missed each other (duplicate rows) or both
        # read the same base quantity (lost copies). Claim under the lock,
        # price AFTER.
        with select_lock:
            if selection["scryfall_id"]:
                for other in store.list_scans():
                    if other["id"] == scan_id or other.get("status") != "selected":
                        continue
                    o = dict(other.get("selection") or {})
                    if (o.get("scryfall_id") == selection["scryfall_id"]
                            and o.get("condition") == condition
                            and o.get("finish") == finish):
                        o["quantity"] = int(o.get("quantity") or 1) + quantity
                        merged = store.update_scan(other["id"], selection=o)
                        (scan_images_dir / f"{scan_id}.jpg").unlink(missing_ok=True)
                        store.delete_scan(scan_id)
                        merged["merged_into"] = other["id"]
                        return merged
            # error=None: a best-guess scan the user then picks must stop
            # showing "No confident art match" and leave the Problems view.
            return store.update_scan(
                scan_id, status="selected", selection=selection, error=None)

    def _safe_get_price(name: str, set_code: str, collector_number: str,
                        foil: bool, set_name: str):
        """
        get_price that treats storefront-unavailable as 'no price yet' (None).
        ONLY for callers that never persist an empty result — the sweep must
        NOT use this, because it records None as a permanent no-listing
        marker and unavailability must stay retryable.
        """
        from mtg_card_scanner.facetoface import F2FUnavailableError
        try:
            return f2f.get_price(name, set_code, collector_number, foil, set_name)
        except F2FUnavailableError as exc:
            print(f"  [server] F2F unavailable: {exc}")
            return None

    def _write_price_if_current(scan_id: int, expect: dict, f2f_value) -> Optional[dict]:
        """
        Store an F2F result ONLY if the scan still matches what was priced.
        Every pricer races the user (fetches take seconds) and the last fetch
        to land used to win regardless of what it had priced — observed
        classes: select's non-foil result overwriting a foil-flip reprice,
        and scan-time _price_top's top-candidate price clobbering the user's
        just-picked exact printing. expect={"selected": True, "scryfall_id",
        "foil"} for selection prices; {"selected": False} for top-candidate
        prices on still-unpicked scans.
        """
        with select_lock:
            row = store.get_scan(scan_id)
            if not row:
                return None
            sel = row.get("selection") or {}
            if expect.get("selected"):
                if (sel.get("scryfall_id") != expect.get("scryfall_id")
                        or bool(sel.get("foil", False)) != bool(expect.get("foil", False))):
                    return row                  # stale price — keep the fresher one
            elif sel:
                return row                      # a pick landed — keep its price
            return store.update_scan(scan_id, f2f=f2f_value)

    async def _apply_selection(scan_id: int, printing: dict, condition: str,
                               finish: str, quantity: int,
                               auto: bool = False) -> dict:
        result = _apply_selection_core(scan_id, printing, condition, finish,
                                       quantity, auto=auto)
        if auto or result.get("merged_into"):
            return result
        sel = result["selection"]
        price = await run_in_threadpool(
            _safe_get_price, sel["name"], sel["set"],
            sel["collector_number"], sel["foil"], sel["set_name"],
        )
        updated = _write_price_if_current(
            scan_id,
            {"selected": True, "scryfall_id": sel["scryfall_id"], "foil": sel["foil"]},
            price.to_dict() if price else None,
        )
        return updated or result

    @app.post("/api/scans/{scan_id}/select")
    async def select(scan_id: int, body: dict = Body(...)):
        scan = store.get_scan(scan_id)
        if not scan:
            return JSONResponse({"error": "not found"}, status_code=404)
        printing = body.get("printing") or {}
        if not printing.get("set") or not printing.get("collector_number"):
            return JSONResponse({"error": "printing needs set + collector_number"}, status_code=400)
        condition = str(
            body.get("condition") or scan["card_read"].get("condition_estimate") or "NM"
        ).upper()
        finish = body.get("finish") or "Non-Foil"
        quantity = max(1, int(body.get("quantity") or 1))
        return await _apply_selection(scan_id, printing, condition, finish, quantity)

    @app.patch("/api/scans/{scan_id}")
    async def patch_scan(scan_id: int, body: dict = Body(...)):
        # Selection read-modify-write happens under select_lock (a concurrent
        # merge bumping quantity could otherwise be overwritten); the reprice
        # network call stays OUTSIDE the lock.
        with select_lock:
            scan = store.get_scan(scan_id)
            if not scan:
                return JSONResponse({"error": "not found"}, status_code=404)
            fields: dict[str, Any] = {}
            if "included" in body:
                fields["included"] = bool(body["included"])
            selection = dict(scan.get("selection") or {})
            changed = False
            for key in ("condition", "finish", "quantity"):
                if key in body:
                    selection[key] = body[key]
                    changed = True
            if changed and selection:
                selection["condition"] = str(selection.get("condition", "NM")).upper()
                selection["quantity"] = max(1, int(selection.get("quantity") or 1))
                selection["foil"] = _foil_from_finish(selection.get("finish", "Non-Foil"))
                fields["selection"] = selection
            if fields:
                scan = store.update_scan(scan_id, **fields)

        if changed and selection and "finish" in body:
            # foil status may have flipped — reprice (guarded: a re-pick that
            # lands during this fetch keeps ITS price, not this stale one)
            price = await run_in_threadpool(
                _safe_get_price, selection.get("name", ""), selection.get("set", ""),
                selection.get("collector_number", ""), selection["foil"],
                selection.get("set_name", ""),
            )
            updated = _write_price_if_current(
                scan_id,
                {"selected": True, "scryfall_id": selection.get("scryfall_id"),
                 "foil": selection["foil"]},
                price.to_dict() if price else None,
            )
            scan = updated or scan
        return scan

    @app.delete("/api/scans/{scan_id}")
    def delete_scan(scan_id: int):
        (scan_images_dir / f"{scan_id}.jpg").unlink(missing_ok=True)
        return {"deleted": store.delete_scan(scan_id)}

    @app.post("/api/scans/delete-all")
    def delete_all_scans(body: dict = Body(default={})):
        """
        Clear the whole scan list (and their photos).

        ``{"only": "unselected"}`` keeps everything that already has a chosen
        printing — the usual way to sweep junk without losing real work.
        """
        only = str(body.get("only") or "").lower()
        targets = [
            s for s in store.list_scans()
            if only != "unselected" or s.get("status") != "selected"
        ]
        for s in targets:
            (scan_images_dir / f"{s['id']}.jpg").unlink(missing_ok=True)
            store.delete_scan(s["id"])
        return {"deleted": len(targets)}

    # ── manual re-identification ────────────────────────────────────────────────
    @app.get("/api/search")
    async def search(q: str):
        if not q.strip():
            return {"candidates": []}
        cands = await run_in_threadpool(get_pipeline().search_candidates, q)
        return {"candidates": cands}

    # ── export (Mana Exchange mass-entry text) ─────────────────────────────────
    @app.get("/api/export")
    def export():
        text = build_mx_export(store.included_selected())
        return PlainTextResponse(
            text,
            headers={"Content-Disposition": "attachment; filename=mana-exchange-import.txt"},
        )

    app.state.store = store
    return app


app = create_app()
