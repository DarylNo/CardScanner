package io.github.darylno.cardscanner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanRateTest {
    private val min = 60_000L

    @Test fun emptyShowsNothing() {
        assertEquals("", ScanRate().label(0))
    }

    @Test fun oneCardHasNoRateYet() {
        val r = ScanRate(); r.record(0)
        assertNull(r.perMinute(5_000))
        assertEquals("—/min · 1", r.label(5_000))
    }

    @Test fun aQuickPairIsNotSixtyPerMinute() {
        val r = ScanRate(); r.record(0); r.record(1_000)
        assertEquals(2.0, r.perMinute(1_000)!!, 1e-9)          // floored to a 1-minute span
    }

    @Test fun steadyTwelvePerMinute() {
        val r = ScanRate()
        for (i in 0 until 60) r.record(i * 5_000L)               // one card every 5 s for 5 min
        assertEquals("12/min · 60", r.label(59 * 5_000L))
    }

    @Test fun oldCardsDropOutOfTheWindowButStayInTheTotal() {
        val r = ScanRate()
        for (i in 0 until 10) r.record(i * 1_000L)                // burst at the start
        r.record(10 * min); r.record(10 * min + 30_000)           // 10 min later, two more
        assertEquals(2.0, r.perMinute(10 * min + 30_000)!!, 1e-9)
        assertEquals(12, r.total)
    }
}
