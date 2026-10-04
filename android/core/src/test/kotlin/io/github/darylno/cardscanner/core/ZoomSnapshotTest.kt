package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.core.Point

/**
 * The snapshot the desktop draws the scan Area on stays in BASE space while
 * zoomed: marks drawn into a zoomed frame at view fractions land at their base
 * fractions (½ + (v − ½)/z) within ±1 px at z 1, 1.6 and 2, the picture keeps
 * the frame's size, and the outside of the zoomed view is grey.
 */
class ZoomSnapshotTest {
    private val w = 1200
    private val h = 1600

    /** Centre of the bright mark in [m] (BGR) inside a window round ([cx], [cy]). */
    private fun markCentre(m: Mat, cx: Double, cy: Double, r: Int = 40): DoubleArray {
        var sx = 0.0; var sy = 0.0; var n = 0.0
        val x0 = (cx - r).toInt().coerceAtLeast(0); val x1 = (cx + r).toInt().coerceAtMost(m.cols() - 1)
        val y0 = (cy - r).toInt().coerceAtLeast(0); val y1 = (cy + r).toInt().coerceAtMost(m.rows() - 1)
        val px = ByteArray(3)
        for (y in y0..y1) for (x in x0..x1) {
            m.get(y, x, px)
            val g = (px[1].toInt() and 0xff).toDouble()
            if (g > 128) { sx += x * g; sy += y * g; n += g }
        }
        assertTrue("a mark near $cx,$cy", n > 0)
        return doubleArrayOf(sx / n, sy / n)
    }

    @Test fun marksLandAtTheirBaseFractions() {
        OpenCvTest.load()
        val marks = listOf(0.2 to 0.3, 0.5 to 0.5, 0.8 to 0.15, 0.35 to 0.9)
        for (z in listOf(1.0, 1.6, 2.0)) {
            // The zoomed frame: dark tray, a green dot at each view fraction (pixel-centre coordinates).
            val frame = Mat(h, w, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))
            // Integer pixel centres (OpenCV rounds a circle's centre): the mark covers pixel cx, i.e. fraction (cx + ½) / w.
            val px = marks.map { (u, v) -> Math.round(u * w).toInt() to Math.round(v * h).toInt() }
            for ((cx, cy) in px) Imgproc.circle(frame, Point(cx.toDouble(), cy.toDouble()), 9, Scalar(0.0, 255.0, 0.0), -1)
            val out = ZoomSnapshot.composeBase(frame, z)
            assertEquals(w, out.cols()); assertEquals(h, out.rows())
            for ((cx, cy) in px) {
                val u = (cx + 0.5) / w; val v = (cy + 0.5) / h
                val bx = ZoomFit.toBaseAxis(u, z) * w - 0.5; val by = ZoomFit.toBaseAxis(v, z) * h - 0.5
                val c = markCentre(out, bx, by)
                assertEquals("z $z mark ($u,$v) x", bx, c[0], 1.0)
                assertEquals("z $z mark ($u,$v) y", by, c[1], 1.0)
            }
            if (z > 1.0) {
                val g = ByteArray(3); out.get(5, 5, g)
                assertEquals("outside the zoomed view is grey", ZoomSnapshot.GREY.toInt(), g[0].toInt() and 0xff)
            }
            frame.release(); out.release()
        }
    }
}
