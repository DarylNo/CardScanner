package io.github.darylno.cardscanner.camera

import io.github.darylno.cardscanner.core.AutoScanner
import io.github.darylno.cardscanner.core.CardOutline
import io.github.darylno.cardscanner.core.CardQuad
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.Gray
import io.github.darylno.cardscanner.core.Nv21Bgr
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.RoiPx
import io.github.darylno.cardscanner.core.ZoomCoordinator
import io.github.darylno.cardscanner.core.ZoomProbe
import io.github.darylno.cardscanner.core.ZoomProbeRun
import io.github.darylno.cardscanner.core.ZoomProbeRun.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.PriorityQueue
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The zoom probe's camera side end to end, with no camera: the REAL ZoomCoordinator, the
 * REAL ScanAnalyzer (its gate, its probe tap, its rebase), the REAL ZoomProbeRun and the
 * REAL worker (ProbeMeasure: CardQuad.find on the frames) through [ZoomProbeGlue] — only
 * the camera is synthetic: a 960×720 tray with a card (the reference's detect fixture's
 * card), each zoom a centred crop of it, 30 frames a second, each frame's CaptureResult
 * reporting its own zoom, the camera moving 70 ms after a zoom is asked and answering at
 * 150 ms (frames in between are at the new zoom, not yet confirmed). Main thread,
 * the analysis thread and the worker are one simulated clock.
 *
 * What must hold: a probe on a scanned card files nothing — no tick, no burst, while it
 * runs and after — goes 1× → the ladder → back, AE/AWB locked for it and back, one AF pass
 * at 1× and the refocus check; its verified restore rebases (and the CONTROL without the
 * rebase shoots the still card again); the browser's picture is only ever labelled with
 * the zoom its frame was at; a screen stop aborts it with no rebase (a swapped card scans);
 * a cancel goes through the verified restore; a refusal changes nothing.
 */
class ZoomProbeGlueTest {
    companion object {
        private const val W = 960
        private const val H = 720
        /** The card pasted upright, centred: 240×336 px (5:7) — 47 % of the frame's height, so the ladder tops at 1.8×. */
        private const val CW = 240
        private const val CH = 336
        private lateinit var empty: Mat
        private lateinit var card: Mat
        /** The same card as the probe's AF pass / AE leave it: darker (a stand-in for "looks different afterwards"). */
        private lateinit var cardAfter: Mat
        /** Another card in the same place (the same art turned round). */
        private lateinit var card2: Mat

        @BeforeClass @JvmStatic fun setUp() {
            nu.pattern.OpenCV.loadLocally()
            val dir = listOf("../core/src/test/resources/detect", "core/src/test/resources/detect", "android/core/src/test/resources/detect")
                .map(::File).first { File(it, "expected.json").isFile }
            val scene = Imgcodecs.imread(File(dir, "dark_tray_white_border.png").path)
            val q = JSONObject(File(dir, "expected.json").readText()).getJSONObject("scenes").getJSONObject("dark_tray_white_border").getJSONArray("quad")
            val quad = FloatArray(8) { q.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
            val face = CardQuad.warpCard(scene, quad, CW, CH)
            val tray = Core.mean(scene.submat(Rect(0, 0, 120, 60))).let { Scalar(it.`val`[0], it.`val`[1], it.`val`[2]) }
            empty = Mat(H, W, CvType.CV_8UC3, tray)
            fun paste(f: Mat): Mat = empty.clone().also { m -> f.copyTo(m.submat(Rect((W - CW) / 2, (H - CH) / 2, CW, CH))) }
            card = paste(face)
            cardAfter = paste(Mat().also { face.convertTo(it, -1, 0.55, 0.0) })
            card2 = paste(Mat().also { Core.flip(face, it, -1) })
        }
    }

    /** A stand-in for CardOutline.find on the detection sample: the bright pixels' box (the white border on the dark tray). */
    private val brightBox: (Gray, Int, Int, RoiFrac?) -> CardOutline.Outline? = { g, fw, fh, roi ->
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (y in 0 until g.h) for (x in 0 until g.w) if (g.px[y * g.w + x] > 100) {
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        if (x1 < 0) null else {
            val sq = floatArrayOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), x0.toFloat(), y1.toFloat())
            val area = roi?.toPixels(fw, fh) ?: RoiPx(0, 0, fw, fh)
            CardOutline.Outline(CardOutline.toFrameFractions(sq, g.w, g.h, area, fw, fh), sq, 0, 0)
        }
    }

    private class Sink : ScanAnalyzer.Sink {
        val updates = mutableListOf<DetectionUpdate>()
        val bursts = mutableListOf<CaptureBurst>()
        val errors = mutableListOf<Throwable>()
        var resync: () -> Unit = {}
        override fun onDetection(update: DetectionUpdate) { updates += update }
        override fun onCaptureRequest(request: CaptureBurst) { bursts += request }
        override fun onAnalyzerError(error: Throwable) { errors += error }
        override fun onZoomResync() = resync()
    }

    /** The synthetic camera, the main thread, the analysis thread and the worker on one clock (ms). */
    private inner class Rig(val area: RoiFrac = RoiFrac(0.2, 0.15, 0.8, 0.85), val allowRebase: Boolean = true, val workerMs: Long = 120) {
        var now = 0L
        private var seq = 0
        private val tasks = PriorityQueue<Triple<Long, Int, () -> Unit>>(compareBy({ it.first }, { it.second }))
        private var nextFrame = 33L
        val log = DebugLog(3000, clock = { now })
        val sink = Sink()
        val analyzer = ScanAnalyzer(Executor { it.run() }, sink, outlineFinder = brightBox, shadowMode = false, log = log)
        var scene: Mat = empty
        var cameraZ = 1.0
        var streaming = true
        var lens = 3.10
        val metas = HashMap<Long, FrameMetaRing.Meta>()
        private val planes = HashMap<Pair<Mat, Double>, FakePlanes>()
        // What the glue did to the camera.
        val aeLocks = mutableListOf<Boolean>()
        val focuses = mutableListOf<String>()
        val armed = mutableListOf<String>()
        val maps = mutableListOf<String>()
        var rebases = 0
        val progresses = mutableListOf<ZoomProbeRun.Progress>()
        val outcomes = mutableListOf<ZoomProbeRun.Outcome>()
        /** Browser pictures: (the zoom the analyzer labelled it with, the zoom its frame was really at). */
        val snaps = mutableListOf<Pair<Double, Double>>()
        /** The browser also asks for a picture each time the camera is moving to a new zoom. */
        var snapOnMove = false
        /** The probe's AF pass puts the card in this state (the lens moved; null = it looks the same). */
        var afterFocus: Mat? = cardAfter
        /** The zoom answers the camera has given (each [ZoomCoordinator.onApplied]). */
        var answers = 0

        fun later(ms: Long, block: () -> Unit) { tasks += Triple(now + ms, seq++, block) }

        lateinit var coord: ZoomCoordinator
        val glue: ZoomProbeGlue = ZoomProbeGlue(analyzer, object : ZoomProbeGlue.Cam {
            override fun post(block: () -> Unit) = later(0, block)
            override fun postDelayed(ms: Long, block: () -> Unit) = later(ms, block)
            override fun now(): Long = now
            override fun ready(): Boolean = true
            override fun applyAeLock() { aeLocks += glue.probeLocked }
            override fun focus(u: Double, v: Double, why: String, done: (Boolean?) -> Unit): Boolean {
                focuses += "%.2f,%.2f %s".format(u, v, why)
                later(400) {
                    lens = 4.21
                    afterFocus?.let { if (scene === card) scene = it }
                    done(true)
                }
                return true
            }
            override fun lensDpt(): Double = lens
            override fun metaAt(timestampNs: Long): FrameMetaRing.Meta? = metas[timestampNs]
            override fun activeArray(): IntArray = intArrayOf(4160, 3120)
            override fun baseArea(): RoiFrac = area
            override fun highRes(): Boolean = false
            override fun facts(restoreZ: Double) = ZoomProbe.Facts("0", W, H, false, 4160, 3120, 2.6, 10.0, restoreZ)
            override fun armCardFocus(why: String) { armed += why }
            // The worker: its own thread — here each job takes [workerMs] of the clock (120 ms), in order.
            private var workerFree = 0L
            override fun worker(): Executor = Executor { job ->
                workerFree = max(workerFree, now) + workerMs
                later(workerFree - now) { job.run() }
            }
        }, log = log)

        init {
            coord = ZoomCoordinator(object : ZoomCoordinator.Port {
                // The camera moves 70 ms after the ask and answers at 150 ms: frames in between are at the new zoom, unconfirmed.
                // With [snapOnMove] the browser asks for a picture while the camera is moving (80 ms: moved, not answered).
                override fun request(gen: Int, ratio: Double) {
                    later(70) { cameraZ = ratio }
                    if (snapOnMove) later(80) { askSnapshot() }
                    later(150) { answers++; coord.onApplied(gen, ratio) }
                }
                override fun closeGate(why: String) = analyzer.closeZoomGate(why)
                override fun map(ratio: Double, view: RoiFrac?, settle: Boolean, why: String) {
                    maps += "%.2f %s%s".format(ratio, view?.encode() ?: "full", if (settle) " settle" else "")
                    analyzer.zoomMapped(ratio, view, settle, why)
                }
                override fun drawingReady(ratio: Double) = analyzer.snapshotsAt(ratio)
                override fun later(ms: Long, block: () -> Unit) = this@Rig.later(ms, block)
                override fun readBack(): Double? = null
                override fun probeAt(gen: Int, asked: Double, camera: Double, readBack: Double?, error: String?) =
                    glue.probeAt(gen, asked, camera, readBack, error)
                override fun probeEnded(why: String) = glue.probeEnded(why)
                override fun probeRebase(why: String) { rebases++; if (allowRebase) glue.probeRebase(why) }
            }, log = { log.i("zoom", it) })
            glue.zoom = coord
            sink.resync = { later(0) { coord.cameraStarted("frames arriving at the closed zoom gate") } }
            coord.setArea(area)
            coord.cameraBound(2.6, 10.0)                       // the switch off (the probe step's own setting): mapped at 1× at once
        }

        val listener = object : CameraController.ProbeListener {
            override fun onProgress(p: ZoomProbeRun.Progress) { progresses += p }
            override fun onFinished(o: ZoomProbeRun.Outcome) { outcomes += o }
        }

        /** The camera's frame now: [scene] seen at [cameraZ] (a centred crop, as CameraX zooms). */
        private fun frame(): FakePlanes = planes.getOrPut(scene to cameraZ) {
            val z = cameraZ
            val bgr = if (z == 1.0) scene else {
                val cw = (W / z).roundToInt(); val ch = (H / z).roundToInt()
                Mat().also { Imgproc.resize(scene.submat(Rect((W - cw) / 2, (H - ch) / 2, cw, ch)), it, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR) }
            }
            val f = Nv21Bgr.fromUprightBgr(bgr, 0)
            FakePlanes(f.data, f.width, f.height)
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val t = tasks.peek()?.first ?: Long.MAX_VALUE
                val next = min(t, nextFrame)
                if (next > end) break
                now = next
                if (t <= nextFrame) { tasks.poll()!!.third() } else {
                    nextFrame += 33
                    if (!streaming) continue
                    val ts = now * 1_000_000L
                    metas[ts] = FrameMetaRing.Meta(ts, 4, lens.toFloat(), 16_666_667, 400, zoomRatio = cameraZ.toFloat(), crop = intArrayOf(0, 0, 4160, 3120))
                    analyzer.process(frame(), 0, ts)
                }
            }
            now = end
        }

        fun until(limit: Long = 200_000, cond: () -> Boolean) { val end = now + limit; while (!cond() && now < end) advance(33) }

        /** The browser's ↻: the next picture, with the zoom the analyzer labels it with and the zoom its frame was at. */
        fun askSnapshot() = analyzer.requestSnapshotAt { f, z -> snaps += z to metas[f.timestampNs]!!.zoomRatio!!.toDouble() }

        /** The browser asks for a picture every [everyMs] until [cond]. */
        fun watchSnapshots(everyMs: Long, cond: () -> Boolean) {
            val end = now + 200_000
            while (!cond() && now < end) {
                askSnapshot()
                advance(everyMs)
            }
        }

        /** Learn the empty tray, put the card down: scanned once, the scanner waiting for the next card. */
        fun scanTheCard() {
            scene = empty; advance(2_500)
            scene = card; advance(2_000)
            assertEquals("the card is scanned once before the probe", 1, sink.bursts.size)
            assertTrue(sink.updates.last().event is AutoScanner.Event.AwaitingNext)
        }

        fun probeLines() = log.all().filter { it.tag == "probe" }.map { it.msg }
    }

    @Test fun aProbeOnAScannedCardFilesNothingLocksAndRestoresAndRebases() {
        val r = Rig()
        r.scanTheCard()
        r.snapOnMove = true
        val ticks = r.sink.updates.size
        val changes = r.coord.changes
        assertTrue(r.glue.start(1, emptyList(), r.listener, seed = 7))
        assertFalse("one run at a time", r.glue.start(2, emptyList(), r.listener, seed = 8))
        r.watchSnapshots(300) { r.outcomes.isNotEmpty() }
        val lines = r.probeLines()
        println(lines.joinToString("\n"))
        val o = r.outcomes.single()
        assertEquals(lines.joinToString("\n"), Kind.DONE, o.kind)
        val res = o.result
        assertNotNull(res)
        assertTrue("valid: ${res!!.logLine()}", res.valid)
        assertEquals("the synthetic zoom IS the centred crop: ${res.logLine()}", true, res.centred)
        assertEquals("CONTROL_ZOOM_RATIO on every frame", true, res.reportsZoomRatio)
        assertNotNull("a valid run keeps its 1× card", o.thumbnail)
        // Nothing ticked, nothing was shot.
        assertEquals("no tick during the probe", ticks, r.sink.updates.size)
        assertEquals(1, r.sink.bursts.size)
        // AE/AWB locked for the probe and given back; one AF pass at 1× on the card, one at the top.
        assertEquals(listOf(true, false), r.aeLocks)
        assertEquals(r.focuses.toString(), 2, r.focuses.size)
        assertTrue(r.focuses[0], r.focuses[0].startsWith("0.50,0.50 zoom probe run 1"))
        assertTrue(r.focuses[1], r.focuses[1].contains("refocus check at 1.80×"))
        assertEquals(listOf("first card after the zoom probe"), r.armed)
        // The verified restore: the same card in the same place → the rebase, then the IDENTICAL mapping.
        assertTrue(lines.joinToString("\n"), lines.any { it.startsWith("restore at 1.00×: the same card in the same place") })
        assertEquals(1, r.rebases)
        assertFalse(r.coord.probing)
        assertEquals("no re-learn", changes, r.coord.changes)
        assertEquals(r.maps.last(), "1.00 ${r.area.encode()} settle", r.maps.last())
        assertTrue("the log stays short: ${lines.size}", lines.size <= 30)
        // The browser's picture was only ever labelled with the zoom its frame was at — and followed the rungs.
        assertTrue("pictures: ${r.snaps}", r.snaps.all { (label, real) -> kotlin.math.abs(label - real) < 1e-4 })   // the meta is a Float
        assertTrue("pictures at several rungs: ${r.snaps}", r.snaps.map { it.first }.distinct().size >= 3)
        // After: the card (which looks different now) is NOT scanned again; lifted → removed; the next card → shot once.
        val after = r.sink.updates.size
        r.advance(4_000)
        assertTrue(r.sink.updates.size > after)
        assertTrue(r.sink.updates.drop(after).none { it.event is AutoScanner.Event.Learning || it.event is AutoScanner.Event.NextCard })
        assertEquals(1, r.sink.bursts.size)
        r.scene = empty; r.advance(1_000)
        assertTrue(r.sink.updates.drop(after).any { (it.event as? AutoScanner.Event.NextCard)?.removed == true })
        r.scene = card2; r.advance(2_000)
        assertEquals(2, r.sink.bursts.size)
        assertTrue(r.sink.errors.isEmpty())
        assertTrue(r.analyzer.stats(), r.analyzer.stats().contains("probe frames"))
    }

    /**
     * Review of c758bc7 (the probe's frame memory): a worker slower than the camera — the N200 estimate, ~1 s a rung —
     * holds TWO rungs' NV21 copies at once (the camera moves on while it measures, ZoomProbeRun.MAX_QUEUED), never
     * more; the run still finishes valid, and its peak is logged, so a rig report shows the real figure. A worker
     * faster than the camera holds one rung's.
     */
    @Test fun aSlowWorkerHoldsAtMostTwoRungsOfFrameCopiesAndTheRunSaysSo() {
        val frameMb = io.github.darylno.cardscanner.core.Nv21Frame.byteCount(W, H) / 1e6
        for ((workerMs, rungs) in listOf(1_000L to 2, 120L to 1)) {
            val r = Rig(workerMs = workerMs)
            r.scanTheCard()
            assertTrue(r.glue.start(1, emptyList(), r.listener, seed = 7))
            r.until { r.outcomes.isNotEmpty() }
            val o = r.outcomes.single()
            assertEquals("worker $workerMs ms: ${r.probeLines().joinToString("\n")}", Kind.DONE, o.kind)
            assertTrue(o.result!!.logLine(), o.result!!.valid)
            val n = rungs * ZoomProbeGlue.FRAMES
            assertEquals("worker $workerMs ms", listOf("probe run 1: frame copies — at most $n waiting for the worker at once " +
                "(%.1f MB of NV21; it lags the camera by up to 2 rungs)".format(java.util.Locale.ROOT, n * frameMb)),
                r.probeLines().filter { it.contains("frame copies") })
            assertTrue("the log stays short: ${r.probeLines().size}", r.probeLines().size <= 30)
            assertEquals("nothing filed", 1, r.sink.bursts.size)
        }
    }

    /** THE CONTROL: the same run with the coordinator's rebase withheld — the still card, looking different, is shot again. */
    @Test fun withoutTheRebaseTheStillCardIsShotAgain() {
        val r = Rig(allowRebase = false)
        r.scanTheCard()
        assertTrue(r.glue.start(1, emptyList(), r.listener, seed = 7))
        r.until { r.outcomes.isNotEmpty() }
        assertEquals(Kind.DONE, r.outcomes.single().kind)
        assertEquals("the restore matched: the rebase WAS asked", 1, r.rebases)
        r.advance(4_000)
        assertEquals("…and without it the drift reads as a swap", 2, r.sink.bursts.size)
    }

    /**
     * A screen stop mid-ladder: stopped at once — AE back, no restore, no rebase; a card swapped meanwhile IS scanned.
     * Stopped right after a rung's answer (the top, 1.80×: the browser's picture follows that rung), the stop stops the
     * picture too — the closing camera's last frames (CameraX dropped the zoom as it detached) and the reopened one's
     * before the gate opens are never labelled with the stale rung: the coordinator's gate closure is idempotent, so
     * only the glue's abort clears the slot (plan correction 10; review of c758bc7).
     */
    @Test fun aScreenStopMidLadderNeverRebasesAndASwappedCardScans() {
        val r = Rig()
        r.scanTheCard()
        assertTrue(r.glue.start(1, emptyList(), r.listener, seed = 7))
        r.until { r.progresses.any { it.status.startsWith("Zoom probe 2/") } }
        assertTrue("the second rung is the top: ${r.progresses.last().status}", r.progresses.last().status.startsWith("Zoom probe 2/12 · 1.80×"))
        val answered = r.answers
        r.until { r.answers > answered }                   // the camera answered the top: the picture re-opened at 1.80×
        r.coord.cameraStopped("the scan screen stopped")
        r.cameraZ = 1.0
        r.askSnapshot(); r.advance(300)                    // the closing camera's last frames, at 1×; the browser asks meanwhile
        r.streaming = false
        r.advance(1_000)
        val o = r.outcomes.single()
        assertEquals(Kind.STOPPED, o.kind)
        assertTrue(o.words, o.words.contains("the scan screen stopped"))
        assertEquals(0, r.rebases)
        assertEquals("AE given back", false, r.aeLocks.last())
        assertTrue(r.probeLines().none { it.startsWith("restore at") })
        // Away: the card is swapped. Back: today's round trip — and the new card scans.
        r.scene = card2; r.cameraZ = 1.0
        r.coord.cameraStarted("the scan screen started")
        r.askSnapshot()
        r.streaming = true
        r.advance(3_000)
        assertEquals("the swapped card IS scanned", 2, r.sink.bursts.size)
        assertTrue("a picture was taken once the camera was back: ${r.snaps}", r.snaps.isNotEmpty())
        assertTrue("every picture labelled with its frame's own zoom: ${r.snaps}",
            r.snaps.all { (label, real) -> kotlin.math.abs(label - real) < 1e-4 })
        assertTrue(r.sink.errors.isEmpty())
    }

    /** The owner's cancel mid-ladder goes through the verified restore: AE back, the same card → rebased, nothing shot. */
    @Test fun aCancelMidLadderRestoresVerifiedAndFilesNothing() {
        val r = Rig()
        r.scanTheCard()
        assertTrue(r.glue.start(1, emptyList(), r.listener, seed = 7))
        r.until { r.progresses.any { it.status.startsWith("Zoom probe 5/") } }
        assertTrue(r.glue.running)
        r.glue.cancel("Cancel probe")
        r.until { r.outcomes.isNotEmpty() }
        assertEquals(Kind.CANCELLED, r.outcomes.single().kind)
        assertFalse(r.glue.running)
        assertTrue(r.probeLines().joinToString("\n"), r.probeLines().any { it.startsWith("restore at 1.00×: the same card in the same place") })
        assertEquals(1, r.rebases)
        assertEquals(listOf(true, false), r.aeLocks)
        r.advance(4_000)
        assertEquals(1, r.sink.bursts.size)
        assertTrue(r.sink.errors.isEmpty())
    }

    /** Tray still watching (the card not yet scanned): refused at the check — no AE lock, no AF, no zoom; the card then scans once. */
    @Test fun aRefusedProbeChangesNothing() {
        val r = Rig()
        r.scene = empty; r.advance(2_500)
        r.scene = card; r.advance(100)                     // just put down: not still for two ticks yet
        assertTrue(r.sink.bursts.isEmpty())
        val changes = r.coord.changes
        assertTrue(r.glue.start(1, emptyList(), r.listener, seed = 7))
        r.until { r.outcomes.isNotEmpty() }
        val o = r.outcomes.single()
        assertEquals(Kind.REFUSED, o.kind)
        assertEquals(ScanAnalyzer.PROBE_NOT_SCANNED, o.words)
        assertTrue(r.aeLocks.isEmpty() && r.focuses.isEmpty() && r.armed.isEmpty())
        assertEquals(0, r.rebases)
        assertEquals(changes, r.coord.changes)
        assertFalse(r.coord.probing)
        r.advance(2_000)
        assertEquals("the card is asked again and shot once", 1, r.sink.bursts.size)
    }
}
