package io.github.darylno.cardscanner.capture

import io.github.darylno.cardscanner.core.CardQuad
import io.github.darylno.cardscanner.core.Flatten
import io.github.darylno.cardscanner.core.Nv21Bgr
import io.github.darylno.cardscanner.core.Nv21Frame
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.RoiPx
import io.github.darylno.cardscanner.core.Sharpness
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns the burst the detector approved (1..3 ring frames) into upload JPEGs:
 *
 * 1. sharpness of each frame = Laplacian variance of the upright Y crop of the
 *    area (the stillness test can't see defocus/motion blur; this can);
 * 2. upright BGR of the sharpest FULL frame, crop the area;
 * 3. CardQuad.find on the crop (the server's own find_card_quad, ported), the
 *    quad offset back into full-frame pixels;
 * 4. Flatten.chooseMargin against the FULL frame — a margin may reach outside
 *    the scan area as long as it stays inside what the camera saw;
 * 5. flatten + JPEG q85 = primary. The raw upright crops (≤ [MAX_RAW_DIM])
 *    are the fallbacks. No quad / no margin → the sharpest raw crop is the
 *    primary. The server stays the judge: it re-detects either way.
 *
 * Any OpenCV failure in the quad/flatten step degrades to the raw crop — a
 * capture is never lost to the optional step.
 *
 * [process] is synchronous and pure (unit-tested on the JVM); [submit] runs it
 * on this pipeline's own single thread so neither the analysis thread nor the
 * UI ever waits on OpenCV or JPEG encoding.
 */
class CapturePipeline(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scan-capture").apply { priority = Thread.NORM_PRIORITY }
    },
) {
    @Volatile var lastSummary: String? = null
        private set

    /** Run [process] on the capture thread; [done] is called on that thread. */
    fun submit(frames: List<Nv21Frame>, cropRoi: RoiFrac?, done: (Result<CaptureResult>) -> Unit): Boolean =
        try {
            executor.execute {
                val r = runCatching { process(frames, cropRoi) }
                r.onSuccess { lastSummary = it.summary() }.onFailure { lastSummary = "failed: $it" }
                done(r)
            }
            true
        } catch (e: RejectedExecutionException) {
            false
        }

    fun shutdown() { executor.shutdown() }

    /**
     * [cropRoi] = the scan area as fractions of the UPRIGHT frame (Mount), or
     * null for the whole frame (Handheld).
     */
    fun process(frames: List<Nv21Frame>, cropRoi: RoiFrac?): CaptureResult {
        require(frames.isNotEmpty()) { "no frames to process" }
        val t0 = System.nanoTime()
        val timings = LinkedHashMap<String, Long>()

        // 1. sharpest by Laplacian variance of the upright Y crop.
        val sharp = DoubleArray(frames.size)
        for ((i, f) in frames.withIndex()) {
            val g = Nv21Bgr.uprightGray(f, cropRoi)
            try { sharp[i] = Sharpness.laplacianVariance(g) } finally { g.release() }
        }
        var best = 0
        for (i in 1 until sharp.size) if (sharp[i] > sharp[best]) best = i
        var t = System.nanoTime(); timings["sharpness"] = ms(t0, t)

        // 2. upright BGR of every frame (the sharpest for the quad; all for the raw crops).
        val bgrs = arrayOfNulls<Mat>(frames.size)
        try {
            val tc = System.nanoTime()
            for (i in frames.indices) bgrs[i] = Nv21Bgr.toUprightBgr(frames[i])
            val full = bgrs[best]!!
            val fw = full.cols(); val fh = full.rows()
            val crop = cropRoi?.toPixels(fw, fh) ?: RoiPx(0, 0, fw, fh)
            t = System.nanoTime(); timings["convert"] = ms(tc, t)

            // 3-4. quad on the crop → full-frame coords → margin on the FULL frame.
            val tq = System.nanoTime()
            var quad: FloatArray? = null
            var margin: Double? = null
            try {
                val sub = full.submat(rect(crop))
                try {
                    val q = CardQuad.find(sub)
                    if (q != null) {
                        quad = CardQuad.translate(q, crop.x.toFloat(), crop.y.toFloat())
                        margin = Flatten.chooseMargin(quad, fw, fh)
                    }
                } finally { sub.release() }
            } catch (e: Exception) {
                quad = null; margin = null
            }
            t = System.nanoTime(); timings["quad"] = ms(tq, t)

            // 5. flatten (when a margin fits).
            val tf = System.nanoTime()
            var flat: Mat? = null
            if (quad != null && margin != null) {
                flat = try { Flatten.flatten(full, quad, margin) } catch (e: Exception) { null }
            }
            t = System.nanoTime(); timings["flatten"] = ms(tf, t)

            val te = System.nanoTime()
            val raw = ArrayList<ByteArray>(frames.size)
            for (i in frames.indices) raw += encodeRawCrop(bgrs[i]!!, crop)
            val primary: ByteArray
            val fallbacks: List<ByteArray>
            val flattened: Boolean
            if (flat != null) {
                try { primary = encodeJpeg(flat) } finally { flat.release() }
                fallbacks = raw
                flattened = true
            } else {
                primary = raw[best]
                // ALL 3 raw frames, sharpest included: the fallback is the
                // server's multi-frame retry ("re-send all 3"), which sorts by
                // sharpness itself — dropping the best frame weakened it.
                fallbacks = raw
                flattened = false
            }
            t = System.nanoTime(); timings["encode"] = ms(te, t)
            timings["total"] = ms(t0, t)
            return CaptureResult(
                primary = primary, fallbacks = fallbacks, flattened = flattened,
                margin = if (flattened) margin else null, timingsMs = timings, quad = quad,
                crop = crop, frameWidth = fw, frameHeight = fh, sharpness = sharp, sharpestIndex = best,
            )
        } finally {
            for (m in bgrs) m?.release()
        }
    }

    companion object {
        const val JPEG_QUALITY = 85
        /** Raw crops are capped here (the "High" 2048×1536 stream in Handheld). */
        const val MAX_RAW_DIM = 1600

        private fun ms(a: Long, b: Long) = (b - a) / 1_000_000L

        private fun rect(r: RoiPx) = Rect(r.x, r.y, r.w, r.h)

        /** The upright crop of [bgr], downscaled (INTER_AREA) so its long side is ≤ [MAX_RAW_DIM]. */
        fun encodeRawCrop(bgr: Mat, crop: RoiPx): ByteArray {
            val sub = bgr.submat(rect(crop))
            try {
                val longSide = max(crop.w, crop.h)
                if (longSide <= MAX_RAW_DIM) return encodeJpeg(sub)
                val s = MAX_RAW_DIM.toDouble() / longSide
                val small = Mat()
                try {
                    val nw = (crop.w * s).roundToInt().coerceIn(1, MAX_RAW_DIM)
                    val nh = (crop.h * s).roundToInt().coerceIn(1, MAX_RAW_DIM)
                    Imgproc.resize(sub, small, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                    return encodeJpeg(small)
                } finally { small.release() }
            } finally { sub.release() }
        }

        fun encodeJpeg(bgr: Mat): ByteArray {
            val buf = MatOfByte()
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
            try {
                check(Imgcodecs.imencode(".jpg", bgr, buf, params)) { "JPEG encode failed" }
                return buf.toArray()
            } finally {
                buf.release(); params.release()
            }
        }
    }
}
