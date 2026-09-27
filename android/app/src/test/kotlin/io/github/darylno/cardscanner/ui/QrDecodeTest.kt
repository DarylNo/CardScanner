package io.github.darylno.cardscanner.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecodeTest {
    private val url = "https://192.168.1.50:8443/phone#pin=" + "ab".repeat(32)

    /** A camera-like Y plane: the QR at [scale]× inside a frame, rows padded to [rowStride]. */
    private fun frame(inverted: Boolean, w: Int = 640, h: Int = 480, rowStride: Int = 704, scale: Int = 5): ByteArray {
        val m = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0)   // 1 px per module, with quiet zone
        val (dark, light) = if (inverted) 0xE8.toByte() to 0x17.toByte() else 0x10.toByte() to 0xF0.toByte()
        val bg = if (inverted) light else 0x80.toByte()
        val y = ByteArray(rowStride * h) { bg }
        val ox = (w - m.width * scale) / 2
        val oy = (h - m.height * scale) / 2
        for (my in 0 until m.height) for (mx in 0 until m.width) {
            val v = if (m.get(mx, my)) dark else light
            for (dy in 0 until scale) for (dx in 0 until scale) y[(oy + my * scale + dy) * rowStride + ox + mx * scale + dx] = v
        }
        return y
    }

    @Test
    fun readsANormalDarkOnLightQr() {
        assertEquals(url, QrDecode.decode(frame(inverted = false), 704, 640, 480))
    }

    @Test
    fun readsTheDesktopsLightOnDarkQr() {
        // The 1.0.6 desktop drew #e8eaed modules on #171a21: this is what setup never matched.
        assertEquals(url, QrDecode.decode(frame(inverted = true), 704, 640, 480))
    }

    @Test
    fun blankFrameIsNull() {
        assertNull(QrDecode.decode(ByteArray(704 * 480) { 0x40 }, 704, 640, 480))
    }
}
