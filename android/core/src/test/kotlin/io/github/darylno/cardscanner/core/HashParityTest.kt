package io.github.darylno.cardscanner.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * The phone's art fingerprint must be THE SAME BITS as the server's — the
 * identification thresholds were measured on the server's hashes. This holds
 * [PHash] / [ArtHasher] / [ArtMatcher] to the answers the SERVER's own code
 * gave on the committed inputs (scripts/export_hash_fixtures.py; CI re-runs it
 * with `--check`, so the expectations can't drift from the server).
 *
 * EXACT equality at every stage — no tolerances: gray bytes, Lanczos bytes,
 * DCT doubles (IEEE bit patterns), median, hash bits, the crop grid (boxes,
 * order, skips), identify (names, ids, distances). Each stage's mismatches
 * are counted and printed before failing, so a break says WHERE it broke.
 */
class HashParityTest {
    companion object {
        lateinit var expected: JSONObject
        lateinit var matcher: ArtMatcher

        fun file(name: String): File =
            File(requireNotNull(HashParityTest::class.java.getResource("/arthash/$name")) { "missing fixture arthash/$name" }.toURI())

        @BeforeClass @JvmStatic
        fun loadFixtures() {
            OpenCvTest.load()
            expected = JSONObject(file("expected.json").readText())
            matcher = matcherFrom(expected.getJSONArray("index_rows"))
        }

        fun matcherFrom(rows: JSONArray): ArtMatcher {
            val n = rows.length()
            val h64 = LongArray(n)
            val h256 = LongArray(n * 4)
            val meta = ArrayList<ArtMatcher.Entry>(n)
            for (i in 0 until n) {
                val r = rows.getJSONArray(i)
                meta.add(ArtMatcher.Entry(r.getString(0), r.getString(1), r.getString(2), r.getString(3), r.getString(4)))
                h64[i] = java.lang.Long.parseUnsignedLong(r.getString(5), 16)
                val w = hex256(r.getString(6))
                System.arraycopy(w, 0, h256, i * 4, 4)
            }
            return ArtMatcher(h64, h256, meta)
        }

        fun hex256(s: String): LongArray {
            require(s.length == 64)
            return LongArray(4) { java.lang.Long.parseUnsignedLong(s.substring(it * 16, it * 16 + 16), 16) }
        }

        fun hex64(v: Long) = String.format("%016x", v)
        fun hex256(w: LongArray) = w.joinToString("") { hex64(it) }
        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { String.format("%02x", it) }

        fun readGray(rel: String): GrayImage {
            val m = Imgcodecs.imread(file(rel).path, Imgcodecs.IMREAD_COLOR)
            check(!m.empty()) { "could not read $rel" }
            try { return ArtHasher.grayFromBgrMat(m) } finally { m.release() }
        }

        fun readHalf(rel: String): GrayImage {
            val m = Imgcodecs.imread(file(rel).path, Imgcodecs.IMREAD_COLOR)
            check(!m.empty()) { "could not read $rel" }
            try { return ArtHasher.halfSizeGray(m) } finally { m.release() }
        }

        fun line(h: ArtHash) = "${h.box.joinToString(",")}:${hex64(h.h64)}:${hex256(h.h256)}"

        fun doubles(hex: String) = DoubleArray(hex.length / 16) {
            java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(hex.substring(it * 16, it * 16 + 16), 16))
        }
    }

    /** Per-stage tallies, printed so a failure says which stage broke first. */
    private class Tally {
        val checks = linkedMapOf<String, Int>()
        val fails = linkedMapOf<String, MutableList<String>>()
        var maxDctDelta = 0.0
        var maxBitDiff = 0
        fun check(stage: String, ok: Boolean, what: () -> String) {
            checks[stage] = (checks[stage] ?: 0) + 1
            if (!ok) fails.getOrPut(stage) { mutableListOf() }.add(what())
        }
        fun report(title: String) {
            println("── $title")
            for ((stage, n) in checks) {
                val f = fails[stage]?.size ?: 0
                println(String.format("  %-10s %4d checked, %s", stage, n, if (f == 0) "all exact" else "$f MISMATCH"))
                fails[stage]?.take(5)?.forEach { println("      $it") }
            }
            if (maxDctDelta > 0) println("  max |Δ dct| = $maxDctDelta")
            if (maxBitDiff > 0) println("  max hash bit difference = $maxBitDiff")
            assertTrue("parity failures: ${fails.mapValues { it.value.size }}", fails.isEmpty())
        }
    }

    /** gray → Lanczos 32/64 → DCT → median → bits on one crop, against the exporter's stages. */
    private fun checkStages(t: Tally, tag: String, crop: GrayImage, st: JSONObject) {
        val r32 = PHash.lanczosResize(crop, 32, 32)
        t.check("resize", r32.pixels.contentEquals(Base64.getDecoder().decode(st.getString("r32")))) { "$tag r32" }
        val r64 = PHash.lanczosResize(crop, 64, 64)
        t.check("resize", sha256(r64.pixels) == st.getString("r64_sha256")) { "$tag r64" }
        for ((hs, img) in listOf(8 to r32, 16 to r64)) {
            val dct = PHash.dct2d(img)
            val want = doubles(st.getString("dct$hs"))
            val low = DoubleArray(hs * hs) { dct[(it / hs) * hs * 4 + it % hs] }
            var same = true
            for (i in low.indices) {
                if (low[i].toRawBits() != want[i].toRawBits()) {
                    same = false
                    t.maxDctDelta = maxOf(t.maxDctDelta, Math.abs(low[i] - want[i]))
                }
            }
            t.check("dct", same) { "$tag dct$hs" }
            val med = PHash.median(low)
            t.check("median", med.toRawBits() == doubles(st.getString("median$hs"))[0].toRawBits()) { "$tag median$hs" }
        }
    }

    @Test fun thresholdsAndGridMirrorTheServer() {
        val th = expected.getJSONObject("thresholds")
        assertEquals(th.getInt("max_confident"), ArtThresholds.MAX_CONFIDENT_DISTANCE)
        assertEquals(th.getInt("high_confidence"), ArtThresholds.HIGH_CONFIDENCE_DISTANCE)
        assertEquals(th.getInt("margin_confident"), ArtThresholds.MARGIN_CONFIDENT_DISTANCE)
        assertEquals(th.getInt("margin_min_gap"), ArtThresholds.MARGIN_MIN_GAP)
        assertEquals(th.getInt("no_card"), ArtThresholds.NO_CARD_DISTANCE)
        assertEquals(th.getInt("shortlist"), ArtThresholds.SHORTLIST)
        val crop = expected.getJSONArray("art_crop")
        assertEquals(listOf(ArtHasher.ART_Y0, ArtHasher.ART_Y1, ArtHasher.ART_X0, ArtHasher.ART_X1),
                     (0 until 4).map { crop.getDouble(it) })
        for ((key, params) in listOf("core_params" to ArtHasher.CORE_PARAMS, "dense_params" to ArtHasher.DENSE_PARAMS)) {
            val a = expected.getJSONArray(key)
            assertEquals(key, a.length(), params.size)
            for (i in params.indices) {
                val p = a.getJSONArray(i)
                assertEquals("$key[$i]", CropParam(p.getDouble(0), p.getDouble(1), p.getDouble(2)), params[i])
            }
        }
    }

    @Test fun indexSideHashesMatch() {
        val t = Tally()
        val side = expected.getJSONObject("index_side")
        for (key in side.keySet().sorted()) {
            val e = side.getJSONObject(key)
            val img = readGray("small/$key.png")
            val box = ArtHasher.cropBox(img.width, img.height, ArtHasher.ART_Y0, ArtHasher.ART_Y1, ArtHasher.ART_X0, ArtHasher.ART_X1)
            checkStages(t, key, img.crop(box[0], box[1], box[2], box[3]), e.getJSONObject("base"))
            val h = ArtHasher.indexHash(img)
            val want64 = java.lang.Long.parseUnsignedLong(e.getString("h64"), 16)
            val want256 = hex256(e.getString("h256"))
            val diff = java.lang.Long.bitCount(h.h64 xor want64) + (0 until 4).sumOf { java.lang.Long.bitCount(h.h256[it] xor want256[it]) }
            t.maxBitDiff = maxOf(t.maxBitDiff, diff)
            t.check("bits", diff == 0) { "$key differs by $diff bits" }
        }
        t.report("index side (${side.length()} Scryfall small images)")
    }

    @Test fun scanSideStagesVariantsAndIdentifyMatch() {
        val t = Tally()
        val scans = expected.getJSONObject("scans")
        for (rel in scans.keySet().sorted()) {
            val e = scans.getJSONObject(rel)
            val half = readHalf(rel)
            val wantHalf = e.getJSONArray("half")
            t.check("halfsize", half.width == wantHalf.getInt(0) && half.height == wantHalf.getInt(1)) {
                "$rel ${half.width}×${half.height} vs $wantHalf"
            }
            t.check("gray", sha256(half.pixels) == e.getString("gray_sha256")) { "$rel gray bytes" }

            val box = ArtHasher.cropBox(half.width, half.height, ArtHasher.ART_Y0, ArtHasher.ART_Y1, ArtHasher.ART_X0, ArtHasher.ART_X1)
            checkStages(t, rel, half.crop(box[0], box[1], box[2], box[3]), e.getJSONObject("base"))

            val core = ArtHasher.variants(half, ArtHasher.CORE_PARAMS)
            val wantCore = e.getJSONArray("core")
            t.check("variants", core.size == wantCore.length()) { "$rel ${core.size} core crops vs ${wantCore.length()}" }
            for (i in 0 until minOf(core.size, wantCore.length())) {
                val want = wantCore.getString(i)
                val got = line(core[i])
                val wb = want.substringBefore(':')
                t.check("boxes", got.substringBefore(':') == wb) { "$rel crop $i box ${got.substringBefore(':')} vs $wb" }
                val w64 = java.lang.Long.parseUnsignedLong(want.split(':')[1], 16)
                val w256 = hex256(want.split(':')[2])
                val diff = java.lang.Long.bitCount(core[i].h64 xor w64) +
                    (0 until 4).sumOf { java.lang.Long.bitCount(core[i].h256[it] xor w256[it]) }
                t.maxBitDiff = maxOf(t.maxBitDiff, diff)
                t.check("bits", diff == 0) { "$rel crop $i differs by $diff bits" }
            }
            val r32 = MessageDigest.getInstance("SHA-256")
            val r64 = MessageDigest.getInstance("SHA-256")
            for (h in core) {
                val c = half.crop(h.box[0], h.box[1], h.box[2], h.box[3])
                r32.update(PHash.lanczosResize(c, 32, 32).pixels)
                r64.update(PHash.lanczosResize(c, 64, 64).pixels)
            }
            t.check("resize", r32.digest().joinToString("") { String.format("%02x", it) } == e.getString("core_r32_sha256")) { "$rel all core r32" }
            t.check("resize", r64.digest().joinToString("") { String.format("%02x", it) } == e.getString("core_r64_sha256")) { "$rel all core r64" }

            val dense = ArtHasher.variants(half, ArtHasher.DENSE_PARAMS)
            t.check("variants", dense.size == e.getInt("dense_count")) { "$rel ${dense.size} dense crops vs ${e.getInt("dense_count")}" }
            t.check("variants", sha256(dense.joinToString("\n") { line(it) }.toByteArray()) == e.getString("dense_sha256")) { "$rel dense grid digest" }

            val got = matcher.identify(core, dense, 5)
            val want = e.getJSONArray("identify")
            val wantList = (0 until want.length()).map {
                val m = want.getJSONObject(it)
                ArtMatcher.Match(m.getString("name"), m.getString("scryfall_id"), m.getString("set"),
                                 m.getString("collector_number"), m.getString("artist"), m.getInt("distance"))
            }
            t.check("identify", got == wantList) { "$rel\n        got  ${got.map { it.name to it.distance }}\n        want ${wantList.map { it.name to it.distance }}" }
            t.check("identify", ArtThresholds.isConfident(got) == e.getBoolean("confident")) { "$rel confident" }
            t.check("identify", (if (got.isEmpty()) null else ArtThresholds.confidenceFor(got[0].distance)) == e.optString("confidence", null)) { "$rel confidence" }
        }
        t.report("scan side (${scans.length()} flattened-card inputs, index of ${matcher.size} rows)")
    }

    @Test fun lanczosSizeSweepMatches() {
        val t = Tally()
        val noise = readGray("synth/noise.png")
        val sweep = expected.getJSONArray("resize_sweep")
        for (i in 0 until sweep.length()) {
            val s = sweep.getJSONObject(i)
            val w = s.getInt("w"); val h = s.getInt("h")
            val px = ByteArray(w * h) { noise.pixels[((it / w) % noise.height) * noise.width + (it % w) % noise.width] }
            val src = GrayImage(w, h, px)
            t.check("source", sha256(px) == s.getString("src_sha256")) { "${w}×$h source" }
            for (size in listOf(32, 64)) {
                t.check("resize", sha256(PHash.lanczosResize(src, size, size).pixels) == s.getString("r${size}_sha256")) { "${w}×$h → $size" }
            }
        }
        t.report("Lanczos size sweep (${sweep.length()} sizes)")
    }

    @Test fun everyLanczosCoefficientTableMatchesPillow() {
        // Pillow's weights use libm sin(); ours use StrictMath.sin (fdlibm, which
        // differs from glibc by an ulp on some arguments). This proves the 22-bit
        // quantised weights are nonetheless identical for every input extent up
        // to 700 px, i.e. every table a 630×880 card or a Scryfall image can hit.
        val t = Tally()
        val blocks = expected.getJSONArray("lanczos_coeffs")
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            val out = b.getInt("out")
            val md = MessageDigest.getInstance("SHA-256")
            for (n in b.getInt("from")..b.getInt("to")) {
                if (n == out) continue
                val m = PHash.lanczosCoefficientMatrix(n, out)
                val bb = java.nio.ByteBuffer.allocate(m.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bb.asIntBuffer().put(m)
                md.update(bb.array())
            }
            t.check("coeffs", md.digest().joinToString("") { String.format("%02x", it) } == b.getString("sha256")) {
                "→$out input extents ${b.getInt("from")}..${b.getInt("to")}"
            }
        }
        t.report("Lanczos coefficient tables (${blocks.length()} blocks of 50 sizes)")
    }

    @Test fun matchedPhotoIsTheRightCard() {
        // Sanity on top of parity: the camera-like 630×880 card is identified as itself.
        val half = readHalf("photo/mh2_186.png")
        val top = matcher.identify(half, 5)
        assertEquals("Asmoranomardicadaistinaculdacar", top[0].name)
        assertTrue(ArtThresholds.isConfident(top))
    }
}
