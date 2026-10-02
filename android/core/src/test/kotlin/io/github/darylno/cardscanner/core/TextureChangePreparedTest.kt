package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app measures the shadow-mode signal every tick against a reference it
 * prepared ONCE for the wait ([TextureChange.prepare]); that path must give the
 * two-image [TextureChange.measure] exactly (same functions, same order — the
 * parity fixtures hold the two-image path to the Python reference).
 */
class TextureChangePreparedTest {
    private val w = 176
    private val h = 235

    private fun noisy(seed: Long, base: Int, spread: Int): IntArray {
        val r = java.util.Random(seed)
        return IntArray(w * h) { (base + r.nextInt(spread)).coerceIn(0, 255) }
    }

    @Test fun thePreparedPathIsBitIdenticalToTheDirectOne() {
        val cur = noisy(1, 90, 90); val ref = noisy(2, 100, 70)
        val quad = floatArrayOf(40f, 40f, 130f, 42f, 128f, 190f, 38f, 188f)
        val mask = TextureChange.polygonMask(quad, 0.15, w, h)
        val direct = TextureChange.measure(cur, ref, w, h, mask)
        val prepared = TextureChange.measure(Gray(w, h, cur), TextureChange.prepare(Gray(w, h, ref)), mask)
        assertNotNull(direct); assertNotNull(prepared)
        assertEquals(direct!!.logHpP75, prepared!!.logHpP75, 0.0)
        assertEquals(direct.logGrad, prepared.logGrad, 0.0)
        assertEquals(direct.blocks, prepared.blocks)
        assertEquals(direct.maskPixels, prepared.maskPixels)
        // Identical frames → 0 both ways.
        val same = TextureChange.measure(Gray(w, h, ref), TextureChange.prepare(Gray(w, h, ref)), mask)!!
        assertEquals(0.0, same.logHpP75, 0.0); assertEquals(0.0, same.logGrad, 0.0)
    }

    @Test fun aGeometryMismatchIsNullNotAThrow() {
        val ref = TextureChange.prepare(Gray(w, h, noisy(3, 100, 50)))
        val mask = BooleanArray(w * h) { true }
        assertNull(TextureChange.measure(Gray(w, h - 1, IntArray(w * (h - 1))), ref, mask))
        assertNull(TextureChange.measure(Gray(w, h, IntArray(w * h)), ref, BooleanArray(10)))
    }

    /** Printed, not asserted: the per-tick cost the analysis thread pays in shadow mode (x86; the phone reports its own in Diagnostics). */
    @Test fun costPerTick() {
        val refPx = noisy(5, 100, 70)
        val cur = Gray(w, h, noisy(4, 90, 90)); val ref = TextureChange.prepare(Gray(w, h, refPx))
        val mask = TextureChange.polygonMask(floatArrayOf(40f, 40f, 130f, 42f, 128f, 190f, 38f, 188f), 0.15, w, h)
        repeat(30) { TextureChange.measure(cur, ref, mask) }
        val n = 50
        val t0 = System.nanoTime()
        repeat(n) { TextureChange.measure(cur, ref, mask) }
        val prepared = (System.nanoTime() - t0) / 1e6 / n
        val t1 = System.nanoTime()
        repeat(n) { TextureChange.measure(cur.px, refPx, w, h, mask) }
        val direct = (System.nanoTime() - t1) / 1e6 / n
        println("TextureChange per tick (176×235, warm): prepared ref %.2f ms · two-image %.2f ms".format(prepared, direct))
    }
}
