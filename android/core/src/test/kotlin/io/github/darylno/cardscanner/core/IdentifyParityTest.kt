package io.github.darylno.cardscanner.core

import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.abs

/**
 * End-to-end differential test of the on-phone identifier: [IdentifyPipeline]
 * against the result dict the server's real `Pipeline.scan_candidates`
 * returned for each committed scenario (scripts/export_identify_fixtures.py —
 * real extract_card, blank guard, ArtIndex.identify, Scryfall paging,
 * rank_printings, _apply_ocr_hint, _mark_art_decisive; only the network and
 * the OCR engine replayed from committed inputs; `--check` in CI).
 *
 * Two levels:
 *  - DECISIONS, every scenario: identified / no_card / confidence / the
 *    card read (name, set, number) / error presence / the candidate id set
 *    and #1 / ocr_confirmed + art_decisive / auto-pick grounds must be the
 *    server's; distances within [DISTANCE_TOL].
 *  - STRICT JSON (key order, ints, exact floats and distances), every
 *    scenario except [WARP_DRIFT].
 *
 * Why [WARP_DRIFT] exists (measured 2026-09-28, not assumed): the server
 * re-detects the card inside the uploaded image and warps it again. The
 * quad agrees to ~3e-5 px, but OpenCV 4.9's `warpPerspective` (this JVM
 * build) does not produce cv2 5.0's pixels even from the SAME float quad, so
 * a region hash can flip a bit or two. On these two scans that moves a
 * distance by 2–4 points; the decisions are unchanged. The ports of every
 * later stage are bit-exact on identical pixels (HashParityTest,
 * PrintingRankerParityTest), so this is the only source of drift — and the
 * phone (OpenCV 4.10) will show the same kind in Compare mode.
 */
class IdentifyParityTest {
    companion object {
        const val DISTANCE_TOL = 8L
        val WARP_DRIFT = setOf("bolt_4ed_blind", "wastes_ogw_blind")
    }

    private val fixture: Map<String, Any?> by lazy {
        OpenCvTest.load()
        PrintingsParityTest.load()
        PrintingRankerParityTest.loadFixtures()
        val f = File(requireNotNull(javaClass.getResource("/identify/expected.json")) { "missing identify fixture" }.toURI())
        PrintingsParityTest.m(MiniJson.parse(f.readText()))
    }

    private fun matcher(): ArtMatcher {
        val rows = PrintingsParityTest.l(fixture["index_rows"])
        val n = rows.size
        val h64 = LongArray(n)
        val h256 = LongArray(4 * n)
        val meta = ArrayList<ArtMatcher.Entry>(n)
        for ((i, r0) in rows.withIndex()) {
            val r = PrintingsParityTest.l(r0).map { it as String }
            meta += ArtMatcher.Entry(r[0], r[1], r[2], r[3], r[4])
            h64[i] = java.lang.Long.parseUnsignedLong(r[5], 16)
            System.arraycopy(HashParityTest.hex256(r[6]), 0, h256, 4 * i, 4)
        }
        return ArtMatcher(h64, h256, meta)
    }

    private class CannedOcr(val text: String) : OcrEngine {
        override fun read(bgr: Mat): String = text
    }

    private fun run(m: ArtMatcher, s: Map<String, Any?>): IdentifyPipeline.Result {
        val client = ScryfallPrintings(IdentifyPipelineTest.Companion.Replay(), nanoClock = { 0L }, sleeper = {})
        val pipeline = IdentifyPipeline(m, { client.getAllPrintings(it) },
            PrintingRanker(PrintingRankerParityTest.Companion.FixtureImages()), CannedOcr(s["ocr_text"] as String))
        val bytes = PrintingRankerParityTest.file("scans/${s["scan"]}.jpg").readBytes()
        val frame = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
        try { return pipeline.scan(listOf(frame)) } finally { frame.release() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decisionDiffs(exp: Map<String, Any?>, act: Map<String, Any?>): List<String> {
        val out = ArrayList<String>()
        fun same(what: String, a: Any?, b: Any?) { if (a != b) out += "$what: server=$a phone=$b" }
        fun num(v: Any?) = (v as Number).toLong()
        same("identified", exp["identified"], act["identified"])
        same("no_card", exp["no_card"], act["no_card"])
        same("confidence", exp["confidence"], act["confidence"])
        same("error?", exp["error"] == null, act["error"] == null)
        val er = exp["card_read"] as Map<String, Any?>
        val ar = act["card_read"] as Map<String, Any?>
        for (k in listOf("name", "set_code", "collector_number")) same("card_read.$k", er[k], ar[k])
        val ea = (er["alternates"] as? List<Map<String, Any?>>).orEmpty()
        val aa = (ar["alternates"] as? List<Map<String, Any?>>).orEmpty()
        same("alternates.names", ea.map { it["name"] }, aa.map { it["name"] })
        ea.zip(aa).forEach { (e, a) ->
            if (abs(num(e["distance"]) - num(a["distance"])) > DISTANCE_TOL) out += "alternate ${e["name"]} distance ${e["distance"]} vs ${a["distance"]}"
        }
        val ec = exp["candidates"] as List<Map<String, Any?>>
        val ac = act["candidates"] as List<Map<String, Any?>>
        same("candidate ids", ec.map { it["id"] }.toSet(), ac.map { it["id"] }.toSet())
        same("#1", ec.firstOrNull()?.get("id"), ac.firstOrNull()?.get("id"))
        same("#1 ocr_confirmed", ec.firstOrNull()?.get("ocr_confirmed"), ac.firstOrNull()?.get("ocr_confirmed"))
        same("#1 art_decisive", ec.firstOrNull()?.get("art_decisive"), ac.firstOrNull()?.get("art_decisive"))
        same("auto-pick", IdentifyDecisions.shouldAutoPick(exp), IdentifyDecisions.shouldAutoPick(act))
        val byId = ac.associateBy { it["id"] }
        for (e in ec) {
            val a = byId[e["id"]] ?: continue
            for (k in listOf("phash_distance", "multi_distance")) {
                if (abs(num(e[k]) - num(a[k])) > DISTANCE_TOL) out += "${e["set"]} $k ${e[k]} vs ${a[k]}"
            }
        }
        return out
    }

    @Test
    fun everyScenarioMatchesTheServer() {
        val m = matcher()
        val scenarios = PrintingsParityTest.m(fixture["scenarios"])
        val failures = linkedMapOf<String, String>()
        for ((key, s0) in scenarios) {
            val s = PrintingsParityTest.m(s0)
            val exp = PrintingsParityTest.m(s["result"])
            val r = run(m, s)
            val decisions = decisionDiffs(exp, r.json)
            if (decisions.isNotEmpty()) failures[key] = "decisions: $decisions"
            val strict = try {
                PrintingsParityTest.assertJson(exp, r.json, key); null
            } catch (e: AssertionError) {
                e.message ?: "mismatch"
            }
            when {
                strict == null -> println("  $key: identical (${r.timingsMs})")
                key in WARP_DRIFT -> println("  $key: decisions identical; known warp drift: $strict")
                else -> failures.putIfAbsent(key, "strict: $strict")
            }
        }
        assertTrue("scenarios differ from the server: $failures", failures.isEmpty())
    }
}
