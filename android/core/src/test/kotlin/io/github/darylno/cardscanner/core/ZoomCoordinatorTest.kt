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

    private inner class FakePort : ZoomCoordinator.Port {
        val requests = mutableListOf<Pair<Int, Double>>()
        val closes = mutableListOf<String>()
        val maps = mutableListOf<Map>()
        var drawingReady = 0
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        var readBack: Double? = null
        override fun request(gen: Int, ratio: Double) { requests += gen to ratio }
        override fun closeGate(why: String) { closes += why }
        override fun map(ratio: Double, view: RoiFrac?, settle: Boolean, why: String) { maps += Map(ratio, view, settle) }
        val drawingRatios = mutableListOf<Double>()
        override fun drawingReady(ratio: Double) { drawingReady++; drawingRatios += ratio }
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
     * Review of 1.1.11, case 1: Settings → the switch ON → back. The request goes out
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
}
