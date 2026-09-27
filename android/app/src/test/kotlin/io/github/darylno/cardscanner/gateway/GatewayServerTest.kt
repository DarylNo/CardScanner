package io.github.darylno.cardscanner.gateway

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import fi.iki.elonen.NanoHTTPD
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GatewayServerTest {

    private lateinit var upstreamServer: MockWebServer
    private lateinit var gateway: GatewayServer
    private var now = 1_000_000L
    private val upClient = OkHttpClient.Builder().followRedirects(false).build()
    private val client = OkHttpClient.Builder().followRedirects(false)
        .readTimeout(20, TimeUnit.SECONDS).build()

    /** Plain-HTTP stand-in for ServerClient.proxy, as the app adapter will do it. */
    private class OkUpstream(private val base: () -> HttpUrl, private val client: OkHttpClient) : Upstream {
        override fun proxy(method: String, pathAndQuery: String, headers: Map<String, String>, body: ByteArray?): Response {
            val media = headers["content-type"]?.toMediaTypeOrNull()
            val b = base()
            val req = Request.Builder()
                .url("http://${b.host}:${b.port}$pathAndQuery")
                .method(method, body?.toRequestBody(media))
            headers.forEach { (k, v) -> req.header(k, v) }
            return client.newCall(req.build()).execute()
        }
    }

    @Before
    fun setUp() {
        upstreamServer = MockWebServer()
        upstreamServer.start()
        val base = upstreamServer.url("/")
        gateway = GatewayServer(OkUpstream({ base }, upClient), port = 0, hostname = "127.0.0.1", clock = { now })
        gateway.startServing()
    }

    @After
    fun tearDown() {
        gateway.stop()
        try { upstreamServer.shutdown() } catch (_: IOException) {}
    }

    private fun url(pathAndQuery: String) = "http://127.0.0.1:${gateway.listeningPort}$pathAndQuery"

    private fun get(pathAndQuery: String, cookie: String? = null, ua: String? = null): Response {
        val b = Request.Builder().url(url(pathAndQuery))
        cookie?.let { b.header("Cookie", it) }
        ua?.let { b.header("User-Agent", it) }
        return client.newCall(b.build()).execute()
    }

    /** Joins with the right code; returns "cs_guest=<token>". */
    private fun join(ua: String = DESKTOP_UA): String {
        get("/join?code=${gateway.code}", ua = ua).use { r ->
            assertEquals(302, r.code)
            val sc = r.header("Set-Cookie")!!
            return sc.substringBefore(';')
        }
    }

    // ---- join flow -------------------------------------------------------------------

    @Test
    fun noCookieGetsCodePageAndNothingIsProxied() {
        get("/").use { r ->
            assertEquals(200, r.code)
            val body = r.body!!.string()
            assertTrue(body.contains("Enter the 6-digit code"))
            assertTrue(body.contains("action=\"/join\""))
            assertEquals("no-store", r.header("Cache-Control"))
        }
        get("/api/scans?limit=5", cookie = "cs_guest=deadbeefdeadbeefdeadbeefdeadbeef").use { r ->
            assertTrue(r.body!!.string().contains("Enter the 6-digit code"))
        }
        val post = Request.Builder().url(url("/api/scans/1/select")).post("{}".toRequestBody()).build()
        client.newCall(post).execute().use { r -> assertEquals(401, r.code) }
        assertEquals(0, upstreamServer.requestCount)
    }

    @Test
    fun badCodeIsRejectedWithoutCookie() {
        val wrong = if (gateway.code == "000000") "111111" else "000000"
        get("/join?code=$wrong").use { r ->
            assertEquals(403, r.code)
            assertNull(r.header("Set-Cookie"))
            assertTrue(r.body!!.string().contains("not right"))
        }
        get("/join?code=12345").use { r -> assertEquals(403, r.code) }
        get("/join").use { r -> assertEquals(200, r.code); assertNull(r.header("Set-Cookie")) }
        assertEquals(0, gateway.sessionCount)
    }

    @Test
    fun goodCodeSetsHardenedCookieAndRedirectsByUserAgent() {
        get("/join?code=${gateway.code}", ua = DESKTOP_UA).use { r ->
            assertEquals(302, r.code)
            assertEquals("/", r.header("Location"))
            val sc = r.header("Set-Cookie")!!
            assertTrue(sc, Regex("^cs_guest=[0-9a-f]{32}; ").containsMatchIn(sc))
            assertTrue(sc.contains("HttpOnly"))
            assertTrue(sc.contains("SameSite=Lax"))
            assertTrue(sc.contains("Path=/"))
        }
        get("/join?code=${gateway.code}", ua = PHONE_UA).use { r ->
            assertEquals("/phone?panel=1", r.header("Location"))
        }
        get("/join?code=${gateway.code}", ua = "Mozilla/5.0 (iPhone) Mobile/15E148").use { r ->
            assertEquals("/phone?panel=1", r.header("Location"))
        }
        // spaces are tolerated ("123 456")
        get("/join?code=${gateway.code.take(3)}%20${gateway.code.drop(3)}").use { r -> assertEquals(302, r.code) }
        assertEquals(4, gateway.sessionCount)
        val a = join(); val b = join()
        assertNotEquals(a, b)
    }

    @Test
    fun lockoutAfterEightBadTriesForFiveMinutes() {
        val wrong = if (gateway.code == "000000") "111111" else "000000"
        repeat(7) { get("/join?code=$wrong").use { r -> assertEquals(403, r.code) } }
        get("/join?code=$wrong").use { r ->
            assertEquals(429, r.code)
            assertEquals("300", r.header("Retry-After"))
        }
        // even the right code is refused while locked
        get("/join?code=${gateway.code}").use { r -> assertEquals(429, r.code); assertNull(r.header("Set-Cookie")) }
        now += GatewayServer.LOCKOUT_MS - 1
        get("/join?code=${gateway.code}").use { r -> assertEquals(429, r.code) }
        now += 2
        get("/join?code=${gateway.code}").use { r -> assertEquals(302, r.code) }
    }

    @Test
    fun failuresOutsideTheWindowDoNotAccumulate() {
        val wrong = if (gateway.code == "000000") "111111" else "000000"
        repeat(7) { get("/join?code=$wrong").use { r -> assertEquals(403, r.code) } }
        now += GatewayServer.FAIL_WINDOW_MS + 1
        repeat(7) { get("/join?code=$wrong").use { r -> assertEquals(403, r.code) } }
        get("/join?code=${gateway.code}").use { r -> assertEquals(302, r.code) }
    }

    // ---- global budget / atomic attempts / LAN-only -----------------------------------

    @Test
    fun globalBudgetRotatesCodeAfterTwentyDistributedFailures() {
        val cookie = join()
        val oldCode = gateway.code
        val wrong = if (oldCode == "000000") "111111" else "000000"
        val rotated = mutableListOf<String>()
        gateway.onCodeRotated = { synchronized(rotated) { rotated += it } }
        // 7 over HTTP from 127.0.0.1 (one short of its per-IP lock) …
        repeat(7) { get("/join?code=$wrong").use { r -> assertEquals(403, r.code) } }
        // … and 12 more spread over addresses no per-IP lock ever sees twice.
        for (i in 0 until 12) {
            val ip = if (i % 2 == 0) "fe80::1234:${i}" else "192.168.43.${10 + i}"
            assertTrue(gateway.attemptJoin(ip, wrong, now) is GatewayServer.JoinResult.Wrong)
        }
        assertEquals(oldCode, gateway.code)
        assertTrue(rotated.isEmpty())
        // the 20th failure in the window rotates the code
        assertTrue(gateway.attemptJoin("fe80::beef", wrong, now) is GatewayServer.JoinResult.Wrong)
        val newCode = gateway.code
        assertNotEquals(oldCode, newCode)   // 1-in-a-million flake would need a retry here
        assertEquals(listOf(newCode), rotated)

        // the old code no longer joins; 127.0.0.1's 7 failures were reset (this would be its 8th → 429)
        get("/join?code=$oldCode").use { r -> assertEquals(403, r.code); assertNull(r.header("Set-Cookie")) }
        // the existing session still works
        upstreamServer.enqueue(MockResponse().setBody("still in"))
        get("/api/version", cookie = cookie).use { r -> assertEquals("still in", r.body!!.string()) }
        assertEquals(1, gateway.sessionCount)
        // and the new code joins
        get("/join?code=$newCode").use { r -> assertEquals(302, r.code) }
    }

    @Test
    fun globalBudgetIsAWindowNotALifetimeCount() {
        val oldCode = gateway.code
        val wrong = if (oldCode == "000000") "111111" else "000000"
        for (i in 0 until 19) gateway.attemptJoin("10.0.0.${i + 2}", wrong, now)
        now += GatewayServer.FAIL_WINDOW_MS + 1
        gateway.attemptJoin("10.0.1.1", wrong, now)
        assertEquals(oldCode, gateway.code)
        // right codes do not count toward the budget
        repeat(30) { gateway.attemptJoin("10.0.2.${it + 2}", oldCode, now) }
        assertEquals(oldCode, gateway.code)
    }

    @Test
    fun parallelBadAttemptsFromOneIpNeverEvaluateMoreThanEight() {
        val oldCode = gateway.code
        val wrong = if (oldCode == "000000") "111111" else "000000"
        val threads = 64
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until threads).map {
                pool.submit<GatewayServer.JoinResult> { start.await(); gateway.attemptJoin("10.1.2.3", wrong, now) }
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            // 7 plain rejections + the 8th that lands the lock; everything else refused unevaluated
            assertEquals(7, results.count { it is GatewayServer.JoinResult.Wrong })
            assertEquals(threads - 7, results.count { it is GatewayServer.JoinResult.Locked })
        } finally {
            pool.shutdownNow()
        }
        // if more than 8 had been evaluated as failures, 20 would have rotated the code
        assertEquals(oldCode, gateway.code)
        // locked even for the right code
        assertTrue(gateway.attemptJoin("10.1.2.3", oldCode, now) is GatewayServer.JoinResult.Locked)
    }

    @Test
    fun parallelBadHttpJoinsFromOneIpGetSevenRejectionsThenLockout() {
        val wrong = if (gateway.code == "000000") "111111" else "000000"
        val n = 24
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until n).map {
                pool.submit<Int> {
                    start.await()
                    // a fresh client per call: separate connections, separate server threads
                    OkHttpClient.Builder().followRedirects(false).build()
                        .newCall(Request.Builder().url(url("/join?code=$wrong")).build()).execute().use { it.code }
                }
            }
            start.countDown()
            val codes = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(codes.toString(), 7, codes.count { it == 403 })
            assertEquals(codes.toString(), n - 7, codes.count { it == 429 })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun isLocalClientRanges() {
        val allowed = listOf(
            "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.43.7",
            "127.0.0.1", "127.8.9.10",
            "fe80::1", "fe80::a1b2:c3ff:fed4:e5f6%wlan0", "FE80::1", "febf:ffff::1", "[fe80::1]",
            "fc00::1", "fd7a:115c:a1e0::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "::1", "0:0:0:0:0:0:0:1",
            "::ffff:192.168.1.5", "::ffff:10.0.0.2", "::FFFF:172.20.1.1", "::ffff:c0a8:0105", "0:0:0:0:0:ffff:127.0.0.1",
        )
        val refused = listOf(
            "100.64.0.1", "100.100.100.100", "100.127.255.255",          // Tailscale / CGNAT
            "8.8.8.8", "172.15.255.255", "172.32.0.1", "192.169.0.1", "11.0.0.1", "169.254.1.1", "0.0.0.0",
            "255.255.255.255", "1.2.3.4",
            "2001:db8::1", "2607:fb90:1234::5", "fec0::1", "fe7f::1", "ff02::1", "::", "fb00::1",
            "::ffff:100.64.0.1", "::ffff:8.8.8.8", "::192.168.1.1", "64:ff9b::192.168.1.1",
            "", "?", "localhost", "10.0.0", "10.0.0.256", "10.0.0.1.2", "1::2::3", "fe80::1::", "fe80:::1",
            "fe80::12345", "gggg::1", "fe80::+1", "fe80::１", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7", "10.0.0.1 ",
        )
        for (ip in allowed) assertTrue("should allow '$ip'", GatewayServer.isLocalClient(ip))
        for (ip in refused) {
            if (ip == "10.0.0.1 ") continue   // trimmed → allowed; listed only to document trimming
            assertFalse("should refuse '$ip'", GatewayServer.isLocalClient(ip))
        }
        assertTrue(GatewayServer.isLocalClient("10.0.0.1 "))
    }

    @Test
    fun offLanClientIsRefusedBeforeAnythingElse() {
        // NanoHTTPD reports every loopback peer as 127.0.0.1, so drive serve() with a session
        // whose peer is a Tailscale / public address; the real socket path is covered below.
        for (ip in listOf("100.101.102.103", "2607:fb90::5", "::ffff:100.64.1.1")) {
            for (path in listOf("/join", "/", "/api/scans")) {
                val r = gateway.serve(FakeSession(ip, path, mapOf("code" to listOf(gateway.code))))
                assertEquals(403, r.status.requestStatus)
                val body = r.data.readBytes().toString(Charsets.UTF_8)
                assertTrue(body, body.contains("Only devices on this phone's Wi-Fi or hotspot can join"))
            }
        }
        assertEquals(0, gateway.sessionCount)
        assertEquals(0, upstreamServer.requestCount)
        // a LAN peer through the same entry point gets the normal code page
        assertEquals(200, gateway.serve(FakeSession("192.168.43.20", "/", emptyMap())).status.requestStatus)
        // end to end over a real socket: loopback is local
        get("/").use { r -> assertEquals(200, r.code); assertTrue(r.body!!.string().contains("Enter the 6-digit code")) }
    }

    private class FakeSession(
        private val ip: String,
        private val path: String,
        private val params: Map<String, List<String>>,
    ) : NanoHTTPD.IHTTPSession {
        override fun execute() {}
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = mapOf("remote-addr" to "127.0.0.1")
        override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getMethod() = NanoHTTPD.Method.GET
        @Deprecated("NanoHTTPD API") override fun getParms(): Map<String, String> = params.mapValues { it.value.first() }
        override fun getParameters(): Map<String, List<String>> = params
        override fun getQueryParameterString(): String? = null
        override fun getUri() = path
        override fun parseBody(files: MutableMap<String, String>?) {}
        override fun getRemoteIpAddress() = ip
        override fun getRemoteHostName() = ip
    }

    // ---- proxy -----------------------------------------------------------------------

    @Test
    fun proxiesGetWithQueryStatusAndHeaders() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setHeader("Cache-Control", "no-store")
            .setHeader("X-Server", "cardscanner")
            .setBody("""{"scans":[]}"""))
        get("/api/scans?limit=5&q=Shivan%20Dragon&x=a%2Bb", cookie = cookie).use { r ->
            assertEquals(200, r.code)
            assertEquals("""{"scans":[]}""", r.body!!.string())
            assertEquals("application/json", r.header("Content-Type"))
            assertEquals("no-store", r.header("Cache-Control"))
            assertEquals("cardscanner", r.header("X-Server"))
        }
        val rec = upstreamServer.takeRequest()
        assertEquals("GET", rec.method)
        assertEquals("/api/scans?limit=5&q=Shivan%20Dragon&x=a%2Bb", rec.path)
    }

    @Test
    fun proxiesPostBodyAndErrorStatus() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json")
            .setBody("""{"detail":"bad foil"}"""))
        val req = Request.Builder().url(url("/api/scans/7/select?merge=1")).header("Cookie", cookie)
            .post("""{"printing":"abc","foil":true}""".toRequestBody("application/json".toMediaTypeOrNull())).build()
        client.newCall(req).execute().use { r ->
            assertEquals(422, r.code)
            assertEquals("""{"detail":"bad foil"}""", r.body!!.string())
        }
        val rec = upstreamServer.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/api/scans/7/select?merge=1", rec.path)
        assertEquals("""{"printing":"abc","foil":true}""", rec.body.readUtf8())
        assertTrue(rec.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun proxiesMultipartUploadBinaryIntact() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setBody("ok"))
        val bytes = ByteArray(300_000) { (it * 31 + 7).toByte() }
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("files", "frame0.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())).build()
        client.newCall(Request.Builder().url(url("/api/scan")).header("Cookie", cookie).post(body).build()).execute()
            .use { r -> assertEquals(200, r.code) }
        val rec = upstreamServer.takeRequest()
        val sent = Buffer().also { body.writeTo(it) }.readByteArray()
        assertTrue(rec.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        assertTrue(sent.contentEquals(rec.body.readByteArray()))
    }

    @Test
    fun proxiesDeleteAnd204() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setResponseCode(204))
        upstreamServer.enqueue(MockResponse().setBody("after"))
        val req = Request.Builder().url(url("/api/scans/12")).header("Cookie", cookie).delete().build()
        client.newCall(req).execute().use { r ->
            assertEquals(204, r.code)
            assertEquals("", r.body!!.string())
        }
        // the keep-alive connection is still sane after a bodyless response
        get("/api/after", cookie = cookie).use { r -> assertEquals("after", r.body!!.string()) }
        val rec = upstreamServer.takeRequest()
        assertEquals("DELETE", rec.method)
        assertEquals("/api/scans/12", rec.path)
        assertEquals("/api/after", upstreamServer.takeRequest().path)
    }

    @Test
    fun stripsHopByHopAndOurCookieUpstream() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setBody("x"))
        val req = Request.Builder().url(url("/api/version"))
            .header("Cookie", "theme=dark; $cookie; lang=en")
            .header("Keep-Alive", "timeout=5")
            .header("Proxy-Authorization", "Basic Zm9vOmJhcg==")
            .header("TE", "trailers")
            .header("Trailer", "X-Checksum")
            .header("Upgrade", "h2c")
            .header("X-Keep-Me", "yes")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { r -> assertEquals(200, r.code) }
        val rec = upstreamServer.takeRequest()
        assertEquals("theme=dark; lang=en", rec.getHeader("Cookie"))
        for (h in listOf("Keep-Alive", "Proxy-Authorization", "TE", "Trailer", "Upgrade", "remote-addr", "http-client-ip")) {
            assertNull("$h leaked upstream", rec.getHeader(h))
        }
        assertEquals("yes", rec.getHeader("X-Keep-Me"))
        assertEquals("application/json", rec.getHeader("Accept"))
        assertFalse(rec.headers.toString().contains(cookie.substringAfter('=')))
    }

    @Test
    fun onlyOurCookieMeansNoCookieHeaderUpstream() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setBody("x"))
        get("/", cookie = cookie).use { r -> assertEquals(200, r.code) }
        assertNull(upstreamServer.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun stripsHopByHopFromResponseKeepsMultipleSetCookieAndRelativizesLocation() {
        val cookie = join()
        val upHost = upstreamServer.hostName
        upstreamServer.enqueue(MockResponse().setResponseCode(307)
            .setHeaders(Headers.Builder()
                .add("Location", "http://$upHost:${upstreamServer.port}/phone?panel=1&detail=5")
                .add("Keep-Alive", "timeout=99")
                .add("Proxy-Authenticate", "Basic")
                .add("Upgrade", "h2c")
                .add("Set-Cookie", "a=1; Path=/")
                .add("Set-Cookie", "b=2; Path=/")
                .add("X-End", "1")
                .build())
            .setBody("moved"))
        get("/phone?detail=5", cookie = cookie).use { r ->
            assertEquals(307, r.code)
            assertEquals("/phone?panel=1&detail=5", r.header("Location"))
            assertNull(r.header("Keep-Alive"))
            assertNull(r.header("Proxy-Authenticate"))
            assertNull(r.header("Upgrade"))
            assertEquals(listOf("a=1; Path=/", "b=2; Path=/"), r.headers("Set-Cookie"))
            assertEquals("1", r.header("X-End"))
            assertEquals("moved", r.body!!.string())
        }
    }

    @Test
    fun streamsLargeBody() {
        val cookie = join()
        val big = ByteArray(12 * 1024 * 1024) { (it % 251).toByte() }
        upstreamServer.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(big)))
        // chunked upstream (unknown length) must stream too
        upstreamServer.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
            .setChunkedBody(Buffer().write(big), 64 * 1024))
        for (i in 0 until 2) {
            get("/scan_images/big.jpg", cookie = cookie).use { r ->
                assertEquals(200, r.code)
                val got = r.body!!.bytes()
                assertEquals(big.size, got.size)
                assertTrue(MessageDigest.getInstance("SHA-256").digest(big)
                    .contentEquals(MessageDigest.getInstance("SHA-256").digest(got)))
            }
        }
    }

    @Test
    fun firstBytesArriveBeforeUpstreamFinishes() {
        val cookie = join()
        val body = ByteArray(2 * 1024 * 1024) { 1 }
        // ~2 s upstream: 256 KB every 250 ms
        upstreamServer.enqueue(MockResponse().setBody(Buffer().write(body)).throttleBody(256 * 1024, 250, TimeUnit.MILLISECONDS))
        val t0 = System.nanoTime()
        get("/slow", cookie = cookie).use { r ->
            val src = r.body!!.byteStream()
            val first = ByteArray(64 * 1024)
            var n = 0
            while (n < first.size) { val k = src.read(first, n, first.size - n); if (k < 0) break; n += k }
            val firstMs = (System.nanoTime() - t0) / 1_000_000
            var total = n.toLong()
            val buf = ByteArray(65536)
            while (true) { val k = src.read(buf); if (k < 0) break; total += k }
            val allMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals(body.size.toLong(), total)
            assertTrue("first 64 KB at ${firstMs}ms, all at ${allMs}ms", firstMs < allMs - 800)
        }
    }

    @Test
    fun upstreamDownGives502Page() {
        val cookie = join()
        upstreamServer.shutdown()
        get("/api/scans", cookie = cookie).use { r ->
            assertEquals(502, r.code)
            assertTrue(r.header("Content-Type")!!.startsWith("text/html"))
            assertTrue(r.body!!.string().contains("Scanner server unreachable (home server offline or Tailscale down)"))
        }
    }

    @Test
    fun upstreamThrowingAnythingGives502() {
        val g = GatewayServer({ _, _, _, _ -> throw IllegalStateException("boom") }, port = 0, hostname = "127.0.0.1")
        g.startServing()
        try {
            val cookie = client.newCall(Request.Builder().url("http://127.0.0.1:${g.listeningPort}/join?code=${g.code}").build())
                .execute().use { it.header("Set-Cookie")!!.substringBefore(';') }
            client.newCall(Request.Builder().url("http://127.0.0.1:${g.listeningPort}/x").header("Cookie", cookie).build())
                .execute().use { r -> assertEquals(502, r.code) }
        } finally {
            g.stop()
        }
    }

    @Test
    fun stopSharingRotatesCodeAndKillsSessions() {
        val cookie = join()
        val oldCode = gateway.code
        upstreamServer.enqueue(MockResponse().setBody("in"))
        get("/", cookie = cookie).use { r -> assertEquals("in", r.body!!.string()) }

        val newCode = gateway.stopSharing()
        assertEquals(newCode, gateway.code)
        assertNotEquals(oldCode, newCode)   // 1-in-a-million flake would need a retry here
        assertEquals(0, gateway.sessionCount)

        get("/", cookie = cookie).use { r -> assertTrue(r.body!!.string().contains("Enter the 6-digit code")) }
        get("/join?code=$oldCode").use { r -> assertEquals(403, r.code) }
        get("/join?code=$newCode").use { r -> assertEquals(302, r.code) }
        assertEquals(1, upstreamServer.requestCount)
    }

    @Test
    fun cookieHelpers() {
        assertEquals(listOf("abc"), GatewayServer.cookieValues("x=1; cs_guest=abc; y=2"))
        assertEquals(emptyList<String>(), GatewayServer.cookieValues(null))
        assertEquals("x=1; y=2", GatewayServer.stripOurCookie("x=1; cs_guest=abc; y=2"))
        assertNull(GatewayServer.stripOurCookie("cs_guest=abc"))
        assertEquals("/a%20b/%C3%A9/c", GatewayServer.encodePath("/a b/é/c"))
        assertEquals("/api/scans/12", GatewayServer.encodePath("/api/scans/12"))
    }

    companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36"
        const val PHONE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36"
    }
}
