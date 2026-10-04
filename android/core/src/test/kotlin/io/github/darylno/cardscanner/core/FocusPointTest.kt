package io.github.darylno.cardscanner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where focus is metered: the Area centre before a card is there, the CARD's
 * centre (the Trigger box mapped out of the detection sample) for the
 * first-card pass — so an off-centre card in a wide Area is what the lens
 * focuses on, not the tray beside it.
 */
class FocusPointTest {
    private val eps = 1e-12

    @Test fun withNoBoxItIsTheAreaCentreOrTheFrameCentre() {
        val a = RoiFrac(0.2, 0.1, 0.6, 0.9)
        val p = FocusPoint.choose(a, null, 176, 235)
        assertFalse(p.onCard)
        assertEquals(0.4, p.u, eps); assertEquals(0.5, p.v, eps)
        val f = FocusPoint.areaCentre(null)
        assertEquals(0.5, f.u, eps); assertEquals(0.5, f.v, eps)
        assertEquals("Area centre (0.40, 0.50)", p.describe())
    }

    @Test fun aBoxIsMappedThroughTheAreaToTheCardsCentre() {
        // A 176×352 sample of the Area x 0.2..0.6, y 0.1..0.9; the card sits in its top-left quarter.
        val a = RoiFrac(0.2, 0.1, 0.6, 0.9)
        val box = Box(x = 10, y = 20, w = 60, h = 80, maskFrac = 0.2)
        val p = FocusPoint.choose(a, box, 176, 352)
        assertTrue(p.onCard)
        assertEquals(0.2 + 0.4 * (40.0 / 176), p.u, eps)
        assertEquals(0.1 + 0.8 * (60.0 / 352), p.v, eps)
        assertTrue(p.describe().startsWith("card centre ("))
        // The Area centre would have been far from the card: that is the point of the change.
        val c = FocusPoint.areaCentre(a)
        assertTrue(Math.abs(c.u - p.u) > 0.1 && Math.abs(c.v - p.v) > 0.2)
    }

    @Test fun aBoxWithNoAreaUsesTheWholeFrame() {
        val box = Box(x = 0, y = 0, w = 176, h = 132, maskFrac = 1.0)
        val p = FocusPoint.choose(null, box, 176, 132)
        assertEquals(0.5, p.u, eps); assertEquals(0.5, p.v, eps)
        assertTrue(p.onCard)
    }

    @Test fun anUnusableSampleFallsBackToTheAreaCentre() {
        val a = RoiFrac(0.0, 0.0, 0.5, 0.5)
        val box = Box(1, 1, 10, 10, 0.1)
        assertFalse(FocusPoint.choose(a, box, 0, 100).onCard)
        assertFalse(FocusPoint.choose(a, box, 100, 0).onCard)
        assertFalse(FocusPoint.choose(a, Box(1, 1, 0, 10, 0.1), 100, 100).onCard)
        // A box reported past the sample edge is clamped into the Area, never outside it.
        val p = FocusPoint.choose(a, Box(150, 150, 100, 100, 0.1), 100, 100)
        assertEquals(0.5, p.u, eps); assertEquals(0.5, p.v, eps)
    }

    @Test fun theSensorPointFollowsTheRotation() {
        // Upright (0.25, 0.75) of a 1600×1200 sensor frame.
        val p = FocusPoint.Point(0.25, 0.75, onCard = true)
        assertArrayEquals(intArrayOf(400, 900), p.toSensor(1600, 1200, 0))
        // Portrait: upright is 1200×1600 → (300, 1200) upright → sensor (v, sh-1-u) = (1200, 899).
        assertArrayEquals(intArrayOf(1200, 899), p.toSensor(1600, 1200, 90))
        // The point always lands inside the frame, even at the far edges.
        val edge = FocusPoint.Point(1.0, 1.0, onCard = false).toSensor(1600, 1200, 270)
        assertTrue(edge[0] in 0 until 1600 && edge[1] in 0 until 1200)
        // Same mapping the samplers use (Rotation), so the AF point and the sampled card agree.
        val u = 300; val v = 1200
        assertEquals(Rotation.sensorX(u, v, 1600, 1200, 90), p.toSensor(1600, 1200, 90)[0])
        assertEquals(Rotation.sensorY(u, v, 1600, 1200, 90), p.toSensor(1600, 1200, 90)[1])
    }
}
