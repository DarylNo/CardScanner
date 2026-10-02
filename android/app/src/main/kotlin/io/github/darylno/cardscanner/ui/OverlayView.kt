package io.github.darylno.cardscanner.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import io.github.darylno.cardscanner.core.Box
import io.github.darylno.cardscanner.core.RoiFrac
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent layer over the PreviewView (FIT_CENTER). Everything it draws is
 * positioned in UPRIGHT-FRAME fractions and mapped through the same letterbox
 * math as phone.html's `contain()`: s = min(vw/fw, vh/fh), centred. Preview and
 * analysis share one ResolutionSelector (same FOV), so a fraction of the
 * analysis frame is the same fraction of the previewed image.
 *
 * Detection boxes arrive in GRAY-SAMPLE coordinates (176 × MH of the ROI, or of
 * the whole frame without one) and are mapped sample → ROI → frame → view.
 *
 * The card layer (owner, 2026-10-02): while a card sits in the Area its live
 * OUTLINE (the quad [ScanAnalyzer] finds on the detection sample, frame
 * fractions) is drawn in the state's colour in place of the mask rectangle —
 * the rectangle when there is none. A capture turns it blue with a ✓ and
 * FREEZES it: [beginHold] at the trigger on whatever is on screen, held until
 * the pipeline answers (check_ms alone can be 250 ms, shorter than the
 * pipeline — a hold that lapsed first drew the grey window in between, the
 * very flicker this removes; capped at check_ms + [PIPELINE_HOLD_MS] so it can
 * never stick), [holdCheck] when the capture lands snaps it to the capture's
 * exact corners and holds it there for check_ms (before this it sat on the
 * LIVE box, which grew when a hand came in). [cancelHold] takes it down if the
 * capture fails — only the hold that burst started, so a failed older capture
 * never takes down a newer one's ✓ (the pipeline is one queue; two bursts can
 * be in flight). While the scanner waits for the next card the card is STILL
 * highlighted — its live outline in grey (owner: "always highlight the card";
 * the outline is smoothed across ticks by core OutlineTracker, outliers
 * dropped) — and only without one is the card-shaped WATCH window drawn as a
 * grey dashed outline (the mask rectangle without either). [plan] is the pure
 * decision of what to draw, so a test can assert it without a Canvas.
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class BoxState { OCCUPIED, SETTLING, AWAIT_NEXT, CAPTURED }

    /**
     * What the card layer shows: [shape] = 8 frame fractions TL,TR,BR,BL (null =
     * no shape), in [state]'s colour, [dashed] for the watch window, [check] = the
     * ✓ and fill, [badge] = the centre ✓ badge (a capture with no shape at all).
     */
    class Plan(val shape: FloatArray?, val state: BoxState, val dashed: Boolean, val check: Boolean, val badge: Boolean)

    /** Upright analysis frame size (aspect drives the letterbox); 3:4 portrait until known. */
    var frameW = 1200
        private set
    var frameH = 1600
        private set

    private var roi: RoiFrac? = null
    private var box: Box? = null
    private var sampleW = 176
    private var sampleH = 132
    private var boxState = BoxState.SETTLING
    /** The live card outline / the watch window, frame fractions (see [setBox]). */
    private var outline: FloatArray? = null
    private var watch: FloatArray? = null
    private var debugText: String? = null
    /** Uptime clock (ms); a test replaces it to move through a hold. */
    var clock: () -> Long = { android.os.SystemClock.uptimeMillis() }

    /** Area-drawing mode (phone.html `settingArea`): single-finger drag defines the ROI. */
    var settingArea = false
        set(v) {
            field = v; dragA = null; dragB = null; invalidate()
        }
    private var dragA: Pair<Float, Float>? = null
    private var dragB: Pair<Float, Float>? = null

    /** Area drag finished: the new ROI, or null = "Area too small" (phone.html's < 8% rule). */
    var onAreaDrawn: ((RoiFrac?) -> Unit)? = null
    /** Double-tap = re-learn the empty tray. */
    var onDoubleTap: (() -> Unit)? = null
    /** Single tap at view pixels (x, y) — the overlay exactly covers the PreviewView (tap-to-focus). */
    var onTap: ((Float, Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density
        color = Color.argb(217, 139, 147, 161)
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 6 * density), 0f)
    }
    private val dragPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; color = Color.parseColor("#fbbf24")
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * density
    }
    private val watchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density
        color = Color.parseColor("#8b93a1")
        pathEffect = DashPathEffect(floatArrayOf(10 * density, 7 * density), 0f)
    }
    private val path = Path()
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 40 * density; textAlign = Paint.Align.CENTER
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12 * density; setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke(); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val (fx, fy) = toFrac(e.x, e.y)
            if (fx in 0f..1f && fy in 0f..1f) onTap?.invoke(e.x, e.y)
            return true
        }
    })

    fun setFrameSize(w: Int, h: Int) {
        if (w > 0 && h > 0 && (w != frameW || h != frameH)) {
            frameW = w; frameH = h; invalidate()
        }
    }

    fun setRoi(r: RoiFrac?) {
        roi = r; invalidate()
    }

    fun setDebugText(t: String?) {
        if (debugText != t) { debugText = t; invalidate() }
    }

    /** Until when (uptime ms) the capture ✓ stays up; see [holdCheck]. */
    private var checkUntil = 0L
    /** The shape the ✓ is frozen on (frame fractions); null = the centre badge. */
    private var holdShape: FloatArray? = null
    /** The burst whose capture started the current hold (0 = none / a snap without one). */
    private var holdId = 0L
    /** True while the capture ✓ is up. */
    val holding: Boolean get() = clock() < checkUntil

    /** The shape the hold is frozen on (frame fractions), or null. */
    fun heldShape(): FloatArray? = holdShape?.copyOf()

    /** The live outline if there is one, else the mask box as a quad, else null. */
    private fun liveShape(): FloatArray? = outline?.copyOf() ?: boxQuad()

    private fun boxQuad(): FloatArray? {
        val bx = box ?: return null
        val r = roi ?: RoiFrac(0.0, 0.0, 1.0, 1.0)
        val rw = r.x1 - r.x0; val rh = r.y1 - r.y0
        val x0 = (r.x0 + rw * bx.x / sampleW).toFloat(); val y0 = (r.y0 + rh * bx.y / sampleH).toFloat()
        val x1 = (r.x0 + rw * (bx.x + bx.w) / sampleW).toFloat(); val y1 = (r.y0 + rh * (bx.y + bx.h) / sampleH).toFloat()
        return floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
    }

    /**
     * The capture of burst [id] has STARTED (the burst exists): turn what is on
     * screen now — the live outline, else the box — blue with a ✓ and freeze it.
     * Starting here, not when the pipeline returns, is what stops the
     * blue → grey → blue flicker (AwaitingNext painted the box grey in between).
     * The hold lasts until the pipeline answers ([holdCheck] / [cancelHold] —
     * one of them always follows), never longer than [ms] + [PIPELINE_HOLD_MS]:
     * at [ms] alone a 250 ms check time lapsed before a several-hundred-ms
     * pipeline and the flicker was back.
     */
    fun beginHold(ms: Long, id: Long = 0L) {
        holdId = id
        holdShape = liveShape()
        checkUntil = clock() + ms + PIPELINE_HOLD_MS
        invalidate()
        postInvalidateDelayed(ms + PIPELINE_HOLD_MS + 16)
    }

    /**
     * The scan-taken signal: the blue shape + ✓ stays up for [ms] after a
     * capture, even as detection moves on to "waiting for the next card". With
     * the capture's [quad] (frame fractions) it snaps onto those exact corners;
     * with no shape at all (Tap to scan without a box, or the card already
     * lifted) a blue ✓ badge shows in the centre instead.
     */
    fun holdCheck(ms: Long, quad: FloatArray? = null) {
        holdShape = quad?.takeIf { it.size == 8 }?.copyOf() ?: (if (holding) holdShape else liveShape())
        checkUntil = clock() + ms
        invalidate()
        postInvalidateDelayed(ms + 16)
    }

    /**
     * The capture of burst [id] failed: no ✓ for it. Only the hold THAT burst
     * began comes down — a newer burst's ✓ (its [beginHold] can land before an
     * older capture's failure) stays.
     */
    fun cancelHold(id: Long) {
        if (id != holdId) return
        checkUntil = 0L; holdShape = null; holdId = 0L; invalidate()
    }

    /**
     * Detection box in Gray-sample coords ([sw]×[sh]); null clears it. [outline] = the
     * live card outline, [watch] = the watch window while awaiting the next card, both
     * frame fractions (8 floats) or null.
     */
    fun setBox(b: Box?, sw: Int, sh: Int, state: BoxState, outline: FloatArray? = null, watch: FloatArray? = null) {
        val o = outline?.takeIf { it.size == 8 }
        val w = watch?.takeIf { it.size == 8 }
        if (b == box && sw == sampleW && sh == sampleH && state == boxState &&
            o.contentEqualsOrBothNull(this.outline) && w.contentEqualsOrBothNull(this.watch)) return
        box = b; sampleW = sw; sampleH = sh; boxState = state
        this.outline = o?.copyOf(); this.watch = w?.copyOf()
        invalidate()
    }

    private fun FloatArray?.contentEqualsOrBothNull(other: FloatArray?): Boolean =
        if (this == null || other == null) this === other else this.contentEquals(other)

    /** What the card layer draws right now (pure; see the class doc). */
    fun plan(): Plan {
        if (holding) {
            val s = holdShape
            return Plan(s?.copyOf(), BoxState.CAPTURED, dashed = false, check = s != null, badge = s == null)
        }
        if (boxState == BoxState.AWAIT_NEXT) {
            // The card is always highlighted: its live outline (grey) while it sits there
            // after a capture; the dashed watch window only when there is no outline.
            val o = outline
            if (o != null) return Plan(o.copyOf(), BoxState.AWAIT_NEXT, dashed = false, check = false, badge = false)
            val w = watch
            return if (w != null) Plan(w.copyOf(), BoxState.AWAIT_NEXT, dashed = true, check = false, badge = false)
            else Plan(boxQuad(), BoxState.AWAIT_NEXT, dashed = false, check = false, badge = false)
        }
        return Plan(liveShape(), boxState, dashed = false, check = boxState == BoxState.CAPTURED, badge = false)
    }

    private fun contain(): FloatArray {
        val s = min(width.toFloat() / frameW, height.toFloat() / frameH)
        return floatArrayOf(s, (width - frameW * s) / 2f, (height - frameH * s) / 2f)
    }

    private fun toFrac(x: Float, y: Float): Pair<Float, Float> {
        val (s, ox, oy) = contain().let { Triple(it[0], it[1], it[2]) }
        return Pair((x - ox) / (frameW * s), (y - oy) / (frameH * s))
    }

    private fun fracRect(x0: Double, y0: Double, x1: Double, y1: Double): RectF {
        val c = contain()
        val s = c[0]; val ox = c[1]; val oy = c[2]
        return RectF(
            (ox + x0 * frameW * s).toFloat(), (oy + y0 * frameH * s).toFloat(),
            (ox + x1 * frameW * s).toFloat(), (oy + y1 * frameH * s).toFloat(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = dragA; val b = dragB
        if (settingArea && a != null && b != null) {
            canvas.drawRect(min(a.first, b.first), min(a.second, b.second),
                max(a.first, b.first), max(a.second, b.second), dragPaint)
        } else roi?.let { canvas.drawRect(fracRect(it.x0, it.y0, it.x1, it.y1), roiPaint) }

        val plan = plan()
        plan.shape?.let { q ->
            val c = contain()
            val s = c[0]; val ox = c[1]; val oy = c[2]
            path.rewind()
            var cx = 0f; var cy = 0f
            for (i in 0 until 4) {
                val x = (ox + q[2 * i] * frameW * s); val y = (oy + q[2 * i + 1] * frameH * s)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                cx += x / 4; cy += y / 4
            }
            path.close()
            if (plan.dashed) {
                canvas.drawPath(path, watchPaint)
            } else {
                boxPaint.color = when (plan.state) {
                    BoxState.OCCUPIED -> Color.parseColor("#6ee7a0")
                    BoxState.SETTLING -> Color.parseColor("#fbbf24")
                    BoxState.AWAIT_NEXT -> Color.parseColor("#8b93a1")
                    BoxState.CAPTURED -> Color.parseColor("#60a5fa")
                }
                boxPaint.style = Paint.Style.STROKE
                canvas.drawPath(path, boxPaint)
                if (plan.check) {
                    boxPaint.style = Paint.Style.FILL
                    boxPaint.alpha = 60
                    canvas.drawPath(path, boxPaint)
                    canvas.drawText("✓", cx, cy + checkPaint.textSize / 3, checkPaint)
                }
            }
        }

        if (plan.badge) {
            val cx = width / 2f; val cy = height / 2f; val r = 44 * density
            boxPaint.style = Paint.Style.FILL
            boxPaint.color = Color.parseColor("#60a5fa")
            boxPaint.alpha = 220
            canvas.drawCircle(cx, cy, r, boxPaint)
            canvas.drawText("✓", cx, cy + checkPaint.textSize / 3, checkPaint)
        }

        debugText?.let { t ->
            var y = 16 * density
            for (line in t.lines()) {
                canvas.drawText(line, 8 * density, y, debugPaint); y += 15 * density
            }
        }
    }

    companion object {
        /** How much longer than check_ms a hold begun at the trigger may wait for the pipeline's answer. */
        const val PIPELINE_HOLD_MS = 5000L
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!settingArea) return gestures.onTouchEvent(event)
        val p = Pair(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragA = p; dragB = p; invalidate() }
            MotionEvent.ACTION_MOVE -> { dragB = p; invalidate() }
            MotionEvent.ACTION_UP -> {
                val a = dragA
                dragB = p
                if (a != null) {
                    val fa = toFrac(a.first, a.second); val fb = toFrac(p.first, p.second)
                    // A plain tap (no drag) is also "too small" — same as phone.html.
                    val moved = abs(a.first - p.first) + abs(a.second - p.second) > 4 * density
                    val r = if (moved) RoiFrac.fromDrag(
                        fa.first.toDouble(), fa.second.toDouble(),
                        fb.first.toDouble(), fb.second.toDouble(),
                    ) else null
                    settingArea = false
                    onAreaDrawn?.invoke(r)
                }
            }
            MotionEvent.ACTION_CANCEL -> { dragA = null; dragB = null; invalidate() }
        }
        return true
    }
}
