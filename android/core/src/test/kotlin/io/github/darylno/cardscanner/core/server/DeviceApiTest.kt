package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.RoiFrac
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's settings from the browser: shapes, validation, admin only. */
class DeviceApiTest {
    private var st = DeviceApi.DeviceState(auto = true, roi = null, torch = false,
        vibration = true, highRes = false, aeLock = false)
    private var writes = 0
    private var live = true
    private val api = DeviceApi(object : DeviceApi.DeviceControl {
        override fun read() = st
        override fun write(s: DeviceApi.DeviceState) { st = s; writes++ }
        override fun snapshot(): ByteArray? = if (live) byteArrayOf(1, 2) else null
        override fun cameraLive() = live
    })

    private fun patch(body: String) = api.handle(ApiRequest("PATCH", "/api/device", body = body.toByteArray()))!!
    private fun json(r: ApiResponse) = MiniJson.parse(String(r.body)) as Map<*, *>

    @Test fun readsTheSettings() {
        val s = json(api.handle(ApiRequest("GET", "/api/device"))!!)
        assertEquals(mapOf("mode" to "tray", "roi" to null, "torch" to false, "vibration" to true,
            "high_res" to false, "ae_lock" to false, "check_ms" to 2500L, "camera_live" to true), s)
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
