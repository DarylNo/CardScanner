package io.github.darylno.cardscanner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineTrackerTest {
    /** A card-sized quad (≈ 70 × 98 sample px) at (x, y), with optional per-corner jitter. */
    private fun card(x: Float, y: Float, w: Float = 70f, h: Float = 98f, j: Float = 0f) =
        floatArrayOf(x + j, y - j, x + w - j, y + j, x + w + j, y + h - j, x - j, y + h + j)

    @Test fun aSteadyCardIsShownAtOnceAndAveraged() {
        val t = OutlineTracker()
        val first = t.update(card(50f, 40f))
        assertNotNull("the first find shows immediately", first)
        assertArrayEquals(card(50f, 40f), first!!, 1e-6f)
        // Sub-pixel jitter ±1 averages out around the true corners.
        for (j in listOf(1f, -1f, 1f, -1f)) t.update(card(50f, 40f, j = j))
        val out = t.update(card(50f, 40f))!!
        assertArrayEquals(card(50f, 40f), out, 0.5f)
        assertEquals(0L, t.outliers)
        assertEquals(5, t.lastSamples)
    }

    @Test fun aMuchBiggerQuadIsThrownAway() {
        val t = OutlineTracker()
        repeat(4) { t.update(card(50f, 40f)) }
        // The finder "sees a much bigger card" for one tick: 1.6× the size.
        val out = t.update(card(30f, 10f, w = 112f, h = 157f))!!
        assertTrue("the bad find is an outlier", t.lastWasOutlier)
        assertEquals(1L, t.outliers)
        assertArrayEquals("the outline does not move", card(50f, 40f), out, 1e-4f)
        assertEquals(4, t.lastSamples)
        // Two bad finds in a row still cannot move it (3 good of 5 remain the majority).
        val out2 = t.update(card(30f, 10f, w = 112f, h = 157f))!!
        assertArrayEquals(card(50f, 40f), out2, 1e-4f)
        assertEquals(2L, t.outliers)
    }

    @Test fun aNewCardIsAdoptedOnceItIsTheMajority() {
        val t = OutlineTracker()
        repeat(5) { t.update(card(50f, 40f)) }
        val moved = card(90f, 60f)
        assertArrayEquals("1 of 5: still the old card", card(50f, 40f), t.update(moved)!!, 1e-4f)
        assertArrayEquals("2 of 5: still the old card", card(50f, 40f), t.update(moved)!!, 1e-4f)
        val third = t.update(moved)!!
        assertArrayEquals("3 of 5: the new card, exactly (the old finds are now the outliers)", moved, third, 1e-4f)
        assertFalse(t.lastWasOutlier)
    }

    @Test fun aMissedTickHoldsTheOutlineBrieflyThenClears() {
        val t = OutlineTracker(holdTicks = 2)
        repeat(3) { t.update(card(50f, 40f)) }
        assertNotNull("1st miss: held", t.update(null))
        assertNotNull("2nd miss: held", t.update(null))
        assertNull("3rd miss: gone", t.update(null))
        assertEquals(0, t.lastSamples)
        // Back after a gap: a fresh window — the first find shows as is, even far from the old place.
        val out = t.update(card(120f, 90f))!!
        assertArrayEquals(card(120f, 90f), out, 1e-6f)
        assertEquals(0L, t.outliers)
    }

    @Test fun resetForgetsEverything() {
        val t = OutlineTracker()
        repeat(5) { t.update(card(50f, 40f)) }
        t.reset()
        assertEquals(0, t.lastSamples)
        assertArrayEquals(card(90f, 60f), t.update(card(90f, 60f))!!, 1e-6f)
    }

    @Test fun degenerateInputNeverThrows() {
        val t = OutlineTracker()
        assertNull(t.update(floatArrayOf(1f, 2f)))
        assertNull(t.update(FloatArray(8) { Float.NaN }))
        assertNotNull(t.update(FloatArray(8)))           // zero-area quad: tolerance floors at 1 px², no division by zero
        assertEquals(0.0, OutlineTracker.area(FloatArray(8)), 0.0)
        assertEquals(2.5f, OutlineTracker.median(listOf(4f, 1f, 2f, 3f)), 0f)
    }
}
