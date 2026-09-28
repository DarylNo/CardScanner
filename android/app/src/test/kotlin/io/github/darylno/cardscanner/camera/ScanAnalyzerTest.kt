package io.github.darylno.cardscanner.camera

import io.github.darylno.cardscanner.core.AutoScanner
import io.github.darylno.cardscanner.core.RoiFrac
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

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
        feed(card, 1)
        assertEquals(1, rec.bursts.size)
        val b = rec.bursts[0]
        assertEquals(CaptureTrigger.MANUAL, b.trigger)
        assertEquals(ScanMode.HANDHELD, b.mode)
        assertEquals(HandheldGuide.frac(b.uprightW, b.uprightH), b.cropRoi)
        assertEquals(3, b.frames.size)     // the ring was already running
        assertArrayEquals(card, b.frames.last().data)
        // A second tap is a new capture, not swallowed.
        analyzer.manualScan()
        feed(card, 1)
        assertEquals(2, rec.bursts.size)
    }

    @Test fun mountManualScanWithAutoOffWaitsForFreshFrames() {
        analyzer.setAuto(false)
        feed(tray, 3)
        ts += 5_000_000_000L                 // long pause: ring frames are now stale
        analyzer.manualScan()
        feed(card, 1)
        assertTrue("stale frames must not be used", rec.bursts.isEmpty())
        feed(card, 2)
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
}
