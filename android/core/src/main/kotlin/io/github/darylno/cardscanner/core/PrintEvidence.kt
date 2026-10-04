package io.github.darylno.cardscanner.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The Tray card-shape gate's FALLBACK (app-only, like [AutoScanner.triggerRefused];
 * phone.html has no outline finder and no gate, so nothing here is a port and the
 * detection differential test never sees it).
 *
 * The gate refuses a Tray Trigger when the live outline ([CardOutline] →
 * [CardQuad.find] on the 176-px detection sample) sees no card. If the finder
 * misses a REAL card (owner, 1.1.10 on the rig: "White bordered cards didn't get
 * picked up as cards"), that card is never scanned — and after
 * RELEARN_AFTER_REFUSALS refusals it becomes "the empty tray", so the next card
 * is judged against it ("old bordered cards didn't see the change of cards").
 *
 * This asks a second, independent question of the SAME trigger box, so it can
 * only ever say YES more often than the outline alone: does the occupied box show
 * a card's PRINTED DETAIL? Inside the CORE of the box (the box inset by
 * [CORE_INSET] of its size on every side — so the edges of a flat bright patch,
 * a glare spot's flank or a hand's outline do not count), the fraction of pixels
 * that are clearly textured NOW (Detection.gradMap's gradient > [PRINT_GRAD]) but
 * were smooth in the learned empty tray (≤ [DetectConst.GRAD_THR], the mask's own
 * "smooth"). A card's art, title, type line and rules text are textured all
 * through the middle of the card; glare, a shadow, an exposure change, a hand's
 * smooth back and the bare tray are not. Plus the box must be card-shaped
 * ([MIN_ASPECT]..[MAX_ASPECT], either orientation — looser than CardQuad's
 * 1.15–1.75, because the mask box of a card is its printed interior plus a
 * dilation, and a sleeve or a turn changes it) and cover at least [MIN_SHARE] of
 * the Area.
 *
 * Thresholds measured on synthetic 176×235 samples (PrintEvidenceTest prints the
 * table; 900 cards — black / white / white-with-dark-frame borders on light,
 * white, dark and textured trays, sleeved and bare, blur 0/1/2 sample px, four
 * sizes up to filling the Area, upright / 10° / sideways — and 200 non-card
 * scenes, 93 of which the occupancy trigger fires on: glare, shadows, exposure
 * steps, hand-like blobs, a noisier sensor, a textured tray). Cards: print ≥ 18 %,
 * box 1.21–1.42, ≥ 8 % of the Area — all 900 accepted. Non-cards: print ≤ 1 %,
 * none accepted. The outline finder itself found 869 of the 900; the 31 it missed
 * are cards that FILL the Area (drawn tight on the card). NOT measured on the rig
 * yet: real art is smoother than the synthetic blocks, hence the low [MIN_PRINT].
 */
object PrintEvidence {
    /** Fraction of the box's core that must be new printed texture (cards ≥ 18 %, non-cards ≤ 1 % measured). */
    const val MIN_PRINT = 0.06
    /**
     * "Textured now" means a gradient above this — 1.5 × GRAD_THR. At GRAD_THR
     * itself sensor noise on a textured tray flips pixels across the line (a
     * playmat with σ 2.5 noise read 11 % against a card minimum of 26 %); a pixel
     * must jump from ≤ 14 to > 21 to count, which noise does not do and print does
     * (non-card maximum 11 % → 1 %, card minimum 26 % → 18 %).
     */
    const val PRINT_GRAD = 21
    /** The core: the box inset by this fraction of its width / height on every side. */
    const val CORE_INSET = 0.25
    /** The box's long / short side, either orientation. A card is 1.40; its mask box measured 1.21–1.42. */
    const val MIN_ASPECT = 1.15
    const val MAX_ASPECT = 1.9
    /** The box's share of the Area (the sample). Cards measured ≥ 8 %. */
    const val MIN_SHARE = 0.03

    /**
     * [print] = new printed texture in the box's core (0..1), [aspect] = the box's
     * long / short side, [share] = the box's area over the sample's.
     */
    class Evidence(val print: Double, val aspect: Double, val share: Double) {
        val printed get() = print >= MIN_PRINT
        val cardShaped get() = aspect >= MIN_ASPECT && aspect <= MAX_ASPECT
        val bigEnough get() = share >= MIN_SHARE
        /** All three: the trigger may fire without an outline. */
        val looksLikeACard get() = printed && cardShaped && bigEnough

        /** "print 3% (need 6%) · box 1.42 (1.15–1.90) · 23% of the Area (≥ 3%)". */
        fun describe(): String =
            "print %.0f%% (need %.0f%%) · box %.2f (%.2f–%.2f) · %.0f%% of the Area (≥ %.0f%%)".format(
                print * 100, MIN_PRINT * 100, aspect, MIN_ASPECT, MAX_ASPECT, share * 100, MIN_SHARE * 100)
    }

    /**
     * The evidence for [box] (sample px, what [Detection.detectBox] returned on
     * [cur]) against the empty tray's gradient [emptyGrad] (the scanner's learned
     * one, [AutoScanner.emptyGradient]). [inset] and [gradThr] are parameters only
     * so the measurement can compare alternatives; the app uses the defaults.
     */
    fun measure(cur: Gray, emptyGrad: IntArray, box: Box, inset: Double = CORE_INSET, gradThr: Int = PRINT_GRAD): Evidence {
        require(emptyGrad.size == cur.size) { "empty gradient ${emptyGrad.size} != sample ${cur.size}" }
        val w = cur.w; val h = cur.h
        val long = max(box.w, box.h).toDouble(); val short = max(1, min(box.w, box.h)).toDouble()
        val aspect = long / short
        val share = box.w.toDouble() * box.h / (w.toDouble() * h)
        // The core, clamped to where Detection.gradMap is defined (it leaves the outer ring at 0).
        val ix = floor(box.w * inset).toInt(); val iy = floor(box.h * inset).toInt()
        val x0 = max(1, box.x + ix); val x1 = min(w - 2, box.x + box.w - 1 - ix)
        val y0 = max(1, box.y + iy); val y1 = min(h - 2, box.y + box.h - 1 - iy)
        if (x1 < x0 || y1 < y0) return Evidence(0.0, aspect, share)
        val p = cur.px
        var n = 0; var hit = 0
        for (y in y0..y1) {
            var i = y * w + x0
            for (x in x0..x1) {
                // Detection.gradMap's formula, inline (no full-map allocation on the analysis thread).
                val g = min(255, abs(p[i + 1] - p[i - 1]) + abs(p[i + w] - p[i - w]))
                if (g > gradThr && emptyGrad[i] <= DetectConst.GRAD_THR) hit++
                n++; i++
            }
        }
        return Evidence(hit.toDouble() / n, aspect, share)
    }
}
