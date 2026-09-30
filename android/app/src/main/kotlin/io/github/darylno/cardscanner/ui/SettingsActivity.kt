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
import io.github.darylno.cardscanner.BuildConfig
import io.github.darylno.cardscanner.R
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

        // ── Diagnostics ──
        // Long-press the header → the hidden Stage 1c F2F network test.
        col.addView(chrome.sectionHeader(getString(R.string.settings_diagnostics)).apply {
            setOnLongClickListener {
                startActivity(Intent(this@SettingsActivity, DiagnosticsActivity::class.java)); true
            }
        })
        val diag = diagnostics()
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
        col.addView(diagCard, lp())
    }

    private fun diagnostics(): String = buildString {
        append("app ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("device ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("server ").append(if (app.phoneServer.running) "serving on :${app.phoneServer.port}" else "stopped")
            .append(" · paired computers ").append(app.phoneServer.admins.count).append('\n')
        append("card database ").append(app.identify.store.installedManifest()?.let { "${it.rows} rows · ${it.buildDate}" } ?: "none")
            .append('\n')
        append("queue pending ").append(app.uploads.pending).append("\n\n")
        append(CameraDiagnostics.last ?: "camera: open the scanner screen once to collect camera details").append("\n\n")
        append("recent captures (ms):\n").append(app.settings.recentTimings.ifBlank { "—" })
    }
}

/** Latest CameraController.diagnostics() text, captured by the camera screen (Settings has no camera). */
object CameraDiagnostics {
    @Volatile var last: String? = null
}
