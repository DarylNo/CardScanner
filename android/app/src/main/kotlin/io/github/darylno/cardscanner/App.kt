package io.github.darylno.cardscanner

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.gateway.GatewayService
import io.github.darylno.cardscanner.ident.LocalIdentify
import io.github.darylno.cardscanner.phoneserver.DeviceBridge
import io.github.darylno.cardscanner.phoneserver.PhoneServer
import io.github.darylno.cardscanner.ui.Adapters
import io.github.darylno.cardscanner.ui.AppSettings
import io.github.darylno.cardscanner.ui.CameraPort
import io.github.darylno.cardscanner.ui.GatewayPort
import io.github.darylno.cardscanner.ui.UploadPort
import org.opencv.android.OpenCVLoader

/**
 * App-wide singletons. Stage 4: the phone is the scanner's server — captures go
 * through the persistent upload queue straight into the phone's own store
 * ([PhoneServer.uploader]: identify on the phone, file here), and the review
 * pages are served from the phone ([GatewayService] hosts [phoneServer]).
 * The queue starts HERE (not in an activity) so jobs persisted before a process
 * death resume as soon as the process is back, whatever screen comes up first.
 */
class App : Application() {
    lateinit var settings: AppSettings
        private set
    /** The card database (art pack) + the on-phone identifier. */
    lateinit var identify: LocalIdentify
        private set
    lateinit var phoneServer: PhoneServer
        private set
    lateinit var uploads: UploadPort
        private set
    /** The Share screen's view of the server (guest code, addresses, QR). */
    lateinit var gateway: GatewayPort
        private set
    /** Stops scanning once a newer release is out (the owner's update lock). */
    lateinit var updates: io.github.darylno.cardscanner.update.UpdateLock
        private set
    /** The guided measurement session (Settings → Diagnostics → Run the measurement session). */
    val measure: io.github.darylno.cardscanner.ui.MeasureSession by lazy { io.github.darylno.cardscanner.ui.MeasureSession(settings) }

    override fun onCreate() {
        super.onCreate()
        startDebugLog()
        if (!OpenCVLoader.initLocal()) Log.e(TAG, "OpenCV native init failed — capture will not work")
        settings = AppSettings(this)
        updates = io.github.darylno.cardscanner.update.UpdateLock(this, BuildConfig.VERSION_NAME,
            // Unit tests (Robolectric) never ask GitHub: a live answer would race the test's own state.
            fetch = if (android.os.Build.FINGERPRINT == "robolectric") { { null } }
                else io.github.darylno.cardscanner.update.UpdateLock::fetchLatest)
        identify = LocalIdentify(this)
        phoneServer = PhoneServer(this, identify, DeviceBridge(settings) { io.github.darylno.cardscanner.camera.CameraCatalog.lenses(this) }, updates, debugHeader = { debugHeader() })
        GatewayService.hostProvider = { phoneServer }
        uploads = Adapters.uploads(this, phoneServer)
        gateway = Adapters.gateway(this)
        // No card database yet → fetch it now (any network); queued scans wait for it.
        updates.checkAsync(force = true)
        identify.onAppStart { if (it is io.github.darylno.cardscanner.ident.ArtPackStore.Check.Installed) uploads.retryNow() }
    }

    fun newCamera(): CameraPort = Adapters.camera(this, settings)

    // ── the live log ────────────────────────────────────────────────────────
    private val crashFile get() = java.io.File(filesDir, "last-crash.txt")

    /**
     * Mirror the live log to logcat (`adb logcat -s CardScanner`), and keep it past a
     * crash: the log is in memory, so an uncaught exception writes it to
     * [crashFile] first; the next launch notes it and the debug report carries it.
     */
    private fun startDebugLog() {
        val log = DebugLog.global
        log.sink = { e ->
            when (e.level) {
                'E' -> Log.e(TAG, "${e.tag}: ${e.msg}")
                'W' -> Log.w(TAG, "${e.tag}: ${e.msg}")
                else -> Log.i(TAG, "${e.tag}: ${e.msg}")
            }
        }
        log.i("app", "start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        if (crashFile.isFile) log.w("app", "the previous run crashed — its last log is in the debug report")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, err ->
            runCatching {
                log.e("crash", "uncaught on ${t.name}: ${err.stackTraceToString().take(1500)}")
                crashFile.writeText(io.github.darylno.cardscanner.core.server.DebugApi.report(log) { "crash at ${DebugLog.iso(System.currentTimeMillis())}" })
            }
            previous?.uncaughtException(t, err)
        }
    }

    /** The report's head: the Settings diagnostics, plus the previous crash's log if there was one. */
    private fun debugHeader(): String = buildString {
        append(runCatching { io.github.darylno.cardscanner.ui.DiagnosticsText.build(this@App) }.getOrElse { "diagnostics failed: ${it.message}" })
        if (crashFile.isFile) {
            append("\n\n── the previous run CRASHED; its log: ──\n")
            append(runCatching { crashFile.readText().takeLast(60_000) }.getOrDefault("(unreadable)"))
        }
    }

    /** Diagnostics + the whole live log — Settings → Share debug report, and GET /api/debug/report.txt. */
    fun debugReport(): String = io.github.darylno.cardscanner.core.server.DebugApi.report(DebugLog.global) { debugHeader() }

    companion object {
        private const val TAG = "CardScanner"
        fun of(ctx: Context): App = ctx.applicationContext as App
    }
}
