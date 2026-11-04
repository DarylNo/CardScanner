package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.ZoomCoordinator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's settings from the browser: shapes, validation, admin only. */
class DeviceApiTest {
    private var st = DeviceApi.DeviceState(auto = true, roi = null, torch = false,
        vibration = true, highRes = false, aeLock = false)
    private var writes = 0
    private var live = true
    private var cams = listOf("0" to "Camera 0 · back · 13 MP · autofocus", "2" to "Camera 2 · back · 2.0 MP · fixed focus")
    private var zoom: ZoomCoordinator.State? = null
    private var baseShots = 0
    private var cameraShots = 0
    private var cameraPic: DeviceApi.Picture? = DeviceApi.Picture(byteArrayOf(7, 8, 9), 1.9, 1200, 1600)
    /** Runs inside the camera view's wait (the 2 s on the phone): what changes while it waits. */
    private var duringCameraShot: () -> Unit = {}
    /** The phone's last zoom probe (AppSettings.zoomProbeLast on the phone), or null = never run. */
    private var probe: Map<String, Any?>? = null
    private val api = DeviceApi(object : DeviceApi.DeviceControl {
        override fun read() = st
        override fun write(s: DeviceApi.DeviceState) { st = s; writes++ }
        override fun snapshot(): ByteArray? { baseShots++; return if (live) byteArrayOf(1, 2) else null }
        override fun cameraSnapshot(): DeviceApi.Picture? { cameraShots++; duringCameraShot(); return if (live) cameraPic else null }
        override fun cameraLive() = live
        override fun cameras() = cams
        override fun zoom() = zoom
        override fun zoomProbe() = probe
    })

    private fun patch(body: String) = api.handle(ApiRequest("PATCH", "/api/device", body = body.toByteArray()))!!
    private fun json(r: ApiResponse) = MiniJson.parse(String(r.body)) as Map<*, *>

    @Test fun readsTheSettings() {
        val s = json(api.handle(ApiRequest("GET", "/api/device"))!!)
        assertEquals(mapOf("mode" to "tray", "roi" to null, "torch" to false, "vibration" to true,
            "high_res" to false, "ae_lock" to false, "check_ms" to 2500L, "camera" to "auto",
            "cameras" to listOf(mapOf("id" to "0", "label" to "Camera 0 · back · 13 MP · autofocus"),
                mapOf("id" to "2", "label" to "Camera 2 · back · 2.0 MP · fixed focus")),
            "camera_live" to true, "snapshot_views" to listOf("base", "camera"),
            "zoom_fit" to false, "zoom" to null, "zoom_probe" to null), s)
    }

    /**
     * The zoom probe (1.1.17) is reported, never driven, from the computer: while it runs,
     * `zoom.probing` and `zoom.probe` (the runner's words for where it is) — never
     * "settling", the gate is closed on purpose — and `zoom_probe` = the last probe's
     * verdict at the top level, read from the phone's settings, so it is there with the
     * scan screen closed too. PATCH has no key for either: "unknown setting", nothing written.
     */
    @Test fun theProbeIsReportedReadOnly() {
        var asked = -1
        val coord = ZoomCoordinator(object : ZoomCoordinator.Port {
            override fun request(gen: Int, ratio: Double) { asked = gen }
            override fun closeGate(why: String) {}
            override fun map(ratio: Double, view: RoiFrac?, settle: Boolean, why: String) {}
            override fun drawingReady(ratio: Double) {}
            override fun later(ms: Long, block: () -> Unit) {}
            override fun readBack(): Double? = null
        })
        fun get() = json(api.handle(ApiRequest("GET", "/api/device"))!!)
        coord.setArea(RoiFrac(0.25, 0.25, 0.75, 0.75)); coord.cameraBound(2.6, 10.0)
        zoom = coord.state()
        val idle = get()["zoom"] as Map<*, *>
        assertEquals(false, idle["probing"])
        assertTrue("$idle", idle.containsKey("probe") && idle["probe"] == null)
        // A run on its 7th rung: the camera answered 1.60×.
        assertTrue(coord.beginProbe("run 1"))
        coord.probeTo(1.6); coord.onApplied(asked, 1.6)
        coord.probeNote = "rung 7/15 · 1.60× · card 979 px at 1.50×"
        zoom = coord.state()
        val running = get()["zoom"] as Map<*, *>
        assertEquals(true, running["probing"])
        assertEquals("rung 7/15 · 1.60× · card 979 px at 1.50×", running["probe"])
        assertEquals(false, running["settling"])
        assertEquals(1.6, (running["ratio"] as Number).toDouble(), 0.0)
        // The last probe, with the scan screen closed.
        live = false; zoom = null
        probe = linkedMapOf("at" to 1_793_000_000_000L, "camera" to "0", "stream" to "1600×1200", "words" to "run 1 valid — lift this card…")
        val closed = get()
        assertEquals(null, closed["zoom"])
        assertEquals(mapOf("at" to 1_793_000_000_000L, "camera" to "0", "stream" to "1600×1200", "words" to "run 1 valid — lift this card…"),
            closed["zoom_probe"])
        // Read-only: no key starts, stops or rewrites it.
        for (b in listOf("""{"probe":true}""", """{"zoom_probe":null}""", """{"probing":false}""")) {
            val r = patch(b)
            assertEquals(b, 400, r.status)
            assertTrue(b, (json(r)["error"] as String).startsWith("unknown setting"))
        }
        assertEquals(0, writes)
    }

    /**
     * "Zoom to fit the Area" (1.1.14): zoom_fit is a plain switch, validated; the live
     * zoom is reported; the Area stays BASE (1×) fractions whatever the zoom.
     */
    @Test fun zoomToFitTheArea() {
        assertEquals(200, patch("""{"zoom_fit":true}""").status)
        assertEquals(true, st.zoomFit)
        for (b in listOf("""{"zoom_fit":"yes"}""", """{"zoom_fit":1}""", """{"zoom_fit":null}""", """{"zoom":1.5}""")) {
            assertEquals(b, 400, patch(b).status)
        }
        assertEquals(1, writes)
        // A zoomed camera: the Area given and answered is the base one, untouched.
        val base = RoiFrac(0.25, 0.25, 0.75, 0.75)
        var asked = -1
        val coord = ZoomCoordinator(object : ZoomCoordinator.Port {
            override fun request(gen: Int, ratio: Double) { asked = gen }
            override fun closeGate(why: String) {}
            override fun map(ratio: Double, view: RoiFrac?, settle: Boolean, why: String) {}
            override fun drawingReady(ratio: Double) {}
            override fun later(ms: Long, block: () -> Unit) {}
            override fun readBack(): Double? = null
        })
        coord.setEnabled(true); coord.setArea(base); coord.cameraBound(2.6, 10.0)
        coord.onApplied(asked, 1.9)
        zoom = coord.state()
        val r = patch("""{"roi":{"x0":0.25,"y0":0.25,"x1":0.75,"y1":0.75}}""")
        assertEquals(200, r.status)
        assertEquals(base, st.roi)
        val j = json(r)
        assertEquals(mapOf("x0" to 0.25, "y0" to 0.25, "x1" to 0.75, "y1" to 0.75), j["roi"])
        assertEquals(true, j["zoom_fit"])
        val z = j["zoom"] as Map<*, *>
        assertEquals(1.9, (z["ratio"] as Number).toDouble(), 0.0)
        assertEquals(1.9, (z["target"] as Number).toDouble(), 0.0)
        assertEquals("the Area fit", z["limit"])
        assertEquals(2.0, (z["fit"] as Number).toDouble(), 0.0)
        assertEquals(2.6, (z["lossless"] as Number).toDouble(), 0.0)
        assertEquals(10.0, (z["max"] as Number).toDouble(), 0.0)
        assertEquals(false, z["settling"])
        assertEquals(false, z["drawing"])
        // A centred Area: nothing to say about centring it (the key is there, null).
        assertTrue("$z", z.containsKey("off_centre"))
        assertEquals(null, z["off_centre"])
        // An Area drawn on the phone: zoomed out to 1× for it — said so, not "settling" at 1.9×.
        coord.setDrawing(true); coord.onApplied(asked, 1.0)
        zoom = coord.state()
        val d = json(patch("""{"torch":false}""")) ["zoom"] as Map<*, *>
        assertEquals(true, d["drawing"]); assertEquals(false, d["settling"])
        assertEquals(1.0, (d["ratio"] as Number).toDouble(), 0.0)
    }

    /**
     * The off-centre hint (1.1.17), whatever the switch says: `would` = what this Area
     * zooms with the switch on; `off_centre` = what the SAME Area would zoom centred,
     * where it would sit (the desktop's dashed ghost box) and the phone's own words.
     */
    @Test fun anOffCentreAreaIsReported() {
        val coord = ZoomCoordinator(object : ZoomCoordinator.Port {
            override fun request(gen: Int, ratio: Double) {}
            override fun closeGate(why: String) {}
            override fun map(ratio: Double, view: RoiFrac?, settle: Boolean, why: String) {}
            override fun drawingReady(ratio: Double) {}
            override fun later(ms: Long, block: () -> Unit) {}
            override fun readBack(): Double? = null
        })
        fun zoomNow(): Map<*, *> { zoom = coord.state(); return json(api.handle(ApiRequest("GET", "/api/device"))!!)["zoom"] as Map<*, *> }
        fun num(v: Any?) = (v as Number).toDouble()
        coord.setArea(RoiFrac(0.45, 0.25, 0.95, 0.75)); coord.cameraBound(2.6, 10.0)     // the switch is off
        val z = zoomNow()
        assertEquals("Zoom to fit is off", z["limit"])
        assertEquals(1.0, num(z["target"]), 0.0)
        val would = z["would"] as Map<*, *>
        assertEquals(1.0, num(would["z"]), 0.0)
        assertEquals("below 1.10× (the Area fit)", would["limit"])
        assertEquals(1.11, num(would["fit"]), 0.0)
        val o = z["off_centre"] as Map<*, *>
        assertEquals(1.0, num(o["now"]), 0.0)
        assertEquals(1.9, num(o["centred"]), 0.0)
        assertEquals(0.2, num(o["dx"]), 0.0)
        assertEquals(0.0, num(o["dy"]), 0.0)
        assertEquals(mapOf("x0" to 0.25, "y0" to 0.25, "x1" to 0.75, "y1" to 0.75), o["centred_roi"])
        val words = o["words"] as String
        assertTrue(words, words.contains("20 % right") && words.contains("would zoom 1.90×") && words.contains("this Area: 1.00×"))
        // Centred: no hint, and the Area would zoom 1.90×.
        coord.setArea(RoiFrac(0.25, 0.25, 0.75, 0.75))
        val c = zoomNow()
        assertTrue("$c", c.containsKey("off_centre"))
        assertEquals(null, c["off_centre"])
        assertEquals(1.9, num((c["would"] as Map<*, *>)["z"]), 0.0)
        assertEquals("the Area fit", (c["would"] as Map<*, *>)["limit"])
    }

    @Test fun appliesWhatIsGivenInOneWrite() {
        val r = patch("""{"mode":"tap","torch":true,"roi":{"x0":0.1,"y0":0.2,"x1":0.6,"y1":0.9}}""")
        assertEquals(200, r.status)
        assertEquals(1, writes)
        assertEquals(RoiFrac(0.1, 0.2, 0.6, 0.9), st.roi)
        assertEquals(true, st.torch); assertEquals(false, st.auto)
        assertEquals(mapOf("x0" to 0.1, "y0" to 0.2, "x1" to 0.6, "y1" to 0.9), json(r)["roi"])
        assertEquals(200, patch("""{"roi":null}""").status)
        assertEquals(null, st.roi)
    }

    @Test fun refusesBadValuesAndWritesNothing() {
        for (b in listOf("""{"mode":"handheld"}""", """{"mode":"mount"}""", """{"auto":false}""", """{"mode":true}""", """{"torch":"yes"}""", """{"nope":1}""",
            """{"torch":true,"roi":{"x0":0.1,"y0":0.1,"x1":0.12,"y1":0.9}}""",       // side < 0.08
            """{"roi":{"x0":-0.1,"y0":0,"x1":0.5,"y1":0.5}}""", """{"roi":[0,0,1,1]}""",
            """{"check_ms":249}""", """{"check_ms":10001}""", """{"check_ms":1500.5}""", """{"check_ms":"2000"}""",
            """{"check_ms":null}""")) {
            assertEquals(b, 400, patch(b).status)
        }
        assertEquals(422, patch("[1]").status)
        assertEquals(0, writes)
        assertEquals(false, st.torch)
    }

    /** The Camera setting: "auto" or one of THIS phone's cameras; anything else writes nothing. */
    @Test fun pickingACamera() {
        val r = patch("""{"camera":"2"}""")
        assertEquals(200, r.status)
        assertEquals("2", st.cameraId); assertEquals("2", json(r)["camera"])
        assertEquals(200, patch("""{"camera":"auto"}""").status)
        assertEquals(null, st.cameraId)
        assertEquals(2, writes)
        for (b in listOf("""{"camera":"7"}""", """{"camera":0}""", """{"camera":null}""", """{"camera":"1"}""")) {
            assertEquals(b, 400, patch(b).status)
        }
        cams = emptyList()
        assertEquals("a phone that lists no cameras still takes Automatic", 200, patch("""{"camera":"auto"}""").status)
        assertEquals(400, patch("""{"camera":"0"}""").status)
        assertEquals(3, writes)
    }

    @Test fun snapshotNeedsTheScanScreen() {
        assertEquals(200, api.handle(ApiRequest("GET", "/api/device/snapshot.jpg"))!!.status)
        live = false
        assertEquals(503, api.handle(ApiRequest("GET", "/api/device/snapshot.jpg"))!!.status)
    }

    /**
     * `?view=camera` (1.1.17): the zoomed frame as the camera delivers it, with ITS zoom in
     * `X-Zoom-Ratio` (the desktop maps the Area into it). No query, `?t=` only and `view=base`
     * stay today's base-space picture; any other view is refused before a picture is taken.
     */
    @Test fun snapshotViews() {
        fun get(q: Map<String, String>) = api.handle(ApiRequest("GET", "/api/device/snapshot.jpg", query = q))!!
        fun error(r: ApiResponse) = json(r)["error"] as String
        for (q in listOf(emptyMap(), mapOf("t" to "1"), mapOf("view" to "base", "t" to "2"))) {
            val r = get(q)
            assertEquals("$q", 200, r.status)
            assertEquals("$q", "image/jpeg", r.contentType)
            assertArrayEquals("$q", byteArrayOf(1, 2), r.body)
            assertEquals("$q", null, r.headers["X-Zoom-Ratio"])
            assertEquals("$q", null, r.headers["X-Frame-Size"])
        }
        assertEquals(3, baseShots); assertEquals(0, cameraShots)
        val c = get(mapOf("view" to "camera", "t" to "3"))
        assertEquals(200, c.status)
        assertEquals("image/jpeg", c.contentType)
        assertArrayEquals(byteArrayOf(7, 8, 9), c.body)
        assertEquals("1.900", c.headers["X-Zoom-Ratio"])
        assertEquals("1200x1600", c.headers["X-Frame-Size"])
        assertEquals(3, baseShots); assertEquals(1, cameraShots)
        cameraPic = DeviceApi.Picture(byteArrayOf(5), 1.0 + 1.0 / 3, 1600, 1200)
        assertEquals("1.333", get(mapOf("view" to "camera")).headers["X-Zoom-Ratio"])
        // The screen is open but no frame came within 2 s, and the phone reports no zoom settling
        // (here: no zoom at all — a scan screen whose camera never started): neutral words, never
        // the zoom (aCameraViewWithNoPictureNamesTheZoomOnlyWhileItSettles).
        cameraPic = null
        val none = get(mapOf("view" to "camera"))
        assertEquals(503, none.status)
        assertEquals("no picture within 2 s — check the scan screen on the phone and try again", error(none))
        // No scan screen: today's words.
        live = false
        val closed = get(mapOf("view" to "camera"))
        assertEquals(503, closed.status)
        assertEquals(error(api.handle(ApiRequest("GET", "/api/device/snapshot.jpg"))!!), error(closed))
        assertTrue(error(closed), error(closed).contains("open the scan screen"))
        live = true
        // A control that has no camera view (an older one): 503, never the base picture in its place.
        var oldShots = 0
        val old = DeviceApi(object : DeviceApi.DeviceControl {
            override fun read() = st
            override fun write(s: DeviceApi.DeviceState) {}
            override fun snapshot(): ByteArray? { oldShots++; return byteArrayOf(1, 2) }
            override fun cameraLive() = true
        })
        assertEquals(503, old.handle(ApiRequest("GET", "/api/device/snapshot.jpg", query = mapOf("view" to "camera")))!!.status)
        assertEquals(0, oldShots)
        // Anything else: 400, and neither picture is taken.
        val b0 = baseShots; val c0 = cameraShots
        for (v in listOf("wide", "", "CAMERA", "base ")) {
            val r = get(mapOf("view" to v))
            assertEquals("view=$v", 400, r.status)
            assertEquals("view must be \"base\" or \"camera\"", error(r))
        }
        assertEquals(b0, baseShots); assertEquals(c0, cameraShots)
    }

    /**
     * Review of d0b65bf: the camera view's 503 said "the camera's zoom is changing" whenever the
     * scan screen was open — with Zoom to fit OFF (no gate, no settle), and at once from a scan
     * screen whose camera never started (camera permission denied: `zoom` null). The zoom is
     * named only when the phone says it is settling, read before AND after the wait (the gate
     * opens the moment the settle is over, so a picture missed for it may come back to a camera
     * no longer settling); anything else gets neutral words.
     */
    @Test fun aCameraViewWithNoPictureNamesTheZoomOnlyWhileItSettles() {
        val zooming = "no picture within 2 s — the camera's zoom is changing; try again"
        val neutral = "no picture within 2 s — check the scan screen on the phone and try again"
        fun cameraError(): String {
            val r = api.handle(ApiRequest("GET", "/api/device/snapshot.jpg", query = mapOf("view" to "camera")))!!
            assertEquals(503, r.status)
            return json(r)["error"] as String
        }
        var asked = -1
        val coord = ZoomCoordinator(object : ZoomCoordinator.Port {
            override fun request(gen: Int, ratio: Double) { asked = gen }
            override fun closeGate(why: String) {}
            override fun map(ratio: Double, view: RoiFrac?, settle: Boolean, why: String) {}
            override fun drawingReady(ratio: Double) {}
            override fun later(ms: Long, block: () -> Unit) {}
            override fun readBack(): Double? = null
        })
        cameraPic = null
        // The scan screen is open but has no camera (no zoom reported): not the zoom.
        zoom = null
        assertEquals(neutral, cameraError())
        // Zoom to fit OFF (the default): a camera with nothing settling.
        coord.setArea(RoiFrac(0.25, 0.25, 0.75, 0.75)); coord.cameraBound(2.6, 10.0)
        zoom = coord.state()
        assertEquals(false, zoom!!.settling)
        assertEquals(neutral, cameraError())
        // Switched on: the zoom is asked for and not answered yet — settling, so it is the reason.
        coord.setEnabled(true)
        zoom = coord.state()
        assertEquals(true, zoom!!.settling)
        assertEquals(zooming, cameraError())
        // Settling when the wait began, settled by the time it ended: still the reason.
        duringCameraShot = { coord.onApplied(asked, 1.9); zoom = coord.state() }
        assertEquals(zooming, cameraError())
        assertEquals(false, zoom!!.settling)
        // Settled before the request: neutral again.
        duringCameraShot = {}
        assertEquals(neutral, cameraError())
        // Not settled when the wait began, settling by its end (a change landed meanwhile): the reason.
        duringCameraShot = { coord.setArea(RoiFrac(0.3, 0.3, 0.7, 0.7)); zoom = coord.state() }
        assertEquals(zooming, cameraError())
        duringCameraShot = {}
        // No scan screen at all: today's words, whatever the zoom said last.
        live = false
        assertEquals("the camera isn't running — open the scan screen on the phone", cameraError())
    }

    @Test fun guestsCannotSeeOrChangeTheSettings() {
        for ((m, p) in listOf("GET" to "/api/device", "PATCH" to "/api/device", "GET" to "/api/device/snapshot.jpg")) {
            assertEquals("$m $p", true, ScanServer.adminOnly(ApiRequest(m, p, role = ROLE_GUEST)))
        }
        // The camera view too: the rule reads the path, which never carries the query.
        assertEquals(true, ScanServer.adminOnly(ApiRequest("GET", "/api/device/snapshot.jpg",
            query = mapOf("view" to "camera"), role = ROLE_GUEST)))
    }

    @Test fun checkMarkTimeIsWholeMsInRange() {
        assertEquals(200, patch("""{"check_ms":1200}""").status)
        assertEquals(1200, st.checkMs)
        assertEquals(1200L, (json(api.handle(ApiRequest("GET", "/api/device"))!!)["check_ms"] as Number).toLong())
        assertEquals(200, patch("""{"check_ms":250}""").status)
        assertEquals(200, patch("""{"check_ms":10000}""").status)
        assertEquals(10000, st.checkMs)
    }
}
