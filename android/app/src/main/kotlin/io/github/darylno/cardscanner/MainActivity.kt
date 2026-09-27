package io.github.darylno.cardscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import io.github.darylno.cardscanner.core.AutoScanner
import io.github.darylno.cardscanner.ui.AppSettings
import io.github.darylno.cardscanner.ui.CameraPort
import io.github.darylno.cardscanner.ui.Captured
import io.github.darylno.cardscanner.ui.OverlayView
import io.github.darylno.cardscanner.ui.Outcome
import io.github.darylno.cardscanner.ui.PanelActivity
import io.github.darylno.cardscanner.ui.SettingsActivity
import io.github.darylno.cardscanner.ui.SetupActivity
import io.github.darylno.cardscanner.ui.ShareActivity
import io.github.darylno.cardscanner.ui.StatusText
import io.github.darylno.cardscanner.ui.UploadPort
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The camera screen: Mount (hands-free auto capture over a tray — the tuned
 * occupancy + stillness trigger) or Handheld (tap Scan → walk-around PRICE
 * CHECK). Status wording mirrors server/static/phone.html.
 *
 * Threading: camera/detection callbacks and upload outcomes arrive on worker
 * threads and are posted to the UI thread; nothing here blocks on the network
 * except [io] tasks.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var app: App
    private lateinit var settings: AppSettings
    private var camera: CameraPort? = null
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var banner: TextView
    private lateinit var modeBtn: Button
    private lateinit var areaBtn: Button
    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusView: TextView
    private lateinit var queueView: TextView
    private lateinit var autoBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var retryBtn: Button

    /** Scan id a Retry would replace (phone.html offerRetry). */
    private var retryScanId: Long? = null
    /** Set when Retry was tapped: the NEXT manual capture replaces this row. */
    private var pendingReplace: Long? = null
    /** Handheld price checks captured but not yet answered (queued offline). */
    private var awaitingPriceCheck = 0
    /**
     * Upload job id → detector scene at capture (for the no_card re-seed guard). In-memory only.
     * Concurrent: filled on [io] right after enqueue returns, read on the UI thread when the
     * outcome lands. The outcome needs a network round trip, so it can't beat the put in practice.
     */
    private val sceneByJob = ConcurrentHashMap<String, io.github.darylno.cardscanner.core.Gray>()

    /**
     * Job id of the most recent capture — the card under the camera NOW. The upload queue is
     * persistent, so an OLD job's outcome can land while a different card is in view; Retry
     * would then capture the current card with replace_scan_id = the old row (wrong card
     * overwrites the failed row). Only this job may offer Retry. Cleared on Trigger / NextCard /
     * a new manual capture / mode change; [captureGen] stops a slow enqueue from re-setting it
     * after such a clear.
     */
    private var lastCaptureJobId: String? = null
    private var captureGen = 0

    private fun forgetLastCapture() {
        lastCaptureJobId = null
        captureGen++
    }

    /**
     * onResume requests the permission at most ONCE per Activity instance: the system
     * dialog pauses/resumes us, so re-asking from onResume looped forever after a
     * permanent denial and showed the rationale twice (callback + onResume).
     */
    private var permissionAsked = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else showCameraRationale(askedNow = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        settings = app.settings
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        applyMode()
        app.uploads.addListener(uploadListener)
    }

    override fun onResume() {
        super.onResume()
        if (!app.server.isPaired) {
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        refreshBanner()
        overlay.setDebugText(null)
        if (camera == null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else if (!permissionAsked) {
                permissionAsked = true
                // Pre-ask rationale only when the system says so; the denial path is the callback's.
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) showCameraRationale(askedNow = false)
                else permission.launch(Manifest.permission.CAMERA)
            } else {
                // Already asked this instance (or back from app settings still denied): no re-ask loop.
                setStatus(getString(R.string.camera_needed), Tone.ERR)
            }
        } else {
            // Settings may have changed torch / AE / focus lock while we were away.
            camera?.setTorch(settings.torch)
            camera?.setAeLock(settings.aeLock)
            camera?.setHighRes(settings.highRes)
            // Belt-and-braces: another screen (Setup's QR camera) may have unbound the shared
            // CameraProvider while we were stopped; re-bind so the preview/scanning isn't dead.
            camera?.rebind()
        }
        uploadListener.onState(app.uploads.pending, null, null)
    }

    override fun onDestroy() {
        app.uploads.removeListener(uploadListener)
        camera?.unbind()
        camera = null
        io.shutdown()
        super.onDestroy()
    }

    // ── layout ──────────────────────────────────────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener(onClick)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0f1115"))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        titleRow.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 18f
        })
        banner = TextView(this).apply {
            setTextColor(Color.parseColor("#8b93a1"))
            textSize = 12f
            setPadding(dp(10), 0, 0, 0)
            text = getString(R.string.banner_fmt, BuildConfig.VERSION_NAME, "…")
        }
        titleRow.addView(banner)
        root.addView(titleRow)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        modeBtn = button("") { toggleMode() }
        areaBtn = button(getString(R.string.area)) { onAreaButton() }
        bar.addView(modeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        bar.addView(areaBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button(getString(R.string.scans)) { openPanel(0L, false) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button(getString(R.string.share)) { startActivity(Intent(this, ShareActivity::class.java)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) }.apply {
            contentDescription = getString(R.string.settings)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f))
        root.addView(bar)

        val stage = FrameLayout(this)
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        overlay = OverlayView(this)
        stage.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(12), dp(8), dp(12), 0)
            minLines = 2
        }
        queueView = TextView(this).apply {
            setTextColor(Color.parseColor("#fbbf24"))
            textSize = 13f
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
            // Tapping the offline indicator retries now (resets the backoff).
            setOnClickListener { app.uploads.retryNow() }
        }
        root.addView(statusView)
        root.addView(queueView)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(8))
        }
        autoBtn = button("") { toggleAuto() }
        scanBtn = button(getString(R.string.scan_card)) { onScanTap() }
        retryBtn = button(getString(R.string.retry)) { onRetryTap() }.apply { visibility = View.GONE }
        bottom.addView(autoBtn, LinearLayout.LayoutParams(0, dp(56), 1f))
        bottom.addView(scanBtn, LinearLayout.LayoutParams(0, dp(56), 1.6f))
        bottom.addView(retryBtn, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(bottom)
        setContentView(root)

        overlay.setRoi(settings.roi)
        overlay.onAreaDrawn = { r -> onAreaDrawn(r) }
        overlay.onDoubleTap = {
            if (settings.mode == AppSettings.Mode.MOUNT) {
                camera?.relearn()
                setStatus(StatusText.RELEARN)
            }
        }
        overlay.onTap = { x, y -> camera?.tapFocus(x, y) }
    }

    // ── status ──────────────────────────────────────────────────────────────
    private enum class Tone { NORMAL, OK, ERR }

    private fun setStatus(msg: String, tone: Tone = Tone.NORMAL) {
        statusView.text = msg
        statusView.setTextColor(
            when (tone) {
                Tone.OK -> Color.parseColor("#6ee7a0")
                Tone.ERR -> Color.parseColor("#f87171")
                Tone.NORMAL -> Color.WHITE
            }
        )
    }

    private fun refreshBanner() {
        io.execute {
            val v = try {
                app.server.version()
            } catch (e: Exception) {
                getString(R.string.server_offline)
            }
            runOnUiThread { banner.text = getString(R.string.banner_fmt, BuildConfig.VERSION_NAME, v) }
        }
    }

    // ── modes / controls ────────────────────────────────────────────────────
    private val handheld get() = settings.mode == AppSettings.Mode.HANDHELD

    private fun applyMode() {
        modeBtn.text = getString(if (handheld) R.string.mode_handheld else R.string.mode_mount)
        autoBtn.visibility = if (handheld) View.GONE else View.VISIBLE
        areaBtn.visibility = if (handheld) View.INVISIBLE else View.VISIBLE
        autoBtn.text = getString(if (settings.auto) R.string.auto_on else R.string.auto_off)
        overlay.setHandheldGuide(handheld)
        overlay.setRoi(if (handheld) null else settings.roi)
        overlay.setBox(null, 176, 132, OverlayView.BoxState.SETTLING)
        updateAreaBtn()
        camera?.setHandheld(handheld)
        camera?.setAuto(!handheld && settings.auto)
        setStatus(
            when {
                handheld -> StatusText.HANDHELD_READY
                settings.auto -> StatusText.WAITING
                else -> StatusText.AUTO_OFF
            }
        )
    }

    private fun toggleMode() {
        if (overlay.settingArea) cancelArea()
        settings.mode = if (handheld) AppSettings.Mode.MOUNT else AppSettings.Mode.HANDHELD
        hideRetry()
        forgetLastCapture()   // a late outcome from the other mode's capture must not offer Retry
        applyMode()
    }

    private fun toggleAuto() {
        settings.auto = !settings.auto
        autoBtn.text = getString(if (settings.auto) R.string.auto_on else R.string.auto_off)
        camera?.setAuto(settings.auto)
        if (settings.auto) setStatus(StatusText.WATCHING)
        else {
            overlay.setBox(null, 176, 132, OverlayView.BoxState.SETTLING)
            setStatus(StatusText.AUTO_OFF)
        }
    }

    private fun updateAreaBtn() {
        areaBtn.text = when {
            overlay.settingArea -> getString(R.string.cancel)
            settings.roi != null -> getString(R.string.area_set)
            else -> getString(R.string.area)
        }
    }

    private fun cancelArea() {
        overlay.settingArea = false
        camera?.pause(false)
        updateAreaBtn()
    }

    /** phone.html areaBtn: Cancel while drawing; clear an existing area; else start drawing. */
    private fun onAreaButton() {
        when {
            overlay.settingArea -> {
                cancelArea(); setStatus(StatusText.AREA_UNCHANGED)
            }
            settings.roi != null -> {
                settings.roi = null
                overlay.setRoi(null)
                camera?.setRoi(null)
                updateAreaBtn()
                setStatus(StatusText.AREA_CLEARED)
            }
            else -> {
                camera?.pause(true)
                overlay.settingArea = true
                updateAreaBtn()
                setStatus(StatusText.AREA_DRAG)
            }
        }
    }

    private fun onAreaDrawn(r: io.github.darylno.cardscanner.core.RoiFrac?) {
        camera?.pause(false)
        if (r == null) {
            updateAreaBtn()
            setStatus(StatusText.AREA_TOO_SMALL, Tone.ERR)
            return
        }
        settings.roi = r
        overlay.setRoi(r)
        camera?.setRoi(r)          // the adapter resets detection (re-learn under the new area)
        updateAreaBtn()
        setStatus(StatusText.AREA_SET)
    }

    private fun onScanTap() {
        if (camera == null) return
        hideRetry()
        forgetLastCapture()   // a new capture supersedes the previous card
        pendingReplace = null
        setStatus(StatusText.CAPTURING)
        camera?.manualScan()
    }

    private fun onRetryTap() {
        val id = retryScanId ?: return
        pendingReplace = id
        hideRetry()
        forgetLastCapture()   // the retry capture's own job becomes the current one
        setStatus(getString(R.string.retry_capturing))
        camera?.manualScan()
    }

    private fun offerRetry(scanId: Long) {
        retryScanId = scanId
        retryBtn.visibility = View.VISIBLE
    }

    private fun hideRetry() {
        retryScanId = null
        retryBtn.visibility = View.GONE
    }

    private fun openPanel(detail: Long, priceCheck: Boolean) {
        startActivity(PanelActivity.intent(this, app.server.bestBase(), app.server.pin(), detail, priceCheck))
    }

    /**
     * The ONE place the rationale is shown. [askedNow] = called from the request callback:
     * a denial with no rationale flag right after asking is PERMANENT ("don't ask again"),
     * where launch() returns instantly denied — so offer this app's system settings page.
     */
    private fun showCameraRationale(askedNow: Boolean) {
        setStatus(getString(R.string.camera_needed), Tone.ERR)
        val permanent = askedNow && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        val b = AlertDialog.Builder(this)
            .setTitle(R.string.camera_perm_title)
            .setMessage(R.string.camera_perm_msg)
            .setNegativeButton(R.string.not_now, null)
        if (permanent) {
            // Literal: res/ is outside this change's scope (no new string resource).
            b.setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                } catch (_: android.content.ActivityNotFoundException) {
                    // No settings app (some kiosk ROMs): the status line already explains.
                }
            }
        } else {
            b.setPositiveButton(R.string.allow) { _, _ -> permission.launch(Manifest.permission.CAMERA) }
        }
        b.show()
    }

    // ── camera ──────────────────────────────────────────────────────────────
    private fun startCamera() {
        if (camera != null) return
        val cam = app.newCamera()
        camera = cam
        cam.setHandheld(handheld)
        cam.setRoi(if (handheld) null else settings.roi)
        cam.setAuto(!handheld && settings.auto)
        cam.bind(this, preview, cameraListener)
    }

    private fun vibrateTick() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        try {
            v?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } catch (_: Exception) {
            // Haptics are a convenience; never let them break a capture.
        }
    }

    private val cameraListener = object : CameraPort.Listener {
        override fun onFrameSize(uprightW: Int, uprightH: Int) {
            runOnUiThread { overlay.setFrameSize(uprightW, uprightH) }
        }

        override fun onDetection(event: AutoScanner.Event, sampleW: Int, sampleH: Int, debug: String?) {
            runOnUiThread { handleDetection(event, sampleW, sampleH, debug) }
        }

        override fun onCaptureStarted(manual: Boolean) {
            runOnUiThread { vibrateTick() }
        }

        override fun onCaptured(capture: Captured, manual: Boolean) {
            runOnUiThread { handleCaptured(capture, manual) }
        }

        override fun onCaptureFailed(message: String) {
            runOnUiThread { setStatus(getString(R.string.capture_failed, message), Tone.ERR) }
        }

        override fun onCameraError(message: String) {
            runOnUiThread { setStatus(getString(R.string.camera_error, message), Tone.ERR) }
        }
    }

    private fun handleDetection(event: AutoScanner.Event, sw: Int, sh: Int, debug: String?) {
        overlay.setDebugText(if (settings.debugOverlay) debug else null)
        if (handheld || overlay.settingArea) return
        when (event) {
            is AutoScanner.Event.Idle -> Unit
            is AutoScanner.Event.Learning -> {
                if (event.learned) setStatus(StatusText.WATCHING)
                else if (settings.auto) setStatus(StatusText.WAITING)
            }
            is AutoScanner.Event.Watching -> {
                overlay.setBox(event.box, sw, sh,
                    if (event.occupied) OverlayView.BoxState.OCCUPIED else OverlayView.BoxState.SETTLING)
                if (event.occupied && event.stableCount > 0) setStatus(StatusText.HOLD_STILL)
            }
            is AutoScanner.Event.Trigger -> {
                overlay.setBox(event.box, sw, sh, OverlayView.BoxState.CAPTURED)
                hideRetry()
                forgetLastCapture()
                setStatus(StatusText.CAPTURING)
            }
            is AutoScanner.Event.AwaitingNext ->
                overlay.setBox(event.box, sw, sh, OverlayView.BoxState.AWAIT_NEXT)
            is AutoScanner.Event.NextCard -> {
                overlay.setBox(event.box, sw, sh, OverlayView.BoxState.SETTLING)
                // The failed card left the tray — a retry now would replace the old row with the WRONG card.
                hideRetry()
                forgetLastCapture()
                setStatus(if (event.removed) StatusText.WATCHING else StatusText.NEW_CARD)
            }
        }
    }

    private fun handleCaptured(capture: Captured, manual: Boolean) {
        settings.addTiming(capture.timings)
        val replace = if (manual) pendingReplace else null
        pendingReplace = null
        val priceCheck = manual && handheld
        if (priceCheck) awaitingPriceCheck++
        setStatus(StatusText.SCANNING)
        val gen = captureGen
        // enqueue persists ≈1 MB with fsyncs — off the main thread. [io] is single-threaded,
        // so jobs still enter the FIFO queue in capture order.
        val work = Runnable {
            val jobId = try {
                app.uploads.enqueue(capture, manual, priceCheck, replace)
            } catch (e: java.io.IOException) {
                runOnUiThread {
                    if (priceCheck && awaitingPriceCheck > 0) awaitingPriceCheck--
                    setStatus(getString(R.string.capture_failed, e.message ?: "storage error"), Tone.ERR)
                }
                return@Runnable
            }
            // Filled right after enqueue returns; the outcome needs a network round trip first.
            capture.scene?.let { sceneByJob[jobId] = it }
            // Posted before any outcome can be (same reason); skipped if a Trigger/NextCard/
            // mode change superseded this capture while it was being written.
            runOnUiThread { if (gen == captureGen) lastCaptureJobId = jobId }
        }
        try {
            io.execute(work)
        } catch (_: RejectedExecutionException) {
            work.run()   // Activity tearing down (io shut): persist inline rather than lose the card
        }
    }

    // ── upload outcomes ─────────────────────────────────────────────────────
    private val uploadListener = object : UploadPort.Listener {
        override fun onOutcome(jobId: String, manual: Boolean, priceCheck: Boolean, replaceScanId: Long?, outcome: Outcome) {
            runOnUiThread { handleOutcome(jobId, manual, priceCheck, replaceScanId, outcome) }
        }

        override fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?) {
            runOnUiThread {
                val t = StatusText.queue(pending, lastError, nextRetryInMs)
                queueView.text = t
                queueView.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
                if (lastError != null && awaitingPriceCheck > 0 && handheld) {
                    setStatus(StatusText.QUEUED_PRICE_CHECK)
                }
            }
        }
    }

    private val resumed get() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun handleOutcome(jobId: String, manual: Boolean, priceCheck: Boolean, replaceScanId: Long?, outcome: Outcome) {
        val scene = sceneByJob.remove(jobId)
        // Only the most recent capture's job may offer Retry (see lastCaptureJobId).
        val current = jobId == lastCaptureJobId
        // priceCheck = a manual scan taken in Handheld mode (walk-around price check).
        if (priceCheck && awaitingPriceCheck > 0) awaitingPriceCheck--
        when (outcome) {
            is Outcome.NoCard -> {
                setStatus(StatusText.NO_CARD, Tone.ERR)
                if (!priceCheck) camera?.noCard(scene)
                // A Retry that came back no_card left the failed row in place — keep offering
                // Retry for it (phone.html does), but only while that card is still the current one.
                if (current && replaceScanId != null && replaceScanId > 0) offerRetry(replaceScanId)
            }
            is Outcome.AutoFiled -> {
                setStatus(StatusText.identified(outcome.json, manual), Tone.OK)
                if (priceCheck) openPick(outcome.scanId, true, outcome.json)
            }
            is Outcome.NeedsPick -> {
                setStatus(StatusText.identified(outcome.json, manual), Tone.OK)
                if (manual) openPick(outcome.scanId, priceCheck, outcome.json)
            }
            is Outcome.BestGuess -> {
                setStatus(StatusText.bestGuess(outcome.json, manual), Tone.ERR)
                if (manual) openPick(outcome.scanId, priceCheck, outcome.json)
            }
            is Outcome.NoMatch -> {
                setStatus(if (priceCheck) getString(R.string.no_match_retry) else StatusText.noMatch(outcome.json), Tone.ERR)
                // A stale job's NoMatch only sets the status line: another card is in view now.
                if (current && outcome.scanId > 0) offerRetry(outcome.scanId)
            }
            is Outcome.Rejected -> setStatus(getString(R.string.upload_rejected, outcome.code), Tone.ERR)
        }
    }

    private fun openPick(scanId: Long, priceCheck: Boolean, json: org.json.JSONObject) {
        if (scanId <= 0) return
        if (resumed) {
            if (priceCheck) setStatus(StatusText.priceCheckOpening(json), Tone.OK)
            openPanel(scanId, priceCheck)
        } else {
            setStatus(getString(R.string.open_scans_hint, StatusText.name(json)), Tone.OK)
        }
    }
}
