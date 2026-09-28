package io.github.darylno.cardscanner.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.security.MessageDigest

/**
 * Holds [OcrMatch], [OcrStrip] and [IdentifyDecisions] to the SERVER's answers
 * in `resources/ocr/expected.json`, which scripts/export_ocr_fixtures.py makes
 * by calling the real ocr_id.py / pipeline.py functions (CI re-runs it with
 * `--check`). Every row must be EXACTLY equal — no tolerance anywhere: the
 * matcher and the decisions are pure logic, and the strip variants are the
 * same OpenCV calls on the same lossless pixels.
 */
class OcrMatchParityTest {
    companion object {
        lateinit var ex: JSONObject

        @BeforeClass @JvmStatic
        fun load() {
            ex = JSONObject(file("expected.json").readText())
        }

        fun file(name: String): File =
            File(requireNotNull(OcrMatchParityTest::class.java.getResource("/ocr/$name")) { "missing fixture ocr/$name" }.toURI())

        /** JSON → plain Kotlin (LinkedHashMap / ArrayList / null / primitives), fresh copies. */
        fun kt(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> LinkedHashMap<String, Any?>().also { m -> v.keys().forEach { m[it] = kt(v.get(it)) } }
            is JSONArray -> ArrayList<Any?>().also { l -> for (i in 0 until v.length()) l.add(kt(v.get(i))) }
            else -> v
        }

        @Suppress("UNCHECKED_CAST")
        fun cands(v: Any?): MutableList<MutableMap<String, Any?>> = kt(v) as MutableList<MutableMap<String, Any?>>

        /** Order-insensitive maps, numbers by value — how Python's == compares the dicts. */
        fun norm(v: Any?): Any? = when (v) {
            is JSONObject, is JSONArray -> norm(kt(v))
            JSONObject.NULL -> null
            is Map<*, *> -> v.entries.associate { it.key.toString() to norm(it.value) }.toSortedMap()
            is List<*> -> v.map { norm(it) }
            is Number -> v.toDouble().let { d -> if (d == Math.floor(d) && !d.isInfinite()) d.toLong() else d }
            else -> v
        }

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        fun bytesOf(m: Mat): ByteArray {
            val c = if (m.isContinuous) m else m.clone()
            val b = ByteArray((c.total() * c.channels()).toInt())
            c.get(0, 0, b)
            return b
        }
    }

    /** Collects every mismatching row so one run reports them all. */
    private class Mismatches(val what: String) {
        val bad = ArrayList<String>()
        var n = 0
        fun check(ok: Boolean, msg: () -> String) { n++; if (!ok) bad.add(msg()) }
        fun done() {
            if (bad.isNotEmpty()) fail("$what: ${bad.size}/$n rows differ from the server:\n" + bad.take(25).joinToString("\n"))
            assertTrue("$what: no rows", n > 0)
        }
    }

    @Test fun constantsMatchTheServer() {
        val c = ex.getJSONObject("constants")
        assertEquals(c.getInt("high_confidence"), ArtThresholds.HIGH_CONFIDENCE_DISTANCE)
        assertEquals(c.getInt("max_confident"), ArtThresholds.MAX_CONFIDENT_DISTANCE)
        assertEquals(c.getInt("margin_confident"), ArtThresholds.MARGIN_CONFIDENT_DISTANCE)
        assertEquals(c.getInt("margin_min_gap"), ArtThresholds.MARGIN_MIN_GAP)
        assertEquals(c.getInt("no_card"), ArtThresholds.NO_CARD_DISTANCE)
        assertEquals(c.getInt("ocr_art_slack"), IdentifyDecisions.OCR_ART_SLACK)
        assertEquals(c.getInt("art_decisive_gap"), IdentifyDecisions.ART_DECISIVE_GAP)
        assertEquals(c.getInt("art_decisive_ceiling"), IdentifyDecisions.ART_DECISIVE_CEILING)
        val conf = c.getJSONObject("confusion")
        assertEquals(conf.keySet().map { it.single() to conf.getString(it).single() }.toMap(), OcrMatch.CONFUSION)
        val s = ex.getJSONObject("strip")
        assertEquals(s.getJSONArray("strip_y").getDouble(0), OcrStrip.STRIP_Y[0], 0.0)
        assertEquals(s.getJSONArray("strip_y").getDouble(1), OcrStrip.STRIP_Y[1], 0.0)
        assertEquals(s.getJSONArray("strip_x").getDouble(0), OcrStrip.STRIP_X[0], 0.0)
        assertEquals(s.getJSONArray("strip_x").getDouble(1), OcrStrip.STRIP_X[1], 0.0)
    }

    @Test fun canonMatchesTheServer() {
        val t = Mismatches("canon")
        val rows = ex.getJSONArray("canon")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONArray(i)
            val got = OcrMatch.canon(r.getString(0))
            t.check(got == r.getString(1)) { "canon(${JSONObject.quote(r.getString(0))}) = $got, server ${r.getString(1)}" }
        }
        t.done()
    }

    @Test fun matchPrintingMatchesTheServer() {
        val lists = ex.getJSONObject("lists")
        val parsed = HashMap<String, List<Map<String, Any?>>>()
        val t = Mismatches("match_printing")
        val rows = ex.getJSONArray("match")
        var named = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONArray(i)
            val blob = r.getString(0)
            val key = r.getString(1)
            val want = if (r.isNull(2)) null else r.getString(2)
            val cs = parsed.getOrPut(key) { cands(lists.getJSONArray(key)) }
            val got = OcrMatch.matchPrinting(blob, cs)
            if (want != null) named++
            t.check(got == want) { "[$key] ${JSONObject.quote(blob)} → $got, server $want" }
        }
        t.done()
        // the table must exercise both outcomes heavily, not just "None"
        assertTrue(named > 1000 && rows.length() - named > 1000)
    }

    @Test fun stripBoundsMatchTheServer() {
        val t = Mismatches("strip bounds")
        val rows = ex.getJSONObject("strip").getJSONArray("rects")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONArray(i)
            val got = OcrStrip.stripBounds(r.getInt(0), r.getInt(1)).toList()
            val want = (2 until 6).map { r.getInt(it) }
            t.check(got == want) { "${r.getInt(0)}x${r.getInt(1)}: $got, server $want" }
        }
        t.done()
    }

    @Test fun stripVariantsArePixelIdentical() {
        OpenCvTest.load()
        val cards = ex.getJSONObject("strip").getJSONObject("cards")
        assertTrue(cards.length() >= 4)
        for (name in cards.keySet()) {
            val card = Imgcodecs.imread(file("strip/$name").path, Imgcodecs.IMREAD_COLOR)
            check(!card.empty()) { "could not read $name" }
            val vs = OcrStrip.variants(card)
            val want = cards.getJSONObject(name).getJSONArray("variants")
            assertEquals(want.length(), vs.size)
            for (j in vs.indices) {
                val w = want.getJSONObject(j)
                val shape = w.getJSONArray("shape")
                assertEquals("$name v$j rows", shape.getInt(0), vs[j].rows())
                assertEquals("$name v$j cols", shape.getInt(1), vs[j].cols())
                assertEquals("$name v$j channels", if (shape.length() > 2) shape.getInt(2) else 1, vs[j].channels())
                val a = bytesOf(vs[j])
                if (j == 1) {
                    // grey + Otsu + ×4 cubic: bit-identical to the server's cv2
                    assertEquals("$name v$j pixels", w.getString("sha256"), sha256(a))
                } else {
                    // ×2.5 cubic (a fractional scale): OpenCV builds differ by
                    // ±1 LSB on a handful of pixels (measured: ≤27 of 16 530 on
                    // the 100×140 card, 2–3 of ~10^6 on real sizes, 4.9 JVM vs
                    // cv2 5.0) — rounding inside the resize, invisible to OCR.
                    val ref = Imgcodecs.imread(file(w.getString("file")).path, Imgcodecs.IMREAD_UNCHANGED)
                    val b = bytesOf(ref)
                    ref.release()
                    assertEquals(b.size, a.size)
                    var maxd = 0
                    var nd = 0
                    for (k in a.indices) {
                        val d = Math.abs((a[k].toInt() and 255) - (b[k].toInt() and 255))
                        if (d > 0) nd++
                        if (d > maxd) maxd = d
                    }
                    assertTrue("$name v$j max |Δ| $maxd", maxd <= 1)
                    assertTrue("$name v$j $nd/${a.size} pixels differ", nd * 200 <= a.size)
                }
                vs[j].release()
            }
            // read_bottom_strip: both variants, in order, joined and canonicalised
            val seen = ArrayList<Pair<Int, Int>>()
            val blob = OcrStrip.readBottomStrip(card) { m -> seen.add(m.cols() to m.rows()); "v${seen.size} i|" }
            assertEquals(OcrMatch.canon("v1 i| v2 i|"), blob)
            assertEquals(2, seen.size)
            assertEquals("", OcrStrip.readBottomStrip(card) { throw IllegalStateException("engine died") })
            card.release()
        }
    }

    @Test fun confidenceForMatchesTheServer() {
        val t = Mismatches("_confidence_for")
        val rows = ex.getJSONArray("confidence_for")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONArray(i)
            val got = IdentifyDecisions.confidenceFor(r.getInt(0))
            t.check(got == r.getString(1)) { "d=${r.getInt(0)}: $got, server ${r.getString(1)}" }
        }
        t.done()
    }

    @Test fun isConfidentMatchesTheServer() {
        val t = Mismatches("_is_confident")
        val rows = ex.getJSONArray("is_confident")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val ds = r.getJSONArray("distances").let { a -> (0 until a.length()).map { a.getInt(it) } }
            val want = r.getBoolean("expect")
            t.check(IdentifyDecisions.isConfident(ds) == want) { "$ds: server $want" }
            val ms = ds.mapIndexed { k, d -> ArtMatcher.Match("n$k", "s$k", "set", "1", "a", d) }
            t.check(ArtThresholds.isConfident(ms) == want) { "ArtThresholds $ds: server $want" }
            if (ds.isNotEmpty()) {
                t.check(IdentifyDecisions.isNoCard(ds[0]) == (ds[0] > ex.getJSONObject("constants").getInt("no_card"))) { "noCard ${ds[0]}" }
            }
        }
        t.done()
    }

    @Test fun artAgreesMatchesTheServer() {
        val t = Mismatches("_art_agrees")
        val rows = ex.getJSONArray("art_agrees")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            @Suppress("UNCHECKED_CAST")
            val hit = kt(r.getJSONObject("hit")) as Map<String, Any?>
            val cs = cands(r.getJSONArray("cands"))
            val got = if (r.isNull("slack")) IdentifyDecisions.artAgrees(hit, cs)
                      else IdentifyDecisions.artAgrees(hit, cs, r.getInt("slack"))
            t.check(got == r.getBoolean("expect")) { "hit=$hit cands=$cs slack=${r.opt("slack")}: $got" }
        }
        t.done()
    }

    @Test fun markArtDecisiveMatchesTheServer() {
        val t = Mismatches("_mark_art_decisive")
        val rows = ex.getJSONArray("mark_art_decisive")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val cs = cands(r.getJSONArray("in"))
            IdentifyDecisions.markArtDecisive(cs)
            t.check(norm(cs) == norm(r.getJSONArray("out"))) { "in=${r.getJSONArray("in")}: $cs, server ${r.getJSONArray("out")}" }
        }
        t.done()
    }

    @Test fun applyOcrHintMatchesTheServer() {
        val o = ex.getJSONObject("apply_ocr_hint")
        val lists = o.getJSONObject("lists")
        val t = Mismatches("_apply_ocr_hint")
        val rows = o.getJSONArray("rows")
        var promoted = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val cs = cands(lists.getJSONArray(r.getString("list")))
            val got = IdentifyDecisions.applyOcrHint(cs, r.getString("blob"), r.getBoolean("detected"))
            val ids = got.map { it["id"] }
            val conf = got.map { it["ocr_confirmed"] }
            val wantIds = kt(r.getJSONArray("out_ids"))
            val wantConf = kt(r.getJSONArray("out_confirmed"))
            if (conf.firstOrNull() == true) promoted++
            t.check(ids == wantIds && conf == wantConf) {
                "[${r.getString("list")}] ${JSONObject.quote(r.getString("blob"))} det=${r.getBoolean("detected")}: $ids $conf, server $wantIds $wantConf"
            }
        }
        t.done()
        assertTrue("some rows must promote", promoted > 50)
    }

    @Test fun sortRankedMatchesTheServer() {
        val rows = ex.getJSONArray("sort_ranked")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val got = IdentifyDecisions.sortRanked(cands(r.getJSONArray("in"))).map { it["id"] }
            assertEquals(kt(r.getJSONArray("out")), got)
        }
    }

    @Test fun autoPickMatchesTheServer() {
        val t = Mismatches("auto-pick")
        val rows = ex.getJSONArray("auto_pick")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            @Suppress("UNCHECKED_CAST")
            val res = kt(r.getJSONObject("result")) as Map<String, Any?>
            t.check(IdentifyDecisions.shouldAutoPick(res) == r.getBoolean("expect")) { "$res: server ${r.getBoolean("expect")}" }
        }
        t.done()
    }

    @Test fun scanCandidatesMatchesTheServer() {
        val sc = ex.getJSONObject("scan")
        val rankedList = sc.getJSONArray("ranked")
        val t = Mismatches("scan_candidates")
        val rows = sc.getJSONArray("rows")
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val frames = r.getJSONArray("frames")
            val idx = (0 until frames.length()).toList()
            val identifyCalls = ArrayList<Int>()
            val rankedCalls = ArrayList<List<Any>>()
            val raise = r.optJSONArray("raise")
            val identify: ((Int) -> List<ArtMatcher.Match>)? = if (!r.getBoolean("index")) null else { k ->
                identifyCalls.add(k)
                if (raise != null) {
                    if (raise.getString(0) == "ArtIndexError") throw IdentifyDecisions.ArtIndexError(raise.getString(1))
                    throw RuntimeException(raise.getString(1))
                }
                val ms = frames.getJSONObject(k).getJSONArray("matches")
                (0 until ms.length()).map { j ->
                    val m = ms.getJSONObject(j)
                    ArtMatcher.Match(m.getString("name"), m.getString("scryfall_id"), m.getString("set"),
                        m.getString("collector_number"), m.getString("artist"), m.getInt("distance"))
                }
            }
            val mode = r.getString("ranked")
            val out = IdentifyDecisions.scanCandidates(
                idx,
                sharpness = { frames.getJSONObject(it).getDouble("sharp") },
                isBlank = { frames.getJSONObject(it).getBoolean("blank") },
                identify = identify,
                ranked = { f, name, topN ->
                    rankedCalls.add(listOf(f, name, topN))
                    when (mode) {
                        "raise" -> throw RuntimeException("HTTP 503 from Scryfall — try later")
                        "raise-key" -> throw RuntimeException("'image_uris'")
                        else -> cands(rankedList)
                    }
                },
            )
            val want = kt(r.getJSONObject("out")) as MutableMap<String, Any?>
            if (want["candidates"] == "@ranked") want["candidates"] = kt(rankedList)
            val note = r.getString("note")
            t.check(norm(out) == norm(want)) { "$note: $out\n   server $want" }
            t.check(norm(identifyCalls) == norm(r.getJSONArray("identify_calls"))) { "$note: identify calls $identifyCalls" }
            t.check(norm(rankedCalls) == norm(r.getJSONArray("ranked_calls"))) { "$note: ranked calls $rankedCalls" }
            t.check(IdentifyDecisions.shouldAutoPick(out) == r.getBoolean("auto_pick")) { "$note: auto-pick" }
        }
        t.done()
    }

    @Test fun finishCandidatesChainsSortHintAndDecisive() {
        // _ranked_candidates' tail on a tiny list: sort → OCR hint → art-decisive.
        fun c(id: String, set: String, d: Int) = mutableMapOf<String, Any?>("id" to id, "set" to set, "collector_number" to "1", "multi_distance" to d)
        val out = IdentifyDecisions.finishCandidates(listOf(c("b", "tmp", 150), c("a", "mh1", 100), c("z", "j22", 190)), "MH1 EN")
        assertEquals(listOf("a", "b", "z"), out.map { it["id"] })
        assertEquals(true, out[0]["ocr_confirmed"])
        assertEquals(null, out[0]["art_decisive"])          // OCR-confirmed skips the art mark
        val out2 = IdentifyDecisions.finishCandidates(listOf(c("b", "tmp", 150), c("a", "mh1", 100)), "")
        assertEquals(true, out2[0]["art_decisive"])
    }
}
