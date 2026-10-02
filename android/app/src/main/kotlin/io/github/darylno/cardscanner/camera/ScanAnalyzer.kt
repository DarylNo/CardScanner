package io.github.darylno.cardscanner.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.github.darylno.cardscanner.core.AutoScanner
import io.github.darylno.cardscanner.core.Box
import io.github.darylno.cardscanner.core.CardOutline
import io.github.darylno.cardscanner.core.OutlineTracker
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.Gray
import io.github.darylno.cardscanner.core.GraySampler
import io.github.darylno.cardscanner.core.Nv21Frame
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
)

/**
 * A burst handed to the capture pipeline. [frames] are private copies (time
 * order); [cropRoi] = the scan area (Mount) or the padded card guide (Handheld);
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
 *     in the [DetectionUpdate]. It runs AFTER the tick / trigger / capture handling
 *     and inside runCatching, so nothing it does can skip fire() / captureDone()
 *     (a skipped captureDone leaves the scanner SCANNING for ever).
 *  5. The WATCH WINDOW: at a capture, the padded (15 %) card polygon in sample
 *     pixels — the live outline at the Trigger tick, else the mask box; when the
 *     capture's own quad arrives ([onCaptureResult], tagged with the burst id,
 *     ignored after a newer trigger) it replaces both. A shutter scan has no box
 *     (the scanner is SCANNING / Auto off, so its ticks are Idle): its window is
 *     the capture quad alone. It is drawn while awaiting the next card.
 *  6. SHADOW MODE (logging only, tag "shadow"): while awaiting the next card every
 *     tick measures [TextureChange] (logHP p75 + logGrad) between the sample and
 *     the scanner's scanned frame inside the window, and logs EVENTS only — the
 *     maximum per wait, each crossing of [TextureChange.START_THRESHOLD] with its
 *     duration, and "WOULD re-arm (texture)" when it holds 2 ticks — at most
 *     ~1 line/s and [SHADOW_LOG_MAX_PER_WAIT] per wait (a signal hovering at the
 *     threshold for an hour would otherwise push every capture line out of the
 *     3000-line ring); the rest are counted in the per-wait summary. The numbers
 *     never re-arm, trigger or change any scanner state (ScanAnalyzerTest:
 *     identical outcomes with it on and off).
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
         * focus on this card first. Answer with [focusDone] (any outcome); the
         * capture then uses frames taken after focus settled, or fires anyway
         * after [FOCUS_WAIT_NS].
         */
        fun onFocusRequest() {}
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
     * change / sharpness drop — see CameraController.)
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

    // shadow-mode wait bookkeeping (one "wait" = one AWAIT_NEXT stretch)
    private var inWait = false
    private var waitTicks = 0
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
    private val snapshotWaiter = java.util.concurrent.atomic.AtomicReference<((Nv21Frame) -> Unit)?>(null)
    private val snapRing = FrameRing(1)

    /** Hand the NEXT analyzed frame (a copy, NV21) to [give] on the analysis thread. */
    fun requestSnapshot(give: (Nv21Frame) -> Unit) { snapshotWaiter.set(give) }

    @Volatile private var frames = 0L
    @Volatile private var gated = 0L
    @Volatile private var ticks = 0L
    @Volatile private var copies = 0L
    @Volatile private var copyNsTotal = 0L
    @Volatile private var sampleNsTotal = 0L
    @Volatile private var captures = 0L
    @Volatile private var errors = 0L
    @Volatile private var lastError: String? = null
    /** Smooths the live outline across ticks (display + the watch window; never the trigger). */
    private val tracker = OutlineTracker()
    @Volatile private var outlineRuns = 0L
    @Volatile private var outlinesFound = 0L
    @Volatile private var outlineNsTotal = 0L
    @Volatile private var outlineErrors = 0L
    @Volatile private var lastOutlineError: String? = null
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
        // A browser asked for a picture of the tray (scan-Area drawing): hand over this frame.
        snapshotWaiter.getAndSet(null)?.let { give ->
            snapRing.copyFrom(p, rotation, timestampNs)
            runCatching { give(snapRing.snapshotLast(1).first()) }
        }
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
            if (mode == ScanMode.MOUNT && runCatching { focusFirst() }.getOrDefault(false)) {
                // Focus on THIS card before shooting it (once per session / area):
                // the capture is held, and taken from frames newer than the focus.
                deferring = true
                deferredBox = trigger.box
                deferStartTs = timestampNs
                focusReadyTs = NONE
                runCatching { sink.onFocusRequest() }
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
                fire(CaptureTrigger.AUTO, deferredBox, uw, uh, if (focused) sinceFocus else AUTO_FRESH_NS)
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
            // Every tick a card may be sitting there — occupied, the trigger, awaiting
            // the next card, a different card settling — so the card stays highlighted.
            val wantOutline = ev is AutoScanner.Event.Trigger ||
                (ev is AutoScanner.Event.Watching && ev.occupied) ||
                ev is AutoScanner.Event.AwaitingNext ||
                (ev is AutoScanner.Event.NextCard && !ev.removed)
            var outline: CardOutline.Outline? = null
            var outlineNs: Long? = null
            if (wantOutline) {
                val t0 = System.nanoTime()
                val raw = runCatching { outlineFinder(g, uw, uh, roi) }
                    .onFailure { outlineErrors++; lastOutlineError = it.toString() }
                    .getOrNull()
                // Smoothed across ticks, outliers dropped (OutlineTracker) — in sample px, then
                // back to frame fractions the same way a raw find goes.
                outline = runCatching { smooth(raw, g, uw, uh) }
                    .onFailure { outlineErrors++; lastOutlineError = it.toString() }
                    .getOrNull()
                outlineNs = System.nanoTime() - t0
                outlineRuns++
                outlineNsTotal += outlineNs
                if (raw != null) outlinesFound++
            } else {
                tracker.reset()
            }
            runCatching { instrument(ev, g, outline, outlineNs, timestampNs) }
                .onFailure { outlineErrors++; lastOutlineError = it.toString() }
            val wf = runCatching { watchFractions(uw, uh) }.getOrNull()
            sink.onDetection(
                DetectionUpdate(ev, lastBox, g.w, g.h, uw, uh, roi, scanner.autoEnabled, scanner.hasEmptyRef, g,
                    outline?.quad, wf, outlineNs),
            )
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
    private fun clearWatch(ts: Long, why: String) {
        if (inWait) endWait(ts, why)
        watch = null; heldTriggerQuad = null; lastFiredId = 0L
        preparedRef = null; preparedRefOf = null
        tracker.reset()
    }

    /** Per tick, after the decisions: the watch window upkeep and the shadow-mode measurement. */
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
            is AutoScanner.Event.AwaitingNext -> if (shadowMode) measureWait(g, ts)
            is AutoScanner.Event.NextCard -> if (inWait) endWait(ts, if (ev.removed) "card removed" else "a different card settled")
            else -> if (inWait) endWait(ts, "no longer waiting (${ev.javaClass.simpleName})")
        }
    }

    private fun measureWait(g: Gray, ts: Long) {
        if (!inWait) { inWait = true; waitTicks = 0; waitMax = Double.NEGATIVE_INFINITY; aboveTicks = 0; abovePeak = 0.0; shadowSuppressed = 0; waitLogged = 0 }
        val wt = watch ?: return
        val ref = scanner.scannedFrame ?: return
        if (wt.w != g.w || wt.h != g.h || ref.w != g.w || ref.h != g.h) return
        val t0 = System.nanoTime()
        if (preparedRefOf !== ref) { preparedRef = TextureChange.prepare(ref); preparedRefOf = ref }
        val sig = TextureChange.measure(g, preparedRef!!, wt.mask) ?: return
        textureNsTotal += System.nanoTime() - t0
        textureRuns++
        waitTicks++
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

    private fun endWait(ts: Long, why: String) {
        inWait = false
        if (waitTicks == 0) return
        val open = if (aboveTicks > 0) " · still ≥ %.0f for %d tick(s) at the end (peak %.1f)".format(TextureChange.START_THRESHOLD, aboveTicks, abovePeak) else ""
        val dropped = if (shadowSuppressed > 0) " · $shadowSuppressed event line(s) held back (1/s, $SHADOW_LOG_MAX_PER_WAIT per wait)" else ""
        // Once per wait, never rate-limited: the one line that summarises what the window saw.
        log.i("shadow", "wait over (%s) after %d ticks: max logHP p75 %.1f (logGrad %.1f, mean grey %.0f)%s%s"
            .format(why, waitTicks, waitMax, waitMaxGrad, waitMaxGrey, open, dropped))
        lastShadowLogTs = ts
        waitTicks = 0; aboveTicks = 0; abovePeak = 0.0; shadowSuppressed = 0; waitLogged = 0
    }

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
                scene = scanner.scannedFrame, box = box, uprightW = uw, uprightH = uh,
            ),
        )
    }

    // ---- commands: callable from any thread, executed on the analysis thread ----

    fun post(block: () -> Unit) {
        try { commands.execute(block) } catch (_: RejectedExecutionException) { /* shut down */ }
    }

    /** Double-tap: re-learn the empty tray. */
    fun reset() = post { scanner.reset(); lastBox = null; deferring = false; clearWatch(lastFrameTs, "reset") }

    /** The focus asked for by [Sink.onFocusRequest] finished (success or not). */
    fun focusDone() = post {
        if (deferring && focusReadyTs == NONE) focusReadyTs = maxOf(lastFrameTs, deferStartTs)
        // The hold gave up waiting and shot while the lens was still moving: the
        // scene it kept is a blur. Now the lens has settled, re-take it.
        else if (!deferring && heldTimedOut) { heldTimedOut = false; rebaseNext = true }
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

    /** New scan area (fractions of the upright frame; null = whole frame): re-learn the tray. */
    fun setRoi(newRoi: RoiFrac?) = post {
        if (newRoi != roi) {
            roi = newRoi
            scanner.reset()
            deferring = false
            lastSample = null; lastBox = null; lastBoxInfo = null
            clearWatch(lastFrameTs, "new area")
        }
    }

    /** Pause ticks while the user drags a new area (phone.html settingArea). */
    fun setPaused(paused: Boolean) = post { scanner.paused = paused }

    fun setMode(newMode: ScanMode) = post {
        if (newMode != mode) {
            mode = newMode
            lastBox = null; lastBoxInfo = null
            deferring = false
            if (newMode == ScanMode.MOUNT) { scanner.reset(); lastSampleTs = NONE }
            clearWatch(lastFrameTs, "mode change")
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
        deferring = false; rebaseNext = false; heldTimedOut = false
        scanner.cameraRestarted()
        ring.clear()
        clearWatch(lastFrameTs, "camera restarted")
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
            if (outlineErrors > 0) append(" · outline errors $outlineErrors (last: $lastOutlineError)")
            val x = textureRuns
            if (x > 0) append(" · texture $x avg ${"%.2f".format(textureNsTotal / 1e6 / x)} ms")
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
        /** Let the tap's jolt pass before the frames a manual scan uses. */
        const val MANUAL_SETTLE_NS = 150_000_000L
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
