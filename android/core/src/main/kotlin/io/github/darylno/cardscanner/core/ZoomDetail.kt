package io.github.darylno.cardscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How much real detail a capture carries, for the zoom A/B (1.1.17, the guided
 * session's A1 / B / A2 steps: the same cards at 1× and zoomed). A diagnostic
 * only — logged next to each capture, never used to decide anything, so there is
 * no Python reference for it (like [PrintEvidence]).
 *
 * The frame-scale sharpness ("sharpness a / [b] / c", the Laplacian of the Area's
 * grey) is NOT comparable across zooms: a zoomed frame shows the same card over
 * more pixels. [identRaster] measures on the identifier's OWN raster instead —
 * the card warped to 630×880 ([CardQuad.warpCard] at its defaults, what
 * CardExtract.extractCard hands the OCR) — so a card at 1× and the same card at
 * 1.9× are scored on the same grid, over the collector strip the OCR actually
 * reads ([OcrStrip.stripBounds]). Comparable as long as the zoom stays within
 * the sensor's lossless range, which ZoomFit.choose caps it to.
 *
 * THE ZOOM PROBE'S DETAIL FIGURE (1.1.17, [ZoomProbe]) is different: it must
 * find where zoom stops adding real detail, which the Laplacian cannot (ISP
 * sharpening of upsampled pixels and aliasing both read as "sharper"). Each
 * rung's frames are warped with their OWN quads onto one card raster at a fixed
 * scale ([warpToScale] / [stepImage], the native scale of the top rung), and
 * compared with the top rung's image by COHERENCE ([coherence]): the share of
 * each spatial frequency's power the two images have in common, per 0.25 c/mm
 * band of card space. Coherence is blind to gain and offset (AE), to any linear
 * filter on either image (sharpening, the warp's interpolation) and to shifts
 * (each tile's is removed); detail an image does not really carry (noise,
 * aliasing) is incoherent with the reference. [Coherence.fRes] = the card-space
 * frequency where it falls through 0.5. [thumbnail] / [ncc] tell the same card
 * from another (the probe's restore check). Still a diagnostic only.
 *
 * MEASURED: the coherence KNEE failed its pre-registered build-time acceptance
 * (ZoomProbeTest: 0 of 10 synthetic cameras within ±0.10× of the true knee) —
 * rungs past the knee share the reference's sensor samples and upsampling
 * replicas, so they stay coherent with it, and finder jitter blurs the low rungs'
 * step images. So the probe does not use it at run time ([ZoomProbe.DETAIL_VALIDATED]
 * is false); it stays, tested, for the acceptance and a future metric. The
 * thumbnail and [ncc] ARE used (the restore's same-card check).
 */
object ZoomDetail {
    /**
     * The card figure skips this much of each side of the warp: the finder's quad
     * sits a little outside the card (a sleeve, the dilation), and a sliver of tray
     * past the card's edge is a hard edge that is not the card's print.
     * PROVISIONAL — a judgement, not a measurement (the 5:7 crop's own slack is ~2 %).
     */
    const val CARD_INSET = 0.06

    /**
     * Detail on the identification raster: the Laplacian variance of the collector
     * strip ([strip]) and of the card inset [CARD_INSET] ([card]), both on the
     * [CardQuad.CARD_W]×[CardQuad.CARD_H] warp.
     */
    class Ident(val strip: Double, val card: Double)

    /** The strip [identRaster] scores: `[y0, y1, x0, x1]` of the 630×880 warp — the OCR's own ([OcrStrip.stripBounds]). */
    fun stripBounds(): IntArray = OcrStrip.stripBounds(CardQuad.CARD_W, CardQuad.CARD_H)

    /**
     * [frame] (BGR or grey, any size) with the card at [quad] (TL,TR,BR,BL in its
     * pixels) → the [Ident] of its 630×880 warp. Throws on an OpenCV failure; the
     * capture pipeline calls it inside runCatching (a capture never fails for it).
     */
    fun identRaster(frame: Mat, quad: FloatArray): Ident {
        val grey = if (frame.channels() == 1) frame else Mat().also { Imgproc.cvtColor(frame, it, Imgproc.COLOR_BGR2GRAY) }
        try {
            val card = CardQuad.warpCard(grey, quad, CardQuad.CARD_W, CardQuad.CARD_H)
            try {
                val (y0, y1, x0, x1) = stripBounds().toList()
                val strip = card.submat(Rect(x0, y0, x1 - x0, y1 - y0))
                val ix = (CardQuad.CARD_W * CARD_INSET).toInt(); val iy = (CardQuad.CARD_H * CARD_INSET).toInt()
                val inner = card.submat(Rect(ix, iy, CardQuad.CARD_W - 2 * ix, CardQuad.CARD_H - 2 * iy))
                try {
                    // laplacianVariance scores a submat as a standalone copy: no pixel outside it counts.
                    return Ident(Sharpness.laplacianVariance(strip), Sharpness.laplacianVariance(inner))
                } finally {
                    strip.release(); inner.release()
                }
            } finally {
                card.release()
            }
        } finally {
            if (grey !== frame) grey.release()
        }
    }

    // ── the zoom probe: coherence against a reference rung (1.1.17) ──

    /** A card's sides in mm (63 × 88): the probe raster is the card at a fixed px/mm. */
    const val CARD_MM_W = 63.0
    const val CARD_MM_H = 88.0

    /** Coherence tiles are this many raster px square (Hann-windowed). */
    const val TILE = 64

    /** The width of a coherence band in card space, c/mm. */
    const val BAND = 0.25

    /** [Coherence.fRes] is where γ² falls through this: half the power in common. */
    const val COHERENT = 0.5

    /**
     * A tile's phase-correlation shift beyond this is a flat tile's noise, not a
     * misregistration (the quads of one card agree within ~2 px): no correction then.
     * PROVISIONAL — a judgement, not a measurement.
     */
    const val MAX_SHIFT_PX = 3.0

    /** The restore check's grey card thumbnail (CardQuad's own 64×88 texture scale). */
    const val THUMB_W = 64
    const val THUMB_H = 88

    /** The raster for [pxPerMm]: the whole card, `[w, h]` px. */
    fun rasterSize(pxPerMm: Double): IntArray =
        intArrayOf((CARD_MM_W * pxPerMm).roundToInt(), (CARD_MM_H * pxPerMm).roundToInt())

    /**
     * The card at [quad] (TL,TR,BR,BL in [frame]'s pixels) of [frame] (grey or BGR,
     * any depth) → the whole card at [pxPerMm] as a CV_32F luma raster ([rasterSize]),
     * INTER_CUBIC from a float copy of the quad's bounding box (no 8-bit rounding after
     * the warp). The caller releases it.
     */
    fun warpToScale(frame: Mat, quad: FloatArray, pxPerMm: Double): Mat {
        require(quad.size == 8) { "need 4 points (8 floats), got ${quad.size}" }
        val (w, h) = rasterSize(pxPerMm).toList()
        val xs = FloatArray(4) { quad[2 * it] }; val ys = FloatArray(4) { quad[2 * it + 1] }
        val bx0 = max(0, floor(xs.min()).toInt() - 4); val by0 = max(0, floor(ys.min()).toInt() - 4)
        val bx1 = min(frame.cols(), ceil(xs.max()).toInt() + 5); val by1 = min(frame.rows(), ceil(ys.max()).toInt() + 5)
        require(bx1 > bx0 && by1 > by0) { "the quad is outside the frame" }
        val view = frame.submat(Rect(bx0, by0, bx1 - bx0, by1 - by0))
        val grey = Mat(); val f = Mat(); val out = Mat()
        val src = CardQuad.quadMat(CardQuad.translate(quad, -bx0.toFloat(), -by0.toFloat()))
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(w.toDouble(), 0.0), Point(w.toDouble(), h.toDouble()), Point(0.0, h.toDouble()))
        var m: Mat? = null
        try {
            if (view.channels() == 1) view.copyTo(grey) else Imgproc.cvtColor(view, grey, Imgproc.COLOR_BGR2GRAY)
            grey.convertTo(f, CvType.CV_32F)
            m = Imgproc.getPerspectiveTransform(src, dst)
            Imgproc.warpPerspective(f, out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            return out
        } catch (e: Throwable) {
            out.release(); throw e
        } finally {
            view.release(); grey.release(); f.release(); src.release(); dst.release(); m?.release()
        }
    }

    /**
     * A rung's step image: each frame warped with ITS OWN quad ([warpToScale]) and
     * averaged (independent noise falls by √n; the card is where each frame's finder
     * put it). The caller releases it.
     */
    fun stepImage(frames: List<Mat>, quads: List<FloatArray>, pxPerMm: Double): Mat {
        require(frames.isNotEmpty() && frames.size == quads.size) { "${frames.size} frames, ${quads.size} quads" }
        val acc = warpToScale(frames[0], quads[0], pxPerMm)
        for (i in 1 until frames.size) {
            val w = warpToScale(frames[i], quads[i], pxPerMm)
            Core.add(acc, w, acc)
            w.release()
        }
        if (frames.size > 1) acc.convertTo(acc, -1, 1.0 / frames.size)
        return acc
    }

    /**
     * Where on the card coherence is measured, in card fractions, and the tile step:
     * the card inset [CARD_INSET] with tiles side by side, and the OCR's collector
     * strip ([OcrStrip.STRIP_X] / [OcrStrip.STRIP_Y]) with tiles half overlapping
     * (it is only ~10 mm tall).
     */
    enum class Region(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val step: Int) {
        CARD(CARD_INSET, CARD_INSET, 1 - CARD_INSET, 1 - CARD_INSET, TILE),
        STRIP(OcrStrip.STRIP_X[0], OcrStrip.STRIP_Y[0], OcrStrip.STRIP_X[1], OcrStrip.STRIP_Y[1], TILE / 2),
    }

    /**
     * γ² per [BAND] of card space: [freqs] = each band's centre (c/mm), [gamma2] = its
     * coherence (NaN = no frequency bin falls in it), over [tiles] tiles, up to the
     * raster's Nyquist ([pxPerMm] / 2).
     */
    class Coherence(val pxPerMm: Double, val freqs: DoubleArray, val gamma2: DoubleArray, val tiles: Int) {
        val nyquist: Double get() = pxPerMm / 2
        /** The card-space frequency where γ² falls through [COHERENT] ([fRes]). */
        val fRes: Double = fRes(freqs, gamma2, nyquist)

        /** γ² at [f] c/mm, linearly interpolated between band centres; NaN outside them. */
        fun at(f: Double): Double {
            var prev = -1
            for (i in freqs.indices) {
                if (gamma2[i].isNaN()) continue
                if (freqs[i] >= f) {
                    if (freqs[i] == f || prev < 0) return if (freqs[i] == f) gamma2[i] else Double.NaN
                    val t = (f - freqs[prev]) / (freqs[i] - freqs[prev])
                    return gamma2[prev] + t * (gamma2[i] - gamma2[prev])
                }
                prev = i
            }
            return Double.NaN
        }
    }

    /**
     * The frequency where [gamma2] (per band centre [freqs]) first falls through
     * [COHERENT], scanning up from a virtual γ² = 1 at 0 c/mm and linearly
     * interpolated; [nyquist] when it never does (identical images). Empty bands
     * (NaN) are skipped.
     */
    fun fRes(freqs: DoubleArray, gamma2: DoubleArray, nyquist: Double): Double {
        var f0 = 0.0; var g0 = 1.0
        for (i in freqs.indices) {
            val g = gamma2[i]
            if (g.isNaN()) continue
            if (g < COHERENT) return f0 + (g0 - COHERENT) / (g0 - g) * (freqs[i] - f0)
            f0 = freqs[i]; g0 = g
        }
        return nyquist
    }

    /** The 2-D Hann window of a [TILE] (OpenCV's createHanningWindow, built here so the DFT and phaseCorrelate share it). */
    private val hann: FloatArray by lazy {
        val w = DoubleArray(TILE) { 0.5 * (1 - cos(2 * PI * it / (TILE - 1))) }
        FloatArray(TILE * TILE) { (w[it / TILE] * w[it % TILE]).toFloat() }
    }

    /**
     * The coherence of [img] with [ref] (two CV_32F rasters of one size at [pxPerMm],
     * e.g. two [stepImage]s) over [region]. Each tile pair: means removed, Hann-
     * windowed, [img]'s sub-pixel shift relative to [ref] measured
     * (Imgproc.phaseCorrelate) and taken out of the cross spectrum as a phase ramp
     * (no resampling). The cross and auto spectra are summed over the tiles PER
     * FREQUENCY BIN, and each band's γ² is the mean of its bins' — rather than one
     * sum over the whole annulus, which a common residual shift (phaseCorrelate's
     * own sub-pixel bias, ~0.1 px) would rotate apart and read as lost detail.
     */
    fun coherence(img: Mat, ref: Mat, pxPerMm: Double, region: Region = Region.CARD): Coherence {
        require(img.type() == CvType.CV_32FC1 && ref.type() == CvType.CV_32FC1) { "coherence needs CV_32F rasters" }
        require(img.rows() == ref.rows() && img.cols() == ref.cols()) { "the rasters differ in size" }
        val w = img.cols(); val h = img.rows()
        val rx0 = (region.x0 * w).toInt(); val ry0 = (region.y0 * h).toInt()
        val rx1 = (region.x1 * w).toInt(); val ry1 = (region.y1 * h).toInt()
        val n = TILE * TILE
        val nyq = pxPerMm / 2
        val nBands = floor(nyq / BAND + 1e-9).toInt()
        // Each DFT bin's band (−1 = DC or past the last full band) and its signed frequency index.
        val band = IntArray(n); val ku = IntArray(n); val kv = IntArray(n)
        for (i in 0 until n) {
            val u = i % TILE; val v = i / TILE
            ku[i] = if (u < TILE / 2) u else u - TILE; kv[i] = if (v < TILE / 2) v else v - TILE
            val f = sqrt((ku[i] * ku[i] + kv[i] * kv[i]).toDouble()) / TILE * pxPerMm
            band[i] = if (f <= 0.0 || f >= nBands * BAND) -1 else floor(f / BAND).toInt()
        }
        val cr = DoubleArray(n); val ci = DoubleArray(n); val pa = DoubleArray(n); val pb = DoubleArray(n)
        val win = Mat(TILE, TILE, CvType.CV_32F).also { it.put(0, 0, hann) }
        val ta = Mat(TILE, TILE, CvType.CV_32F); val tb = Mat(TILE, TILE, CvType.CV_32F)
        val sa = FloatArray(n); val sb = FloatArray(n)
        val fa = Mat(); val fb = Mat()
        val da = FloatArray(2 * n); val db = FloatArray(2 * n)
        var tiles = 0
        try {
            var y = ry0
            while (y + TILE <= ry1) {
                var x = rx0
                while (x + TILE <= rx1) {
                    img.submat(y, y + TILE, x, x + TILE).let { it.get(0, 0, sa); it.release() }
                    ref.submat(y, y + TILE, x, x + TILE).let { it.get(0, 0, sb); it.release() }
                    val ma = sa.average().toFloat(); val mb = sb.average().toFloat()
                    for (i in 0 until n) { sa[i] -= ma; sb[i] -= mb }
                    ta.put(0, 0, sa); tb.put(0, 0, sb)
                    // phaseCorrelate(ref, img) = d with img(x) ≈ ref(x − d).
                    val d = Imgproc.phaseCorrelate(tb, ta, win)
                    val dx = if (abs(d.x) <= MAX_SHIFT_PX && abs(d.y) <= MAX_SHIFT_PX) d.x else 0.0
                    val dy = if (abs(d.x) <= MAX_SHIFT_PX && abs(d.y) <= MAX_SHIFT_PX) d.y else 0.0
                    for (i in 0 until n) { sa[i] *= hann[i]; sb[i] *= hann[i] }
                    ta.put(0, 0, sa); tb.put(0, 0, sb)
                    Core.dft(ta, fa, Core.DFT_COMPLEX_OUTPUT, 0); Core.dft(tb, fb, Core.DFT_COMPLEX_OUTPUT, 0)
                    fa.get(0, 0, da); fb.get(0, 0, db)
                    for (i in 0 until n) {
                        if (band[i] < 0) continue
                        val ar = da[2 * i].toDouble(); val ai = da[2 * i + 1].toDouble()
                        val br = db[2 * i].toDouble(); val bi = db[2 * i + 1].toDouble()
                        // A·conj(B) carries e^{-2πi k·d/N} for img = ref shifted by d: undo it.
                        val xr = ar * br + ai * bi; val xi = ai * br - ar * bi
                        val ph = 2 * PI * (ku[i] * dx + kv[i] * dy) / TILE
                        val c = cos(ph); val s = sin(ph)
                        cr[i] += xr * c - xi * s; ci[i] += xr * s + xi * c
                        pa[i] += ar * ar + ai * ai; pb[i] += br * br + bi * bi
                    }
                    tiles++
                    x += region.step
                }
                y += region.step
            }
        } finally {
            win.release(); ta.release(); tb.release(); fa.release(); fb.release()
        }
        val sum = DoubleArray(nBands); val cnt = IntArray(nBands)
        for (i in 0 until n) {
            val b = band[i]
            if (b < 0) continue
            val den = pa[i] * pb[i]
            if (den <= 0.0) continue
            sum[b] += (cr[i] * cr[i] + ci[i] * ci[i]) / den
            cnt[b]++
        }
        return Coherence(pxPerMm, DoubleArray(nBands) { (it + 0.5) * BAND },
            DoubleArray(nBands) { if (cnt[it] == 0) Double.NaN else sum[it] / cnt[it] }, tiles)
    }

    /**
     * The card at [quad] of [frame] as a [THUMB_W]×[THUMB_H] grey thumbnail of its
     * inset ([CARD_INSET]: no tray sliver), area-averaged from the 630×880 warp and
     * normalised to zero mean and unit variance — for [ncc]. Throws on an OpenCV failure.
     */
    fun thumbnail(frame: Mat, quad: FloatArray): FloatArray {
        val grey = if (frame.channels() == 1) frame else Mat().also { Imgproc.cvtColor(frame, it, Imgproc.COLOR_BGR2GRAY) }
        val card = CardQuad.warpCard(grey, quad, CardQuad.CARD_W, CardQuad.CARD_H)
        val small = Mat(); val f = Mat()
        try {
            val ix = (CardQuad.CARD_W * CARD_INSET).toInt(); val iy = (CardQuad.CARD_H * CARD_INSET).toInt()
            val inner = card.submat(Rect(ix, iy, CardQuad.CARD_W - 2 * ix, CardQuad.CARD_H - 2 * iy))
            Imgproc.resize(inner, small, Size(THUMB_W.toDouble(), THUMB_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            inner.release()
            small.convertTo(f, CvType.CV_32F)
            val px = FloatArray(THUMB_W * THUMB_H)
            f.get(0, 0, px)
            val mean = px.average()
            val sd = sqrt(px.sumOf { (it - mean) * (it - mean) } / px.size)
            return FloatArray(px.size) { if (sd > 0) ((px[it] - mean) / sd).toFloat() else 0f }
        } finally {
            card.release(); small.release(); f.release()
            if (grey !== frame) grey.release()
        }
    }

    /** Normalised cross-correlation of two [thumbnail]s: 1 = the same picture, ~0 = unrelated. */
    fun ncc(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "thumbnails differ in size" }
        var s = 0.0
        for (i in a.indices) s += a[i].toDouble() * b[i]
        return s / a.size
    }
}
