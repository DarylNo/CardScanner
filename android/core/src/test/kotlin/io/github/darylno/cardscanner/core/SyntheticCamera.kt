package io.github.darylno.cardscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A synthetic phone camera for the zoom probe (ZoomProbeTest): a card master at
 * [PX_PER_MM] → optics + pixel aperture → the sensor readout grid (full, or 2×2
 * binned) → per-frame sensor noise → the STREAM at zoom z, a centred crop of
 * the readout scaled to the stream (area-filtered down, bilinear up) → ISP
 * sharpening → stream noise → 8 bit. Its knee is KNOWN: past z = [Config.lossless]
 * (readout ÷ stream) the stream only upsamples readout pixels. The finder is
 * modelled too: each frame's quad is the true corners pushed out by a bias and
 * jittered. Only a window round the card is rendered (the tray is flat). Seeded.
 *
 * The model and the acceptance grid were written down before the first run
 * (scratchpad PREREG_ZOOM_ACCEPTANCE.md, quoted in the 1.1.17 commit).
 */
object SyntheticCamera {
    const val PX_PER_MM = 40.0
    const val MARGIN_MM = 6.0
    const val TRAY = 200.0
    /** The card is turned this much on the tray (the finder's quads are never axis-aligned). */
    const val ANGLE_DEG = 1.2

    class Noise(val read: Double, val shot: Double, val label: String)
    val LOW = Noise(1.0, 0.02, "low")
    val MID = Noise(1.5, 0.04, "mid")
    val HIGH = Noise(2.5, 0.08, "high")

    /**
     * One camera: a [stream]-px long side, the card [h1] px long at 1×, a sensor
     * readout of [readout] px on its long side ([bin] = 2: a 2×2-binned readout of a
     * sensor twice that), the optics' Gaussian [psf] in full-sensor px, [noise],
     * ISP [sharpen]ing and an extra [defocus] blur (full-sensor px). The window is
     * always centred on the card (the crop's geometry is ZoomProbeTest's, in numbers).
     */
    class Config(
        val name: String, val stream: Int, val h1: Int, val readout: Int, val bin: Int, val psf: Double, val noise: Noise,
        val sharpen: Boolean = true, val defocus: Double = 0.0,
    ) {
        /** The true knee: readout ÷ stream. */
        val lossless: Double get() = readout.toDouble() / stream
    }

    /** One frame of the card window: 8-bit luma, the finder's quad, the true quad (TL,TR,BR,BL) and the zoom it is at. */
    class Frame(val mat: Mat, val quad: FloatArray, val truth: FloatArray, val z: Double)

    // ── the card master ──

    /** The card master (CV_32F, the card plus [MARGIN_MM] of tray each side, [PX_PER_MM]). */
    fun master(seed: Long): Mat {
        val rnd = Random(seed)
        val w = ((63 + 2 * MARGIN_MM) * PX_PER_MM).toInt(); val h = ((88 + 2 * MARGIN_MM) * PX_PER_MM).toInt()
        val m = Mat(h, w, CvType.CV_32F, Scalar(TRAY))
        fun px(mm: Double) = (mm + MARGIN_MM) * PX_PER_MM
        fun rect(x0: Double, y0: Double, x1: Double, y1: Double, v: Double) =
            Imgproc.rectangle(m, Point(px(x0), px(y0)), Point(px(x1) - 1, px(y1) - 1), Scalar(v), -1)
        rect(0.0, 0.0, 63.0, 88.0, 20.0)                                    // black border
        rect(3.0, 3.0, 60.0, 85.0, 90.0 + rnd.nextInt(80))                   // the coloured frame
        // Text: letter-like stroke glyphs, [h] mm tall, strokes [stroke] mm, level [v].
        fun line(x0: Double, x1: Double, y: Double, gh: Double, stroke: Double, v: Double) {
            var x = x0
            val gw = 0.6 * gh
            while (x + gw < x1) {
                if (rnd.nextDouble() < 0.15) { x += gw; continue }          // a space
                repeat(2 + rnd.nextInt(3)) {
                    when (rnd.nextInt(3)) {
                        0 -> { val sx = x + rnd.nextDouble() * (gw - stroke); rect(sx, y, sx + stroke, y + gh, v) }
                        1 -> { val sy = y + rnd.nextDouble() * (gh - stroke); rect(x, sy, x + gw, sy + stroke, v) }
                        else -> Imgproc.line(m, Point(px(x), px(y + gh)), Point(px(x + gw), px(y)), Scalar(v),
                            (stroke * PX_PER_MM).toInt().coerceAtLeast(1), Imgproc.LINE_AA)
                    }
                }
                x += gw * 1.15
            }
        }
        rect(4.5, 4.5, 58.5, 10.0, 228.0); line(6.0, 45.0, 6.0, 2.6, 0.35, 30.0)
        // The art: 1/f octave noise (amplitude ∝ scale: equal power per octave), std 40 round 120.
        val ax0 = px(5.0).toInt(); val ay0 = px(11.5).toInt(); val ax1 = px(58.0).toInt(); val ay1 = px(49.0).toInt()
        val art = Mat(ay1 - ay0, ax1 - ax0, CvType.CV_32F, Scalar(0.0))
        val n = Mat(art.size(), CvType.CV_32F); val b = Mat()
        Core.setRNGSeed(rnd.nextInt())
        for (sMm in doubleArrayOf(0.05, 0.1, 0.2, 0.4, 0.8, 1.6, 3.2)) {
            Core.randn(n, 0.0, 1.0)
            Imgproc.GaussianBlur(n, b, Size(0.0, 0.0), sMm * PX_PER_MM)
            Core.addWeighted(art, 1.0, b, sMm, 0.0, art)
        }
        val mean = org.opencv.core.MatOfDouble(); val sd = org.opencv.core.MatOfDouble()
        Core.meanStdDev(art, mean, sd)
        art.convertTo(art, -1, 40.0 / sd.toArray()[0], 120.0 - 40.0 * mean.toArray()[0] / sd.toArray()[0])
        Core.min(art, Scalar(250.0), art); Core.max(art, Scalar(5.0), art)
        art.copyTo(m.submat(Rect(ax0, ay0, ax1 - ax0, ay1 - ay0)))
        n.release(); b.release(); art.release()
        rect(4.5, 50.5, 58.5, 55.5, 225.0); line(6.0, 40.0, 51.8, 2.0, 0.3, 30.0)
        rect(5.0, 57.0, 58.0, 80.0, 235.0)
        for (row in 0 until 6) line(6.5, 56.5, 58.5 + row * 3.4, 1.6, 0.22, 35.0)
        // The collector line(s): white on the dark bottom of the frame / border.
        rect(3.0, 80.5, 60.0, 85.0, 25.0)
        line(3.5, 30.0, 81.4, 1.1, 0.17, 215.0)
        line(3.5, 24.0, 83.3, 1.1, 0.17, 215.0)
        return m
    }

    // ── the camera ──

    /** One camera ([cfg]) looking at one card ([master]): the noise-free readout is rendered once. */
    class Camera(val cfg: Config, master: Mat) {
        /** Stream px/mm at 1×, full-sensor px/mm, readout px/mm. */
        val s1 = cfg.h1 / 88.0
        private val sFull = s1 * cfg.lossless * cfg.bin
        val s = sFull / cfg.bin
        private val th = Math.toRadians(ANGLE_DEG)
        private val ct = cos(th); private val st = sin(th)
        /** The card's bounding box + 3 mm (the stream window) and + 5 mm (the readout patch), in mm. */
        private val bw = 63 * ct + 88 * st; private val bh = 88 * ct + 63 * st
        private val ws = ceil((bw + 10) * s).toInt(); private val hs = ceil((bh + 10) * s).toInt()
        private val readout = Mat()
        private val sigma = Mat()

        init {
            // Optics + pixel aperture (a Gaussian for the box: σ² = bin²/12), in master px.
            val sig = sqrt(cfg.psf * cfg.psf + cfg.bin * cfg.bin / 12.0 + cfg.defocus * cfg.defocus) * PX_PER_MM / sFull
            val blurred = Mat(); Imgproc.GaussianBlur(master, blurred, Size(0.0, 0.0), sig)
            // readout px (i, j) → mm from the card centre → the card's own axes → master px.
            val cs = (ws - 1) / 2.0; val csy = (hs - 1) / 2.0
            val k = PX_PER_MM / s
            val off = (31.5 + MARGIN_MM) * PX_PER_MM - 0.5; val offY = (44 + MARGIN_MM) * PX_PER_MM - 0.5
            val a = Mat(2, 3, CvType.CV_64F)
            a.put(0, 0, k * ct, k * st, -k * ct * cs - k * st * csy + off,
                -k * st, k * ct, k * st * cs - k * ct * csy + offY)
            Imgproc.warpAffine(blurred, readout, a, Size(ws.toDouble(), hs.toDouble()),
                Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
            blurred.release(); a.release()
            // Per-pixel noise σ = sqrt(read² + shot·signal).
            val t = Mat(); Core.max(readout, Scalar(0.0), t)
            t.convertTo(t, -1, cfg.noise.shot, cfg.noise.read * cfg.noise.read)
            Core.sqrt(t, sigma); t.release()
        }

        /** The stream window at [z]: [w]×[h] px, centred on the card. */
        private fun windowSize(z: Double): IntArray = intArrayOf(ceil((bw + 6) * s1 * z).toInt(), ceil((bh + 6) * s1 * z).toInt())

        /** The true quad (TL,TR,BR,BL) in the window at [z]. */
        fun trueQuad(z: Double): FloatArray {
            val (w, h) = windowSize(z).toList()
            val cw = (w - 1) / 2.0; val ch = (h - 1) / 2.0
            val corners = arrayOf(doubleArrayOf(-31.5, -44.0), doubleArrayOf(31.5, -44.0), doubleArrayOf(31.5, 44.0), doubleArrayOf(-31.5, 44.0))
            val q = FloatArray(8)
            for ((i, c) in corners.withIndex()) {
                val x = c[0] * ct - c[1] * st; val y = c[0] * st + c[1] * ct
                q[2 * i] = (x * s1 * z + cw).toFloat(); q[2 * i + 1] = (y * s1 * z + ch).toFloat()
            }
            return q
        }

        /** [n] frames at [z] (seeded by [rnd]): independent sensor and stream noise, a fresh finder quad each. */
        fun frames(z: Double, n: Int, rnd: Random): List<Frame> {
            val (w, h) = windowSize(z).toList()
            val r = cfg.lossless / z                                     // readout px per stream px
            val cw = (w - 1) / 2.0; val ch = (h - 1) / 2.0
            val cs = (ws - 1) / 2.0; val csy = (hs - 1) / 2.0
            val map = Mat(2, 3, CvType.CV_64F).also { it.put(0, 0, r, 0.0, cs - cw * r, 0.0, r, csy - ch * r) }
            val out = ArrayList<Frame>()
            val noisy = Mat(); val nz = Mat(readout.size(), CvType.CV_32F)
            val pre = Mat(); val st0 = Mat(); val bl = Mat()
            try {
                for (f in 0 until n) {
                    Core.setRNGSeed(rnd.nextInt())
                    Core.randn(nz, 0.0, 1.0)
                    Core.multiply(nz, sigma, nz)
                    Core.add(readout, nz, noisy)
                    // The ISP's scaler: area-filtered down (a Gaussian for the box of r readout px), bilinear either way.
                    if (r > 1.0) Imgproc.GaussianBlur(noisy, pre, Size(0.0, 0.0), r / sqrt(12.0)) else noisy.copyTo(pre)
                    Imgproc.warpAffine(pre, st0, map, Size(w.toDouble(), h.toDouble()),
                        Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
                    if (cfg.sharpen) {
                        Imgproc.GaussianBlur(st0, bl, Size(0.0, 0.0), 1.0)
                        Core.addWeighted(st0, 2.0, bl, -1.0, 0.0, st0)
                    }
                    val sn = Mat(st0.size(), CvType.CV_32F); Core.randn(sn, 0.0, 0.5)
                    Core.add(st0, sn, st0); sn.release()
                    val u8 = Mat(); st0.convertTo(u8, CvType.CV_8U)          // rounds and saturates
                    out += Frame(u8, finderQuad(z, rnd), trueQuad(z), z)
                }
            } finally {
                map.release(); noisy.release(); nz.release(); pre.release(); st0.release(); bl.release()
            }
            return out
        }

        /** The finder's quad: the true corners pushed [BIAS_PX] out on every side (the dilation) and jittered [JITTER_PX]. */
        private fun finderQuad(z: Double, rnd: Random): FloatArray {
            val q = trueQuad(z)
            val signs = arrayOf(intArrayOf(-1, -1), intArrayOf(1, -1), intArrayOf(1, 1), intArrayOf(-1, 1))
            for ((i, sg) in signs.withIndex()) {
                val ox = sg[0] * BIAS_PX; val oy = sg[1] * BIAS_PX
                q[2 * i] += (ox * ct - oy * st + rnd.nextGaussian() * JITTER_PX).toFloat()
                q[2 * i + 1] += (ox * st + oy * ct + rnd.nextGaussian() * JITTER_PX).toFloat()
            }
            return q
        }

        fun release() { readout.release(); sigma.release() }
    }

    const val BIAS_PX = 1.5
    const val JITTER_PX = 0.5
}
