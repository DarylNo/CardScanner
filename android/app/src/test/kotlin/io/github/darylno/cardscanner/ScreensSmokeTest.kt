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
import io.github.darylno.cardscanner.net.PrefsConfigStore
import io.github.darylno.cardscanner.net.ServerConfig
import io.github.darylno.cardscanner.ui.Adapters
import io.github.darylno.cardscanner.ui.DiagnosticsActivity
import io.github.darylno.cardscanner.ui.PanelActivity
import io.github.darylno.cardscanner.ui.SettingsActivity
import io.github.darylno.cardscanner.ui.SetupActivity
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
        PrefsConfigStore(app).clear()
        freshServerAdapter()
    }

    @After
    fun tearDown() {
        // Destroy what we built so camera/upload listeners don't leak into the next test.
        controllers.asReversed().forEach { c ->
            runCatching { c.pause().stop().destroy() }
        }
        PrefsConfigStore(app).clear()
    }

    /**
     * [Adapters] caches the ServerAdapter in a process-wide object, and Robolectric
     * keeps static state across tests while handing each test a NEW Application —
     * so rebuild it (and App.server, which App.onCreate already filled from the
     * stale cache) against this test's context. Test-only; production calls it once.
     */
    private fun freshServerAdapter() {
        Adapters::class.java.getDeclaredField("serverAdapter").apply { isAccessible = true }.set(Adapters, null)
        val server = Adapters.server(app)
        App::class.java.getDeclaredField("server").apply { isAccessible = true }.set(app, server)
    }

    private fun pair() {
        PrefsConfigStore(app).save(ServerConfig(listOf("https://192.168.1.5:8443"), "a".repeat(64)))
        assertTrue("fake pairing must read back as paired", app.server.isPaired)
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

    @Test
    fun mainActivity_unpaired_redirectsToSetup() {
        assertFalse(app.server.isPaired)
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        assertFalse("MainActivity must not finish itself when unpaired", a.isFinishing)
        val next = nextStarted()
        assertNotNull("unpaired launch must open SetupActivity", next)
        assertEquals(SetupActivity::class.java.name, next!!.component?.className)
    }

    @Test
    fun mainActivity_paired_cameraGranted_resumes() {
        pair()
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        assertResumed(a)
        // Paired: no redirect to Setup.
        val next = nextStarted()
        assertTrue("paired launch must not redirect to Setup",
            next?.component?.className != SetupActivity::class.java.name)
    }

    @Test
    fun mainActivity_paired_cameraDenied_resumes() {
        pair()
        grantCamera(false)
        val a = launch(MainActivity::class.java)
        assertResumed(a)
    }

    @Test
    fun mainActivity_paired_survivesPauseResumeCycle() {
        pair()
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
    fun setupActivity_unpaired_resumes() {
        val a = launch(SetupActivity::class.java)
        assertResumed(a)
    }

    @Test
    fun setupActivity_paired_resumes() {
        pair()
        val a = launch(SetupActivity::class.java)
        assertResumed(a)
    }

    @Test
    fun panelActivity_withExtras_resumes() {
        pair()
        val intent = Intent(app, PanelActivity::class.java)
            .putExtra(PanelActivity.EXTRA_BASE, "https://192.168.1.5:8443")
            .putExtra(PanelActivity.EXTRA_PIN, "a".repeat(64))
        val a = launch(PanelActivity::class.java, intent)
        assertResumed(a)
    }

    @Test
    fun panelActivity_withoutExtras_doesNotCrash() {
        val a = launch(PanelActivity::class.java)
        assertResumed(a)
    }

    @Test
    fun shareActivity_resumes() {
        pair()
        val a = launch(ShareActivity::class.java)
        assertResumed(a)
    }

    @Test
    fun shareActivity_unpaired_doesNotCrash() {
        val a = launch(ShareActivity::class.java)
        assertResumed(a)
    }

    @Test
    fun settingsActivity_resumes() {
        pair()
        val a = launch(SettingsActivity::class.java)
        assertResumed(a)
    }

    @Test
    fun diagnosticsActivity_resumes_withoutStartingAnything() {
        val a = launch(DiagnosticsActivity::class.java)
        assertResumed(a)
    }

    @Test
    fun diagnosticsActivity_compareSection_defaultOff_andTogglable() {
        assertFalse("Compare mode must default to OFF", app.settings.compareMode)
        // Robolectric's default network is metered, so toggling ON can't start a pack download here.
        assertFalse(io.github.darylno.cardscanner.ident.CompareMode.unmetered(app))
        val a = launch(DiagnosticsActivity::class.java)
        val title = findText(a.window.decorView, a.getString(R.string.cmp_switch))
        assertNotNull("Compare mode switch not shown", title)
        assertNotNull(findText(a.window.decorView, a.getString(R.string.cmp_test_last)))
        assertNotNull(findText(a.window.decorView, a.getString(R.string.cmp_copy)))
        // The whole row toggles its switch (ScanChrome.switchRow).
        val row = title!!.parent.parent as View
        assertTrue(row.performClick())
        assertTrue(app.settings.compareMode)
        assertTrue(row.performClick())
        assertFalse(app.settings.compareMode)
        assertResumed(a)
    }

    @Test
    fun settings_longPressDiagnosticsHeader_opensNetworkTest() {
        pair()
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
        listOf(MainActivity::class.java, SetupActivity::class.java, PanelActivity::class.java,
            ShareActivity::class.java, SettingsActivity::class.java, DiagnosticsActivity::class.java).forEach { cls ->
            val ai = byName[cls.name]
            assertNotNull("${cls.simpleName} missing from the manifest", ai)
            assertEquals("${cls.simpleName} exported", cls == MainActivity::class.java, ai!!.exported)
        }
    }
}
