package io.github.darylno.cardscanner.f2f

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

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
 * set-code confirmation over at most 5 candidate product fetches. The
 * matching is golden-tested against the reference (F2fMatchParityTest,
 * scripts/export_f2f_match_fixtures.py) — change facetoface.py first.
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
                val match = matchPrinting(suggest(q), foilLabel, wantCollector, wantSet, wantSetName, card.name) ?: continue
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
        name: String,
    ): Pair<JSONObject, List<JSONObject>>? {
        if (products.isEmpty()) return null
        // 1) foil filter
        val foilMatches = products.map { it to parseTitleBrackets(it.optString("title", "")) }
            .filter { norm(it.second.foilLabel) == norm(foilLabel) }
        if (foilMatches.isEmpty()) return null
        // 2) collector match; promos without a collector fall back to set name —
        //    only collector-less titles of THIS card's name (any product of the
        //    set name filed another List card's price: Mortuary → Buried Alive;
        //    a name that merely started with it, Fireball's on a Fire // Ice)
        var candidates = foilMatches.filter { it.second.collector.isNotEmpty() && normCollector(it.second.collector) == wantCollector }
        // 2b) a meld half: "Midnight Scavengers // Chittering Host [96a] [B] [Eldritch Moon]" for Scryfall's
        //     one-faced #96 — with no exact title, ONE title whose collector is the card's plus the side letter
        //     "a" and whose name part is "<this card> // <another face>" (two such are ambiguous: none). Meld-shaped
        //     on purpose: "one letter more" alone took Tymaret's "- Serial Numbered [148z]". SKU still confirms.
        if (candidates.isEmpty()) {
            val halves = foilMatches.filter {
                it.second.collector.isNotEmpty() && sideLetterOf(it.second.collector, wantCollector) &&
                    titleIsMeldHalf(it.first.optString("title", ""), name)
            }
            if (halves.size == 1) candidates = halves
        }
        if (candidates.isEmpty() && wantSetName.isNotEmpty()) {
            candidates = foilMatches.filter {
                it.second.collector.isEmpty() && norm(it.second.setName) == wantSetName &&
                    titleNamesCard(it.first.optString("title", ""), name)
            }
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
        private val MARKS = Regex("""\p{Mn}+""")

        /** facetoface._COLLECTOR_RE, a FULL match: "141", "0083", "185j", "A25-141", "PZ2-038", "M19-185j". */
        private val COLLECTOR = Regex("""(?:[A-Za-z0-9]{1,6}-)?[0-9]+[A-Za-z★†]?""")

        /**
         * facetoface._SKU_SET_RULES: a SKU confirms the set when it has EVERY
         * segment of one group and no veto segment. plst = the List's waves
         * (M-MB1 2019-11, M-FMB1 2020-03 foils, PLIST 2020-09 → 2024-02, PLST
         * 2024-08+); ulst = SIN-MTG-UPLIST; cmb2 = M-CMB1-NOPW, which cmb1 vetoes.
         */
        private class SkuRule(val groups: List<List<String>>, val vetoes: List<String> = emptyList())

        private val SKU_SET_RULES = mapOf(
            "plst" to SkuRule(listOf(listOf("plst"), listOf("plist"), listOf("mb1"), listOf("fmb1"))),
            "ulst" to SkuRule(listOf(listOf("ulst"), listOf("uplist"))),
            "cmb2" to SkuRule(listOf(listOf("cmb2"), listOf("cmb1", "nopw"))),
            "cmb1" to SkuRule(listOf(listOf("cmb1")), vetoes = listOf("nopw")),
        )

        fun norm(s: String): String = NON_ALNUM.replace(s.lowercase(), "")

        /** facetoface._norm_collector: lowercase, leading zeros stripped per hyphen segment ("PZ2-038" == "PZ2-38"). */
        fun normCollector(s: String): String =
            s.trim().lowercase().split('-').joinToString("-") { seg ->
                seg.trimStart('0').ifEmpty { if (seg.isEmpty()) "" else "0" }
            }

        /** facetoface._norm_name: NFD minus Mn marks, lowercased, alphanumeric only. */
        fun normName(s: String): String = norm(MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), ""))

        /**
         * facetoface._NAME_END: where a title's name part may run on past the card's name — " //" (back face),
         * " -" / " —" (a language or note), " (" (a variant). A plain space, exactly as the reference spells it.
         */
        private val NAME_END = Regex(" +(?://|-|—|\\()")

        /**
         * facetoface._title_names_card: the title's text before "[" IS the card's front face — equal whole, or cut
         * at its first NAME_END (the uncut form keeps "Erase (Not the Urza's Legacy One)"); never an empty one.
         * Equal, not "starts with": Fireball, Shockwave, Mountain Goat, Fog Bank are other cards.
         */
        fun titleNamesCard(title: String, name: String): Boolean {
            val want = normName(name.split(" // ")[0])
            if (want.isEmpty()) return false
            val part = title.substringBefore('[')
            // split(limit = 2)[0] = the text before the first match, a leading match giving "" — re.split(maxsplit=1)[0].
            return want == normName(part) || want == normName(part.split(NAME_END, limit = 2)[0])
        }

        /**
         * facetoface._title_is_meld_half: the title's text before "[" is "<the card's name> // <another face>"
         * (front equal under normName, the other face not normalising to nothing) and the card's own name holds
         * no " // " — how F2F titles a meld half ("Midnight Scavengers // Chittering Host [96a] [B] …").
         */
        fun titleIsMeldHalf(title: String, name: String): Boolean {
            if (" // " in name) return false
            val want = normName(name)
            val part = title.substringBefore('[')
            if (want.isEmpty() || " // " !in part) return false
            val front = part.substringBefore(" // ")
            val other = part.substringAfter(" // ")
            return normName(front) == want && normName(other).isNotEmpty()
        }

        /**
         * facetoface._side_letter_of: the title's collector is the card's plus the side letter "a" ("96a" for
         * "96"), both through normCollector (so "91A" reads "91a"); the card's ends in an ASCII digit. Only "a":
         * every meld half F2F letters uses it, while its meld PROMOS are meld-shaped too with another letter
         * ("Urza, Lord Protector // Urza, Planeswalker [225s] [Prerelease] …") and may carry the main set's SKU.
         */
        fun sideLetterOf(titleCollector: String, collector: String): Boolean {
            val t = normCollector(titleCollector)
            val want = normCollector(collector)
            return want.isNotEmpty() && want.last() in '0'..'9' &&
                t.length == want.length + 1 && t.startsWith(want) && t.last() == 'a'
        }

        /**
         * facetoface._parse_title_brackets: foil = last, set = second-last, collector = the first bracket BEFORE
         * the set name that looks like one (≥ 3 brackets) — "[401] [Rulebook Showcase] …", but also
         * "[Prerelease] [59s] …", "[Susan Van Camp] [38a] …", "[Autumn] [80c] …".
         */
        fun parseTitleBrackets(title: String): TitleParts {
            val b = BRACKETS.findAll(title).map { it.groupValues[1].trim() }.toList()
            if (b.isEmpty()) return TitleParts("", "", "")
            return TitleParts(
                collector = if (b.size >= 3) b.subList(0, b.size - 2).firstOrNull { COLLECTOR.matches(it) } ?: "" else "",
                setName = if (b.size >= 2) b[b.size - 2] else "",
                foilLabel = b.last(),
            )
        }

        fun skuMatchesSet(sku: String, setCode: String): Boolean {
            val want = setCode.lowercase()
            if (want.isEmpty()) return false
            val segments = sku.split('-').map { it.lowercase() }.toSet()
            val rule = SKU_SET_RULES[want] ?: SkuRule(listOf(listOf(want)))
            if (rule.vetoes.any { it in segments }) return false
            return rule.groups.any { group -> group.all { it in segments } }
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
