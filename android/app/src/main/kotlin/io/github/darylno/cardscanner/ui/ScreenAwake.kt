package io.github.darylno.cardscanner.ui

import android.app.Activity
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.ScreenDim
import io.github.darylno.cardscanner.gateway.GatewayService
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
 * Only while the screen has window FOCUS (review of 1.1.15): a dialog on top (the
 * pairing QR and code, a text field, the review page's confirm()), the notification
 * shade or the other half of a split screen takes it, and touches there never reach
 * the activity's onUserInteraction — so losing focus brightens and stops the idle
 * clock ([focus]), and regaining it starts the clock again.
 *
 * Every kept-awake screen also counts as a visible app screen for the phone server's
 * idle stop (GatewayService.screenVisible) — before, Share, Settings and the network
 * test did not, so the server stopped after 30 min under a Share screen still showing
 * its code.
 *
 * Display only: the camera, the analyzer and the phone server's work never hear of it.
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
    private var hasFocus = true
    private var skipLogged = false
    private val timer = Runnable { tick() }

    /** True while this screen is held at the dim level. */
    val dimmed: Boolean get() = dim.dimmed

    override fun onStart(owner: LifecycleOwner) = GatewayService.screenVisible(true)

    override fun onStop(owner: LifecycleOwner) = GatewayService.screenVisible(false)

    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        skipLogged = false
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        restart()
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        handler.removeCallbacks(timer)
        dim.activity(clock())
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    /** "Dim when idle" changed on this very screen: apply it now, not on the next visit. */
    fun reload() { if (resumed) restart() }

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

    /** The activity's onWindowFocusChanged: no dimming while a dialog / the shade / another window has focus. */
    fun focus(has: Boolean) {
        hasFocus = has
        if (!resumed) return
        if (has) {
            dim.activity(clock())
            schedule()
        } else {
            handler.removeCallbacks(timer)
            if (dim.activity(clock())) {
                setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                DebugLog.global.i("screen", "bright again — a dialog or another window is on top ($where)")
            }
        }
    }

    private fun restart() {
        dim = ScreenDim(settings.dimAfterS * 1000L)
        dim.activity(clock())
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        schedule()
    }

    private fun tick() {
        if (!resumed || !hasFocus) return              // focus(true) starts the clock again
        if (!dim.tick(clock())) { schedule(); return }
        val setting = brightnessSetting()
        val max = declaredMax()
        val level = ScreenDim.level(ScreenDim.ownBrightness(setting, max))
        val raw = "phone brightness ${setting ?: "?"}/${max ?: "?"}"
        if (level == null) {
            // Already about as dark as it goes: leave it, and look again after the next idle spell.
            dim.activity(clock())
            schedule()
            if (!skipLogged) {
                skipLogged = true
                DebugLog.global.i("screen", "not dimming — the screen is already that dark ($raw, $where)")
            }
            return
        }
        setBrightness(level)
        DebugLog.global.i("screen", "dimmed to ${(level * 100).roundToInt()} % — " +
            "${ScreenDim.label((dim.afterMs / 1000).toInt())} with no touch or scan ($raw, $where)")
    }

    private fun schedule() {
        handler.removeCallbacks(timer)
        val due = dim.dueAt() ?: return
        handler.postDelayed(timer, (due - clock()).coerceAtLeast(0))
    }

    private fun brightnessSetting(): Int? = runCatching {
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull()

    private fun setBrightness(v: Float) {
        val w = activity.window
        val lp = w.attributes
        if (lp.screenBrightness == v) return
        lp.screenBrightness = v
        w.attributes = lp
    }

    companion object {
        /**
         * The brightness range this phone declares for SCREEN_BRIGHTNESS — the framework's
         * config_screenBrightnessSettingMaximum (255 in AOSP; OEMs with a wider range set it).
         * Null when the phone doesn't expose it.
         */
        fun declaredMax(): Int? = runCatching {
            val r = Resources.getSystem()
            val id = r.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
            if (id == 0) null else r.getInteger(id).takeIf { it > 0 }
        }.getOrNull()
    }
}
