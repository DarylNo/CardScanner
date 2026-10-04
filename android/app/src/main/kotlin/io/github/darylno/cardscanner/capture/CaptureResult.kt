package io.github.darylno.cardscanner.capture

import io.github.darylno.cardscanner.core.RoiPx

/**
 * What one capture produced, ready for upload.
 *
 * [primary] is the single JPEG to send first: the card flattened WITH a margin
 * when a quad was found and a margin fits inside the frame ([flattened] true,
 * [margin] = the chosen fraction), otherwise the sharpest raw upright crop.
 * [fallbacks] are the raw upright crops re-sent when the server answers
 * no_card / not identified (the server's own multi-frame retry): ALL captured
 * frames in time order, the sharpest included, flattened or not.
 *
 * [quad] is in FULL upright-frame pixels (TL,TR,BR,BL); [crop] is the region
 * of the upright frame the raw crops cover (the scan area, or the whole frame
 * in Handheld); [sharpness] is per captured frame (time order, the Laplacian
 * variance of the Area's grey) and [sharpestIndex] indexes it — the frame the
 * primary and the quad come from. [timingsMs] keys: sharpness, convert, quad,
 * flatten, encode, total.
 */
class CaptureResult(
    val primary: ByteArray,
    val fallbacks: List<ByteArray>,
    val flattened: Boolean,
    val margin: Double?,
    val timingsMs: Map<String, Long>,
    val quad: FloatArray?,
    val crop: RoiPx,
    val frameWidth: Int,
    val frameHeight: Int,
    val sharpness: DoubleArray,
    val sharpestIndex: Int,
) {
    /** One line for the status/diagnostics log. */
    fun summary(): String = buildString {
        append(if (flattened) "flattened m=$margin" else if (quad != null) "raw (no margin fits)" else "raw (no quad)")
        append(" · ${primary.size / 1024} KB + ${fallbacks.size} fallback")
        append(" · ").append(sharpnessText())
        append(" · ")
        append(timingsMs.entries.joinToString(" ") { "${it.key}=${it.value}ms" })
    }

    /**
     * Every captured frame's sharpness (Laplacian variance of the Area, oldest
     * first) with the one used in brackets: "sharpness 41.2 / [55.0] / 38.9".
     * A burst whose frames all read soft, or a wide spread between them (motion,
     * a lens still moving), shows here — "is it focusing?" (1.1.11; it used to
     * say only "sharp #1 of 3").
     */
    fun sharpnessText(): String =
        if (sharpness.isEmpty()) "sharpness —"
        else "sharpness " + sharpness.indices.joinToString(" / ") { i ->
            val v = "%.1f".format(sharpness[i])
            if (i == sharpestIndex) "[$v]" else v
        }
}
