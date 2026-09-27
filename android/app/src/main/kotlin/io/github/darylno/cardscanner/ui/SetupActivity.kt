package io.github.darylno.cardscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.qrcode.QRCodeReader
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.net.Pin
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pairing. (1) Scan the desktop's phone QR (`https://<ip>:8443/phone#pin=…`):
 * the pin in the fragment pairs directly — the QR on the user's own screen is
 * the trust anchor. (2) Or type an address: probe the certificate and ask the
 * user to compare its SHA-256 before trusting it. Either way the server's
 * /api/addresses list (LAN + Tailscale) is learned and saved for failover.
 */
class SetupActivity : AppCompatActivity() {
    private lateinit var app: App
    private lateinit var preview: PreviewView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var addresses: TextView
    private lateinit var doneBtn: Button
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysisExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val handled = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    /**
     * THIS screen's own QR use cases. Never unbind-all on the provider: it is
     * process-wide, so that also tears down MainActivity's Preview+Analysis
     * (Setup is destroyed AFTER MainActivity has re-bound on pairing / Re-pair),
     * leaving a black preview and dead scanning. Unbind only what we bound.
     */
    private var qrUseCases: Array<UseCase> = emptyArray()

    private fun unbindQr() {
        val uc = qrUseCases
        qrUseCases = emptyArray()
        if (uc.isNotEmpty()) runCatching { cameraProvider?.unbind(*uc) }
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startQrCamera() else status.text = "No camera permission — type the server address instead."
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun text(t: String, size: Float = 15f, color: Int = Color.parseColor("#e5e7eb")) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
        }
        col.addView(text("Pair with your scanner server", 20f, Color.WHITE))
        col.addView(text("On the desktop, open the scanner page and show the phone QR code, then point this camera at it."))
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        col.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)))
        col.addView(text("…or type the server address (e.g. 192.168.1.42 or rig.tailnet.ts.net):"))
        input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8b93a1"))
            hint = "192.168.1.42:8443"
        }
        col.addView(input)
        col.addView(Button(this).apply {
            text = "Connect"
            isAllCaps = false
            setOnClickListener { onTyped() }
        })
        status = text("", 15f, Color.parseColor("#fbbf24"))
        addresses = text("", 14f)
        col.addView(status)
        col.addView(addresses)
        doneBtn = Button(this).apply {
            text = "Done — start scanning"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { finish() }
        }
        col.addView(doneBtn)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0f1115"))
            addView(col)
        })

        // Backing out unpaired leaves the app (the camera screen would only send us back here).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (app.server.isPaired) finish() else finishAffinity()
            }
        })

        // Re-pairing: forget any SSL "proceed" the WebView may remember for the old cert.
        WebView(this).apply { clearSslPreferences(); destroy() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startQrCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        unbindQr()   // own use cases only — see qrUseCases
        analysisExec.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    // ── QR camera (own, minimal CameraX binding; the scan camera isn't running here) ──
    private fun startQrCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                status.text = "Camera unavailable: ${e.message}"
                return@addListener
            }
            cameraProvider = provider
            val pv = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val reader = QRCodeReader()
            analysis.setAnalyzer(analysisExec) { image -> decode(image, reader) }
            try {
                unbindQr()   // a previous QR binding of ours (failed() restarts) — not MainActivity's
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
                qrUseCases = arrayOf(pv, analysis)
            } catch (e: Exception) {
                status.text = "Camera unavailable: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun decode(image: ImageProxy, reader: QRCodeReader) {
        try {
            if (handled.get()) return
            val plane = image.planes[0]
            val buf = plane.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val text = QrDecode.decode(bytes, plane.rowStride, image.width, image.height, reader)
            if (text != null && app.server.normalize(text) != null && handled.compareAndSet(false, true)) {
                runOnUiThread { onScanned(text) }
            }
        } finally {
            image.close()
        }
    }

    private fun onScanned(text: String) {
        unbindQr()
        val base = app.server.normalize(text)
        val pin = Pin.parsePinFromQrUrl(text)
        if (base == null) {
            handled.set(false); startQrCamera(); return
        }
        input.setText(base)
        if (pin != null) pair(base, pin) else probeAndConfirm(base)
    }

    private fun onTyped() {
        val raw = input.text.toString()
        val base = app.server.normalize(raw)
        if (base == null) {
            status.text = "That doesn't look like a server address."
            return
        }
        val pin = Pin.parsePinFromQrUrl(raw)
        handled.set(true)
        unbindQr()
        if (pin != null) pair(base, pin) else probeAndConfirm(base)
    }

    private fun probeAndConfirm(base: String) {
        status.text = "Contacting $base…"
        io.execute {
            val fp = try {
                app.server.probe(base)
            } catch (e: Exception) {
                runOnUiThread { failed("Couldn't reach $base: ${e.message}") }
                return@execute
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Trust this server?")
                    .setMessage(
                        "$base presents certificate\n\nSHA-256 ${Pin.formatFingerprint(fp)}\n\n" +
                            "Compare it with the #pin= value in the desktop's phone QR link " +
                            "(desktop scanner page → phone QR). If they differ, do NOT trust it — " +
                            "scan the desktop QR instead."
                    )
                    .setPositiveButton("Trust") { _, _ -> pair(base, fp) }
                    .setNegativeButton("Cancel") { _, _ -> failed("Not paired.") }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun pair(base: String, pin: String) {
        status.text = "Pairing with $base…"
        io.execute {
            try {
                val urls = app.server.pair(base, pin)
                app.uploads.retryNow()
                runOnUiThread {
                    status.text = "✓ Paired. Fingerprint ${Pin.formatFingerprint(pin).take(23)}…"
                    status.setTextColor(Color.parseColor("#6ee7a0"))
                    addresses.text = describe(urls)
                    doneBtn.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                runOnUiThread { failed("Pairing failed: ${e.message}") }
            }
        }
    }

    private fun failed(msg: String) {
        status.text = msg
        status.setTextColor(Color.parseColor("#f87171"))
        handled.set(false)
        startQrCamera()
    }

    companion object {
        /** "Home: https://192.168.1.42:8443" / "Tailscale: https://rig.tail…:8443". */
        fun describe(urls: List<String>): String = urls.joinToString("\n") { "${label(it)}: $it" }

        fun label(url: String): String {
            val host = url.substringAfter("://").substringBefore(':').substringBefore('/')
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            return when {
                host.endsWith(".ts.net") -> "Tailscale"
                parts.size == 4 && parts[0] == 100 && parts[1] in 64..127 -> "Tailscale"
                parts.size == 4 -> "Home"
                else -> "Server"
            }
        }
    }
}
