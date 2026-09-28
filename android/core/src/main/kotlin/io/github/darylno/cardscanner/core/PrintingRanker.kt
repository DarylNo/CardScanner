package io.github.darylno.cardscanner.core

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ConcurrentHashMap

/**
 * A Scryfall printing as `visual_match` reads it — only the fields the ranker
 * touches (`id`, `set`, `collector_number`, `type_line`, `border_color`,
 * `promo`, `set_type`, `image_uris.small/normal/large`). [set] is null when
 * the key is absent (Python's `p.get("set", "?")` then reads "?"); an absent
 * `image_uris` is three nulls. [payload] rides along untouched — the Python
 * ranker returns `{**p, …}`, i.e. every field of the printing.
 */
data class Printing(
    val id: String,
    val set: String?,
    val collectorNumber: String = "",
    val typeLine: String = "",
    val borderColor: String? = null,
    val promo: Boolean = false,
    val setType: String = "",
    val imageSmall: String? = null,
    val imageNormal: String? = null,
    val imageLarge: String? = null,
    val payload: Any? = null,
) {
    /** `image_uris.get("small") or …get("normal") or …get("large")` — the first non-empty. */
    val imageUrl: String?
        get() = listOf(imageSmall, imageNormal, imageLarge).firstOrNull { !it.isNullOrEmpty() }
}

/**
 * A printing's image, decoded: 8-bit BGR [Mat] (CV_8UC3), exactly as
 * `Imgcodecs.imdecode(bytes, IMREAD_COLOR)` returns it. The ranker takes
 * ownership and releases it once hashed. Tests serve committed files; the app
 * serves a disk cache + HTTP (the server fetches `small`).
 *
 * The decoder matters: the server decodes these JPEGs with Pillow, and the
 * hashes only agree if the pixels do. OpenCV's bundled libjpeg-turbo was
 * measured to decode the fixture images to Pillow's exact pixels
 * (PrintingRankerParityTest.decode); Android's BitmapFactory is a different
 * decoder and is NOT covered by that proof.
 *
 * Any exception skips that printing — as `rank_printings` does.
 */
fun interface ImageSource {
    fun load(scryfallId: String, url: String): Mat
}

/** The four region pHashes (imagehash.phash, 64 bits) of one card image. */
class RegionHashes(val art: Long, val corner: Long, val title: Long, val textbox: Long)

/**
 * Constants and pure helpers of `mtg_card_scanner/visual_match.py`, mirrored
 * value for value. They were measured on the server — never re-tune them here.
 */
object VisualMatch {
    // Region fractions (y0, y1, x0, x1) — _ART_*, _TITLE_*, _TEXTBOX_*, _LIST_CORNER_*.
    val ART = doubleArrayOf(ArtHasher.ART_Y0, ArtHasher.ART_Y1, ArtHasher.ART_X0, ArtHasher.ART_X1)
    val TITLE = doubleArrayOf(0.00, 0.085, 0.03, 0.97)
    val TEXTBOX = doubleArrayOf(0.54, 0.86, 0.05, 0.95)
    val LIST_CORNER = doubleArrayOf(0.84, 0.99, 0.00, 0.20)

    const val WEIGHT_ART = 4
    const val WEIGHT_TITLE = 1
    const val WEIGHT_TEXTBOX = 1
    const val WEIGHT_TOTAL = WEIGHT_ART + WEIGHT_TITLE + WEIGHT_TEXTBOX

    /** NEAR_TIE_DISTANCE — art-pHash gap between #1 and #2 at or below which it's a tie. */
    const val NEAR_TIE_DISTANCE = 8

    const val BORDER_BLACK_THRESHOLD = 60
    const val BORDER_WHITE_THRESHOLD = 160

    /** _MAX_CANDIDATES_PER_SCAN — beyond it: oldest half + newest half. */
    const val MAX_CANDIDATES_PER_SCAN = 120

    /** _STAMP_SETS — The List / Mystery Booster: a planeswalker symbol replaces the set symbol. */
    val STAMP_SETS: Set<String> = setOf("plst", "mb1", "cmb1")
    const val LIST_CORNER_MARGIN = 6

    /** `_cap_candidates`. */
    fun <T> capCandidates(printings: List<T>): List<T> {
        if (printings.size <= MAX_CANDIDATES_PER_SCAN) return printings
        val half = MAX_CANDIDATES_PER_SCAN / 2
        return printings.subList(0, half) + printings.subList(printings.size - half, printings.size)
    }

    /** `_is_promo`. */
    fun isPromo(p: Printing): Boolean = p.promo || p.setType == "promo" || p.setType == "promo_pack"

    /** `_is_basic_land_printing`. */
    fun isBasicLandPrinting(p: Printing): Boolean = "Basic Land" in p.typeLine

    /** `_crop_region`'s pixel box for fractions (y0, y1, x0, x1): x0, y0, x1, y1. */
    fun box(w: Int, h: Int, frac: DoubleArray): IntArray = ArtHasher.cropBox(w, h, frac[0], frac[1], frac[2], frac[3])

    /** `imagehash.phash(crop_<region>(img))` for a Pillow-"L" card image. */
    fun regionHash(gray: GrayImage, frac: DoubleArray): Long {
        val b = box(gray.width, gray.height, frac)
        return PHash.phash64(gray.crop(b[0], b[1], b[2], b[3]))
    }

    /** All four region hashes — what rank_printings memoizes per candidate. */
    fun regionHashes(gray: GrayImage): RegionHashes = RegionHashes(
        art = regionHash(gray, ART), corner = regionHash(gray, LIST_CORNER),
        title = regionHash(gray, TITLE), textbox = regionHash(gray, TEXTBOX))

    /** `imagehash` `h1 - h2`: Hamming distance of the 64 bits. */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** `str(imagehash.phash(...))` — 16 lower-case hex digits. */
    fun hashHex(h: Long): String = String.format("%016x", h)

    /**
     * The mean luminance `_detect_border_color` thresholds: rows h//4 .. 3h//4
     * of the outer `max(8, w//55)` columns on each side, `cv2.COLOR_BGR2GRAY`,
     * numpy mean. Null when the card is under 20 px either way. The mean of
     * uint8 values is an exact integer sum over a count in float64 — the same
     * double numpy's pairwise sum produces.
     */
    fun borderMean(card: Mat): Double? {
        val h = card.rows()
        val w = card.cols()
        if (h < 20 || w < 20) return null
        val y0 = h / 4
        val y1 = 3 * h / 4
        val bw = maxOf(8, w / 55)
        var sum = 0L
        var count = 0L
        for ((c0, c1) in listOf(0 to bw, (w - bw) to w)) {
            val strip = card.submat(y0, y1, c0, c1)
            val gray = Mat()
            try {
                if (strip.channels() == 1) strip.copyTo(gray) else Imgproc.cvtColor(strip, gray, Imgproc.COLOR_BGR2GRAY)
                require(gray.type() == CvType.CV_8UC1) { "border strip is ${CvType.typeToString(gray.type())}" }
                val px = ByteArray(gray.rows() * gray.cols())
                gray.get(0, 0, px)
                for (v in px) sum += v.toInt() and 0xFF
                count += px.size
            } finally {
                gray.release()
                strip.release()
            }
        }
        return sum.toDouble() / count
    }

    /** `_detect_border_color`: "black", "white" or "unknown". */
    fun detectBorderColor(card: Mat): String {
        val mean = borderMean(card) ?: return "unknown"
        return when {
            mean < BORDER_BLACK_THRESHOLD -> "black"
            mean > BORDER_WHITE_THRESHOLD -> "white"
            else -> "unknown"
        }
    }

    /** Python `str.strip()` for the ASCII whitespace a card read can carry. */
    internal fun pyStrip(s: String): String = s.trim { it == ' ' || it in "\t\n\r\u000b\u000c" }
}

/**
 * Port of `visual_match.ArtMatcher` — ranks a card's candidate printings
 * against the scan: per printing, the pHash distance of four regions of the
 * scan vs the printing's own Scryfall image,
 *
 *     multi = art×4 + title×1 + textbox×1     (basic lands: art only)
 *
 * sorted by the ART distance (stable), plus the near-tie tiebreakers of
 * `best_match`. Same crops, same hashes ([PHash], bit-exact), same order —
 * PrintingRankerParityTest holds it to the server's answers
 * (scripts/export_ranker_fixtures.py, `--check` in CI).
 *
 * Input is the WARPED card (`ArtMatcher._warp_frame`'s output — the server's
 * `card_detect.extract_card`; on the phone [CardQuad]). The per-printing
 * region hashes are memoized by Scryfall id, as the server's `_hash_cache`.
 *
 * Where the server's pipeline uses it: `pipeline._ranked_candidates` calls
 * `rank_printings` only and re-sorts by multi_distance ([pipelineOrder]);
 * `best_match` is not on the live path today but is ported with its
 * fixtures so the tiebreakers stay available and proven.
 */
class PrintingRanker(private val images: ImageSource) {
    private val hashCache = ConcurrentHashMap<String, RegionHashes>()

    /** One ranked printing: rank_printings' `{**p, phash_distance, multi_distance, corner_distance, phash_hash}`. */
    class Ranked(
        val printing: Printing,
        val phashDistance: Int,
        val multiDistance: Int,
        val cornerDistance: Int,
        val phashHash: String,
    )

    /** A printing rank_printings skipped (no url / no id / fetch or hash failure). */
    class Skipped(val printing: Printing, val reason: String)

    /**
     * rank_printings' result plus the state it leaves on the matcher:
     * `_last_scan_border_color`, `_last_scan_is_basic`.
     */
    class Ranking(
        val ranked: List<Ranked>,
        val borderColor: String,
        val isBasic: Boolean,
        val considered: List<Printing>,
        val skipped: List<Skipped>,
    )

    /** best_match's `(best, ranked, near_tie)` plus the diagnostics it sets. */
    class BestMatch(
        val best: Ranked?,
        val ranking: Ranking,
        val nearTie: Boolean,
        val printingUncertain: Boolean,
        val topCandidates: List<String>,
        /** "list" | "base" | "n/a". */
        val listCornerDecision: String,
        /** {"list": d, "base": d} when a decision was made, else empty. */
        val listCornerDistances: Map<String, Int>,
    )

    /** Region hashes of one candidate image, memoized by [scryfallId]. */
    fun candidateHashes(scryfallId: String, url: String): RegionHashes =
        hashCache[scryfallId] ?: run {
            val img = images.load(scryfallId, url)
            val h = try {
                VisualMatch.regionHashes(ArtHasher.grayFromBgrMat(img))
            } finally {
                img.release()
            }
            hashCache[scryfallId] = h
            h
        }

    /** `rank_printings(card_img, printings)` on the already-warped card (8UC3 BGR). */
    fun rankPrintings(warpedCard: Mat, printings: List<Printing>): Ranking {
        val capped = VisualMatch.capCandidates(printings)
        val border = VisualMatch.detectBorderColor(warpedCard)
        val isBasic = capped.any { VisualMatch.isBasicLandPrinting(it) }

        val scan = ArtHasher.grayFromBgrMat(warpedCard)
        val scanArt = VisualMatch.regionHash(scan, VisualMatch.ART)
        val scanCorner = VisualMatch.regionHash(scan, VisualMatch.LIST_CORNER)
        val scanTitle = if (isBasic) 0L else VisualMatch.regionHash(scan, VisualMatch.TITLE)
        val scanTextbox = if (isBasic) 0L else VisualMatch.regionHash(scan, VisualMatch.TEXTBOX)

        val results = ArrayList<Ranked>(capped.size)
        val skipped = ArrayList<Skipped>()
        for (p in capped) {
            val url = p.imageUrl
            if (url.isNullOrEmpty() || p.id.isEmpty()) {
                skipped.add(Skipped(p, "no image url or id"))
                continue
            }
            try {
                val c = candidateHashes(p.id, url)
                val artD = VisualMatch.distance(scanArt, c.art)
                val cornerD = VisualMatch.distance(scanCorner, c.corner)
                val multiD = if (isBasic) artD else
                    artD * VisualMatch.WEIGHT_ART +
                        VisualMatch.distance(scanTitle, c.title) * VisualMatch.WEIGHT_TITLE +
                        VisualMatch.distance(scanTextbox, c.textbox) * VisualMatch.WEIGHT_TEXTBOX
                results.add(Ranked(p, artD, multiD, cornerD, VisualMatch.hashHex(c.art)))
            } catch (e: Exception) {
                skipped.add(Skipped(p, e.toString()))
            }
        }
        // list.sort(key=phash_distance): stable, ties keep printing order.
        return Ranking(results.sortedBy { it.phashDistance }, border, isBasic, capped, skipped)
    }

    /**
     * `best_match(card_img, printings, vision_set_code, vision_collector_number)`.
     * On a near tie (art gap ≤ [VisualMatch.NEAR_TIE_DISTANCE]):
     *  - basic lands: the art leader, flagged printing_uncertain with the top sets;
     *  - otherwise: an exact literal (set, collector number) read wins; else the
     *    tie sorted by multi_distance → border-colour filter → List-corner
     *    decision (black/white matches only) → first non-promo.
     */
    fun bestMatch(
        warpedCard: Mat,
        printings: List<Printing>,
        visionSetCode: String = "",
        visionCollectorNumber: String = "",
    ): BestMatch {
        val ranking = rankPrintings(warpedCard, printings)
        val ranked = ranking.ranked
        if (ranked.isEmpty()) return BestMatch(null, ranking, false, false, emptyList(), "n/a", emptyMap())

        val nearTie = ranked.size >= 2 && ranked[1].phashDistance - ranked[0].phashDistance <= VisualMatch.NEAR_TIE_DISTANCE
        var best = ranked[0]
        var uncertain = false
        var topSets: List<String> = emptyList()
        var decision = "n/a"
        var cornerDists: Map<String, Int> = emptyMap()

        if (nearTie) {
            val tieCeil = ranked[0].phashDistance + VisualMatch.NEAR_TIE_DISTANCE
            val tie = ranked.filter { it.phashDistance <= tieCeil }
            if (ranking.isBasic) {
                best = tie[0]
                uncertain = true
                topSets = tie.take(6).map { (it.printing.set ?: "?").uppercase() }
            } else {
                val literal = findLiteralMatch(tie, visionSetCode, visionCollectorNumber)
                if (literal != null) {
                    best = literal
                } else {
                    val tieSorted = tie.sortedBy { it.multiDistance }
                    val scanBorder = ranking.borderColor
                    val borderMatched = if (scanBorder == "black" || scanBorder == "white")
                        tieSorted.filter { it.printing.borderColor == scanBorder } else emptyList()
                    val candidates = if (borderMatched.isNotEmpty()) {
                        val d = listCornerDecision(borderMatched)
                        decision = d.second
                        cornerDists = d.third
                        d.first
                    } else tieSorted
                    best = candidates.firstOrNull { !VisualMatch.isPromo(it.printing) } ?: candidates[0]
                }
            }
        }
        return BestMatch(best, ranking, nearTie, uncertain, topSets, decision, cornerDists)
    }

    companion object {
        /**
         * `pipeline._ranked_candidates`' re-sort of rank_printings' output:
         * stable by multi_distance (always present here), so equal scores keep
         * their art order.
         */
        fun pipelineOrder(ranked: List<Ranked>): List<Ranked> = ranked.sortedBy { it.multiDistance }

        /** `_find_literal_match`: exact (set, collector number) after strip/lower and leading-zero strip. */
        fun findLiteralMatch(tie: List<Ranked>, visionSetCode: String, visionCollectorNumber: String): Ranked? {
            if (visionSetCode.isEmpty() || visionCollectorNumber.isEmpty()) return null
            val vset = VisualMatch.pyStrip(visionSetCode).lowercase()
            val vnum = VisualMatch.pyStrip(visionCollectorNumber).trimStart('0').ifEmpty { VisualMatch.pyStrip(visionCollectorNumber) }
            for (c in tie) {
                val cset = VisualMatch.pyStrip(c.printing.set ?: "").lowercase()
                val raw = VisualMatch.pyStrip(c.printing.collectorNumber)
                val cnum = raw.trimStart('0').ifEmpty { raw }
                if (cset == vset && cnum == vnum) return c
            }
            return null
        }

        /**
         * `_list_corner_decision`: List (PLST/MB1/CMB1) candidates win only when
         * their best corner distance beats the base candidates' best by
         * [VisualMatch.LIST_CORNER_MARGIN]; anything else is "base"; one group
         * missing is "n/a" (all candidates kept).
         */
        fun listCornerDecision(borderMatched: List<Ranked>): Triple<List<Ranked>, String, Map<String, Int>> {
            fun isList(c: Ranked) = (c.printing.set ?: "").lowercase() in VisualMatch.STAMP_SETS
            val listGroup = borderMatched.filter { isList(it) }
            val baseGroup = borderMatched.filter { !isList(it) }
            if (listGroup.isEmpty() || baseGroup.isEmpty()) return Triple(borderMatched, "n/a", emptyMap())
            val listBest = listGroup.minOf { it.cornerDistance }
            val baseBest = baseGroup.minOf { it.cornerDistance }
            val d = mapOf("list" to listBest, "base" to baseBest)
            return if (listBest <= baseBest - VisualMatch.LIST_CORNER_MARGIN) Triple(listGroup, "list", d)
            else Triple(baseGroup, "base", d)
        }
    }
}
