package io.github.darylno.cardscanner.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.darylno.cardscanner.core.OcrEngine
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * The phone's collector-line recogniser: Google ML Kit's on-device Latin text
 * model, the BUNDLED artifact (model inside the APK — no Play Services model
 * download, works offline). It stands where RapidOCR stands on the rig:
 * `OcrStrip.readBottomStrip(card, engine)` feeds it both preprocessing
 * variants and hands the union to `OcrMatch.matchPrinting`.
 *
 * Blocking: call from a worker thread, never the main thread (Tasks.await
 * refuses the main thread). Any failure propagates to readBottomStrip, which
 * reads it as "" — the art ranking then stands untouched, as on the server.
 */
class MlKitOcrEngine : OcrEngine, Closeable {
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun read(bgr: Mat): String {
        val bmp = toBitmap(bgr)
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)),
                                   TIMEOUT_S, TimeUnit.SECONDS)
            return OcrText.joinLines(text.textBlocks.map { b -> b.lines.map { it.text } })
        } finally {
            bmp.recycle()
        }
    }

    override fun close() = recognizer.close()

    companion object {
        const val TIMEOUT_S = 10L

        /** An 8-bit BGR (variant 0) or grey (variant 1) Mat as an ARGB_8888 Bitmap. */
        fun toBitmap(m: Mat): Bitmap {
            require(m.depth() == CvType.CV_8U) { "8-bit image expected" }
            val rgba = Mat()
            try {
                when (m.channels()) {
                    1 -> Imgproc.cvtColor(m, rgba, Imgproc.COLOR_GRAY2RGBA)
                    3 -> Imgproc.cvtColor(m, rgba, Imgproc.COLOR_BGR2RGBA)
                    4 -> Imgproc.cvtColor(m, rgba, Imgproc.COLOR_BGRA2RGBA)
                    else -> throw IllegalArgumentException("${m.channels()} channels")
                }
                val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(rgba, bmp)
                return bmp
            } finally {
                rgba.release()
            }
        }
    }
}
