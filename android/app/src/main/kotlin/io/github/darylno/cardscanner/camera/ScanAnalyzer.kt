package io.github.darylno.cardscanner.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.github.darylno.cardscanner.core.AutoScanner
import io.github.darylno.cardscanner.core.Box
import io.github.darylno.cardscanner.core.Gray
import io.github.darylno.cardscanner.core.GraySampler
import io.github.darylno.cardscanner.core.Nv21Frame
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.Rotation
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Mount = auto-capture over a tray (the tuned detection); Handheld = tap to capture. */
enum class ScanMode { MOUNT, HANDHELD }

/** Who asked for a capture: the detector's Trigger, or the Scan button. */
enum class CaptureTrigger { AUTO, MANUAL }

/**
 * One detection tick, for the overlay/status line. [box] is in the detection
 * sample's pixels ([grayW]×[grayH], which cover [roi] of the UPRIGHT frame, or
 * the whole frame when null); [uprightW]×[uprightH] is the analysis frame
 * upright. [sample] is the Gray the tick ran on.
 */
class DetectionUpdate(
    val event: AutoScanner.Event,
    val box: Box?,
    val grayW: Int,
    val grayH: Int,
    val uprightW: Int,
    val uprightH: Int,
    val roi: RoiFrac?,
    val autoEnabled: Boolean,
    val hasEmptyRef: Boolean,
    val sample: Gray,
)

/**
 * A burst handed to the capture pipeline. [frames] are private copies (time
 * order); [cropRoi] = the scan area (Mount) or null = whole frame (Handheld);
 * [scene] = the scanner's scannedFrame at capture time — hand it back to
 * [ScanAnalyzer.onNoCard] if the server answers no_card.
 */
class CaptureBurst(
    val id: Long,
    val trigger: CaptureTrigger,
    val mode: ScanMode,
    val frames: List<Nv21Frame>,
    val cropRoi: RoiFrac?,
    val scene: Gray?,
    val box: Box?,
    val uprightW: Int,
    val uprightH: Int,
)

/**
 * The analysis-thread loop. EVERYTHING that touches [AutoScanner] or the
 * [FrameRing] runs on the analysis executor: frames arrive there, and every UI
 * command is posted there ([post]) — AutoScanner is not thread-safe.
 *
 * Per frame (≥ [FRAME_GATE_NS] apart by sensor timestamp; ~90 ms because a
 * 100 ms gate on 33 ms frames aliases to 133 ms):
 *  1. Mount: every ≥ [SAMPLE_GATE_NS] a detection sample straight from the Y
 *     plane (GraySampler.sampleY, no copy) → AutoScanner.tick. The detector
 *     keeps phone.html's 200 ms-ish cadence: STABLE_FRAC was tuned for it.
 *  2. Copy the frame into the ring when a capture could need it (Handheld:
 *     always; Mount: while the mask sees something, while awaiting the next
 *     card, with Auto off, or with a manual scan pending). The copy of THIS
 *     frame happens before any snapshot, so a burst ends at the trigger frame.
 *  3. Trigger → snapshot the last 3 fresh frames, captureDone() at once (the
 *     frames already exist — there is no capture window), hand off.
 * The image is ALWAYS closed (an unclosed ImageProxy stalls the stream).
 */
class ScanAnalyzer(
    private val commands: Executor,
    private val sink: Sink,
    private val ring: FrameRing = FrameRing(4),
    private val scanner: AutoScanner = AutoScanner(),
) : ImageAnalysis.Analyzer {

    /** Called on the ANALYSIS thread; implementations must not block. */
    interface Sink {
        fun onDetection(update: DetectionUpdate)
        fun onCaptureRequest(request: CaptureBurst)
        fun onAnalyzerError(error: Throwable)
    }

    private val planes = ImageProxyPlanes()

    // --- analysis-thread state ---
    private var roi: RoiFrac? = null
    private var mode = ScanMode.MOUNT
    private var lastFrameTs = NONE
    private var lastSampleTs = NONE
    private var lastSample: Gray? = null
    private var lastBox: Box? = null
    private var manualPending = false
    private var manualStartTs = NONE
    private var nextId = 1L

    // --- stats (written on the analysis thread, read racily by diagnostics) ---
    @Volatile private var frames = 0L
    @Volatile private var gated = 0L
    @Volatile private var ticks = 0L
    @Volatile private var copies = 0L
    @Volatile private var copyNsTotal = 0L
    @Volatile private var sampleNsTotal = 0L
    @Volatile private var captures = 0L
    @Volatile private var errors = 0L
    @Volatile private var lastError: String? = null
    @Volatile private var frameDesc = "no frame yet"
    /** Sensor size + rotation of the last frame: `[sw, sh, rotation]`, or null. */
    @Volatile var lastFrameGeometry: IntArray? = null
        private set
    /** The last box the mask saw (sample pixels) with the roi it was sampled in. */
    @Volatile var lastBoxInfo: Pair<Box, RoiFrac?>? = null
        private set
    @Volatile var lastGrayDims: IntArray? = null
        private set

    override fun analyze(image: ImageProxy) {
        try {
            planes.bind(image)
            process(planes, image.imageInfo.rotationDegrees, image.imageInfo.timestamp)
        } catch (t: Throwable) {
            errors++
            lastError = t.toString()
            runCatching { sink.onAnalyzerError(t) }
        } finally {
            planes.unbind()
            image.close()
        }
    }

    /** One camera frame (the testable core of [analyze]). Analysis thread only. */
    fun process(p: YuvPlanes, rotation: Int, timestampNs: Long) {
        frames++
        // A timestamp going backwards = a new camera session: accept it.
        if (lastFrameTs != NONE && timestampNs >= lastFrameTs && timestampNs - lastFrameTs < FRAME_GATE_NS) {
            gated++
            return
        }
        lastFrameTs = timestampNs
        val sw = p.width; val sh = p.height
        val uw = Rotation.uprightWidth(sw, sh, rotation)
        val uh = Rotation.uprightHeight(sw, sh, rotation)
        lastFrameGeometry = intArrayOf(sw, sh, Rotation.normalize(rotation))
        frameDesc = "${sw}x$sh rot $rotation"

        var event: AutoScanner.Event? = null
        if (mode == ScanMode.MOUNT &&
            (lastSampleTs == NONE || timestampNs < lastSampleTs || timestampNs - lastSampleTs >= SAMPLE_GATE_NS)) {
            lastSampleTs = timestampNs
            val t0 = System.nanoTime()
            // out = null: AutoScanner keeps the Grays it is given — never recycle them.
            val g = GraySampler.sampleY(p.yBuffer, p.yRowStride, sw, sh, rotation, roi)
            sampleNsTotal += System.nanoTime() - t0
            ticks++
            lastSample = g
            lastGrayDims = intArrayOf(g.w, g.h)
            val ev = scanner.tick(g)
            event = ev
            lastBox = when (ev) {
                is AutoScanner.Event.Watching -> ev.box
                is AutoScanner.Event.Trigger -> ev.box
                is AutoScanner.Event.AwaitingNext -> ev.box
                is AutoScanner.Event.NextCard -> ev.box
                else -> null
            }
            lastBoxInfo = lastBox?.let { it to roi }
            sink.onDetection(
                DetectionUpdate(ev, lastBox, g.w, g.h, uw, uh, roi, scanner.autoEnabled, scanner.hasEmptyRef, g),
            )
        }

        val trigger = event as? AutoScanner.Event.Trigger
        val needCopy = mode == ScanMode.HANDHELD || manualPending || trigger != null ||
            !scanner.autoEnabled || lastBox != null || scanner.mode != AutoScanner.Mode.WATCHING
        if (needCopy) {
            val t0 = System.nanoTime()
            ring.copyFrom(p, rotation, timestampNs)
            copyNsTotal += System.nanoTime() - t0
            copies++
        }

        if (trigger != null) {
            fire(CaptureTrigger.AUTO, trigger.box, uw, uh, AUTO_FRESH_NS)
        } else if (manualPending) {
            if (manualStartTs == NONE || timestampNs < manualStartTs) manualStartTs = timestampNs
            if (ring.countFresh(MANUAL_FRESH_NS) >= BURST ||
                timestampNs - manualStartTs >= MANUAL_WAIT_NS) {
                fire(CaptureTrigger.MANUAL, lastBox, uw, uh, MANUAL_FRESH_NS)
            }
        }
    }

    private fun fire(trigger: CaptureTrigger, box: Box?, uw: Int, uh: Int, freshNs: Long) {
        val burst = ring.snapshotLast(BURST, freshNs)
        scanner.captureDone()
        manualPending = false
        manualStartTs = NONE
        if (burst.isEmpty()) {
            runCatching { sink.onAnalyzerError(IllegalStateException("capture with no frames in the ring")) }
            return
        }
        captures++
        sink.onCaptureRequest(
            CaptureBurst(
                id = nextId++, trigger = trigger, mode = mode, frames = burst,
                cropRoi = if (mode == ScanMode.MOUNT) roi else null,
                scene = scanner.scannedFrame, box = box, uprightW = uw, uprightH = uh,
            ),
        )
    }

    // ---- commands: callable from any thread, executed on the analysis thread ----

    fun post(block: () -> Unit) {
        try { commands.execute(block) } catch (_: RejectedExecutionException) { /* shut down */ }
    }

    /** Double-tap: re-learn the empty tray. */
    fun reset() = post { scanner.reset(); lastBox = null }

    fun setAuto(enabled: Boolean) = post { scanner.setAuto(enabled) }

    /** Scan button: snapshot the scene now, capture from the ring on the next frames. */
    fun manualScan() = post {
        if (!manualPending) {
            manualPending = true
            manualStartTs = NONE
            scanner.manualScan(if (mode == ScanMode.MOUNT) lastSample else null)
        }
    }

    /** New scan area (fractions of the upright frame; null = whole frame): re-learn the tray. */
    fun setRoi(newRoi: RoiFrac?) = post {
        if (newRoi != roi) {
            roi = newRoi
            scanner.reset()
            lastSample = null; lastBox = null; lastBoxInfo = null
        }
    }

    /** Pause ticks while the user drags a new area (phone.html settingArea). */
    fun setPaused(paused: Boolean) = post { scanner.paused = paused }

    fun setMode(newMode: ScanMode) = post {
        if (newMode != mode) {
            mode = newMode
            lastBox = null; lastBoxInfo = null
            if (newMode == ScanMode.MOUNT) { scanner.reset(); lastSampleTs = NONE }
        }
    }

    /** Server said no_card: adopt the current view as empty if it still shows [capturedScene]. */
    fun onNoCard(capturedScene: Gray?) = post {
        if (mode == ScanMode.MOUNT) scanner.onNoCard(lastSample, capturedScene)
    }

    /** A (re)bind: old references are the wrong geometry. */
    fun cameraRestarted() = post {
        scanner.cameraRestarted()
        ring.clear()
        lastFrameTs = NONE; lastSampleTs = NONE
        lastSample = null; lastBox = null; lastBoxInfo = null
    }

    fun stats(): String {
        val c = copies; val t = ticks
        return buildString {
            append("frames $frames (gated $gated) · $frameDesc · ticks $t")
            if (t > 0) append(" avg sample ${"%.2f".format(sampleNsTotal / 1e6 / t)} ms")
            append(" · copies $c")
            if (c > 0) append(" avg copy ${"%.2f".format(copyNsTotal / 1e6 / c)} ms")
            append(" · captures $captures · errors $errors")
            lastError?.let { append(" (last: $it)") }
        }
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
        const val FRAME_GATE_NS = 90_000_000L
        const val SAMPLE_GATE_NS = 190_000_000L
        const val BURST = 3
        /** An auto burst only uses frames from the ~400 ms the stillness test approved. */
        const val AUTO_FRESH_NS = 400_000_000L
        const val MANUAL_FRESH_NS = 450_000_000L
        /** A manual scan fires with whatever fresh frames exist after this long (low fps). */
        const val MANUAL_WAIT_NS = 700_000_000L
    }
}
