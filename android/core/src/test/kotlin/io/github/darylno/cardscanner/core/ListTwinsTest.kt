package io.github.darylno.cardscanner.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ListTwins] — the List's twins — held to the server (mtg_card_scanner/
 * list_twins.py) by `resources/ocr/expected.json`'s `list_twins` section
 * (scripts/export_ocr_fixtures.py: is_list_twin over EVERY ordered pair of the
 * recorded real printings, hand shapes, and with_twins over rankings), plus the
 * real Scryfall shapes tests/test_list_twins.py checks, and the decisions that
 * use them: [OcrMatch.matchPrinting]'s twin no-op, the art-decisive pick and
 * [PrintingCandidates.rankedCandidates]' insertion.
 */
class ListTwinsTest {
    private val fx: JSONObject by lazy {
        OcrMatchParityTest.load()
        OcrMatchParityTest.ex.getJSONObject("list_twins")
    }

    private fun p(s: String, n: String, id: String = "$s:$n"): Map<String, Any?> =
        linkedMapOf("id" to id, "set" to s, "collector_number" to n)

    private fun pairs(a: JSONArray): Set<List<Int>> =
        (0 until a.length()).map { a.getJSONArray(it).let { r -> listOf(r.getInt(0), r.getInt(1)) } }.toSet()

    @Test fun listSetsMatchTheServer() {
        val want = (0 until fx.getJSONArray("list_sets").length()).map { fx.getJSONArray("list_sets").getString(it) }.toSet()
        assertEquals(want, ListTwins.LIST_SETS)
    }

    @Test fun everyPairOfTheRealPrintingsMatchesTheServer() {
        val printings = JSONObject(OcrMatchParityTest.file("printings.json").readText())
        val want = fx.getJSONObject("pairs")
        var checked = 0
        var twins = 0
        for (name in printings.keySet()) {
            val recs = OcrMatchParityTest.cands(printings.getJSONArray(name))
            val got = HashSet<List<Int>>()
            for ((i, t) in recs.withIndex()) for ((j, x) in recs.withIndex()) {
                checked++
                if (ListTwins.isListTwin(t, x)) got += listOf(i, j)
            }
            assertEquals(name, pairs(want.getJSONArray(name)), got)
            twins += got.size
        }
        assertTrue("pairs checked: $checked", checked > 30_000 && twins >= 12)
    }

    @Test fun handShapesMatchTheServer() {
        val hand = OcrMatchParityTest.cands(fx.getJSONArray("hand"))
        val got = HashSet<List<Int>>()
        for ((i, t) in hand.withIndex()) for ((j, x) in hand.withIndex()) if (ListTwins.isListTwin(t, x)) got += listOf(i, j)
        assertEquals(pairs(fx.getJSONArray("hand_pairs")), got)
    }

    @Test fun withTwinsMatchesTheServer() {
        val rankings = fx.getJSONObject("rankings")
        val rows = fx.getJSONArray("with_twins")
        var grew = 0
        for (k in 0 until rows.length()) {
            val r = rows.getJSONObject(k)
            val ranked = OcrMatchParityTest.cands(rankings.getJSONArray(r.getString("ranking")))
            val topN = r.getInt("top_n")
            val out = ListTwins.withTwins(ranked, topN)
            val want = (0 until r.getJSONArray("out").length()).map { r.getJSONArray("out").getInt(it) }
            assertEquals("${r.getString("ranking")} top $topN", want, out.map { o -> ranked.indexOfFirst { it === o } })
            if (out.size > minOf(topN, ranked.size)) grew++
        }
        assertTrue("some rankings must pull a twin in", grew >= 10)
    }

    @Test fun realScryfallShapes() {
        assertTrue(ListTwins.isListTwin(p("plst", "A25-141"), p("a25", "141")))
        assertTrue(ListTwins.isListTwin(p("plst", "PF19-1"), p("pf19", "1")))
        assertTrue(ListTwins.isListTwin(p("plst", "2XM-3"), p("2xm", "3")))
        assertTrue(ListTwins.isListTwin(p("plst", "TAFR-1"), p("tafr", "1")))
        assertFalse(ListTwins.isListTwin(p("plst", "TAFR-1"), p("afr", "1")))
        assertTrue(ListTwins.isListTwin(p("plst", "JUD-78†"), p("jud", "78")))
        assertTrue(ListTwins.isListTwin(p("plst", "M19-185j"), p("m19", "185")))
        assertFalse(ListTwins.isListTwin(p("plst", "CHK-160"), p("chk", "160a")))
        assertFalse(ListTwins.isListTwin(p("plst", "A25-141"), p("a2s", "141")))     // never OCR-folded
        assertTrue(ListTwins.isListTwin(p("PLST", "a25-141"), p("A25", "141")))
        assertFalse(ListTwins.isListTwin(p("a25", "141"), p("plst", "A25-141")))
        assertTrue(ListTwins.twins(p("a25", "141"), p("plst", "A25-141")))
    }

    @Test fun withTwinsKeepsTheSameInstances() {
        val r = listOf(p("a25", "141"), p("x", "1"), p("plst", "A25-141"))
        val out = ListTwins.withTwins(r, 1)
        assertEquals(2, out.size)
        assertSame(r[0], out[0]); assertSame(r[2], out[1])
    }

    @Test fun theLineAListCardPrintsNeverConfirmsEither() {
        val cands = listOf(p("a25", "141", "a25"), p("plst", "A25-141", "plst"), p("m10", "146", "m10"))
        assertNull(OcrMatch.matchPrinting("141/249 U A25 • EN", cands))
        assertNull(OcrMatch.matchPrinting("A25-141 PLST", cands))
        assertEquals("m10", OcrMatch.matchPrinting("146/249 C M10 • EN", cands))
        assertEquals("a25", OcrMatch.matchPrinting("141/249 U A25 • EN", listOf(cands[0], cands[2])))
        // an ORIGINAL #1 whose "•" reads as "1" is not its List twin
        val ecl = listOf(p("ecl", "1", "ecl"), p("plst", "ECL-1", "plst"))
        assertNull(OcrMatch.matchPrinting("C 0001 ECL 1EN", ecl))
        assertEquals("ecl", OcrMatch.matchPrinting("C 0001 ECL 1EN", listOf(ecl[0], cands[2])))
    }

    @Test fun aTwinPulledInFromBelowTheTopNSitsAfterItsOriginalAndPicksNothing() {
        // a25 141 ranked #1, 13 others 5 apart, its List twin last (#15).
        val printings = ArrayList<MutableMap<String, Any?>>()
        printings += LinkedHashMap(p("a25", "141", "a25"))
        for (i in 0 until 13) printings += LinkedHashMap(p("s$i", "${100 + i}", "o$i"))
        printings += LinkedHashMap(p("plst", "A25-141", "plst"))
        val ranked = printings.mapIndexed { i, m -> LinkedHashMap(m).also { it["multi_distance"] = 5L * i; it["phash_distance"] = 3L * i } }
        val cands = PrintingCandidates.rankedCandidates(printings, ranked) { cs ->
            @Suppress("UNCHECKED_CAST")
            IdentifyDecisions.applyOcrHint(cs, "141/249 U A25 • EN") as List<LinkedHashMap<String, Any?>>
        }
        assertEquals(listOf("a25", "plst", "o0"), cands.take(3).map { it["id"] })
        assertEquals(13, cands.size)
        assertNull(cands[0]["ocr_confirmed"])
        assertNull(cands[0]["art_decisive"])           // the twin at #2 is 70 back; o0 is 5
    }

    @Test fun artDecisiveRulesForAListPair() {
        fun c(id: String, s: String, n: String, d: Long?): MutableMap<String, Any?> =
            linkedMapOf("id" to id, "set" to s, "collector_number" to n, "multi_distance" to d)
        for (mark in listOf<(List<MutableMap<String, Any?>>) -> Unit>(
            { IdentifyDecisions.markArtDecisive(it) }, { PrintingCandidates.markArtDecisive(it) })) {
            val tied = listOf(c("a", "a25", "141", 100), c("t", "plst", "A25-141", 108), c("b", "m10", "146", 170))
            mark(tied); assertNull(tied[0]["art_decisive"])
            val noDist = listOf(c("a", "a25", "141", 100), c("t", "plst", "A25-141", null), c("b", "m10", "146", 170))
            mark(noDist); assertNull(noDist[0]["art_decisive"])
            val pulledIn = listOf(c("a", "a25", "141", 100), c("t", "plst", "A25-141", 140), c("b", "m10", "146", 105))
            mark(pulledIn); assertNull(pulledIn[0]["art_decisive"])
            val otherArt = listOf(c("o", "ogw", "183", 94), c("s", "sld", "795★", 166), c("t", "plst", "OGW-183", 176))
            mark(otherArt); assertEquals(true, otherArt[0]["art_decisive"])
        }
    }
}
