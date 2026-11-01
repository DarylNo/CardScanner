package io.github.darylno.cardscanner.camera

import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.Nv21Frame
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.ZoomCoordinator
import io.github.darylno.cardscanner.core.ZoomFit
import io.github.darylno.cardscanner.core.ZoomProbe
import io.github.darylno.cardscanner.core.ZoomProbeRun
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * The zoom probe's camera side (1.1.17): [ZoomProbeRun.Port] over the zoom coordinator's
 * probe mode, the analyzer's probe tap, one AF pass on the card, the probe's AE/AWB lock
 * and the `zoom-probe` worker ([ProbeMeasure]) — plus the probe half of the coordinator's
 * Port ([probeAt], [probeEnded], [probeRebase]). CameraController builds one over CameraX
 * ([Cam]); ZoomProbeGlueTest drives the REAL coordinator, analyzer, runner and worker
 * through it with a synthetic camera. Main thread only.
 *
 * The rules it keeps:
 * - Every coordinator answer is POSTED to the runner (the coordinator answers "the camera
 *   is already there" inside probeTo, and the runner must not re-enter itself), and so is
 *   every analyzer / worker answer.
 * - Every rung is asked with the browser's picture stopped first
 *   ([ScanAnalyzer.stopSnapshots]) and re-opened at the zoom the camera ANSWERED
 *   ([ScanAnalyzer.snapshotsAt]); an abort and the end stop it too (the coordinator's gate
 *   closure is idempotent: nothing else would).
 * - Once the coordinator has stopped the probe (a lifecycle input — its abort is posted
 *   behind), nothing here touches the camera or the analyzer: a step the runner takes
 *   before the abort lands must not clear a drawing's picture, lock the exposure or move
 *   the lens; and the end then reports false (no rebase).
 * - The rebase is the coordinator's to ask ([probeRebase], only while the probe runs).
 * It never calls ScanAnalyzer.fire, Sink.onCaptureRequest or the upload queue: it files nothing.
 */
internal class ZoomProbeGlue(private val analyzer: ScanAnalyzer, private val cam: Cam, private val log: DebugLog = DebugLog.global) {

    /** What the glue needs of the camera (CameraController over CameraX). Main thread. */
    interface Cam {
        /** Run [block] on the main thread after the current step (FIFO). */
        fun post(block: () -> Unit)
        /** …after [ms] — dropped once the camera is released. */
        fun postDelayed(ms: Long, block: () -> Unit)
        fun now(): Long
        /** A camera is bound and not released. */
        fun ready(): Boolean
        /** Re-apply AE/AWB lock as the owner's setting OR [probeLocked]. */
        fun applyAeLock()
        /**
         * One AF pass locked at ([u], [v]) of the view (fractions), logged as [why]. False = it
         * cannot run (no frame geometry / camera). [done] on the main thread: true = FOCUSED,
         * false = not focused, null = cancelled / superseded.
         */
        fun focus(u: Double, v: Double, why: String, done: (Boolean?) -> Unit): Boolean
        /** The lens position now (dioptres), or null. */
        fun lensDpt(): Double?
        /** The CaptureResult of the frame with this sensor timestamp, or null. */
        fun metaAt(timestampNs: Long): FrameMetaRing.Meta?
        /** The sensor's active array `[w, h]` (the crop region's frame), or null. */
        fun activeArray(): IntArray?
        /** The stored BASE Area (null = the whole frame). */
        fun baseArea(): RoiFrac?
        /** High resolution (2048×1536: fewer frames per rung). */
        fun highRes(): Boolean
        /** What the probe logs about the camera, returning to [restoreZ]. */
        fun facts(restoreZ: Double): ZoomProbe.Facts
        /** The run ended after an AF pass: the next card gets its own focus pass (no Area-centre lock). */
        fun armCardFocus(why: String)
        /** The `zoom-probe` worker (never the capture pipeline). */
        fun worker(): Executor
    }

    /** The coordinator (built with a Port that forwards its probe half here). */
    lateinit var zoom: ZoomCoordinator

    /** The run in progress, or null. */
    private var run: ZoomProbeRun? = null
    /** The probe holds AE/AWB locked whatever the owner's setting (CameraController ORs it in). */
    var probeLocked = false
        private set
    /** The run started an AF pass: the lens moved. */
    private var afRan = false

    /** A rung's frames on their way to the worker ([ZoomProbeRun.Grab.token]); [at] = when they reached the main thread. */
    private class Frames(val frames: List<Nv21Frame>, val at: Long)

    val running: Boolean get() = run?.running == true

    /** See CameraController.startZoomProbe. */
    fun start(n: Int, earlier: List<ZoomProbeRun.Earlier>, l: CameraController.ProbeListener, seed: Long): Boolean {
        if (!cam.ready() || run != null) return false
        afRan = false
        val side = Side(l)
        val r = ZoomProbeRun(side, n, seed, earlier)
        side.runner = r
        run = r
        r.start()
        return true
    }

    /** The owner cancelled: the run ends through its verified restore. */
    fun cancel(why: String) { run?.cancel(why) }

    /** Stop the run AT ONCE — no restore, no rebase — before a rebind (it re-learns anyway) or the release. */
    fun stopNow(why: String) { run?.aborted(why) }

    // ── the coordinator Port's probe half ──

    fun probeAt(gen: Int, asked: Double, camera: Double, readBack: Double?, error: String?) {
        cam.post {
            // An answer that finds the probe already stopped is dropped: the runner's abort is posted right
            // behind it and must not be preceded by one more step on the camera.
            if (!zoom.probing) return@post
            // The browser's picture follows the rung the camera ANSWERED (each ask stopped it first).
            if (error == null) analyzer.snapshotsAt(camera)
            run?.onZoom(gen, asked, camera, readBack, error)
        }
    }

    fun probeEnded(why: String) {
        // At once, before the coordinator goes on with today's path: the probe's frames and the rung's
        // picture stop. Then the runner stops — no restore, no rebase.
        analyzer.cancelProbeFrames()
        analyzer.stopSnapshots()
        cam.post { run?.aborted(why) }
    }

    fun probeRebase(@Suppress("UNUSED_PARAMETER") why: String) = analyzer.rebaseAfterProbe()

    private inner class Side(private val l: CameraController.ProbeListener) : ZoomProbeRun.Port {
        /** The run this side serves (set right after it is built). */
        lateinit var runner: ZoomProbeRun

        override fun begin(why: String): String? {
            if (!cam.ready()) return "The camera is not ready — try again in a second"
            if (analyzer.lastFrameGeometry == null) return "No picture from the camera yet — try again in a second"
            if (zoom.beginProbe(why)) return null
            val st = zoom.state()
            return when {
                st.drawing -> "Finish drawing the Area first"
                st.settling -> "The zoom is still settling — try again in a second"
                else -> "The camera is not ready — try again in a second"
            }
        }

        override fun check(answer: (String?) -> Unit) = analyzer.canProbe { a -> cam.post { answer(a) } }

        override fun aeLock(on: Boolean) {
            if (on && !zoom.probing) return
            probeLocked = on
            cam.applyAeLock()
        }

        /** The snapshot slot stops first: frames between the ask and the camera's answer are at neither zoom. */
        override fun zoomTo(z: Double) {
            if (!zoom.probing) return
            analyzer.stopSnapshots()
            zoom.probeTo(z)
        }

        override fun focusOnCard(u: Double, v: Double, why: String, done: (ZoomProbe.Focus) -> Unit) {
            val before = cam.lensDpt()
            val t0 = cam.now()
            val started = zoom.probing && cam.focus(u.coerceIn(0.0, 1.0), v.coerceIn(0.0, 1.0), why) { ok ->
                val ms = cam.now() - t0
                val f = ZoomProbe.Focus(ok == true, before, cam.lensDpt(), ms,
                    FocusLog.classify(ok, ms, superseded = false).word.uppercase(Locale.ROOT))
                cam.post { done(f) }
            }
            if (started) afRan = true
            else cam.post { done(ZoomProbe.Focus(false, before, before, 0, "NO CAMERA")) }
        }

        override fun frames(done: (ZoomProbeRun.Grab?) -> Unit) {
            if (!zoom.probing) { cam.post { done(null) }; return }
            val camZ = zoom.state().ratio
            val activeW = cam.activeArray()?.getOrNull(0)
            analyzer.probeFrames(ScanAnalyzer.SETTLE_DROP_FRAMES, if (cam.highRes()) FRAMES_HIGH else FRAMES) { skipped, frames ->
                cam.post {
                    if (frames.isEmpty()) { done(null); return@post }
                    // The dropped frames' own reports: how many were already at the answered zoom (the settle evidence).
                    val atNew = skipped.count { ts ->
                        cam.metaAt(ts)?.frameZoom(activeW)?.let { abs(it - camZ) <= ZoomProbe.OTHER_ZOOM_META * camZ } == true
                    }
                    done(ZoomProbeRun.Grab(frames.size, skipped.size, atNew, Frames(frames, cam.now())))
                }
            }
        }

        override fun measure(role: ZoomProbeRun.Role, grab: ZoomProbeRun.Grab, rect: IntArray?, done: (ZoomProbeRun.Measured?) -> Unit) {
            val pf = grab.token as? Frames
            if (pf == null) { cam.post { done(null) }; return }
            val area = ZoomFit.toView(cam.baseArea(), zoom.state().ratio)    // "the Area" at the camera's zoom (LOCATE: 1×, the base Area)
            val act = cam.activeArray()
            val thumb = role == ZoomProbeRun.Role.LOCATE || role == ZoomProbeRun.Role.START || role == ZoomProbeRun.Role.RESTORE
            // The frames' own CaptureResults, read once late ones have landed — here, before the worker's
            // queue could age them out of the ~2 s ring.
            val wait = (META_WAIT_MS - (cam.now() - pf.at)).coerceAtLeast(0)
            cam.postDelayed(wait) {
                val metas = pf.frames.map { cam.metaAt(it.timestampNs) }
                val queued = runCatching {
                    cam.worker().execute {
                        val m = runCatching { ProbeMeasure.measure(pf.frames, metas, rect, area, act?.getOrNull(0), act?.getOrNull(1), thumb) }
                            .onFailure { e -> runCatching { log.w("probe", "measuring the ${role.name.lowercase(Locale.ROOT)} frames failed: $e") } }
                            .getOrNull()
                        cam.post { done(m) }
                    }
                }.isSuccess
                if (!queued) done(null)
            }
        }

        override fun end(verified: Boolean, why: String): Boolean {
            // A lifecycle input stopped the probe first (its abort is posted behind this answer): nothing to
            // end, and nothing to clear — the slot may be a drawing's picture by now.
            if (!zoom.probing) return false
            // The rung's picture and any probe frames stop before the end's map (or the target's request,
            // which the coordinator's idempotent gate closure would never pass on to the analyzer).
            analyzer.stopSnapshots()
            analyzer.cancelProbeFrames()
            return zoom.endProbe(why, rebase = verified)
        }

        override fun later(ms: Long, block: () -> Unit) = cam.postDelayed(ms, block)
        override fun now(): Long = cam.now()
        override fun log(line: String) { runCatching { log.i("probe", line) } }

        override fun progress(p: ZoomProbeRun.Progress) {
            if (zoom.probing && run === runner) zoom.probeNote = p.count.ifEmpty { p.status }
            l.onProgress(p)
        }

        override fun finished(o: ZoomProbeRun.Outcome) {
            if (run === runner) run = null
            if (probeLocked) { probeLocked = false; cam.applyAeLock() }
            // The lens moved for the probe: the next card gets its own pass — and NO Area-centre lock, which
            // with the scanned card still on the tray could read as a swap (plan step 10.6).
            if (afRan) cam.armCardFocus("first card after the zoom probe")
            afRan = false
            l.onFinished(o)
        }

        override fun facts(): ZoomProbe.Facts = cam.facts(zoom.target().z)
    }

    companion object {
        /** The probe's frames per rung, after ScanAnalyzer.SETTLE_DROP_FRAMES dropped ones (High res: fewer — 4.7 MB each). PROVISIONAL. */
        const val FRAMES = 6
        const val FRAMES_HIGH = 4
        /** A rung's CaptureResults are read this long after its last frame came (a result can lag its image). PROVISIONAL. */
        const val META_WAIT_MS = 150L
    }
}
