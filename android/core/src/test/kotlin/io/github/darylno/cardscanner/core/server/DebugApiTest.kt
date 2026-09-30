package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live log (a bounded ring) and its admin-only API. */
class DebugApiTest {
    private var now = 1_700_000_000_000L
    private val log = DebugLog(capacity = 5, clock = { now++ })
    private val api = DebugApi(log) { "app 9.9.9\ndevice test" }

    private fun get(path: String, q: Map<String, String> = emptyMap()) = api.handle(ApiRequest("GET", path, q))!!

    @Test fun theRingKeepsTheNewestAndSinceReturnsWhatsNew() {
        for (i in 1..8) log.i("t", "line $i")
        assertEquals(8, log.lastSeq)
        assertEquals((4..8).map { "line $it" }, log.all().map { it.msg })         // capacity 5
        assertEquals(listOf("line 7", "line 8"), log.since(6).map { it.msg })
        assertEquals(listOf("line 8"), log.since(0, limit = 1).map { it.msg })   // the newest when capped
    }

    @Test fun theSinkSeesEveryLineAndCannotBreakLogging() {
        val seen = mutableListOf<String>()
        log.sink = { seen += it.msg; error("a broken sink") }
        log.w("t", "one"); log.e("t", "two", IllegalStateException("boom"))
        assertEquals(listOf("one", "two — IllegalStateException: boom"), seen)
    }

    @Test fun logEndpointShape() {
        log.i("identify", "Opt · 2 printing(s)")
        val body = MiniJson.parse(String(get("/api/debug/log", mapOf("since" to "0")).body)) as Map<*, *>
        assertEquals(1L, body["seq"])
        val e = (body["entries"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("seq", "t", "level", "tag", "msg"), e.keys.toList())
        assertEquals("I", e["level"]); assertEquals("identify", e["tag"])
        val none = MiniJson.parse(String(get("/api/debug/log", mapOf("since" to "1")).body)) as Map<*, *>
        assertEquals(emptyList<Any?>(), none["entries"])
    }

    @Test fun theReportIsTheHeaderThenTheLog() {
        log.i("app", "start"); log.e("capture", "failed: no frames")
        val r = get("/api/debug/report.txt")
        assertEquals("text/plain; charset=utf-8", r.contentType)
        assertTrue(r.headers["Content-Disposition"]!!.startsWith("attachment"))
        val text = String(r.body)
        assertTrue(text, text.contains("app 9.9.9\ndevice test"))
        assertTrue(text, text.contains("I/app: start") && text.contains("E/capture: failed: no frames"))
        assertTrue(text.indexOf("device test") < text.indexOf("I/app: start"))
    }

    @Test fun onlyTheAdminSeesIt() {
        for (p in listOf("/api/debug/log", "/api/debug/report.txt"))
            assertTrue(p, ScanServer.adminOnly(ApiRequest("GET", p, role = ROLE_GUEST)))
        assertEquals(null, api.handle(ApiRequest("POST", "/api/debug/log")))
        assertEquals(null, api.handle(ApiRequest("GET", "/api/debug/other")))
    }
}
