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
import androidx.lifecycle.LifecycleOwner
import io.github.darylno.cardscanner.capture.CapturePipeline
import io.github.darylno.cardscanner.capture.CaptureResult
import io.github.darylno.cardscanner.core.CameraChoice
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.Gray
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.Rotation
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/** A Mount capture this much softer than the session's median re-arms the card-focus pass. */
private const val SOFT_CAPTURE_RATIO = 0.5
/** A card focus that fails is retried on the next card, this many passes in all. */
private const val CARD_FOCUS_TRIES = 3
/** A finished capture: [result] on success, else [error]. [scene] is for [CameraController.onNoCard]. */
class CaptureOutcome(
    val id: Long,
    val trigger: CaptureTrigger,
    val mode: ScanMode,
    val scene: Gray?,
    val result: CaptureResult?,
    val error: Throwable?,
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
 * capture (≤ FOCUS_WAIT_NS) while AF runs at the area centre with the card
 * under it, then shoots from post-focus frames. Later cards shoot instantly.
 * A sharpness watchdog (a capture far softer than the session median) re-arms
 * that one-card focus pass. A tap still overrides. Handheld = continuous AF with tap-to-focus. Torch / AE+AWB lock / focus
 * are re-applied after every bind AND every camera re-open (CameraControl
 * state dies with the session).
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

        override fun onFocusRequest() {
            main.post { focusOnCard() }
        }

        override fun onCaptureRequest(request: CaptureBurst) {
            main.post { listener.onCaptureStarted(request) }
            val ok = pipeline.submit(request.frames, request.cropRoi) { r ->
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
                    request.id, request.trigger, request.mode, request.scene, r.getOrNull(), r.exceptionOrNull(),
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
    private var roi: RoiFrac? = null
    var focusLocked = false
    /**
     * Mount: focus on the NEXT card before shooting it. Set after a bind, a
     * switch to Mount, an area change, or a sharpness drop; cleared once that
     * card has been focused on (or by a tap). Read by the analysis thread
     * through [ScanAnalyzer.focusFirst].
     */
    @Volatile private var cardFocusDue = false
    private var cardFocusSeq = 0
    @Volatile private var cardFocusFails = 0
    private val recentSharpness = ArrayDeque<Double>()

    init {
        analyzer.focusFirst = { cardFocusDue && scanMode == ScanMode.MOUNT }
    }
    /** Mount: lock at the area centre as soon as a frame gives us the geometry. */
    private var pendingAreaLock = false
        private set
    private var focusNote = "continuous (default)"

    // Latest per-frame metadata (camera thread), for diagnostics only.
    @Volatile private var metaAfState: Int? = null
    @Volatile private var metaFocusDist: Float? = null
    @Volatile private var metaExposureNs: Long? = null
    @Volatile private var metaIso: Int? = null
    @Volatile private var metaStab: Int? = null
    private val metaCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            metaAfState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)
            metaFocusDist = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)
            metaExposureNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
            metaIso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
            metaStab = result.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
        }
    }

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
        DebugLog.global.i("camera", readZoomFacts(cam).summary())
        lastStateType = null
        cam.cameraInfo.cameraState.observe(owner) { st -> onCameraState(st) }
        analyzer.cameraRestarted()
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
            applySettings()
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
        focusLocked = false
        if (scanMode == ScanMode.HANDHELD) {
            cam.cameraControl.cancelFocusAndMetering()
            focusNote = "continuous (handheld)"
        } else {
            focusNote = "locking on the scan-area centre…"
            pendingAreaLock = true
            cardFocusDue = true
            cardFocusFails = 0
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
        focusLocked = false
        val cam = camera ?: return
        cam.cameraControl.cancelFocusAndMetering()   // both: back to continuous AF
        focusNote = if (m == ScanMode.HANDHELD) "continuous (handheld)" else "locking on the scan-area centre…"
        pendingAreaLock = m == ScanMode.MOUNT
        cardFocusDue = m == ScanMode.MOUNT
        cardFocusFails = 0
    }

    fun setAuto(enabled: Boolean) = analyzer.setAuto(enabled)
    fun reset() = analyzer.reset()
    fun manualScan() = analyzer.manualScan()
    fun setPaused(paused: Boolean) = analyzer.setPaused(paused)

    fun setRoi(r: RoiFrac?) {
        roi = r
        analyzer.setRoi(r)
        focusLocked = false   // new area: re-lock on ITS centre
        pendingAreaLock = scanMode == ScanMode.MOUNT
        cardFocusDue = scanMode == ScanMode.MOUNT
        cardFocusFails = 0
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

    /**
     * Mount: lock focus now on the card the mask sees (else the scan-area centre).
     * Handheld: one-shot AF at the area centre, then back to continuous.
     */
    fun refocus() {
        if (scanMode == ScanMode.MOUNT) lockAtAreaCentre()
        else focusAtSensor(areaCentreSensor(null) ?: return, lock = false)
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
            focusLocked = true
            pendingAreaLock = false
            cardFocusDue = false   // the user chose the focus point
        }
        runFocus(cam, b.build(), if (lock) "locked at tap" else "tap AF")
    }

    /** Mount: lock AF at the scan-area centre (frame centre without an area). */
    private fun lockAtAreaCentre() {
        if (scanMode != ScanMode.MOUNT) { pendingAreaLock = false; return }
        val c = areaCentreSensor(null) ?: return          // no frame yet: stay pending
        pendingAreaLock = false
        focusAtSensor(c, lock = true)
    }

    /** Sensor-pixel point to focus on: the box centre (if known) else the scan-area centre. */
    private fun areaCentreSensor(boxInfo: Pair<io.github.darylno.cardscanner.core.Box, RoiFrac?>?): FloatArray? {
        val geo = analyzer.lastFrameGeometry ?: return null
        val sw = geo[0]; val sh = geo[1]; val rot = geo[2]
        val uw = Rotation.uprightWidth(sw, sh, rot); val uh = Rotation.uprightHeight(sw, sh, rot)
        val area = boxInfo?.second ?: roi ?: RoiFrac(0.0, 0.0, 1.0, 1.0)
        var fu = (area.x0 + area.x1) / 2; var fv = (area.y0 + area.y1) / 2
        val dims = analyzer.lastGrayDims
        val box = boxInfo?.first
        if (box != null && dims != null) {
            fu = area.x0 + (area.x1 - area.x0) * (box.x + box.w / 2.0) / dims[0]
            fv = area.y0 + (area.y1 - area.y0) * (box.y + box.h / 2.0) / dims[1]
        }
        val u = (fu * uw).toInt().coerceIn(0, uw - 1)
        val v = (fv * uh).toInt().coerceIn(0, uh - 1)
        return floatArrayOf(
            Rotation.sensorX(u, v, sw, sh, rot).toFloat(), Rotation.sensorY(u, v, sw, sh, rot).toFloat(),
            sw.toFloat(), sh.toFloat(),
        )
    }

    /**
     * The analyzer is holding the first card's capture: focus at the area centre
     * WITH the card under it (a plain empty tray gives AF nothing to grip), then
     * release the capture. Any outcome releases it; the analyzer also gives up
     * after FOCUS_WAIT_NS.
     */
    private fun focusOnCard() {
        val seq = ++cardFocusSeq
        val c = areaCentreSensor(null)
        if (c == null || scanMode != ScanMode.MOUNT) { analyzer.focusDone(); return }
        pendingAreaLock = false
        focusAtSensor(c, lock = true, what = "focused on card at area centre") { ok ->
            // A superseded pass (a newer hold asked again) must not release the
            // NEWER hold mid-sweep: that burst would shoot, and rebase, a blur.
            if (seq != cardFocusSeq) return@focusAtSensor
            if (ok == true || ++cardFocusFails >= CARD_FOCUS_TRIES) {
                cardFocusDue = false   // one pass per session/area; the watchdog re-arms it
            } else {
                // Failed: try again on the next card (capped) — the soft-capture
                // watchdog can't catch it, its median is soft too.
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
                    cardFocusDue = true
                    cardFocusFails = 0
                    recentSharpness.clear()
                    main.post { focusNote = "capture came out soft — refocusing on the next card" }
                    return
                }
            }
            recentSharpness.addLast(v)
            while (recentSharpness.size > 10) recentSharpness.removeFirst()
        }
    }

    private fun focusAtSensor(c: FloatArray, lock: Boolean, what: String? = null, onDone: ((Boolean?) -> Unit)? = null) {
        val cam = camera ?: run { onDone?.invoke(null); return }
        val an = analysis ?: run { onDone?.invoke(null); return }
        // Analysis-buffer (sensor-oriented, unrotated) coordinates.
        val factory = runCatching { SurfaceOrientedMeteringPointFactory(c[2], c[3], an) }
            .getOrElse { SurfaceOrientedMeteringPointFactory(c[2], c[3]) }
        val pt = factory.createPoint(c[0], c[1])
        val b = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF)
        if (lock) b.disableAutoCancel()
        if (lock) focusLocked = true
        runFocus(cam, b.build(), what ?: if (lock) "locked on area centre" else "one-shot AF", relockable = lock, onDone = onDone)
    }

    /** Bumped per focus action: only the LATEST one's result may change state. */
    private var focusGen = 0

    private fun runFocus(
        cam: Camera, action: FocusMeteringAction, what: String,
        relockable: Boolean = false, onDone: ((Boolean?) -> Unit)? = null,
    ) {
        focusNote = "$what (running)"
        val gen = ++focusGen
        val f = cam.cameraControl.startFocusAndMetering(action)
        f.addListener({
            val ok = try { f.get().isFocusSuccessful } catch (e: Exception) { null }
            // A newer action (e.g. the user's tap) cancels this one: its result
            // must neither overwrite the note nor undo the tap's lock — but a
            // held capture waiting on it is still released.
            if (gen != focusGen) { onDone?.invoke(null); return@addListener }
            focusNote = when (ok) {
                true -> "$what: focused"
                false -> "$what: NOT focused"
                null -> "$what: cancelled"
            }
            // An empty, plain tray gives AF nothing to grip: let the first auto
            // Trigger lock again at the same point, with the card under it.
            if (relockable && ok != true && scanMode == ScanMode.MOUNT) focusLocked = false
            onDone?.invoke(ok)
        }, ContextCompat.getMainExecutor(context))
    }

    // ---------------- diagnostics ----------------

    /** Multi-line report for the Diagnostics screen (Copy button). Main thread. */
    /**
     * The current UPRIGHT analysis frame as JPEG (long side ≤ [maxSide]) — the space the
     * scan Area fractions live in, so a browser can draw the Area on it. Null when no frame
     * arrives within [timeoutMs] (camera not bound). Blocks the caller; never the UI thread.
     */
    fun snapshotJpeg(maxSide: Int = 1280, timeoutMs: Long = 2_000): ByteArray? {
        val got = java.util.concurrent.ArrayBlockingQueue<io.github.darylno.cardscanner.core.Nv21Frame>(1)
        analyzer.requestSnapshot { got.offer(it) }
        val frame = got.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return null
        val bgr = io.github.darylno.cardscanner.core.Nv21Bgr.toUprightBgr(frame)
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

    fun diagnostics(): String = buildString {
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
        appendLine("requested: ${resolution.name} ${resolution.size}")
        appendLine("analysis: ${analysis?.resolutionInfo?.resolution} · preview: ${preview?.resolutionInfo?.resolution}")
        appendLine("mode: $scanMode · focus: $focusNote · torch: $torchOn · AE/AWB lock: $aeAwbLock")
        appendLine("camera state: ${camera?.cameraInfo?.cameraState?.value?.type}")
        val z = readZoomFacts(camera)
        appendLine("zoom (CameraX): min ${z.minRatio} · max ${z.maxRatio} · current ${z.ratio} · linear ${z.linear}")
        appendLine("zoom (Camera2): ratio range ${z.ratioRange} · max digital ${z.maxDigital} · " +
            "active array ${z.activeArray} · pixel array ${z.pixelArray} · cropping ${z.cropping}")
        appendLine("last frame: AF state $metaAfState · focus ${metaFocusDist} dpt · exposure " +
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
