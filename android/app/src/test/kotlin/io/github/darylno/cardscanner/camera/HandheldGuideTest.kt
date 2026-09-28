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
}
