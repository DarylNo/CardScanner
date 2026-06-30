"""
Live camera preview window for the MTG card scanner.

ScannerPreview opens an OpenCV window, streams the webcam feed with a
steadiness indicator, and calls a user-supplied scan_callback when the
user presses a key or (in auto mode) when the image is stable enough.

Keys:
  s / SPACE   trigger a scan immediately
  q / ESC     close the window and return
"""

import time
from dataclasses import dataclass
from typing import Callable, Optional

import cv2
import numpy as np

from mtg_card_scanner.output import ScanResult

# ── tunables ──────────────────────────────────────────────────────────────────
_STEADY_THRESHOLD  = 1.5   # mean-pixel diff below this = steady frame
_STEADY_NEEDED     = 10    # consecutive steady frames before auto-trigger
_RESULT_TTL        = 9.0   # seconds to display last scan result
_FONT              = cv2.FONT_HERSHEY_SIMPLEX
_WINDOW            = "MTG Card Scanner"
_BURST_FRAMES      = 3     # number of frames captured per scan trigger
_BURST_INTERVAL_MS = 80    # ms between burst frames

ScanCallback = Callable[[list], Optional[ScanResult]]


@dataclass
class _ResultOverlay:
    result: ScanResult
    expires_at: float


class ScannerPreview:
    """
    Persistent OpenCV preview window.  Call :meth:`run` to open it; it blocks
    until the user presses q/ESC.  The window updates at the camera frame rate
    and freezes briefly during the model call (typically a few seconds).
    """

    def __init__(self, camera_index: int = 0, burst_frames: int = _BURST_FRAMES) -> None:
        self.camera_index = camera_index
        self.burst_frames = burst_frames
        self._overlay: Optional[_ResultOverlay] = None
        self._cap: Optional[cv2.VideoCapture] = None

    # ── public ────────────────────────────────────────────────────────────────

    def run(self, callback: ScanCallback, auto_mode: bool = False) -> None:
        """
        Open the live preview and block until the user quits.

        :param callback:  Called with a BGR numpy frame when a scan is triggered.
                          Should return a ScanResult or None on error.
        :param auto_mode: If True, auto-trigger when the image is steady for
                          ~_STEADY_NEEDED frames.  Otherwise waits for s/SPACE.
        """
        cap = cv2.VideoCapture(self.camera_index)
        if not cap.isOpened():
            raise RuntimeError(
                f"Cannot open camera {self.camera_index}. "
                "Check the camera is connected and not used by another app."
            )

        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
        self._cap = cap

        cv2.namedWindow(_WINDOW, cv2.WINDOW_NORMAL)
        cv2.resizeWindow(_WINDOW, 1280, 720)

        prev_gray: Optional[np.ndarray] = None
        steady_count  = 0
        auto_triggered = False   # prevents continuous re-scan in auto mode

        mode_label = "AUTO" if auto_mode else "MANUAL"
        print(f"[preview] Camera {self.camera_index} opened — mode={mode_label}")
        print("[preview] Keys: [S/SPACE] scan  [Q/ESC] quit")

        try:
            while True:
                ret, frame = cap.read()
                if not ret or frame is None:
                    continue

                # ── steadiness ───────────────────────────────────────────────
                gray      = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
                mean_diff = 0.0
                if prev_gray is not None:
                    diff      = cv2.absdiff(gray, prev_gray)
                    mean_diff = float(diff.mean())
                    if mean_diff < _STEADY_THRESHOLD:
                        steady_count = min(steady_count + 1, _STEADY_NEEDED)
                    else:
                        steady_count  = max(steady_count - 2, 0)
                        auto_triggered = False
                prev_gray = gray
                is_steady = steady_count >= _STEADY_NEEDED

                # ── draw & show ───────────────────────────────────────────────
                display = _compose(
                    frame, is_steady, steady_count, mean_diff,
                    auto_mode, self._overlay,
                )
                cv2.imshow(_WINDOW, display)

                # ── auto-trigger ─────────────────────────────────────────────
                if auto_mode and is_steady and not auto_triggered:
                    auto_triggered = True
                    self._do_scan(frame, callback)
                    steady_count = 0

                # ── expire overlay ───────────────────────────────────────────
                if self._overlay and time.monotonic() >= self._overlay.expires_at:
                    self._overlay = None

                # ── key handling ─────────────────────────────────────────────
                key = cv2.waitKey(1) & 0xFF
                if key in (ord("q"), 27):          # q or ESC
                    break
                if key in (ord("s"), ord(" ")):    # s or SPACE
                    self._do_scan(frame, callback)

        finally:
            self._cap = None
            cap.release()
            cv2.destroyAllWindows()
            print("[preview] Window closed.")

    # ── private ───────────────────────────────────────────────────────────────

    def _do_scan(self, trigger_frame: np.ndarray, callback: ScanCallback) -> None:
        """
        Capture a burst of frames, show a freeze-frame with 'SCANNING' overlay,
        then call callback with the list of frames.
        """
        h, w = trigger_frame.shape[:2]

        # Paint a feedback frame immediately so the user knows it started
        scanning = trigger_frame.copy()
        _top_bar(scanning, "SCANNING — please wait...", (0, 200, 255))
        cv2.rectangle(scanning, (0, 0), (w - 1, h - 1), (0, 200, 255), 5)
        cv2.imshow(_WINDOW, scanning)
        cv2.waitKey(1)          # flush to display

        # Capture burst frames (trigger frame + N-1 more)
        frames = [trigger_frame]
        for _ in range(self.burst_frames - 1):
            time.sleep(_BURST_INTERVAL_MS / 1000.0)
            if self._cap is not None and self._cap.isOpened():
                ret, f = self._cap.read()
                if ret and f is not None:
                    frames.append(f)

        try:
            result = callback(frames)
            if result is not None:
                self._overlay = _ResultOverlay(
                    result=result,
                    expires_at=time.monotonic() + _RESULT_TTL,
                )
        except Exception as exc:
            print(f"[preview] Scan error: {exc}")


# ── drawing helpers (module-level, pure functions) ────────────────────────────

def _top_bar(img: np.ndarray, text: str, color: tuple, alpha: float = 0.65) -> None:
    """Draw a semi-transparent top status bar with text in-place on img."""
    h, w = img.shape[:2]
    bar = img.copy()
    cv2.rectangle(bar, (0, 0), (w, 50), (18, 18, 18), -1)
    cv2.addWeighted(bar, alpha, img, 1.0 - alpha, 0, img)
    cv2.putText(img, text, (14, 34), _FONT, 0.85, color, 2, cv2.LINE_AA)


def _result_panel(img: np.ndarray, overlay: _ResultOverlay) -> None:
    """Draw a semi-transparent results panel at the bottom of img in-place."""
    h, w = img.shape[:2]
    r  = overlay.result
    remaining = max(0, int(overlay.expires_at - time.monotonic()))

    foil_tag  = " [FOIL]" if r.foil else ""
    price_val = r.price_usd_foil if (r.foil and r.price_usd_foil) else r.price_usd
    price_str = f"${price_val}" if price_val else "N/A"

    # Build match/confidence summary for 4th line
    match_parts = []
    if r.match_method == "phash" and r.phash_distance is not None:
        match_parts.append(f"pHash d={r.phash_distance}")
    elif r.match_method:
        match_parts.append(r.match_method)
    if r.name_confidence:
        match_parts.append(f"conf:{r.name_confidence}")
    match_line = "  ·  ".join(match_parts) if match_parts else ""

    lines = [
        (f"{r.scryfall_name}{foil_tag}",                                        (100, 255, 100)),
        (f"{r.scryfall_set_name}  ·  #{r.collector_number}  ·  {r.language.upper()}",  (215, 215, 215)),
        (f"{r.condition}  ·  {r.scryfall_rarity.title()}  ·  {price_str} USD  [{remaining}s]",
         (100, 220, 255)),
    ]
    if match_line:
        lines.append((match_line, (180, 180, 100)))

    line_h   = 40
    panel_h  = line_h * len(lines) + 16
    y0       = h - panel_h

    bg = img.copy()
    cv2.rectangle(bg, (0, y0), (w, h), (12, 12, 12), -1)
    cv2.addWeighted(bg, 0.70, img, 0.30, 0, img)

    for i, (text, color) in enumerate(lines):
        cv2.putText(img, text, (14, y0 + 34 + i * line_h), _FONT, 0.84, color, 2, cv2.LINE_AA)


def _compose(
    frame: np.ndarray,
    is_steady: bool,
    steady_count: int,
    mean_diff: float,
    auto_mode: bool,
    overlay: Optional[_ResultOverlay],
) -> np.ndarray:
    """Return a new frame with all UI elements composited onto it."""
    h, w   = frame.shape[:2]
    out    = frame.copy()
    ratio  = steady_count / _STEADY_NEEDED

    # ── border colour ─────────────────────────────────────────────────────────
    if ratio >= 1.0:
        border = (0, 215, 0)           # green  — steady
    elif ratio > 0.4:
        border = (0, 200, 200)         # yellow — approaching
    else:
        border = (60, 60, 200)         # red    — moving
    cv2.rectangle(out, (0, 0), (w - 1, h - 1), border, 4)

    # ── top status bar ────────────────────────────────────────────────────────
    if auto_mode and is_steady:
        _top_bar(out, "STEADY — CAPTURED!", (0, 215, 0))
    elif auto_mode:
        pct = int(ratio * 100)
        col = (180, 200, 60) if ratio > 0.4 else (160, 160, 200)
        _top_bar(out, f"Hold steady...  {pct}%    (diff={mean_diff:.1f})", col)
    else:
        _top_bar(out, "READY  —  [S] or [SPACE] to scan", (200, 200, 200))

    # ── result panel ──────────────────────────────────────────────────────────
    if overlay and time.monotonic() < overlay.expires_at:
        _result_panel(out, overlay)

    # ── bottom hint ───────────────────────────────────────────────────────────
    if auto_mode:
        hint = "[AUTO]  Hold card steady to scan    [Q / ESC]  Quit"
    else:
        hint = "[S / SPACE]  Scan    [Q / ESC]  Quit"
    cv2.putText(out, hint, (14, h - 10), _FONT, 0.55, (135, 135, 135), 1, cv2.LINE_AA)

    return out
