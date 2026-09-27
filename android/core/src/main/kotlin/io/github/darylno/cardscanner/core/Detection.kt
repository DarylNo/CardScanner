package io.github.darylno.cardscanner.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Port of server/static/phone.html's occupancy detection — TUNED on the rig,
 * do not "improve" (CLAUDE.md: three separate card-shaped geometry gates each
 * rejected real sleeved cards; the trigger is occupancy + stillness only).
 *
 * Every function here mirrors the JS function of the same name line for line,
 * including its integer/float behaviour, and DetectionDifferentialTest runs
 * the page's REAL code under Node against this port on identical frames — so
 * a change to either side that alters a decision fails the build.
 *
 * Frames are small grey samples of the scan area: [MW] wide, [Gray.h] tall
 * (phone.html's MH, which tracks the scan area's aspect), values 0..255.
 */
object DetectConst {
    const val MW = 176
    const val SAMPLE_MS = 200L
    const val PIX_DELTA = 25          // gray levels — per-pixel "really changed" bar
    const val GRAD_THR = 14           // gradient magnitude counted as "textured"
    const val STABLE_FRAC = 0.03
    const val EMPTY_FRAC = 0.015      // mask below this → tray is empty
    const val SWAP_FRAC = 0.06        // change vs last-scanned frame = new card
    const val STABLE_FRAMES = 2       // steady samples (~0.4s) before scanning
    const val OCCUPIED_FRAC = 0.02
    const val MIN_MH = 48

    /** phone.html sampleSmallGray: MH = max(48, Math.round(MW * roiH / roiW)). */
    fun mhFor(roiW: Double, roiH: Double): Int = max(MIN_MH, jsRound(MW * roiH / roiW))

    /** JavaScript Math.round (half rounds toward +∞) — Kotlin's round() is half-even. */
    fun jsRound(x: Double): Int = floor(x + 0.5).toInt()
}

/** A grey sample: [w]×[h] pixels, row-major, each 0..255. */
class Gray(val w: Int, val h: Int, val px: IntArray) {
    init { require(px.size == w * h) { "px size ${px.size} != ${w}x$h" } }
    val size get() = px.size
}

/** phone.html detectBox's result, in sample pixels. */
data class Box(val x: Int, val y: Int, val w: Int, val h: Int, val maskFrac: Double)

internal data class Band(val a: Int, val b: Int)

object Detection {
    /**
     * Mean-delta subtraction cancels camera auto-exposure hunting (shifts every
     * pixel together); real motion changes pixels in different directions.
     */
    fun changedFrac(a: Gray, b: Gray): Double {
        var sum = 0.0
        for (i in 0 until a.size) sum += a.px[i] - b.px[i]
        val mean = sum / a.size
        var n = 0
        for (i in 0 until a.size) {
            if (abs(a.px[i] - b.px[i] - mean) > DetectConst.PIX_DELTA) n++
        }
        return n.toDouble() / a.size
    }

    fun gradMap(gray: Gray): IntArray {
        val w = gray.w
        val g = IntArray(gray.size)
        for (y in 1 until gray.h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                g[i] = min(255, abs(gray.px[i + 1] - gray.px[i - 1]) + abs(gray.px[i + w] - gray.px[i - w]))
            }
        }
        return g
    }

    /**
     * textured now + smooth when empty → printed detail; OR hugely different
     * from empty → solid regions. 3x3 close bridges the card's signal islands.
     */
    fun cardMask(cur: Gray, curGrad: IntArray, emptyRef: Gray, emptyGrad: IntArray): ByteArray {
        val w = cur.w; val h = cur.h
        val mask = ByteArray(cur.size)
        for (i in 0 until cur.size) {
            if ((curGrad[i] > DetectConst.GRAD_THR && emptyGrad[i] <= DetectConst.GRAD_THR) ||
                abs(cur.px[i] - emptyRef.px[i]) > DetectConst.PIX_DELTA * 2) mask[i] = 1
        }
        val dil = ByteArray(cur.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var on = false
                var dy = -1
                while (dy <= 1 && !on) {
                    var dx = -1
                    while (dx <= 1 && !on) {
                        val yy = y + dy; val xx = x + dx
                        if (yy in 0 until h && xx in 0 until w && mask[yy * w + xx].toInt() != 0) on = true
                        dx++
                    }
                    dy++
                }
                dil[y * w + x] = if (on) 1 else 0
            }
        }
        return dil
    }

    internal fun mainBand(counts: IntArray, thr: Double): Band? {
        var best: Band? = null
        var a = -1
        for (i in 0..counts.size) {
            val on = i < counts.size && counts[i] >= thr
            if (on && a < 0) a = i
            if (!on && a >= 0) {
                if (best == null || (i - a) > (best.b - best.a + 1)) best = Band(a, i - 1)
                a = -1
            }
        }
        return best
    }

    internal fun expandBand(counts: IntArray, band: Band, lowThr: Double): Band {
        var a = band.a; var b = band.b
        while (a > 0 && counts[a - 1] >= lowThr) a--
        while (b < counts.size - 1 && counts[b + 1] >= lowThr) b++
        return Band(a, b)
    }

    fun detectBox(cur: Gray, emptyRef: Gray, emptyGrad: IntArray): Box? {
        val w = cur.w; val h = cur.h
        val curGrad = gradMap(cur)
        val mask = cardMask(cur, curGrad, emptyRef, emptyGrad)
        val rows = IntArray(h); val cols = IntArray(w)
        var n = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x].toInt() != 0) { rows[y]++; cols[x]++; n++ }
            }
        }
        if (n == 0) return null
        val maskFrac = n.toDouble() / (w * h)
        val rMax = rows.max(); val cMax = cols.max()
        var rband = mainBand(rows, max(2.0, rMax * 0.30)) ?: return null
        var cband = mainBand(cols, max(2.0, cMax * 0.30)) ?: return null
        rband = expandBand(rows, rband, max(2.0, rMax * 0.04))
        cband = expandBand(cols, cband, max(2.0, cMax * 0.04))
        return Box(cband.a, rband.a, cband.b - cband.a + 1, rband.b - rband.a + 1, maskFrac)
    }
}
