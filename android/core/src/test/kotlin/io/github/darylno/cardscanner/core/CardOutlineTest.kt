package io.github.darylno.cardscanner.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The live card outline ([CardOutline]) found on the DETECTION SAMPLE must
 * land on the full-resolution card ([CardQuad.find] on the whole scene — the
 * server's own answer in detect/expected.json, which is also what the
 * capture's quad is): every detect scene with a card gives an outline whose
 * corners, mapped back to scene pixels, are within [TOL_SAMPLE_PX]·k of the
 * reference quad; the two empty scenes give none; garbage never throws.
 *
 * The sample is made the way the app makes it — the REAL [GraySampler] on the
 * scene's luma — so the test exercises the same 176×MH pixels the scanner
 * would hand over. The pull-in's effect is printed per scene (raw vs pulled).
 */
class CardOutlineTest {
    companion object {
        /**
         * Corner error allowed after the pull-in, in SAMPLE pixels (× k = frame
         * pixels): resolution-independent, since the finder places an edge to
         * about a sample pixel either way. Measured on the detect scenes at
         * k = 5.45 (the printed table): ≤ 0.63 sample px on six scenes, 1.19 on
         * the glare-gradient tray, 1.74 on the 170-px card (22×31 sample px).
         */
        const val TOL_SAMPLE_PX = 2.0

        lateinit var scenes: JSONObject

        @BeforeClass @JvmStatic
        fun load() {
            OpenCvTest.load()
            scenes = JSONObject(OpenCvTest.detectFile("expected.json").readText()).getJSONObject("scenes")
        }

        fun quadOf(entry: JSONObject): FloatArray? {
            if (entry.isNull("quad")) return null
            val a = entry.getJSONArray("quad")
            return FloatArray(8) { a.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
        }

        /** The scene's detection sample: GraySampler on its luma (rotation 0, [roi] of the frame). */
        fun sampleOf(bgr: Mat, roi: RoiFrac?): Gray {
            val gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            try {
                val y = ByteArray(gray.cols() * gray.rows())
                gray.get(0, 0, y)
                return GraySampler.sampleY(ByteBuffer.wrap(y), gray.cols(), gray.cols(), gray.rows(), 0, roi)
            } finally {
                gray.release()
            }
        }

        /** Frame fractions (pixel-edge coordinates) → OpenCV pixel-centre coordinates of a [w]×[h] frame. */
        fun toFramePx(frac: FloatArray, w: Int, h: Int): FloatArray =
            FloatArray(8) { if (it % 2 == 0) frac[it] * w - 0.5f else frac[it] * h - 0.5f }

        /** Largest per-corner distance, corner i against corner i. */
        fun maxCornerDelta(a: FloatArray, b: FloatArray): Double =
            (0 until 4).maxOf { hypot((a[2 * it] - b[2 * it]).toDouble(), (a[2 * it + 1] - b[2 * it + 1]).toDouble()) }

        /** The same under the best cyclic corner shift (a 180°-ambiguous card may start at the other end). */
        fun bestCyclicDelta(a: FloatArray, b: FloatArray): Pair<Double, Int> =
            (0 until 4).map { s -> maxCornerDelta(a, FloatArray(8) { b[(it + 2 * s) % 8] }) to s }.minBy { it.first }
    }

    private fun sceneNames() = scenes.keys().asSequence().toList().sorted()

    @Test
    fun outlineLandsOnTheFullResolutionQuad() {
        // Per scene: k (frame px per sample px), the raw sample-resolution quad's
        // error, the error after the pull-in, the same without the half-pixel
        // centre compensation (to show the mapping is the right one), the
        // cyclic corner shift that matched, and the time. Printed BEFORE the
        // asserts so a failure still shows the whole table.
        val report = StringBuilder(
            "scene                        k      sample    found  raw Δ(px)  pulled Δ(px)  no-½px Δ   Δ(sample px)  shift  µs (best of 5, warm)\n")
        val problems = ArrayList<String>()
        var withCard = 0; var empty = 0
        for (name in sceneNames()) {
            val entry = scenes.getJSONObject(name)
            val want = quadOf(entry)
            val img = OpenCvTest.readBgr("$name.png")
            try {
                val w = img.cols(); val h = img.rows()
                val sample = sampleOf(img, null)
                val outline = CardOutline.find(sample, w, h, null)
                // Steady-state cost: the first call pays JIT + OpenCV warm-up.
                val micros = (1..5).minOf { CardOutline.find(sample, w, h, null)?.nanos ?: -1000L } / 1000
                val k = w.toDouble() / sample.w
                val tolPx = TOL_SAMPLE_PX * k
                var rawDelta = Double.NaN; var pulledDelta = Double.NaN; var noHalfDelta = Double.NaN; var shift = 0
                if (want != null && outline != null) {
                    val area = RoiPx(0, 0, w, h)
                    val raw = CardOutline.findSampleQuad(sample)!!
                    val rawPx = toFramePx(CardOutline.toFrameFractions(raw, sample.w, sample.h, area, w, h), w, h)
                    rawDelta = bestCyclicDelta(want, rawPx).first
                    val got = toFramePx(outline.quad, w, h)
                    bestCyclicDelta(want, got).let { pulledDelta = it.first; shift = it.second }
                    val noHalf = FloatArray(8) { outline.sampleQuad[it] * (if (it % 2 == 0) (w.toFloat() / sample.w) else (h.toFloat() / sample.h)) }
                    noHalfDelta = bestCyclicDelta(want, noHalf).first
                }
                fun fmt(d: Double) = if (d.isNaN()) "-" else String.format("%.2f", d)
                report.append(String.format("%-28s %.3f  %dx%-4d  %-6s %-10s %-13s %-10s %-13s %-6d %d\n", name, k, sample.w, sample.h,
                    outline != null, fmt(rawDelta), fmt(pulledDelta), fmt(noHalfDelta), fmt(pulledDelta / k), shift, micros))
                if ((want != null) != (outline != null)) problems += "$name: outline presence (reference quad=${want != null})"
                if (want != null) {
                    withCard++
                    if (outline != null) {
                        if (pulledDelta > tolPx) problems += "$name: corners off by $pulledDelta px = ${pulledDelta / k} sample px (> $TOL_SAMPLE_PX)"
                        if (pulledDelta > rawDelta) problems += "$name: the pull-in made it worse ($rawDelta → $pulledDelta)"
                        if (outline.ms < 0 || outline.nanos <= 0) problems += "$name: ms"
                        if (outline.sampleQuad.size != 8) problems += "$name: sampleQuad size"
                        for (f in outline.quad) if (f !in -0.01f..1.01f) problems += "$name: fraction $f outside 0..1"
                    }
                } else {
                    empty++
                }
            } finally {
                img.release()
            }
        }
        println(report)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        assertTrue("fixture set shrank: $withCard card scenes", withCard >= 8)
        assertEquals("empty scenes", 2, empty)
    }

    @Test
    fun outlineInAnAreaMapsToTheSameFrameCorners() {
        // The scanner samples the scan Area, not the frame: an outline found in
        // an Area's sample must map through the Area to the same frame corners.
        val name = "dark_tray_white_border"
        val entry = scenes.getJSONObject(name)
        val want = quadOf(entry)!!
        val img = OpenCvTest.readBgr("$name.png")
        try {
            val w = img.cols(); val h = img.rows()
            for (roi in listOf(RoiFrac(0.15, 0.04, 0.85, 0.98), RoiFrac(0.2, 0.05, 0.8, 0.95), RoiFrac(0.0, 0.0, 0.9, 1.0))) {
                val sample = sampleOf(img, roi)
                val outline = CardOutline.find(sample, w, h, roi)
                assertNotNull("$name in $roi: no outline", outline)
                val got = toFramePx(outline!!.quad, w, h)
                val (delta, _) = bestCyclicDelta(want, got)
                println("$name roi=$roi sample=${sample.w}x${sample.h} Δ=${"%.2f".format(delta)} px")
                val k = (roi.widthPx(w) / sample.w + roi.heightPx(h) / sample.h) / 2
                assertTrue("$name in $roi: corners off by $delta px (k=$k)", delta <= TOL_SAMPLE_PX * k)
            }
        } finally {
            img.release()
        }
    }

    @Test
    fun garbageGivesNullAndNeverThrows() {
        val rnd = java.util.Random(7)
        val noise = Gray(176, 235, IntArray(176 * 235) { rnd.nextInt(256) })
        assertNull(CardOutline.find(noise, 1200, 1600, null))
        val flat = Gray(176, 132, IntArray(176 * 132) { 200 })
        assertNull(CardOutline.find(flat, 960, 720, null))
        val tiny = Gray(2, 2, IntArray(4) { 10 })
        assertNull(CardOutline.find(tiny, 960, 720, null))
        val one = Gray(1, 1, IntArray(1))
        assertNull(CardOutline.find(one, 960, 720, null))
        // Out-of-range values are clamped, not thrown on.
        val wild = Gray(176, 132, IntArray(176 * 132) { if (it % 7 == 0) 999 else -40 })
        assertNull(CardOutline.find(wild, 960, 720, null))
        // A degenerate frame size (on a sample that does hold a card).
        val img = OpenCvTest.readBgr("white_tray_black_border.png")
        val sample = try { sampleOf(img, null) } finally { img.release() }
        assertNotNull(CardOutline.find(sample, 960, 720, null))
        assertNull(CardOutline.find(sample, 0, 0, null))
        assertNull(CardOutline.find(sample, -5, 720, null))
    }

    @Test
    fun pullInShrinksAndKeepsTheCentre() {
        for (angle in listOf(0.0, 7.0, 30.0, 45.0, 88.0, 120.0)) {
            val a = Math.toRadians(angle)
            val cx = 80.0; val cy = 100.0; val hw = 40.0; val hh = 56.0
            val corners = listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh)
            val quad = FloatArray(8) {
                val (x, y) = corners[it / 2]
                if (it % 2 == 0) (cx + x * cos(a) - y * sin(a)).toFloat() else (cy + x * sin(a) + y * cos(a)).toFloat()
            }
            val d = 4.3
            val out = CardOutline.pullIn(quad, d)
            assertNotNull("angle $angle", out)
            val ocx = (0 until 4).sumOf { out!![2 * it].toDouble() } / 4
            val ocy = (0 until 4).sumOf { out!![2 * it + 1].toDouble() } / 4
            assertEquals("centre x at $angle°", cx, ocx, 1e-3)
            assertEquals("centre y at $angle°", cy, ocy, 1e-3)
            for (i in 0 until 4) {
                val before = hypot(quad[2 * i] - cx, quad[2 * i + 1] - cy)
                val after = hypot(out!![2 * i] - cx, out[2 * i + 1] - cy)
                assertTrue("corner $i at $angle° grew ($before → $after)", after < before)
            }
            // Each side moved in by exactly d: the sides are shorter by 2d.
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                val lenBefore = hypot((quad[2 * j] - quad[2 * i]).toDouble(), (quad[2 * j + 1] - quad[2 * i + 1]).toDouble())
                val lenAfter = hypot((out!![2 * j] - out[2 * i]).toDouble(), (out[2 * j + 1] - out[2 * i + 1]).toDouble())
                assertEquals("side $i at $angle°", lenBefore - 2 * d, lenAfter, 1e-3)
            }
        }
        // No pull-in: an identical copy. A pull-in that would swallow the quad: null.
        val q = floatArrayOf(10f, 10f, 30f, 10f, 30f, 40f, 10f, 40f)
        assertTrue(CardOutline.pullIn(q, 0.0)!!.contentEquals(q))
        assertNull(CardOutline.pullIn(q, 10.0))
        assertNull(CardOutline.pullIn(q, 12.0))
        assertNull(CardOutline.pullIn(floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f), 1.0))
        assertNull(CardOutline.pullIn(FloatArray(6), 1.0))
        assertEquals(0.0, CardOutline.pullInPx(1.0), 0.0)
        assertEquals(0.0, CardOutline.pullInPx(0.5), 0.0)
        assertEquals(5.0 * (1 - 1 / 6.8), CardOutline.pullInPx(6.8), 1e-12)
    }

    @Test
    fun frameFractionsAreEdgeCoordinatesThroughTheArea() {
        // Sample pixel 0 of a 176-wide sample of a 1760-px-wide area starting at
        // x=100 spans frame px 100..110; its centre (sample x = 0) → edge 105 → 105/2000.
        val area = RoiPx(100, 50, 1760, 2350)
        val q = floatArrayOf(0f, 0f, 175f, 0f, 175f, 234f, 0f, 234f)
        val f = CardOutline.toFrameFractions(q, 176, 235, area, 2000, 3000)
        assertEquals(105.0 / 2000, f[0].toDouble(), 1e-6)
        assertEquals((50 + 0.5 * 10) / 3000, f[1].toDouble(), 1e-6)
        assertEquals((100 + 175.5 * 10) / 2000, f[2].toDouble(), 1e-6)
        assertEquals((50 + 234.5 * 10) / 3000, f[5].toDouble(), 1e-6)
    }
}
