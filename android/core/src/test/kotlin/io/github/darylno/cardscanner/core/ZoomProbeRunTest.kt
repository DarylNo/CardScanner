package io.github.darylno.cardscanner.core

import io.github.darylno.cardscanner.core.ZoomProbeRun.Kind
import io.github.darylno.cardscanner.core.ZoomProbeRun.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue
import java.util.Random
import kotlin.math.max

/**
 * The zoom probe's sequencer ([ZoomProbeRun]) against a fake camera, worker and
 * clock: the order of a full run, the restores (verified once the AF pass ran),
 * the coordinator's abort (no end, no rebase), one zoom in flight and at most two
 * rungs queued, stale answers ignored, the 90 s budget, the log's size, and
 * [ZoomProbeRun.Port.finished] exactly once.
 */
class ZoomProbeRunTest {
    private val W = 1200; private val H = 1600
    private val H1 = 612.0

    private fun quad(cx: Double, cy: Double, long: Double): FloatArray {
        val hw = long * 63 / 88 / 2; val hh = long / 2
        return floatArrayOf((cx - hw).toFloat(), (cy - hh).toFloat(), (cx + hw).toFloat(), (cy - hh).toFloat(),
            (cx + hw).toFloat(), (cy + hh).toFloat(), (cx - hw).toFloat(), (cy + hh).toFloat())
    }

    /** A normalised 64×88 thumbnail of "card" [seed]: the same seed is the same card (NCC 1), another is unrelated (≈ 0). */
    private fun thumb(seed: Long): FloatArray {
        val r = Random(seed)
        val v = DoubleArray(ZoomDetail.THUMB_W * ZoomDetail.THUMB_H) { r.nextGaussian() }
        val m = v.average(); val sd = kotlin.math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        return FloatArray(v.size) { ((v[it] - m) / sd).toFloat() }
    }

    private inner class Fake : ZoomProbeRun.Port {
        lateinit var run: ZoomProbeRun
        var now = 0L
        private var seq = 0
        private val timers = PriorityQueue<Triple<Long, Int, () -> Unit>>(compareBy<Triple<Long, Int, () -> Unit>>({ it.first }, { it.second }))
        val calls = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val outcomes = mutableListOf<ZoomProbeRun.Outcome>()
        val progresses = mutableListOf<ZoomProbeRun.Progress>()
        val focusAt = mutableListOf<Pair<Double, Double>>()
        // The scene and the camera.
        var beginRefusal: String? = null
        var checkRefusal: String? = null
        var focusOk = true
        var cameraZ = 1.9
        var restoreZ = 1.9
        var refuseAbove: Double? = null
        var silent = setOf<Double>()
        var noCard = false
        var bx = 0.5; var by = 0.5
        /** The card's long side at 1× without the finder's 3 px. */
        var h1 = H1
        var card = 1L
        /** What the restore's frames show: another card, the card moved. */
        var restoreCard = 1L
        var restoreBx = 0.5
        var framesNever = false
        var workerMs = 300L
        var stuck = setOf<Role>()
        /** Roles the test answers by hand (from [pendingWorker]); the rest answer after [workerMs]. */
        var manual = setOf<Role>()
        val pendingWorker = ArrayDeque<() -> Unit>()
        var zoomOutstanding = 0; var maxZoomOutstanding = 0
        var measureOutstanding = 0; var maxMeasureOutstanding = 0
        var aeOn = false
        /** How long each AF pass takes: the 1× pass, then the refocus check. */
        var focusMs = 600L; var refocusMs = 600L
        /** [calls]' size when each AF pass answered (the lens has settled from there on). */
        val focusAnsweredAt = mutableListOf<Int>()
        /** A silent zoom that still MOVES the camera (it got there; the answer never came). */
        var silentMoves = false
        /** The fake camera's zoom when each progress came. */
        val progressCam = mutableListOf<Double>()

        override fun begin(why: String): String? { calls += "begin"; return beginRefusal }
        override fun check(answer: (String?) -> Unit) { calls += "check"; later(10) { answer(checkRefusal) } }
        override fun aeLock(on: Boolean) { calls += "ae ${if (on) "on" else "off"}"; aeOn = on }
        override fun zoomTo(z: Double) {
            calls += "zoom %.2f".format(z)
            zoomOutstanding++; maxZoomOutstanding = max(maxZoomOutstanding, zoomOutstanding)
            if (z in silent) {
                if (silentMoves) cameraZ = z
                later(ZoomCoordinator.SETTLE_TIMEOUT_MS) { zoomOutstanding--; run.onZoom(1, z, z, null, ZoomCoordinator.PROBE_NO_ANSWER) }
                return
            }
            val refused = refuseAbove?.let { z > it } == true
            // The glue posts the coordinator's answer: never inside zoomTo, even when the camera is already there.
            later(if (z == cameraZ) 0 else 150) {
                zoomOutstanding--
                if (!refused) cameraZ = z
                run.onZoom(1, z, cameraZ, if (refused) null else z, if (refused) "zoom ratio out of range" else null)
            }
        }
        override fun focusOnCard(u: Double, v: Double, why: String, done: (ZoomProbe.Focus) -> Unit) {
            calls += "focus"; focusAt += u to v
            val ms = if (focusAt.size == 1) focusMs else refocusMs
            later(ms) { focusAnsweredAt += calls.size; done(ZoomProbe.Focus(focusOk, 4.05, 4.21, ms, if (focusOk) "FOCUSED" else "NOT focused")) }
        }
        override fun frames(done: (ZoomProbeRun.Grab?) -> Unit) {
            calls += "frames"
            if (!framesNever) later(200) { done(ZoomProbeRun.Grab(6, 2, 1)) }
        }
        override fun measure(role: Role, grab: ZoomProbeRun.Grab, rect: IntArray?, done: (ZoomProbeRun.Measured?) -> Unit) {
            calls += "measure $role"
            measureOutstanding++; maxMeasureOutstanding = max(maxMeasureOutstanding, measureOutstanding)
            val z = cameraZ
            val restore = role == Role.RESTORE
            val x = if (restore) restoreBx else bx
            val frames = List(6) {
                ZoomProbe.FrameFacts(if (noCard) null else quad((0.5 + (x - 0.5) * z) * W, (0.5 + (by - 0.5) * z) * H, h1 * z + 3.0),
                    z, true, if (role == Role.REFOCUS) 4.24 else 4.21, 16_700_000L, 400, "FOCUSED_LOCKED", 120.0, true)
            }
            val th = when (role) { Role.LOCATE, Role.START -> thumb(card); Role.RESTORE -> thumb(restoreCard); else -> null }
            val deliver = { measureOutstanding--; done(ZoomProbeRun.Measured(frames, W, H, th, 250)) }
            if (role in stuck) return
            if (role in manual) pendingWorker += deliver else later(workerMs, deliver)
        }
        override fun end(verified: Boolean, why: String): Boolean { calls += "end $verified"; return true }
        override fun later(ms: Long, block: () -> Unit) { timers += Triple(now + ms, seq++, block) }
        override fun now(): Long = now
        override fun log(line: String) { logs += line }
        override fun progress(p: ZoomProbeRun.Progress) { progresses += p; progressCam += cameraZ }
        override fun finished(o: ZoomProbeRun.Outcome) { outcomes += o; calls += "finished ${o.kind}" }
        override fun facts() = ZoomProbe.Facts("0", W, H, false, 4160, 3120, 2.6, 10.0, restoreZ)

        /** Run every timer due within [ms] (in order). */
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val t = timers.peek() ?: break
                if (t.first > until) break
                timers.poll(); now = t.first; t.third()
            }
            now = until
        }
        /** Run until [cond] holds (or [limit] ms). */
        fun until(limit: Long = 200_000, cond: () -> Boolean) {
            val end = now + limit
            while (!cond() && now < end) advance(10)
        }
        val zooms get() = calls.filter { it.startsWith("zoom ") }.map { it.removePrefix("zoom ").toDouble() }
        fun index(call: String) = calls.indexOf(call)
    }

    private fun fake(earlier: List<ZoomProbeRun.Earlier> = emptyList(), runNo: Int = 1, setup: Fake.() -> Unit = {}): Fake {
        val f = Fake()
        f.setup()
        f.run = ZoomProbeRun(f, runNo, 3, earlier)
        return f
    }

    @Test
    fun aFullRunGoesLocateFocusRungsRefocusRestoreAndFinishesOnce() {
        val f = fake()
        f.run.start()
        f.until { f.outcomes.isNotEmpty() }
        val doneAt = f.now
        f.advance(200_000)                                          // nothing more happens after it finished
        val plan = ZoomProbe.plan(quad(W / 2.0, H / 2.0, H1 + 3), W, H, 10.0, 3) as ZoomProbe.Plan
        assertEquals(2.2, plan.top, 0.0)
        // BEGIN → CHECK → AE lock → LOCATE at 1× → FOCUS on the card at 1× → the ladder → REFOCUS at the top → RESTORE → end.
        assertEquals(listOf("begin", "check", "ae on", "zoom 1.00", "frames", "measure LOCATE", "focus", "zoom 1.00", "frames", "measure START"),
            f.calls.take(10))
        assertEquals("every rung in the plan's order, then the refocus at the top, then back to the scanner's 1.90×",
            listOf(1.0) + plan.order + listOf(plan.top, 1.9), f.zooms)
        assertEquals(2, f.calls.count { it == "focus" })
        assertEquals("the 1× pass on the card's centre", 0.5, f.focusAt[0].first, 1e-3)
        assertEquals("the refocus at the top, on the card in view fractions", 0.5, f.focusAt[1].second, 1e-3)
        val restoreZoom = f.calls.lastIndexOf("zoom 1.90")
        assertTrue(f.index("measure REFOCUS") in f.calls.lastIndexOf("focus") until restoreZoom)
        assertTrue("AE unlocked before the restore's frames", f.calls.lastIndexOf("ae off") in restoreZoom until f.index("measure RESTORE"))
        assertEquals(listOf("end true"), f.calls.filter { it.startsWith("end") })
        assertTrue(f.index("end true") < f.index("finished DONE"))
        assertEquals(1, f.outcomes.size)
        val o = f.outcomes.single()
        assertEquals(Kind.DONE, o.kind)
        val r = o.result!!
        assertTrue(r.reason, r.valid); assertEquals(true, r.centred)
        assertEquals(plan.order.size, r.rungs)
        assertEquals("FOCUSED", r.refocus!!.state)
        assertTrue(f.logs.any { it.startsWith("refocus check at 2.20×: AF FOCUSED in 600 ms, lens 4.05 → 4.21 dpt (moved 0.16 dpt") })
        assertTrue("the thumbnail comes back for the next run's same-card check", o.thumbnail != null)
        assertFalse(f.aeOn)
        assertTrue("well inside the budget: $doneAt ms", doneAt < ZoomProbeRun.BUDGET_MS / 3)
        assertEquals(1, f.maxZoomOutstanding)
        assertTrue(f.logs.any { it.startsWith("probe run 1 START") && it.contains("ladder 1.00→2.20× in 12 rungs (+3 repeats)") })
        assertTrue(f.logs.any { it.startsWith("restore at 1.90×: the same card in the same place") })
        assertTrue(f.logs.last(), f.logs.last().startsWith("probe run 1 RESULT: valid · centred"))
        // The cancel and the abort after the end change nothing.
        f.run.cancel("late"); f.run.aborted("late")
        assertEquals(1, f.outcomes.size)
    }

    @Test
    fun aRefusedBeginOrCheckChangesNothing() {
        val a = fake { beginRefusal = "The zoom is still settling — try again in a second" }
        a.run.start(); a.advance(10_000)
        assertEquals(listOf("begin", "finished REFUSED"), a.calls)
        assertEquals("The zoom is still settling — try again in a second", a.outcomes.single().words)
        val b = fake { checkRefusal = "Let the card scan first (wait for the ✓), then Run probe" }
        b.run.start(); b.advance(10_000)
        assertEquals("the camera never moved: the probe ends on the identical mapping",
            listOf("begin", "check", "end false", "finished REFUSED"), b.calls)
        assertTrue(b.logs.single(), b.logs.single().startsWith("probe refused — Let the card scan first"))
    }

    @Test
    fun noCardOrNoFocusRestoresBeforeAnyZoomedMeasurement() {
        val a = fake { noCard = true }
        a.run.start(); a.until { a.outcomes.isNotEmpty() }
        assertEquals("1×, the retry, then back", listOf(1.0, 1.0, 1.9), a.zooms)
        assertEquals(2, a.calls.count { it == "measure LOCATE" })
        assertFalse("no AF ran", a.calls.contains("focus"))
        assertFalse("nothing to verify: no AF ran", a.calls.contains("measure RESTORE"))
        assertEquals(listOf("end false"), a.calls.filter { it.startsWith("end") })
        assertEquals(Kind.REFUSED, a.outcomes.single().kind)
        assertEquals("No card found at 1× — put a card near the middle and Run again", a.outcomes.single().words)
        assertFalse(a.aeOn)
        val b = fake { focusOk = false }
        b.run.start(); b.until { b.outcomes.isNotEmpty() }
        assertEquals("1×, then straight back: no zoomed rung", listOf(1.0, 1.9), b.zooms)
        assertTrue("the AF moved the lens: the restore checks the card", b.calls.contains("measure RESTORE"))
        assertFalse(b.calls.any { it == "measure START" || it == "measure RUNG" })
        assertEquals("the same card against the LOCATE frames: rebased", listOf("end true"), b.calls.filter { it.startsWith("end") })
        assertEquals("Couldn't focus on the card — more light, then Run again", b.outcomes.single().words)
        // An off-centre card: the plan refuses with its numbers, nothing zoomed.
        val c = fake { bx = 0.75; by = 0.70 }
        c.run.start(); c.until { c.outcomes.isNotEmpty() }
        assertEquals(listOf(1.0, 1.9), c.zooms)
        assertTrue(c.outcomes.single().words, c.outcomes.single().words.startsWith("The card is 25 % right and 20 % below the view's centre"))
    }

    @Test
    fun oneRungInFlightAndStaleAnswersIgnored() {
        val f = fake { manual = setOf(Role.START, Role.RUNG) }
        f.run.start()
        f.until(30_000) { f.calls.count { it == "measure RUNG" } >= 1 && f.pendingWorker.size >= 2 }
        // Two rungs wait for the worker: the camera does not move on.
        val zoomsNow = f.zooms.size
        f.advance(10_000)
        assertEquals("no third rung asked while two are queued", zoomsNow, f.zooms.size)
        assertEquals(2, f.measureOutstanding)
        // Stale and duplicate zoom answers do nothing.
        val logs = f.logs.size
        f.run.onZoom(1, 2.6, 2.6, 2.6, null)
        f.run.onZoom(1, 1.0, 1.0, 1.0, null)
        assertEquals(zoomsNow, f.zooms.size); assertEquals(logs, f.logs.size)
        // The worker answers one: the next rung is asked.
        f.pendingWorker.removeFirst()()
        f.advance(1)
        assertEquals(zoomsNow + 1, f.zooms.size)
        // Let it all run.
        while (f.outcomes.isEmpty() && f.now < 300_000) { while (f.pendingWorker.isNotEmpty()) f.pendingWorker.removeFirst()(); f.advance(50) }
        assertEquals(Kind.DONE, f.outcomes.single().kind)
        assertEquals(1, f.maxZoomOutstanding)
        assertTrue("at most ${ZoomProbeRun.MAX_QUEUED} rungs queued (+ none during the restore): ${f.maxMeasureOutstanding}", f.maxMeasureOutstanding <= ZoomProbeRun.MAX_QUEUED)
        // A refused rung ends the ladder there; a silent one is skipped.
        val g = fake { refuseAbove = 1.95; silent = setOf(1.4) }
        g.run.start(); g.until { g.outcomes.isNotEmpty() }
        val plan = ZoomProbe.plan(quad(W / 2.0, H / 2.0, H1 + 3), W, H, 10.0, 3) as ZoomProbe.Plan
        assertEquals("the top was refused right after 1×: the ladder ends", listOf(1.0, 1.0, plan.top), g.zooms.take(3))
        assertTrue(g.logs.any { it.contains("refused (zoom ratio out of range) — the ladder ends here") })
        assertEquals("no refocus above 1× (none answered): straight to the restore", 1.9, g.zooms.last(), 0.0)
        val h = fake { silent = setOf(1.4) }
        h.run.start(); h.until { h.outcomes.isNotEmpty() }
        assertTrue(h.logs.any { it.contains("1.40× · no answer in 2000 ms — skipped") })
        assertEquals(Kind.DONE, h.outcomes.single().kind)
        assertTrue("every rung after the silent one was still asked", h.zooms.containsAll(plan.ladder))
    }

    /**
     * The scan screen numbers the session's runs (1.1.17): an attempt that put its run number in
     * the `probe` log (START, a focus failure after it, CANCELLED, STOPPED, a result) used that
     * number — the next one is the next number; a refusal before any of that (BEGIN, CHECK, no
     * card, the plan's own refusal — "probe refused — …", no number) did not, and the owner's
     * next Run probe is the same run again.
     */
    @Test
    fun onlyAnAttemptThatLoggedItsRunNumberUsedIt() {
        fun outcome(f: Fake, act: Fake.() -> Unit = {}): ZoomProbeRun.Outcome { f.run.start(); f.act(); f.until { f.outcomes.isNotEmpty() }; return f.outcomes.single() }
        fun numberedInLog(f: Fake) = f.logs.any { it.startsWith("probe run 1 ") || it.startsWith("probe run 1:") }
        val cases = listOf(
            "begin refused" to fake { beginRefusal = "The zoom is still settling — try again in a second" },
            "check refused" to fake { checkRefusal = "Let the card scan first (wait for the ✓), then Run probe" },
            "no card" to fake { noCard = true },
            "off-centre" to fake { bx = 0.75; by = 0.70 },
            "no focus" to fake { focusOk = false },
            "done" to fake(),
        )
        for ((what, f) in cases) {
            val o = outcome(f)
            assertEquals("$what: ${f.logs}", numberedInLog(f), o.numbered)
        }
        assertEquals(listOf(false, false, false, false, true, true), cases.map { it.second.outcomes.single().numbered })
        val c = fake(); c.run.start(); c.advance(5); c.run.cancel("Back"); c.until { c.outcomes.isNotEmpty() }
        assertTrue("a cancel logged its number: ${c.logs}", numberedInLog(c) && c.outcomes.single().numbered)
        val s = fake(); s.run.start(); s.advance(5); s.run.aborted("the scan screen stopped")
        assertTrue("a stop logged its number: ${s.logs}", numberedInLog(s) && s.outcomes.single().numbered)
    }

    @Test
    fun aCancelAlwaysGoesThroughTheVerifiedRestore() {
        for (cancelAfterRung in listOf(1, 5, 16)) {                  // early, mid-ladder, at the refocus (15 rungs + REFOCUS)
            val f = fake()
            f.run.start()
            f.until { f.calls.count { it == "measure START" || it == "measure RUNG" || it == "measure REFOCUS" } >= cancelAfterRung }
            f.run.cancel("Cancel probe")
            f.run.cancel("again")                                        // a second cancel is ignored
            f.until { f.outcomes.isNotEmpty() }
            val after = f.calls.drop(f.calls.lastIndexOf("ae off") - 1)
            assertEquals("$cancelAfterRung", listOf("zoom 1.90", "ae off", "frames", "measure RESTORE", "end true", "finished CANCELLED"), after)
            assertEquals(Kind.CANCELLED, f.outcomes.single().kind)
            assertTrue(f.logs.any { it.startsWith("probe run 1 CANCELLED at ") && it.endsWith(" — Cancel probe") })
            assertFalse(f.aeOn)
        }
        // Before the AF pass: nothing to verify — back to 1.90×, the probe ends with no rebase.
        val g = fake()
        g.run.start(); g.advance(5)                                       // still checking
        g.run.cancel("Back")
        g.until { g.outcomes.isNotEmpty() }
        assertEquals(listOf("begin", "check", "zoom 1.90", "end false", "finished CANCELLED"), g.calls)
    }

    @Test
    fun aCoordinatorAbortNeverEndsOrRebases() {
        for (abortAt in listOf("ladder", "restore")) {
            val f = fake { if (abortAt == "restore") manual = setOf(Role.START, Role.RUNG, Role.RESTORE) }
            f.run.start()
            if (abortAt == "ladder") f.until { f.calls.count { it == "measure RUNG" } >= 3 }
            else {
                f.until(30_000) { f.pendingWorker.isNotEmpty() && f.calls.contains("measure START") }
                f.run.cancel("Stop")                                     // → the restore; its frames wait on the worker
                f.until(30_000) { f.calls.contains("measure RESTORE") }
            }
            val n = f.calls.size
            f.run.aborted("the scan screen stopped")
            // Everything still in flight comes back late: all ignored.
            while (f.pendingWorker.isNotEmpty()) f.pendingWorker.removeFirst()()
            f.run.onZoom(1, 1.9, 1.9, 1.9, null)
            f.advance(200_000)
            val after = f.calls.drop(n)
            // In the ladder AE is still locked for the probe: unlocked. In the restore it already was.
            assertEquals("$abortAt: $after", if (abortAt == "ladder") listOf("ae off", "finished STOPPED") else listOf("finished STOPPED"), after)
            assertFalse(f.calls.any { it.startsWith("end") })
            assertEquals(1, f.outcomes.size)
            assertTrue(f.logs.last(), f.logs.last().startsWith("probe run 1 STOPPED at ${if (abortAt == "ladder") "rung " else "restoring"}") &&
                f.logs.last().endsWith("— the scan screen stopped (nothing filed; the scanned scene kept as before)"))
            assertEquals("Zoom probe stopped — the scan screen stopped. Nothing was filed; Run again when ready", f.outcomes.single().words)
        }
    }

    @Test
    fun rebaseOnlyWhenTheRestoreMatches() {
        fun endOf(setup: Fake.() -> Unit): String {
            val f = fake(setup = setup)
            f.run.start(); f.until { f.outcomes.isNotEmpty() }
            return f.calls.single { it.startsWith("end") }
        }
        assertEquals("end true", endOf { })
        assertEquals("another card put down meanwhile", "end false", endOf { restoreCard = 2L })
        assertEquals("the card was nudged 2 %", "end false", endOf { restoreBx = 0.52 })
        val f = fake()
        f.run.start()
        f.until { f.calls.contains("ae off") }                       // the restore is back at 1.90× (1.90 is a rung too)
        f.framesNever = true                                         // the restore gets no frames
        f.until { f.outcomes.isNotEmpty() }
        assertEquals(listOf("end false"), f.calls.filter { it.startsWith("end") })
        assertTrue(f.logs.any { it == "restore at 1.90×: no frames to check — the scanned scene is kept; a swapped card will scan" })
        // The second run on the same card as run 1 is kept but marked.
        val again = fake(listOf(ZoomProbeRun.Earlier(1, thumb(1L))), runNo = 2)
        again.run.start(); again.until { again.outcomes.isNotEmpty() }
        assertEquals(1, again.outcomes.single().result!!.sameCardAs)
        val other = fake(listOf(ZoomProbeRun.Earlier(1, thumb(1L))), runNo = 2) { card = 2L; restoreCard = 2L }
        other.run.start(); other.until { other.outcomes.isNotEmpty() }
        assertEquals(null, other.outcomes.single().result!!.sameCardAs)
    }

    @Test
    fun theBudgetStopsAStuckProbe() {
        val f = fake { stuck = setOf(Role.RUNG, Role.RESTORE) }
        f.run.start()
        f.until(150_000) { f.outcomes.isNotEmpty() }
        assertEquals(Kind.CANCELLED, f.outcomes.single().kind)
        assertTrue(f.logs.any { it.startsWith("probe run 1: the 90 s budget ran out at rung ") })
        assertTrue("the restore waited ${ZoomProbeRun.MEASURE_GUARD_MS} ms for the stuck worker, then gave up (no rebase)",
            f.calls.filter { it.startsWith("end") } == listOf("end false"))
        assertTrue(f.now in ZoomProbeRun.BUDGET_MS..ZoomProbeRun.BUDGET_MS + 15_000)
        assertEquals("The zoom probe ran past 90 s — stopped; nothing filed. Run again", f.outcomes.single().words)
    }

    /**
     * Review of 08a77d1: a cancel (or the budget) while an AF pass is still running ended
     * the probe before the pass finished — in FOCUSING not even verified (the "lens may have
     * moved" mark came with the pass's ANSWER), in REFOCUS rebased before the lens settled.
     * The pass is waited for (≤ its guard), then the VERIFIED restore: its frames, and any
     * rebase, come after the lens has settled.
     */
    @Test
    fun aStopDuringAnAfPassWaitsForItThenVerifies() {
        val expected = listOf("zoom 1.90", "ae off", "frames", "measure RESTORE", "end true")
        // A cancel during the 1× pass (FOCUSING), the answer 2 s away.
        val a = fake { focusMs = 2_000 }
        a.run.start()
        a.until { a.calls.contains("focus") }
        a.run.cancel("Cancel probe")
        a.until { a.outcomes.isNotEmpty() }
        assertEquals(expected + "finished CANCELLED", a.calls.drop(a.index("focus") + 1))
        assertTrue("the restore starts after the pass answered", a.focusAnsweredAt.single() <= a.index("zoom 1.90"))
        assertEquals(Kind.CANCELLED, a.outcomes.single().kind)
        assertFalse(a.logs.any { it.contains("no focus pass ran") })
        // A cancel during the refocus check's pass (3 s).
        val b = fake { refocusMs = 3_000 }
        b.run.start()
        b.until { b.calls.count { it == "focus" } == 2 }
        val at = b.calls.lastIndexOf("focus")
        b.run.cancel("Cancel probe")
        b.until { b.outcomes.isNotEmpty() }
        assertEquals(expected + "finished CANCELLED", b.calls.drop(at + 1))
        assertTrue("the restore's frames come after the lens settled", b.focusAnsweredAt[1] <= b.index("measure RESTORE"))
        assertFalse("the refocus frames are not taken after a cancel", b.calls.contains("measure REFOCUS"))
        // The budget running out during the 1× pass: the same.
        val c = fake { manual = setOf(Role.LOCATE) }
        c.run.start()
        c.until(30_000) { c.pendingWorker.isNotEmpty() }
        c.advance(ZoomProbeRun.BUDGET_MS - 300 - c.now)
        c.pendingWorker.removeFirst()()                              // LOCATE answers: the AF pass goes out 300 ms before the budget
        c.until { c.outcomes.isNotEmpty() }
        assertTrue(c.logs.any { it.startsWith("probe run 1: the 90 s budget ran out at focusing") })
        assertEquals(expected + "finished CANCELLED", c.calls.drop(c.index("focus") + 1))
        assertTrue(c.focusAnsweredAt.single() <= c.index("zoom 1.90"))
    }

    /**
     * The REAL coordinator wired to the REAL runner through one main-thread FIFO, as the
     * CameraController glue will be: the coordinator's probeAt / probeEnded and the worker's
     * answers are all posted.
     */
    private inner class Wired : ZoomCoordinator.Port, ZoomProbeRun.Port {
        var now = 0L
        private var seq = 0
        private val timers = PriorityQueue<Triple<Long, Int, () -> Unit>>(compareBy<Triple<Long, Int, () -> Unit>>({ it.first }, { it.second }))
        val coord = ZoomCoordinator(this)
        lateinit var run: ZoomProbeRun
        var cameraZ = 1.0
        val calls = mutableListOf<String>()
        val outcomes = mutableListOf<ZoomProbeRun.Outcome>()
        var restoreDeliver: (() -> Unit)? = null
        // The camera side.
        override fun request(gen: Int, ratio: Double) { later(150) { cameraZ = ratio; coord.onApplied(gen, ratio) } }
        override fun closeGate(why: String) { calls += "close" }
        override fun map(ratio: Double, view: RoiFrac?, settle: Boolean, why: String) { calls += "map %.2f".format(ratio) }
        override fun drawingReady(ratio: Double) {}
        override fun readBack(): Double? = null
        override fun probeAt(gen: Int, asked: Double, camera: Double, readBack: Double?, error: String?) {
            later(0) { run.onZoom(gen, asked, camera, readBack, error) }
        }
        override fun probeEnded(why: String) { later(0) { run.aborted(why) } }
        override fun probeRebase(why: String) { calls += "REBASE" }
        // The runner side.
        override fun begin(why: String): String? = if (coord.beginProbe(why)) null else "refused"
        override fun check(answer: (String?) -> Unit) { later(10) { answer(null) } }
        override fun aeLock(on: Boolean) {}
        override fun zoomTo(z: Double) = coord.probeTo(z)
        override fun focusOnCard(u: Double, v: Double, why: String, done: (ZoomProbe.Focus) -> Unit) {
            later(600) { done(ZoomProbe.Focus(true, 4.05, 4.21, 600, "FOCUSED")) }
        }
        override fun frames(done: (ZoomProbeRun.Grab?) -> Unit) { later(200) { done(ZoomProbeRun.Grab(6, 2, 1)) } }
        override fun measure(role: Role, grab: ZoomProbeRun.Grab, rect: IntArray?, done: (ZoomProbeRun.Measured?) -> Unit) {
            val z = cameraZ
            val frames = List(6) {
                ZoomProbe.FrameFacts(quad(W / 2.0, H / 2.0, H1 * z + 3.0), z, true, 4.21, 16_700_000L, 400, "FOCUSED_LOCKED", 120.0, true)
            }
            val th = if (role == Role.LOCATE || role == Role.START || role == Role.RESTORE) thumb(1L) else null
            val deliver = { done(ZoomProbeRun.Measured(frames, W, H, th, 250)) }
            if (role == Role.RESTORE) { calls += "measure RESTORE"; restoreDeliver = deliver } else later(300, deliver)
        }
        // The glue's contract: the coordinator asks the rebase (probeRebase) only while the probe still runs.
        override fun end(verified: Boolean, why: String): Boolean = coord.endProbe(why, rebase = verified)
        override fun later(ms: Long, block: () -> Unit) { timers += Triple(now + ms, seq++, block) }
        override fun now(): Long = now
        override fun log(line: String) {}
        override fun progress(p: ZoomProbeRun.Progress) {}
        override fun finished(o: ZoomProbeRun.Outcome) { outcomes += o; calls += "finished ${o.kind}" }
        override fun facts() = ZoomProbe.Facts("0", W, H, false, 4160, 3120, 2.6, 10.0, coord.target().z)
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val t = timers.peek() ?: break
                if (t.first > until) break
                timers.poll(); now = t.first; t.third()
            }
            now = until
        }
        fun until(limit: Long = 200_000, cond: () -> Boolean) { val end = now + limit; while (!cond() && now < end) advance(10) }
    }

    private fun wired(): Wired {
        val w = Wired()
        w.coord.setEnabled(true); w.coord.setArea(RoiFrac(0.25, 0.25, 0.75, 0.75)); w.coord.cameraBound(2.6, 8.0)
        w.advance(1_000)
        assertEquals(1.9, w.coord.mapped, 0.0)
        w.run = ZoomProbeRun(w, 1, 3)
        w.run.start()
        w.until { w.restoreDeliver != null }
        return w
    }

    /**
     * Review of 08a77d1 (the plan's risk #9): the worker's RESTORE answer already queued when
     * ON_STOP is handled ran FIRST — end(true), a rebase posted after the coordinator had
     * aborted the probe, and the run reported DONE. A card swapped while the owner was away
     * would be rebased into the scene and never scanned. Now the end finds the probe gone:
     * no rebase, the run STOPPED.
     */
    @Test
    fun aLifecycleAbortRacingTheRestoresEndNeverRebases() {
        // The control: no abort — one rebase, the identical mapping, DONE.
        val ok = wired()
        val n = ok.calls.size
        ok.later(0) { ok.restoreDeliver!!() }
        ok.until { ok.outcomes.isNotEmpty() }
        assertEquals(listOf("REBASE", "map 1.90", "finished DONE"), ok.calls.drop(n))
        assertFalse(ok.coord.probing)
        // The race: the worker's answer is queued, then ON_STOP is handled.
        val w = wired()
        val m = w.calls.size
        w.later(0) { w.restoreDeliver!!() }
        w.coord.cameraStopped("the scan screen stopped")
        w.until { w.outcomes.isNotEmpty() }
        w.advance(10_000)
        assertFalse("no rebase after the abort: ${w.calls.drop(m)}", w.calls.contains("REBASE"))
        assertEquals(Kind.STOPPED, w.outcomes.single().kind)
        assertFalse(w.coord.probing)
        assertTrue("the coordinator's abort, then the stop's own close: nothing mapped", w.calls.drop(m).none { it.startsWith("map") })
    }

    /**
     * Review of 08a77d1: the restore's zoom with no answer was verified anyway — frames
     * measured at a zoom the camera never confirmed, and a rebase the coordinator's end
     * (which asks the target again) can void. An unanswered restore is not verified.
     */
    @Test
    fun anUnansweredRestoreIsNotVerified() {
        val f = fake { silent = setOf(1.9); silentMoves = true }
        f.run.start(); f.until { f.outcomes.isNotEmpty() }
        val restore = f.calls.lastIndexOf("zoom 1.90")
        assertFalse("no restore frames: ${f.calls.drop(restore)}", f.calls.drop(restore).contains("measure RESTORE"))
        assertEquals(listOf("end false"), f.calls.filter { it.startsWith("end") })
        assertTrue(f.logs.any { it.startsWith("restore at 1.90×: the camera did not answer the zoom (no answer in 2000 ms) — not verified") })
        assertEquals(Kind.DONE, f.outcomes.single().kind)
    }

    /**
     * Review of 08a77d1: each measured rung's progress came after the NEXT rung's, carrying the
     * previous rung's number and zoom — the pill stepped back and forth and Progress.z (the
     * glue draws the Area there) was stale most of each rung. z is the camera's own zoom; the
     * rung number only moves forward; a measured figure rides the count line.
     */
    @Test
    fun theProgressFollowsTheCamera() {
        val f = fake()
        f.run.start(); f.until { f.outcomes.isNotEmpty() }
        f.progresses.forEachIndexed { i, p ->
            if (p.z != null) assertEquals("progress $i '${p.status}'", f.progressCam[i], p.z!!, 0.0)
        }
        val steps = f.progresses.mapNotNull { Regex("Zoom probe (\\d+)/").find(it.status)?.groupValues?.get(1)?.toInt() }
        assertTrue(steps.toString(), steps.size >= 15)
        assertEquals("the rung number never steps back: $steps", steps.sorted(), steps)
        assertTrue(f.progresses.joinToString("\n") { it.count }, f.progresses.any { Regex("rung \\d+/15 · [0-9.]+× · card \\d+ px at [0-9.]+×").matches(it.count) })
    }

    @Test
    fun theLogStaysUnder30LinesFor18Rungs() {
        // A card 450 px long: the ladder runs 1.00→3.00× — 16 rungs + 3 repeats.
        val f = fake { h1 = 447.0 }
        f.run.start(); f.until { f.outcomes.isNotEmpty() }
        val plan = ZoomProbe.plan(quad(W / 2.0, H / 2.0, 450.0), W, H, 10.0, 3) as ZoomProbe.Plan
        assertEquals(19, plan.order.size)
        assertEquals(Kind.DONE, f.outcomes.single().kind)
        assertTrue(f.outcomes.single().result!!.valid)
        assertTrue("${f.logs.size} lines:\n" + f.logs.joinToString("\n"), f.logs.size <= 30)
        assertEquals("START + 19 rungs + refocus + restore + RESULT", 23, f.logs.size)
    }
}
