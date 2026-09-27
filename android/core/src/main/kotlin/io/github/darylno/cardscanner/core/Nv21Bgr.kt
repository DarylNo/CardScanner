package io.github.darylno.cardscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * NV21 camera frames → upright OpenCV Mats for the capture pipeline.
 *
 * Only the (at most three) frames of a capture are converted — the 5 Hz
 * detection loop reads luma through [GraySampler] without any Mat — so this
 * favours plain, obviously-correct native calls (one cvtColor, one rotate)
 * over cleverness. Callers own and must release() the returned Mats.
 */
object Nv21Bgr {
    /**
     * OpenCV `Core.rotate` code for a CameraX rotation, or null for 0°.
     * Same mapping as [Rotation] (RotationTest holds the two together).
     */
    fun rotateCode(rotation: Int): Int? = when (Rotation.normalize(rotation)) {
        0 -> null
        90 -> Core.ROTATE_90_CLOCKWISE
        180 -> Core.ROTATE_180
        else -> Core.ROTATE_90_COUNTERCLOCKWISE
    }

    /** Full frame, upright, 8UC3 BGR: COLOR_YUV2BGR_NV21 on the (h·3/2)×w byte Mat, then rotate. */
    fun toUprightBgr(frame: Nv21Frame): Mat {
        val yuv = Mat(frame.height * 3 / 2, frame.width, CvType.CV_8UC1)
        val bgr = Mat()
        try {
            yuv.put(0, 0, frame.data)      // copies only the Mat's own size, see [Nv21Frame.data]
            Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_NV21)
        } finally {
            yuv.release()
        }
        return rotated(bgr, frame.rotation)
    }

    /**
     * Upright luma only (8UC1) — for ranking frames by [Sharpness] without a
     * colour conversion. With [roi], just the scan area (cropped in sensor
     * space BEFORE rotating, and returned as its own continuous Mat so filters
     * never read pixels outside the crop).
     */
    fun uprightGray(frame: Nv21Frame, roi: RoiFrac? = null): Mat {
        val y = Mat(frame.height, frame.width, CvType.CV_8UC1)
        y.put(0, 0, frame.data)            // the first w·h bytes = the Y plane
        if (roi == null) return rotated(y, frame.rotation)
        val r = sensorRect(frame, roi.toPixels(frame.uprightWidth, frame.uprightHeight))
        val crop = y.submat(r.y, r.y + r.h, r.x, r.x + r.w)
        try {
            val code = rotateCode(frame.rotation)
            val out = Mat()
            if (code == null) crop.copyTo(out) else Core.rotate(crop, out, code)
            return out
        } finally {
            crop.release(); y.release()
        }
    }

    /**
     * The sensor-space rectangle covering upright pixel rectangle [area]
     * (the pixels GraySampler averages for the same area).
     */
    fun sensorRect(frame: Nv21Frame, area: RoiPx): RoiPx {
        val sw = frame.width; val sh = frame.height; val rot = frame.rotation
        val xa = Rotation.sensorX(area.x, area.y, sw, sh, rot)
        val xb = Rotation.sensorX(area.right - 1, area.bottom - 1, sw, sh, rot)
        val ya = Rotation.sensorY(area.x, area.y, sw, sh, rot)
        val yb = Rotation.sensorY(area.right - 1, area.bottom - 1, sw, sh, rot)
        return RoiPx(minOf(xa, xb), minOf(ya, yb), maxOf(xa, xb) - minOf(xa, xb) + 1, maxOf(ya, yb) - minOf(ya, yb) + 1)
    }

    /**
     * The inverse, for tests and fixtures: an UPRIGHT BGR image → the NV21
     * frame a sensor with [rotation] would have delivered (rotate back, BGR →
     * I420, interleave V,U). Chroma is subsampled 2×2, so colour edges soften;
     * luma survives to within OpenCV's YUV rounding.
     */
    fun fromUprightBgr(bgr: Mat, rotation: Int, timestampNs: Long = 0L): Nv21Frame {
        require(bgr.type() == CvType.CV_8UC3) { "expected 8UC3 BGR, got type ${bgr.type()}" }
        val rot = Rotation.normalize(rotation)
        val sensor = Mat()
        val i420 = Mat()
        try {
            // Undo the upright rotation: rotating by (360 - rot) clockwise.
            when (rot) {
                0 -> bgr.copyTo(sensor)
                90 -> Core.rotate(bgr, sensor, Core.ROTATE_90_COUNTERCLOCKWISE)
                180 -> Core.rotate(bgr, sensor, Core.ROTATE_180)
                else -> Core.rotate(bgr, sensor, Core.ROTATE_90_CLOCKWISE)
            }
            val w = sensor.cols(); val h = sensor.rows()
            require(w % 2 == 0 && h % 2 == 0) { "NV21 needs even dimensions: ${w}x$h" }
            Imgproc.cvtColor(sensor, i420, Imgproc.COLOR_BGR2YUV_I420)
            val planar = ByteArray(w * h * 3 / 2)
            i420.get(0, 0, planar)
            val nv21 = ByteArray(w * h * 3 / 2)
            System.arraycopy(planar, 0, nv21, 0, w * h)
            val q = (w / 2) * (h / 2)
            val uOff = w * h; val vOff = w * h + q
            for (k in 0 until q) {
                nv21[w * h + 2 * k] = planar[vOff + k]
                nv21[w * h + 2 * k + 1] = planar[uOff + k]
            }
            return Nv21Frame(nv21, w, h, rot, timestampNs)
        } finally {
            sensor.release(); i420.release()
        }
    }

    /** Rotates [src] upright, releasing it; returns [src] itself for 0°. */
    private fun rotated(src: Mat, rotation: Int): Mat {
        val code = rotateCode(rotation) ?: return src
        val out = Mat()
        try {
            Core.rotate(src, out, code)
        } finally {
            src.release()
        }
        return out
    }
}
