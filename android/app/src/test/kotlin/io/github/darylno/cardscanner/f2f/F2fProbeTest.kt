package io.github.darylno.cardscanner.f2f

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The probe over plain OkHttp against a MockWebServer standing in for the
 * storefront (never the real one). Fixture bodies are the rig's own captured
 * storefront answers, tests/fixtures/facetoface/.
 */
class F2fProbeTest {
    private lateinit var server: MockWebServer
    private val paths = CopyOnWriteArrayList<RecordedRequest>()

    private val m10Bolt = ProbeCard("Lightning Bolt", "m10", "Magic 2010", "146")

    @Before fun setUp() { server = MockWebServer() }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun fixture(name: String): String {
        // Unit tests run with the module dir (android/app) as working directory.
        val f = listOf(File("../../tests/fixtures/facetoface/$name"), File("../tests/fixtures/facetoface/$name"))
            .first { it.exists() }
        return f.readText()
    }

    private fun dispatch(fn: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request
                return fn(request)
            }
        }
        server.start()
    }

    private fun storefront(skuSet: String = "M10"): (RecordedRequest) -> MockResponse = { r ->
        val p = r.path ?: ""
        when {
            p.startsWith("/search/suggest.json") -> MockResponse().setBody(fixture("suggest_lightning_bolt.json"))
            p == "/products/lightning-bolt-146-magic-2010-non-foil.json" ->
                MockResponse().setBody(fixture("product_lightning-bolt-146-magic-2010-non-foil.json").replace("M-M10-", "M-$skuSet-"))
            p == "/products/lightning-bolt-149-magic-2011-non-foil.json" ->
                MockResponse().setBody(fixture("product_lightning-bolt-149-magic-2011-non-foil.json"))
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** No real sleeping: waits are recorded and return immediately. */
    private class RecordingWaiter : Waiter {
        val waits = CopyOnWriteArrayList<Double>()
        override fun await(seconds: Double): Boolean { waits += seconds; return false }
    }

    @Test
    fun exactPrinting_costsOneSuggestPlusOneProduct_withTheRigsHeaders() {
        dispatch(storefront())
        val probe = F2fProbe(OkHttpTransport(), listOf(m10Bolt), base = base(), waiter = RecordingWaiter())
        val stats = probe.run()
        assertEquals(1, stats.priced)
        assertEquals(2, stats.requests)
        assertEquals(2, stats.ok)
        assertTrue(stats.cardLines.single(), stats.cardLines.single().contains("NM 3.49"))
        val first = paths[0]
        assertTrue(first.path!!.startsWith("/search/suggest.json?q=Lightning%20Bolt%20146%20Magic%202010&"))
        assertEquals("/products/lightning-bolt-146-magic-2010-non-foil.json", paths[1].path)
        for (r in paths) {
            assertEquals(F2f.USER_AGENT, r.getHeader("User-Agent"))
            assertEquals("application/json", r.getHeader("Accept"))
            assertEquals("en-CA,en;q=0.9", r.getHeader("Accept-Language"))
        }
        assertTrue(stats.verdict(), stats.verdict().startsWith("PASS"))
    }

    @Test
    fun skuFromAnotherSet_isNeverTrusted_andTheWholeLadderIsWalked() {
        dispatch(storefront(skuSet = "A25"))
        val stats = F2fProbe(OkHttpTransport(), listOf(m10Bolt), base = base(), waiter = RecordingWaiter()).run()
        assertEquals(0, stats.priced)
        assertEquals(1, stats.notListed)
        val suggests = paths.filter { it.path!!.startsWith("/search/suggest.json") }.map { it.requestUrl!!.queryParameter("q") }
        assertEquals(listOf("Lightning Bolt 146 Magic 2010", "Lightning Bolt Magic 2010", "Lightning Bolt"), suggests)
    }

    @Test
    fun a429_pacesDown_waitsAtLeast2s_thenRecovers() {
        var first = true
        val inner = storefront()
        dispatch { r ->
            if (first) { first = false; MockResponse().setResponseCode(429).addHeader("Retry-After", "1") } else inner(r)
        }
        val w = RecordingWaiter()
        val probe = F2fProbe(OkHttpTransport(), listOf(m10Bolt), base = base(), waiter = w)
        val stats = probe.run()
        assertEquals(1, stats.tooMany)
        assertEquals(1, stats.first429At)
        assertEquals(1, stats.priced)
        assertTrue("429 wait = max(Retry-After 1, 2×attempt 1) = 2: $w", w.waits.contains(2.0))
        assertEquals(4.0, stats.paceMax, 1e-9)            // 2 s start × 2
        assertTrue(stats.verdict(), stats.verdict().startsWith("MARGINAL"))
    }

    @Test
    fun breaker_endsTheRun_after5UnavailableCards() {
        dispatch { MockResponse().setResponseCode(503) }
        val cards = (1..8).map { ProbeCard("Card $it", "set", "Set", "$it") }
        val stats = F2fProbe(OkHttpTransport(), cards, base = base(), waiter = RecordingWaiter()).run()
        assertTrue(stats.breakerTripped)
        assertEquals(5, stats.unavailable)
        assertEquals(10, stats.requests)                  // 2 attempts per dead suggest
        assertEquals(10, stats.server5xx)
        assertTrue(stats.verdict(), stats.verdict().startsWith("FAIL"))
    }

    @Test
    fun stop_interruptsARetryAfterWait() {
        dispatch { MockResponse().setResponseCode(429).addHeader("Retry-After", "15") }
        val stop = StopSignal()
        val probe = F2fProbe(OkHttpTransport(), ProbeCards.ALL, stop, base = base())
        val t = Thread { probe.run() }.apply { start() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        Thread.sleep(200)                                  // now inside the 15 s wait
        val t0 = System.nanoTime()
        stop.set()
        t.join(3000)
        assertFalse("probe still running after Stop", t.isAlive)
        assertTrue("Stop took too long", (System.nanoTime() - t0) / 1e9 < 2.0)
        assertTrue(probe.stats.stopped)
        assertTrue(probe.stats.finished)
        assertEquals(1, probe.stats.requests)
        assertEquals(0, probe.stats.cardsDone)              // a stopped card is not counted as unavailable
    }

    @Test
    fun stop_cancelsAnInFlightRequest() {
        dispatch { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val stop = StopSignal()
        val probe = F2fProbe(OkHttpTransport(), ProbeCards.ALL, stop, base = base())
        val t = Thread { probe.run() }.apply { start() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        Thread.sleep(200)                                  // request is hanging (8 s timeout)
        stop.set()
        t.join(3000)
        assertFalse("probe still running after Stop", t.isAlive)
        assertEquals("cancelled", probe.stats.recentRequests().last().outcome)
        assertEquals(0, probe.stats.timeouts)
    }

    @Test
    fun stopSignal_waitsFullyWhenNotSet_andWakesWhenSet() {
        val s = StopSignal()
        val t0 = System.nanoTime()
        assertFalse(s.await(0.15))
        assertTrue((System.nanoTime() - t0) / 1e6 >= 140)
        Thread { Thread.sleep(100); s.set() }.start()
        val t1 = System.nanoTime()
        assertTrue(s.await(10.0))
        assertTrue((System.nanoTime() - t1) / 1e9 < 2.0)
    }

    @Test
    fun report_isCompactJson() {
        dispatch(storefront())
        val stats = F2fProbe(OkHttpTransport(), listOf(m10Bolt), base = base(), waiter = RecordingWaiter()).run()
        val j = stats.toJson()
        assertEquals("okhttp", j.getString("transport"))
        assertEquals(2, j.getInt("requests"))
        assertEquals(0, j.getJSONObject("status").getInt("429"))
        assertTrue(j.getString("seq"), Regex("""s200/\d+ p200/\d+""").matches(j.getString("seq")))
        assertEquals(1, j.getJSONArray("card_results").length())
    }

    @Test
    fun quote_matchesUrllib() {
        assertEquals("Ragavan%2C%20Nimble%20Pilferer", F2fPricer.quote("Ragavan, Nimble Pilferer"))
        assertEquals("Lim-D%C3%BBl%27s%20Vault", F2fPricer.quote("Lim-Dûl's Vault"))
        assertEquals("a/b_c.d~e", F2fPricer.quote("a/b_c.d~e"))
    }

    @Test
    fun ladder_mostSpecificFirst_deduplicated() {
        assertEquals(listOf("Sol Ring 472 Commander Legends foil", "Sol Ring Commander Legends foil", "Sol Ring foil", "Sol Ring"),
            F2fPricer.queries(ProbeCard("Sol Ring", "cmr", "Commander Legends", "472", foil = true)))
        assertEquals(listOf("Forest"), F2fPricer.queries(ProbeCard("Forest", "", "", "")))
    }

    @Test
    fun cardList_isAbout30RealPrintings() {
        assertTrue(ProbeCards.ALL.size in 28..36)
        ProbeCards.ALL.forEach {
            assertTrue(it.label, it.name.isNotBlank() && it.setCode.isNotBlank() && it.setName.isNotBlank() && it.collector.isNotBlank())
        }
    }
}
