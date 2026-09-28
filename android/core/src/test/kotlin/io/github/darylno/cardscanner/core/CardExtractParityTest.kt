package io.github.darylno.cardscanner.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * [CardExtract] (the server's extract_card + is_blank_surface, used by the
 * on-phone identifier) against the SERVER's answers per synthetic scene:
 * `extract_detected`, `blank` and the texture metrics behind it
 * (scripts/export_detect_fixtures.py; `--check` in CI). The black-holder
 * scene exercises the centre-crop fallback's stricter edge bar.
 */
class CardExtractParityTest {
    private val scenes: JSONObject by lazy {
        OpenCvTest.load()
        JSONObject(OpenCvTest.detectFile("expected.json").readText()).getJSONObject("scenes")
    }

    @Test
    fun extractAndBlankMatchTheServer() {
        val fails = mutableListOf<String>()
        var n = 0
        for (name in scenes.keySet().sorted()) {
            val e = scenes.getJSONObject(name)
            if (!e.has("blank")) continue
            n++
            val img = OpenCvTest.readBgr("$name.png")
            val x = CardExtract.extractCard(img)
            try {
                if (x.detected != e.getBoolean("extract_detected")) fails += "$name detected=${x.detected}"
                val blank = CardExtract.isBlankSurface(x.card, x.detected)
                if (blank != e.getBoolean("blank")) fails += "$name blank=$blank"
                if (CardExtract.frameIsBlank(img) != blank) fails += "$name frameIsBlank disagrees"
                val gray = Mat()
                Imgproc.cvtColor(x.card, gray, Imgproc.COLOR_BGR2GRAY)
                val (std, edge) = CardQuad.textureMetrics(gray)
                gray.release()
                val m = e.getJSONArray("blank_metrics")
                // Detected cards are warped by a sub-pixel-different quad (OpenCV 4.9 vs
                // the server's cv2) — metrics agree closely, not bit for bit.
                if (abs(std - m.getDouble(0)) > 0.5 || abs(edge - m.getDouble(1)) > 0.01) {
                    fails += "$name metrics std=$std edge=$edge vs $m"
                }
                assertEquals(CardQuad.CARD_W, x.card.cols())
                assertEquals(CardQuad.CARD_H, x.card.rows())
            } finally {
                x.card.release(); img.release()
            }
        }
        assertTrue("no scene carried blank fixtures", n > 0)
        assertTrue("extract/blank mismatches: $fails", fails.isEmpty())
    }

    @Test
    fun warpOrFrame_keepsTheRawFrameWhenNoQuad() {
        OpenCvTest.load()
        val flat = Mat(300, 400, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))
        val w = CardExtract.warpOrFrame(flat)
        try {
            assertEquals(false, w.detected)
            assertEquals(400, w.card.cols())     // the frame itself, as ArtIndex._warp
            assertEquals(300, w.card.rows())
            assertTrue(CardExtract.isBlankSurface(w.card, detected = false))
        } finally {
            w.card.release(); flat.release()
        }
    }

    @Test
    fun centreCrop_keepsAspect() {
        OpenCvTest.load()
        val wide = Mat(720, 960, CvType.CV_8UC3, Scalar(10.0, 200.0, 30.0))
        val c = CardExtract.centreCropFallback(wide)
        try {
            assertEquals(630, c.cols()); assertEquals(880, c.rows())
        } finally {
            c.release(); wide.release()
        }
    }
}
