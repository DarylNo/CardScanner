package io.github.darylno.cardscanner.core

import java.util.concurrent.ConcurrentHashMap

/** An 8-bit single-channel image, row-major, `width × height` bytes (PIL mode "L"). */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init { require(width >= 0 && height >= 0 && pixels.size == width * height) { "bad gray image $width×$height/${pixels.size}" } }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    /** PIL `Image.crop((x0, y0, x1, y1))` for a box inside the image: columns x0..x1-1, rows y0..y1-1. */
    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): GrayImage {
        require(x0 in 0..x1 && y0 in 0..y1 && x1 <= width && y1 <= height) { "crop ($x0,$y0,$x1,$y1) outside $width×$height" }
        val w = x1 - x0
        val h = y1 - y0
        val out = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(pixels, (y + y0) * width + x0, out, y * w, w)
        return GrayImage(w, h, out)
    }
}

/**
 * Bit-exact port of `imagehash.phash` (ImageHash 4.3) as the server runs it:
 *
 *     image.convert("L").resize((4n, 4n), LANCZOS)          # Pillow
 *     dct = scipy.fftpack.dct(scipy.fftpack.dct(pixels, axis=0), axis=1)
 *     low = dct[:n, :n];  bits = low > numpy.median(low)
 *
 * Every step reproduces the reference's arithmetic, not just its maths —
 * the art-index thresholds (110 / 140+20 / 210) only transfer if the bits do:
 *  - **L conversion** is Pillow's fixed-point `rgb2l` (Convert.c `L24`).
 *  - **Lanczos** is Pillow's `ImagingResample` (Resample.c): two separable
 *    passes (horizontal first, only over the rows the vertical pass reads),
 *    double-precision filter weights normalised per output pixel, then
 *    quantised to 22-bit fixed point with Pillow's round-half-away and summed
 *    in `int` with its clip8.
 * - **DCT-II** is NOT a textbook O(n²) sum: it is a transliteration of
 *    pocketfft's `T_dcst23` (the C++ library behind scipy.fftpack.dct since
 *    SciPy 1.4) — the same halfcomplex backward rFFT (radix-4/2 passes), the
 *    same twiddle tables (`sincos_2pibyn`), the same operation order. A
 *    mathematically equal DCT differs in the last bits, which is enough to
 *    flip `> median` on flat/gradient regions whose AC terms are ≈0.
 *
 * Trig: the DCT twiddles must equal glibc's to the last bit, so they are
 * correctly rounded ([CorrectlyRounded]); the Lanczos weights use
 * [StrictMath] (fdlibm — identical on every JVM and on ART), which can be an
 * ulp off glibc but is proven to give identical 22-bit weights for every
 * input extent 1..700 (HashParityTest.everyLanczosCoefficientTableMatchesPillow).
 */
object PHash {
    /** Pillow `rgb2l`: ITU-R 601-2 luma in 16.16 fixed point, rounded. */
    fun luma(r: Int, g: Int, b: Int): Int = (r * 19595 + g * 38470 + b * 7471 + 0x8000) ushr 16

    /** Interleaved 8-bit BGR (OpenCV order, `w*h*3` bytes) → Pillow "L". */
    fun grayFromBgr(bgr: ByteArray, width: Int, height: Int): GrayImage {
        require(bgr.size == width * height * 3) { "expected ${width * height * 3} BGR bytes, got ${bgr.size}" }
        val out = ByteArray(width * height)
        var j = 0
        for (i in out.indices) {
            val b = bgr[j].toInt() and 0xFF
            val g = bgr[j + 1].toInt() and 0xFF
            val r = bgr[j + 2].toInt() and 0xFF
            out[i] = luma(r, g, b).toByte()
            j += 3
        }
        return GrayImage(width, height, out)
    }

    // ── Pillow LANCZOS ──────────────────────────────────────────────────────

    private const val PRECISION_BITS = 32 - 8 - 2
    private const val LANCZOS_SUPPORT = 3.0

    private fun sinc(x0: Double): Double {
        if (x0 == 0.0) return 1.0
        val x = x0 * Math.PI
        return StrictMath.sin(x) / x
    }

    private fun lanczos(x: Double): Double =
        if (-3.0 <= x && x < 3.0) sinc(x) * sinc(x / 3) else 0.0

    /** Pillow `precompute_coeffs` + `normalize_coeffs_8bpc` for a full-extent box. */
    private class Coeffs(val ksize: Int, val bounds: IntArray, val kk: IntArray)

    private val coeffCache = ConcurrentHashMap<Long, Coeffs>()

    private fun coeffs(inSize: Int, outSize: Int): Coeffs =
        coeffCache.getOrPut((inSize.toLong() shl 32) or outSize.toLong()) { computeCoeffs(inSize, outSize) }

    private fun computeCoeffs(inSize: Int, outSize: Int): Coeffs {
        // box = (0, 0, inSize, …) as C floats: in1 - in0 is exact for any real image size.
        val scale = inSize.toDouble() / outSize
        val filterscale = if (scale < 1.0) 1.0 else scale
        val support = LANCZOS_SUPPORT * filterscale
        val ksize = Math.ceil(support).toInt() * 2 + 1
        val bounds = IntArray(outSize * 2)
        val kk = IntArray(outSize * ksize)
        val k = DoubleArray(ksize)
        val invFilterscale = 1.0 / filterscale
        for (xx in 0 until outSize) {
            val center = 0.0 + (xx + 0.5) * scale
            var ww = 0.0
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            for (x in 0 until xmax) {
                // C: (x + xmin - center + 0.5) — the int sum first, then doubles.
                val w = lanczos(((x + xmin).toDouble() - center + 0.5) * invFilterscale)
                k[x] = w
                ww += w
            }
            if (ww != 0.0) for (x in 0 until xmax) k[x] /= ww
            for (x in xmax until ksize) k[x] = 0.0
            for (x in 0 until ksize) {
                val v = k[x]
                kk[xx * ksize + x] =
                    if (v < 0) (-0.5 + v * (1 shl PRECISION_BITS)).toInt()
                    else (0.5 + v * (1 shl PRECISION_BITS)).toInt()
            }
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coeffs(ksize, bounds, kk)
    }

    /**
     * The fixed-point weight of input column p for output xx, as an
     * `inSize × outSize` row-major matrix (0 outside the window) — what
     * Pillow's resize of an impulse image in mode "I" reads back, which is how
     * the parity fixtures check every coefficient table the phone can hit.
     */
    internal fun lanczosCoefficientMatrix(inSize: Int, outSize: Int): IntArray {
        val c = coeffs(inSize, outSize)
        val m = IntArray(inSize * outSize)
        for (xx in 0 until outSize) {
            val xmin = c.bounds[xx * 2]
            val xmax = c.bounds[xx * 2 + 1]
            for (x in 0 until xmax) m[(x + xmin) * outSize + xx] = c.kk[xx * c.ksize + x]
        }
        return m
    }

    private fun clip8(v: Int): Int {
        val s = v shr PRECISION_BITS
        return if (s < 0) 0 else if (s > 255) 255 else s
    }

    /** Pillow `Image.resize((outW, outH), Image.Resampling.LANCZOS)` on an "L" image. */
    fun lanczosResize(src: GrayImage, outW: Int, outH: Int): GrayImage {
        if (src.width == outW && src.height == outH) return GrayImage(outW, outH, src.pixels.copyOf())
        val needH = outW != src.width
        val needV = outH != src.height
        val vert = coeffs(src.height, outH)
        val vBounds = vert.bounds.copyOf()
        val yFirst = vBounds[0]
        val yLast = vBounds[outH * 2 - 2] + vBounds[outH * 2 - 1]

        var inW = src.width
        var inPx = src.pixels
        if (needH) {
            val hor = coeffs(src.width, outW)
            for (i in 0 until outH) vBounds[i * 2] -= yFirst
            val rows = yLast - yFirst
            val tmp = ByteArray(outW * rows)
            val ks = hor.ksize
            for (yy in 0 until rows) {
                val rowOff = (yy + yFirst) * src.width
                for (xx in 0 until outW) {
                    val xmin = hor.bounds[xx * 2]
                    val xmax = hor.bounds[xx * 2 + 1]
                    val kOff = xx * ks
                    var ss = 1 shl (PRECISION_BITS - 1)
                    for (x in 0 until xmax) ss += (src.pixels[rowOff + x + xmin].toInt() and 0xFF) * hor.kk[kOff + x]
                    tmp[yy * outW + xx] = clip8(ss).toByte()
                }
            }
            inW = outW
            inPx = tmp
        }
        if (!needV) return GrayImage(inW, outH, inPx)
        val out = ByteArray(inW * outH)
        val ks = vert.ksize
        for (yy in 0 until outH) {
            val ymin = vBounds[yy * 2]
            val ymax = vBounds[yy * 2 + 1]
            val kOff = yy * ks
            for (xx in 0 until inW) {
                var ss = 1 shl (PRECISION_BITS - 1)
                for (y in 0 until ymax) ss += (inPx[(y + ymin) * inW + xx].toInt() and 0xFF) * vert.kk[kOff + y]
                out[yy * inW + xx] = clip8(ss).toByte()
            }
        }
        return GrayImage(inW, outH, out)
    }

    // ── pocketfft DCT-II (unnormalised, forward) ────────────────────────────

    /**
     * sin/cos rounded correctly to the nearest double — what glibc's libm (the
     * reference's `std::sin/std::cos`) returns. Measured: fdlibm (StrictMath)
     * is off by 1 ulp on ~10% of pocketfft's twiddle arguments and the
     * intrinsic Math.sin on a few more, which moved the 32-point DCT by up to
     * 1.5e-11 and flipped `> median` on real cards. Only used to build the
     * twiddle tables (a few hundred values, once per plan).
     */
    internal object CorrectlyRounded {
        private val MC = java.math.MathContext(60)
        private val EPS = java.math.BigDecimal.ONE.movePointLeft(70)

        fun sin(v: Double): Double = nearest(series(java.math.BigDecimal(v), odd = true))
        fun cos(v: Double): Double = nearest(series(java.math.BigDecimal(v), odd = false))

        /** Taylor series; the arguments here are all |v| ≤ π/4. */
        private fun series(x: java.math.BigDecimal, odd: Boolean): java.math.BigDecimal {
            require(x.abs() <= java.math.BigDecimal("0.8")) { "series used outside [-π/4, π/4]: $x" }
            val x2 = x.multiply(x, MC).negate()
            var term = if (odd) x else java.math.BigDecimal.ONE
            var sum = term
            var k = if (odd) 1 else 0
            while (term.abs() > EPS) {
                term = term.multiply(x2, MC).divide(java.math.BigDecimal((k + 1).toLong() * (k + 2)), MC)
                sum = sum.add(term, MC)
                k += 2
            }
            return sum
        }

        /** The double nearest to [exact] (platform BigDecimal→double conversion is not trusted). */
        private fun nearest(exact: java.math.BigDecimal): Double {
            val d = exact.toDouble()
            var best = d
            var bestErr = java.math.BigDecimal(d).subtract(exact).abs()
            for (c in doubleArrayOf(Math.nextDown(d), Math.nextUp(d))) {
                val err = java.math.BigDecimal(c).subtract(exact).abs()
                if (err < bestErr) { best = c; bestErr = err }
            }
            return best
        }
    }

    /** pocketfft `sincos_2pibyn<double>` — the twiddle generator, same split tables. */
    private class SinCos2PiByN(private val n: Int) {
        private val mask: Int
        private val shift: Int
        private val v1r: DoubleArray; private val v1i: DoubleArray
        private val v2r: DoubleArray; private val v2i: DoubleArray

        init {
            val ang = 0.25 * Math.PI / n   // Thigh(0.25L*pi/n): exact power-of-two scaling for these n
            val nval = (n + 2) / 2
            var s = 1
            while ((1 shl s) * (1 shl s) < nval) ++s
            shift = s
            mask = (1 shl s) - 1
            v1r = DoubleArray(mask + 1); v1i = DoubleArray(mask + 1)
            v1r[0] = 1.0
            for (i in 1..mask) calc(i, ang).let { v1r[i] = it[0]; v1i[i] = it[1] }
            val n2 = (nval + mask) / (mask + 1)
            v2r = DoubleArray(n2); v2i = DoubleArray(n2)
            v2r[0] = 1.0
            for (i in 1 until n2) calc(i * (mask + 1), ang).let { v2r[i] = it[0]; v2i[i] = it[1] }
        }

        private fun calc(x0: Int, ang: Double): DoubleArray {
            var x = x0.toLong() shl 3
            val n = n.toLong()
            fun c(v: Long) = CorrectlyRounded.cos(v.toDouble() * ang)
            fun s(v: Long) = CorrectlyRounded.sin(v.toDouble() * ang)
            if (x < 4 * n) {
                if (x < 2 * n) {
                    return if (x < n) doubleArrayOf(c(x), s(x)) else doubleArrayOf(s(2 * n - x), c(2 * n - x))
                }
                x -= 2 * n
                return if (x < n) doubleArrayOf(-s(x), c(x)) else doubleArrayOf(-c(2 * n - x), s(2 * n - x))
            }
            x = 8 * n - x
            if (x < 2 * n) {
                return if (x < n) doubleArrayOf(c(x), -s(x)) else doubleArrayOf(s(2 * n - x), -c(2 * n - x))
            }
            x -= 2 * n
            return if (x < n) doubleArrayOf(-s(x), -c(x)) else doubleArrayOf(-c(2 * n - x), -s(2 * n - x))
        }

        fun re(idx0: Int): Double {
            val idx = if (2 * idx0 <= n) idx0 else n - idx0
            val a = idx and mask; val b = idx shr shift
            return v1r[a] * v2r[b] - v1i[a] * v2i[b]
        }

        fun im(idx0: Int): Double {
            if (2 * idx0 <= n) {
                val a = idx0 and mask; val b = idx0 shr shift
                return v1r[a] * v2i[b] + v1i[a] * v2r[b]
            }
            val idx = n - idx0
            val a = idx and mask; val b = idx shr shift
            return -(v1r[a] * v2i[b] + v1i[a] * v2r[b])
        }
    }

    /** pocketfft `rfftp` — only the halfcomplex→real (backward) direction DCT-II uses, radix 4 and 2. */
    private class Rfftp(private val n: Int) {
        private val fact: IntArray
        private val tw: Array<DoubleArray>

        init {
            val f = ArrayList<Int>()
            var len = n
            while (len % 4 == 0) { f.add(4); len = len shr 2 }
            if (len % 2 == 0) {
                len = len shr 1
                f.add(2)
                val t = f[0]; f[0] = f[f.size - 1]; f[f.size - 1] = t
            }
            require(len == 1) { "pocketfft port supports power-of-two lengths only (got $n)" }
            fact = f.toIntArray()
            val twid = SinCos2PiByN(n)
            var l1 = 1
            tw = Array(fact.size) { k ->
                val ip = fact[k]
                val ido = n / (l1 * ip)
                val t = DoubleArray(maxOf(0, (ip - 1) * (ido - 1)))
                if (k < fact.size - 1) {
                    for (j in 1 until ip) for (i in 1..(ido - 1) / 2) {
                        t[(j - 1) * (ido - 1) + 2 * i - 2] = twid.re(j * l1 * i)
                        t[(j - 1) * (ido - 1) + 2 * i - 1] = twid.im(j * l1 * i)
                    }
                }
                l1 *= ip
                t
            }
        }

        /** In place on [c] (length n), fct = 1. */
        fun backward(c: DoubleArray) {
            var p1 = c
            var p2 = DoubleArray(n)
            var l1 = 1
            for (k in fact.indices) {
                val ip = fact[k]
                val ido = n / (ip * l1)
                if (ip == 4) radb4(ido, l1, p1, p2, tw[k]) else radb2(ido, l1, p1, p2, tw[k])
                val t = p1; p1 = p2; p2 = t
                l1 *= ip
            }
            if (p1 !== c) System.arraycopy(p1, 0, c, 0, n)
        }

        private fun radb2(ido: Int, l1: Int, cc: DoubleArray, ch: DoubleArray, wa: DoubleArray) {
            fun cc(a: Int, b: Int, c: Int) = cc[a + ido * (b + 2 * c)]
            fun ch(a: Int, b: Int, c: Int, v: Double) { ch[a + ido * (b + l1 * c)] = v }
            fun wa(x: Int, i: Int) = wa[i + x * (ido - 1)]
            for (k in 0 until l1) {
                val a = cc(0, 0, k); val b = cc(ido - 1, 1, k)
                ch(0, k, 0, a + b); ch(0, k, 1, a - b)
            }
            if ((ido and 1) == 0) for (k in 0 until l1) {
                ch(ido - 1, k, 0, 2 * cc(ido - 1, 0, k))
                ch(ido - 1, k, 1, -2 * cc(0, 1, k))
            }
            if (ido <= 2) return
            for (k in 0 until l1) {
                var i = 2
                while (i < ido) {
                    val ic = ido - i
                    ch(i - 1, k, 0, cc(i - 1, 0, k) + cc(ic - 1, 1, k))
                    val tr2 = cc(i - 1, 0, k) - cc(ic - 1, 1, k)
                    val ti2 = cc(i, 0, k) + cc(ic, 1, k)
                    ch(i, k, 0, cc(i, 0, k) - cc(ic, 1, k))
                    // MULPM(a, b, c, d, e, f): a = c*e + d*f; b = c*f - d*e
                    val w0 = wa(0, i - 2); val w1 = wa(0, i - 1)
                    ch(i, k, 1, w0 * ti2 + w1 * tr2)
                    ch(i - 1, k, 1, w0 * tr2 - w1 * ti2)
                    i += 2
                }
            }
        }

        private fun radb4(ido: Int, l1: Int, cc: DoubleArray, ch: DoubleArray, wa: DoubleArray) {
            fun cc(a: Int, b: Int, c: Int) = cc[a + ido * (b + 4 * c)]
            fun ch(a: Int, b: Int, c: Int, v: Double) { ch[a + ido * (b + l1 * c)] = v }
            fun wa(x: Int, i: Int) = wa[i + x * (ido - 1)]
            for (k in 0 until l1) {
                val tr2 = cc(0, 0, k) + cc(ido - 1, 3, k)
                val tr1 = cc(0, 0, k) - cc(ido - 1, 3, k)
                val tr3 = 2 * cc(ido - 1, 1, k)
                val tr4 = 2 * cc(0, 2, k)
                ch(0, k, 0, tr2 + tr3); ch(0, k, 2, tr2 - tr3)
                ch(0, k, 3, tr1 + tr4); ch(0, k, 1, tr1 - tr4)
            }
            if ((ido and 1) == 0) for (k in 0 until l1) {
                val ti1 = cc(0, 3, k) + cc(0, 1, k)
                val ti2 = cc(0, 3, k) - cc(0, 1, k)
                val tr2 = cc(ido - 1, 0, k) + cc(ido - 1, 2, k)
                val tr1 = cc(ido - 1, 0, k) - cc(ido - 1, 2, k)
                ch(ido - 1, k, 0, tr2 + tr2)
                ch(ido - 1, k, 1, SQRT2 * (tr1 - ti1))
                ch(ido - 1, k, 2, ti2 + ti2)
                ch(ido - 1, k, 3, -SQRT2 * (tr1 + ti1))
            }
            if (ido <= 2) return
            for (k in 0 until l1) {
                var i = 2
                while (i < ido) {
                    val ic = ido - i
                    val tr2 = cc(i - 1, 0, k) + cc(ic - 1, 3, k); val tr1 = cc(i - 1, 0, k) - cc(ic - 1, 3, k)
                    val ti1 = cc(i, 0, k) + cc(ic, 3, k); val ti2 = cc(i, 0, k) - cc(ic, 3, k)
                    val tr4 = cc(i, 2, k) + cc(ic, 1, k); val ti3 = cc(i, 2, k) - cc(ic, 1, k)
                    val tr3 = cc(i - 1, 2, k) + cc(ic - 1, 1, k); val ti4 = cc(i - 1, 2, k) - cc(ic - 1, 1, k)
                    ch(i - 1, k, 0, tr2 + tr3); val cr3 = tr2 - tr3
                    ch(i, k, 0, ti2 + ti3); val ci3 = ti2 - ti3
                    val cr4 = tr1 + tr4; val cr2 = tr1 - tr4
                    val ci2 = ti1 + ti4; val ci4 = ti1 - ti4
                    var w0 = wa(0, i - 2); var w1 = wa(0, i - 1)
                    ch(i, k, 1, w0 * ci2 + w1 * cr2); ch(i - 1, k, 1, w0 * cr2 - w1 * ci2)
                    w0 = wa(1, i - 2); w1 = wa(1, i - 1)
                    ch(i, k, 2, w0 * ci3 + w1 * cr3); ch(i - 1, k, 2, w0 * cr3 - w1 * ci3)
                    w0 = wa(2, i - 2); w1 = wa(2, i - 1)
                    ch(i, k, 3, w0 * ci4 + w1 * cr4); ch(i - 1, k, 3, w0 * cr4 - w1 * ci4)
                    i += 2
                }
            }
        }
    }

    /** sqrt(2) as pocketfft's `T0(1.414213562373095048801688724209698L)`. */
    private val SQRT2 = Math.sqrt(2.0)

    /** pocketfft `T_dcst23<double>` DCT-II, cosine, not ortho, fct = 1. */
    private class Dct2(private val n: Int) {
        private val fft = Rfftp(n)
        private val twiddle = SinCos2PiByN(4 * n).let { t -> DoubleArray(n) { t.re(it + 1) } }

        /** In place on [c]. */
        fun exec(c: DoubleArray) {
            val ns2 = (n + 1) / 2
            c[0] *= 2
            if ((n and 1) == 0) c[n - 1] *= 2
            var k = 1
            while (k < n - 1) {   // MPINPLACE(c[k+1], c[k])
                val t = c[k + 1]
                c[k + 1] = t - c[k]
                c[k] = t + c[k]
                k += 2
            }
            fft.backward(c)
            k = 1
            var kc = n - 1
            while (k < ns2) {
                val t1 = twiddle[k - 1] * c[kc] + twiddle[kc - 1] * c[k]
                val t2 = twiddle[k - 1] * c[k] - twiddle[kc - 1] * c[kc]
                c[k] = 0.5 * (t1 + t2)
                c[kc] = 0.5 * (t1 - t2)
                ++k; --kc
            }
            if ((n and 1) == 0) c[ns2] *= twiddle[ns2 - 1]
        }
    }

    private val dctPlans = ConcurrentHashMap<Int, Dct2>()
    private fun dctPlan(n: Int): Dct2 = dctPlans.getOrPut(n) { Dct2(n) }

    /**
     * `scipy.fftpack.dct(scipy.fftpack.dct(pixels, axis=0), axis=1)` of a square
     * `n × n` image; returns row-major doubles.
     */
    fun dct2d(img: GrayImage): DoubleArray {
        require(img.width == img.height) { "dct2d needs a square image" }
        val n = img.width
        val plan = dctPlan(n)
        val out = DoubleArray(n * n)
        val col = DoubleArray(n)
        for (x in 0 until n) {                       // axis 0: down each column
            for (y in 0 until n) col[y] = img[x, y].toDouble()
            plan.exec(col)
            for (y in 0 until n) out[y * n + x] = col[y]
        }
        val row = DoubleArray(n)
        for (y in 0 until n) {                       // axis 1: along each row
            System.arraycopy(out, y * n, row, 0, n)
            plan.exec(row)
            System.arraycopy(row, 0, out, y * n, n)
        }
        return out
    }

    /** `numpy.median` of an even- or odd-length sample. */
    fun median(values: DoubleArray): Double {
        val s = values.copyOf()
        s.sort()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /**
     * The phash bits of [img] as imagehash lays them out: row-major over the low
     * `hashSize × hashSize` DCT block, the FIRST bit being the most significant
     * of the hex string — packed into big-endian 64-bit words (the server's
     * `_split_u64`). hashSize 8 → 1 word, 16 → 4 words.
     */
    fun phashWords(img: GrayImage, hashSize: Int): LongArray {
        val size = hashSize * 4
        val small = lanczosResize(img, size, size)
        val dct = dct2d(small)
        val low = DoubleArray(hashSize * hashSize)
        for (y in 0 until hashSize) for (x in 0 until hashSize) low[y * hashSize + x] = dct[y * size + x]
        val med = median(low)
        val words = LongArray((low.size + 63) / 64)
        for (i in low.indices) {
            if (low[i] > med) {
                val bitFromTop = i % 64
                words[i / 64] = words[i / 64] or (1L shl (63 - bitFromTop))
            }
        }
        return words
    }

    /** `int(str(imagehash.phash(img)), 16)`. */
    fun phash64(img: GrayImage): Long = phashWords(img, 8)[0]

    /** `_split_u64(str(imagehash.phash(img, hash_size=16)), 4)`. */
    fun phash256(img: GrayImage): LongArray = phashWords(img, 16)
}
