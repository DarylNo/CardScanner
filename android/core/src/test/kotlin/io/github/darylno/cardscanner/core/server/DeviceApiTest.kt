package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.ZoomCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's settings from the browser: shapes, validation, admin only. */
class DeviceApiTest {
    private var st = DeviceApi.DeviceState(auto = true, roi = null, torch = false,
        vibration = true, highRes = false, aeLock = false)
    private var writes = 0
    private var live = true
    private var cams = listOf("0" to "Camera 0 · back · 13 MP · autofocus", "2" to "Camera 2 · back · 2.0 MP · fixed focus")
    private var zoom: ZoomCoordinator.State? = null
    private val api = DeviceApi(object : DeviceApi.DeviceControl {
        override fun read() = st
        override fun write(s: DeviceApi.DeviceState) { st = s; writes++ }
        override fun snapshot(): ByteArray? = if (live) byteArrayOf(1, 2) else null
        override fun cameraLive() = live
        override fun cameras() = cams
        override fun zoom() = zoom
    })

    private fun patch(body: String) = api.handle(ApiRequest("PATCH", "/api/device", body = body.toByteArray()))!!
    private fun json(r: ApiResponse) = MiniJson.parse(String(r.body)) as Map<*, *>

    @Test fun readsTheSettings() {
        val s = json(api.handle(ApiRequest("GET", "/api/device"))!!)
        assertEquals(mapOf("mode" to "tray", "roi" to null, "torch" to false, "vibration" to true,
            "high_res" to false, "ae_lock" to false, "check_ms" to 2500L, "camera" to "auto",
            "cameras" to listOf(mapOf("id" to "0", "label" to "Camera 0 · back · 13 MP · autofocus"),
                mapOf("id" to "2", "label" to "Camera 2 · back · 2.0 MP · fixed focus")),
            "camera_live" to true, "zoom_fit" to false, "zoom" to null), s)
    }

    /**
     * "Zoom to fit the Area" (1.1.11): zoom_fit is a plain switch, validated; the live
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
            override fun drawingReady() {}
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

    @Test fun guestsCannotSeeOrChangeTheSettings() {
        for ((m, p) in listOf("GET" to "/api/device", "PATCH" to "/api/device", "GET" to "/api/device/snapshot.jpg")) {
            assertEquals("$m $p", true, ScanServer.adminOnly(ApiRequest(m, p, role = ROLE_GUEST)))
        }
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
