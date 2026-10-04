package io.github.darylno.cardscanner.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.github.darylno.cardscanner.core.AutoScanner
import io.github.darylno.cardscanner.core.Box
import io.github.darylno.cardscanner.core.CardOutline
import io.github.darylno.cardscanner.core.OutlineTracker
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.DetectConst
import io.github.darylno.cardscanner.core.Detection
import io.github.darylno.cardscanner.core.Gray
import io.github.darylno.cardscanner.core.GraySampler
import io.github.darylno.cardscanner.core.Nv21Frame
import io.github.darylno.cardscanner.core.PrintEvidence
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.RoiPx
import io.github.darylno.cardscanner.core.Rotation
import io.github.darylno.cardscanner.core.TextureChange
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
 *
 * Display only (nothing the trigger uses): [outline] = the live card outline
 * found on this tick's sample ([CardOutline]), [watch] = the card-shaped watch
 * window the scanner is waiting in after a capture — both 8 floats
 * TL,TR,BR,BL as FRACTIONS of the upright frame, or null. [outlineNanos] =
 * how long the outline took on this tick (null when none was looked for).
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
    val outline: FloatArray? = null,
    val watch: FloatArray? = null,
    val outlineNanos: Long? = null,
    /** This Trigger was REFUSED by the card-shape gate (nothing shot; the scanner keeps watching). */
    val triggerRefused: Boolean = false,
    /** The camera zoom the frame was taken at ([roi] is the Area in THAT frame's fractions — the view Area). */
    val zoom: Double = 1.0,
)

/**
 * A burst handed to the capture pipeline. [frames] are private copies (time
 * order); [cropRoi] = the scan area (Mount) or the padded card guide (Handheld);
 * [scene] = the scanner's scannedFrame at capture time — hand it back to
 * [ScanAnalyzer.onNoCard] if the server answers no_card.
 */
/**
 * A held first-card Trigger asking the camera to focus on ITS card: [box] in
 * the [sampleW]×[sampleH] detection sample, which covers [roi] of the upright
 * frame (null = the whole frame). The camera meters the box's centre
 * ([io.github.darylno.cardscanner.core.FocusPoint]).
 */
class FocusRequest(val box: Box, val roi: RoiFrac?, val sampleW: Int, val sampleH: Int)

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
    /** The camera zoom the frames were taken at (the capture line's "@ z×"); [cropRoi] is in their fractions. */
    val zoom: Double = 1.0,
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
 *
 * DISPLAY + MEASUREMENT, after every decision above (owner, 2026-10-02: "see it
 * draw an outline around the card … then watch that area for new cards";
 * "can't trigger on silly things like shadows") — none of it feeds the trigger,
 * [AutoScanner] / [Detection] and phone.html's detection are untouched:
 *  4. On every tick a card may be sitting there (occupied, the Trigger tick,
 *     awaiting the next card, a different card settling) the live card outline
 *     is found on the SAME 176×MH sample ([CardOutline], ~1.3 ms on x86),
 *     smoothed across ticks with outliers dropped ([OutlineTracker]: the
 *     "much bigger card" the finder sometimes sees never moves it) and goes out
 *     in the [DetectionUpdate]. It runs inside runCatching, so nothing it does
 *     can skip fire() / captureDone() (a skipped captureDone leaves the scanner
 *     SCANNING for ever).
 *     THE ONE PLACE IT FEEDS A DECISION — the card-shape gate (owner,
 *     2026-10-02, a video of the Tray "trying to scan nothing": "should try to
 *     find a shape that matches the ratio of a magic card"): a Tray Trigger
 *     with NO outline (none this tick, none held from the last two) is REFUSED
 *     — nothing shot, [AutoScanner.triggerRefused] puts the scanner back to
 *     WATCHING so it asks again after the next steady ticks; after
 *     [RELEARN_AFTER_REFUSALS] refusals of one still scene it is adopted as
 *     the empty tray (what a wasted no_card scan used to do). The occupancy +
 *     stillness trigger itself is untouched; the gate sits after it, in the
 *     app, and only says no. A finder that THREW is unknown, not "no card",
 *     and the capture goes ahead. The shutter is never gated.
 *     THE FALLBACK (1.1.11, owner on 1.1.10: "White bordered cards didn't get
 *     picked up as cards"): before refusing, the same trigger box is asked for a
 *     card's PRINTED DETAIL ([PrintEvidence]: new texture through the box's
 *     core, a card-shaped box, a minimum share of the Area, a learned tray that
 *     was smooth there, print that stands out from round the box). A yes fires
 *     the capture without an outline; glare, shadows, exposure and the bare
 *     tray are smooth and are still refused — and still adopted after
 *     [RELEARN_AFTER_REFUSALS]; on a textured tray the fallback stands aside (a
 *     stale reference reads as print there). It can only ever say yes more
 *     often. Every refusal, fallback accept and adoption states its evidence
 *     (tag "detect").
 *  5. The WATCH WINDOW: at a capture, the padded (15 %) card polygon in sample
 *     pixels — the live outline at the Trigger tick, else the mask box; when the
 *     capture's own quad arrives ([onCaptureResult], tagged with the burst id,
 *     ignored after a newer trigger) it replaces both. A shutter scan has no box
 *     (the scanner is SCANNING / Auto off, so its ticks are Idle): its window is
 *     the capture quad alone. It is drawn while awaiting the next card.
 *  6. THE WAIT (every Tray wait, logging only, tag "detect"): while awaiting the
 *     next card each tick measures the swap test's own number — the change vs
 *     the scanned card — and whether the card sat still, plus the mask's
 *     smallest share; the wait's "wait over" line reports the maxima (so a swap
 *     that was missed shows WHY: never crossed SWAP_FRAC, never still, …), and
 *     every NextCard says "removed" or "swapped — change N %".
 *  7. SHADOW MODE (logging only, tag "shadow"): while awaiting the next card every
 *     tick measures [TextureChange] (logHP p75 + logGrad) between the sample and
 *     the scanner's scanned frame inside the window, and logs EVENTS only — the
 *     maximum per wait, each crossing of [TextureChange.START_THRESHOLD] with its
 *     duration, and "WOULD re-arm (texture)" when it holds 2 ticks — at most
 *     ~1 line/s and [SHADOW_LOG_MAX_PER_WAIT] per wait (a signal hovering at the
 *     threshold for an hour would otherwise push every capture line out of the
 *     3000-line ring); the rest are counted in the wait's "wait over" line,
 *     which also carries the texture maximum. The numbers
 *     never re-arm, trigger or change any scanner state (ScanAnalyzerTest:
 *     identical outcomes with it on and off).
 *
 * ZOOM (1.1.11, "Zoom to fit the Area" — core ZoomFit / ZoomCoordinator): [roi]
 * is always the Area in the fractions of the frames as they ARRIVE — the VIEW
 * Area at the camera's zoom [zoomMapped] says, so the sample, the capture crop,
 * the watch window and the focus points all follow the zoom with no other
 * change. While the camera's zoom changes the SETTLE GATE is closed
 * ([closeZoomGate]): no tick, no ring copy, no snapshot, no capture (a pending
 * shutter waits; a held first-card capture is dropped and asked for again;
 * while an Area is drawn at 1× only the browser's snapshot is served,
 * [snapshotsAt]). It
 * opens once the coordinator maps the ratio READ BACK from the camera and
 * [SETTLE_DROP_FRAMES] more frames (which may predate it) are dropped; the
 * ring is emptied then, so no burst ever mixes zooms. The tray is re-learned
 * only when (zoom, view Area) really changed — a re-apply after the camera
 * comes back is not a change, and a still card across a real change is learned
 * as part of the new empty tray (never scanned twice). With the zoom off none
 * of this runs: the Area arrives at 1× exactly as [setRoi] always did.
 */
class ScanAnalyzer(
    private val commands: Executor,
    private val sink: Sink,
    private val ring: FrameRing = FrameRing(4),
    private val scanner: AutoScanner = AutoScanner(),
    /** The live outline finder (display only). Injectable so a test can make it throw. */
    private val outlineFinder: (Gray, Int, Int, RoiFrac?) -> CardOutline.Outline? = CardOutline::find,
    /** SHADOW MODE: measure + log the texture-change signal while awaiting the next card. Never acts. */
    private val shadowMode: Boolean = true,
    private val log: DebugLog = DebugLog.global,
) : ImageAnalysis.Analyzer {

    /** Called on the ANALYSIS thread; implementations must not block. */
    interface Sink {
        fun onDetection(update: DetectionUpdate)
        fun onCaptureRequest(request: CaptureBurst)
        fun onAnalyzerError(error: Throwable)
        /**
         * An AUTO trigger is being held because [focusFirst] said the lens should
         * focus on this card first ([request] = where the card is). Answer with
         * [focusDone] (any outcome); the capture then uses frames taken after
         * focus settled, or fires anyway after [FOCUS_WAIT_NS].
         */
        fun onFocusRequest(request: FocusRequest) {}
        /**
         * Frames are arriving while the zoom gate is closed and nothing has
         * re-opened it (asked once per closure): the camera is evidently back —
         * the controller re-applies the zoom ([io.github.darylno.cardscanner.core.ZoomCoordinator.cameraStarted]).
         */
        fun onZoomResync() {}
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

    /**
     * Asked on every AUTO trigger (analysis thread): hold this capture until the
     * lens has focused on the card? (Mount: the first card after a bind / area
     * change / sharpness drop — see CameraController.) Every hold is logged
     * under `focus` (1.1.11): held, answered after N ms → capture #k from the
     * frames after it, TIMED OUT after [FOCUS_WAIT_NS] → shot from the last
     * frames, a late answer, or cancelled — the controller logs the pass itself.
     */
    @Volatile var focusFirst: () -> Boolean = { false }
    private var deferring = false
    /** The last hold timed out before its focus answered (its scene may be a blur). */
    private var heldTimedOut = false
    /** Re-take the scanned scene from the next sample (a late focus settled). */
    private var rebaseNext = false
    private var deferredBox: Box? = null
    private var deferStartTs = NONE
    private var focusReadyTs = NONE
    private var manualStartTs = NONE
    private var nextId = 1L

    // --- the zoom (analysis thread) ---
    /** The camera zoom the frames arrive at ([roi] is in their fractions). */
    private var zoomZ = 1.0
    /** The settle gate: frames are dropped while the camera's zoom changes. */
    private var zoomGateClosed = false
    /** Frames still to drop before the gate opens (set when the coordinator maps the read-back ratio). */
    private var zoomDropLeft = 0
    private var zoomResyncAsked = false
    private var zoomDroppedThis = 0
    /**
     * Drawing an Area while zoomed: the camera reports [snapZ] (1×) but the gate stays
     * closed (no tick, copy or capture — Cancel must come back with no re-learn); the
     * browser's picture may still be taken at [snapZ], after [snapDropLeft] frames.
     */
    private var snapZ: Double? = null
    private var snapDropLeft = 0

    // --- display + shadow-mode state (analysis thread; never read by the scanner) ---
    /**
     * The card-shaped watch window: [polygon] = the padded card quad in SAMPLE px
     * (8 doubles TL,TR,BR,BL, OpenCV pixel-centre convention) of a [w]×[h]
     * sample, [raw] = the unpadded quad it came from, [source] = outline / box /
     * capture, [burstId] = the capture it belongs to. [mask] is built once.
     */
    private class Watch(val raw: FloatArray, val source: String, val burstId: Long, val w: Int, val h: Int) {
        val polygon: DoubleArray = TextureChange.padQuad(raw)
        val mask: BooleanArray = TextureChange.polygonMaskOf(polygon, w, h)
    }
    private var watch: Watch? = null
    /** The scanned frame's prepared planes (blur, gradient), computed once per scanned frame — not every tick. */
    private var preparedRef: TextureChange.Prepared? = null
    private var preparedRefOf: Gray? = null
    /** The live outline at a HELD (focus-first) Trigger tick — the later fire() uses it. */
    private var heldTriggerQuad: FloatArray? = null
    private var lastFiredId = 0L
    /** The outline's cost on the Trigger tick, for the per-capture "outline" line. */
    private var triggerOutlineNanos = -1L

    // wait bookkeeping (one "wait" = one AWAIT_NEXT stretch) — the swap test's numbers, every Tray wait
    private var inWait = false
    private var waitTicks = 0
    /** Ticks the card sat still (vs the previous tick, the swap test's own stillness). */
    private var waitStillTicks = 0
    /** Largest change vs the scanned card (Detection.changedFrac, the swap test's number): occupied ticks / occupied AND still ticks. */
    private var waitMaxChange = 0.0
    private var waitMaxChangeStill = 0.0
    /** Smallest mask share seen (removed = below EMPTY_FRAC). */
    private var waitMinMask = 1.0
    /** The previous tick's sample (the swap test's stillness is against it). */
    private var prevTickSample: Gray? = null
    // shadow-mode texture bookkeeping, per wait
    private var textureTicks = 0
    private var waitMax = Double.NEGATIVE_INFINITY
    private var waitMaxGrad = 0.0
    private var waitMaxGrey = 0.0
    private var aboveTicks = 0
    private var abovePeak = 0.0
    private var abovePeakGrad = 0.0
    private var abovePeakGrey = 0.0
    private var lastShadowLogTs = NONE
    private var shadowSuppressed = 0
    /** Event lines written in this wait (capped at [SHADOW_LOG_MAX_PER_WAIT]). */
    private var waitLogged = 0

    // --- stats (written on the analysis thread, read racily by diagnostics) ---
    private val snapshotWaiter = java.util.concurrent.atomic.AtomicReference<((Nv21Frame, Double) -> Unit)?>(null)
    private val snapRing = FrameRing(1)

    /** Hand the NEXT analyzed frame (a copy, NV21) to [give] on the analysis thread. */
    fun requestSnapshot(give: (Nv21Frame) -> Unit) = requestSnapshotAt { f, _ -> give(f) }

    /**
     * Hand the NEXT analyzed frame and the zoom it was taken at to [give] (analysis
     * thread). Never a frame from inside the zoom settle gate: the browser's base-space
     * picture ([io.github.darylno.cardscanner.core.ZoomSnapshot]) needs the right zoom.
     */
    fun requestSnapshotAt(give: (Nv21Frame, Double) -> Unit) { snapshotWaiter.set(give) }

    @Volatile private var frames = 0L
    @Volatile private var gated = 0L
    @Volatile private var ticks = 0L
    @Volatile private var copies = 0L
    @Volatile private var copyNsTotal = 0L
    @Volatile private var sampleNsTotal = 0L
    @Volatile private var captures = 0L
    @Volatile private var errors = 0L
    @Volatile private var lastError: String? = null
    /** Smooths the live outline across ticks (display, the watch window, and the card-shape gate). */
    private val tracker = OutlineTracker()
    /** Consecutive Triggers the card-shape gate refused on the current still scene. */
    private var refusals = 0
    /** The highest printed-detail share among this scene's refusals (for the adoption line). */
    private var refusalPeakPrint = 0.0
    @Volatile private var triggersRefused = 0L
    /** Triggers with no outline that the printed-detail fallback let through. */
    @Volatile private var printAccepts = 0L
    @Volatile private var logErrors = 0L
    @Volatile private var lastLogError: String? = null
    @Volatile private var outlineRuns = 0L
    @Volatile private var outlinesFound = 0L
    @Volatile private var outlineNsTotal = 0L
    @Volatile private var outlineErrors = 0L
    @Volatile private var lastOutlineError: String? = null
    @Volatile private var zoomDropped = 0L
    @Volatile private var zoomClosures = 0L
    @Volatile private var zoomChanges = 0L
    /** The zoom ticks are mapped at now (Diagnostics; written on the analysis thread). */
    @Volatile var currentZoom = 1.0
        private set
    @Volatile private var textureRuns = 0L
    @Volatile private var textureNsTotal = 0L
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
        // THE ZOOM SETTLE GATE: while the camera's zoom changes, a frame may be at the old
        // zoom or the new one — nothing looks at it (no tick, copy, snapshot or capture;
        // drawing an Area at 1×, a snapshot only).
        if (zoomGateClosed) {
            zoomDropped++; zoomDroppedThis++
            if (zoomDropLeft > 0) {
                if (--zoomDropLeft == 0) {
                    zoomGateClosed = false
                    safeLog("zoom") { "analyzer resumed at %.2f× — %d frame(s) dropped while the zoom settled".format(zoomZ, zoomDroppedThis) }
                }
            } else if (!zoomResyncAsked) {
                zoomResyncAsked = true
                runCatching { sink.onZoomResync() }
            }
            // Drawing an Area at 1× ([snapshotsAt]): the picture of the tray is still served.
            val sz = snapZ
            if (sz != null) {
                if (snapDropLeft > 0) snapDropLeft-- else serveSnapshot(p, rotation, timestampNs, sz)
            }
            return
        }
        // A browser asked for a picture of the tray (scan-Area drawing): hand over this frame.
        serveSnapshot(p, rotation, timestampNs, zoomZ)
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
        var tickSample: Gray? = null
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
            if (rebaseNext) {
                rebaseNext = false
                if (scanner.mode == AutoScanner.Mode.AWAIT_NEXT) scanner.rebaseScene(g)
            }
            val ev = scanner.tick(g)
            event = ev
            tickSample = g
            lastBox = when (ev) {
                is AutoScanner.Event.Watching -> ev.box
                is AutoScanner.Event.Trigger -> ev.box
                is AutoScanner.Event.AwaitingNext -> ev.box
                is AutoScanner.Event.NextCard -> ev.box
                else -> null
            }
            lastBoxInfo = lastBox?.let { it to roi }
        }

        // The live outline for this tick — BEFORE the trigger decision, because the
        // Tray trigger now asks it whether a card shape is there at all (below).
        // Every tick a card may be sitting there: occupied, the trigger, awaiting
        // the next card, a different card settling — so the card stays highlighted.
        val ev0 = event
        val g0 = tickSample
        var outline: CardOutline.Outline? = null
        var outlineNs: Long? = null
        var outlineThrew = false
        if (ev0 != null && g0 != null) {
            val wantOutline = ev0 is AutoScanner.Event.Trigger ||
                (ev0 is AutoScanner.Event.Watching && ev0.occupied) ||
                ev0 is AutoScanner.Event.AwaitingNext ||
                (ev0 is AutoScanner.Event.NextCard && !ev0.removed)
            if (wantOutline) {
                val t0 = System.nanoTime()
                val raw = runCatching { outlineFinder(g0, uw, uh, roi) }
                    .onFailure { outlineThrew = true; outlineErrors++; lastOutlineError = it.toString() }
                    .getOrNull()
                // Smoothed across ticks, outliers dropped (OutlineTracker) — in sample px, then
                // back to frame fractions the same way a raw find goes.
                outline = runCatching { smooth(raw, g0, uw, uh) }
                    .onFailure { outlineThrew = true; outlineErrors++; lastOutlineError = it.toString() }
                    .getOrNull()
                outlineNs = System.nanoTime() - t0
                outlineRuns++
                outlineNsTotal += outlineNs
                if (raw != null) outlinesFound++
            } else {
                tracker.reset()
            }
            if (!(ev0 is AutoScanner.Event.Watching && ev0.occupied) && ev0 !is AutoScanner.Event.Trigger) refusals = 0
        }

        var trigger = event as? AutoScanner.Event.Trigger
        var triggerRefused = false
        if (trigger != null && mode == ScanMode.MOUNT && outline == null && !outlineThrew && g0 != null) {
            // THE CARD-SHAPE GATE (owner, 2026-10-02, a video of the tray "trying to
            // scan nothing"): the mask says something still is in the Area, but no
            // card-shaped, card-textured quad (CardQuad: ratio 1.15–1.75, rectangular,
            // printed interior) was found on this tick or held from the last two —
            // glare, a shadow, a hand's edge. A finder that THREW is "unknown", not
            // "no card": the capture goes ahead as before.
            // THE FALLBACK (1.1.11): the finder can miss a real card (owner: white-
            // bordered cards on 1.1.10). Before refusing, ask the trigger box itself
            // for a card's printed detail — measured, it separates every synthetic
            // card from glare / shadow / exposure / smooth hands — but only where the
            // learned tray was smooth and the print stands out from round the box: on
            // a textured tray a stale reference (learned with a card on it, out of
            // focus, a light under AE lock, a nudged mat) reads as print (review of
            // 1.1.11), and there the fallback stands aside. A failure to measure is
            // "no evidence": refused as before.
            val b = trigger.box
            val ev = runCatching { scanner.emptyGradient()?.let { PrintEvidence.measure(g0, it, b) } }.getOrNull()
            val where = { "box ${b.x},${b.y} ${b.w}×${b.h} of ${g0.w}×${g0.h}, mask %.1f%%".format(b.maskFrac * 100) }
            if (ev != null && ev.looksLikeACard) {
                printAccepts++
                detectLog {
                    ("TRIGGER accepted without an outline — printed detail %.0f%% (need %.0f%%) in a card-shaped box %.2f, %.0f%% of the Area · " +
                        "learned tray smooth there (texture %.0f%%) · %s · %s")
                        .format(ev.print * 100, PrintEvidence.MIN_PRINT * 100, ev.aspect, ev.share * 100, ev.trayTexture * 100,
                            ev.ring?.let { "ring round the box %.0f%%".format(it * 100) } ?: "no ring (the box fills the Area)", where())
                }
            } else {
                triggerRefused = true
                triggersRefused++
                refusals++
                scanner.triggerRefused()
                if (refusals == 1) refusalPeakPrint = 0.0
                if (ev != null) refusalPeakPrint = maxOf(refusalPeakPrint, ev.print)
                val evidence = { ev?.let { "${whyNot(it)} · ${it.describe()}" } ?: "printed detail not measured" }
                if (refusals == 1) detectLog { "TRIGGER refused — no card shape · ${evidence()} · ${where()} · still watching" }
                if (refusals >= RELEARN_AFTER_REFUSALS) {
                    scanner.adoptEmpty(g0)
                    detectLog {
                        val peak = "%.0f%%".format(refusalPeakPrint * 100)
                        "no card shape after $refusals refused triggers — adopted the view as the empty tray · last: ${evidence()} · print peaked at $peak over the $refusals"
                    }
                    refusals = 0
                }
                trigger = null
            }
        }

        val needCopy = mode == ScanMode.HANDHELD || manualPending || trigger != null ||
            !scanner.autoEnabled || lastBox != null || scanner.mode != AutoScanner.Mode.WATCHING
        if (needCopy) {
            val t0 = System.nanoTime()
            ring.copyFrom(p, rotation, timestampNs)
            copyNsTotal += System.nanoTime() - t0
            copies++
        }

        if (trigger != null) {
            if (mode == ScanMode.MOUNT && runCatching { focusFirst() }.getOrDefault(false)) {
                // Focus on THIS card before shooting it (once per session / area):
                // the capture is held, and taken from frames newer than the focus.
                deferring = true
                deferredBox = trigger.box
                deferStartTs = timestampNs
                focusReadyTs = NONE
                val b = trigger.box
                val sw0 = g0?.w ?: lastGrayDims?.get(0) ?: 0
                val sh0 = g0?.h ?: lastGrayDims?.get(1) ?: 0
                safeLog("focus") {
                    "first card: Trigger held (≤ %.1f s) for a focus pass on the card — box %d,%d %d×%d of %d×%d"
                        .format(FOCUS_WAIT_NS / 1e9, b.x, b.y, b.w, b.h, sw0, sh0)
                }
                runCatching { sink.onFocusRequest(FocusRequest(b, roi, sw0, sh0)) }
            } else {
                fire(CaptureTrigger.AUTO, trigger.box, uw, uh, AUTO_FRESH_NS)
            }
        } else if (deferring) {
            val sinceFocus = if (focusReadyTs == NONE || timestampNs <= focusReadyTs) -1L
                else timestampNs - focusReadyTs - 1
            val focused = sinceFocus >= 0 && ring.countFresh(sinceFocus) >= BURST
            if (focused || timestampNs - deferStartTs >= FOCUS_WAIT_NS || timestampNs < deferStartTs) {
                // The lens moved since the trigger: "what was scanned" becomes
                // THIS frame (the burst's newest), not the pre-focus trigger scene.
                runCatching {
                    scanner.rebaseScene(GraySampler.sampleY(p.yBuffer, p.yRowStride, sw, sh, rotation, roi))
                }
                // focus never answered → shoot anyway, from the usual fresh window
                heldTimedOut = !focused
                val heldMs = (timestampNs - deferStartTs) / 1_000_000
                val answeredMs = if (focusReadyTs == NONE) -1L else (focusReadyTs - deferStartTs) / 1_000_000
                val clockReset = timestampNs < deferStartTs
                val id = nextId
                fire(CaptureTrigger.AUTO, deferredBox, uw, uh, if (focused) sinceFocus else AUTO_FRESH_NS)
                val shot = if (nextId > id) "capture #$id" else "no capture (no frames)"
                safeLog("focus") {
                    when {
                        focused -> "first card: focus answered $answeredMs ms after the Trigger — $shot shot from the $BURST frames after it ($heldMs ms after the Trigger)"
                        clockReset -> "first card: focus hold ended — the camera's clock restarted; $shot shot from the last frames"
                        answeredMs >= 0 -> "first card: focus hold timed out after %.1f s — focus answered after $answeredMs ms but fewer than $BURST frames came after it; $shot shot from the last frames"
                            .format(FOCUS_WAIT_NS / 1e9)
                        else -> "first card: focus hold timed out after %.1f s — no focus answer; $shot shot from the last frames (the lens may still have been moving)"
                            .format(FOCUS_WAIT_NS / 1e9)
                    }
                }
            }
        } else if (manualPending) {
            if (manualStartTs == NONE || timestampNs < manualStartTs) manualStartTs = timestampNs
            // Frames from AFTER the tap has settled: the ring's newest frames at
            // tap time are the press itself (a jolt → handheld motion blur).
            val sinceTap = timestampNs - (manualStartTs + MANUAL_SETTLE_NS) - 1
            if (sinceTap >= 0 && ring.countFresh(sinceTap) >= BURST) {
                fire(CaptureTrigger.MANUAL, lastBox, uw, uh, sinceTap)
            } else if (timestampNs - manualStartTs >= MANUAL_WAIT_NS) {
                fire(CaptureTrigger.MANUAL, lastBox, uw, uh, MANUAL_FRESH_NS)
            }
        }

        // ---- display + shadow mode: AFTER every decision above, and never able to skip one ----
        val ev = event
        val g = tickSample
        if (ev != null && g != null) {
            if (!triggerRefused) {
                runCatching { instrument(ev, g, outline, outlineNs, timestampNs) }
                    .onFailure { outlineErrors++; lastOutlineError = it.toString() }
            }
            prevTickSample = g
            val wf = runCatching { watchFractions(uw, uh) }.getOrNull()
            sink.onDetection(
                DetectionUpdate(ev, lastBox, g.w, g.h, uw, uh, roi, scanner.autoEnabled, scanner.hasEmptyRef, g,
                    outline?.quad, wf, outlineNs, triggerRefused, zoomZ),
            )
        }
    }

    /** Hand this frame to a waiting snapshot request, taken at zoom [z]. */
    private fun serveSnapshot(p: YuvPlanes, rotation: Int, timestampNs: Long, z: Double) {
        snapshotWaiter.getAndSet(null)?.let { give ->
            snapRing.copyFrom(p, rotation, timestampNs)
            runCatching { give(snapRing.snapshotLast(1).first(), z) }
        }
    }

    // ---- the watch window + shadow mode (display / log only) ----

    /** [raw] through the tracker: the outline to show, as an Outline in the same spaces, or null. */
    private fun smooth(raw: CardOutline.Outline?, g: Gray, uw: Int, uh: Int): CardOutline.Outline? {
        val sq = tracker.update(raw?.sampleQuad) ?: return null
        if (raw != null && sq.contentEquals(raw.sampleQuad)) return raw
        val area = roi?.toPixels(uw, uh) ?: RoiPx(0, 0, uw, uh)
        val ns = raw?.nanos ?: 0L
        return CardOutline.Outline(CardOutline.toFrameFractions(sq, g.w, g.h, area, uw, uh), sq, ns / 1_000_000, ns)
    }

    /** The watch window as frame fractions for the overlay, or null. */
    private fun watchFractions(uw: Int, uh: Int): FloatArray? {
        val wt = watch ?: return null
        val area = roi?.toPixels(uw, uh) ?: RoiPx(0, 0, uw, uh)
        return CardOutline.toFrameFractions(FloatArray(8) { wt.polygon[it].toFloat() }, wt.w, wt.h, area, uw, uh)
    }

    /** The mask box as a quad in sample px (pixel-centre convention: the box covers pixels x..x+w-1). */
    private fun boxQuad(b: Box): FloatArray {
        val x0 = b.x - 0.5f; val y0 = b.y - 0.5f; val x1 = b.x + b.w - 0.5f; val y1 = b.y + b.h - 0.5f
        return floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
    }

    private fun setWatch(raw: FloatArray?, source: String, burstId: Long) {
        val g = lastSample
        watch = if (raw == null || g == null) null else Watch(raw, source, burstId, g.w, g.h)
    }

    /** fire(): the window for burst [id] — the held trigger's outline, else the mask box. */
    private fun armWatch(id: Long, box: Box?) {
        val held = heldTriggerQuad
        heldTriggerQuad = null
        lastFiredId = id
        if (held != null) setWatch(held, "outline", id) else setWatch(box?.let(::boxQuad), "box", id)
    }

    /** Reset / new Area / mode change / camera restart: no burst owns a window any more. */
    private fun clearWatch(why: String) {
        if (inWait) endWait(why)
        prevTickSample = null
        watch = null; heldTriggerQuad = null; lastFiredId = 0L
        preparedRef = null; preparedRefOf = null
        tracker.reset()
    }

    /** Per tick, after the decisions: the watch window upkeep, the wait's swap numbers and the shadow-mode measurement. */
    private fun instrument(ev: AutoScanner.Event, g: Gray, outline: CardOutline.Outline?, outlineNs: Long?, ts: Long) {
        when (ev) {
            is AutoScanner.Event.Trigger -> {
                triggerOutlineNanos = outlineNs ?: -1L
                if (deferring) {
                    // Held for focus: fire() comes on a later frame and takes this.
                    heldTriggerQuad = outline?.sampleQuad
                } else if (outline != null && watch?.burstId == lastFiredId) {
                    // Fired this frame from the mask box: the outline is the better window.
                    setWatch(outline.sampleQuad, "outline", lastFiredId)
                }
            }
            is AutoScanner.Event.AwaitingNext -> {
                trackWait(ev.box, g)
                if (shadowMode) measureTexture(g, ts)
            }
            is AutoScanner.Event.NextCard -> {
                logNextCard(ev, g)
                // The deciding tick belongs to the wait's numbers (a swap's change, a removal's empty mask).
                if (inWait) { trackWait(ev.box, g); endWait(if (ev.removed) "card removed" else "a different card settled") }
            }
            else -> if (inWait) endWait("no longer waiting (${ev.javaClass.simpleName})")
        }
    }

    /** The scanned scene, when it is comparable with [g] (same sample geometry). */
    private fun scannedLike(g: Gray): Gray? = scanner.scannedFrame?.takeIf { it.w == g.w && it.h == g.h }

    /**
     * Every Tray wait, every tick (at most two passes over the 176×MH sample):
     * the swap test's own number — the change vs the scanned card — on the ticks
     * something occupied the Area (a swap needs that; the tick a card is lifted
     * away would only inflate it) and on those of them where it also sat still vs
     * the previous tick (where a swap could have fired); the still ticks; and
     * the smallest mask share (removed = below EMPTY_FRAC). Reported once, in the
     * wait's "wait over" line.
     */
    private fun trackWait(box: Box?, g: Gray) {
        if (!inWait) startWait()
        waitTicks++
        val mask = box?.maskFrac ?: 0.0
        if (mask < waitMinMask) waitMinMask = mask
        val prev = prevTickSample?.takeIf { it.w == g.w && it.h == g.h }
        val still = prev != null && Detection.changedFrac(g, prev) < DetectConst.STABLE_FRAC
        if (still) waitStillTicks++
        if (mask <= DetectConst.OCCUPIED_FRAC) return
        val ref = scannedLike(g) ?: return
        val change = Detection.changedFrac(g, ref)
        if (change > waitMaxChange) waitMaxChange = change
        if (still && change > waitMaxChangeStill) waitMaxChangeStill = change
    }

    private fun startWait() {
        inWait = true
        waitTicks = 0; waitStillTicks = 0; waitMaxChange = 0.0; waitMaxChangeStill = 0.0; waitMinMask = 1.0
        textureTicks = 0; waitMax = Double.NEGATIVE_INFINITY; aboveTicks = 0; abovePeak = 0.0; shadowSuppressed = 0; waitLogged = 0
    }

    /** One line per NextCard: why the scanner moved on. */
    private fun logNextCard(ev: AutoScanner.Event.NextCard, g: Gray) {
        val mask = ev.box?.maskFrac ?: 0.0
        if (ev.removed) {
            detectLog { "next card: removed — mask %.1f%% (< %.1f%%) · watching".format(mask * 100, DetectConst.EMPTY_FRAC * 100) }
        } else {
            detectLog {
                // The change goes in as an argument: a "%" pasted into a format string throws.
                val change = scannedLike(g)?.let { "%.1f%%".format(Detection.changedFrac(g, it) * 100) } ?: "?"
                "next card: swapped — change %s vs the scanned card (needs > %.0f%%), still, mask %.1f%%"
                    .format(change, DetectConst.SWAP_FRAC * 100, mask * 100)
            }
        }
    }

    /** SHADOW MODE: the texture-change signal inside the watch window (logs events only). */
    private fun measureTexture(g: Gray, ts: Long) {
        val wt = watch ?: return
        val ref = scanner.scannedFrame ?: return
        if (wt.w != g.w || wt.h != g.h || ref.w != g.w || ref.h != g.h) return
        val t0 = System.nanoTime()
        if (preparedRefOf !== ref) { preparedRef = TextureChange.prepare(ref); preparedRefOf = ref }
        val sig = TextureChange.measure(g, preparedRef!!, wt.mask) ?: return
        textureNsTotal += System.nanoTime() - t0
        textureRuns++
        textureTicks++
        var sum = 0L
        for (v in g.px) sum += v
        val grey = sum.toDouble() / g.size
        if (sig.logHpP75 > waitMax) { waitMax = sig.logHpP75; waitMaxGrad = sig.logGrad; waitMaxGrey = grey }
        if (sig.logHpP75 >= TextureChange.START_THRESHOLD) {
            aboveTicks++
            if (sig.logHpP75 > abovePeak) { abovePeak = sig.logHpP75; abovePeakGrad = sig.logGrad; abovePeakGrey = grey }
            if (aboveTicks == REARM_TICKS) {
                shadowLog(ts, "WOULD re-arm (texture): logHP p75 %.1f ≥ %.0f for %d ticks (logGrad %.1f, mean grey %.0f, %s window of %d blocks)"
                    .format(abovePeak, TextureChange.START_THRESHOLD, REARM_TICKS, abovePeakGrad, abovePeakGrey, wt.source, sig.blocks))
            }
        } else if (aboveTicks > 0) {
            shadowLog(ts, "crossed %.0f: logHP p75 peak %.1f for %d tick(s) (logGrad %.1f, mean grey %.0f) — now %.1f"
                .format(TextureChange.START_THRESHOLD, abovePeak, aboveTicks, abovePeakGrad, abovePeakGrey, sig.logHpP75))
            aboveTicks = 0; abovePeak = 0.0
        }
    }

    /**
     * The wait's one summary line (tag "detect", never rate-limited — once per
     * wait): the swap test's numbers, so a missed swap shows why, plus the
     * shadow-mode texture maximum when it ran.
     */
    private fun endWait(why: String) {
        inWait = false
        if (waitTicks == 0) return
        detectLog {
            val swap = "change vs the scanned card max %.1f%%, %.1f%% while a card sat still (a swap needs > %.0f%% while still) · still %d/%d ticks · mask min %.1f%% (removed < %.1f%%)"
                .format(waitMaxChange * 100, waitMaxChangeStill * 100, DetectConst.SWAP_FRAC * 100, waitStillTicks, waitTicks,
                    waitMinMask * 100, DetectConst.EMPTY_FRAC * 100)
            val texture = if (textureTicks == 0) "" else {
                val open = if (aboveTicks > 0) ", still ≥ %.0f for %d tick(s) at the end (peak %.1f)".format(TextureChange.START_THRESHOLD, aboveTicks, abovePeak) else ""
                val dropped = if (shadowSuppressed > 0) ", $shadowSuppressed shadow event line(s) held back (1/s, $SHADOW_LOG_MAX_PER_WAIT per wait)" else ""
                " · texture over %d ticks: max logHP p75 %.1f (logGrad %.1f, mean grey %.0f)%s%s"
                    .format(textureTicks, waitMax, waitMaxGrad, waitMaxGrey, open, dropped)
            }
            "wait over (%s) after %d ticks: %s%s".format(why, waitTicks, swap, texture)
        }
        waitTicks = 0; textureTicks = 0; aboveTicks = 0; abovePeak = 0.0; shadowSuppressed = 0; waitLogged = 0
    }

    /**
     * A "detect" line that can never throw into the decisions (fire() / captureDone()
     * must always run). A line that failed to build is counted in [stats], not lost silently.
     */
    private inline fun detectLog(msg: () -> String) = safeLog("detect", msg)

    /** Any analyzer log line, built and written so that it can never throw into the decisions. */
    private inline fun safeLog(tag: String, msg: () -> String) {
        runCatching { log.i(tag, msg()) }.onFailure { logErrors++; lastLogError = it.toString() }
    }

    /** A command dropped a held first-card capture (nothing is shot for it). */
    private fun cancelHold(why: String) {
        if (!deferring) return
        deferring = false
        safeLog("focus") { "first card: focus hold cancelled ($why) — nothing shot" }
    }

    /** Which of the fallback's three tests failed, in words. */
    private fun whyNot(e: PrintEvidence.Evidence): String = listOfNotNull(
        if (!e.printed) "no printed detail" else null,
        if (!e.cardShaped) "box not card-shaped" else null,
        if (!e.bigEnough) "box too small" else null,
        // A textured tray: "new texture" may be the tray itself against a stale reference.
        if (!e.trayClean) "the learned tray is textured there" else null,
        if (!e.standsOut) "no more textured than round the box" else null,
    ).joinToString(", ").ifEmpty { "evidence ok" }

    /**
     * Shadow-mode event lines: at most one per [SHADOW_LOG_GAP_NS] and
     * [SHADOW_LOG_MAX_PER_WAIT] per wait (the ring holds 3000 lines; ticks come
     * 5×/s — a signal flickering about the threshold through a long idle wait
     * logged up to 3,600 lines/hour and evicted the captures it was measuring).
     * The held-back count goes in the "wait over" line.
     */
    private fun shadowLog(ts: Long, msg: String) {
        if (waitLogged >= SHADOW_LOG_MAX_PER_WAIT ||
            (lastShadowLogTs != NONE && ts >= lastShadowLogTs && ts - lastShadowLogTs < SHADOW_LOG_GAP_NS)) {
            shadowSuppressed++
            return
        }
        waitLogged++
        lastShadowLogTs = ts
        log.i("shadow", msg)
    }

    /**
     * The capture pipeline's answer for burst [burstId]: its [quad] (TL,TR,BR,BL
     * in upright-frame pixels of a [frameW]×[frameH] frame) becomes the watch
     * window, mapped through the Area into sample px — unless a newer trigger
     * has fired since (its window must not be overwritten by an old capture's)
     * or the window was cleared (reset / new Area / mode change / camera
     * restart). A shutter scan fires with no box, so it has no window until
     * now: the capture quad is its first. Logs one "outline" line per capture:
     * the card's height in frame px and how the live outline compared.
     */
    fun onCaptureResult(burstId: Long, quad: FloatArray?, frameW: Int, frameH: Int) = post {
        runCatching {
            val q = quad?.takeIf { it.size == 8 && frameW > 0 && frameH > 0 }
            val size = q?.let { " · card %.0f px tall".format(cardHeightPx(it)) } ?: " · no card quad in the capture"
            val wt = watch
            if (wt != null && wt.burstId != burstId) {
                // One pipeline time late: a newer trigger owns the window now.
                log.i("outline", "capture #$burstId$size · quad arrived after burst #${wt.burstId} took the window — ignored")
                return@post
            }
            val g = lastSample
            if (burstId != lastFiredId || g == null) {
                log.i("outline", "capture #$burstId$size · the watch window was cleared since (reset / new Area / camera) — ignored")
                return@post
            }
            if (q == null) {
                log.i("outline", "capture #$burstId$size — " +
                    (if (wt != null) "window stays from the ${wt.source}" else "no watch window (shutter scan, no box at the trigger)"))
                return@post
            }
            val area = roi?.toPixels(frameW, frameH) ?: RoiPx(0, 0, frameW, frameH)
            val kx = area.w.toDouble() / g.w; val ky = area.h.toDouble() / g.h
            // Inverse of CardOutline.toFrameFractions: frame pixel-centre X → sample pixel-centre x.
            val sq = FloatArray(8) {
                if (it % 2 == 0) ((q[it] + 0.5 - area.x) / kx - 0.5).toFloat() else ((q[it] + 0.5 - area.y) / ky - 0.5).toFloat()
            }
            val k = (kx + ky) / 2
            val note = when {
                wt == null -> "no window at the trigger (shutter scan, no box)"
                wt.source == "outline" -> {
                    val d = (0 until 4).maxOf { Math.hypot((wt.raw[2 * it] - sq[2 * it]).toDouble(), (wt.raw[2 * it + 1] - sq[2 * it + 1]).toDouble()) }
                    "live outline Δ ≤ %.1f frame px (%.2f sample px) from the capture quad, found in %.1f ms".format(d * k, d, triggerOutlineNanos / 1e6)
                }
                else -> "no live outline at the trigger (window was the mask box)"
            }
            setWatch(sq, "capture", burstId)
            log.i("outline", "capture #$burstId: $note$size · window from the capture quad")
        }.onFailure { outlineErrors++; lastOutlineError = it.toString() }
    }

    /** The card's height in frame px: the mean of its two long sides (TL→BL, TR→BR). */
    private fun cardHeightPx(q: FloatArray): Double =
        (Math.hypot((q[6] - q[0]).toDouble(), (q[7] - q[1]).toDouble()) +
            Math.hypot((q[4] - q[2]).toDouble(), (q[5] - q[3]).toDouble())) / 2

    private fun fire(trigger: CaptureTrigger, box: Box?, uw: Int, uh: Int, freshNs: Long) {
        val burst = ring.snapshotLast(BURST, freshNs)
        deferring = false; deferredBox = null
        refusals = 0
        scanner.captureDone()
        manualPending = false
        manualStartTs = NONE
        if (burst.isEmpty()) {
            runCatching { sink.onAnalyzerError(IllegalStateException("capture with no frames in the ring")) }
            return
        }
        captures++
        val id = nextId++
        // Display / shadow mode only — after the scanner's own bookkeeping, and never able to throw past it.
        runCatching { armWatch(id, box) }.onFailure { outlineErrors++; lastOutlineError = it.toString() }
        sink.onCaptureRequest(
            CaptureBurst(
                id = id, trigger = trigger, mode = mode, frames = burst,
                cropRoi = if (mode == ScanMode.MOUNT) roi else HandheldGuide.frac(uw, uh),
                scene = scanner.scannedFrame, box = box, uprightW = uw, uprightH = uh, zoom = zoomZ,
            ),
        )
    }

    // ---- commands: callable from any thread, executed on the analysis thread ----

    fun post(block: () -> Unit) {
        try { commands.execute(block) } catch (_: RejectedExecutionException) { /* shut down */ }
    }

    /** Double-tap: re-learn the empty tray. */
    fun reset() = post { scanner.reset(); lastBox = null; cancelHold("re-learn"); refusals = 0; clearWatch("reset") }

    /** The focus asked for by [Sink.onFocusRequest] finished (success or not). */
    fun focusDone() = post {
        if (deferring && focusReadyTs == NONE) focusReadyTs = maxOf(lastFrameTs, deferStartTs)
        // The hold gave up waiting and shot while the lens was still moving: the
        // scene it kept is a blur. Now the lens has settled, re-take it.
        else if (!deferring && heldTimedOut) {
            heldTimedOut = false; rebaseNext = true
            safeLog("focus") { "first card: focus answered after the hold gave up — the scanned scene is re-taken from the next sample" }
        }
    }

    fun setAuto(enabled: Boolean) = post { scanner.setAuto(enabled) }

    /** Scan button: snapshot the scene now, capture from the ring on the next frames. */
    fun manualScan() = post {
        if (!manualPending) {
            manualPending = true
            manualStartTs = NONE
            scanner.manualScan(if (mode == ScanMode.MOUNT) lastSample else null)
        }
    }

    /** New scan area (fractions of the frames as they arrive; null = whole frame): re-learn the tray. */
    fun setRoi(newRoi: RoiFrac?) = post { applyMapping(zoomZ, newRoi, settle = false, why = "new Area") }

    /**
     * The zoom coordinator's mapping: frames arrive at [ratio] and the Area is [view]
     * (fractions of THOSE frames). Re-learns the tray only when (ratio, view) changed —
     * exactly [setRoi]'s re-learn, plus the ring emptied on a new ratio. [settle] = the
     * gate is closed for a camera change: drop [SETTLE_DROP_FRAMES] more frames (they may
     * predate the zoom), then open. With the zoom off the controller sends (1.0, the Area,
     * no settle): the pre-zoom setRoi path, bit for bit.
     */
    fun zoomMapped(ratio: Double, view: RoiFrac?, settle: Boolean, why: String) = post { applyMapping(ratio, view, settle, why) }

    private fun applyMapping(ratio: Double, view: RoiFrac?, settle: Boolean, why: String) {
        snapZ = null
        val newRatio = ratio != zoomZ
        if (newRatio || view != roi) {
            roi = view
            zoomZ = ratio
            currentZoom = ratio
            scanner.reset()
            cancelHold(if (newRatio) "zoom change" else "new Area")
            if (newRatio) { rebaseNext = false; heldTimedOut = false; zoomChanges++ }
            lastSample = null; lastBox = null; lastBoxInfo = null
            clearWatch(if (newRatio) "zoom change" else "new area")
            if (newRatio) safeLog("zoom") { "frames now at %.2f× ($why) — the Area is %s of the zoomed frame; re-learning the empty tray"
                .format(ratio, view?.encode() ?: "the whole frame") }
        }
        if (settle || newRatio) ring.clear()          // no burst ever mixes zooms
        if (settle && zoomGateClosed) {
            zoomDropLeft = SETTLE_DROP_FRAMES
            zoomResyncAsked = true                     // settling: frames are expected, not a reason to ask
        }
    }

    /**
     * The camera's zoom is about to change (or the camera went away while zoomed):
     * drop every frame until [zoomMapped] settles it. A pending shutter waits (its
     * settle time restarts); a held first-card capture is dropped and the card asked
     * about again on its next steady ticks (never left SCANNING).
     */
    fun closeZoomGate(why: String) = post {
        if (!zoomGateClosed) {
            zoomGateClosed = true
            zoomClosures++
            zoomDroppedThis = 0
            safeLog("zoom") { "analyzer paused — $why" }
        }
        zoomDropLeft = 0
        zoomResyncAsked = false
        snapZ = null
        manualStartTs = NONE
        if (deferring) {
            cancelHold("the camera's zoom is changing")
            scanner.triggerRefused()
        }
    }

    /**
     * Drawing an Area while zoomed: the camera reports [ratio] now (1×). The gate stays
     * closed — no tick, ring copy or capture, the mapping and the empty tray kept, so
     * Cancel comes back with no re-learn — but a browser's snapshot request is served
     * again, at [ratio], once [SETTLE_DROP_FRAMES] frames that may predate it are
     * dropped (before, `snapshot.jpg` answered 503 for the whole drawing). Undone by the
     * next [closeZoomGate] / [zoomMapped].
     */
    fun snapshotsAt(ratio: Double) = post {
        if (snapZ != ratio) {
            snapZ = ratio
            snapDropLeft = SETTLE_DROP_FRAMES
        }
    }

    /** Pause ticks while the user drags a new area (phone.html settingArea). */
    fun setPaused(paused: Boolean) = post { scanner.paused = paused }

    fun setMode(newMode: ScanMode) = post {
        if (newMode != mode) {
            mode = newMode
            lastBox = null; lastBoxInfo = null
            cancelHold("mode change")
            if (newMode == ScanMode.MOUNT) { scanner.reset(); lastSampleTs = NONE }
            clearWatch("mode change")
        }
    }

    /** Server said no_card: adopt the current view as empty if it still shows [capturedScene]. */
    fun onNoCard(capturedScene: Gray?) = post {
        if (mode == ScanMode.MOUNT) scanner.onNoCard(lastSample, capturedScene)
    }

    /** A (re)bind: old references are the wrong geometry. */
    fun cameraRestarted() = post {
        // A rebind mid-hold: the held capture will never fire, so the scanner
        // would stay SCANNING (capturing, owed a captureDone) — Auto dead.
        if (deferring) scanner.reset()
        cancelHold("camera restarted")
        rebaseNext = false; heldTimedOut = false
        scanner.cameraRestarted()
        ring.clear()
        clearWatch("camera restarted")
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
            val o = outlineRuns
            if (o > 0) append(" · outlines $outlinesFound/$o avg outline ${"%.2f".format(outlineNsTotal / 1e6 / o)} ms" +
                " · ${tracker.outliers} outlier(s) dropped")
            if (triggersRefused > 0) append(" · triggers refused (no card shape) $triggersRefused")
            if (printAccepts > 0) append(" · accepted on printed detail (no outline) $printAccepts")
            if (logErrors > 0) append(" · log line errors (detect/focus) $logErrors (last: $lastLogError)")
            if (outlineErrors > 0) append(" · outline errors $outlineErrors (last: $lastOutlineError)")
            val x = textureRuns
            if (x > 0) append(" · texture $x avg ${"%.2f".format(textureNsTotal / 1e6 / x)} ms")
            if (zoomClosures > 0 || currentZoom != 1.0) append(" · zoom %.2f× (%d change(s), gate closed %d time(s), %d frame(s) dropped settling)"
                .format(currentZoom, zoomChanges, zoomClosures, zoomDropped))
        }
    }

    companion object {
        /** Refused Triggers of one still scene before it is adopted as the empty tray (~2 s at 5 ticks/s). */
        const val RELEARN_AFTER_REFUSALS = 5

        private const val NONE = Long.MIN_VALUE
        const val FRAME_GATE_NS = 90_000_000L
        const val SAMPLE_GATE_NS = 190_000_000L
        const val BURST = 3
        /** An auto burst only uses frames from the ~400 ms the stillness test approved. */
        const val AUTO_FRESH_NS = 400_000_000L
        const val MANUAL_FRESH_NS = 450_000_000L
        /** A manual scan fires with whatever fresh frames exist after this long (low fps). */
        const val MANUAL_WAIT_NS = 700_000_000L
        /** Let the tap's jolt pass before the frames a manual scan uses. */
        const val MANUAL_SETTLE_NS = 150_000_000L
        /** Frames dropped after the zoom is read back before the gate opens (they may predate it). */
        const val SETTLE_DROP_FRAMES = 2
        /** Longest a first-card capture waits for focus before shooting anyway. */
        const val FOCUS_WAIT_NS = 1_500_000_000L
        /** Shadow mode: "WOULD re-arm" when logHP p75 ≥ START_THRESHOLD this many ticks running (~0.4 s). */
        const val REARM_TICKS = 2
        /** Shadow mode: at most one event line per this (3000-line ring, 5 ticks/s)… */
        const val SHADOW_LOG_GAP_NS = 1_000_000_000L
        /** …and this many per wait; the rest are counted in the wait's summary line. */
        const val SHADOW_LOG_MAX_PER_WAIT = 10
    }
}
