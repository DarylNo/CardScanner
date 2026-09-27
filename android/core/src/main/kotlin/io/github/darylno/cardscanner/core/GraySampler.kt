package io.github.darylno.cardscanner.core

import java.nio.ByteBuffer

/**
 * Builds the detection sample ([Gray], [DetectConst.MW] × MH) of the scan
 * area straight from a camera frame's luma — phone.html's `sampleSmallGray`
 * for the native app.
 *
 * Geometry is phone.html's: the sample covers the UPRIGHT scan area ([RoiFrac],
 * whole frame when null) and MH = `max(48, round(176 · roiH / roiW))` of the
 * area's (unrounded) upright pixel size, so a new area aspect resets
 * AutoScanner's references exactly as the page does.
 *
 * Pixel values differ in HOW they are made, deliberately:
 *  - phone.html drew the video into a 176-px canvas (Chrome's bilinear-ish
 *    downscale) and took `(R + 2G + B) >> 2`;
 *  - here each output pixel is the BOX AVERAGE of the sensor's Y (luma) over
 *    its cell of the area (cell i spans `floor(i·W/176) .. floor((i+1)·W/176)`,
 *    at least 1 px), rounded to nearest.
 * Y and (R+2G+B)/4 agree on neutral greys (a tray, a card border) and a box
 * average carries LESS sensor noise than a bilinear tap, which only helps a
 * detector keyed on "changed by more than 25 levels". It is still a change of
 * input to tuned thresholds, so it is flagged for on-device validation: the
 * debug overlay shows maskFrac / changedFrac against the page's numbers.
 *
 * Speed: the per-geometry plan (cell bounds, sensor↔upright mapping via
 * [Rotation]) is computed once and cached; a sample then reads each ROI
 * sensor pixel exactly once, in memory order, summing runs that share a cell
 * — no rotation copy, no per-pixel division.
 */
object GraySampler {
    /** MH for an upright frame [uprightW]×[uprightH] and scan area [roi] (null = whole frame). */
    fun mhFor(uprightW: Int, uprightH: Int, roi: RoiFrac?): Int =
        if (roi == null) DetectConst.mhFor(uprightW.toDouble(), uprightH.toDouble())
        else DetectConst.mhFor(roi.widthPx(uprightW), roi.heightPx(uprightH))

    /** Detection sample of a ring/captured frame. Identical to [sampleY] on the same pixels. */
    fun sample(frame: Nv21Frame, roi: RoiFrac?): Gray =
        sampleY(ByteBuffer.wrap(frame.data), frame.width, frame.width, frame.height, frame.rotation, roi)

    /**
     * Detection sample read directly from a Y plane (the live ImageProxy's
     * plane 0 — no copy). Pixel (x, y) of the SENSOR image is at ABSOLUTE
     * index `y · rowStride + x` of [y] (its position is ignored and untouched);
     * [sw]×[sh] is the sensor size and [rotation] the CameraX rotationDegrees.
     *
     * [out], when given and exactly MW·MH long, receives the pixels and backs
     * the returned Gray — only pass it if nothing still holds the previous
     * sample: AutoScanner KEEPS the Grays it is given (prevFrame, emptyRef,
     * scannedFrame), so the analysis loop must not recycle them.
     */
    fun sampleY(
        y: ByteBuffer, rowStride: Int, sw: Int, sh: Int, rotation: Int,
        roi: RoiFrac?, out: IntArray? = null,
    ): Gray {
        val plan = planFor(rowStride, sw, sh, rotation, roi)
        require(y.capacity() >= (sh - 1).toLong() * rowStride + sw) {
            "Y plane too small: ${y.capacity()} bytes for ${sw}x$sh stride $rowStride"
        }
        val n = DetectConst.MW * plan.mh
        val px = if (out != null && out.size == n) out else IntArray(n)
        if (plan.fast) plan.sampleFast(y, px) else plan.sampleGeneral(y, px)
        return Gray(DetectConst.MW, plan.mh, px)
    }

    /**
     * Straightforward per-cell reference (reads through [Rotation] pixel by
     * pixel). Tests hold the fast path to it; it is also the real path for
     * areas smaller than the sample grid, where cells overlap.
     */
    internal fun sampleYReference(
        y: ByteBuffer, rowStride: Int, sw: Int, sh: Int, rotation: Int, roi: RoiFrac?,
    ): Gray {
        val plan = Plan(rowStride, sw, sh, rotation, roi)
        val px = IntArray(DetectConst.MW * plan.mh)
        plan.sampleGeneral(y, px)
        return Gray(DetectConst.MW, plan.mh, px)
    }

    // ── cached geometry ─────────────────────────────────────────────────────
    private data class Key(val rowStride: Int, val sw: Int, val sh: Int, val rotation: Int, val roi: RoiFrac?)

    @Volatile private var cached: Pair<Key, Plan>? = null

    private fun planFor(rowStride: Int, sw: Int, sh: Int, rotation: Int, roi: RoiFrac?): Plan {
        val key = Key(rowStride, sw, sh, Rotation.normalize(rotation), roi)
        cached?.let { (k, p) -> if (k == key) return p }
        return Plan(rowStride, sw, sh, key.rotation, roi).also { cached = key to it }
    }

    private val scratch = ThreadLocal<Scratch>()

    private class Scratch(var row: ByteArray, var sums: IntArray)

    private fun scratch(rowLen: Int, cells: Int): Scratch {
        val s = scratch.get() ?: Scratch(ByteArray(rowLen), IntArray(cells)).also { scratch.set(it) }
        if (s.row.size < rowLen) s.row = ByteArray(rowLen)
        if (s.sums.size < cells) s.sums = IntArray(cells)
        return s
    }

    /** Everything about one (frame geometry, rotation, area) that does not depend on pixels. */
    private class Plan(val rowStride: Int, val sw: Int, val sh: Int, rotation: Int, roi: RoiFrac?) {
        val rot = Rotation.normalize(rotation)
        val uw = Rotation.uprightWidth(sw, sh, rot)
        val uh = Rotation.uprightHeight(sw, sh, rot)
        val mh = GraySampler.mhFor(uw, uh, roi)
        val area: RoiPx = roi?.toPixels(uw, uh) ?: RoiPx(0, 0, uw, uh)
        private val mw = DetectConst.MW

        init {
            require(sw > 0 && sh > 0) { "empty frame ${sw}x$sh" }
            require(rowStride >= sw) { "rowStride $rowStride < width $sw" }
        }

        // Cell bounds along the area's upright width / height: [start, end).
        val colStart = IntArray(mw) { (it.toLong() * area.w / mw).toInt() }
        val colEnd = IntArray(mw) { maxOf(colStart[it] + 1, ((it + 1).toLong() * area.w / mw).toInt()) }
        val rowStart = IntArray(mh) { (it.toLong() * area.h / mh).toInt() }
        val rowEnd = IntArray(mh) { maxOf(rowStart[it] + 1, ((it + 1).toLong() * area.h / mh).toInt()) }

        /** Every area pixel lies in exactly one cell (area at least one pixel per sample pixel). */
        val fast = area.w >= mw && area.h >= mh

        // Sensor bounding box of the upright area.
        private val sx0: Int
        private val sx1: Int
        private val sy0: Int
        private val sy1: Int

        init {
            val xa = Rotation.sensorX(area.x, area.y, sw, sh, rot)
            val xb = Rotation.sensorX(area.right - 1, area.bottom - 1, sw, sh, rot)
            val ya = Rotation.sensorY(area.x, area.y, sw, sh, rot)
            val yb = Rotation.sensorY(area.right - 1, area.bottom - 1, sw, sh, rot)
            sx0 = minOf(xa, xb); sx1 = maxOf(xa, xb) + 1
            sy0 = minOf(ya, yb); sy1 = maxOf(ya, yb) + 1
        }

        // Fast path tables. For 0/180 the upright column follows sensor x and the
        // row follows sensor y; for 90/270 it is the other way round. Either way
        // an output index = xPart(x) + yPart(y), and consecutive sensor x values
        // fall into the same cell in runs, so a row is summed run by run.
        private val runStart: IntArray       // offsets from sx0
        private val runEnd: IntArray
        private val runPart: IntArray
        private val yPart: IntArray          // per sensor row sy0 until sy1
        private val counts: IntArray         // pixels per output cell

        init {
            if (fast) {
                val uFromX = rot == 0 || rot == 180
                val colOf = IntArray(area.w); val rowOf = IntArray(area.h)
                for (i in 0 until mw) for (u in colStart[i] until colEnd[i]) colOf[u] = i
                for (j in 0 until mh) for (v in rowStart[j] until rowEnd[j]) rowOf[v] = j
                val xPart = IntArray(sx1 - sx0) { k ->
                    val x = sx0 + k
                    if (uFromX) colOf[Rotation.uprightU(x, 0, sw, sh, rot) - area.x]
                    else rowOf[Rotation.uprightV(x, 0, sw, sh, rot) - area.y] * mw
                }
                yPart = IntArray(sy1 - sy0) { k ->
                    val y = sy0 + k
                    if (uFromX) rowOf[Rotation.uprightV(0, y, sw, sh, rot) - area.y] * mw
                    else colOf[Rotation.uprightU(0, y, sw, sh, rot) - area.x]
                }
                val starts = ArrayList<Int>(); val ends = ArrayList<Int>(); val parts = ArrayList<Int>()
                var k = 0
                while (k < xPart.size) {
                    var e = k + 1
                    while (e < xPart.size && xPart[e] == xPart[k]) e++
                    starts += k; ends += e; parts += xPart[k]
                    k = e
                }
                runStart = starts.toIntArray(); runEnd = ends.toIntArray(); runPart = parts.toIntArray()
                counts = IntArray(mw * mh) { idx ->
                    val i = idx % mw; val j = idx / mw
                    (colEnd[i] - colStart[i]) * (rowEnd[j] - rowStart[j])
                }
            } else {
                runStart = IntArray(0); runEnd = IntArray(0); runPart = IntArray(0)
                yPart = IntArray(0); counts = IntArray(0)
            }
        }

        fun sampleFast(y: ByteBuffer, px: IntArray) {
            val n = mw * mh
            val rowLen = sx1 - sx0
            val s = GraySampler.scratch(rowLen, n)
            val row = s.row; val sums = s.sums
            java.util.Arrays.fill(sums, 0, n, 0)
            val buf = y.duplicate()
            for (k in 0 until sy1 - sy0) {
                buf.position((sy0 + k) * rowStride + sx0)
                buf.get(row, 0, rowLen)
                val yp = yPart[k]
                for (r in runStart.indices) {
                    var acc = 0
                    for (x in runStart[r] until runEnd[r]) acc += row[x].toInt() and 0xFF
                    sums[yp + runPart[r]] += acc
                }
            }
            for (i in 0 until n) px[i] = (sums[i] + counts[i] / 2) / counts[i]
        }

        fun sampleGeneral(y: ByteBuffer, px: IntArray) {
            for (j in 0 until mh) for (i in 0 until mw) {
                var acc = 0L; var cnt = 0
                for (v in rowStart[j] until rowEnd[j]) for (u in colStart[i] until colEnd[i]) {
                    val uu = area.x + u; val vv = area.y + v
                    val x = Rotation.sensorX(uu, vv, sw, sh, rot)
                    val yy = Rotation.sensorY(uu, vv, sw, sh, rot)
                    acc += y.get(yy * rowStride + x).toInt() and 0xFF
                    cnt++
                }
                px[j * mw + i] = ((acc + cnt / 2) / cnt).toInt()
            }
        }
    }
}
