package io.github.darylno.cardscanner.core

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** A pack that is not an art pack, is from a newer format, or is corrupt. */
class ArtPackException(message: String) : IOException(message)

/**
 * The artwork fingerprint index, read from the pack CI publishes (the
 * `art-pack` release; writer and byte layout: mtg_card_scanner/art_pack.py —
 * that docstring is the spec, this is a reader of it).
 *
 * Memory: the hashes live in two primitive arrays ([h64] = n longs, [h256] =
 * 4n longs, row i at 4i..4i+3, word 0 = most significant, as the server's
 * `_split_u64`), and the strings stay as ONE UTF-8 blob plus an offset table,
 * decoded per call — ~50k rows cost ~6.5 MB of heap and no per-row objects.
 * The file is streamed through gunzip + SHA-256 once; nothing is buffered
 * twice.
 *
 * Longs are the unsigned u64 bit patterns: compare with `xor` + `bitCount`,
 * never with signed arithmetic.
 */
class ArtPack private constructor(
    val formatVersion: Int,
    /** Unix seconds (UTC) the pack was built. */
    val buildTime: Long,
    /** Scryfall `unique_artwork` revision the index was built from ("" if unknown). */
    val bulkUpdatedAt: String,
    /** Which [flags] bits carry information in this pack (see [FLAG_DIGITAL]). */
    val knownFlags: Int,
    val h64: LongArray,
    val h256: LongArray,
    val flags: ByteArray,
    private val offsets: IntArray,
    private val blob: ByteArray,
) {
    val size: Int get() = h64.size

    fun scryfallId(i: Int): String = field(i, 0)
    fun name(i: Int): String = field(i, 1)
    fun setCode(i: Int): String = field(i, 2)
    fun collectorNumber(i: Int): String = field(i, 3)
    fun artist(i: Int): String = field(i, 4)

    /** Digital-only representative (Arena/MTGO). Identification KEEPS these;
     *  null when this pack doesn't know (built without the bulk file). */
    fun isDigital(i: Int): Boolean? =
        if (knownFlags and FLAG_DIGITAL == 0) null else (flags[i].toInt() and FLAG_DIGITAL) != 0

    private fun field(i: Int, k: Int): String {
        if (i < 0 || i >= size) throw IndexOutOfBoundsException("row $i of $size")
        val j = FIELDS * i + k
        return String(blob, offsets[j], offsets[j + 1] - offsets[j], Charsets.UTF_8)
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val FLAG_DIGITAL = 0x01
        private val MAGIC = "MTGAP".toByteArray(Charsets.US_ASCII)
        private const val HEADER_SIZE = 64
        private const val FIELDS = 5
        private const val SHA_LEN = 32
        private const val MAX_ROWS = 10_000_000
        private const val MAX_BLOB = 1 shl 30

        fun read(file: File): ArtPack = file.inputStream().use { read(it) }

        /** Parse a pack, gzipped or raw (sniffed). Verifies magic, version,
         *  structure and the trailing SHA-256; throws [ArtPackException]. */
        fun read(input: InputStream): ArtPack {
            val buffered = BufferedInputStream(input, 1 shl 16)
            buffered.mark(2)
            val gz = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            val raw: InputStream = if (gz) GZIPInputStream(buffered, 1 shl 16) else buffered
            val sha = MessageDigest.getInstance("SHA-256")
            val din = DigestInputStream(raw, sha)
            try {
                return parse(din, sha, raw)
            } catch (e: EOFException) {
                throw ArtPackException("art pack truncated")
            }
        }

        private fun parse(din: DigestInputStream, sha: MessageDigest, raw: InputStream): ArtPack {
            val header = ByteBuffer.wrap(readExactly(din, HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(5).also { header.get(it) }
            if (!magic.contentEquals(MAGIC)) throw ArtPackException("not an art pack (bad magic)")
            val version = header.get().toInt() and 0xff
            if (version != FORMAT_VERSION) {
                throw ArtPackException(
                    "unsupported art pack format version $version (this reader understands $FORMAT_VERSION)")
            }
            val headerSize = header.short.toInt() and 0xffff
            val buildTime = header.long
            val bulkBytes = ByteArray(32).also { header.get(it) }
            val n = header.int
            val sLen = header.int
            val knownFlags = header.int
            header.int                                        // reserved
            if (headerSize != HEADER_SIZE || n < 0 || n > MAX_ROWS || sLen < 0 || sLen > MAX_BLOB) {
                throw ArtPackException("corrupt art pack header")
            }
            val bulkLen = bulkBytes.indexOf(0.toByte()).let { if (it < 0) 32 else it }

            val scratch = ByteArray(1 shl 16)
            val h64 = LongArray(n).also { readLongs(din, it, scratch) }
            val h256 = LongArray(4 * n).also { readLongs(din, it, scratch) }
            val flags = readExactly(din, n)
            val offsets = IntArray(FIELDS * n + 1).also { readInts(din, it, scratch) }
            val blob = readExactly(din, sLen)

            din.on(false)
            val expected = sha.digest()
            val trailer = readExactly(din, SHA_LEN)
            if (!MessageDigest.isEqual(expected, trailer)) {
                throw ArtPackException("art pack checksum mismatch (corrupt download?)")
            }
            if (raw.read() != -1) throw ArtPackException("art pack has trailing bytes")

            if (offsets[0] != 0 || offsets[FIELDS * n] != sLen) {
                throw ArtPackException("corrupt art pack string offsets")
            }
            for (j in 1..FIELDS * n) {
                if (offsets[j] < offsets[j - 1]) throw ArtPackException("corrupt art pack string offsets")
            }
            return ArtPack(version, buildTime, String(bulkBytes, 0, bulkLen, Charsets.US_ASCII),
                knownFlags, h64, h256, flags, offsets, blob)
        }

        private fun readExactly(input: InputStream, len: Int): ByteArray {
            val out = ByteArray(len)
            fill(input, out, len)
            return out
        }

        private fun fill(input: InputStream, buf: ByteArray, len: Int) {
            var off = 0
            while (off < len) {
                val r = input.read(buf, off, len - off)
                if (r < 0) throw EOFException()
                off += r
            }
        }

        private fun readLongs(input: InputStream, dst: LongArray, scratch: ByteArray) {
            val per = scratch.size / 8
            var done = 0
            while (done < dst.size) {
                val count = minOf(per, dst.size - done)
                fill(input, scratch, count * 8)
                ByteBuffer.wrap(scratch, 0, count * 8).order(ByteOrder.LITTLE_ENDIAN)
                    .asLongBuffer().get(dst, done, count)
                done += count
            }
        }

        private fun readInts(input: InputStream, dst: IntArray, scratch: ByteArray) {
            val per = scratch.size / 4
            var done = 0
            while (done < dst.size) {
                val count = minOf(per, dst.size - done)
                fill(input, scratch, count * 4)
                ByteBuffer.wrap(scratch, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN)
                    .asIntBuffer().get(dst, done, count)
                done += count
            }
        }
    }
}
