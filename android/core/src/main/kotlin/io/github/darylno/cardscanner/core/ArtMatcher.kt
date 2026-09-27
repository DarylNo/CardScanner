package io.github.darylno.cardscanner.core

import org.opencv.core.Mat

/**
 * The identification thresholds, mirrored from art_index.py (and the two
 * pipeline.py rules that apply them). They were measured on the rig with the
 * SERVER's hashes; they mean the same here only because [PHash] reproduces
 * those hashes bit for bit — never re-tune them on the phone side.
 */
object ArtThresholds {
    /** `_MAX_CONFIDENT_DISTANCE` — combined score: above this → manual search. */
    const val MAX_CONFIDENT_DISTANCE = 110
    /** `_HIGH_CONFIDENCE_DISTANCE` — at/below this → "high". */
    const val HIGH_CONFIDENCE_DISTANCE = 90
    /** `_MARGIN_CONFIDENT_DISTANCE` — margin rule ceiling for the best score. */
    const val MARGIN_CONFIDENT_DISTANCE = 140
    /** `_MARGIN_MIN_GAP` — required lead over the second-best NAME. */
    const val MARGIN_MIN_GAP = 20
    /** `_NO_CARD_DISTANCE` — above this the frame holds no card at all. */
    const val NO_CARD_DISTANCE = 210
    /** `_SHORTLIST` — rows re-scored on the dense grid. */
    const val SHORTLIST = 400

    /** pipeline._is_confident: absolute bar, or a lone leader below the margin ceiling. */
    fun isConfident(matches: List<ArtMatcher.Match>): Boolean {
        if (matches.isEmpty()) return false
        val d = matches[0].distance
        if (d <= MAX_CONFIDENT_DISTANCE) return true
        val gap = if (matches.size > 1) matches[1].distance - d else (1 shl 30)
        return d <= MARGIN_CONFIDENT_DISTANCE && gap >= MARGIN_MIN_GAP
    }

    /** pipeline._confidence_for. */
    fun confidenceFor(distance: Int): String = when {
        distance <= HIGH_CONFIDENCE_DISTANCE -> "high"
        distance <= MAX_CONFIDENT_DISTANCE -> "medium"
        else -> "low"
    }
}

/**
 * In-memory art index + `ArtIndex.identify` (art_index.py), same answers as
 * the server for the same rows and the same card:
 *
 *  1. score EVERY row on the core crop grid: `4·d64 + d256`, min over crops;
 *  2. the best [ArtThresholds.SHORTLIST] rows (stable: ties keep row order)
 *     are re-scored on the dense grid;
 *  3. walk them by dense score (stable: ties keep shortlist order), dedupe by
 *     name — an Arena "A-" rebalance counts as its paper name — take top N.
 *
 * [h64] holds one coarse hash per row; [h256] 4 words per row (row i at
 * `4*i .. 4*i+3`, big-endian like `_split_u64`); [meta] the row's card.
 * No thresholding here: callers apply [ArtThresholds].
 */
class ArtMatcher(val h64: LongArray, val h256: LongArray, val meta: List<Entry>) {
    init {
        require(h256.size == h64.size * 4 && meta.size == h64.size) {
            "index arrays disagree: ${h64.size} h64, ${h256.size} h256 words, ${meta.size} meta"
        }
    }

    data class Entry(val scryfallId: String, val name: String, val set: String,
                     val collectorNumber: String, val artist: String)

    data class Match(val name: String, val scryfallId: String, val set: String,
                     val collectorNumber: String, val artist: String, val distance: Int)

    val size: Int get() = h64.size

    /**
     * `_score`: best combined distance per row over [variants], for [rows]
     * (null = every row, in row order). The server starts from 4096.
     */
    fun score(variants: List<ArtHash>, rows: IntArray? = null): IntArray {
        val nv = variants.size
        val v64 = LongArray(nv) { variants[it].h64 }
        val v256 = LongArray(nv * 4)
        for (j in 0 until nv) System.arraycopy(variants[j].h256, 0, v256, j * 4, 4)
        val n = rows?.size ?: h64.size
        val out = IntArray(n)
        for (r in 0 until n) {
            val row = rows?.get(r) ?: r
            val a = h64[row]
            val b = row * 4
            val w0 = h256[b]; val w1 = h256[b + 1]; val w2 = h256[b + 2]; val w3 = h256[b + 3]
            var best = 4096
            for (j in 0 until nv) {
                val c = j * 4
                val d = 4 * java.lang.Long.bitCount(a xor v64[j]) +
                    java.lang.Long.bitCount(w0 xor v256[c]) + java.lang.Long.bitCount(w1 xor v256[c + 1]) +
                    java.lang.Long.bitCount(w2 xor v256[c + 2]) + java.lang.Long.bitCount(w3 xor v256[c + 3])
                if (d < best) best = d
            }
            out[r] = best
        }
        return out
    }

    /** `np.argsort(scores, kind="stable")` — score, then position. */
    private fun stableArgsort(scores: IntArray): IntArray {
        val keys = LongArray(scores.size) { (scores[it].toLong() shl 32) or it.toLong() }
        keys.sort()
        return IntArray(keys.size) { (keys[it] and 0xFFFFFFFFL).toInt() }
    }

    /** `identify` from already-hashed core + dense crops of the same card. */
    fun identify(core: List<ArtHash>, dense: List<ArtHash>, topN: Int = 5): List<Match> {
        if (size == 0) return emptyList()
        val coarse = score(core)
        val shortlist = stableArgsort(coarse).let { it.copyOf(minOf(it.size, ArtThresholds.SHORTLIST)) }
        val denseScores = score(dense, shortlist)
        val order = stableArgsort(denseScores)
        val results = ArrayList<Match>(topN)
        val seen = HashSet<String>()
        for (pos in order) {
            val m = meta[shortlist[pos]]
            // Arena Alchemy rebalances ("A-Name") share the paper card's artwork.
            val name = if (m.name.startsWith("A-")) m.name.substring(2) else m.name
            if (!seen.add(name)) continue
            results.add(Match(name, m.scryfallId, m.set, m.collectorNumber, m.artist, denseScores[pos]))
            if (results.size >= topN) break
        }
        return results
    }

    /** `identify` from the half-size "L" card ([ArtHasher.halfSizeGray]). */
    fun identify(halfCard: GrayImage, topN: Int = 5): List<Match> =
        identify(ArtHasher.variants(halfCard, ArtHasher.CORE_PARAMS),
                 ArtHasher.variants(halfCard, ArtHasher.DENSE_PARAMS), topN)

    /** `identify` from the flattened (already warped) card, 8UC3 BGR. */
    fun identify(cardBgr: Mat, topN: Int = 5): List<Match> = identify(ArtHasher.halfSizeGray(cardBgr), topN)
}
