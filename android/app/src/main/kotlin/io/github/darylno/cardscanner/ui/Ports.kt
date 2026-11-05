package io.github.darylno.cardscanner.ui

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import io.github.darylno.cardscanner.core.AutoScanner
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.ZoomProbeRun
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
    /**
     * The card's corners the capture found (CaptureResult.quad: TL,TR,BR,BL in
     * UPRIGHT-FRAME pixels of a [frameW]×[frameH] frame), or null — the shape the
     * overlay's blue ✓ snaps to. Display + log only.
     */
    val quad: FloatArray? = null,
    val frameW: Int = 0,
    val frameH: Int = 0,
    /** The burst's id (the capture lines' "#n"), 0 when unknown. */
    val burstId: Long = 0L,
    /**
     * The camera's own report for the frame the pipeline chose — "AF … · lens …
     * dpt · exposure … · ISO …" (CameraController.lensAtCapture) — or null. Log only.
     */
    val lens: String? = null,
    /** The camera zoom the burst was taken at (1.0 = none) — the capture line's "@ z×". */
    val zoom: Double = 1.0,
    /**
     * The zoom A/B's figure: the collector strip's detail on the 630×880 identification
     * raster (CaptureResult.detail), or null — measured only during its arm steps.
     */
    val stripDetail: Double? = null,
    /** The chosen frame's ISO (the camera's own report), or null when none was kept. */
    val iso: Int? = null,
) {
    /** The quad as frame FRACTIONS (the overlay's space), or null without one. */
    fun quadFractions(): FloatArray? {
        val q = quad ?: return null
        if (q.size != 8 || frameW <= 0 || frameH <= 0) return null
        // Pixel-centre coordinates → pixel-edge fractions (the same +0.5 as CardOutline.toFrameFractions).
        return FloatArray(8) { if (it % 2 == 0) (q[it] + 0.5f) / frameW else (q[it] + 0.5f) / frameH }
    }

    /** The card's height in frame pixels (the mean of its two vertical sides), 0 without a quad — the zoom question's number. */
    fun cardHeightPx(): Int {
        val q = quad ?: return 0
        if (q.size != 8) return 0
        val left = Math.hypot((q[6] - q[0]).toDouble(), (q[7] - q[1]).toDouble())
        val right = Math.hypot((q[4] - q[2]).toDouble(), (q[5] - q[3]).toDouble())
        return Math.round((left + right) / 2).toInt()
    }

    /**
     * The card's LONG side in frame pixels — the larger of [cardHeightPx] and [cardWidthPx],
     * so a card lying sideways counts the same — 0 without a quad (the zoom A/B's card size).
     */
    fun cardLongPx(): Int = maxOf(cardHeightPx(), cardWidthPx())

    /** The card's width in frame pixels (the mean of its two horizontal sides), 0 without a quad. */
    fun cardWidthPx(): Int {
        val q = quad ?: return 0
        if (q.size != 8) return 0
        val top = Math.hypot((q[2] - q[0]).toDouble(), (q[3] - q[1]).toDouble())
        val bottom = Math.hypot((q[4] - q[6]).toDouble(), (q[5] - q[7]).toDouble())
        return Math.round((top + bottom) / 2).toInt()
    }
}

/** Camera + detection + capture, confined behind the analysis thread by the adapter. */
interface CameraPort {
    interface Listener {
        /** Upright analysis frame size (overlay letterbox). Any thread. */
        fun onFrameSize(uprightW: Int, uprightH: Int)
        /**
         * A detection tick. [sampleW]×[sampleH] = the Gray sample the box lives in;
         * [outline] = the live card outline and [watch] = the card-shaped watch
         * window, both 8 floats TL,TR,BR,BL in upright-frame FRACTIONS, or null
         * (display only — see ScanAnalyzer). [triggerRefused]: this Trigger was
         * refused by the card-shape gate (no card outline), nothing was shot and
         * the scanner keeps watching. Any thread.
         */
        fun onDetection(event: AutoScanner.Event, sampleW: Int, sampleH: Int, debug: String?,
                        outline: FloatArray? = null, watch: FloatArray? = null, triggerRefused: Boolean = false)
        /**
         * The burst [id] exists (frames grabbed) — haptic "captured" now, before
         * processing. Exactly one of [onCaptured] / [onCaptureFailed] follows it;
         * the pipeline is one queue, so a newer burst can start before an older
         * one answers — [id] tells them apart.
         */
        fun onCaptureStarted(id: Long, manual: Boolean)
        /** Frames captured and processed (auto trigger or manual). Any thread. */
        fun onCaptured(capture: Captured, manual: Boolean)
        /** Burst [id] produced nothing. */
        fun onCaptureFailed(id: Long, message: String)
        /** Fatal AND non-fatal camera/analyzer errors (a non-fatal one can still end a capture). */
        fun onCameraError(message: String)
        /**
         * "Zoom to fit the Area": frames are now mapped at [ratio] and the Area sits at
         * [viewRoi] of the zoomed frame (what the overlay draws); or, with [drawingReady],
         * the camera is back at 1× for drawing an Area. Any thread.
         */
        fun onZoom(ratio: Double, viewRoi: RoiFrac?, drawingReady: Boolean) {}
    }

    fun bind(owner: LifecycleOwner, preview: PreviewView, listener: Listener)
    /**
     * Re-bind the already-bound camera with its current settings (idempotent).
     * Belt-and-braces: anything that unbinds the process-wide CameraProvider
     * behind our back (another screen's QR camera) must not leave a dead preview.
     */
    fun rebind()
    fun unbind()
    fun setAuto(on: Boolean)
    /** The stored scan Area — BASE (1×) fractions; the camera maps it into its zoomed frames itself. */
    fun setRoi(roi: RoiFrac?)
    /** "Zoom to fit the Area" (Settings → Camera). */
    fun setZoomFit(on: Boolean) {}
    /**
     * Drawing an Area: zoom out to 1× ([Listener.onZoom] drawingReady arms the drag —
     * at once when the camera is at 1× already), then back to the Area's zoom. Answers
     * false when no camera will answer (arm the drag yourself).
     */
    fun setDrawing(on: Boolean): Boolean = false
    /** The zoom now, for the browser's `/api/device` (null = no camera). Any thread. */
    fun zoomState(): io.github.darylno.cardscanner.core.ZoomCoordinator.State? = null
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
    fun setHighRes(high: Boolean)
    /** The Camera setting (a Camera2 id, null = Automatic); rebinds — and re-learns the tray — when it changes. */
    fun setCamera(id: String?) {}
    /**
     * "Refocus on a card" in minutes (core RefocusPolicy: 0 = every card, −1 = never): in Tray, the
     * next card after that long since the last focus on a card waits for a focus pass on it. No rebind.
     */
    fun setRefocusMin(minutes: Int) {}
    /** The zoom A/B's arm steps: each capture also measures its identification raster's detail (CapturePipeline.measureDetail). */
    fun setMeasureDetail(on: Boolean) {}
    fun diagnostics(): String
    /** The current upright frame as JPEG, or null (see CameraController.snapshotJpeg). Off the UI thread. */
    fun snapshotJpeg(): ByteArray? = null
    /** The zoomed frame as the camera delivers it, or null (see CameraController.cameraSnapshot). Off the UI thread. */
    fun cameraSnapshot(): io.github.darylno.cardscanner.core.server.DeviceApi.Picture? = null
    /**
     * Run the zoom probe (1.1.17, CameraController.startZoomProbe): [run] = its number in the
     * session, [earlier] = the earlier VALID runs' 1× card thumbnails (ZoomProbeRun.Outcome.thumbnail),
     * so the same card again does not count as another. False = no run was made (no camera, or
     * one already runs) — nothing changed; true = under way, and every outcome — a refusal too —
     * comes back through [l]. It files nothing.
     */
    fun startZoomProbe(run: Int, earlier: List<ZoomProbeRun.Earlier>, l: ProbeListener): Boolean = false
    /** The owner cancelled the probe (or a control the touch policy turns into a cancel): it ends through its verified restore. */
    fun cancelZoomProbe(why: String) {}
    fun zoomProbeRunning(): Boolean = false
}

/** The zoom probe's words for the scan screen (1.1.17). Every call on the UI thread, in order. */
interface ProbeListener {
    /**
     * Where the run is: [status] for the status pill ("Zoom probe 7/18 · 1.60× — hands off the
     * tray"), [count] for the measurement card, [z] = the zoom the camera last ANSWERED — draw
     * the Area there (null before the first answer).
     */
    fun onProgress(status: String, count: String, z: Double?)
    /**
     * The run ended, exactly once: [outcome] carries how (refused / stopped / cancelled / done),
     * THIS run's words, its result (ZoomProbe.Result: toJson() to keep it, ZoomProbe.combine over a
     * session's runs for the words to show) and, when valid, its 1× card thumbnail.
     */
    fun onFinished(outcome: ZoomProbeRun.Outcome)
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
         * Worker thread. [manual] = the job was a manual (tap) scan; [openScan] =
         * it was tapped with Auto off, so its scan opens once identified (persisted
         * with the job, so it survives a process death while queued offline). [replaceScanId] = the row a Retry
         * job was replacing (null for a fresh scan) — a Retry that comes back
         * no_card leaves that failed row in place, so Retry is offered again.
         */
        fun onOutcome(jobId: String, manual: Boolean, openScan: Boolean, replaceScanId: Long?, outcome: Outcome)
        fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?)
    }

    /** Persist the capture (disk only, never network); returns the job id. */
    fun enqueue(capture: Captured, manual: Boolean, openScan: Boolean, replaceScanId: Long?): String
    fun retryNow()
    fun addListener(l: Listener)
    fun removeListener(l: Listener)
    val pending: Int
}

/** Guest gateway (Share screen). */
interface GatewayPort {
    val running: Boolean
    val port: Int
    /** Why the last start failed, if it did. */
    val error: String?
    fun code(): String
    fun start()
    /** A new guest code: every guest session ends; the server, this phone and the paired computer carry on. */
    fun newGuestCode()
    fun localAddresses(): List<String>
    fun joinUrl(ip: String): String
    fun qr(text: String, sizePx: Int): android.graphics.Bitmap
}
