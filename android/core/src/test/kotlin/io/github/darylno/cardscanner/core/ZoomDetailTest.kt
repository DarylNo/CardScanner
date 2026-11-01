package io.github.darylno.cardscanner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The zoom A/B's detail figure ([ZoomDetail.identRaster]): measured on the
 * identifier's OWN raster — the card warped to 630×880 (CardQuad.warpCard, what
 * CardExtract hands the OCR) — so it is comparable across zooms, and over the
 * collector strip the OCR actually reads ([OcrStrip.stripBounds]).
 */
class ZoomDetailTest {
    companion object {
        @BeforeClass @JvmStatic fun setUp() { OpenCvTest.load() }
    }

    /** A 1000×1000 grey frame with an axis-aligned 630×880 card at (100, 60): the warp is a pure shift. */
    private val quad = floatArrayOf(100f, 60f, 730f, 60f, 730f, 940f, 100f, 940f)

    private fun frame(): Mat = Mat(1000, 1000, CvType.CV_8UC3, Scalar(128.0, 128.0, 128.0))

    /** A 4-px checkerboard over [r] (card coordinates) of [m]. */
    private fun checker(m: Mat, r: Rect) {
        for (y in r.y until r.y + r.height) for (x in r.x until r.x + r.width) {
            val v = if (((x / 4) + (y / 4)) % 2 == 0) 40.0 else 220.0
            m.put(60 + y, 100 + x, v, v, v)
        }
    }

    @Test
    fun identRasterUsesTheOcrStrip() {
        val b = OcrStrip.stripBounds(CardQuad.CARD_W, CardQuad.CARD_H)
        assertArrayEquals("the OCR's own strip of the 630×880 warp", b, ZoomDetail.stripBounds())
        assertArrayEquals(intArrayOf(774, 871, 12, 378), b)
        val (y0, y1, x0, x1) = b.toList()
        // Print only inside the strip: the strip reads it, the flat card around it reads none.
        val f = frame()
        checker(f, Rect(x0, y0, x1 - x0, y1 - y0))
        val inStrip = ZoomDetail.identRaster(f, quad)
        assertTrue("the strip's print is read (${inStrip.strip})", inStrip.strip > 1000)
        // Print elsewhere on the card (the art box) moves the card figure, never the strip's.
        checker(f, Rect(60, 100, 500, 400))
        val more = ZoomDetail.identRaster(f, quad)
        assertEquals("pixels outside the strip never reach it", inStrip.strip, more.strip, 0.0)
        assertTrue("the card figure sees the art box (${inStrip.card} → ${more.card})", more.card > inStrip.card * 2)
        // Just outside the strip (the card's own bottom border): the strip does not see it.
        val g = frame()
        checker(g, Rect(x1 + 2, y0, 200, y1 - y0))
        checker(g, Rect(x0, y1 + 1, x1 - x0, CardQuad.CARD_H - y1 - 1))
        assertTrue("print beside the strip stays out of it (${ZoomDetail.identRaster(g, quad).strip})", ZoomDetail.identRaster(g, quad).strip < 50)
    }

    @Test
    fun aSofterCardReadsLessDetailAndGreyEqualsColour() {
        val f = frame()
        checker(f, Rect(0, 0, CardQuad.CARD_W, CardQuad.CARD_H))
        val sharp = ZoomDetail.identRaster(f, quad)
        val soft = Mat(); Imgproc.GaussianBlur(f, soft, Size(0.0, 0.0), 2.0)
        val blurred = ZoomDetail.identRaster(soft, quad)
        assertTrue("strip ${sharp.strip} > ${blurred.strip}", sharp.strip > blurred.strip * 2)
        assertTrue("card ${sharp.card} > ${blurred.card}", sharp.card > blurred.card * 2)
        val grey = Mat(); Imgproc.cvtColor(f, grey, Imgproc.COLOR_BGR2GRAY)
        val g = ZoomDetail.identRaster(grey, quad)
        assertEquals(sharp.strip, g.strip, 1e-9); assertEquals(sharp.card, g.card, 1e-9)
    }
}
