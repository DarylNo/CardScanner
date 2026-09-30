package io.github.darylno.cardscanner

import android.app.Application
import android.content.Context
import android.util.Log
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

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e(TAG, "OpenCV native init failed — capture will not work")
        settings = AppSettings(this)
        updates = io.github.darylno.cardscanner.update.UpdateLock(this, BuildConfig.VERSION_NAME,
            // Unit tests (Robolectric) never ask GitHub: a live answer would race the test's own state.
            fetch = if (android.os.Build.FINGERPRINT == "robolectric") { { null } }
                else io.github.darylno.cardscanner.update.UpdateLock::fetchLatest)
        identify = LocalIdentify(this)
        phoneServer = PhoneServer(this, identify, DeviceBridge(settings), updates)
        GatewayService.hostProvider = { phoneServer }
        uploads = Adapters.uploads(this, phoneServer)
        gateway = Adapters.gateway(this)
        // No card database yet → fetch it now (any network); queued scans wait for it.
        updates.checkAsync(force = true)
        identify.onAppStart { if (it is io.github.darylno.cardscanner.ident.ArtPackStore.Check.Installed) uploads.retryNow() }
    }

    fun newCamera(): CameraPort = Adapters.camera(this, settings)

    companion object {
        private const val TAG = "CardScanner"
        fun of(ctx: Context): App = ctx.applicationContext as App
    }
}
