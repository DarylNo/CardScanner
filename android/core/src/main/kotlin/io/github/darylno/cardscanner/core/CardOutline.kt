package io.github.darylno.cardscanner.core

import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The LIVE card outline for the scan screen: the card's quad found on the
 * detection sample the scanner already takes (~5×/s, 176×MH grey of the scan
 * Area, [GraySampler]) — never on a full frame, which would cost an
 * estimated 65–90 ms on the N200 against ~4–5 ms here (lock-on timing
 * report, 2026-10-02).
 *
 * DISPLAY ONLY. Nothing here feeds the trigger: [AutoScanner] / [Detection]
 * and phone.html's detection are untouched (CLAUDE.md: the Tray trigger is
 * occupancy + stillness only). It wraps the sample in a Mat and runs the
 * reference port [CardQuad.find] as is.
 *
 * The outline found at sample resolution comes out slightly too large: the
 * finder's dilation step swells each side by ~[DILATION_PX] pixels at
 * whatever resolution it runs, and the full-resolution answer (the capture's
 * `CaptureResult.quad`, which this outline snaps to on capture) carries the
 * same swelling in frame pixels. The difference is 5·(k−1) frame px per side,
 * k = frame px per sample px — pulled in here as 5·(1−1/k) sample px (measured
 * ≤ 4.4 frame px from the full-resolution quad at k ≈ 6.8 on the detect
 * fixtures; CardOutlineTest prints the per-scene table).
 *
 * Never throws: any failure (OpenCV not loaded, a degenerate sample, a
 * collapsed quad) is null, so the analysis loop cannot be killed by it.
 */
object CardOutline {
    /** How far CardQuad's `dilate(5×5, 2)` pushes each side out, in the pixels find runs on. */
    const val DILATION_PX = 5.0

    /**
     * [quad]: 8 floats TL, TR, BR, BL as FRACTIONS 0..1 of the upright analysis
     * frame — the space [RoiFrac] lives in, where a fraction f is the pixel EDGE
     * f·frameW (so the overlay scales it straight to the view). [sampleQuad]: the
     * same corners in SAMPLE pixels, OpenCV's convention (pixel (x, y) centred on
     * the point (x, y)), after the pull-in. [ms] / [nanos]: how long the find took.
     */
    class Outline(val quad: FloatArray, val sampleQuad: FloatArray, val ms: Long, val nanos: Long = ms * 1_000_000)

    /**
     * The card outline in [sample] (the scanner's grey sample of [roi] — the
     * whole frame when null — of a [frameW]×[frameH] upright frame), or null
     * when there is none or anything goes wrong.
     */
    fun find(sample: Gray, frameW: Int, frameH: Int, roi: RoiFrac?): Outline? {
        val t0 = System.nanoTime()
        return try {
            if (frameW <= 0 || frameH <= 0 || sample.w < 2 || sample.h < 2) return null
            val raw = findSampleQuad(sample) ?: return null
            val area = roi?.toPixels(frameW, frameH) ?: RoiPx(0, 0, frameW, frameH)
            val kx = area.w.toDouble() / sample.w
            val ky = area.h.toDouble() / sample.h
            val pulled = pullIn(raw, pullInPx((kx + ky) / 2)) ?: return null
            val quad = toFrameFractions(pulled, sample.w, sample.h, area, frameW, frameH)
            val dt = System.nanoTime() - t0
            Outline(quad, pulled, dt / 1_000_000, dt)
        } catch (_: Throwable) {
            null
        }
    }

    /** The pull-in per side in sample px for [k] frame px per sample px: 5·(1 − 1/k), never negative. */
    fun pullInPx(k: Double): Double = if (k.isNaN() || k <= 1.0) 0.0 else DILATION_PX * (1.0 - 1.0 / k)

    /** [CardQuad.find] on [sample] as an 8UC1 Mat (values clamped to 0..255), in sample pixels. Mats released. */
    fun findSampleQuad(sample: Gray): FloatArray? {
        var m: Mat? = null
        try {
            val bytes = ByteArray(sample.size) { sample.px[it].coerceIn(0, 255).toByte() }
            m = Mat(sample.h, sample.w, CvType.CV_8UC1)
            m.put(0, 0, bytes)
            return CardQuad.find(m)
        } finally {
            m?.release()
        }
    }

    /**
     * [quad] with every side moved [d] pixels toward the quad's centre (each edge
     * line offset along its inward normal; the new corners are where adjacent
     * offset lines meet — so a rectangle keeps its centre exactly). Null when
     * the quad would collapse (a side shorter than 2·d) or is degenerate.
     */
    fun pullIn(quad: FloatArray, d: Double): FloatArray? {
        if (quad.size != 8 || quad.any { it.isNaN() }) return null
        if (d <= 0.0) return quad.copyOf()
        val cx = (0 until 4).sumOf { quad[2 * it].toDouble() } / 4
        val cy = (0 until 4).sumOf { quad[2 * it + 1].toDouble() } / 4
        // Offset point + direction of each edge i (P_i → P_{i+1}).
        val qx = DoubleArray(4); val qy = DoubleArray(4)
        val ex = DoubleArray(4); val ey = DoubleArray(4)
        for (i in 0 until 4) {
            val x0 = quad[2 * i].toDouble(); val y0 = quad[2 * i + 1].toDouble()
            val x1 = quad[2 * ((i + 1) % 4)].toDouble(); val y1 = quad[2 * ((i + 1) % 4) + 1].toDouble()
            val dx = x1 - x0; val dy = y1 - y0
            val len = sqrt(dx * dx + dy * dy)
            if (len <= 2 * d) return null
            var nx = -dy / len; var ny = dx / len
            if (nx * (cx - x0) + ny * (cy - y0) < 0) { nx = -nx; ny = -ny }   // point inward
            qx[i] = x0 + d * nx; qy[i] = y0 + d * ny
            ex[i] = dx; ey[i] = dy
        }
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val p = (i + 3) % 4           // the edge arriving at corner i, and edge i leaving it
            val det = ex[p] * ey[i] - ey[p] * ex[i]
            if (abs(det) < 1e-9) return null
            // qp + t·ep = qi + s·ei  →  t = cross(qi − qp, ei) / cross(ep, ei)
            val t = ((qx[i] - qx[p]) * ey[i] - (qy[i] - qy[p]) * ex[i]) / det
            val x = qx[p] + t * ex[p]; val y = qy[p] + t * ey[p]
            // Must have moved toward the centre, not past it.
            val before = (quad[2 * i] - cx) * (quad[2 * i] - cx) + (quad[2 * i + 1] - cy) * (quad[2 * i + 1] - cy)
            val after = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (!(after < before) || (x - cx) * (quad[2 * i] - cx) + (y - cy) * (quad[2 * i + 1] - cy) <= 0) return null
            out[2 * i] = x.toFloat(); out[2 * i + 1] = y.toFloat()
        }
        return out
    }

    /**
     * Sample-pixel corners (centres at integers) → frame fractions: sample pixel
     * i spans frame px [area.x + i·k, area.x + (i+1)·k), so the point x maps to
     * the frame EDGE coordinate area.x + (x + 0.5)·k, over the frame size.
     */
    fun toFrameFractions(sampleQuad: FloatArray, sampleW: Int, sampleH: Int, area: RoiPx, frameW: Int, frameH: Int): FloatArray {
        val kx = area.w.toDouble() / sampleW
        val ky = area.h.toDouble() / sampleH
        return FloatArray(8) {
            val v = sampleQuad[it].toDouble() + 0.5
            if (it % 2 == 0) ((area.x + v * kx) / frameW).toFloat() else ((area.y + v * ky) / frameH).toFloat()
        }
    }
}
