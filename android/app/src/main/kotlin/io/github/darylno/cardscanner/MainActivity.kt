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
import io.github.darylno.cardscanner.ui.ScanChrome
import io.github.darylno.cardscanner.ui.ScanRate
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

    private lateinit var chrome: ScanChrome
    private lateinit var serverText: TextView
    private lateinit var connDot: View
    private lateinit var mountSeg: TextView
    private lateinit var handSeg: TextView
    private lateinit var areaBtn: TextView
    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusView: TextView
    private lateinit var queueView: TextView
    private lateinit var rateView: TextView
    /** Full-screen coloured edge flash + a big message: the "next card" / "same card" signals. */
    private lateinit var flashView: View
    private lateinit var ackView: TextView
    /** Identity of the last card FILED in Mount mode (printing id, else name) — for "same card as last". */
    private var lastFiledKey: String? = null
    /** Cards filed per minute this session (see [ScanRate]); refreshed every few seconds. */
    private val scanRate = ScanRate()
    private val rateTick = object : Runnable {
        override fun run() {
            refreshRate()
            rateView.postDelayed(this, 5_000)
        }
    }
    private lateinit var autoBtn: TextView
    private lateinit var shutter: View
    private lateinit var retryBtn: TextView

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

    override fun onStart() {
        super.onStart()
        rateView.post(rateTick)
    }

    override fun onStop() {
        rateView.removeCallbacks(rateTick)
        super.onStop()
    }

    private fun refreshRate() {
        val t = scanRate.label(android.os.SystemClock.elapsedRealtime())
        rateView.text = t
        rateView.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    /** A scan that FILED a card (the server made a row) counts toward the rate. */
    private fun countFiled(scanId: Long?) {
        if (scanId == null || scanId <= 0) return
        scanRate.record(android.os.SystemClock.elapsedRealtime())
        refreshRate()
    }

    override fun onDestroy() {
        app.uploads.removeListener(uploadListener)
        camera?.unbind()
        camera = null
        io.shutdown()
        super.onDestroy()
    }

    // ── layout ──────────────────────────────────────────────────────────────
    // A camera app: the preview fills the screen; controls float over it on
    // scrims. Top: server status + Scans/Share/Settings. Middle: nothing but
    // the card. Bottom: status pill, Mount|Handheld switch, and the shutter
    // with Auto (left) and Area (right) in Mount mode.
    private fun dp(v: Int) = chrome.dp(v)

    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT

    private fun buildUi() {
        chrome = ScanChrome(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        overlay = OverlayView(this)
        root.addView(preview, FrameLayout.LayoutParams(match(), match()))
        root.addView(overlay, FrameLayout.LayoutParams(match(), match()))

        // ── top bar ──
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = chrome.scrim(top = true)
            setPadding(dp(12), dp(10), dp(12), dp(28))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        connDot = View(this).apply { background = chrome.dot(ScanChrome.Palette.TEXT_DIM) }
        topRow.addView(connDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        serverText = TextView(this).apply {
            setTextColor(ScanChrome.Palette.TEXT_DIM)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = getString(R.string.banner_fmt, BuildConfig.VERSION_NAME, "…")
        }
        topRow.addView(serverText, LinearLayout.LayoutParams(0, wrap(), 1f))
        topRow.addView(chrome.chip(getString(R.string.scans)) { openPanel(0L, false) },
            LinearLayout.LayoutParams(wrap(), wrap()).apply { marginStart = dp(6) })
        topRow.addView(chrome.chip(getString(R.string.share)) { startActivity(Intent(this, ShareActivity::class.java)) },
            LinearLayout.LayoutParams(wrap(), wrap()).apply { marginStart = dp(6) })
        topRow.addView(chrome.chip("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) }.apply {
            contentDescription = getString(R.string.settings)
            textSize = 18f
        }, LinearLayout.LayoutParams(wrap(), wrap()).apply { marginStart = dp(6) })
        top.addView(topRow)
        // Cards per minute (rolling 5 min) · cards this session — small and dim.
        rateView = TextView(this).apply {
            setTextColor(ScanChrome.Palette.TEXT_DIM)
            textSize = 12f
            visibility = View.GONE
            contentDescription = getString(R.string.scan_rate_desc)
        }
        top.addView(rateView, LinearLayout.LayoutParams(wrap(), wrap()).apply { topMargin = dp(4); marginStart = dp(16) })
        queueView = chrome.chip("") { app.uploads.retryNow() }.apply {
            // Tapping the upload indicator retries now (resets the backoff).
            setTextColor(ScanChrome.Palette.WARN)
            textSize = 13f
            minHeight = dp(36)
            visibility = View.GONE
        }
        top.addView(queueView, LinearLayout.LayoutParams(wrap(), wrap()).apply {
            gravity = Gravity.END; topMargin = dp(8)
        })
        root.addView(top, FrameLayout.LayoutParams(match(), wrap(), Gravity.TOP))

        // ── bottom controls ──
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = chrome.scrim(top = false)
            setPadding(dp(16), dp(40), dp(16), dp(20))
        }
        statusView = chrome.statusPill()
        bottom.addView(statusView, LinearLayout.LayoutParams(wrap(), wrap()).apply { bottomMargin = dp(10) })
        retryBtn = chrome.chip(getString(R.string.retry)) { onRetryTap() }.apply {
            setTextColor(ScanChrome.Palette.TEXT_ON_ACTIVE)
            background = chrome.pill(ScanChrome.Palette.WARN)
            visibility = View.GONE
        }
        bottom.addView(retryBtn, LinearLayout.LayoutParams(wrap(), wrap()).apply { bottomMargin = dp(10) })

        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = chrome.pill(ScanChrome.Palette.CHIP)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        fun segItem(label: String, mode: AppSettings.Mode) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = dp(36)
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { if (settings.mode != mode) toggleMode() }
        }
        mountSeg = segItem(getString(R.string.mode_mount), AppSettings.Mode.MOUNT)
        handSeg = segItem(getString(R.string.mode_handheld), AppSettings.Mode.HANDHELD)
        seg.addView(mountSeg); seg.addView(handSeg)
        bottom.addView(seg, LinearLayout.LayoutParams(wrap(), wrap()).apply { bottomMargin = dp(16) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        autoBtn = chrome.chip("") { toggleAuto() }
        areaBtn = chrome.chip(getString(R.string.area)) { onAreaButton() }
        shutter = View(this).apply {
            background = chrome.shutter()
            contentDescription = getString(R.string.scan_card)
            isClickable = true
            isFocusable = true
            setOnClickListener { onScanTap() }
        }
        val side = { v: View, g: Int -> FrameLayout(this).apply {
            addView(v, FrameLayout.LayoutParams(wrap(), wrap(), g or Gravity.CENTER_VERTICAL))
        } }
        controls.addView(side(autoBtn, Gravity.START), LinearLayout.LayoutParams(0, wrap(), 1f))
        controls.addView(shutter, LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(12); marginEnd = dp(12) })
        controls.addView(side(areaBtn, Gravity.END), LinearLayout.LayoutParams(0, wrap(), 1f))
        bottom.addView(controls, LinearLayout.LayoutParams(match(), wrap()))
        root.addView(bottom, FrameLayout.LayoutParams(match(), wrap(), Gravity.BOTTOM))

        // Edge-to-edge (enforced on Android 15): keep the controls clear of the system bars.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            top.setPadding(dp(12) + bars.left, dp(10) + bars.top, dp(12) + bars.right, dp(28))
            bottom.setPadding(dp(16) + bars.left, dp(40), dp(16) + bars.right, dp(20) + bars.bottom)
            insets
        }
        // Acknowledgement layer, above everything, never takes touches.
        flashView = View(this).apply { alpha = 0f; isClickable = false; isFocusable = false }
        root.addView(flashView, FrameLayout.LayoutParams(match(), match()))
        ackView = TextView(this).apply {
            alpha = 0f
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            isClickable = false
        }
        root.addView(ackView, FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER).apply {
            bottomMargin = dp(120); marginStart = dp(32); marginEnd = dp(32)   // long messages wrap inside the screen
        })
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
                Tone.OK -> ScanChrome.Palette.OK
                Tone.ERR -> ScanChrome.Palette.ERR
                Tone.NORMAL -> ScanChrome.Palette.TEXT
            }
        )
    }

    private fun refreshBanner() {
        io.execute {
            val v = try {
                app.server.version()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                serverText.text = getString(R.string.banner_fmt, BuildConfig.VERSION_NAME, v ?: getString(R.string.server_offline))
                connDot.background = chrome.dot(if (v != null) ScanChrome.Palette.OK else ScanChrome.Palette.ERR)
            }
        }
    }

    // ── modes / controls ────────────────────────────────────────────────────
    private val handheld get() = settings.mode == AppSettings.Mode.HANDHELD

    private fun applyMode() {
        styleSegment(mountSeg, !handheld)
        styleSegment(handSeg, handheld)
        // INVISIBLE, not GONE: the shutter stays centred in both modes.
        autoBtn.visibility = if (handheld) View.INVISIBLE else View.VISIBLE
        areaBtn.visibility = if (handheld) View.INVISIBLE else View.VISIBLE
        styleAuto()
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

    private fun styleSegment(v: TextView, on: Boolean) {
        v.background = if (on) chrome.pill(ScanChrome.Palette.CHIP_ACTIVE) else null
        v.setTextColor(if (on) ScanChrome.Palette.TEXT_ON_ACTIVE else ScanChrome.Palette.TEXT)
    }

    /** Auto reads as a toggle: filled when on. */
    private fun styleAuto() {
        autoBtn.text = getString(if (settings.auto) R.string.auto_on else R.string.auto_off)
        autoBtn.background = chrome.pill(if (settings.auto) ScanChrome.Palette.CHIP_ACTIVE else ScanChrome.Palette.CHIP)
        autoBtn.setTextColor(if (settings.auto) ScanChrome.Palette.TEXT_ON_ACTIVE else ScanChrome.Palette.TEXT)
    }

    private fun toggleAuto() {
        settings.auto = !settings.auto
        styleAuto()
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
        if (!settings.vibration) return
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

    private fun vibratePattern(vararg timings: Long) {
        if (!settings.vibration) return
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        try {
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0L) + timings, -1))
        } catch (_: Exception) {
            // Haptics are a convenience; never let them break a capture.
        }
    }

    private enum class Ack { NEXT_CARD, SAME_CARD }

    /**
     * The owner asked for an unmistakable "you can drop the next card" signal, and a
     * different one when the result is the same card as last time (still on the tray,
     * or a second copy). Coloured edge flash + a big message that fades + a haptic
     * pattern you can feel without looking: two short buzzes = next card, one long
     * buzz = same card as last.
     */
    private fun acknowledge(kind: Ack, message: String) {
        val colour = if (kind == Ack.NEXT_CARD) ScanChrome.Palette.OK else ScanChrome.Palette.WARN
        flashView.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(14), colour)
        }
        ackView.text = message
        ackView.setTextColor(ScanChrome.Palette.TEXT_ON_ACTIVE)
        ackView.background = chrome.pill(colour)
        for (v in listOf(flashView, ackView)) {
            v.animate().cancel()
            v.alpha = 1f
            v.animate().alpha(0f).setStartDelay(if (v === ackView) 900L else 350L).setDuration(500L).start()
        }
        if (kind == Ack.NEXT_CARD) vibratePattern(40, 70, 40) else vibratePattern(450)
    }

    /** Same printing (else same name) as the last card filed → it's likely still on the tray. */
    private fun filedKey(json: org.json.JSONObject): String? {
        json.optJSONObject("selection")?.optString("scryfall_id")?.takeIf { it.isNotBlank() }?.let { return "id:$it" }
        json.optJSONArray("candidates")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }?.let { return "id:$it" }
        return StatusText.name(json).takeIf { it != "?" }?.let { "name:$it" }
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
        // The photo is taken — the rest (upload, identify) happens in the background,
        // so the card can go now. Say so, loudly.
        acknowledge(Ack.NEXT_CARD, getString(if (handheld) R.string.ack_got_it else R.string.ack_next_card))
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
        countFiled(when (outcome) {   // no_card / rejected carry no row → not counted
            is Outcome.AutoFiled -> outcome.scanId
            is Outcome.NeedsPick -> outcome.scanId
            is Outcome.BestGuess -> outcome.scanId
            is Outcome.NoMatch -> outcome.scanId
            else -> null
        })
        // Mount: a result identical to the previous card means the old card is probably
        // still on the tray (or it's a second copy) — tell the operator to drop the next one.
        if (!priceCheck && !handheld) {
            val json = when (outcome) {
                is Outcome.AutoFiled -> outcome.json
                is Outcome.NeedsPick -> outcome.json
                is Outcome.BestGuess -> outcome.json
                else -> null
            }
            val key = json?.let(::filedKey)
            if (key != null) {
                if (key == lastFiledKey) {
                    acknowledge(Ack.SAME_CARD, getString(R.string.ack_same_card))
                }
                lastFiledKey = key
            }
        }
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
