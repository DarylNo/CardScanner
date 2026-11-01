package io.github.darylno.cardscanner.camera

import io.github.darylno.cardscanner.core.CardQuad
import io.github.darylno.cardscanner.core.Nv21Bgr
import io.github.darylno.cardscanner.core.Nv21Frame
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.RoiPx
import io.github.darylno.cardscanner.core.ZoomDetail
import io.github.darylno.cardscanner.core.ZoomProbe
import io.github.darylno.cardscanner.core.ZoomProbeRun
import org.opencv.core.Core
import org.opencv.core.Mat

/**
 * The zoom probe's worker (1.1.17 — CameraController runs it on its own `zoom-probe`
 * thread, never the capture pipeline): one rung's frames → each frame's facts
 * ([ZoomProbe.FrameFacts]) — the card as the identification's own finder sees it
 * ([CardQuad.find] on the upright BGR frame, as CapturePipeline runs it) inside the
 * search rect, the zoom / lens / exposure / ISO / AF state the frame's OWN
 * CaptureResult reported ([FrameMetaRing.Meta]), the card's mean luma (with AE/AWB
 * locked, how a light change shows) — and, when asked, the card's 64×88 thumbnail
 * ([ZoomDetail.thumbnail]) for the restore's same-card check. Pure OpenCV, so the JVM
 * tests run it (ProbeMeasureTest). Every Mat is released; one frame is converted at a time.
 */
internal object ProbeMeasure {
    /** The card's mean luma is read off a raster this coarse (px/mm): its average, not its detail. */
    private const val LUMA_PX_PER_MM = 2.0

    /**
     * [frames] (with [metas], their CaptureResults by timestamp — null = not kept) → the
     * worker's answer. The card is looked for in [rect] (`[x, y, w, h]` upright px), else in
     * [area] (fractions of the frame: the scan Area at the camera's zoom), else in the whole
     * frame. [activeW]×[activeH] = the sensor's active array (the crop region's frame).
     * [thumbnail] = the role needs the card's thumbnail (LOCATE / START / RESTORE).
     */
    fun measure(
        frames: List<Nv21Frame>, metas: List<FrameMetaRing.Meta?>, rect: IntArray?, area: RoiFrac?,
        activeW: Int?, activeH: Int?, thumbnail: Boolean,
    ): ZoomProbeRun.Measured {
        val t0 = System.nanoTime()
        var w = 0; var h = 0
        val facts = ArrayList<ZoomProbe.FrameFacts>(frames.size)
        for ((i, f) in frames.withIndex()) {
            val bgr = Nv21Bgr.toUprightBgr(f)
            try {
                w = bgr.cols(); h = bgr.rows()
                val quad = find(bgr, searchArea(rect, area, w, h))
                val luma = quad?.let { q -> runCatching { meanLuma(bgr, q) }.getOrNull() }
                val m = metas.getOrNull(i)
                facts += ZoomProbe.FrameFacts(
                    quad, m?.frameZoom(activeW), reported = m?.zoomRatio != null, lensDpt = m?.focusDpt?.toDouble(),
                    exposureNs = m?.exposureNs, iso = m?.iso, afState = m?.afState?.let(FocusLog::afStateName),
                    luma = luma, cropCentred = m?.cropCentred(activeW, activeH),
                )
            } finally {
                bgr.release()
            }
        }
        val thumb = if (thumbnail) thumbnailOf(frames, facts) else null
        return ZoomProbeRun.Measured(facts, w, h, thumb, (System.nanoTime() - t0) / 1_000_000)
    }

    /** Where to look: [rect] (`[x, y, w, h]` px), else [area] (fractions), else the whole [w]×[h] frame — clamped into it (never empty). */
    fun searchArea(rect: IntArray?, area: RoiFrac?, w: Int, h: Int): RoiPx {
        val r = rect?.takeIf { it.size == 4 }?.let { RoiPx(it[0], it[1], it[2], it[3]) } ?: area?.toPixels(w, h) ?: RoiPx(0, 0, w, h)
        val x0 = r.x.coerceIn(0, w - 1); val y0 = r.y.coerceIn(0, h - 1)
        val x1 = r.right.coerceIn(x0 + 1, w); val y1 = r.bottom.coerceIn(y0 + 1, h)
        return RoiPx(x0, y0, x1 - x0, y1 - y0)
    }

    /** [CardQuad.find] in [r] of [bgr], in the frame's own px; null = no card (or the finder failed). */
    private fun find(bgr: Mat, r: RoiPx): FloatArray? {
        val sub = bgr.submat(r.y, r.bottom, r.x, r.right)
        return try {
            CardQuad.find(sub)?.let { CardQuad.translate(it, r.x.toFloat(), r.y.toFloat()) }
        } catch (e: Exception) {
            null
        } finally {
            sub.release()
        }
    }

    private fun meanLuma(bgr: Mat, quad: FloatArray): Double {
        val m = ZoomDetail.warpToScale(bgr, quad, LUMA_PX_PER_MM)
        try { return Core.mean(m).`val`[0] } finally { m.release() }
    }

    /** The thumbnail of the frame whose card is the median size (the runner's own pick for the 1× card), or null with no card. */
    private fun thumbnailOf(frames: List<Nv21Frame>, facts: List<ZoomProbe.FrameFacts>): FloatArray? {
        val withQuad = facts.indices.filter { facts[it].quad != null }
        if (withQuad.isEmpty()) return null
        val pick = withQuad.sortedBy { ZoomProbe.longSide(facts[it].quad!!) }[withQuad.size / 2]
        val bgr = Nv21Bgr.toUprightBgr(frames[pick])
        try { return runCatching { ZoomDetail.thumbnail(bgr, facts[pick].quad!!) }.getOrNull() } finally { bgr.release() }
    }
}
