package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** `cv2.Laplacian(gray, CV_64F).var()` — numpy's var is the POPULATION variance. */
class SharpnessTest {
    companion object {
        @BeforeClass @JvmStatic
        fun load() = OpenCvTest.load()
    }

    @Test
    fun isThePopulationVarianceOfTheLaplacian() {
        // A tiny image makes ddof visible: with n = 20, ddof=1 would be 5% larger.
        val g = Mat(4, 5, CvType.CV_8UC1)
        g.put(0, 0, byteArrayOf(10, 200.toByte(), 30, 90, 0, 255.toByte(), 1, 77, 150.toByte(), 3,
            40, 41, 250.toByte(), 9, 120, 60, 180.toByte(), 2, 99, 222.toByte()))
        val lap = Mat()
        Imgproc.Laplacian(g, lap, CvType.CV_64F)
        val v = DoubleArray(20); lap.get(0, 0, v)
        val mean = v.average()
        val pop = v.sumOf { (it - mean) * (it - mean) } / v.size
        val got = Sharpness.laplacianVariance(g)
        assertEquals(pop, got, pop * 1e-9)
        assertTrue("must not be the sample variance", kotlin.math.abs(got - pop * 20 / 19) > pop * 0.01)
        g.release(); lap.release()
    }

    @Test
    fun flatIsZeroBlurIsLower() {
        val flat = Mat(100, 100, CvType.CV_8UC1, Scalar(128.0))
        assertEquals(0.0, Sharpness.laplacianVariance(flat), 0.0)
        val img = OpenCvTest.readBgr("white_tray_black_border.png")
        val blurred = Mat()
        Imgproc.GaussianBlur(img, blurred, Size(9.0, 9.0), 0.0)
        assertTrue(Sharpness.frameSharpness(blurred) < Sharpness.frameSharpness(img) / 2)
        // frameSharpness = BGR→grey then laplacianVariance.
        val gray = Mat(); Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        assertEquals(Sharpness.laplacianVariance(gray), Sharpness.frameSharpness(img), 0.0)
        for (m in listOf(flat, img, blurred, gray)) m.release()
    }

    @Test
    fun aSubmatScoresLikeAStandaloneCopy() {
        // The server scores a decoded standalone image; an OpenCV ROI view would
        // let the Laplacian read the parent's pixels past its edge instead.
        val img = OpenCvTest.readBgr("white_tray_black_border.png")
        val gray = Mat(); Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        val view = gray.submat(37, 401, 53, 377)
        val copy = view.clone()
        assertTrue(view.isSubmatrix)
        assertEquals(Sharpness.laplacianVariance(copy), Sharpness.laplacianVariance(view), 0.0)
        for (m in listOf(img, gray, view, copy)) m.release()
    }

    @Test
    fun rejectsColourForTheGreyEntryPoint() {
        val bgr = Mat(10, 10, CvType.CV_8UC3, Scalar(1.0, 2.0, 3.0))
        try { Sharpness.laplacianVariance(bgr); fail("3-channel accepted") } catch (_: IllegalArgumentException) {}
        bgr.release()
    }
}
