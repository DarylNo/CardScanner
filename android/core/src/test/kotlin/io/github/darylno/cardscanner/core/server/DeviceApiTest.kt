package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.RoiFrac
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's settings from the browser: shapes, validation, admin only. */
class DeviceApiTest {
    private var st = DeviceApi.DeviceState(handheld = false, auto = true, roi = null, torch = false,
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
        assertEquals(mapOf("mode" to "mount", "auto" to true, "roi" to null, "torch" to false, "vibration" to true,
            "high_res" to false, "ae_lock" to false, "camera_live" to true), s)
    }

    @Test fun appliesWhatIsGivenInOneWrite() {
        val r = patch("""{"mode":"handheld","torch":true,"roi":{"x0":0.1,"y0":0.2,"x1":0.6,"y1":0.9}}""")
        assertEquals(200, r.status)
        assertEquals(1, writes)
        assertEquals(RoiFrac(0.1, 0.2, 0.6, 0.9), st.roi)
        assertEquals(true, st.handheld); assertEquals(true, st.torch); assertEquals(true, st.auto)
        assertEquals(mapOf("x0" to 0.1, "y0" to 0.2, "x1" to 0.6, "y1" to 0.9), json(r)["roi"])
        assertEquals(200, patch("""{"roi":null}""").status)
        assertEquals(null, st.roi)
    }

    @Test fun refusesBadValuesAndWritesNothing() {
        for (b in listOf("""{"mode":"pocket"}""", """{"torch":"yes"}""", """{"nope":1}""",
            """{"torch":true,"roi":{"x0":0.1,"y0":0.1,"x1":0.12,"y1":0.9}}""",       // side < 0.08
            """{"roi":{"x0":-0.1,"y0":0,"x1":0.5,"y1":0.5}}""", """{"roi":[0,0,1,1]}""")) {
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
}
