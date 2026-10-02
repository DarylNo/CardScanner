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

    init {
        // 1.1.2 (owner): Handheld is gone — it is Mount with Auto off now. A phone that
        // was left in Handheld comes back with Auto off, the closest thing it knew.
        if (prefs.getString(K_MODE, null) == "HANDHELD") prefs.edit().putBoolean(K_AUTO, false).remove(K_MODE).apply()
        else if (prefs.contains(K_MODE)) prefs.edit().remove(K_MODE).apply()
    }

    /** Auto capture (phone.html's "Auto: ON"). Off = tap the shutter; each scan then opens. */
    var auto: Boolean
        get() = prefs.getBoolean(K_AUTO, false)   // a fresh install starts in Tap to scan (owner)
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

    /**
     * The camera that scans: a Camera2 id, or null = Automatic (core CameraChoice:
     * camera 0 facing back, else the first back camera). For testers on other phones.
     */
    var cameraId: String?
        get() = prefs.getString(K_CAMERA, null)
        set(v) { if (v == null) prefs.edit().remove(K_CAMERA).apply() else prefs.edit().putString(K_CAMERA, v).apply() }

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

    // ── the guided measurement session (MeasureSession): survives leaving the screen ──
    /** The current step, −1 = no session. */
    var measureStep: Int
        get() = prefs.getInt(K_MEASURE_STEP, -1)
        set(v) = prefs.edit().putInt(K_MEASURE_STEP, v).apply()
    var measureCaptures: Int
        get() = prefs.getInt(K_MEASURE_CAPTURES, 0)
        set(v) = prefs.edit().putInt(K_MEASURE_CAPTURES, v).apply()
    var measureStepStartedAt: Long
        get() = prefs.getLong(K_MEASURE_STARTED, 0L)
        set(v) = prefs.edit().putLong(K_MEASURE_STARTED, v).apply()
    /** The step's Yes/No answer (null = none yet). */
    var measureAnswer: Boolean?
        get() = if (prefs.contains(K_MEASURE_ANSWER)) prefs.getBoolean(K_MEASURE_ANSWER, false) else null
        set(v) { if (v == null) prefs.edit().remove(K_MEASURE_ANSWER).apply() else prefs.edit().putBoolean(K_MEASURE_ANSWER, v).apply() }
    /** The owner's own "aeLock,vibration,checkMs" to restore when the session ends. */
    var measureOriginal: String?
        get() = prefs.getString(K_MEASURE_ORIG, null)
        set(v) { if (v == null) prefs.edit().remove(K_MEASURE_ORIG).apply() else prefs.edit().putString(K_MEASURE_ORIG, v).apply() }

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
        const val K_CAMERA = "camera_id"
        const val K_FOCUS_LOCK = "focus_lock"
        const val K_AE_LOCK = "ae_lock"
        const val K_TORCH = "torch"
        const val K_VIBRATION = "vibration"
        const val K_NOTIF_ASKED = "notif_asked"
        const val K_CHECK_MS = "check_ms"
        const val K_DEBUG = "debug_overlay"
        const val K_MEASURE_STEP = "measure_step"
        const val K_MEASURE_CAPTURES = "measure_captures"
        const val K_MEASURE_STARTED = "measure_started"
        const val K_MEASURE_ANSWER = "measure_answer"
        const val K_MEASURE_ORIG = "measure_orig"
        const val K_TIMINGS = "recent_timings"
    }
}
