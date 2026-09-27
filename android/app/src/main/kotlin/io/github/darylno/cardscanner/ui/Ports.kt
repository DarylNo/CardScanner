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
)

/** Camera + detection + capture, confined behind the analysis thread by the adapter. */
interface CameraPort {
    interface Listener {
        /** Upright analysis frame size (overlay letterbox). Any thread. */
        fun onFrameSize(uprightW: Int, uprightH: Int)
        /** A detection tick. [sampleW]×[sampleH] = the Gray sample the box lives in. Any thread. */
        fun onDetection(event: AutoScanner.Event, sampleW: Int, sampleH: Int, debug: String?)
        /** Frames captured and processed (auto trigger or manual). Any thread. */
        fun onCaptured(capture: Captured, manual: Boolean)
        fun onCaptureFailed(message: String)
        fun onCameraError(message: String)
    }

    fun bind(owner: LifecycleOwner, preview: PreviewView, listener: Listener)
    fun unbind()
    fun setHandheld(handheld: Boolean)
    fun setAuto(on: Boolean)
    fun setRoi(roi: RoiFrac?)
    /** Double-tap / area change: forget the empty tray and learn it again. */
    fun relearn()
    fun pause(paused: Boolean)
    fun manualScan()
    fun tapFocus(fx: Float, fy: Float)
    /** A no_card answer: re-seed the empty reference (phone.html submitScan). */
    fun noCard()
    fun setTorch(on: Boolean)
    fun setAeLock(on: Boolean)
    fun setFocusLock(on: Boolean)
    fun diagnostics(): String
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
        /** Worker thread. [manual] = the job was a manual (tap) scan. */
        fun onOutcome(manual: Boolean, outcome: Outcome)
        fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?)
    }

    fun enqueue(capture: Captured, manual: Boolean, replaceScanId: Long?)
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
    fun code(): String
    fun start()
    fun stop()
    fun localAddresses(): List<String>
    fun joinUrl(ip: String): String
    fun qr(text: String, sizePx: Int): android.graphics.Bitmap
}
