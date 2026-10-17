package io.github.darylno.cardscanner.core

import io.github.darylno.cardscanner.core.server.ApiRequest
import io.github.darylno.cardscanner.core.server.GoldenApi.jsonDiff
import io.github.darylno.cardscanner.core.server.MemoryScanStore
import io.github.darylno.cardscanner.core.server.PhoneApi
import io.github.darylno.cardscanner.core.server.ScanImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Holds [CardList] / [CardListSource] / PhoneApi's list_tags to the reference
 * (mtg_card_scanner/card_list.py + server/app.py) through
 * scripts/export_card_list_fixtures.py's cardlist/expected.json.
 */
@Suppress("UNCHECKED_CAST")
class CardListParityTest {
    private val fx: Map<String, Any?> by lazy {
        val text = javaClass.getResourceAsStream("/cardlist/expected.json")!!.use { String(it.readBytes(), Charsets.UTF_8) }
        MiniJson.parse(text) as Map<String, Any?>
    }
    private fun cases(key: String) = fx[key] as List<Map<String, Any?>>

    @Test fun knownTagsMatch() = assertEquals(fx["known_tags"], CardList.KNOWN_TAGS)

    @Test fun normalizeMatches() {
        for (c in cases("normalize")) assertEquals("normalize ${c["in"]}", c["out"], CardList.normalizeName(c["in"] as String))
    }

    @Test fun nameKeysMatch() {
        for (c in cases("keys")) assertEquals("keys ${c["in"]}", c["out"], CardList.nameKeys(c["in"] as String))
    }

    @Test fun parseMatches() {
        for (c in cases("parse")) {
            val text = c["text"] as String
            val got: Any? = try { CardList.parse(text).toJson() } catch (e: CardList.ListException) { "error:" + e.message }
            val want: Any? = if (c.containsKey("error")) "error:" + c["error"] else c["result"]
            jsonDiff(want, got, "\$")?.let { fail("parse ${MiniJson.stringify(text)}: $it") }
        }
    }

    @Test fun tagsMatch() {
        val index = CardList.parse((cases("parse")[0])["text"] as String).index
        for (c in cases("tags")) assertEquals("tags ${c["name"]}", c["tags"], CardList.tagsFor(c["name"] as String?, index))
    }

    @Test fun scanNameMatches() {
        for (c in cases("scan_name")) assertEquals("${c["scan"]}", c["name"], CardList.scanName(c["scan"] as Map<String, Any?>))
    }

    @Test fun theSourceSessionMatches() {
        val session = fx["source"] as Map<String, Any?>
        val dir = createTempDir("cardlist")
        try {
            val cache = File(dir, "card_list_cache.json")
            var tick = 0
            val clock = { tick++; "C" + tick.toString().padStart(4, '0') }
            val pending = ArrayList<Any?>()
            var fetched = ArrayList<String>()
            val fetch = { url: String ->
                fetched += url
                val a = pending.removeAt(0)
                if (a == "down") throw IOException("down")
                val pair = a as List<Any?>
                (pair[0] as Number).toInt() to (pair[1] as String)
            }
            var src: CardListSource? = null
            for ((i, st) in (session["steps"] as List<Map<String, Any?>>).withIndex()) {
                fetched = ArrayList()
                var returned: Boolean? = null
                when (st["op"]) {
                    "new", "restart" -> src = CardListSource(st["url"] as String, cache, fetch, clock)
                    "set_url" -> src!!.setUrl(st["url"] as String)
                    else -> {
                        if (st.containsKey("answer")) pending += st["answer"]
                        returned = src!!.refresh()
                    }
                }
                val tag = "#$i ${st["op"]}"
                assertEquals("$tag returned", st["returned"], returned)
                assertEquals("$tag fetched", st["fetched"], fetched)
                assertEquals("$tag current", (st["current_count"] as Number?)?.toLong(), src!!.current()?.count)
                jsonDiff(st["status"], src.status(), "\$")?.let { fail("$tag status: $it") }
            }
            assertFalse("the status never carries the URL", MiniJson.stringify(src!!.status()).contains("s3cret"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theApiSessionMatchesTheServer() {
        val session = fx["api"] as Map<String, Any?>
        val dir = createTempDir("cardlist-api")
        try {
            var tick = 0
            val store = MemoryScanStore { tick++; "T" + tick.toString().padStart(4, '0') }
            var ltick = 0
            val pending = ArrayList<Any?>()
            val src = CardListSource("", File(dir, "cl.json"), { _ ->
                val a = pending.removeAt(0)
                if (a == "down") throw IOException("down")
                val pair = a as List<Any?>
                (pair[0] as Number).toInt() to (pair[1] as String)
            }, { ltick++; "C" + ltick.toString().padStart(4, '0') })
            val api = PhoneApi(store, object : ScanImages {
                override fun read(id: Long): ByteArray? = null
                override fun write(id: Long, jpeg: ByteArray) {}
                override fun delete(id: Long) {}
            }) { src.current() }
            for (s in session["seeds"] as List<Map<String, Any?>>) {
                store.create(s["identified"] as Boolean, s["card_read"] as Map<String, Any?>?,
                    s["confidence"] as Map<String, Any?>?, s["candidates"] as List<Any?>?, s["error"] as String?)
            }
            val out = ArrayList<String>()
            for ((i, step) in (session["steps"] as List<Map<String, Any?>>).withIndex()) {
                val body = step["body"] as Map<String, Any?>?
                if (step["method"] == "LIST") {
                    src.setUrl(body!!["url"] as String)
                    if (body["answer"] != null) { pending += body["answer"]; src.refresh() }
                    jsonDiff(step["status"], src.status(), "\$")?.let { out += "#$i LIST: $it" }
                    continue
                }
                val tag = "#$i ${step["method"]} ${step["path"]}"
                val res = api.handle(ApiRequest(step["method"] as String, step["path"] as String,
                    body = body?.let { MiniJson.stringify(it).toByteArray() }))
                if (res == null) { out += "$tag: not handled"; continue }
                if (res.status != (step["status"] as Number).toInt()) out += "$tag: status ${res.status}"
                jsonDiff(step["response"], MiniJson.parse(String(res.body, Charsets.UTF_8)), "\$")?.let { out += "$tag: $it" }
            }
            if (out.isNotEmpty()) fail(out.joinToString("\n"))
        } finally { dir.deleteRecursively() }
    }
}

class CardListSummaryTest {
    @Test fun summaries() {
        fun st(enabled: Boolean, count: Long = 0, updated: String? = null, error: String? = null) =
            mapOf("enabled" to enabled, "count" to count, "updated_at" to updated, "error" to error, "error_at" to "10:05")
        assertEquals("Off — no URL set", CardList.summary(st(false)))
        assertEquals("No list yet", CardList.summary(st(true)))
        assertEquals("No list yet · last error: HTTP 404 (10:05)", CardList.summary(st(true, error = "HTTP 404")))
        assertEquals("1,234 cards, updated 2026-10-12 09:00", CardList.summary(st(true, 1234, "2026-10-12 09:00")))
        assertEquals("1 card, updated 09:00 · last error: unreachable (10:05)",
            CardList.summary(st(true, 1, "09:00", "unreachable")))
    }
}
