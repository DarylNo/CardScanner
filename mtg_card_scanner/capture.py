"""Webcam capture — single-frame on demand or auto-capture when image is steady."""

import cv2
import numpy as np


def capture_frame(camera_index: int = 0) -> np.ndarray:
    """Capture a single frame from the webcam and return it."""
    cap = cv2.VideoCapture(camera_index)
    if not cap.isOpened():
        raise RuntimeError(
            f"Cannot open camera {camera_index}. "
            "Check that the camera is connected and not in use by another app."
        )
    try:
        ret, frame = cap.read()
        if not ret or frame is None:
            raise RuntimeError("Failed to read frame from camera.")
        return frame
    finally:
        cap.release()


def wait_for_steady_frame(
    camera_index: int = 0,
    diff_threshold: float = 1.5,
    stable_count: int = 10,
) -> np.ndarray:
    """
    Stream frames and return once the image has been stable for `stable_count`
    consecutive frames (mean pixel diff below `diff_threshold`).

    Key bindings while the preview window is open:
      s  — capture the current frame immediately
      q  — cancel (raises KeyboardInterrupt)
    """
    cap = cv2.VideoCapture(camera_index)
    if not cap.isOpened():
        raise RuntimeError(f"Cannot open camera {camera_index}.")

    prev_gray: np.ndarray | None = None
    steady = 0
    last_frame: np.ndarray | None = None

    try:
        print("Auto-capture active — hold the card steady. Press 's' to capture now, 'q' to cancel.")
        while True:
            ret, frame = cap.read()
            if not ret or frame is None:
                continue

            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

            if prev_gray is not None:
                diff = cv2.absdiff(gray, prev_gray)
                mean_diff = float(diff.mean())

                if mean_diff < diff_threshold:
                    steady += 1
                else:
                    steady = 0

                label = f"Steady: {steady}/{stable_count}  diff={mean_diff:.2f}"
                color = (0, 200, 0) if steady > 0 else (0, 60, 200)
                display = frame.copy()
                cv2.putText(display, label, (10, 34), cv2.FONT_HERSHEY_SIMPLEX, 0.8, color, 2)
                cv2.imshow("MTG Scanner — auto-capture", display)

                if steady >= stable_count:
                    cv2.destroyAllWindows()
                    return frame

            prev_gray = gray
            last_frame = frame

            key = cv2.waitKey(1) & 0xFF
            if key == ord("q"):
                raise KeyboardInterrupt("Cancelled by user.")
            if key == ord("s") and last_frame is not None:
                cv2.destroyAllWindows()
                return last_frame
    finally:
        cap.release()
