package io.github.darylno.cardscanner.camera

import java.nio.ByteBuffer

/**
 * A YUV_420_888 image built from NV21 bytes with ARBITRARY strides, the way
 * Camera2 lays planes out: row padding, pixelStride 1 (planar) or 2 (chroma
 * samples with a junk byte between them), and buffers that end right after
 * the last sample of the last row (no trailing row padding). Padding bytes are
 * filled with junk so a stride bug shows up as wrong pixels.
 */
class FakePlanes(
    val nv21: ByteArray,
    override val width: Int,
    override val height: Int,
    override val yRowStride: Int = width,
    uvRowStride: Int = width / 2,
    uvPixelStride: Int = 1,
    direct: Boolean = true,
) : YuvPlanes {
    override val yBuffer: ByteBuffer
    override val uBuffer: ByteBuffer
    override val vBuffer: ByteBuffer
    override val uRowStride = uvRowStride
    override val vRowStride = uvRowStride
    override val uPixelStride = uvPixelStride
    override val vPixelStride = uvPixelStride

    init {
        val yLen = (height - 1) * yRowStride + width
        val y = ByteArray(yLen) { 0x5A }
        for (r in 0 until height) System.arraycopy(nv21, r * width, y, r * yRowStride, width)
        val cw = width / 2; val ch = height / 2
        val cLen = (ch - 1) * uvRowStride + (cw - 1) * uvPixelStride + 1
        val u = ByteArray(cLen) { 0x33 }
        val v = ByteArray(cLen) { 0x77 }
        for (r in 0 until ch) for (k in 0 until cw) {
            val src = width * height + r * width + 2 * k
            v[r * uvRowStride + k * uvPixelStride] = nv21[src]
            u[r * uvRowStride + k * uvPixelStride] = nv21[src + 1]
        }
        fun buf(a: ByteArray): ByteBuffer =
            if (direct) ByteBuffer.allocateDirect(a.size).put(a).also { it.rewind() } else ByteBuffer.wrap(a)
        yBuffer = buf(y); uBuffer = buf(u); vBuffer = buf(v)
    }

    companion object {
        /** NV21 with a Y pattern from [yAt] and chroma from [vAt]/[uAt]. */
        fun nv21(w: Int, h: Int, yAt: (Int, Int) -> Int, vAt: (Int, Int) -> Int = { _, _ -> 128 },
                 uAt: (Int, Int) -> Int = { _, _ -> 128 }): ByteArray {
            val a = ByteArray(w * h * 3 / 2)
            for (y in 0 until h) for (x in 0 until w) a[y * w + x] = yAt(x, y).toByte()
            for (r in 0 until h / 2) for (k in 0 until w / 2) {
                a[w * h + r * w + 2 * k] = vAt(k, r).toByte()
                a[w * h + r * w + 2 * k + 1] = uAt(k, r).toByte()
            }
            return a
        }
    }
}
