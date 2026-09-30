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
}
