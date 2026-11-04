package io.github.darylno.cardscanner.camera

import io.github.darylno.cardscanner.core.Nv21Bgr
import io.github.darylno.cardscanner.core.Nv21Frame
import io.github.darylno.cardscanner.core.ZoomSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import kotlin.math.abs

/**
 * `/api/device/snapshot.jpg`'s two pictures from one analysis frame (1.1.17): the BASE
 * one (`snapshotJpeg`, unchanged — the zoomed frame on grey, long side ≤ 1280) and the
 * CAMERA view (`?view=camera` — the frame as the camera delivers it, no grey ring, long
 * side ≤ 2048, with the frame's own zoom and size).
 */
class SnapshotPictureTest {
    companion object {
        @BeforeClass @JvmStatic fun setUp() { nu.pattern.OpenCV.loadLocally() }
    }

    /** A sensor-landscape NV21 frame (rotation 90 → upright [h]×[w]) of flat luma [y], neutral chroma. */
    private fun frame(w: Int, h: Int, y: Int): Nv21Frame {
        val data = ByteArray(w * h * 3 / 2) { 128.toByte() }
        java.util.Arrays.fill(data, 0, w * h, y.toByte())
        return Nv21Frame(data, w, h, 90, 0L)
    }

    private fun decode(jpeg: ByteArray): Mat {
        val buf = MatOfByte(*jpeg)
        try { return Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR) } finally { buf.release() }
    }

    /** The mean of the B, G, R values of pixel ([x], [y]). */
    private fun grey(m: Mat, x: Int, y: Int): Double = m.get(y, x).average()

    /** What the flat frame [f] reads once converted (NV21 luma is video range: Y 200 → ≈ 214). */
    private fun level(f: Nv21Frame): Double {
        val m = Nv21Bgr.toUprightBgr(f)
        try { return grey(m, 0, 0) } finally { m.release() }
    }

    @Test fun theCameraViewIsTheZoomedFrameAsItIs() {
        val f = frame(160, 120, 200)
        val v = level(f)
        val cam = SnapshotPicture.encode(f, 1.9, maxSide = 2048, base = false)
        assertEquals(1.9, cam.ratio, 0.0)
        assertEquals(120, cam.w); assertEquals(160, cam.h)               // upright, the stream's own pixels
        val m = decode(cam.jpeg)
        try {
            assertEquals(cam.w, m.cols()); assertEquals(cam.h, m.rows())
            for ((x, y) in listOf(1 to 1, cam.w - 2 to 1, 1 to cam.h - 2, cam.w - 2 to cam.h - 2, cam.w / 2 to cam.h / 2)) {
                assertTrue("($x,$y) = ${grey(m, x, y)} vs $v", abs(grey(m, x, y) - v) <= 4)
            }
        } finally { m.release() }
    }

    @Test fun theBasePictureStaysTheZoomedViewOnGrey() {
        val f = frame(160, 120, 200)
        val v = level(f)
        val base = SnapshotPicture.encode(f, 1.9, maxSide = 1280, base = true)
        assertEquals(1.9, base.ratio, 0.0)
        assertEquals(120, base.w); assertEquals(160, base.h)
        val m = decode(base.jpeg)
        try {
            // The corners are the grey ring (what the zoomed camera doesn't see), the middle the frame.
            for ((x, y) in listOf(1 to 1, base.w - 2 to 1, 1 to base.h - 2, base.w - 2 to base.h - 2)) {
                assertTrue("($x,$y) = ${grey(m, x, y)}", abs(grey(m, x, y) - ZoomSnapshot.GREY) <= 4)
            }
            assertTrue("${grey(m, base.w / 2, base.h / 2)} vs $v", abs(grey(m, base.w / 2, base.h / 2) - v) <= 4)
        } finally { m.release() }
    }

    @Test fun at1xBothViewsAreTheSamePicture() {
        val f = frame(160, 120, 180)
        assertArrayEquals(SnapshotPicture.encode(f, 1.0, 1280, base = true).jpeg,
            SnapshotPicture.encode(f, 1.0, 1280, base = false).jpeg)
    }

    @Test fun theLongSideIsCappedAndTheSizeIsTheEncodedOne() {
        val f = frame(400, 300, 150)                                     // upright 300×400
        val cam = SnapshotPicture.encode(f, 1.5, maxSide = 200, base = false)
        assertEquals(150, cam.w); assertEquals(200, cam.h)
        val m = decode(cam.jpeg)
        try { assertEquals(150, m.cols()); assertEquals(200, m.rows()) } finally { m.release() }
        val big = SnapshotPicture.encode(f, 1.5, maxSide = 2048, base = false)
        assertEquals(300, big.w); assertEquals(400, big.h)               // never scaled UP
    }
}
