package io.github.darylno.cardscanner.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameRingTest {
    private val w = 64
    private val h = 48

    private fun randomNv21(seed: Int): ByteArray = Random(seed).let { r -> ByteArray(w * h * 3 / 2) { r.nextInt(256).toByte() } }

    private fun copied(p: FakePlanes): ByteArray {
        val ring = FrameRing(4)
        ring.copyFrom(p, 90, 1L)
        return ring.snapshotLast(1)[0].data
    }

    @Test fun tightPlanarCopyIsExact() {
        val src = randomNv21(1)
        assertArrayEquals(src, copied(FakePlanes(src, w, h)))
    }

    @Test fun paddedRowsAndInterleavedChromaAreHonoured() {
        val src = randomNv21(2)
        // Qualcomm-like: Y rows padded to 80, chroma pixelStride 2, rows padded to 96, no tail padding.
        assertArrayEquals(src, copied(FakePlanes(src, w, h, yRowStride = 80, uvRowStride = 96, uvPixelStride = 2)))
    }

    @Test fun paddedPlanarChromaAndHeapBuffers() {
        val src = randomNv21(3)
        assertArrayEquals(src, copied(FakePlanes(src, w, h, yRowStride = 72, uvRowStride = 40, uvPixelStride = 1, direct = false)))
    }

    @Test fun ringKeepsNewestFramesOldestFirstAsCopies() {
        val ring = FrameRing(4)
        assertNull(ring.newestTimestampNs())
        assertTrue(ring.snapshotLast(3).isEmpty())
        val frames = (0 until 6).map { randomNv21(10 + it) }
        frames.forEachIndexed { i, f -> ring.copyFrom(FakePlanes(f, w, h, yRowStride = 80, uvRowStride = 80, uvPixelStride = 2), 90, i * 100L) }
        assertEquals(4, ring.size)
        val snap = ring.snapshotLast(3)
        assertEquals(listOf(300L, 400L, 500L), snap.map { it.timestampNs })
        for (k in 0..2) assertArrayEquals(frames[3 + k], snap[k].data)
        assertEquals(90, snap[0].rotation)
        assertEquals(w, snap[0].width); assertEquals(h, snap[0].height)
        // Copies: the ring moving on must not change a snapshot.
        ring.copyFrom(FakePlanes(randomNv21(99), w, h), 90, 600L)
        ring.copyFrom(FakePlanes(randomNv21(98), w, h), 90, 700L)
        assertArrayEquals(frames[3], snap[0].data)
    }

    @Test fun staleFramesAreNeverSnapshotted() {
        val ring = FrameRing(4)
        ring.copyFrom(FakePlanes(randomNv21(1), w, h), 0, 0L)              // a previous card
        ring.copyFrom(FakePlanes(randomNv21(2), w, h), 0, 2_000_000_000L)
        ring.copyFrom(FakePlanes(randomNv21(3), w, h), 0, 2_100_000_000L)
        assertEquals(2, ring.countFresh(400_000_000L))
        val snap = ring.snapshotLast(3, 400_000_000L)
        assertEquals(listOf(2_000_000_000L, 2_100_000_000L), snap.map { it.timestampNs })
    }

    @Test fun sizeChangeReallocatesAndDropsOldFrames() {
        val ring = FrameRing(4)
        ring.copyFrom(FakePlanes(randomNv21(1), w, h), 0, 1L)
        val big = Random(5).let { r -> ByteArray(128 * 96 * 3 / 2) { r.nextInt(256).toByte() } }
        ring.copyFrom(FakePlanes(big, 128, 96, yRowStride = 144, uvRowStride = 144, uvPixelStride = 2), 0, 2L)
        assertEquals(1, ring.size)
        assertArrayEquals(big, ring.snapshotLast(3)[0].data)
    }
}
