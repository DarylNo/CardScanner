package io.github.darylno.cardscanner.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.TextView

/**
 * Drawables and widgets for the camera screen's floating controls: pills,
 * the round shutter, top/bottom scrims. Code-built (the app has no layout
 * XML) so the whole look lives here, one palette.
 */
class ScanChrome(private val ctx: Context) {
    object Palette {
        val SCRIM = Color.parseColor("#B3000000")
        val CHIP = Color.parseColor("#99202329")
        val CHIP_ACTIVE = Color.parseColor("#F2F4F7")
        val TEXT = Color.WHITE
        val TEXT_DIM = Color.parseColor("#B8BEC8")
        val TEXT_ON_ACTIVE = Color.parseColor("#111317")
        val OK = Color.parseColor("#34D399")
        val ERR = Color.parseColor("#F87171")
        val WARN = Color.parseColor("#FBBF24")
        val PRESSED = Color.parseColor("#66FFFFFF")
    }

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(v: Float) = v * ctx.resources.displayMetrics.density

    fun pill(fill: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpf(999f)
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun pressable(normal: GradientDrawable, pressedFill: Int = Palette.PRESSED) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            shape = normal.shape; cornerRadius = dpf(999f); setColor(pressedFill)
        })
        addState(intArrayOf(), normal)
    }

    /** A tappable floating pill: min 44dp tall (thumb target), text centred. */
    fun chip(label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        setTextColor(Palette.TEXT)
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = dp(44)
        minWidth = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        background = pressable(pill(Palette.CHIP))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Non-interactive status pill; the text colour carries the tone. */
    fun statusPill(): TextView = TextView(ctx).apply {
        setTextColor(Palette.TEXT)
        textSize = 15f
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = pill(Palette.CHIP)
    }

    /** The shutter: white ring, filled centre; the centre dims while pressed. */
    fun shutter(): LayerDrawable {
        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(4), Color.WHITE); setColor(Color.TRANSPARENT) }
        val core = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#B3FFFFFF")) })
            addState(intArrayOf(android.R.attr.state_enabled),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            addState(intArrayOf(),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#66FFFFFF")) })
        }
        return LayerDrawable(arrayOf(ring, core)).apply { val i = dp(8); setLayerInset(1, i, i, i, i) }
    }

    fun scrim(top: Boolean) = GradientDrawable(
        if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(Palette.SCRIM, Color.TRANSPARENT),
    )

    fun dot(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(dp(8), dp(8)) }
}
