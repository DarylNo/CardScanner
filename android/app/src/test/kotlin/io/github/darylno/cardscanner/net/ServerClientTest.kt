package io.github.darylno.cardscanner.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * TLS pinning + failover against MockWebServer serving a cert shaped like the
 * server's: RSA, CN only, NO subjectAlternativeName (launch.py ensure_certs).
 */
class ServerClientTest {
    private val servers = mutableListOf<MockWebServer>()

    private fun cert(cn: String = "mtg-card-scanner"): HeldCertificate =
        HeldCertificate.Builder().rsa2048().commonName(cn).build()   // no SAN added

    private fun tlsServer(held: HeldCertificate): MockWebServer {
        val hc = HandshakeCertificates.Builder().heldCertificate(held).build()
        return MockWebServer().apply {
            useHttps(hc.sslSocketFactory(), false)
            start()
            servers += this
        }
    }

    private fun base(s: MockWebServer) = "https://127.0.0.1:${s.port}"

    /** A port nothing listens on → connection refused. */
    private fun deadBase(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "https://127.0.0.1:$port"
    }

    private fun pinOf(h: HeldCertificate) = Pin.sha256Hex(h.certificate)

    private fun client(urls: List<String>, pin: String, store: InMemoryConfigStore = InMemoryConfigStore(ServerConfig(urls, pin))) =
        ServerClient(store, Timeouts(connectMs = 2_000, readMs = 5_000, writeMs = 5_000)) to store

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    @Test
    fun certFixtureHasNoSan() {
        val held = cert()
        assertNull(held.certificate.subjectAlternativeNames)
    }

    @Test
    fun probeCapturesFingerprintAndSendsNothing() {
        val held = cert()
        val s = tlsServer(held)
        val r = Pairing.probe(base(s))
        assertTrue(r.ok)
        assertEquals(pinOf(held), r.fingerprintHex)
        assertEquals(0, s.requestCount)
    }

    @Test
    fun probeOfDeadAddressReportsError() {
        val r = Pairing.probe(deadBase(), Timeouts(connectMs = 2_000))
        assertNull(r.fingerprintHex)
        assertNotNull(r.error)
    }

    @Test
    fun rightPinGets200AndPeerCertificates() {
        val held = cert()
        val s = tlsServer(held)
        s.enqueue(MockResponse().setBody("""{"version":"v1.2.3"}"""))
        val (c, _) = client(listOf(base(s)), pinOf(held))
        assertEquals("v1.2.3", c.version())
        // The leaf is read from the trust manager, never from response.handshake
        // (OkHttp snapshots accepted issuers at client build, so its lazily
        // cleaned peerCertificates list comes back empty for a pinned leaf).
        val pinned = pinnedClient(pinOf(held), Timeouts(connectMs = 2_000))
        s.enqueue(MockResponse().setBody("ok"))
        pinned.client.newCall(Request.Builder().url(base(s) + "/x").build()).execute().use { r ->
            assertEquals(200, r.code)
            assertEquals("ok", r.body!!.string())
        }
        assertEquals(pinOf(held), Pin.sha256Hex(pinned.trustManager.lastSeen!!))
        assertEquals(1, pinned.trustManager.acceptedIssuers.size)
    }

    @Test
    fun aForeignCertOnOneAddressFailsOverToThePinnedServer() {
        // A stale home IP answered by another TLS device (router on :8443) must
        // not strand uploads: nothing was sent to it, and the next address is
        // pinned too.
        val evil = tlsServer(cert("evil"))
        val good = cert()
        val right = tlsServer(good)
        right.enqueue(MockResponse().setBody("""{"version":"x"}"""))
        val (c, store) = client(listOf(base(evil), base(right)), pinOf(good))
        assertEquals("x", c.version())
        assertEquals(0, evil.requestCount)
        assertEquals(1, right.requestCount)
        assertEquals(base(right), store.lastGood())
    }

    @Test
    fun onlyMismatchesAnywhereIsReportedAsPinMismatch() {
        val evilHeld = cert("evil")
        val a = tlsServer(evilHeld)
        val b = tlsServer(cert("evil2"))
        val (c, store) = client(listOf(base(a), base(b)), pinOf(cert()))
        try {
            c.version()
            fail("expected PinMismatchException")
        } catch (e: PinMismatchException) {
            assertEquals(base(a), e.url)
            assertEquals(pinOf(evilHeld), e.seenFingerprintHex)
        }
        assertEquals(0, a.requestCount + b.requestCount)
        assertNull(store.lastGood())
    }

    @Test
    fun wrongPinReportsTheSeenFingerprint() {
        val evil = cert("evil")
        val s = tlsServer(evil)
        val (c, _) = client(listOf(base(s)), pinOf(cert()))
        try {
            c.version()
            fail("expected PinMismatchException")
        } catch (e: PinMismatchException) {
            assertEquals(pinOf(evil), e.seenFingerprintHex)
        }
    }

    @Test
    fun defaultHostnameVerifierRejectsTheNoSanCert() {
        val held = cert()
        val s = tlsServer(held)
        s.enqueue(MockResponse().setBody("x"))
        val tm = PinnedTrustManager(pinOf(held))
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val plain = OkHttpClient.Builder().sslSocketFactory(ctx.socketFactory, tm)
            .connectTimeout(2, TimeUnit.SECONDS).build()
        try {
            plain.newCall(Request.Builder().url(base(s) + "/").build()).execute().close()
            fail("default verifier should reject a CN-only cert")
        } catch (_: SSLPeerUnverifiedException) {
        }
    }

    @Test
    fun failsOverOnConnectionRefusedAndPromotesLastGood() {
        val held = cert()
        val s = tlsServer(held)
        s.enqueue(MockResponse().setBody("""{"version":"v9"}"""))
        s.enqueue(MockResponse().setBody("""{"version":"v9"}"""))
        val dead = deadBase()
        val (c, store) = client(listOf(dead, base(s)), pinOf(held))
        assertEquals("v9", c.version())
        assertEquals(base(s), store.lastGood())
        assertEquals(listOf(base(s), dead), c.orderedBases())
        assertEquals(base(s), c.currentBase())
        assertEquals("v9", c.version())
        assertEquals(2, s.requestCount)
    }

    @Test
    fun noFailoverAfterA500() {
        val held = cert()
        val a = tlsServer(held)
        val b = tlsServer(held)
        a.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        b.enqueue(MockResponse().setBody("""{"no_card":true}"""))
        val (c, _) = client(listOf(base(a), base(b)), pinOf(held))
        try {
            c.scan(listOf(byteArrayOf(1, 2, 3)), null, null)
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(500, e.code)
            assertEquals("boom", e.body)
        }
        assertEquals(1, a.requestCount)
        assertEquals(0, b.requestCount)
    }

    @Test
    fun allAddressesDeadIsAnIOException() {
        val (c, _) = client(listOf(deadBase(), deadBase()), pinOf(cert()))
        try {
            c.version()
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertFalse(e is PinMismatchException)
            assertTrue(e.message!!.contains("No server address reachable"))
        }
    }

    @Test
    fun scanSendsMultipartFilesAndReplaceId() {
        val held = cert()
        val s = tlsServer(held)
        s.enqueue(MockResponse().setBody("""{"id":5,"identified":true}"""))
        val (c, _) = client(listOf(base(s)), pinOf(held))
        val json = c.scan(listOf("AAA".toByteArray(), "BBB".toByteArray()), 42L, "job1-p")
        assertEquals(5L, json.getLong("id"))
        val req = s.takeRequest()
        assertEquals("/api/scan", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("""name="files"; filename="frame0.jpg""""))
        assertTrue(body.contains("""name="files"; filename="frame1.jpg""""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertTrue(body.contains("""name="replace_scan_id""""))
        assertTrue(body.contains("\r\n42\r\n"))
    }

    @Test
    fun proxyPassesMethodPathBodyAndStatus() {
        val held = cert()
        val s = tlsServer(held)
        s.enqueue(MockResponse().setResponseCode(201).setHeader("X-A", "b").setBody("made"))
        val (c, _) = client(listOf(base(s)), pinOf(held))
        val headers = okhttp3.Headers.headersOf("Host", "phone:8080", "X-Req", "1")
        c.proxy("post", "/api/x?y=1", headers, "hi".toByteArray().toRequestBody())
            .use { r ->
                assertEquals(201, r.code)
                assertEquals("b", r.header("X-A"))
                assertEquals("made", r.body!!.string())
            }
        val req = s.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/x?y=1", req.path)
        assertEquals("1", req.getHeader("X-Req"))
        assertEquals("hi", req.body.readUtf8())
    }

    @Test
    fun pairReturnsBasePlusAdvertisedAddresses() {
        val held = cert()
        val s = tlsServer(held)
        s.enqueue(MockResponse().setBody("""{"version":"v1"}"""))
        s.enqueue(MockResponse().setBody(
            """{"urls":["${base(s)}","https://rig.tail123.ts.net:8443","https://100.101.1.2:8443"]}""",
        ))
        val cfg = Pairing.pair(base(s) + "/phone#pin=" + pinOf(held), pinOf(held))
        assertEquals(pinOf(held), cfg.pin)
        assertEquals(listOf(base(s), "https://rig.tail123.ts.net:8443", "https://100.101.1.2:8443"), cfg.urls)
    }

    @Test
    fun pairWithWrongPinFails() {
        val s = tlsServer(cert())
        try {
            Pairing.pair(base(s), pinOf(cert("other")))
            fail("expected PinMismatchException")
        } catch (_: PinMismatchException) {
        }
    }

    @Test
    fun pinChangeRebuildsClient() {
        val h1 = cert()
        val s = tlsServer(h1)
        s.enqueue(MockResponse().setBody("""{"version":"a"}"""))
        val (c, store) = client(listOf(base(s)), pinOf(h1))
        assertEquals("a", c.version())
        c.updateConfig(ServerConfig(listOf(base(s)), pinOf(cert("rotated"))))
        try {
            c.version()
            fail("pooled connection validated against the old pin must not be reused")
        } catch (_: PinMismatchException) {
        }
        assertEquals(base(s), store.lastGood())
    }
}
