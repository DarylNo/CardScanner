package io.github.darylno.cardscanner.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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

        // ── form screens (Setup / Settings / Share) ──
        val BG = Color.parseColor("#0F1115")
        val SURFACE = Color.parseColor("#1A1D23")
        val SURFACE_HI = Color.parseColor("#252932")
        val DIVIDER = Color.parseColor("#14FFFFFF")
        val OUTLINE = Color.parseColor("#3DFFFFFF")
        val GHOST = Color.parseColor("#0FFFFFFF")
        val ACTIVE_PRESSED = Color.parseColor("#C9CDD4")
        val ERR_BG = Color.parseColor("#2EF87171")
        val WARN_BG = Color.parseColor("#24FBBF24")
        val WARN_STROKE = Color.parseColor("#59FBBF24")
        val ERR_STROKE = Color.parseColor("#59F87171")
        val TEXT_FAINT = Color.parseColor("#7D8592")
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

    /** A chip showing only an icon ([res], tinted like text); [label] is read by TalkBack. */
    fun iconChip(res: Int, label: String, onClick: () -> Unit): TextView = chip("", onClick).apply {
        contentDescription = label
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, res)?.mutate()?.apply {
            setTint(Palette.TEXT)
            setBounds(0, 0, dp(22), dp(22))
        }
        setCompoundDrawables(d, null, null, null)
        setPadding(dp(12), 0, dp(12), 0)
        minWidth = dp(48)
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

    // ── form screens: cards, buttons, switch rows, top bar ──────────────────
    // Setup / Settings / Share share the camera screen's palette: near-black
    // page, content grouped in rounded surface cards, pill buttons.

    fun rounded(fill: Int, radiusDp: Float = 16f, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpf(radiusDp)
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun pressablePill(fill: Int, pressed: Int, stroke: Int? = null) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pill(pressed, stroke))
        addState(intArrayOf(), pill(fill, stroke))
    }

    /** A rounded surface card grouping one section's content. */
    fun card(fill: Int = Palette.SURFACE, stroke: Int? = null, padDp: Int = 16): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(fill, 16f, stroke)
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

    /** Small, spaced, dim caps label above a card. */
    fun sectionHeader(label: String): TextView = TextView(ctx).apply {
        text = label.uppercase()
        textSize = 12f
        letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.TEXT_FAINT)
        setPadding(dp(4), dp(24), dp(4), dp(8))
    }

    fun text(t: CharSequence, sizeSp: Float = 15f, color: Int = Palette.TEXT, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = t
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

    private fun pillButton(label: String, textColor: Int, bg: StateListDrawable, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(textColor)
        background = bg
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = dp(48)
        minimumWidth = dp(48)
        gravity = Gravity.CENTER
        setPadding(dp(20), 0, dp(20), 0)
        setOnClickListener { onClick() }
    }

    /** The screen's main action: filled light pill. */
    fun primaryButton(label: String, onClick: () -> Unit) =
        pillButton(label, Palette.TEXT_ON_ACTIVE, pressablePill(Palette.CHIP_ACTIVE, Palette.ACTIVE_PRESSED), onClick)

    /** Other actions: translucent, outlined pill. */
    fun secondaryButton(label: String, onClick: () -> Unit) =
        pillButton(label, Palette.TEXT, pressablePill(Palette.GHOST, Palette.PRESSED, Palette.OUTLINE), onClick)

    /** Removes / stops / forgets something: red-tinted pill. */
    fun destructiveButton(label: String, onClick: () -> Unit) =
        pillButton(label, Palette.ERR, pressablePill(Palette.ERR_BG, Color.parseColor("#55F87171"), Palette.ERR_STROKE), onClick)

    /** A compact pill (e.g. Remove on a list row): smaller text, 40dp visual, 48dp touch via padding. */
    fun smallButton(b: Button): Button = b.apply {
        textSize = 13f
        minHeight = dp(36); minimumHeight = dp(36)
        setPadding(dp(14), 0, dp(14), 0)
    }

    /** Hairline between rows inside a card. */
    fun divider(): View = View(ctx).apply { setBackgroundColor(Palette.DIVIDER) }

    fun dividerParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
        topMargin = dp(4); bottomMargin = dp(4)
    }

    /** Settings row: title + one-line dim description, palette-tinted switch on the right; the whole row toggles. */
    fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = SwitchCompat(ctx).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Palette.CHIP_ACTIVE, Color.parseColor("#9AA1AC")))
            trackTintList = ColorStateList(states, intArrayOf(Palette.OK, Color.parseColor("#4A505B")))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(8), 0, dp(8))
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16f))
                addView(text(desc, 13f, Palette.TEXT_DIM).apply { setPadding(0, dp(2), 0, 0) })
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
            addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isClickable = true
            setOnClickListener { sw.toggle() }
        }
    }

    /** A tappable setting that shows its current [value] on the right (the tap edits it). */
    fun valueRow(title: String, desc: String, value: String, onClick: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(0, dp(8), 0, dp(8))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16f))
            addView(text(desc, 13f, Palette.TEXT_DIM).apply { setPadding(0, dp(2), 0, 0) })
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        addView(text(value, 15f, Palette.TEXT, bold = true))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Top bar: round back chip + screen title. */
    fun topBar(title: String, backLabel: String, onBack: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(16), dp(8))
        addView(chip("←") { onBack() }.apply {
            contentDescription = backLabel
            textSize = 20f
            minWidth = dp(44)
            setPadding(0, 0, 0, dp(2))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(text(title, 20f, Palette.TEXT, bold = true).apply { setPadding(dp(14), 0, 0, 0) })
    }

    /** Clip a container (e.g. the camera frame) to its rounded background. */
    fun clipRounded(v: View, fill: Int, radiusDp: Float) {
        v.background = rounded(fill, radiusDp)
        v.outlineProvider = ViewOutlineProvider.BACKGROUND
        v.clipToOutline = true
    }

    /**
     * Edge-to-edge (enforced on Android 15): pad [root] by the system bars (and
     * the keyboard, so a focused field is never hidden), like MainActivity does.
     */
    fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }
}

/**
 * Four corner brackets over the pairing camera: "aim the QR in here". Also
 * paints the frame's rounded corners in [pageColor], so the frame reads as
 * rounded even where the preview surface ignores the outline clip.
 */
class ViewfinderView(ctx: Context, private val pageColor: Int, private val cornerDp: Float) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = pageColor }
    private val maskPath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cr = cornerDp * d
        maskPath.reset()
        maskPath.fillType = Path.FillType.EVEN_ODD
        maskPath.addRect(0f, 0f, w, h, Path.Direction.CW)
        maskPath.addRoundRect(0f, 0f, w, h, cr, cr, Path.Direction.CW)
        canvas.drawPath(maskPath, mask)
        val side = minOf(width, height) * 0.62f
        val l = (width - side) / 2f
        val t = (height - side) / 2f
        val r = l + side
        val b = t + side
        val arm = side * 0.16f
        val rad = 14f * d
        path.reset()
        // Each corner: arm → rounded corner → arm.
        path.moveTo(l, t + arm); path.lineTo(l, t + rad); path.quadTo(l, t, l + rad, t); path.lineTo(l + arm, t)
        path.moveTo(r - arm, t); path.lineTo(r - rad, t); path.quadTo(r, t, r, t + rad); path.lineTo(r, t + arm)
        path.moveTo(r, b - arm); path.lineTo(r, b - rad); path.quadTo(r, b, r - rad, b); path.lineTo(r - arm, b)
        path.moveTo(l + arm, b); path.lineTo(l + rad, b); path.quadTo(l, b, l, b - rad); path.lineTo(l, b - arm)
        canvas.drawPath(path, paint)
    }
}
