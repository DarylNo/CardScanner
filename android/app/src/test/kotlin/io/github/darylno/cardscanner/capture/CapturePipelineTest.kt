package io.github.darylno.cardscanner.capture

import io.github.darylno.cardscanner.core.Nv21Bgr
import io.github.darylno.cardscanner.core.Nv21Frame
import io.github.darylno.cardscanner.core.RoiFrac
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs

/**
 * The capture decisions on NV21 frames synthesized from the server-exported
 * detect fixtures (android/core/src/test/resources/detect): the scene goes
 * upright BGR → sensor NV21 (rotation 90, as a portrait phone delivers it) →
 * the pipeline, so rotation, cropping, quad offset and margin choice are all
 * exercised on the real data path.
 */
class CapturePipelineTest {
    companion object {
        private lateinit var dir: File
        private lateinit var expected: JSONObject

        @BeforeClass @JvmStatic fun setUp() {
            nu.pattern.OpenCV.loadLocally()
            dir = listOf("../core/src/test/resources/detect", "core/src/test/resources/detect", "android/core/src/test/resources/detect")
                .map(::File).first { File(it, "expected.json").isFile }
            expected = JSONObject(File(dir, "expected.json").readText()).getJSONObject("scenes")
        }
    }

    private val pipeline = CapturePipeline()

    private fun scene(name: String): Mat = Imgcodecs.imread(File(dir, "$name.png").path).also { assertFalse(it.empty()) }

    private fun frames(bgr: Mat, n: Int = 3, rotation: Int = 90): List<Nv21Frame> =
        (0 until n).map { Nv21Bgr.fromUprightBgr(bgr, rotation, it * 100_000_000L) }

    private fun decode(jpeg: ByteArray): Mat = Imgcodecs.imdecode(MatOfByte(*jpeg), Imgcodecs.IMREAD_COLOR)

    private fun meanAbsDiff(a: Mat, b: Mat): Double {
        val d = Mat(); Core.absdiff(a, b, d)
        val m = Core.mean(d); d.release()
        return (m.`val`[0] + m.`val`[1] + m.`val`[2]) / 3
    }

    private fun expectedQuad(name: String): FloatArray? {
        val q = expected.getJSONObject(name).optJSONArray("quad") ?: return null
        return FloatArray(8) { q.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
    }

    private fun expectedMargin(name: String): Double? =
        expected.getJSONObject(name).let { if (it.isNull("margin")) null else it.getDouble("margin") }

    @Test fun flattenedScenesMatchTheServersDecision() {
        for (name in listOf("dark_tray_white_border", "glare_gradient_tray", "white_tray_black_border", "sideways_card")) {
            val bgr = scene(name)
            val r = pipeline.process(frames(bgr), null)
            val m = expectedMargin(name)
            assertNotNull("$name: fixture expects a margin", m)
            assertTrue("$name should flatten", r.flattened)
            assertEquals(name, m!!, r.margin!!, 1e-9)
            val flat = decode(r.primary)
            val flatSize = expected.getJSONObject(name).getJSONArray("flat_size")
            assertEquals("$name flat width", flatSize.getInt(0), flat.cols())
            assertEquals("$name flat height", flatSize.getInt(1), flat.rows())
            val eq = expectedQuad(name)!!
            val q = r.quad!!
            for (i in 0 until 8) assertTrue("$name corner $i: ${q[i]} vs ${eq[i]}", abs(q[i] - eq[i]) < 3f)
            // Fallbacks = all 3 raw frames, upright, whole frame (no roi).
            assertEquals(3, r.fallbacks.size)
            for (fb in r.fallbacks) {
                val d = decode(fb)
                assertEquals(bgr.cols(), d.cols()); assertEquals(bgr.rows(), d.rows())
                assertTrue("$name fallback is the upright scene", meanAbsDiff(d, bgr) < 6.0)
            }
            assertEquals(listOf("sharpness", "convert", "quad", "flatten", "encode", "total"), r.timingsMs.keys.toList())
        }
    }

    @Test fun noQuadOrNoMarginUploadsTheSharpestRawCrop() {
        for (name in listOf("empty_tray", "card_near_frame_edge")) {
            val bgr = scene(name)
            val r = pipeline.process(frames(bgr), null)
            assertFalse("$name must not flatten", r.flattened)
            assertNull(r.margin)
            assertEquals(3, r.fallbacks.size)     // all 3 raw frames — the server's retry
            val p = decode(r.primary)
            assertEquals(bgr.cols(), p.cols()); assertEquals(bgr.rows(), p.rows())
            assertTrue(meanAbsDiff(p, bgr) < 6.0)
        }
        // The edge scene HAS a quad — only the margin doesn't fit.
        assertNotNull(pipeline.process(frames(scene("card_near_frame_edge")), null).quad)
        assertNull(pipeline.process(frames(scene("empty_tray")), null).quad)
    }

    @Test fun roiCropFindsTheQuadAndOffsetsItIntoTheFullFrame() {
        val name = "dark_tray_white_border"
        val bgr = scene(name)                                   // 960×720, card ≈ x 243..695, y 75..663
        val roi = RoiFrac(0.15, 0.05, 0.85, 0.98)
        val r = pipeline.process(frames(bgr), roi)
        val px = roi.toPixels(bgr.cols(), bgr.rows())
        assertEquals(px, r.crop)
        assertTrue(r.flattened)
        val eq = expectedQuad(name)!!
        for (i in 0 until 8) assertTrue("corner $i: ${r.quad!![i]} vs ${eq[i]}", abs(r.quad!![i] - eq[i]) < 3f)
        // Raw crops are the upright ROI, not the whole frame.
        val want = bgr.submat(Rect(px.x, px.y, px.w, px.h))
        for (fb in r.fallbacks) {
            val d = decode(fb)
            assertEquals(px.w, d.cols()); assertEquals(px.h, d.rows())
            assertTrue(meanAbsDiff(d, want) < 6.0)
        }
    }

    @Test fun marginIsChosenAgainstTheFullFrameNotTheCrop() {
        // A tight area around the card: the 8% margin reaches outside the AREA but not the frame.
        val name = "dark_tray_white_border"
        val bgr = scene(name)
        val roi = RoiFrac(0.24, 0.09, 0.74, 0.94)
        val r = pipeline.process(frames(bgr), roi)
        assertTrue(r.flattened)
        assertEquals(expectedMargin(name)!!, r.margin!!, 1e-9)
    }

    @Test fun sharpestFrameWinsAndOthersBecomeFallbacks() {
        val bgr = scene("empty_tray")
        val card = scene("card_near_frame_edge")
        val blur = Mat(); Imgproc.GaussianBlur(card, blur, Size(15.0, 15.0), 0.0)
        val fs = listOf(Nv21Bgr.fromUprightBgr(blur, 90, 0), Nv21Bgr.fromUprightBgr(card, 90, 1), Nv21Bgr.fromUprightBgr(bgr, 90, 2))
        val r = pipeline.process(fs, null)
        assertEquals(1, r.sharpestIndex)
        assertTrue(r.sharpness[1] > r.sharpness[0] && r.sharpness[1] > r.sharpness[2])
        assertFalse(r.flattened)
        assertTrue(meanAbsDiff(decode(r.primary), card) < 6.0)
        assertEquals(3, r.fallbacks.size)         // all frames in capture order, sharpest included
        assertTrue(meanAbsDiff(decode(r.fallbacks[0]), blur) < 6.0)
        assertTrue(meanAbsDiff(decode(r.fallbacks[1]), card) < 6.0)
        assertTrue(meanAbsDiff(decode(r.fallbacks[2]), bgr) < 6.0)
    }

    @Test fun rotationZeroAndSingleFrameWork() {
        val bgr = scene("portrait_frame")
        val r = pipeline.process(frames(bgr, n = 1, rotation = 0), null)
        assertEquals(expectedMargin("portrait_frame") != null, r.flattened)
        assertEquals(1, r.fallbacks.size)
    }

    @Test fun rawCropsAreCappedAt1600() {
        val big = Mat()
        Imgproc.resize(scene("dark_tray_white_border"), big, Size(2048.0, 1536.0))
        val r = pipeline.process(frames(big, n = 2), null)
        for (fb in r.fallbacks) {
            val d = decode(fb)
            assertEquals(1600, maxOf(d.cols(), d.rows()))
            assertEquals(1200, minOf(d.cols(), d.rows()))
        }
    }

    @Test fun submitRunsOnTheCaptureThread() {
        val q = java.util.concurrent.ArrayBlockingQueue<Pair<String, Result<CaptureResult>>>(1)
        assertTrue(pipeline.submit(frames(scene("empty_tray")), null) { q.put(Thread.currentThread().name to it) })
        val (thread, res) = q.poll(30, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("scan-capture", thread)
        assertTrue(res.isSuccess)
        assertNotNull(pipeline.lastSummary)
        pipeline.shutdown()
        assertFalse(pipeline.submit(frames(scene("empty_tray")), null) { })
    }
}
