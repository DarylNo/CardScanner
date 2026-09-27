package io.github.darylno.cardscanner.capture

import io.github.darylno.cardscanner.core.RoiPx

/**
 * What one capture produced, ready for upload.
 *
 * [primary] is the single JPEG to send first: the card flattened WITH a margin
 * when a quad was found and a margin fits inside the frame ([flattened] true,
 * [margin] = the chosen fraction), otherwise the sharpest raw upright crop.
 * [fallbacks] are the raw upright crops re-sent when the server answers
 * no_card / not identified (the server's own multi-frame retry): all captured
 * frames in time order when [flattened], else the frames other than the one
 * already used as [primary].
 *
 * [quad] is in FULL upright-frame pixels (TL,TR,BR,BL); [crop] is the region
 * of the upright frame the raw crops cover (the scan area, or the whole frame
 * in Handheld); [sharpness] is per captured frame (time order) and
 * [sharpestIndex] indexes it. [timingsMs] keys: sharpness, convert, quad,
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
        append(" · sharp #$sharpestIndex of ${sharpness.size}")
        append(" · ")
        append(timingsMs.entries.joinToString(" ") { "${it.key}=${it.value}ms" })
    }
}
