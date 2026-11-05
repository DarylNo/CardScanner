package io.github.darylno.cardscanner.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.darylno.cardscanner.capture.CapturePipeline
import io.github.darylno.cardscanner.capture.CaptureResult
import io.github.darylno.cardscanner.core.CameraChoice
import io.github.darylno.cardscanner.core.CardFocusState
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.FocusPoint
import io.github.darylno.cardscanner.core.Gray
import io.github.darylno.cardscanner.core.Nv21Bgr
import io.github.darylno.cardscanner.core.Nv21Frame
import io.github.darylno.cardscanner.core.RefocusPolicy
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.Rotation
import io.github.darylno.cardscanner.core.ZoomCoordinator
import io.github.darylno.cardscanner.core.ZoomFit
import io.github.darylno.cardscanner.core.ZoomProbe
import io.github.darylno.cardscanner.core.ZoomProbeRun
import io.github.darylno.cardscanner.core.ZoomSnapshot
import io.github.darylno.cardscanner.core.server.DeviceApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/**
 * `/api/device/snapshot.jpg`'s picture of one analysis frame taken at zoom `z`
 * ([CameraController.snapshotJpeg] / [CameraController.cameraSnapshot]; pure OpenCV,
 * so the JVM tests run it, SnapshotPictureTest).
 */
internal object SnapshotPicture {
    /**
     * [base]: the BASE-space picture — zoomed, the view scaled by 1/z on grey
     * ([ZoomSnapshot]), so the browser draws the stored base Area on it 1:1 (what
     * `snapshot.jpg` always served). Else the frame as it is (`?view=camera`). Long side
     * ≤ [maxSide], never scaled up; the Picture's w×h is the encoded size.
     */
    fun encode(frame: Nv21Frame, z: Double, maxSide: Int, base: Boolean): DeviceApi.Picture {
        val upright = Nv21Bgr.toUprightBgr(frame)
        val bgr = if (base && z > 1.0) ZoomSnapshot.composeBase(upright, z).also { upright.release() } else upright
        try {
            val scale = maxSide.toDouble() / maxOf(bgr.cols(), bgr.rows())
            if (scale < 1.0) {
                val small = org.opencv.core.Mat()
                org.opencv.imgproc.Imgproc.resize(bgr, small, org.opencv.core.Size(), scale, scale,
                    org.opencv.imgproc.Imgproc.INTER_AREA)
                try { return DeviceApi.Picture(CapturePipeline.encodeJpeg(small), z, small.cols(), small.rows()) }
                finally { small.release() }
            }
            return DeviceApi.Picture(CapturePipeline.encodeJpeg(bgr), z, bgr.cols(), bgr.rows())
        } finally {
            bgr.release()
        }
    }
}

/** A Mount capture this much softer than the session's median re-arms the card-focus pass. */
private const val SOFT_CAPTURE_RATIO = 0.5
/**
 * A finished capture: [result] on success, else [error]. [scene] is for [CameraController.onNoCard].
 * [lens] = the camera's own report for the frame the pipeline chose (AF state, lens
 * position, exposure, ISO — "is it focusing?"), or null when none was kept; [iso] =
 * that frame's ISO alone (the zoom A/B compares it), or null.
 */
class CaptureOutcome(
    val id: Long,
    val trigger: CaptureTrigger,
    val mode: ScanMode,
    val scene: Gray?,
    val result: CaptureResult?,
    val error: Throwable?,
    val lens: String? = null,
    /** The camera zoom the burst was taken at (the capture line's "@ z×"). */
    val zoom: Double = 1.0,
    val iso: Int? = null,
)

/**
 * CameraX 1.5.3 front end for the scan screen: ONE bindToLifecycle of Preview
 * + ImageAnalysis (no ImageCapture — the burst comes from the analysis ring),
 * on camera "0" (the 13 MP main camera; the Nord N200's id 3 is a 2 MP
 * fixed-focus module that must never be picked), falling back to
 * DEFAULT_BACK_CAMERA.
 *
 * Both use cases share one 4:3 ResolutionSelector so the preview (FIT_CENTER)
 * shows exactly the analysis field of view — the scan area maps linearly.
 * Video stabilization is forced OFF on both (EIS crops/warps and hurt the
 * fixed mount in Chrome).
 *
 * Focus: Mount locks AF (FLAG_AF + disableAutoCancel — CameraX then holds
 * CONTROL_AF_MODE_AUTO, the lens stays put) at the CENTRE OF THE SCAN AREA, or
 * the frame centre when no area is set (owner's call). As soon as a frame
 * arrives after a bind / mode switch / area change it locks there so the lens
 * stops hunting (a hunting lens softened a still card enough to read as a
 * "new card" and re-scan it every ~20 s). An empty tray is a poor AF target,
 * so the FIRST card is focused on properly: [ScanAnalyzer] holds that card's
 * capture (≤ FOCUS_WAIT_NS) while AF runs ON THE CARD — the Trigger box's
 * centre ([FocusPoint]; the Area centre before 1.1.14) — then shoots from
 * post-focus frames. Later cards shoot instantly — until "Refocus on a card"
 * (1.1.18, Settings → Camera, core [RefocusPolicy], default every 5 min,
 * provisional): once that long has passed since the last focus on a card (or
 * the end of the last card-focus chain), the next Tray card is held for a pass
 * — until the camera answers, ≤ 5.5 s ([ScanAnalyzer.PERIODIC_FOCUS_WAIT_NS]),
 * never shot mid-sweep (a lens that drifts — heat, a nudged mount — stayed soft
 * for the whole session before). A sharpness watchdog (a capture far softer
 * than the session median) re-arms that one-card focus pass too. A tap still
 * overrides: it locks where tapped and PAUSES the periodic pass until a new
 * Area, a camera re-open or a mode switch. The arming state is core
 * [CardFocusState]. The shutter (Tap to scan) never holds for a pass.
 * Handheld = continuous AF with tap-to-focus.
 * Torch / AE+AWB lock / focus are re-applied after every bind AND every
 * camera re-open (CameraControl state dies with the session).
 *
 * "Is it focusing?" (owner on 1.1.10): every pass is logged under `focus`
 * — why, where (Area / card centre → sensor point), the result (focused / NOT
 * focused / timed out / cancelled) with its ms, and the camera's own AF state
 * and lens position ([FocusLog]); so are the watchdog's re-arms, each
 * periodic pass armed, and the card pass giving up. Each capture carries its
 * chosen frame's AF state and lens position ([FrameMetaRing], by sensor
 * timestamp).
 *
 * "Zoom to fit the Area" (1.1.14, Settings → Camera, OFF by default in this
 * build): [ZoomCoordinator] decides, this class is its CameraX glue. The Area it
 * is given ([setRoi]) is BASE (1×) fractions; the analyzer, the focus points and
 * the overlay get the VIEW Area at the ratio READ BACK from the capture results
 * (CONTROL_ZOOM_RATIO on API 30+, else the crop region). CameraX resets zoom on
 * every detach, so the zoom is re-applied after every bind, camera re-open and
 * screen start; the analyzer's settle gate is closed meanwhile. Logged under
 * `zoom`: what was asked, what the camera reports, what bound the ratio.
 *
 * THE ZOOM PROBE (1.1.17, [startZoomProbe]; core ZoomProbeRun sequences it, the
 * coordinator's probe mode holds the analyzer's gate): its camera side is
 * [ZoomProbeGlue] over this class — the coordinator's rung answers and aborts posted to
 * the runner, the analyzer's probe frames, one AF pass on the card, the probe's AE/AWB
 * lock ORed into the owner's, and a `zoom-probe` worker that measures each rung
 * ([ProbeMeasure]) off the capture pipeline. It files nothing. Logged under `probe`
 * (the runner's lines), `focus` (its passes) and `zoom` (the coordinator's).
 *
 * Threading: construct, command and [start] on the main thread. [Listener]
 * callbacks arrive on the main thread. Analyzer commands are forwarded to the
 * analysis thread by [ScanAnalyzer].
 */
@OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraController(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val listener: Listener,
) {
    enum class Resolution(val size: Size) { STANDARD(Size(1600, 1200)), HIGH(Size(2048, 1536)) }

    /** All callbacks on the main thread. */
    interface Listener {
        /** Every detection tick (~5 Hz, Mount only). */
        fun onDetection(update: DetectionUpdate) {}
        /** The burst exists — "captured, swap the card" can be shown / haptic fired now. */
        fun onCaptureStarted(request: CaptureBurst) {}
        /** The pipeline finished (JPEGs ready) or failed. */
        fun onCapture(outcome: CaptureOutcome) {}
        /** Camera in use / disabled / fatal errors (and analyzer exceptions, fatal=false). */
        fun onCameraError(message: String, fatal: Boolean) {}
        /** Bound (after every bind): a one-line description of what was bound. */
        fun onCameraReady(summary: String) {}
        /**
         * The analyzer now maps frames at zoom [ratio] with the Area at [view] (its
         * fractions of the zoomed frame — what the overlay draws), or, with
         * [drawingReady], the camera is at 1× for drawing an Area ([view] = the base Area).
         */
        fun onZoom(ratio: Double, view: RoiFrac?, drawingReady: Boolean) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "scan-analysis") }
    val pipeline = CapturePipeline()

    val analyzer: ScanAnalyzer = ScanAnalyzer(analysisExecutor, object : ScanAnalyzer.Sink {
        override fun onDetection(update: DetectionUpdate) {
            main.post {
                listener.onDetection(update)
                if (pendingAreaLock) lockAtAreaCentre()   // first frame geometry is known now
            }
        }

        override fun onFocusRequest(request: FocusRequest) {
            main.post { focusOnCard(request) }
        }

        override fun onCaptureRequest(request: CaptureBurst) {
            main.post { listener.onCaptureStarted(request) }
            // The camera's report for each burst frame, taken NOW (the ring keeps ~2 s of frames;
            // the pipeline can take longer than that on a slow phone).
            val metas = request.frames.map { frameMeta.at(it.timestampNs) }
            val ok = pipeline.submit(request.frames, request.cropRoi) { r ->
                val lens = r.getOrNull()?.let { res -> runCatching { lensAtCapture(request, metas, res.sharpestIndex) }.getOrNull() }
                val iso = r.getOrNull()?.let { res -> runCatching { isoAtCapture(request, metas, res.sharpestIndex) }.getOrNull() }
                r.getOrNull()?.let { res ->
                    // Only captures where a card was found count: an empty-tray
                    // (phantom) capture is always "soft", and letting it re-arm the
                    // refocus fed a loop — refocus → stale scene → phantom → refocus.
                    if (request.trigger == CaptureTrigger.AUTO && request.mode == ScanMode.MOUNT && res.flattened) {
                        res.sharpness.getOrNull(res.sharpestIndex)?.let(::noteSharpness)
                    }
                    // The capture's exact corners → the analyzer's card-shaped watch window
                    // (shadow-mode logging + the overlay; tagged with the burst, never the trigger).
                    analyzer.onCaptureResult(request.id, res.quad, res.frameWidth, res.frameHeight)
                }
                val outcome = CaptureOutcome(
                    request.id, request.trigger, request.mode, request.scene, r.getOrNull(), r.exceptionOrNull(), lens,
                    request.zoom, iso,
                )
                main.post { listener.onCapture(outcome) }
            }
            if (!ok) main.post {
                listener.onCapture(CaptureOutcome(
                    request.id, request.trigger, request.mode, request.scene, null,
                    IllegalStateException("capture pipeline is shut down"),
                ))
            }
        }

        override fun onAnalyzerError(error: Throwable) {
            main.post { listener.onCameraError("analyzer: $error", false) }
        }

        override fun onZoomResync() {
            main.post { if (camera != null && !released) zoom.cameraStarted("frames arriving at the closed zoom gate") }
        }
    })

    private var provider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var boundCameraId: String? = null
    private var lastStateType: CameraState.Type? = null
    private var released = false

    var resolution = Resolution.STANDARD
        private set
    /** Read by the analysis thread too (the Tray trigger's [ScanAnalyzer.focusHold]). */
    @Volatile var scanMode = ScanMode.MOUNT
        private set
    var torchOn = false
        private set
    var aeAwbLock = false
        private set
    /** The stored scan Area: BASE (1×) fractions of the upright frame. */
    private var roi: RoiFrac? = null
    /** The Area as fractions of the frames as they arrive now (the zoom coordinator's mapping). */
    @Volatile private var viewRoi: RoiFrac? = null
    @Volatile private var viewRatio = 1.0
    /** The sensor's active array (the read-back's crop region is relative to it). */
    @Volatile private var activeArray: android.graphics.Rect? = null
    /**
     * Mount: focus on the NEXT card before shooting it (core [CardFocusState]). Armed after a
     * bind, a switch to Mount, an area change, a zoom change or a sharpness drop — and, at a
     * Tray trigger, by "Refocus on a card" once its interval has passed since the last focus
     * on a card (or the last chain's end); disarmed once that card has been focused on, when
     * its chain gives up, or by a tap (which also pauses the periodic pass). The analysis
     * thread asks it at every Tray trigger ([ScanAnalyzer.focusHold]), the main thread arms,
     * starts and ends passes, the capture thread's watchdog arms it: every call takes its
     * monitor, so the analysis thread's check-then-arm is one step (see [CardFocusState]).
     */
    private val cardFocus = CardFocusState(log = { DebugLog.global.i("focus", it) })
    /** The card-focus clock: monotonic, sleep included (what "every N min" counts on). Any thread. */
    private val clockNs: () -> Long = SystemClock::elapsedRealtimeNanos
    /** "Refocus on a card" as last handed over ([setRefocusMin]); null = not yet — the first value is not logged as a change. */
    private var refocusApplied: Int? = null
    private val recentSharpness = ArrayDeque<Double>()
    /** Why the pending Area-centre lock runs (camera bind / camera re-open / new Area / mode switch). */
    private var pendingLockWhy = "camera bind"
    /** Every focus pass's result this session (the Diagnostics focus line). */
    private val focusStats = FocusStats()
    /** Numbers the focus log's passes (#n start … #n result). */
    private var focusSeq = 0
    /** LENS_INFO_FOCUS_DISTANCE_CALIBRATION is APPROXIMATE / CALIBRATED: dioptres are physical (cm can be shown). */
    @Volatile private var focusCalibrated = false
    /** Device + characteristics lines of [diagnostics]: they only change with a bind, so they are read once per bind. */
    private var staticDiagnostics: String? = null

    init {
        // The analysis thread, at every Tray trigger: an armed pass holds the card (1.5 s for the
        // first card / watchdog, until the answer for a periodic chain); else, when "Refocus on a
        // card" says it is time (and no tap paused it), a periodic pass is armed and holds it.
        analyzer.focusHold = { if (scanMode == ScanMode.MOUNT) cardFocus.holdFor(clockNs()) else null }
    }
    /** Mount: lock at the area centre as soon as a frame gives us the geometry. */
    private var pendingAreaLock = false
        private set
    private var focusNote = "continuous (default)"

    // Latest per-frame metadata (camera thread): diagnostics, the focus log, and per frame in [frameMeta].
    @Volatile private var metaAfState: Int? = null
    @Volatile private var metaFocusDist: Float? = null
    @Volatile private var metaExposureNs: Long? = null
    @Volatile private var metaIso: Int? = null
    @Volatile private var metaStab: Int? = null
    /** The camera's own zoom report for the newest frame (API 30+), and its crop region. */
    @Volatile private var metaZoomRatio: Float? = null
    @Volatile private var metaCrop: android.graphics.Rect? = null
    /**
     * Capture results seen (camera thread). The zoom's read-back only counts a result
     * that arrived after the request ([zoomAskedAt]): one left over from before a stop
     * says what the camera WAS (review of 1.1.14: a slow reopen timed out onto it).
     */
    private val metaSeq = java.util.concurrent.atomic.AtomicLong()
    /** [metaSeq] when the latest zoom request was made (main thread). */
    @Volatile private var zoomAskedAt = Long.MAX_VALUE
    /** The last ~2 s of frames' reports by SENSOR_TIMESTAMP (= the analysis frame's timestamp): what a capture's frames said. */
    private val frameMeta = FrameMetaRing()
    private val metaCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val af = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)
            val dist = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)
            val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
            val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
            val zr = if (Build.VERSION.SDK_INT >= 30) result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO) else null
            val crop = result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)
            metaAfState = af; metaFocusDist = dist; metaExposureNs = exp; metaIso = iso
            metaStab = result.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
            if (Build.VERSION.SDK_INT >= 30) metaZoomRatio = zr
            metaCrop = crop
            metaSeq.incrementAndGet()
            result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)?.let { ts ->
                // This frame's OWN zoom and crop ride its meta: the zoom probe asks each frame what zoom it was at.
                frameMeta.put(FrameMetaRing.Meta(ts, af, dist, exp, iso, zr,
                    crop?.let { intArrayOf(it.left, it.top, it.width(), it.height()) }))
            }
        }
    }

    /**
     * The capture line's "AF … · lens … · exposure … · ISO …" for the frame the
     * pipeline chose ([chosen] indexes [request]'s frames; [metas] = their reports
     * looked up when the burst was handed over). Capture thread.
     */
    private fun lensAtCapture(request: CaptureBurst, metas: List<FrameMetaRing.Meta?>, chosen: Int): String? {
        val frame = request.frames.getOrNull(chosen) ?: return null
        val own = metas.getOrNull(chosen) ?: frameMeta.at(frame.timestampNs)
        if (own != null) return own.describe(focusCalibrated)
        val newest = frameMeta.latest() ?: return null
        return newest.describe(focusCalibrated) + " (the newest frame's report — the chosen frame's was not kept)"
    }

    /** The chosen frame's own ISO (as [lensAtCapture] finds its report; never another frame's), or null. Capture thread. */
    private fun isoAtCapture(request: CaptureBurst, metas: List<FrameMetaRing.Meta?>, chosen: Int): Int? {
        val frame = request.frames.getOrNull(chosen) ?: return null
        return (metas.getOrNull(chosen) ?: frameMeta.at(frame.timestampNs))?.iso
    }

    // ---------------- zoom to fit the Area (core ZoomCoordinator; this is its CameraX glue) ----------------

    private val zoom: ZoomCoordinator = ZoomCoordinator(object : ZoomCoordinator.Port {
        override fun request(gen: Int, ratio: Double) = applyZoomRatio(gen, ratio)
        override fun closeGate(why: String) = analyzer.closeZoomGate(why)
        override fun map(ratio: Double, view: RoiFrac?, settle: Boolean, why: String) {
            val changed = ratio != viewRatio
            viewRoi = view
            viewRatio = ratio
            analyzer.zoomMapped(ratio, view, settle, why)
            if (changed) {
                // The metering regions were set in the old zoom's sensor crop: lock again at the
                // new view's Area centre, and focus on the next card (like a new Area).
                if (scanMode == ScanMode.MOUNT) {
                    pendingAreaLock = true
                    pendingLockWhy = "zoom change"
                    armCardFocus("first card after the zoom change", resumesRefocus = true)
                }
                synchronized(recentSharpness) { recentSharpness.clear() }
            }
            listener.onZoom(ratio, view, false)
        }
        override fun drawingReady(ratio: Double) {
            analyzer.snapshotsAt(ratio)          // the browser's picture while the phone draws an Area
            listener.onZoom(1.0, roi, true)
        }
        override fun later(ms: Long, block: () -> Unit) { main.postDelayed({ if (!released) block() }, ms) }
        override fun readBack(): Double? = freshReadBack()
        // The probe's half (posted to the runner, the tap and the slot stopped on an abort): ZoomProbeGlue.
        override fun probeAt(gen: Int, asked: Double, camera: Double, readBack: Double?, error: String?) =
            probe.probeAt(gen, asked, camera, readBack, error)
        override fun probeEnded(why: String) = probe.probeEnded(why)
        override fun probeRebase(why: String) = probe.probeRebase(why)
        override fun cameraAway(why: String) = analyzer.cameraAway(why)
    }, log = { DebugLog.global.i("zoom", it) })

    /** The screen's lifecycle: CameraX drops the zoom when the camera detaches (ON_STOP) and the zoom is re-applied at ON_START. */
    private val lifecycleWatch = LifecycleEventObserver { _, e ->
        if (camera == null || released) return@LifecycleEventObserver
        when (e) {
            Lifecycle.Event.ON_STOP -> { clearZoomMeta(); zoom.cameraStopped("the scan screen stopped") }
            Lifecycle.Event.ON_START -> zoom.cameraStarted("the scan screen started")
            else -> Unit
        }
    }
    private var lifecycleWatched = false

    /** CameraX setZoomRatio for request [gen]; the answer goes back to the coordinator with the camera's own read-back. */
    private fun applyZoomRatio(gen: Int, ratio: Double) {
        val cam = camera ?: run { zoom.onFailed(gen, notActive = true, message = "no camera bound"); return }
        zoomAskedAt = metaSeq.get()
        val f = try {
            cam.cameraControl.setZoomRatio(ratio.toFloat())
        } catch (e: Exception) {
            zoom.onFailed(gen, notActive = false, message = e.message ?: e.javaClass.simpleName); return
        }
        f.addListener({
            if (released) return@addListener
            try {
                f.get()
                // The probe's rung line carries this evidence per frame (and once per rung, not per ask).
                if (!zoom.probing) runCatching { DebugLog.global.i("zoom", "camera reports " + describeReadBack()) }
                zoom.onApplied(gen, freshReadBack())
            } catch (e: Exception) {
                val c = e.cause ?: e
                val msg = c.message ?: c.javaClass.simpleName
                zoom.onFailed(gen, notActive = msg.contains("not active", ignoreCase = true), message = msg)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * The zoom the newest capture result reports: CONTROL_ZOOM_RATIO (API 30+, what
     * CameraX sets there) when it is not 1, else the active array over the crop
     * region's width (the pre-30 way, also how a HAL may report it), else null.
     */
    private fun readBackZoom(): Double? {
        val r = metaZoomRatio?.toDouble()
        val crop = metaCrop
        val act = activeArray
        val fromCrop = if (crop != null && act != null && crop.width() > 0) act.width().toDouble() / crop.width() else null
        return when {
            r != null && Math.abs(r - 1.0) > 0.005 -> r
            fromCrop != null -> fromCrop
            else -> r
        }
    }

    /** [readBackZoom], but only from a capture result that arrived after the latest zoom request; else null. */
    private fun freshReadBack(): Double? = if (metaSeq.get() > zoomAskedAt) readBackZoom() else null

    /** The camera detached / closed: its last report is not what the reopened camera does. */
    private fun clearZoomMeta() { metaZoomRatio = null; metaCrop = null }

    /** "CONTROL_ZOOM_RATIO 1.90 · crop 0,0 4160×3120 of the 4160×3120 active array (centred)" — the zoom log's evidence. */
    private fun describeReadBack(): String {
        val crop = metaCrop; val act = activeArray
        val cropText = if (crop == null) "no crop region" else {
            val centred = act?.let {
                val dx = (crop.centerX() - it.centerX()).toDouble() / it.width()
                val dy = (crop.centerY() - it.centerY()).toDouble() / it.height()
                if (Math.abs(dx) <= 0.01 && Math.abs(dy) <= 0.01) "centred" else "NOT centred: off by %+.1f%%, %+.1f%%".format(dx * 100, dy * 100)
            } ?: "active array unknown"
            "crop ${crop.left},${crop.top} ${crop.width()}×${crop.height()} of the ${act?.let { "${it.width()}×${it.height()}" } ?: "?"} active array ($centred)"
        }
        return "CONTROL_ZOOM_RATIO ${metaZoomRatio?.let { "%.3f".format(it) } ?: "—"} · $cropText"
    }

    /** "Zoom to fit the Area" (Settings → Camera). */
    fun setZoomFit(on: Boolean) = zoom.setEnabled(on)

    /** Drawing an Area on the phone: zoom out to 1× first ([Listener.onZoom] drawingReady), back to the Area's zoom after. */
    fun setDrawing(on: Boolean) = zoom.setDrawing(on)

    /** The zoom now (Diagnostics, `/api/device`). Any thread (racy reads of a few fields). */
    fun zoomState(): ZoomCoordinator.State = zoom.state()

    /** Bind the camera into [view] (idempotent; rebinds with the current settings). */
    fun start(view: PreviewView, res: Resolution = resolution) {
        released = false
        previewView = view
        resolution = res
        val p = provider
        if (p != null) { bind(); return }
        val f = ProcessCameraProvider.getInstance(context)
        f.addListener({
            try {
                provider = f.get()
                bind()
            } catch (e: Exception) {
                listener.onCameraError("camera provider unavailable: $e", true)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Our preview + analysis are still bound to the (process-wide) provider. */
    fun isBound(): Boolean {
        val p = provider ?: return false
        val a = analysis ?: return false
        return runCatching { p.isBound(a) }.getOrDefault(false)
    }

    /** The saved Camera setting (a Camera2 id, null = Automatic); see [selectCamera]. */
    var cameraId: String? = null
        private set

    /** Pick the camera that scans (null = Automatic) — rebinds when it changes (and stops a zoom probe: the rebind re-learns). */
    fun setCamera(id: String?) {
        if (id == cameraId) return
        cameraId = id
        probe.stopNow("the camera setting changed — the camera is bound again")
        if (camera != null) bind()
    }

    /** Standard 1600×1200 / High 2048×1536 — rebinds (and stops a zoom probe: the rebind re-learns). */
    fun setResolution(res: Resolution) {
        if (res == resolution) return
        resolution = res
        probe.stopNow("the resolution changed — the camera is bound again")
        if (camera != null) bind()
    }

    /** Unbind and stop the worker threads (Activity onDestroy). */
    fun release() {
        probe.stopNow("the scan screen closed")                // while the camera is still there (the AE lock goes back)
        released = true
        if (lifecycleWatched) runCatching { owner.lifecycle.removeObserver(lifecycleWatch) }
        analysis?.clearAnalyzer()
        camera?.cameraInfo?.cameraState?.removeObservers(owner)
        // Only OUR use cases: the provider is a process singleton, and a
        // Setup screen scanning a QR above us owns its own.
        runCatching { provider?.unbind(*listOfNotNull(preview, analysis).toTypedArray()) }
        camera = null; analysis = null; preview = null
        analysisExecutor.shutdown()
        pipeline.shutdown()
        if (probeWorker.isInitialized()) probeWorker.value.shutdownNow()
    }

    private fun resolutionSelector(): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(resolution.size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
        )
        .build()

    private fun bind() {
        if (released) return
        val p = provider ?: return
        val view = previewView ?: return
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return

        view.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        view.scaleType = PreviewView.ScaleType.FIT_CENTER
        val rs = resolutionSelector()

        val pb = Preview.Builder().setResolutionSelector(rs)
        Camera2Interop.Extender(pb).setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        val pv = pb.build()
        pv.setSurfaceProvider(view.surfaceProvider)

        val ab = ImageAnalysis.Builder()
            .setResolutionSelector(rs)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        Camera2Interop.Extender(ab)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
            .setSessionCaptureCallback(metaCallback)
        val an = ab.build()
        an.setAnalyzer(analysisExecutor, analyzer)

        camera?.cameraInfo?.cameraState?.removeObservers(owner)
        p.unbindAll()
        camera = null; analysis = null; preview = null

        val cam = try {
            p.bindToLifecycle(owner, selectCamera(p), pv, an)
        } catch (e: Exception) {
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pv, an)
            } catch (e2: Exception) {
                listener.onCameraError("could not open the camera: ${e2.message ?: e2}", true)
                return
            }
        }
        camera = cam; analysis = an; preview = pv
        boundCameraId = runCatching { Camera2CameraInfo.from(cam.cameraInfo).cameraId }.getOrNull()
        val calibration = runCatching {
            Camera2CameraInfo.from(cam.cameraInfo).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
        }.getOrNull()
        focusCalibrated = calibration != null && calibration != CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
        frameMeta.clear()
        clearZoomMeta()
        DebugLog.global.i("camera", readZoomFacts(cam).summary())
        staticDiagnostics = runCatching { readStaticDiagnostics() }.getOrElse { "camera details: $it\n" }
        lastStateType = null
        cam.cameraInfo.cameraState.observe(owner) { st -> onCameraState(st) }
        analyzer.cameraRestarted()
        // The zoom: CameraX starts a bound camera at 1×; the coordinator maps the Area (zoom off)
        // or asks for this lens's ratio (lossless = active array ÷ analysis stream).
        val active = runCatching {
            Camera2CameraInfo.from(cam.cameraInfo).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        }.getOrNull()
        activeArray = active
        val stream = an.resolutionInfo?.resolution ?: resolution.size
        val lossless = active?.let { ZoomFit.lossless(it.width(), it.height(), stream.width, stream.height) }
        val lensMax = runCatching { cam.cameraInfo.zoomState.value?.maxZoomRatio?.toDouble() }.getOrNull()
        boundLossless = lossless; boundLensMax = lensMax
        zoom.cameraBound(lossless, lensMax)
        if (!lifecycleWatched) { owner.lifecycle.addObserver(lifecycleWatch); lifecycleWatched = true }
        pendingLockWhy = "camera bind"
        cardFocus.bound()                 // a fresh bind: "Refocus on a card" counts from this bind's first card
        applySettings()
        listener.onCameraReady(
            "camera ${boundCameraId ?: "?"} · analysis ${an.resolutionInfo?.resolution ?: "?"} · " +
                "preview ${pv.resolutionInfo?.resolution ?: "?"}",
        )
    }

    /**
     * The Camera setting resolved on this phone (core CameraChoice): the saved id when
     * the phone offers it, else Automatic — camera "0" facing back, else the first
     * back camera. Falls back to the default back camera if CameraX can't see it.
     */
    private fun selectCamera(p: ProcessCameraProvider): CameraSelector {
        val lenses = CameraCatalog.lenses(context)
        val want = CameraChoice.resolve(lenses, cameraId) ?: return CameraSelector.DEFAULT_BACK_CAMERA
        val chosen = cameraId
        DebugLog.global.i("camera", when {
            chosen == null -> "camera setting: Automatic → camera $want"
            chosen == want -> "camera setting: camera $want"
            else -> "camera setting: camera $chosen is not on this phone → Automatic, camera $want"
        })
        val sel = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() == want } }
            .build()
        val ok = try { p.hasCamera(sel) } catch (e: Exception) { false }
        return if (ok) sel else CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun onCameraState(st: CameraState) {
        st.error?.let { err ->
            val fatal = err.type == CameraState.ErrorType.CRITICAL
            listener.onCameraError(describeError(err.code), fatal)
        }
        // A re-open after onStop/onStart (same binding): session-scoped controls were lost.
        if (st.type == CameraState.Type.OPEN && lastStateType != null && lastStateType != CameraState.Type.OPEN) {
            pendingLockWhy = "camera re-open"
            applySettings()
        }
        // The zoom: a closed camera has dropped it (the analyzer's gate closes while one is in
        // use); an opening one may stream at an unknown moment; an open one gets it re-applied.
        when (st.type) {
            CameraState.Type.CLOSING, CameraState.Type.CLOSED -> { clearZoomMeta(); zoom.cameraStopped("camera ${st.type}") }
            CameraState.Type.PENDING_OPEN, CameraState.Type.OPENING -> zoom.cameraOpening("camera ${st.type}")
            CameraState.Type.OPEN -> if (lastStateType != CameraState.Type.OPEN) zoom.cameraStarted("camera open")
        }
        lastStateType = st.type
    }

    private fun describeError(code: Int): String = when (code) {
        CameraState.ERROR_CAMERA_IN_USE -> "Camera in use by another app — close it and come back."
        CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras open — close other camera apps."
        CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "Camera error (recovering)…"
        CameraState.ERROR_STREAM_CONFIG -> "Camera stream configuration failed."
        CameraState.ERROR_CAMERA_DISABLED -> "Camera disabled by device policy."
        CameraState.ERROR_CAMERA_FATAL_ERROR -> "Camera fatal error — restart the app / phone."
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Camera blocked by Do Not Disturb mode."
        else -> "Camera error $code"
    }

    /** Torch, AE/AWB lock and focus mode — after every bind and re-open. */
    private fun applySettings() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(torchOn)
        applyAeAwbLock()
        if (scanMode == ScanMode.HANDHELD) {
            cam.cameraControl.cancelFocusAndMetering()
            focusNote = "continuous (handheld)"
        } else {
            focusNote = "locking on the scan-area centre…"
            pendingAreaLock = true
            armCardFocus("first card after the $pendingLockWhy", resumesRefocus = true)
        }
    }

    /**
     * The owner's AE/AWB lock — ORed with the zoom probe's ([ZoomProbeGlue.probeLocked]: ISO and exposure stay put
     * across its rungs). applyRemote pushes the owner's setting on every PATCH, so the owner's
     * value cannot be the probe's lever.
     */
    private fun applyAeAwbLock() {
        val cam = camera ?: return
        val lock = aeAwbLock || probe.probeLocked
        runCatching {
            Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
                    .build(),
            )
        }
    }

    // ---------------- commands (main thread) ----------------

    fun setScanMode(m: ScanMode) {
        if (m == scanMode) return
        scanMode = m
        analyzer.setMode(m)
        val cam = camera ?: return
        cam.cameraControl.cancelFocusAndMetering()   // both: back to continuous AF
        focusNote = if (m == ScanMode.HANDHELD) "continuous (handheld)" else "locking on the scan-area centre…"
        pendingAreaLock = m == ScanMode.MOUNT
        pendingLockWhy = "mode switch"
        if (m == ScanMode.MOUNT) armCardFocus("first card after the mode switch", resumesRefocus = true) else cardFocus.disarm()
    }

    /**
     * The next card gets a focus pass ([why] goes in the focus log); the failure count starts again.
     * [resumesRefocus]: an arm that re-locks elsewhere (bind / re-open / new Area / mode switch / zoom
     * change / the probe) lifts a tap's pause on "Refocus on a card"; the watchdog's does not. Any thread.
     */
    private fun armCardFocus(why: String, resumesRefocus: Boolean = false) = cardFocus.arm(why, resumesRefocus)

    /**
     * "Refocus on a card" (Settings → Camera, `/api/device` `refocus_min`): minutes since the last
     * focus on a card after which the next Tray card waits for a pass on it ([RefocusPolicy]:
     * 0 = every card, −1 = never). Live — no rebind, nothing re-armed; a change is logged.
     */
    fun setRefocusMin(minutes: Int) {
        val m = RefocusPolicy.normalize(minutes)
        val before = refocusApplied
        refocusApplied = m
        cardFocus.minutes = m
        if (before != null && before != m) {
            DebugLog.global.i("focus", "refocus on a card: ${RefocusPolicy.label(before)} → ${RefocusPolicy.label(m)}")
        }
    }

    fun setAuto(enabled: Boolean) = analyzer.setAuto(enabled)
    fun reset() = analyzer.reset()
    fun manualScan() = analyzer.manualScan()
    fun setPaused(paused: Boolean) = analyzer.setPaused(paused)

    /** The stored (BASE) Area; the analyzer gets it through the zoom coordinator (as it is, with the zoom off). */
    fun setRoi(r: RoiFrac?) {
        roi = r
        zoom.setArea(r)
        // New area: re-lock on ITS centre, and focus on its first card.
        pendingAreaLock = scanMode == ScanMode.MOUNT
        pendingLockWhy = "new Area"
        if (scanMode == ScanMode.MOUNT) armCardFocus("first card in the new Area", resumesRefocus = true) else cardFocus.disarm()
        synchronized(recentSharpness) { recentSharpness.clear() }
    }

    /** Server answered no_card for the capture whose [CaptureOutcome.scene] is [capturedScene]. */
    fun onNoCard(capturedScene: Gray?) = analyzer.onNoCard(capturedScene)

    fun setTorch(on: Boolean) {
        torchOn = on
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(on)
    }

    fun hasTorch(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    fun setAeAwbLock(on: Boolean) {
        aeAwbLock = on
        applyAeAwbLock()
    }

    /** The zoom A/B's arm steps: each capture also measures its identification raster's detail (CapturePipeline). */
    fun setMeasureDetail(on: Boolean) { pipeline.measureDetail = on }

    /** Tap on the preview ([x],[y] in PreviewView pixels). Mount: lock there; Handheld: AF there, auto-cancel. */
    fun tapToFocus(x: Float, y: Float) {
        // The touch policy (1.1.17): during the zoom probe a tap only cancels it (through its verified
        // restore) — the owner taps again to focus there.
        if (probe.running) { probe.cancel("a tap on the preview"); return }
        val cam = camera ?: return
        val view = previewView ?: return
        val pt = view.meteringPointFactory.createPoint(x, y)
        val b = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        val lock = scanMode == ScanMode.MOUNT
        if (lock) {
            b.disableAutoCancel()
            // The user chose this point: the next auto trigger must not re-lock
            // on the area centre and throw the tap away — nor may "Refocus on a card"
            // (review of 1.1.18): no card pass, and the periodic pass pauses until a new
            // Area, a camera re-open or a mode switch (logged once, Diagnostics says so).
            pendingAreaLock = false
            cardFocus.tapped()
        }
        val where = "tap at %.0f,%.0f of the %d×%d preview".format(x, y, view.width, view.height)
        runFocus(cam, b.build(), if (lock) "locked at tap" else "tap AF", why = "tap", where = where) { ok ->
            // An answered tap lock is the last focus "Refocus on a card" counts from (a cancelled one is not).
            if (lock && ok != null) cardFocus.tapAnswered(clockNs())
        }
    }

    /** Mount: lock AF at the scan-area centre (frame centre without an area) — no card to aim at yet. */
    private fun lockAtAreaCentre() {
        if (scanMode != ScanMode.MOUNT) { pendingAreaLock = false; return }
        val p = FocusPoint.areaCentre(viewRoi)     // the Area in the frames' own (zoomed) fractions
        val c = sensorPoint(p) ?: return          // no frame yet: stay pending
        pendingAreaLock = false
        focusAtSensor(c, lock = true, what = "locked on area centre", why = pendingLockWhy, where = p.describe())
    }

    /** [p] (upright fractions) on the last analysis frame's sensor: `[x, y, sw, sh]`, or null before a frame. */
    private fun sensorPoint(p: FocusPoint.Point): FloatArray? {
        val geo = analyzer.lastFrameGeometry ?: return null
        val sw = geo[0]; val sh = geo[1]; val rot = geo[2]
        val xy = p.toSensor(sw, sh, rot)
        return floatArrayOf(xy[0].toFloat(), xy[1].toFloat(), sw.toFloat(), sh.toFloat())
    }

    /**
     * The analyzer is holding a Tray card's capture: focus ON THE CARD — the
     * Trigger box's centre ([FocusPoint.choose]; a plain empty tray gives AF
     * nothing to grip) — then release the capture. Any outcome releases it; the
     * analyzer also gives up after the hold's wait ([FocusRequest.waitNs]).
     */
    private fun focusOnCard(request: FocusRequest) {
        val p = FocusPoint.choose(request.roi, request.box, request.sampleW, request.sampleH)
        val c = sensorPoint(p)
        if (c == null || scanMode != ScanMode.MOUNT) { analyzer.focusDone(); return }
        pendingAreaLock = false
        // A pass still out (its hold gave up at 1.5 s, CameraX answers a pass that never converges
        // only after 5 s) is cut off by this one: it counts as ended with no answer — a failure toward
        // the 3-tries give-up (review of 1.1.18: dropped uncounted, the chain never ended and every
        // card was held). Counted now, so this pass's why already says "retry …".
        val started = cardFocus.passStarted(clockNs())
        started.cutOff?.let { cardPassEnded(it) }
        val pass = started.pass
        val why = cardFocus.why
        focusAtSensor(c, lock = true, what = "focused on card at ${if (p.onCard) "card" else "area"} centre",
            why = why, where = p.describe()) { ok ->
            // An answer (focused or NOT) restarts "Refocus on a card"'s clock; no answer does not.
            val end = cardFocus.passEnded(pass, ok, clockNs())
            // Cut off by a newer pass (counted when that one started): it must not release the
            // NEWER hold mid-sweep — that burst would shoot, and rebase, a blur.
            if (end == CardFocusState.End.CUT_OFF) return@focusAtSensor
            cardPassEnded(end)
            analyzer.focusDone()
        }
    }

    /** What a card pass's end means here: the give-up's log line, a retry's note. Main thread. */
    private fun cardPassEnded(end: CardFocusState.End) {
        when (end) {
            // One pass per bind / Area / zoom; the watchdog and "Refocus on a card" arm the next.
            CardFocusState.End.FOCUSED, CardFocusState.End.OUTSIDE, CardFocusState.End.CUT_OFF -> Unit
            CardFocusState.End.GAVE_UP -> {
                focusStats.gaveUp()
                DebugLog.global.w("focus", FocusLog.givenUpLine(CardFocusState.TRIES, cardFocus.minutes))
            }
            // Failed: try again on the next card (capped) — the soft-capture
            // watchdog can't catch it, its median is soft too.
            CardFocusState.End.RETRY -> focusNote += " — focus failed, retrying on the next card"
        }
    }

    /**
     * Sharpness watchdog (Laplacian variance of each Mount capture's best frame):
     * a capture far softer than this session's usual means the lens moved (bumped
     * mount, heat) — focus again on the next card. Pipeline thread.
     */
    private fun noteSharpness(v: Double) {
        synchronized(recentSharpness) {
            if (recentSharpness.size >= 5) {
                val median = recentSharpness.sorted()[recentSharpness.size / 2]
                if (v < median * SOFT_CAPTURE_RATIO) {
                    val n = recentSharpness.size
                    recentSharpness.clear()
                    focusStats.watchdogFired()
                    DebugLog.global.w("focus", FocusLog.watchdogLine(v, median, SOFT_CAPTURE_RATIO, n))
                    armCardFocus("watchdog: a soft capture")
                    main.post { focusNote = "capture came out soft — refocusing on the next card" }
                    return
                }
            }
            recentSharpness.addLast(v)
            while (recentSharpness.size > 10) recentSharpness.removeFirst()
        }
    }

    private fun focusAtSensor(
        c: FloatArray, lock: Boolean, what: String, why: String, where: String,
        onDone: ((Boolean?) -> Unit)? = null,
    ) {
        val cam = camera ?: run { onDone?.invoke(null); return }
        val an = analysis ?: run { onDone?.invoke(null); return }
        // Analysis-buffer (sensor-oriented, unrotated) coordinates.
        val factory = runCatching { SurfaceOrientedMeteringPointFactory(c[2], c[3], an) }
            .getOrElse { SurfaceOrientedMeteringPointFactory(c[2], c[3]) }
        val pt = factory.createPoint(c[0], c[1])
        val b = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF)
        if (lock) b.disableAutoCancel()
        val sensor = intArrayOf(c[0].toInt(), c[1].toInt(), c[2].toInt(), c[3].toInt())
        runFocus(cam, b.build(), what, why, where, sensor, onDone)
    }

    /** Bumped per focus action: only the LATEST one's result may change state. */
    private var focusGen = 0

    /**
     * Start one focus pass and log it under `focus` (start + result, numbered):
     * [why] asked for it, [where] it meters ([sensor] = `[x, y, w, h]` when known).
     */
    private fun runFocus(
        cam: Camera, action: FocusMeteringAction, what: String, why: String, where: String,
        sensor: IntArray? = null, onDone: ((Boolean?) -> Unit)? = null,
    ) {
        focusNote = "$what (running)"
        val gen = ++focusGen
        val n = ++focusSeq
        val t0 = SystemClock.elapsedRealtime()
        DebugLog.global.i("focus", FocusLog.startLine(n, why, where, sensor, metaAfState, metaFocusDist, focusCalibrated))
        val f = cam.cameraControl.startFocusAndMetering(action)
        f.addListener({
            var error: String? = null
            val ok = try { f.get().isFocusSuccessful } catch (e: Exception) {
                error = (e.cause ?: e).let { it.message ?: it.javaClass.simpleName }
                null
            }
            val ms = SystemClock.elapsedRealtime() - t0
            val outcome = FocusLog.classify(ok, ms, superseded = gen != focusGen)
            focusStats.record(outcome, why, ms)
            runCatching {
                DebugLog.global.i("focus", FocusLog.resultLine(n, why, outcome, ms, metaAfState, metaFocusDist, focusCalibrated, error))
            }
            // A newer action (e.g. the user's tap) cancels this one: its result
            // must not overwrite the note — but a held capture waiting on it is
            // still released.
            if (gen != focusGen) { onDone?.invoke(null); return@addListener }
            focusNote = when (outcome) {
                FocusLog.Outcome.FOCUSED -> "$what: focused in $ms ms"
                FocusLog.Outcome.TIMED_OUT -> "$what: NOT focused (timed out after $ms ms)"
                FocusLog.Outcome.FAILED -> "$what: NOT focused ($ms ms)"
                else -> "$what: cancelled"
            }
            onDone?.invoke(ok)
        }, ContextCompat.getMainExecutor(context))
    }

    // ---------------- the zoom probe (1.1.17: core ZoomProbeRun sequences it; this is its camera side) ----------------

    /** The scan screen's view of a probe run. Main thread. */
    interface ProbeListener {
        /** Where the run is: the status pill's words, the measurement card's, and the zoom the camera last ANSWERED (draw the Area there). */
        fun onProgress(p: ZoomProbeRun.Progress) {}
        /** The run ended — exactly once: refused, stopped, cancelled or done (with its result). */
        fun onFinished(o: ZoomProbeRun.Outcome) {}
    }

    /** This lens's limits, kept at bind for [ZoomProbe.Facts]. */
    private var boundLossless: Double? = null
    private var boundLensMax: Double? = null
    /** The probe's worker thread — never the capture pipeline (its lastSummary, the watch window and the watchdog stay the scanner's). */
    private val probeWorker = lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "zoom-probe") } }

    /** The probe's camera side ([ZoomProbeGlue]) over this camera. */
    private val probe: ZoomProbeGlue = ZoomProbeGlue(analyzer, object : ZoomProbeGlue.Cam {
        override fun post(block: () -> Unit) { main.post(block) }
        // Tagged with the run's token (API 28+): the run's end removes its timers from the main looper at once.
        override fun postDelayed(ms: Long, token: Any, block: () -> Unit) { main.postDelayed({ if (!released) block() }, token, ms) }
        override fun cancelDelayed(token: Any) = main.removeCallbacksAndMessages(token)
        override fun now(): Long = SystemClock.elapsedRealtime()
        override fun ready(): Boolean = !released && camera != null && analysis != null
        override fun applyAeLock() = applyAeAwbLock()
        override fun focus(u: Double, v: Double, why: String, done: (Boolean?) -> Unit): Boolean {
            val p = FocusPoint.Point(u, v, onCard = true)
            val c = sensorPoint(p) ?: return false
            if (camera == null || analysis == null) return false
            focusAtSensor(c, lock = true, what = "zoom probe: focused on the card", why = why, where = p.describe(), onDone = done)
            return true
        }
        override fun lensDpt(): Double? = metaFocusDist?.toDouble()
        override fun metaAt(timestampNs: Long): FrameMetaRing.Meta? = frameMeta.at(timestampNs)
        override fun activeArray(): IntArray? = activeArray?.let { intArrayOf(it.width(), it.height()) }
        override fun baseArea(): RoiFrac? = roi
        override fun highRes(): Boolean = resolution == Resolution.HIGH
        override fun facts(restoreZ: Double): ZoomProbe.Facts {
            val geo = analyzer.lastFrameGeometry
            val w = geo?.let { Rotation.uprightWidth(it[0], it[1], it[2]) } ?: resolution.size.width
            val h = geo?.let { Rotation.uprightHeight(it[0], it[1], it[2]) } ?: resolution.size.height
            val act = activeArray
            return ZoomProbe.Facts(boundCameraId ?: "?", w, h, resolution == Resolution.HIGH, act?.width(), act?.height(),
                boundLossless, boundLensMax, restoreZ)
        }
        override fun armCardFocus(why: String) { if (scanMode == ScanMode.MOUNT) this@CameraController.armCardFocus(why, resumesRefocus = true) }
        override fun worker(): java.util.concurrent.Executor = probeWorker.value
    })

    init { probe.zoom = zoom }

    /**
     * Run the zoom probe: [run] = its number in the session, [earlier] = the earlier VALID runs'
     * 1× card thumbnails (the same card again does not count as another). False = no run was made
     * (no camera, released, or one already runs) — nothing changed. True = the run is under way:
     * every outcome, a refusal included, comes back through [l]. While it runs the analyzer's gate
     * stays closed (nothing ticks, copies or fires): it files nothing. Main thread.
     */
    fun startZoomProbe(run: Int, earlier: List<ZoomProbeRun.Earlier>, l: ProbeListener): Boolean =
        probe.start(run, earlier, l, seed = SystemClock.elapsedRealtime() xor run.toLong())

    /** The owner cancelled (Cancel probe, or a control the touch policy turns into a cancel): the verified restore. */
    fun cancelZoomProbe(why: String) = probe.cancel(why)

    fun zoomProbeRunning(): Boolean = probe.running

    // ---------------- diagnostics ----------------

    /**
     * The current UPRIGHT analysis frame as JPEG (long side ≤ [maxSide]) — the space the
     * scan Area fractions live in, so a browser can draw the Area on it. Null when no frame
     * arrives within [timeoutMs] (camera not bound). Blocks the caller; never the UI thread.
     */
    fun snapshotJpeg(maxSide: Int = 1280, timeoutMs: Long = 2_000): ByteArray? =
        snapshot(maxSide, timeoutMs, base = true)?.jpeg

    /**
     * `snapshot.jpg?view=camera` (1.1.17): the upright analysis frame AS THE CAMERA DELIVERS
     * IT — zoomed, no grey ring, long side ≤ [maxSide] (2048: the stream's own pixels, so
     * focus can be judged) — with the zoom that frame was taken at. The same one-slot
     * request as [snapshotJpeg], obeying the zoom settle gate (null: no frame in time).
     */
    fun cameraSnapshot(maxSide: Int = 2048, timeoutMs: Long = 2_000): DeviceApi.Picture? =
        snapshot(maxSide, timeoutMs, base = false)

    /** The next analyzed frame and its zoom ([ScanAnalyzer.requestSnapshotAt]) as a picture, or null in [timeoutMs]. */
    private fun snapshot(maxSide: Int, timeoutMs: Long, base: Boolean): DeviceApi.Picture? {
        val got = java.util.concurrent.ArrayBlockingQueue<Pair<Nv21Frame, Double>>(1)
        analyzer.requestSnapshotAt { f, z -> got.offer(f to z) }
        val (frame, z) = got.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return null
        return SnapshotPicture.encode(frame, z, maxSide, base)
    }

    /**
     * The camera block of Settings → Diagnostics and the debug report — LIVE:
     * read whenever Diagnostics opens or a report is made (and after every
     * capture, as the snapshot kept for when this screen is gone), so focus,
     * the last frame, the analyzer and the last capture are current. Before
     * 1.1.14 it was copied once at bind, before any focus lock had run. The
     * device / characteristics part is read once per bind. Main thread.
     */
    fun diagnostics(): String = (staticDiagnostics ?: readStaticDiagnostics().also { staticDiagnostics = it }) + liveDiagnostics()

    /**
     * The "focus" line: this session's passes, the last result, the lens now, whether the next card
     * gets a pass, and "Refocus on a card" ("refocus every 5 min · last card focus 3 min ago").
     */
    fun focusSummary(): String =
        "focus: ${focusStats.summary()} · now ${FocusLog.afAndLens(metaAfState, metaFocusDist, focusCalibrated)}" +
            (if (scanMode == ScanMode.MOUNT) {
                " · next card pass: " + (if (cardFocus.isDue) "due (${cardFocus.why})" else "not due") +
                    " · " + cardFocus.describe(clockNs()) +
                    cardFocus.periodic.takeIf { it > 0 }?.let { " · periodic passes $it" }.orEmpty()
            } else "")

    private fun readStaticDiagnostics(): String = buildString {
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= 31) appendLine("soc: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("camera setting: " + (cameraId?.let { "camera $it" } ?: "Automatic") +
            " · offered: " + CameraChoice.offered(CameraCatalog.lenses(context)).joinToString("; ") { CameraChoice.label(it) })
        val cm = context.getSystemService(CameraManager::class.java)
        runCatching {
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    else -> "external"
                }
                appendLine("camera $id: $facing, level ${levelName(ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}" +
                    if (id == boundCameraId) "  ← bound" else "")
            }
        }.onFailure { appendLine("camera list: $it") }
        val id = boundCameraId
        if (id != null) runCatching {
            val ch = cm.getCameraCharacteristics(id)
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString(",") { capName(it) }
            appendLine("capabilities: $caps")
            val yuv = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)?.take(14)?.joinToString(" ")
            appendLine("YUV sizes: $yuv")
            appendLine("video stabilization modes: " +
                ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.joinToString(","))
            appendLine("OIS modes: " +
                ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.joinToString(","))
            appendLine("min focus distance: ${ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)} dpt, " +
                "calibration ${ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)}")
            appendLine("sensor orientation: ${ch.get(CameraCharacteristics.SENSOR_ORIENTATION)}")
        }.onFailure { appendLine("characteristics: $it") }
    }

    private fun liveDiagnostics(): String = buildString {
        appendLine("requested: ${resolution.name} ${resolution.size}")
        appendLine("analysis: ${analysis?.resolutionInfo?.resolution} · preview: ${preview?.resolutionInfo?.resolution}")
        appendLine("mode: $scanMode · focus note: $focusNote · torch: $torchOn · AE/AWB lock: $aeAwbLock")
        appendLine(focusSummary())
        appendLine(zoom.state().describe() + " · " + describeReadBack())
        appendLine("camera state: ${camera?.cameraInfo?.cameraState?.value?.type}")
        val z = readZoomFacts(camera)
        appendLine("zoom (CameraX): min ${z.minRatio} · max ${z.maxRatio} · current ${z.ratio} · linear ${z.linear}")
        appendLine("zoom (Camera2): ratio range ${z.ratioRange} · max digital ${z.maxDigital} · " +
            "active array ${z.activeArray} · pixel array ${z.pixelArray} · cropping ${z.cropping}")
        appendLine("last frame: AF state $metaAfState ${FocusLog.afStateName(metaAfState)} · " +
            "${FocusLog.lens(metaFocusDist, focusCalibrated)} · exposure " +
            "${metaExposureNs?.let { "%.1f ms".format(it / 1e6) }} · ISO $metaIso · stab $metaStab")
        appendLine("analyzer: ${analyzer.stats()}")
        appendLine("last capture: ${pipeline.lastSummary ?: "none"}")
    }

    /**
     * The zoom / sensor facts the auto-zoom question needs (nothing reported
     * them before 1.1.5): CameraX's ZoomState and the Camera2 characteristics
     * behind it. Every item reads "unknown" when absent — Robolectric's fake
     * camera and odd vendors never crash this.
     */
    private class ZoomFacts(
        val minRatio: String, val maxRatio: String, val ratio: String, val linear: String,
        val ratioRange: String, val maxDigital: String, val activeArray: String, val pixelArray: String,
        val cropping: String,
    ) {
        /** The one-line form logged at bind. */
        fun summary(): String = "zoom range ${minRatio}–${maxRatio} (ratio range ${ratioRange}, " +
            "max digital ${maxDigital}, active array ${activeArray}, cropping ${cropping})"
    }

    private fun readZoomFacts(cam: Camera?): ZoomFacts {
        val unknown = "unknown"
        val zs = runCatching { cam?.cameraInfo?.zoomState?.value }.getOrNull()
        val info = runCatching { cam?.let { Camera2CameraInfo.from(it.cameraInfo) } }.getOrNull()
        fun <T> characteristic(key: CameraCharacteristics.Key<T>): T? =
            runCatching { info?.getCameraCharacteristic(key) }.getOrNull()
        // CONTROL_ZOOM_RATIO_RANGE exists from API 30 (the N200 runs 30+); the guard sits beside the use for lint.
        val ratioRange = runCatching {
            if (Build.VERSION.SDK_INT >= 30) info?.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        }.getOrNull()
        return ZoomFacts(
            minRatio = zs?.let { "%.2f".format(it.minZoomRatio) } ?: unknown,
            maxRatio = zs?.let { "%.2f".format(it.maxZoomRatio) } ?: unknown,
            ratio = zs?.let { "%.2f".format(it.zoomRatio) } ?: unknown,
            linear = zs?.let { "%.2f".format(it.linearZoom) } ?: unknown,
            ratioRange = ratioRange?.let { "${it.lower}–${it.upper}" } ?: unknown,
            maxDigital = characteristic(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toString() ?: unknown,
            activeArray = characteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?.let { "${it.width()}×${it.height()}" } ?: unknown,
            pixelArray = characteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                ?.let { "${it.width}×${it.height}" } ?: unknown,
            cropping = characteristic(CameraCharacteristics.SCALER_CROPPING_TYPE)?.let { croppingName(it) } ?: unknown,
        )
    }

    private fun croppingName(c: Int) = when (c) {
        CameraCharacteristics.SCALER_CROPPING_TYPE_CENTER_ONLY -> "CENTER_ONLY"
        CameraCharacteristics.SCALER_CROPPING_TYPE_FREEFORM -> "FREEFORM"
        else -> "$c"
    }

    private fun levelName(l: Int?) = when (l) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "$l"
    }

    private fun capName(c: Int) = when (c) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        else -> "$c"
    }
}
