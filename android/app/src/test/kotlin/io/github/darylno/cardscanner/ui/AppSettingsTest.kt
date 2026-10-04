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

    /** "Zoom to fit the Area" (1.1.11) ships OFF; it persists; a stored Area is untouched by it (base fractions, no migration). */
    @Test fun zoomToFitIsOffByDefaultAndTheAreaStaysAsStored() {
        prefs().edit().clear().putString("roi", "0.2,0.25,0.8,0.75").commit()
        val s = AppSettings(ctx)
        assertEquals(false, s.zoomFit)
        s.zoomFit = true
        assertEquals(true, AppSettings(ctx).zoomFit)
        assertEquals(io.github.darylno.cardscanner.core.RoiFrac(0.2, 0.25, 0.8, 0.75), AppSettings(ctx).roi)
        assertEquals("0.2,0.25,0.8,0.75", prefs().getString("roi", null))
    }
}
