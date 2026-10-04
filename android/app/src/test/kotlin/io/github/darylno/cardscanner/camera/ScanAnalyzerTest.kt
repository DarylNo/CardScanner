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
        val focusTargets = mutableListOf<FocusRequest>()
        var resyncs = 0
        override fun onFocusRequest(request: FocusRequest) { focusRequests++; focusTargets += request }
        override fun onZoomResync() { resyncs++ }
        override fun onDetection(update: DetectionUpdate) { updates += update }
        override fun onCaptureRequest(request: CaptureBurst) { bursts += request }
        override fun onAnalyzerError(error: Throwable) { errors += error }
    }

    private val direct = Executor { it.run() }
    private val rec = Recorder()
    // The card-shape gate needs an outline finder that sees the synthetic card (OpenCV is not loaded here).
    private val analyzer by lazy { ScanAnalyzer(direct, rec, outlineFinder = boxFinder) }
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

    // ── "is it focusing?" (1.1.11): the hold says what happened, and asks to meter the CARD ──

    /** The focus pass is asked for the TRIGGER's card (its box, in the sample of the Area), and the answered hold is logged with its timing. */
    @Test fun theFocusRequestCarriesTheCardAndTheAnsweredHoldIsLogged() {
        val rec = Recorder()
        val log = DebugLog(200, clock = { 0L })
        val roi = RoiFrac(0.1, 0.1, 0.9, 0.9)
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder, shadowMode = false, log = log)
        a.setRoi(roi)
        var due = true
        a.focusFirst = { due }
        feedTo(a, tray, 12); feedTo(a, card, 12)
        assertEquals(1, rec.focusRequests)
        val req = rec.focusTargets.single()
        val trig = rec.updates.first { it.event is AutoScanner.Event.Trigger }.event as AutoScanner.Event.Trigger
        assertEquals(trig.box, req.box)
        assertEquals(roi, req.roi)
        val u = rec.updates.last()
        assertEquals(u.grayW, req.sampleW); assertEquals(u.grayH, req.sampleH)
        // The point it meters is the card's centre in the frame (the card spans x 120..231, y 60..215 of 352×264).
        val p = io.github.darylno.cardscanner.core.FocusPoint.choose(req.roi, req.box, req.sampleW, req.sampleH)
        assertTrue(p.onCard)
        assertEquals((120 + 232) / 2.0 / w, p.u, 0.03)
        assertEquals((60 + 216) / 2.0 / h, p.v, 0.03)
        due = false
        a.focusDone()
        feedTo(a, card, 3)
        assertEquals(1, rec.bursts.size)
        val focus = log.all().filter { it.tag == "focus" }.map { it.msg }
        assertTrue(focus.joinToString("\n"), focus.any { it.startsWith("first card: Trigger held (≤ 1.5 s)") })
        assertTrue(focus.joinToString("\n"), focus.any {
            it.startsWith("first card: focus answered ") && it.contains("capture #${rec.bursts[0].id} shot from the 3 frames after it")
        })
        assertTrue(focus.none { it.contains("timed out") })
    }

    /** The hold that runs out (no focus answer within FOCUS_WAIT_NS) says so — the silent case of the 1.1.10 report. */
    @Test fun aTimedOutHoldIsLoggedAndALateAnswerReTakesTheScene() {
        val rec = Recorder()
        val log = DebugLog(200, clock = { 0L })
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder, shadowMode = false, log = log)
        a.focusFirst = { true }
        feedTo(a, tray, 12); feedTo(a, card, 12)
        feedTo(a, card, (ScanAnalyzer.FOCUS_WAIT_NS / 100_000_000L).toInt() + 1)   // no focusDone()
        assertEquals(1, rec.bursts.size)
        val focus = { log.all().filter { it.tag == "focus" }.map { it.msg } }
        assertTrue(focus().joinToString("\n"), focus().any {
            it.startsWith("first card: focus hold timed out after 1.5 s — no focus answer; capture #${rec.bursts[0].id} shot from the last frames")
        })
        a.focusDone()                                       // the lens settles later
        assertTrue(focus().joinToString("\n"), focus().any { it.contains("answered after the hold gave up") })
    }

    /** A held capture that a re-learn / new Area / camera restart drops is logged, not silently lost. */
    @Test fun aCancelledHoldIsLogged() {
        val rec = Recorder()
        val log = DebugLog(200, clock = { 0L })
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder, shadowMode = false, log = log)
        a.focusFirst = { true }
        feedTo(a, tray, 12); feedTo(a, card, 12)
        assertEquals(1, rec.focusRequests)
        a.cameraRestarted()
        assertTrue(rec.bursts.isEmpty())
        assertTrue(log.all().any { it.tag == "focus" && it.msg == "first card: focus hold cancelled (camera restarted) — nothing shot" })
        a.reset()                                           // nothing held any more: no second line
        assertEquals(1, log.all().count { it.tag == "focus" && it.msg.contains("cancelled") })
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

    // ── the card-shape gate (owner, 2026-10-02: the tray "trying to scan nothing") ──

    /** Something still in the Area but no card shape: the Trigger is refused, nothing shot, and the scene is adopted as empty after 5 refusals. */
    @Test fun aTriggerWithNoCardShapeIsRefusedAndTheTrayRelearnedAfterFive() {
        val rec = Recorder()
        val log = DebugLog(100, clock = { 0L })
        val a = ScanAnalyzer(direct, rec, outlineFinder = { _, _, _, _ -> null }, log = log)
        feedTo(a, tray, 12)
        feedTo(a, card, 40)                              // a glare patch that just sits there
        assertTrue("nothing shot", rec.bursts.isEmpty())
        val refused = rec.updates.filter { it.event is AutoScanner.Event.Trigger }
        assertTrue("triggers kept coming (back to WATCHING, not AWAIT_NEXT)", refused.size >= 5)
        assertTrue("every one refused", refused.all { it.triggerRefused })
        // Between refusals the scanner is WATCHING again (stillness counted afresh).
        val afterFirst = rec.updates.drop(rec.updates.indexOfFirst { it.triggerRefused } + 1)
        assertTrue(afterFirst.any { it.event is AutoScanner.Event.Watching })
        assertTrue(afterFirst.none { it.event is AutoScanner.Event.AwaitingNext })
        // After RELEARN_AFTER_REFUSALS the view is the empty tray: no longer occupied.
        val last = rec.updates.last().event
        assertTrue("adopted as empty: $last", last is AutoScanner.Event.Watching && !last.occupied)
        val lines = log.all().filter { it.tag == "detect" }.map { it.msg }
        assertEquals("one refusal line per scene", 1, lines.count { it.startsWith("TRIGGER refused") })
        assertEquals(1, lines.count { it.contains("adopted the view as the empty tray") })
        // A flat bright patch: its edges sit outside the box's core, so no printed detail — said so, with the numbers.
        val refusal = lines.first { it.startsWith("TRIGGER refused") }
        assertTrue(refusal, refusal.contains("no printed detail") && refusal.contains("print 0% (need 6%)") && refusal.contains("of the Area"))
        val adopted = lines.first { it.contains("adopted the view") }
        assertTrue(adopted, adopted.contains("last: no printed detail") && adopted.contains("print peaked at 0% over the 5"))
        assertTrue(a.stats(), a.stats().contains("triggers refused (no card shape) 5"))
        assertTrue(rec.errors.isEmpty())
        // A real card placed afterwards (the finder sees it) is still scanned.
    }

    /** A card the finder misses on the trigger tick is scanned on the next ask, not lost. */
    @Test fun aCardTheFinderMissesOnceIsScannedOnTheNextAsk() {
        val rec = Recorder()
        var misses = 3                                  // the 3rd occupied tick is the Trigger (2 steady ticks first)
        val a = ScanAnalyzer(direct, rec, outlineFinder = { g, fw, fh, roi ->
            val o = boxFinder(g, fw, fh, roi)
            if (o != null && misses > 0) { misses--; null } else o
        })
        feedTo(a, tray, 12); feedTo(a, card, 16)
        assertEquals("shot on the next ask", 1, rec.bursts.size)
        assertTrue("the first ask was refused", rec.updates.any { it.triggerRefused })
        assertEquals(0, misses)
    }

    /** The gate is Tray-only: a handheld / shutter scan is never refused. */
    @Test fun theShutterIsNeverGated() {
        val rec = Recorder()
        val a = ScanAnalyzer(direct, rec, outlineFinder = { _, _, _, _ -> null })
        feedTo(a, tray, 12)
        a.setAuto(false)
        a.manualScan()
        feedTo(a, card, 12)
        assertEquals(1, rec.bursts.size)
        assertEquals(CaptureTrigger.MANUAL, rec.bursts[0].trigger)
    }

    /**
     * SHADOW MODE is logging only: with it on and off, the SAME frames give the
     * SAME trigger / awaiting / next-card decisions and captures. The log shows
     * it ran.
     */
    @Test fun shadowModeNeverChangesTheScannersDecisions() {
        val recA = Recorder(); val recB = Recorder()
        val log = DebugLog(100, clock = { 0L })
        val a = ScanAnalyzer(direct, recA, outlineFinder = boxFinder, shadowMode = true, log = log)
        val b = ScanAnalyzer(direct, recB, outlineFinder = boxFinder, shadowMode = false, log = DebugLog(10))
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
        val waits = log.all().filter { it.tag == "detect" && it.msg.startsWith("wait over") }
        println(log.all().joinToString("\n") { it.line() })
        println("stats A: ${a.stats()}")
        assertTrue("every wait is summarised, with the shadow-mode texture maximum", waits.isNotEmpty() && waits.all { it.msg.contains("texture over") })
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
        val events = log.all().filter { it.tag == "shadow" }.map { it.msg }
        val over = log.all().filter { it.tag == "detect" && it.msg.startsWith("wait over") }.map { it.msg }
        assertTrue(events.joinToString("\n"), events.any { it.startsWith("crossed") })
        assertEquals(events.joinToString("\n"), ScanAnalyzer.SHADOW_LOG_MAX_PER_WAIT, events.size)
        assertEquals(1, over.size)
        assertTrue(over[0], over[0].contains("${cycles - ScanAnalyzer.SHADOW_LOG_MAX_PER_WAIT} shadow event line(s) held back"))
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

    // ── the card-shape gate's printed-detail FALLBACK (1.1.11: white-bordered cards the finder missed) ──

    /** A card with printed detail through its middle: a 6-px checker — 3 sample px — over the card. */
    private val printed = FakePlanes.nv21(w, h, { x, y -> if (x in 120..231 && y in 60..215) (if ((x / 6 + y / 6) % 2 == 0) 250 else 120) else 100 })
    /** Another printed card in the same place (a different pattern): a swap. */
    private val printed2 = FakePlanes.nv21(w, h, { x, y -> if (x in 120..231 && y in 60..215) (if ((x / 10 + y / 4) % 2 == 0) 40 else 200) else 100 })

    /** Smooth things that are not cards (frame px; the sample halves them). */
    private fun gaussianFrame(base: Int, amp: Double, sx: Double, sy: Double) = FakePlanes.nv21(w, h, { x, y ->
        (base + amp * Math.exp(-((x - 176.0) * (x - 176.0) / (2 * sx * sx) + (y - 138.0) * (y - 138.0) / (2 * sy * sy)))).toInt().coerceIn(0, 255) })

    /**
     * The owner's 1.1.10 report: white-bordered cards "didn't get picked up as
     * cards". When the outline finder misses a card, its printed detail still
     * fires the capture — and the card is NOT adopted as the empty tray.
     */
    @Test fun aPrintedCardTheFinderMissesIsScannedOnItsPrintedDetail() {
        val rec = Recorder()
        val log = DebugLog(200, clock = { 0L })
        val a = ScanAnalyzer(direct, rec, outlineFinder = { _, _, _, _ -> null }, log = log)
        feedTo(a, tray, 12); feedTo(a, printed, 12)
        assertEquals("captured without an outline", 1, rec.bursts.size)
        assertEquals(CaptureTrigger.AUTO, rec.bursts[0].trigger)
        val trig = rec.updates.indexOfFirst { it.event is AutoScanner.Event.Trigger }
        assertFalse("not refused", rec.updates[trig].triggerRefused)
        assertTrue("awaiting the next card — not adopted as the tray",
            rec.updates.drop(trig + 1).all { it.event is AutoScanner.Event.AwaitingNext })
        val lines = log.all().filter { it.tag == "detect" }.map { it.msg }
        val accepted = lines.single { it.startsWith("TRIGGER accepted without an outline") }
        assertTrue(accepted, accepted.contains("printed detail") && accepted.contains("card-shaped box") && accepted.contains("need 6%"))
        assertTrue(lines.none { it.startsWith("TRIGGER refused") || it.contains("adopted the view") })
        assertTrue(a.stats(), a.stats().contains("accepted on printed detail (no outline) 1"))
        // A swap to another printed card: moved on, scanned — and said why.
        feedTo(a, printed2, 12)
        assertEquals(2, rec.bursts.size)
        val swapped = log.all().map { it.msg }.single { it.startsWith("next card: swapped") }
        assertTrue(swapped, Regex("change \\d+\\.\\d% vs the scanned card \\(needs > 6%\\), still").containsMatchIn(swapped))
        val swapWait = log.all().map { it.msg }.single { it.startsWith("wait over (a different card settled)") }
        val still = Regex("(\\d+\\.\\d)% while a card sat still").find(swapWait)!!.groupValues[1].toDouble()
        assertTrue("the swap tick is in the wait's numbers: $swapWait", still > 6.0)
        // Removed: said so, and the wait is summarised with the swap test's numbers.
        feedTo(a, tray, 4)
        val removed = log.all().map { it.msg }.single { it.startsWith("next card: removed") }
        assertTrue(removed, removed.contains("mask 0.0% (< 1.5%)"))
        assertTrue(rec.errors.isEmpty())
        assertFalse("every detect line was built: ${a.stats()}", a.stats().contains("log errors"))
    }

    /** Glare, a shadow and an exposure change are smooth: refused with the evidence, adopted as the tray after five, nothing shot. */
    @Test fun smoothThingsWithNoOutlineAreStillRefusedAndAdopted() {
        val cases = mapOf(
            "glare" to gaussianFrame(100, 80.0, 40.0, 56.0),              // a card-shaped glare: 1.4, 10 % of the Area
            "shadow" to gaussianFrame(160, -60.0, 60.0, 84.0),
            "exposure" to FakePlanes.nv21(w, h, { _, _ -> 160 }),          // the whole Area +60: a 1.33 box, 100 % of it
        )
        for ((name, frame) in cases) {
            val rec = Recorder()
            val log = DebugLog(200, clock = { 0L })
            val a = ScanAnalyzer(direct, rec, outlineFinder = { _, _, _, _ -> null }, log = log)
            val base = if (name == "shadow") FakePlanes.nv21(w, h, { _, _ -> 160 }) else tray
            feedTo(a, base, 12)
            feedTo(a, frame, 40)
            assertTrue("$name: nothing shot", rec.bursts.isEmpty())
            val refused = rec.updates.filter { it.triggerRefused }
            assertTrue("$name: refused at least five times (${refused.size})", refused.size >= ScanAnalyzer.RELEARN_AFTER_REFUSALS)
            val last = rec.updates.last().event
            assertTrue("$name: adopted as the empty tray: $last", last is AutoScanner.Event.Watching && !last.occupied)
            val lines = log.all().filter { it.tag == "detect" }.map { it.msg }
            val refusal = lines.first { it.startsWith("TRIGGER refused") }
            assertTrue("$name: $refusal", refusal.contains("no printed detail") && refusal.contains("print 0% (need 6%)"))
            assertTrue("$name: $lines", lines.any { it.contains("adopted the view as the empty tray") })
            assertTrue("$name: $lines", lines.none { it.startsWith("TRIGGER accepted") })
            assertTrue(rec.errors.isEmpty())
        }
    }

    /**
     * The owner's other 1.1.10 report: "old bordered cards didn't see the change
     * of cards". Every Tray wait (shadow mode or not) ends with the swap test's
     * own numbers, so a missed swap shows WHY — here a "different" card that
     * changes too little of the Area never crosses SWAP_FRAC.
     */
    @Test fun everyWaitReportsTheSwapTestsNumbers() {
        val rec = Recorder()
        val log = DebugLog(200, clock = { 0L })
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder, shadowMode = false, log = log)
        // Nearly the same card: a 24×24 frame-px patch changed (0.6 % of the Area).
        val nearly = FakePlanes.nv21(w, h, { x, y -> if (x in 150..173 && y in 100..123) 30 else if (x in 120..231 && y in 60..215) 230 else 100 })
        feedTo(a, tray, 12); feedTo(a, card, 12)
        assertEquals(1, rec.bursts.size)
        feedTo(a, nearly, 12)
        assertEquals("too small a change: no swap", 1, rec.bursts.size)
        feedTo(a, tray, 4)
        val waits = log.all().filter { it.tag == "detect" && it.msg.startsWith("wait over") }.map { it.msg }
        assertEquals(waits.toString(), 1, waits.size)
        val m = Regex("change vs the scanned card max (\\d+\\.\\d)%, (\\d+\\.\\d)% while a card sat still \\(a swap needs > 6% while still\\) · still (\\d+)/(\\d+) ticks · mask min (\\d+\\.\\d)%")
            .find(waits[0])
        assertNotNull(waits[0], m)
        val (max, still, stillTicks, ticks, maskMin) = m!!.destructured
        assertTrue(waits[0], waits[0].startsWith("wait over (card removed)"))
        assertEquals("the patch: 12×12 of 176×132 sample px", 0.6, still.toDouble(), 0.15)
        // The tick the card was lifted away is not a swap candidate: it never inflates the maximum.
        assertEquals(waits[0], still.toDouble(), max.toDouble(), 1e-9)
        assertTrue(waits[0], stillTicks.toInt() in 1..ticks.toInt())
        assertEquals("the removal tick is in the mask's minimum", 0.0, maskMin.toDouble(), 1e-9)
        assertFalse("no texture numbers without shadow mode", waits[0].contains("texture"))
        assertTrue(log.all().map { it.msg }.single { it.startsWith("next card: removed") }.isNotEmpty())
    }

    // ── "Zoom to fit the Area" (1.1.11): the settle gate and the zoom's mapping ──

    /** The base Area the zoom tests use (fit 1/(2·0.3) = 1.67: 1.5× keeps it inside the view). */
    private val zBase = RoiFrac(0.25, 0.2, 0.75, 0.8)
    private val zView = io.github.darylno.cardscanner.core.ZoomFit.toView(zBase, 1.5)!!

    /** The same tray + card as the camera shows them at 1.5× (scaled about the frame centre). */
    private val cardZoomed = FakePlanes.nv21(w, h, { x, y ->
        val bx = 176 + (x - 176) / 1.5; val by = 132 + (y - 132) / 1.5
        if (bx in 120.0..231.0 && by in 60.0..215.0) 230 else 100 })

    private fun copies(a: ScanAnalyzer) = Regex("copies (\\d+)").find(a.stats())!!.groupValues[1].toInt()

    /** While the camera's zoom changes nothing is ticked, copied, snapshot or shot; a pending shutter waits for the new zoom. */
    @Test fun whileTheZoomSettlesNothingIsTickedCopiedOrShot() {
        val rec = Recorder()
        val log = DebugLog(300, clock = { 0L })
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder, shadowMode = false, log = log)
        a.setAuto(false)                                            // Tap to scan: the shutter
        a.setRoi(zBase)
        feedTo(a, tray, 6)
        val ticks = rec.updates.size
        val copied = copies(a)
        a.closeZoomGate("test: zoom → 1.50×")
        a.manualScan()                                             // tapped while settling
        val snaps = mutableListOf<Double>()
        a.requestSnapshotAt { _, z -> snaps += z }
        feedTo(a, card, 10)                                         // frames at an unknown zoom
        assertEquals("no ticks", ticks, rec.updates.size)
        assertEquals("no ring copies", copied, copies(a))
        assertTrue("no capture", rec.bursts.isEmpty())
        assertTrue("no snapshot", snaps.isEmpty())
        assertEquals("frames at a closed gate ask the camera once", 1, rec.resyncs)
        a.zoomMapped(1.5, zView, settle = true, why = "test")
        val opened = ts
        feedTo(a, cardZoomed, ScanAnalyzer.SETTLE_DROP_FRAMES)
        assertTrue("the frames that may predate the zoom are dropped too", rec.bursts.isEmpty() && snaps.isEmpty())
        feedTo(a, cardZoomed, 12)
        assertEquals(1, rec.bursts.size)
        val b = rec.bursts[0]
        assertEquals(CaptureTrigger.MANUAL, b.trigger)
        assertEquals(1.5, b.zoom, 0.0)
        assertEquals("the crop is the Area in the zoomed frame", zView, b.cropRoi)
        assertTrue("every frame is from after the zoom settled",
            b.frames.all { it.timestampNs >= opened + ScanAnalyzer.SETTLE_DROP_FRAMES * 100_000_000L })
        assertEquals("the snapshot is the next open frame, with its zoom", listOf(1.5), snaps)
        assertTrue(rec.updates.last().zoom == 1.5 && rec.updates.last().roi == zView)
        val zoomLines = log.all().filter { it.tag == "zoom" }.map { it.msg }
        assertTrue(zoomLines.toString(), zoomLines.any { it.startsWith("analyzer paused") } &&
            zoomLines.any { it.startsWith("analyzer resumed at 1.50×") } && zoomLines.any { it.startsWith("frames now at 1.50×") })
        assertTrue(a.stats(), a.stats().contains("zoom 1.50×"))
    }

    /** A still card across a zoom change is learned into the new empty tray — never scanned a second time. */
    @Test fun aStillCardAcrossAZoomChangeIsNotScannedTwice() {
        val rec = Recorder()
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder, shadowMode = false, log = DebugLog(300))
        a.setRoi(zBase)
        feedTo(a, tray, 12); feedTo(a, card, 12)
        assertEquals("scanned once at 1×", 1, rec.bursts.size)
        a.closeZoomGate("test: the switch turned on")
        feedTo(a, card, 3)
        a.zoomMapped(1.5, zView, settle = true, why = "test")
        val before = rec.updates.size
        feedTo(a, cardZoomed, 60)                                   // the card never moved
        assertEquals("the same still card is not scanned again", 1, rec.bursts.size)
        assertTrue("the new view was learned", rec.updates.drop(before).any { (it.event as? AutoScanner.Event.Learning)?.learned == true })
        assertTrue(rec.updates.drop(before).none { it.event is AutoScanner.Event.Trigger && !it.triggerRefused })
    }

    /** A re-apply (the camera came back at the same zoom and Area) keeps the learned tray: a card scans at once. */
    @Test fun theSameViewAfterTheGateKeepsTheEmptyTray() {
        val rec = Recorder()
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder, shadowMode = false, log = DebugLog(300))
        a.zoomMapped(1.5, zView, settle = false, why = "bind")
        feedTo(a, tray, 12)
        assertTrue(rec.updates.any { (it.event as? AutoScanner.Event.Learning)?.learned == true })
        a.closeZoomGate("test: the scan screen stopped")
        feedTo(a, tray, 4)
        a.zoomMapped(1.5, zView, settle = true, why = "camera re-open")
        val before = rec.updates.size
        feedTo(a, tray, 6)
        assertTrue("no re-learn", rec.updates.drop(before).none { it.event is AutoScanner.Event.Learning })
        feedTo(a, cardZoomed, 12)
        assertEquals(1, rec.bursts.size)
        assertEquals(1.5, rec.bursts[0].zoom, 0.0)
    }

    /** With the zoom off the controller maps (1.0, the Area) without a gate: the exact pre-zoom setRoi path. */
    @Test fun zoomOffIsTheOldPathEventForEvent() {
        val recA = Recorder(); val recB = Recorder()
        val a = ScanAnalyzer(direct, recA, outlineFinder = boxFinder, shadowMode = false, log = DebugLog(10))
        val b = ScanAnalyzer(direct, recB, outlineFinder = boxFinder, shadowMode = false, log = DebugLog(10))
        val roi = RoiFrac(0.1, 0.1, 0.9, 0.9)
        val t0 = ts
        a.setRoi(roi)
        b.zoomMapped(1.0, roi, settle = false, why = "camera bind")
        for (an in listOf(a, b)) {
            ts = t0
            feedTo(an, tray, 12); feedTo(an, card, 12); feedTo(an, tray, 4)
            if (an === b) b.zoomMapped(1.0, roi, settle = false, why = "camera re-open")   // a re-map that changes nothing
            feedTo(an, card, 12); feedTo(an, tray, 4)
        }
        fun describe(e: AutoScanner.Event) = when (e) {
            is AutoScanner.Event.Watching -> "W${if (e.occupied) 1 else 0}/${e.stableCount}"
            is AutoScanner.Event.NextCard -> "N${if (e.removed) "r" else "s"}"
            else -> e.javaClass.simpleName
        }
        assertEquals(recA.updates.map { describe(it.event) }, recB.updates.map { describe(it.event) })
        assertEquals(recA.bursts.map { it.frames.map { f -> f.timestampNs } }, recB.bursts.map { it.frames.map { f -> f.timestampNs } })
        assertEquals(2, recA.bursts.size)
        assertTrue(recB.bursts.all { it.zoom == 1.0 && it.cropRoi == roi })
        assertEquals(0, recB.resyncs)
        assertTrue(b.stats(), !b.stats().contains("zoom"))
    }

    /** A first-card capture held for focus when the gate closes is dropped, and the still card is asked about again — Tray never stays SCANNING. */
    @Test fun aHeldCaptureDroppedByTheGateIsAskedAgain() {
        val rec = Recorder()
        val log = DebugLog(300, clock = { 0L })
        val a = ScanAnalyzer(direct, rec, outlineFinder = boxFinder, shadowMode = false, log = log)
        var due = true
        a.focusFirst = { due }
        feedTo(a, tray, 12); feedTo(a, card, 12)
        assertEquals(1, rec.focusRequests)
        assertTrue(rec.bursts.isEmpty())
        a.closeZoomGate("test: the camera closed")
        feedTo(a, card, 3)
        a.zoomMapped(1.0, null, settle = true, why = "camera re-open")       // same view: no re-learn
        due = false
        feedTo(a, card, 20)
        assertEquals("the card was asked about again and shot", 1, rec.bursts.size)
        assertTrue(log.all().any { it.tag == "focus" && it.msg.contains("hold cancelled (the camera's zoom is changing)") })
    }
}
