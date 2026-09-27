package io.github.darylno.cardscanner.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The user-drawn scan Area (phone.html's `roi`): fractions 0..1 of the
 * UPRIGHT camera frame, so it survives a resolution change (Standard ↔ High)
 * and means the same thing to the grey sampler, the capture crop and the
 * overlay. It crops BOTH detection sampling and capture (CLAUDE.md).
 *
 * Validation is phone.html's: each side at least 8% of the frame — a smaller
 * area gives the 176-px-wide detection sample nothing to work with.
 */
data class RoiFrac(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    init {
        require(x0 in 0.0..1.0 && y0 in 0.0..1.0 && x1 in 0.0..1.0 && y1 in 0.0..1.0) {
            "roi fractions must be within 0..1: $this"
        }
        require(x1 - x0 >= MIN_SIDE && y1 - y0 >= MIN_SIDE) {
            "roi sides must be at least $MIN_SIDE of the frame: $this"
        }
    }

    /** Width in upright pixels, unrounded — phone.html roiRect().w (drives MH). */
    fun widthPx(frameW: Int): Double = (x1 - x0) * frameW

    /** Height in upright pixels, unrounded — phone.html roiRect().h (drives MH). */
    fun heightPx(frameH: Int): Double = (y1 - y0) * frameH

    /**
     * Integer pixel rectangle of the area in a [frameW]×[frameH] upright frame:
     * edges rounded to the nearest pixel, clamped inside the frame, never
     * empty. This is what the sampler averages over and what capture crops.
     */
    fun toPixels(frameW: Int, frameH: Int): RoiPx {
        require(frameW > 0 && frameH > 0) { "frame must be non-empty: ${frameW}x$frameH" }
        val l = (x0 * frameW).roundToInt().coerceIn(0, frameW - 1)
        val t = (y0 * frameH).roundToInt().coerceIn(0, frameH - 1)
        val r = (x1 * frameW).roundToInt().coerceIn(l + 1, frameW)
        val b = (y1 * frameH).roundToInt().coerceIn(t + 1, frameH)
        return RoiPx(l, t, r - l, b - t)
    }

    /** Compact persisted form "x0,y0,x1,y1" (round-trips exactly via [parse]). */
    fun encode(): String = "$x0,$y0,$x1,$y1"

    companion object {
        /** phone.html: `cand.x1 - cand.x0 < 0.08 || cand.y1 - cand.y0 < 0.08` → "Area too small". */
        const val MIN_SIDE = 0.08

        /** Like the constructor, but null instead of an exception for an invalid area. */
        fun orNull(x0: Double, y0: Double, x1: Double, y1: Double): RoiFrac? =
            if (x0.isNaN() || y0.isNaN() || x1.isNaN() || y1.isNaN()) null
            else runCatching { RoiFrac(x0, y0, x1, y1) }.getOrNull()

        /**
         * phone.html's pointerup: two dragged points (frame fractions, possibly
         * outside the frame) → clamped to 0..1, sorted; null = "Area too small".
         */
        fun fromDrag(ax: Double, ay: Double, bx: Double, by: Double): RoiFrac? {
            fun c(v: Double) = min(1.0, max(0.0, v))
            return orNull(min(c(ax), c(bx)), min(c(ay), c(by)), max(c(ax), c(bx)), max(c(ay), c(by)))
        }

        /** Inverse of [encode]; null for anything malformed or invalid (e.g. an old/corrupt pref). */
        fun parse(s: String?): RoiFrac? {
            val p = s?.split(',')?.map { it.trim().toDoubleOrNull() } ?: return null
            if (p.size != 4 || p.any { it == null }) return null
            return orNull(p[0]!!, p[1]!!, p[2]!!, p[3]!!)
        }
    }
}

/** An integer pixel rectangle in the upright frame: [x, x+w) × [y, y+h). */
data class RoiPx(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h
}
