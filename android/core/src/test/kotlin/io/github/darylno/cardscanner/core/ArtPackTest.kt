package io.github.darylno.cardscanner.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Holds the Kotlin reader to the SERVER's writer: fixture.bin.gz was made by
 * mtg_card_scanner.art_pack via scripts/export_pack_fixture.py (CI re-exports
 * it with --check and fails on drift), and expected.json is what the Python
 * reader decoded from it. Every row, every hash word, every flag must match.
 */
class ArtPackTest {
    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/artpack/$name")!!.use { it.readBytes() }

    private val gz = resource("fixture.bin.gz")
    private val expected = JSONObject(String(resource("expected.json"), Charsets.UTF_8))
    private val raw: ByteArray = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }

    private fun hex64(v: Long) = java.lang.Long.toUnsignedString(v, 16).padStart(16, '0')

    @Test
    fun readsTheServersPackExactly() {
        val p = ArtPack.read(ByteArrayInputStream(gz))
        assertEquals(expected.getInt("format_version"), p.formatVersion)
        assertEquals(expected.getLong("build_time"), p.buildTime)
        assertEquals(expected.getString("bulk_updated_at"), p.bulkUpdatedAt)
        assertEquals(expected.getInt("known_flags"), p.knownFlags)
        val rows = expected.getJSONArray("rows")
        assertEquals(rows.length(), p.size)
        assertEquals(4 * p.size, p.h256.size)
        for (i in 0 until p.size) {
            val r = rows.getJSONObject(i)
            assertEquals(r.getString("scryfall_id"), p.scryfallId(i))
            assertEquals(r.getString("name"), p.name(i))
            assertEquals(r.getString("set_code"), p.setCode(i))
            assertEquals(r.getString("collector_number"), p.collectorNumber(i))
            assertEquals(r.getString("artist"), p.artist(i))
            assertEquals(r.getString("hash_hex"), hex64(p.h64[i]))
            val h256 = (0 until 4).joinToString("") { hex64(p.h256[4 * i + it]) }
            assertEquals(r.getString("hash256_hex"), h256)
            assertEquals(r.getInt("flags"), p.flags[i].toInt())
            assertEquals(r.getInt("flags") and ArtPack.FLAG_DIGITAL != 0, p.isDigital(i))
        }
    }

    @Test
    fun hashesCompareAsUnsignedBitPatterns() {
        // Row with h64 = ffff…: popcount distance to 0000… must be 64.
        val p = ArtPack.read(ByteArrayInputStream(gz))
        val ones = (0 until p.size).first { p.h64[it] == -1L }
        val zeros = (0 until p.size).first { p.h64[it] == 0L }
        assertEquals(64, java.lang.Long.bitCount(p.h64[ones] xor p.h64[zeros]))
        assertEquals(BigInteger("ffffffffffffffff", 16).toLong(), p.h64[ones])
    }

    @Test
    fun rawPackIsAcceptedToo() {
        assertEquals(expected.getJSONArray("rows").length(), ArtPack.read(ByteArrayInputStream(raw)).size)
    }

    @Test
    fun trailerIsTheServersChecksum() {
        val trailer = raw.copyOfRange(raw.size - 32, raw.size).joinToString("") { "%02x".format(it) }
        assertEquals(expected.getString("pack_sha256"), trailer)
    }

    private fun expectFailure(bytes: ByteArray, contains: String) {
        try {
            ArtPack.read(ByteArrayInputStream(bytes))
            fail("expected ArtPackException containing '$contains'")
        } catch (e: ArtPackException) {
            assertTrue("${e.message}", e.message!!.contains(contains))
        }
    }

    private fun gzip(b: ByteArray): ByteArray =
        ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test
    fun checksumRejectsCorruption() {
        for (at in listOf(70, raw.size - 40, raw.size - 1)) {
            val bad = raw.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            expectFailure(bad, "checksum")
            expectFailure(gzip(bad), "checksum")
        }
    }

    @Test
    fun truncationAndTrailingBytesRejected() {
        expectFailure(raw.copyOf(raw.size - 1), "truncated")
        expectFailure(raw.copyOf(20), "truncated")
        expectFailure(raw + byteArrayOf(0), "trailing")
    }

    @Test
    fun futureVersionRejectedEvenWithValidChecksum() {
        val bad = raw.copyOf().also { it[5] = (ArtPack.FORMAT_VERSION + 1).toByte() }
        val body = bad.copyOf(bad.size - 32)
        val resigned = body + MessageDigest.getInstance("SHA-256").digest(body)
        expectFailure(resigned, "version")
    }

    @Test
    fun badMagicRejected() {
        val bad = raw.copyOf().also { "NOPE!".toByteArray().copyInto(it, 0) }
        expectFailure(bad, "magic")
    }

    @Test
    fun digitalUnknownWhenPackDoesNotKnow() {
        // knownFlags lives at offset 56; clear it and re-sign.
        val bad = raw.copyOf().also { for (k in 56 until 60) it[k] = 0 }
        val body = bad.copyOf(bad.size - 32)
        val p = ArtPack.read(ByteArrayInputStream(body + MessageDigest.getInstance("SHA-256").digest(body)))
        assertNull(p.isDigital(0))
    }
}
