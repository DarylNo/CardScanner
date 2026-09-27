package io.github.darylno.cardscanner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer

/**
 * NV21 frames → upright Mats: the capture pipeline's conversions must put
 * the card where the grey sampler saw it, for every sensor rotation, and a
 * reusable (oversized) ring slot must convert like an exact-size one.
 */
class Nv21BgrTest {
    companion object {
        @BeforeClass @JvmStatic
        fun load() = OpenCvTest.load()

        val ROTATIONS = intArrayOf(0, 90, 180, 270)
    }

    private fun bytes(m: Mat): ByteArray = ByteArray((m.total() * m.channels()).toInt()).also { m.get(0, 0, it) }

    @Test
    fun frameValidatesLayout() {
        val f = Nv21Frame(ByteArray(640 * 480 * 3 / 2 + 1000), 640, 480, 90, 5L)   // oversized ring slot is fine
        assertEquals(640 * 480 * 3 / 2, f.byteCount)
        assertEquals(480, f.uprightWidth); assertEquals(640, f.uprightHeight)
        for ((w, h, n) in listOf(Triple(641, 480, 700000), Triple(640, 480, 1000), Triple(0, 480, 1))) {
            try { Nv21Frame(ByteArray(n), w, h, 0, 0L); fail("accepted ${w}x$h with $n bytes") } catch (_: IllegalArgumentException) {}
        }
        try { Nv21Frame(ByteArray(640 * 480 * 2), 640, 480, 45, 0L); fail("accepted rotation 45") } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun roundTripThroughNv21IsUprightAndFaithful() {
        val src = OpenCvTest.readBgr("dark_tray_white_border.png")
        try {
            for (rot in ROTATIONS) {
                val frame = Nv21Bgr.fromUprightBgr(src, rot, 42L)
                assertEquals("sensor width rot $rot", if (rot % 180 == 0) 960 else 720, frame.width)
                assertEquals(960, frame.uprightWidth); assertEquals(720, frame.uprightHeight)
                // A reusable ring slot: bigger array, same frame.
                val slot = Nv21Frame(frame.data.copyOf(frame.byteCount + 4096), frame.width, frame.height, rot, 42L)
                val back = Nv21Bgr.toUprightBgr(frame)
                val back2 = Nv21Bgr.toUprightBgr(slot)
                try {
                    assertEquals(src.size(), back.size())
                    assertEquals(CvType.CV_8UC3, back.type())
                    assertArrayEquals("oversized slot converts identically, rot $rot", bytes(back), bytes(back2))
                    val d = Mat(); Core.absdiff(src, back, d)
                    val mad = Core.mean(d).`val`.take(3).average(); d.release()
                    // BGR→I420→BGR with 2×2 chroma subsampling: small, but not zero.
                    assertTrue("rot $rot round-trip MAD $mad", mad < 3.0)
                } finally {
                    back.release(); back2.release()
                }
            }
        } finally {
            src.release()
        }
    }

    @Test
    fun detectionSurvivesTheNv21RoundTrip() {
        // What the phone will actually do: the card arrives as sensor NV21 and
        // is found in the upright BGR conversion. Chroma subsampling must not
        // move the corners meaningfully off the server's answer.
        val fixtures = org.json.JSONObject(OpenCvTest.detectFile("expected.json").readText()).getJSONObject("scenes")
        val report = StringBuilder()
        for (name in listOf("dark_tray_white_border", "white_tray_black_border", "sideways_card", "portrait_frame")) {
            val entry = fixtures.getJSONObject(name)
            val a = entry.getJSONArray("quad")
            val want = FloatArray(8) { a.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
            val src = OpenCvTest.readBgr("$name.png")
            try {
                for (rot in intArrayOf(90, 270)) {
                    val bgr = Nv21Bgr.toUprightBgr(Nv21Bgr.fromUprightBgr(src, rot))
                    val got = requireNotNull(CardQuad.find(bgr)) { "$name rot $rot: no quad after NV21" }
                    val delta = CardQuadParityTest.maxCornerDelta(want, got)
                    report.append("$name rot $rot: max corner Δ %.3f px\n".format(delta))
                    assertTrue("$name rot $rot: Δ $delta px", delta <= 2.0)
                    bgr.release()
                }
            } finally {
                src.release()
            }
        }
        println(report)
    }

    @Test
    fun uprightGrayIsTheRotatedLumaAndItsRoiCrop() {
        val src = OpenCvTest.readBgr("glare_gradient_tray.png")
        try {
            for (rot in ROTATIONS) {
                val frame = Nv21Bgr.fromUprightBgr(src, rot)
                // Oracle: the Y plane as a Mat, rotated by OpenCV.
                val y = Mat(frame.height, frame.width, CvType.CV_8UC1); y.put(0, 0, frame.data)
                val want = Mat()
                val code = Nv21Bgr.rotateCode(rot)
                if (code == null) y.copyTo(want) else Core.rotate(y, want, code)
                val full = Nv21Bgr.uprightGray(frame)
                assertEquals(want.size(), full.size())
                assertArrayEquals("uprightGray rot $rot", bytes(want), bytes(full))

                val roi = RoiFrac(0.13, 0.21, 0.77, 0.94)
                val px = roi.toPixels(frame.uprightWidth, frame.uprightHeight)
                val crop = Nv21Bgr.uprightGray(frame, roi)
                assertEquals(px.w, crop.cols()); assertEquals(px.h, crop.rows())
                assertTrue("the crop must own its pixels", crop.isContinuous && crop.dataAddr() != 0L)
                val sub = want.submat(px.y, px.bottom, px.x, px.right)
                val subCopy = Mat(); sub.copyTo(subCopy)
                assertArrayEquals("uprightGray(roi) rot $rot", bytes(subCopy), bytes(crop))

                // The grey sampler reads the very same pixels for the same area.
                val g = GraySampler.sample(frame, roi)
                val g2 = GraySampler.sampleY(ByteBuffer.wrap(bytes(crop)), crop.cols(), crop.cols(), crop.rows(), 0, null)
                assertArrayEquals("sampler vs cropped luma rot $rot", g2.px, g.px)

                for (m in listOf(y, want, full, crop, sub, subCopy)) m.release()
            }
        } finally {
            src.release()
        }
    }

    @Test
    fun sensorRectCoversTheUprightArea() {
        val f = Nv21Frame(ByteArray(1600 * 1200 * 3 / 2), 1600, 1200, 90, 0L)
        // Upright 1200×1600; area x 100..399, y 200..999 ↔ sensor x = v, y = sh-1-u.
        val r = Nv21Bgr.sensorRect(f, RoiPx(100, 200, 300, 800))
        assertEquals(RoiPx(200, 1200 - 400, 800, 300), r)
        val f0 = Nv21Frame(ByteArray(1600 * 1200 * 3 / 2), 1600, 1200, 0, 0L)
        assertEquals(RoiPx(100, 200, 300, 800), Nv21Bgr.sensorRect(f0, RoiPx(100, 200, 300, 800)))
    }
}
