package io.github.darylno.cardscanner.ident

import io.github.darylno.cardscanner.ui.Outcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Compare-row logic: how a server outcome and a phone result reduce to
 * [SideSummary]s, what counts as agreeing, the summary statistics, and the
 * 200-row JSON-lines log.
 */
class CompareLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cand(id: String, set: String, cn: String, ocr: Boolean = false) = JSONObject()
        .put("id", id).put("name", "Lightning Bolt").put("set", set).put("collector_number", cn)
        .apply { if (ocr) put("ocr_confirmed", true) }

    /** A server scan row / pipeline result as JSON. */
    private fun result(identified: Boolean, name: String?, vararg cands: JSONObject, conf: String = "high",
                       selection: JSONObject? = null) = JSONObject().apply {
        put("id", 17)
        put("identified", identified)
        put("card_read", if (name == null) JSONObject() else JSONObject().put("name", name))
        put("confidence", JSONObject().put("name", conf).put("set", conf).put("collector", conf))
        put("candidates", JSONArray(cands.toList()))
        put("error", JSONObject.NULL)
        selection?.let { put("selection", it) }
    }

    private fun side(o: JSONObject, auto: Boolean) = SideSummary.fromResult(o, auto)

    private fun row(server: SideSummary, phone: SideSummary, total: Long = 500, fallback: Boolean = false) =
        CompareRow(1L, "000000000001", "auto", server, phone, fallback, mapOf("decode" to 5L, "total" to total))

    @Test
    fun autoFiledServer_readsTheSelection_notCandidateZero() {
        val sel = JSONObject().put("scryfall_id", "m10-id").put("set", "m10").put("collector_number", "146").put("auto_picked", true)
        // Server's candidates[0] could differ after a re-pick; the selection is what was filed.
        val json = result(true, "Lightning Bolt", cand("jmp-id", "jmp", "342"), cand("m10-id", "m10", "146", ocr = true), selection = sel)
        val s = CompareMode.serverSummary(Outcome.AutoFiled(json, 17, false))!!
        assertEquals("m10-id", s.printingId)
        assertEquals("m10", s.set)
        assertTrue(s.autoPick)
        assertFalse("ocr flag is candidates[0]'s", s.ocrConfirmed)
        assertEquals("high", s.confidence)
    }

    @Test
    fun agreement_nameAndPrintingAndAutoPick() {
        val server = side(result(true, "Lightning Bolt", cand("a", "m10", "146", ocr = true), cand("b", "jmp", "1")), true)
        val same = side(result(true, "Lightning Bolt", cand("a", "m10", "146", ocr = true), cand("b", "jmp", "1")), true)
        assertTrue(row(server, same).agree)
        assertEquals(emptyList<String>(), row(server, same).disagreements())

        val otherPrinting = side(result(true, "Lightning Bolt", cand("b", "jmp", "1"), cand("a", "m10", "146")), false)
        val r = row(server, otherPrinting)
        assertTrue(r.nameAgree)
        assertFalse(r.printingAgree)
        assertFalse(r.autoPickAgree)
        assertEquals(listOf("printing", "auto-pick"), r.disagreements())

        val otherName = side(result(false, "Shivan Dragon", cand("s", "7ed", "218"), conf = "low"), false)
        assertEquals(listOf("name", "printing", "auto-pick"), row(server, otherName).disagreements())
    }

    @Test
    fun noCardOnBothSides_agrees_butNoCardVsBestGuessDoesNot() {
        val serverNoCard = CompareMode.serverSummary(Outcome.NoCard("No card detected.", false))!!
        val phoneNoCard = side(JSONObject().put("identified", false).put("no_card", true)
            .put("card_read", JSONObject()).put("candidates", JSONArray()).put("error", "No card detected (blank surface in frame)."), false)
        assertTrue(row(serverNoCard, phoneNoCard).agree)
        assertNull(phoneNoCard.name)
        assertEquals("no card", phoneNoCard.label())

        val guess = side(result(false, "Lightning Bolt", cand("a", "m10", "146"), conf = "low"), false)
        assertFalse(row(serverNoCard, guess).nameAgree)
    }

    @Test
    fun rejectedUpload_isNotCompared() {
        assertNull(CompareMode.serverSummary(Outcome.Rejected(413, "too big", false)))
    }

    @Test
    fun summary_percentagesAndPercentiles() {
        val a = side(result(true, "Lightning Bolt", cand("a", "m10", "146", ocr = true), cand("b", "jmp", "1")), true)
        val b = side(result(true, "Lightning Bolt", cand("b", "jmp", "1"), cand("a", "m10", "146")), false)
        val rows = listOf(
            row(a, a, total = 100), row(a, a, total = 200), row(a, b, total = 300), row(a, a, total = 4000, fallback = true))
        val s = CompareSummary.of(rows)
        assertEquals(4, s.n)
        assertEquals(100.0, s.nameAgreePct!!, 0.0)
        assertEquals(75.0, s.printingAgreePct!!, 0.0)
        assertEquals(200L, s.medianTotalMs)
        assertEquals(4000L, s.p95TotalMs)
        assertEquals(75.0, s.phoneOcrRate!!, 0.0)
        assertEquals(100.0, s.serverOcrRate!!, 0.0)
        assertEquals(1, s.serverFallbackRows)
        assertNull(CompareSummary.of(emptyList()).nameAgreePct)
        assertEquals(3L, CompareSummary.percentile(listOf(1L, 2L, 3L), 95))
        assertEquals(2L, CompareSummary.percentile(listOf(1L, 2L, 3L), 50))
    }

    @Test
    fun log_keepsTheLast200_asJsonLines_andSurvivesARestart() {
        val f = File(tmp.root, "c/compare.jsonl")
        val log = CompareLog(f)
        var changes = 0
        log.addListener { changes++ }
        val a = side(result(true, "Lightning Bolt", cand("a", "m10", "146")), false)
        val b = side(result(true, "Lightning Bolt", cand("b", "jmp", "1")), false)
        for (i in 0 until 205) log.add(row(a, if (i % 5 == 0) b else a).copy(jobId = "%012d".format(i)))
        assertEquals(205, changes)
        assertEquals(200, log.rows().size)
        assertEquals("000000000005", log.rows().first().jobId)
        assertEquals(200, f.readLines().count { it.isNotBlank() })

        f.appendText("{not json\n")
        val again = CompareLog(f)
        assertEquals(200, again.rows().size)
        assertEquals(log.rows().last(), again.rows().last())
        assertEquals(40, again.rows().count { !it.agree })

        val report = again.report(JSONObject().put("app", "test"))
        assertEquals("identify-compare-stage2", report.getString("probe"))
        assertEquals("test", report.getString("app"))
        assertEquals(200, report.getJSONObject("summary").getInt("n"))
        assertEquals(40, report.getJSONArray("disagreements").length())
        assertEquals(CompareLog.REPORT_TIMINGS, report.getJSONArray("timings_ms").length())
        // Compact: one line.
        assertFalse(report.toString().contains("\n"))

        again.clear()
        assertTrue(CompareLog(f).rows().isEmpty())
    }

    @Test
    fun rowJson_roundTrips() {
        val a = side(result(true, "Fire // Ice", cand("f", "mh2", "290")), true)
        val r = row(a, a.copy(confidence = "medium", error = "x"))
        assertEquals(r, CompareRow.fromJson(JSONObject(r.toJson().toString())))
    }
}
