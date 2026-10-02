package io.github.darylno.cardscanner.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Port of the reference `mtg_card_scanner/change_signal.py`: the shadow-proof
 * "texture change" signal between two detection samples inside a watch
 * polygon — did the CARD change, or only the light on it? The WHY and the
 * measurement live in change_signal.py; this is the same arithmetic in pure
 * Kotlin (no OpenCV, no allocation beyond a few double arrays — it runs on
 * the analysis thread at ~5 Hz on a 176×MH sample), held to the reference's
 * answers by TextureChangeParityTest on the fixtures
 * `scripts/export_change_fixtures.py` writes (`core/src/test/resources/change/`).
 * Change change_signal.py first, re-export, then this.
 *
 * SHADOW MODE ONLY for now (CLAUDE.md: the Tray trigger is occupancy +
 * stillness): the app computes and logs it while waiting for the next card;
 * nothing re-arms or triggers on it until the rig confirms [START_THRESHOLD].
 *
 *   L  = ln(Y + LOG_OFFSET)                  a multiplicative shadow → an additive offset
 *   HP = L − gaussianBlur(L, σ = SIGMA)      the offset and any soft edge are removed
 *   per BLOCK×BLOCK block ≥ BLOCK_INSIDE inside the mask: mean |ΔHP| × SCALE
 *   logHP p75 = the 75th percentile of the block values (numpy's linear interpolation)
 *   logGrad  = mean over the mask of |∇L_cur − ∇L_ref| × SCALE (Sobel 3×3 / 8)
 *
 * Quads are in SAMPLE pixels with OpenCV's convention (pixel (x, y) centred on
 * the point (x, y)) — what [CardQuad.find] / [CardOutline.Outline.sampleQuad]
 * return; the mask tests integer pixel positions against the polygon.
 */
object TextureChange {
    const val SIGMA = 4.0
    const val LOG_OFFSET = 8.0
    const val BLOCK = 8
    const val BLOCK_INSIDE = 0.75
    const val PAD_FRAC = 0.15
    const val PERCENTILE = 75.0
    const val SCALE = 100.0
    /** logHP p75 above this = the card changed. SYNTHETIC midpoint — confirm on the rig (change_signal.py). */
    const val START_THRESHOLD = 8.0

    /** The signal between two samples: [logHpP75] (the decision value), [logGrad] (second opinion), what was watched. */
    class Signal(val logHpP75: Double, val logGrad: Double, val blocks: Int, val maskPixels: Int)

    /**
     * One image's derived planes — L, its high-pass and its Sobel gradient —
     * computed once. The scanned frame is fixed for a whole wait, so the app
     * prepares it once per watch instead of re-blurring it every tick (the
     * 33-tap blur is ~70 % of [measure]: 12.5 → 4.7 ms warm on x86 for
     * 176×235, so an estimated 15–20 ms per tick on the N200 — Diagnostics'
     * analyzer line reports the real "texture avg" there).
     * Same functions, same order, so the numbers are bit-identical to the
     * two-image [measure] (TextureChangePreparedTest).
     */
    class Prepared(val w: Int, val h: Int, val log: DoubleArray, val hp: DoubleArray, val gx: DoubleArray, val gy: DoubleArray) {
        companion object {
            fun of(px: IntArray, w: Int, h: Int): Prepared {
                val l = logImage(px)
                val (gx, gy) = sobel(l, w, h)
                return Prepared(w, h, l, highPass(l, w, h), gx, gy)
            }
        }
    }

    fun prepare(g: Gray): Prepared = Prepared.of(g.px, g.w, g.h)

    /** `pad_quad`: [quad] grown about its centroid so each side moves out by [padFrac] of the card's size. */
    fun padQuad(quad: FloatArray, padFrac: Double = PAD_FRAC): DoubleArray {
        require(quad.size == 8) { "need 4 corners (8 floats), got ${quad.size}" }
        var cx = 0.0; var cy = 0.0
        for (i in 0 until 4) { cx += quad[2 * i].toDouble(); cy += quad[2 * i + 1].toDouble() }
        cx /= 4.0; cy /= 4.0
        val k = 1.0 + 2.0 * padFrac
        return DoubleArray(8) {
            val c = if (it % 2 == 0) cx else cy
            c + (quad[it].toDouble() - c) * k
        }
    }

    /**
     * `polygon_mask`: which pixels of a [w]×[h] sample lie inside [quad] padded
     * by [padFrac] (edges count as inside). Row-major BooleanArray of w·h.
     */
    fun polygonMask(quad: FloatArray, padFrac: Double = PAD_FRAC, w: Int, h: Int): BooleanArray =
        polygonMaskOf(padQuad(quad, padFrac), w, h)

    /** The mask of an already-padded polygon [p] (8 doubles, TL TR BR BL). Same arithmetic order as the reference. */
    fun polygonMaskOf(p: DoubleArray, w: Int, h: Int): BooleanArray {
        require(p.size == 8) { "need 4 corners (8 doubles), got ${p.size}" }
        var area2 = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area2 += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        val sign = if (area2 >= 0.0) 1.0 else -1.0
        val mask = BooleanArray(w * h) { true }
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val x0 = p[2 * i]; val y0 = p[2 * i + 1]
            val x1 = p[2 * j]; val y1 = p[2 * j + 1]
            val dx = x1 - x0; val dy = y1 - y0
            for (y in 0 until h) {
                val a = dx * (y - y0)
                val row = y * w
                for (x in 0 until w) {
                    if (mask[row + x]) {
                        val cross = a - dy * (x - x0)
                        if (sign * cross < 0.0) mask[row + x] = false
                    }
                }
            }
        }
        return mask
    }

    /** `blocks_inside`: top-left (y, x) of every whole block ≥ [BLOCK_INSIDE] inside [mask], row-major. */
    fun blocksInside(mask: BooleanArray, w: Int, h: Int): IntArray {
        val out = ArrayList<Int>()
        val need = BLOCK_INSIDE * (BLOCK * BLOCK)
        var by = 0
        while (by + BLOCK <= h) {
            var bx = 0
            while (bx + BLOCK <= w) {
                var n = 0
                for (y in by until by + BLOCK) for (x in bx until bx + BLOCK) if (mask[y * w + x]) n++
                // numpy: mean() >= 0.75 ⇔ n/64 >= 0.75 ⇔ n >= 48 (exact in binary).
                if (n >= need) { out += by; out += bx }
                bx += BLOCK
            }
            by += BLOCK
        }
        return out.toIntArray()
    }

    /**
     * `measure` on two [Gray] samples; null when no block is inside [mask] —
     * or when the geometries differ (a new scan Area changes MH between the
     * scanned frame and the current sample; the reference raises, the phone
     * just has nothing to compare), so a logging-only caller never throws.
     */
    fun measure(cur: Gray, ref: Gray, mask: BooleanArray): Signal? {
        if (cur.w != ref.w || cur.h != ref.h || mask.size != cur.size) return null
        return measure(cur.px, ref.px, cur.w, cur.h, mask)
    }

    /** The same on raw pixel arrays of a [w]×[h] sample; null on a size mismatch or no block inside [mask]. */
    fun measure(cur: IntArray, ref: IntArray, w: Int, h: Int, mask: BooleanArray): Signal? {
        if (w <= 0 || h <= 0 || cur.size != w * h || ref.size != w * h || mask.size != w * h) return null
        return measure(Prepared.of(cur, w, h), Prepared.of(ref, w, h), mask)
    }

    /** [measure] against a reference prepared once ([prepare]); null on a geometry mismatch or no block inside [mask]. */
    fun measure(cur: Gray, ref: Prepared, mask: BooleanArray): Signal? {
        if (cur.w != ref.w || cur.h != ref.h || mask.size != cur.size) return null
        return measure(Prepared.of(cur.px, cur.w, cur.h), ref, mask)
    }

    /** The signal between two prepared images of the same geometry; null when no block is inside [mask]. */
    fun measure(cur: Prepared, ref: Prepared, mask: BooleanArray): Signal? {
        val w = cur.w; val h = cur.h
        if (w <= 0 || h <= 0 || ref.w != w || ref.h != h || mask.size != w * h) return null
        val blocks = blocksInside(mask, w, h)
        if (blocks.isEmpty()) return null
        val vals = blockValuesOfHp(cur.hp, ref.hp, w, h, mask, blocks)
        var maskPixels = 0
        for (m in mask) if (m) maskPixels++
        return Signal(percentile(vals, PERCENTILE), logGradOf(cur.gx, cur.gy, ref.gx, ref.gy, w, h, mask), blocks.size / 2, maskPixels)
    }

    /** `block_values` on the log images: each block's mean |ΔHP| × SCALE over its masked pixels, in block order. */
    fun blockValues(lc: DoubleArray, lr: DoubleArray, w: Int, h: Int, mask: BooleanArray, blocks: IntArray): DoubleArray =
        blockValuesOfHp(highPass(lc, w, h), highPass(lr, w, h), w, h, mask, blocks)

    /** [blockValues] on already high-passed images. */
    fun blockValuesOfHp(hc: DoubleArray, hr: DoubleArray, w: Int, h: Int, mask: BooleanArray, blocks: IntArray): DoubleArray {
        val n = blocks.size / 2
        return DoubleArray(n) { b ->
            val by = blocks[2 * b]; val bx = blocks[2 * b + 1]
            var sum = 0.0; var cnt = 0
            for (y in by until by + BLOCK) for (x in bx until bx + BLOCK) {
                val i = y * w + x
                if (mask[i]) { sum += abs(hc[i] - hr[i]); cnt++ }
            }
            sum / cnt * SCALE
        }
    }

    /** `log_grad`: mean over the mask of hypot(Δgx, Δgy) × SCALE. */
    fun logGrad(lc: DoubleArray, lr: DoubleArray, w: Int, h: Int, mask: BooleanArray): Double {
        val gc = sobel(lc, w, h); val gr = sobel(lr, w, h)
        return logGradOf(gc.first, gc.second, gr.first, gr.second, w, h, mask)
    }

    /** [logGrad] on already computed Sobel gradients. */
    fun logGradOf(cgx: DoubleArray, cgy: DoubleArray, rgx: DoubleArray, rgy: DoubleArray, w: Int, h: Int, mask: BooleanArray): Double {
        var sum = 0.0; var cnt = 0
        for (i in 0 until w * h) if (mask[i]) {
            sum += hypot(cgx[i] - rgx[i], cgy[i] - rgy[i]); cnt++
        }
        return sum / cnt * SCALE
    }

    /** `log_image`: ln(Y + LOG_OFFSET). */
    fun logImage(px: IntArray): DoubleArray = DoubleArray(px.size) { ln(px[it].toDouble() + LOG_OFFSET) }

    /** `high_pass`: L − gaussianBlur(L). */
    fun highPass(l: DoubleArray, w: Int, h: Int): DoubleArray {
        val b = gaussianBlur(l, w, h, SIGMA)
        return DoubleArray(l.size) { l[it] - b[it] }
    }

    /**
     * cv2.getGaussianKernel(ksize = round(8σ + 1) | 1, σ) for a CV_64F image:
     * exp(−x²/(2σ²)) at x = i − (n−1)/2, normalised to sum 1 — the same
     * expression order as OpenCV's.
     */
    fun gaussianKernel(sigma: Double): DoubleArray {
        val n = (Math.round(sigma * 4 * 2 + 1).toInt()) or 1
        val scale2X = -0.5 / (sigma * sigma)
        val k = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val x = i - (n - 1) * 0.5
            k[i] = exp(scale2X * x * x)
            sum += k[i]
        }
        for (i in 0 until n) k[i] /= sum
        return k
    }

    /** OpenCV BORDER_REFLECT_101 (`gfedcb|abcdefgh|gfedcba`) for index [p] in a length-[len] axis. */
    fun reflect101(p: Int, len: Int): Int {
        if (len == 1) return 0
        var q = p
        while (q < 0 || q >= len) q = if (q < 0) -q else 2 * len - q - 2
        return q
    }

    /** cv2.GaussianBlur(src, (0, 0), σ) on a CV_64F [w]×[h] image: separable, rows then columns, REFLECT_101. */
    fun gaussianBlur(src: DoubleArray, w: Int, h: Int, sigma: Double): DoubleArray {
        val k = gaussianKernel(sigma)
        val r = k.size / 2
        val tmp = DoubleArray(w * h)
        val xi = Array(w) { x -> IntArray(k.size) { j -> reflect101(x + j - r, w) } }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0.0
                val idx = xi[x]
                for (j in k.indices) acc += k[j] * src[row + idx[j]]
                tmp[row + x] = acc
            }
        }
        val out = DoubleArray(w * h)
        val yi = Array(h) { y -> IntArray(k.size) { j -> reflect101(y + j - r, h) } }
        for (y in 0 until h) {
            val idx = yi[y]
            for (x in 0 until w) {
                var acc = 0.0
                for (j in k.indices) acc += k[j] * tmp[idx[j] * w + x]
                out[y * w + x] = acc
            }
        }
        return out
    }

    /** cv2.Sobel(L, CV_64F, 1, 0, 3) / 8 and (0, 1) / 8, REFLECT_101 borders: (gx, gy). */
    fun sobel(l: DoubleArray, w: Int, h: Int): Pair<DoubleArray, DoubleArray> {
        val gx = DoubleArray(w * h); val gy = DoubleArray(w * h)
        for (y in 0 until h) {
            val ym = reflect101(y - 1, h) * w; val y0 = y * w; val yp = reflect101(y + 1, h) * w
            for (x in 0 until w) {
                val xm = reflect101(x - 1, w); val xp = reflect101(x + 1, w)
                gx[y0 + x] = ((l[ym + xp] - l[ym + xm]) + 2 * (l[y0 + xp] - l[y0 + xm]) + (l[yp + xp] - l[yp + xm])) / 8
                gy[y0 + x] = ((l[yp + xm] - l[ym + xm]) + 2 * (l[yp + x] - l[ym + x]) + (l[yp + xp] - l[ym + xp])) / 8
            }
        }
        return Pair(gx, gy)
    }

    /** numpy.percentile(values, q) with its default linear interpolation (and its t ≥ 0.5 lerp branch). */
    fun percentile(values: DoubleArray, q: Double): Double {
        require(values.isNotEmpty()) { "percentile of nothing" }
        val v = values.sortedArray()
        val n = v.size
        val virtual = q / 100.0 * (n - 1)
        val lo = floor(virtual).toInt().coerceIn(0, n - 1)
        val hi = (lo + 1).coerceAtMost(n - 1)
        val t = virtual - lo
        val a = v[lo]; val b = v[hi]
        val diff = b - a
        return if (t >= 0.5) b - diff * (1 - t) else a + diff * t
    }
}
