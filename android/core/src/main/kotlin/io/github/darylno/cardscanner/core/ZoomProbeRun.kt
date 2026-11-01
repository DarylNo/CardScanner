package io.github.darylno.cardscanner.core

import java.util.Locale

/**
 * One run of the zoom probe (1.1.17), step by step — the sequencer. Pure and
 * main-thread only (JVM-tested by ZoomProbeRunTest with a fake [Port] and clock);
 * the camera side (the coordinator's probe mode, the analyzer's probe frames, AF,
 * the `zoom-probe` worker) is the app's CameraController.
 *
 * The order (the plan's "Measurement method"):
 * 1. BEGIN — [Port.begin] = ZoomCoordinator.beginProbe: the analyzer's gate closes
 *    and stays closed to the end. Refused → nothing changed.
 * 2. CHECK — [Port.check], read after the gate closed (Tray must have scanned the
 *    card; no shutter or held capture pending). Refused → [Port.end] at once (the
 *    camera never moved: the identical mapping comes back, no re-learn).
 * 3. AE/AWB LOCK, [AE_SETTLE_MS].
 * 4. LOCATE at 1× (one retry). No card → RESTORE without verification (no AF ran).
 * 5. PLAN — [ZoomProbe.plan]. Refused (off-centre, fills the view) → the same.
 * 6. FOCUS — ONE AF pass on the card at 1× (a digital zoom is a crop: the lens is
 *    not touched again on the ladder). Not focused → RESTORE WITH verification.
 * 7. THE LADDER — one zoom in flight at a time; the camera moves on while the
 *    worker measures (at most [MAX_QUEUED] rungs queued). A refused rung ends the
 *    ladder there; one with no answer is skipped.
 * 8. REFOCUS CHECK at the top rung (no knee is measured — [ZoomProbe.DETAIL_VALIDATED]
 *    is false): one AF pass there, its lens move reported.
 * 9. RESTORE — back to [ZoomProbe.Facts.restoreZ], AE/AWB unlocked, [RESTORE_AE_MS],
 *    frames: the same card in the same place ([ZoomProbe.sameCard]) → [Port.end]
 *    with verified = true (the glue rebases the scanned scene, then ends the probe);
 *    anything else → verified = false, and a swapped card will scan.
 * A CANCEL (the owner) from any step after BEGIN goes through the RESTORE —
 * verified once the AF pass ran. The [BUDGET_MS] budget does the same. A
 * coordinator ABORT ([aborted]: the screen stopped, a new Area, …) stops at once:
 * no RESTORE, no [Port.end] (the coordinator already ended it), no rebase — what
 * follows is today's round trip. Answers that come after their step moved on (a
 * stale zoom, a late worker) are ignored; [Port.finished] comes exactly once.
 * Nothing here can file a scan.
 */
class ZoomProbeRun(
    private val port: Port,
    /** This run's number in the session (1, 2, …). */
    val run: Int,
    private val seed: Long,
    /** Earlier VALID runs' 1× thumbnails: a run on the same card again does not count as another card. */
    private val earlier: List<Earlier> = emptyList(),
) {
    /** An earlier valid run's 1× card thumbnail ([ZoomDetail.thumbnail]). */
    class Earlier(val run: Int, val thumbnail: FloatArray)

    /** What the worker is asked: LOCATE (find the card at 1× + its thumbnail), START (the 1× rung + its thumbnail), RUNG, REFOCUS, RESTORE (+ thumbnail). */
    enum class Role { LOCATE, START, RUNG, REFOCUS, RESTORE }

    /** Frames taken at the camera's zoom: [skipped] dropped first, [skippedAtNew] of those already at it; [token] carries them to the worker. */
    class Grab(val frames: Int, val skipped: Int, val skippedAtNew: Int, val token: Any? = null)

    /** The worker's answer: each frame's facts in a [w]×[h] frame, the card [thumbnail] (START / RESTORE), [ms] of work. */
    class Measured(val frames: List<ZoomProbe.FrameFacts>, val w: Int, val h: Int, val thumbnail: FloatArray? = null, val ms: Long = 0)

    /** For the scan screen: the status pill's [status], the measurement card's [count], the rung's zoom [z] (null = none). */
    class Progress(val status: String, val count: String, val z: Double?)

    enum class Kind(val word: String) { REFUSED("refused"), STOPPED("stopped"), CANCELLED("cancelled"), DONE("done") }

    /** How the run ended — exactly once: [words] for the owner, [result] when it measured, [thumbnail] its 1× card. */
    class Outcome(val kind: Kind, val words: String, val result: ZoomProbe.Result?, val thumbnail: FloatArray?)

    interface Port {
        /** ZoomCoordinator.beginProbe: null = begun (the gate is closed), else why not, in the owner's words (nothing changed). */
        fun begin(why: String): String?
        /** Read after the gate closed: [answer] null = the probe may run, else the refusal's words. On the main thread. */
        fun check(answer: (String?) -> Unit)
        /** AE/AWB locked for the probe (true) or back to the owner's own setting (false). */
        fun aeLock(on: Boolean)
        /** ZoomCoordinator.probeTo([z]); the answer comes back as [onZoom] (posted, never inside this call). */
        fun zoomTo(z: Double)
        /** One AF pass metered at ([u], [v]) of the view (fractions). */
        fun focusOnCard(u: Double, v: Double, why: String, done: (ZoomProbe.Focus) -> Unit)
        /** Probe frames at the camera's zoom, after the dropped ones (null = none came). */
        fun frames(done: (Grab?) -> Unit)
        /** The worker measures [grab] as [role], looking for the card in [rect] (`[x, y, w, h]`; null = the Area, else the frame). */
        fun measure(role: Role, grab: Grab, rect: IntArray?, done: (Measured?) -> Unit)
        /** The probe is over: [verified] = the restore found the same card in the same place (rebase it), then endProbe. */
        fun end(verified: Boolean, why: String)
        fun later(ms: Long, block: () -> Unit)
        fun now(): Long
        fun log(line: String)
        fun progress(p: Progress)
        fun finished(o: Outcome)
        fun facts(): ZoomProbe.Facts
    }

    private enum class State { IDLE, CHECKING, LOCKING, LOCATING, FOCUSING, LADDER, REFOCUS, RESTORING, DONE }

    private var state = State.IDLE
    /** Bumped whenever a step is left for good (the restore, an abort): answers issued before are stale. */
    private var epoch = 0
    /** The one sequential answer awaited (a timer, a check, frames, a focus pass): anything else is stale. */
    private var token = 0
    private var finished = false
    private var began = false
    private var aeLocked = false
    private var afRan = false
    private lateinit var facts: ZoomProbe.Facts
    private var plan: ZoomProbe.Plan? = null
    private var rungs: Array<ZoomProbe.Rung?> = emptyArray()
    private var locked: ZoomProbe.Locked? = null
    private var focus1: ZoomProbe.Focus? = null
    private var refocusFocus: ZoomProbe.Focus? = null
    private var refocusRung: ZoomProbe.Rung? = null
    private var startThumb: FloatArray? = null
    /** The LOCATE frames at 1×: the restore's reference when the ladder never started (the AF pass failed). */
    private var locateRung: ZoomProbe.Rung? = null
    private var locateThumb: FloatArray? = null
    private var sameCardAs: Int? = null
    /** The rung being asked (for the progress and the STOPPED line). */
    private var at = 0
    private var outstanding = 0
    private var ladderAsked = false
    private var resumeAt: Int? = null
    private var ladderEnded = false

    // The awaited zoom answer: only [awaitZ], once.
    private var awaitZ: Double? = null
    private var zoomNext: ((Answer) -> Unit)? = null
    private var zoomAskedAt = 0L

    private class Answer(val camera: Double, val readBack: Double?, val error: String?, val ms: Long)

    val running: Boolean get() = began && !finished

    // ── inputs ──

    /** Run it (once). */
    fun start() {
        if (state != State.IDLE) return
        facts = port.facts()
        port.begin("run $run")?.let { why ->
            port.log("probe refused — $why; nothing changed")
            state = State.DONE; finish(Kind.REFUSED, why, null)
            return
        }
        began = true
        port.later(BUDGET_MS) { if (!finished && state != State.RESTORING && state != State.DONE) onBudget() }
        state = State.CHECKING
        val t = ++token
        port.check { answer ->
            if (t != token || state != State.CHECKING) return@check
            if (answer != null) {
                port.log("probe refused — $answer; nothing changed")
                // The camera never moved and nothing was locked: end the probe on the identical mapping.
                state = State.DONE
                port.end(false, "zoom probe refused")
                finish(Kind.REFUSED, answer, null)
            } else lock()
        }
    }

    /** The coordinator's answer to [Port.zoomTo] (ZoomCoordinator.Port.probeAt, posted by the glue). */
    fun onZoom(gen: Int, asked: Double, camera: Double, readBack: Double?, error: String?) {
        val want = awaitZ ?: return
        if (asked != want) return
        awaitZ = null
        val next = zoomNext ?: return
        zoomNext = null
        next(Answer(camera, readBack, error, port.now() - zoomAskedAt))
    }

    /** The owner cancelled (Cancel probe, or any control the touch policy turns into a cancel). */
    fun cancel(why: String) {
        if (!began || finished || state == State.RESTORING || state == State.DONE) return
        port.log("probe run $run CANCELLED at ${where()} — $why")
        restore(afRan, Kind.CANCELLED, "Zoom probe cancelled ($why) — nothing filed")
    }

    /** The coordinator stopped the probe (a lifecycle input): stop at once — no restore, no end, no rebase. */
    fun aborted(why: String) {
        if (!began || finished) return
        epoch++; token++; awaitZ = null; zoomNext = null
        state = State.DONE
        if (aeLocked) { port.aeLock(false); aeLocked = false }
        port.log("probe run $run STOPPED at ${where()} — $why (nothing filed; the scanned scene kept as before)")
        finish(Kind.STOPPED, "Zoom probe stopped — $why. Nothing was filed; Run again when ready", ZoomProbe.stopped(run, plan, why, facts))
    }

    // ── the steps ──

    private fun where(): String = plan?.let { if (state == State.LADDER || state == State.REFOCUS) "rung ${at + 1}/${it.order.size}" else state.name.lowercase(Locale.ROOT) }
        ?: state.name.lowercase(Locale.ROOT)

    private fun wait(ms: Long, then: () -> Unit) {
        val t = ++token
        port.later(ms) { if (t == token) then() }
    }

    private fun zoom(z: Double, then: (Answer) -> Unit) {
        val t = ++token
        awaitZ = z; zoomNext = then; zoomAskedAt = port.now()
        port.later(ZOOM_GUARD_MS) {
            if (t == token && awaitZ == z) onZoom(-1, z, z, null, ZoomCoordinator.PROBE_NO_ANSWER)
        }
        port.zoomTo(z)
    }

    private fun frames(then: (Grab?) -> Unit) {
        val t = ++token
        var done = false
        port.later(FRAMES_GUARD_MS) { if (t == token && !done) { done = true; then(null) } }
        port.frames { g -> if (t == token && !done) { done = true; then(g) } }
    }

    private fun lock() {
        state = State.LOCKING
        port.aeLock(true); aeLocked = true
        wait(AE_SETTLE_MS) { locate(0) }
    }

    private fun locate(tries: Int) {
        state = State.LOCATING
        progress(null, "finding the card at 1×")
        zoom(1.0) { _ ->
            frames { g ->
                if (g == null) return@frames if (tries == 0) locate(1) else noCard()
                val e = epoch
                port.measure(Role.LOCATE, g, null) { m ->
                    if (e != epoch || state != State.LOCATING) return@measure
                    val found = m?.frames?.mapNotNull { it.quad }.orEmpty()
                    if (found.isEmpty()) { if (tries == 0) locate(1) else noCard(); return@measure }
                    val q = found.sortedBy { ZoomProbe.longSide(it) }[found.size / 2]
                    locateRung = ZoomProbe.Rung(-1, 1.0, 1.0, null, null, m!!.frames, g.skipped, g.skippedAtNew, m.w, m.h, m.ms)
                    locateThumb = m.thumbnail
                    planFor(q, m.w, m.h)
                }
            }
        }
    }

    private fun noCard() {
        port.log("probe refused — no card found at 1× (2 tries); nothing changed")
        restore(false, Kind.REFUSED, "No card found at 1× — put a card near the middle and Run again")
    }

    private fun planFor(q: FloatArray, w: Int, h: Int) {
        when (val p = ZoomProbe.plan(q, w, h, facts.lensMax, seed)) {
            is ZoomProbe.Refusal -> {
                port.log("probe refused — ${p.words}; nothing changed")
                restore(false, Kind.REFUSED, p.words)
            }
            is ZoomProbe.Plan -> {
                plan = p
                rungs = arrayOfNulls(p.order.size)
                port.log(ZoomProbe.startLine(run, facts, p))
                focus(p)
            }
        }
    }

    private fun focus(p: ZoomProbe.Plan) {
        state = State.FOCUSING
        progress(1.0, "focusing on the card")
        val t = ++token
        var done = false
        val handle: (ZoomProbe.Focus) -> Unit = { f ->
            if (t == token && !done && state == State.FOCUSING) {
                done = true
                afRan = true
                focus1 = f
                if (!f.ok) {
                    port.log("probe run $run: the 1× focus pass did not focus (${f.state}) — restoring")
                    restore(true, Kind.REFUSED, "Couldn't focus on the card — more light, then Run again")
                } else ladder(0)
            }
        }
        port.later(FOCUS_GUARD_MS) { handle(ZoomProbe.Focus(false, null, null, FOCUS_GUARD_MS, "no answer in ${FOCUS_GUARD_MS} ms")) }
        port.focusOnCard(p.cx, p.cy, "zoom probe run $run", handle)
    }

    private fun ladder(i: Int) {
        val p = plan ?: return
        state = State.LADDER
        if (i >= p.order.size || ladderEnded) {
            ladderAsked = true
            if (outstanding == 0) afterLadder()
            return
        }
        at = i
        val z = p.order[i]
        progress(z, null)
        zoom(z) { a ->
            if (a.error != null) {
                val r = ZoomProbe.Rung(i, z, a.camera, a.ms, a.error, emptyList(), 0, 0, facts.w, facts.h, a.ms)
                rungs[i] = r
                port.log(ZoomProbe.rungLine(i + 1, p.order.size, r, startH(), locked))
                if (a.error != ZoomCoordinator.PROBE_NO_ANSWER) ladderEnded = true      // refused: no higher rung will do better
                ladder(i + 1)
                return@zoom
            }
            frames { g ->
                if (g == null) {
                    val r = ZoomProbe.Rung(i, z, a.camera, a.ms, "no frames in ${FRAMES_GUARD_MS} ms", emptyList(), 0, 0, facts.w, facts.h, 0)
                    rungs[i] = r
                    port.log(ZoomProbe.rungLine(i + 1, p.order.size, r, startH(), locked))
                    ladder(i + 1)
                    return@frames
                }
                outstanding++
                val e = epoch
                val role = if (i == 0) Role.START else Role.RUNG
                port.measure(role, g, ZoomProbe.searchRect(p.box, a.camera, facts.w, facts.h)) { m -> if (e == epoch) measured(i, z, a, g, m) }
                if (outstanding < MAX_QUEUED) ladder(i + 1) else resumeAt = i + 1
            }
        }
    }

    private fun startH(): Double? = rungs.firstOrNull()?.longPx

    private fun measured(i: Int, z: Double, a: Answer, g: Grab, m: Measured?) {
        val p = plan ?: return
        outstanding--
        val r = ZoomProbe.Rung(i, z, a.camera, a.ms, if (m == null) "not measured" else null, m?.frames.orEmpty(), g.skipped,
            g.skippedAtNew, m?.w ?: facts.w, m?.h ?: facts.h, m?.ms ?: 0)
        rungs[i] = r
        if (i == 0) {
            startThumb = m?.thumbnail
            if (r.cx != null) locked = ZoomProbe.Locked(r.used.mapNotNull { it.lensDpt }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] },
                r.used.firstOrNull()?.exposureNs, r.used.firstOrNull()?.iso, r.luma, r.cx, r.cy!!)
            startThumb?.let { th -> sameCardAs = earlier.firstOrNull { ZoomDetail.ncc(it.thumbnail, th) >= ZoomProbe.SAME_NCC }?.run }
        }
        port.log(ZoomProbe.rungLine(i + 1, p.order.size, r, startH(), locked))
        progress(z, r.longPx?.let { "card %.0f px".format(Locale.ROOT, it) }, i)
        resumeAt?.let { resumeAt = null; ladder(it); return }
        if (ladderAsked && outstanding == 0) afterLadder()
    }

    /** The refocus check at the highest rung the camera answered (the top, normally), then the verified restore. */
    private fun afterLadder() {
        val p = plan ?: return
        val zr = rungs.filterNotNull().filter { it.error == null && it.longPx != null }.maxOfOrNull { it.asked }
        if (zr == null || zr <= 1.0) { restore(true, Kind.DONE, null); return }
        state = State.REFOCUS
        progress(zr, "refocus check")
        zoom(zr) { a ->
            if (a.error != null) { restore(true, Kind.DONE, null); return@zoom }
            val t = ++token
            var done = false
            val handle: (ZoomProbe.Focus) -> Unit = { f ->
                if (t == token && !done && state == State.REFOCUS) {
                    done = true
                    refocusFocus = f
                    frames { g ->
                        if (g == null) {
                            port.log(ZoomProbe.refocusLine(zr, f, null, null))
                            restore(true, Kind.DONE, null)
                            return@frames
                        }
                        val e = epoch
                        port.measure(Role.REFOCUS, g, ZoomProbe.searchRect(p.box, a.camera, facts.w, facts.h)) { m ->
                            if (e != epoch || state != State.REFOCUS) return@measure
                            val r = ZoomProbe.Rung(p.order.size, zr, a.camera, a.ms, if (m == null) "not measured" else null, m?.frames.orEmpty(),
                                g.skipped, g.skippedAtNew, m?.w ?: facts.w, m?.h ?: facts.h, m?.ms ?: 0, afterRefocus = true)
                            refocusRung = r
                            port.log(ZoomProbe.refocusLine(zr, f, rungs.filterNotNull().lastOrNull { it.asked == zr && it.longPx != null }, r))
                            restore(true, Kind.DONE, null)
                        }
                    }
                }
            }
            port.later(FOCUS_GUARD_MS) { handle(ZoomProbe.Focus(false, null, null, FOCUS_GUARD_MS, "no answer in ${FOCUS_GUARD_MS} ms")) }
            port.focusOnCard(ZoomFit.toViewAxis(p.cx, a.camera), ZoomFit.toViewAxis(p.cy, a.camera),
                "zoom probe refocus check at %.2f×".format(Locale.ROOT, zr), handle)
        }
    }

    /**
     * Back to the scanner's ratio and the owner's AE: verified ([verify]) = frames
     * must show the same card in the same place before the scene is rebased.
     * [kind] / [words] say how the run ends (words null = the run's own result).
     */
    private fun restore(verify: Boolean, kind: Kind, words: String?) {
        if (state == State.RESTORING || state == State.DONE) return
        state = State.RESTORING
        epoch++                                              // queued rung answers are stale now
        outstanding = 0; resumeAt = null
        val z = facts.restoreZ
        progress(z, "restoring")
        zoom(z) { a ->
            if (aeLocked) { port.aeLock(false); aeLocked = false }
            if (!verify) {
                port.end(false, "zoom probe over")
                port.log(ZoomProbe.restoreLine(z, null, false))
                conclude(kind, words)
                return@zoom
            }
            wait(RESTORE_AE_MS) {
                frames { g ->
                    if (g == null) { endRestore(z, null); conclude(kind, words); return@frames }
                    val p = plan
                    val e = epoch
                    val t = ++token
                    var answered = false
                    val handle: (Measured?) -> Unit = { m ->
                        if (e == epoch && t == token && !answered && state == State.RESTORING) {
                            answered = true
                            val r = m?.let { ZoomProbe.Rung(-1, z, a.camera, a.ms, null, it.frames, g.skipped, g.skippedAtNew, it.w, it.h, it.ms) }
                            // The 1× reference: the ladder's start, else the LOCATE frames (the AF pass ran, the ladder did not).
                            val ref = rungs.firstOrNull()?.takeIf { it.longPx != null } ?: locateRung
                            val refThumb = if (ref === locateRung) locateThumb else startThumb
                            val ncc = if (m?.thumbnail != null && refThumb != null) ZoomDetail.ncc(refThumb, m.thumbnail) else null
                            val same = if (r == null) null else ZoomProbe.sameCard(ref, r, ZoomProbe.fitH(cleanRungs()), ncc)
                            endRestore(z, same)
                            conclude(kind, words)
                        }
                    }
                    port.later(MEASURE_GUARD_MS) { handle(null) }
                    port.measure(Role.RESTORE, g, p?.let { ZoomProbe.searchRect(it.box, a.camera, facts.w, facts.h) }, handle)
                }
            }
        }
    }

    private fun cleanRungs(): List<ZoomProbe.Rung> {
        val l = locked ?: return emptyList()
        return rungs.filterNotNull().filter { ZoomProbe.validity(it, l).isEmpty() }
    }

    private fun endRestore(z: Double, same: ZoomProbe.SameCard?) {
        port.end(same?.same == true, "zoom probe over")
        port.log(ZoomProbe.restoreLine(z, same, true))
    }

    private fun conclude(kind: Kind, words: String?) {
        state = State.DONE
        val p = plan
        if (kind == Kind.DONE && p != null) {
            val r = ZoomProbe.verdict(run, p, rungs.filterNotNull() + listOfNotNull(refocusRung), focus1, refocusFocus, facts, sameCardAs)
            port.log(r.logLine())
            finish(Kind.DONE, r.words(), r)
        } else finish(kind, words ?: "Zoom probe over", null)
    }

    private fun onBudget() {
        port.log("probe run $run: the ${BUDGET_MS / 1000} s budget ran out at ${where()} — restoring")
        restore(afRan, Kind.CANCELLED, "The zoom probe ran past ${BUDGET_MS / 1000} s — stopped; nothing filed. Run again")
    }

    /** The scan screen's words: [k] = the rung they are about (default: the one being asked). */
    private fun progress(z: Double?, what: String?, k: Int = at) {
        val p = plan
        val n = p?.order?.size
        val step = if (p != null && (state == State.LADDER)) "${k + 1}/$n" else null
        val status = "Zoom probe" + (step?.let { " $it" } ?: "") + (z?.let { " · %.2f×".format(Locale.ROOT, it) } ?: "") + " — hands off the tray"
        val count = listOfNotNull(step?.let { "rung $it" }, z?.let { "%.2f×".format(Locale.ROOT, it) }, what).joinToString(" · ")
        port.progress(Progress(status, count, z))
    }

    private fun finish(kind: Kind, words: String, result: ZoomProbe.Result?) {
        if (finished) return
        finished = true
        port.finished(Outcome(kind, words, result, if (result?.valid == true) startThumb else null))
    }

    companion object {
        /** AE/AWB settle after the lock (and the owner's AE after the unlock, [RESTORE_AE_MS]). PROVISIONAL. */
        const val AE_SETTLE_MS = 300L
        const val RESTORE_AE_MS = 500L
        /** No answer to a zoom by then (the coordinator answers within its own 2 s): the rung is skipped. */
        const val ZOOM_GUARD_MS = 3_000L
        /** Probe frames by then, or the rung is skipped. PROVISIONAL. */
        const val FRAMES_GUARD_MS = 3_000L
        /** An AF pass by then, or it counts as not focused. PROVISIONAL (CameraX gives up after 5 s). */
        const val FOCUS_GUARD_MS = 6_000L
        /** The restore's measurement by then, or the restore is unverified (no rebase). PROVISIONAL. */
        const val MEASURE_GUARD_MS = 10_000L
        /** The whole run: past this, the verified restore. PROVISIONAL. */
        const val BUDGET_MS = 90_000L
        /** Rungs waiting for the worker before the camera stops moving on. */
        const val MAX_QUEUED = 2
    }
}
