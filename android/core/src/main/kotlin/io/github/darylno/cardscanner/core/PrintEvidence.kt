package io.github.darylno.cardscanner.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * "New texture against the learned tray" only means PRINT while the learned tray
 * is a true picture of the empty tray. On a TEXTURED tray (a playmat, wood) it
 * often is not: learned with a card lying on it (a double-tap, a new Area, a zoom
 * change, a camera rebind), learned slightly out of focus, a room light under AE
 * lock, a nudged mat — and then the tray's own texture reads as print, over the
 * whole Area (the box IS the Area, so its shape and size always pass). The review
 * of 1.1.11 measured those as phantom scans of an empty tray. So two more tests,
 * both independent of how fresh the reference is:
 *  - [trayTexture] — the learned tray was SMOOTH in the core: at most
 *    [MAX_TRAY_TEXTURE] of it above [TRAY_GRAD] (half the mask's "textured"). On
 *    a textured tray the fallback stands aside (1.1.10's refusal); the outline
 *    found every synthetic card on one. A stale reference of a smooth tray has no
 *    texture to misread.
 *  - [ring] — the print must STAND OUT from what is just outside the box: the
 *    texture (gradient > [PRINT_GRAD]) of the current frame in a ring round the
 *    box must be at most 1/[RING_FACTOR] of the core's print. A smooth thing
 *    learned into a textured mat and lifted off it leaves a card-shaped box of
 *    "new" mat texture — exactly as textured as the mat round it. With no ring
 *    inside the sample (the box fills the Area) the test is not asked.
 *
 * Thresholds measured on synthetic 176×235 samples (PrintEvidenceTest prints the
 * tables; 900 cards — black / white / white-with-dark-frame borders on light,
 * white, dark and textured trays, sleeved and bare, blur 0/1/2 sample px, four
 * sizes up to filling the Area, upright / 10° / sideways — 200 non-card scenes
 * with a fresh reference, 93 of which the occupancy trigger fires on, and 348
 * empty trays against a STALE reference, every one of which triggers).
 * Cards on a smooth tray: all 720 accepted — print ≥ 24 %, learned-tray texture
 * ≤ 0.5 %, print ≥ 3.5 × the ring, box 1.23–1.42, ≥ 9 % of the Area. Cards on
 * the textured tray: the fallback stands aside for all 180 (the outline found
 * 173). Fresh-reference non-cards: print ≤ 1 %, none accepted. Stale references:
 * print up to 40 %, the old three tests shot 142 of the 348 (AE-lock light 17,
 * focus 32, nudge 32, lifted card 40, lifted smooth thing 21) — now none; the
 * learned tray's texture refuses all 142 (8–88 % against the 4 % limit). The
 * outline itself found 869 of the 900 cards; the 31 it missed fill the Area.
 * Limits, measured: sensor noise alone makes a flat tray read "textured" above
 * σ ≈ 1.6 levels at sample resolution (2.7 % at σ 1.5, 12 % at σ 2) — the
 * fallback then stands aside, as on a textured tray; and a textured tray
 * learned MORE than 2 sample px out of focus reads as smooth (blur 2.5: tray
 * texture 1–4 %, print 9–10 %) — est. beyond what a phone lens does at the
 * mount's distance. NOT measured on the rig yet: real art is smoother than the
 * synthetic blocks, hence the low [MIN_PRINT].
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
    /** "The learned tray had texture here": an empty-tray gradient above this (GRAD_THR / 2). */
    const val TRAY_GRAD = DetectConst.GRAD_THR / 2
    /** At most this share of the core may have been textured in the learned tray. */
    const val MAX_TRAY_TEXTURE = 0.04
    /** The ring starts this many sample px outside the box (a blurred card edge stays out of it)… */
    const val RING_GAP = 2
    /** …and is this share of the box's short side wide, at least [RING_MIN_WIDTH] px. */
    const val RING_PAD = 0.08
    const val RING_MIN_WIDTH = 4
    /** Fewer ring pixels than this inside the sample = no ring (the box fills the Area). */
    const val RING_MIN_PX = 150
    /** The core's print must be at least this many times the ring's texture. */
    const val RING_FACTOR = 2.0

    /**
     * [print] = new printed texture in the box's core (0..1), [aspect] = the box's
     * long / short side, [share] = the box's area over the sample's,
     * [trayTexture] = the learned tray's textured share of the core,
     * [ring] = the current frame's textured share of the ring round the box, or
     * null when there is no ring inside the sample.
     */
    class Evidence(
        val print: Double, val aspect: Double, val share: Double,
        val trayTexture: Double = 0.0, val ring: Double? = null,
    ) {
        val printed get() = print >= MIN_PRINT
        val cardShaped get() = aspect >= MIN_ASPECT && aspect <= MAX_ASPECT
        val bigEnough get() = share >= MIN_SHARE
        /** The learned tray was smooth where the print is counted (a stale reference cannot read as print). */
        val trayClean get() = trayTexture <= MAX_TRAY_TEXTURE
        /** The print is not just what surrounds the box (no ring = not asked). */
        val standsOut get() = ring == null || print >= RING_FACTOR * ring
        /** All five: the trigger may fire without an outline. */
        val looksLikeACard get() = printed && cardShaped && bigEnough && trayClean && standsOut

        /** "print 3% (need 6%) · box 1.42 (1.15–1.90) · 23% of the Area (≥ 3%) · tray texture 0% (≤ 4%) · ring 1% (≤ ½ print)". */
        fun describe(): String =
            "print %.0f%% (need %.0f%%) · box %.2f (%.2f–%.2f) · %.0f%% of the Area (≥ %.0f%%) · tray texture %.0f%% (≤ %.0f%%) · %s".format(
                print * 100, MIN_PRINT * 100, aspect, MIN_ASPECT, MAX_ASPECT, share * 100, MIN_SHARE * 100,
                trayTexture * 100, MAX_TRAY_TEXTURE * 100,
                ring?.let { "ring %.0f%% (≤ print ÷ %.0f)".format(it * 100, RING_FACTOR) } ?: "no ring (the box fills the Area)")
    }

    /**
     * The evidence for [box] (sample px, what [Detection.detectBox] returned on
     * [cur]) against the empty tray's gradient [emptyGrad] (the scanner's learned
     * one, [AutoScanner.emptyGradient]). The other parameters exist only so the
     * measurement can compare alternatives; the app uses the defaults.
     */
    fun measure(
        cur: Gray, emptyGrad: IntArray, box: Box, inset: Double = CORE_INSET, gradThr: Int = PRINT_GRAD,
        trayGrad: Int = TRAY_GRAD, ringGap: Int = RING_GAP, ringPad: Double = RING_PAD,
    ): Evidence {
        require(emptyGrad.size == cur.size) { "empty gradient ${emptyGrad.size} != sample ${cur.size}" }
        val w = cur.w; val h = cur.h
        val long = max(box.w, box.h).toDouble(); val short = max(1, min(box.w, box.h)).toDouble()
        val aspect = long / short
        val share = box.w.toDouble() * box.h / (w.toDouble() * h)
        val p = cur.px
        // Detection.gradMap's formula, inline (no full-map allocation on the analysis thread).
        fun grad(i: Int) = min(255, abs(p[i + 1] - p[i - 1]) + abs(p[i + w] - p[i - w]))
        // The core, clamped to where Detection.gradMap is defined (it leaves the outer ring at 0).
        val ix = floor(box.w * inset).toInt(); val iy = floor(box.h * inset).toInt()
        val x0 = max(1, box.x + ix); val x1 = min(w - 2, box.x + box.w - 1 - ix)
        val y0 = max(1, box.y + iy); val y1 = min(h - 2, box.y + box.h - 1 - iy)
        if (x1 < x0 || y1 < y0) return Evidence(0.0, aspect, share)
        var n = 0; var hit = 0; var coreTray = 0
        for (y in y0..y1) {
            var i = y * w + x0
            for (x in x0..x1) {
                val e = emptyGrad[i]
                if (e <= DetectConst.GRAD_THR && grad(i) > gradThr) hit++
                if (e > trayGrad) coreTray++
                n++; i++
            }
        }
        // The learned tray over the WHOLE box too: a smooth thing learned into a textured mat
        // and lifted can leave a core that is all "smooth tray" inside a box that is mostly mat.
        var bn = 0; var boxTray = 0
        for (y in max(1, box.y)..min(h - 2, box.y + box.h - 1)) {
            var i = y * w + max(1, box.x)
            for (x in max(1, box.x)..min(w - 2, box.x + box.w - 1)) {
                bn++
                if (emptyGrad[i] > trayGrad) boxTray++
                i++
            }
        }
        val trayTexture = max(coreTray.toDouble() / n, boxTray.toDouble() / max(1, bn))
        // The ring: between ringGap and ringGap + width px outside the box, inside the defined gradient.
        val width = max(RING_MIN_WIDTH, (short * ringPad).roundToInt())
        val ox0 = box.x - ringGap - width; val ox1 = box.x + box.w - 1 + ringGap + width
        val oy0 = box.y - ringGap - width; val oy1 = box.y + box.h - 1 + ringGap + width
        val gx0 = box.x - ringGap; val gx1 = box.x + box.w - 1 + ringGap
        val gy0 = box.y - ringGap; val gy1 = box.y + box.h - 1 + ringGap
        var rn = 0; var rhit = 0
        for (y in max(1, oy0)..min(h - 2, oy1)) {
            val inBandY = y < gy0 || y > gy1
            var i = y * w + max(1, ox0)
            for (x in max(1, ox0)..min(w - 2, ox1)) {
                if (inBandY || x < gx0 || x > gx1) {
                    rn++
                    if (grad(i) > gradThr) rhit++
                }
                i++
            }
        }
        val ring = if (rn >= RING_MIN_PX) rhit.toDouble() / rn else null
        return Evidence(hit.toDouble() / n, aspect, share, trayTexture, ring)
    }
}
