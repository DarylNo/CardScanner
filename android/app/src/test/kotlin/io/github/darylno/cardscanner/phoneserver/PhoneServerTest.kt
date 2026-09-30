package io.github.darylno.cardscanner.phoneserver

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.server.ApiRequest
import io.github.darylno.cardscanner.core.server.GoldenApi
import io.github.darylno.cardscanner.gateway.GatewayServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stage 3 on the phone: the SQLite store answers the golden session recorded
 * from the real server, and the preview serves pages + API through the real
 * gateway (join code, cookie) end to end.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: SqliteScanStore
    private lateinit var backend: PhoneBackend
    private var gateway: GatewayServer? = null
    private val client = OkHttpClient.Builder().followRedirects(false).build()

    @Before fun setUp() {
        store = SqliteScanStore(tmp.newFile("scans.db").also { it.delete() })
        backend = PhoneBackend(
            store, PhotoDir(tmp.newFolder("photos")),
            pages = { name -> "<html>$name</html>".toByteArray() },
            version = "1.0.14 (phone)",
            search = { q -> listOf(mapOf("id" to "x", "name" to q)) },
            lanIp = { "192.168.1.20" },
            packRows = { 50498 },
        )
    }

    @After fun tearDown() {
        gateway?.stop()
        store.close()
    }

    @Test fun theSqliteStoreAnswersTheGoldenSession() {
        var n = 0
        val result = GoldenApi.replay { clock ->
            SqliteScanStore(tmp.newFile("golden${n++}.db").also { it.delete() }, clock)
        }
        if (result.isNotEmpty()) fail(result.joinToString("\n"))
    }

    @Test fun pagesAndStartupEndpointsLoad() {
        fun get(p: String, q: Map<String, String> = emptyMap()) = backend.handle(ApiRequest("GET", p, q))
        assertEquals("<html>desktop.html</html>", String(get("/").body))
        assertEquals("<html>phone.html</html>", String(get("/phone").body))
        for (p in listOf("/api/version", "/api/health", "/api/setup/status", "/api/update-check",
            "/api/price-status", "/api/price-debug", "/api/scans")) {
            val r = get(p)
            assertEquals(p, 200, r.status)
            MiniJson.parse(String(r.body))                       // valid JSON
        }
        val setup = MiniJson.parse(String(get("/api/setup/status").body)) as Map<*, *>
        assertEquals(true, setup["index_built"])                // no "build the card database" banner
        val found = MiniJson.parse(String(get("/api/search", mapOf("q" to "Opt")).body)) as Map<*, *>
        assertEquals("Opt", ((found["candidates"] as List<*>)[0] as Map<*, *>)["name"])
        assertEquals(501, get("/api/export.csv").status)          // 3d, said plainly
        assertEquals(404, get("/nope").status)
    }

    @Test fun servedThroughTheGatewayWithTheJoinCode() {
        val g = GatewayServer(LocalUpstream(backend), port = 0, hostname = "127.0.0.1").also { gateway = it }
        g.startServing()
        val base = "http://127.0.0.1:${g.listeningPort}"
        // no cookie → the code page, never the data
        client.newCall(Request.Builder().url("$base/api/scans").build()).execute().use { r ->
            assertTrue(r.code == 200 || r.code == 401)
            assertTrue(!r.body!!.string().startsWith("["))
        }
        val cookie = client.newCall(Request.Builder().url("$base/join?code=${g.code}").build()).execute().use { r ->
            assertEquals(302, r.code)
            r.header("Set-Cookie")!!.substringBefore(';')
        }
        // a filed capture shows up in the served list, and a pick round-trips
        backend.api.fileScan(mapOf("identified" to true, "card_read" to mapOf("name" to "Opt"),
            "confidence" to mapOf("name" to "high"),
            "candidates" to listOf(mapOf("id" to "o1", "name" to "Opt", "set" to "dom", "collector_number" to "60"),
                mapOf("id" to "o2", "name" to "Opt", "set" to "xln", "collector_number" to "65"))), byteArrayOf(9))
        val list = client.newCall(Request.Builder().url("$base/api/scans").header("Cookie", cookie).build())
            .execute().use { r -> assertEquals(200, r.code); MiniJson.parse(r.body!!.string()) as List<*> }
        assertEquals(1, list.size)
        val pick = """{"printing":{"id":"o2","name":"Opt","set":"xln","collector_number":"65"}}"""
        client.newCall(Request.Builder().url("$base/api/scans/1/select").header("Cookie", cookie)
            .post(pick.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            assertEquals(200, r.code)
            val row = MiniJson.parse(r.body!!.string()) as Map<*, *>
            assertEquals("selected", row["status"])
            assertEquals("xln", (row["selection"] as Map<*, *>)["set"])
        }
        client.newCall(Request.Builder().url("$base/api/scans/1/image").header("Cookie", cookie).build())
            .execute().use { r -> assertEquals(200, r.code); assertEquals(1, r.body!!.bytes().size) }
    }
}
