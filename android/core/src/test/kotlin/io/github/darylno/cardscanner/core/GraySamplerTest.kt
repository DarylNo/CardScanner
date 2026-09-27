package io.github.darylno.cardscanner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer

/**
 * The detection sample must be the box average of the UPRIGHT scan area's
 * luma, whatever the sensor orientation — checked against an oracle that
 * physically rotates the Y plane with OpenCV and averages each cell by
 * definition, so the sampler's cached run tables and rotation arithmetic are
 * held to something that shares none of their code.
 */
class GraySamplerTest {
    companion object {
        @BeforeClass @JvmStatic
        fun load() = OpenCvTest.load()

        val ROTATIONS = intArrayOf(0, 90, 180, 270)
        const val MW = DetectConst.MW

        val ROIS: List<RoiFrac?> = listOf(
            null,
            RoiFrac(0.1, 0.2, 0.9, 0.85),
            RoiFrac(0.0, 0.0, 0.5, 1.0),
            RoiFrac(0.33, 0.07, 0.77, 0.61),
            RoiFrac(0.5, 0.5, 1.0, 1.0),
            RoiFrac(0.12, 0.3, 0.21, 0.39),      // smaller than the 176-wide grid → overlapping cells
        )
    }

    /** Deterministic "scene": smooth gradients + blocky detail + noise, so every cell differs. */
    private fun yPlane(sw: Int, sh: Int, seed: Long): ByteArray {
        val rnd = java.util.Random(seed)
        return ByteArray(sw * sh) { i ->
            val x = i % sw; val y = i / sw
            val block = if (((x / 13) + (y / 9)) % 3 == 0) 70 else 0
            (40 + (x * 100) / sw + (y * 60) / sh + block + rnd.nextInt(-12, 13)).coerceIn(0, 255).toByte()
        }
    }

    /** Oracle: rotate the sensor Y upright with Core.rotate, crop the area, average every cell by definition. */
    private fun oracle(y: ByteArray, sw: Int, sh: Int, rot: Int, roi: RoiFrac?): Gray {
        val sensor = Mat(sh, sw, CvType.CV_8UC1); sensor.put(0, 0, y)
        val upright = Mat()
        val code = Nv21Bgr.rotateCode(rot)
        if (code == null) sensor.copyTo(upright) else Core.rotate(sensor, upright, code)
        val uw = upright.cols(); val uh = upright.rows()
        val u = ByteArray(uw * uh); upright.get(0, 0, u)
        sensor.release(); upright.release()
        val a = roi?.toPixels(uw, uh) ?: RoiPx(0, 0, uw, uh)
        val mh = if (roi == null) DetectConst.mhFor(uw.toDouble(), uh.toDouble())
                 else DetectConst.mhFor((roi.x1 - roi.x0) * uw, (roi.y1 - roi.y0) * uh)
        val px = IntArray(MW * mh)
        for (j in 0 until mh) for (i in 0 until MW) {
            val c0 = (i.toLong() * a.w / MW).toInt(); val c1 = maxOf(c0 + 1, ((i + 1).toLong() * a.w / MW).toInt())
            val r0 = (j.toLong() * a.h / mh).toInt(); val r1 = maxOf(r0 + 1, ((j + 1).toLong() * a.h / mh).toInt())
            var sum = 0L; var n = 0
            for (v in r0 until r1) for (c in c0 until c1) { sum += u[(a.y + v) * uw + a.x + c].toInt() and 0xFF; n++ }
            px[j * MW + i] = Math.round(sum.toDouble() / n).toInt()
        }
        return Gray(MW, mh, px)
    }

    /** The same Y plane as a direct buffer with row padding and a moved position (like a live ImageProxy plane). */
    private fun padded(y: ByteArray, sw: Int, sh: Int, rowStride: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect((sh - 1) * rowStride + sw)   // last row unpadded, as Camera2 does
        for (r in 0 until sh) {
            buf.position(r * rowStride)
            buf.put(y, r * sw, sw)
            if (r < sh - 1) repeat(rowStride - sw) { buf.put(0xEE.toByte()) }   // garbage in the padding
        }
        buf.position(7)          // must be ignored
        return buf
    }

    @Test
    fun matchesTheRotateThenAverageOracle() {
        for ((sw, sh) in listOf(640 to 480, 322 to 242, 400 to 300)) for (rot in ROTATIONS) for (roi in ROIS) {
            val y = yPlane(sw, sh, (sw * 31 + rot).toLong())
            val want = oracle(y, sw, sh, rot, roi)
            val got = GraySampler.sampleY(ByteBuffer.wrap(y), sw, sw, sh, rot, roi)
            assertEquals("MH ${sw}x$sh rot $rot roi $roi", want.h, got.h)
            assertArrayEquals("pixels ${sw}x$sh rot $rot roi $roi", want.px, got.px)
        }
    }

    @Test
    fun bothOverloadsAndTheReferencePathAgree() {
        val sw = 640; val sh = 480
        for (rot in ROTATIONS) for (roi in ROIS) {
            val y = yPlane(sw, sh, rot.toLong() + 5)
            val frame = Nv21Frame(y.copyOf(sw * sh * 3 / 2), sw, sh, rot, 123L)
            val a = GraySampler.sample(frame, roi)
            val b = GraySampler.sampleY(padded(y, sw, sh, 704), 704, sw, sh, rot, roi)
            val c = GraySampler.sampleYReference(ByteBuffer.wrap(y), sw, sw, sh, rot, roi)
            assertEquals(a.h, b.h)
            assertArrayEquals("sample vs sampleY rot $rot roi $roi", a.px, b.px)
            assertArrayEquals("fast vs reference rot $rot roi $roi", c.px, a.px)
        }
    }

    @Test
    fun knownPatternsAverageExactly() {
        // Uniform frame → every sample pixel is that level.
        val flat = ByteArray(352 * 264) { 137.toByte() }
        val g = GraySampler.sampleY(ByteBuffer.wrap(flat), 352, 352, 264, 0, null)
        assertEquals(132, g.h)
        assertTrue(g.px.all { it == 137 })

        // 2× the grid: each output pixel is a 2×2 block; values chosen so the
        // mean is x.25 / x.5 / x.75 — the half rounds UP (to nearest, ties up).
        val sw = 352; val sh = 264
        val y = ByteArray(sw * sh)
        for (r in 0 until sh) for (c in 0 until sw) {
            val base = 10 * ((c / 2) % 20)
            val extra = when ((r % 2) * 2 + (c % 2)) { 0 -> 0; 1 -> 1; 2 -> ((c / 2) % 3); else -> 0 }
            y[r * sw + c] = (base + extra).toByte()
        }
        val s = GraySampler.sampleY(ByteBuffer.wrap(y), sw, sw, sh, 0, null)
        for (j in 0 until s.h) for (i in 0 until MW) {
            val sum = 4 * (10 * (i % 20)) + 1 + (i % 3)            // extras 0 + 1 + (0|1|2) + 0
            assertEquals("cell ($i,$j)", Math.floorDiv(2 * sum + 4, 8), s.px[j * MW + i])
        }
    }

    @Test
    fun mhFollowsTheUprightAreaAspect() {
        // Landscape 4:3 sensor, upright landscape (rot 0): 176·1200/1600 = 132.
        assertEquals(132, GraySampler.mhFor(1600, 1200, null))
        // Same sensor on a portrait phone (rot 90) → upright 1200×1600: round(234.67) = 235.
        val y = ByteArray(1600 * 1200)
        assertEquals(235, GraySampler.sampleY(ByteBuffer.wrap(y), 1600, 1600, 1200, 90, null).h)
        assertEquals(132, GraySampler.sampleY(ByteBuffer.wrap(y), 1600, 1600, 1200, 180, null).h)
        // A wide strip hits phone.html's floor of 48.
        assertEquals(48, GraySampler.mhFor(1200, 1600, RoiFrac(0.0, 0.4, 1.0, 0.5)))
        // An area's aspect uses its UNROUNDED pixel size, like phone.html roiRect().
        val roi = RoiFrac(0.1, 0.2, 0.8, 0.9)
        assertEquals(DetectConst.mhFor(0.7 * 1200, 0.7 * 1600), GraySampler.mhFor(1200, 1600, roi))
        assertEquals(235, GraySampler.mhFor(1200, 1600, roi))
        // JS Math.round: 176·97/352 = 48.5 exactly → 49 (half toward +∞; half-even would say 48).
        assertEquals(49, GraySampler.mhFor(352, 97, null))
    }

    @Test
    fun rotation90TurnsAVerticalSensorBarIntoAHorizontalBar() {
        // A bright vertical bar in the SENSOR image (columns 200..279 of 640×480)
        // is, after the 90° clockwise rotation to upright (480×640), a
        // horizontal band at upright rows 200..279 → sample rows ~ 200·MH/640.
        val sw = 640; val sh = 480
        val y = ByteArray(sw * sh) { i -> if (i % sw in 200 until 280) 220.toByte() else 30.toByte() }
        val g = GraySampler.sampleY(ByteBuffer.wrap(y), sw, sw, sh, 90, null)
        assertEquals(DetectConst.mhFor(480.0, 640.0), g.h)          // 235
        val rowMean = DoubleArray(g.h) { j -> (0 until MW).sumOf { g.px[j * MW + it] }.toDouble() / MW }
        val band = (0 until g.h).filter { rowMean[it] > 125 }
        val lo = 200 * g.h / 640; val hi = (280 * g.h + 639) / 640
        assertTrue("band rows $band expected within $lo..$hi", band.isNotEmpty() && band.first() >= lo && band.last() < hi)
        assertTrue("band must span nearly the rows it covers: $band", band.size >= (hi - lo) - 2)
        // …and every sample row is uniform across its width (a horizontal bar, not a vertical one).
        for (j in 0 until g.h) {
            val row = g.px.copyOfRange(j * MW, (j + 1) * MW)
            assertEquals("row $j uniform", row.min(), row.max())
        }
        // Rotation 0: the same sensor bar stays vertical.
        val g0 = GraySampler.sampleY(ByteBuffer.wrap(y), sw, sw, sh, 0, null)
        for (i in 0 until MW) {
            val col = IntArray(g0.h) { g0.px[it * MW + i] }
            assertEquals("column $i uniform", col.min(), col.max())
        }
    }

    @Test
    fun outArrayIsReusedOnlyWhenItFits() {
        val y = yPlane(640, 480, 9)
        val out = IntArray(MW * 132)
        val g = GraySampler.sampleY(ByteBuffer.wrap(y), 640, 640, 480, 0, null, out)
        assertSame(out, g.px)
        val g2 = GraySampler.sampleY(ByteBuffer.wrap(y), 640, 640, 480, 90, null, out)   // MH 235: doesn't fit
        assertTrue(g2.px !== out)
        assertEquals(MW * 235, g2.px.size)
    }

    @Test
    fun tooSmallBufferIsRejected() {
        try {
            GraySampler.sampleY(ByteBuffer.allocate(640 * 479), 640, 640, 480, 0, null)
            throw AssertionError("short buffer accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun fullFrameSampleIsFast() {
        // Not a benchmark gate (CI machines vary) — a generous ceiling that
        // catches an accidental per-pixel slow path; the timing is printed.
        val sw = 1600; val sh = 1200
        val buf = ByteBuffer.allocateDirect(sw * sh).also { it.put(yPlane(sw, sh, 3)); it.rewind() }
        val roi = RoiFrac(0.05, 0.05, 0.95, 0.95)
        repeat(30) { GraySampler.sampleY(buf, sw, sw, sh, 90, roi) }            // warm-up / JIT
        val n = 50
        val t0 = System.nanoTime()
        repeat(n) { GraySampler.sampleY(buf, sw, sw, sh, 90, roi) }
        val ms = (System.nanoTime() - t0) / 1e6 / n
        println("GraySampler 1600x1200 rot 90 roi: %.2f ms/sample (JVM)".format(ms))
        assertTrue("sampling took $ms ms", ms < 50.0)
    }
}
