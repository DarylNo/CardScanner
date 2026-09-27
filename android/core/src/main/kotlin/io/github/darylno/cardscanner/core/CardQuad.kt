package io.github.darylno.cardscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Faithful port of the SERVER's `mtg_card_scanner/card_detect.py`
 * (`find_card_quad` and the helpers it uses), so the phone finds the same
 * card the server would and can upload it flattened ([Flatten]).
 *
 * Same constants, same edge maps, same order of operations — the comments
 * that explain WHY each constant exists live in card_detect.py and are only
 * summarised here. CardQuadParityTest holds this port to the server's own
 * answers on the fixtures `scripts/export_detect_fixtures.py` exports
 * (`core/src/test/resources/detect/`), and CI re-exports them, so the two can
 * never silently drift apart. Change card_detect.py first, then this.
 *
 * The server stays the judge: it re-detects on whatever the phone uploads.
 * `is_blank_surface` is deliberately NOT ported — rejecting blank scans is
 * the server's job, not the phone's.
 *
 * Quads are `FloatArray(8)`: TL, TR, BR, BL as x0,y0, x1,y1, x2,y2, x3,y3 in
 * the pixels of the frame they were found in, arranged so the card's LONG
 * axis runs top→bottom (sideways cards come out upright; 180° is ambiguous).
 */
object CardQuad {
    /** card_detect.CARD_W/CARD_H: the server's standard warp (63:88). */
    const val CARD_W = 630
    const val CARD_H = 880

    /** A real card's long/short ≈ 1.40; outside this window a quad is not a card. */
    const val MIN_CARD_RATIO = 1.15
    const val MAX_CARD_RATIO = 1.75

    /** The contour must fill its own min-area rectangle. */
    const val MIN_RECTANGULARITY = 0.75

    /** Printed detail inside the quad (64×88 thumbnail): rejects black holders / bare tray. */
    const val MIN_INTERIOR_STD = 12.0
    const val MIN_INTERIOR_EDGE_FRAC = 0.015

    /** A card in the full frame can be ~2.5% of it; the floor only skips contour noise. */
    const val MIN_AREA_FRAC = 0.015

    /** A contour covering ≥92% of the frame is the frame itself, not a card. */
    const val MAX_AREA_FRAC = 0.92

    /** A card-shaped contour: its oriented corners and contour area. */
    class Candidate(val corners: FloatArray, val area: Double)

    /**
     * `find_card_quad`: the largest card-shaped, card-TEXTURED quadrilateral
     * in [frame] (8UC3 BGR; a 1-channel grey frame is accepted as-is), or
     * null when nothing card-shaped is found.
     *
     * Three edge maps (Canny 25/85, Canny 50/150, Otsu-inverse + close) cover
     * black-bordered cards on a white tray, white-bordered ones on a dark
     * tray, and borders that barely differ from the tray; a rotated
     * minAreaRect replaces fragile 4-vertex polygon fitting.
     */
    fun find(frame: Mat, minAreaFrac: Double = MIN_AREA_FRAC): FloatArray? {
        val h = frame.rows(); val w = frame.cols()
        val minArea = (h * w).toDouble() * minAreaFrac
        val maxArea = (h * w).toDouble() * MAX_AREA_FRAC

        val tmp = ArrayList<Mat>()
        fun <M : Mat> keep(m: M): M { tmp += m; return m }
        try {
            val gray = if (frame.channels() == 1) frame else keep(Mat()).also {
                Imgproc.cvtColor(frame, it, Imgproc.COLOR_BGR2GRAY)
            }
            val blurred = keep(Mat())
            Imgproc.GaussianBlur(gray, blurred, Size(7.0, 7.0), 0.0)
            val kernel = keep(Mat.ones(5, 5, CvType.CV_8U))

            val canny1 = keep(Mat()); Imgproc.Canny(blurred, canny1, 25.0, 85.0)
            val canny2 = keep(Mat()); Imgproc.Canny(blurred, canny2, 50.0, 150.0)
            // Otsu split — catches cards whose border barely differs from the tray.
            val otsu = keep(Mat())
            Imgproc.threshold(blurred, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
            val otsuClosed = keep(Mat()); Imgproc.morphologyEx(otsu, otsuClosed, Imgproc.MORPH_CLOSE, kernel)
            val edgeMaps = listOf(canny1, canny2, otsuClosed)

            var bestCorners: FloatArray? = null
            var bestArea = 0.0
            for (edges in edgeMaps) {
                val closed = keep(Mat())
                Imgproc.dilate(edges, closed, kernel, Point(-1.0, -1.0), 2)
                val contours = ArrayList<MatOfPoint>()
                val hierarchy = keep(Mat())
                Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                tmp.addAll(contours)
                for (cnt in contours) {
                    val found = candidateFromContour(cnt, minArea, maxArea)
                    // Texture gate last (it warps a patch; geometry is cheaper): it
                    // rejects card-shaped uniform surfaces like a black holder.
                    if (found != null && found.area > bestArea && hasCardTexture(gray, found.corners)) {
                        bestCorners = found.corners
                        bestArea = found.area
                    }
                }
            }
            return bestCorners
        } finally {
            for (m in tmp) m.release()
        }
    }

    /**
     * `_order_corners`: positional [TL, TR, BR, BL] of 4 points — TL smallest
     * x+y, TR smallest y−x, BR largest x+y, BL largest y−x, first index on a
     * tie (numpy argmin/argmax). float32 arithmetic, as numpy does it.
     */
    fun orderCorners(pts: FloatArray): FloatArray {
        require(pts.size == 8) { "need 4 points (8 floats), got ${pts.size}" }
        val s = FloatArray(4) { pts[2 * it] + pts[2 * it + 1] }
        val d = FloatArray(4) { pts[2 * it + 1] - pts[2 * it] }
        val order = intArrayOf(argmin(s), argmin(d), argmax(s), argmax(d))
        return FloatArray(8) { pts[2 * order[it / 2] + it % 2] }
    }

    /**
     * `_orient_corners`: [orderCorners], then — only when the rectangle is
     * really landscape (top edge longer than the right edge) — roll by one so
     * the card's long axis runs vertically. Fixes both boxPoints' arbitrary
     * start/winding and a sideways card squashed into the portrait output.
     */
    fun orientCorners(box: FloatArray): FloatArray {
        val pts = orderCorners(box)
        val wEdge = norm(pts[2] - pts[0], pts[3] - pts[1])
        val hEdge = norm(pts[4] - pts[2], pts[5] - pts[3])
        if (wEdge > hEdge) return FloatArray(8) { pts[(it + 2) % 8] }   // np.roll(pts, -1, axis=0)
        return pts
    }

    /** `_candidate_from_contour`: (corners, area) if [cnt] is card-shaped, else null. */
    fun candidateFromContour(cnt: MatOfPoint, minArea: Double, maxArea: Double): Candidate? {
        val area = Imgproc.contourArea(cnt)
        if (area < minArea || area > maxArea) return null
        // Python's cv2.minAreaRect takes the int32 contour; the Java binding only
        // takes float points. Integer pixel coordinates are exact in float32 and
        // OpenCV converts the hull to float itself, so the rectangle is the same.
        val pts2f = MatOfPoint2f()
        val boxMat = Mat()
        try {
            cnt.convertTo(pts2f, CvType.CV_32F)
            val rect = Imgproc.minAreaRect(pts2f)
            val rw = rect.size.width; val rh = rect.size.height
            if (rw <= 1 || rh <= 1) return null
            val ratio = max(rw, rh) / min(rw, rh)
            if (!(ratio >= MIN_CARD_RATIO && ratio <= MAX_CARD_RATIO)) return null
            if (area / (rw * rh) < MIN_RECTANGULARITY) return null
            Imgproc.boxPoints(rect, boxMat)
            val box = FloatArray(8)
            boxMat.get(0, 0, box)
            return Candidate(orientCorners(box), area)
        } finally {
            pts2f.release(); boxMat.release()
        }
    }

    /**
     * `_texture_metrics`: (population std, Canny edge fraction) of a grey card
     * image's interior at 64×88 scale — rows 6..81, cols 5..58, dropping the
     * border/edge transition.
     */
    fun textureMetrics(grayCard: Mat): Pair<Double, Double> {
        val small = Mat()
        val inner = Mat()
        val edges = Mat()
        try {
            Imgproc.resize(grayCard, small, Size(64.0, 88.0), 0.0, 0.0, Imgproc.INTER_AREA)
            // A CLONE, not a view: numpy's `small[6:82, 5:59]` reaches cv2 as a
            // standalone array, so Canny's border handling sees only the inner
            // pixels. An OpenCV submat would let it read the parent around them.
            val view = small.submat(6, 82, 5, 59)
            view.copyTo(inner)
            view.release()
            Imgproc.Canny(inner, edges, 30.0, 90.0)
            val px = ByteArray(inner.total().toInt())
            inner.get(0, 0, px)
            return Pair(populationStd(px), Core.countNonZero(edges).toDouble() / edges.total().toDouble())
        } finally {
            small.release(); inner.release(); edges.release()
        }
    }

    /** `_has_card_texture`: the quad's warped interior contains printed-card detail. */
    fun hasCardTexture(gray: Mat, corners: FloatArray): Boolean {
        val patch = warpCard(gray, corners, 64, 88)
        try {
            val (std, edgeFrac) = textureMetrics(patch)
            return std >= MIN_INTERIOR_STD && edgeFrac >= MIN_INTERIOR_EDGE_FRAC
        } finally {
            patch.release()
        }
    }

    /** `warp_card`: perspective-warp [corners] of [frame] to an upright [outW]×[outH] card (INTER_LINEAR, black border). */
    fun warpCard(frame: Mat, corners: FloatArray, outW: Int = CARD_W, outH: Int = CARD_H): Mat {
        val src = quadMat(corners)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW.toDouble(), 0.0),
            Point(outW.toDouble(), outH.toDouble()), Point(0.0, outH.toDouble()),
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        try {
            Imgproc.warpPerspective(frame, out, m, Size(outW.toDouble(), outH.toDouble()))
        } finally {
            src.release(); dst.release(); m.release()
        }
        return out
    }

    /** [quad] shifted by ([dx], [dy]) — e.g. from scan-area crop into full-frame coordinates. */
    fun translate(quad: FloatArray, dx: Float, dy: Float): FloatArray =
        FloatArray(8) { quad[it] + if (it % 2 == 0) dx else dy }

    /** A 4-point float32 Mat of [quad] for OpenCV (TL, TR, BR, BL). */
    internal fun quadMat(quad: FloatArray): MatOfPoint2f {
        require(quad.size == 8) { "need 4 points (8 floats), got ${quad.size}" }
        val m = MatOfPoint2f()
        m.alloc(4)
        m.put(0, 0, quad)
        return m
    }

    // numpy semantics: first index wins on ties.
    private fun argmin(a: FloatArray): Int { var k = 0; for (i in 1 until a.size) if (a[i] < a[k]) k = i; return k }
    private fun argmax(a: FloatArray): Int { var k = 0; for (i in 1 until a.size) if (a[i] > a[k]) k = i; return k }

    /** np.linalg.norm of a float32 2-vector: sqrt(dot) in float32. */
    private fun norm(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

    /** numpy `ndarray.std()` (ddof=0): two-pass, in double. */
    private fun populationStd(px: ByteArray): Double {
        var sum = 0.0
        for (b in px) sum += (b.toInt() and 0xFF)
        val mean = sum / px.size
        var sq = 0.0
        for (b in px) { val d = (b.toInt() and 0xFF) - mean; sq += d * d }
        return sqrt(sq / px.size)
    }
}
