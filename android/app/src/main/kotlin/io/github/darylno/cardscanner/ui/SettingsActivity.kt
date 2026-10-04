package io.github.darylno.cardscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.MainActivity
import io.github.darylno.cardscanner.BuildConfig
import io.github.darylno.cardscanner.R
import io.github.darylno.cardscanner.camera.CameraCatalog
import io.github.darylno.cardscanner.core.CameraChoice
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.server.DeviceApi
import java.util.concurrent.Executors

/**
 * Server addresses (failover list; add / remove / refresh / re-pair), camera
 * options and Diagnostics. Camera options take effect when the camera screen
 * resumes (resolution: on its next bind).
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var app: App
    private lateinit var col: LinearLayout
    private lateinit var chrome: ScanChrome
    private val io = Executors.newSingleThreadExecutor()

    private fun dp(v: Int) = chrome.dp(v)
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        chrome = ScanChrome(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ScanChrome.Palette.BG)
            addView(chrome.topBar(getString(R.string.settings), getString(R.string.back)) { finish() },
                LinearLayout.LayoutParams(match(), wrap()))
            addView(ScrollView(this@SettingsActivity).apply { addView(col) }, LinearLayout.LayoutParams(match(), 0, 1f))
        }
        chrome.applyInsets(root)
        setContentView(root)
        render()
    }

    private var serverSection: ServerSection? = null

    override fun onDestroy() {
        serverSection?.destroy()
        io.shutdown()
        super.onDestroy()
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(top) }

    private fun note(t: String) = chrome.text(t, 13f, ScanChrome.Palette.TEXT_FAINT).apply { setPadding(dp(4), dp(8), dp(4), 0) }

    /**
     * The Camera setting: Automatic (camera 0 facing back, else the first back camera —
     * core CameraChoice) or any back / external camera this phone has, each labelled
     * with what tells them apart (MP, autofocus or fixed focus, focal length).
     */
    private fun chooseCamera() {
        val s = app.settings
        val lenses = CameraCatalog.lenses(this)
        val offered = CameraChoice.offered(lenses)
        val auto = CameraChoice.automatic(lenses)
        val ids = listOf<String?>(null) + offered.map { it.id }
        val labels = listOf(auto?.let { getString(R.string.settings_lens_auto_item, it) } ?: getString(R.string.settings_lens_auto_none)) +
            offered.map { CameraChoice.label(it) }
        val checked = ids.indexOf(s.cameraId).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_lens)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                val id = ids[which]
                if (id != s.cameraId) {
                    DebugLog.global.i("device", "camera setting → " + (id?.let { "camera $it" } ?: "Automatic"))
                    s.cameraId = id
                }
                d.dismiss(); render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The blue ✓'s hold time: typed in ms, refused outside DeviceApi's range (same rule as the browser). */
    private fun editCheckMs() {
        val s = app.settings
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(s.checkMs.toString())
            setSelectAllOnFocus(true)
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(match(), wrap())) }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_check_ms)
            .setMessage(getString(R.string.settings_check_ms_range, DeviceApi.CHECK_MS_MIN, DeviceApi.CHECK_MS_MAX))
            .setView(box)
            .setPositiveButton(R.string.settings_check_ms_save) { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v < DeviceApi.CHECK_MS_MIN || v > DeviceApi.CHECK_MS_MAX) {
                    Toast.makeText(this, getString(R.string.settings_check_ms_range, DeviceApi.CHECK_MS_MIN, DeviceApi.CHECK_MS_MAX), Toast.LENGTH_LONG).show()
                } else { s.checkMs = v; render() }
            }
            .setNeutralButton(R.string.settings_check_ms_reset) { _, _ -> s.checkMs = DeviceApi.CHECK_MS_DEFAULT; render() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun render() {
        col.removeAllViews()
        val s = app.settings

        // ── This phone's server (Stage 4: the phone IS the server) ──
        serverSection?.destroy()
        serverSection = ServerSection(this, chrome, app.phoneServer, app.identify).also { it.build(col) }

        // ── Camera ──
        col.addView(chrome.sectionHeader(getString(R.string.settings_camera)))
        val cam = chrome.card().apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        // Which lens (owner, 2026-10-02: testers bring other phones with other cameras).
        cam.addView(chrome.valueRow(getString(R.string.settings_lens), getString(R.string.settings_lens_desc),
            s.cameraId?.let { getString(R.string.settings_lens_value, it) } ?: getString(R.string.settings_lens_auto)) { chooseCamera() })
        cam.addView(chrome.divider(), chrome.dividerParams())
        // "Zoom to fit the Area" (owner on 1.1.10: "No zoom?"; 1.1.11, off by default — tried on the rig first).
        cam.addView(chrome.switchRow(getString(R.string.settings_zoomfit), getString(R.string.settings_zoomfit_desc), s.zoomFit) {
            if (it != s.zoomFit) DebugLog.global.i("device", "zoom to fit the Area → ${if (it) "on" else "off"}")
            s.zoomFit = it
        })
        cam.addView(chrome.divider(), chrome.dividerParams())
        cam.addView(chrome.switchRow(getString(R.string.settings_highres), getString(R.string.settings_highres_desc), s.highRes) { s.highRes = it })
        // No focus-lock switch: Mount always locks focus on the first card after
        // each bind (CameraController) — a switch that couldn't turn it off
        // was removed rather than shipped. Tap the preview to refocus.
        cam.addView(chrome.divider(), chrome.dividerParams())
        cam.addView(chrome.switchRow(getString(R.string.settings_aelock), getString(R.string.settings_aelock_desc), s.aeLock) { s.aeLock = it })
        cam.addView(chrome.divider(), chrome.dividerParams())
        cam.addView(chrome.switchRow(getString(R.string.settings_torch), getString(R.string.settings_torch_desc), s.torch) { s.torch = it })
        cam.addView(chrome.divider(), chrome.dividerParams())
        cam.addView(chrome.switchRow(getString(R.string.settings_vibration), getString(R.string.settings_vibration_desc), s.vibration) { s.vibration = it })
        cam.addView(chrome.divider(), chrome.dividerParams())
        cam.addView(chrome.valueRow(getString(R.string.settings_check_ms), getString(R.string.settings_check_ms_desc),
            getString(R.string.settings_check_ms_value, s.checkMs)) { editCheckMs() })
        cam.addView(chrome.divider(), chrome.dividerParams())
        cam.addView(chrome.switchRow(getString(R.string.settings_debug), getString(R.string.settings_debug_desc), s.debugOverlay) { s.debugOverlay = it })
        col.addView(cam, lp())
        col.addView(note(getString(R.string.settings_camera_note)))

        // ── Scans (owner, 2026-10-01: "need a way to delete all scans") ──
        col.addView(chrome.sectionHeader(getString(R.string.settings_scans)))
        val scansCard = chrome.card().apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        val count = runCatching { app.phoneServer.store.count() }.getOrDefault(0)
        scansCard.addView(chrome.valueRow(getString(R.string.settings_delete_all), getString(R.string.settings_delete_all_desc),
            resources.getQuantityString(R.plurals.scans_n, count, count)) { confirmDeleteAll() })
        col.addView(scansCard, lp())

        // ── Diagnostics ──
        // Long-press the header → the hidden Stage 1c F2F network test.
        col.addView(chrome.sectionHeader(getString(R.string.settings_diagnostics)).apply {
            setOnLongClickListener {
                startActivity(Intent(this@SettingsActivity, DiagnosticsActivity::class.java)); true
            }
        })
        val diag = DiagnosticsText.build(app)
        val diagCard = chrome.card()
        diagCard.addView(chrome.text(diag, 12f, ScanChrome.Palette.TEXT_DIM).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        diagCard.addView(chrome.secondaryButton(getString(R.string.settings_copy_diag)) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Card Scanner diagnostics", diag))
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }, lp(14))
        diagCard.addView(chrome.secondaryButton(getString(R.string.settings_share_report)) { shareDebugReport() }, lp(10))
        // The guided rig session (owner, 2026-10-02): prompts each test on the scan screen, marks the log.
        diagCard.addView(chrome.primaryButton(getString(if (app.measure.active) R.string.measure_resume else R.string.measure_run)) {
            if (!app.measure.active) app.measure.start()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }, lp(10))
        col.addView(diagCard, lp())
    }

    /**
     * Delete every scan on the phone — the only copy, so the dialog says how many and
     * that there is no backup. Goes through the phone server as the owner (the pages'
     * own "Clear all"), then the count on this screen is refreshed.
     */
    private fun confirmDeleteAll() {
        val count = runCatching { app.phoneServer.store.count() }.getOrDefault(0)
        val what = resources.getQuantityString(R.plurals.scans_n, count, count)
        if (count == 0) { Toast.makeText(this, R.string.settings_delete_all_none, Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_delete_all_title, what))
            .setMessage(R.string.settings_delete_all_msg)
            .setPositiveButton(R.string.settings_delete_all_go) { _, _ ->
                app.phoneServer.deleteAllScansAsOwner { n ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val msg = if (n >= 0) getString(R.string.settings_deleted_n, resources.getQuantityString(R.plurals.scans_n, n, n))
                                  else getString(R.string.settings_delete_all_failed)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        render()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The debug report (diagnostics + the live log) through the share sheet — paste it anywhere. */
    private fun shareDebugReport() = DebugShare.share(this, app)
}
