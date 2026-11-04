package io.github.darylno.cardscanner.camera

import io.github.darylno.cardscanner.core.Nv21Bgr
import io.github.darylno.cardscanner.core.Nv21Frame
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.RoiPx
import io.github.darylno.cardscanner.core.ZoomDetail
import io.github.darylno.cardscanner.core.ZoomProbe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.abs

/**
 * The zoom probe's worker (CameraController's `zoom-probe` thread) on NV21 frames built
 * from the reference's detect fixtures (upright BGR → sensor NV21 at rotation 90, as the
 * phone delivers them): the card is found where the identification's own finder finds it,
 * inside the search rect only; each frame carries ITS OWN CaptureResult's zoom, lens,
 * exposure, ISO and AF state; the card's luma; and the thumbnail the restore compares.
 */
class ProbeMeasureTest {
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

    private fun scene(name: String): Mat = Imgcodecs.imread(File(dir, "$name.png").path).also { assertFalse(it.empty()) }

    private fun frames(bgr: Mat, n: Int): List<Nv21Frame> = (0 until n).map { Nv21Bgr.fromUprightBgr(bgr, 90, 1_000L + it) }

    private fun expectedQuad(name: String): FloatArray {
        val q = expected.getJSONObject(name).getJSONArray("quad")
        return FloatArray(8) { q.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
    }

    /** A frame's report as the camera thread files it (API 30+: CONTROL_ZOOM_RATIO, the crop the full array). */
    private fun meta(ts: Long, zoom: Float? = 1.6f) =
        FrameMetaRing.Meta(ts, 4, 4.2f, 16_666_667, 400, zoomRatio = zoom, crop = intArrayOf(0, 0, 4160, 3120))

    @Test fun eachFrameCarriesItsCardAndItsOwnReport() {
        val name = "dark_tray_white_border"
        val bgr = scene(name)
        val fs = frames(bgr, 3)
        val metas = listOf(meta(fs[0].timestampNs), null, meta(fs[2].timestampNs, zoom = null))
        val m = ProbeMeasure.measure(fs, metas, rect = null, area = null, activeW = 4160, activeH = 3120, thumbnail = true)
        assertEquals("upright size", bgr.cols(), m.w)
        assertEquals(bgr.rows(), m.h)
        assertEquals(3, m.frames.size)
        val eq = expectedQuad(name)
        for ((i, f) in m.frames.withIndex()) {
            val q = f.quad
            assertNotNull("frame $i: the card is found", q)
            for (k in 0 until 8) assertTrue("frame $i corner $k: ${q!![k]} vs ${eq[k]}", abs(q[k] - eq[k]) < 3f)
            val l = f.luma
            assertNotNull("frame $i: the card's luma", l)
            assertTrue("frame $i: luma $l", l!! in 30.0..250.0)
        }
        val a = m.frames[0]
        assertEquals(1.6, a.zoom!!, 1e-6)
        assertTrue("CONTROL_ZOOM_RATIO came on it", a.reported)
        assertEquals(4.2, a.lensDpt!!, 1e-6)
        assertEquals(16_666_667L, a.exposureNs)
        assertEquals(400, a.iso)
        assertEquals("FOCUSED_LOCKED", a.afState)
        assertEquals(true, a.cropCentred)
        // No report kept: nothing is made up.
        val b = m.frames[1]
        assertNull(b.zoom); assertFalse(b.reported); assertNull(b.lensDpt); assertNull(b.iso); assertNull(b.afState); assertNull(b.cropCentred)
        // No CONTROL_ZOOM_RATIO (pre-30 / a HAL that leaves it out): the crop region says the zoom — 1× for the full array.
        val c = m.frames[2]
        assertFalse(c.reported)
        assertEquals(1.0, c.zoom!!, 1e-9)
        // The thumbnail is the card's — the one the restore's same-card check compares.
        val th = m.thumbnail
        assertNotNull(th)
        assertEquals(ZoomDetail.THUMB_W * ZoomDetail.THUMB_H, th!!.size)
        val again = ProbeMeasure.measure(frames(bgr, 1), listOf(null), null, null, null, null, thumbnail = true).thumbnail!!
        assertTrue(ZoomDetail.ncc(th, again) > 0.99)
        assertTrue(ZoomProbe.longSide(a.quad!!) > 300)
    }

    @Test fun theCardIsLookedForOnlyWhereTheProbeSays() {
        val name = "dark_tray_white_border"
        val bgr = scene(name)
        val fs = frames(bgr, 2)
        // A rect off the card (the top-left corner): nothing found, no luma, no thumbnail.
        val off = ProbeMeasure.measure(fs, listOf(null, null), intArrayOf(0, 0, 120, 60), null, null, null, thumbnail = true)
        assertTrue(off.frames.all { it.quad == null && it.luma == null })
        assertNull(off.thumbnail)
        // The rect round the card (the runner's searchRect): found, in the FRAME's px.
        val eq = expectedQuad(name)
        val near = ProbeMeasure.measure(fs, listOf(null, null), intArrayOf(200, 40, 540, 660), null, null, null, thumbnail = false)
        for (f in near.frames) for (k in 0 until 8) assertTrue("corner $k", abs(f.quad!![k] - eq[k]) < 3f)
        assertNull("not asked for", near.thumbnail)
        // No rect: the Area (fractions at the camera's zoom) — here one round the card, then one beside it.
        val around = RoiFrac(200.0 / bgr.cols(), 40.0 / bgr.rows(), 740.0 / bgr.cols(), 700.0 / bgr.rows())
        assertNotNull(ProbeMeasure.measure(fs, listOf(null, null), null, around, null, null, false).frames[0].quad)
        val beside = RoiFrac(0.0, 0.0, 200.0 / bgr.cols(), 1.0)
        assertNull(ProbeMeasure.measure(fs, listOf(null, null), null, beside, null, null, false).frames[0].quad)
    }

    @Test fun theSearchAreaIsClampedIntoTheFrame() {
        assertEquals(RoiPx(0, 0, 100, 80), ProbeMeasure.searchArea(null, null, 100, 80))
        assertEquals(RoiPx(90, 70, 10, 10), ProbeMeasure.searchArea(intArrayOf(90, 70, 50, 50), null, 100, 80))
        assertEquals(RoiPx(0, 0, 30, 20), ProbeMeasure.searchArea(intArrayOf(-10, -5, 40, 25), null, 100, 80))
        assertEquals("never empty", RoiPx(99, 79, 1, 1), ProbeMeasure.searchArea(intArrayOf(150, 150, 5, 5), null, 100, 80))
        assertEquals(RoiPx(25, 20, 50, 40), ProbeMeasure.searchArea(null, RoiFrac(0.25, 0.25, 0.75, 0.75), 100, 80))
    }
}
