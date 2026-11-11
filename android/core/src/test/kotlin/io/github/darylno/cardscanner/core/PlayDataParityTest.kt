package io.github.darylno.cardscanner.core

import io.github.darylno.cardscanner.core.server.ApiRequest
import io.github.darylno.cardscanner.core.server.GoldenApi.jsonDiff
import io.github.darylno.cardscanner.core.server.MemoryScanStore
import io.github.darylno.cardscanner.core.server.PhoneApi
import io.github.darylno.cardscanner.core.server.ScanImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Holds [PlayData] (and PhoneApi's `play` / GET /api/play-data) to the reference
 * (mtg_card_scanner/play_data.py + server/app.py) through
 * scripts/export_play_data_fixtures.py's playdata/expected.json. Key ORDER is
 * checked where the reference fixes it (the formats, in FORMATS order) — the
 * shared [jsonDiff] compares key sets.
 */
@Suppress("UNCHECKED_CAST")
class PlayDataParityTest {
    private val fx: Map<String, Any?> by lazy {
        val text = javaClass.getResourceAsStream("/playdata/expected.json")!!.use { String(it.readBytes(), Charsets.UTF_8) }
        MiniJson.parse(text) as Map<String, Any?>
    }
    private fun cases(key: String) = fx[key] as List<Map<String, Any?>>
    private val pack: PlayData.Pack by lazy { PlayData.parse(fx["pack_text"] as String) }

    /** A parsed pack in the exporter's `_pack_json` shape (cards and names sorted, sets sorted). */
    private fun packJson(p: PlayData.Pack): Map<String, Any?> = linkedMapOf(
        "as_of" to p.asOf, "window_days" to p.windowDays, "built_at" to p.builtAt,
        "formats" to LinkedHashMap<String, Any?>().also { out ->
            for ((k, f) in p.formats) out[k] = linkedMapOf(
                "decks" to f.decks, "events" to f.events,
                "sources" to f.sources.mapValues { (_, s) -> linkedMapOf("decks" to s.decks, "events" to s.events, "weighted" to s.weighted) },
                "cards" to f.cards.toSortedMap().mapValues { (_, h) -> listOf(h.bp, h.rank) },
            )
        },
        "basic" to p.basic.sorted(), "names" to p.names.toSortedMap(), "ambiguous" to p.ambiguous.sorted(),
    )

    private fun assertJson(want: Any?, got: Any?, what: String) {
        jsonDiff(want, got, "\$")?.let { fail("$what: $it") }
    }

    /** The map's keys in order — jsonDiff compares sets; the reference fixes the formats' order. */
    private fun assertOrder(want: Any?, got: Any?, what: String) {
        if (want is Map<*, *> && got is Map<*, *>) assertEquals("$what key order", want.keys.toList(), got.keys.toList())
    }

    @Test fun constantsMatch() {
        val c = fx["constants"] as Map<String, Any?>
        assertEquals(c["kind"], PlayData.PACK_KIND)
        assertEquals(c["version"], PlayData.PACK_VERSION)
        assertEquals(c["staple_bp"], PlayData.STAPLE_BP)
        assertEquals(c["played_bp"], PlayData.PLAYED_BP)
        assertEquals(c["tiers"], PlayData.TIERS)
        assertEquals(c["file"], PlayData.PACK_FILE)
        assertEquals(c["tag"], PlayData.PACK_TAG)
        assertEquals(c["window_days"], PlayData.WINDOW_DAYS)
        assertEquals(c["formats"], PlayData.FORMATS.map { listOf(it.first, it.second) })
        assertEquals(c["tracked_formats"], Formats.TRACKED_FORMATS.map { it.first })
        // every play-data format is a tracked legality ("not played" reads the scan's own)
        val tracked = Formats.TRACKED_FORMATS.map { it.first }
        for ((k, _) in PlayData.FORMATS) assertTrue("$k is tracked", k in tracked)
    }

    @Test fun tiersMatch() {
        for (row in fx["tier_for_bp"] as List<List<Any?>>) {
            assertEquals("bp ${row[0]}", row[1], PlayData.tierForBp(row[0] as Long))
        }
    }

    @Test fun everyParseCaseMatches() {
        val out = ArrayList<String>()
        for ((i, c) in cases("parse").withIndex()) {
            val text = c["text"] as String
            val got: Any? = try { packJson(PlayData.parse(text)) } catch (e: PlayData.PlayDataException) { "error:" + e.message }
            val want: Any? = if (c.containsKey("error")) "error:" + c["error"] else c["pack"]
            jsonDiff(want, got, "\$")?.let { out += "#$i ${text.take(120)}: $it" }
            if (want is Map<*, *> && got is Map<*, *>) {
                if ((want["formats"] as Map<*, *>).keys.toList() != (got["formats"] as Map<*, *>).keys.toList()) {
                    out += "#$i formats order ${(got["formats"] as Map<*, *>).keys}"
                }
            }
        }
        if (out.isNotEmpty()) fail(out.joinToString("\n"))
        // the fixture carries every refusal the reference makes
        val errors = cases("parse").mapNotNull { it["error"] }.toSet()
        assertEquals(setOf("not JSON", "not a play data file", "unsupported version", "malformed", "no formats"), errors)
    }

    @Test fun aDoubleIsNeverACount() {
        // the fixture pins these; spelled out here so a MiniJson change that collapsed
        // 2689.0 into 2689 fails with a readable message
        assertTrue(MiniJson.parse("2689.0") is Double)
        assertTrue(MiniJson.parse("2689") is Long)
    }

    @Test fun everyPlayForCaseMatches() {
        val out = ArrayList<String>()
        for ((i, c) in cases("play_for").withIndex()) {
            val p = if (c["no_pack"] == true) null else pack
            val got = PlayData.playFor(c["popularity"], c["name"], p)
            jsonDiff(c["expected"], got, "\$")?.let { out += "#$i ${c["name"]} ${MiniJson.stringify(c["popularity"])}: $it" }
            if (c["expected"] is Map<*, *> && got != null) {
                if ((c["expected"] as Map<*, *>).keys.toList() != got.keys.toList()) out += "#$i format order ${got.keys}"
            }
        }
        if (out.isNotEmpty()) fail(out.joinToString("\n"))
        assertTrue("the no-pack case is in the table", cases("play_for").any { it["no_pack"] == true && it["expected"] == null })
    }

    @Test fun answersDoNotChangeOnceMemoised() {
        // the name → oracle_id memo: the second read of every case answers exactly as the first
        val fresh = PlayData.parse(fx["pack_text"] as String)
        for (c in cases("play_for")) {
            if (c["no_pack"] == true) continue
            val a = PlayData.playFor(c["popularity"], c["name"], fresh)
            val b = PlayData.playFor(c["popularity"], c["name"], fresh)
            assertJson(a, b, "memo ${c["name"]}")
            assertJson(c["expected"], b, "memo ${c["name"]}")
        }
    }

    @Test fun everyScanPlayMatches() {
        for (c in cases("scan_play")) {
            val scan = c["scan"] as Map<String, Any?>
            val got = PlayData.scanPlay(scan, pack)
            assertJson(c["expected"], got, "scan ${scan["id"]}")
            assertOrder(c["expected"], got, "scan ${scan["id"]}")
        }
    }

    @Test fun flagMatchesWithAndWithoutThePack() {
        for (c in cases("flag")) {
            val scan = c["scan"] as Map<String, Any?>
            val withPack = c["pack"] == true
            val got = PlayData.flag(scan, if (withPack) pack else null)
            assertJson(c["expected"], got, "flag scan ${scan["id"]} pack=$withPack")
            if (!withPack) {
                assertSame("no pack: the same scan, untouched", scan, got)
                assertFalse("no pack: no play key", got.containsKey("play"))
            } else {
                assertTrue("a pack: the play key is always there", got.containsKey("play"))
                assertFalse("copy-on-write: the stored scan is untouched", scan.containsKey("play"))
            }
        }
    }

    @Test fun statusMatches() {
        val st = fx["status"] as Map<String, Any?>
        assertJson(st["none"], PlayData.status(null), "status none")
        assertJson(st["pack"], PlayData.status(pack), "status pack")
        val pack2 = PlayData.parse((cases("parse")[1])["text"] as String)
        assertJson(st["pack2"], PlayData.status(pack2), "status pack2")
        assertOrder((st["pack"] as Map<*, *>)["formats"], PlayData.status(pack)["formats"], "status pack formats")
        assertOrder((st["pack2"] as Map<*, *>)["formats"], PlayData.status(pack2)["formats"], "status pack2 formats")
    }

    @Test fun theApiSessionMatchesTheServer() {
        val session = fx["api"] as Map<String, Any?>
        var tick = 0
        val store = MemoryScanStore { tick++; "T" + tick.toString().padStart(4, '0') }
        var current: PlayData.Pack? = null
        val api = PhoneApi(store, object : ScanImages {
            override fun read(id: Long): ByteArray? = null
            override fun write(id: Long, jpeg: ByteArray) {}
            override fun delete(id: Long) {}
        }, cardList = { null }, playData = { current })
        for (s in session["seeds"] as List<Map<String, Any?>>) {
            store.create(s["identified"] as Boolean, s["card_read"] as Map<String, Any?>?,
                s["confidence"] as Map<String, Any?>?, s["candidates"] as List<Any?>?, s["error"] as String?)
        }
        val out = ArrayList<String>()
        var requests = 0
        for ((i, step) in (session["steps"] as List<Map<String, Any?>>).withIndex()) {
            if (step["op"] == "data") {
                current = (step["text"] as String?)?.let { PlayData.parse(it) }
                continue
            }
            requests++
            val body = step["body"] as Map<String, Any?>?
            val tag = "#$i ${step["method"]} ${step["path"]}"
            val res = api.handle(ApiRequest(step["method"] as String, step["path"] as String,
                body = body?.let { MiniJson.stringify(it).toByteArray() }))
            if (res == null) { out += "$tag: not handled"; continue }
            if (res.status != (step["status"] as Number).toInt()) out += "$tag: status ${res.status}"
            val got = MiniJson.parse(String(res.body, Charsets.UTF_8))
            jsonDiff(step["response"], got, "\$")?.let { out += "$tag: $it" }
        }
        if (out.isNotEmpty()) fail(out.joinToString("\n"))
        assertTrue("the session made requests", requests >= 10)
    }

    @Test fun onePackReadPerListRequest() {
        var tick = 0
        val store = MemoryScanStore { tick++; "T$tick" }
        var reads = 0
        val api = PhoneApi(store, object : ScanImages {
            override fun read(id: Long): ByteArray? = null
            override fun write(id: Long, jpeg: ByteArray) {}
            override fun delete(id: Long) {}
        }, playData = { reads++; pack })
        repeat(5) { store.create(true, mapOf("name" to "Thoughtseize"), emptyMap(), emptyList(), null) }
        val res = api.handle(ApiRequest("GET", "/api/scans"))!!
        assertEquals(200, res.status)
        assertEquals(1, reads)
        val rows = MiniJson.parse(String(res.body)) as List<Map<String, Any?>>
        assertEquals(5, rows.size)
        for (r in rows) assertNull("an old scan by name: no legality, so in no deck reads null", (r["play"] as Map<*, *>)["vintage"])
    }
}
