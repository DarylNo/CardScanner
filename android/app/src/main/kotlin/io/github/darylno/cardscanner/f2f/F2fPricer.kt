package io.github.darylno.cardscanner.f2f

import org.json.JSONArray
import org.json.JSONObject

/** A printing to price (facetoface.get_price's arguments). */
data class ProbeCard(
    val name: String,
    val setCode: String,
    val setName: String,
    val collector: String,
    val foil: Boolean = false,
) {
    val label: String get() = "$name ${setCode.uppercase()} $collector" + if (foil) " foil" else ""
}

sealed class PriceOutcome {
    data class Found(val handle: String, val conditions: Map<String, Double>) : PriceOutcome()
    /** A confirmed miss: every ladder step answered, nothing matched. */
    object NotListed : PriceOutcome()
    /** A fetch failed (F2FUnavailableError) — unknown, NOT "no listing". */
    data class Unavailable(val what: String) : PriceOutcome()
    object Stopped : PriceOutcome()
}

/**
 * facetoface.FaceToFaceClient.get_price ported: most-specific-first query
 * ladder, foil filter, collector (or promo set-name) narrowing, and SKU
 * set-code confirmation over at most 5 candidate product fetches.
 */
class F2fPricer(
    private val fetcher: F2fFetcher,
    private val base: String = F2f.BASE,
    private val isStopped: () -> Boolean = { false },
) {
    private class Unavailable(val what: String) : Exception(what)

    fun price(card: ProbeCard): PriceOutcome {
        if (card.name.isEmpty()) return PriceOutcome.NotListed
        val foilLabel = if (card.foil) "foil" else "non-foil"
        val wantCollector = normCollector(card.collector)
        val wantSet = norm(card.setCode)
        val wantSetName = norm(card.setName)
        try {
            for (q in queries(card)) {
                if (isStopped()) return PriceOutcome.Stopped
                val match = matchPrinting(suggest(q), foilLabel, wantCollector, wantSet, wantSetName) ?: continue
                val conditions = variantsToConditions(match.second)
                if (conditions.isEmpty()) continue
                return PriceOutcome.Found(match.first.optString("handle", ""), conditions)
            }
        } catch (e: Unavailable) {
            return if (isStopped()) PriceOutcome.Stopped else PriceOutcome.Unavailable(e.what)
        }
        return if (isStopped()) PriceOutcome.Stopped else PriceOutcome.NotListed
    }

    private fun suggest(q: String): List<JSONObject> {
        val data = fetcher.getJson(suggestUrl(base, q), "suggest") ?: throw Unavailable("suggest '$q'")
        val arr = data.optJSONObject("resources")?.optJSONObject("results")?.optJSONArray("products") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun product(handle: String): JSONObject {
        val data = fetcher.getJson(productUrl(base, handle), "product") ?: throw Unavailable("product '$handle'")
        return data.optJSONObject("product") ?: JSONObject()
    }

    private fun variantsOf(handle: String): List<JSONObject> {
        val arr = product(handle).optJSONArray("variants") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun matchPrinting(
        products: List<JSONObject>, foilLabel: String, wantCollector: String, wantSet: String, wantSetName: String,
    ): Pair<JSONObject, List<JSONObject>>? {
        if (products.isEmpty()) return null
        // 1) foil filter
        val foilMatches = products.map { it to parseTitleBrackets(it.optString("title", "")) }
            .filter { norm(it.second.foilLabel) == norm(foilLabel) }
        if (foilMatches.isEmpty()) return null
        // 2) collector match; promos without a collector fall back to set name
        var candidates = foilMatches.filter { it.second.collector.isNotEmpty() && normCollector(it.second.collector) == wantCollector }
        if (candidates.isEmpty() && wantSetName.isNotEmpty()) {
            candidates = foilMatches.filter { norm(it.second.setName) == wantSetName }
        }
        if (candidates.isEmpty()) return null
        // 3) SKU set-code confirmation
        for ((cand, _) in candidates.take(5)) {
            val variants = variantsOf(cand.optString("handle", ""))
            if (variants.isEmpty()) continue
            if (wantSet.isEmpty() || skuMatchesSet(variants[0].optString("sku", ""), wantSet)) return cand to variants
        }
        // 4) only an unambiguous candidate, and only with no set code to verify
        if (wantSet.isEmpty() && candidates.size == 1) {
            val variants = variantsOf(candidates[0].first.optString("handle", ""))
            if (variants.isNotEmpty()) return candidates[0].first to variants
        }
        return null
    }

    data class TitleParts(val collector: String, val setName: String, val foilLabel: String)

    companion object {
        /** The ladder: "name collector setname [foil]" first, then looser; de-duplicated in order. */
        fun queries(card: ProbeCard): List<String> {
            val suffix = if (card.foil) " foil" else ""
            val qs = mutableListOf<String>()
            if (card.setName.isNotEmpty() && card.collector.isNotEmpty()) qs += "${card.name} ${card.collector} ${card.setName}$suffix"
            if (card.setName.isNotEmpty()) qs += "${card.name} ${card.setName}$suffix"
            if (suffix.isNotEmpty()) qs += "${card.name}$suffix"
            qs += card.name
            return qs.distinct()
        }

        fun suggestUrl(base: String, q: String): String =
            "$base/search/suggest.json?q=${quote(q)}" +
                "&resources%5Btype%5D=product" +
                "&resources%5Blimit%5D=${F2f.SUGGEST_LIMIT}" +
                "&resources%5Boptions%5D%5Bunavailable_products%5D=show"

        fun productUrl(base: String, handle: String) = "$base/products/$handle.json"

        /** urllib.parse.quote(s) with its default safe='/': unreserved + '/' kept, rest %XX of UTF-8. */
        fun quote(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '.' || ch == '-' || ch == '~' || ch == '/') {
                    sb.append(ch)
                } else {
                    sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
                }
            }
            return sb.toString()
        }

        private val NON_ALNUM = Regex("[^a-z0-9]")
        private val BRACKETS = Regex("""\[([^\]]+)]""")

        fun norm(s: String): String = NON_ALNUM.replace(s.lowercase(), "")

        fun normCollector(s: String): String {
            val t = s.trim().lowercase()
            return t.trimStart('0').ifEmpty { t }
        }

        fun parseTitleBrackets(title: String): TitleParts {
            val b = BRACKETS.findAll(title).map { it.groupValues[1] }.toList()
            if (b.isEmpty()) return TitleParts("", "", "")
            return TitleParts(
                collector = if (b.size >= 3) b[b.size - 3].trim() else "",
                setName = if (b.size >= 2) b[b.size - 2].trim() else "",
                foilLabel = b.last().trim(),
            )
        }

        fun skuMatchesSet(sku: String, setCode: String): Boolean {
            val want = setCode.lowercase()
            return want.isNotEmpty() && sku.split('-').any { it.lowercase() == want }
        }

        fun variantsToConditions(variants: List<JSONObject>): Map<String, Double> {
            val out = linkedMapOf<String, Double>()
            for (v in variants) {
                val cond = v.optString("option1", "").trim().uppercase()
                if (cond.isEmpty() || !v.has("price") || v.isNull("price")) continue
                val p = v.opt("price").toString().trim().toDoubleOrNull() ?: continue
                out[cond] = p
            }
            return out
        }
    }
}
