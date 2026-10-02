package io.github.darylno.cardscanner.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.abs

/**
 * Holds the Kotlin port of change_signal.py ([TextureChange]) to the
 * REFERENCE's answers on the samples scripts/export_change_fixtures.py
 * writes (`change/expected.json`; CI re-exports them and fails on drift):
 * the padded polygon mask (pixel count + index sum, so a one-pixel
 * difference shows), the block set, every block's value, logHP p75 and
 * logGrad — within [TOL] (the maths is the same double arithmetic; the
 * measured gap is printed), the Gaussian kernel taps, and numpy.percentile.
 */
class TextureChangeParityTest {
    companion object {
        /** The contract's tolerance; the achieved maximum is printed. */
        const val TOL = 1e-6

        lateinit var expected: JSONObject

        fun file(name: String): File =
            File(requireNotNull(TextureChangeParityTest::class.java.getResource("/change/$name")) { "missing fixture change/$name" }.toURI())

        @BeforeClass @JvmStatic
        fun load() {
            OpenCvTest.load()
            expected = JSONObject(file("expected.json").readText())
        }

        /** A fixture PNG (8-bit grey) as the scanner's [Gray]. */
        fun readGray(name: String): Gray {
            val m = Imgcodecs.imread(file(name).path, Imgcodecs.IMREAD_GRAYSCALE)
            check(!m.empty()) { "could not read change/$name" }
            try {
                val b = ByteArray(m.cols() * m.rows())
                m.get(0, 0, b)
                return Gray(m.cols(), m.rows(), IntArray(b.size) { b[it].toInt() and 0xFF })
            } finally {
                m.release()
            }
        }

        fun quadOf(a: JSONArray): FloatArray = FloatArray(8) { a.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
        fun doublesOf(a: JSONArray): DoubleArray = DoubleArray(a.length()) { a.getDouble(it) }
    }

    private val grays = HashMap<String, Gray>()
    private fun gray(name: String) = grays.getOrPut(name) { readGray(name) }

    @Test
    fun constantsMirrorTheReference() {
        assertEquals(expected.getDouble("sigma"), TextureChange.SIGMA, 0.0)
        assertEquals(expected.getDouble("log_offset"), TextureChange.LOG_OFFSET, 0.0)
        assertEquals(expected.getInt("block"), TextureChange.BLOCK)
        assertEquals(expected.getDouble("block_inside"), TextureChange.BLOCK_INSIDE, 0.0)
        assertEquals(expected.getDouble("pad_frac"), TextureChange.PAD_FRAC, 0.0)
        assertEquals(expected.getDouble("percentile"), TextureChange.PERCENTILE, 0.0)
        assertEquals(expected.getDouble("scale"), TextureChange.SCALE, 0.0)
        assertEquals(expected.getDouble("start_threshold"), TextureChange.START_THRESHOLD, 0.0)
        assertEquals(DetectConst.MW, expected.getInt("sample_w"))
    }

    @Test
    fun gaussianKernelMatchesOpenCv() {
        val want = doublesOf(expected.getJSONArray("gaussian_kernel"))
        val got = TextureChange.gaussianKernel(TextureChange.SIGMA)
        assertEquals("taps", want.size, got.size)
        var maxDelta = 0.0
        for (i in want.indices) maxDelta = maxOf(maxDelta, abs(want[i] - got[i]))
        println("gaussian kernel: ${got.size} taps, max |Δ| = $maxDelta")
        assertTrue("kernel differs by $maxDelta", maxDelta <= 1e-12)
        assertEquals(1.0, got.sum(), 1e-12)
    }

    @Test
    fun percentileMatchesNumpy() {
        val cases = expected.getJSONArray("percentile_cases")
        var maxDelta = 0.0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val values = doublesOf(c.getJSONArray("values"))
            val got = TextureChange.percentile(values, TextureChange.PERCENTILE)
            maxDelta = maxOf(maxDelta, abs(got - c.getDouble("p75")))
            assertEquals("case $i (n=${values.size})", c.getDouble("p75"), got, 1e-12)
        }
        println("numpy.percentile: ${cases.length()} cases, max |Δ| = $maxDelta")
    }

    @Test
    fun paddedPolygonMasksMatchPixelForPixel() {
        val polys = expected.getJSONObject("polygons")
        val w = expected.getInt("sample_w"); val h = expected.getInt("sample_h")
        for (name in polys.keys()) {
            val p = polys.getJSONObject(name)
            val quad = quadOf(p.getJSONArray("quad"))
            val padded = TextureChange.padQuad(quad)
            val wantPadded = doublesOf(JSONArray().also { a -> val pa = p.getJSONArray("padded"); for (i in 0 until 4) { a.put(pa.getJSONArray(i).getDouble(0)); a.put(pa.getJSONArray(i).getDouble(1)) } })
            for (i in 0 until 8) assertEquals("$name padded[$i]", wantPadded[i], padded[i], 1e-9)
            val mask = TextureChange.polygonMask(quad, TextureChange.PAD_FRAC, w, h)
            var n = 0; var indexSum = 0L
            for (i in mask.indices) if (mask[i]) { n++; indexSum += i }
            assertEquals("$name mask pixels", p.getInt("mask_pixels"), n)
            assertEquals("$name mask index sum", p.getLong("mask_index_sum"), indexSum)
            assertEquals("$name blocks", p.getInt("blocks"), TextureChange.blocksInside(mask, w, h).size / 2)
            println("polygon $name: $n pixels, ${p.getInt("blocks")} blocks — identical")
        }
    }

    @Test
    fun signalsMatchTheReference() {
        val polys = expected.getJSONObject("polygons")
        val cases = expected.getJSONArray("cases")
        val w = expected.getInt("sample_w"); val h = expected.getInt("sample_h")
        val report = StringBuilder("tray   scenario                       polygon        blocks  logHP p75   Δ          logGrad   Δ          max block Δ\n")
        var maxP75 = 0.0; var maxGrad = 0.0; var maxBlock = 0.0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val tag = "${c.getString("tray")} ${c.getString("name")} / ${c.getString("polygon")}"
            val quad = quadOf(polys.getJSONObject(c.getString("polygon")).getJSONArray("quad"))
            val mask = TextureChange.polygonMask(quad, TextureChange.PAD_FRAC, w, h)
            val cur = gray(c.getString("cur")); val ref = gray(c.getString("ref"))
            assertEquals(w, cur.w); assertEquals(h, cur.h)
            val got = TextureChange.measure(cur, ref, mask)
            assertNotNull("$tag: no signal", got)
            got!!
            assertEquals("$tag blocks", c.getInt("blocks"), got.blocks)
            assertEquals("$tag mask pixels", c.getInt("mask_pixels"), got.maskPixels)
            // The block values: every one where the fixture carries them, their sum everywhere.
            val blocks = TextureChange.blocksInside(mask, w, h)
            val gotBlocks = TextureChange.blockValues(TextureChange.logImage(cur.px), TextureChange.logImage(ref.px), w, h, mask, blocks)
            assertEquals("$tag block count", c.getInt("blocks"), gotBlocks.size)
            var blockDelta = abs(c.getDouble("block_sum") - gotBlocks.sum()) / gotBlocks.size
            if (c.has("block_values")) {
                val wantBlocks = doublesOf(c.getJSONArray("block_values"))
                assertEquals("$tag block values", wantBlocks.size, gotBlocks.size)
                for (b in wantBlocks.indices) blockDelta = maxOf(blockDelta, abs(wantBlocks[b] - gotBlocks[b]))
            }
            val dP75 = abs(c.getDouble("log_hp_p75") - got.logHpP75)
            val dGrad = abs(c.getDouble("log_grad") - got.logGrad)
            maxP75 = maxOf(maxP75, dP75); maxGrad = maxOf(maxGrad, dGrad); maxBlock = maxOf(maxBlock, blockDelta)
            report.append(String.format("%-6s %-30s %-14s %-7d %-10.4f  %-10.2e %-9.4f %-10.2e %.2e\n",
                c.getString("tray"), c.getString("name"), c.getString("polygon"), got.blocks,
                got.logHpP75, dP75, got.logGrad, dGrad, blockDelta))
            assertTrue("$tag: logHP p75 ${got.logHpP75} vs ${c.getDouble("log_hp_p75")}", dP75 <= TOL)
            assertTrue("$tag: logGrad ${got.logGrad} vs ${c.getDouble("log_grad")}", dGrad <= TOL)
            assertTrue("$tag: a block value differs by $blockDelta", blockDelta <= TOL)
        }
        report.append(String.format("max |Δ|: logHP p75 %.2e, logGrad %.2e, block value %.2e (tolerance %.0e)\n", maxP75, maxGrad, maxBlock, TOL))
        println(report)
        assertTrue("fixture set shrank", cases.length() >= 10)
    }

    @Test
    fun theSyntheticCasesSeparateLightFromCard() {
        // On the fixture pairs themselves: every light-only case under, every
        // card change over, START_THRESHOLD (card A's own outline as the polygon).
        val polys = expected.getJSONObject("polygons")
        val cases = expected.getJSONArray("cases")
        val w = expected.getInt("sample_w"); val h = expected.getInt("sample_h")
        val mask = TextureChange.polygonMask(quadOf(polys.getJSONObject("card_A").getJSONArray("quad")), TextureChange.PAD_FRAC, w, h)
        var light = 0; var card = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (c.getString("polygon") != "card_A") continue
            val s = TextureChange.measure(gray(c.getString("cur")), gray(c.getString("ref")), mask)!!
            if (c.getString("cls") == "light") { light++; assertTrue("${c.getString("name")}: ${s.logHpP75}", s.logHpP75 < TextureChange.START_THRESHOLD) }
            else { card++; assertTrue("${c.getString("name")}: ${s.logHpP75}", s.logHpP75 > TextureChange.START_THRESHOLD) }
        }
        assertTrue(light >= 4 && card >= 3)
    }

    @Test
    fun helpersBehave() {
        // REFLECT_101: gfedcb|abcdefgh|gfedcba
        assertEquals(1, TextureChange.reflect101(-1, 8))
        assertEquals(2, TextureChange.reflect101(-2, 8))
        assertEquals(6, TextureChange.reflect101(8, 8))
        assertEquals(5, TextureChange.reflect101(9, 8))
        assertEquals(0, TextureChange.reflect101(5, 1))
        // Period 2·len−2 = 4 for len 3 (… 1 0 1 2 1 0 1 2 …): −20 ≡ −4 → 0, as OpenCV's borderInterpolate iterates it.
        assertEquals(0, TextureChange.reflect101(-20, 3))
        assertEquals(1, TextureChange.reflect101(-3, 3))
        assertEquals(2, TextureChange.reflect101(6, 3))
        // A uniform image: the blur is the identity, the high-pass zero, the gradient zero.
        val flat = DoubleArray(20 * 12) { 3.5 }
        for (v in TextureChange.gaussianBlur(flat, 20, 12, 4.0)) assertEquals(3.5, v, 1e-12)
        for (v in TextureChange.highPass(flat, 20, 12)) assertEquals(0.0, v, 1e-12)
        val (gx, gy) = TextureChange.sobel(flat, 20, 12)
        for (i in gx.indices) { assertEquals(0.0, gx[i], 0.0); assertEquals(0.0, gy[i], 0.0) }
        // A ramp: Sobel/8 is the slope, everywhere but the reflected border columns.
        val ramp = DoubleArray(20 * 12) { (it % 20) * 2.0 }
        val (rx, _) = TextureChange.sobel(ramp, 20, 12)
        assertEquals(2.0, rx[5 * 20 + 7], 1e-12)
        assertEquals(0.0, rx[5 * 20 + 0], 1e-12)
        // Nothing inside → null; a geometry mismatch (a new Area changed MH) → null, never a throw; identical frames → zero.
        val none = BooleanArray(20 * 12)
        assertNull(TextureChange.measure(IntArray(240) { 10 }, IntArray(240) { 10 }, 20, 12, none))
        assertNull(TextureChange.measure(Gray(20, 12, IntArray(240)), Gray(20, 10, IntArray(200)), BooleanArray(240) { true }))
        assertNull(TextureChange.measure(Gray(20, 12, IntArray(240)), Gray(20, 12, IntArray(240)), BooleanArray(200) { true }))
        assertNull(TextureChange.measure(IntArray(240), IntArray(200), 20, 12, BooleanArray(240) { true }))
        val all = BooleanArray(20 * 12) { true }
        val s = TextureChange.measure(IntArray(240) { it % 17 * 9 }, IntArray(240) { it % 17 * 9 }, 20, 12, all)!!
        assertEquals(0.0, s.logHpP75, 0.0); assertEquals(0.0, s.logGrad, 0.0)
        assertEquals(2, s.blocks); assertEquals(240, s.maskPixels)
        // Blocks need 48 of 64 pixels.
        val m = BooleanArray(32 * 16)
        for (y in 0 until 8) for (x in 0 until 8) m[y * 32 + x] = true
        for (y in 8 until 14) for (x in 8 until 16) m[y * 32 + x] = true      // 48
        for (y in 0 until 5) for (x in 16 until 24) m[y * 32 + x] = true      // 40
        assertTrue(TextureChange.blocksInside(m, 32, 16).contentEquals(intArrayOf(0, 0, 8, 8)))
    }
}
