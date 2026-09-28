package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * Name -> candidate printings must be THE SERVER'S answer. Replays the real
 * Scryfall `/cards/search` pages recorded by scripts/export_printings_fixtures.py
 * (every page — Forest is 6 pages of 175) through [HttpJson] and holds the port
 * to what the server's own code produced from the same pages
 * (expected.json.gz; CI re-runs the script with `--check`).
 *
 * JSON-level equality, strict: same keys in the same order, int vs float
 * distinguished, floats compared exactly.
 */
class PrintingsParityTest {
    companion object {
        lateinit var expected: Map<String, Any?>
        lateinit var responses: Map<String, Any?>

        private fun gz(name: String): Map<String, Any?> {
            val url = requireNotNull(PrintingsParityTest::class.java.getResource("/printings/$name")) { "missing fixture printings/$name" }
            val text = GZIPInputStream(url.openStream()).use { it.readBytes().toString(Charsets.UTF_8) }
            return m(MiniJson.parse(text))
        }

        @BeforeClass @JvmStatic
        fun load() {
            expected = gz("expected.json.gz")
            responses = m(gz("recorded.json.gz")["responses"]) + m(expected["synthetic_responses"])
        }

        @Suppress("UNCHECKED_CAST")
        fun m(v: Any?): Map<String, Any?> = v as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun l(v: Any?): List<Any?> = v as List<Any?>

        /** Deep copy into fresh mutable containers (the server parses each response afresh). */
        fun copy(v: Any?): Any? = when (v) {
            is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> v.forEach { (k, x) -> out[k as String] = copy(x) } }
            is List<*> -> ArrayList<Any?>().also { out -> v.forEach { out.add(copy(it)) } }
            else -> v
        }

        fun assertJson(exp: Any?, act: Any?, path: String = "$") {
            when (exp) {
                is Map<*, *> -> {
                    if (act !is Map<*, *>) fail("$path: expected object, got $act")
                    act as Map<*, *>
                    assertEquals("$path: keys (order matters)", exp.keys.toList(), act.keys.toList())
                    for (k in exp.keys) assertJson(exp[k], act[k], "$path.$k")
                }
                is List<*> -> {
                    if (act !is List<*>) fail("$path: expected array, got $act")
                    act as List<*>
                    assertEquals("$path: length", exp.size, act.size)
                    for (i in exp.indices) assertJson(exp[i], act[i], "$path[$i]")
                }
                is Long -> {
                    val a = if (act is Int) act.toLong() else act
                    if (a !is Long || a != exp) fail("$path: expected int $exp, got ${act?.let { it::class.simpleName }} $act")
                }
                is Double -> if (act !is Double || act.toRawBits() != exp.toRawBits()) fail("$path: expected float $exp, got $act")
                else -> assertEquals(path, exp, act)
            }
        }
    }

    /** Serves the recorded responses by exact URL; logs what was asked. */
    private class Replay(val log: MutableList<String> = ArrayList()) : HttpJson {
        override fun get(url: String): String {
            log.add(url)
            val rec = m(responses[url] ?: throw AssertionError("no recorded response for $url"))
            val status = (rec["status"] as Long).toInt()
            val body = rec["body"].let { if (it is String) it else MiniJson.stringify(it) }
            if (status !in 200..299) throw HttpStatusException(status, body, rec["content_type"] as String, url)
            return body
        }
    }

    private fun client(log: MutableList<String> = ArrayList()) =
        ScryfallPrintings(Replay(log), nanoClock = { 0L }, sleeper = {})

    @Test
    fun constantsMatchServer() {
        val c = m(expected["constants"])
        assertEquals(c["ranked_card_count"], Popularity.RANKED_CARD_COUNT.toLong())
        assertEquals(c["max_search_pages"], ScryfallPrintings.MAX_SEARCH_PAGES.toLong())
        assertEquals((c["min_delay"] as Double) * 1e9, ScryfallPrintings.MIN_DELAY_NANOS.toDouble(), 0.5)
        assertEquals(c["art_break_gap"], ArtworkSplit.ART_BREAK_GAP)
        assertEquals(c["art_break_ceiling"], ArtworkSplit.ART_BREAK_CEILING)
    }

    @Test
    fun everyNameMatchesServer() {
        val names = m(expected["names"])
        assertTrue("fixture covers the recorded names", names.size >= 13)
        for ((name, e) in names) {
            val exp = m(e)
            val log = ArrayList<String>()
            val printings = try {
                client(log).getAllPrintings(name)
            } catch (ex: ScryfallException) {
                val err = m(exp["error"] ?: throw AssertionError("$name: unexpected $ex"))
                assertEquals(name, "ScryfallError", err["type"])
                assertEquals(name, err["message"], ex.message)
                assertEquals("$name urls", exp["urls"], log)
                continue
            } catch (ex: HttpStatusException) {
                val err = m(exp["error"] ?: throw AssertionError("$name: unexpected $ex"))
                assertEquals(name, "HTTPError", err["type"])
                assertEquals("$name urls", exp["urls"], log)
                continue
            }
            assertEquals("$name: server raised ${exp["error"]}", null, exp["error"])
            // Same requests in the same order: the exact first query and every next_page.
            assertEquals("$name urls", exp["urls"], log)
            assertEquals("$name printing ids", exp["printing_ids"], printings.map { it["id"] })

            val all = PrintingCandidates.searchCandidates(client().getAllPrintings(name), Int.MAX_VALUE)
            assertJson(exp["search_candidates_all"], all, "$name.search_candidates_all")
            val top = PrintingCandidates.searchCandidates(client().getAllPrintings(name))
            assertJson(l(exp["search_candidates_all"]).take(40), top, "$name.search_candidates")

            val capped = PrintingCandidates.capForRanking(printings)
            assertEquals("$name capped ids", exp["capped_ids"], capped.map { it["id"] })

            val r = exp["ranking"]?.let { m(it) } ?: continue
            val dist = m(r["distances"])
            val byId = capped.associate { p ->
                val d = l(dist[p["id"] as String])
                p["id"] to (LinkedHashMap(p).also { it["phash_distance"] = d[0]; it["multi_distance"] = d[1] } as Map<String, Any?>)
            }
            val ranked = l(r["ranked_ids"]).map { byId.getValue(it) }
            val cands = PrintingCandidates.rankedCandidates(printings, ranked)
            assertJson(r["candidates"], cands, "$name.ranking.candidates")
            @Suppress("UNCHECKED_CAST")
            val flagged = ArtworkSplit.flagOtherArt(copy(cands) as List<MutableMap<String, Any?>>)
            assertJson(r["flagged"], flagged, "$name.ranking.flagged")
        }
    }

    @Test
    fun summarizeMatchesServer() {
        for ((i, case) in l(expected["summarize"]).withIndex()) {
            val c = m(case)
            val out = Popularity.summarize(m(c["printing"]), c["print_count"] as Long?)
            assertJson(c["out"], out, "summarize[$i] ${c["printing"]}")
        }
    }

    @Test
    fun oracleKeysAndCountsMatchServer() {
        val printings = l(expected["summarize"]).map { m(m(it)["printing"]) }.filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals(expected["oracle_keys"], printings.map { Popularity.oracleKey(it) })
        assertJson(expected["print_counts"], Popularity.printCountsByOracle(printings))
    }

    @Test
    fun topPercentAndTiersMatchServerForEveryRank() {
        val pcts = l(expected["top_percent"])
        val changes = ArrayList<List<Any?>>()
        var last: String? = null
        for (r in 1..pcts.size) {
            assertJson(pcts[r - 1], Popularity.topPercent(r.toLong()), "top_percent($r)")
            val t = Popularity.tierFor(r.toLong()).first
            if (t != last) { changes.add(listOf(r.toLong(), t)); last = t }
        }
        assertJson(expected["tier_changes"], changes)
        assertEquals("unranked" to "Unranked", Popularity.tierFor(null))
    }

    @Test
    fun otherArtMatchesServer() {
        for ((i, case) in l(expected["other_art"]).withIndex()) {
            val c = m(case)
            @Suppress("UNCHECKED_CAST")
            val flagged = ArtworkSplit.flagOtherArt(copy(c["candidates"]) as List<MutableMap<String, Any?>>)
            assertJson(c["flagged"], flagged, "other_art[$i]")
        }
        val scan = linkedMapOf<String, Any?>("id" to 1L)
        assertEquals(scan, ArtworkSplit.flagOtherArt(scan))
    }

    @Test
    fun candidateDictEdgeCasesMatchServer() {
        val cases = listOf<Pair<Map<String, Any?>, Map<String, Any?>>>(
            emptyMap<String, Any?>() to emptyMap(),
            mapOf("id" to null, "promo" to "x", "finishes" to null, "image_uris" to null) to emptyMap(),
            mapOf("image_uris" to mapOf("small" to "s", "normal" to "", "large" to "L")) to
                mapOf("phash_distance" to 3L, "multi_distance" to 20L),
            mapOf("image_uris" to mapOf("large" to "L")) to mapOf("multi_distance" to 1.5),
        )
        val out = cases.flatMap { (p, extra) -> listOf(null, 2L).map { PrintingCandidates.candidateDict(p + extra, it) } }
        assertJson(expected["candidate_dict"], out)
    }

    @Test
    fun throttleSpacesRequestsFromTheEndOfTheLastResponse() {
        var now = 0L
        val sleeps = ArrayList<Long>()
        val http = HttpJson { now += 30_000_000L; """{"data":[{"id":"x"}],"has_more":false}""" }  // each request takes 30 ms
        val c = ScryfallPrintings(http, nanoClock = { now }, sleeper = { ms -> sleeps.add(ms); now += ms * 1_000_000L })
        c.getJson("https://api.scryfall.com/cards/search")      // first request: no wait
        now += 50_000_000L                                       // 50 ms of other work
        c.getJson("https://api.scryfall.com/cards/search")      // 110 - 50 = 60 ms
        now += 200_000_000L
        c.getJson("https://api.scryfall.com/cards/search")      // past the gap: no wait
        assertEquals(listOf(60L), sleeps)
    }

    @Test
    fun queryStringIsRequestsUrlencode() {
        // Accents are UTF-8 percent-encoded, as requests/quote_plus sends them.
        assertEquals(
            "https://api.scryfall.com/cards/search?q=%21%22Lim-D%C3%BBl%27s+Vault%22&unique=prints&order=released&dir=asc&include_extras=true",
            ScryfallPrintings.urlWithParams("https://api.scryfall.com/cards/search", ScryfallPrintings.printingsParams("Lim-Dûl's Vault")),
        )
    }

    @Test
    fun miniJsonKeepsPythonNumberTypes() {
        val v = m(MiniJson.parse("""{"i": 3, "f": 3.0, "e": 1e2, "n": -0, "s": "aé\"", "b": [true, null]}"""))
        assertEquals(3L, v["i"]); assertEquals(3.0, v["f"]); assertEquals(100.0, v["e"]); assertEquals(0L, v["n"])
        assertEquals("aé\"", v["s"]); assertEquals(listOf(true, null), v["b"])
        assertEquals(v, MiniJson.parse(MiniJson.stringify(v)))
    }
}
