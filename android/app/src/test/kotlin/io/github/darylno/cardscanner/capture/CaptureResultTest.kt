package io.github.darylno.cardscanner.capture

import io.github.darylno.cardscanner.core.RoiPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture line carries all three frames' sharpness with the one used
 * (1.1.14, "is it focusing?") — it used to say only "sharp #1 of 3", so a burst
 * that was soft throughout looked the same as a crisp one.
 */
class CaptureResultTest {
    private fun result(sharp: DoubleArray, best: Int, flattened: Boolean = true) = CaptureResult(
        primary = ByteArray(300 * 1024), fallbacks = listOf(ByteArray(1), ByteArray(1), ByteArray(1)), flattened = flattened,
        margin = if (flattened) 0.06 else null, timingsMs = linkedMapOf("sharpness" to 12L, "total" to 80L),
        quad = null, crop = RoiPx(0, 0, 10, 10), frameWidth = 1200, frameHeight = 1600, sharpness = sharp, sharpestIndex = best,
    )

    @Test fun everyFramesSharpnessIsShownWithTheChosenOneBracketed() {
        val r = result(doubleArrayOf(41.24, 55.0, 38.96), 1)
        assertEquals("sharpness 41.2 / [55.0] / 39.0", r.sharpnessText())
        assertEquals("flattened m=0.06 · 300 KB + 3 fallback · sharpness 41.2 / [55.0] / 39.0 · sharpness=12ms total=80ms", r.summary())
    }

    /** The zoom A/B's detail rides the line after the sharpness only when it was measured; otherwise the line is as before. */
    @Test fun theDetailIsShownOnlyWhenMeasured() {
        val plain = result(doubleArrayOf(41.24, 55.0, 38.96), 1)
        assertEquals(null, plain.detail)
        assertEquals("flattened m=0.06 · 300 KB + 3 fallback · sharpness 41.2 / [55.0] / 39.0 · sharpness=12ms total=80ms", plain.summary())
        val measured = CaptureResult(
            primary = ByteArray(300 * 1024), fallbacks = listOf(ByteArray(1)), flattened = true, margin = 0.06,
            timingsMs = linkedMapOf("total" to 80L), quad = null, crop = RoiPx(0, 0, 10, 10), frameWidth = 1200, frameHeight = 1600,
            sharpness = doubleArrayOf(55.0), sharpestIndex = 0, detail = io.github.darylno.cardscanner.core.ZoomDetail.Ident(212.4, 98.6),
        )
        assertEquals("flattened m=0.06 · 300 KB + 1 fallback · sharpness [55.0] · strip detail 212 · card detail 99 (630×880) · total=80ms",
            measured.summary())
    }

    @Test fun oneOrNoFrames() {
        assertEquals("sharpness [7.5]", result(doubleArrayOf(7.5), 0).sharpnessText())
        assertEquals("sharpness —", result(DoubleArray(0), 0).sharpnessText())
        assertTrue(result(doubleArrayOf(1.0, 2.0), 1, flattened = false).summary().startsWith("raw (no quad) · 300 KB"))
    }
}
