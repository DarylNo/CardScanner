package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * [Rotation]'s coordinate mapping must be exactly what OpenCV's Core.rotate
 * does to a sensor image ([Nv21Bgr.rotateCode]) — the grey sampler reads
 * through the mapping while the captured frames are physically rotated, and
 * the two must agree pixel for pixel on where the scan area is.
 */
class RotationTest {
    companion object {
        @BeforeClass @JvmStatic
        fun load() = OpenCvTest.load()

        val ROTATIONS = intArrayOf(0, 90, 180, 270)
    }

    private fun randomMat(rows: Int, cols: Int, seed: Long): Mat {
        val rnd = java.util.Random(seed)
        val m = Mat(rows, cols, CvType.CV_8UC1)
        m.put(0, 0, ByteArray(rows * cols) { rnd.nextInt(256).toByte() })
        return m
    }

    private fun bytes(m: Mat): ByteArray = ByteArray((m.total() * m.channels()).toInt()).also { m.get(0, 0, it) }

    @Test
    fun mappingMatchesCoreRotateOnRandomMats() {
        // Odd and even, landscape and portrait sensor sizes (off-by-one errors hide in odd sizes).
        for ((sw, sh) in listOf(7 to 5, 6 to 4, 5 to 9, 16 to 12, 1 to 3)) for (rot in ROTATIONS) {
            val sensor = randomMat(sh, sw, (sw * 1000 + sh * 10 + rot).toLong())
            val upright = Mat()
            val code = Nv21Bgr.rotateCode(rot)
            if (code == null) sensor.copyTo(upright) else Core.rotate(sensor, upright, code)
            val uw = Rotation.uprightWidth(sw, sh, rot); val uh = Rotation.uprightHeight(sw, sh, rot)
            assertEquals("upright width ${sw}x$sh rot $rot", upright.cols(), uw)
            assertEquals("upright height ${sw}x$sh rot $rot", upright.rows(), uh)
            val s = bytes(sensor); val u = bytes(upright)
            for (v in 0 until uh) for (uu in 0 until uw) {
                val x = Rotation.sensorX(uu, v, sw, sh, rot)
                val y = Rotation.sensorY(uu, v, sw, sh, rot)
                assertEquals("${sw}x$sh rot $rot upright ($uu,$v) ← sensor ($x,$y)", s[y * sw + x], u[v * uw + uu])
            }
            sensor.release(); upright.release()
        }
    }

    @Test
    fun specFormulas() {
        // The spec's table, literally: upright (u,v) → sensor (x,y) for a sw×sh sensor.
        val sw = 1600; val sh = 1200; val u = 17; val v = 301
        assertEquals(u to v, Rotation.sensorX(u, v, sw, sh, 0) to Rotation.sensorY(u, v, sw, sh, 0))
        assertEquals(v to sh - 1 - u, Rotation.sensorX(u, v, sw, sh, 90) to Rotation.sensorY(u, v, sw, sh, 90))
        assertEquals(sw - 1 - u to sh - 1 - v, Rotation.sensorX(u, v, sw, sh, 180) to Rotation.sensorY(u, v, sw, sh, 180))
        assertEquals(sw - 1 - v to u, Rotation.sensorX(u, v, sw, sh, 270) to Rotation.sensorY(u, v, sw, sh, 270))
        assertEquals(1200, Rotation.uprightWidth(sw, sh, 90)); assertEquals(1600, Rotation.uprightHeight(sw, sh, 90))
        assertEquals(1600, Rotation.uprightWidth(sw, sh, 180)); assertEquals(1200, Rotation.uprightHeight(sw, sh, 180))
    }

    @Test
    fun inverseRoundTripsEveryPixel() {
        for ((sw, sh) in listOf(7 to 5, 4 to 6)) for (rot in ROTATIONS) {
            val uw = Rotation.uprightWidth(sw, sh, rot); val uh = Rotation.uprightHeight(sw, sh, rot)
            val seen = HashSet<Int>()
            for (v in 0 until uh) for (u in 0 until uw) {
                val x = Rotation.sensorX(u, v, sw, sh, rot); val y = Rotation.sensorY(u, v, sw, sh, rot)
                assertTrue("($x,$y) outside ${sw}x$sh", x in 0 until sw && y in 0 until sh)
                assertEquals(u, Rotation.uprightU(x, y, sw, sh, rot))
                assertEquals(v, Rotation.uprightV(x, y, sw, sh, rot))
                seen += y * sw + x
            }
            assertEquals("rot $rot must be a bijection", sw * sh, seen.size)
        }
    }

    @Test
    fun normalizeFoldsCameraXValuesAndRejectsOthers() {
        assertEquals(0, Rotation.normalize(0))
        assertEquals(270, Rotation.normalize(-90))
        assertEquals(90, Rotation.normalize(450))
        assertEquals(180, Rotation.normalize(-180))
        for (bad in intArrayOf(45, 1, -30)) {
            try { Rotation.normalize(bad); fail("rotation $bad accepted") } catch (_: IllegalArgumentException) {}
        }
        assertNull(Nv21Bgr.rotateCode(0))
        assertEquals(Core.ROTATE_90_CLOCKWISE, Nv21Bgr.rotateCode(90))
        assertEquals(Core.ROTATE_180, Nv21Bgr.rotateCode(180))
        assertEquals(Core.ROTATE_90_COUNTERCLOCKWISE, Nv21Bgr.rotateCode(270))
        assertEquals(Core.ROTATE_90_COUNTERCLOCKWISE, Nv21Bgr.rotateCode(-90))
    }
}
