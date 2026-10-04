package io.github.darylno.cardscanner.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Zoom to fit the Area" (owner on 1.1.10: "No zoom?" → "Go"; 1.1.11). The
 * camera zooms in so the drawn scan Area fills the view, which gives a card that
 * sits small in the frame more sensor pixels (fewer upsampled photo pixels).
 *
 * COORDINATE SPACES. CameraX zooms about the FRAME CENTRE (a centred crop of the
 * sensor, scaled back to the stream size), so a point at fraction `b` of the
 * unzoomed (1×, BASE) upright frame sits at `v = ½ + (b − ½)·z` of the zoomed
 * (VIEW) frame, per axis.
 * - The stored Area (`AppSettings.roi`, `/api/device` `roi`) is ALWAYS base, 1×
 *   fractions. Nothing was ever zoomed before 1.1.11, so every stored Area
 *   already is one: no migration.
 * - Everything that works on camera frames (the detection sample, the capture
 *   crop and its margins, the overlay, the focus metering points, the watch
 *   window) uses the VIEW Area, computed at use with [toView].
 * - The view Area keeps the base Area's aspect, so the detection sample's MH
 *   is the same at every zoom.
 *
 * THE RATIO. z = [STEP]-floored min of: [FIT_FILL] × [fit] (the Area never
 * leaves the view, with a little slack), [lossless] (beyond it the stream only
 * upsamples the sensor), the lens's own max, and [MAX_ZOOM] (provisional until
 * the rig's lossless knee is measured) — and z = 1 below [MIN_USEFUL] (a zoom
 * change re-learns the empty tray; not worth it for a few %). The default Tap
 * Area allows ≈ 1.20×.
 *
 * Pure (JVM-tested by ZoomFitTest). The state machine that applies it to the
 * camera is [ZoomCoordinator].
 */
object ZoomFit {
    /** Provisional ceiling until the rig's lossless knee is measured (a binned readout knees near 1.3). */
    const val MAX_ZOOM = 2.0
    /** The zoomed view keeps 5 % of slack round the Area: the Area is never clipped. */
    const val FIT_FILL = 0.95
    /** Below this a zoom is not applied (z = 1): it would re-learn the tray for a few % of pixels. */
    const val MIN_USEFUL = 1.10
    /** Ratios are floored to this step (a stable number: re-applying the same Area gives the same z). */
    const val STEP = 0.05

    /** What bound the ratio ([Choice.limit]). */
    enum class Limit(val words: String) {
        /** No zoom asked for: the switch is off. */
        OFF("Zoom to fit is off"),
        /** No Area (the full frame is scanned): nothing to fit. */
        NO_AREA("no scan Area — the full frame is scanned"),
        /** Drawing an Area: the whole 1× frame is shown. */
        DRAWING("drawing an Area — the whole frame is shown"),
        FIT("the Area fit"),
        LOSSLESS("lossless (sensor ÷ stream)"),
        LENS_MAX("the lens max"),
        CAP("MAX_ZOOM"),
    }

    /**
     * The ratio for an Area: [z] (what is applied; 1.0 = no zoom), [raw] = the
     * min before the [STEP] floor, [limit] = which cap bound it, [belowMin] = the
     * caps allowed less than [MIN_USEFUL] so z fell back to 1. [fit] / [lossless] /
     * [lensMax] are the inputs (null = unknown).
     */
    class Choice(
        val z: Double, val raw: Double, val limit: Limit, val belowMin: Boolean,
        val fit: Double?, val lossless: Double?, val lensMax: Double?,
    ) {
        /** "1.85× (the Area fit)" / "1.00× (below 1.10×: the Area fit allows 1.04×)" — the log's words. */
        fun describe(): String = when {
            limit == Limit.OFF || limit == Limit.NO_AREA || limit == Limit.DRAWING -> "1.00× (${limit.words})"
            belowMin -> "1.00× (${limit.words} allows only %.2f× — below %.2f×, not worth a re-learn)".format(raw, MIN_USEFUL)
            else -> "%.2f× (limited by ${limit.words})".format(z)
        }

        companion object {
            fun none(limit: Limit, lossless: Double? = null, lensMax: Double? = null) =
                Choice(1.0, 1.0, limit, false, null, lossless, lensMax)
        }
    }

    /**
     * The largest zoom that keeps [area] (base fractions) inside the view:
     * 1 / (2 · max |edge − ½|). 1.0 for the full frame (null) or an Area that
     * touches a frame edge.
     */
    fun fit(area: RoiFrac?): Double {
        val a = area ?: return 1.0
        val m = max(max(abs(a.x0 - 0.5), abs(a.x1 - 0.5)), max(abs(a.y0 - 0.5), abs(a.y1 - 0.5)))
        return if (m <= 0.0) Double.POSITIVE_INFINITY else 1.0 / (2.0 * m)
    }

    /**
     * How far the stream can be zoomed before it only upsamples the sensor:
     * the sensor's active array over the analysis stream, per side, the smaller
     * of the two (a stream of another aspect covers a crop of the array).
     * Orientation-free (long side vs long side). Null when unknown.
     */
    fun lossless(activeW: Int, activeH: Int, streamW: Int, streamH: Int): Double? {
        if (activeW <= 0 || activeH <= 0 || streamW <= 0 || streamH <= 0) return null
        val aL = max(activeW, activeH).toDouble(); val aS = min(activeW, activeH).toDouble()
        val sL = max(streamW, streamH).toDouble(); val sS = min(streamW, streamH).toDouble()
        return min(aL / sL, aS / sS)
    }

    /** [v] floored to [STEP] (robust to 1.2 / 0.05 = 23.999…). */
    fun floorStep(v: Double): Double = floor(v / STEP + 1e-9) * STEP

    /**
     * The zoom for [area] (base fractions; null = the full frame → 1×) with the
     * lens's [lossless] and [lensMax] (null = unknown, not a cap) and the
     * provisional [cap] ([MAX_ZOOM]).
     */
    fun choose(area: RoiFrac?, lossless: Double?, lensMax: Double?, cap: Double = MAX_ZOOM): Choice {
        if (area == null) return Choice.none(Limit.NO_AREA, lossless, lensMax)
        val f = fit(area)
        // Order = the tie-break: the Area fit first (the owner's box), then the sensor, the lens, the cap.
        var raw = FIT_FILL * f
        var limit = Limit.FIT
        if (lossless != null && lossless < raw) { raw = lossless; limit = Limit.LOSSLESS }
        if (lensMax != null && lensMax < raw) { raw = lensMax; limit = Limit.LENS_MAX }
        if (cap < raw) { raw = cap; limit = Limit.CAP }
        val z = floorStep(raw)
        // Exactly representable steps (e.g. 24 * 0.05 = 1.2000000000000002 → 1.2): round to the step's decimals.
        val zr = Math.round(z * 100.0) / 100.0
        return if (zr < MIN_USEFUL) Choice(1.0, raw, limit, true, f, lossless, lensMax)
        else Choice(zr, raw, limit, false, f, lossless, lensMax)
    }

    /** Base fraction [b] on one axis → the view fraction at zoom [z]. */
    fun toViewAxis(b: Double, z: Double): Double = if (z == 1.0) b else 0.5 + (b - 0.5) * z

    /** View fraction [v] on one axis → the base fraction at zoom [z]. */
    fun toBaseAxis(v: Double, z: Double): Double = if (z == 1.0) v else 0.5 + (v - 0.5) / z

    /**
     * The base Area [b] as fractions of the frame zoomed by [z] — EXACTLY [b]
     * (the same object) at z == 1.0, so the unzoomed path never perturbs a bit
     * (MainActivity compares Areas by equality). Null in → null out (the full
     * frame). The edges are clamped into the frame; an Area that does not fit
     * the view (never the case for a z from [choose]) gives null — the caller
     * treats that as "not on screen", never as the full frame for scanning.
     */
    fun toView(b: RoiFrac?, z: Double): RoiFrac? {
        if (b == null || z == 1.0) return b
        return RoiFrac.orNull(
            toViewAxis(b.x0, z).coerceIn(0.0, 1.0), toViewAxis(b.y0, z).coerceIn(0.0, 1.0),
            toViewAxis(b.x1, z).coerceIn(0.0, 1.0), toViewAxis(b.y1, z).coerceIn(0.0, 1.0),
        )
    }

    /** Inverse of [toView] (no clamping needed: a view Area always lies inside the base frame for z ≥ 1). */
    fun toBase(v: RoiFrac?, z: Double): RoiFrac? {
        if (v == null || z == 1.0) return v
        return RoiFrac.orNull(toBaseAxis(v.x0, z), toBaseAxis(v.y0, z), toBaseAxis(v.x1, z), toBaseAxis(v.y1, z))
    }

    /**
     * Where a frame zoomed by [z] sits in a BASE-space picture of the same
     * [w]×[h] size (the browser's snapshot: the desktop draws the base Area on
     * it 1:1): `[x, y, sw, sh]`, centred, sw = round(w / z).
     */
    fun baseRect(w: Int, h: Int, z: Double): IntArray {
        if (z <= 1.0) return intArrayOf(0, 0, w, h)
        val sw = (w / z).roundToInt().coerceIn(1, w)
        val sh = (h / z).roundToInt().coerceIn(1, h)
        return intArrayOf(((w - sw) / 2.0).roundToInt(), ((h - sh) / 2.0).roundToInt(), sw, sh)
    }

    /**
     * The post-fit check (logging only): cards are all 63×88 mm and the tray does
     * not move, so a card's height in frame px should scale with the zoom. Feed
     * every Tray capture's card height with its zoom; the first capture at a
     * zoom different from the previous capture's returns one line comparing it
     * with the median height at the previous zoom. A residual beyond ±3 % says
     * the centred-crop maths does not hold on this phone (a logical multi-camera,
     * a HAL crop that isn't centred) — worth a debug report.
     */
    class PostFit(private val keep: Int = 5) {
        private val heights = LinkedHashMap<Double, ArrayDeque<Double>>()
        private var lastZ: Double? = null

        fun note(z: Double, cardH: Double): String? {
            if (cardH <= 0.0 || z <= 0.0) return null
            val prevZ = lastZ
            lastZ = z
            var line: String? = null
            if (prevZ != null && prevZ != z) {
                val prev = heights[prevZ]
                if (prev != null && prev.isNotEmpty()) {
                    val med = prev.sorted()[prev.size / 2]
                    val predicted = med * z / prevZ
                    val residual = cardH / predicted - 1.0
                    line = "post-fit check: card %.0f px tall at %.2f× vs %.0f px at %.2f× (median of %d) → predicted %.0f px, residual %+.1f%% (%s)"
                        .format(cardH, z, med, prevZ, prev.size, predicted, residual * 100,
                            if (abs(residual) <= 0.03) "within ±3 % — the centred zoom maths holds" else "BEYOND ±3 % — the zoom is not the centred crop this app assumes")
                }
            }
            val q = heights.getOrPut(z) { ArrayDeque() }
            q.addLast(cardH)
            while (q.size > keep) q.removeFirst()
            return line
        }
    }
}
