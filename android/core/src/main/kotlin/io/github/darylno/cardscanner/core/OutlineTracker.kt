package io.github.darylno.cardscanner.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Smooths the live card outline across ticks (owner, 2026-10-02: "always
 * highlight the card … average its position and throw away outliers. Right
 * now the card tracing is 98% successful. Sometimes it thinks it's a much
 * bigger card").
 *
 * DISPLAY ONLY — feeds the overlay and the watch window, never the trigger.
 *
 * Each tick's raw quad (sample px, TL TR BR BL) joins a window of the last
 * [window] finds. The reference shape is the per-coordinate MEDIAN of the
 * window; a find whose farthest corner lies more than [tolFrac] × √area of
 * that median away is an outlier (the "much bigger card" lands 10+ px off on
 * every corner where the card is ~70 px wide in the sample) and is left out;
 * the outline shown is the MEAN of the rest. One or two bad finds in five can
 * never move it. A new card at a new place is the minority until it is the
 * majority (3 of 5 ticks, ~600 ms at 5 ticks/s), then the old finds are the
 * outliers and the outline jumps — never a slide across the tray.
 *
 * A tick with no find keeps the last outline up for [holdTicks] ticks (a hand
 * passing, a frame the finder lost), then the window empties: when the card
 * comes back it starts clean. [reset] does the same at once (card removed,
 * tray re-learned, new Area).
 */
class OutlineTracker(val window: Int = 5, val holdTicks: Int = 2, val tolFrac: Double = 0.12) {
    private val history = ArrayDeque<FloatArray>()
    private var misses = 0
    private var last: FloatArray? = null

    /** Raw finds judged outliers (cumulative, for the Diagnostics line). */
    var outliers = 0L
        private set
    /** Raw finds fed in (cumulative). */
    var finds = 0L
        private set
    /** Whether the last [update]'s find was left out as an outlier. */
    var lastWasOutlier = false
        private set
    /** How many finds the last outline was averaged from (0 = none shown). */
    var lastSamples = 0
        private set

    /** Feed this tick's raw find (null = none); returns the outline to show, or null. */
    fun update(quad: FloatArray?): FloatArray? {
        lastWasOutlier = false
        if (quad == null || quad.size != 8 || quad.any { it.isNaN() }) {
            misses++
            if (misses > holdTicks) { reset(); return null }
            return last?.copyOf()
        }
        misses = 0
        finds++
        if (history.size >= window) history.removeFirst()
        history.addLast(quad.copyOf())
        val med = FloatArray(8) { i -> median(history.map { it[i] }) }
        val tol = tolFrac * sqrt(max(area(med), 1.0))
        val inliers = history.filter { maxCornerDist(it, med) <= tol }
        if (history.size >= 3 && maxCornerDist(quad, med) > tol) { outliers++; lastWasOutlier = true }
        val out = if (inliers.isEmpty()) med else FloatArray(8) { i -> inliers.map { it[i] }.average().toFloat() }
        lastSamples = if (inliers.isEmpty()) history.size else inliers.size
        last = out
        return out.copyOf()
    }

    /** Forget everything: the next find starts a fresh window. */
    fun reset() {
        history.clear(); misses = 0; last = null; lastSamples = 0; lastWasOutlier = false
    }

    companion object {
        fun median(v: List<Float>): Float {
            val s = v.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
        }

        /** Shoelace area of an 8-float quad. */
        fun area(q: FloatArray): Double {
            var a = 0.0
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                a += q[2 * i].toDouble() * q[2 * j + 1] - q[2 * j].toDouble() * q[2 * i + 1]
            }
            return abs(a) / 2
        }

        /** The largest corner-to-corner distance between two quads. */
        fun maxCornerDist(a: FloatArray, b: FloatArray): Double {
            var m = 0.0
            for (i in 0 until 4) {
                val dx = (a[2 * i] - b[2 * i]).toDouble(); val dy = (a[2 * i + 1] - b[2 * i + 1]).toDouble()
                m = max(m, sqrt(dx * dx + dy * dy))
            }
            return m
        }
    }
}
