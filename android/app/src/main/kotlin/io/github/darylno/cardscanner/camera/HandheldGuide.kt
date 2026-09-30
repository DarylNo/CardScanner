package io.github.darylno.cardscanner.camera

import io.github.darylno.cardscanner.core.RoiFrac
import kotlin.math.max
import kotlin.math.min

/**
 * The Handheld card guide: a 63:88 rectangle, centred, [FILL] of the limiting
 * side of the UPRIGHT frame. One definition for both the drawn guide
 * (OverlayView) and the capture crop, so what the owner lines up is what gets
 * sent.
 *
 * Handheld used to send the WHOLE frame. On a busy background (wood grain,
 * 2026-09-28: Belladonna Took, HOB #4) neither the phone's nor the server's
 * quad finder found the card, so the art hash ran on card + table and landed
 * at Δ182–194 with the wrong artwork first; the same card in Mount's scan
 * Area matched Δ110. Cropping to the guide plus [PAD] is the Area for
 * Handheld: the server still re-detects and judges inside it.
 */
object HandheldGuide {
    const val FILL = 0.8
    /** Slack around the guide on every side, as a fraction of the guide's own size. */
    const val PAD = 0.15

    /**
     * The scan Area drawn FOR the owner when Auto is off and none is set (1.1.2 —
     * Handheld became "Mount, Auto off"): the same centred 63:88 card, smaller
     * ([DEFAULT_AREA_FILL] instead of [FILL]) so the phone sits back from the card —
     * close enough to read the collector line, not so close its own shadow falls on
     * the card (owner: "shadows appear when we get too close") — plus [PAD] of slack
     * for the re-detect. A starting point: drawing an Area replaces it.
     */
    const val DEFAULT_AREA_FILL = 0.6

    fun defaultArea(w: Int, h: Int): RoiFrac = frac(w, h, PAD, DEFAULT_AREA_FILL)

    /**
     * The Area to draw for the owner now, or null: only with Auto OFF, only when none
     * is set (a drawn one is never replaced), and only once the frame size is known.
     */
    fun areaToDraw(auto: Boolean, current: RoiFrac?, w: Int, h: Int): RoiFrac? =
        if (auto || current != null || w <= 0 || h <= 0) null else defaultArea(w, h)

    /** The guide (pad = 0) or the capture crop (pad = [PAD]) as fractions of a [w]×[h] upright frame. */
    fun frac(w: Int, h: Int, pad: Double = PAD, fill: Double = FILL): RoiFrac {
        var gw = w * fill
        var gh = gw * 88.0 / 63.0
        if (gh > h * fill) { gh = h * fill; gw = gh * 63.0 / 88.0 }
        val px = gw * pad; val py = gh * pad
        val x0 = max(0.0, (w - gw) / 2 - px) / w
        val x1 = min(w.toDouble(), (w + gw) / 2 + px) / w
        val y0 = max(0.0, (h - gh) / 2 - py) / h
        val y1 = min(h.toDouble(), (h + gh) / 2 + py) / h
        return RoiFrac(x0, y0, x1, y1)
    }
}
