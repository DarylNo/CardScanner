package io.github.darylno.cardscanner

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.github.darylno.cardscanner.gateway.GatewayService
import io.github.darylno.cardscanner.ui.DiagnosticsActivity
import io.github.darylno.cardscanner.ui.PanelActivity
import io.github.darylno.cardscanner.ui.SettingsActivity
import io.github.darylno.cardscanner.ui.ShareActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper

/**
 * First-launch smoke tests: every Activity in the manifest is built through
 * create → start → resume on Robolectric and must not throw. The camera can't
 * bind here (no CameraX HAL) — the controller may report an error, but the
 * screen must survive it. Uses the REAL [App] (OpenCVLoader.initLocal() just
 * returns false without the native lib, which App logs and tolerates).
 */
@RunWith(RobolectricTestRunner::class)
class ScreensSmokeTest {
    private lateinit var app: App
    private val controllers = mutableListOf<ActivityController<*>>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // Destroy what we built so camera/upload listeners don't leak into the next test.
        controllers.asReversed().forEach { c ->
            runCatching { c.pause().stop().destroy() }
        }
    }

    private fun <A : Activity> launch(cls: Class<A>, intent: Intent? = null): A {
        val c = if (intent == null) Robolectric.buildActivity(cls) else Robolectric.buildActivity(cls, intent)
        controllers += c
        c.setup()
        ShadowLooper.idleMainLooper()
        return c.get()
    }

    /** None of these screens finishes itself on create/resume by design — resumed and not finishing. */
    private fun assertResumed(a: Activity) {
        assertFalse("${a.javaClass.simpleName} finished itself", a.isFinishing)
        assertTrue("${a.javaClass.simpleName} did not reach RESUMED",
            (a as LifecycleOwner).lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    private fun grantCamera(granted: Boolean) {
        val sa = shadowOf(app)
        if (granted) sa.grantPermissions(Manifest.permission.CAMERA) else sa.denyPermissions(Manifest.permission.CAMERA)
    }

    private fun nextStarted(): Intent? = shadowOf(app).nextStartedActivity

    // ── MainActivity ────────────────────────────────────────────────────────

    /** Stage 4: nothing to pair — the first launch scans, and the phone starts serving. */
    @Test
    fun mainActivity_firstLaunch_scansAndStartsTheServer() {
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        assertResumed(a)
        // Only the one-time notification permission prompt (the server's Stop lives there) — no screen of ours.
        val started = nextStarted()
        assertTrue("no other screen opens on first launch: $started",
            started == null || started.action == "android.content.pm.action.REQUEST_PERMISSIONS")
        assertEquals(null, nextStarted())
        val svc = shadowOf(app).nextStartedService
        assertNotNull("the phone's server must be started", svc)
        assertEquals(GatewayService::class.java.name, svc!!.component?.className)
    }

    /** Owner, 2026-10-02: every button on the scan screen is at the bottom (thumb reach); the top only reads. */
    @Test
    fun mainActivity_buttonsAreAtTheBottom() {
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        val top = a.window.decorView.findViewWithTag<View>(TAG_TOP_BAR)
        val bottom = a.window.decorView.findViewWithTag<View>(TAG_BOTTOM_BAR)
        assertNotNull(top); assertNotNull(bottom)
        for (label in listOf(a.getString(R.string.scans), a.getString(R.string.share), "⚙",
                a.getString(R.string.mode_tray), a.getString(R.string.mode_tap))) {
            assertNotNull("$label is in the bottom bar", findText(bottom, label))
            assertEquals("$label is not in the top bar", null, findText(top, label))
        }
        fun clickables(v: View): Int = (if (v.isClickable && v !is ViewGroup) 1 else 0) +
            (if (v is ViewGroup) (0 until v.childCount).sumOf { clickables(v.getChildAt(it)) } else 0)
        // Only the upload indicator (tap = retry now, shown while scans are queued) stays up there.
        assertTrue("no buttons left in the top bar", clickables(top) <= 1)
        while (nextStarted() != null) { }
        findText(bottom, a.getString(R.string.share))!!.performClick()
        assertEquals(io.github.darylno.cardscanner.ui.ShareActivity::class.java.name, nextStarted()?.component?.className)
        findText(bottom, "⚙")!!.performClick()
        assertEquals(SettingsActivity::class.java.name, nextStarted()?.component?.className)
    }

    /** The update lock: a newer release is out → "Update required" covers the scan screen. */
    @Test
    fun mainActivity_newerReleaseOut_stopsScanning() {
        grantCamera(true)
        app.getSharedPreferences("update_lock", android.content.Context.MODE_PRIVATE).edit()
            .putString("version", "v99.0.0")
            .putString("apk_url", "https://github.com/DarylNo/CardScanner/releases/download/v99.0.0/mtg-card-scanner-android.apk")
            .putLong("checked_at", System.currentTimeMillis()).commit()
        try {
            val a = launch(MainActivity::class.java)
            assertResumed(a)
            val root = (a as android.app.Activity).window.decorView
            assertTrue("the lock is shown", root.findViewWithText("Update required") != null)
            assertTrue("Download update offered", root.findViewWithText("Download update") != null)
        } finally {
            app.getSharedPreferences("update_lock", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    /**
     * Swipe-to-delete: a scan swiped away on the review page is deleted even when the
     * screen closes inside its 5 s Undo window (the WebView dies with its timers) —
     * the owner saw "deleted" scans come back.
     */
    @Test
    fun panelActivity_leavingInsideTheUndoWindow_stillDeletes() {
        val filed = app.phoneServer.backend.file(mapOf("identified" to true, "card_read" to mapOf("name" to "Opt"),
            "confidence" to mapOf("name" to "high"), "candidates" to listOf(mapOf("id" to "o1", "name" to "Opt",
                "set" to "dom", "collector_number" to "60"))), null)
        val id = (filed["id"] as Number).toLong()
        val kept = (app.phoneServer.backend.file(mapOf("identified" to true, "card_read" to mapOf("name" to "Shock"),
            "confidence" to mapOf("name" to "high"), "candidates" to listOf(mapOf("id" to "s1", "name" to "Shock",
                "set" to "m19", "collector_number" to "156"))), null)["id"] as Number).toLong()
        val c = Robolectric.buildActivity(PanelActivity::class.java, PanelActivity.intent(app))
        controllers += c
        c.setup(); ShadowLooper.idleMainLooper()
        c.get().pendingDeletes = listOf(id)                  // what the page reported after the swipe
        c.pause()                                             // Back / Home before Undo ran out
        val deadline = System.currentTimeMillis() + 5_000
        while (app.phoneServer.store.get(id) != null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(null, app.phoneServer.store.get(id))
        assertNotNull("only the swiped scan goes", app.phoneServer.store.get(kept))
        assertTrue(c.get().pendingDeletes.isEmpty())
    }

    @Test
    fun panelActivity_parsesThePagesPendingIds() {
        assertEquals(listOf(3L, 7L), PanelActivity.parseIds("[3,7,7,-1,0]"))
        assertEquals(emptyList<Long>(), PanelActivity.parseIds("[]"))
        assertEquals(emptyList<Long>(), PanelActivity.parseIds("not json"))
    }

    @Test
    fun mainActivity_cameraDenied_resumes() {
        grantCamera(false)
        val a = launch(MainActivity::class.java)
        assertResumed(a)
    }

    @Test
    fun mainActivity_survivesPauseResumeCycle() {
        grantCamera(true)
        val c = Robolectric.buildActivity(MainActivity::class.java)
        controllers += c
        c.setup()
        ShadowLooper.idleMainLooper()
        c.pause().stop()
        ShadowLooper.idleMainLooper()
        c.restart().start().resume()
        ShadowLooper.idleMainLooper()
        assertResumed(c.get())
    }

    // ── ui.* activities ─────────────────────────────────────────────────────

    @Test
    fun panelActivity_forAScan_resumesAndStartsTheServer() {
        val a = launch(PanelActivity::class.java, PanelActivity.intent(app, detail = 12))
        assertResumed(a)
        assertEquals(GatewayService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        // 1.1.2: the scan opens plainly — no price-check Keep/Discard any more
        assertEquals("http://127.0.0.1:8090/phone?panel=1&detail=12",
            PanelActivity.buildUrl(app.phoneServer.localBase(), 12))
    }

    @Test
    fun panelActivity_withoutExtras_doesNotCrash() {
        val a = launch(PanelActivity::class.java)
        assertResumed(a)
    }

    @Test
    fun shareActivity_resumes() {
        val a = launch(ShareActivity::class.java)
        assertResumed(a)
    }

    /** Settings → Delete all scans: the count is shown, the dialog says it, and OK deletes every scan and photo. */
    @Test
    fun settings_deleteAllScans_asksThenDeletesEverything() {
        for (name in listOf("Opt", "Shock")) app.phoneServer.backend.file(mapOf("identified" to true,
            "card_read" to mapOf("name" to name), "confidence" to mapOf("name" to "high"),
            "candidates" to listOf(mapOf("id" to name.lowercase(), "name" to name, "set" to "dom", "collector_number" to "1"))), null)
        assertEquals(2, app.phoneServer.store.count())
        val a = launch(SettingsActivity::class.java)
        val row = findText(a.window.decorView, a.getString(R.string.settings_delete_all))
        assertNotNull(row)
        assertNotNull("the row shows how many would go", findText(a.window.decorView, "2 scans"))
        // The row's click lands on its container (ScanChrome.valueRow): climb to the clickable parent.
        var v: View? = row
        while (v != null && !v.isClickable) v = v.parent as? View
        assertNotNull("a clickable row", v)
        v!!.performClick()
        ShadowLooper.idleMainLooper()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.isShowing)
        assertNotNull(findText(d.window!!.decorView, a.getString(R.string.settings_delete_all_title, "2 scans")))
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        val deadline = System.currentTimeMillis() + 5_000
        while (app.phoneServer.store.count() != 0 && System.currentTimeMillis() < deadline) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        assertEquals(0, app.phoneServer.store.count())
    }

    @Test
    fun settingsActivity_showsThePhonesServer() {
        val a = launch(SettingsActivity::class.java)
        assertResumed(a)
        assertNotNull("pairing a computer lives in Settings", findText(a.window.decorView, a.getString(R.string.pv_pair)))
        assertNotNull(findText(a.window.decorView, a.getString(R.string.pv_pack_check)))
    }

    @Test
    fun diagnosticsActivity_resumes_withoutStartingAnything() {
        val a = launch(DiagnosticsActivity::class.java)
        assertResumed(a)
    }

    @Test
    fun settings_longPressDiagnosticsHeader_opensNetworkTest() {
        val a = launch(SettingsActivity::class.java)
        val label = a.getString(R.string.settings_diagnostics).uppercase()
        val header = findText(a.window.decorView, label)
        assertNotNull("DIAGNOSTICS header not found", header)
        assertTrue(header!!.performLongClick())
        assertEquals(DiagnosticsActivity::class.java.name, nextStarted()?.component?.className)
    }

    private fun findText(v: View, text: String): TextView? {
        if (v is TextView && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), text)?.let { return it }
        return null
    }

    // ── manifest ────────────────────────────────────────────────────────────

    @Test
    fun gatewayService_declaredAndNotExported() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, GatewayService::class.java), 0)
        assertNotNull(info)
        assertFalse("GatewayService must not be exported", info.exported)
    }

    @Test
    fun everyManifestActivity_isDeclared_onlyMainExported() {
        val pi = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES)
        val byName = pi.activities!!.associateBy { it.name }
        listOf(MainActivity::class.java, PanelActivity::class.java,
            ShareActivity::class.java, SettingsActivity::class.java, DiagnosticsActivity::class.java).forEach { cls ->
            val ai = byName[cls.name]
            assertNotNull("${cls.simpleName} missing from the manifest", ai)
            assertEquals("${cls.simpleName} exported", cls == MainActivity::class.java, ai!!.exported)
        }
    }

    private fun android.view.View.findViewWithText(t: String): android.view.View? {
        if (this is android.widget.TextView && text?.toString() == t && isShown) return this
        if (this is android.view.ViewGroup) for (i in 0 until childCount) getChildAt(i).findViewWithText(t)?.let { return it }
        return null
    }
}
