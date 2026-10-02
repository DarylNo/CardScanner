package io.github.darylno.cardscanner.ui

import android.content.Context
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import io.github.darylno.cardscanner.core.Box
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The scan screen's card layer (owner, 2026-10-02): the live outline replaces
 * the mask rectangle, a capture turns it blue and FREEZES it on the capture's
 * corners for check_ms, a failed capture takes it down, and the card-shaped
 * watch window shows while waiting for the next card. Asserted on
 * [OverlayView.plan] (what onDraw paints) with a test clock; onDraw itself is
 * exercised once so a Path/Paint mistake can't hide.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayViewTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var now = 10_000L

    private fun view(): OverlayView = OverlayView(ctx).also {
        it.clock = { now }
        it.setFrameSize(1200, 1600)
        it.measure(0, 0); it.layout(0, 0, 600, 800)
    }

    private val box = Box(50, 60, 60, 90, 0.2)
    /** The box as the quad the overlay draws for it (frame fractions, no Area). */
    private val boxQuad = floatArrayOf(50f / 176, 60f / 235, 110f / 176, 60f / 235, 110f / 176, 150f / 235, 50f / 176, 150f / 235)
    private val outline = floatArrayOf(0.30f, 0.26f, 0.62f, 0.27f, 0.61f, 0.70f, 0.29f, 0.69f)
    private val captureQuad = floatArrayOf(0.31f, 0.25f, 0.63f, 0.26f, 0.62f, 0.71f, 0.30f, 0.70f)
    private val watch = floatArrayOf(0.25f, 0.20f, 0.67f, 0.21f, 0.66f, 0.76f, 0.24f, 0.75f)

    @Test fun theOutlineIsDrawnInPlaceOfTheRectangleWhenThereIsOne() {
        val v = view()
        v.setBox(box, 176, 235, OverlayView.BoxState.OCCUPIED, outline, null)
        val p = v.plan()
        assertArrayEquals(outline, p.shape!!, 1e-6f)
        assertEquals(OverlayView.BoxState.OCCUPIED, p.state)
        assertFalse(p.dashed); assertFalse(p.check); assertFalse(p.badge)
        // No outline → today's rectangle, in the state's colour.
        v.setBox(box, 176, 235, OverlayView.BoxState.SETTLING, null, null)
        val r = v.plan()
        assertArrayEquals(boxQuad, r.shape!!, 1e-6f)
        assertEquals(OverlayView.BoxState.SETTLING, r.state)
        v.draw(Canvas())
    }

    @Test fun theHoldStartsOnTheOutlineAndFreezesOnTheCaptureQuad() {
        val v = view()
        v.setBox(box, 176, 235, OverlayView.BoxState.OCCUPIED, outline, null)
        v.beginHold(2500)                                   // onCaptureStarted: blue + ✓ on what is on screen
        assertTrue(v.holding)
        var p = v.plan()
        assertEquals(OverlayView.BoxState.CAPTURED, p.state)
        assertTrue(p.check); assertFalse(p.badge)
        assertArrayEquals(outline, p.shape!!, 1e-6f)
        // A hand comes in: the live box grows and detection moves on to waiting — the ✓ does not move.
        v.setBox(Box(10, 10, 160, 220, 0.6), 176, 235, OverlayView.BoxState.AWAIT_NEXT, null, watch)
        p = v.plan()
        assertEquals(OverlayView.BoxState.CAPTURED, p.state)
        assertArrayEquals("frozen on the trigger's outline", outline, p.shape!!, 1e-6f)
        now += 400
        v.holdCheck(2500, captureQuad)                      // the capture landed: snap to its exact corners
        p = v.plan()
        assertArrayEquals(captureQuad, p.shape!!, 1e-6f)
        assertArrayEquals(captureQuad, v.heldShape()!!, 1e-6f)
        now += 2400                                         // still inside check_ms from the snap
        assertTrue(v.holding)
        assertArrayEquals(captureQuad, v.plan().shape!!, 1e-6f)
        now += 200                                          // check_ms over → the watch window, grey dashed
        assertFalse(v.holding)
        p = v.plan()
        assertEquals(OverlayView.BoxState.AWAIT_NEXT, p.state)
        assertTrue(p.dashed); assertFalse(p.check)
        assertArrayEquals(watch, p.shape!!, 1e-6f)
        v.draw(Canvas())
    }

    @Test fun aFailedCaptureTakesTheCheckDown() {
        val v = view()
        v.setBox(box, 176, 235, OverlayView.BoxState.OCCUPIED, outline, null)
        v.beginHold(2500, id = 7)
        assertTrue(v.holding)
        v.cancelHold(7)
        assertFalse(v.holding)
        assertNull(v.heldShape())
        val p = v.plan()
        assertEquals(OverlayView.BoxState.OCCUPIED, p.state)
        assertFalse(p.check); assertFalse(p.badge)
        assertArrayEquals(outline, p.shape!!, 1e-6f)
    }

    /**
     * The pipeline is one queue: burst 2's capture can start before burst 1's
     * answer. Burst 1 then failing must not take down the ✓ burst 2 put up.
     */
    @Test fun anOlderCapturesFailureLeavesANewerHoldUp() {
        val v = view()
        v.setBox(box, 176, 235, OverlayView.BoxState.OCCUPIED, outline, null)
        v.beginHold(2500, id = 1)
        now += 300
        v.beginHold(2500, id = 2)                           // the shutter again, pipeline still on burst 1
        v.cancelHold(1)                                     // burst 1: "capture pipeline is shut down"
        assertTrue("burst 2's ✓ stays", v.holding)
        assertTrue(v.plan().check)
        v.cancelHold(2)
        assertFalse(v.holding)
    }

    /**
     * Check mark time can be 250 ms (CHECK_MS_MIN) — shorter than the pipeline.
     * The hold begun at the trigger must outlive the pipeline, or the grey watch
     * window shows in between (blue → grey → blue, the flicker this fixes); the
     * snap then holds check_ms from the capture, and a hold can never stick.
     */
    @Test fun aShortCheckTimeStillHoldsUntilThePipelineAnswers() {
        val v = view()
        v.setBox(box, 176, 235, OverlayView.BoxState.OCCUPIED, outline, null)
        v.beginHold(250, id = 3)
        v.setBox(Box(10, 10, 160, 220, 0.6), 176, 235, OverlayView.BoxState.AWAIT_NEXT, null, watch)
        now += 900                                          // the pipeline is still running
        assertTrue("still blue, not the grey window", v.holding)
        assertEquals(OverlayView.BoxState.CAPTURED, v.plan().state)
        assertFalse(v.plan().dashed)
        v.holdCheck(250, captureQuad)                       // the capture landed
        now += 240
        assertTrue(v.holding)
        now += 20
        assertFalse("check_ms from the snap", v.holding)
        assertTrue(v.plan().dashed)
        // Nothing ever answers (cannot happen — one outcome per burst — but a hold must not stick).
        val u = view()
        u.setBox(box, 176, 235, OverlayView.BoxState.OCCUPIED, outline, null)
        u.beginHold(250, id = 4)
        now += 250 + OverlayView.PIPELINE_HOLD_MS - 1
        assertTrue(u.holding)
        now += 2
        assertFalse(u.holding)
    }

    @Test fun theWatchWindowShowsWhileWaitingForTheNextCard() {
        val v = view()
        v.setBox(box, 176, 235, OverlayView.BoxState.AWAIT_NEXT, null, watch)
        val p = v.plan()
        assertTrue(p.dashed)
        assertArrayEquals(watch, p.shape!!, 1e-6f)
        assertEquals(OverlayView.BoxState.AWAIT_NEXT, p.state)
        // Without a window: today's grey rectangle on the mask box.
        v.setBox(box, 176, 235, OverlayView.BoxState.AWAIT_NEXT, null, null)
        val r = v.plan()
        assertFalse(r.dashed)
        assertArrayEquals(boxQuad, r.shape!!, 1e-6f)
        v.draw(Canvas())
    }

    /** Owner: "always highlight the card" — the live outline stays up (grey) while waiting; the dashed window only without one. */
    @Test fun theCardStaysHighlightedWhileWaitingForTheNextCard() {
        val v = view()
        v.setBox(box, 176, 235, OverlayView.BoxState.AWAIT_NEXT, outline, watch)
        val p = v.plan()
        assertArrayEquals("the outline, not the window", outline, p.shape!!, 1e-6f)
        assertEquals(OverlayView.BoxState.AWAIT_NEXT, p.state)
        assertFalse(p.dashed); assertFalse(p.check); assertFalse(p.badge)
        v.draw(Canvas())
        // The outline lost (a hand over the card) → the dashed watch window takes over.
        v.setBox(box, 176, 235, OverlayView.BoxState.AWAIT_NEXT, null, watch)
        assertTrue(v.plan().dashed)
        assertArrayEquals(watch, v.plan().shape!!, 1e-6f)
    }

    @Test fun noShapeAtAllGivesTheCentreBadgeUntilTheCaptureBringsOne() {
        val v = view()
        v.setBox(null, 176, 235, OverlayView.BoxState.SETTLING, null, null)
        v.beginHold(2500)
        var p = v.plan()
        assertNull(p.shape); assertTrue(p.badge); assertFalse(p.check)
        v.draw(Canvas())
        v.holdCheck(2500, captureQuad)                      // Tap to scan: the pipeline found the card anyway
        p = v.plan()
        assertFalse(p.badge); assertTrue(p.check)
        assertArrayEquals(captureQuad, p.shape!!, 1e-6f)
        // …and with no quad either, the badge stays (today's behaviour).
        val u = view()
        u.setBox(null, 176, 235, OverlayView.BoxState.SETTLING, null, null)
        u.holdCheck(2500, null)
        assertTrue(u.plan().badge)
        assertNotNull(u.plan())
    }

    @Test fun capturedCarriesTheQuadAsFractionsAndTheCardHeight() {
        val c = Captured(ByteArray(0), emptyList(), true, "t", null,
            quad = floatArrayOf(99.5f, 199.5f, 699.5f, 199.5f, 699.5f, 1099.5f, 99.5f, 1099.5f), frameW = 1200, frameH = 1600)
        assertArrayEquals(floatArrayOf(100f / 1200, 200f / 1600, 700f / 1200, 200f / 1600, 700f / 1200, 1100f / 1600, 100f / 1200, 1100f / 1600),
            c.quadFractions()!!, 1e-6f)
        assertEquals(900, c.cardHeightPx())
        assertEquals(600, c.cardWidthPx())
        val none = Captured(ByteArray(0), emptyList(), false, "t", null)
        assertNull(none.quadFractions())
        assertEquals(0, none.cardHeightPx())
    }
}
