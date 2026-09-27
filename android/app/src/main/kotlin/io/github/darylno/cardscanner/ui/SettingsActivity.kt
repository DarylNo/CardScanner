package io.github.darylno.cardscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.BuildConfig
import io.github.darylno.cardscanner.R
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

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(top) }

    private fun note(t: String) = chrome.text(t, 13f, ScanChrome.Palette.TEXT_FAINT).apply { setPadding(dp(4), dp(8), dp(4), 0) }

    private fun render() {
        col.removeAllViews()
        val s = app.settings

        // ── Server: the failover list, in order ──
        col.addView(chrome.sectionHeader(getString(R.string.settings_server)).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
        val server = chrome.card(padDp = 8)
        val urls = app.server.urls()
        if (urls.isEmpty()) {
            server.addView(chrome.text(getString(R.string.settings_not_paired), 15f, ScanChrome.Palette.TEXT_DIM)
                .apply { setPadding(dp(8), dp(8), dp(8), dp(8)) })
        }
        urls.forEachIndexed { i, u ->
            if (i > 0) server.addView(chrome.divider(), chrome.dividerParams().apply { marginStart = dp(8); marginEnd = dp(8) })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(4), dp(6))
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(chrome.text(SetupActivity.label(u), 16f))
                addView(chrome.text(u, 13f, ScanChrome.Palette.TEXT_DIM).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setPadding(0, dp(2), 0, 0)
                })
            }
            row.addView(texts, LinearLayout.LayoutParams(0, wrap(), 1f).apply { marginEnd = dp(8) })
            row.addView(chrome.smallButton(chrome.destructiveButton(getString(R.string.settings_remove)) {
                if (urls.size <= 1) {
                    Toast.makeText(this, getString(R.string.settings_keep_one), Toast.LENGTH_SHORT).show()
                } else {
                    app.server.setUrls(urls - u); render()
                }
            }), LinearLayout.LayoutParams(wrap(), wrap()))
            server.addView(row, lp())
        }
        col.addView(server, lp())
        col.addView(note(getString(R.string.settings_server_note)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(chrome.secondaryButton(getString(R.string.settings_add)) { addAddress() },
            LinearLayout.LayoutParams(0, wrap(), 1f).apply { marginEnd = dp(6) })
        actions.addView(chrome.secondaryButton(getString(R.string.settings_refresh)) {
            io.execute {
                val msg = try {
                    app.server.refreshAddresses(); getString(R.string.settings_refreshed)
                } catch (e: Exception) {
                    getString(R.string.settings_refresh_failed, e.message)
                }
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); render() }
            }
        }, LinearLayout.LayoutParams(0, wrap(), 1f).apply { marginStart = dp(6) })
        col.addView(actions, lp(12))
        col.addView(chrome.destructiveButton(getString(R.string.settings_repair)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.settings_repair_confirm)
                .setPositiveButton(R.string.settings_repair_ok) { _, _ ->
                    app.server.unpair()
                    startActivity(Intent(this, SetupActivity::class.java))
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }, lp(12))

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
        append("server ").append(app.server.bestBase() ?: "—").append('\n')
        append("queue pending ").append(app.uploads.pending).append("\n\n")
        append(CameraDiagnostics.last ?: "camera: open the scanner screen once to collect camera details").append("\n\n")
        append("recent captures (ms):\n").append(app.settings.recentTimings.ifBlank { "—" })
    }

    private fun addAddress() {
        val input = EditText(this).apply {
            hint = getString(R.string.settings_add_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_add_title)
            .setView(box)
            .setPositiveButton(R.string.settings_add_ok) { _, _ ->
                val n = app.server.normalize(input.text.toString())
                if (n == null) Toast.makeText(this, getString(R.string.settings_not_address), Toast.LENGTH_SHORT).show()
                else { app.server.setUrls(app.server.urls() + n); render() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/** Latest CameraController.diagnostics() text, captured by the camera screen (Settings has no camera). */
object CameraDiagnostics {
    @Volatile var last: String? = null
}
