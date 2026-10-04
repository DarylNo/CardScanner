package io.github.darylno.cardscanner.core

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * `/api/device/snapshot.jpg` while zoomed (1.1.11): the browser draws the scan
 * Area on the snapshot as fractions of the picture, and the stored Area is in
 * BASE (1×) fractions — so a zoomed frame is placed, scaled by 1/z, centred on
 * a grey picture of the full frame's size ([ZoomFit.baseRect]). The desktop's
 * Area drawing then keeps mapping 1:1, and the grey ring shows how much of the
 * tray the zoom leaves out. At z ≤ 1 the frame is returned as it is (a copy).
 */
object ZoomSnapshot {
    /** The grey outside the zoomed view. */
    const val GREY = 96.0

    /** [frame] (an upright BGR frame at zoom [z]) as a base-space picture of the same size. The caller releases it. */
    fun composeBase(frame: Mat, z: Double): Mat {
        if (z <= 1.0) return frame.clone()
        val w = frame.cols(); val h = frame.rows()
        val r = ZoomFit.baseRect(w, h, z)
        val out = Mat(h, w, frame.type(), Scalar(GREY, GREY, GREY))
        val small = Mat()
        try {
            Imgproc.resize(frame, small, Size(r[2].toDouble(), r[3].toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val dst = out.submat(Rect(r[0], r[1], r[2], r[3]))
            small.copyTo(dst)
            dst.release()
        } finally {
            small.release()
        }
        return out
    }
}
