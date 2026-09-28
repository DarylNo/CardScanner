package io.github.darylno.cardscanner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryEstimateTest {
    private val min = 60_000L

    @Test fun noReadingShowsNothing() = assertEquals("", BatteryEstimate().label(0))

    @Test fun measuringUntilThreeMinutesAndOnePercent() {
        val b = BatteryEstimate()
        b.record(0, 80.0, false)
        b.record(2 * min, 79.0, false)
        assertNull("under 3 minutes", b.minutesLeft(2 * min))
        assertEquals("🔋 79%", b.label(2 * min))
    }

    @Test fun onePercentPerFourMinutes() {
        val b = BatteryEstimate()
        for (i in 0..8) b.record(i * min, 80.0 - i / 4, false)   // 80 → 78 over 8 min
        assertEquals(78.0 * 4, b.minutesLeft(8 * min)!!, 1e-9)    // 312 min
        assertEquals("🔋 78% · ~5h 12m", b.label(8 * min))
    }

    @Test fun chargingShowsTheBoltAndRestartsTheMeasurement() {
        val b = BatteryEstimate()
        b.record(0, 50.0, false); b.record(10 * min, 45.0, false)
        b.record(11 * min, 46.0, true)
        assertEquals("⚡ 46%", b.label(11 * min))
        b.record(12 * min, 60.0, false)                             // unplugged: start over
        assertEquals("🔋 60%", b.label(12 * min))
    }

    @Test fun onlyTheRecentWindowCounts() {
        val b = BatteryEstimate(windowMs = 10 * min)
        b.record(0, 100.0, false)                                   // fast drain long ago…
        b.record(5 * min, 90.0, false)
        for (i in 30..40) b.record(i * min, 90.0 - (i - 30) * 0.2, false)   // …slow now
        assertEquals(88.0 / 0.2, b.minutesLeft(40 * min)!!, 1e-6)
    }
}
