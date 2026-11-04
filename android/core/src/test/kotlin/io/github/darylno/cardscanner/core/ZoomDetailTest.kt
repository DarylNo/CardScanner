package io.github.darylno.cardscanner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assert.assertFalse
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * The zoom A/B's detail figure ([ZoomDetail.identRaster]): measured on the
 * identifier's OWN raster — the card warped to 630×880 (CardQuad.warpCard, what
 * CardExtract hands the OCR) — so it is comparable across zooms, and over the
 * collector strip the OCR actually reads ([OcrStrip.stripBounds]).
 *
 * And the zoom probe's coherence figure ([ZoomDetail.coherence]): an image
 * resolves everything against itself, nothing against independent noise, is
 * blind to gain / offset / a sub-pixel shift / a linear sharpen, and an image
 * upsampled from a coarser one never resolves past what the coarse one could
 * carry. The restore check's thumbnail tells a blurred copy of a card from
 * another card.
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

    // ── the zoom probe's coherence ──

    /** px/mm of the test rasters: Nyquist 6 c/mm, a 756×1056 card. */
    private val S = 12.0

    private fun raster(): Mat = ZoomDetail.rasterSize(S).let { Mat(it[1], it[0], CvType.CV_32F, Scalar(0.0)) }

    /**
     * A broadband card-sized texture (a 1/f-like field: octaves of blurred noise, σ 1 to
     * 16 px, each octave's amplitude ∝ its scale, so its power falls with frequency like
     * a photo's) of std ≈ 30 round 128, seeded.
     */
    private fun scene(seed: Long): Mat {
        Core.setRNGSeed(seed.toInt())
        val acc = raster()
        val n = raster(); val b = Mat()
        for (sigma in doubleArrayOf(1.0, 2.0, 4.0, 8.0, 16.0)) {
            Core.randn(n, 0.0, 1.0)
            Imgproc.GaussianBlur(n, b, Size(0.0, 0.0), sigma)
            Core.addWeighted(acc, 1.0, b, sigma, 0.0, acc)
        }
        val mean = org.opencv.core.MatOfDouble(); val sd = org.opencv.core.MatOfDouble()
        Core.meanStdDev(acc, mean, sd)
        acc.convertTo(acc, -1, 30.0 / sd.toArray()[0], 128.0 - 30.0 * mean.toArray()[0] / sd.toArray()[0])
        n.release(); b.release()
        return acc
    }

    /** [m] plus N(0, [sigma]) (seeded): a new Mat. */
    private fun noisy(m: Mat, sigma: Double, seed: Long): Mat {
        Core.setRNGSeed(seed.toInt())
        val n = Mat(m.size(), CvType.CV_32F); Core.randn(n, 0.0, sigma)
        val out = Mat(); Core.add(m, n, out); n.release()
        return out
    }

    @Test
    fun identicalImagesResolveToTheRasterNyquist() {
        val a = scene(1)
        for (r in ZoomDetail.Region.values()) {
            val c = ZoomDetail.coherence(a, a.clone(), S, r)
            assertTrue("$r: tiles ${c.tiles}", c.tiles >= if (r == ZoomDetail.Region.CARD) 140 else 24)
            assertTrue("$r: every band coherent ${c.gamma2.toList()}", c.gamma2.filter { !it.isNaN() }.all { it > 0.999 })
            assertEquals("$r: never falls through 0.5 → the raster's Nyquist", S / 2, c.fRes, 0.0)
            assertEquals(1.0, c.at(5.0), 1e-3)
        }
        assertEquals("bands of 0.25 c/mm up to the Nyquist", 24, ZoomDetail.coherence(a, a, S).freqs.size)
    }

    @Test
    fun independentNoiseHasNoCoherence() {
        val a = noisy(raster(), 20.0, 2); val b = noisy(raster(), 20.0, 3)
        for (r in ZoomDetail.Region.values()) {
            val c = ZoomDetail.coherence(a, b, S, r)
            val g = c.gamma2.filter { !it.isNaN() }
            assertTrue("$r: ${g.max()}", g.max() < 0.15)
            assertTrue("$r: f_res ${c.fRes}", c.fRes < 0.3)
        }
    }

    @Test
    fun gainOffsetShiftAndALinearSharpenChangeNothing() {
        val sc = scene(4)
        val ref = noisy(sc, 6.0, 5)
        val img = noisy(sc, 6.0, 6)
        val f0 = ZoomDetail.coherence(img, ref, S).fRes
        val s0 = ZoomDetail.coherence(img, ref, S, ZoomDetail.Region.STRIP).fRes
        assertTrue("a measurable f_res, not an extreme: $f0", f0 > 1.0 && f0 < S / 2 - 1.0)
        // Gain + offset (AE), a sub-pixel shift (the finder), an unsharp mask (ISP sharpening) — all after the noise.
        val g = Mat(); img.convertTo(g, -1, 1.7, 30.0)
        val sh = Mat()
        Imgproc.warpAffine(g, sh, Mat(2, 3, CvType.CV_64F).also { it.put(0, 0, 1.0, 0.0, 0.43, 0.0, 1.0, -0.71) },
            g.size(), Imgproc.INTER_CUBIC, Core.BORDER_REFLECT)
        val blur = Mat(); Imgproc.GaussianBlur(sh, blur, Size(0.0, 0.0), 1.0)
        val sharp = Mat(); Core.addWeighted(sh, 2.2, blur, -1.2, 0.0, sharp)
        val f1 = ZoomDetail.coherence(sharp, ref, S).fRes
        val s1 = ZoomDetail.coherence(sharp, ref, S, ZoomDetail.Region.STRIP).fRes
        println("coherence invariance: card f_res %.2f → %.2f c/mm, strip %.2f → %.2f".format(f0, f1, s0, s1))
        assertEquals("card: within one band", f0, f1, ZoomDetail.BAND)
        assertEquals("strip: within one band", s0, s1, ZoomDetail.BAND)
        // The shift is really taken out: the same image shifted 2.6 px still resolves what it did.
        val far = Mat()
        Imgproc.warpAffine(img, far, Mat(2, 3, CvType.CV_64F).also { it.put(0, 0, 1.0, 0.0, 2.6, 0.0, 1.0, 1.3) },
            img.size(), Imgproc.INTER_CUBIC, Core.BORDER_REFLECT)
        assertEquals(f0, ZoomDetail.coherence(far, ref, S).fRes, ZoomDetail.BAND)
    }

    /**
     * Downsampled ×½ (INTER_AREA, the ISP's scaler) and upsampled back: the coarse
     * image's square passband ends at Nyquist N = S/4 on each axis — its corner at √2·N
     * — so no band past √2·N is resolved, however the interpolation sharpens it, while
     * the same scene never downsampled resolves well past it.
     */
    @Test
    fun anUpsampledImageNeverResolvesPastItsSource() {
        val sc = scene(7)
        val ref = noisy(sc, 1.0, 8)
        val full = noisy(sc, 1.0, 9)
        val low = Mat(); Imgproc.resize(full, low, Size(), 0.5, 0.5, Imgproc.INTER_AREA)
        val up = Mat(); Imgproc.resize(low, up, sc.size(), 0.0, 0.0, Imgproc.INTER_CUBIC)
        val upSharp = Mat(); val bl = Mat(); Imgproc.GaussianBlur(up, bl, Size(0.0, 0.0), 1.0)
        Core.addWeighted(up, 2.5, bl, -1.5, 0.0, upSharp)
        val srcNyq = S / 4
        val fFull = ZoomDetail.coherence(full, ref, S).fRes
        val fUp = ZoomDetail.coherence(up, ref, S).fRes
        val fSharp = ZoomDetail.coherence(upSharp, ref, S).fRes
        println("upsampled: full %.2f · ×½ then back %.2f · and sharpened %.2f c/mm (source Nyquist %.2f, its corner %.2f)"
            .format(fFull, fUp, fSharp, srcNyq, srcNyq * sqrt(2.0)))
        assertTrue("the coarse image resolves most of what it carries ($fUp)", fUp > 0.8 * srcNyq)
        assertTrue("…never past its passband's corner ($fUp)", fUp <= srcNyq * sqrt(2.0))
        assertTrue("sharpening adds nothing real ($fSharp)", fSharp <= srcNyq * sqrt(2.0))
        assertTrue("the full-resolution copy resolves past it ($fFull)", fFull > srcNyq * sqrt(2.0) + ZoomDetail.BAND)
    }

    /** A card in a 900×1200 grey frame at [quad], the face of SyntheticTray.cardFace([seed]). */
    private fun cardFrame(seed: Long, quad: FloatArray): Mat {
        val face = SyntheticTray.cardFace(seed, SyntheticTray.Border.BLACK)
        val tex = Mat(SyntheticTray.TEX_H, SyntheticTray.TEX_W, CvType.CV_8U)
        tex.put(0, 0, ByteArray(face.size) { face[it].toByte() })
        val frame = Mat(1200, 900, CvType.CV_8U, Scalar(200.0))
        val src = MatOfPoint2f(Point(0.0, 0.0), Point(SyntheticTray.TEX_W.toDouble(), 0.0),
            Point(SyntheticTray.TEX_W.toDouble(), SyntheticTray.TEX_H.toDouble()), Point(0.0, SyntheticTray.TEX_H.toDouble()))
        val m = Imgproc.getPerspectiveTransform(src, CardQuad.quadMat(quad))
        Imgproc.warpPerspective(tex, frame, m, frame.size(), Imgproc.INTER_LINEAR, Core.BORDER_TRANSPARENT)
        return frame
    }

    @Test
    fun aBlurredSameCardKeepsThumbnailNccAndADifferentCardDoesNot() {
        val q = floatArrayOf(200f, 150f, 650f, 160f, 640f, 790f, 190f, 780f)
        val a = ZoomDetail.thumbnail(cardFrame(11, q), q)
        assertEquals(ZoomDetail.THUMB_W * ZoomDetail.THUMB_H, a.size)
        assertEquals("a thumbnail against itself", 1.0, ZoomDetail.ncc(a, a), 1e-4)
        val soft = Mat(); Imgproc.GaussianBlur(cardFrame(11, q), soft, Size(0.0, 0.0), 2.5)
        val nudged = FloatArray(8) { q[it] + if (it % 2 == 0) 3f else -2f }   // the finder's quad a few px off
        val same = ZoomDetail.ncc(a, ZoomDetail.thumbnail(soft, nudged))
        val other = (12L..16L).map { ZoomDetail.ncc(a, ZoomDetail.thumbnail(cardFrame(it, q), q)) }
        println("thumbnail NCC: same card blurred + quad nudged %.3f · other cards %s".format(same, other.joinToString { "%.3f".format(it) }))
        assertTrue("the same card, blurred and a few px off: $same", same >= 0.90)
        assertTrue("other cards of the same frame layout: $other", other.all { it < 0.90 })
        assertFalse(same.isNaN())
    }
}
