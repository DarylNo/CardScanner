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

        override fun begin(why: String): String? { calls += "begin"; return beginRefusal }
        override fun check(answer: (String?) -> Unit) { calls += "check"; later(10) { answer(checkRefusal) } }
        override fun aeLock(on: Boolean) { calls += "ae ${if (on) "on" else "off"}"; aeOn = on }
        override fun zoomTo(z: Double) {
            calls += "zoom %.2f".format(z)
            zoomOutstanding++; maxZoomOutstanding = max(maxZoomOutstanding, zoomOutstanding)
            if (z in silent) { later(ZoomCoordinator.SETTLE_TIMEOUT_MS) { zoomOutstanding--; run.onZoom(1, z, z, null, ZoomCoordinator.PROBE_NO_ANSWER) }; return }
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
            later(600) { done(ZoomProbe.Focus(focusOk, 4.05, 4.21, 600, if (focusOk) "FOCUSED" else "NOT focused")) }
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
        override fun end(verified: Boolean, why: String) { calls += "end $verified" }
        override fun later(ms: Long, block: () -> Unit) { timers += Triple(now + ms, seq++, block) }
        override fun now(): Long = now
        override fun log(line: String) { logs += line }
        override fun progress(p: ZoomProbeRun.Progress) { progresses += p }
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
            assertTrue(f.logs.last(), f.logs.last().startsWith("probe run 1 STOPPED at ") &&
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
