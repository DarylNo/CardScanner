package io.github.darylno.cardscanner.core

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * The scan PHOTO — port of `card_detect.scan_photo`, the reference server's
 * photo step in `/api/scan`: the picture the review pages show for a scan.
 * The sharpest of the uploaded frames; when the card's edges are found in it,
 * the card straightened and filling [FILL] of a [PHOTO_W]×[PHOTO_H] photo with
 * a deliberate buffer of tray around it (owner, 2026-10-01: "tight on the card
 * plus some buffer — make it 95%"); otherwise that frame exactly as uploaded
 * (owner: "keep photo" — the real picture, never a distorted crop of it).
 *
 * Before 1.1.3 the phone filed the upload itself: the flattened card with its
 * 8% detection margin (card ≈ 64–75% of the picture), or a raw crop (≈ 30%).
 * Photos already filed are never re-cropped.
 *
 * Parity: ScanPhotoParityTest against `detect/expected.json` (`photo`,
 * `flat_photo`, the constants) from scripts/export_detect_fixtures.py.
 */
object ScanPhoto {
    /** `PHOTO_FILL`: the card spans this much of the photo's width and height. */
    const val FILL = 0.95

    /** `PHOTO_MARGIN_X/Y` = round(CARD_W/H × (1/FILL − 1) / 2): the buffer each side. */
    const val MARGIN_X = 17
    const val MARGIN_Y = 23

    /** `PHOTO_W/H`: 664 × 926 — the card's 630×880 warp plus the margins. */
    const val PHOTO_W = CardQuad.CARD_W + 2 * MARGIN_X
    const val PHOTO_H = CardQuad.CARD_H + 2 * MARGIN_Y

    /** The reference writes the photo at JPEG quality 90. */
    const val JPEG_QUALITY = 90

    /**
     * `scan_photo`'s answer: [photo] when the edges were found (the caller
     * releases it), else null — keep frame [frame] (the sharpest) as uploaded.
     */
    class Result(val photo: Mat?, val frame: Int) {
        val detected: Boolean get() = photo != null
    }

    /** `pick_sharpest`: the index of the frame with the highest Laplacian variance (the first on a tie, as Python's max). */
    fun sharpestIndex(frames: List<Mat>): Int {
        require(frames.isNotEmpty()) { "no frames" }
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for ((i, f) in frames.withIndex()) {
            val s = Sharpness.frameSharpness(f)
            if (s > bestScore) { best = i; bestScore = s }
        }
        return best
    }

    /**
     * `warp_photo`: the card at [corners] straightened into the photo, its
     * 630×880 warp inset by the margins (INTER_LINEAR; black where the buffer
     * falls outside the frame — a card against the frame edge).
     */
    fun warpPhoto(frame: Mat, corners: FloatArray): Mat {
        val src = CardQuad.quadMat(corners)
        val mx = MARGIN_X.toDouble(); val my = MARGIN_Y.toDouble()
        val w = CardQuad.CARD_W.toDouble(); val h = CardQuad.CARD_H.toDouble()
        val dst = MatOfPoint2f(Point(mx, my), Point(mx + w, my), Point(mx + w, my + h), Point(mx, my + h))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        try {
            Imgproc.warpPerspective(frame, out, m, Size(PHOTO_W.toDouble(), PHOTO_H.toDouble()))
        } finally {
            src.release(); dst.release(); m.release()
        }
        return out
    }

    /** `scan_photo(frames)`: the sharpest frame's card as [warpPhoto] makes it, or no photo and that frame's index. */
    fun of(frames: List<Mat>): Result {
        val i = sharpestIndex(frames)
        val corners = CardQuad.find(frames[i]) ?: return Result(null, i)
        return Result(warpPhoto(frames[i], corners), i)
    }

    /** The photo as the reference stores it: JPEG at quality [JPEG_QUALITY]. */
    fun jpeg(photo: Mat): ByteArray {
        val buf = MatOfByte()
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
        try {
            check(Imgcodecs.imencode(".jpg", photo, buf, params)) { "JPEG encode failed" }
            return buf.toArray()
        } finally {
            buf.release(); params.release()
        }
    }
}
