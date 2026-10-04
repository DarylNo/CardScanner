package io.github.darylno.cardscanner.core

import kotlin.math.abs

/**
 * Applies "Zoom to fit the Area" ([ZoomFit]) to the camera and keeps the
 * analyzer's mapping in step with what the camera ACTUALLY does. Pure — the
 * CameraX glue is CameraController, the frame gate is ScanAnalyzer — so the
 * lifecycle is JVM-tested (ZoomCoordinatorTest) with a fake [Port] and clock.
 * Main thread only.
 *
 * The rules, each one a verified finding of the 1.1.11 briefing:
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
 * - A request with no answer after [SETTLE_TIMEOUT_MS] maps with whatever the
 *   newest capture result says ([Port.readBack]), else the request.
 * - Drawing an Area zooms OUT to 1× first (the drag is armed by
 *   [Port.drawingReady]); the analyzer is left alone (its gate stays closed,
 *   its mapping and empty tray kept), so Cancel zooms back with no re-learn and
 *   a new Area re-learns once, at its own zoom.
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
        /** Drawing an Area: the camera is at 1× now (the whole frame) — the drag may be armed. */
        fun drawingReady()
        /** Run [block] after [ms] (the main thread). */
        fun later(ms: Long, block: () -> Unit)
        /** The zoom the newest capture result reports, or null (no result yet). */
        fun readBack(): Double?
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
        }
    }

    private class Pending(val gen: Int, val ratio: Double, val why: String) { var tries = 0 }

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
    /** The ratio last asked of the camera and answered (1.0 after a bind / stop: CameraX resets). */
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
    private var gen = 0
    @Volatile private var problem: String? = null

    /** Applied ratio changes this session (the analyzer re-learned at each). */
    @Volatile var changes = 0
        private set

    /** What the Area asks for now (drawing aside). */
    fun target(): ZoomFit.Choice = when {
        !enabled -> ZoomFit.Choice.none(ZoomFit.Limit.OFF, lossless, lensMax)
        else -> ZoomFit.choose(base, lossless, lensMax)
    }

    private fun want(): Double = if (drawing) 1.0 else target().z

    fun state(): State = State(enabled, mapped, target(), pending != null || gateClosed, drawing, problem)

    // ── inputs ──

    /** The Settings switch. */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        log("Zoom to fit the Area switched ${if (on) "ON" else "off"} → ${target().describe()}")
        reconcile("the switch turned ${if (on) "on" else "off"}")
    }

    /** The stored (BASE) Area; null = the full frame. Always re-maps (a new Area re-learns, as before zoom). */
    fun setArea(a: RoiFrac?) {
        base = a
        reconcile("new Area", forceMap = true)
    }

    /** Drawing an Area on the phone: zoom out to 1× (true), back to the Area's zoom (false). */
    fun setDrawing(on: Boolean) {
        if (on == drawing) return
        drawing = on
        reconcile(if (on) "drawing an Area — zoomed out to the whole frame" else "Area drawing over")
    }

    /**
     * A camera was bound (a new lens / resolution): CameraX starts it at 1.0.
     * [lossless] / [lensMax] are this lens's limits (null = unknown).
     */
    fun cameraBound(lossless: Double?, lensMax: Double?) {
        this.lossless = lossless
        this.lensMax = lensMax
        gen++
        pending = null
        active = true
        requested = 1.0; camera = 1.0
        if (enabled) log("camera bound: lossless ${lossless?.let { "%.2f×".format(it) } ?: "unknown"}, lens max " +
            "${lensMax?.let { "%.2f×".format(it) } ?: "unknown"} → ${target().describe()}")
        reconcile("camera bind", forceMap = true)
    }

    /**
     * The camera detached (ON_STOP) or closed: CameraX has reset the zoom to 1.0.
     * A zoom in use closes the gate until [cameraStarted] re-applies it.
     */
    fun cameraStopped(why: String) {
        if (!active) return
        active = false
        val inUse = pending != null || requested != 1.0 || camera != 1.0 || mapped != 1.0
        gen++
        pending = null
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
        reconcile(why)
    }

    // ── the camera's answers ──

    /** Request [g] completed; [readBack] = the ratio the newest capture result reports (null = none). */
    fun onApplied(g: Int, readBack: Double?) {
        val p = pending ?: return
        if (p.gen != g) return
        pending = null
        requested = p.ratio
        camera = agreed(p.ratio, readBack)
        problem = if (camera != p.ratio) "asked for %.2f×, the camera reports %.2f× — mapping with %.2f×".format(p.ratio, readBack, camera) else null
        log("%.2f× applied (%s) · read back %s".format(camera, p.why, readBack?.let { "%.3f×".format(it) } ?: "nothing — the request is assumed") +
            (if (camera != p.ratio) " — NOT what was asked (%.2f×): frames are mapped at what the camera reports".format(p.ratio) else ""))
        settle(p.why)
    }

    /** Request [g] failed: [notActive] = CameraX's "Camera is not active" (retried), else given up at once. */
    fun onFailed(g: Int, notActive: Boolean, message: String) {
        val p = pending ?: return
        if (p.gen != g) return
        if (notActive && p.tries < MAX_TRIES) {
            p.tries++
            port.later(RETRY_MS) { if (pending === p) port.request(p.gen, p.ratio) }
            return
        }
        pending = null
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
            if (drawing) { port.drawingReady(); return }
            val view = ZoomFit.toView(base, camera)
            if (forceMap || gateClosed || view != mappedView || camera != mapped) mapTo(camera, view, why)
            return
        }
        if (!active) return                                   // a camera change waits for the camera
        request(t, why)
    }

    private fun request(t: Double, why: String) {
        val g = ++gen
        val p = Pending(g, t, why)
        pending = p
        closeGate("zoom → %.2f× ($why)".format(t))
        log("→ %.2f× requested ($why)%s".format(t, if (t != 1.0 && !drawing) " · ${target().describe()}" else ""))
        port.request(g, t)
        port.later(SETTLE_TIMEOUT_MS) { onTimeout(g) }
    }

    private fun onTimeout(g: Int) {
        val p = pending ?: return
        if (p.gen != g) return
        val rb = port.readBack()
        pending = null
        requested = p.ratio
        camera = agreed(p.ratio, rb)
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
        if (drawing) { port.drawingReady(); return }
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
    }
}
