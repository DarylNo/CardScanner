package io.github.darylno.cardscanner.gateway

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Renders a join URL as a QR code (ZXing core; no zxing-android dependency). */
object QrBitmap {

    /** A [sizePx]×[sizePx] QR of [text] with a 2-module quiet zone. */
    fun encode(text: String, sizePx: Int, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap {
        val m = matrix(text, sizePx)
        val w = m.width
        val h = m.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) pixels[row + x] = if (m.get(x, y)) dark else light
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** The module matrix (JVM-testable part). */
    fun matrix(text: String, sizePx: Int): BitMatrix =
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, sizePx, sizePx,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
}
