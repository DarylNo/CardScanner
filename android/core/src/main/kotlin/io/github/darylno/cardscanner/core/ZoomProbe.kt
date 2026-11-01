package io.github.darylno.cardscanner.core

import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The zoom probe's pure maths (1.1.17): the ratio ladder for a card at 1×
 * ([plan]), what one frame says about its own zoom ([frameZoom]), where to look
 * for the card at a rung ([searchRect]), each rung's validity ([validity]), the
 * knee of detail against zoom ([knee]), one run's result ([verdict]) and the
 * rule across runs ([combine]). A diagnostic, like [PrintEvidence]: there is no
 * Python reference — it changes no identification and files nothing — and every
 * threshold here is PROVISIONAL (a judgement written down before the rig data,
 * not a measurement) unless it says otherwise. The sequencer is [ZoomProbeRun],
 * the camera side the app's CameraController.
 *
 * DETAIL IS NOT DECIDED HERE ([DETAIL_VALIDATED] is false). The plan measured the
 * knee by the coherence of each rung against the top rung ([ZoomDetail.coherence])
 * and pre-registered a bar for it on a synthetic camera with known knees
 * (ZoomProbeTest: within ±0.10×, |bias| ≤ 0.05×, in ≥ 9 of 10 configurations,
 * sharpening on). It met it in 0 of 10, so the pre-registered fallback holds: the
 * probe reports GEOMETRY (the card's size against zoom: is it the centred crop the
 * app maps with?), CENTRING (the card's base centre against zoom) and SETTLE facts
 * (frames still at the old zoom after the drops, whether CONTROL_ZOOM_RATIO is
 * reported, the AF lens across zooms), and MAX_ZOOM is not decided from detail.
 * [knee] stays — the acceptance runs it, and a future metric would use it.
 */
object ZoomProbe {
    /**
     * Whether a detail metric passed the build-time acceptance, so a knee may
     * recommend MAX_ZOOM. FALSE: the coherence knee failed it (0 of 10, 2026-11-01;
     * ZoomProbeTest.theCoherenceKneeIsRecoveredAcrossTheGrid pins this to the result).
     */
    const val DETAIL_VALIDATED = false

    // ── the ladder ──

    /** The ladder never goes past this, whatever the lens allows. PROVISIONAL. */
    const val CEILING = 3.0
    /** A card that leaves less room than this (the reachable top) is not worth a probe. PROVISIONAL. */
    const val MIN_TOP = 1.5
    /** Slack round the padded card box at the top, as ZoomFit keeps round an Area ([ZoomFit.FIT_FILL]). */
    const val TOP_FILL = ZoomFit.FIT_FILL
    /** The card's box is padded by this share of its own size on every side (the finder needs tray round it). PROVISIONAL. */
    const val CARD_PAD = 0.06
    /** Fine rungs up to [FINE_TO], then [COARSE_STEP]s to the top. */
    const val FINE_STEP = 0.1
    const val FINE_TO = 2.0
    const val COARSE_STEP = 0.2
    /** A card's 88 mm side: px/mm = its long side in px / this. */
    const val CARD_MM = 88.0

    /** Why a probe can't run on this card. */
    enum class Why { NO_CARD, FILLS_VIEW, OFF_CENTRE, LENS }

    sealed class Planned

    /** The probe won't run: [words] say what to do; nothing changed. */
    class Refusal(val why: Why, val words: String) : Planned()

    /**
     * A run's ladder: [order] = the rungs in the order asked (1×, the top = the
     * reference, a seeded shuffle with 1× again in the middle, the top again, 1× last),
     * [ladder] = the distinct rungs ascending, [top] the highest; [pxPerMm] = the detail
     * raster (the card's native scale at the top — the acceptance's; unused at run time
     * while [DETAIL_VALIDATED] is false); [h1] = the card's long side at 1×
     * (px); [box] = its box in base fractions; [cx] / [cy] its centre (base fractions).
     */
    class Plan(
        val order: List<Double>, val ladder: List<Double>, val top: Double, val pxPerMm: Double,
        val h1: Double, val box: RoiFrac, val cx: Double, val cy: Double,
    ) : Planned() {
        /** Index (in [order]) of the reference rung: the first time the top is asked. */
        val referenceIndex: Int get() = 1
    }

    /** The card's long side in px: the larger of the mean vertical and the mean horizontal side of [quad] (TL,TR,BR,BL). */
    fun longSide(quad: FloatArray): Double {
        fun d(a: Int, b: Int) = sqrt(((quad[2 * a] - quad[2 * b]) * (quad[2 * a] - quad[2 * b]) +
            (quad[2 * a + 1] - quad[2 * b + 1]) * (quad[2 * a + 1] - quad[2 * b + 1])).toDouble())
        val vertical = (d(0, 3) + d(1, 2)) / 2
        val horizontal = (d(0, 1) + d(3, 2)) / 2
        return max(vertical, horizontal)
    }

    /** [quad]'s centre (mean of its corners) in px. */
    fun centre(quad: FloatArray): DoubleArray =
        doubleArrayOf((0 until 4).sumOf { quad[2 * it].toDouble() } / 4, (0 until 4).sumOf { quad[2 * it + 1].toDouble() } / 4)

    /** [quad]'s bounding box in fractions of a [w]×[h] frame, clamped (null when it is not a box). */
    fun boxOf(quad: FloatArray, w: Int, h: Int): RoiFrac? {
        val xs = (0 until 4).map { quad[2 * it] / w.toDouble() }; val ys = (0 until 4).map { quad[2 * it + 1] / h.toDouble() }
        return RoiFrac.orNull(xs.min().coerceIn(0.0, 1.0), ys.min().coerceIn(0.0, 1.0), xs.max().coerceIn(0.0, 1.0), ys.max().coerceIn(0.0, 1.0))
    }

    /** [b] padded by [pad] of its own width / height on every side, clamped into the frame. */
    fun padded(b: RoiFrac, pad: Double = CARD_PAD): RoiFrac {
        val px = (b.x1 - b.x0) * pad; val py = (b.y1 - b.y0) * pad
        return RoiFrac((b.x0 - px).coerceIn(0.0, 1.0), (b.y0 - py).coerceIn(0.0, 1.0), (b.x1 + px).coerceIn(0.0, 1.0), (b.y1 + py).coerceIn(0.0, 1.0))
    }

    /** [v] floored to a tenth (robust to 2.3 / 0.1 = 22.999…), rounded to 2 decimals. */
    fun floorTenth(v: Double): Double = (floor(v * 10 + 1e-9) / 10).let { Math.round(it * 100) / 100.0 }

    /** The top a box allows: the [TOP_FILL]·fit of the padded box, under [CEILING] and the lens max. */
    private fun topFor(paddedBox: RoiFrac, lensMax: Double?): Double =
        floorTenth(min(min(CEILING, lensMax ?: CEILING), TOP_FILL * ZoomFit.fit(paddedBox)))

    /**
     * The ladder for the card at [quad] (TL,TR,BR,BL in a [w]×[h] upright frame at 1×)
     * with the lens's [lensMax] (null = unknown, not a cap) — or a [Refusal]. The top
     * deliberately ignores MAX_ZOOM and lossless: the knee must be findable past them.
     * [seed] shuffles the middle rungs (the same seed, the same order).
     */
    fun plan(quad: FloatArray?, w: Int, h: Int, lensMax: Double?, seed: Long): Planned {
        if (quad == null || w <= 0 || h <= 0) return Refusal(Why.NO_CARD, "No card found at 1× — put a card near the middle and Run again")
        val box = boxOf(quad, w, h) ?: return Refusal(Why.NO_CARD, "No card found at 1× — put a card near the middle and Run again")
        val pb = padded(box)
        val top = topFor(pb, lensMax)
        if (top < MIN_TOP) {
            if (lensMax != null && lensMax < MIN_TOP)
                return Refusal(Why.LENS, "This lens zooms only to %.2f× — nothing to probe".format(Locale.ROOT, lensMax))
            val centredTop = topFor(ZoomFit.centred(pb), lensMax)
            if (centredTop >= MIN_TOP) {
                val (cx, cy) = centre(quad).let { it[0] / w - 0.5 to it[1] / h - 0.5 }
                return Refusal(Why.OFF_CENTRE, "The card is ${where(cx, cy)} the view's centre — the probe could reach only " +
                    "%.2f×; centred it could reach %.2f×. Move it toward the middle and Run again".format(Locale.ROOT, top, centredTop))
            }
            return Refusal(Why.FILLS_VIEW, "The card fills the view (only %.1f× of room) — nothing to probe at this mount height"
                .format(Locale.ROOT, TOP_FILL * ZoomFit.fit(pb)))
        }
        val ladder = ArrayList<Double>()
        var i = 0
        while (true) {
            val z = Math.round((1.0 + i * FINE_STEP) * 100) / 100.0
            if (z > min(FINE_TO, top) + 1e-9) break
            ladder += z; i++
        }
        var z = FINE_TO + COARSE_STEP
        while (z <= top + 1e-9) { ladder += Math.round(z * 100) / 100.0; z += COARSE_STEP }
        if (ladder.last() != top) ladder += top
        val rest = ladder.filter { it != 1.0 && it != top }.toMutableList()
        java.util.Collections.shuffle(rest, Random(seed))
        val half = rest.size / 2
        val order = listOf(1.0, top) + rest.subList(0, half) + listOf(1.0) + rest.subList(half, rest.size) + listOf(top, 1.0)
        val c = centre(quad)
        val h1 = longSide(quad)
        return Plan(order, ladder, top, h1 * top / CARD_MM, h1, box, c[0] / w, c[1] / h)
    }

    /** "14 % right and 6 % below" / "20 % left of" — whole % of the frame, as the off-centre hint words it. */
    internal fun where(dx: Double, dy: Double): String {
        val x = Math.round(abs(dx) * 100); val y = Math.round(abs(dy) * 100)
        val parts = listOfNotNull(
            if (x > 0) "$x % ${if (dx > 0) "right" else "left"}" else null,
            if (y > 0) "$y % ${if (dy > 0) "below" else "above"}" else null,
        )
        return when {
            parts.isEmpty() -> "under 1 % off"
            y > 0 -> parts.joinToString(" and ")
            else -> parts.single() + " of"
        }
    }

    // ── the knee ──

    /** "No knee inside the ladder" when the fitted knee is within this of the top. */
    const val NO_KNEE_MARGIN = 0.05
    /** "Zoom adds no real detail" when the gain up to the knee is under max(this c/mm, [NO_DETAIL_REL] of the 1× level). PROVISIONAL. */
    const val NO_DETAIL_ABS = 0.25
    const val NO_DETAIL_REL = 0.10

    /**
     * The hinge f(z) ≈ [b] + [a]·(min(z, [k]) − 1) fitted to the rungs: [k] = the knee
     * (searched over the rung values and their midpoints, [a] and [b] by least squares),
     * [rms] = the fit's residual. [noKnee] = the knee is the top (detail still grows
     * there), [noDetail] = zoom adds less than [NO_DETAIL_ABS] / [NO_DETAIL_REL] up to it.
     */
    class Knee(val k: Double, val a: Double, val b: Double, val rms: Double, val noKnee: Boolean, val noDetail: Boolean) {
        /** The fitted gain up to the knee, as a share of the 1× level. */
        val gain: Double get() = if (b > 0) a * (k - 1) / b else 0.0
        fun at(z: Double): Double = b + a * (min(z, k) - 1)
    }

    /** [knee] of (zoom, value) [points] whose ladder top is [top]; null with fewer than 3 points or 2 distinct zooms. */
    fun knee(points: List<Pair<Double, Double>>, top: Double): Knee? {
        if (points.size < 3) return null
        val zs = points.map { it.first }.distinct().sorted()
        if (zs.size < 2) return null
        val ks = (zs + zs.zipWithNext { p, q -> (p + q) / 2 }).sorted()
        var best: Knee? = null; var bestSse = Double.MAX_VALUE
        for (k in ks) {
            val xs = points.map { min(it.first, k) - 1 }; val ys = points.map { it.second }
            val n = xs.size
            val mx = xs.average(); val my = ys.average()
            val sxx = xs.sumOf { (it - mx) * (it - mx) }
            val a = if (sxx <= 1e-12) 0.0 else xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
            val b = my - a * mx
            val sse = xs.indices.sumOf { val r = ys[it] - (b + a * xs[it]); r * r }
            // Exact ties (every k at or past the last rung fits a straight line alike): the smallest k.
            if (sse < bestSse - 1e-9 * max(1.0, bestSse)) {
                bestSse = sse
                best = Knee(k, a, b, sqrt(sse / n), false, false)
            }
        }
        val kn = best ?: return null
        val noKnee = kn.k >= top - NO_KNEE_MARGIN
        val noDetail = kn.a * (kn.k - 1) < max(NO_DETAIL_ABS, NO_DETAIL_REL * kn.b)
        return Knee(kn.k, kn.a, kn.b, kn.rms, noKnee, noDetail)
    }

    // ── per frame ──

    /** A frame's meta ratio more than this off the camera's answer: the frame is at another zoom. */
    const val OTHER_ZOOM_META = 0.01
    /** A frame's card more than this off the rung's median long side: at another zoom (no meta needed). */
    const val OTHER_ZOOM_H = 0.02
    /** The card is searched in its 1× box padded by this share of its size on every side, mapped to the rung. PROVISIONAL. */
    const val SEARCH_PAD = 0.25

    /**
     * The zoom one frame reports — the live path's rule (CameraController.readBackZoom,
     * left untouched): CONTROL_ZOOM_RATIO when it is not 1, else the active array's
     * width ÷ the crop region's, else CONTROL_ZOOM_RATIO (1 or null).
     */
    fun frameZoom(controlZoom: Double?, cropW: Int?, activeW: Int?): Double? {
        val fromCrop = if (cropW != null && activeW != null && cropW > 0) activeW.toDouble() / cropW else null
        return when {
            controlZoom != null && abs(controlZoom - 1.0) > 0.005 -> controlZoom
            fromCrop != null -> fromCrop
            else -> controlZoom
        }
    }

    /**
     * Where to look for the card at zoom [z]: its 1× box ([cardBase], base fractions)
     * padded [SEARCH_PAD] per side, mapped to the view ([ZoomFit.toViewAxis]) and
     * clamped — `[x, y, w, h]` px of a [w]×[h] frame.
     */
    fun searchRect(cardBase: RoiFrac, z: Double, w: Int, h: Int): IntArray {
        val px = (cardBase.x1 - cardBase.x0) * SEARCH_PAD; val py = (cardBase.y1 - cardBase.y0) * SEARCH_PAD
        val x0 = (ZoomFit.toViewAxis(cardBase.x0 - px, z) * w).coerceIn(0.0, w.toDouble())
        val y0 = (ZoomFit.toViewAxis(cardBase.y0 - py, z) * h).coerceIn(0.0, h.toDouble())
        val x1 = (ZoomFit.toViewAxis(cardBase.x1 + px, z) * w).coerceIn(0.0, w.toDouble())
        val y1 = (ZoomFit.toViewAxis(cardBase.y1 + py, z) * h).coerceIn(0.0, h.toDouble())
        val l = x0.toInt(); val t = y0.toInt()
        return intArrayOf(l, t, max(1, kotlin.math.ceil(x1).toInt() - l), max(1, kotlin.math.ceil(y1).toInt() - t))
    }

    /**
     * One probe frame as the worker measured it: the card's [quad] in its view px (null =
     * not found), the zoom its meta reports ([frameZoom]; null = no meta), whether it
     * carried CONTROL_ZOOM_RATIO at all ([reported]), the lens, exposure, ISO and AF
     * state, the card's mean [luma] (null without a quad) and whether the HAL's crop
     * region is centred ([cropCentred] — logged only: on API 30+ it is the full array).
     */
    class FrameFacts(
        val quad: FloatArray?, val zoom: Double?, val reported: Boolean = false,
        val lensDpt: Double? = null, val exposureNs: Long? = null, val iso: Int? = null, val afState: String? = null,
        val luma: Double? = null, val cropCentred: Boolean? = null,
    )

    private fun median(v: List<Double>): Double? = if (v.isEmpty()) null else v.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    /**
     * One rung of a run: asked [asked] (its place [index] in the order), the camera
     * answered [camera] after [answerMs] (or [error]); [frames] were taken after
     * [skipped] dropped frames, [skippedAtNew] of which were already at the new zoom.
     * [afterRefocus] = measured after the refocus check's AF pass (its lens is not the lock).
     */
    class Rung(
        val index: Int, val asked: Double, val camera: Double, val answerMs: Long?, val error: String?,
        val frames: List<FrameFacts>, val skipped: Int, val skippedAtNew: Int, val w: Int, val h: Int, val ms: Long,
        val afterRefocus: Boolean = false,
    ) {
        private val heights = frames.map { it.quad?.let(::longSide) }
        private val hMed = median(heights.filterNotNull())
        /** Per frame: at another zoom than the camera's answer (its meta, or its card's size). */
        val otherZoom: BooleanArray = BooleanArray(frames.size) { i ->
            val z = frames[i].zoom; val hh = heights[i]
            (z != null && abs(z - camera) > OTHER_ZOOM_META * camera) || (hh != null && hMed != null && abs(hh - hMed) > OTHER_ZOOM_H * hMed)
        }
        /** The frames measured: a card found, at the camera's zoom. */
        val used: List<FrameFacts> = frames.filterIndexed { i, f -> f.quad != null && !otherZoom[i] }
        /** The card's long side (px, median of [used]). */
        val longPx: Double? = median(used.map { longSide(it.quad!!) })
        /** The card's centre in BASE fractions (median of [used], mapped with the camera's ratio). */
        val cx: Double? = median(used.map { ZoomFit.toBaseAxis(centre(it.quad!!)[0] / w, camera) })
        val cy: Double? = median(used.map { ZoomFit.toBaseAxis(centre(it.quad!!)[1] / h, camera) })
        val luma: Double? = median(used.mapNotNull { it.luma })
        val atOtherZoom: Int get() = otherZoom.count { it }
        /** Frames that carried CONTROL_ZOOM_RATIO / had a meta at all. */
        val reported: Int get() = frames.count { it.reported }
        val withMeta: Int get() = frames.count { it.zoom != null }
    }

    // ── validity ──

    const val MIN_FRAMES = 4
    /** The AF lock holds when the lens stays within this of it (dioptres). */
    const val LENS_TOL_DPT = 0.02
    /** A rung's card size off the H = a·z + c fit by more than this: not the centred crop at that rung. */
    const val H_FIT_TOL = 0.01
    /** A rung's base centre this far from the 1× start (fraction of the frame): moved, or not centred. */
    const val CENTRE_TOL = 0.01
    /**
     * The card's mean luma this far (share) from the 1× start: the light changed (AE/AWB are locked,
     * so nothing else moves it). Not in the plan's list — it is how "the light changed" shows with
     * the exposure locked. PROVISIONAL.
     */
    const val LUMA_TOL = 0.08
    /** The 1× repeats (and the two top rungs) must agree on the card's size within this. */
    const val REPEAT_H_TOL = 0.015
    /** At least this share of rungs must measure cleanly for a run to count. */
    const val MIN_VALID_SHARE = 0.8
    /** More than this share of rungs with frames still at the old zoom after the drops: SETTLE_DROP_FRAMES must rise first. */
    const val SETTLE_SHARE = 0.10
    /** The identifier's warp height (CardExtract → CardQuad.warpCard): past z_pipe = this ÷ H(1×) its 630×880 raster drops native pixels. */
    val Z_PIPE_PX: Int get() = CardQuad.CARD_H

    /** What a run locked at 1× (after its AF pass, under AE/AWB lock) and where the card started. */
    class Locked(val lensDpt: Double?, val exposureNs: Long?, val iso: Int?, val luma: Double?, val cx: Double, val cy: Double)

    /**
     * H = [a]·z + [c] (c = the finder's constant px bias), its largest relative deviation and rms
     * over the rungs fitted; [worstZ] = the camera ratio of that largest deviation (null = not known).
     */
    class HFit(val a: Double, val c: Double, val maxDev: Double, val rms: Double, val worstZ: Double? = null) {
        fun at(z: Double) = a * z + c
    }

    /** Least squares H = a·z + c over the rungs with a card (null with fewer than 2 distinct zooms). */
    fun fitH(rungs: List<Rung>): HFit? {
        val pts = rungs.filter { it.longPx != null && it.error == null }.map { it.camera to it.longPx!! }
        if (pts.map { it.first }.distinct().size < 2) return null
        val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        val a = pts.sumOf { (it.first - mx) * (it.second - my) } / sxx
        val c = my - a * mx
        val devs = pts.map { (it.second - (a * it.first + c)) / it.second }
        val worst = devs.indices.maxByOrNull { abs(devs[it]) }!!
        return HFit(a, c, abs(devs[worst]), sqrt(devs.sumOf { it * it } / devs.size), pts[worst].first)
    }

    /**
     * The MEASUREMENT rules a rung must pass (empty = it measured cleanly): an answer,
     * ≥ [MIN_FRAMES] frames used, a card found, the lens held within [LENS_TOL_DPT] of
     * the lock (not after the refocus check), the locked exposure and ISO, the light
     * ([LUMA_TOL]). The GEOMETRY rules — the size on the H fit ([H_FIT_TOL]) and the
     * base centre ([CENTRE_TOL]) — are [geometry]'s: failing them is what an
     * off-centre crop does, a finding about the camera, not a bad measurement.
     */
    fun validity(r: Rung, locked: Locked): List<String> {
        val out = ArrayList<String>()
        if (r.error != null) { out += if (r.error == ZoomCoordinator.PROBE_NO_ANSWER) "no answer" else "refused (${r.error})"; return out }
        if (r.longPx == null) { out += "no card found"; return out }
        if (r.used.size < MIN_FRAMES) out += "only ${r.used.size} frame(s) usable"
        if (!r.afterRefocus && locked.lensDpt != null) {
            val worst = r.used.mapNotNull { it.lensDpt }.maxByOrNull { abs(it - locked.lensDpt) }
            if (worst != null && abs(worst - locked.lensDpt) > LENS_TOL_DPT + 1e-9)
                out += "the lens moved (%.2f → %.2f dpt)".format(Locale.ROOT, locked.lensDpt, worst)
        }
        if (locked.exposureNs != null && r.used.any { it.exposureNs != null && it.exposureNs != locked.exposureNs }) out += "the exposure changed"
        if (locked.iso != null && r.used.any { it.iso != null && it.iso != locked.iso }) out += "the ISO changed"
        if (locked.luma != null && r.luma != null && abs(r.luma / locked.luma - 1) > LUMA_TOL)
            out += "the light changed (card luma %+.0f %%)".format(Locale.ROOT, (r.luma / locked.luma - 1) * 100)
        return out
    }

    /** [r]'s geometry against the [fit] and the 1× start ([locked]): empty = the centred crop at this rung. */
    fun geometry(r: Rung, locked: Locked, fit: HFit?): List<String> {
        val out = ArrayList<String>()
        val h = r.longPx ?: return out
        if (fit != null) {
            val dev = (h - fit.at(r.camera)) / h
            if (abs(dev) > H_FIT_TOL) out += "card size ${pct(dev)} % off H = a·z + c"
        }
        val dx = (r.cx ?: return out) - locked.cx; val dy = r.cy!! - locked.cy
        if (abs(dx) > CENTRE_TOL || abs(dy) > CENTRE_TOL) out += "base centre moved ${pct(dx)} %, ${pct(dy)} %"
        return out
    }

    // ── one run ──

    /** What the camera side knows (logged with the run; [restoreZ] = the ratio the probe returns to). */
    class Facts(
        val cameraId: String, val w: Int, val h: Int, val highRes: Boolean, val activeW: Int?, val activeH: Int?,
        val lossless: Double?, val lensMax: Double?, val restoreZ: Double,
    ) {
        /** "1600×1200" — the stream, long side first (MAX_ZOOM holds only for the stream it was measured on). */
        val stream: String get() = "${max(w, h)}×${min(w, h)}"
    }

    /** The 1× AF pass (or the refocus check's): [ok] = FOCUSED, the lens before / after, how long. */
    class Focus(val ok: Boolean, val dptBefore: Double?, val dptAfter: Double?, val ms: Long, val state: String)

    /**
     * One run's result. [valid] = it measured cleanly (else [reason], owner words);
     * [centred] = the zoom is the centred crop the app maps with (null = not judged);
     * [fit] / [drift] its evidence; [settleRungs] of [rungs] rungs had frames still at
     * the old zoom after the drops (at most [settleMax] on one); [reportsZoomRatio]
     * whether CONTROL_ZOOM_RATIO came on the frames (null = no metas); [zPipe] = 880 ÷
     * H(1×); [lensDrift] the largest lens move off the lock; [refocus] the check at the
     * top; [sameCardAs] = the earlier run this card repeats (the runner's thumbnail NCC);
     * [driftZ] = the camera ratio where the centre drifted most; [camera] = the Camera2 id it
     * ran on (MAX_ZOOM, like the stream, holds only for the camera it was measured on). The
     * knee and MAX_ZOOM are not measured ([DETAIL_VALIDATED] is false).
     *
     * Kept as JSON ([toJson]; the scan screen keeps a session's runs, 1.1.17) and read back
     * with [fromMap]: what [words], [combine] and [toMap] read comes back as it was — the
     * refocus check's details ([refocus]) and the H fit's rms do not (the RESULT line had them).
     */
    class Result(
        val run: Int, val valid: Boolean, val reason: String?, val centred: Boolean?, val fit: HFit?, val drift: Double?,
        val rungs: Int, val validRungs: Int, val settleRungs: Int, val settleMax: Int, val reportsZoomRatio: Boolean?,
        val zPipe: Double?, val lensDrift: Double?, val refocus: Focus?, val stream: String, val top: Double,
        val sameCardAs: Int?, val h1: Double?, val stoppedAt: String? = null, val driftZ: Double? = null,
        val camera: String? = null,
    ) {
        /** The settle rule: frames at the old zoom after the drops on more than [SETTLE_SHARE] of rungs. */
        val settleOk: Boolean get() = rungs == 0 || settleRungs.toDouble() / rungs <= SETTLE_SHARE + 1e-9

        /**
         * Why the run is NOT the centred crop, from the rule(s) that failed and where each was worst
         * (review of 08a77d1: the words always cited the drift at the top, even when the SIZE failed).
         */
        fun notCentredWhy(): String = listOfNotNull(
            fit?.takeIf { it.maxDev > H_FIT_TOL + 1e-9 }?.let {
                "the card's size is %.1f %% off a straight line at %.2f×".format(Locale.ROOT, it.maxDev * 100, it.worstZ ?: top)
            },
            drift?.takeIf { it > CENTRE_TOL + 1e-9 }?.let { "the card moved %.1f %% at %.2f×".format(Locale.ROOT, it * 100, driftZ ?: top) },
        ).ifEmpty { listOf("too few rungs measured to fit the card's size against zoom") }.joinToString(" and ")

        /** The settle fact in words. */
        private fun settleWords() = if (settleOk) "frames settle within the dropped ones" else "frames are still at the old zoom on $settleRungs of $rungs rungs"

        /** The owner's line on the scan screen: what the run found and what to do next (the H-fit numbers are the log line's). */
        fun words(): String = when {
            stoppedAt != null -> "Run $run stopped ($stoppedAt) — nothing filed; Run again when ready"
            !valid -> "Not conclusive — ${reason ?: "?"}"
            centred == false -> "The zoom is NOT the centred crop this app assumes (${notCentredWhy()}) — keep Zoom to fit off and send the report"
            else -> "Run $run: valid — the zoom is the centred crop, ${settleWords()} (the probe does not measure MAX_ZOOM). " +
                "Lift this card, put a DIFFERENT card down, let it scan, then Run again"
        }

        /** The `probe` RESULT line. */
        fun logLine(): String = buildString {
            append("probe run $run RESULT: ")
            if (stoppedAt != null) { append("STOPPED ($stoppedAt)"); return@buildString }
            append(if (valid) "valid" else "inconclusive — $reason")
            append(" · ").append(when (centred) {
                true -> "centred"
                false -> "NOT centred"
                null -> "centring not judged"
            })
            fit?.let { append(" (H = %.1f·z %+.1f, max dev %.1f %%; centre drift ≤ %.1f %%)".format(Locale.ROOT, it.a, it.c, it.maxDev * 100, (drift ?: 0.0) * 100)) }
            append(" · rungs valid $validRungs of $rungs")
            append(" · old-zoom frames after the drops: $settleRungs of $rungs rungs (max $settleMax on one)")
            append(" · CONTROL_ZOOM_RATIO reported: ").append(when (reportsZoomRatio) { true -> "yes"; false -> "no"; null -> "no metas" })
            lensDrift?.let { append(" · lens drift off the lock %.2f dpt".format(Locale.ROOT, it)) }
            zPipe?.let { append(" · z_pipe %.2f×".format(Locale.ROOT, it)) }
            append(" · stream $stream · knee: not measured (no validated detail metric)")
            sameCardAs?.let { append(" · the same card as run $it") }
        }

        /**
         * The run's facts as JSON values. What the words and the rules read is kept as it was
         * measured ([fromMap] must say the same: a rounded max_dev or drift could cross its 1 %
         * rule, a rounded z_pipe print another %.2f); the rest is rounded for reading.
         */
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "run" to run, "valid" to valid, "reason" to reason, "centred" to centred, "camera" to camera,
            "h_fit" to fit?.let { linkedMapOf("a" to it.a.r2(), "c" to it.c.r2(), "max_dev" to it.maxDev, "worst_z" to it.worstZ) },
            "drift" to drift, "drift_z" to driftZ,
            "rungs" to rungs, "valid_rungs" to validRungs, "settle_rungs" to settleRungs, "settle_max" to settleMax,
            "settle_ok" to settleOk, "reports_zoom_ratio" to reportsZoomRatio, "z_pipe" to zPipe,
            "lens_drift" to lensDrift?.r2(), "stream" to stream, "top" to top, "h1" to h1?.r2(), "same_card_as" to sameCardAs,
            "stopped" to stoppedAt, "knee" to null, "recommended" to null, "words" to words(),
        )

        fun toJson(): String = MiniJson.stringify(toMap())

        companion object {
            /** A [toMap] read back (MiniJson numbers: Long or Double), or null when [m] is not one. */
            fun fromMap(m: Map<*, *>): Result? = runCatching {
                fun d(k: String, from: Map<*, *> = m) = (from[k] as? Number)?.toDouble()
                fun i(k: String) = (m[k] as? Number)?.toInt()
                val f = m["h_fit"] as? Map<*, *>
                Result(
                    run = i("run")!!, valid = m["valid"] as Boolean, reason = m["reason"] as? String, centred = m["centred"] as? Boolean,
                    fit = f?.let { HFit(d("a", it)!!, d("c", it)!!, d("max_dev", it)!!, 0.0, d("worst_z", it)) },
                    drift = d("drift"), rungs = i("rungs")!!, validRungs = i("valid_rungs")!!, settleRungs = i("settle_rungs")!!,
                    settleMax = i("settle_max")!!, reportsZoomRatio = m["reports_zoom_ratio"] as? Boolean, zPipe = d("z_pipe"),
                    lensDrift = d("lens_drift"), refocus = null, stream = m["stream"] as String, top = d("top")!!,
                    sameCardAs = i("same_card_as"), h1 = d("h1"), stoppedAt = m["stopped"] as? String, driftZ = d("drift_z"),
                    camera = m["camera"] as? String,
                )
            }.getOrNull()
        }
    }

    /**
     * One run's [Result] from its [rungs] (every rung asked, in order, the refocus
     * check's last with [Rung.afterRefocus]), the [plan], the 1× [focus] pass and the
     * [refocus] check. The order of judgement: the 1× focus; the 1× repeats and the
     * two top rungs agree (the card did not move, the light did not change); enough
     * rungs measured cleanly ([validity]); then the GEOMETRY — the centred crop — and
     * the settle facts.
     */
    fun verdict(run: Int, plan: Plan, rungs: List<Rung>, focus: Focus?, refocus: Focus?, facts: Facts, sameCardAs: Int? = null): Result {
        val main = rungs.filter { !it.afterRefocus }
        val ones = main.filter { it.asked == 1.0 }
        val tops = main.filter { it.asked == plan.top && plan.top != 1.0 }
        val start = ones.firstOrNull { it.longPx != null }
        val zPipe = median(ones.mapNotNull { it.longPx })?.let { Z_PIPE_PX / it }
        val settleRungs = main.count { it.atOtherZoom > 0 }
        val settleMax = main.maxOfOrNull { it.atOtherZoom } ?: 0
        val withMeta = main.sumOf { it.withMeta }
        val reports = if (withMeta == 0) null else main.sumOf { it.reported } > 0
        fun result(valid: Boolean, reason: String?, centred: Boolean?, fit: HFit?, drift: Double?, validRungs: Int, lensDrift: Double?,
                   driftZ: Double? = null) =
            Result(run, valid, reason, centred, fit, drift, main.size, validRungs, settleRungs, settleMax, reports, zPipe,
                lensDrift, refocus, facts.stream, plan.top, sameCardAs, start?.longPx, driftZ = driftZ, camera = facts.cameraId)
        if (focus == null || !focus.ok)
            return result(false, "the 1× focus pass did not report FOCUSED (${focus?.state ?: "none"}) — more light, then Run again", null, null, null, 0, null)
        if (start == null) return result(false, "no card found at 1×", null, null, null, 0, null)
        val locked = Locked(start.used.mapNotNull { it.lensDpt }.let(::median), start.used.firstOrNull()?.exposureNs,
            start.used.firstOrNull()?.iso, start.luma, start.cx!!, start.cy!!)
        val reasons = main.map { validity(it, locked) }
        val validRungs = reasons.count { it.isEmpty() }
        val lensDrift = locked.lensDpt?.let { l -> main.flatMap { it.used }.mapNotNull { it.lensDpt }.maxOfOrNull { abs(it - l) } }
        // The card must not move: the 1× repeats, then the two top rungs, agree on size and place.
        fun agree(g: List<Rung>): Double? {
            val m = g.filter { it.longPx != null }
            if (m.size < 2) return null
            val hs = m.map { it.longPx!! }
            val dh = hs.max() / hs.min() - 1
            val dc = m.maxOf { max(abs(it.cx!! - m[0].cx!!), abs(it.cy!! - m[0].cy!!)) }
            return if (dh > REPEAT_H_TOL || dc > CENTRE_TOL) max(dh, dc) else null
        }
        agree(ones)?.let { return result(false, "the card moved (1× again is %.0f %% off the start): leave the tray untouched and Run again"
            .format(Locale.ROOT, max(1.0, it * 100)), null, null, null, validRungs, lensDrift) }
        // Only the WORDS of the 80 % rule below: more than 20 % of rungs lost to the light or the lens
        // leaves under 80 % valid anyway. A slip on a 1× repeat costs that rung, like any other
        // (review of 08a77d1: it used to sink the run — a rule not in the pre-registered list).
        val lightOrFocus = reasons.count { r -> r.any { it.startsWith("the light") || it.startsWith("the lens") || it.startsWith("the exposure") || it.startsWith("the ISO") } }
        if (lightOrFocus > main.size * (1 - MIN_VALID_SHARE))
            return result(false, "the light or focus changed during the run — keep shadows and hands off, then Run again", null, null, null, validRungs, lensDrift)
        agree(tops)?.let { return result(false, "the card moved between the two %.2f× rungs — leave the tray untouched and Run again"
            .format(Locale.ROOT, plan.top), null, null, null, validRungs, lensDrift) }
        if (validRungs < MIN_VALID_SHARE * main.size)
            return result(false, "only $validRungs of ${main.size} rungs measured cleanly (${reasons.flatten().groupingBy { it.substringBefore(" (") }.eachCount().entries
                .sortedByDescending { it.value }.take(2).joinToString { "${it.key} ×${it.value}" }})", null, null, null, validRungs, lensDrift)
        // The geometry: over the rungs that measured cleanly.
        val clean = main.filterIndexed { i, _ -> reasons[i].isEmpty() }
        val fit = fitH(clean)
        val drifts = clean.mapNotNull { r -> r.cx?.let { r.camera to max(abs(it - locked.cx), abs(r.cy!! - locked.cy)) } }
        val worst = drifts.maxByOrNull { it.second }
        val drift = worst?.second ?: 0.0
        val centred = fit != null && fit.maxDev <= H_FIT_TOL + 1e-9 && drift <= CENTRE_TOL + 1e-9
        return result(true, null, centred, fit, drift, validRungs, lensDrift, worst?.first)
    }

    /** A run the scan screen or a lifecycle input stopped (nothing measured counts). */
    fun stopped(run: Int, plan: Plan?, why: String, facts: Facts): Result =
        Result(run, false, why, null, null, null, 0, 0, 0, 0, null, null, null, null, facts.stream, plan?.top ?: 1.0, null, null, why,
            camera = facts.cameraId)

    // ── across runs ──

    /** The probe's half of "Zoom to fit ON by default", and what the runs say together. */
    class Combined(val validCards: Int, val centred: Boolean?, val settleOk: Boolean?, val reportsZoomRatio: Boolean?, val zPipe: Double?,
                   val words: String) {
        /** Two valid runs on different cards. */
        val enough: Boolean get() = validCards >= 2
        /**
         * The probe half's rules, each (passed, words): two valid runs on different cards, centred,
         * the settle rule, the knee — never passed in this build ([DETAIL_VALIDATED] is false), so
         * Zoom to fit is never ON by default on the probe's say. The session's verdict reads them.
         */
        fun checkList(): List<Pair<Boolean, String>> = listOf(
            enough to "$validCards valid run(s) on different cards (needs 2)",
            (centred == true) to (if (centred == false) "NOT centred" else "centred"),
            (settleOk == true) to "settle",
            false to "knee: not measured (no validated detail metric — MAX_ZOOM is not decided by the probe)",
        )

        /** "✓ 2 valid runs on different cards ✓ centred ✓ settle ✗ knee: not measured …" */
        fun checks(): String = checkList().joinToString(" ") { (ok, words) -> (if (ok) "✓ " else "✗ ") + words }

        /**
         * [checkList]'s rules by short names, in its order — "✓ 2 cards ✓ centred ✓ settle ✗ knee not
         * measured" — for the session's done card, which has no room for their words (review of
         * a60e51d: they pushed it past 45 % of the N200's screen); [checks] is the log's.
         */
        fun shortChecks(): String = checkList().zip(listOf(
            if (enough) "$validCards cards" else "$validCards of 2 cards",
            if (centred == false) "NOT centred" else "centred",
            "settle",
            "knee not measured",
        )).joinToString(" ") { (c, name) -> (if (c.first) "✓ " else "✗ ") + name }

        fun logLine(): String = "probe VERDICT: $words · probe half of default-on: ${checks()}"
    }

    /**
     * The rule across [runs] (oldest first): the valid runs on DIFFERENT cards (a run
     * the runner found to repeat an earlier valid run's card does not count again);
     * centred only when every valid run is (any run not centred → no decision: the zoom
     * is not the crop the app maps with); the settle rule on every valid run. MAX_ZOOM
     * is never recommended ([DETAIL_VALIDATED] is false).
     *
     * Only the runs on the NEWEST run's camera and stream count together (review of
     * a60e51d): a result holds only for the camera and the stream it was measured on, and
     * the owner can change the lens or High res between runs. The others are named at the
     * end of the words ("run 1 (camera 0 · 1600×1200) counts apart: …").
     */
    fun combine(all: List<Result>): Combined {
        val newest = all.lastOrNull()
        val runs = all.filter { it.camera == newest?.camera && it.stream == newest?.stream }
        val apart = all.filter { r -> runs.none { it === r } }
        val valid = runs.filter { it.valid && it.stoppedAt == null }
        val validRuns = valid.map { it.run }.toSet()
        val cards = valid.filter { r -> r.sameCardAs == null || r.sameCardAs !in validRuns }
        val centred = if (valid.isEmpty()) null else valid.all { it.centred == true }
        val settle = if (valid.isEmpty()) null else valid.all { it.settleOk }
        val reports = valid.mapNotNull { it.reportsZoomRatio }.let { if (it.isEmpty()) null else it.all { r -> r } }
        val zPipe = valid.mapNotNull { it.zPipe }.minOrNull()
        val last = runs.lastOrNull()
        // Where the session stands, for after a run that did not count.
        val tally = when (cards.size) {
            0 -> "no valid run yet"
            1 -> "run ${cards[0].run} is valid: one more valid run on a DIFFERENT card decides"
            else -> "${cards.size} cards already agree — Next"
        }
        val words = when {
            last == null -> "never run"
            valid.any { it.centred == false } -> valid.first { it.centred == false }.let {
                "the zoom is NOT the centred crop this app assumes (run ${it.run}: ${it.notCentredWhy()}) — keep Zoom to fit off and send the report"
            }
            // The run just made failed or was stopped (review of 08a77d1): ITS reason and what to do lead, then the tally.
            !last.valid || last.stoppedAt != null -> last.words() + " · " + tally
            last.sameCardAs != null && last.sameCardAs in validRuns -> "that is the same card as run ${last.sameCardAs} — use a different one"
            cards.size >= 2 -> "%d cards agree: the zoom is the centred crop%s%s — Next".format(Locale.ROOT, cards.size,
                if (settle == true) ", frames settle within the dropped ones" else ", but frames are still at the old zoom after the drops (SETTLE_DROP_FRAMES must rise first)",
                zPipe?.let { " · z_pipe %.2f×".format(Locale.ROOT, it) } ?: "")
            else -> "run ${cards[0].run} valid — lift this card, put a DIFFERENT card down, let it scan, then Run again"
        }
        return Combined(cards.size, centred, settle, reports, zPipe, words + apartWords(apart))
    }

    /** " · run 1 (camera 0 · 1600×1200), runs 2, 3 (camera 2 · 1600×1200) count apart: another camera or stream", or "". */
    private fun apartWords(apart: List<Result>): String {
        if (apart.isEmpty()) return ""
        val groups = apart.groupBy { r -> listOfNotNull(r.camera?.let { "camera $it" }, r.stream).joinToString(" · ") }
        return " · " + groups.entries.joinToString(", ") { (where, rs) ->
            (if (rs.size == 1) "run " else "runs ") + rs.joinToString(", ") { it.run.toString() } + " ($where)"
        } + (if (apart.size == 1) " counts" else " count") + " apart: another camera or stream"
    }

    // ── the restore: is it the same card in the same place? ──

    /** The restore's card size within this of the run's H(z) (or of the start scaled by z). */
    const val SAME_H = 0.015
    /** …its base centre within this of the start… */
    const val SAME_CENTRE = 0.01
    /** …and its thumbnail this like the start's ([ZoomDetail.ncc]). PROVISIONAL (the plan's figure). */
    const val SAME_NCC = 0.90

    /** The restore check: [same] = all three hold; [dh] / [dc] / [ncc] the evidence (null = not measured). */
    class SameCard(val same: Boolean, val dh: Double?, val dc: Double?, val ncc: Double?) {
        fun describe(): String = "H %s, centre Δ %s, thumbnail NCC %s".format(Locale.ROOT,
            dh?.let { "%+.1f %%".format(Locale.ROOT, it * 100) } ?: "—", dc?.let { "%.1f %%".format(Locale.ROOT, it * 100) } ?: "—",
            ncc?.let { "%.2f".format(Locale.ROOT, it) } ?: "—")
    }

    /**
     * The restore's [restore] rung against the run's 1× [start]: its size on the run's
     * [fit] at the restore ratio (no fit: the start's size × the ratio), its base centre,
     * and [ncc] of the two thumbnails. Anything missing is "not the same" — the
     * scanned scene is then kept and a swapped card scans.
     */
    fun sameCard(start: Rung?, restore: Rung?, fit: HFit?, ncc: Double?): SameCard {
        val h = restore?.longPx; val h0 = start?.longPx
        val expected = when {
            restore == null -> null
            fit != null -> fit.at(restore.camera)
            h0 != null -> h0 * restore.camera / start.camera
            else -> null
        }
        val dh = if (h != null && expected != null) h / expected - 1 else null
        val dc = if (restore?.cx != null && start?.cx != null) max(abs(restore.cx - start.cx), abs(restore.cy!! - start.cy!!)) else null
        val same = dh != null && abs(dh) <= SAME_H + 1e-9 && dc != null && dc <= SAME_CENTRE + 1e-9 && ncc != null && ncc >= SAME_NCC
        return SameCard(same, dh, dc, ncc)
    }

    // ── the `probe` log lines (≈ 25 a run: START, one per rung, refocus, restore, RESULT) ──

    private fun x(v: Double?) = v?.let { "%.2f×".format(Locale.ROOT, it) } ?: "unknown"

    /** A share as signed % to one decimal, never "−0.0" (a float a hair under zero). */
    internal fun pct(share: Double): String = "%+.1f".format(Locale.ROOT, Math.round(share * 1000) / 10.0 + 0.0)

    /** "probe run 1 START · camera 0 · stream 1600×1200 (High res off) · active 4160×3120 · …" */
    fun startLine(run: Int, facts: Facts, plan: Plan): String =
        "probe run $run START · camera ${facts.cameraId} · stream ${facts.stream} (High res ${if (facts.highRes) "on" else "off"}) · active " +
            (if (facts.activeW != null && facts.activeH != null) "${facts.activeW}×${facts.activeH}" else "unknown") +
            " · lossless ${x(facts.lossless)} · lens max ${x(facts.lensMax)} · AE/AWB locked for the probe" +
            " · card %.0f px long at 1× (base centre %.2f, %.2f)".format(Locale.ROOT, plan.h1, plan.cx, plan.cy) +
            " · ladder 1.00→%.2f× in %d rungs (+3 repeats) · back to %.2f× after".format(Locale.ROOT, plan.top, plan.ladder.size, facts.restoreZ)

    /** "rung 7/18 1.60× · camera 1.600 (CONTROL_ZOOM_RATIO on 6/6 frames …) · answered 180 ms · …" */
    fun rungLine(k: Int, n: Int, r: Rung, h1: Double?, start: Locked?): String = buildString {
        append("rung %d/%d %.2f×".format(Locale.ROOT, k, n, r.asked))
        if (r.error != null) {
            append(if (r.error == ZoomCoordinator.PROBE_NO_ANSWER) " · no answer in ${ZoomCoordinator.SETTLE_TIMEOUT_MS} ms — skipped" else " · refused (${r.error}) — the ladder ends here")
            return@buildString
        }
        val crop = r.frames.mapNotNull { it.cropCentred }
        append(" · camera %.3f (CONTROL_ZOOM_RATIO on %d/%d frames; crop %s — not evidence on API 30+)".format(Locale.ROOT, r.camera,
            r.reported, r.frames.size, if (crop.isEmpty()) "unknown" else "centred on ${crop.count { it }}/${crop.size}"))
        r.answerMs?.let { append(" · answered $it ms") }
        append(" · skipped ${r.skipped} (${r.skippedAtNew} already at %.2f×)".format(Locale.ROOT, r.camera))
        append(" · ${r.used.size}/${r.frames.size} frames used, ${r.atOtherZoom} at another zoom")
        val h = r.longPx
        if (h == null) append(" · no card found") else {
            append(" · card %.0f px".format(Locale.ROOT, h))
            if (h1 != null && h1 > 0) append(" (×%.3f of 1×)".format(Locale.ROOT, h / h1))
            if (start != null) append(" · base centre Δ ${pct(r.cx!! - start.cx)} %, ${pct(r.cy!! - start.cy)} %")
        }
        val lens = r.used.mapNotNull { it.lensDpt }
        if (lens.isNotEmpty()) append(" · lens %.2f dpt%s".format(Locale.ROOT, median(lens), if (start?.lensDpt != null &&
            lens.all { abs(it - start.lensDpt) <= LENS_TOL_DPT + 1e-9 }) " held" else ""))
        r.used.firstOrNull()?.afState?.let { append(" · AF $it") }
        r.used.firstOrNull()?.let { f ->
            if (f.exposureNs != null || f.iso != null) append(" · %s ISO %s".format(Locale.ROOT,
                f.exposureNs?.let { "%.1f ms".format(Locale.ROOT, it / 1e6) } ?: "—", f.iso?.toString() ?: "—"))
        }
        if (start != null) validity(r, start).takeIf { it.isNotEmpty() }?.let { append(" · NOT VALID: ").append(it.joinToString("; ")) }
        append(" · %.1f s".format(Locale.ROOT, r.ms / 1000.0))
    }

    /** "refocus check at 2.20×: AF FOCUSED in 820 ms, lens 4.21 → 4.25 dpt (moved 0.04 dpt …) · card …" */
    fun refocusLine(z: Double, f: Focus, before: Rung?, after: Rung?): String = buildString {
        append("refocus check at %.2f×: AF %s in %d ms".format(Locale.ROOT, z, f.state, f.ms))
        if (f.dptBefore != null && f.dptAfter != null) {
            val d = f.dptAfter - f.dptBefore
            append(", lens %.2f → %.2f dpt (%s)".format(Locale.ROOT, f.dptBefore, f.dptAfter,
                if (abs(d) <= LENS_TOL_DPT + 1e-9) "held — the 1× lock is still right at this zoom"
                else "moved %.2f dpt — the 1× lock is off at this zoom: a Phase-3 focus question".format(Locale.ROOT, abs(d))))
        }
        val h0 = before?.longPx; val h1 = after?.longPx
        if (h0 != null && h1 != null) append(" · card %.0f → %.0f px (%+.1f %%, focus breathing)".format(Locale.ROOT, h0, h1, (h1 / h0 - 1) * 100))
    }

    /**
     * "restore at 1.90×: the same card in the same place (…) — the scanned scene is re-taken; nothing filed".
     * [verify] false = no AF pass was ever started (nothing moved); [zoomError] = the camera never answered
     * the restore's zoom, so nothing could be verified.
     */
    fun restoreLine(z: Double, s: SameCard?, verify: Boolean, zoomError: String? = null): String = when {
        !verify -> "restore at %.2f×: no focus pass ran — the scanned scene is kept as it was; nothing filed".format(Locale.ROOT, z)
        zoomError != null -> ("restore at %.2f×: the camera did not answer the zoom (%s) — not verified: the scanned scene is kept, " +
            "so the card on the tray may scan again; nothing filed").format(Locale.ROOT, z, zoomError)
        s == null -> "restore at %.2f×: no frames to check — the scanned scene is kept; a swapped card will scan".format(Locale.ROOT, z)
        s.same -> "restore at %.2f×: the same card in the same place (%s) — the scanned scene is re-taken; nothing filed".format(Locale.ROOT, z, s.describe())
        else -> "restore at %.2f×: NOT the same card in the same place (%s) — the scanned scene is kept; a new card will scan".format(Locale.ROOT, z, s.describe())
    }

    internal fun Double.r2(): Double = (this * 100).roundToInt() / 100.0
}
