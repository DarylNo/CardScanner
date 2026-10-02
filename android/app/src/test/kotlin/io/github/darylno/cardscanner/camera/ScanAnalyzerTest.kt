package io.github.darylno.cardscanner.camera

import io.github.darylno.cardscanner.core.AutoScanner
import io.github.darylno.cardscanner.core.CardOutline
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.Gray
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.RoiPx
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Drives the analysis loop with synthetic frames (a flat tray, then a bright
 * card) on a same-thread executor: commands run inline, exactly as they would
 * interleave with frames on the real single analysis thread.
 */
class ScanAnalyzerTest {
    private val w = 352
    private val h = 264          // 4:3 → detection sample 176×132
    private val tray = FakePlanes.nv21(w, h, { _, _ -> 100 })
    private val card = FakePlanes.nv21(w, h, { x, y -> if (x in 120..231 && y in 60..215) 230 else 100 })

    private class Recorder : ScanAnalyzer.Sink {
        val updates = mutableListOf<DetectionUpdate>()
        val bursts = mutableListOf<CaptureBurst>()
        val errors = mutableListOf<Throwable>()
        var focusRequests = 0
        override fun onFocusRequest() { focusRequests++ }
        override fun onDetection(update: DetectionUpdate) { updates += update }
        override fun onCaptureRequest(request: CaptureBurst) { bursts += request }
        override fun onAnalyzerError(error: Throwable) { errors += error }
    }

    private val direct = Executor { it.run() }
    private val rec = Recorder()
    private val analyzer = ScanAnalyzer(direct, rec)
    private var ts = 1_000_000_000L

    /** One frame every [stepMs] (a 30 fps stream gated to ~10 Hz looks like 100 ms). */
    private fun feed(nv21: ByteArray, frames: Int, stepMs: Long = 100, rotation: Int = 0) {
        repeat(frames) {
            analyzer.process(FakePlanes(nv21, w, h, yRowStride = w + 32, uvRowStride = w + 32, uvPixelStride = 2), rotation, ts)
            ts += stepMs * 1_000_000L
        }
    }

    @Test fun aSnapshotIsTheNextFrameAndOnlyOnce() {
        val got = mutableListOf<io.github.darylno.cardscanner.core.Nv21Frame>()
        analyzer.requestSnapshot { got += it }
        feed(card, 1, rotation = 90)
        feed(tray, 3)
        assertEquals(1, got.size)                         // one request, one frame
        val f = got[0]
        assertEquals(w, f.width); assertEquals(h, f.height); assertEquals(90, f.rotation)
        assertEquals(230, f.data[60 * w + 150].toInt() and 0xff)     // the card's frame, not a later tray
    }

    @Test fun framesCloserThanTheGateAreSkipped() {
        feed(tray, 10, stepMs = 33)
        // 33 ms frames through a 90 ms gate → every 3rd frame (0, 99, 198, 297 ms) is processed;
        // the detector samples at ≥190 ms → frames 0, 6 (198 ms) → 2 ticks.
        assertEquals(2, rec.updates.size)
        assertTrue(rec.updates.all { it.event is AutoScanner.Event.Learning })
    }

    @Test fun triggerHandsOffTheLastThreeFramesAndCompletesTheCapture() {
        feed(tray, 12)                           // learn the empty tray (ticks every 200 ms)
        assertTrue(rec.updates.any { (it.event as? AutoScanner.Event.Learning)?.learned == true })
        assertTrue(rec.bursts.isEmpty())
        feed(card, 12)
        assertEquals(1, rec.bursts.size)
        val b = rec.bursts[0]
        assertEquals(CaptureTrigger.AUTO, b.trigger)
        assertEquals(ScanMode.MOUNT, b.mode)
        assertEquals(3, b.frames.size)
        assertNotNull(b.scene); assertNotNull(b.box)
        // Consecutive processed frames ending at the trigger frame, all of the card.
        val t = b.frames.map { it.timestampNs }
        assertEquals(listOf(100_000_000L, 100_000_000L), t.zipWithNext { a, c -> c - a })
        val trig = rec.updates.indexOfFirst { it.event is AutoScanner.Event.Trigger }
        assertTrue(trig >= 0)
        for (f in b.frames) assertArrayEquals(card, f.data)
        // captureDone() ran immediately: the next tick is already awaiting the next card.
        val after = rec.updates.drop(trig + 1)
        assertTrue(after.isNotEmpty())
        assertTrue(after.all { it.event is AutoScanner.Event.AwaitingNext })
        // Removing the card → NextCard; no second capture of the same card.
        feed(tray, 4)
        assertTrue(rec.updates.any { it.event is AutoScanner.Event.NextCard })
        assertEquals(1, rec.bursts.size)
        assertTrue(rec.errors.isEmpty())
    }

    /** First card of a session: hold the capture until focus is done, then use post-focus frames only. */
    @Test fun firstCardWaitsForFocusThenUsesFramesTakenAfterIt() {
        var due = true
        analyzer.focusFirst = { due }
        feed(tray, 12)
        feed(card, 12)                                   // trigger happens in here
        assertEquals(1, rec.focusRequests)
        assertTrue("held until focus answers", rec.bursts.isEmpty())
        val focusAt = ts
        due = false                                      // the controller clears it once focused
        analyzer.focusDone()
        feed(card, 2)
        assertTrue("needs three frames newer than the focus", rec.bursts.isEmpty())
        feed(card, 1)
        assertEquals(1, rec.bursts.size)
        val b = rec.bursts[0]
        assertEquals(CaptureTrigger.AUTO, b.trigger)
        assertEquals(3, b.frames.size)
        assertTrue("every frame is from after the focus", b.frames.all { it.timestampNs >= focusAt })
        // The next card shoots instantly (no second focus pass).
        feed(tray, 4); feed(card, 12)
        assertEquals(1, rec.focusRequests)
        assertEquals(2, rec.bursts.size)
    }

    /**
     * The refocus changes how the card looks (blur → sharp, which crosses
     * SWAP_FRAC). The "what was scanned" scene must be the post-focus one the
     * burst shot — the trigger-time scene made the still-present card read as
     * a swap, and it was scanned again with nothing placed (2026-09-28).
     */
    @Test fun aRefocusedCardIsNotScannedAgain() {
        val sharp = FakePlanes.nv21(w, h, { x, y ->
            if (x in 120..231 && y in 60..215) (if (((x / 4) + (y / 4)) % 2 == 0) 255 else 150) else 100 })
        var due = true
        analyzer.focusFirst = { due }
        feed(tray, 12)
        feed(card, 12)                                   // triggers on the pre-focus look
        assertEquals(1, rec.focusRequests)
        due = false
        analyzer.focusDone()
        feed(sharp, 3)                                   // the lens moved: the card now looks different
        assertEquals(1, rec.bursts.size)
        assertEquals("scene = what the burst shot", rec.bursts[0].frames.last().timestampNs, ts - 100_000_000L)
        feed(sharp, 20)                                  // nothing placed, card still there
        assertEquals("no phantom re-scan", 1, rec.bursts.size)
        assertTrue(rec.updates.none { (it.event as? AutoScanner.Event.NextCard)?.removed == false })
    }

    @Test fun aCameraRebindDuringTheFocusHoldDoesNotKillAuto() {
        analyzer.focusFirst = { true }
        feed(tray, 12)
        feed(card, 12)
        assertEquals(1, rec.focusRequests)
        analyzer.cameraRestarted()                       // e.g. resolution change mid-hold
        analyzer.focusFirst = { false }
        feed(tray, 12)                                   // re-learn the tray
        feed(card, 12)
        assertEquals("auto still scans after the rebind", 1, rec.bursts.size)
    }

    @Test fun aFocusThatNeverAnswersStillShootsAfterTheWait() {
        analyzer.focusFirst = { true }
        feed(tray, 12)
        feed(card, 12)
        assertEquals(1, rec.focusRequests)
        assertTrue(rec.bursts.isEmpty())
        feed(card, (ScanAnalyzer.FOCUS_WAIT_NS / 100_000_000L).toInt() + 1)   // no focusDone()
        assertEquals(1, rec.bursts.size)
        assertEquals(3, rec.bursts[0].frames.size)
    }

    @Test fun roiIsCarriedIntoTheBurstAndDetectionDims() {
        val roi = RoiFrac(0.25, 0.1, 0.75, 0.9)
        analyzer.setRoi(roi)
        feed(tray, 12)
        feed(card, 12)
        assertEquals(1, rec.bursts.size)
        assertEquals(roi, rec.bursts[0].cropRoi)
        val u = rec.updates.last()
        assertEquals(176, u.grayW)
        assertEquals(Math.round(176.0 * (0.8 * h) / (0.5 * w)).toInt(), u.grayH)
        assertEquals(w, u.uprightW); assertEquals(h, u.uprightH)
    }

    @Test fun handheldManualScanUsesFreshFramesCroppedToTheGuide() {
        analyzer.setMode(ScanMode.HANDHELD)
        feed(tray, 5)
        assertTrue("handheld never runs the tray detector", rec.updates.isEmpty())
        analyzer.manualScan()
        val tap = ts
        feed(card, 4)
        assertTrue("waits for 3 frames after the tap's jolt settles", rec.bursts.isEmpty())
        feed(card, 1)
        assertEquals(1, rec.bursts.size)
        val b = rec.bursts[0]
        assertEquals(CaptureTrigger.MANUAL, b.trigger)
        assertEquals(ScanMode.HANDHELD, b.mode)
        assertEquals(HandheldGuide.frac(b.uprightW, b.uprightH), b.cropRoi)
        assertEquals(3, b.frames.size)
        assertTrue("no frame from the press itself",
            b.frames.all { it.timestampNs > tap + ScanAnalyzer.MANUAL_SETTLE_NS })
        // A second tap is a new capture, not swallowed.
        analyzer.manualScan()
        feed(card, 5)
        assertEquals(2, rec.bursts.size)
    }

    @Test fun mountManualScanWithAutoOffWaitsForFreshFrames() {
        analyzer.setAuto(false)
        feed(tray, 3)
        ts += 5_000_000_000L                 // long pause: ring frames are now stale
        analyzer.manualScan()
        feed(card, 1)
        assertTrue("stale frames must not be used", rec.bursts.isEmpty())
        feed(card, 3)
        assertTrue("and not the tap's own frames", rec.bursts.isEmpty())
        feed(card, 1)
        assertEquals(1, rec.bursts.size)
        val b = rec.bursts[0]
        assertEquals(CaptureTrigger.MANUAL, b.trigger)
        assertEquals(3, b.frames.size)
        for (f in b.frames) assertArrayEquals(card, f.data)
    }

    @Test fun manualScanFiresWithFewerFramesAtVeryLowFrameRates() {
        analyzer.setMode(ScanMode.HANDHELD)
        analyzer.manualScan()
        feed(card, 3, stepMs = 400)          // 2.5 fps: never 3 frames inside the fresh window
        assertEquals(1, rec.bursts.size)
        assertTrue(rec.bursts[0].frames.size in 1..2)
    }

    @Test fun trayLearningNeverCopiesFrames() {
        feed(tray, 12)
        assertTrue(analyzer.stats(), analyzer.stats().contains("copies 0"))
    }

    @Test fun rotatedFramesReportUprightSize() {
        feed(tray, 1, rotation = 90)
        assertEquals(h, rec.updates.last().uprightW)
        assertEquals(w, rec.updates.last().uprightH)
    }

    // ── the live outline, the watch window and shadow mode (display / log only) ──

    /** A stand-in for CardOutline.find (OpenCV is not loaded here): the bright pixels' bounding box. */
    private val boxFinder: (Gray, Int, Int, RoiFrac?) -> CardOutline.Outline? = { g, fw, fh, roi ->
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (y in 0 until g.h) for (x in 0 until g.w) if (g.px[y * g.w + x] > 165) {
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        if (x1 < 0) null else {
            val sq = floatArrayOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), x0.toFloat(), y1.toFloat())
            val area = roi?.toPixels(fw, fh) ?: RoiPx(0, 0, fw, fh)
            CardOutline.Outline(CardOutline.toFrameFractions(sq, g.w, g.h, area, fw, fh), sq, 0, 0)
        }
    }

    private fun centroid(q: FloatArray) = Pair((q[0] + q[2] + q[4] + q[6]) / 4, (q[1] + q[3] + q[5] + q[7]) / 4)

    private fun feedTo(a: ScanAnalyzer, nv21: ByteArray, frames: Int, stepMs: Long = 100) {
        repeat(frames) {
            a.process(FakePlanes(nv21, w, h, yRowStride = w + 32, uvRowStride = w + 32, uvPixelStride = 2), 0, ts)
            ts += stepMs * 1_000_000L
        }
    }

    /**
     * Critique #3: the outline helper runs AFTER the trigger / capture handling
     * and inside runCatching — a helper that throws must never skip fire() or
     * captureDone() (which would leave the scanner SCANNING until a double-tap).
     */
    @Test fun anOutlineFinderThatThrowsNeverSkipsTheCapture() {
        val rec = Recorder()
        val a = ScanAnalyzer(direct, rec, outlineFinder = { _, _, _, _ -> throw IllegalStateException("outline boom") })
        feedTo(a, tray, 12); feedTo(a, card, 12)
        assertEquals("the capture fired", 1, rec.bursts.size)
        val trig = rec.updates.indexOfFirst { it.event is AutoScanner.Event.Trigger }
        assertTrue(trig >= 0)
        assertNull("no outline, no crash", rec.updates[trig].outline)
        // captureDone() ran: the scanner is awaiting the next card, not stuck SCANNING.
        assertTrue(rec.updates.drop(trig + 1).all { it.event is AutoScanner.Event.AwaitingNext })
        feedTo(a, tray, 4); feedTo(a, card, 12)
        assertEquals("and Auto still scans the next card", 2, rec.bursts.size)
        assertTrue("outline failures are not analyzer errors", rec.errors.isEmpty())
        assertTrue(a.stats(), a.stats().contains("outline errors"))
    }

    @Test fun theLiveOutlineRidesTheDetectionUpdateOnOccupiedTicksOnly() {
        val rec = Recorder()
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder)
        feedTo(a, tray, 12)
        assertTrue("nothing to outline while learning / empty", rec.updates.all { it.outline == null })
        feedTo(a, card, 12)
        val occupied = rec.updates.filter { (it.event as? AutoScanner.Event.Watching)?.occupied == true || it.event is AutoScanner.Event.Trigger }
        assertTrue(occupied.isNotEmpty())
        for (u in occupied) {
            val q = u.outline
            assertNotNull("outline on an occupied tick", q)
            // The card spans x 120..231 of 352 and y 60..215 of 264 — the outline (frame fractions) lands on it.
            val (cx, cy) = centroid(q!!)
            assertEquals(176.0 / 352, cx.toDouble(), 0.02); assertEquals(138.0 / 264, cy.toDouble(), 0.02)
            assertEquals((232 - 120) / 352.0, (q[2] - q[0]).toDouble(), 0.03)
            assertNotNull(u.outlineNanos)
        }
        assertTrue(a.stats(), a.stats().contains("avg outline"))
    }

    /** Owner: "always highlight the card" — the outline also rides the ticks after a capture, while the card still sits there. */
    @Test fun theOutlineStaysUpWhileAwaitingTheNextCard() {
        val rec = Recorder()
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder)
        feedTo(a, tray, 12); feedTo(a, card, 12)
        val waiting = rec.updates.filter { it.event is AutoScanner.Event.AwaitingNext }
        assertTrue(waiting.isNotEmpty())
        for (u in waiting) {
            val q = u.outline
            assertNotNull("outline while awaiting the next card", q)
            val (cx, cy) = centroid(q!!)
            assertEquals(176.0 / 352, cx.toDouble(), 0.02); assertEquals(138.0 / 264, cy.toDouble(), 0.02)
        }
        // The card leaves: nothing to outline, and the tracker starts clean for the next one.
        feedTo(a, tray, 4)
        assertNull(rec.updates.last().outline)
    }

    /**
     * Owner: "average its position and throw away outliers … sometimes it thinks
     * it's a much bigger card": one tick's find of a card 1.6× the size never
     * moves the outline shown, and the Diagnostics line counts it.
     */
    @Test fun aMuchBiggerFindIsDroppedAsAnOutlier() {
        val rec = Recorder()
        var big = false
        val finder: (Gray, Int, Int, RoiFrac?) -> CardOutline.Outline? = { g, fw, fh, roi ->
            val o = boxFinder(g, fw, fh, roi)
            if (o == null || !big) o else {
                val sq = o.sampleQuad
                val cx = (sq[0] + sq[2]) / 2; val cy = (sq[1] + sq[5]) / 2
                val b = FloatArray(8) { i -> if (i % 2 == 0) cx + (sq[i] - cx) * 1.6f else cy + (sq[i] - cy) * 1.6f }
                val area = roi?.toPixels(fw, fh) ?: RoiPx(0, 0, fw, fh)
                CardOutline.Outline(CardOutline.toFrameFractions(b, g.w, g.h, area, fw, fh), b, 0, 0)
            }
        }
        val a = ScanAnalyzer(direct, rec, outlineFinder = finder)
        feedTo(a, tray, 12); feedTo(a, card, 12)
        val steady = rec.updates.last().outline!!
        big = true
        feedTo(a, card, 2)                 // one tick (≥190 ms between samples)
        big = false
        val shown = rec.updates.last().outline!!
        assertArrayEquals("the bigger find did not move the outline", steady, shown, 1e-4f)
        assertTrue(a.stats(), a.stats().contains("1 outlier(s) dropped"))
        feedTo(a, card, 2)
        assertArrayEquals(steady, rec.updates.last().outline!!, 1e-4f)
    }

    @Test fun theWatchWindowIsTheCardShapePaddedAndSnapsToTheCaptureQuad() {
        val rec = Recorder()
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder)
        feedTo(a, tray, 12); feedTo(a, card, 12)
        assertEquals(1, rec.bursts.size)
        val waiting = rec.updates.filter { it.event is AutoScanner.Event.AwaitingNext }
        assertTrue(waiting.isNotEmpty())
        val wq = waiting.last().watch
        assertNotNull("a watch window while awaiting the next card", wq)
        // The card's outline padded 15 % per side: same centre, 1.3× the size.
        val (cx, cy) = centroid(wq!!)
        assertEquals(176.0 / 352, cx.toDouble(), 0.02); assertEquals(138.0 / 264, cy.toDouble(), 0.02)
        assertEquals(1.3 * (232 - 120) / 352.0, (wq[2] - wq[0]).toDouble(), 0.04)
        assertEquals(1.3 * (216 - 60) / 264.0, (wq[5] - wq[3]).toDouble(), 0.04)
        // The capture's exact quad (frame px), 20 px to the right of the live outline: the window follows it.
        val q = floatArrayOf(140f, 60f, 251f, 60f, 251f, 215f, 140f, 215f)
        a.onCaptureResult(rec.bursts[0].id, q, w, h)
        feedTo(a, card, 2)
        val wq2 = rec.updates.last().watch!!
        assertEquals((cx + 20f / 352).toDouble(), centroid(wq2).first.toDouble(), 0.01)
        assertEquals(cy.toDouble(), centroid(wq2).second.toDouble(), 0.01)
    }

    /** Critique #5: an old capture's quad (one pipeline time late) must not overwrite a newer trigger's window. */
    @Test fun aStaleCaptureQuadNeverReplacesANewerTriggersWindow() {
        val rec = Recorder()
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder)
        val card2 = FakePlanes.nv21(w, h, { x, y -> if (x in 40..151 && y in 40..195 && (x / 8 + y / 8) % 2 == 0) 240 else 100 })
        feedTo(a, tray, 12); feedTo(a, card, 12)
        feedTo(a, tray, 4); feedTo(a, card2, 12)
        assertEquals(2, rec.bursts.size)
        val before = rec.updates.last().watch!!
        a.onCaptureResult(rec.bursts[0].id, floatArrayOf(300f, 200f, 340f, 200f, 340f, 250f, 300f, 250f), w, h)
        feedTo(a, card2, 2)
        assertArrayEquals("burst 1's quad arrived after burst 2 fired — ignored", before, rec.updates.last().watch!!, 1e-6f)
        a.onCaptureResult(rec.bursts[1].id, floatArrayOf(300f, 200f, 340f, 200f, 340f, 250f, 300f, 250f), w, h)
        feedTo(a, card2, 2)
        assertFalse("the current burst's quad is taken", before.contentEquals(rec.updates.last().watch!!))
    }

    /**
     * SHADOW MODE is logging only: with it on (and an outline) and off (no
     * outline), the SAME frames give the SAME trigger / awaiting / next-card
     * decisions and captures. The log shows it ran.
     */
    @Test fun shadowModeNeverChangesTheScannersDecisions() {
        val recA = Recorder(); val recB = Recorder()
        val log = DebugLog(100, clock = { 0L })
        val a = ScanAnalyzer(direct, recA, outlineFinder = boxFinder, shadowMode = true, log = log)
        val b = ScanAnalyzer(direct, recB, outlineFinder = { _, _, _, _ -> null }, shadowMode = false, log = DebugLog(10))
        val hand = FakePlanes.nv21(w, h, { x, y -> if (x in 150..189 && y in 100..139) 30 else if (x in 120..231 && y in 60..215) 230 else 100 })
        val card2 = FakePlanes.nv21(w, h, { x, y -> if (x in 120..231 && y in 60..215) (if ((x / 6 + y / 6) % 2 == 0) 250 else 120) else 100 })
        val t0 = ts
        for (an in listOf(a, b)) {
            ts = t0
            feedTo(an, tray, 12); feedTo(an, card, 12)      // learn, trigger
            feedTo(an, card, 6)                             // waiting on the scanned card
            feedTo(an, hand, 3); feedTo(an, card, 6)        // a hand over the card, then gone
            feedTo(an, card2, 12)                           // a different card in its place (swap)
            feedTo(an, tray, 4); feedTo(an, card, 12)       // removed, next card
            feedTo(an, tray, 4)
        }
        fun describe(e: AutoScanner.Event) = when (e) {
            is AutoScanner.Event.Watching -> "W${if (e.occupied) 1 else 0}/${e.stableCount}"
            is AutoScanner.Event.NextCard -> "N${if (e.removed) "r" else "s"}"
            else -> e.javaClass.simpleName
        }
        assertEquals(recA.updates.map { describe(it.event) }, recB.updates.map { describe(it.event) })
        assertEquals(recA.bursts.map { it.id }, recB.bursts.map { it.id })
        assertEquals(recA.bursts.map { it.frames.map { f -> f.timestampNs } }, recB.bursts.map { it.frames.map { f -> f.timestampNs } })
        assertTrue(recA.errors.isEmpty() && recB.errors.isEmpty())
        val shadow = log.all().filter { it.tag == "shadow" }
        println(log.all().joinToString("\n") { it.line() })
        println("stats A: ${a.stats()}")
        assertTrue("shadow mode logged its waits", shadow.any { it.msg.startsWith("wait over") })
        assertTrue("…but nothing per tick (events only)", shadow.size < recA.updates.count { it.event is AutoScanner.Event.AwaitingNext })
        assertTrue(a.stats(), a.stats().contains("texture"))
    }

    /**
     * A shutter scan fires with NO box (the scanner is SCANNING / Auto off, so
     * its ticks are Idle): its window is the capture quad — not "ignored" with a
     * line claiming the window had moved on (every Tap-to-scan capture used to
     * read as a stale-quad event, and a Tray shutter scan waited with no
     * card-shaped window).
     */
    @Test fun aShutterScanGetsItsWindowFromTheCaptureQuad() {
        val rec = Recorder()
        val log = DebugLog(100, clock = { 0L })
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder, log = log)
        feedTo(a, tray, 12)
        a.manualScan()
        feedTo(a, card, 8)
        assertEquals(1, rec.bursts.size)
        assertNull("no box at a shutter trigger", rec.bursts[0].box)
        feedTo(a, card, 2)
        assertNull("no window before the capture answers", rec.updates.last().watch)
        a.onCaptureResult(rec.bursts[0].id, floatArrayOf(120f, 60f, 231f, 60f, 231f, 215f, 120f, 215f), w, h)
        feedTo(a, card, 2)
        val wq = rec.updates.last().watch
        assertNotNull("the capture quad became the window", wq)
        assertEquals(176.0 / 352, centroid(wq!!).first.toDouble(), 0.02)
        assertEquals(1.3 * (232 - 120) / 352.0, (wq[2] - wq[0]).toDouble(), 0.04)
        val lines = log.all().filter { it.tag == "outline" }.map { it.msg }
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines[0], lines[0].contains("card 155 px tall") && lines[0].contains("window from the capture quad"))
        assertFalse(lines[0], lines[0].contains("ignored"))
        // Tap to scan (Auto off): the same path, the same line.
        a.setAuto(false)
        feedTo(a, tray, 4); feedTo(a, card, 4)
        a.manualScan()
        feedTo(a, card, 8)
        assertEquals(2, rec.bursts.size)
        a.onCaptureResult(rec.bursts[1].id, floatArrayOf(120f, 60f, 231f, 60f, 231f, 215f, 120f, 215f), w, h)
        val tap = log.all().filter { it.tag == "outline" }.map { it.msg }
        assertEquals(tap.toString(), 2, tap.size)
        assertTrue(tap[1], tap[1].contains("window from the capture quad") && !tap[1].contains("ignored"))
        // A reset after the burst fired: its quad has no home — said so, not "a newer burst".
        a.manualScan()
        feedTo(a, card, 8)
        assertEquals(3, rec.bursts.size)
        a.reset()
        a.onCaptureResult(rec.bursts[2].id, floatArrayOf(120f, 60f, 231f, 60f, 231f, 215f, 120f, 215f), w, h)
        val cleared = log.all().filter { it.tag == "outline" }.map { it.msg }.last()
        assertTrue(cleared, cleared.contains("cleared") && cleared.contains("ignored") && cleared.contains("card 155 px tall"))
        feedTo(a, card, 2)
        assertNull(rec.updates.last().watch)
    }

    /**
     * Shadow-mode event lines are capped per wait: a signal flickering about the
     * threshold through a long idle wait (one line per second) would otherwise
     * push every capture line out of the 3000-line ring in under an hour. The
     * wait's summary carries the count held back.
     */
    @Test fun shadowModeEventLinesAreCappedPerWait() {
        val rec = Recorder()
        val log = DebugLog(200, clock = { 0L })
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder, shadowMode = true, log = log)
        // A textured "hand" over the whole card for ONE tick, then the card again for five:
        // never stable, so never a swap — the wait runs on while the signal crosses 8 each cycle.
        val hand = FakePlanes.nv21(w, h, { x, y -> if (x in 120..231 && y in 60..215) (if ((x / 6 + y / 6) % 2 == 0) 250 else 120) else 100 })
        feedTo(a, tray, 12); feedTo(a, card, 12)
        assertEquals(1, rec.bursts.size)
        val cycles = 2 * ScanAnalyzer.SHADOW_LOG_MAX_PER_WAIT
        repeat(cycles) { feedTo(a, hand, 2); feedTo(a, card, 10) }   // 1.2 s per cycle: outside the 1/s gap
        assertTrue("still one wait", rec.updates.takeLast(cycles * 6).all { it.event is AutoScanner.Event.AwaitingNext })
        feedTo(a, tray, 4)                                            // card removed: the wait ends
        val shadow = log.all().filter { it.tag == "shadow" }.map { it.msg }
        val events = shadow.filter { !it.startsWith("wait over") }
        val over = shadow.filter { it.startsWith("wait over") }
        assertTrue(shadow.joinToString("\n"), events.any { it.startsWith("crossed") })
        assertEquals(shadow.joinToString("\n"), ScanAnalyzer.SHADOW_LOG_MAX_PER_WAIT, events.size)
        assertEquals(1, over.size)
        assertTrue(over[0], over[0].contains("${cycles - ScanAnalyzer.SHADOW_LOG_MAX_PER_WAIT} event line(s) held back"))
    }

    @Test fun aLateFocusHoldKeepsTheTriggerTicksOutlineForItsWindow() {
        val rec = Recorder()
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder)
        var due = true
        a.focusFirst = { due }
        feedTo(a, tray, 12); feedTo(a, card, 12)
        assertEquals(1, rec.focusRequests)
        due = false
        a.focusDone()
        feedTo(a, card, 3)
        assertEquals(1, rec.bursts.size)
        feedTo(a, card, 2)
        val wq = rec.updates.last().watch
        assertNotNull("the held trigger's outline became the window", wq)
        assertEquals(176.0 / 352, centroid(wq!!).first.toDouble(), 0.02)
    }
}
