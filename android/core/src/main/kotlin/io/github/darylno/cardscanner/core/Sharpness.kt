package io.github.darylno.cardscanner.core

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

/**
 * Blur score — port of card_detect.frame_sharpness: variance of the
 * Laplacian, higher = sharper. The phone uploads only the sharpest of the
 * three ring frames (the server's pick_sharpest does the same over a burst),
 * so the score has to rank frames the way the server would.
 */
object Sharpness {
    /**
     * `cv2.Laplacian(gray, cv2.CV_64F).var()` — numpy's var is the POPULATION
     * variance (ddof=0), which is what OpenCV's meanStdDev returns squared.
     * [gray] must be single-channel (e.g. [Nv21Bgr.uprightGray], the Y plane:
     * no colour conversion needed to rank frames).
     */
    fun laplacianVariance(gray: Mat): Double {
        require(gray.channels() == 1) { "laplacianVariance needs a 1-channel image, got ${gray.channels()}" }
        val lap = Mat()
        val mean = MatOfDouble()
        val std = MatOfDouble()
        try {
            Imgproc.Laplacian(gray, lap, CvType.CV_64F)
            org.opencv.core.Core.meanStdDev(lap, mean, std)
            val s = std.toArray()[0]
            return s * s
        } finally {
            lap.release(); mean.release(); std.release()
        }
    }

    /** card_detect.frame_sharpness exactly: BGR → grey (COLOR_BGR2GRAY) → [laplacianVariance]. */
    fun frameSharpness(bgr: Mat): Double {
        if (bgr.channels() == 1) return laplacianVariance(bgr)
        val gray = Mat()
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            return laplacianVariance(gray)
        } finally {
            gray.release()
        }
    }
}
