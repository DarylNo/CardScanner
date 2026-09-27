package io.github.darylno.cardscanner.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * On-phone flattening — port of the executable spec `tests/phone_flatten_ref.py`
 * (read its docstring for the measurements behind every number here).
 *
 * The phone uploads the card perspective-FLATTENED with a margin of tray
 * around it instead of the whole scan-area frame. The server is still the
 * judge: it re-runs its own find_card_quad on the upload, and the margin is
 * what lets it find the card's edges again (a card filling >92% of the image
 * is rejected as "the frame itself"). A margin is used only when ALL of it
 * lies inside the camera frame ([marginFits]) — inventing pixels past the
 * frame edge (BORDER_REPLICATE smearing a black border outward) measurably
 * made the server's re-detection worse — and it steps down 8% → 6% → 4%; when
 * none fits, the caller uploads the frame as-is ([chooseMargin] = null).
 *
 * The constants are the contract with the server side; CardQuadParityTest
 * checks the flattened output against the Python reference's pixels.
 */
object Flatten {
    /** Largest margin that fits wins, as a fraction of the card's own width/height, each side. */
    val MARGINS: List<Double> = listOf(0.08, 0.06, 0.04)

    /** Flattened card = 1.25 × the server's 630×880 warp. */
    const val SCALE = 1.25

    /**
     * `round(630 * 1.25)` in Python is round(787.5) → 788 (banker's rounding,
     * 788 is even); `round(880 * 1.25)` = 1100. Hard-coded; FlattenTest
     * asserts them against the half-even computation.
     */
    const val CARD_W = 788
    const val CARD_H = 1100

    /** Pixel margins and output size for one margin fraction. */
    data class Layout(val mx: Int, val my: Int, val outW: Int, val outH: Int)

    /**
     * `_layout`: `mx, my = round(CARD_W·margin), round(CARD_H·margin)` with
     * Python's half-even round() (Math.rint): 0.08 → (63, 88), 0.06 → (47, 66),
     * 0.04 → (32, 44).
     */
    fun layout(margin: Double): Layout {
        val mx = Math.rint(CARD_W * margin).toInt()
        val my = Math.rint(CARD_H * margin).toInt()
        return Layout(mx, my, CARD_W + 2 * mx, CARD_H + 2 * my)
    }

    /**
     * `_transform`: the perspective matrix (3×3 CV_64F, caller releases)
     * taking [quad] (TL,TR,BR,BL frame pixels) to the card rectangle inset by
     * the margin in the output.
     */
    fun transform(quad: FloatArray, margin: Double): Mat {
        val (mx, my) = layout(margin)
        val src = CardQuad.quadMat(quad)
        val dst = MatOfPoint2f(
            Point(mx.toDouble(), my.toDouble()),
            Point((mx + CARD_W).toDouble(), my.toDouble()),
            Point((mx + CARD_W).toDouble(), (my + CARD_H).toDouble()),
            Point(mx.toDouble(), (my + CARD_H).toDouble()),
        )
        try {
            return Imgproc.getPerspectiveTransform(src, dst)
        } finally {
            src.release(); dst.release()
        }
    }

    /**
     * `margin_fits`: true when every corner of the flattened output maps back
     * INSIDE the [frameW]×[frameH] frame (inverse transform of the output
     * corners, float32 points as cv2.perspectiveTransform returns them).
     */
    fun marginFits(quad: FloatArray, frameW: Int, frameH: Int, margin: Double): Boolean {
        val lay = layout(margin)
        val m = transform(quad, margin)
        val inv = Mat()
        val out = MatOfPoint2f(
            Point(0.0, 0.0), Point(lay.outW.toDouble(), 0.0),
            Point(lay.outW.toDouble(), lay.outH.toDouble()), Point(0.0, lay.outH.toDouble()),
        )
        val src = MatOfPoint2f()
        try {
            Core.invert(m, inv, Core.DECOMP_LU)          // np.linalg.inv
            Core.perspectiveTransform(out, src, inv)
            return src.toArray().all { p ->
                p.x >= 0 && p.x <= frameW - 1 && p.y >= 0 && p.y <= frameH - 1
            }
        } finally {
            m.release(); inv.release(); out.release(); src.release()
        }
    }

    /** `choose_margin`: the largest [MARGINS] entry that fits, or null (upload the frame as-is). */
    fun chooseMargin(quad: FloatArray, frameW: Int, frameH: Int): Double? =
        MARGINS.firstOrNull { marginFits(quad, frameW, frameH, it) }

    /**
     * `phone_flatten`: card + margin, perspective-flattened (same type as
     * [frame], normally 8UC3 BGR; caller releases). [quad] is in [frame]'s pixels.
     * INTER_LINEAR + BORDER_REPLICATE exactly as the reference.
     */
    fun flatten(frame: Mat, quad: FloatArray, margin: Double): Mat {
        val lay = layout(margin)
        val m = transform(quad, margin)
        val out = Mat()
        try {
            Imgproc.warpPerspective(
                frame, out, m, Size(lay.outW.toDouble(), lay.outH.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE,
            )
        } finally {
            m.release()
        }
        return out
    }
}
