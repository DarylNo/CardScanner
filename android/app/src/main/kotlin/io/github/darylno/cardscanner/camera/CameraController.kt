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
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.FocusPoint
import io.github.darylno.cardscanner.core.Gray
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.ZoomCoordinator
import io.github.darylno.cardscanner.core.ZoomFit
import io.github.darylno.cardscanner.core.ZoomSnapshot
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/** A Mount capture this much softer than the session's median re-arms the card-focus pass. */
private const val SOFT_CAPTURE_RATIO = 0.5
/** A card focus that fails is retried on the next card, this many passes in all. */
private const val CARD_FOCUS_TRIES = 3
/**
 * A finished capture: [result] on success, else [error]. [scene] is for [CameraController.onNoCard].
 * [lens] = the camera's own report for the frame the pipeline chose (AF state, lens
 * position, exposure, ISO — "is it focusing?"), or null when none was kept.
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
 * centre ([FocusPoint]; the Area centre before 1.1.11) — then shoots from
 * post-focus frames. Later cards shoot instantly. A sharpness watchdog (a
 * capture far softer than the session median) re-arms that one-card focus
 * pass. A tap still overrides. Handheld = continuous AF with tap-to-focus.
 * Torch / AE+AWB lock / focus are re-applied after every bind AND every
 * camera re-open (CameraControl state dies with the session).
 *
 * "Is it focusing?" (owner on 1.1.10): every pass is logged under `focus`
 * — why, where (Area / card centre → sensor point), the result (focused / NOT
 * focused / timed out / cancelled) with its ms, and the camera's own AF state
 * and lens position ([FocusLog]); so are the watchdog's re-arms and the
 * card pass giving up. Each capture carries its chosen frame's AF state and
 * lens position ([FrameMetaRing], by sensor timestamp).
 *
 * "Zoom to fit the Area" (1.1.11, Settings → Camera, OFF by default in this
 * build): [ZoomCoordinator] decides, this class is its CameraX glue. The Area it
 * is given ([setRoi]) is BASE (1×) fractions; the analyzer, the focus points and
 * the overlay get the VIEW Area at the ratio READ BACK from the capture results
 * (CONTROL_ZOOM_RATIO on API 30+, else the crop region). CameraX resets zoom on
 * every detach, so the zoom is re-applied after every bind, camera re-open and
 * screen start; the analyzer's settle gate is closed meanwhile. Logged under
 * `zoom`: what was asked, what the camera reports, what bound the ratio.
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
                    request.zoom,
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
    var scanMode = ScanMode.MOUNT
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
     * Mount: focus on the NEXT card before shooting it. Set after a bind, a
     * switch to Mount, an area change, or a sharpness drop; cleared once that
     * card has been focused on (or by a tap). Read by the analysis thread
     * through [ScanAnalyzer.focusFirst].
     */
    @Volatile private var cardFocusDue = false
    /** Why the next card pass runs (the focus log's words): first card after a bind, the watchdog, a retry… */
    @Volatile private var cardFocusWhy = "first card"
    private var cardFocusSeq = 0
    @Volatile private var cardFocusFails = 0
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
        analyzer.focusFirst = { cardFocusDue && scanMode == ScanMode.MOUNT }
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
     * says what the camera WAS (review of 1.1.11: a slow reopen timed out onto it).
     */
    private val metaSeq = java.util.concurrent.atomic.AtomicLong()
    /** [metaSeq] when the latest zoom request was made (main thread). */
    @Volatile private var zoomAskedAt = Long.MAX_VALUE
    /** The last ~2 s of frames' reports by SENSOR_TIMESTAMP (= the analysis frame's timestamp): what a capture's frames said. */
    private val frameMeta = FrameMetaRing()
    private val metaCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            metaAfState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)
            metaFocusDist = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)
            metaExposureNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
            metaIso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
            metaStab = result.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
            if (Build.VERSION.SDK_INT >= 30) metaZoomRatio = result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
            metaCrop = result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)
            metaSeq.incrementAndGet()
            result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)?.let { ts ->
                frameMeta.put(FrameMetaRing.Meta(ts, metaAfState, metaFocusDist, metaExposureNs, metaIso))
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

    // ---------------- zoom to fit the Area (core ZoomCoordinator; this is its CameraX glue) ----------------

    private val zoom = ZoomCoordinator(object : ZoomCoordinator.Port {
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
                    armCardFocus("first card after the zoom change")
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
                runCatching { DebugLog.global.i("zoom", "camera reports " + describeReadBack()) }
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

    /** Pick the camera that scans (null = Automatic) — rebinds when it changes. */
    fun setCamera(id: String?) {
        if (id == cameraId) return
        cameraId = id
        if (camera != null) bind()
    }

    /** Standard 1600×1200 / High 2048×1536 — rebinds. */
    fun setResolution(res: Resolution) {
        if (res == resolution) return
        resolution = res
        if (camera != null) bind()
    }

    /** Unbind and stop the worker threads (Activity onDestroy). */
    fun release() {
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
        zoom.cameraBound(lossless, lensMax)
        if (!lifecycleWatched) { owner.lifecycle.addObserver(lifecycleWatch); lifecycleWatched = true }
        pendingLockWhy = "camera bind"
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
            armCardFocus("first card after the $pendingLockWhy")
        }
    }

    private fun applyAeAwbLock() {
        val cam = camera ?: return
        runCatching {
            Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, aeAwbLock)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, aeAwbLock)
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
        if (m == ScanMode.MOUNT) armCardFocus("first card after the mode switch") else cardFocusDue = false
    }

    /** The next card gets a focus pass ([why] goes in the focus log); the failure count starts again. */
    private fun armCardFocus(why: String) {
        cardFocusWhy = why
        cardFocusFails = 0
        cardFocusDue = true
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
        if (scanMode == ScanMode.MOUNT) armCardFocus("first card in the new Area") else cardFocusDue = false
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

    /** Tap on the preview ([x],[y] in PreviewView pixels). Mount: lock there; Handheld: AF there, auto-cancel. */
    fun tapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val view = previewView ?: return
        val pt = view.meteringPointFactory.createPoint(x, y)
        val b = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        val lock = scanMode == ScanMode.MOUNT
        if (lock) {
            b.disableAutoCancel()
            // The user chose this point: the next auto trigger must not re-lock
            // on the area centre and throw the tap away.
            pendingAreaLock = false
            cardFocusDue = false   // the user chose the focus point
        }
        val where = "tap at %.0f,%.0f of the %d×%d preview".format(x, y, view.width, view.height)
        runFocus(cam, b.build(), if (lock) "locked at tap" else "tap AF", why = "tap", where = where)
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
     * The analyzer is holding the first card's capture: focus ON THE CARD — the
     * Trigger box's centre ([FocusPoint.choose]; a plain empty tray gives AF
     * nothing to grip) — then release the capture. Any outcome releases it; the
     * analyzer also gives up after FOCUS_WAIT_NS.
     */
    private fun focusOnCard(request: FocusRequest) {
        val seq = ++cardFocusSeq
        val p = FocusPoint.choose(request.roi, request.box, request.sampleW, request.sampleH)
        val c = sensorPoint(p)
        if (c == null || scanMode != ScanMode.MOUNT) { analyzer.focusDone(); return }
        pendingAreaLock = false
        val why = cardFocusWhy
        focusAtSensor(c, lock = true, what = "focused on card at ${if (p.onCard) "card" else "area"} centre",
            why = why, where = p.describe()) { ok ->
            // A superseded pass (a newer hold asked again) must not release the
            // NEWER hold mid-sweep: that burst would shoot, and rebase, a blur.
            if (seq != cardFocusSeq) return@focusAtSensor
            if (ok == true) {
                cardFocusDue = false   // one pass per session/area; the watchdog re-arms it
            } else if (++cardFocusFails >= CARD_FOCUS_TRIES) {
                cardFocusDue = false
                focusStats.gaveUp()
                DebugLog.global.w("focus", FocusLog.givenUpLine(CARD_FOCUS_TRIES))
            } else {
                // Failed: try again on the next card (capped) — the soft-capture
                // watchdog can't catch it, its median is soft too.
                cardFocusWhy = "retry ${cardFocusFails + 1} of $CARD_FOCUS_TRIES (the last card pass failed)"
                focusNote += " — focus failed, retrying on the next card"
            }
            analyzer.focusDone()
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

    // ---------------- diagnostics ----------------

    /**
     * The current UPRIGHT analysis frame as JPEG (long side ≤ [maxSide]) — the space the
     * scan Area fractions live in, so a browser can draw the Area on it. Null when no frame
     * arrives within [timeoutMs] (camera not bound). Blocks the caller; never the UI thread.
     */
    fun snapshotJpeg(maxSide: Int = 1280, timeoutMs: Long = 2_000): ByteArray? {
        val got = java.util.concurrent.ArrayBlockingQueue<Pair<io.github.darylno.cardscanner.core.Nv21Frame, Double>>(1)
        analyzer.requestSnapshotAt { f, z -> got.offer(f to z) }
        val (frame, z) = got.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return null
        val upright = io.github.darylno.cardscanner.core.Nv21Bgr.toUprightBgr(frame)
        // Zoomed: the BASE-space picture (the zoomed view scaled by 1/z on grey) — the browser
        // draws the stored base Area on it 1:1.
        val bgr = if (z > 1.0) ZoomSnapshot.composeBase(upright, z).also { upright.release() } else upright
        try {
            val scale = maxSide.toDouble() / maxOf(bgr.cols(), bgr.rows())
            if (scale < 1.0) {
                val small = org.opencv.core.Mat()
                org.opencv.imgproc.Imgproc.resize(bgr, small, org.opencv.core.Size(), scale, scale,
                    org.opencv.imgproc.Imgproc.INTER_AREA)
                try { return CapturePipeline.encodeJpeg(small) } finally { small.release() }
            }
            return CapturePipeline.encodeJpeg(bgr)
        } finally {
            bgr.release()
        }
    }

    /**
     * The camera block of Settings → Diagnostics and the debug report — LIVE:
     * read whenever Diagnostics opens or a report is made (and after every
     * capture, as the snapshot kept for when this screen is gone), so focus,
     * the last frame, the analyzer and the last capture are current. Before
     * 1.1.11 it was copied once at bind, before any focus lock had run. The
     * device / characteristics part is read once per bind. Main thread.
     */
    fun diagnostics(): String = (staticDiagnostics ?: readStaticDiagnostics().also { staticDiagnostics = it }) + liveDiagnostics()

    /** The "focus" line: this session's passes, the last result, the lens now. */
    fun focusSummary(): String =
        "focus: ${focusStats.summary()} · now ${FocusLog.afAndLens(metaAfState, metaFocusDist, focusCalibrated)}" +
            (if (scanMode == ScanMode.MOUNT) " · next card pass: " + (if (cardFocusDue) "due ($cardFocusWhy)" else "not due") else "")

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
