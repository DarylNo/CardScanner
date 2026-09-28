package io.github.darylno.cardscanner.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Port of mtg_card_scanner/popularity.py — how widely played a card is, read
 * off the Scryfall printing we already fetched (no extra request, ever).
 *
 * The measured rules (the module docstring there is the reasoning):
 *  - an absent EDHREC rank is UNRANKED with a reason, never "fringe";
 *  - reprint count is NOT popularity: `print_count` rides along, never moves a tier;
 *  - the rank is read off the PRINTING (one name can span two oracle cards),
 *    and printings are grouped by [oracleKey], which reaches into card_faces
 *    for reversible_card printings;
 *  - tiers are percentiles of the ranked pool, not raw ranks.
 *
 * Outputs are Python-model maps in the server's key order ([summarize] ==
 * `popularity.summarize`), so they serialize to the same JSON.
 */
object Popularity {
    const val RANKED_CARD_COUNT = 32_296

    private class Tier(val ceiling: Double, val id: String, val label: String)

    private val TIERS = listOf(
        Tier(1.0, "staple", "Staple"),
        Tier(5.0, "strong", "Very popular"),
        Tier(15.0, "solid", "Popular"),
        Tier(40.0, "played", "Played"),
    )
    private val FRINGE = "fringe" to "Fringe"
    private val UNRANKED = "unranked" to "Unranked"
    const val UNRANKED_REASON = "not ranked by EDHREC — banned in Commander, a basic land, or too new"

    /** `int(v) if isinstance(v, (int, float)) and v else None` */
    private fun rankOf(v: Any?): Long? = if (Py.isNumber(v) && Py.truthy(v)) Py.toInt(v) else null

    /** Penny Dreadful rank, ONLY while the printing is penny-legal (the rank outlives rotation). */
    fun pennyRank(printing: Map<String, Any?>): Long? {
        val legal = Py.map(printing["legalities"])
        if (Py.get(legal, "penny") != "legal") return null
        return rankOf(printing["penny_rank"])
    }

    /** "top N%" of all ranked cards: 3 decimals under 1%, else 1 — Python round() (half-even on the exact double). */
    fun topPercent(rank: Long?): Double? {
        if (rank == null || rank == 0L || rank < 1) return null
        val pct = 100.0 * rank.toDouble() / RANKED_CARD_COUNT
        return BigDecimal(pct).setScale(if (pct < 1) 3 else 1, RoundingMode.HALF_EVEN).toDouble()
    }

    /** (tier id, label). An absent rank is UNRANKED, not fringe. */
    fun tierFor(rank: Long?): Pair<String, String> {
        val pct = topPercent(rank) ?: return UNRANKED
        for (t in TIERS) if (pct <= t.ceiling) return t.id to t.label
        return FRINGE
    }

    /** The printing's oracle_id, reaching into card_faces (reversible_card); "" when none. */
    fun oracleKey(printing: Map<String, Any?>): String {
        val oid = printing["oracle_id"]
        if (Py.truthy(oid)) return Py.str(oid)
        val faces = printing["card_faces"]
        if (Py.truthy(faces)) for (f in faces as List<*>) {
            val face = Py.map(f) ?: continue
            if (Py.truthy(face["oracle_id"])) return Py.str(face["oracle_id"])
        }
        return ""
    }

    /** oracle key -> number of printings (first-seen order). */
    fun printCountsByOracle(printings: List<Map<String, Any?>>): LinkedHashMap<String, Long> {
        val counts = LinkedHashMap<String, Long>()
        for (p in printings) { val k = oracleKey(p); counts[k] = (counts[k] ?: 0L) + 1 }
        return counts
    }

    /** popularity.summarize: the popularity facts for one printing, in the server's key order. */
    fun summarize(printing: Map<String, Any?>, printCount: Long? = null): LinkedHashMap<String, Any?> {
        val rank = rankOf(printing["edhrec_rank"])
        val (tier, label) = tierFor(rank)
        val out = LinkedHashMap<String, Any?>()
        out["edhrec_rank"] = rank
        out["top_percent"] = topPercent(rank)
        out["tier"] = tier
        out["label"] = label
        out["game_changer"] = Py.truthy(Py.get(printing, "game_changer", false))
        out["reserved"] = Py.truthy(Py.get(printing, "reserved", false))
        if (rank == null) out["unranked_reason"] = UNRANKED_REASON
        if (printCount != null) out["print_count"] = printCount
        val formats = Formats.formatLegality(printing)
        if (formats.isNotEmpty()) out["formats"] = formats
        pennyRank(printing)?.let { out["penny_rank"] = it }
        return out
    }
}

/**
 * popularity.format_legality: where a printing may legally be played, for the
 * 7 paper formats that drive singles demand. Legality is never popularity and
 * never moves a tier — but "banned" in Commander explains an UNRANKED tier.
 */
object Formats {
    /** (key, label) in the order a seller thinks about them. */
    val TRACKED_FORMATS: List<Pair<String, String>> = listOf(
        "standard" to "Standard",
        "pioneer" to "Pioneer",
        "modern" to "Modern",
        "legacy" to "Legacy",
        "vintage" to "Vintage",
        "pauper" to "Pauper",
        "commander" to "Commander",
    )
    private val LEGALITY_VALUES = setOf("legal", "not_legal", "restricted", "banned")

    /** Every tracked key (unknown values -> "not_legal"), or EMPTY when there is no legality data. */
    fun formatLegality(printing: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val legalities = printing["legalities"]
        if (!Py.truthy(legalities)) return out
        val m = Py.map(legalities)!!
        for ((key, _) in TRACKED_FORMATS) {
            val v = m[key]
            out[key] = if (v is String && v in LEGALITY_VALUES) v else "not_legal"
        }
        return out
    }
}
