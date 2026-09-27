package io.github.darylno.cardscanner.core

/**
 * One camera frame, SENSOR-oriented, in NV21: the Y plane ([width]×[height]
 * bytes, no row padding) followed by the V,U-interleaved chroma plane at half
 * resolution — the layout OpenCV's COLOR_YUV2BGR_NV21 reads, so a capture
 * converts with one native call ([Nv21Bgr]). [rotation] is the CameraX
 * `rotationDegrees` that turns it upright ([Rotation]); [timestampNs] is the
 * camera timestamp (ImageInfo.timestamp).
 *
 * [data] may be longer than the frame (a reusable ring slot sized for the
 * largest frame); only the first [byteCount] bytes are the image.
 */
class Nv21Frame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val timestampNs: Long,
) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "NV21 needs even, positive dimensions: ${width}x$height"
        }
        Rotation.normalize(rotation)
        require(data.size >= byteCount(width, height)) {
            "NV21 ${width}x$height needs ${byteCount(width, height)} bytes, got ${data.size}"
        }
    }

    /** Bytes of image in [data]: Y plane + half-resolution interleaved chroma. */
    val byteCount: Int get() = byteCount(width, height)

    val uprightWidth: Int get() = Rotation.uprightWidth(width, height, rotation)
    val uprightHeight: Int get() = Rotation.uprightHeight(width, height, rotation)

    companion object {
        fun byteCount(width: Int, height: Int): Int = width * height * 3 / 2
    }
}
