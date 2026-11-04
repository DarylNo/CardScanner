package io.github.darylno.cardscanner

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
        for (label in listOf(a.getString(R.string.scans), "⚙",
                a.getString(R.string.mode_tray), a.getString(R.string.mode_tap))) {
            assertNotNull("$label is in the bottom bar", findText(bottom, label))
            assertEquals("$label is not in the top bar", null, findText(top, label))
        }
        // Share is the standard share glyph, named for TalkBack.
        assertNotNull("Share (icon) is in the bottom bar", findDesc(bottom, a.getString(R.string.share)))
        assertEquals(null, findDesc(top, a.getString(R.string.share)))
        // Visible clickables only: the measurement session's prompt card also lives in the top
        // scrim, but it is GONE outside a session.
        fun clickables(v: View): Int = if (v.visibility != View.VISIBLE) 0 else (if (v.isClickable && v !is ViewGroup) 1 else 0) +
            (if (v is ViewGroup) (0 until v.childCount).sumOf { clickables(v.getChildAt(it)) } else 0)
        // Only the upload indicator (tap = retry now, shown while scans are queued) and the
        // camera pill (a phone with more than one lens; none here) live up there.
        assertTrue("no buttons left in the top bar", clickables(top) <= 1)
        // Share sits directly above Scans (owner, 2026-10-02): same vertical column, Share first.
        val share = findDesc(bottom, a.getString(R.string.share))!!
        assertNotNull("the chip carries the glyph", (share as TextView).compoundDrawables[0])
        val scans = findText(bottom, a.getString(R.string.scans))!!
        val column = share.parent as LinearLayout
        assertSame("Share and Scans share a column", column, scans.parent)
        assertEquals(LinearLayout.VERTICAL, column.orientation)
        assertTrue("Share is above Scans", column.indexOfChild(share) < column.indexOfChild(scans))
        while (nextStarted() != null) { }
        share.performClick()
        assertEquals(io.github.darylno.cardscanner.ui.ShareActivity::class.java.name, nextStarted()?.component?.className)
        findText(bottom, "⚙")!!.performClick()
        assertEquals(SettingsActivity::class.java.name, nextStarted()?.component?.className)
    }

    /**
     * Every scan-screen control is actually ON SCREEN, laid out at the N200's size
     * (1080×2400, ~411×914 dp). 1.1.7 shipped with the mode row's spacer swelling
     * to the whole screen height: Share, Scans, the shutter and Area all had 0 px
     * height while the "is it in the bottom bar" test above still passed.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun mainActivity_everyControlIsOnScreen() {
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        assertEveryControlIsOnScreen(a)
    }

    /** [mainActivity_everyControlIsOnScreen]'s checks, on any scan screen [a] laid out at the N200's size. */
    private fun assertEveryControlIsOnScreen(a: MainActivity) {
        val root = a.window.decorView
        val screenH = root.height
        assertTrue("laid out ($screenH px)", screenH > 0)
        val bottom = root.findViewWithTag<View>(TAG_BOTTOM_BAR)
        val share = findDesc(bottom, a.getString(R.string.share))!!
        val scans = findText(bottom, a.getString(R.string.scans))!!
        val shutter = findDesc(bottom, a.getString(R.string.scan_card))!!
        val area = findText(bottom, a.getString(R.string.area))!!
        val gear = findDesc(bottom, a.getString(R.string.settings))!!
        val tray = findText(bottom, a.getString(R.string.mode_tray))!!
        for ((name, v) in listOf("Share" to share, "Scans" to scans, "shutter" to shutter, "Area" to area, "⚙" to gear, "Tray" to tray)) {
            val loc = IntArray(2); v.getLocationInWindow(loc)
            assertTrue("$name has a size (${v.width}×${v.height})", v.width > 0 && v.height > 0)
            assertTrue("$name is inside the screen (y ${loc[1]}..${loc[1] + v.height} of $screenH)", loc[1] >= 0 && loc[1] + v.height <= screenH)
        }
        // The bottom bar hugs the bottom: the mode row sits just above the controls,
        // not in the middle of the screen.
        val trayY = IntArray(2).also { tray.getLocationInWindow(it) }[1]
        val shutterY = IntArray(2).also { shutter.getLocationInWindow(it) }[1]
        assertTrue("Tray|Tap sits above the shutter row (tray $trayY, shutter $shutterY)", trayY < shutterY)
        assertTrue("Tray|Tap is in the bottom third (y $trayY of $screenH)", trayY > screenH * 2 / 3)
        // Share is directly above Scans.
        val shareY = IntArray(2).also { share.getLocationInWindow(it) }[1]
        val scansY = IntArray(2).also { scans.getLocationInWindow(it) }[1]
        assertTrue("Share ($shareY) is above Scans ($scansY)", shareY + share.height <= scansY)
    }

    /** The measurement session's card sits on the scan screen, counts captures, and Next/Stop drive it. */
    @Test
    fun mainActivity_measurementSession_promptsEachStep() {
        grantCamera(true)
        app.measure.start()
        val a = launch(MainActivity::class.java)
        val step1 = app.measure.steps[0]
        assertNotNull("the first step's title is shown", findText(a.window.decorView, a.getString(R.string.measure_step, 1, app.measure.steps.size, step1.title)))
        assertNotNull(findText(a.window.decorView, step1.instruction))
        assertNotNull(findText(a.window.decorView, a.getString(R.string.measure_captures, 0)))
        findText(a.window.decorView, a.getString(R.string.measure_yes))!!.performClick()
        findText(a.window.decorView, a.getString(R.string.measure_next))!!.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(1, app.measure.index)
        assertNotNull(findText(a.window.decorView, app.measure.steps[1].instruction))
        findText(a.window.decorView, a.getString(R.string.measure_stop))!!.performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(app.measure.active)
        assertFalse("the card is gone", findText(a.window.decorView, app.measure.steps[1].instruction)!!.isShown)
    }

    /**
     * The zoom A/B's B step turns "Zoom to fit the Area" ON on the live camera (before
     * 1.1.17 Next pushed only AE lock, so B ran at 1×), and Stop gives the owner's own
     * switch back — both as the paired computer's GET /api/device reads them. An Area of
     * 0.25..0.75 fits 1.90× (the Area fit), as in deviceApi_patchAnswersWithTheZoomAfterTheChange.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun mainActivity_zoomBStep_appliesTheZoomLive() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = false
        grantCamera(true)
        // Only this test's log lines (review of a43cb5b: all() of the process-wide ring was satisfied by an EARLIER test's
        // Plan.ZOOM line, so the check passed in the class run with the full session's B logging nothing).
        val t0 = io.github.darylno.cardscanner.core.DebugLog.global.lastSeq
        app.measure.start()                                      // the full session: Next through to B
        val a = launch(MainActivity::class.java)
        val root = a.window.decorView
        repeat(app.measure.steps.size) {
            if (app.measure.active && app.measure.step.id != "zoom-b" && !app.measure.isLast) {
                findText(root, a.getString(R.string.measure_next))!!.performClick()
                ShadowLooper.idleMainLooper()
            }
        }
        assertEquals("zoom-b", app.measure.step.id)
        val api = io.github.darylno.cardscanner.core.server.DeviceApi(app.phoneServer.device)
        fun device(): Map<*, *> {
            val r = api.handle(io.github.darylno.cardscanner.core.server.ApiRequest("GET", "/api/device"))!!
            assertEquals(200, r.status)
            return io.github.darylno.cardscanner.core.MiniJson.parse(String(r.body)) as Map<*, *>
        }
        val on = device()
        assertEquals(true, on["zoom_fit"])
        val zoom = on["zoom"] as Map<*, *>
        assertEquals("B zooms the camera: $on", 1.9, (zoom["target"] as Number).toDouble(), 0.0)
        assertEquals("the Area fit", zoom["limit"])
        val marker = "step ${app.measure.index + 1}/${app.measure.steps.size} zoom-b zoom: zoom to fit the Area: on · target 1.90× (limited by the Area fit)"
        assertTrue("the test marker records the zoom B runs at: $marker",
            io.github.darylno.cardscanner.core.DebugLog.global.since(t0, limit = 3000).any { it.tag == "test" && it.msg.startsWith(marker) })
        findText(root, a.getString(R.string.measure_stop))!!.performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(app.measure.active)
        assertFalse("the owner's own switch (off) is back", app.settings.zoomFit)
        val off = device()
        assertEquals(false, off["zoom_fit"])
        assertEquals(1.0, ((off["zoom"] as Map<*, *>)["target"] as Number).toDouble(), 0.0)
    }

    /**
     * The zoom steps' prompt card at the N200's size, with their longest words: A1's
     * instruction with every note (Tap to scan, an Area that can't zoom where it is, the
     * off-centre hint), B's question and its "B is at 1.00×" notes (the Area too wide; the
     * camera that did not zoom, its problem quoted), and the done step's
     * A/B summary and verdict. Back, Stop, Next and Yes/No each have a size and sit on
     * screen, the card stays ≤ 45 % of the screen height (the preview must still show the
     * tray), and the bottom row is still on screen. NATIVE graphics: real text widths.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun mainActivity_zoomSteps_cardFitsWithTheLongestText() = zoomStepsCardFits(io.github.darylno.cardscanner.ui.MeasureSession.Plan.ZOOM)

    /**
     * [mainActivity_zoomSteps_cardFitsWithTheLongestText] through the FULL rig session (review
     * of a60e51d): its done card carries the same A/B summary and probe half, under a longer
     * instruction — with the probe runs recorded it reached 47 % of the screen.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun mainActivity_fullSession_doneCardFitsWithTheLongestText() = zoomStepsCardFits(io.github.darylno.cardscanner.ui.MeasureSession.Plan.FULL)

    /**
     * The zoom steps' cards in [plan] (the zoom plan, or the full session from its zoom A1 on),
     * the probe step with three runs recorded (valid, inconclusive, NOT centred — review of
     * a60e51d: the done card's probe half pushed it past 45 %), and the done card.
     */
    private fun zoomStepsCardFits(plan: io.github.darylno.cardscanner.ui.MeasureSession.Plan) {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.45, 0.25, 0.95, 0.75)
        app.settings.auto = false
        app.settings.zoomFit = false
        val cam = ZoomStateCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        app.measure.start(plan)
        while (app.measure.step.id != "zoom-a1") app.measure.next()
        val a = launch(MainActivity::class.java)
        val root = a.window.decorView
        val m = app.measure
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.width, root.height)
        }
        fun onScreen(name: String, v: View) {
            val l = IntArray(2); v.getLocationInWindow(l)
            assertTrue("${m.step.id}: $name has a size (${v.width}×${v.height})", v.width > 0 && v.height > 0)
            assertTrue("${m.step.id}: $name is inside the screen (x ${l[0]}..${l[0] + v.width} of ${root.width}, y ${l[1]}..${l[1] + v.height} of ${root.height})",
                l[0] >= 0 && l[0] + v.width <= root.width && l[1] >= 0 && l[1] + v.height <= root.height)
        }
        fun checkCard(vararg texts: String) {
            ShadowLooper.idleMainLooper(); layout()
            for (t in texts) assertNotNull("${m.step.id} shows: $t", generateSequence(listOf(root)) { vs -> vs.flatMap { v -> (v as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.ifEmpty { null } }
                .flatten().filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.toString().contains(t) })
            val title = findText(root, a.getString(R.string.measure_step, m.index + 1, m.steps.size, m.step.title))!!
            var card: View = title
            while ((card.parent as View).tag != TAG_TOP_BAR) card = card.parent as View
            assertTrue("${m.step.id}: the card is ≤ 45 % of the screen (${card.height} of ${root.height} px)", card.height <= root.height * 0.45)
            onScreen("the card", card)
            if (m.index > 0) onScreen("Back", findText(root, a.getString(R.string.measure_back))!!)
            onScreen("Stop", findText(root, a.getString(R.string.measure_stop))!!)
            onScreen("Next", findText(root, a.getString(if (m.isLast) R.string.measure_finish else R.string.measure_next))!!)
            if (m.step.question != null) {
                onScreen("Yes", findText(root, a.getString(R.string.measure_yes))!!)
                onScreen("No", findText(root, a.getString(R.string.measure_no))!!)
            }
            val bottom = root.findViewWithTag<View>(TAG_BOTTOM_BAR)
            onScreen("Scans", findText(bottom, a.getString(R.string.scans))!!)
            onScreen("the shutter", findDesc(bottom, a.getString(R.string.scan_card))!!)
        }
        fun next() { findText(root, a.getString(R.string.measure_next))!!.performClick(); ShadowLooper.idleMainLooper() }
        /** One arm's cards: the scan screen's capture → queued → identified path, as the session hears it. */
        fun arm(first: Long, z: Double, h: Int, ranks: List<Int>) {
            for ((i, r) in ranks.withIndex()) {
                m.captured(first + i, manual = false, zoom = z, cardLongPx = h, stripDetail = 200.0 + i, iso = 400)
                m.queued("j${first + i}", first + i)
                m.identified("j${first + i}", rank = r, name = "Card number ${i + 1} with a long name", ocr = r == 3, autoPicked = r >= 2, printings = 7)
            }
        }
        assertEquals("zoom-a1", m.step.id)
        checkCard(m.step.instruction, "Tap to scan is on — switch to Tray", "can't zoom where it is", "off-centre Area: centred, it would zoom 1.90×")
        arm(1, 1.0, 612, listOf(3, 1, 1, 0, 1, 2, 0, 3, 1, 1))
        next()
        assertEquals("zoom-probe", m.step.id)
        checkCard(m.step.instruction)
        // The probe's runs: valid, inconclusive, NOT centred on both rules (the longest words the done card can carry).
        m.recordProbe(1, probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 1", probeResult(1)))
        val stuck = probeResult(2, valid = false, centred = null, fit = null, drift = null, driftZ = null, validRungs = 9,
            reason = "only 9 of 15 rungs measured cleanly (the lens moved off the lock ×4, no answer from the camera ×2)")
        m.recordProbe(2, probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, stuck.words(), stuck))
        val bent = probeResult(3, centred = false, fit = io.github.darylno.cardscanner.core.ZoomProbe.HFit(612.0, 3.0, 0.032, 0.0, 2.0), drift = 0.021, driftZ = 1.5)
        m.recordProbe(3, probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, bent.words(), bent))
        next()
        assertEquals("zoom-b", m.step.id)
        checkCard(m.step.instruction, m.step.question!!, "B is at 1.00× — the Area allows only 1.06×")
        // B's longest line: a centred Area whose zoom the camera never applied ("Camera is not
        // active" past the retries — the coordinator maps 1×), its problem quoted; Yes refreshes it.
        cam.zoom.setArea(io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75))
        val (g, _) = cam.asked!!
        repeat(io.github.darylno.cardscanner.core.ZoomCoordinator.MAX_TRIES + 1) { cam.zoom.onFailed(g, notActive = true, message = "Camera is not active") }
        findText(root, a.getString(R.string.measure_yes))!!.performClick()
        checkCard(m.step.instruction, m.step.question!!,
            "B is at 1.00× — the camera did not zoom (1.90× could not be applied (Camera is not active, 6 tries)): go Back, then Next to try again")
        // The worst B the summary can carry: worse on some, extra captures, more no-cards, off the post-fit.
        arm(11, 1.9, 1400, listOf(1, 1, 1, 0, 0, 0, 0, 3, 1, 1, 0, 0))
        next()
        assertEquals("zoom-a2", m.step.id)
        checkCard(m.step.instruction, "Tap to scan is on — switch to Tray")
        // A2 unstable on five cards: every A/B rule but "B zoomed" fails AND rule 2 is out of reach this run.
        arm(31, 1.0, 612, listOf(3, 3, 3, 3, 1, 2, 0, 3, 3, 3))
        next()
        if (m.step.id == "ae-lock") next()
        assertEquals(if (plan == io.github.darylno.cardscanner.ui.MeasureSession.Plan.ZOOM) "zoom-done" else "done", m.step.id)
        checkCard(m.step.instruction, "Zoom to fit ON by default: NO", "✗ post-fit", "rule 2 out of reach this run (5 unstable at 1×, 0 still identifying)",
            "\nProbe: ✓ 2 cards ✗ NOT centred ✓ settle · knee not measured")
    }

    /**
     * A camera that runs the zoom probe the way CameraController answers it (1.1.17): a
     * [ZoomStateCamera] (the real coordinator behind the Area and the zoom state) whose
     * probe the TEST drives — what was started (run number, the earlier valid runs it
     * compares with), what cancelled it, and the listener to send progress and the end to.
     */
    private class ProbeCamera(val inner: ZoomStateCamera = ZoomStateCamera()) : io.github.darylno.cardscanner.ui.CameraPort by inner {
        val started = mutableListOf<Pair<Int, List<Int>>>()
        val cancels = mutableListOf<String>()
        var probe: io.github.darylno.cardscanner.ui.ProbeListener? = null
        var running = false
        val calls get() = inner.rec.calls
        override fun startZoomProbe(run: Int, earlier: List<io.github.darylno.cardscanner.core.ZoomProbeRun.Earlier>,
                                    l: io.github.darylno.cardscanner.ui.ProbeListener): Boolean {
            started += run to earlier.map { it.run }; probe = l; running = true; return true
        }
        override fun cancelZoomProbe(why: String) { cancels += why }
        override fun zoomProbeRunning() = running
        override fun manualScan() { inner.rec.calls += "manualScan" }
        override fun tapFocus(x: Float, y: Float) { inner.rec.calls += "tapFocus" }
        override fun relearn() { inner.rec.calls += "relearn" }
        override fun setTorch(on: Boolean) { inner.rec.calls += "setTorch($on)" }
        override fun setCamera(id: String?) { inner.rec.calls += "setCamera($id)" }
        /** The run ends as the controller reports it: on the UI thread, once. */
        fun finish(o: io.github.darylno.cardscanner.core.ZoomProbeRun.Outcome) { running = false; probe!!.onFinished(o); ShadowLooper.idleMainLooper() }
    }

    /** A probe run's result (ZoomProbe.verdict's fields): valid and centred on camera 0, or with [words] for an inconclusive one. */
    private fun probeResult(run: Int, valid: Boolean = true, centred: Boolean? = true, reason: String? = null,
                            fit: io.github.darylno.cardscanner.core.ZoomProbe.HFit? = io.github.darylno.cardscanner.core.ZoomProbe.HFit(612.0, 3.0, 0.002, 0.0, 2.0),
                            drift: Double? = 0.001, driftZ: Double? = 2.2, validRungs: Int = 15, camera: String = "0", stream: String = "1600×1200") =
        io.github.darylno.cardscanner.core.ZoomProbe.Result(run, valid, reason, centred, fit, drift, 15, validRungs, 0, 0, true, 1.43, 0.0, null,
            stream, 2.2, null, 612.0, driftZ = driftZ, camera = camera)

    private fun probeOutcome(kind: io.github.darylno.cardscanner.core.ZoomProbeRun.Kind, words: String,
                             r: io.github.darylno.cardscanner.core.ZoomProbe.Result? = null, numbered: Boolean = r != null) =
        io.github.darylno.cardscanner.core.ZoomProbeRun.Outcome(kind, words, r, if (r?.valid == true) FloatArray(64 * 88) { 0.5f } else null, numbered)

    /** The text of the measurement card's count line (the line under the instruction). */
    private fun countLine(root: View): String = generateSequence(listOf(root)) { vs -> vs.flatMap { v -> (v as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.ifEmpty { null } }
        .flatten().filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.toString().startsWith(app.getString(R.string.measure_captures, 0).substringBefore("0")) }
        ?.text?.toString() ?: ""

    /**
     * The zoom probe's step on the scan screen (1.1.17): Run probe starts run 1 (no earlier
     * card); while it runs the button is Cancel probe, the status pill and the count line say
     * where it is, the Area is drawn where it sits at the camera's ANSWERED zoom (the chip
     * "Area ✓ · 1.6×") and the outline / watch window are hidden; Cancel probe cancels it.
     * Each end shows what the session's runs say (a run with no result: its own words); Run
     * again numbers the next run (a refusal before it began leaves the number) and hands it
     * the valid runs' cards to compare with; the last verdict reaches /api/device.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun mainActivity_zoomProbeStep_runsCancelsAndRunsAgain() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = false
        val cam = ProbeCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        app.measure.start(io.github.darylno.cardscanner.ui.MeasureSession.Plan.ZOOM)
        app.measure.next()
        assertEquals("zoom-probe", app.measure.step.id)
        val a = launch(MainActivity::class.java)
        val root = a.window.decorView
        val bottom = root.findViewWithTag<View>(TAG_BOTTOM_BAR)
        val overlay = findOverlay(root)!!
        fun overlayRoi() = io.github.darylno.cardscanner.ui.OverlayView::class.java.getDeclaredField("roi").apply { isAccessible = true }.get(overlay)
        assertFalse("no Yes / No on the probe step", findText(root, a.getString(R.string.measure_yes))?.isShown == true)
        val run = findText(root, "Run probe")
        assertNotNull("the step's own button", run)
        assertTrue(run!!.isShown)
        run.performClick(); ShadowLooper.idleMainLooper()
        assertEquals(listOf(1 to emptyList<Int>()), cam.started)
        assertNotNull("running: the button cancels", findText(root, "Cancel probe"))
        cam.probe!!.onProgress("Zoom probe 7/15 · 1.60× — hands off the tray", "rung 7/15 · 1.60× · card 979 px at 1.50×", 1.6)
        ShadowLooper.idleMainLooper()
        assertEquals("Zoom probe 7/15 · 1.60× — hands off the tray", a.statusForTest)
        assertTrue(countLine(root), countLine(root).endsWith("\nrung 7/15 · 1.60× · card 979 px at 1.50×"))
        assertNotNull("the chip says the rung's zoom", findText(bottom, a.getString(R.string.area_zoomed, "1.6")))
        assertEquals("the Area where it sits at the camera's 1.60×",
            io.github.darylno.cardscanner.core.ZoomFit.toView(app.settings.roi, 1.6), overlayRoi())
        assertEquals("no outline or watch window on the rungs", null, overlay.plan().shape)
        findText(root, "Cancel probe")!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals(listOf("Cancel probe"), cam.cancels)
        assertEquals("still running until it ends", 1, cam.started.size)
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.CANCELLED, "Zoom probe cancelled (Cancel probe) — nothing filed", numbered = true))
        assertTrue(countLine(root), countLine(root).endsWith("\nZoom probe cancelled (Cancel probe) — nothing filed"))
        assertTrue(a.statusForTest, a.statusForTest.startsWith("Zoom probe cancelled"))
        // Run again: run 2 (the cancelled run used 1), valid.
        findText(root, "Run again")!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals(2 to emptyList<Int>(), cam.started.last())
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 2: valid — …", probeResult(2)))
        val valid = "run 2 valid — lift this card, put a DIFFERENT card down, let it scan, then Run again"
        assertTrue(countLine(root), countLine(root).endsWith("\n$valid"))
        val api = io.github.darylno.cardscanner.core.server.DeviceApi(app.phoneServer.device)
        val dev = io.github.darylno.cardscanner.core.MiniJson.parse(String(
            api.handle(io.github.darylno.cardscanner.core.server.ApiRequest("GET", "/api/device"))!!.body)) as Map<*, *>
        // The computer gets what the run FOUND — no "Run again" there (review of a43cb5b).
        assertEquals("run 2 valid (one more card needed)", (dev["zoom_probe"] as Map<*, *>)["words"])
        // Run 3 compares its card with run 2's; refused before it began — its words, and the number is kept.
        findText(root, "Run again")!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals(3 to listOf(2), cam.started.last())
        val wait = "Let the card scan first (wait for the ✓), then Run probe"
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.REFUSED, wait))
        assertTrue(countLine(root), countLine(root).endsWith("\n$wait"))
        findText(root, "Run again")!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals(3 to listOf(2), cam.started.last())
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.REFUSED, wait))
        // Not running: Next moves on, and the button is gone on B.
        findText(root, a.getString(R.string.measure_next))!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals("zoom-b", app.measure.step.id)
        assertEquals(null, findText(root, "Run again")?.takeIf { it.isShown })
    }

    /**
     * The touch policy while the zoom probe runs (1.1.17): every control on the scan screen
     * ONLY cancels it — through its verified restore, never a stop that skips it — and does
     * nothing else; the owner taps again once it is over. The shutter scans nothing, a preview
     * tap focuses nowhere, a double-tap re-learns nothing, the Area / Tray|Tap / the camera pill
     * change nothing, Scans / ⚙ / Share / the Back key leave nothing, Back / Next / Stop on the
     * card keep the step. Once it is over every control works again.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun mainActivity_theShutterIsRefusedDuringTheProbe() {
        addN200Cameras()
        app.settings.cameraId = null
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        val cam = ProbeCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        app.measure.start(io.github.darylno.cardscanner.ui.MeasureSession.Plan.ZOOM)
        app.measure.next()
        val a = launch(MainActivity::class.java)
        val root = a.window.decorView
        val bottom = root.findViewWithTag<View>(TAG_BOTTOM_BAR)
        val top = root.findViewWithTag<View>(TAG_TOP_BAR)
        val overlay = findOverlay(root)!!
        while (nextStarted() != null) { }
        cam.running = true                                         // the probe runs (through the adapter: zoomProbeRunning)
        cam.calls.clear()
        val controls = listOf<Pair<String, () -> Unit>>(
            "the shutter" to { findDesc(bottom, a.getString(R.string.scan_card))!!.performClick() },
            "a tap on the preview" to { overlay.onTap!!.invoke(100f, 100f) },
            "a double-tap" to { overlay.onDoubleTap!!.invoke() },
            "the Area button" to { findText(bottom, a.getString(R.string.area_set))!!.performClick() },
            "Tray | Tap" to { findText(bottom, a.getString(R.string.mode_tap))!!.performClick() },
            "the camera pill" to { findText(top, "‹  Auto · 13 MP  ›")!!.performClick() },
            "Scans" to { findText(bottom, a.getString(R.string.scans))!!.performClick() },
            "Settings" to { findDesc(bottom, a.getString(R.string.settings))!!.performClick() },
            "Share" to { findDesc(bottom, a.getString(R.string.share))!!.performClick() },
            "Back" to { findText(root, a.getString(R.string.measure_back))!!.performClick() },
            "Next" to { findText(root, a.getString(R.string.measure_next))!!.performClick() },
            "Stop" to { findText(root, a.getString(R.string.measure_stop))!!.performClick() },
            "the Back key" to { a.onBackPressedDispatcher.onBackPressed() },
        )
        for ((n, c) in controls.withIndex()) {
            val (name, act) = c
            act(); ShadowLooper.idleMainLooper()
            assertEquals("$name only cancels the probe: ${cam.cancels}", n + 1, cam.cancels.size)
            assertEquals("…once, as \"$name\"", name, cam.cancels.last())
            assertTrue("$name: ${a.statusForTest}", a.statusForTest.startsWith("Cancelling the zoom probe"))
        }
        assertEquals("nothing else reached the camera: ${cam.calls}", emptyList<String>(), cam.calls)
        assertEquals("nothing opened", null, nextStarted())
        assertFalse("the scan screen stays", a.isFinishing)
        assertEquals("the step stays", "zoom-probe", app.measure.step.id)
        assertTrue(app.measure.active)
        assertTrue(app.settings.auto); assertEquals(null, app.settings.cameraId)
        assertEquals(io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75), app.settings.roi)
        // Over: the shutter scans again.
        cam.running = false
        findDesc(bottom, a.getString(R.string.scan_card))!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals(listOf("manualScan"), cam.calls)
        assertEquals(controls.size, cam.cancels.size)
    }

    /**
     * A scan identified while the zoom probe runs never opens (review of a60e51d): a shutter scan
     * taken just before Run probe (Tap to scan — "any card in the Area will do" — or Tray's
     * shutter) used to open its pick once identified, which leaves the scan screen mid-run — a
     * lifecycle stop that ends the probe with no verified restore. It is not opened and the probe
     * is not cancelled; the status pill stays the probe's, and what the scan said comes under the
     * probe's end ("… ready: tap Scans …", openPick's own words for a scan it cannot open). Once
     * the probe is over a scan opens as before.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun mainActivity_aScanIdentifiedDuringTheProbeNeverLeavesTheScreen() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = false                                  // Tap to scan: a shutter scan opens once identified
        val cam = ProbeCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        app.measure.start(io.github.darylno.cardscanner.ui.MeasureSession.Plan.ZOOM)
        app.measure.next()
        val a = launch(MainActivity::class.java)
        val root = a.window.decorView
        while (nextStarted() != null) { }
        findText(root, "Run probe")!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals(1, cam.started.size)
        val rung = "Zoom probe 7/15 · 1.60× — hands off the tray"
        cam.probe!!.onProgress(rung, "rung 7/15 · 1.60× · card 979 px at 1.50×", 1.6); ShadowLooper.idleMainLooper()
        fun scan(name: String) = org.json.JSONObject().put("card_read", org.json.JSONObject().put("name", name))
        val l = a.uploadListenerForTest
        // Tap to scan's shutter scan, auto-filed (it opens); Tray's shutter scans that need a pick / are a best guess.
        l.onOutcome("job-1", true, true, null, io.github.darylno.cardscanner.ui.Outcome.AutoFiled(scan("Opt"), 41, false))
        l.onOutcome("job-2", true, false, null, io.github.darylno.cardscanner.ui.Outcome.NeedsPick(scan("Shock"), 42, false))
        l.onOutcome("job-3", true, false, null, io.github.darylno.cardscanner.ui.Outcome.BestGuess(scan("Duress"), 43, false))
        ShadowLooper.idleMainLooper()
        assertEquals("nothing opened mid-probe", null, nextStarted()?.component?.className)
        assertEquals("the probe was neither cancelled nor stopped", emptyList<String>(), cam.cancels)
        assertFalse(a.isFinishing)
        assertEquals("the status pill stays the probe's", rung, a.statusForTest)
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 1", probeResult(1)))
        // The probe's end in a few words, then what the last scan said (the card above has the probe's own words).
        assertEquals("Zoom probe over · " + a.getString(R.string.open_scans_hint, "Duress"), a.statusForTest)
        assertEquals("still nothing opened", null, nextStarted()?.component?.className)
        // The pill's two lines leave every control on screen at the N200's size.
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.width, root.height)
        val bottom = root.findViewWithTag<View>(TAG_BOTTOM_BAR)
        val pill = findText(root, a.statusForTest)!!
        for ((name, v) in listOf("the status pill" to pill, "Share" to findDesc(bottom, a.getString(R.string.share))!!,
                "Scans" to findText(bottom, a.getString(R.string.scans))!!, "shutter" to findDesc(bottom, a.getString(R.string.scan_card))!!,
                "⚙" to findDesc(bottom, a.getString(R.string.settings))!!, "Tray" to findText(bottom, a.getString(R.string.mode_tray))!!)) {
            val loc = IntArray(2); v.getLocationInWindow(loc)
            assertTrue("$name has a size (${v.width}×${v.height})", v.width > 0 && v.height > 0)
            assertTrue("$name is inside the screen (y ${loc[1]}..${loc[1] + v.height} of ${root.height})", loc[1] >= 0 && loc[1] + v.height <= root.height)
        }
        // Over: a scan identified now opens, as it always did.
        l.onOutcome("job-4", true, true, null, io.github.darylno.cardscanner.ui.Outcome.AutoFiled(scan("Opt"), 44, false))
        ShadowLooper.idleMainLooper()
        assertEquals(PanelActivity::class.java.name, nextStarted()?.component?.className)
    }

    /**
     * Review of a43cb5b: the scan identified during the probe — its line held, then shown when the probe ends — was
     * never READ on the N200: the pill has 2 lines, and the end line ("Zoom probe over — nothing filed. What it found
     * is on the card above") filled both, so the held line was ellipsized away (the old test read the TextView's text,
     * in legacy graphics, 1 px a character). The end is a few words in front of it now ("Zoom probe over · ✓ …"):
     * whole in the pill with a long card name, for each way a run ends. NATIVE graphics: real text widths.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun mainActivity_theHeldScanLineFitsThePillWhenTheProbeEnds() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = false
        val cam = ProbeCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        app.measure.start(io.github.darylno.cardscanner.ui.MeasureSession.Plan.ZOOM)
        app.measure.next()
        val a = launch(MainActivity::class.java)
        val root = a.window.decorView
        while (nextStarted() != null) { }
        fun scan(name: String) = org.json.JSONObject().put("card_read", org.json.JSONObject().put("name", name))
        val kinds = listOf(
            io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE to "Run 1",
            io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.STOPPED to "Zoom probe stopped — the scan screen stopped. Nothing was filed; Run again when ready",
            io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.CANCELLED to "The zoom probe ran past 90 s — stopped; nothing filed. Run again",
        )
        for ((i, kw) in kinds.withIndex()) {
            val (kind, words) = kw
            findText(root, if (i == 0) "Run probe" else "Run again")!!.performClick(); ShadowLooper.idleMainLooper()
            cam.probe!!.onProgress("Zoom probe 7/15 · 1.60× — hands off the tray", "rung 7/15 · 1.60×", 1.6); ShadowLooper.idleMainLooper()
            a.uploadListenerForTest.onOutcome("job-$i", true, true, null,
                io.github.darylno.cardscanner.ui.Outcome.AutoFiled(scan("Emrakul, the Promised End"), 50L + i, false))
            ShadowLooper.idleMainLooper()
            cam.finish(probeOutcome(kind, words, if (kind == io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE) probeResult(1) else null,
                numbered = true))
            val held = a.getString(R.string.open_scans_hint, "Emrakul, the Promised End")
            val expected = io.github.darylno.cardscanner.ui.StatusText.probeEndShort(kind) + " · " + held
            assertEquals("$kind", expected, a.statusForTest)
            assertStatusFitsThePill(a, expected)
            while (nextStarted() != null) { }
        }
    }

    /**
     * The update lock's own buttons follow the touch policy too (review of a60e51d): the lock can
     * come up mid-run (UpdateLock's check answers late) and covers the card's Cancel probe; its
     * Download update and Scans each leave the screen — a lifecycle stop, no verified restore — so
     * while the probe runs each only cancels it. Once it is over they open as before.
     */
    @Test
    fun mainActivity_theUpdateLocksButtonsOnlyCancelTheProbe() {
        grantCamera(true)
        app.getSharedPreferences("update_lock", android.content.Context.MODE_PRIVATE).edit()
            .putString("version", "v99.0.0")
            .putString("apk_url", "https://github.com/DarylNo/CardScanner/releases/download/v99.0.0/mtg-card-scanner-android.apk")
            .putLong("checked_at", System.currentTimeMillis()).commit()
        try {
            val cam = ProbeCamera()
            app.cameraFactory = { _, _ -> cam }
            app.measure.start(io.github.darylno.cardscanner.ui.MeasureSession.Plan.ZOOM)
            app.measure.next()
            val a = launch(MainActivity::class.java)
            val root = a.window.decorView
            val download = findText(root, a.getString(R.string.update_download))!!
            assertTrue("the lock is shown", download.isShown)
            val lockScans = findText(download.parent as View, a.getString(R.string.scans))!!
            while (nextStarted() != null) { }
            cam.running = true                                     // the probe runs (the lock came up mid-run)
            download.performClick(); ShadowLooper.idleMainLooper()
            lockScans.performClick(); ShadowLooper.idleMainLooper()
            assertEquals(listOf("Download update", "Scans"), cam.cancels)
            assertEquals("nothing opened", null, nextStarted())
            assertTrue(a.statusForTest, a.statusForTest.startsWith("Cancelling the zoom probe"))
            cam.running = false
            lockScans.performClick(); ShadowLooper.idleMainLooper()
            assertEquals(PanelActivity::class.java.name, nextStarted()?.component?.className)
            download.performClick(); ShadowLooper.idleMainLooper()
            assertEquals(android.content.Intent.ACTION_VIEW, nextStarted()?.action)
        } finally {
            app.getSharedPreferences("update_lock", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    /**
     * A change from the computer while the zoom probe runs (1.1.17): it is stored at once (the
     * PATCH answers), the probe is cancelled — its verified restore — and the change reaches
     * the camera only once the probe has ended (a torch, a lens, the Zoom to fit switch mid-run
     * would end it with no restore, or spoil its frames).
     */
    @Test
    fun deviceApi_aChangeFromTheComputerDuringTheProbeWaitsForItsEnd() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.torch = false
        val cam = ProbeCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        app.measure.start(io.github.darylno.cardscanner.ui.MeasureSession.Plan.ZOOM)
        app.measure.next()
        val a = launch(MainActivity::class.java)
        findText(a.window.decorView, "Run probe")!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals(1, cam.started.size)
        cam.calls.clear()
        assertEquals(true, patchFromTheServerThread("""{"torch":true}""")["torch"])
        assertTrue("stored at once", app.settings.torch)
        assertEquals(listOf("a change from the computer"), cam.cancels)
        assertEquals("nothing reached the camera while it runs: ${cam.calls}", emptyList<String>(), cam.calls)
        patchFromTheServerThread("""{"check_ms":500}""")
        assertEquals("a second PATCH: still waiting", emptyList<String>(), cam.calls)
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.CANCELLED, "Zoom probe cancelled (a change from the computer) — nothing filed", numbered = true))
        assertTrue("applied once it ended: ${cam.calls}", cam.calls.contains("setTorch(true)"))
        assertEquals("…the mode not handed over again (no re-arm): ${cam.calls}", emptyList<String>(), cam.calls.filter { it.startsWith("setAuto(") })
        cam.calls.clear()
        patchFromTheServerThread("""{"torch":false}""")
        assertTrue("no probe: at once", cam.calls.contains("setTorch(false)"))
    }

    /**
     * The probe step's card at the N200's size with its longest words (NATIVE text): the
     * off-centre refusal with its numbers, a NOT-centred verdict naming both rules, an
     * inconclusive run with the session's tally, and the running count line — Run probe /
     * Cancel probe / Run again, Back, Stop and Next each have a size and sit on screen, the card
     * stays ≤ 45 % of the screen and the bottom row is still there.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun mainActivity_zoomProbeStep_controlsAreOnScreen() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        val cam = ProbeCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        app.measure.start()                                          // the FULL session: "Step 10 of 15"
        while (app.measure.step.id != "zoom-probe") app.measure.next()
        val a = launch(MainActivity::class.java)
        val root = a.window.decorView
        val m = app.measure
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.width, root.height)
        }
        fun onScreen(name: String, v: View) {
            val l = IntArray(2); v.getLocationInWindow(l)
            assertTrue("$name has a size (${v.width}×${v.height})", v.width > 0 && v.height > 0)
            assertTrue("$name is inside the screen (x ${l[0]}..${l[0] + v.width} of ${root.width}, y ${l[1]}..${l[1] + v.height} of ${root.height})",
                l[0] >= 0 && l[0] + v.width <= root.width && l[1] >= 0 && l[1] + v.height <= root.height)
        }
        fun check(button: String, words: String) {
            ShadowLooper.idleMainLooper(); layout()
            assertTrue("the count line: ${countLine(root)}", countLine(root).endsWith("\n$words"))
            val title = findText(root, a.getString(R.string.measure_step, m.index + 1, m.steps.size, m.step.title))!!
            var card: View = title
            while ((card.parent as View).tag != TAG_TOP_BAR) card = card.parent as View
            assertTrue("the card is ≤ 45 % of the screen (${card.height} of ${root.height} px) with: $words", card.height <= root.height * 0.45)
            onScreen("the card", card)
            onScreen(button, findText(root, button)!!)
            onScreen("Back", findText(root, a.getString(R.string.measure_back))!!)
            onScreen("Stop", findText(root, a.getString(R.string.measure_stop))!!)
            onScreen("Next", findText(root, a.getString(R.string.measure_next))!!)
            val bottom = root.findViewWithTag<View>(TAG_BOTTOM_BAR)
            onScreen("Scans", findText(bottom, a.getString(R.string.scans))!!)
            onScreen("the shutter", findDesc(bottom, a.getString(R.string.scan_card))!!)
        }
        ShadowLooper.idleMainLooper(); layout()
        onScreen("Run probe", findText(root, "Run probe")!!)
        // The longest refusal: the off-centre card, its numbers (the plan's own words).
        val offCentre = io.github.darylno.cardscanner.core.ZoomProbe.plan(floatArrayOf(546f, 594f, 984f, 594f, 984f, 1206f, 546f, 1206f),
            1200, 1600, 10.0, 1) as io.github.darylno.cardscanner.core.ZoomProbe.Refusal
        assertEquals(io.github.darylno.cardscanner.core.ZoomProbe.Why.OFF_CENTRE, offCentre.why)
        findText(root, "Run probe")!!.performClick(); ShadowLooper.idleMainLooper()
        cam.probe!!.onProgress("Zoom probe 12/15 · 2.20× — hands off the tray", "rung 12/15 · 2.20× · card 1349 px at 2.00×", 2.2)
        check("Cancel probe", "rung 12/15 · 2.20× · card 1349 px at 2.00×")
        assertTrue(offCentre.words, offCentre.words.startsWith("The card is 14 % right and 6 % below the view's centre"))
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.REFUSED, offCentre.words))
        check("Run again", offCentre.words)
        // A valid run, then one NOT centred on both rules: the combined words name run 2's.
        findText(root, "Run again")!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals("the refusal left the number", 1, cam.started.last().first)
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 1", probeResult(1)))
        findText(root, "Run again")!!.performClick(); ShadowLooper.idleMainLooper()
        val bent = probeResult(2, centred = false, fit = io.github.darylno.cardscanner.core.ZoomProbe.HFit(612.0, 3.0, 0.032, 0.0, 2.0), drift = 0.021, driftZ = 1.5)
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, bent.words(), bent))
        val notCentred = io.github.darylno.cardscanner.core.ZoomProbe.combine(listOf(probeResult(1), bent)).words
        assertTrue(notCentred, notCentred.contains("the card's size is 3.2 % off a straight line at 2.00× and the card moved 2.1 % at 1.50×"))
        check("Run again", notCentred)
        // An inconclusive run after a valid one (a fresh tally): its reason, then the tally.
        app.settings.zoomProbeRuns = null
        findText(root, "Run again")!!.performClick(); ShadowLooper.idleMainLooper()
        assertEquals(1, cam.started.last().first)
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 1", probeResult(1)))
        findText(root, "Run again")!!.performClick(); ShadowLooper.idleMainLooper()
        val stuck = probeResult(2, valid = false, centred = null, fit = null, drift = null, driftZ = null, validRungs = 9,
            reason = "only 9 of 15 rungs measured cleanly (the lens moved off the lock ×4, no answer from the camera ×2)")
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, stuck.words(), stuck))
        val inconclusive = io.github.darylno.cardscanner.core.ZoomProbe.combine(listOf(probeResult(1), stuck)).words
        assertTrue(inconclusive, inconclusive.endsWith(" · run 1 is valid: one more valid run on a DIFFERENT card decides"))
        check("Run again", inconclusive)
        // Runs on another lens and another stream before a NOT-centred one (review of a60e51d): the words
        // say which runs count apart — the longest a run's words get.
        app.settings.zoomProbeRuns = null
        findText(root, "Run again")!!.performClick(); ShadowLooper.idleMainLooper()
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 1", probeResult(1)))
        findText(root, "Run again")!!.performClick(); ShadowLooper.idleMainLooper()
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 2", probeResult(2, camera = "2")))
        findText(root, "Run again")!!.performClick(); ShadowLooper.idleMainLooper()
        val far = probeResult(3, centred = false, fit = io.github.darylno.cardscanner.core.ZoomProbe.HFit(612.0, 3.0, 0.032, 0.0, 2.0), drift = 0.021,
            driftZ = 1.5, stream = "2048×1536")
        cam.finish(probeOutcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, far.words(), far))
        val apart = io.github.darylno.cardscanner.core.ZoomProbe.combine(listOf(probeResult(1), probeResult(2, camera = "2"), far)).words
        assertTrue(apart, apart.startsWith("the zoom is NOT the centred crop") &&
            apart.endsWith(" · run 1 (camera 0 · 1600×1200), run 2 (camera 2 · 1600×1200) count apart: another camera or stream"))
        check("Run again", apart)
    }

    /** Every scan-screen control is on screen at the N200's size with a session on the zoom probe's step (its card in the top scrim). */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun mainActivity_everyControlIsOnScreen_onTheProbeStep() {
        app.measure.start(io.github.darylno.cardscanner.ui.MeasureSession.Plan.ZOOM)
        app.measure.next()
        assertEquals("zoom-probe", app.measure.step.id)
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        assertNotNull(findText(a.window.decorView, "Run probe"))
        assertEveryControlIsOnScreen(a)
    }

    /**
     * Settings → Diagnostics → "Run the zoom steps (about 10 min)": sized and on screen at
     * the N200's size (scrolled to), it starts the zoom plan — A1, the probe step, B, A2,
     * done — and opens the scan screen; while a session is on it is not offered (Resume is).
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun settings_runZoomSteps_isOnScreenAndStartsThePlan() {
        val a = launch(SettingsActivity::class.java)
        val root = a.window.decorView
        val label = a.getString(R.string.measure_run_zoom)
        assertEquals("Run the zoom steps (about 10 min)", label)
        val btn = findText(root, label)
        assertNotNull("the button", btn)
        val scroll = generateSequence(btn!!.parent) { it.parent }.filterIsInstance<android.widget.ScrollView>().first()
        val loc = IntArray(2); btn.getLocationInWindow(loc)
        val sloc = IntArray(2); scroll.getLocationInWindow(sloc)
        scroll.scrollTo(0, loc[1] - sloc[1] - 100)
        ShadowLooper.idleMainLooper()
        val l = IntArray(2); btn.getLocationInWindow(l)
        assertTrue("it has a size (${btn.width}×${btn.height})", btn.width > 0 && btn.height > 0)
        assertTrue("it is inside the screen (y ${l[1]}..${l[1] + btn.height} of ${root.height}, x ${l[0]}..${l[0] + btn.width} of ${root.width})",
            l[1] >= 0 && l[1] + btn.height <= root.height && l[0] >= 0 && l[0] + btn.width <= root.width)
        while (nextStarted() != null) { }
        btn.performClick()
        assertTrue(app.measure.active)
        assertEquals(listOf("zoom-a1", "zoom-probe", "zoom-b", "zoom-a2", "zoom-done"), app.measure.steps.map { it.id })
        assertEquals("zoom-a1", app.measure.step.id)
        val started = nextStarted()
        assertEquals(MainActivity::class.java.name, started?.component?.className)
        assertTrue("onto the scan screen already below", started!!.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        val again = launch(SettingsActivity::class.java)
        assertEquals("not offered while a session is on", null, findText(again.window.decorView, label))
        assertNotNull(findText(again.window.decorView, again.getString(R.string.measure_resume)))
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

    /**
     * Settings → Camera (owner, 2026-10-02: testers bring other phones): the chooser
     * lists Automatic plus this phone's back cameras with what tells them apart; a pick
     * is saved and the row shows it; Automatic clears it.
     */
    @Test
    fun settings_cameraChooser_listsTheLensesAndSavesThePick() {
        addN200Cameras()
        app.settings.cameraId = null
        val a = launch(SettingsActivity::class.java)
        fun openChooser(): androidx.appcompat.app.AlertDialog {
            var v: View? = findText(a.window.decorView, a.getString(R.string.settings_lens))
            while (v != null && !v.isClickable) v = v.parent as? View
            assertNotNull("a clickable Camera row", v)
            v!!.performClick()
            ShadowLooper.idleMainLooper()
            return org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        }
        assertNotNull("the row reads Automatic", findText(a.window.decorView, a.getString(R.string.settings_lens_auto)))
        val d = openChooser()
        val list = d.listView
        val items = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
        assertEquals(listOf(a.getString(R.string.settings_lens_auto_item, "0"),
            "Camera 0 · back · 13 MP · autofocus", "Camera 3 · back · 1.9 MP · fixed focus"), items)
        list.performItemClick(list.getChildAt(2) ?: list, 2, list.adapter.getItemId(2))
        ShadowLooper.idleMainLooper()
        assertEquals("3", app.settings.cameraId)
        assertNotNull("the row shows the pick", findText(a.window.decorView, a.getString(R.string.settings_lens_value, "3")))
        val d2 = openChooser()
        d2.listView.performItemClick(d2.listView.getChildAt(0) ?: d2.listView, 0, d2.listView.adapter.getItemId(0))
        ShadowLooper.idleMainLooper()
        assertEquals(null, app.settings.cameraId)
    }

    /** Robolectric's camera manager with the N200's lenses: 0 main, 1 front, 3 the 2 MP fixed-focus module. */
    private fun addN200Cameras() {
        fun add(id: String, facing: Int, w: Int, h: Int, minFocus: Float) {
            val ch = org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics()
            val sh = shadowOf(ch) as org.robolectric.shadows.ShadowCameraCharacteristics
            sh.set(android.hardware.camera2.CameraCharacteristics.LENS_FACING, facing)
            sh.set(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, android.util.Size(w, h))
            sh.set(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE, minFocus)
            shadowOf(app.getSystemService(android.hardware.camera2.CameraManager::class.java)).addCamera(id, ch)
        }
        add("0", android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK, 4096, 3072, 10f)
        add("1", android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT, 4608, 3456, 0f)
        add("3", android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK, 1600, 1200, 0f)
    }

    /**
     * The camera pill on the live view (owner, 2026-10-02: "a pill that you swipe
     * around"): swipe left = next, right = previous, tap = next, Automatic is a stop;
     * each change is saved at once. A phone with a single lens shows no pill.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun mainActivity_cameraPill_swipesThroughTheLenses() {
        addN200Cameras()
        app.settings.cameraId = null
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        val top = a.window.decorView.findViewWithTag<View>(TAG_TOP_BAR)
        val pill = findText(top, "‹  Auto · 13 MP  ›")
        assertNotNull("the pill reads Automatic", pill)
        assertTrue("and is on screen", pill!!.isShown && pill.height > 0)
        fun swipe(dx: Float) {
            val t = android.os.SystemClock.uptimeMillis()
            val x = pill.width / 2f; val y = pill.height / 2f
            pill.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
            pill.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 50, android.view.MotionEvent.ACTION_MOVE, x + dx / 2, y, 0))
            pill.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 100, android.view.MotionEvent.ACTION_UP, x + dx, y, 0))
            ShadowLooper.idleMainLooper()
        }
        val far = 40 * a.resources.displayMetrics.density
        swipe(-far)
        assertEquals("swipe left → the next stop, camera 0", "0", app.settings.cameraId)
        assertEquals("‹  Cam 0 · 13 MP  ›", pill.text.toString())
        swipe(-far)
        assertEquals("3", app.settings.cameraId)
        assertEquals("‹  Cam 3 · 1.9 MP fixed  ›", pill.text.toString())
        swipe(far)
        assertEquals("swipe right → back to camera 0", "0", app.settings.cameraId)
        swipe(0f)
        assertEquals("a tap steps forward", "3", app.settings.cameraId)
        swipe(-far)
        assertEquals("wraps round to Automatic", null, app.settings.cameraId)
        swipe(-5 * a.resources.displayMetrics.density)
        assertEquals("a tiny move is a tap", "0", app.settings.cameraId)
        app.settings.cameraId = null
    }

    @Test
    fun mainActivity_cameraPill_hiddenWithOneLens() {
        app.settings.cameraId = null
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        val top = a.window.decorView.findViewWithTag<View>(TAG_TOP_BAR)
        val pill = findText(top, "‹  Auto  ›")
        assertNotNull(pill)
        assertEquals("no lenses listed → no pill", View.GONE, pill!!.visibility)
    }

    /**
     * "Zoom to fit the Area" (1.1.14): the Settings → Camera switch is there, OFF by
     * default, sized and on screen at the N200's size (scrolled to), and saves the setting.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun settings_zoomToFitSwitch_isOnScreenAndSaves() {
        app.settings.zoomFit = false
        val a = launch(SettingsActivity::class.java)
        val root = a.window.decorView
        val title = findText(root, a.getString(R.string.settings_zoomfit))
        assertNotNull("the switch row", title)
        var row: View = title!!
        while (!row.isClickable) row = row.parent as View
        val sw = findSwitch(row)
        assertNotNull("a switch in the row", sw)
        assertFalse("off by default", sw!!.isChecked)
        // Scroll it into view, then it must have a size and sit inside the screen.
        val scroll = generateSequence(row.parent) { it.parent }.filterIsInstance<android.widget.ScrollView>().first()
        val loc = IntArray(2); row.getLocationInWindow(loc)
        val sloc = IntArray(2); scroll.getLocationInWindow(sloc)
        scroll.scrollTo(0, loc[1] - sloc[1] - 100)
        ShadowLooper.idleMainLooper()
        val screenH = root.height; val screenW = root.width
        for ((name, v) in listOf("row" to row, "switch" to sw)) {
            val l = IntArray(2); v.getLocationInWindow(l)
            assertTrue("$name has a size (${v.width}×${v.height})", v.width > 0 && v.height > 0)
            assertTrue("$name is inside the screen (y ${l[1]}..${l[1] + v.height} of $screenH, x ${l[0]}..${l[0] + v.width} of $screenW)",
                l[1] >= 0 && l[1] + v.height <= screenH && l[0] >= 0 && l[0] + v.width <= screenW)
        }
        row.performClick()
        assertEquals(true, app.settings.zoomFit)
        row.performClick()
        assertEquals(false, app.settings.zoomFit)
    }

    /**
     * Settings → Screen → "Dim when idle" (1.1.15): the row is sized and on screen at the
     * N200's size (scrolled to), reads "After 1 min" by default, and the chooser offers
     * 30 s / 1 / 2 / 5 min / Never and saves the pick.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun settings_dimWhenIdle_isOnScreenAndSaves() {
        app.settings.dimAfterS = io.github.darylno.cardscanner.core.ScreenDim.DEFAULT_S
        val a = launch(SettingsActivity::class.java)
        val root = a.window.decorView
        val title = findText(root, a.getString(R.string.settings_dim))
        assertNotNull("the Dim when idle row", title)
        var row: View = title!!
        while (!row.isClickable) row = row.parent as View
        assertNotNull("reads After 1 min", findText(row, a.getString(R.string.settings_dim_after, "1 min")))
        val scroll = generateSequence(row.parent) { it.parent }.filterIsInstance<android.widget.ScrollView>().first()
        val loc = IntArray(2); row.getLocationInWindow(loc)
        val sloc = IntArray(2); scroll.getLocationInWindow(sloc)
        scroll.scrollTo(0, loc[1] - sloc[1] - 100)
        ShadowLooper.idleMainLooper()
        val l = IntArray(2); row.getLocationInWindow(l)
        assertTrue("the row has a size (${row.width}×${row.height})", row.width > 0 && row.height > 0)
        assertTrue("the row is inside the screen (y ${l[1]}..${l[1] + row.height} of ${root.height})",
            l[1] >= 0 && l[1] + row.height <= root.height && l[0] >= 0 && l[0] + row.width <= root.width)
        row.performClick()
        ShadowLooper.idleMainLooper()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val list = d.listView
        val items = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
        assertEquals(listOf("After 30 s", "After 1 min", "After 2 min", "After 5 min", a.getString(R.string.settings_dim_never_item)), items)
        list.performItemClick(list.getChildAt(0) ?: list, 0, list.adapter.getItemId(0))
        ShadowLooper.idleMainLooper()
        assertEquals(30, app.settings.dimAfterS)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31))
        assertTrue("30 s applies to the Settings screen itself, at once", a.awake.dimmed)
        var row2: View = findText(a.window.decorView, a.getString(R.string.settings_dim))!!
        while (!row2.isClickable) row2 = row2.parent as View
        row2.performClick()
        ShadowLooper.idleMainLooper()
        val d2 = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        d2.listView.performItemClick(d2.listView.getChildAt(4) ?: d2.listView, 4, d2.listView.adapter.getItemId(4))
        ShadowLooper.idleMainLooper()
        assertEquals(0, app.settings.dimAfterS)
        assertNotNull("the row reads Never", findText(a.window.decorView, a.getString(R.string.settings_dim_never)))
        assertFalse("Never: bright again at once", a.awake.dimmed)
    }

    /**
     * The Area chip says when the camera is zoomed ("Area ✓ · 1.55×", the longest label)
     * and still fits on screen at the N200's size; drawing a new Area reads Cancel, and
     * Cancel comes back to the zoomed label.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun mainActivity_zoomedAreaChip_isOnScreen() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.2, 0.75, 0.8)
        app.settings.auto = false
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        val root = a.window.decorView
        val bottom = root.findViewWithTag<View>(TAG_BOTTOM_BAR)
        assertNotNull(findText(bottom, a.getString(R.string.area_set)))
        val view = io.github.darylno.cardscanner.core.ZoomFit.toView(app.settings.roi, 1.55)
        a.cameraListenerForTest.onZoom(1.55, view, false)
        ShadowLooper.idleMainLooper()
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.width, root.height)
        val label = a.getString(R.string.area_zoomed, "1.55")
        val chip = findText(bottom, label)
        assertNotNull("the chip reads $label", chip)
        val l = IntArray(2); chip!!.getLocationInWindow(l)
        assertTrue("the chip has a size (${chip.width}×${chip.height})", chip.width > 0 && chip.height > 0)
        assertTrue("the chip is inside the screen (x ${l[0]}..${l[0] + chip.width} of ${root.width}, y ${l[1]}..${l[1] + chip.height} of ${root.height})",
            l[0] >= 0 && l[0] + chip.width <= root.width && l[1] >= 0 && l[1] + chip.height <= root.height)
        assertTrue("the whole label fits (${chip.paint.measureText(label)} px in ${chip.width - chip.totalPaddingLeft - chip.totalPaddingRight})",
            chip.paint.measureText(label) <= chip.width - chip.totalPaddingLeft - chip.totalPaddingRight + 1)
        // Drawing a new Area (Tap to scan: the chip draws): Cancel, then back to the zoomed label.
        chip.performClick()
        ShadowLooper.idleMainLooper()
        assertNotNull("drawing reads Cancel", findText(bottom, a.getString(R.string.cancel)))
        findText(bottom, a.getString(R.string.cancel))!!.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("no longer drawing", null, findText(bottom, a.getString(R.string.cancel)))
        a.cameraListenerForTest.onZoom(1.55, view, false)        // the camera is back at the Area's zoom
        ShadowLooper.idleMainLooper()
        assertNotNull(findText(bottom, label))
    }

    /**
     * Review of 1.1.14: the phone server answers a PATCH /api/device on ITS thread
     * right after storing the settings, and the scan screen applies them on the UI
     * thread — so the answer's `zoom` was the one from BEFORE the change ("Zoom to
     * fit is off" right after ticking it, the old 1.90× after unticking it) until the
     * panel's next poll. The bridge now waits (≤ 500 ms) for the screen to apply.
     * The PATCH runs on a background thread, as NanoHTTPD's does; this thread is the
     * UI thread, running what the bridge waits for.
     */
    @Test
    fun deviceApi_patchAnswersWithTheZoomAfterTheChange() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = false
        app.settings.zoomFit = false
        grantCamera(true)
        launch(MainActivity::class.java)
        fun zoomOf(m: Map<*, *>) = m["zoom"] as Map<*, *>
        val on = patchFromTheServerThread("""{"zoom_fit":true}""")
        assertEquals(true, on["zoom_fit"])
        assertEquals("the answer is the zoom AFTER the change: $on", 1.9, (zoomOf(on)["target"] as Number).toDouble(), 0.0)
        assertEquals("the Area fit", zoomOf(on)["limit"])
        val off = patchFromTheServerThread("""{"zoom_fit":false}""")
        assertEquals(false, off["zoom_fit"])
        assertEquals("$off", 1.0, (zoomOf(off)["target"] as Number).toDouble(), 0.0)
        assertEquals("Zoom to fit is off", zoomOf(off)["limit"])
    }

    /**
     * PATCH /api/device the way the phone server does: on a background thread (as
     * NanoHTTPD's), while this thread — the UI thread — runs what the bridge waits for.
     */
    private fun patchFromTheServerThread(body: String): Map<*, *> {
        val api = io.github.darylno.cardscanner.core.server.DeviceApi(app.phoneServer.device)
        var answer: io.github.darylno.cardscanner.core.server.ApiResponse? = null
        val t = Thread {
            answer = api.handle(io.github.darylno.cardscanner.core.server.ApiRequest("PATCH", "/api/device", body = body.toByteArray()))
        }
        t.start()
        while (t.isAlive) { ShadowLooper.idleMainLooper(); t.join(5) }
        val r = answer!!
        assertEquals(200, r.status)
        return io.github.darylno.cardscanner.core.MiniJson.parse(String(r.body)) as Map<*, *>
    }

    /** A camera that records what the scan screen asks of it (Robolectric has none to bind). */
    private class RecordingCamera : io.github.darylno.cardscanner.ui.CameraPort {
        val calls = mutableListOf<String>()
        /** Every setAuto since the last [calls] clear, in order. */
        val autos get() = calls.filter { it.startsWith("setAuto(") }
        override fun bind(owner: LifecycleOwner, preview: androidx.camera.view.PreviewView,
                          listener: io.github.darylno.cardscanner.ui.CameraPort.Listener) { calls += "bind" }
        override fun rebind() { calls += "rebind" }
        override fun unbind() { calls += "unbind" }
        override fun setAuto(on: Boolean) { calls += "setAuto($on)" }
        override fun setRoi(roi: io.github.darylno.cardscanner.core.RoiFrac?) { calls += "setRoi(${roi?.encode()})" }
        override fun setZoomFit(on: Boolean) { calls += "setZoomFit($on)" }
        override fun relearn() { calls += "relearn" }
        override fun pause(paused: Boolean) { calls += "pause($paused)" }
        override fun manualScan() { calls += "manualScan" }
        override fun tapFocus(x: Float, y: Float) { calls += "tapFocus" }
        override fun noCard(scene: io.github.darylno.cardscanner.core.Gray?) { calls += "noCard" }
        override fun setTorch(on: Boolean) { calls += "setTorch($on)" }
        override fun setAeLock(on: Boolean) { calls += "setAeLock($on)" }
        override fun setHighRes(high: Boolean) { calls += "setHighRes($high)" }
        override fun setCamera(id: String?) { calls += "setCamera($id)" }
        override fun diagnostics(): String = "recording camera"
    }

    /**
     * A [RecordingCamera] with the real zoom coordinator behind it, as CameraController
     * has: the Area given to the camera is the coordinator's, and so is the zoom state
     * the scan screen reads (a lens with lossless 2.6× and a 10× max, the switch off).
     */
    private class ZoomStateCamera(val rec: RecordingCamera = RecordingCamera()) : io.github.darylno.cardscanner.ui.CameraPort by rec {
        /** The last zoom request (gen, ratio): never answered unless a test answers it. */
        var asked: Pair<Int, Double>? = null
        val zoom = io.github.darylno.cardscanner.core.ZoomCoordinator(object : io.github.darylno.cardscanner.core.ZoomCoordinator.Port {
            override fun request(gen: Int, ratio: Double) { asked = gen to ratio }
            override fun closeGate(why: String) {}
            override fun map(ratio: Double, view: io.github.darylno.cardscanner.core.RoiFrac?, settle: Boolean, why: String) {}
            override fun drawingReady(ratio: Double) {}
            override fun later(ms: Long, block: () -> Unit) {}
            override fun readBack(): Double? = null
        })
        /** The scan screen's camera listener: the test delivers the scanner's ticks through it. */
        var listener: io.github.darylno.cardscanner.ui.CameraPort.Listener? = null
        override fun bind(owner: LifecycleOwner, preview: androidx.camera.view.PreviewView,
                          listener: io.github.darylno.cardscanner.ui.CameraPort.Listener) {
            rec.bind(owner, preview, listener); this.listener = listener; zoom.cameraBound(2.6, 10.0)
        }
        override fun setRoi(roi: io.github.darylno.cardscanner.core.RoiFrac?) { rec.setRoi(roi); zoom.setArea(roi) }
        override fun setZoomFit(on: Boolean) { rec.setZoomFit(on); zoom.setEnabled(on) }
        override fun zoomState() = zoom.state()
    }

    /**
     * The off-centre hint (1.1.17) after an Area is drawn on the phone: an Area hugging
     * the right edge zooms 1× (its fit allows only 1.06×), the same Area centred 1.90×
     * — the status line says so. Laid out at the N200's size, the longest status this
     * adds sits whole inside the status pill (≤ its 2 lines, nothing ellipsized) and
     * the pill on screen; the bottom row is still there. NATIVE graphics: Robolectric's
     * legacy text measures every character as 1 px, so the pill just grew to fit it
     * (92 px for 92 characters) and the check proved nothing.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun mainActivity_offCentreAreaStatus_fits() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = false
        val cam = ZoomStateCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        val root = a.window.decorView
        val overlay = findOverlay(root)!!
        // A centred Area says what it always said.
        overlay.onAreaDrawn!!.invoke(io.github.darylno.cardscanner.core.RoiFrac(0.3, 0.3, 0.7, 0.7))
        ShadowLooper.idleMainLooper()
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.AREA_SET, a.statusForTest)
        // The drag ended on an Area hugging the right edge.
        overlay.onAreaDrawn!!.invoke(io.github.darylno.cardscanner.core.RoiFrac(0.45, 0.25, 0.95, 0.75))
        ShadowLooper.idleMainLooper()
        assertEquals("setRoi(0.45,0.25,0.95,0.75)", cam.rec.calls.last { it.startsWith("setRoi(") })
        // Zoom to fit is OFF (this build's default): nothing zooms either way, so the hint says the condition
        // (review of a43cb5b: "it would zoom 1.90×" read as a fact with the switch off).
        val expected = "Scan area set — centred, Zoom to fit would zoom 1.90× (here 1.00×). Empty the tray to re-learn…"
        assertEquals(expected, a.statusForTest)
        assertStatusFitsThePill(a, expected)
    }

    /** The off-centre hint with Zoom to fit ON: "centred, it would zoom 1.90× (this Area: 1.00×)", in the 2-line pill. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun mainActivity_offCentreAreaStatus_switchOn_fits() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = true
        val cam = ZoomStateCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        val overlay = findOverlay(a.window.decorView)!!
        overlay.onAreaDrawn!!.invoke(io.github.darylno.cardscanner.core.RoiFrac(0.45, 0.25, 0.95, 0.75))
        ShadowLooper.idleMainLooper()
        val expected = "Scan area set — centred, it would zoom 1.90× (this Area: 1.00×). Empty the tray to re-learn…"
        assertEquals(expected, a.statusForTest)
        assertStatusFitsThePill(a, expected)
    }

    /**
     * Review of 39ef7cf: in Tray the "Scan area set — centred, it would zoom …" line was
     * gone within one analyzer tick — the new Area re-learns the tray, and the scanner's
     * very next tick (Learning, ~200 ms) put "Tray: waiting for a steady view…" over it,
     * then "watching for a card…". Tray is where the hint matters most (switching to Tray
     * asks for the box). The hint now stays: the area-set line while the tray is learned,
     * then on "watching for a card…" — until the next capture or another Area. Each fits
     * the 2-line pill at the N200's size (NATIVE graphics: real text widths).
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun mainActivity_offCentreHint_staysOnTheTrayLines() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = false
        val cam = ZoomStateCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        val root = a.window.decorView
        val overlay = findOverlay(root)!!
        val l = cam.listener!!
        fun tick(e: io.github.darylno.cardscanner.core.AutoScanner.Event) {
            l.onDetection(e, 176, 235, null); ShadowLooper.idleMainLooper()
        }
        val learning = io.github.darylno.cardscanner.core.AutoScanner.Event.Learning(false)
        val learned = io.github.darylno.cardscanner.core.AutoScanner.Event.Learning(true)
        val edge = io.github.darylno.cardscanner.core.RoiFrac(0.45, 0.25, 0.95, 0.75)
        val set = "Scan area set — centred, Zoom to fit would zoom 1.90× (here 1.00×). Empty the tray to re-learn…"
        val watching = "Tray: watching for a card… Off-centre Area: centred, Zoom to fit would zoom 1.90× (here 1.00×)."
        overlay.onAreaDrawn!!.invoke(edge)
        ShadowLooper.idleMainLooper()
        assertEquals(set, a.statusForTest)
        tick(learning); tick(learning)
        assertEquals("the scanner's next ticks (learning the new Area's tray) keep the hint", set, a.statusForTest)
        tick(learned)
        assertEquals("the tray is learned: watching, with the hint", watching, a.statusForTest)
        assertStatusFitsThePill(a, watching)
        tick(learned)                                                   // a re-learn (double-tap): still said
        assertEquals(watching, a.statusForTest)
        // The first capture ends it: the Tray's own lines again.
        l.onCaptureStarted(1L, false); ShadowLooper.idleMainLooper()
        tick(learned)
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.WATCHING, a.statusForTest)
        tick(learning)
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.WAITING, a.statusForTest)
        // Another Area ends it too: drawn again, then cleared to the full frame (Tray's Area button).
        overlay.onAreaDrawn!!.invoke(edge)
        ShadowLooper.idleMainLooper()
        tick(learned)
        assertEquals(watching, a.statusForTest)
        findText(root, a.getString(R.string.area_set))!!.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.AREA_CLEARED, a.statusForTest)
        tick(learning)
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.WAITING, a.statusForTest)
        tick(learned)
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.WATCHING, a.statusForTest)
        // A centred Area has no hint: the old lines, unchanged.
        overlay.onAreaDrawn!!.invoke(io.github.darylno.cardscanner.core.RoiFrac(0.3, 0.3, 0.7, 0.7))
        ShadowLooper.idleMainLooper()
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.AREA_SET, a.statusForTest)
        tick(learning)
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.WAITING, a.statusForTest)
        tick(learned)
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.WATCHING, a.statusForTest)
    }

    /**
     * Laid out at the activity's size: [expected] is the status pill's text, it sits whole
     * inside the pill (≤ its lines, nothing ellipsized, within its width and height), the
     * pill on screen, the text long enough to need its 2 lines (so real widths were
     * measured), and the bottom row still on screen under it.
     */
    private fun assertStatusFitsThePill(a: MainActivity, expected: String) {
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.width, root.height)
        val pill = findText(root, expected)!!
        val l = IntArray(2); pill.getLocationInWindow(l)
        assertTrue("the pill has a size (${pill.width}×${pill.height})", pill.width > 0 && pill.height > 0)
        assertTrue("the pill is inside the screen (x ${l[0]}..${l[0] + pill.width} of ${root.width}, y ${l[1]}..${l[1] + pill.height} of ${root.height})",
            l[0] >= 0 && l[0] + pill.width <= root.width && l[1] >= 0 && l[1] + pill.height <= root.height)
        val lay = pill.layout
        assertNotNull("laid out", lay)
        val innerW = pill.width - pill.totalPaddingLeft - pill.totalPaddingRight
        val innerH = pill.height - pill.totalPaddingTop - pill.totalPaddingBottom
        assertTrue("within the pill's ${pill.maxLines} lines (${lay.lineCount})", lay.lineCount <= pill.maxLines)
        for (i in 0 until lay.lineCount) {
            assertEquals("line $i is not ellipsized", 0, lay.getEllipsisCount(i))
            // getLineMax: the ink, without the trailing space a wrapped line may end on (it hangs past the edge
            // unseen; getLineWidth counts it — a line ending "… — " read 3 px wide).
            assertTrue("line $i fits the pill's width (${lay.getLineMax(i)} of $innerW px)", lay.getLineMax(i) <= innerW + 1)
        }
        assertTrue("the text fits the pill's height (${lay.height} of $innerH px)", lay.height <= innerH + 1)
        val wide = pill.paint.measureText(expected)
        assertTrue("the status is long enough to need its 2 lines here ($wide px vs $innerW): the check measures real text",
            wide > innerW)
        // The bottom row is still on screen under the 2-line status.
        val bottom = root.findViewWithTag<View>(TAG_BOTTOM_BAR)
        for (v in listOf(findText(bottom, a.getString(R.string.scans))!!, findDesc(bottom, a.getString(R.string.scan_card))!!)) {
            val p = IntArray(2); v.getLocationInWindow(p)
            assertTrue("${v.contentDescription ?: (v as? TextView)?.text} on screen", v.height > 0 && p[1] >= 0 && p[1] + v.height <= root.height)
        }
    }

    private fun findOverlay(v: View): io.github.darylno.cardscanner.ui.OverlayView? {
        if (v is io.github.darylno.cardscanner.ui.OverlayView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findOverlay(v.getChildAt(i))?.let { return it }
        return null
    }

    /**
     * Found while checking the zoom probe (1.1.17): EVERY desktop PATCH re-armed Tray on
     * a card that had already scanned. The scan screen re-applied the mode on any change
     * — a torch, a check time, the 📱 Scanner panel's zoom box — and setAuto(true) puts
     * the scanner back to WATCHING (AutoScanner.setAuto), so the card still sitting there
     * in AWAIT_NEXT triggered again two steady ticks later and was filed twice. Only a
     * PATCH that changes the mode may reach the camera's setAuto, once.
     */
    @Test
    fun deviceApi_aPatchThatKeepsTheModeNeverReArmsTray() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = false
        val cam = RecordingCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        launch(MainActivity::class.java)
        assertEquals("the scan screen starts its camera in Tray: ${cam.calls}", listOf("setAuto(true)"), cam.autos)
        for (body in listOf("""{"torch":true}""", """{"check_ms":500}""", """{"zoom_fit":true}""", """{"torch":false}""")) {
            cam.calls.clear()
            patchFromTheServerThread(body)
            assertTrue("the PATCH $body reached the camera: ${cam.calls}", cam.calls.isNotEmpty())
            assertEquals("a PATCH that keeps the mode ($body) never re-arms Tray: ${cam.calls}", emptyList<String>(), cam.autos)
        }
        cam.calls.clear()
        assertEquals("tap", patchFromTheServerThread("""{"mode":"tap"}""")["mode"])
        assertEquals("a mode change reaches the camera exactly once: ${cam.calls}", listOf("setAuto(false)"), cam.autos)
        cam.calls.clear()
        patchFromTheServerThread("""{"torch":true}""")
        assertEquals("…and a PATCH after it that keeps Tap to scan stays quiet: ${cam.calls}", emptyList<String>(), cam.autos)
        cam.calls.clear()
        assertEquals("tray", patchFromTheServerThread("""{"mode":"tray"}""")["mode"])
        assertEquals("back to Tray from the computer: exactly once: ${cam.calls}", listOf("setAuto(true)"), cam.autos)
    }

    /**
     * The same rule for the update lock (found by the 1.1.17 review): lifting it (only a
     * rolled-back release does, on a running build) ran applyMode() with the default
     * pushAuto = true, so camera.setAuto(true) → AutoScanner.setAuto(true) put a card that
     * had already scanned (AWAIT_NEXT) back to WATCHING, and it was filed a second time two
     * steady ticks later. The lift must un-pause the scanner and clear "Update required"
     * without handing the unchanged mode over again.
     */
    @Test
    fun updateLock_liftingItNeverReArmsTray() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = false
        val prefs = app.getSharedPreferences("update_lock", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("version", "v99.0.0")
            .putString("apk_url", "https://github.com/DarylNo/CardScanner/releases/download/v99.0.0/mtg-card-scanner-android.apk")
            .putLong("checked_at", System.currentTimeMillis()).commit()
        try {
            val cam = RecordingCamera()
            app.cameraFactory = { _, _ -> cam }
            grantCamera(true)
            val a = launch(MainActivity::class.java)
            assertTrue("locked at start", app.updates.locked)
            assertEquals("the scan screen starts its camera in Tray: ${cam.calls}", listOf("setAuto(true)"), cam.autos)
            assertTrue("scanning paused under the lock: ${cam.calls}", "pause(true)" in cam.calls)
            assertEquals(a.getString(R.string.update_required_status), a.statusForTest)
            // A rolled-back release: the latest seen is older than this build again.
            prefs.edit().putString("version", "v0.0.1").commit()
            assertFalse(app.updates.locked)
            cam.calls.clear()
            a.updateLockChangedForTest(); ShadowLooper.idleMainLooper()
            assertEquals("lifting the lock never re-arms Tray: ${cam.calls}", emptyList<String>(), cam.autos)
            assertEquals("…but it does resume scanning: ${cam.calls}", "pause(false)", cam.calls.lastOrNull { it.startsWith("pause(") })
            assertEquals("…and the mode's own line replaces \"Update required\"",
                io.github.darylno.cardscanner.ui.StatusText.WAITING, a.statusForTest)
            assertFalse("the lock is gone", findText(a.window.decorView, a.getString(R.string.update_download))?.isShown == true)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    /** UpdateLock's stored answer: a release newer than this build is out (the lock comes up). */
    private fun storeNewerRelease() {
        app.getSharedPreferences("update_lock", android.content.Context.MODE_PRIVATE).edit()
            .putString("version", "v99.0.0")
            .putString("apk_url", "https://github.com/DarylNo/CardScanner/releases/download/v99.0.0/mtg-card-scanner-android.apk")
            .putLong("checked_at", System.currentTimeMillis()).commit()
    }

    /** A rolled-back release: the latest seen is older than this build again (the lock lifts). */
    private fun storeOlderRelease() {
        app.getSharedPreferences("update_lock", android.content.Context.MODE_PRIVATE).edit()
            .putString("version", "v0.0.1").commit()
    }

    /** The status pill as it is now (the TextView showing the status line). */
    private fun statusPill(a: MainActivity): TextView = findText(a.window.decorView, a.statusForTest)!!

    /**
     * Review of e418d1c: lifting the lock no longer re-armed Tray, but it still wrote the mode's
     * line — "Tray: waiting for a steady view…" — over a scanner still in AWAIT_NEXT on the card
     * it had filed (the lock only paused detection; AutoScanner keeps its state), and AwaitingNext
     * ticks never rewrite the line, so it stayed up telling the owner the tray was empty: re-seat
     * the card and it is filed again. The same contradiction 1.1.17 fixed for the PATCH path
     * ([deviceApi_aPatchThatKeepsTheModeKeepsTheStatusLine]). The lift puts back the line the lock
     * covered, in its own colour.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun updateLock_liftingItPutsBackTheScannersLine() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = false
        try {
            val cam = RecordingCamera()
            app.cameraFactory = { _, _ -> cam }
            grantCamera(true)
            val a = launch(MainActivity::class.java)
            assertFalse("unlocked at start", app.updates.locked)
            val l = a.cameraListenerForTest
            val box = io.github.darylno.cardscanner.core.Box(40, 30, 60, 84, 0.2)
            l.onDetection(io.github.darylno.cardscanner.core.AutoScanner.Event.Trigger(box,
                io.github.darylno.cardscanner.core.Gray(176, 132, IntArray(176 * 132))), 176, 132, null)
            l.onDetection(io.github.darylno.cardscanner.core.AutoScanner.Event.AwaitingNext(box), 176, 132, null)
            ShadowLooper.idleMainLooper()
            val scanned = io.github.darylno.cardscanner.ui.StatusText.CAPTURING
            assertEquals(scanned, a.statusForTest)
            // A newer release is seen: the lock comes up over the scanner's line.
            storeNewerRelease()
            assertTrue(app.updates.locked)
            a.updateLockChangedForTest(); ShadowLooper.idleMainLooper()
            assertEquals(a.getString(R.string.update_required_status), a.statusForTest)
            assertEquals("scanning paused under the lock: ${cam.calls}", "pause(true)", cam.calls.lastOrNull { it.startsWith("pause(") })
            // Rolled back: the lock lifts.
            storeOlderRelease()
            assertFalse(app.updates.locked)
            cam.calls.clear()
            a.updateLockChangedForTest(); ShadowLooper.idleMainLooper()
            assertEquals("lifting the lock never re-arms Tray: ${cam.calls}", emptyList<String>(), cam.autos)
            assertEquals("…it resumes scanning: ${cam.calls}", "pause(false)", cam.calls.lastOrNull { it.startsWith("pause(") })
            assertEquals("…and the line the lock covered is back: the scanner still waits for that card to leave",
                scanned, a.statusForTest)
            assertEquals("…in its own colour", io.github.darylno.cardscanner.ui.ScanChrome.Palette.TEXT, statusPill(a).currentTextColor)
            // The scanner, still in AWAIT_NEXT, says nothing new: the line stays true.
            l.onDetection(io.github.darylno.cardscanner.core.AutoScanner.Event.AwaitingNext(box), 176, 132, null)
            ShadowLooper.idleMainLooper()
            assertEquals(scanned, a.statusForTest)
        } finally {
            app.getSharedPreferences("update_lock", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    /**
     * The same lift over "✗ No match — Retry with a fresh capture?": the lock hides Retry (it
     * refuses it), so putting the line back without its Retry would invite a tap on nothing.
     * Line, colour and Retry come back together — the card it was for is still the current one
     * (nothing was captured while the lock paused detection).
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun updateLock_liftingItPutsBackANoMatchWithItsRetry() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = false
        val jobs = java.util.concurrent.LinkedBlockingQueue<String>()
        // The scan screen's upload queue, answering at once with the job's id (App's own would persist
        // the capture and its worker answer it later — this test hands the outcome over itself).
        val uploads = object : io.github.darylno.cardscanner.ui.UploadPort {
            override fun enqueue(capture: io.github.darylno.cardscanner.ui.Captured, manual: Boolean, openScan: Boolean,
                                 replaceScanId: Long?): String = "job-${jobs.size + 1}".also { jobs.add(it) }
            override fun retryNow() {}
            override fun addListener(l: io.github.darylno.cardscanner.ui.UploadPort.Listener) {}
            override fun removeListener(l: io.github.darylno.cardscanner.ui.UploadPort.Listener) {}
            override val pending: Int get() = 0
        }
        App::class.java.getDeclaredField("uploads").apply { isAccessible = true }.set(app, uploads)
        try {
            val cam = RecordingCamera()
            app.cameraFactory = { _, _ -> cam }
            grantCamera(true)
            val a = launch(MainActivity::class.java)
            val root = a.window.decorView
            fun retryShown() = findText(root, a.getString(R.string.retry))?.isShown == true
            // A Tray capture, queued as job-1 (the current card), comes back with no match: Retry is offered.
            a.cameraListenerForTest.onCaptured(io.github.darylno.cardscanner.ui.Captured(ByteArray(0), emptyList(), false, "t", null,
                burstId = 1), false)
            val deadline = System.currentTimeMillis() + 5000
            while (a.lastCaptureJobIdForTest != "job-1" && System.currentTimeMillis() < deadline) {
                ShadowLooper.idleMainLooper(); Thread.sleep(2)
            }
            assertEquals("the capture is the current card", "job-1", a.lastCaptureJobIdForTest)
            val scan = org.json.JSONObject().put("error", "No match.")
            a.uploadListenerForTest.onOutcome("job-1", false, false, null, io.github.darylno.cardscanner.ui.Outcome.NoMatch(scan, 61, false))
            ShadowLooper.idleMainLooper()
            val noMatch = io.github.darylno.cardscanner.ui.StatusText.noMatch(scan)
            assertEquals(noMatch, a.statusForTest)
            assertTrue("Retry offered", retryShown())
            storeNewerRelease()
            a.updateLockChangedForTest(); ShadowLooper.idleMainLooper()
            assertEquals(a.getString(R.string.update_required_status), a.statusForTest)
            assertFalse("the lock hides Retry (it refuses it)", retryShown())
            storeOlderRelease()
            cam.calls.clear()
            a.updateLockChangedForTest(); ShadowLooper.idleMainLooper()
            assertEquals("lifting the lock never re-arms Tray: ${cam.calls}", emptyList<String>(), cam.autos)
            assertTrue("the lift gives back the Retry the lock hid", retryShown())
            assertEquals("…with its line", noMatch, a.statusForTest)
            assertEquals("…in its own colour", io.github.darylno.cardscanner.ui.ScanChrome.Palette.ERR, statusPill(a).currentTextColor)
        } finally {
            app.getSharedPreferences("update_lock", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    /**
     * A line written while the lock was up is newer than the one it covered — an identification
     * answering (the upload queue never stops): the lift keeps it, also when UpdateLock answered
     * again meanwhile and put its own line back over it (each check does; so does every return to
     * the scan screen). A mode changed while locked (from the computer) is the new mode's line.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun updateLock_aLineWrittenUnderTheLockIsKeptWhenItLifts() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = false
        try {
            val cam = RecordingCamera()
            app.cameraFactory = { _, _ -> cam }
            grantCamera(true)
            val a = launch(MainActivity::class.java)
            val l = a.cameraListenerForTest
            val box = io.github.darylno.cardscanner.core.Box(40, 30, 60, 84, 0.2)
            l.onDetection(io.github.darylno.cardscanner.core.AutoScanner.Event.Trigger(box,
                io.github.darylno.cardscanner.core.Gray(176, 132, IntArray(176 * 132))), 176, 132, null)
            l.onDetection(io.github.darylno.cardscanner.core.AutoScanner.Event.AwaitingNext(box), 176, 132, null)
            ShadowLooper.idleMainLooper()
            val lockLine = a.getString(R.string.update_required_status)
            storeNewerRelease()
            a.updateLockChangedForTest(); ShadowLooper.idleMainLooper()
            assertEquals(lockLine, a.statusForTest)
            // An older job answers under the lock: its line replaces "Update required".
            a.uploadListenerForTest.onOutcome("job-9", false, false, null, io.github.darylno.cardscanner.ui.Outcome.NoCard(null, false))
            ShadowLooper.idleMainLooper()
            val noCard = io.github.darylno.cardscanner.ui.StatusText.NO_CARD
            assertEquals(noCard, a.statusForTest)
            // UpdateLock answers again, still locked: its own line goes back up over it.
            a.updateLockChangedForTest(); ShadowLooper.idleMainLooper()
            assertEquals(lockLine, a.statusForTest)
            storeOlderRelease()
            a.updateLockChangedForTest(); ShadowLooper.idleMainLooper()
            assertEquals("the newer line, not the one the lock first covered, nor the mode's", noCard, a.statusForTest)
            assertEquals("…in its own colour", io.github.darylno.cardscanner.ui.ScanChrome.Palette.ERR, statusPill(a).currentTextColor)

            // Locked again; the computer switches to Tap to scan meanwhile: the lift says the new mode's line.
            storeNewerRelease()
            a.updateLockChangedForTest(); ShadowLooper.idleMainLooper()
            assertEquals(lockLine, a.statusForTest)
            cam.calls.clear()
            assertEquals("tap", patchFromTheServerThread("""{"mode":"tap"}""")["mode"])
            assertEquals("the mode change reached the camera, once: ${cam.calls}", listOf("setAuto(false)"), cam.autos)
            assertEquals("…under the lock's line", lockLine, a.statusForTest)
            storeOlderRelease()
            cam.calls.clear()
            a.updateLockChangedForTest(); ShadowLooper.idleMainLooper()
            assertEquals("…and not again at the lift: ${cam.calls}", emptyList<String>(), cam.autos)
            assertEquals(io.github.darylno.cardscanner.ui.StatusText.AUTO_OFF, a.statusForTest)
        } finally {
            app.getSharedPreferences("update_lock", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    /**
     * Review of the fix above: the status line must keep saying what the scanner last said.
     * A PATCH that keeps the mode still rewrote it to "Tray: waiting for a steady view…" —
     * and the scanner, now rightly left in AWAIT_NEXT with the scanned card still there,
     * says nothing new until that card leaves, so the false line stayed up, over
     * "✓ <name> — filed for desktop pick. Next card…" or "✗ No match — Retry…" (Retry
     * still on screen). Only a mode change or an Area change (the scanner starts afresh)
     * rewrites it.
     */
    @Test
    fun deviceApi_aPatchThatKeepsTheModeKeepsTheStatusLine() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = false
        val cam = RecordingCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        val a = launch(MainActivity::class.java)
        val l = a.cameraListenerForTest
        val box = io.github.darylno.cardscanner.core.Box(40, 30, 60, 84, 0.2)
        l.onDetection(io.github.darylno.cardscanner.core.AutoScanner.Event.Trigger(box,
            io.github.darylno.cardscanner.core.Gray(176, 132, IntArray(176 * 132))), 176, 132, null)
        ShadowLooper.idleMainLooper()
        val scanned = io.github.darylno.cardscanner.ui.StatusText.CAPTURING
        assertEquals(scanned, a.statusForTest)
        l.onDetection(io.github.darylno.cardscanner.core.AutoScanner.Event.AwaitingNext(box), 176, 132, null)
        ShadowLooper.idleMainLooper()
        for (body in listOf("""{"torch":true}""", """{"check_ms":500}""", """{"zoom_fit":true}""")) {
            patchFromTheServerThread(body)
            assertEquals("a PATCH that keeps Tray ($body) leaves the scanner's line", scanned, a.statusForTest)
        }
        l.onDetection(io.github.darylno.cardscanner.core.AutoScanner.Event.AwaitingNext(box), 176, 132, null)
        ShadowLooper.idleMainLooper()
        assertEquals(scanned, a.statusForTest)

        // A mode change says the new mode's line…
        patchFromTheServerThread("""{"mode":"tap"}""")
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.AUTO_OFF, a.statusForTest)
        // …and in Tap to scan, too, a PATCH that keeps the mode leaves the last line.
        l.onCaptureFailed(7, "no frames in the ring")
        ShadowLooper.idleMainLooper()
        val failed = a.getString(R.string.capture_failed, "no frames in the ring")
        assertEquals(failed, a.statusForTest)
        patchFromTheServerThread("""{"torch":false}""")
        assertEquals("a PATCH that keeps Tap to scan leaves the last line", failed, a.statusForTest)
        // A drawing the PATCH cancels does not leave its drag prompt up.
        findText(a.window.decorView, a.getString(R.string.area_set))!!.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.AREA_DRAG, a.statusForTest)
        patchFromTheServerThread("""{"torch":true}""")
        assertEquals("the PATCH cancelled the drawing: not its prompt", io.github.darylno.cardscanner.ui.StatusText.AUTO_OFF, a.statusForTest)
        assertNotNull("…and the Area button is no longer Cancel", findText(a.window.decorView, a.getString(R.string.area_set)))
        // A new Area from the computer starts the scanner afresh: its mode's line again.
        patchFromTheServerThread("""{"roi":{"x0":0.1,"y0":0.2,"x1":0.6,"y1":0.9}}""")
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.AUTO_OFF, a.statusForTest)
        patchFromTheServerThread("""{"mode":"tray"}""")
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.WAITING, a.statusForTest)
    }

    /**
     * Review of the fix above: a PATCH while the scan screen is STOPPED — Tap to scan
     * stops it after every scan (the scan opens), Scans and Settings do too — reaches
     * only AppSettings (the bridge has no screen then), and coming back re-applied the
     * torch, AE, high res, camera and zoom but never the mode or the Area: the camera
     * kept scanning in the old mode under the old chip while the computer showed the new
     * one, and tapping the new mode's chip did nothing (setAutoMode: already that mode).
     * The return catches up — once, and only what changed.
     */
    @Test
    fun deviceApi_aModeChangedWhileTheScanScreenWasAwayReachesTheCameraOnReturn() {
        app.settings.roi = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        app.settings.auto = true
        app.settings.zoomFit = false
        val cam = RecordingCamera()
        app.cameraFactory = { _, _ -> cam }
        grantCamera(true)
        val c = Robolectric.buildActivity(MainActivity::class.java)
        controllers += c
        c.setup()
        ShadowLooper.idleMainLooper()
        val a = c.get()
        assertEquals(listOf("setAuto(true)"), cam.autos)
        fun rois() = cam.calls.filter { it.startsWith("setRoi(") }
        fun chipOn(label: Int) = findText(a.window.decorView, a.getString(label))!!.currentTextColor ==
            io.github.darylno.cardscanner.ui.ScanChrome.Palette.TEXT_ON_ACTIVE
        fun away(vararg patches: String) {
            c.pause().stop()
            ShadowLooper.idleMainLooper()
            assertEquals("a stopped scan screen is not the bridge's", null, app.phoneServer.device.screen)
            cam.calls.clear()
            for (p in patches) patchFromTheServerThread(p)
            assertEquals("nothing reaches a stopped screen's camera: ${cam.calls}", emptyList<String>(), cam.calls)
            c.restart().start().resume()
            ShadowLooper.idleMainLooper()
        }
        away("""{"mode":"tap"}""")
        assertEquals("the mode changed while away reaches the camera on return, once: ${cam.calls}",
            listOf("setAuto(false)"), cam.autos)
        assertEquals("…the Area did not change: ${cam.calls}", emptyList<String>(), rois())
        assertTrue("…and the chip says Tap to scan", chipOn(R.string.mode_tap))
        assertFalse(chipOn(R.string.mode_tray))
        assertEquals(io.github.darylno.cardscanner.ui.StatusText.AUTO_OFF, a.statusForTest)

        away()
        assertEquals("a plain round trip hands the camera nothing new: ${cam.calls}", emptyList<String>(), cam.autos + rois())
        away("""{"torch":true}""")
        assertEquals("…nor does a PATCH that keeps the mode and the Area: ${cam.calls}", emptyList<String>(), cam.autos + rois())
        assertTrue(cam.calls.contains("setTorch(true)"))

        away("""{"roi":{"x0":0.1,"y0":0.2,"x1":0.6,"y1":0.9}}""")
        assertEquals("an Area changed while away reaches the camera on return, once: ${cam.calls}",
            listOf("setRoi(0.1,0.2,0.6,0.9)"), rois())
        assertEquals(emptyList<String>(), cam.autos)

        away("""{"mode":"tray"}""")
        assertEquals("back to Tray while away: once: ${cam.calls}", listOf("setAuto(true)"), cam.autos)
        assertTrue(chipOn(R.string.mode_tray))
        // …and the phone's own chip answers again.
        cam.calls.clear()
        findText(a.window.decorView, a.getString(R.string.mode_tap))!!.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("setAuto(false)"), cam.autos)
    }

    /**
     * Settings at the N200's size while the scan store is still opening (its one-time
     * migrations: review of 0b19864): the screen renders without waiting for it — the
     * Delete all scans row reads "…" — and the count lands once the open is done, OFF the
     * main thread; a tap on the row meanwhile is answered after the open, not dropped.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun settings_whileTheStoreOpens_rendersWithoutWaitingForIt() {
        val ps = app.phoneServer
        assertFalse("precondition: the store has not opened", ps.storeOpen)
        lateinit var a: SettingsActivity
        synchronized(ps.storeOpening) {                  // the store is "opening" the whole time
            a = launch(SettingsActivity::class.java)
            assertFalse("Settings opened the store on the main thread", ps.storeOpen)
            assertNotNull("the row waits with …", findText(a.window.decorView, a.getString(R.string.settings_scans_counting)))
            var v: View? = findText(a.window.decorView, a.getString(R.string.settings_delete_all))
            while (v != null && !v.isClickable) v = v.parent as? View
            assertNotNull("a clickable row", v)
            v!!.performClick()                            // counted off the main thread, answered after the open
            ShadowLooper.idleMainLooper()
            assertFalse(ps.storeOpen)
        }
        val deadline = System.currentTimeMillis() + 5_000
        while ((findText(a.window.decorView, "0 scans") == null ||
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == null) && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(); Thread.sleep(20)
        }
        assertNotNull("the count lands in the row", findText(a.window.decorView, "0 scans"))
        assertEquals(a.getString(R.string.settings_delete_all_none), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertTrue("opened off the main thread: ${ps.storeOpenedOn}",
            ps.storeOpenedOn != null && ps.storeOpenedOn != Thread.currentThread().name)
    }

    /**
     * Holds the store's lock on another thread — standing in for a long list() (the price worker
     * lists every scan on each poll) — and watches the main (test) thread: the moment it is
     * BLOCKED inside the store (waiting for this lock), [mainBlocked] is set and the lock let go,
     * so a regression shows as a failed assertion, never a hung test.
     */
    private class StoreBusy(private val lock: Any) {
        private val main = Thread.currentThread()
        @Volatile var mainBlocked = false
            private set
        private val done = java.util.concurrent.CountDownLatch(1)
        private val held = java.util.concurrent.CountDownLatch(1)
        private val holder = Thread({
            synchronized(lock) {
                held.countDown()
                val store = io.github.darylno.cardscanner.phoneserver.SqliteScanStore::class.java.name
                val deadline = System.nanoTime() + 20_000_000_000L
                while (done.count > 0 && System.nanoTime() < deadline) {
                    if (main.state == Thread.State.BLOCKED && main.stackTrace.any { it.className == store }) {
                        mainBlocked = true; break
                    }
                    done.await(2, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
        }, "store-busy").apply { isDaemon = true; start() }
        init { held.await() }
        fun release() { done.countDown(); holder.join(20_000) }
    }

    /** Polls the main looper until [cond] holds (or 5 s pass). */
    private fun waitOnMain(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < deadline) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        ShadowLooper.idleMainLooper()
    }

    private fun clickRow(a: Activity, label: String) {
        var v: View? = findText(a.window.decorView, label)
        while (v != null && !v.isClickable) v = v.parent as? View
        assertNotNull("a clickable $label row", v)
        v!!.performClick()
        ShadowLooper.idleMainLooper()
    }

    /**
     * Review of 806a4cd (F1): once the store is open, Settings must still never take its lock on
     * the main thread — list() holds it while it decodes every row, and the price worker lists on
     * every poll (after 1.1.17's re-price every scan is owed), so a count there could stall the
     * screen. With the lock held elsewhere, Settings renders ("…") and the Delete all scans tap
     * returns without the main thread ever blocking on it; the count lands once it is free.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun settings_whileTheStoreIsBusy_neverWaitsForItsLockOnTheMainThread() {
        for (name in listOf("Opt", "Shock")) app.phoneServer.backend.file(mapOf("identified" to true,
            "card_read" to mapOf("name" to name), "confidence" to mapOf("name" to "high"),
            "candidates" to listOf(mapOf("id" to name.lowercase(), "name" to name, "set" to "dom", "collector_number" to "1"),
                mapOf("id" to "${name.lowercase()}2", "name" to name, "set" to "m19", "collector_number" to "2"))), null)
        val lock = app.phoneServer.store.lock
        val render = StoreBusy(lock)
        lateinit var a: SettingsActivity
        var rowWhileBusy: Boolean
        try {
            a = launch(SettingsActivity::class.java)
            rowWhileBusy = findText(a.window.decorView, a.getString(R.string.settings_scans_counting)) != null
        } finally { render.release() }
        val tap = StoreBusy(lock)
        var dialogWhileBusy: Boolean
        try {
            clickRow(a, a.getString(R.string.settings_delete_all))
            dialogWhileBusy = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true
        } finally { tap.release() }
        assertEquals("the main thread waited for the store's lock (render, tap)",
            listOf(false, false), listOf(render.mainBlocked, tap.mainBlocked))
        assertTrue("the row reads … while the store is busy", rowWhileBusy)
        assertFalse("no dialog before the count", dialogWhileBusy)
        waitOnMain { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
        assertNotNull("the count lands in the row", findText(a.window.decorView, "2 scans"))
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertNotNull(findText(d.window!!.decorView, a.getString(R.string.settings_delete_all_title, "2 scans")))
    }

    /**
     * Review of 806a4cd (F2): taps on Delete all scans while its count is pending (here: the store
     * still opening) ask ONCE — before, each tap queued its own count and later stacked its own
     * confirmation, and confirming two ran delete-all twice.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun settings_deleteAll_tappedThreeTimesWhileCounting_asksOnce() {
        // Two scans already on disk, the store not yet open (its one-time migrations "running").
        val file = java.io.File(app.filesDir, "phoneserver/scans.db")
        io.github.darylno.cardscanner.phoneserver.SqliteScanStore(file).apply {
            for (name in listOf("Opt", "Shock")) create(true, mapOf("name" to name), mapOf("name" to "high"),
                listOf(mapOf("id" to name.lowercase(), "name" to name, "set" to "dom", "collector_number" to "1"),
                    mapOf("id" to "${name.lowercase()}2", "name" to name, "set" to "m19", "collector_number" to "2")), null)
            close()
        }
        val ps = app.phoneServer
        assertFalse("precondition: the store has not opened", ps.storeOpen)
        lateinit var a: SettingsActivity
        synchronized(ps.storeOpening) {
            a = launch(SettingsActivity::class.java)
            repeat(3) { clickRow(a, a.getString(R.string.settings_delete_all)) }
            assertTrue("nothing asked before the count", org.robolectric.shadows.ShadowDialog.getShownDialogs().isEmpty())
        }
        waitOnMain { org.robolectric.shadows.ShadowDialog.getShownDialogs().isNotEmpty() }
        // Every queued count has landed by now (they are sub-millisecond once the store is open).
        val until = System.currentTimeMillis() + 600
        while (System.currentTimeMillis() < until) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        val shown = org.robolectric.shadows.ShadowDialog.getShownDialogs()
        assertEquals("one confirmation for three taps", 1, shown.size)
        assertNotNull(findText(shown[0].window!!.decorView, a.getString(R.string.settings_delete_all_title, "2 scans")))
        // Asked and answered (Cancel): the next tap asks again.
        (shown[0] as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()
        clickRow(a, a.getString(R.string.settings_delete_all))
        waitOnMain { org.robolectric.shadows.ShadowDialog.getShownDialogs().size == 2 }
        assertEquals(2, org.robolectric.shadows.ShadowDialog.getShownDialogs().size)
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
        // Counted off the main thread (review of 806a4cd): the number lands shortly after.
        waitOnMain { findText(a.window.decorView, "2 scans") != null }
        assertNotNull("the row shows how many would go", findText(a.window.decorView, "2 scans"))
        // The row's click lands on its container (ScanChrome.valueRow): climb to the clickable parent.
        clickRow(a, a.getString(R.string.settings_delete_all))
        waitOnMain { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(d.isShowing)
        assertNotNull(findText(d.window!!.decorView, a.getString(R.string.settings_delete_all_title, "2 scans")))
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        val deadline = System.currentTimeMillis() + 5_000
        while (app.phoneServer.store.count() != 0 && System.currentTimeMillis() < deadline) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        assertEquals(0, app.phoneServer.store.count())
    }

    /**
     * Settings → External card list: off by default; an http:// address is refused; an
     * https:// one is saved exactly as typed, shown by its host only (the rest may carry a
     * key), and its state line appears (tests never fetch, so: the last error); Turn off
     * clears it.
     */
    @Test
    fun settings_externalCardList_savesAnHttpsUrlAndShowsOnlyItsHost() {
        app.settings.cardListUrl = ""
        val a = launch(SettingsActivity::class.java)
        fun openDialog(): androidx.appcompat.app.AlertDialog {
            var v: View? = findText(a.window.decorView, a.getString(R.string.settings_card_list_url))
            while (v != null && !v.isClickable) v = v.parent as? View
            assertNotNull("a clickable List URL row", v)
            v!!.performClick()
            ShadowLooper.idleMainLooper()
            return org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        }
        fun input(d: androidx.appcompat.app.AlertDialog): android.widget.EditText {
            fun find(v: View): android.widget.EditText? = v as? android.widget.EditText
                ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            return find(d.window!!.decorView)!!
        }
        assertNotNull("off by default", findText(a.window.decorView, a.getString(R.string.settings_card_list_off)))
        var d = openDialog()
        input(d).setText("http://lists.example/cards.json")
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("http:// is refused", "", app.settings.cardListUrl)
        d = openDialog()
        input(d).setText("  https://lists.example/cards.json?key=s3cret ")
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("https://lists.example/cards.json?key=s3cret", app.settings.cardListUrl)
        val deadline = System.currentTimeMillis() + 5_000
        while (app.cardList.status()["error"] == null && System.currentTimeMillis() < deadline) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        ShadowLooper.idleMainLooper()
        assertNotNull("the row shows the host only", findText(a.window.decorView, "lists.example"))
        assertTrue("never the key", findText(a.window.decorView, "https://lists.example/cards.json?key=s3cret") == null)
        assertTrue("the state line: no list yet, and why",
            app.cardList.summary().startsWith("No list yet · last error: unreachable"))
        assertNotNull(findText(a.window.decorView, a.getString(R.string.settings_card_list_refresh)))
        d = openDialog()
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("", app.settings.cardListUrl)
    }

    /**
     * Unit tests never download the card database: every Robolectric test builds a fresh App, and
     * each App's start fetched the real art pack from GitHub on its own thread — 1.1.17's larger
     * suite ran the 1 GB test heap out of memory ("OutOfMemoryError … in thread artpack").
     */
    @Test
    fun appStart_inUnitTests_neverFetchesTheCardDatabase() {
        val deadline = System.currentTimeMillis() + 5_000
        while (app.identify.lastCheck == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val c = app.identify.lastCheck
        assertTrue("the start-up check is skipped, not fetched: $c",
            c is io.github.darylno.cardscanner.ident.ArtPackStore.Check.Skipped)
    }

    @Test
    fun settingsActivity_showsThePhonesServer() {
        val a = launch(SettingsActivity::class.java)
        assertResumed(a)
        assertNotNull("pairing a computer lives in Settings", findText(a.window.decorView, a.getString(R.string.pv_pair)))
        assertNotNull(findText(a.window.decorView, a.getString(R.string.pv_pack_check)))
    }

    /**
     * "Is it focusing?" (1.1.14): Settings → Diagnostics reads the scan screen's camera LIVE
     * (it was a bind-time copy — focus / last frame / analyzer / last capture were stale),
     * with the session's focus line; once the scan screen is gone, its last snapshot and age.
     */
    @Test
    fun diagnostics_readTheScanScreensCameraLive_thenItsSnapshot() {
        grantCamera(true)
        val c = Robolectric.buildActivity(MainActivity::class.java)
        controllers += c
        c.setup()
        ShadowLooper.idleMainLooper()
        val live = io.github.darylno.cardscanner.ui.DiagnosticsText.build(app)
        assertTrue(live, live.contains("camera (live — read from the scan screen's camera now):"))
        assertTrue(live, live.contains("focus: passes this session: "))
        assertTrue(live, live.contains("analyzer: frames "))
        c.pause().stop().destroy()
        ShadowLooper.idleMainLooper()
        val after = io.github.darylno.cardscanner.ui.DiagnosticsText.build(app)
        assertTrue(after, after.contains("camera (snapshot from "))
        assertTrue(after, after.contains("the scan screen is closed"))
        assertTrue(after, after.contains("focus: passes this session: "))
    }

    /**
     * The zoom probe's last verdict (1.1.17) is read from the phone's settings, so Settings →
     * Diagnostics (and the debug report's head) and the paired computer's GET /api/device
     * (`zoom_probe`) have it with no scan screen open: when, which camera, which stream, and
     * what the runs said — or "never run".
     */
    @Test
    fun diagnosticsAndTheComputerReadTheLastZoomProbeWithTheScreenClosed() {
        app.settings.zoomProbeLast = null
        val never = io.github.darylno.cardscanner.ui.DiagnosticsText.build(app)
        assertTrue(never, never.lines().contains("zoom probe: never run"))
        val api = io.github.darylno.cardscanner.core.server.DeviceApi(app.phoneServer.device)
        fun device() = io.github.darylno.cardscanner.core.MiniJson.parse(String(
            api.handle(io.github.darylno.cardscanner.core.server.ApiRequest("GET", "/api/device"))!!.body)) as Map<*, *>
        val d0 = device()
        assertTrue("$d0", d0.containsKey("zoom_probe") && d0["zoom_probe"] == null)
        val at = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.OCTOBER, 31, 14, 3, 0) }.timeInMillis
        val words = "2 cards agree: the zoom is the centred crop, frames settle within the dropped ones"
        app.settings.zoomProbeLast = io.github.darylno.cardscanner.core.MiniJson.stringify(linkedMapOf(
            "at" to at, "camera" to "0", "stream" to "1600×1200", "run" to 3, "runs" to 2, "valid_cards" to 2, "words" to words,
            "probe_half" to "✓ 2 valid run(s) on different cards (needs 2) ✓ centred ✓ settle · knee: not measured in this build (MAX_ZOOM stays 2.00×)"))
        val diag = io.github.darylno.cardscanner.ui.DiagnosticsText.build(app)
        assertTrue(diag, diag.lines().contains("zoom probe: 31 Oct 14:03 · camera 0 · 1600×1200 — $words"))
        val p = device()["zoom_probe"] as Map<*, *>
        assertEquals(at, p["at"]); assertEquals("0", p["camera"]); assertEquals("1600×1200", p["stream"]); assertEquals(words, p["words"])
        assertEquals("no scan screen: no live zoom", null, device()["zoom"])
        app.settings.zoomProbeLast = "not json"
        assertTrue("a broken record reads as never run", io.github.darylno.cardscanner.ui.DiagnosticsText.build(app).lines().contains("zoom probe: never run"))
        assertEquals(null, device()["zoom_probe"])
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

    private fun findSwitch(v: View): androidx.appcompat.widget.SwitchCompat? {
        if (v is androidx.appcompat.widget.SwitchCompat) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findSwitch(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun findDesc(v: View, desc: String): View? {
        if (v.contentDescription?.toString() == desc) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findDesc(v.getChildAt(i), desc)?.let { return it }
        return null
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
