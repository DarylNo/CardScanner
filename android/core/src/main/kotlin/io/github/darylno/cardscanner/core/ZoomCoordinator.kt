package io.github.darylno.cardscanner.core

import kotlin.math.abs

/**
 * Applies "Zoom to fit the Area" ([ZoomFit]) to the camera and keeps the
 * analyzer's mapping in step with what the camera ACTUALLY does. Pure — the
 * CameraX glue is CameraController, the frame gate is ScanAnalyzer — so the
 * lifecycle is JVM-tested (ZoomCoordinatorTest) with a fake [Port] and clock.
 * Main thread only.
 *
 * The rules, each one a verified finding of the 1.1.14 briefing:
 * - The switch OFF (the default in this build) and an unzoomed camera: no zoom
 *   call, no gate, no settle, ever — the Area goes to the analyzer exactly as
 *   before ([Port.map] at 1.0, settle = false).
 * - Every camera change (a new ratio) CLOSES the analyzer's gate first (no
 *   ticks, no ring copies, no captures), asks the camera, and maps with the
 *   ratio READ BACK from the capture results ([onApplied]; CameraX's ZoomState
 *   moves when the request is made, not when frames carry it). A read-back
 *   within 1 % of the request counts as the request, so a re-apply maps
 *   bit-identically and never re-learns the tray.
 * - CameraX resets zoom to 1.0 every time the camera detaches (ON_STOP: the
 *   Scans / Settings / Tap-detail round trips). [cameraStopped] closes the gate
 *   while a zoom is in use; [cameraStarted] (the camera OPEN again, the screen
 *   started, or frames arriving at a closed gate) re-applies it. The analyzer
 *   re-learns only when (ratio, view Area) really changed — a re-apply is not.
 * - "Camera is not active" (a request before the use cases attach) is retried
 *   [MAX_TRIES] times [RETRY_MS] apart, then the mapping falls back to 1× (what
 *   an inactive camera comes back at) and scanning carries on.
 * - A request with no answer after [SETTLE_TIMEOUT_MS] maps with what a capture
 *   result NEWER than the request says ([Port.readBack] — never one left over
 *   from before a stop), else the request. The camera's answer may still come
 *   (a slow reopen): if it differs from what the timeout mapped, the gate
 *   closes and the Area is re-mapped at it (review of 1.1.14 — before, the late
 *   answer was dropped and the Area stayed mapped at the wrong zoom until the
 *   next stop).
 * - Drawing an Area zooms OUT to 1× first (the drag is armed by
 *   [Port.drawingReady]); the analyzer is left alone (its gate stays closed,
 *   its mapping and empty tray kept), so Cancel zooms back with no re-learn and
 *   a new Area re-learns once, at its own zoom. Only the browser's picture of
 *   the tray is served meanwhile, at the camera's ratio; [state] reports the
 *   camera's ratio, not settling, while the drawing waits for nothing.
 * - The off-centre hint (1.1.17, [ZoomFit.offCentre]) is read-only: [state]
 *   carries what the Area would zoom and what the same Area would zoom centred,
 *   whatever the switch says, and the log says it once per change of its words
 *   (and why, when it goes) — from the first [cameraBound] on, when the lens's
 *   caps are known. It never reaches the [Port].
 * - THE ZOOM PROBE (1.1.17, a measured ratio ladder; the sequencer is
 *   [ZoomProbeRun]) is a MODE of this machine, so the gate has one owner and can
 *   never be left closed by someone else: [beginProbe] closes the analyzer's gate
 *   and keeps it closed for every rung ([probeTo] asks the camera; the answer is
 *   [Port.probeAt], never a [Port.map]); [endProbe] maps the IDENTICAL (ratio,
 *   view) the analyzer had, behind the gate — no re-learn, `changes` unchanged —
 *   or asks for the target first if the camera is elsewhere. A rung the camera
 *   never answered is UNKNOWN, never assumed: the next ask (a rung, the restore,
 *   the end) goes to the camera; and a request the timeout gave up on just before
 *   the probe keeps its late re-map (noted mid-probe, mapped at the end — review of
 *   08a77d1). The end's rebase ([Port.probeRebase]) is asked only while the probe
 *   still runs, before its map: never after an abort — and every stop / close /
 *   reopen tells the analyzer ([Port.cameraAway], with or without a zoom in use),
 *   so a rebase no sample took yet dies with the round trip. Any lifecycle input
 *   (stop, opening, bind, a new Area, the switch, drawing) stops the probe FIRST
 *   ([Port.probeEnded]) and then does exactly what it does today, so an aborted
 *   probe is today's Scans / Settings round trip. Nothing here files a scan.
 */
class ZoomCoordinator(private val port: Port, private val log: (String) -> Unit = {}) {

    interface Port {
        /** Ask the camera for [ratio]; answer with [onApplied] / [onFailed] tagged [gen]. */
        fun request(gen: Int, ratio: Double)
        /** The analyzer stops (no ticks, copies, captures) until a [map] with settle = true. */
        fun closeGate(why: String)
        /**
         * The analyzer maps the Area as [view] (fractions of the frame at [ratio]) —
         * re-learning only when (ratio, view) changed; [settle] = the gate is closed:
         * open it once the frames that may predate the zoom are dropped.
         */
        fun map(ratio: Double, view: RoiFrac?, settle: Boolean, why: String)
        /**
         * Drawing an Area: the camera is at [ratio] now (1×, the whole frame) — the drag
         * may be armed, and the analyzer may serve snapshots at [ratio] (its gate stays closed).
         */
        fun drawingReady(ratio: Double)
        /** Run [block] after [ms] (the main thread). */
        fun later(ms: Long, block: () -> Unit)
        /**
         * The zoom the newest capture result reports — only a result that arrived
         * AFTER the latest [request] (a read-back left over from before a stop
         * says what the camera WAS) — or null.
         */
        fun readBack(): Double?
        /**
         * The zoom probe's rung: the camera answered [probeTo]'s [asked] — it is at
         * [camera] (a read-back within [AGREE] is the request), [readBack] is the RAW
         * read-back (null = none), or [error] says why not ([PROBE_NO_ANSWER] = no
         * answer within [SETTLE_TIMEOUT_MS]; anything else = refused / failed). The
         * gate stays closed. May come back INSIDE [probeTo] (the camera already there):
         * the glue posts it on. [gen] is the request's (or the current one).
         */
        fun probeAt(gen: Int, asked: Double, camera: Double, readBack: Double?, error: String?) {}
        /** A lifecycle input stopped the probe ([why]) before it ended; what follows is today's path. */
        fun probeEnded(why: String) {}
        /**
         * [endProbe] with rebase = true, while the probe still ran: the analyzer re-takes its
         * scanned scene (ScanAnalyzer.rebaseAfterProbe) — asked BEFORE the end's map or
         * request, and never after an abort (a card swapped while the screen was away must scan).
         */
        fun probeRebase(why: String) {}
        /**
         * The camera stopped, closed or is (re)opening ([cameraStopped] / [cameraOpening]) — ALSO with no
         * zoom in use, when no gate closes: the analyzer drops a rebase the probe's verified end still owes
         * (ScanAnalyzer.cameraAway). Never a gate, never a map. Review of a43cb5b: a stop between a verified
         * end and the first sample carried the rebase across the round trip, and the first sample back took
         * a card swapped while the screen was away into the scanned scene — it was never filed.
         */
        fun cameraAway(why: String) {}
    }

    /** What Diagnostics, the zoom log and `/api/device` report. */
    class State(
        val enabled: Boolean,
        /** The ratio frames are mapped at now (read back from the camera). */
        val ratio: Double,
        /** What the Area asks for (1.0 with the switch off / no Area). */
        val target: ZoomFit.Choice,
        val settling: Boolean,
        val drawing: Boolean,
        /** The last thing that went wrong applying a zoom, or null. */
        val problem: String?,
        /** What this Area would zoom with the switch ON ([ZoomFit.choose]) — also while it is off (the target is then 1×). */
        val area: ZoomFit.Choice? = null,
        /** The off-centre hint ([ZoomFit.offCentre]): what the same Area would zoom centred, or null. */
        val offCentre: ZoomFit.OffCentre? = null,
        /** The zoom probe holds the gate ([ratio] is then the camera's rung, not settling). */
        val probing: Boolean = false,
        /** The probe runner's words for where it is ("rung 7/18 · 1.60×"), or null. */
        val probeNote: String? = null,
    ) {
        /** "zoom: target 1.85× (limited by the Area fit) · applied 1.85× · lossless 2.60× · lens max 10.00× · MAX_ZOOM 2.00×" */
        fun describe(): String = buildString {
            append("zoom to fit the Area: ").append(if (enabled) "on" else "off")
            append(" · target ").append(target.describe())
            append(" · applied %.2f×".format(ratio))
            append(" · Area fit ").append(target.fit?.let { if (it.isInfinite()) "∞" else "%.2f×".format(it) } ?: "—")
            append(" · lossless ").append(target.lossless?.let { "%.2f×".format(it) } ?: "unknown")
            append(" · lens max ").append(target.lensMax?.let { "%.2f×".format(it) } ?: "unknown")
            append(" · MAX_ZOOM %.2f×".format(ZoomFit.MAX_ZOOM))
            if (settling) append(" · SETTLING")
            if (drawing) append(" · drawing an Area")
            problem?.let { append(" · last problem: ").append(it) }
            offCentre?.let { append(" · off-centre: ").append(it.describe()) }
            if (probing) append(" · ZOOM PROBE running").append(probeNote?.let { " ($it)" } ?: "")
        }
    }

    /** [probe] = asked by the zoom probe (a rung, the restore), not by the scanner. */
    private class Pending(val gen: Int, val ratio: Double, val why: String, val probe: Boolean = false) { var tries = 0 }

    @Volatile var enabled = false
        private set
    @Volatile var base: RoiFrac? = null
        private set
    @Volatile var drawing = false
        private set
    @Volatile private var lossless: Double? = null
    @Volatile private var lensMax: Double? = null
    /** A camera is bound and not stopped. */
    @Volatile private var active = false
    /**
     * The ratio last asked of the camera and answered (1.0 after a bind / stop: CameraX resets).
     * NaN = unknown: a probe rung timed out ([onTimeout]) — the next ask of any ratio goes to the camera.
     */
    private var requested = 1.0
    /** The ratio the camera reported for it (what frames are at). */
    @Volatile private var camera = 1.0
    /** The ratio the analyzer maps with. */
    @Volatile var mapped = 1.0
        private set
    @Volatile var mappedView: RoiFrac? = null
        private set
    @Volatile private var gateClosed = false
    @Volatile private var pending: Pending? = null
    /** The last request the timeout gave up on: its late answer may still re-map ([onApplied]). */
    private var lastTimedOut: Pending? = null
    private var gen = 0
    @Volatile private var problem: String? = null

    /** Applied ratio changes this session (the analyzer re-learned at each). */
    @Volatile var changes = 0
        private set

    /** The zoom probe holds the gate ([beginProbe] … [endProbe]). */
    @Volatile var probing = false
        private set
    /** The rung the probe wants the camera at. */
    private var probeWant = 1.0
    /** [problem] when the probe began: a rung's clamp or refusal is the probe's finding, not the scanner's. */
    private var problemBeforeProbe: String? = null
    /** The probe asked the camera something (so where the camera is at the end is the probe's doing). */
    private var probeAsked = false
    /** Where the probe is, in the runner's words (Diagnostics / `/api/device`); cleared when it ends. */
    @Volatile var probeNote: String? = null

    /** What the Area asks for now (drawing aside). */
    fun target(): ZoomFit.Choice = when {
        !enabled -> ZoomFit.Choice.none(ZoomFit.Limit.OFF, lossless, lensMax)
        else -> ZoomFit.choose(base, lossless, lensMax)
    }

    private fun want(): Double = if (probing) probeWant else if (drawing) 1.0 else target().z

    /**
     * While an Area is drawn and the camera has answered, the camera's ratio (1×) is
     * what pictures are taken at and nothing is settling — the gate is closed on
     * purpose (the analyzer is left alone), not waiting for the camera.
     */
    fun state(): State {
        // The hint is computed whatever the switch says (target() is "off" then, with no fit),
        // once a lens is bound: before it, its caps are unknown and the ratios would be wrong.
        val area = ZoomFit.choose(base, lossless, lensMax)
        val oc = if (lensKnown) ZoomFit.offCentre(base, lossless, lensMax) else null
        // The probe: the camera's rung, never "settling" (the gate is closed on purpose), the scanner's own problem.
        if (probing) return State(enabled, camera, target(), false, drawing, problemBeforeProbe, area, oc, true, probeNote)
        return if (drawing && pending == null) State(enabled, camera, target(), false, true, problem, area, oc)
        else State(enabled, mapped, target(), pending != null || gateClosed, drawing, problem, area, oc)
    }

    // ── inputs ──

    /** The Settings switch. */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        abortProbe("Zoom to fit switched ${if (on) "on" else "off"}")
        enabled = on
        log("Zoom to fit the Area switched ${if (on) "ON" else "off"} → ${target().describe()}")
        reconcile("the switch turned ${if (on) "on" else "off"}")
        noteOffCentre()
    }

    /** The stored (BASE) Area; null = the full frame. Always re-maps (a new Area re-learns, as before zoom). */
    fun setArea(a: RoiFrac?) {
        abortProbe("a new Area")
        base = a
        reconcile("new Area", forceMap = true)
        noteOffCentre()
    }

    /** Drawing an Area on the phone: zoom out to 1× (true), back to the Area's zoom (false). */
    fun setDrawing(on: Boolean) {
        if (on == drawing) return
        if (on) abortProbe("drawing an Area")
        drawing = on
        reconcile(if (on) "drawing an Area — zoomed out to the whole frame" else "Area drawing over")
    }

    /**
     * A camera was bound (a new lens / resolution): CameraX starts it at 1.0.
     * [lossless] / [lensMax] are this lens's limits (null = unknown).
     */
    fun cameraBound(lossless: Double?, lensMax: Double?) {
        abortProbe("the camera was bound again")
        this.lossless = lossless
        this.lensMax = lensMax
        gen++
        pending = null
        lastTimedOut = null
        active = true
        lensKnown = true
        requested = 1.0; camera = 1.0
        if (enabled) log("camera bound: lossless ${lossless?.let { "%.2f×".format(it) } ?: "unknown"}, lens max " +
            "${lensMax?.let { "%.2f×".format(it) } ?: "unknown"} → ${target().describe()}")
        reconcile("camera bind", forceMap = true)
        noteOffCentre()
    }

    /**
     * The camera detached (ON_STOP) or closed: CameraX has reset the zoom to 1.0.
     * A zoom in use closes the gate until [cameraStarted] re-applies it.
     */
    fun cameraStopped(why: String) {
        port.cameraAway(why)
        if (!active) return
        abortProbe(why)
        active = false
        val inUse = pending != null || requested != 1.0 || camera != 1.0 || mapped != 1.0
        gen++
        pending = null
        lastTimedOut = null
        requested = 1.0; camera = 1.0
        if (inUse) {
            closeGate("$why — the camera drops the zoom; it is re-applied when the camera is back")
        }
    }

    /**
     * The camera is opening (PENDING_OPEN / OPENING, e.g. recovering from an
     * error while still attached): frames may come at an unknown moment —
     * close the gate while a zoom is in use; [cameraStarted] re-applies.
     */
    fun cameraOpening(why: String) {
        port.cameraAway(why)
        abortProbe(why)
        if (pending != null || requested != 1.0 || mapped != 1.0) closeGate(why)
    }

    /**
     * The camera is open again (state OPEN, the screen started, or frames
     * arriving at a closed gate): re-apply what the Area asks for. Waits for a
     * request already on its way.
     */
    fun cameraStarted(why: String) {
        if (active && pending != null) return
        active = true
        if (probing) return                                   // the probe asks for its own rungs; a resync asks nothing
        reconcile(why)
    }

    // ── the zoom probe ──

    /**
     * Start the zoom probe: refused (false, nothing changed) unless a camera is
     * active, no Area is being drawn, nothing is on its way to the camera, no
     * probe runs and the gate is open. Closes the analyzer's gate — it stays
     * closed until [endProbe] (or an abort) — and starts at the camera's ratio.
     */
    fun beginProbe(why: String): Boolean {
        if (!active || drawing || pending != null || probing || gateClosed) {
            log("zoom probe refused ($why) — " + when {
                !active -> "no camera active"
                drawing -> "an Area is being drawn"
                pending != null -> "the zoom is settling"
                probing -> "a probe is already running"
                else -> "the analyzer's gate is closed (the camera is reopening)"
            })
            return false
        }
        problemBeforeProbe = problem
        probing = true
        probeAsked = false
        probeWant = camera
        // A request the timeout gave up on just before is KEPT (review of 08a77d1): its late answer
        // never maps mid-probe ([lateAnswer] only notes where the camera is), and once the probe
        // asks the camera anything that request supersedes it ([request]).
        log("zoom probe START (%s) — the analyzer stays paused; camera at %.2f×, frames mapped at %.2f× (kept)".format(why, camera, mapped))
        closeGate("zoom probe ($why)")
        return true
    }

    /** The probe's next rung: ask the camera for [ratio] (any ratio — the probe ignores MAX_ZOOM); answered by [Port.probeAt]. */
    fun probeTo(ratio: Double) {
        if (!probing) return
        probeWant = ratio
        reconcile("zoom probe → %.2f×".format(ratio))
    }

    /**
     * The probe is over: map what the analyzer had — the IDENTICAL (ratio, view)
     * when the camera answered the target (no re-learn); what the camera reports
     * when it answered the target with another ratio (as [onApplied] maps, a
     * re-learn); else — a rung or the restore left the camera elsewhere or
     * unanswered — the target is ASKED for first, never assumed. Behind the gate,
     * which opens after the settle frames. [rebase] = the runner's restore found the
     * same card in the same place: [Port.probeRebase] first. Returns false (and does
     * nothing, no rebase) when no probe runs — a lifecycle input already stopped it.
     */
    fun endProbe(why: String, rebase: Boolean = false): Boolean {
        if (!probing) return false
        probing = false
        probeNote = null
        problem = problemBeforeProbe
        val t = target().z
        val answered = pending == null && requested == t      // NaN (a rung with no answer) is never t
        val view = ZoomFit.toView(base, camera)
        log("zoom probe END ($why) — " + when {
            !answered -> "the target %.2f× is asked for (the camera is at %.2f×)".format(t, camera)
            camera == mapped && view == mappedView -> "back to %.2f× on the same mapping (no re-learn)".format(camera)
            camera == t -> "the camera is at the target %.2f×, the analyzer was mapped at %.2f× — mapping with %.2f× (the analyzer re-learns)".format(t, mapped, camera)
            else -> "the camera reports %.2f× for the target %.2f× — mapping with %.2f× (the analyzer re-learns)".format(camera, t, camera)
        })
        if (answered && camera != t && probeAsked)
            problem = "after the zoom probe the camera reports %.2f× for %.2f× — mapping with %.2f×".format(camera, t, camera)
        if (rebase) port.probeRebase(why)
        reconcile("zoom probe over")
        return true
    }

    /** A lifecycle input stops a running probe first; the caller then does what it does today. */
    private fun abortProbe(why: String) {
        if (!probing) return
        probing = false
        probeNote = null
        problem = problemBeforeProbe
        log("zoom probe STOPPED ($why)")
        port.probeEnded(why)
    }

    // ── the camera's answers ──

    /** Request [g] completed; [readBack] = the ratio the newest capture result reports (null = none). */
    fun onApplied(g: Int, readBack: Double?) {
        val p = pending ?: return lateAnswer(g, readBack)
        if (p.gen != g) return
        pending = null
        requested = p.ratio
        camera = agreed(p.ratio, readBack)
        if (probing) { port.probeAt(g, p.ratio, camera, readBack, null); return }    // no problem, no settle: the rung line says it
        problem = if (camera != p.ratio) "asked for %.2f×, the camera reports %.2f× — mapping with %.2f×".format(p.ratio, readBack, camera) else null
        log("%.2f× applied (%s) · read back %s".format(camera, p.why, readBack?.let { "%.3f×".format(it) } ?: "nothing — the request is assumed") +
            (if (camera != p.ratio) " — NOT what was asked (%.2f×): frames are mapped at what the camera reports".format(p.ratio) else ""))
        settle(p.why)
    }

    /** Request [g] failed: [notActive] = CameraX's "Camera is not active" (retried), else given up at once. */
    fun onFailed(g: Int, notActive: Boolean, message: String) {
        val p = pending ?: return lateFailure(g, notActive, message)
        if (p.gen != g) return
        if (notActive && p.tries < MAX_TRIES) {
            p.tries++
            port.later(RETRY_MS) { if (pending === p) port.request(p.gen, p.ratio) }
            return
        }
        pending = null
        if (probing) {
            // The rung's finding (the runner ends the ladder there); the scanner's problem is untouched.
            if (notActive) { requested = 1.0; camera = 1.0 } else requested = p.ratio
            port.probeAt(g, p.ratio, camera, null, message)
            return
        }
        problem = "%.2f× could not be applied (%s%s)".format(p.ratio, message, if (notActive) ", ${p.tries + 1} tries" else "")
        if (notActive) {
            // An inactive camera comes back at 1.0: map there; the next start asks again.
            requested = 1.0; camera = 1.0
        } else {
            // Refused (e.g. out of range): the camera kept what it had; don't ask for this ratio again.
            requested = p.ratio
        }
        log("${problem} — scanning at %.2f×".format(camera))
        settle(p.why, chase = false)      // never re-ask in a loop: the next start / Area / switch asks again
    }

    /**
     * The answer to a request the timeout already settled (a slow reopen): if the
     * camera is at a different ratio than the timeout assumed, close the gate and
     * map what it reports (or re-arm a drawing) — else nothing (it was right).
     */
    private fun lateAnswer(g: Int, readBack: Double?) {
        val p = lastTimedOut ?: return
        if (p.gen != g) return
        lastTimedOut = null
        val now = agreed(p.ratio, readBack)
        log("%.2f× answered after the %d ms timeout (%s) · read back %s · %s".format(p.ratio, SETTLE_TIMEOUT_MS, p.why,
            readBack?.let { "%.3f×".format(it) } ?: "nothing — the request is assumed",
            if (now == camera) "the timeout assumed %.2f× — right".format(camera)
            else if (probing) "the timeout assumed %.2f× — the camera is at %.2f× (the zoom probe holds the gate: mapped when it ends)".format(camera, now)
            else "the timeout assumed %.2f× — re-mapping at %.2f×".format(camera, now)))
        if (probing) {
            // Never a map mid-probe: only where the camera is — [endProbe] maps (or asks) from it.
            if (p.probe) requested = p.ratio                  // the rung WAS answered: known again
            if (now == camera) return
            camera = now
            if (!p.probe) problemBeforeProbe = lateNote(p.ratio, now)
            return
        }
        if (now == camera) return
        camera = now
        problem = lateNote(p.ratio, now)
        closeGate("the camera answered %.2f× after the timeout".format(now))
        settle(p.why)
    }

    private fun lateNote(asked: Double, now: Double) =
        "no answer for %.2f× within %d ms; it came later at %.2f× and the Area was re-mapped".format(asked, SETTLE_TIMEOUT_MS, now)

    /** A request the timeout already mapped failed after all: map what the camera is left at. */
    private fun lateFailure(g: Int, notActive: Boolean, message: String) {
        val p = lastTimedOut ?: return
        if (p.gen != g) return
        lastTimedOut = null
        // An inactive camera comes back at 1.0 (the next start asks again); a refusal kept what it had.
        val now = if (notActive) 1.0 else port.readBack()?.let { agreed(p.ratio, it) } ?: camera
        if (notActive) requested = 1.0
        val what = "%.2f× could not be applied (%s), answered after the %d ms timeout".format(p.ratio, message, SETTLE_TIMEOUT_MS)
        if (probing) {
            // As [lateAnswer]: never a map mid-probe — where the camera is, for [endProbe].
            if (p.probe && !notActive) requested = p.ratio    // refused: not asked again in a loop
            log("$what — the camera is at %.2f× (the zoom probe holds the gate)".format(now))
            camera = now
            if (!p.probe) problemBeforeProbe = what
            return
        }
        problem = what
        log("$problem — scanning at %.2f×".format(now))
        if (now == camera) return
        camera = now
        closeGate("the camera refused %.2f× after the timeout".format(p.ratio))
        settle(p.why, chase = false)
    }

    /** The off-centre hint's words last logged (null = none): it is logged once per change, never per setArea. */
    private var offCentreWords: String? = null

    /**
     * A camera has been bound at least once, so [lossless] / [lensMax] are this lens's. The scan
     * screen hands a new coordinator its Area BEFORE the bind (CameraAdapter.bind: setRoi, then
     * start → bind → [cameraBound]); the hint logged from no caps said 1.90× and the bind's line
     * contradicted it, or "cleared" it (review of 39ef7cf). The hint waits for the lens.
     */
    @Volatile private var lensKnown = false

    /** Log the off-centre hint when its words change (also with the switch off). Logging only — never the [Port]. */
    private fun noteOffCentre() {
        if (!lensKnown) return
        val w = ZoomFit.offCentre(base, lossless, lensMax)?.describe()
        if (w == offCentreWords) return
        offCentreWords = w
        log("off-centre Area: " + (w ?: noHintWhy()))
    }

    /**
     * Why there is no hint now, for the line that says it went: no Area, another cap binds
     * (centring can only raise the Area fit, never the sensor, the lens or MAX_ZOOM), or the
     * Area fit binds and centring would gain under [ZoomFit.OFF_CENTRE_GAIN].
     */
    private fun noHintWhy(): String {
        val now = ZoomFit.choose(base, lossless, lensMax)
        return when {
            base == null -> "none now — ${ZoomFit.Limit.NO_AREA.words}"
            now.limit != ZoomFit.Limit.FIT -> "none now — ${now.limit.words} binds, not the Area fit: centring would zoom no more"
            else -> "centred enough now"
        }
    }

    // ── the machine ──

    private fun agreed(asked: Double, readBack: Double?): Double =
        if (readBack == null || readBack <= 0.0 || abs(readBack - asked) <= AGREE * asked) asked else readBack

    private fun closeGate(why: String) {
        if (gateClosed) return
        gateClosed = true
        port.closeGate(why)
    }

    /**
     * Bring the camera and the analyzer to what is wanted. [forceMap] = re-map even
     * when nothing about the zoom changed (a new Area / bind: the analyzer must
     * hear about it — with the zoom off that IS the pre-zoom setRoi path).
     */
    private fun reconcile(why: String, forceMap: Boolean = false) {
        val t = want()
        val p = pending
        if (p != null && p.ratio == t) return                 // on its way; the answer maps it
        if (p == null && t == requested) {
            // The camera is already there (a stopped camera is at 1.0): only the mapping
            // (or the drawing) moves. With the zoom off this is the ONLY path — the Area
            // reaches the analyzer at once, exactly as before zoom existed.
            // The probe: answered at once, inside probeTo (never a map while probing).
            if (probing) { port.probeAt(gen, t, camera, null, null); return }
            if (drawing) { port.drawingReady(camera); return }
            val view = ZoomFit.toView(base, camera)
            if (forceMap || gateClosed || view != mappedView || camera != mapped) mapTo(camera, view, why)
            return
        }
        if (!active) return                                   // a camera change waits for the camera
        request(t, why)
    }

    private fun request(t: Double, why: String) {
        val g = ++gen
        val p = Pending(g, t, why, probe = probing)
        pending = p
        if (probing) probeAsked = true
        lastTimedOut = null                                   // superseded: the camera answers the newest request
        closeGate("zoom → %.2f× ($why)".format(t))
        if (!probing) log("→ %.2f× requested ($why)%s".format(t, if (t != 1.0 && !drawing) " · ${target().describe()}" else ""))
        port.request(g, t)
        port.later(SETTLE_TIMEOUT_MS) { onTimeout(g) }
    }

    private fun onTimeout(g: Int) {
        val p = pending ?: return
        if (p.gen != g) return
        val rb = port.readBack()
        pending = null
        camera = agreed(p.ratio, rb)
        lastTimedOut = p                                      // its late answer may still say where the camera is
        if (probing) {
            // The rung is NOT reached (review of 08a77d1: recording it made the next ask of that ratio —
            // the restore — "already there", and the end mapped the stale read-back): unknown, so the
            // next rung or the end asks the camera again. Never a map; the runner skips the rung.
            requested = Double.NaN
            port.probeAt(g, p.ratio, camera, rb, PROBE_NO_ANSWER)
            return
        }
        requested = p.ratio
        problem = "no answer for %.2f× within %d ms (read back %s)".format(p.ratio, SETTLE_TIMEOUT_MS, rb?.let { "%.2f×".format(it) } ?: "nothing")
        log("$problem — mapping with %.2f×".format(camera))
        settle(p.why)
    }

    /**
     * The camera answered: map at what it reports (or arm the drawing). [chase] = a
     * target that moved on meanwhile is asked for now (never after a failure).
     */
    private fun settle(why: String, chase: Boolean = true) {
        val t = want()
        if (chase && t != requested && active) { request(t, "$why, then the target moved on"); return }
        if (drawing) { port.drawingReady(camera); return }
        mapTo(camera, ZoomFit.toView(base, camera), why)
    }

    private fun mapTo(ratio: Double, view: RoiFrac?, why: String) {
        val settle = gateClosed
        gateClosed = false
        if (ratio != mapped) changes++
        mapped = ratio
        mappedView = view
        port.map(ratio, view, settle, why)
    }

    companion object {
        /** "Camera is not active" retries before mapping at 1×. */
        const val MAX_TRIES = 5
        const val RETRY_MS = 200L
        /** A request with no answer by then maps with what the camera reports. */
        const val SETTLE_TIMEOUT_MS = 2_000L
        /** A read-back within this share of the request IS the request (bit-identical re-applies). */
        const val AGREE = 0.01
        /** [Port.probeAt]'s error for a rung the camera did not answer within [SETTLE_TIMEOUT_MS]. */
        const val PROBE_NO_ANSWER = "no answer in 2000 ms"
    }
}
