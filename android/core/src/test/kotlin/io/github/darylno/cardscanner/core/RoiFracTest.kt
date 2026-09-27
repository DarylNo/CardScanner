package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The scan Area follows phone.html's rules: fractions of the upright frame, each side ≥ 8%. */
class RoiFracTest {
    private fun rejects(x0: Double, y0: Double, x1: Double, y1: Double) {
        try { RoiFrac(x0, y0, x1, y1); fail("accepted $x0,$y0,$x1,$y1") } catch (_: IllegalArgumentException) {}
        assertNull(RoiFrac.orNull(x0, y0, x1, y1))
    }

    @Test
    fun validation() {
        RoiFrac(0.0, 0.0, 1.0, 1.0)
        RoiFrac(0.0, 0.0, 0.08, 0.08)                  // exactly 8% is allowed (phone.html rejects `< 0.08`)
        rejects(0.0, 0.0, 0.079, 0.5)
        rejects(0.2, 0.2, 0.1, 0.9)                    // inverted
        rejects(-0.1, 0.0, 0.5, 0.5)
        rejects(0.0, 0.0, 1.2, 0.5)
        rejects(Double.NaN, 0.0, 0.5, 0.5)
    }

    @Test
    fun toPixelsRoundsAndClamps() {
        assertEquals(RoiPx(120, 320, 960, 1040), RoiFrac(0.1, 0.2, 0.9, 0.85).toPixels(1200, 1600))
        assertEquals(RoiPx(0, 0, 1200, 1600), RoiFrac(0.0, 0.0, 1.0, 1.0).toPixels(1200, 1600))
        val p = RoiFrac(0.92, 0.92, 1.0, 1.0).toPixels(10, 10)       // tiny frame: never empty
        assertEquals(RoiPx(9, 9, 1, 1), p)
        assertEquals(10, p.right); assertEquals(10, p.bottom)
        // Unrounded sizes drive MH, exactly like phone.html roiRect().
        assertEquals(0.8 * 1200, RoiFrac(0.1, 0.2, 0.9, 0.85).widthPx(1200), 0.0)
        assertEquals((0.85 - 0.2) * 1600, RoiFrac(0.1, 0.2, 0.9, 0.85).heightPx(1600), 0.0)
    }

    @Test
    fun dragClampsSortsAndRejectsSmall() {
        assertEquals(RoiFrac(0.2, 0.0, 1.0, 0.7), RoiFrac.fromDrag(1.4, 0.7, 0.2, -0.3))
        assertNull(RoiFrac.fromDrag(0.5, 0.5, 0.55, 0.9))           // "Area too small"
    }

    @Test
    fun encodeParseRoundTrip() {
        val r = RoiFrac(0.123456789, 0.2, 0.9, 0.85)
        assertEquals(r, RoiFrac.parse(r.encode()))
        for (bad in listOf(null, "", "1,2,3", "a,b,c,d", "0.5,0.5,0.51,0.9", "0,0,1,1,1")) assertNull(bad, RoiFrac.parse(bad))
    }
}
