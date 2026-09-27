package io.github.darylno.cardscanner.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Holds the Kotlin port of card_detect.find_card_quad + the flattening spec
 * (tests/phone_flatten_ref.py) to the SERVER's own answers on the synthetic
 * tray scenes exported by scripts/export_detect_fixtures.py (CI re-exports
 * them and fails on drift, so these numbers are the server's, not ours).
 *
 * Per scene: quad presence must match; every corner within [CORNER_TOL_PX]
 * of the server's; the chosen margin must match; the flattened card, cut to
 * 1/4 with INTER_AREA as the exporter does, must match the fixture's pixels
 * (mean abs diff < [MAD_TOL]); Laplacian sharpness within 1%. The fixtures
 * were made with the server's cv2 and this runs OpenCV 4.9 (org.openpnp), so
 * sub-pixel/rounding differences are allowed — a different card, a different
 * corner order or a different margin is not.
 */
class CardQuadParityTest {
    companion object {
        const val CORNER_TOL_PX = 1.5
        const val MAD_TOL = 2.0
        const val SHARPNESS_REL_TOL = 0.01

        lateinit var expected: JSONObject
        lateinit var scenes: JSONObject

        @BeforeClass @JvmStatic
        fun loadFixtures() {
            OpenCvTest.load()
            expected = JSONObject(OpenCvTest.detectFile("expected.json").readText())
            scenes = expected.getJSONObject("scenes")
        }

        private fun quadOf(entry: JSONObject): FloatArray? {
            if (entry.isNull("quad")) return null
            val a: JSONArray = entry.getJSONArray("quad")
            return FloatArray(8) { a.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
        }

        private fun marginOf(entry: JSONObject): Double? =
            if (entry.isNull("margin")) null else entry.getDouble("margin")

        /** Largest per-corner Euclidean distance between two quads. */
        fun maxCornerDelta(a: FloatArray, b: FloatArray): Double =
            (0 until 4).maxOf { hypot((a[2 * it] - b[2 * it]).toDouble(), (a[2 * it + 1] - b[2 * it + 1]).toDouble()) }

        /** Mean absolute difference over all pixels and channels. */
        fun meanAbsDiff(a: Mat, b: Mat): Double {
            val d = Mat()
            try {
                Core.absdiff(a, b, d)
                val m = Core.mean(d)
                return (0 until a.channels()).sumOf { m.`val`[it] } / a.channels()
            } finally {
                d.release()
            }
        }

        /** The exporter's thumbnail: `cv2.resize(flat, (w // 4, h // 4), INTER_AREA)`. */
        fun thumb(flat: Mat, k: Int): Mat {
            val small = Mat()
            Imgproc.resize(flat, small, Size((flat.cols() / k).toDouble(), (flat.rows() / k).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            return small
        }
    }

    private fun sceneNames(): List<String> = scenes.keys().asSequence().toList().sorted()

    @Test
    fun fixturesDescribeTheFlattenContract() {
        // The exporter writes the reference constants beside the scenes; the
        // Kotlin constants must be the same numbers.
        val margins = expected.getJSONArray("margins")
        assertEquals(Flatten.MARGINS.size, margins.length())
        for (i in 0 until margins.length()) assertEquals(margins.getDouble(i), Flatten.MARGINS[i], 0.0)
        assertEquals(expected.getDouble("scale"), Flatten.SCALE, 0.0)
        assertEquals(expected.getInt("card_w"), Flatten.CARD_W)
        assertEquals(expected.getInt("card_h"), Flatten.CARD_H)
        assertTrue("fixture set shrank", sceneNames().size >= 9)
    }

    @Test
    fun quadPresenceAndCornersMatchTheServer() {
        val report = StringBuilder("scene                        server  kotlin  maxΔ(px)\n")
        for (name in sceneNames()) {
            val entry = scenes.getJSONObject(name)
            val img = OpenCvTest.readBgr("$name.png")
            try {
                assertEquals("$name width", entry.getInt("width"), img.cols())
                assertEquals("$name height", entry.getInt("height"), img.rows())
                val want = quadOf(entry)
                val got = CardQuad.find(img)
                val delta = if (want != null && got != null) maxCornerDelta(want, got) else Double.NaN
                report.append(String.format("%-28s %-7s %-7s %s\n", name, want != null, got != null,
                    if (delta.isNaN()) "-" else String.format("%.4f", delta)))
                assertEquals("$name: quad presence (server=${want != null})", want != null, got != null)
                if (want != null) {
                    assertTrue("$name: corners off by $delta px (server ${want.toList()} kotlin ${got!!.toList()})",
                        delta <= CORNER_TOL_PX)
                }
            } finally {
                img.release()
            }
        }
        println(report)
    }

    @Test
    fun chosenMarginMatchesTheServer() {
        for (name in sceneNames()) {
            val entry = scenes.getJSONObject(name)
            val want = quadOf(entry) ?: continue
            val w = entry.getInt("width"); val h = entry.getInt("height")
            // The Flatten port on the server's exact quad …
            assertEquals("$name: margin on the server's quad", marginOf(entry), Flatten.chooseMargin(want, w, h))
            // … and end to end on the quad the port found.
            val img = OpenCvTest.readBgr("$name.png")
            try {
                val got = assertNotNullQuad(name, CardQuad.find(img))
                assertEquals("$name: margin on the Kotlin quad", marginOf(entry), Flatten.chooseMargin(got, w, h))
            } finally {
                img.release()
            }
        }
    }

    @Test
    fun flattenedCardMatchesTheServerPixels() {
        val k = expected.getInt("thumb")
        val report = StringBuilder("scene                        margin  size       MAD(server quad)  MAD(kotlin quad)\n")
        var flattened = 0
        for (name in sceneNames()) {
            val entry = scenes.getJSONObject(name)
            val margin = marginOf(entry) ?: continue
            val want = quadOf(entry)!!
            val size = entry.getJSONArray("flat_size")
            val img = OpenCvTest.readBgr("$name.png")
            val ref = OpenCvTest.readBgr("$name.flat.png")
            try {
                val lay = Flatten.layout(margin)
                assertEquals("$name: flat width", size.getInt(0), lay.outW)
                assertEquals("$name: flat height", size.getInt(1), lay.outH)
                val mads = listOf(want, assertNotNullQuad(name, CardQuad.find(img))).map { quad ->
                    val flat = Flatten.flatten(img, quad, margin)
                    val small = thumb(flat, k)
                    try {
                        assertEquals("$name: flat size", size.getInt(0), flat.cols())
                        assertEquals("$name: flat size", size.getInt(1), flat.rows())
                        assertEquals("$name: thumb size", ref.size(), small.size())
                        assertEquals(CvType.CV_8UC3, flat.type())
                        meanAbsDiff(small, ref)
                    } finally {
                        flat.release(); small.release()
                    }
                }
                report.append(String.format("%-28s %.2f    %dx%d  %.4f            %.4f\n",
                    name, margin, lay.outW, lay.outH, mads[0], mads[1]))
                assertTrue("$name: flatten on the server's quad differs, MAD ${mads[0]}", mads[0] < MAD_TOL)
                assertTrue("$name: flatten on the Kotlin quad differs, MAD ${mads[1]}", mads[1] < MAD_TOL)
                flattened++
            } finally {
                img.release(); ref.release()
            }
        }
        println(report)
        assertTrue("no flattened scenes in the fixtures", flattened >= 6)
    }

    @Test
    fun sharpnessMatchesTheServer() {
        val report = StringBuilder("scene                        server      kotlin      rel\n")
        for (name in sceneNames()) {
            val entry = scenes.getJSONObject(name)
            val want = entry.getDouble("sharpness")
            val img = OpenCvTest.readBgr("$name.png")
            try {
                val got = Sharpness.frameSharpness(img)
                val rel = if (want == 0.0) got else abs(got - want) / want
                report.append(String.format("%-28s %-11.3f %-11.3f %.2e\n", name, want, got, rel))
                if (want == 0.0) assertEquals("$name sharpness", 0.0, got, 1e-9)
                else assertTrue("$name sharpness $got vs server $want", rel <= SHARPNESS_REL_TOL)
            } finally {
                img.release()
            }
        }
        println(report)
    }

    // ── helper-level checks (numpy semantics the parity rests on) ───────────

    @Test
    fun orderCornersIsPositional() {
        // Scrambled axis-aligned rectangle → TL, TR, BR, BL.
        val pts = floatArrayOf(10f, 90f, 60f, 10f, 10f, 10f, 60f, 90f)
        assertArrayEquals(floatArrayOf(10f, 10f, 60f, 10f, 60f, 90f, 10f, 90f), CardQuad.orderCorners(pts), 0f)
    }

    @Test
    fun orientCornersStandsALandscapeRectangleUp() {
        // Portrait stays as ordered.
        val portrait = floatArrayOf(0f, 0f, 63f, 0f, 63f, 88f, 0f, 88f)
        assertArrayEquals(portrait, CardQuad.orientCorners(portrait), 0f)
        // Landscape (top edge longer than the right edge) → np.roll(pts, -1): TR becomes TL.
        val landscape = floatArrayOf(0f, 0f, 88f, 0f, 88f, 63f, 0f, 63f)
        assertArrayEquals(floatArrayOf(88f, 0f, 88f, 63f, 0f, 63f, 0f, 0f), CardQuad.orientCorners(landscape), 0f)
    }

    @Test
    fun candidateRejectsWrongShapes() {
        fun contour(vararg p: Double) = MatOfPoint(*Array(p.size / 2) { Point(p[2 * it], p[2 * it + 1]) })
        // A 63:88 rectangle is a card …
        val card = contour(100.0, 100.0, 226.0, 100.0, 226.0, 276.0, 100.0, 276.0)
        val c = CardQuad.candidateFromContour(card, 10.0, 1e9)
        assertNotNull(c)
        assertEquals(126.0 * 176.0, c!!.area, 1e-9)
        // … a square (ratio 1.0) and a 1:3 strip are not; neither is one outside the area window.
        assertNull(CardQuad.candidateFromContour(contour(0.0, 0.0, 100.0, 0.0, 100.0, 100.0, 0.0, 100.0), 10.0, 1e9))
        assertNull(CardQuad.candidateFromContour(contour(0.0, 0.0, 300.0, 0.0, 300.0, 100.0, 0.0, 100.0), 10.0, 1e9))
        assertNull(CardQuad.candidateFromContour(card, 1e6, 1e9))
        assertNull(CardQuad.candidateFromContour(card, 10.0, 100.0))
        // A triangle inside a card-shaped box fails rectangularity (area/box = 0.5).
        assertNull(CardQuad.candidateFromContour(contour(100.0, 100.0, 226.0, 100.0, 100.0, 276.0), 10.0, 1e9))
        card.release()
    }

    @Test
    fun textureGateRejectsAUniformPatchAndAcceptsDetail() {
        val flat = Mat(880, 630, CvType.CV_8UC1, Scalar(30.0))
        // Printed-looking detail survives the 64×88 INTER_AREA thumbnail: dark
        // "text" blocks on a light face. (Pixel noise would not — a 10×10 area
        // average flattens it to std ≈ 7, below the bar, like glare.)
        val busy = Mat(880, 630, CvType.CV_8UC1, Scalar(225.0))
        for (by in 0 until 880 step 60) for (bx in (by / 60 % 2) * 60 until 630 step 120) {
            Imgproc.rectangle(busy, Point(bx.toDouble(), by.toDouble()), Point(bx + 59.0, by + 29.0), Scalar(20.0), -1)
        }
        try {
            val (stdFlat, edgesFlat) = CardQuad.textureMetrics(flat)
            assertEquals(0.0, stdFlat, 0.0)
            assertEquals(0.0, edgesFlat, 0.0)
            val (stdBusy, edgesBusy) = CardQuad.textureMetrics(busy)
            // card_detect._texture_metrics on the same drawing (numpy std, ddof=0):
            // (85.78364787845966, 0.22002923976608188) = 903 edge pixels of 76×54.
            assertEquals(85.78364787845966, stdBusy, 1e-9)
            assertEquals(903.0 / (76 * 54), edgesBusy, 0.0)
            assertTrue(stdBusy >= CardQuad.MIN_INTERIOR_STD)
            assertTrue(edgesBusy >= CardQuad.MIN_INTERIOR_EDGE_FRAC)
            val whole = floatArrayOf(0f, 0f, 630f, 0f, 630f, 880f, 0f, 880f)
            assertTrue(!CardQuad.hasCardTexture(flat, whole))
            assertTrue(CardQuad.hasCardTexture(busy, whole))
        } finally {
            flat.release(); busy.release()
        }
    }

    @Test
    fun translateOffsetsEveryCorner() {
        val q = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
        assertArrayEquals(floatArrayOf(11f, 22f, 13f, 24f, 15f, 26f, 17f, 28f), CardQuad.translate(q, 10f, 20f), 0f)
    }

    @Test
    fun findOnACropThenTranslateEqualsFindOnTheFullFrame() {
        // CapturePipeline finds the quad in the scan-area crop and offsets it
        // into full-frame coordinates; with the card well inside the crop that
        // must land on the same corners as detecting in the whole scene.
        val name = "dark_tray_white_border"
        val img = OpenCvTest.readBgr("$name.png")
        try {
            val full = assertNotNullQuad(name, CardQuad.find(img))
            val sub = img.submat(40, 700, 150, 800)
            val crop = Mat()
            sub.copyTo(crop)
            sub.release()
            val inCrop = assertNotNullQuad("$name crop", CardQuad.find(crop))
            crop.release()
            val back = CardQuad.translate(inCrop, 150f, 40f)
            assertTrue("crop→full delta ${maxCornerDelta(full, back)}", maxCornerDelta(full, back) <= 1.0)
        } finally {
            img.release()
        }
    }

    @Test
    fun aGreySubmatFindsWhatAStandaloneCopyFinds() {
        // The capture path may hand find() a VIEW of the scan area. The server
        // always gets a standalone image, so the blur must not see the parent's
        // pixels around the view (find() copies a grey submat; a BGR one is
        // converted into a fresh Mat anyway). Checked on every quad scene.
        for (name in listOf("dark_tray_white_border", "white_tray_black_border", "card_near_frame_edge")) {
            val img = OpenCvTest.readBgr("$name.png")
            val gray = Mat(); Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
            val full = assertNotNullQuad(name, CardQuad.find(img))
            // Crop hugging the card (8 px of tray) so the ROI edge is inside the blur's reach.
            val xs = (0 until 4).map { full[2 * it] }; val ys = (0 until 4).map { full[2 * it + 1] }
            val x0 = maxOf(0, xs.min().toInt() - 8); val y0 = maxOf(0, ys.min().toInt() - 8)
            val x1 = minOf(img.cols(), xs.max().toInt() + 9); val y1 = minOf(img.rows(), ys.max().toInt() + 9)
            for (src in listOf(gray, img)) {
                val view = src.submat(y0, y1, x0, x1)
                val copy = view.clone()
                val a = CardQuad.find(copy)
                val b = CardQuad.find(view)
                assertEquals("$name ch=${src.channels()}: presence", a != null, b != null)
                if (a != null) assertArrayEquals("$name ch=${src.channels()}", a, b, 0f)
                view.release(); copy.release()
            }
            img.release(); gray.release()
        }
    }

    private fun assertNotNullQuad(name: String, q: FloatArray?): FloatArray {
        assertNotNull("$name: Kotlin port found no quad", q)
        return q!!
    }
}
