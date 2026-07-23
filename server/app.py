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
        return FileResponse(_STATIC / "desktop.html")

    @app.get("/phone")
    def phone():
        return FileResponse(_STATIC / "phone.html")

    @app.get("/api/health")
    def health():
        return {"ok": True}

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
            card_img, _ = extract_card(pick_sharpest(frames))
            scan_images_dir.mkdir(parents=True, exist_ok=True)
            cv2.imwrite(str(scan_images_dir / f"{scan['id']}.jpg"), card_img,
                        [cv2.IMWRITE_JPEG_QUALITY, 90])
        except Exception as exc:
            print(f"  [server] could not save scan image for #{scan['id']}: {exc}")

        # Price the TOP-RANKED printing in the background so the review lists
        # can show an F2F low-high range before a printing is even picked.
        # Selecting a printing later overwrites this with the exact price.
        cands = result.get("candidates") or []
        if result["identified"] and cands:
            top = cands[0]

            def _price_top(scan_id: int, c: dict) -> None:
                try:
                    p = f2f.get_price(c.get("name", ""), c.get("set", ""),
                                      c.get("collector_number", ""), False,
                                      c.get("set_name", ""))
                    if p:
                        store.update_scan(scan_id, f2f=p.to_dict())
                except Exception as exc:
                    print(f"  [server] top-candidate pricing failed for #{scan_id}: {exc}")

            background_tasks.add_task(_price_top, scan["id"], top)
        return scan

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
        price = await run_in_threadpool(
            f2f.get_price, selection["name"], selection["set"],
            selection["collector_number"], foil, selection["set_name"],
        )
        return store.update_scan(
            scan_id, status="selected", selection=selection,
            f2f=price.to_dict() if price else None,
        )

    @app.patch("/api/scans/{scan_id}")
    async def patch_scan(scan_id: int, body: dict = Body(...)):
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
            if "finish" in body:  # foil status may have flipped — reprice
                price = await run_in_threadpool(
                    f2f.get_price, selection.get("name", ""), selection.get("set", ""),
                    selection.get("collector_number", ""), selection["foil"],
                    selection.get("set_name", ""),
                )
                fields["f2f"] = price.to_dict() if price else None

        if not fields:
            return scan
        return store.update_scan(scan_id, **fields)

    @app.delete("/api/scans/{scan_id}")
    def delete_scan(scan_id: int):
        (scan_images_dir / f"{scan_id}.jpg").unlink(missing_ok=True)
        return {"deleted": store.delete_scan(scan_id)}

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
