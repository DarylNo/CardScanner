package io.github.darylno.cardscanner.core

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

/**
 * How much real detail a capture carries, for the zoom A/B (1.1.17, the guided
 * session's A1 / B / A2 steps: the same cards at 1× and zoomed). A diagnostic
 * only — logged next to each capture, never used to decide anything, so there is
 * no Python reference for it (like [PrintEvidence]).
 *
 * The frame-scale sharpness ("sharpness a / [b] / c", the Laplacian of the Area's
 * grey) is NOT comparable across zooms: a zoomed frame shows the same card over
 * more pixels. [identRaster] measures on the identifier's OWN raster instead —
 * the card warped to 630×880 ([CardQuad.warpCard] at its defaults, what
 * CardExtract.extractCard hands the OCR) — so a card at 1× and the same card at
 * 1.9× are scored on the same grid, over the collector strip the OCR actually
 * reads ([OcrStrip.stripBounds]). Comparable as long as the zoom stays within
 * the sensor's lossless range, which ZoomFit.choose caps it to.
 */
object ZoomDetail {
    /**
     * The card figure skips this much of each side of the warp: the finder's quad
     * sits a little outside the card (a sleeve, the dilation), and a sliver of tray
     * past the card's edge is a hard edge that is not the card's print.
     * PROVISIONAL — a judgement, not a measurement (the 5:7 crop's own slack is ~2 %).
     */
    const val CARD_INSET = 0.06

    /**
     * Detail on the identification raster: the Laplacian variance of the collector
     * strip ([strip]) and of the card inset [CARD_INSET] ([card]), both on the
     * [CardQuad.CARD_W]×[CardQuad.CARD_H] warp.
     */
    class Ident(val strip: Double, val card: Double)

    /** The strip [identRaster] scores: `[y0, y1, x0, x1]` of the 630×880 warp — the OCR's own ([OcrStrip.stripBounds]). */
    fun stripBounds(): IntArray = OcrStrip.stripBounds(CardQuad.CARD_W, CardQuad.CARD_H)

    /**
     * [frame] (BGR or grey, any size) with the card at [quad] (TL,TR,BR,BL in its
     * pixels) → the [Ident] of its 630×880 warp. Throws on an OpenCV failure; the
     * capture pipeline calls it inside runCatching (a capture never fails for it).
     */
    fun identRaster(frame: Mat, quad: FloatArray): Ident {
        val grey = if (frame.channels() == 1) frame else Mat().also { Imgproc.cvtColor(frame, it, Imgproc.COLOR_BGR2GRAY) }
        try {
            val card = CardQuad.warpCard(grey, quad, CardQuad.CARD_W, CardQuad.CARD_H)
            try {
                val (y0, y1, x0, x1) = stripBounds().toList()
                val strip = card.submat(Rect(x0, y0, x1 - x0, y1 - y0))
                val ix = (CardQuad.CARD_W * CARD_INSET).toInt(); val iy = (CardQuad.CARD_H * CARD_INSET).toInt()
                val inner = card.submat(Rect(ix, iy, CardQuad.CARD_W - 2 * ix, CardQuad.CARD_H - 2 * iy))
                try {
                    // laplacianVariance scores a submat as a standalone copy: no pixel outside it counts.
                    return Ident(Sharpness.laplacianVariance(strip), Sharpness.laplacianVariance(inner))
                } finally {
                    strip.release(); inner.release()
                }
            } finally {
                card.release()
            }
        } finally {
            if (grey !== frame) grey.release()
        }
    }
}
