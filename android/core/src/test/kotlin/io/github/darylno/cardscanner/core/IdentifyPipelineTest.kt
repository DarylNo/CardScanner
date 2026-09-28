package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * The Stage 2 wiring, end to end on committed server fixtures: a real scan
 * (ranker/scans/bolt_m10.jpg, decoded as the upload handler decodes it) →
 * the art index the server exported (arthash index_rows: Lightning Bolt among
 * ~900 rows and decoys) → the RECORDED Scryfall pages for the name → the
 * committed `small` images → a fake OCR engine → the server's result shape.
 *
 * The pieces are each parity-tested elsewhere; this proves they are glued
 * where pipeline.py glues them and that the result JSON has /api/scan's shape.
 */
class IdentifyPipelineTest {
    companion object {
        lateinit var matcher: ArtMatcher

        @BeforeClass @JvmStatic
        fun load() {
            OpenCvTest.load()
            HashParityTest.loadFixtures()
            matcher = HashParityTest.matcher
            PrintingsParityTest.load()
            PrintingRankerParityTest.loadFixtures()
        }

        /** Replays the recorded Scryfall pages; counts requests. */
        class Replay : HttpJson {
            var requests = 0
            override fun get(url: String): String {
                requests++
                val rec = PrintingsParityTest.m(PrintingsParityTest.responses[url] ?: throw IOException("no recording for $url"))
                val status = (rec["status"] as Long).toInt()
                val body = rec["body"].let { if (it is String) it else MiniJson.stringify(it) }
                if (status !in 200..299) throw HttpStatusException(status, body, rec["content_type"] as String, url)
                return body
            }
        }

        fun scan(key: String): Mat {
            val bytes = PrintingRankerParityTest.file("scans/$key.jpg").readBytes()
            val m = Imgcodecs.imdecode(org.opencv.core.MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
            check(!m.empty())
            return m
        }

        val SERVER_RESULT_KEYS = listOf("identified", "card_read", "confidence", "candidates", "error")
        val CANDIDATE_KEYS = listOf("popularity", "id", "name", "set", "set_name", "collector_number", "rarity",
            "released_at", "border_color", "frame", "promo", "finishes", "image_small", "image_normal",
            "phash_distance", "multi_distance")
    }

    private class FakeOcr(val text: String) : OcrEngine {
        var calls = 0
        override fun read(bgr: Mat): String { calls++; return text }
    }

    private fun pipeline(ocr: OcrEngine?, http: HttpJson = Replay(), m: ArtMatcher? = matcher): IdentifyPipeline {
        val client = ScryfallPrintings(http, nanoClock = { 0L }, sleeper = {})
        return IdentifyPipeline(m, { client.getAllPrintings(it) },
            PrintingRanker(PrintingRankerParityTest.Companion.FixtureImages()), ocr)
    }

    @Suppress("UNCHECKED_CAST")
    private fun cands(r: IdentifyPipeline.Result) = r.json["candidates"] as List<Map<String, Any?>>

    @Test
    fun boltM10_identified_ranked_andOcrConfirmed() {
        val ocr = FakeOcr("146/249 C\nM10 • EN CHRISTOPHER MOELLER")
        val frame = scan("bolt_m10")
        val r = try { pipeline(ocr).scan(listOf(frame)) } finally { frame.release() }

        assertEquals(SERVER_RESULT_KEYS, r.json.keys.toList())
        assertEquals(true, r.json["identified"])
        assertNull(r.json["error"])
        @Suppress("UNCHECKED_CAST")
        val read = r.json["card_read"] as Map<String, Any?>
        assertEquals("Lightning Bolt", read["name"])
        assertEquals(listOf("name", "set_code", "collector_number", "foil", "language", "condition_estimate",
            "condition_reason", "artist", "alternates"), read.keys.toList())

        val c = cands(r)
        assertEquals(PrintingCandidates.SCAN_TOP_N, c.size)
        assertTrue("every candidate is the name's", c.all { it["name"] == "Lightning Bolt" })
        assertTrue(c.all { it.keys.toList().take(CANDIDATE_KEYS.size) == CANDIDATE_KEYS })
        // OCR named M10 uniquely and its art agrees → promoted and flagged.
        assertEquals("m10", c[0]["set"])
        assertEquals(true, c[0]["ocr_confirmed"])
        assertTrue(r.wouldAutoPick)
        assertEquals(2, ocr.calls)                     // both strip variants
        assertNotNull(r.ocrText)

        assertEquals(71, r.printingCount)              // every PAPER printing the recorded pages return
        assertEquals(0, r.skippedPrintings)
        assertEquals(listOf("blank", "identify", "printings", "ranking", "ocr", "total"), r.timingsMs.keys.toList())
        assertTrue(r.timingsMs.values.all { it >= 0 })
        assertTrue(r.timingsMs["total"]!! >= r.timingsMs["identify"]!!)
    }

    @Test
    fun noOcrRead_leavesTheArtOrder() {
        val frame = scan("bolt_m10")
        val r = try { pipeline(FakeOcr("")).scan(listOf(frame)) } finally { frame.release() }
        val c = cands(r)
        assertTrue(c.none { it["ocr_confirmed"] == true })
        val multis = c.map { (it["multi_distance"] as Long) }
        assertEquals("candidates sorted by multi_distance", multis.sorted(), multis)
        assertEquals(IdentifyDecisions.shouldAutoPick(r.json), r.wouldAutoPick)
    }

    @Test
    fun blankFrame_isNoCard() {
        val flat = Mat(880, 630, CvType.CV_8UC3, Scalar(30.0, 30.0, 30.0))
        val r = try { pipeline(null).scan(listOf(flat)) } finally { flat.release() }
        assertEquals(false, r.json["identified"])
        assertEquals(true, r.json["no_card"])
        assertEquals("No card detected (blank surface in frame).", r.json["error"])
        assertFalse(r.wouldAutoPick)
    }

    @Test
    fun noArtIndex_isTheServersError() {
        val frame = scan("bolt_m10")
        val r = try { pipeline(null, m = null).scan(listOf(frame)) } finally { frame.release() }
        assertEquals("No art index configured.", r.json["error"])
    }

    @Test
    fun printingsFailure_keepsTheIdentification() {
        val frame = scan("bolt_m10")
        val r = try {
            pipeline(null, http = HttpJson { throw IOException("offline") }).scan(listOf(frame))
        } finally { frame.release() }
        assertEquals(true, r.json["identified"])
        assertTrue(cands(r).isEmpty())
        assertEquals("Printings lookup failed for 'Lightning Bolt': offline", r.json["error"])
    }

    @Test(expected = CancellationException::class)
    fun cancelled_throws() {
        val frame = scan("bolt_m10")
        try { pipeline(null).scan(listOf(frame)) { true } } finally { frame.release() }
    }

    @Test
    fun rankerPrinting_readsTheMapLikeVisualMatch() {
        val p = linkedMapOf<String, Any?>("id" to "x", "set" to "lea", "collector_number" to "161",
            "type_line" to "Instant", "promo" to true, "set_type" to "core",
            "image_uris" to linkedMapOf("normal" to "n", "large" to "l"))
        val q = IdentifyPipeline.rankerPrinting(p)
        assertEquals("n", q.imageUrl)
        assertTrue(q.promo)
        assertNull(q.borderColor)
        assertEquals(null, IdentifyPipeline.rankerPrinting(mapOf("id" to "y")).set)
        assertEquals(p, q.payload)
    }

    @Test
    fun matcherFor_pack_decodesRowsLazily() {
        val pack = ArtPack.read(File(requireNotNull(javaClass.getResource("/artpack/fixture.bin.gz")).toURI()))
        val m = IdentifyPipeline.matcherFor(pack)
        assertEquals(pack.size, m.size)
        assertEquals(pack.name(0), m.meta[0].name)
        assertEquals(pack.scryfallId(pack.size - 1), m.meta[pack.size - 1].scryfallId)
    }
}
