package io.github.darylno.cardscanner.ui

import android.app.Activity
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.MainActivity
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.ScreenDim
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/**
 * "Keep the phone awake, dim it when idle" (owner, 2026-10-17; 1.1.15): every app screen
 * keeps the display on, dims after Settings → Screen → "Dim when idle" with no touch (or,
 * on the scan screen, no scan), and the next touch gives the phone its own brightness back.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenAwakeTest {
    private lateinit var app: App
    private val controllers = mutableListOf<ActivityController<*>>()
    private val NONE = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.settings.dimAfterS = ScreenDim.DEFAULT_S
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
    }

    @After
    fun tearDown() {
        controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
    }

    private fun <A : Activity> launch(cls: Class<A>): Pair<ActivityController<A>, A> {
        val c = Robolectric.buildActivity(cls)
        controllers += c
        c.setup()
        ShadowLooper.idleMainLooper()
        return c to c.get()
    }

    private fun Activity.keptOn() = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    private fun Activity.brightness() = window.attributes.screenBrightness
    private fun idle(s: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(s))
    private fun screenLog(after: Long) = DebugLog.global.since(after).filter { it.tag == "screen" }.map { it.msg }

    /** Every app screen keeps the phone awake — before 1.1.15 only the scan screen did. */
    @Test
    fun everyAppScreen_keepsThePhoneAwake() {
        for (cls in listOf(MainActivity::class.java, PanelActivity::class.java, SettingsActivity::class.java,
                           ShareActivity::class.java, DiagnosticsActivity::class.java)) {
            val (_, a) = launch(cls)
            assertTrue("${cls.simpleName} keeps the screen on", a.keptOn())
            assertEquals("${cls.simpleName} starts at the phone's own brightness", NONE, a.brightness(), 0f)
        }
    }

    @Test
    fun dimsAfterTheIdleTime_aTouchBringsItBack_andTheClockRestarts() {
        val (_, a) = launch(SettingsActivity::class.java)
        val t0 = DebugLog.global.lastSeq
        idle(59)
        assertEquals("not yet at 59 s", NONE, a.brightness(), 0f)
        idle(2)
        assertEquals("dim after a minute with no touch", ScreenDim.LEVEL, a.brightness(), 1e-6f)
        assertTrue("still kept on while dim", a.keptOn())
        assertTrue(a.awake.dimmed)
        a.onUserInteraction()
        assertEquals("a touch gives the phone its own brightness back", NONE, a.brightness(), 0f)
        idle(59)
        assertEquals("the idle clock restarted at the touch", NONE, a.brightness(), 0f)
        idle(2)
        assertEquals(ScreenDim.LEVEL, a.brightness(), 1e-6f)
        val log = screenLog(t0)
        assertEquals(log.toString(), 3, log.size)
        assertTrue(log[0], log[0].startsWith("dimmed to 10 % — 1 min with no touch or scan (Settings)"))
        assertTrue(log[1], log[1].startsWith("bright again — touch (Settings)"))
    }

    @Test
    fun touchesWhileBright_keepPushingTheDimBack() {
        val (_, a) = launch(PanelActivity::class.java)
        repeat(5) { idle(50); a.onUserInteraction() }          // 250 s, never 60 s idle
        assertEquals(NONE, a.brightness(), 0f)
        idle(61)
        assertEquals(ScreenDim.LEVEL, a.brightness(), 1e-6f)
    }

    @Test
    fun never_keepsThePhonesBrightness() {
        app.settings.dimAfterS = 0
        val (_, a) = launch(ShareActivity::class.java)
        idle(3600)
        assertTrue(a.keptOn())
        assertEquals(NONE, a.brightness(), 0f)
        assertFalse(a.awake.dimmed)
    }

    @Test
    fun theDimLevel_isNeverBrighterThanHalfThePhonesOwn() {
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 20)   // ≈ 8 %
        val (_, a) = launch(SettingsActivity::class.java)
        idle(61)
        assertEquals(20 / 255f / 2f, a.brightness(), 1e-6f)
    }

    @Test
    fun leavingTheScreen_restoresTheBrightness_andAChangedSettingAppliesOnReturn() {
        val (c, a) = launch(SettingsActivity::class.java)
        idle(61)
        assertEquals(ScreenDim.LEVEL, a.brightness(), 1e-6f)
        c.pause()
        assertEquals("paused: the phone's own brightness", NONE, a.brightness(), 0f)
        app.settings.dimAfterS = 30
        idle(600)
        assertEquals("no dimming while paused", NONE, a.brightness(), 0f)
        c.resume(); ShadowLooper.idleMainLooper()
        idle(29)
        assertEquals(NONE, a.brightness(), 0f)
        idle(2)
        assertEquals("the new 30 s applies on return", ScreenDim.LEVEL, a.brightness(), 1e-6f)
    }

    /** The scan screen: a scan is activity too, so a dimmed screen lights up for the ✓. */
    @Test
    fun scanScreen_aScanBrightensIt() {
        val (_, a) = launch(MainActivity::class.java)
        idle(61)
        assertEquals(ScreenDim.LEVEL, a.brightness(), 1e-6f)
        a.awake.poke("scan")        // what onCaptureStarted does (from the analyzer's thread in the app)
        assertEquals(NONE, a.brightness(), 0f)
        idle(61)
        val worker = Thread { a.awake.poke("scan") }.apply { start(); join() }
        ShadowLooper.idleMainLooper()
        assertFalse(worker.isAlive)
        assertEquals("a poke from another thread is applied on the UI thread", NONE, a.brightness(), 0f)
    }
}
