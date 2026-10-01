package io.github.darylno.cardscanner.update

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The update lock: a newer release (with its APK) stops scanning; nothing else does. */
@RunWith(RobolectricTestRunner::class)
class UpdateLockTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private var body: String? = null
    private var clock = 1_000_000L

    @Before fun clear() { ctx.getSharedPreferences("update_lock", Context.MODE_PRIVATE).edit().clear().commit() }

    private fun release(tag: String, apk: Boolean = true, prerelease: Boolean = false): String {
        val asset = if (apk) """{"name":"mtg-card-scanner-android.apk",
            "browser_download_url":"https://github.com/DarylNo/CardScanner/releases/download/$tag/mtg-card-scanner-android.apk"}""" else ""
        return """{"tag_name":"$tag","draft":false,"prerelease":$prerelease,"assets":[$asset]}"""
    }

    private fun lock(current: String) = UpdateLock(ctx, current, fetch = { body }, now = { clock })

    @Test fun aNewerReleaseLocksAndTheUpdateUnlocks() {
        body = release("v1.1.3")
        val old = lock("1.1.2")
        assertFalse(old.locked)
        assertTrue(old.check())
        assertTrue(old.locked)
        assertEquals("v1.1.3", old.latest?.version)
        assertTrue(old.latest!!.apkUrl.endsWith("/v1.1.3/mtg-card-scanner-android.apk"))
        // the installed update is that version: unlocked, with no check needed
        assertFalse(lock("1.1.3").locked)
    }

    @Test fun theSameOrAnOlderReleaseNeverLocks() {
        for (tag in listOf("v1.1.2", "v1.1.1", "v1.0.17")) {
            body = release(tag)
            val l = lock("1.1.2")
            l.check()
            assertFalse(tag, l.locked)
        }
    }

    @Test fun offlineChangesNothing() {
        body = null
        val l = lock("1.1.2")
        assertFalse(l.check()); assertFalse(l.locked)              // never seen a newer one: keeps scanning
        body = release("v1.2.0"); l.check(); assertTrue(l.locked)
        body = null
        assertFalse(l.check()); assertTrue(l.locked)               // seen one: stays locked offline
    }

    @Test fun noApkYetOrAPrereleaseDoesNotLock() {
        body = release("v1.1.3", apk = false)
        val l = lock("1.1.2")
        l.check(); assertFalse(l.locked); assertNull(l.latest)
        body = release("v1.1.3", prerelease = true); l.check(); assertFalse(l.locked)
        body = "not json"; l.check(); assertFalse(l.locked)
    }

    @Test fun versionsCompareAsNumbers() {
        assertTrue(UpdateLock.isNewer("v1.10.0", "1.9.9"))
        assertTrue(UpdateLock.isNewer("v2.0", "1.99.99"))
        assertTrue(UpdateLock.isNewer("v1.1.3", "1.1.2 (phone)"))
        assertFalse(UpdateLock.isNewer("v1.1.2", "1.1.2"))
        assertFalse(UpdateLock.isNewer("v1.1.2", "1.1.2.0"))
        assertFalse(UpdateLock.isNewer("art-pack", "1.1.2"))       // garbage never locks
        assertFalse(UpdateLock.isNewer("v1.1.3", ""))
    }

    @Test fun checksAreThrottledUnlessForced() {
        body = release("v1.1.2")
        var calls = 0
        val l = UpdateLock(ctx, "1.1.2", fetch = { calls++; body }, now = { clock })
        l.check()
        assertEquals(1, calls)
        l.checkAsync()                                              // within 30 min: skipped
        Thread.sleep(200)
        assertEquals(1, calls)
    }
}
