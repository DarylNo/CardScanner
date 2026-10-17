package io.github.darylno.cardscanner.core

/**
 * "Keep the phone awake, dim it when idle" (owner, 2026-10-17: "stop the phone from
 * going fully asleep. Allow some dimming?"). Every app screen keeps the display on;
 * after [afterMs] with no activity — a touch anywhere, or a card being scanned on the
 * scan screen — the window's own brightness drops to [level], and the next activity
 * gives the phone its own brightness back. Pure: the app's `ScreenAwake` feeds it
 * clock times and applies its answers to the window.
 *
 * [afterMs] ≤ 0 = never dim (the screen stays on at the phone's own brightness).
 */
class ScreenDim(val afterMs: Long) {
    /** True while the window is held at the dim level. */
    var dimmed = false
        private set
    private var lastActivityMs = 0L

    /** Activity at [nowMs] (a touch, a scan, the screen opening). True when this brightens a dimmed screen. */
    fun activity(nowMs: Long): Boolean {
        lastActivityMs = nowMs
        val was = dimmed
        dimmed = false
        return was
    }

    /** When the screen is next due to dim; null = never (dimming off, or already dim). */
    fun dueAt(): Long? = if (afterMs <= 0 || dimmed) null else lastActivityMs + afterMs

    /** A timer fired at [nowMs]. True when the screen dims now (idle for at least [afterMs]). */
    fun tick(nowMs: Long): Boolean {
        val due = dueAt() ?: return false
        if (nowMs < due) return false
        dimmed = true
        return true
    }

    companion object {
        /** Settings → Screen → "Dim when idle", in the order offered; 0 = never dim. */
        val CHOICES_S = intArrayOf(30, 60, 120, 300, 0)
        /** A minute with no touch and no scan (the owner asked for "some dimming"). */
        const val DEFAULT_S = 60
        /** The dim level, as a window brightness (0..1 of the panel's range). */
        const val LEVEL = 0.1f
        /** Never all the way down: 0 reads as "backlight off" on some panels. */
        const val MIN_LEVEL = 0.01f

        /** A stored choice that is not offered (an older or hand-edited value) reads as the default. */
        fun normalize(s: Int): Int = if (s in CHOICES_S) s else DEFAULT_S

        /** The widest brightness range phones are known to use for SCREEN_BRIGHTNESS (some OnePlus / Xiaomi). */
        const val WIDEST_MAX = 4095

        /**
         * The phone's own brightness as 0..1: its SCREEN_BRIGHTNESS [setting] over the range it
         * declares ([declaredMax], the framework's config_screenBrightnessSettingMaximum — 255 on
         * most phones, 1023–4095 on some). A reading above the declared range is read against
         * [WIDEST_MAX]: dimmer than meant on such a phone, never brighter. Null when unknown.
         */
        fun ownBrightness(setting: Int?, declaredMax: Int?): Float? {
            val v = setting?.takeIf { it >= 0 } ?: return null
            val max = (declaredMax?.takeIf { it > 0 } ?: 255).let { if (v > it) WIDEST_MAX else it }
            return (v.toFloat() / max).coerceIn(0f, 1f)
        }

        /**
         * The brightness to dim to: [LEVEL], but at most half of the phone's own brightness
         * ([own], 0..1, from [ownBrightness]) so dimming never brightens a screen the owner
         * already turned down. Null = don't dim: half the phone's own would be under
         * [MIN_LEVEL], i.e. the screen is already about as dark as it goes.
         */
        fun level(own: Float?): Float? {
            val cap = own?.takeIf { it in 0f..1f }?.let { it / 2f } ?: return LEVEL
            if (cap < MIN_LEVEL) return null
            return minOf(LEVEL, cap)
        }

        /** "30 s", "1 min", "5 min"; "never" for 0. */
        fun label(s: Int): String = when {
            s <= 0 -> "never"
            s < 60 -> "$s s"
            s % 60 == 0 -> "${s / 60} min"
            else -> "${s / 60} min ${s % 60} s"
        }
    }
}
