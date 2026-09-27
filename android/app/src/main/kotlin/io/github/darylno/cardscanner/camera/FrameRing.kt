package io.github.darylno.cardscanner.camera

import androidx.camera.core.ImageProxy
import io.github.darylno.cardscanner.core.Nv21Frame
import java.nio.ByteBuffer

/**
 * The three planes of a YUV_420_888 image, as Camera2/CameraX hands them
 * over: Y has pixelStride 1 and rows [yRowStride] apart; U and V are half
 * resolution with their own row/pixel strides (2 on Qualcomm, where U and V
 * overlap as NV12/NV21 in memory; 1 for planar layouts). Buffers may end right
 * after the last pixel of the last row (no padding there), so reads must never
 * assume `rows · rowStride` bytes exist.
 *
 * An interface (not ImageProxy itself) so the stride handling is unit-tested
 * on the JVM with deliberately padded fakes.
 */
interface YuvPlanes {
    val width: Int
    val height: Int
    val yBuffer: ByteBuffer
    val yRowStride: Int
    val uBuffer: ByteBuffer
    val uRowStride: Int
    val uPixelStride: Int
    val vBuffer: ByteBuffer
    val vRowStride: Int
    val vPixelStride: Int
}

/** Reusable (allocation-free per frame) [YuvPlanes] view of a live [ImageProxy]. */
class ImageProxyPlanes : YuvPlanes {
    private var planes: Array<ImageProxy.PlaneProxy>? = null
    override var width = 0; private set
    override var height = 0; private set

    fun bind(image: ImageProxy): ImageProxyPlanes {
        planes = image.planes
        width = image.width
        height = image.height
        return this
    }

    /** Drop the references — plane buffers are invalid once the image is closed. */
    fun unbind() { planes = null }

    private fun p(i: Int) = planes!![i]
    override val yBuffer: ByteBuffer get() = p(0).buffer
    override val yRowStride: Int get() = p(0).rowStride
    override val uBuffer: ByteBuffer get() = p(1).buffer
    override val uRowStride: Int get() = p(1).rowStride
    override val uPixelStride: Int get() = p(1).pixelStride
    override val vBuffer: ByteBuffer get() = p(2).buffer
    override val vRowStride: Int get() = p(2).rowStride
    override val vPixelStride: Int get() = p(2).pixelStride
}

/**
 * K preallocated NV21 slots holding the most recent FULL analysis frames.
 *
 * Why a ring of copies: with STRATEGY_KEEP_ONLY_LATEST the ImageReader is only
 * 4 deep and an unclosed ImageProxy stalls the stream, so frames can never be
 * held — the bytes are copied out and the image closed. When the detector
 * fires, the last 3 frames ARE the burst (they are the frames the stillness
 * test just approved), so no capture has to happen after the trigger.
 *
 * Steady state allocates nothing: slots and the chroma row scratch are sized
 * once per frame size. [snapshotLast] returns COPIES (only on a capture), so
 * the ring keeps running while the capture pipeline works.
 *
 * Not thread-safe: owned by the analysis thread.
 */
class FrameRing(val capacity: Int = 4) {
    init { require(capacity >= 1) }

    private var w = 0
    private var h = 0
    private var slots: Array<ByteArray> = emptyArray()
    private val ts = LongArray(capacity)
    private val rot = IntArray(capacity)
    private var head = 0            // next slot to write
    var size = 0
        private set
    private var uRow = ByteArray(0)
    private var vRow = ByteArray(0)

    val width get() = w
    val height get() = h

    /** Copy [p] into the next slot as NV21 (Y then V,U interleaved). */
    fun copyFrom(p: YuvPlanes, rotation: Int, timestampNs: Long) {
        require(p.width > 0 && p.height > 0 && p.width % 2 == 0 && p.height % 2 == 0) {
            "NV21 needs even, positive dimensions: ${p.width}x${p.height}"
        }
        if (p.width != w || p.height != h) {
            w = p.width; h = p.height
            slots = Array(capacity) { ByteArray(Nv21Frame.byteCount(w, h)) }
            size = 0; head = 0
        }
        val needU = (w / 2 - 1) * p.uPixelStride + 1
        val needV = (w / 2 - 1) * p.vPixelStride + 1
        if (uRow.size < needU) uRow = ByteArray(needU)
        if (vRow.size < needV) vRow = ByteArray(needV)
        copyNv21(p, slots[head], uRow, vRow)
        ts[head] = timestampNs
        rot[head] = rotation
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** Timestamp of the newest frame, or null when empty. */
    fun newestTimestampNs(): Long? = if (size == 0) null else ts[(head - 1 + capacity) % capacity]

    /** How many held frames are at most [maxAgeNs] older than the newest one. */
    fun countFresh(maxAgeNs: Long): Int {
        val newest = newestTimestampNs() ?: return 0
        var n = 0
        for (k in 0 until size) {
            val i = (head - 1 - k + 2 * capacity) % capacity
            if (newest - ts[i] > maxAgeNs) break
            n++
        }
        return n
    }

    /**
     * Copies of the newest [n] frames (oldest first) that are at most
     * [maxAgeNs] older than the newest — a frame left over from a previous
     * card must never be uploaded as part of this one.
     */
    fun snapshotLast(n: Int = 3, maxAgeNs: Long = Long.MAX_VALUE): List<Nv21Frame> {
        val take = minOf(n, countFresh(maxAgeNs))
        val out = ArrayList<Nv21Frame>(take)
        for (k in take - 1 downTo 0) {
            val i = (head - 1 - k + 2 * capacity) % capacity
            out += Nv21Frame(slots[i].copyOf(), w, h, rot[i], ts[i])
        }
        return out
    }

    fun clear() { size = 0; head = 0 }

    companion object {
        /**
         * YUV_420_888 → NV21 into [dst] (w·h·3/2 bytes), honouring every stride.
         * Y rows are bulk copies; each chroma row is bulk-read into [uRow]/[vRow]
         * (only the bytes up to its last sample — the last row may be short) and
         * then interleaved V,U from the arrays, which is far cheaper on ART than
         * per-byte ByteBuffer reads.
         */
        fun copyNv21(p: YuvPlanes, dst: ByteArray, uRow: ByteArray, vRow: ByteArray) {
            val w = p.width; val h = p.height
            val yb = p.yBuffer; val yrs = p.yRowStride
            if (yrs == w) {
                yb.position(0)
                yb.get(dst, 0, w * h)
            } else {
                for (r in 0 until h) {
                    yb.position(r * yrs)
                    yb.get(dst, r * w, w)
                }
            }
            yb.position(0)
            val cw = w / 2; val ch = h / 2
            val ub = p.uBuffer; val vb = p.vBuffer
            val ups = p.uPixelStride; val vps = p.vPixelStride
            val urs = p.uRowStride; val vrs = p.vRowStride
            val needU = (cw - 1) * ups + 1
            val needV = (cw - 1) * vps + 1
            var o = w * h
            for (r in 0 until ch) {
                vb.position(r * vrs); vb.get(vRow, 0, needV)
                ub.position(r * urs); ub.get(uRow, 0, needU)
                var iu = 0; var iv = 0
                for (k in 0 until cw) {
                    dst[o] = vRow[iv]
                    dst[o + 1] = uRow[iu]
                    o += 2; iu += ups; iv += vps
                }
            }
            ub.position(0); vb.position(0)
        }
    }
}
