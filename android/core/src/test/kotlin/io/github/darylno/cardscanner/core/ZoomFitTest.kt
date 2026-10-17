package io.github.darylno.cardscanner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * "Zoom to fit the Area": the ratio rule (each cap binds in turn), the base ↔
 * view mapping (exact identity at 1×, round trip, the view Area always inside
 * the frame and never smaller than the base one), and the snapshot's base-space
 * placement.
 */
class ZoomFitTest {
    private val eps = 1e-12

    @Test fun fitIsTheDistanceOfTheFarthestEdgeFromTheCentre() {
        assertEquals(1.0, ZoomFit.fit(null), eps)
        assertEquals(1.0, ZoomFit.fit(RoiFrac(0.0, 0.2, 0.7, 0.8)), eps)          // touches the left edge
        assertEquals(1.0 / (2 * 0.3), ZoomFit.fit(RoiFrac(0.2, 0.25, 0.8, 0.75)), eps)
        assertEquals(1.0 / (2 * 0.35), ZoomFit.fit(RoiFrac(0.3, 0.15, 0.6, 0.6)), eps) // y0 is the farthest
    }

    @Test fun losslessIsTheSensorOverTheStreamPerSideTheSmaller() {
        assertEquals(2.6, ZoomFit.lossless(4160, 3120, 1600, 1200)!!, eps)
        assertEquals(2.6, ZoomFit.lossless(4160, 3120, 1200, 1600)!!, eps)        // orientation-free
        assertEquals(4000.0 / 1920, ZoomFit.lossless(4000, 3000, 1920, 1080)!!, eps)  // 16:9 crop of 4:3
        assertNull(ZoomFit.lossless(0, 0, 1600, 1200))
    }

    @Test fun eachCapBindsInTurn() {
        val a = RoiFrac(0.25, 0.25, 0.75, 0.75)                                    // fit 2.0 → 0.95·2 = 1.90
        ZoomFit.choose(a, lossless = 3.0, lensMax = 8.0, cap = 4.0).let {
            assertEquals(1.90, it.z, eps); assertEquals(ZoomFit.Limit.FIT, it.limit); assertFalse(it.belowMin)
        }
        ZoomFit.choose(a, lossless = 1.62, lensMax = 8.0, cap = 4.0).let {
            assertEquals(1.60, it.z, eps); assertEquals(ZoomFit.Limit.LOSSLESS, it.limit)
        }
        ZoomFit.choose(a, lossless = 3.0, lensMax = 1.33, cap = 4.0).let {
            assertEquals(1.30, it.z, eps); assertEquals(ZoomFit.Limit.LENS_MAX, it.limit)
        }
        ZoomFit.choose(RoiFrac(0.4, 0.4, 0.6, 0.6), lossless = 9.0, lensMax = 9.0).let {   // fit 5 → MAX_ZOOM
            assertEquals(ZoomFit.MAX_ZOOM, it.z, eps); assertEquals(ZoomFit.Limit.CAP, it.limit)
        }
        // Unknown lossless / lens max are not caps.
        assertEquals(1.90, ZoomFit.choose(a, null, null, cap = 4.0).z, eps)
    }

    @Test fun belowTenPercentItStaysAtOne() {
        val wide = RoiFrac(0.04, 0.04, 0.96, 0.96)                                 // fit 1.087 → 1.03
        val c = ZoomFit.choose(wide, 3.0, 8.0)
        assertEquals(1.0, c.z, 0.0)
        assertTrue(c.belowMin)
        assertEquals(ZoomFit.Limit.FIT, c.limit)
        assertEquals(1.0, ZoomFit.choose(null, 3.0, 8.0).z, 0.0)
        assertEquals(ZoomFit.Limit.NO_AREA, ZoomFit.choose(null, 3.0, 8.0).limit)
        // An Area touching an edge cannot be zoomed at all.
        assertEquals(1.0, ZoomFit.choose(RoiFrac(0.0, 0.3, 0.5, 0.7), 3.0, 8.0).z, 0.0)
    }

    @Test fun ratiosAreFlooredToTheStepRobustlyAndExactly() {
        assertEquals(1.2, ZoomFit.floorStep(1.2), 1e-9)                             // 1.2/0.05 = 23.999…
        assertEquals(1.15, ZoomFit.floorStep(1.1999), 1e-9)
        // choose() returns the exact decimal literal (24 × 0.05 = 1.2000000000000002 would not
        // equal a stored 1.2), so the same Area always maps bit-identically.
        val m = 0.95 / (2 * 1.21)                                                   // 0.95·fit = 1.21
        val z = ZoomFit.choose(RoiFrac(0.5 - m, 0.4, 0.5 + m, 0.6), 3.0, 8.0).z
        assertTrue("$z", z == 1.2)
    }

    /** The default Tap-to-scan Area (HandheldGuide.defaultArea on a 1200×1600 portrait frame) allows 1.20×. */
    @Test fun theDefaultTapAreaGivesOnePointTwo() {
        // HandheldGuide: a centred 63:88 card at 60 % of the limiting side + 15 % pad.
        val w = 1200.0; val h = 1600.0
        var gw = w * 0.6; var gh = gw * 88 / 63
        if (gh > h * 0.6) { gh = h * 0.6; gw = gh * 63 / 88 }
        val a = RoiFrac((w - gw) / 2 / w - gw * 0.15 / w, (h - gh) / 2 / h - gh * 0.15 / h,
            (w + gw) / 2 / w + gw * 0.15 / w, (h + gh) / 2 / h + gh * 0.15 / h)
        val c = ZoomFit.choose(a, lossless = 2.6, lensMax = 10.0)
        assertEquals(1.20, c.z, eps)
        assertEquals(ZoomFit.Limit.FIT, c.limit)
    }

    @Test fun viewIsExactlyTheBaseAtOneAndNullStaysNull() {
        val a = RoiFrac(0.123456789, 0.2, 0.7, 0.9)
        assertSame(a, ZoomFit.toView(a, 1.0))
        assertSame(a, ZoomFit.toBase(a, 1.0))
        assertNull(ZoomFit.toView(null, 1.7))
        assertEquals(0.3, ZoomFit.toViewAxis(0.3, 1.0), 0.0)
    }

    @Test fun theViewIsTheBaseScaledAboutTheCentre() {
        val v = ZoomFit.toView(RoiFrac(0.3, 0.25, 0.7, 0.75), 2.0)!!
        assertEquals(0.1, v.x0, eps); assertEquals(0.0, v.y0, eps)
        assertEquals(0.9, v.x1, eps); assertEquals(1.0, v.y1, eps)
        // An Area that would leave the view (never chosen by choose()) is not silently "the full frame".
        assertNull(ZoomFit.toView(RoiFrac(0.0, 0.0, 0.1, 0.1), 2.0))
    }

    /** Random Areas at their own chosen zoom: view inside [0,1], sides ≥ the base's (≥ MIN_SIDE), aspect kept, round trip. */
    @Test fun randomAreasMapInsideTheFrameAndRoundTrip() {
        val rnd = Random(20261004)
        var zoomed = 0
        repeat(5000) {
            val x0 = rnd.nextDouble(0.0, 0.9); val y0 = rnd.nextDouble(0.0, 0.9)
            val x1 = rnd.nextDouble(x0 + RoiFrac.MIN_SIDE, 1.0 + 1e-9).coerceAtMost(1.0)
            val y1 = rnd.nextDouble(y0 + RoiFrac.MIN_SIDE, 1.0 + 1e-9).coerceAtMost(1.0)
            val b = RoiFrac.orNull(x0, y0, x1, y1) ?: return@repeat
            val c = ZoomFit.choose(b, rnd.nextDouble(1.0, 4.0), rnd.nextDouble(1.0, 10.0))
            assertTrue(c.z == 1.0 || (c.z >= ZoomFit.MIN_USEFUL && c.z <= ZoomFit.MAX_ZOOM))
            assertTrue("z ${c.z} within 0.95·fit ${c.fit}", c.z == 1.0 || c.z <= ZoomFit.FIT_FILL * c.fit!! + 1e-12)
            val v = ZoomFit.toView(b, c.z)
            assertNotNull("$b at ${c.z}", v)
            v!!
            for (e in listOf(v.x0, v.y0, v.x1, v.y1)) assertTrue(e in 0.0..1.0)
            assertTrue(v.x1 - v.x0 >= (b.x1 - b.x0) - eps && v.y1 - v.y0 >= (b.y1 - b.y0) - eps)
            assertTrue(v.x1 - v.x0 >= RoiFrac.MIN_SIDE && v.y1 - v.y0 >= RoiFrac.MIN_SIDE)
            // The aspect is kept: the detection sample's MH is the same at every zoom.
            assertEquals((b.y1 - b.y0) / (b.x1 - b.x0), (v.y1 - v.y0) / (v.x1 - v.x0), 1e-9)
            assertEquals(DetectConst.mhFor(b.widthPx(1200), b.heightPx(1600)), DetectConst.mhFor(v.widthPx(1200), v.heightPx(1600)))
            val back = ZoomFit.toBase(v, c.z)!!
            assertEquals(b.x0, back.x0, eps); assertEquals(b.y0, back.y0, eps)
            assertEquals(b.x1, back.x1, eps); assertEquals(b.y1, back.y1, eps)
            if (c.z > 1.0) zoomed++
        }
        assertTrue("the property test exercised real zooms ($zoomed)", zoomed > 500)
    }

    @Test fun theZoomedFrameSitsCentredInTheBasePicture() {
        assertArrayEquals(intArrayOf(0, 0, 1200, 1600), ZoomFit.baseRect(1200, 1600, 1.0))
        assertArrayEquals(intArrayOf(300, 400, 600, 800), ZoomFit.baseRect(1200, 1600, 2.0))
        // A point at view fraction v lands at base fraction ½ + (v − ½)/z.
        for (z in listOf(1.25, 1.6, 2.0)) {
            val r = ZoomFit.baseRect(1200, 1600, z)
            for (v in listOf(0.0, 0.1, 0.5, 0.83, 1.0)) {
                val px = r[0] + v * r[2]
                assertEquals(ZoomFit.toBaseAxis(v, z) * 1200, px, 1.0)
            }
        }
    }

    @Test fun thePostFitCheckComparesCardHeightsAcrossZooms() {
        val pf = ZoomFit.PostFit()
        assertNull(pf.note(1.0, 430.0))
        assertNull(pf.note(1.0, 434.0))
        assertNull(pf.note(1.0, 428.0))
        val line = pf.note(1.5, 645.0)
        assertNotNull(line)
        assertTrue(line!!, line.contains("predicted 645 px") && line.contains("within ±3 %"))
        assertNull("only the first capture at a new zoom", pf.note(1.5, 650.0))
        val off = pf.note(1.0, 520.0)!!
        assertTrue(off, off.contains("BEYOND ±3 %"))
    }
}
