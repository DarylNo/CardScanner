package io.github.darylno.cardscanner.core

import java.util.Random
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic DETECTION SAMPLES (the 176×MH grey the scanner ticks on) of a tray
 * with a card — or with something that is not a card — for measuring the
 * card-shape gate's print-evidence fallback ([PrintEvidence]).
 *
 * The card face is tests/card_scenes.py's `card_texture` (title bar with name
 * blocks, blocky art, type line, rules-text lines, collector line inside a
 * coloured frame) drawn in grey, plus scripts/score_card_edges.py's
 * white-border-with-DARK-frame variant. It is placed by centre / height / angle
 * at 4× supersampling and box-averaged down — what GraySampler's cell average
 * does to a full-resolution frame — then optionally blurred (an unfocused lens)
 * and given sensor noise. Seeded: the same scene comes out the same every run.
 */
object SyntheticTray {
    const val TEX_W = 315
    const val TEX_H = 440

    enum class Border(val label: String) { BLACK("black"), WHITE("white"), WHITE_DARK_FRAME("white+dark frame") }

    private fun grey(b: Int, g: Int, r: Int) = (0.114 * b + 0.587 * g + 0.299 * r).roundToInt()

    /** card_scenes.card_texture in grey (BGR → luma), seeded; [TEX_W]×[TEX_H]. */
    fun cardFace(seed: Long, border: Border): IntArray {
        val rnd = Random(seed)
        fun ri(a: Int, b: Int) = a + rnd.nextInt(b - a)
        val w = TEX_W; val h = TEX_H
        val img = IntArray(w * h) { if (border == Border.BLACK) 18 else 235 }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {     // cv2.rectangle(-1): both corners inclusive
            for (y in y0..y1) for (x in x0..x1) img[y * w + x] = v
        }
        rect(14, 14, w - 15, h - 15, grey(ri(120, 230), ri(120, 230), ri(120, 230)))
        rect(22, 22, w - 23, 52, grey(235, 230, 220))
        for (i in 0 until 6) { val x = 30 + i * 22; rect(x, 31, x + 14, 43, 30) }
        val art = Array(6) { IntArray(8) { grey(ri(0, 255), ri(0, 255), ri(0, 255)) } }
        val aw = w - 46; val ah = 170
        for (yy in 0 until ah) for (xx in 0 until aw) img[(58 + yy) * w + 23 + xx] = art[yy * 6 / ah][xx * 8 / aw]
        rect(22, 234, w - 23, 258, grey(235, 230, 220))
        rect(22, 264, w - 23, h - 48, grey(240, 236, 228))
        for (row in 0 until 8) {
            val y = 274 + row * 14
            val n = ri(5, 11)
            var x = 30
            for (k in 0 until n) {
                val ww = ri(8, 26)
                if (x + ww > w - 32) break
                rect(x, y, x + ww, y + 6, 40)
                x += ww + 6
            }
        }
        for (i in 0 until 9) rect(26 + i * 9, h - 36, 31 + i * 9, h - 30, 220)
        if (border == Border.WHITE_DARK_FRAME) {        // score_card_edges.texture's "wdark"
            val t0 = img.copyOf()
            val frame = t0[18 * w + 18]
            for (y in 0 until h) for (x in 0 until w) {
                val ring = x in 14 until w - 14 && y in 14 until h - 14
                val inner = x in 22 until w - 23 && y in 22 until h - 23
                if (ring && !inner) img[y * w + x] = 60
                else if (inner && t0[y * w + x] == frame) img[y * w + x] = 60
            }
        }
        return img
    }

    /**
     * A card on the tray: [face] ([cardFace]), centred at ([cx], [cy]) sample px,
     * [heightPx] tall (its 88 mm side), turned [angleDeg]. [sleeve]: a clear
     * sleeve — the face hazed (contrast ×0.88 toward 240) and a band 1.5 mm
     * round it 12 levels brighter than the tray.
     */
    class Card(val face: IntArray, val cx: Double, val cy: Double, val heightPx: Double, val angleDeg: Double, val sleeve: Boolean)

    /** Rounded-corner test in card mm (corner radius 3 mm). */
    private fun inRounded(u: Double, v: Double, x0: Double, y0: Double, x1: Double, y1: Double, r: Double): Boolean {
        if (u < x0 || v < y0 || u >= x1 || v >= y1) return false
        val cx = if (u < x0 + r) x0 + r else if (u > x1 - r) x1 - r else return true
        val cy = if (v < y0 + r) y0 + r else if (v > y1 - r) y1 - r else return true
        return hypot(u - cx, v - cy) <= r
    }

    /**
     * Render a [w]×[h] sample: [tray] gives the background at a sample-space
     * point (pixel EDGE convention: pixel (x, y) covers [x, x+1)×[y, y+1)),
     * [card] is drawn over it. [ss]× supersampled, box-averaged, then blurred by
     * [blur] sample px (0 = none) and given N(0, [noise]) per pixel ([seed]).
     */
    fun render(w: Int, h: Int, tray: (Double, Double) -> Double, card: Card? = null,
               blur: Double = 0.0, noise: Double = 1.0, seed: Long = 1, ss: Int = 4): Gray {
        val out = DoubleArray(w * h)
        val a = Math.toRadians(card?.angleDeg ?: 0.0)
        val ca = cos(a); val sa = sin(a)
        val scale = (card?.heightPx ?: 88.0) / 88.0          // sample px per mm
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0.0
            for (sy in 0 until ss) for (sx in 0 until ss) {
                val px = x + (sx + 0.5) / ss; val py = y + (sy + 0.5) / ss
                var v = tray(px, py)
                if (card != null) {
                    val dx = px - card.cx; val dy = py - card.cy
                    val u = (dx * ca + dy * sa) / scale + 31.5
                    val vv = (-dx * sa + dy * ca) / scale + 44.0
                    if (inRounded(u, vv, 0.0, 0.0, 63.0, 88.0, 3.0)) {
                        val tx = min(TEX_W - 1, (u * TEX_W / 63.0).toInt()); val ty = min(TEX_H - 1, (vv * TEX_H / 88.0).toInt())
                        val t = card.face[ty * TEX_W + tx].toDouble()
                        v = if (card.sleeve) 0.88 * t + 0.12 * 240 else t
                    } else if (card.sleeve && inRounded(u, vv, -1.5, -1.5, 64.5, 89.5, 4.5)) {
                        v = min(255.0, v + 12)
                    }
                }
                sum += v
            }
            out[y * w + x] = sum / (ss * ss)
        }
        val blurred = if (blur > 0) gaussian(out, w, h, blur) else out
        val rnd = Random(seed)
        return Gray(w, h, IntArray(w * h) { (blurred[it] + rnd.nextGaussian() * noise).roundToInt().coerceIn(0, 255) })
    }

    /** Separable Gaussian blur, replicated border. */
    fun gaussian(src: DoubleArray, w: Int, h: Int, sigma: Double): DoubleArray {
        val r = max(1, (3 * sigma).toInt() + 1)
        val k = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        val tmp = DoubleArray(w * h); val dst = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            for (i in -r..r) s += k[i + r] * src[y * w + (x + i).coerceIn(0, w - 1)]
            tmp[y * w + x] = s
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            for (i in -r..r) s += k[i + r] * tmp[(y + i).coerceIn(0, h - 1) * w + x]
            dst[y * w + x] = s
        }
        return dst
    }

    // ── trays and the things on them that are not cards ──

    fun flat(level: Double): (Double, Double) -> Double = { _, _ -> level }

    /**
     * A textured tray (a playmat, wood): a smooth random field of std ≈ [amp]
     * levels (Gaussian-blurred white noise, σ 1.2 sample px) over [level].
     */
    fun grain(w: Int, h: Int, level: Double, amp: Double, seed: Long): (Double, Double) -> Double {
        val rnd = Random(seed)
        val f = gaussian(DoubleArray(w * h) { rnd.nextGaussian() }, w, h, 1.2)
        val sd = sqrt(f.sumOf { it * it } / f.size)
        return { x, y -> level + amp * f[min(h - 1, y.toInt()) * w + min(w - 1, x.toInt())] / sd }
    }

    /** [base] plus a smooth Gaussian blob of [amp] levels (glare > 0, shadow < 0), σ [sx]×[sy] sample px at ([cx], [cy]). */
    fun blob(base: (Double, Double) -> Double, amp: Double, cx: Double, cy: Double, sx: Double, sy: Double): (Double, Double) -> Double =
        { x, y -> base(x, y) + amp * exp(-((x - cx) * (x - cx) / (2 * sx * sx) + (y - cy) * (y - cy) / (2 * sy * sy))) }

    /** [base] after a global exposure change: ×[gain] then +[offset]. */
    fun exposure(base: (Double, Double) -> Double, gain: Double, offset: Double): (Double, Double) -> Double =
        { x, y -> base(x, y) * gain + offset }

    /**
     * A hand-like blob: a filled ellipse ([cx], [cy], semi-axes [ax]×[ay]) of
     * skin [level] shaded ±[shade] levels top to bottom (a smooth back of a hand
     * or a fist; blur it to defocus the edge), optionally with a forearm band
     * [armW] wide running from it to the bottom of the Area.
     */
    fun hand(base: (Double, Double) -> Double, level: Double, cx: Double, cy: Double, ax: Double, ay: Double,
             shade: Double = 10.0, armW: Double = 0.0): (Double, Double) -> Double = { x, y ->
        val e = ((x - cx) / ax).let { it * it } + ((y - cy) / ay).let { it * it }
        val arm = armW > 0 && y >= cy && kotlin.math.abs(x - cx) <= armW / 2
        if (e <= 1.0 || arm) level + shade * ((y - cy) / ay).coerceIn(-1.0, 1.0) else base(x, y)
    }
}
