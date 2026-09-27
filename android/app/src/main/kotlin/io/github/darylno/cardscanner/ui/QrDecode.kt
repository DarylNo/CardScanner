package io.github.darylno.cardscanner.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads a QR out of a camera frame's Y plane. Tries the frame as-is, then
 * INVERTED: the desktop drew its phone QR light-on-dark (until 1.0.7) and
 * QRCodeReader only finds dark-on-light finder patterns — pairing by QR
 * silently never matched. Phone camera apps invert on their own; this does too.
 */
object QrDecode {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun decode(y: ByteArray, rowStride: Int, width: Int, height: Int, reader: QRCodeReader = QRCodeReader()): String? {
        val src = PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false)
        return tryDecode(src, reader) ?: tryDecode(src.invert(), reader)
    }

    private fun tryDecode(src: LuminanceSource, reader: QRCodeReader): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(src)), hints).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
