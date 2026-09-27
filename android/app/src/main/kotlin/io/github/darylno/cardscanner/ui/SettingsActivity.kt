package io.github.darylno.cardscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.BuildConfig
import java.util.concurrent.Executors

/**
 * Server addresses (failover list; add / remove / refresh / re-pair), camera
 * options and Diagnostics. Camera options take effect when the camera screen
 * resumes (resolution: on its next bind).
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var app: App
    private lateinit var col: LinearLayout
    private val io = Executors.newSingleThreadExecutor()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0f1115"))
            addView(col)
        })
        render()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun text(t: String, size: Float = 15f, color: Int = Color.parseColor("#e5e7eb")) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        isChecked = value
        setTextColor(Color.parseColor("#e5e7eb"))
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> set(v) }
    }

    private fun render() {
        col.removeAllViews()
        val s = app.settings
        col.addView(text("Settings", 20f, Color.WHITE))

        col.addView(text("Server addresses (tried in order, automatic failover)", 16f, Color.WHITE))
        val urls = app.server.urls()
        if (urls.isEmpty()) col.addView(text("Not paired."))
        for (u in urls) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(text("${SetupActivity.label(u)}: $u"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Remove") {
                if (urls.size <= 1) {
                    Toast.makeText(this, "Keep at least one address (or re-pair).", Toast.LENGTH_SHORT).show()
                } else {
                    app.server.setUrls(urls - u); render()
                }
            })
            col.addView(row)
        }
        col.addView(button("Add address…") { addAddress() })
        col.addView(button("Refresh from server") {
            io.execute {
                val msg = try {
                    app.server.refreshAddresses(); "Addresses updated."
                } catch (e: Exception) {
                    "Couldn't refresh: ${e.message}"
                }
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); render() }
            }
        })
        col.addView(button("Re-pair (scan the desktop QR again)") {
            AlertDialog.Builder(this)
                .setMessage("Forget this server and pair again?")
                .setPositiveButton("Re-pair") { _, _ ->
                    app.server.unpair()
                    startActivity(Intent(this, SetupActivity::class.java))
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })

        col.addView(text("Camera", 16f, Color.WHITE))
        col.addView(toggle("High analysis resolution (2048×1536; Standard is 1600×1200)", s.highRes) { s.highRes = it })
        // No focus-lock switch: Mount always locks focus on the first card after
        // each bind (CameraController) — a switch that couldn't turn it off
        // was removed rather than shipped. Tap the preview to refocus.
        col.addView(toggle("Lock exposure + white balance", s.aeLock) { s.aeLock = it })
        col.addView(toggle("Torch", s.torch) { s.torch = it })
        col.addView(toggle("Debug overlay (mask %, steady count, timings)", s.debugOverlay) { s.debugOverlay = it })

        col.addView(text("Diagnostics", 16f, Color.WHITE))
        val diag = diagnostics()
        col.addView(text(diag, 12f))
        col.addView(button("Copy diagnostics") {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Card Scanner diagnostics", diag))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        })
        col.addView(button("Back") { finish() })
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
        val input = EditText(this).apply { hint = "https://host:8443" }
        AlertDialog.Builder(this)
            .setTitle("Add server address")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val n = app.server.normalize(input.text.toString())
                if (n == null) Toast.makeText(this, "Not a server address.", Toast.LENGTH_SHORT).show()
                else { app.server.setUrls(app.server.urls() + n); render() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/** Latest CameraController.diagnostics() text, captured by the camera screen (Settings has no camera). */
object CameraDiagnostics {
    @Volatile var last: String? = null
}
