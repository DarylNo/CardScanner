package io.github.darylno.cardscanner.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Is it focusing?" — the `focus` log's words and the Diagnostics focus line.
 * Pure: no camera. What the lines must let a reader of a rig report tell apart:
 * a pass that FOCUSED, one CameraX gave up on (its 5 s timeout), one that
 * failed early, one cancelled by a newer pass; and the lens position at the time.
 */
class FocusLogTest {
    @Test fun outcomesAreToldApart() {
        assertEquals(FocusLog.Outcome.FOCUSED, FocusLog.classify(true, 420, superseded = false))
        assertEquals(FocusLog.Outcome.FAILED, FocusLog.classify(false, 900, superseded = false))
        // CameraX completes an unconverged pass NOT focused after 5 s: that is a timeout, not a quick failure.
        assertEquals(FocusLog.Outcome.TIMED_OUT, FocusLog.classify(false, 5_003, superseded = false))
        assertEquals(FocusLog.Outcome.TIMED_OUT, FocusLog.classify(false, FocusLog.CAMERAX_AF_TIMEOUT_MS - 200, superseded = false))
        assertEquals(FocusLog.Outcome.FAILED, FocusLog.classify(false, FocusLog.CAMERAX_AF_TIMEOUT_MS - 1_000, superseded = false))
        assertEquals(FocusLog.Outcome.CANCELLED, FocusLog.classify(null, 30, superseded = false))
        // A newer pass started meanwhile: whatever this one answered, it is superseded.
        assertEquals(FocusLog.Outcome.SUPERSEDED, FocusLog.classify(true, 30, superseded = true))
        assertEquals(FocusLog.Outcome.SUPERSEDED, FocusLog.classify(null, 30, superseded = true))
    }

    @Test fun afStatesAndTheLensReadAsWords() {
        assertEquals("INACTIVE", FocusLog.afStateName(0))
        assertEquals("ACTIVE_SCAN", FocusLog.afStateName(3))
        assertEquals("FOCUSED_LOCKED", FocusLog.afStateName(4))
        assertEquals("NOT_FOCUSED_LOCKED", FocusLog.afStateName(5))
        assertEquals("PASSIVE_UNFOCUSED", FocusLog.afStateName(6))
        assertEquals("unknown", FocusLog.afStateName(null))
        assertEquals("state 9", FocusLog.afStateName(9))
        // Dioptres are 1/m: 4 dpt = 25 cm, shown only when the lens reports a physical scale.
        assertEquals("lens 4.00 dpt (≈ 25 cm)", FocusLog.lens(4f, calibrated = true))
        assertEquals("lens 4.00 dpt", FocusLog.lens(4f, calibrated = false))
        assertEquals("lens 0.00 dpt (∞)", FocusLog.lens(0f, calibrated = true))
        assertEquals("lens ? dpt", FocusLog.lens(null, calibrated = true))
        assertEquals("AF FOCUSED_LOCKED · lens 5.00 dpt (≈ 20 cm)", FocusLog.afAndLens(4, 5f, true))
    }

    @Test fun aPassIsLoggedAsAStartAndAResult() {
        val start = FocusLog.startLine(7, "first card after the camera bind", "card centre (0.52, 0.48)",
            intArrayOf(832, 600, 1600, 1200), afState = 0, dpt = 3.1f, calibrated = false)
        assertEquals("#7 first card after the camera bind → card centre (0.52, 0.48) = sensor 832,600 of 1600×1200 · " +
            "now AF INACTIVE · lens 3.10 dpt", start)
        assertEquals("#2 tap → tap at 10,20 of the 100×200 preview · now AF unknown · lens ? dpt",
            FocusLog.startLine(2, "tap", "tap at 10,20 of the 100×200 preview", null, null, null, true))
        val ok = FocusLog.resultLine(7, "first card", FocusLog.Outcome.FOCUSED, 420, 4, 4.2f, calibrated = true)
        assertEquals("#7 first card: focused in 420 ms · AF FOCUSED_LOCKED · lens 4.20 dpt (≈ 24 cm)", ok)
        val timedOut = FocusLog.resultLine(8, "camera bind", FocusLog.Outcome.TIMED_OUT, 5_001, 3, 2f, false)
        assertTrue(timedOut, timedOut.startsWith("#8 camera bind: timed out in 5001 ms · AF ACTIVE_SCAN"))
        assertTrue(timedOut, timedOut.endsWith("(CameraX gives up after 5 s)"))
        // A cancelled pass says why (CameraX's message); a focused one never carries an error.
        val cancelled = FocusLog.resultLine(9, "new Area", FocusLog.Outcome.CANCELLED, 12, null, null, false,
            error = "Cancelled by another startFocusAndMetering()")
        assertTrue(cancelled, cancelled.endsWith(" — Cancelled by another startFocusAndMetering()"))
        assertFalse(FocusLog.resultLine(1, "x", FocusLog.Outcome.FOCUSED, 1, null, null, false, error = "boom").contains("boom"))
    }

    @Test fun theWatchdogAndTheGiveUpSayWhatHappened() {
        val w = FocusLog.watchdogLine(12.34, 40.0, 0.5, 5)
        assertEquals("watchdog: capture sharpness 12.3 < 0.50 × the session median 40.0 (last 5 flattened Tray captures) — " +
            "focusing again on the next card", w)
        val g = FocusLog.givenUpLine(3)
        assertTrue(g, g.startsWith("card focus failed 3 times in a row — no more card passes"))
        // Review of 1.1.18 (F6): the periodic refocus is named only when there is one.
        assertEquals("card focus failed 3 times in a row — no more card passes until a tap, a new Area, a camera re-open " +
            "or the soft-capture watchdog; the lens stays where it is", FocusLog.givenUpLine(3, -1))
        assertEquals(g, FocusLog.givenUpLine(3, -1))
        assertEquals("card focus failed 3 times in a row — no more card passes until a tap, a new Area, a camera re-open, " +
            "the soft-capture watchdog or the next periodic refocus in 5 min (Refocus on a card); the lens stays where it is",
            FocusLog.givenUpLine(3, 5))
        assertEquals("card focus failed 3 times in a row — this chain ends; Refocus on a card is Every card, so the next card " +
            "starts a fresh one; the lens stays where it is", FocusLog.givenUpLine(3, 0))
    }

    @Test fun agesReadNaturally() {
        assertEquals("0.4 s", FocusLog.age(400))
        assertEquals("12 s", FocusLog.age(12_900))
        assertEquals("2 min 5 s", FocusLog.age(125_000))
        assertEquals("1 h 3 min", FocusLog.age(3_780_000))
        assertEquals("0.0 s", FocusLog.age(-5))
    }

    @Test fun theSessionSummaryCountsEveryKindOfResult() {
        var now = 1_000_000L
        val st = FocusStats(clock = { now })
        assertEquals("passes this session: 0 focused · 0 NOT focused · 0 timed out · 0 cancelled · last: none yet · watchdog re-arms 0",
            st.summary())
        st.record(FocusLog.Outcome.FOCUSED, "camera bind", 300)
        st.record(FocusLog.Outcome.TIMED_OUT, "first card after the camera bind", 5_001)
        st.record(FocusLog.Outcome.FAILED, "retry 2 of 3 (the last card pass failed)", 800)
        st.record(FocusLog.Outcome.SUPERSEDED, "new Area", 20)
        st.record(FocusLog.Outcome.FOCUSED, "tap", 450)
        st.watchdogFired()
        st.gaveUp()
        now += 125_000
        assertEquals("passes this session: 2 focused · 1 NOT focused · 1 timed out · 1 cancelled · " +
            "last: tap: focused in 450 ms (2 min 5 s ago) · watchdog re-arms 1 · card focus given up 1×", st.summary())
    }

    @Test fun eachFramesReportIsFoundByItsSensorTimestamp() {
        val ring = FrameMetaRing(capacity = 3)
        assertNull(ring.latest())
        val a = FrameMetaRing.Meta(100, 3, 2.0f, 16_666_667, 400)
        val b = FrameMetaRing.Meta(133, 4, 4.0f, 8_000_000, 200)
        ring.put(a); ring.put(b)
        assertSame(a, ring.at(100))
        assertSame(b, ring.at(133))
        assertNull("only an exact timestamp is that frame's", ring.at(134))
        assertSame(b, ring.latest())
        ring.put(FrameMetaRing.Meta(166, 4, 4f, null, null))
        ring.put(FrameMetaRing.Meta(200, 4, 4f, null, null))
        assertNull("rolled out past the capacity", ring.at(100))
        assertEquals("AF FOCUSED_LOCKED · lens 4.00 dpt (≈ 25 cm) · exposure 8.0 ms · ISO 200", b.describe(calibrated = true))
        assertEquals("AF FOCUSED_LOCKED · lens 4.00 dpt", ring.latest()!!.describe(calibrated = false))
        ring.clear()
        assertNull(ring.latest())
    }

    /**
     * The zoom probe (1.1.17) reads each frame's OWN zoom: CONTROL_ZOOM_RATIO and the crop
     * region ride the meta (plain Ints — this test is plain JUnit, no android.graphics.Rect),
     * and the 5-argument form every caller before 1.1.17 used still builds a meta without them.
     */
    @Test fun aFramesZoomAndCropAreKept() {
        val m = FrameMetaRing.Meta(100, 4, 4.2f, 16_666_667, 400, zoomRatio = 1.6f, crop = intArrayOf(0, 0, 4160, 3120))
        assertEquals(1.6f, m.zoomRatio!!, 0f)
        assertArrayEquals(intArrayOf(0, 0, 4160, 3120), m.crop)
        val old = FrameMetaRing.Meta(100, 4, 4.2f, 16_666_667, 400)
        assertNull(old.zoomRatio); assertNull(old.crop)
        assertEquals("the words are the same as before", old.describe(true), m.describe(true))
        val ring = FrameMetaRing()
        ring.put(m)
        assertSame(m, ring.at(100))
        // The frame's zoom by the live read-back's rule (ZoomProbe.frameZoom): CONTROL_ZOOM_RATIO when it is
        // not 1 (API 30+: the crop is then the full array), else the active array ÷ the crop's width.
        assertEquals(1.6, m.frameZoom(4160)!!, 1e-6)
        assertEquals(2.0, FrameMetaRing.Meta(1, null, null, null, null, zoomRatio = 1f, crop = intArrayOf(1040, 780, 2080, 1560))
            .frameZoom(4160)!!, 1e-9)
        assertEquals(2.0, FrameMetaRing.Meta(1, null, null, null, null, crop = intArrayOf(1040, 780, 2080, 1560)).frameZoom(4160)!!, 1e-9)
        assertEquals("no crop, ratio 1: 1", 1.0, FrameMetaRing.Meta(1, null, null, null, null, zoomRatio = 1f).frameZoom(4160)!!, 1e-9)
        assertNull("nothing reported", old.frameZoom(4160))
        // Whether the HAL's crop is centred on the active array (a logged fact, not evidence on API 30+).
        assertEquals(true, m.cropCentred(4160, 3120))
        assertEquals(true, FrameMetaRing.Meta(1, null, null, null, null, crop = intArrayOf(1040, 780, 2080, 1560)).cropCentred(4160, 3120))
        assertEquals("a crop 3 % off to the right", false,
            FrameMetaRing.Meta(1, null, null, null, null, crop = intArrayOf(1165, 780, 2080, 1560)).cropCentred(4160, 3120))
        assertNull(old.cropCentred(4160, 3120))
        assertNull("the active array unknown", m.cropCentred(null, null))
    }
}
