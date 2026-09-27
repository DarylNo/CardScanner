package io.github.darylno.cardscanner.f2f

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * The Kotlin fetcher must pace EXACTLY like facetoface._default_get_json.
 * f2f/pacing.json is a trace the REAL Python client produced on a fake clock
 * (scripts/export_f2f_pacing_fixture.py; CI --check keeps it current): for
 * each scripted sequence of storefront answers (200 / 429 [+Retry-After] /
 * 5xx / 4xx / timeout / idle gaps) every wait, request time, pace and
 * result must match. Regenerate the fixture, never hand-edit it.
 */
class F2fPacingParityTest {
    private class FakeClock(var t: Double) : ProbeClock {
        override fun nowS() = t
    }

    private val fixture: JSONObject by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("f2f/pacing.json")!!.bufferedReader().readText()
        JSONObject(text)
    }

    /** Replay one scenario through the Kotlin port; returns its trace in the fixture's shape. */
    private fun replay(script: JSONArray, latency: Double, start: Double): List<Map<String, Any>> {
        val clock = FakeClock(start)
        val trace = mutableListOf<Map<String, Any>>()
        val queue = ArrayDeque((0 until script.length()).map { script.getJSONObject(it) })
        lateinit var fetcher: F2fFetcher
        val transport = object : F2fTransport {
            override val name = "fake"
            override val detail = "fake"
            override fun get(url: String, headers: Map<String, String>, timeoutMs: Long): TransportResponse {
                val spec = queue.removeFirst()
                val status = spec.get("status")
                trace += mapOf("req" to status, "t" to clock.t, "delay" to fetcher.pacer.delay)
                clock.t += latency
                if (status == "timeout") throw SocketTimeoutException("timed out")
                val ra = if (spec.isNull("retry_after")) null else spec.optString("retry_after", null)
                return TransportResponse(status as Int, """{"ok":true}""", "h2", ra)
            }
            override fun cancel() {}
        }
        val waiter = Waiter { s -> trace += mapOf("wait" to s); clock.t += s; false }
        fetcher = F2fFetcher(transport, clock, waiter, { false })
        var n = 0
        while (queue.isNotEmpty()) {
            val head = queue.first()
            if (head.has("gap")) {
                clock.t += head.getDouble("gap"); queue.removeFirst(); continue
            }
            n++
            val data = fetcher.getJson("https://example.invalid/call/$n.json", "suggest")
            trace += mapOf("result" to (if (data != null) "ok" else "fail"), "delay" to fetcher.pacer.delay)
        }
        return trace
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> Math.abs(a.toDouble() - b.toDouble()) < 1e-7
        else -> a == b
    }

    @Test
    fun everyScenario_matchesThePythonTrace() {
        val scenarios = fixture.getJSONObject("scenarios")
        val latency = fixture.getDouble("latency_s")
        val start = fixture.getDouble("start_clock_s")
        assertTrue("fixture has scenarios", scenarios.length() >= 5)
        for (name in scenarios.keys()) {
            val sc = scenarios.getJSONObject(name)
            val want = sc.getJSONArray("trace")
            val got = replay(sc.getJSONArray("script"), latency, start)
            for (i in 0 until minOf(want.length(), got.size)) {
                val w = want.getJSONObject(i)
                val g = got[i]
                assertEquals("$name[$i] keys (want $w, got $g)", w.keys().asSequence().toSet(), g.keys)
                for (k in w.keys()) {
                    assertTrue("$name[$i].$k: want ${w.get(k)} got ${g[k]}", same(w.get(k), g[k]))
                }
            }
            assertEquals("$name: trace length", want.length(), got.size)
        }
    }

    @Test
    fun headersAndBase_matchTheRig() {
        assertEquals(fixture.getString("base"), F2f.BASE)
        val h = fixture.getJSONObject("session_headers")
        assertEquals(h.keys().asSequence().toSet(), F2f.HEADERS.keys)
        for (k in h.keys()) assertEquals(k, h.getString(k), F2f.HEADERS[k])
    }

    /** Every suggest answer empty → the requested URLs ARE the ladder, in order, encoded like urllib.quote. */
    @Test
    fun queryLadder_urlsMatchTheRig() {
        val ladders = fixture.getJSONArray("ladders")
        for (i in 0 until ladders.length()) {
            val l = ladders.getJSONObject(i)
            val c = l.getJSONObject("card")
            val card = ProbeCard(c.getString("name"), c.getString("set_code"), c.getString("set_name"),
                c.getString("collector_number"), c.getBoolean("foil"))
            val urls = mutableListOf<String>()
            val transport = object : F2fTransport {
                override val name = "fake"
                override val detail = "fake"
                override fun get(url: String, headers: Map<String, String>, timeoutMs: Long): TransportResponse {
                    urls += url
                    return TransportResponse(200, """{"resources":{"results":{"products":[]}}}""", null, null)
                }
                override fun cancel() {}
            }
            val clock = FakeClock(0.0)
            val fetcher = F2fFetcher(transport, clock, { s -> clock.t += s; false }, { false })
            assertEquals(PriceOutcome.NotListed, F2fPricer(fetcher, F2f.BASE).price(card))
            val want = l.getJSONArray("urls").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(card.label, want, urls)
        }
    }
}
