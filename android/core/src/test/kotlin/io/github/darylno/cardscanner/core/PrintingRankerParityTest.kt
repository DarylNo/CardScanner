package io.github.darylno.cardscanner.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.security.MessageDigest

/**
 * The phone must rank a card's printings EXACTLY as the server does — same
 * crops, same region hashes, same distances, same order, same tiebreak. This
 * holds [PrintingRanker] / [VisualMatch] to the answers the SERVER's own
 * visual_match gave on the committed inputs (scripts/export_ranker_fixtures.py;
 * CI re-runs it with `--check`, so the expectations can't drift).
 *
 * EXACT equality at every stage, no tolerances: decoded pixels (sha256),
 * border mean (IEEE bits) + label, region boxes + hashes, per-region and
 * combined distances, the art order, the pipeline's multi order, best_match.
 * Mismatches are tallied per stage and printed before failing.
 */
class PrintingRankerParityTest {
    companion object {
        lateinit var expected: JSONObject
        lateinit var printings: Map<String, List<Printing>>

        fun file(name: String): File =
            File(requireNotNull(PrintingRankerParityTest::class.java.getResource("/ranker/$name")) { "missing fixture ranker/$name" }.toURI())

        @BeforeClass @JvmStatic
        fun loadFixtures() {
            OpenCvTest.load()
            expected = JSONObject(file("expected.json").readText())
            val pj = JSONObject(file("printings.json").readText())
            printings = pj.keySet().associateWith { name ->
                val a = pj.getJSONArray(name)
                (0 until a.length()).map { printing(a.getJSONObject(it)) }
            }
        }

        fun printing(o: JSONObject): Printing {
            val uris = o.optJSONObject("image_uris")
            fun uri(k: String): String? = if (uris != null && uris.has(k)) uris.getString(k) else null
            return Printing(
                id = o.optString("id", ""),
                set = if (o.has("set")) o.getString("set") else null,
                collectorNumber = o.optString("collector_number", ""),
                typeLine = o.optString("type_line", ""),
                borderColor = if (o.has("border_color")) o.getString("border_color") else null,
                promo = o.optBoolean("promo", false),
                setType = o.optString("set_type", ""),
                imageSmall = uri("small"), imageNormal = uri("normal"), imageLarge = uri("large"),
                payload = o.optString("name", ""),
            )
        }

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { String.format("%02x", it) }

        fun bgrBytes(m: Mat): ByteArray {
            val c = if (m.isContinuous) m else m.clone()
            val b = ByteArray(c.rows() * c.cols() * 3)
            c.get(0, 0, b)
            return b
        }

        fun rgbBytes(m: Mat): ByteArray {
            val b = bgrBytes(m)
            var i = 0
            while (i < b.size) { val t = b[i]; b[i] = b[i + 2]; b[i + 2] = t; i += 3 }
            return b
        }

        /** A fixture scan decoded as the upload handler decodes it (cv2.imdecode ≡ Imgcodecs). */
        fun readScan(key: String): Mat {
            val m = Imgcodecs.imread(file("scans/$key.jpg").path, Imgcodecs.IMREAD_COLOR)
            check(!m.empty()) { "could not read scans/$key.jpg" }
            return m
        }

        /** The fixture [ImageSource]: committed `small` JPEGs; records the url it was asked for. */
        class FixtureImages : ImageSource {
            val urls = LinkedHashMap<String, String>()
            var loads = 0
            override fun load(scryfallId: String, url: String): Mat {
                val f = File(file("images").path, "$scryfallId.jpg")
                if (!f.exists()) throw java.io.FileNotFoundException(scryfallId)
                urls.putIfAbsent(scryfallId, url)
                loads++
                val m = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_COLOR)
                check(!m.empty()) { "could not decode $f" }
                return m
            }
        }

        /** export_ranker_fixtures._recipe, mirrored. */
        fun recipe(r: String): List<Printing> {
            val (kind, arg) = r.split(":", limit = 2)
            return when (kind) {
                "card" -> printings.getValue(arg)
                "concat" -> arg.split("|").flatMap { printings.getValue(it) }
                "edge" -> printings.getValue(arg).toMutableList().also { ps ->
                    ps[1] = ps[1].copy(imageSmall = null, imageLarge = null)
                    ps[2] = ps[2].copy(imageSmall = "", imageNormal = null)
                    ps[3] = ps[3].copy(imageSmall = null, imageNormal = null, imageLarge = null)
                    ps[4] = ps[4].copy(id = "")
                    ps[5] = ps[5].copy(id = "00000000-0000-4000-8000-00000000dead")
                    ps[6] = ps[6].copy(imageSmall = null, imageNormal = null, imageLarge = null)
                }
                else -> error("unknown recipe $r")
            }
        }

        fun ids(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
        fun hexBits(s: String): Long = java.lang.Long.parseUnsignedLong(s, 16)
        fun dbl(hex: String): Double = java.lang.Double.longBitsToDouble(hexBits(hex))
    }

    private class Tally {
        val checks = linkedMapOf<String, Int>()
        val fails = linkedMapOf<String, MutableList<String>>()
        fun check(stage: String, ok: Boolean, what: () -> String) {
            checks[stage] = (checks[stage] ?: 0) + 1
            if (!ok) fails.getOrPut(stage) { mutableListOf() }.add(what())
        }
        fun report(title: String) {
            println("── $title")
            for ((stage, n) in checks) {
                val f = fails[stage]?.size ?: 0
                println(String.format("  %-14s %5d checked, %s", stage, n, if (f == 0) "all exact" else "$f MISMATCH"))
                fails[stage]?.take(5)?.forEach { println("      $it") }
            }
            assertTrue("parity failures: ${fails.mapValues { it.value.size }}", fails.isEmpty())
        }
    }

    private val regionFracs = mapOf(
        "art" to VisualMatch.ART, "title" to VisualMatch.TITLE,
        "textbox" to VisualMatch.TEXTBOX, "corner" to VisualMatch.LIST_CORNER)

    private fun regionHashOf(h: RegionHashes, k: String) = when (k) {
        "art" -> h.art; "title" -> h.title; "textbox" -> h.textbox; else -> h.corner
    }

    private fun checkRegions(t: Tally, tag: String, gray: GrayImage, want: JSONObject) {
        val h = VisualMatch.regionHashes(gray)
        for ((k, frac) in regionFracs) {
            val e = want.getJSONObject(k)
            val box = VisualMatch.box(gray.width, gray.height, frac)
            val wb = e.getJSONArray("box")
            t.check("region box", (0 until 4).all { box[it] == wb.getInt(it) }) { "$tag $k ${box.toList()} vs $wb" }
            t.check("region hash", VisualMatch.hashHex(regionHashOf(h, k)) == e.getString("hash")) {
                "$tag $k ${VisualMatch.hashHex(regionHashOf(h, k))} vs ${e.getString("hash")}"
            }
        }
    }

    @Test fun constantsMirrorTheServer() {
        val c = expected.getJSONObject("constants")
        val r = c.getJSONObject("regions")
        for ((k, frac) in regionFracs) {
            val a = r.getJSONArray(k)
            assertEquals(k, (0 until 4).map { a.getDouble(it) }, frac.toList())
        }
        val w = c.getJSONArray("weights")
        assertEquals(listOf(w.getInt(0), w.getInt(1), w.getInt(2)),
                     listOf(VisualMatch.WEIGHT_ART, VisualMatch.WEIGHT_TITLE, VisualMatch.WEIGHT_TEXTBOX))
        assertEquals(c.getInt("near_tie"), VisualMatch.NEAR_TIE_DISTANCE)
        val b = c.getJSONArray("border_thresholds")
        assertEquals(b.getInt(0), VisualMatch.BORDER_BLACK_THRESHOLD)
        assertEquals(b.getInt(1), VisualMatch.BORDER_WHITE_THRESHOLD)
        assertEquals(c.getInt("max_candidates"), VisualMatch.MAX_CANDIDATES_PER_SCAN)
        assertEquals(ids(c.getJSONArray("stamp_sets")).toSet(), VisualMatch.STAMP_SETS)
        assertEquals(c.getInt("list_corner_margin"), VisualMatch.LIST_CORNER_MARGIN)
    }

    @Test fun capKeepsOldestAndNewestHalf() {
        val forest = JSONArray(file("forest_ids.json").readText())
        assertEquals(ids(expected.getJSONArray("cap_forest")), VisualMatch.capCandidates(ids(forest)))
    }

    @Test fun candidateImagesDecodeAndHashLikeTheServer() {
        val t = Tally()
        val cands = expected.getJSONObject("candidates")
        for (id in cands.keySet().sorted()) {
            val e = cands.getJSONObject(id)
            val m = Imgcodecs.imread(File(file("images").path, "$id.jpg").path, Imgcodecs.IMREAD_COLOR)
            try {
                val size = e.getJSONArray("size")
                t.check("decode size", m.cols() == size.getInt(0) && m.rows() == size.getInt(1)) { "$id ${m.cols()}×${m.rows()}" }
                t.check("decode pixels", sha256(rgbBytes(m)) == e.getString("rgb_sha256")) { "$id: OpenCV's JPEG decode ≠ Pillow's" }
                checkRegions(t, id, ArtHasher.grayFromBgrMat(m), e.getJSONObject("regions"))
            } finally {
                m.release()
            }
        }
        t.report("ranker candidates (${cands.length()} Scryfall `small` images)")
    }

    @Test fun scansDecodeBorderAndHashLikeTheServer() {
        val t = Tally()
        val scans = expected.getJSONObject("scans")
        for (key in scans.keySet().sorted()) {
            val e = scans.getJSONObject(key)
            val m = readScan(key)
            try {
                t.check("decode pixels", sha256(bgrBytes(m)) == e.getString("bgr_sha256")) { key }
                val b = e.getJSONObject("border")
                val mean = VisualMatch.borderMean(m)
                t.check("border mean", mean != null && mean.toRawBits() == dbl(b.getString("mean")).toRawBits()) {
                    "$key $mean vs ${dbl(b.getString("mean"))}"
                }
                t.check("border label", VisualMatch.detectBorderColor(m) == b.getString("label")) { key }
                checkRegions(t, key, ArtHasher.grayFromBgrMat(m), e.getJSONObject("regions"))
            } finally {
                m.release()
            }
        }
        val synth = expected.getJSONArray("border_synth")
        for (i in 0 until synth.length()) {
            val e = synth.getJSONObject(i)
            val c = e.getJSONArray("bgr")
            val m = Mat(e.getInt("h"), e.getInt("w"), CvType.CV_8UC3,
                        Scalar(c.getDouble(0), c.getDouble(1), c.getDouble(2)))
            val tag = "${e.getInt("h")}×${e.getInt("w")} $c"
            val mean = VisualMatch.borderMean(m)
            t.check("border mean", if (e.isNull("mean")) mean == null
                    else mean != null && mean.toRawBits() == dbl(e.getString("mean")).toRawBits()) { "$tag $mean" }
            t.check("border label", VisualMatch.detectBorderColor(m) == e.getString("label")) { tag }
            m.release()
        }
        t.report("ranker scans (${scans.length()} flattened cards) + ${synth.length()} synthetic borders")
    }

    @Test fun rankingMatchesTheServerRunByRun() {
        val t = Tally()
        val runs = expected.getJSONArray("runs")
        val scanHashes = HashMap<String, RegionHashes>()
        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            val label = run.getString("label")
            val input = recipe(run.getString("recipe"))
            t.check("input", input.map { it.id } == ids(run.getJSONArray("input_ids"))) { "$label recipe drifted" }
            t.check("cap", VisualMatch.capCandidates(input).map { it.id } == ids(run.getJSONArray("capped_ids"))) { label }

            val images = FixtureImages()
            val ranker = PrintingRanker(images)
            val scanKey = run.getString("scan")
            val scan = readScan(scanKey)
            try {
                val sh = scanHashes.getOrPut(scanKey) { VisualMatch.regionHashes(ArtHasher.grayFromBgrMat(scan)) }
                val ranking = ranker.rankPrintings(scan, input)
                t.check("is_basic", ranking.isBasic == run.getBoolean("is_basic")) { label }
                t.check("border", ranking.borderColor == run.getString("border")) { label }
                val rows = run.getJSONArray("ranked")
                t.check("order", ranking.ranked.map { it.printing.id } == (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }) {
                    "$label art order differs"
                }
                for ((j, r) in ranking.ranked.withIndex()) {
                    if (j >= rows.length()) break
                    val e = rows.getJSONObject(j)
                    val id = r.printing.id
                    t.check("url", images.urls[id] == e.getString("url")) { "$label $id ${images.urls[id]}" }
                    val c = ranker.candidateHashes(id, e.getString("url"))
                    for (k in listOf("art", "title", "textbox", "corner")) {
                        t.check("dist $k", VisualMatch.distance(regionHashOf(sh, k), regionHashOf(c, k)) == e.getInt(k)) { "$label $id" }
                    }
                    t.check("phash_distance", r.phashDistance == e.getInt("art")) { "$label $id" }
                    t.check("corner_distance", r.cornerDistance == e.getInt("corner")) { "$label $id" }
                    t.check("multi_distance", r.multiDistance == e.getInt("multi")) { "$label $id ${r.multiDistance} vs ${e.getInt("multi")}" }
                    t.check("phash_hash", r.phashHash == e.getString("phash_hash")) { "$label $id" }
                }
                t.check("pipeline order", PrintingRanker.pipelineOrder(ranking.ranked).map { it.printing.id } ==
                    ids(run.getJSONArray("pipeline_order"))) { "$label multi re-sort differs" }

                val bm = run.getJSONObject("best_match")
                val loadsBefore = images.loads
                val best = ranker.bestMatch(scan, input, bm.getString("vision_set"), bm.getString("vision_number"))
                t.check("hash cache", images.loads == loadsBefore) { "$label: best_match re-fetched ${images.loads - loadsBefore} images" }
                t.check("best", best.best?.printing?.id == (if (bm.isNull("best")) null else bm.getString("best"))) {
                    "$label ${best.best?.printing?.set}/${best.best?.printing?.collectorNumber} vs ${bm.opt("best")}"
                }
                t.check("near_tie", best.nearTie == bm.getBoolean("near_tie")) { label }
                t.check("uncertain", best.printingUncertain == bm.getBoolean("printing_uncertain")) { label }
                t.check("top sets", best.topCandidates == ids(bm.getJSONArray("top_candidates"))) { "$label ${best.topCandidates}" }
                t.check("list corner", best.listCornerDecision == bm.getString("corner_decision")) { "$label ${best.listCornerDecision}" }
                val cd = bm.getJSONObject("corner_distances")
                t.check("corner dists", best.listCornerDistances == cd.keySet().associateWith { cd.getInt(it) }) {
                    "$label ${best.listCornerDistances} vs $cd"
                }
            } finally {
                scan.release()
            }
        }
        t.report("ranker runs (${runs.length()} rank_printings + best_match calls)")
    }

    /** The fixtures must actually reach every branch they claim to (guards a refresh that loses one). */
    @Test fun fixturesCoverEveryBranch() {
        val runs = expected.getJSONArray("runs")
        val seen = HashSet<String>()
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            val bm = r.getJSONObject("best_match")
            seen += "border:" + r.getString("border")
            seen += "basic:" + r.getBoolean("is_basic")
            seen += "tie:" + bm.getBoolean("near_tie")
            seen += "corner:" + bm.getString("corner_decision")
            if (bm.getBoolean("printing_uncertain")) seen += "uncertain"
            if (r.getJSONArray("capped_ids").length() < r.getJSONArray("input_ids").length()) seen += "capped"
            if (r.getJSONArray("ranked").length() < r.getJSONArray("capped_ids").length()) seen += "skipped"
            if (bm.getString("vision_set").isNotEmpty()) seen += "literal"
            val rows = r.getJSONArray("ranked")
            if (rows.length() > 0 && ids(r.getJSONArray("pipeline_order"))[0] != rows.getJSONObject(0).getString("id")) seen += "multi reorders"
            // Level 3: the tie's multi-distance leader is a promo and a non-promo won.
            if (bm.getBoolean("near_tie") && !r.getBoolean("is_basic") && bm.getString("vision_set").isEmpty()) {
                val byId = recipe(r.getString("recipe")).associateBy { it.id }
                val rs = (0 until rows.length()).map { rows.getJSONObject(it) }
                val ceil = rs[0].getInt("art") + VisualMatch.NEAR_TIE_DISTANCE
                val leader = rs.filter { it.getInt("art") <= ceil }.minBy { it.getInt("multi") }.getString("id")
                if (VisualMatch.isPromo(byId.getValue(leader)) && !VisualMatch.isPromo(byId.getValue(bm.getString("best")))) seen += "promo passed over"
            }
        }
        val want = listOf("border:black", "border:white", "border:unknown", "basic:true", "basic:false",
                          "tie:true", "tie:false", "corner:list", "corner:base", "corner:n/a",
                          "uncertain", "capped", "skipped", "literal", "multi reorders", "promo passed over")
        assertEquals("branches not covered", emptyList<String>(), want.filter { it !in seen })
    }
}
