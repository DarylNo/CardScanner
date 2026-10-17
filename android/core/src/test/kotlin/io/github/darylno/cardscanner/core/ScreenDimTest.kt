package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Keep awake, dim when idle" (1.1.15): the idle clock, the choices and the dim level. */
class ScreenDimTest {
    @Test
    fun dimsOnlyAfterTheIdleTime_andAnyActivityBrightensAndRestartsTheClock() {
        val d = ScreenDim(60_000)
        d.activity(1_000)
        assertEquals(61_000L, d.dueAt())
        assertFalse("not yet", d.tick(60_999))
        assertFalse(d.dimmed)
        assertTrue("idle a full minute", d.tick(61_000))
        assertTrue(d.dimmed)
        assertNull("dim: nothing more is due", d.dueAt())
        assertFalse("a second tick does not dim again", d.tick(200_000))
        assertTrue("activity on a dimmed screen brightens it", d.activity(250_000))
        assertFalse(d.dimmed)
        assertEquals(310_000L, d.dueAt())
        assertFalse("activity on a bright screen is not a brighten", d.activity(260_000))
        assertFalse("the clock restarted at the last activity", d.tick(310_000))
        assertTrue(d.tick(320_000))
    }

    @Test
    fun neverDims_whenOff() {
        val d = ScreenDim(0)
        d.activity(0)
        assertNull(d.dueAt())
        assertFalse(d.tick(Long.MAX_VALUE / 2))
        assertFalse(d.dimmed)
    }

    @Test
    fun choices_defaultAndNormalize() {
        assertTrue(ScreenDim.DEFAULT_S in ScreenDim.CHOICES_S)
        assertTrue("Never is offered", 0 in ScreenDim.CHOICES_S)
        assertEquals(30, ScreenDim.normalize(30))
        assertEquals(0, ScreenDim.normalize(0))
        assertEquals("a value not offered reads as the default", ScreenDim.DEFAULT_S, ScreenDim.normalize(45))
        assertEquals(ScreenDim.DEFAULT_S, ScreenDim.normalize(-5))
        assertEquals(listOf("30 s", "1 min", "2 min", "5 min", "never"), ScreenDim.CHOICES_S.map { ScreenDim.label(it) })
        assertEquals("1 min 30 s", ScreenDim.label(90))
    }

    @Test
    fun level_isTenPercent_neverBrighterThanHalfThePhonesOwn_andNoneWhenAlreadyDark() {
        assertEquals("unknown: the fixed level", ScreenDim.LEVEL, ScreenDim.level(null))
        assertEquals("a bright phone dims to the fixed level", 0.1f, ScreenDim.level(1f))
        assertEquals(0.1f, ScreenDim.level(0.2f)!!, 1e-6f)
        assertEquals("a phone already at 12 % dims to 6 %", 0.06f, ScreenDim.level(0.12f)!!, 1e-6f)
        assertNull("already about as dark as it goes: no dimming", ScreenDim.level(0.015f))
        assertNull(ScreenDim.level(0f))
        assertEquals("a reading outside 0..1 is not trusted", 0.1f, ScreenDim.level(3.2f))
    }

    @Test
    fun ownBrightness_readsTheSettingAgainstThePhonesDeclaredRange() {
        assertEquals(1f, ScreenDim.ownBrightness(255, 255)!!, 0f)
        assertEquals("no declared range: the standard 255", 0.5f, ScreenDim.ownBrightness(128, null)!!, 0.01f)
        assertEquals("a 0..2047 phone at night (40 ≈ 2 %)", 40 / 2047f, ScreenDim.ownBrightness(40, 2047)!!, 1e-6f)
        assertEquals("a reading above the declared range is read against the widest (never brighter)",
            800 / 4095f, ScreenDim.ownBrightness(800, 255)!!, 1e-6f)
        assertNull(ScreenDim.ownBrightness(null, 255))
        assertNull(ScreenDim.ownBrightness(-1, 255))
        // The night case the review found: 40 on a 0..2047 phone must not "dim" to 7.8 %.
        assertNull("2 % already: left alone", ScreenDim.level(ScreenDim.ownBrightness(40, 2047)))
        assertEquals(ScreenDim.ownBrightness(300, 2047)!! / 2, ScreenDim.level(ScreenDim.ownBrightness(300, 2047))!!, 1e-6f)
    }
}
