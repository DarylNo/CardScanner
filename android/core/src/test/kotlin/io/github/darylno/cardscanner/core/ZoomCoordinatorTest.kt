package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom lifecycle against a fake camera: the switch off never touches the
 * camera; every camera change closes the gate first and maps with the ratio
 * read back; a re-apply after a stop maps identically (no re-learn); drawing
 * zooms out and Cancel comes back with the same mapping; stale answers are
 * ignored; "not active" is retried then falls back to 1×; a silent camera
 * times out onto what it reports.
 */
class ZoomCoordinatorTest {
    private val area = RoiFrac(0.25, 0.25, 0.75, 0.75)            // fit 2.0 → 0.95·2 = 1.90
    private val other = RoiFrac(0.2, 0.3, 0.8, 0.7)               // fit 1/(2·0.3) = 1.67 → 1.55

    private class Map(val ratio: Double, val view: RoiFrac?, val settle: Boolean)
    private class ProbeAt(val gen: Int, val asked: Double, val camera: Double, val readBack: Double?, val error: String?)

    private inner class FakePort : ZoomCoordinator.Port {
        val requests = mutableListOf<Pair<Int, Double>>()
        val closes = mutableListOf<String>()
        val maps = mutableListOf<Map>()
        var drawingReady = 0
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        var readBack: Double? = null
        /** Every call in order: "request 1.90", "close", "map 1.90", "drawing 1.00", "probeAt 1.50", "probeEnded". */
        val events = mutableListOf<String>()
        /** The probe's answers: (asked, camera, readBack, error). */
        val probeAts = mutableListOf<ProbeAt>()
        val probeEnds = mutableListOf<String>()
        override fun request(gen: Int, ratio: Double) { requests += gen to ratio; events += "request %.2f".format(ratio) }
        override fun closeGate(why: String) { closes += why; events += "close" }
        override fun map(ratio: Double, view: RoiFrac?, settle: Boolean, why: String) {
            maps += Map(ratio, view, settle); events += "map %.2f".format(ratio)
        }
        val drawingRatios = mutableListOf<Double>()
        override fun drawingReady(ratio: Double) { drawingReady++; drawingRatios += ratio; events += "drawing %.2f".format(ratio) }
        override fun probeAt(gen: Int, asked: Double, camera: Double, readBack: Double?, error: String?) {
            probeAts += ProbeAt(gen, asked, camera, readBack, error); events += "probeAt %.2f".format(asked)
        }
        override fun probeEnded(why: String) { probeEnds += why; events += "probeEnded" }
        override fun later(ms: Long, block: () -> Unit) { timers += ms to block }
        override fun readBack(): Double? = readBack
        /** Run the timers of exactly [ms] that are due (a fake clock that only knows the delays). */
        fun fire(ms: Long) { val due = timers.filter { it.first == ms }; timers.removeAll(due); due.forEach { it.second() } }
        val lastGen get() = requests.last().first
    }

    private val port = FakePort()
    private val log = mutableListOf<String>()
    private val z = ZoomCoordinator(port) { log += it }

    private fun answer(readBack: Double? = port.requests.last().second) = z.onApplied(port.lastGen, readBack)

    @Test fun switchOffNeverTouchesTheCameraAndMapsTheAreaAsBefore() {
        z.setArea(area)                                           // before the bind: mapped at once
        z.cameraBound(2.6, 8.0)
        z.cameraStopped("screen stopped")
        z.setArea(other)                                          // while stopped: still mapped at once
        z.cameraStarted("camera re-open")
        z.setDrawing(true); z.setDrawing(false)
        assertTrue("no zoom call", port.requests.isEmpty())
        assertTrue("no gate", port.closes.isEmpty())
        assertTrue(port.maps.all { it.ratio == 1.0 && !it.settle })
        assertEquals(listOf(area, area, other), port.maps.map { it.view })
        assertEquals(1, port.drawingReady)                        // drawing at 1× needs no zoom-out
        assertEquals(1.0, z.mapped, 0.0)
    }

    /**
     * The off-centre hint (1.1.17) is read-only: an Area hugging the right edge, the
     * switch OFF, the same inputs as the test above — still no zoom call, no gate,
     * every map at 1× with settle = false — while the state, Diagnostics' line and the
     * zoom log say what centring the same Area would give. Logged once per change of
     * its words, never once per setArea.
     */
    @Test fun theHintIsReadOnlyWithTheSwitchOff() {
        val edge = RoiFrac(0.45, 0.25, 0.95, 0.75)               // fit 1.11 → 1.06: below 1.10× → 1×; centred 1.90×
        z.setArea(edge)
        z.cameraBound(2.6, 8.0)
        z.cameraStopped("screen stopped")
        z.setArea(edge)                                           // the same Area again: no second line
        z.cameraStarted("camera re-open")
        z.setDrawing(true); z.setDrawing(false)
        assertTrue("no zoom call", port.requests.isEmpty())
        assertTrue("no gate", port.closes.isEmpty())
        assertTrue(port.maps.all { it.ratio == 1.0 && !it.settle })
        assertEquals(listOf(edge, edge, edge), port.maps.map { it.view })
        assertEquals(1, port.drawingReady)
        val s = z.state()
        assertNotNull("the hint is there with the switch off", s.offCentre)
        assertEquals(1.0, s.offCentre!!.now.z, 0.0); assertEquals(1.9, s.offCentre!!.centred.z, 0.0)
        assertEquals("what this Area would zoom with the switch on", 1.0, s.area!!.z, 0.0)
        assertTrue(s.area!!.belowMin)
        assertEquals(ZoomFit.Limit.OFF, s.target.limit)
        val d = s.describe()
        assertTrue(d, d.startsWith("zoom to fit the Area: off · target 1.00× (Zoom to fit is off)"))
        assertTrue(d, d.endsWith(" · off-centre: the Area's centre is 20 % right of the frame centre — centred, the same Area " +
            "would zoom 1.90× (this Area: 1.00×, its fit allows only 1.06×)"))
        assertEquals("one line for one hint: $log", 1, log.count { it.startsWith("off-centre Area: ") })
        // Centred: the hint clears, said once; nothing touched the camera for it.
        z.setArea(area)
        z.setArea(area)
        assertEquals("$log", listOf("off-centre Area: centred enough now"), log.filter { it.startsWith("off-centre Area: ") }.drop(1))
        assertFalse(z.state().describe().contains("off-centre"))
        assertTrue(port.requests.isEmpty() && port.closes.isEmpty())
    }

    /**
     * Review of 39ef7cf: the scan screen hands a NEW coordinator its Area before the
     * camera binds (CameraAdapter.bind: setZoomFit, setDrawing, setRoi, then start →
     * bind → cameraBound), so the hint was logged from a lens with no caps known —
     * "would zoom 1.90×" — and the line after the bind contradicted it (1.30×), or
     * "cleared" it with a false "centred enough now" when the sensor binds. The hint
     * waits for the lens: nothing before the first bind (log or state), then one line
     * with the bound caps.
     */
    @Test fun theHintWaitsForTheLensBeforeItSpeaks() {
        val edge = RoiFrac(0.45, 0.25, 0.95, 0.75)
        z.setEnabled(false); z.setDrawing(false); z.setArea(edge)          // CameraAdapter.bind's order
        assertTrue("$log", log.none { it.startsWith("off-centre Area") })
        assertEquals("no hint from a lens not bound yet", null, z.state().offCentre)
        z.cameraBound(1.3, 8.0)                                         // the sensor caps the centred Area at 1.30×
        val lines = log.filter { it.startsWith("off-centre Area") }
        assertEquals("$log", 1, lines.size)
        assertTrue(lines.single(), lines.single().contains("would zoom 1.30× (this Area: 1.00×"))
        assertEquals(1.3, z.state().offCentre!!.centred.z, 0.0)
        // A lens whose sensor binds the Area itself (lossless 1.05 < its fit's 1.06): no hint, and no line at all.
        val log2 = mutableListOf<String>()
        val z2 = ZoomCoordinator(FakePort()) { log2 += it }
        z2.setEnabled(false); z2.setDrawing(false); z2.setArea(edge)
        z2.cameraBound(1.05, 8.0)
        assertTrue("$log2", log2.none { it.startsWith("off-centre Area") })
        assertEquals(null, z2.state().offCentre)
        assertTrue(port.requests.isEmpty() && port.closes.isEmpty())
    }

    /** When the hint goes, the line says why — not "centred enough" for an Area that never moved. */
    @Test fun aHintThatGoesSaysWhy() {
        val edge = RoiFrac(0.45, 0.25, 0.95, 0.75)
        fun last() = log.last { it.startsWith("off-centre Area") }
        z.setArea(edge); z.cameraBound(2.6, 8.0)
        assertTrue(last(), last().contains("would zoom 1.90×"))
        z.setArea(null)
        assertEquals("off-centre Area: none now — no scan Area — the full frame is scanned", last())
        z.setArea(edge)
        assertTrue(last(), last().contains("would zoom 1.90×"))
        z.cameraBound(1.05, 8.0)                                        // another lens: its sensor binds first
        assertEquals("off-centre Area: none now — lossless (sensor ÷ stream) binds, not the Area fit: centring would zoom no more", last())
        z.cameraBound(2.6, 8.0)
        assertTrue(last(), last().contains("would zoom 1.90×"))
        z.setArea(area)                                                 // moved to the centre
        assertEquals("off-centre Area: centred enough now", last())
        assertEquals(6, log.count { it.startsWith("off-centre Area") })
        assertTrue(port.requests.isEmpty() && port.closes.isEmpty())
    }

    @Test fun aZoomClosesTheGateThenMapsWithTheReadBackRatio() {
        z.setEnabled(true)
        z.setArea(area)
        z.cameraBound(2.6, 8.0)
        assertEquals(listOf(1.9), port.requests.map { it.second })
        assertEquals(1, port.closes.size)
        assertTrue("nothing mapped at the new zoom before the camera answers", port.maps.none { it.ratio != 1.0 })
        answer(1.9)
        val m = port.maps.last()
        assertEquals(1.9, m.ratio, 0.0)
        assertTrue(m.settle)
        assertEquals(ZoomFit.toView(area, 1.9), m.view)
        assertEquals(1.9, z.state().ratio, 0.0)
        assertFalse(z.state().settling)
    }

    @Test fun aReadBackWithinOnePercentIsTheRequestAndAClampedOneIsUsed() {
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0)
        answer(1.8999)                                            // HAL float noise
        assertEquals(1.9, port.maps.last().ratio, 0.0)
        z.setArea(RoiFrac(0.3, 0.3, 0.7, 0.7))                    // fit 2.5 → MAX_ZOOM 2.0
        assertEquals(2.0, port.requests.last().second, 0.0)
        answer(1.6)                                               // the camera clamps
        assertEquals(1.6, port.maps.last().ratio, 0.0)
        assertEquals(ZoomFit.toView(RoiFrac(0.3, 0.3, 0.7, 0.7), 1.6), port.maps.last().view)
        assertNotNull(z.state().problem)
        // Re-asking for the same Area does not loop on the clamp.
        val n = port.requests.size
        z.cameraStarted("frames arriving")
        assertEquals(n, port.requests.size)
    }

    /** ON_STOP → CameraX drops the zoom; back on screen it is re-applied and maps IDENTICALLY (the analyzer keeps its tray). */
    @Test fun aReApplyAfterAStopMapsIdentically() {
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0); answer()
        val before = port.maps.last()
        z.cameraStopped("screen stopped")
        assertEquals("the gate closes while the camera is away", 2, port.closes.size)
        z.cameraStarted("camera re-open")
        z.cameraStarted("screen started")                         // a second start waits for the first request
        assertEquals(listOf(1.9, 1.9), port.requests.map { it.second })
        answer()
        val after = port.maps.last()
        assertEquals(before.ratio, after.ratio, 0.0)
        assertEquals(before.view, after.view)
        assertTrue(after.settle)
        assertEquals("one applied change this session", 1, z.changes)
    }

    @Test fun drawingZoomsOutAndCancelComesBackWithTheSameMapping() {
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0); answer()
        val mapsBefore = port.maps.size
        val mapped = port.maps.last()
        z.setDrawing(true)
        assertEquals(1.0, port.requests.last().second, 0.0)
        assertEquals(0, port.drawingReady)
        assertTrue("zooming out: settling", z.state().settling)
        answer()
        assertEquals("the drag is armed once the camera is at 1×", 1, port.drawingReady)
        assertEquals("…and pictures are taken at 1×", listOf(1.0), port.drawingRatios)
        assertEquals("the analyzer is left alone while drawing", mapsBefore, port.maps.size)
        // What /api/device reports while the phone draws: the camera's 1×, not "1.90× settling…".
        assertEquals(1.0, z.state().ratio, 0.0)
        assertFalse(z.state().settling)
        assertTrue(z.state().drawing)
        assertEquals("the mapping is kept", 1.9, z.mapped, 0.0)
        z.setDrawing(false)                                       // Cancel
        assertEquals(1.9, port.requests.last().second, 0.0)
        answer()
        assertEquals(mapped.view, port.maps.last().view)
        assertEquals(mapped.ratio, port.maps.last().ratio, 0.0)
        assertEquals(1, z.changes)
    }

    @Test fun aNewAreaDrawnWhileZoomedOutMapsOnceAtItsOwnZoom() {
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0); answer()
        z.setDrawing(true); answer()
        val mapsBefore = port.maps.size
        z.setArea(other)                                          // released: stored base Area
        assertEquals("still drawing: no mapping yet", mapsBefore, port.maps.size)
        z.setDrawing(false)
        assertEquals(1.55, port.requests.last().second, 1e-12)
        answer()
        assertEquals(mapsBefore + 1, port.maps.size)
        assertEquals(ZoomFit.toView(other, 1.55), port.maps.last().view)
    }

    @Test fun aStaleAnswerIsIgnored() {
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0)
        val g1 = port.lastGen
        z.setArea(other)                                          // supersedes before the camera answers
        val g2 = port.lastGen
        assertTrue(g2 != g1)
        z.onApplied(g1, 1.9)
        assertTrue("the old answer maps nothing", port.maps.none { it.ratio == 1.9 })
        z.onApplied(g2, 1.55)
        assertEquals(1.55, port.maps.last().ratio, 1e-12)
        assertEquals(ZoomFit.toView(other, 1.55), port.maps.last().view)
    }

    @Test fun notActiveIsRetriedThenScanningCarriesOnAtOne() {
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0)
        val g = port.lastGen
        repeat(ZoomCoordinator.MAX_TRIES) {
            z.onFailed(g, notActive = true, "Camera is not active.")
            port.fire(ZoomCoordinator.RETRY_MS)
        }
        assertEquals(1 + ZoomCoordinator.MAX_TRIES, port.requests.size)
        z.onFailed(g, notActive = true, "Camera is not active.")
        assertEquals("no further retry", 1 + ZoomCoordinator.MAX_TRIES, port.requests.size)
        val m = port.maps.last()
        assertEquals(1.0, m.ratio, 0.0); assertTrue(m.settle); assertEquals(area, m.view)
        assertTrue(z.state().problem!!.contains("could not be applied"))
        // The next start asks again (an inactive camera comes back at 1×).
        z.cameraStarted("camera re-open")
        assertEquals(1.9, port.requests.last().second, 0.0)
    }

    @Test fun aRefusedRatioIsNotAskedAgainInALoop() {
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0)
        z.onFailed(port.lastGen, notActive = false, "zoom ratio out of range")
        assertEquals(1, port.requests.size)
        assertEquals(1.0, port.maps.last().ratio, 0.0)
        z.cameraStarted("frames arriving")
        assertEquals(1, port.requests.size)
    }

    @Test fun aSilentCameraTimesOutOntoWhatItReports() {
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0)
        port.readBack = 1.0                                        // frames still say 1×
        port.fire(ZoomCoordinator.SETTLE_TIMEOUT_MS)
        assertEquals(1.0, port.maps.last().ratio, 0.0)
        assertTrue(port.maps.last().settle)
        assertTrue(z.state().problem!!.contains("no answer"))
        // A timeout for an answered request does nothing.
        z.setArea(other); answer()
        val n = port.maps.size
        port.fire(ZoomCoordinator.SETTLE_TIMEOUT_MS)
        assertEquals(n, port.maps.size)
    }

    /**
     * Review of 1.1.14, case 1: Settings → the switch ON → back. The request goes out
     * at onResume, before the camera has reopened; a reopen slower than the timeout
     * finds only the read-back from BEFORE the stop (1×) and maps it. The camera's
     * real answer (1.9×) then came and was dropped — the Area stayed mapped at 1×
     * while frames were at 1.9×. Now the late answer re-maps, behind the gate.
     */
    @Test fun aLateAnswerAfterTheTimeoutReMapsWhatTheCameraDid() {
        z.setEnabled(false); z.setArea(area); z.cameraBound(2.6, 8.0)
        port.readBack = 1.0                                        // the last frame before the stop
        z.cameraStopped("the scan screen stopped")
        z.cameraStarted("the scan screen started")
        z.setEnabled(true)                                         // onResume: the switch is on now
        val g = port.lastGen
        port.fire(ZoomCoordinator.SETTLE_TIMEOUT_MS)               // the camera takes > 2 s to reopen
        assertEquals("the timeout mapped the stale read-back", 1.0, z.mapped, 0.0)
        val closes = port.closes.size
        z.onApplied(g, 1.9)                                        // …and then it answers
        assertEquals(1.9, z.mapped, 0.0)
        assertEquals(ZoomFit.toView(area, 1.9), port.maps.last().view)
        assertTrue("the gate closed for the re-map", port.closes.size == closes + 1 && port.maps.last().settle)
        z.cameraStarted("camera open")
        assertEquals(1.9, z.mapped, 0.0)
        assertEquals("one request: nothing re-asked", listOf(1.9), port.requests.map { it.second })
        assertTrue(log.any { it.contains("after the 2000 ms timeout") && it.contains("re-mapping at 1.90×") })
    }

    /** Case 2: the switch turned OFF while zoomed; the timeout read the stale 1.9× — the switch-off camera is at 1×. */
    @Test fun aLateAnswerAfterTheTimeoutReMapsTheSwitchOff() {
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0); answer()
        port.readBack = 1.9                                        // the last frame before the stop
        z.cameraStopped("the scan screen stopped")
        z.cameraStarted("the scan screen started")                 // ON_START re-asks 1.9×…
        val g1 = port.lastGen
        z.setEnabled(false)                                        // …onResume: the switch is off → 1×
        val g2 = port.lastGen
        port.fire(ZoomCoordinator.SETTLE_TIMEOUT_MS)
        assertEquals("the timeout mapped the stale read-back", 1.9, z.mapped, 0.0)
        z.onFailed(g1, notActive = false, "Cancelled by another setZoomRatio()")   // the superseded one: ignored
        assertEquals(1.9, z.mapped, 0.0)
        z.onApplied(g2, 1.0)
        assertEquals("switch off = 1×", 1.0, z.mapped, 0.0)
        assertEquals(area, port.maps.last().view)
        assertFalse(z.state().settling)
    }

    /** A late answer that agrees with what the timeout mapped changes nothing; one after a stop is not answered at all. */
    @Test fun aLateAnswerThatAgreesOrComesAfterAStopIsIgnored() {
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0)
        val g = port.lastGen
        port.fire(ZoomCoordinator.SETTLE_TIMEOUT_MS)               // no read-back: the request is assumed
        assertEquals(1.9, z.mapped, 0.0)
        val n = port.maps.size; val closes = port.closes.size
        z.onApplied(g, 1.9)
        assertEquals(n, port.maps.size); assertEquals(closes, port.closes.size)
        // Another timeout, then a stop: the late answer belongs to a camera that is gone.
        z.setArea(other)
        val g2 = port.lastGen
        port.fire(ZoomCoordinator.SETTLE_TIMEOUT_MS)
        z.cameraStopped("the scan screen stopped")
        val n2 = port.maps.size
        z.onApplied(g2, 1.2)
        assertEquals(n2, port.maps.size)
    }

    @Test fun switchingOffWhileZoomedGoesBackToOneOnce() {
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0); answer()
        z.setEnabled(false)
        assertEquals(1.0, port.requests.last().second, 0.0)
        answer()
        assertEquals(1.0, port.maps.last().ratio, 0.0)
        assertEquals(area, port.maps.last().view)
        val n = port.requests.size
        z.cameraStopped("screen stopped"); z.cameraStarted("camera re-open"); z.setArea(other)
        assertEquals("off again: no more camera calls", n, port.requests.size)
    }

    @Test fun anAreaWithTheSameZoomOnlyRemapsWithoutTheCamera() {
        val a1 = RoiFrac(0.25, 0.25, 0.75, 0.75)
        val a2 = RoiFrac(0.26, 0.25, 0.74, 0.75)                  // same 1.90×
        z.setEnabled(true); z.setArea(a1); z.cameraBound(2.6, 8.0); answer()
        val n = port.requests.size; val closes = port.closes.size
        z.setArea(a2)
        assertEquals(n, port.requests.size)
        assertEquals(closes, port.closes.size)
        assertEquals(ZoomFit.toView(a2, 1.9), port.maps.last().view)
        assertFalse(port.maps.last().settle)
    }

    @Test fun noAreaOrAWideOneStaysAtOne() {
        z.setEnabled(true); z.setArea(null); z.cameraBound(2.6, 8.0)
        z.setArea(RoiFrac(0.02, 0.02, 0.98, 0.98))
        assertTrue(port.requests.isEmpty())
        assertTrue(z.state().target.belowMin)
        assertTrue(z.state().describe().contains("below 1.10×"))
    }

    // ── the zoom probe (1.1.17): a coordinator mode that holds the gate for every rung ──

    /** Switch on, the Area mapped at 1.90× — where a probe usually starts. */
    private fun mappedAt19() { z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0); answer() }

    @Test fun aProbeHoldsTheGateForEveryRungAndNeverMaps() {
        mappedAt19()
        val maps = port.maps.size
        assertEquals(1, port.closes.size)
        assertTrue(z.beginProbe("run 1"))
        assertEquals("exactly one more close", 2, port.closes.size)
        assertTrue(z.state().probing)
        for ((asked, rb) in listOf(1.0 to 1.0, 1.5 to 1.4999, 2.6 to 2.6)) {    // 2.6 is above MAX_ZOOM: the probe ignores it
            z.probeTo(asked)
            assertEquals(asked, port.requests.last().second, 0.0)
            z.onApplied(port.lastGen, rb)
            val a = port.probeAts.last()
            assertEquals(asked, a.asked, 0.0); assertEquals("the RAW read-back", rb, a.readBack!!, 0.0)
            assertEquals("a read-back within 1 % IS the request", asked, a.camera, 0.0)
            assertEquals(null, a.error)
        }
        assertEquals(listOf(1.9, 1.0, 1.5, 2.6), port.requests.map { it.second })
        assertEquals(3, port.probeAts.size)
        assertEquals("nothing mapped during the probe", maps, port.maps.size)
        assertEquals("the gate stays closed: no more closes", 2, port.closes.size)
        val s = z.state()
        assertTrue(s.probing)
        assertEquals("the camera's ratio, the rung", 2.6, s.ratio, 0.0)
        assertFalse("not settling — the gate is closed on purpose", s.settling)
        assertEquals("the mapping is kept", 1.9, z.mapped, 0.0)
        z.probeNote = "rung 3/18 · 2.60×"
        assertTrue(z.state().describe(), z.state().describe().endsWith(" · ZOOM PROBE running (rung 3/18 · 2.60×)"))
        assertTrue("$log", log.any { it.startsWith("zoom probe START (run 1)") && it.contains("camera at 1.90×") })
        assertTrue("the request / applied lines are silent while probing: $log", log.none { it.startsWith("→ 1.50×") || it.startsWith("1.50× applied") })
    }

    @Test fun theProbeEndsOnTheSameMappingWithNoChange() {
        mappedAt19()
        val before = port.maps.last()
        assertTrue(z.beginProbe("run 1"))
        z.probeTo(1.0); answer()
        z.probeTo(2.2); answer()
        z.probeTo(1.9); answer()                                  // the RESTORE: back to the target
        assertEquals(before.ratio, z.mapped, 0.0)
        val n = port.maps.size
        z.endProbe("zoom probe over")
        assertEquals("one map", n + 1, port.maps.size)
        val m = port.maps.last()
        assertEquals(1.9, m.ratio, 0.0); assertEquals(before.view, m.view); assertTrue("the gate opens after the settle frames", m.settle)
        assertEquals("no applied change: the analyzer keeps its tray", 1, z.changes)
        assertEquals(null, z.state().problem)
        assertFalse(z.state().probing); assertFalse(z.state().settling)
        assertEquals(null, z.probeNote)
        assertTrue("$log", log.any { it == "zoom probe END (zoom probe over) — back to 1.90× on the same mapping (no re-learn)" })
        z.endProbe("again")                                       // ended already: nothing
        assertEquals(n + 1, port.maps.size)
    }

    @Test fun aProbeWithTheSwitchOffEndsOnTheOldMapping() {
        z.setArea(area); z.cameraBound(2.6, 8.0)
        assertTrue(z.beginProbe("run 1"))
        assertEquals("the only close is the probe's", 1, port.closes.size)
        z.probeTo(1.6); answer()
        z.probeTo(1.0); answer()                                  // restore = the switch-off target, 1×
        z.endProbe("zoom probe over")
        val m = port.maps.last()
        assertEquals(1.0, m.ratio, 0.0)
        assertTrue("the SAME Area object (MainActivity compares by equality; toView(1.0) is the identity)", m.view === area)
        assertTrue(m.settle)
        assertEquals(0, z.changes)
        // The switch-off rules again: no camera calls, no gate, maps at once.
        val n = port.requests.size
        z.cameraStopped("screen stopped"); z.cameraStarted("camera re-open"); z.setArea(other)
        assertEquals(n, port.requests.size)
        assertEquals(1, port.closes.size)
        assertEquals(other, port.maps.last().view); assertFalse(port.maps.last().settle)
    }

    @Test fun eachLifecycleInputAbortsTheProbeFirst() {
        val inputs = linkedMapOf<String, (ZoomCoordinator) -> Unit>(
            "stop" to { it.cameraStopped("the scan screen stopped") },
            "opening" to { it.cameraOpening("camera opening") },
            "bind" to { it.cameraBound(2.6, 8.0) },
            "new Area" to { it.setArea(other) },
            "switch" to { it.setEnabled(false) },
            "drawing" to { it.setDrawing(true) },
        )
        for ((name, input) in inputs) {
            val p = FakePort()
            val l = mutableListOf<String>()
            val c = ZoomCoordinator(p) { l += it }
            c.setEnabled(true); c.setArea(area); c.cameraBound(2.6, 8.0); c.onApplied(p.lastGen, 1.9)
            assertTrue(name, c.beginProbe("run 1"))
            c.probeTo(1.5); c.onApplied(p.lastGen, 1.5)
            val mark = p.events.size
            input(c)
            val after = p.events.drop(mark)
            assertEquals("$name: probeEnded exactly once — $after", 1, p.probeEnds.size)
            assertEquals("$name: before any map or request — $after", "probeEnded", after.first())
            assertFalse(name, c.state().probing)
            assertTrue("$name: $l", l.any { it.startsWith("zoom probe STOPPED (") })
            c.probeTo(2.0)                                         // the runner's stale ask after the abort: nothing
            assertTrue("$name: a probeTo after the abort asks nothing", p.events.drop(mark).none { it == "request 2.00" })
            assertEquals(1, p.probeEnds.size)
        }
        // …then today's behaviour, e.g. aReApplyAfterAStopMapsIdentically after a mid-probe stop.
        mappedAt19()
        val before = port.maps.last()
        assertTrue(z.beginProbe("run 1"))
        z.probeTo(2.4); answer()
        z.cameraStopped("the scan screen stopped")
        z.cameraStarted("camera re-open")
        assertEquals(1.9, port.requests.last().second, 0.0)
        answer()
        assertEquals(before.ratio, port.maps.last().ratio, 0.0); assertEquals(before.view, port.maps.last().view)
        assertTrue(port.maps.last().settle)
        assertEquals(1, z.changes)
        assertEquals(1, port.probeEnds.size)
    }

    @Test fun aResyncDuringTheProbeAsksNothing() {
        mappedAt19()
        assertTrue(z.beginProbe("run 1"))
        val n = port.events.size
        z.cameraStarted("frames arriving at a closed gate")
        z.cameraStarted("camera open")
        assertEquals("no request, no probeAt, no map", n, port.events.size)
        assertTrue(z.state().probing)
    }

    @Test fun aRefusedSilentOrClampedRungIsReportedNeverMapped() {
        mappedAt19()
        val maps = port.maps.size; val closes = port.closes.size
        assertTrue(z.beginProbe("run 1"))
        z.probeTo(2.8)
        z.onFailed(port.lastGen, notActive = false, "zoom ratio out of range")
        assertEquals("zoom ratio out of range", port.probeAts.last().error)
        assertEquals(2.8, port.probeAts.last().asked, 0.0)
        z.probeTo(2.2)                                             // the camera never answers
        val g = port.lastGen
        port.readBack = 1.9
        port.fire(ZoomCoordinator.SETTLE_TIMEOUT_MS)
        assertEquals(ZoomCoordinator.PROBE_NO_ANSWER, port.probeAts.last().error)
        assertEquals("no answer in 2000 ms", ZoomCoordinator.PROBE_NO_ANSWER)
        z.onApplied(g, 2.2)                                        // …and then it answers after all
        assertEquals("a late answer after a probe timeout re-maps nothing", maps, port.maps.size)
        assertEquals(closes + 1, port.closes.size)
        z.probeTo(2.0); z.onApplied(port.lastGen, 1.6)             // clamped
        assertEquals(1.6, port.probeAts.last().camera, 0.0); assertEquals(1.6, port.probeAts.last().readBack!!, 0.0)
        assertEquals(maps, port.maps.size)
        z.probeTo(1.9); answer()
        z.endProbe("zoom probe over")
        assertEquals("a clamped rung leaves no problem behind", null, z.state().problem)
        assertEquals(1.9, port.maps.last().ratio, 0.0)
        assertEquals(1, z.changes)
    }

    @Test fun aProbeIsRefusedWhileSettlingDrawingUnboundOrGated() {
        assertFalse("no camera bound", z.beginProbe("run 1"))
        z.setEnabled(true); z.setArea(area); z.cameraBound(2.6, 8.0)
        assertFalse("a request on its way (settling)", z.beginProbe("run 1"))
        answer()
        z.setDrawing(true); answer()
        assertFalse("drawing an Area", z.beginProbe("run 1"))
        z.setDrawing(false); answer()
        z.cameraOpening("camera opening")
        assertFalse("the gate is closed (the camera is reopening)", z.beginProbe("run 1"))
        z.cameraStarted("camera open")                            // already there: maps behind the closed gate
        val closes = port.closes.size
        assertTrue(z.beginProbe("run 1"))
        assertFalse("a probe already running", z.beginProbe("run 2"))
        assertEquals(closes + 1, port.closes.size)
        assertTrue(port.probeEnds.isEmpty())
    }

    /**
     * Documents the re-entrancy the CameraController glue handles with main.post: a
     * probeTo at the camera's current ratio has nothing to ask, so its probeAt comes
     * back INSIDE the probeTo call (reconcile's "camera already there" branch).
     */
    @Test fun probeToAtTheCurrentRatioAnswersSynchronously() {
        mappedAt19()
        assertTrue(z.beginProbe("run 1"))
        val n = port.requests.size
        z.probeTo(1.9)
        assertEquals("nothing asked of the camera", n, port.requests.size)
        assertEquals("answered inside the call", 1, port.probeAts.size)
        val a = port.probeAts.single()
        assertEquals(1.9, a.asked, 0.0); assertEquals(1.9, a.camera, 0.0); assertEquals(null, a.readBack); assertEquals(null, a.error)
    }
}
