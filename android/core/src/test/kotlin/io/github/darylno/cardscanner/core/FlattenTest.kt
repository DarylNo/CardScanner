package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * The flatten constants are the contract with tests/phone_flatten_ref.py —
 * each is pinned to the Python arithmetic that produced it — and the margin
 * logic is checked on geometry simple enough to work out by hand.
 */
class FlattenTest {
    companion object {
        @BeforeClass @JvmStatic
        fun load() = OpenCvTest.load()

        /** Server card_detect.find_card_quad's area window (min_area_frac, max_area). */
        const val SERVER_MIN_FRAC = CardQuad.MIN_AREA_FRAC
        const val SERVER_MAX_FRAC = CardQuad.MAX_AREA_FRAC
    }

    @Test
    fun constantsArePythonsArithmetic() {
        assertEquals(listOf(0.08, 0.06, 0.04), Flatten.MARGINS)
        assertEquals(1.25, Flatten.SCALE, 0.0)
        // round(630 * 1.25) = round(787.5) → 788 in Python (half-EVEN; 788 is even).
        assertEquals(787.5, CardQuad.CARD_W * Flatten.SCALE, 0.0)
        assertEquals(788, Math.rint(CardQuad.CARD_W * Flatten.SCALE).toInt())
        assertEquals(788, Flatten.CARD_W)
        assertEquals(1100, Math.rint(CardQuad.CARD_H * Flatten.SCALE).toInt())
        assertEquals(1100, Flatten.CARD_H)
        // card_detect.py's own warp size, which SCALE multiplies.
        assertEquals(630, CardQuad.CARD_W); assertEquals(880, CardQuad.CARD_H)
    }

    @Test
    fun layoutPixelsAreHalfEvenRounded() {
        assertEquals(Flatten.Layout(63, 88, 914, 1276), Flatten.layout(0.08))
        assertEquals(Flatten.Layout(47, 66, 882, 1232), Flatten.layout(0.06))
        // 788·0.04 = 31.52 → 32 (not a tie; guards against truncation).
        assertEquals(Flatten.Layout(32, 44, 852, 1188), Flatten.layout(0.04))
        // A true tie goes to the even neighbour, like Python: 0.5/788 → 0.5 px → 0.
        assertEquals(0, Flatten.layout(0.5 / 788).mx)
        assertEquals(2, Flatten.layout(2.5 / 788).mx)
    }

    @Test
    fun cardFractionOfEveryLayoutSitsInTheServersWindow() {
        // The server re-detects the card on the upload: it must be more than
        // its min-area floor and LESS than 92% (above that it is "the frame itself").
        for (m in Flatten.MARGINS) {
            val lay = Flatten.layout(m)
            val frac = (Flatten.CARD_W.toDouble() * Flatten.CARD_H) / (lay.outW.toDouble() * lay.outH)
            assertTrue("margin $m: card is $frac of the upload", frac > SERVER_MIN_FRAC && frac < SERVER_MAX_FRAC)
        }
        // 4% (the smallest) was measured at 84% — keep that headroom visible.
        val smallest = Flatten.layout(Flatten.MARGINS.last())
        assertEquals(0.8564, 788.0 * 1100 / (smallest.outW * smallest.outH), 1e-4)
    }

    /** Axis-aligned card quad at (x0,y0) of exactly CARD_W×CARD_H frame pixels (scale 1). */
    private fun cardAt(x0: Float, y0: Float) = floatArrayOf(
        x0, y0, x0 + Flatten.CARD_W, y0, x0 + Flatten.CARD_W, y0 + Flatten.CARD_H, x0, y0 + Flatten.CARD_H,
    )

    @Test
    fun marginStepsDownToWhatFitsInsideTheFrame() {
        // At scale 1 the margin in frame pixels is the layout's (mx, my):
        // 8% = (63, 88), 6% = (47, 66), 4% = (32, 44). Frame leaves 50 px left/right, 100 top/bottom.
        val w = 50 + Flatten.CARD_W + 50; val h = 100 + Flatten.CARD_H + 100
        assertEquals(0.06, Flatten.chooseMargin(cardAt(50f, 100f), w, h))       // 63 > 50 ≥ 47
        assertTrue(!Flatten.marginFits(cardAt(50f, 100f), w, h, 0.08))
        assertEquals(0.04, Flatten.chooseMargin(cardAt(40f, 100f), w + 20, h))  // 47 > 40 ≥ 32
        assertNull(Flatten.chooseMargin(cardAt(20f, 100f), w + 60, h))          // 32 > 20: upload as-is
        assertEquals(0.08, Flatten.chooseMargin(cardAt(100f, 100f), 100 + Flatten.CARD_W + 100, h))
        // The far edges count too: output (outW, outH) maps to x0 + CARD_W + mx ≤ frameW - 1.
        assertEquals(0.06, Flatten.chooseMargin(cardAt(100f, 100f), 100 + Flatten.CARD_W + 50, h))
        // Vertical: 88 > 70 ≥ 66 → 6%.
        assertEquals(0.06, Flatten.chooseMargin(cardAt(100f, 70f), 1000, 70 + Flatten.CARD_H + 100))
    }

    @Test
    fun marginFitsIsExactlyTheInsideTest() {
        // Python: every mapped output corner in [0, frame_w - 1] × [0, frame_h - 1].
        // Card of 394×550 at (40, 60): scale 0.5, so the 8% margin is (31.5, 44) frame px.
        val q = floatArrayOf(40f, 60f, 434f, 60f, 434f, 610f, 40f, 610f)
        assertTrue(Flatten.marginFits(q, 434 + 32 + 1, 610 + 46, 0.08))   // right edge at 465.5 ≤ 466
        assertTrue(!Flatten.marginFits(q, 434 + 31, 610 + 45, 0.08))      // 465.5 > 464
        assertTrue(!Flatten.marginFits(q, 434 + 33, 610 + 44, 0.08))      // bottom 654 > 653
    }

    @Test
    fun flattenIsAPureShiftAtScaleOneAndReplicatesBorders() {
        // A frame with a unique value per pixel region; card quad at scale 1 →
        // the transform is a translation by (mx - x0, my - y0), so the output is
        // the frame crop around the card, pixel for pixel.
        val w = 1000; val h = 1400
        val frame = Mat(h, w, CvType.CV_8UC3)
        val rnd = java.util.Random(1)
        frame.put(0, 0, ByteArray(w * h * 3) { rnd.nextInt(256).toByte() })
        val x0 = 90; val y0 = 120
        val flat = Flatten.flatten(frame, cardAt(x0.toFloat(), y0.toFloat()), 0.08)
        val lay = Flatten.layout(0.08)
        try {
            assertEquals(lay.outW, flat.cols()); assertEquals(lay.outH, flat.rows())
            assertEquals(CvType.CV_8UC3, flat.type())
            val crop = frame.submat(y0 - lay.my, y0 - lay.my + lay.outH, x0 - lay.mx, x0 - lay.mx + lay.outW)
            val diff = Mat()
            Core.absdiff(crop, flat, diff)
            val mad = Core.mean(diff).`val`.take(3).average()
            assertTrue("scale-1 flatten should reproduce the crop, MAD $mad", mad < 0.01)
            diff.release(); crop.release()
        } finally {
            flat.release(); frame.release()
        }

        // BORDER_REPLICATE: a margin reaching past the frame edge repeats the
        // edge pixels instead of painting black (choose_margin normally
        // prevents this; flatten itself must still behave like the reference).
        val grey = Mat(1300, 900, CvType.CV_8UC3, Scalar(90.0, 120.0, 150.0))
        val out = Flatten.flatten(grey, cardAt(10f, 10f), 0.08)
        try {
            val min = Core.minMaxLoc(out.reshape(1)).minVal
            assertEquals("no black border fill", 90.0, min, 0.0)
        } finally {
            out.release(); grey.release()
        }
    }

    @Test
    fun transformMapsTheQuadOntoTheInsetCard() {
        val q = floatArrayOf(250f, 110f, 640f, 80f, 690f, 620f, 300f, 660f)
        val m = Flatten.transform(q, 0.06)
        val lay = Flatten.layout(0.06)
        val src = CardQuad.quadMat(q)
        val dst = org.opencv.core.MatOfPoint2f()
        try {
            Core.perspectiveTransform(src, dst, m)
            val p = dst.toArray()
            val want = listOf(
                lay.mx to lay.my, lay.mx + Flatten.CARD_W to lay.my,
                lay.mx + Flatten.CARD_W to lay.my + Flatten.CARD_H, lay.mx to lay.my + Flatten.CARD_H,
            )
            for (i in 0 until 4) {
                assertEquals(want[i].first.toDouble(), p[i].x, 1e-3)
                assertEquals(want[i].second.toDouble(), p[i].y, 1e-3)
            }
            assertEquals(CvType.CV_64F, m.type())
        } finally {
            m.release(); src.release(); dst.release()
        }
    }

    @Test
    fun serverRedetectsTheCardOnAFlattenedFixture() {
        // The whole point of the margin: the server (this port of its detector)
        // must find the card again in the upload, as a quad close to the inset
        // card rectangle — not the full image, not nothing.
        for (name in listOf("dark_tray_white_border", "white_tray_black_border", "small_card_full_frame")) {
            val img = OpenCvTest.readBgr("$name.png")
            try {
                val q = requireNotNull(CardQuad.find(img)) { "$name: no quad" }
                val m = requireNotNull(Flatten.chooseMargin(q, img.cols(), img.rows())) { "$name: no margin" }
                val flat = Flatten.flatten(img, q, m)
                val lay = Flatten.layout(m)
                val again = requireNotNull(CardQuad.find(flat)) { "$name: server could not re-detect the flattened card" }
                val area = Imgproc.contourArea(org.opencv.core.MatOfPoint2f(*Array(4) {
                    org.opencv.core.Point(again[2 * it].toDouble(), again[2 * it + 1].toDouble())
                }))
                val frac = area / (lay.outW.toDouble() * lay.outH)
                assertTrue("$name: re-detected card is $frac of the upload", frac in 0.6..SERVER_MAX_FRAC)
                flat.release()
            } finally {
                img.release()
            }
        }
    }
}
