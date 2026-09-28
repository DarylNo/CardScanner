package io.github.darylno.cardscanner.core

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The SERVER's side of card extraction — `card_detect.extract_card`,
 * `_centre_crop_fallback` and `is_blank_surface` — for the on-phone
 * identifier (Stage 2), which has to see the card exactly as the server's
 * pipeline does when it re-detects on whatever the phone uploaded.
 *
 * The CAPTURE path never uses this: the phone still uploads and the server
 * stays the judge of blank scans until Stage 4 (see [CardQuad]'s note).
 *
 * Parity: CardExtractParityTest against `detect/expected.json`
 * (`extract_detected`, `blank`), made by the server's own code
 * (scripts/export_detect_fixtures.py, `--check` in CI).
 */
object CardExtract {
    /** `_MIN_FALLBACK_EDGE_FRAC` — the stricter edge bar for a centre-crop fallback. */
    const val MIN_FALLBACK_EDGE_FRAC = 0.09

    /** `extract_card`'s `(card_img, detected)`. The caller releases [card]. */
    class Extracted(val card: Mat, val detected: Boolean)

    /**
     * `_centre_crop_fallback`: the largest centred region with the card's
     * aspect, scaled (INTER_CUBIC) to 630×880 without stretching.
     */
    fun centreCropFallback(frame: Mat): Mat {
        val h = frame.rows(); val w = frame.cols()
        val target = CardQuad.CARD_W.toDouble() / CardQuad.CARD_H
        val cropH = minOf(h, (w / target).toInt())
        val cropW = (cropH * target).toInt()
        val x0 = maxOf(0, (w - cropW) / 2)
        val y0 = maxOf(0, (h - cropH) / 2)
        // numpy slicing clamps at the array edge.
        val cw = minOf(cropW, w - x0)
        val ch = minOf(cropH, h - y0)
        val crop = frame.submat(Rect(x0, y0, cw, ch))
        val out = Mat()
        try {
            Imgproc.resize(crop, out, Size(CardQuad.CARD_W.toDouble(), CardQuad.CARD_H.toDouble()),
                0.0, 0.0, Imgproc.INTER_CUBIC)
        } finally {
            crop.release()
        }
        return out
    }

    /** `extract_card`: the warped quad, else the centre-crop fallback. */
    fun extractCard(frame: Mat): Extracted {
        val corners = CardQuad.find(frame)
        return if (corners != null) Extracted(CardQuad.warpCard(frame, corners), true)
        else Extracted(centreCropFallback(frame), false)
    }

    /**
     * `ArtIndex._warp` / `ArtMatcher._warp_frame`: the warped card when a quad
     * is found, else the FRAME ITSELF (a copy here) — not the centre crop.
     * That is what the server hashes and ranks.
     */
    fun warpOrFrame(frame: Mat): Extracted {
        val corners = try { CardQuad.find(frame) } catch (e: Exception) { null }
        return if (corners != null) Extracted(CardQuad.warpCard(frame, corners), true)
        else Extracted(frame.clone(), false)
    }

    /**
     * `is_blank_surface`: no printed card content (a holder, the bare tray).
     * [detected] false = a centre-crop fallback, which needs the stricter edge bar.
     */
    fun isBlankSurface(card: Mat, detected: Boolean = true): Boolean {
        val gray = if (card.channels() == 1) card else Mat().also { Imgproc.cvtColor(card, it, Imgproc.COLOR_BGR2GRAY) }
        try {
            val (std, edgeFrac) = CardQuad.textureMetrics(gray)
            val bar = if (detected) CardQuad.MIN_INTERIOR_EDGE_FRAC else MIN_FALLBACK_EDGE_FRAC
            return std < CardQuad.MIN_INTERIOR_STD || edgeFrac < bar
        } finally {
            if (gray !== card) gray.release()
        }
    }

    /** pipeline.scan_candidates' `_blank(frame)`: extract, then judge. */
    fun frameIsBlank(frame: Mat): Boolean {
        val e = extractCard(frame)
        try {
            return isBlankSurface(e.card, e.detected)
        } finally {
            e.card.release()
        }
    }
}
