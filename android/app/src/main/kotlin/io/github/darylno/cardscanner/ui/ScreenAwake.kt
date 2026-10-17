package io.github.darylno.cardscanner.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.ScreenDim
import kotlin.math.roundToInt

/**
 * Keeps the phone awake on an app screen and dims it when idle (core [ScreenDim];
 * owner, 2026-10-17: "stop the phone from going fully asleep. Allow some dimming?").
 * One per activity, as a lifecycle observer. While the screen is resumed the display
 * is kept on (FLAG_KEEP_SCREEN_ON — before 1.1.15 only the scan screen did that, so
 * the phone slept on the review, Settings and Share screens, which stops the camera
 * and, after 30 min, the phone server). After Settings → Screen → "Dim when idle"
 * with no activity the WINDOW's brightness drops to [ScreenDim.level]; [poke] — the
 * activity's onUserInteraction, a scan on the scan screen — gives the phone its own
 * brightness back. Leaving the screen restores it as well.
 *
 * Display only: the camera, the analyzer and the phone server never hear of it.
 */
class ScreenAwake(
    private val activity: Activity,
    private val settings: AppSettings,
    private val where: String,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var dim = ScreenDim(0)
    private var resumed = false
    private val timer = Runnable { tick() }

    /** True while this screen is held at the dim level. */
    val dimmed: Boolean get() = dim.dimmed

    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dim = ScreenDim(settings.dimAfterS * 1000L)     // a changed setting applies on return
        dim.activity(clock())
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        schedule()
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        handler.removeCallbacks(timer)
        dim.activity(clock())
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    /** Activity — a touch, a scan: brightens a dimmed screen and restarts the idle clock. Any thread. */
    fun poke(why: String) {
        if (Looper.myLooper() !== Looper.getMainLooper()) { handler.post { poke(why) }; return }
        if (!resumed) return
        if (dim.activity(clock())) {
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            DebugLog.global.i("screen", "bright again — $why ($where)")
            schedule()
        }
        // Not dimmed: the pending timer finds the idle clock restarted and waits again.
    }

    private fun tick() {
        if (!resumed) return
        if (dim.tick(clock())) {
            val level = ScreenDim.level(systemBrightness())
            setBrightness(level)
            DebugLog.global.i("screen", "dimmed to ${(level * 100).roundToInt()} % — " +
                "${ScreenDim.label((dim.afterMs / 1000).toInt())} with no touch or scan ($where)")
        } else schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(timer)
        val due = dim.dueAt() ?: return
        handler.postDelayed(timer, (due - clock()).coerceAtLeast(0))
    }

    /**
     * The phone's own brightness, 0..1, when it reports one in the standard 0..255 range
     * (some phones use a wider range; [ScreenDim.level] then falls back to its fixed level).
     */
    private fun systemBrightness(): Float? = runCatching {
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrNull()

    private fun setBrightness(v: Float) {
        val w = activity.window
        val lp = w.attributes
        if (lp.screenBrightness == v) return
        lp.screenBrightness = v
        w.attributes = lp
    }
}
