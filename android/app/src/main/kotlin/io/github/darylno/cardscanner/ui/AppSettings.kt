package io.github.darylno.cardscanner.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.server.DeviceApi

/**
 * The app's own UI preferences (not the server config — that lives in the NET
 * agent's `PrefsConfigStore`). Every field is read fresh from
 * SharedPreferences so the Settings screen and the camera screen never hold
 * diverging copies; writes use apply() (these are conveniences, not state that
 * must survive a crash mid-write).
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)

    enum class Mode { MOUNT, HANDHELD }

    var mode: Mode
        get() = if (prefs.getString(K_MODE, null) == Mode.HANDHELD.name) Mode.HANDHELD else Mode.MOUNT
        set(v) = prefs.edit().putString(K_MODE, v.name).apply()

    /** Mount-mode auto capture (phone.html's "Auto: ON"). */
    var auto: Boolean
        get() = prefs.getBoolean(K_AUTO, true)
        set(v) = prefs.edit().putBoolean(K_AUTO, v).apply()

    /** The user-drawn scan Area; null = full frame. */
    var roi: RoiFrac?
        get() = RoiFrac.parse(prefs.getString(K_ROI, null))
        set(v) {
            if (v == null) prefs.edit().remove(K_ROI).apply()
            else prefs.edit().putString(K_ROI, v.encode()).apply()
        }

    /** Analysis resolution: false = Standard 1600×1200, true = High 2048×1536. */
    var highRes: Boolean
        get() = prefs.getBoolean(K_HIGH_RES, false)
        set(v) = prefs.edit().putBoolean(K_HIGH_RES, v).apply()

    /** Mount mode: lock focus once a card is present (default on — the tray never moves). */
    var focusLock: Boolean
        get() = prefs.getBoolean(K_FOCUS_LOCK, true)
        set(v) = prefs.edit().putBoolean(K_FOCUS_LOCK, v).apply()

    /** AE + AWB lock (default OFF — spec). */
    var aeLock: Boolean
        get() = prefs.getBoolean(K_AE_LOCK, false)
        set(v) = prefs.edit().putBoolean(K_AE_LOCK, v).apply()

    var torch: Boolean
        get() = prefs.getBoolean(K_TORCH, false)
        set(v) = prefs.edit().putBoolean(K_TORCH, v).apply()

    /** Capture / "next card" / "same card" buzzes. On by default. */
    var vibration: Boolean
        get() = prefs.getBoolean(K_VIBRATION, true)
        set(v) = prefs.edit().putBoolean(K_VIBRATION, v).apply()

    /** How long the blue ✓ stays up after each scan (ms), clamped to DeviceApi's range. */
    var checkMs: Int
        get() = prefs.getInt(K_CHECK_MS, DeviceApi.CHECK_MS_DEFAULT).coerceIn(DeviceApi.CHECK_MS_MIN, DeviceApi.CHECK_MS_MAX)
        set(v) = prefs.edit().putInt(K_CHECK_MS, v.coerceIn(DeviceApi.CHECK_MS_MIN, DeviceApi.CHECK_MS_MAX)).apply()

    /** The scan screen asked for the notification permission once (the server's Stop lives there). */
    var notifAsked: Boolean
        get() = prefs.getBoolean(K_NOTIF_ASKED, false)
        set(v) = prefs.edit().putBoolean(K_NOTIF_ASKED, v).apply()

    var debugOverlay: Boolean
        get() = prefs.getBoolean(K_DEBUG, false)
        set(v) = prefs.edit().putBoolean(K_DEBUG, v).apply()

    /** Last few capture timing lines, newest first (Diagnostics). */
    var recentTimings: String
        get() = prefs.getString(K_TIMINGS, "") ?: ""
        set(v) = prefs.edit().putString(K_TIMINGS, v).apply()

    fun addTiming(line: String) {
        recentTimings = (listOf(line) + recentTimings.lines().filter { it.isNotBlank() })
            .take(20).joinToString("\n")
    }

    private companion object {
        const val K_MODE = "mode"
        const val K_AUTO = "auto"
        const val K_ROI = "roi"
        const val K_HIGH_RES = "high_res"
        const val K_FOCUS_LOCK = "focus_lock"
        const val K_AE_LOCK = "ae_lock"
        const val K_TORCH = "torch"
        const val K_VIBRATION = "vibration"
        const val K_NOTIF_ASKED = "notif_asked"
        const val K_CHECK_MS = "check_ms"
        const val K_DEBUG = "debug_overlay"
        const val K_TIMINGS = "recent_timings"
    }
}
