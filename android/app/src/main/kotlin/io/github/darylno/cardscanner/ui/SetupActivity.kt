package io.github.darylno.cardscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import io.github.darylno.cardscanner.R
import io.github.darylno.cardscanner.net.Pin
import java.security.cert.CertificateException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

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
    private lateinit var statusDetail: TextView
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
        if (ok) startQrCamera() else cameraUnusable(getString(R.string.setup_no_permission))
    }

    private lateinit var chrome: ScanChrome
    private lateinit var manualCard: LinearLayout
    private lateinit var manualToggle: Button
    private lateinit var pairedCard: LinearLayout

    private fun dp(v: Int) = chrome.dp(v)
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT

    private enum class Tone { NORMAL, OK, ERR }

    /** The one status line under the camera: friendly words only (details go to logcat). */
    private fun setStatus(msg: String, tone: Tone = Tone.NORMAL, detail: String? = null) {
        statusDetail.text = detail ?: ""
        statusDetail.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
        status.text = msg
        status.setTextColor(
            when (tone) {
                Tone.OK -> ScanChrome.Palette.OK
                Tone.ERR -> ScanChrome.Palette.ERR
                Tone.NORMAL -> ScanChrome.Palette.TEXT_DIM
            }
        )
    }

    /** No QR camera (denied / missing): say so plainly and open the typed-address section. */
    private fun cameraUnusable(msg: String, detail: String? = null) {
        setStatus(msg, Tone.ERR, detail)
        showManual(true)
    }

    private fun showManual(show: Boolean) {
        manualCard.visibility = if (show) View.VISIBLE else View.GONE
        manualToggle.text = getString(R.string.setup_manual_show) + if (show) "  ▴" else "  ▾"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        chrome = ScanChrome(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        col.addView(chrome.text(getString(R.string.setup_title), 28f, ScanChrome.Palette.TEXT, bold = true))

        // ── steps ──
        fun step(n: Int, t: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            addView(chrome.text(n.toString(), 13f, ScanChrome.Palette.TEXT_ON_ACTIVE, bold = true).apply {
                gravity = Gravity.CENTER
                background = chrome.pill(ScanChrome.Palette.CHIP_ACTIVE)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            addView(chrome.text(t, 15f, ScanChrome.Palette.TEXT_DIM), LinearLayout.LayoutParams(0, wrap(), 1f))
        }
        col.addView(step(1, getString(R.string.setup_step1)), LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(16) })
        col.addView(step(2, getString(R.string.setup_step2)), LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(12) })

        // ── camera in a rounded frame with viewfinder corners ──
        val frame = FrameLayout(this)
        chrome.clipRounded(frame, Color.BLACK, 20f)
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView-backed so the rounded clip applies (a SurfaceView ignores it).
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        frame.addView(preview, FrameLayout.LayoutParams(match(), match()))
        frame.addView(ViewfinderView(this, ScanChrome.Palette.BG, 20f), FrameLayout.LayoutParams(match(), match()))
        col.addView(frame, LinearLayout.LayoutParams(match(), dp(300)).apply { topMargin = dp(24) })

        status = chrome.text("", 15f, ScanChrome.Palette.TEXT_DIM).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(14), dp(8), dp(6))
        }
        col.addView(status, LinearLayout.LayoutParams(match(), wrap()))
        // The technical reason under a failure, so it can be diagnosed from the phone.
        statusDetail = chrome.text("", 12f, ScanChrome.Palette.TEXT_DIM).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextIsSelectable(true)
            setPadding(dp(8), 0, dp(8), dp(6))
            visibility = View.GONE
        }
        col.addView(statusDetail, LinearLayout.LayoutParams(match(), wrap()))
        setStatus(getString(R.string.setup_looking))

        // ── paired: addresses + Start scanning (hidden until pairing succeeds) ──
        pairedCard = chrome.card().apply { visibility = View.GONE }
        pairedCard.addView(chrome.text(getString(R.string.setup_paired_to).uppercase(), 12f, ScanChrome.Palette.TEXT_FAINT, bold = true).apply {
            letterSpacing = 0.08f
        })
        addresses = chrome.text("", 14f, ScanChrome.Palette.TEXT).apply { setPadding(0, dp(8), 0, dp(4)) }
        pairedCard.addView(addresses)
        col.addView(pairedCard, LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(8) })
        doneBtn = chrome.primaryButton(getString(R.string.setup_done)) { finish() }.apply { visibility = View.GONE }
        col.addView(doneBtn, LinearLayout.LayoutParams(match(), dp(52)).apply { topMargin = dp(16) })

        // ── typed address (secondary, collapsed) ──
        manualToggle = chrome.secondaryButton("") { showManual(manualCard.visibility != View.VISIBLE) }
        col.addView(manualToggle, LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(12) })
        manualCard = chrome.card()
        manualCard.addView(chrome.text(getString(R.string.setup_manual_hint), 14f, ScanChrome.Palette.TEXT_DIM))
        input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(ScanChrome.Palette.TEXT)
            setHintTextColor(ScanChrome.Palette.TEXT_FAINT)
            hint = getString(R.string.setup_input_hint)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> onTyped(); true }
            background = chrome.rounded(ScanChrome.Palette.SURFACE_HI, 12f, ScanChrome.Palette.OUTLINE)
            setPadding(dp(14), 0, dp(14), 0)
        }
        manualCard.addView(input, LinearLayout.LayoutParams(match(), dp(52)).apply { topMargin = dp(12) })
        manualCard.addView(chrome.primaryButton(getString(R.string.setup_connect)) { onTyped() },
            LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(12) })
        col.addView(manualCard, LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(12) })
        showManual(false)

        val root = ScrollView(this).apply {
            setBackgroundColor(ScanChrome.Palette.BG)
            isFillViewport = true
            addView(col)
        }
        chrome.applyInsets(root)
        setContentView(root)

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
                Log.w(TAG, "QR camera: provider unavailable", e)
                cameraUnusable(getString(R.string.setup_camera_unavailable), technical(e))
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
                Log.w(TAG, "QR camera: bind failed", e)
                cameraUnusable(getString(R.string.setup_camera_unavailable), technical(e))
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
            setStatus(getString(R.string.setup_bad_address), Tone.ERR)
            return
        }
        val pin = Pin.parsePinFromQrUrl(raw)
        handled.set(true)
        unbindQr()
        if (pin != null) pair(base, pin) else probeAndConfirm(base)
    }

    private fun probeAndConfirm(base: String) {
        setStatus(getString(R.string.setup_contacting, base))
        io.execute {
            val fp = try {
                app.server.probe(base)
            } catch (e: Exception) {
                Log.w(TAG, "probe $base failed", e)
                runOnUiThread { failed(getString(R.string.setup_unreachable, base), technical(e)) }
                return@execute
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(R.string.setup_trust_title)
                    .setMessage(getString(R.string.setup_trust_msg, base, Pin.formatFingerprint(fp)))
                    .setPositiveButton(R.string.setup_trust) { _, _ -> pair(base, fp) }
                    .setNegativeButton(R.string.cancel) { _, _ -> failed(getString(R.string.setup_not_paired)) }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun pair(base: String, pin: String) {
        setStatus(getString(R.string.setup_pairing, base))
        io.execute {
            try {
                val urls = app.server.pair(base, pin)
                app.uploads.retryNow()
                runOnUiThread {
                    setStatus(getString(R.string.setup_paired, Pin.formatFingerprint(pin).take(23)), Tone.OK)
                    addresses.text = describe(urls)
                    pairedCard.visibility = View.VISIBLE
                    doneBtn.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.w(TAG, "pairing with $base failed", e)
                val msg = if (e is SSLException || e is CertificateException) R.string.setup_cert_mismatch
                    else R.string.setup_pair_failed
                runOnUiThread { failed(getString(msg), technical(e)) }
            }
        }
    }

    private fun failed(msg: String, detail: String? = null) {
        setStatus(msg, Tone.ERR, detail)
        handled.set(false)
        startQrCamera()
    }

    companion object {
        private const val TAG = "CardScanner.Setup"

        /** "SSLPeerUnverifiedException: … ← CertificateException: …" — the reason shown under a failure. */
        fun technical(e: Throwable): String = generateSequence(e) { it.cause.takeIf { c -> c !== it } }
            .take(3)
            .joinToString(" ← ") { t -> t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "") }

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
