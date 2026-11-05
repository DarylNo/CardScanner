package io.github.darylno.cardscanner.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 1.1.2: Handheld is gone — a phone left in it comes back as Mount with Auto off. */
@RunWith(RobolectricTestRunner::class)
class AppSettingsTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private fun prefs() = ctx.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)

    @Test fun aPhoneLeftInHandheldComesBackWithAutoOff() {
        prefs().edit().clear().putString("mode", "HANDHELD").putBoolean("auto", true).commit()
        val s = AppSettings(ctx)
        assertEquals(false, s.auto)
        assertFalse(prefs().contains("mode"))
    }

    @Test fun aMountPhoneKeepsItsAuto() {
        prefs().edit().clear().putString("mode", "MOUNT").putBoolean("auto", true).commit()
        assertEquals(true, AppSettings(ctx).auto)
        assertFalse(prefs().contains("mode"))
    }

    @Test fun theMigrationRunsOnce() {
        prefs().edit().clear().putString("mode", "HANDHELD").commit()
        val s = AppSettings(ctx)
        s.auto = true                                   // the owner turns Auto back on…
        assertEquals(true, AppSettings(ctx).auto)       // …and a later start leaves it on
    }

    /** "Refocus on a card" (1.1.18): every 5 min by default (provisional); only the offered choices are kept. */
    @Test fun refocusOnACardDefaultsTo5MinAndKeepsOnlyItsChoices() {
        prefs().edit().clear().commit()
        val s = AppSettings(ctx)
        assertEquals(5, s.refocusMin)
        for (m in listOf(0, 2, 10, 30, -1)) { s.refocusMin = m; assertEquals(m, AppSettings(ctx).refocusMin) }
        s.refocusMin = 7
        assertEquals("not offered → the default", 5, s.refocusMin)
        prefs().edit().putInt("refocus_min", 99).commit()
        assertEquals("a stored value that is not offered reads as the default", 5, AppSettings(ctx).refocusMin)
    }

    /**
     * Settings → "Guests get full access" (owner, 2026-11-05: "Any guest to the web site to pick
     * cards has full admin access"): ON by default — an install that never touched it reads on —
     * and an off persists.
     */
    @Test fun guestsGetFullAccessIsOnByDefaultAndPersists() {
        prefs().edit().clear().commit()
        val s = AppSettings(ctx)
        assertEquals(true, s.guestsFullAccess)
        s.guestsFullAccess = false
        assertEquals(false, AppSettings(ctx).guestsFullAccess)
        assertEquals(false, prefs().getBoolean("guests_full_access", true))
        s.guestsFullAccess = true
        assertEquals(true, AppSettings(ctx).guestsFullAccess)
    }

    /** "Zoom to fit the Area" (1.1.14) ships OFF; it persists; a stored Area is untouched by it (base fractions, no migration). */
    @Test fun zoomToFitIsOffByDefaultAndTheAreaStaysAsStored() {
        prefs().edit().clear().putString("roi", "0.2,0.25,0.8,0.75").commit()
        val s = AppSettings(ctx)
        assertEquals(false, s.zoomFit)
        s.zoomFit = true
        assertEquals(true, AppSettings(ctx).zoomFit)
        assertEquals(io.github.darylno.cardscanner.core.RoiFrac(0.2, 0.25, 0.8, 0.75), AppSettings(ctx).roi)
        assertEquals("0.2,0.25,0.8,0.75", prefs().getString("roi", null))
    }

    /** "Dim when idle" (1.1.15): a minute by default; Never (0) persists; a value not offered reads as the default. */
    @Test fun dimWhenIdleDefaultsToAMinute() {
        prefs().edit().clear().commit()
        val s = AppSettings(ctx)
        assertEquals(60, s.dimAfterS)
        s.dimAfterS = 0
        assertEquals(0, AppSettings(ctx).dimAfterS)
        prefs().edit().putInt("dim_after_s", 45).commit()
        assertEquals(60, AppSettings(ctx).dimAfterS)
    }
}
