package io.github.darylno.cardscanner.core

import io.github.darylno.cardscanner.core.SyntheticCamera.Config
import io.github.darylno.cardscanner.core.SyntheticCamera.HIGH
import io.github.darylno.cardscanner.core.SyntheticCamera.LOW
import io.github.darylno.cardscanner.core.SyntheticCamera.MID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The zoom probe's maths ([ZoomProbe]) and its detail metric ([ZoomDetail.coherence])
 * on a synthetic camera with KNOWN knees ([SyntheticCamera]).
 */
class ZoomProbeTest {
    companion object {
        @BeforeClass @JvmStatic fun setUp() { OpenCvTest.load() }

        /**
         * THE PRE-REGISTERED ACCEPTANCE GRID (written down before the first run; plan:
         * "recover the true knee within ±0.10× with |bias| ≤ 0.05× in ≥ 9 of 10
         * configurations, with sharpening on"). True knee = readout ÷ stream.
         */
        val GRID = listOf(
            Config("1  binned", 1600, 612, 2080, 2, 0.60, MID),
            Config("2", 1600, 612, 2560, 1, 0.60, MID),
            Config("3", 1600, 520, 2880, 1, 0.60, MID),
            Config("4", 1600, 450, 3200, 1, 0.60, MID),
            Config("5  noisy", 1600, 700, 2400, 1, 0.60, HIGH),
            Config("6  soft lens", 1600, 560, 2720, 1, 0.80, MID),
            Config("7  sharp, quiet", 1600, 612, 2240, 1, 0.45, LOW),
            Config("8", 1600, 480, 3840, 1, 0.60, MID),
            Config("9  High res", 2048, 783, 4160, 1, 0.60, MID),
            Config("10 binned", 1600, 500, 1920, 2, 0.60, MID),
        )
        /** Seeds per configuration (card, noise and finder jitter all differ). */
        val SEEDS = listOf(1L, 2L, 3L)
        /** Frames per rung (noise draws averaged into the step image). */
        const val FRAMES = 3
        const val LENS_MAX = 8.0

        private val masters = HashMap<Long, Mat>()
        fun master(seed: Long): Mat = masters.getOrPut(seed) { SyntheticCamera.master(seed) }
    }

    /** One synthetic run's numbers per rung: zoom, f_res (card / strip), γ² at 5 c/mm, Laplacian (card / strip). */
    class RungDetail(val z: Double, val fCard: Double, val fStrip: Double, val g5: Double, val lapCard: Double, val lapStrip: Double)

    class LadderRun(val cfg: Config, val seed: Long, val plan: ZoomProbe.Plan, val rungs: List<RungDetail>, val ms: Long) {
        val knee: ZoomProbe.Knee = ZoomProbe.knee(rungs.map { it.z to it.fCard }, plan.top)!!
        val lapKnee: ZoomProbe.Knee = ZoomProbe.knee(rungs.map { it.z to it.lapCard }, plan.top)!!
        val error: Double get() = knee.k - cfg.lossless
    }

    /** The card's 1× quad in the full upright frame (the camera renders a window round it; centred on the frame). */
    private fun frameQuad(cam: SyntheticCamera.Camera, cfg: Config): FloatArray {
        val q = cam.trueQuad(1.0)
        val c = ZoomProbe.centre(q)
        val fw = cfg.stream * 3 / 4; val fh = cfg.stream
        return FloatArray(8) { q[it] + (if (it % 2 == 0) fw / 2.0 - c[0] else fh / 2.0 - c[1]).toFloat() }
    }

    /** Run the probe's ladder on [cfg] with [seed]: each rung's step image against the first top rung's. */
    fun ladder(cfg: Config, seed: Long): LadderRun {
        val t0 = System.nanoTime()
        val cam = SyntheticCamera.Camera(cfg, master(seed))
        val plan = ZoomProbe.plan(frameQuad(cam, cfg), cfg.stream * 3 / 4, cfg.stream, LENS_MAX, seed) as ZoomProbe.Plan
        val s = plan.pxPerMm
        val rnd = Random(seed * 7919 + cfg.name.hashCode())
        fun step(z: Double): Pair<Mat, DoubleArray> {
            val fs = cam.frames(z, FRAMES, rnd)
            val img = ZoomDetail.stepImage(fs.map { it.mat }, fs.map { it.quad }, s)
            val lap = fs.map { ZoomDetail.identRaster(it.mat, it.quad) }
            fs.forEach { it.mat.release() }
            return img to doubleArrayOf(lap.map { it.card }.average(), lap.map { it.strip }.average())
        }
        val first = step(plan.order[0])
        val ref = step(plan.order[plan.referenceIndex])
        val out = ArrayList<RungDetail>()
        fun measure(z: Double, st: Pair<Mat, DoubleArray>) {
            val card = ZoomDetail.coherence(st.first, ref.first, s, ZoomDetail.Region.CARD)
            val strip = ZoomDetail.coherence(st.first, ref.first, s, ZoomDetail.Region.STRIP)
            out += RungDetail(z, card.fRes, strip.fRes, card.at(5.0), st.second[0], st.second[1])
            st.first.release()
        }
        measure(plan.order[0], first)
        for (i in 2 until plan.order.size) measure(plan.order[i], step(plan.order[i]))
        ref.first.release()
        cam.release()
        return LadderRun(cfg, seed, plan, out, (System.nanoTime() - t0) / 1_000_000)
    }

    /**
     * THE BUILD-TIME ACCEPTANCE OF THE METRIC, pre-registered before the first run:
     * per configuration, 3 seeds; it PASSES when every seed's knee is within ±0.10× of
     * the true one (readout ÷ stream) and the mean error within ±0.05×; the bar is met
     * when ≥ 9 of 10 configurations pass. The table is printed, with where the
     * Laplacian's knee lands under sharpening.
     *
     * Measured 2026-11-01 (the 1.1.17 commit carries the table): 0 of 10 — NOT MET.
     * Coherence keeps rising past the lossless knee where nearby rungs share the
     * reference's sensor samples and upsampling replicas (binned / sharp sensors:
     * +0.7…+1.4×), and rises too slowly below it where finder jitter blurs the step
     * image more at low zoom and the optics + noise cap detail early (−0.15…−0.5×).
     * So the pre-registered fallback holds: [ZoomProbe.DETAIL_VALIDATED] is false —
     * the probe reports geometry, centring and settle facts only, and MAX_ZOOM is not
     * decided from detail. This test pins the two together: a metric that someday
     * meets the bar fails it until the constant is changed on purpose.
     */
    @Test
    fun theCoherenceKneeIsRecoveredAcrossTheGrid() {
        SEEDS.forEach { master(it) }                        // the masters are built once, on this thread
        val pool = Executors.newFixedThreadPool(4)
        val futures = GRID.map { cfg -> SEEDS.map { seed -> pool.submit<LadderRun> { ladder(cfg, seed) } } }
        val results = try { futures.map { fs -> fs.map { it.get() } } } finally { pool.shutdown() }
        val rows = ArrayList<String>()
        rows += "config            stream  H1  true   top | knee per seed (error)               mean err | pass | Laplacian knee | fit flags | f_res card 1× → top (seed 1) | ms"
        var passed = 0
        val allErrors = ArrayList<Double>()
        for ((ci, cfg) in GRID.withIndex()) {
            val runs = results[ci]
            val errs = runs.map { it.error }
            allErrors += errs
            val ok = errs.all { abs(it) <= 0.10 + 1e-9 } && abs(errs.average()) <= 0.05 + 1e-9
            if (ok) passed++
            val r1 = runs[0]
            val f1 = r1.rungs.filter { it.z == 1.0 }.map { it.fCard }.average()
            val ft = r1.rungs.filter { it.z == r1.plan.top }.map { it.fCard }.average()
            rows += String.format(Locale.ROOT, "%-16s %5d %4d %5.2f %5.2f | %s | %+6.3f | %s | %s | %s | %4.2f → %4.2f c/mm | %d",
                cfg.name, cfg.stream, cfg.h1, cfg.lossless, r1.plan.top,
                runs.joinToString("  ") { String.format(Locale.ROOT, "%.2f (%+.2f)", it.knee.k, it.error) },
                errs.average(), if (ok) "PASS" else "fail",
                runs.joinToString("/") { String.format(Locale.ROOT, "%.2f", it.lapKnee.k) },
                runs.joinToString(" ") { if (it.knee.noKnee) "noKnee" else if (it.knee.noDetail) "noDetail" else "ok" },
                f1, ft, runs.sumOf { it.ms })
            rows += "    seed 1 rungs: " + r1.rungs.sortedBy { it.z }.joinToString(" ") { String.format(Locale.ROOT, "%.1f:%.2f/%.2f", it.z, it.fCard, it.fStrip) }
        }
        rows += String.format(Locale.ROOT, "configurations passing: %d of %d (bar: ≥ 9) · grid-wide mean error %+.3f× · bar %s",
            passed, GRID.size, allErrors.average(), if (passed >= 9) "MET" else "NOT MET")
        println(rows.joinToString("\n"))
        assertEquals("the measured outcome of the pre-registered bar vs ZoomProbe.DETAIL_VALIDATED", passed >= 9, ZoomProbe.DETAIL_VALIDATED)
        assertFalse("the fallback: MAX_ZOOM is not decided from detail", ZoomProbe.DETAIL_VALIDATED)
    }

    // ── the ladder, the frames, the rules (numbers, no pictures) ──

    /** The upright 1600×1200 stream: 1200 wide, 1600 tall. */
    private val W = 1200; private val H = 1600

    /** An upright card quad (TL,TR,BR,BL): centre ([cx], [cy]) px, [long] px tall (its 88 mm side). */
    private fun quad(cx: Double, cy: Double, long: Double): FloatArray {
        val hw = long * 63 / 88 / 2; val hh = long / 2
        return floatArrayOf((cx - hw).toFloat(), (cy - hh).toFloat(), (cx + hw).toFloat(), (cy - hh).toFloat(),
            (cx + hw).toFloat(), (cy + hh).toFloat(), (cx - hw).toFloat(), (cy + hh).toFloat())
    }

    @Test
    fun theLadderKeepsTheCardInViewAndIgnoresMaxZoom() {
        val p = ZoomProbe.plan(quad(600.0, 800.0, 450.0), W, H, 8.0, 1) as ZoomProbe.Plan
        // 450/1600 padded 6 % a side → fit 3.16 → ×0.95 = 3.00: the CEILING, far past MAX_ZOOM.
        assertEquals(3.0, p.top, 0.0)
        assertTrue(p.top > ZoomFit.MAX_ZOOM)
        assertEquals(listOf(1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 2.0, 2.2, 2.4, 2.6, 2.8, 3.0), p.ladder)
        assertEquals(450.0 * 3.0 / 88, p.pxPerMm, 1e-9)
        assertEquals(450.0, p.h1, 1e-9)
        val padded = ZoomProbe.padded(p.box)
        for (z in p.ladder) for (e in listOf(padded.x0, padded.x1, padded.y0, padded.y1))
            assertTrue("z $z edge $e stays in view", ZoomFit.toViewAxis(e, z) in 0.0..1.0)
        // A lens max caps it (and a top that is not a ladder step is added as the last rung).
        val q = ZoomProbe.plan(quad(600.0, 800.0, 450.0), W, H, 2.35, 1) as ZoomProbe.Plan
        assertEquals(2.3, q.top, 0.0)
        assertEquals(listOf(2.0, 2.2, 2.3), q.ladder.takeLast(3))
        // The N200's usual mount: a 612 px card → 2.20× (12 rungs).
        val n = ZoomProbe.plan(quad(600.0, 800.0, 612.0), W, H, 8.0, 1) as ZoomProbe.Plan
        assertEquals(2.2, n.top, 0.0)
        assertEquals(12, n.ladder.size)
        // The search rect at the top holds the whole card with room.
        val r = ZoomProbe.searchRect(n.box, n.top, W, H)
        val cardAtTop = ZoomFit.toViewAxis(n.box.y1, n.top) * H - ZoomFit.toViewAxis(n.box.y0, n.top) * H
        assertTrue("${r.toList()} vs card ${cardAtTop}", r[3] >= cardAtTop && r[1] >= 0 && r[1] + r[3] <= H)
    }

    @Test
    fun anOffCentreCardIsRefusedWithTheNumbers() {
        // 450 px tall, its centre 25 % right and 20 % below the frame centre: the probe could reach little.
        val off = ZoomProbe.plan(quad(900.0, 1120.0, 450.0), W, H, 8.0, 1) as ZoomProbe.Refusal
        assertEquals(ZoomProbe.Why.OFF_CENTRE, off.why)
        assertTrue(off.words, off.words.startsWith("The card is 25 % right and 20 % below the view's centre — the probe could reach only 1.10×; centred it could reach 3.00×."))
        assertTrue(off.words, off.words.endsWith("Move it toward the middle and Run again"))
        val fills = ZoomProbe.plan(quad(600.0, 800.0, 1450.0), W, H, 8.0, 1) as ZoomProbe.Refusal
        assertEquals(ZoomProbe.Why.FILLS_VIEW, fills.why)
        assertTrue(fills.words, fills.words.startsWith("The card fills the view (only 1.0× of room)"))
        assertEquals(ZoomProbe.Why.NO_CARD, (ZoomProbe.plan(null, W, H, 8.0, 1) as ZoomProbe.Refusal).why)
        assertEquals(ZoomProbe.Why.LENS, (ZoomProbe.plan(quad(600.0, 800.0, 450.0), W, H, 1.4, 1) as ZoomProbe.Refusal).why)
    }

    @Test
    fun theOrderIsSeededWithRepeatsAndTheReferenceSecond() {
        val a = ZoomProbe.plan(quad(600.0, 800.0, 612.0), W, H, 8.0, 7) as ZoomProbe.Plan
        val o = a.order
        assertEquals(1.0, o.first(), 0.0); assertEquals(a.top, o[1], 0.0); assertEquals(1, a.referenceIndex)
        assertEquals(a.top, o[o.size - 2], 0.0); assertEquals(1.0, o.last(), 0.0)
        assertEquals("1× three times", 3, o.count { it == 1.0 }); assertEquals("the top twice", 2, o.count { it == a.top })
        assertEquals("every rung asked, + 3 repeats", a.ladder.size + 3, o.size)
        assertEquals(a.ladder.toSet(), o.toSet())
        val mid = o.subList(2, o.size - 2)
        assertEquals("1× again in the middle", mid.size / 2, mid.indexOf(1.0))
        assertEquals("the same seed, the same order", o, (ZoomProbe.plan(quad(600.0, 800.0, 612.0), W, H, 8.0, 7) as ZoomProbe.Plan).order)
        assertNotEquals("another seed, another order", o, (ZoomProbe.plan(quad(600.0, 800.0, 612.0), W, H, 8.0, 8) as ZoomProbe.Plan).order)
        assertTrue("no rung asked twice in a row", o.zipWithNext().none { it.first == it.second })
    }

    @Test
    fun frameZoomFollowsTheLivePathsRule() {
        assertEquals(1.9, ZoomProbe.frameZoom(1.9, null, null)!!, 0.0)
        assertEquals("CONTROL_ZOOM_RATIO 1 (API < 30): the crop says it", 1.9, ZoomProbe.frameZoom(1.0, 2189, 4160)!!, 1e-3)
        assertEquals("within 0.5 % of 1: the crop", 2.0, ZoomProbe.frameZoom(1.003, 2080, 4160)!!, 0.0)
        assertEquals(1.0, ZoomProbe.frameZoom(1.0, null, null)!!, 0.0)
        assertNull(ZoomProbe.frameZoom(null, null, null))
    }

    // ── synthetic runs, in numbers ──

    private val lens = 4.21; private val exposure = 16_700_000L; private val iso = 400

    /**
     * A rung of [n] frames at [z]: the card [h1] px at 1× (+ a finder bias of 3 px) whose BASE centre
     * is ([bx], [by]); a crop off-centre by [crop](z) base fractions puts it elsewhere in the view.
     */
    private fun rung(i: Int, z: Double, h1: Double = 612.0, bx: Double = 0.5, by: Double = 0.5, crop: (Double) -> Double = { 0.0 },
                     n: Int = 6, lensDpt: Double = lens, luma: Double = 120.0, exp: Long = exposure, frameZ: Double? = z,
                     error: String? = null, afterRefocus: Boolean = false): ZoomProbe.Rung {
        val vx = 0.5 + (bx - 0.5 - crop(z)) * z; val vy = 0.5 + (by - 0.5) * z
        val frames = List(n) { ZoomProbe.FrameFacts(quad(vx * W, vy * H, h1 * z + 3.0), frameZ, true, lensDpt, exp, iso, "FOCUSED_LOCKED", luma, true) }
        return ZoomProbe.Rung(i, z, z, 120, error, if (error != null) emptyList() else frames, 2, 2, W, H, 900, afterRefocus)
    }

    private val facts = ZoomProbe.Facts("0", W, H, false, 4160, 3120, 2.6, 10.0, 1.0)
    private val focused = ZoomProbe.Focus(true, 4.05, lens, 640, "FOCUSED")
    private fun planFor(h1: Double = 612.0) = ZoomProbe.plan(quad(600.0, 800.0, h1), W, H, 8.0, 3) as ZoomProbe.Plan

    /** Every rung of [plan] in order, each from [make]. */
    private fun run(plan: ZoomProbe.Plan, make: (Int, Double) -> ZoomProbe.Rung = { i, z -> rung(i, z) }) =
        plan.order.mapIndexed { i, z -> make(i, z) }

    @Test
    fun eachValidityRuleRejectsItsRung() {
        val locked = ZoomProbe.Locked(lens, exposure, iso, 120.0, 0.5, 0.5)
        assertEquals(emptyList<String>(), ZoomProbe.validity(rung(3, 1.6), locked))
        fun only(r: ZoomProbe.Rung) = ZoomProbe.validity(r, locked).single()
        assertEquals("no answer", only(rung(3, 1.6, error = ZoomCoordinator.PROBE_NO_ANSWER)))
        assertEquals("refused (out of range)", only(rung(3, 1.6, error = "out of range")))
        assertEquals("only 3 frame(s) usable", only(rung(3, 1.6, n = 3)))
        assertEquals("the lens moved (4.21 → 4.24 dpt)", only(rung(3, 1.6, lensDpt = 4.24)))
        assertEquals("…but not after the refocus check", emptyList<String>(), ZoomProbe.validity(rung(3, 1.6, lensDpt = 4.5, afterRefocus = true), locked))
        assertEquals("the exposure changed", only(rung(3, 1.6, exp = 20_000_000L)))
        assertEquals("the light changed (card luma -12 %)", only(rung(3, 1.6, luma = 105.6)))
        val noCard = ZoomProbe.Rung(3, 1.6, 1.6, 100, null, List(6) { ZoomProbe.FrameFacts(null, 1.6) }, 2, 2, W, H, 900)
        assertEquals("no card found", only(noCard))
        // Frames still at the old zoom (their meta, or their card's size) are not used — and counted.
        val mixed = ZoomProbe.Rung(3, 1.6, 1.6, 100, null, rung(3, 1.6).frames.take(4) + rung(3, 1.4, frameZ = 1.4).frames.take(2), 2, 0, W, H, 900)
        assertEquals(2, mixed.atOtherZoom); assertEquals(4, mixed.used.size)
        assertEquals(emptyList<String>(), ZoomProbe.validity(mixed, locked))
        // The geometry rules: size off the H fit, base centre moved.
        val fit = ZoomProbe.HFit(612.0, 3.0, 0.0, 0.0)
        assertEquals(emptyList<String>(), ZoomProbe.geometry(rung(3, 1.6), locked, fit))
        assertEquals("card size +1.5 % off H = a·z + c", ZoomProbe.geometry(rung(3, 1.6, h1 = 612.0 * 1.0155), locked, fit).single())
        assertEquals("base centre moved +1.5 %, +0.0 %", ZoomProbe.geometry(rung(3, 1.6, bx = 0.515), locked, fit).single())
    }

    @Test
    fun theVerdictNeverDecidesMaxZoomFromDetail() {
        val plan = planFor()
        val r = ZoomProbe.verdict(1, plan, run(plan), focused, focused, facts)
        assertTrue(r.reason, r.valid); assertEquals(true, r.centred)
        assertEquals(612.0, r.fit!!.a, 1e-3); assertEquals(3.0, r.fit!!.c, 1e-3)
        assertEquals(0, r.settleRungs); assertTrue(r.settleOk); assertEquals(true, r.reportsZoomRatio)
        val m = r.toMap()
        assertTrue(m.containsKey("knee") && m["knee"] == null)
        assertTrue(m.containsKey("recommended") && m["recommended"] == null)
        assertTrue(r.words(), r.words().endsWith("the probe does not measure MAX_ZOOM"))
        assertTrue(r.logLine(), r.logLine().contains("knee: not measured (no validated detail metric)"))
        val c = ZoomProbe.combine(listOf(r, ZoomProbe.verdict(2, plan, run(plan), focused, focused, facts)))
        assertTrue(c.enough)
        assertTrue(c.checks(), c.checks().endsWith("✗ knee: not measured (no validated detail metric — MAX_ZOOM is not decided by the probe)"))
        assertFalse("never a default-on yes from the probe", c.checks().contains("✓ knee"))
        assertFalse(ZoomProbe.DETAIL_VALIDATED)
        // The JSON round-trips (it is what /api/device and Diagnostics will read).
        assertEquals(true, (MiniJson.parse(r.toJson()) as Map<*, *>)["centred"])
    }

    /** The crop shifted 3 % at 2× (δ = 0.03·(z − 1) of the frame): the card's base centre drifts — caught. */
    @Test
    fun anOffCentreCropIsCaught() {
        val plan = planFor()
        val r = ZoomProbe.verdict(1, plan, run(plan) { i, z -> rung(i, z, crop = { 0.03 * (it - 1) }) }, focused, focused, facts)
        assertTrue("the run itself measured cleanly: ${r.reason}", r.valid)
        assertEquals(false, r.centred)
        assertEquals("the drift at the top (2.2×): 3.6 %", 0.036, r.drift!!, 1e-6)
        assertTrue(r.words(), r.words().startsWith("The zoom is NOT the centred crop this app assumes (the card moved 3.6 % at 2.20×)"))
        assertTrue(r.logLine(), r.logLine().contains("NOT centred"))
        // A crop shifted half a percent is within the rule.
        val ok = ZoomProbe.verdict(1, plan, run(plan) { i, z -> rung(i, z, crop = { 0.004 * (it - 1) }) }, focused, focused, facts)
        assertEquals(true, ok.centred)
        // A run that is not centred blocks the decision across runs.
        val c = ZoomProbe.combine(listOf(ok, r))
        assertEquals(false, c.centred)
        assertTrue(c.words, c.words.startsWith("the zoom is NOT the centred crop"))
    }

    @Test
    fun aMovedCardOrALightChangeIsInconclusive() {
        val plan = planFor()
        val midOne = plan.order.withIndex().filter { it.value == 1.0 }.map { it.index }[1]
        // Nudged 2 % after the start: the middle 1× repeat disagrees.
        val moved = ZoomProbe.verdict(1, plan, run(plan) { i, z -> rung(i, z, bx = if (i >= midOne) 0.52 else 0.5) }, focused, focused, facts)
        assertFalse(moved.valid)
        assertEquals("the card moved (1× again is 2 % off the start): leave the tray untouched and Run again", moved.reason)
        assertEquals("Not conclusive — the card moved (1× again is 2 % off the start): leave the tray untouched and Run again", moved.words())
        // A lamp switched on half way (AE/AWB locked: only the card's luma shows it).
        val light = ZoomProbe.verdict(1, plan, run(plan) { i, z -> rung(i, z, luma = if (i >= midOne) 140.0 else 120.0) }, focused, focused, facts)
        assertFalse(light.valid)
        assertTrue(light.reason!!, light.reason!!.startsWith("the light or focus changed"))
        // The lens drifted off the lock.
        val focus = ZoomProbe.verdict(1, plan, run(plan) { i, z -> rung(i, z, lensDpt = if (i >= midOne) 4.30 else lens) }, focused, focused, facts)
        assertTrue(focus.reason!!, focus.reason!!.startsWith("the light or focus changed"))
        // No FOCUSED at 1×.
        val nf = ZoomProbe.verdict(1, plan, run(plan), ZoomProbe.Focus(false, 4.0, 4.0, 5000, "NOT focused"), null, facts)
        assertTrue(nf.reason!!, nf.reason!!.startsWith("the 1× focus pass did not report FOCUSED"))
        // Too many rungs the camera did not answer.
        val silent = ZoomProbe.verdict(1, plan, run(plan) { i, z -> if (i % 3 == 2) rung(i, z, error = ZoomCoordinator.PROBE_NO_ANSWER) else rung(i, z) }, focused, focused, facts)
        assertTrue(silent.reason!!, silent.reason!!.startsWith("only ") && silent.reason!!.contains("no answer"))
        // Frames still at the old zoom on more than 10 % of rungs: valid, but the settle rule fails.
        val slow = ZoomProbe.verdict(1, plan, run(plan) { i, z ->
            if (i % 4 == 3) ZoomProbe.Rung(i, z, z, 120, null, rung(i, z).frames + rung(i, 1.0).frames.take(1), 2, 0, W, H, 900) else rung(i, z)
        }, focused, focused, facts)
        assertTrue(slow.reason, slow.valid); assertFalse(slow.settleOk)
        assertEquals(1, slow.settleMax)
        assertTrue(slow.words(), slow.words().contains("still at the old zoom on"))
    }

    @Test
    fun combineNeedsTwoValidRunsOnDifferentCards() {
        val plan = planFor()
        fun valid(n: Int, same: Int? = null) = ZoomProbe.verdict(n, plan, run(plan), focused, focused, facts, sameCardAs = same)
        assertEquals("never run", ZoomProbe.combine(emptyList()).words)
        val one = ZoomProbe.combine(listOf(valid(1)))
        assertFalse(one.enough)
        assertEquals("run 1 valid — lift this card, put a DIFFERENT card down, let it scan, then Run again", one.words)
        val again = ZoomProbe.combine(listOf(valid(1), valid(2, same = 1)))
        assertFalse("the same card twice is one card", again.enough)
        assertEquals("that is the same card as run 1 — use a different one", again.words)
        val two = ZoomProbe.combine(listOf(valid(1), valid(2, same = 1), valid(3)))
        assertTrue(two.enough); assertEquals(2, two.validCards)
        assertTrue(two.words, two.words.startsWith("2 cards agree: the zoom is the centred crop, frames settle within the dropped ones · z_pipe 1.43× — Next"))
        assertTrue(two.logLine(), two.logLine().startsWith("probe VERDICT: 2 cards agree"))
        assertTrue(two.checks(), two.checks().startsWith("✓ 2 valid run(s) on different cards (needs 2) ✓ centred ✓ settle"))
        // An inconclusive run between them does not count, and stopped runs neither.
        val stopped = ZoomProbe.stopped(4, plan, "the scan screen stopped", facts)
        assertEquals(2, ZoomProbe.combine(listOf(valid(1), stopped, valid(3))).validCards)
        assertEquals("Run 4 stopped (the scan screen stopped) — nothing filed; Run again when ready", stopped.words())
    }

    @Test
    fun zPipeIs880OverH1x() {
        val plan = planFor(612.0)
        val r = ZoomProbe.verdict(1, plan, run(plan) { i, z -> rung(i, z, h1 = 609.0) }, focused, focused, facts)
        assertEquals("880 ÷ the 1× card (609 + the finder's 3 px)", 880.0 / 612.0, r.zPipe!!, 1e-9)
        assertEquals(880, ZoomProbe.Z_PIPE_PX)
        assertTrue(r.logLine(), r.logLine().contains("z_pipe 1.44×"))
    }

    @Test
    fun theRestoreKnowsTheSameCardInTheSamePlace() {
        val fit = ZoomProbe.HFit(612.0, 3.0, 0.0, 0.0)
        val start = rung(0, 1.0)
        assertTrue(ZoomProbe.sameCard(start, rung(20, 1.9), fit, 0.97).same)
        assertFalse("another card", ZoomProbe.sameCard(start, rung(20, 1.9), fit, 0.41).same)
        assertFalse("moved 1.5 %", ZoomProbe.sameCard(start, rung(20, 1.9, bx = 0.515), fit, 0.97).same)
        assertFalse("bigger by 2 % (lifted, another sleeve)", ZoomProbe.sameCard(start, rung(20, 1.9, h1 = 624.5), fit, 0.97).same)
        assertFalse("no frames", ZoomProbe.sameCard(start, null, fit, null).same)
        assertTrue("no fit: the start scaled by the ratio", ZoomProbe.sameCard(start, rung(20, 1.0), null, 0.95).same)
        assertTrue(ZoomProbe.restoreLine(1.9, ZoomProbe.sameCard(start, rung(20, 1.9), fit, 0.97), true)
            .startsWith("restore at 1.90×: the same card in the same place (H +0.0 %, centre Δ 0.0 %, thumbnail NCC 0.97)"))
    }
}
