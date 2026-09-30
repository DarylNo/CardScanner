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
        val a = launch(PanelActivity::class.java, PanelActivity.intent(app, detail = 12, priceCheck = true))
        assertResumed(a)
        assertEquals(GatewayService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertEquals("http://127.0.0.1:8090/phone?panel=1&detail=12&pricecheck=1",
            PanelActivity.buildUrl(app.phoneServer.localBase(), 12, true))
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
}
