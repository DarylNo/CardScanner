package io.github.darylno.cardscanner.ui

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import io.github.darylno.cardscanner.core.AutoScanner
import io.github.darylno.cardscanner.core.RoiFrac
import org.json.JSONObject

/*
 * The UI's view of the other agents' packages. MainActivity & co. talk ONLY to
 * these ports; Adapters.kt implements them over the real net/, gateway/,
 * camera/ and capture/ classes. That keeps every signature dependency on code
 * this agent doesn't own in one file.
 */

/** A capture ready to upload (CapturePipeline's result, flattened primary + raw fallbacks). */
class Captured(
    val primary: ByteArray,
    val fallbacks: List<ByteArray>,
    val flattened: Boolean,
    val timings: String,
    /** The detector's scene at capture time (Mount) — handed back on a no_card answer. */
    val scene: io.github.darylno.cardscanner.core.Gray?,
)

/** Camera + detection + capture, confined behind the analysis thread by the adapter. */
interface CameraPort {
    interface Listener {
        /** Upright analysis frame size (overlay letterbox). Any thread. */
        fun onFrameSize(uprightW: Int, uprightH: Int)
        /** A detection tick. [sampleW]×[sampleH] = the Gray sample the box lives in. Any thread. */
        fun onDetection(event: AutoScanner.Event, sampleW: Int, sampleH: Int, debug: String?)
        /** The burst exists (frames grabbed) — haptic "captured" now, before processing. */
        fun onCaptureStarted(manual: Boolean)
        /** Frames captured and processed (auto trigger or manual). Any thread. */
        fun onCaptured(capture: Captured, manual: Boolean)
        fun onCaptureFailed(message: String)
        /** Fatal AND non-fatal camera/analyzer errors (a non-fatal one can still end a capture). */
        fun onCameraError(message: String)
    }

    fun bind(owner: LifecycleOwner, preview: PreviewView, listener: Listener)
    /**
     * Re-bind the already-bound camera with its current settings (idempotent).
     * Belt-and-braces: anything that unbinds the process-wide CameraProvider
     * behind our back (another screen's QR camera) must not leave a dead preview.
     */
    fun rebind()
    fun unbind()
    fun setHandheld(handheld: Boolean)
    fun setAuto(on: Boolean)
    fun setRoi(roi: RoiFrac?)
    /** Double-tap / area change: forget the empty tray and learn it again. */
    fun relearn()
    fun pause(paused: Boolean)
    fun manualScan()
    /** Tap on the preview, in PreviewView pixels. */
    fun tapFocus(x: Float, y: Float)
    /** A no_card answer for the capture whose scene was [scene]: re-seed the empty reference (phone.html submitScan). */
    fun noCard(scene: io.github.darylno.cardscanner.core.Gray?)
    fun setTorch(on: Boolean)
    fun setAeLock(on: Boolean)
    fun setFocusLock(on: Boolean)
    fun setHighRes(high: Boolean)
    fun diagnostics(): String
    /** The current upright frame as JPEG, or null (see CameraController.snapshotJpeg). Off the UI thread. */
    fun snapshotJpeg(): ByteArray? = null
}

/** Final outcome of one upload job (mirrors net.ScanOutcome). */
sealed class Outcome {
    abstract val usedFallback: Boolean
    data class NoCard(val error: String?, override val usedFallback: Boolean) : Outcome()
    data class AutoFiled(val json: JSONObject, val scanId: Long, override val usedFallback: Boolean) : Outcome()
    data class NeedsPick(val json: JSONObject, val scanId: Long, override val usedFallback: Boolean) : Outcome()
    data class BestGuess(val json: JSONObject, val scanId: Long, override val usedFallback: Boolean) : Outcome()
    data class NoMatch(val json: JSONObject, val scanId: Long, override val usedFallback: Boolean) : Outcome()
    data class Rejected(val code: Int, val body: String, override val usedFallback: Boolean) : Outcome()
}

interface UploadPort {
    interface Listener {
        /**
         * Worker thread. [manual] = the job was a manual (tap) scan; [priceCheck] =
         * it was taken in Handheld mode (persisted with the job, so it survives a
         * process death while queued offline). [replaceScanId] = the row a Retry
         * job was replacing (null for a fresh scan) — a Retry that comes back
         * no_card leaves that failed row in place, so Retry is offered again.
         */
        fun onOutcome(jobId: String, manual: Boolean, priceCheck: Boolean, replaceScanId: Long?, outcome: Outcome)
        fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?)
    }

    /** Persist the capture (disk only, never network); returns the job id. */
    fun enqueue(capture: Captured, manual: Boolean, priceCheck: Boolean, replaceScanId: Long?): String
    fun retryNow()
    fun addListener(l: Listener)
    fun removeListener(l: Listener)
    val pending: Int
}

/** Paired server: config, failover client, pairing. */
interface ServerPort {
    val isPaired: Boolean
    /** Best base URL right now (lastGood, else the first configured). */
    fun bestBase(): String?
    fun pin(): String?
    fun urls(): List<String>
    fun setUrls(urls: List<String>)
    /** Blocking; network. */
    fun version(): String
    /** Blocking; refreshes the address list from /api/addresses and saves it. */
    fun refreshAddresses(): List<String>
    /** Blocking: probe [base] for its certificate fingerprint (hex), or throw. */
    fun probe(base: String): String
    /** Blocking: pair with [base] under [pin], save the config. Returns the learned URLs. */
    fun pair(base: String, pin: String): List<String>
    fun normalize(input: String): String?
    fun unpair()
}

/** Guest gateway (Share screen). */
interface GatewayPort {
    val running: Boolean
    val port: Int
    /** Why the last start failed, if it did. */
    val error: String?
    fun code(): String
    fun start()
    fun stop()
    fun localAddresses(): List<String>
    fun joinUrl(ip: String): String
    fun qr(text: String, sizePx: Int): android.graphics.Bitmap
}
