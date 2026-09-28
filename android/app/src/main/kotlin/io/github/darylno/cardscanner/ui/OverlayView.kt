package io.github.darylno.cardscanner.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import io.github.darylno.cardscanner.core.Box
import io.github.darylno.cardscanner.camera.HandheldGuide
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
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class BoxState { OCCUPIED, SETTLING, AWAIT_NEXT, CAPTURED }

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
    private var handheldGuide = false
    private var debugText: String? = null

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
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; color = Color.argb(200, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(10 * density, 8 * density), 0f)
    }
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

    fun setHandheldGuide(on: Boolean) {
        if (handheldGuide != on) { handheldGuide = on; invalidate() }
    }

    fun setDebugText(t: String?) {
        if (debugText != t) { debugText = t; invalidate() }
    }

    /** Detection box in Gray-sample coords ([sw]×[sh]); null clears it. */
    fun setBox(b: Box?, sw: Int, sh: Int, state: BoxState) {
        if (b == box && sw == sampleW && sh == sampleH && state == boxState) return
        box = b; sampleW = sw; sampleH = sh; boxState = state; invalidate()
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

        if (handheldGuide) {
            // The same guide the Handheld capture crops to (plus its pad).
            val g = HandheldGuide.frac(frameW, frameH, pad = 0.0)
            canvas.drawRoundRect(fracRect(g.x0, g.y0, g.x1, g.y1), 8 * density, 8 * density, guidePaint)
        }

        box?.let { bx ->
            val r = roi ?: RoiFrac(0.0, 0.0, 1.0, 1.0)
            val rw = r.x1 - r.x0; val rh = r.y1 - r.y0
            val rect = fracRect(
                r.x0 + rw * bx.x / sampleW, r.y0 + rh * bx.y / sampleH,
                r.x0 + rw * (bx.x + bx.w) / sampleW, r.y0 + rh * (bx.y + bx.h) / sampleH,
            )
            boxPaint.color = when (boxState) {
                BoxState.OCCUPIED -> Color.parseColor("#6ee7a0")
                BoxState.SETTLING -> Color.parseColor("#fbbf24")
                BoxState.AWAIT_NEXT -> Color.parseColor("#8b93a1")
                BoxState.CAPTURED -> Color.parseColor("#60a5fa")
            }
            boxPaint.style = Paint.Style.STROKE
            canvas.drawRect(rect, boxPaint)
            if (boxState == BoxState.CAPTURED) {
                boxPaint.style = Paint.Style.FILL
                boxPaint.alpha = 60
                canvas.drawRect(rect, boxPaint)
                canvas.drawText("✓", rect.centerX(), rect.centerY() + checkPaint.textSize / 3, checkPaint)
            }
        }

        debugText?.let { t ->
            var y = 16 * density
            for (line in t.lines()) {
                canvas.drawText(line, 8 * density, y, debugPaint); y += 15 * density
            }
        }
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
