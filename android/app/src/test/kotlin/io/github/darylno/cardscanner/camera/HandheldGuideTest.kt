package io.github.darylno.cardscanner.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandheldGuideTest {
    private fun px(w: Int, h: Int, pad: Double) = HandheldGuide.frac(w, h, pad).let {
        doubleArrayOf(it.x0 * w, it.y0 * h, it.x1 * w, it.y1 * h)
    }

    @Test fun guideIsACentredCardAtEightyPercentOfTheLimitingSide() {
        for ((w, h) in listOf(1200 to 1600, 1536 to 2048, 1600 to 1200, 1000 to 3000)) {
            val g = px(w, h, 0.0)
            val gw = g[2] - g[0]; val gh = g[3] - g[1]
            assertEquals("$w×$h aspect", 63.0 / 88.0, gw / gh, 1e-9)
            assertTrue("$w×$h fits", gw <= w * 0.8 + 1e-9 && gh <= h * 0.8 + 1e-9)
            assertTrue("$w×$h limiting side is 80%", Math.abs(gw - w * 0.8) < 1e-6 || Math.abs(gh - h * 0.8) < 1e-6)
            assertEquals(w / 2.0, (g[0] + g[2]) / 2, 1e-6)
            assertEquals(h / 2.0, (g[1] + g[3]) / 2, 1e-6)
        }
    }

    @Test fun captureCropPadsTheGuideAndStaysInTheFrame() {
        // Portrait 1200×1600: guide 916.4×1280; +15% each side → x fits, y clamps.
        val g = px(1200, 1600, 0.0)
        val c = px(1200, 1600, HandheldGuide.PAD)
        val gw = g[2] - g[0]; val gh = g[3] - g[1]
        assertEquals(g[0] - gw * 0.15, c[0], 1e-6)
        assertEquals(g[2] + gw * 0.15, c[2], 1e-6)
        assertEquals(0.0, c[1], 1e-9)
        assertEquals(1600.0, c[3], 1e-9)
        // The default pad is the capture crop.
        assertEquals(HandheldGuide.frac(1200, 1600, HandheldGuide.PAD), HandheldGuide.frac(1200, 1600))
    }

    /** 1.1.2: the Area drawn for Auto off — a centred card at 60% of the limiting side, padded 15%. */
    @Test fun defaultAreaIsASmallerCentredCardWithSlack() {
        for ((w, h) in listOf(1200 to 1600, 1536 to 2048, 1600 to 1200)) {
            val a = HandheldGuide.defaultArea(w, h)
            val aw = (a.x1 - a.x0) * w; val ah = (a.y1 - a.y0) * h
            // the card inside it: the area less its 15% pad each side
            val cw = aw / 1.3; val ch = ah / 1.3
            assertEquals("$w×$h aspect", 63.0 / 88.0, cw / ch, 1e-6)
            assertTrue("$w×$h limiting side is 60%", Math.abs(cw - w * 0.6) < 1e-6 || Math.abs(ch - h * 0.6) < 1e-6)
            assertEquals(0.5, (a.x0 + a.x1) / 2, 1e-9)
            assertEquals(0.5, (a.y0 + a.y1) / 2, 1e-9)
            assertTrue("$w×$h inside the frame", a.x0 > 0 && a.y0 > 0 && a.x1 < 1 && a.y1 < 1)
            // smaller than the old Handheld guide: the phone sits further back
            val g = HandheldGuide.frac(w, h, 0.0)
            assertTrue(cw < (g.x1 - g.x0) * w)
        }
    }

    @Test fun anAreaIsDrawnOnlyWithAutoOffAndNoneSet() {
        val mine = io.github.darylno.cardscanner.core.RoiFrac(0.1, 0.1, 0.5, 0.5)
        assertEquals(HandheldGuide.defaultArea(1200, 1600), HandheldGuide.areaToDraw(false, null, 1200, 1600))
        assertEquals(null, HandheldGuide.areaToDraw(true, null, 1200, 1600))      // Auto on: full frame is fine
        assertEquals(null, HandheldGuide.areaToDraw(false, mine, 1200, 1600))     // never replaces the owner's
        assertEquals(null, HandheldGuide.areaToDraw(false, null, 0, 0))           // frame size not known yet
    }

    /** "Zoom to fit the Area": the default Tap Area (base fractions) allows 1.20× on a 4:3 portrait frame, limited by its fit. */
    @Test fun theDefaultAreaZoomsToOnePointTwo() {
        for ((w, h) in listOf(1200 to 1600, 1536 to 2048)) {
            val c = io.github.darylno.cardscanner.core.ZoomFit.choose(HandheldGuide.defaultArea(w, h), lossless = 2.6, lensMax = 10.0)
            assertEquals("$w×$h", 1.20, c.z, 1e-12)
            assertEquals(io.github.darylno.cardscanner.core.ZoomFit.Limit.FIT, c.limit)
        }
    }
}
