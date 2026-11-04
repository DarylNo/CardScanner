package io.github.darylno.cardscanner.f2f

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone must MATCH a printing to an F2F product exactly like
 * facetoface.get_price. f2f/match.json comes from the REAL Python client
 * (scripts/export_f2f_match_fixtures.py; CI --check keeps it current):
 *  (a) tables of its title / collector / SKU / name helpers over every title
 *      and SKU in storefront answers captured live 2026-11-04 (List waves
 *      MB1 / FMB1 / PLIST / PLST, ulst's UPLIST, cmb2's CMB1-NOPW, variant
 *      titles like "[401] [Rulebook Showcase] [CLB] [Non-Foil]");
 *  (b) get_price sessions over those answers — every URL requested, in order,
 *      and the product it resolved to (or none).
 * Regenerate the fixture, never hand-edit it; change facetoface.py first.
 */
class F2fMatchParityTest {
    private class FakeClock(var t: Double) : ProbeClock {
        override fun nowS() = t
    }

    private val fixture: JSONObject by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("f2f/match.json")!!
            .bufferedReader(Charsets.UTF_8).readText()
        JSONObject(text)
    }

    private fun rows(table: String): List<JSONArray> {
        val a = fixture.getJSONObject(table).getJSONArray("rows")
        return (0 until a.length()).map { a.getJSONArray(it) }
    }

    /** Runs [check] on every row; fails once with every mismatch listed. */
    private fun table(name: String, minRows: Int, check: (JSONArray) -> String?) {
        val rs = rows(name)
        assertTrue("$name has ${rs.size} rows", rs.size >= minRows)
        val bad = rs.mapNotNull(check)
        assertTrue("$name: ${bad.size} of ${rs.size} rows differ:\n" + bad.take(40).joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun parseTitleBrackets_matchesTheReference() = table("parse_title", 150) { r ->
        val want = F2fPricer.TitleParts(r.getString(1), r.getString(2), r.getString(3))
        val got = F2fPricer.parseTitleBrackets(r.getString(0))
        if (got == want) null else "'${r.getString(0)}': want $want got $got"
    }

    @Test
    fun normCollector_matchesTheReference() = table("norm_collector", 100) { r ->
        val got = F2fPricer.normCollector(r.getString(0))
        if (got == r.getString(1)) null else "'${r.getString(0)}': want '${r.getString(1)}' got '$got'"
    }

    @Test
    fun skuMatchesSet_matchesTheReference() = table("sku_matches_set", 1000) { r ->
        val got = F2fPricer.skuMatchesSet(r.getString(0), r.getString(1))
        if (got == r.getBoolean(2)) null else "${r.getString(0)} vs '${r.getString(1)}': want ${r.getBoolean(2)} got $got"
    }

    @Test
    fun normName_matchesTheReference() = table("norm_name", 100) { r ->
        val got = F2fPricer.normName(r.getString(0))
        if (got == r.getString(1)) null else "'${r.getString(0)}': want '${r.getString(1)}' got '$got'"
    }

    @Test
    fun titleNamesCard_matchesTheReference() = table("title_names_card", 1000) { r ->
        val got = F2fPricer.titleNamesCard(r.getString(0), r.getString(1))
        if (got == r.getBoolean(2)) null else "'${r.getString(0)}' names '${r.getString(1)}': want ${r.getBoolean(2)} got $got"
    }

    /** Each session through F2fPricer over a fake transport answering from the same bodies. */
    @Test
    fun getPriceSessions_requestTheSameUrlsAndResolveTheSameProduct() {
        val bodies = fixture.getJSONObject("bodies")
        val sessions = fixture.getJSONArray("sessions")
        assertTrue("fixture has sessions", sessions.length() >= 15)
        val bad = mutableListOf<String>()
        for (i in 0 until sessions.length()) {
            val s = sessions.getJSONObject(i)
            val id = s.getString("id")
            val c = s.getJSONObject("card")
            val answers = s.getJSONObject("answers")
            val urls = mutableListOf<String>()
            val transport = object : F2fTransport {
                override val name = "fake"
                override val detail = "fake"
                override fun get(url: String, headers: Map<String, String>, timeoutMs: Long): TransportResponse {
                    urls += url
                    // An Error, not an Exception: the fetcher must not swallow it as a transport failure.
                    if (!answers.has(url)) throw AssertionError("requested a URL the reference never did: $url")
                    return TransportResponse(200, bodies.getJSONObject(answers.getString(url)).toString(), "h2", null)
                }

                override fun cancel() {}
            }
            val clock = FakeClock(0.0)
            val fetcher = F2fFetcher(transport, clock, { sec -> clock.t += sec; false }, { false })
            val card = ProbeCard(c.getString("name"), c.getString("set_code"), c.getString("set_name"),
                c.getString("collector_number"), c.getBoolean("foil"))
            val want: PriceOutcome = if (s.isNull("result")) PriceOutcome.NotListed else {
                val r = s.getJSONObject("result")
                val conds = r.getJSONObject("conditions")
                PriceOutcome.Found(r.getString("handle"), conds.keys().asSequence().associateWith { conds.getDouble(it) })
            }
            val wantUrls = s.getJSONArray("requests").let { a -> (0 until a.length()).map { a.getString(it) } }
            val got = try {
                F2fPricer(fetcher, fixture.getString("base")).price(card)
            } catch (e: AssertionError) {
                bad += "$id: ${e.message}"
                continue
            }
            if (urls != wantUrls) bad += "$id: requests\n  want $wantUrls\n  got  $urls"
            if (got != want) bad += "$id: want $want got $got"
        }
        assertEquals("sessions that differ from the reference:\n" + bad.joinToString("\n"), 0, bad.size)
    }

    @Test
    fun base_isTheRigs() = assertEquals(fixture.getString("base"), F2f.BASE)
}
