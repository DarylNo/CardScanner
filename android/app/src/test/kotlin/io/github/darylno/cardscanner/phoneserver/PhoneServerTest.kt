package io.github.darylno.cardscanner.phoneserver

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.server.ApiRequest
import io.github.darylno.cardscanner.core.server.F2fAnswer
import io.github.darylno.cardscanner.core.server.GoldenApi
import io.github.darylno.cardscanner.gateway.AdminPairing
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
            f2f = { _, _, _, _, _ -> F2fAnswer.NotListed },
            launch = { it.run() },
            now = { System.nanoTime() / 1e9 },
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

    @Test fun theSqliteStoreAnswersTheGoldenExportSession() {
        var n = 0
        val result = GoldenApi.replayExport(tmp.newFile("layout.json").also { it.delete() }) { clock ->
            SqliteScanStore(tmp.newFile("export${n++}.db").also { it.delete() }, clock)
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
        assertEquals(200, get("/api/export.csv").status)
        assertEquals(200, get("/api/export").status)
        assertEquals(404, get("/nope").status)
    }

    @Test fun servedThroughTheGatewayWithTheJoinCode() {
        val admins = AdminPairing(null)
        val g = GatewayServer(LocalUpstream(backend), port = 0, hostname = "127.0.0.1", admins = admins)
            .also { gateway = it }
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
        // every answer names its type (pages, JSON, photos) — the browser must not have to sniff
        client.newCall(Request.Builder().url("$base/").header("Cookie", cookie).build())
            .execute().use { r -> assertEquals("text/html; charset=utf-8", r.header("Content-Type")) }
        client.newCall(Request.Builder().url("$base/api/scans").header("Cookie", cookie).build())
            .execute().use { r -> assertEquals("application/json", r.header("Content-Type")) }
        // the CSV download (admin: the paired computer) reaches the browser as a named attachment, BOM first
        val admin = joinCookie(base, admins.newCode(), GatewayServer.ADMIN_COOKIE)!!
        client.newCall(Request.Builder().url("$base/api/export.csv").header("Cookie", admin).build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertEquals("attachment; filename=cards.csv", r.header("Content-Disposition"))
                val text = String(r.body!!.bytes(), Charsets.UTF_8)
                assertEquals("\uFEFFQuantity,Name,Set Code,Set Name,Collector Number,Condition,Finish,Price\r\n" +
                    "1,Opt,XLN,,65,NM,Non-Foil,\r\n", text)
            }
        client.newCall(Request.Builder().url("$base/api/export").header("Cookie", admin).build())
            .execute().use { r -> assertEquals("1 XLN 65 NM Non-Foil\n", r.body!!.string()) }
    }

    // ── roles (3e) ──────────────────────────────────────────────────────────

    private fun fileTwo() = repeat(2) {
        backend.api.fileScan(mapOf("identified" to true, "card_read" to mapOf("name" to "Opt"),
            "candidates" to listOf(mapOf("id" to "o$it", "name" to "Opt", "set" to "dom", "collector_number" to "60"),
                mapOf("id" to "x$it", "name" to "Opt", "set" to "xln", "collector_number" to "65"))), null)
    }

    private fun joinCookie(base: String, code: String, name: String): String? =
        client.newCall(Request.Builder().url("$base/join?code=$code").build()).execute().use { r ->
            r.headers("Set-Cookie").firstOrNull { it.startsWith("$name=") }?.substringBefore(';')
        }

    private fun req(base: String, cookie: String, method: String, path: String, body: String? = null,
                    extra: Pair<String, String>? = null): Int {
        val b = Request.Builder().url("$base$path").header("Cookie", cookie)
        extra?.let { b.header(it.first, it.second) }
        b.method(method, body?.toRequestBody("application/json".toMediaType()))
        return client.newCall(b.build()).execute().use { it.code }
    }

    private fun page(base: String, cookie: String, path: String): String =
        client.newCall(Request.Builder().url("$base$path").header("Cookie", cookie).build()).execute().use { it.body!!.string() }

    @Test fun guestsFlagButOnlyThePairedComputerDeletesAndExports() {
        val admins = AdminPairing(tmp.newFile("admins.json").also { it.delete() })
        val g = GatewayServer(LocalUpstream(backend), port = 0, hostname = "127.0.0.1", admins = admins)
            .also { gateway = it }
        g.startServing()
        val base = "http://127.0.0.1:${g.listeningPort}"
        fileTwo()
        val guest = joinCookie(base, g.code, GatewayServer.COOKIE)!!
        assertEquals(403, req(base, guest, "DELETE", "/api/scans/1"))
        assertEquals(403, req(base, guest, "GET", "/api/export.csv"))
        // a guest can't claim to be admin: the gateway drops the client's role header
        assertEquals(403, req(base, guest, "DELETE", "/api/scans/1", extra = GatewayServer.ROLE_HEADER to "admin"))
        assertEquals(200, req(base, guest, "PATCH", "/api/scans/1", """{"flagged":true}"""))
        assertEquals(true, store.get(1)!!["flagged"])
        val me = client.newCall(Request.Builder().url("$base/api/me").header("Cookie", guest).build())
            .execute().use { MiniJson.parse(it.body!!.string()) }
        assertEquals(mapOf("role" to "guest"), me)

        // pair the computer with the one-time code: admin, remembered
        val code = admins.newCode()
        val admin = joinCookie(base, code, GatewayServer.ADMIN_COOKIE)!!
        assertEquals(null, joinCookie(base, code, GatewayServer.ADMIN_COOKIE))     // used up
        assertEquals(200, req(base, admin, "GET", "/api/export.csv"))
        assertEquals(200, req(base, admin, "POST", "/api/scans/delete-all", """{"only":"flagged"}"""))
        assertEquals(listOf(2L), store.list().map { it["id"] })
        // pairing survives a restart (only the hash is on disk), and ends on revoke
        val reloaded = AdminPairing(java.io.File(tmp.root, "admins.json"))
        assertTrue(reloaded.isAdmin(admin.substringAfter('=')))
        assertTrue(!java.io.File(tmp.root, "admins.json").readText().contains(admin.substringAfter('=')))
        // guest sessions end when sharing stops; the paired computer stays admin
        g.stopSharing()
        assertTrue(page(base, guest, "/api/scans").contains("Join the card scanner"))
        assertTrue(page(base, admin, "/api/scans").startsWith("["))
        // revoking on the phone ends the pairing at once
        admins.revokeAll()
        assertTrue(page(base, admin, "/api/scans").contains("Join the card scanner"))
    }

    @Test fun theAdminCodeHasItsOwnBudgetAcrossAddresses() {
        // spread over many LAN addresses no IP is locked out — the code itself retires
        val admins = AdminPairing(null)
        val g = GatewayServer(LocalUpstream(backend), port = 0, hostname = "127.0.0.1", admins = admins)
        val code = admins.newCode()
        val wrong = if (code == "00000000") "11111111" else "00000000"
        repeat(AdminPairing.MAX_WRONG) { g.attemptJoin("10.0.0.${it + 1}", wrong, 1_000L) }
        assertEquals(null, admins.pendingCode)
        assertTrue(g.attemptJoin("10.0.0.99", code, 1_001L) is GatewayServer.JoinResult.Wrong)
        // 6-digit guest-code typos don't burn the owner's pairing
        val code2 = admins.newCode()
        repeat(AdminPairing.MAX_WRONG * 2) { admins.redeem("123456", 2_000L) }
        assertEquals(code2, admins.pendingCode)
    }

    @Test fun rotatingTheGuestCodeRetiresTheAdminCode() {
        val admins = AdminPairing(null)
        val g = GatewayServer(LocalUpstream(backend), port = 0, hostname = "127.0.0.1", admins = admins)
        admins.newCode()
        val wrong = if (g.code == "000000") "111111" else "000000"
        repeat(GatewayServer.GLOBAL_MAX_FAILURES) { g.attemptJoin("10.1.${it / 5}.${it % 5 + 1}", wrong, 1_000L) }
        assertEquals(null, admins.pendingCode)
    }

    @Test fun noRoleHeaderIsAGuest() {
        fileTwo()
        val up = LocalUpstream(backend)
        up.proxy("DELETE", "/api/scans/1", emptyMap(), null).use { assertEquals(403, it.code) }
        up.proxy("DELETE", "/api/scans/1", mapOf(GatewayServer.ROLE_HEADER to "Admin"), null).use { assertEquals(403, it.code) }
        up.proxy("DELETE", "/api/scans/1", mapOf(GatewayServer.ROLE_HEADER to "admin"), null).use { assertEquals(200, it.code) }
    }

    @Test fun wrongAdminCodesCountTowardTheLockout() {
        val admins = AdminPairing(null)
        val g = GatewayServer(LocalUpstream(backend), port = 0, hostname = "127.0.0.1", admins = admins)
        admins.newCode()
        var last: Any? = null
        repeat(GatewayServer.MAX_FAILURES) { last = g.attemptJoin("10.0.0.9", "00000000", 1_000L) }
        assertTrue(last is GatewayServer.JoinResult.Locked)
        // even the right code is refused while locked
        assertTrue(g.attemptJoin("10.0.0.9", admins.pendingCode!!, 1_001L) is GatewayServer.JoinResult.Locked)
    }

    // ── the phone's own settings from the browser ───────────────────────────

    @Test fun thePairedComputerChangesThePhonesSettingsLive() {
        val settings = io.github.darylno.cardscanner.ui.AppSettings(org.robolectric.RuntimeEnvironment.getApplication())
        settings.mode = io.github.darylno.cardscanner.ui.AppSettings.Mode.MOUNT
        settings.torch = false
        settings.roi = null
        val bridge = DeviceBridge(settings)
        var applied = 0
        bridge.screen = object : DeviceBridge.Screen {
            override fun applyRemoteSettings() { applied++ }
            override fun snapshotJpeg(): ByteArray? = byteArrayOf(-1, -40, -1)
        }
        val b = PhoneBackend(store, PhotoDir(tmp.newFolder("p2")), pages = { null }, version = "t",
            search = { emptyList() }, lanIp = { null }, packRows = { 0 },
            f2f = { _, _, _, _, _ -> F2fAnswer.NotListed }, launch = { it.run() }, now = { 0.0 },
            device = io.github.darylno.cardscanner.core.server.DeviceApi(bridge))
        val admins = AdminPairing(null)
        val g = GatewayServer(LocalUpstream(b), port = 0, hostname = "127.0.0.1", admins = admins).also { gateway = it }
        g.startServing()
        val base = "http://127.0.0.1:${g.listeningPort}"
        val guest = joinCookie(base, g.code, GatewayServer.COOKIE)!!
        val admin = joinCookie(base, admins.newCode(), GatewayServer.ADMIN_COOKIE)!!

        assertEquals(403, req(base, guest, "GET", "/api/device"))
        assertEquals(403, req(base, guest, "PATCH", "/api/device", """{"torch":true}"""))
        assertEquals(false, settings.torch)

        assertEquals(200, req(base, admin, "PATCH", "/api/device",
            """{"mode":"handheld","torch":true,"roi":{"x0":0.1,"y0":0.1,"x1":0.9,"y1":0.8}}"""))
        assertEquals(io.github.darylno.cardscanner.ui.AppSettings.Mode.HANDHELD, settings.mode)
        assertEquals(true, settings.torch)
        assertEquals(io.github.darylno.cardscanner.core.RoiFrac(0.1, 0.1, 0.9, 0.8), settings.roi)
        assertEquals(1, applied)                                         // the scan screen was told
        val st = MiniJson.parse(page(base, admin, "/api/device")) as Map<*, *>
        assertEquals("handheld", st["mode"]); assertEquals(true, st["camera_live"])
        client.newCall(Request.Builder().url("$base/api/device/snapshot.jpg").header("Cookie", admin).build())
            .execute().use { r -> assertEquals(200, r.code); assertEquals("image/jpeg", r.header("Content-Type")) }
        bridge.screen = null                                            // scan screen closed
        client.newCall(Request.Builder().url("$base/api/device/snapshot.jpg").header("Cookie", admin).build())
            .execute().use { r -> assertEquals(503, r.code) }
    }
}
