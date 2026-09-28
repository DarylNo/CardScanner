package io.github.darylno.cardscanner.core

import java.io.IOException

/**
 * The network, kept behind one function so :core stays pure JVM. Return the
 * response body of a GET to [url] (sent exactly as given — it is already
 * encoded). A non-2xx answer must throw [HttpStatusException]; a transport
 * failure throws whatever IOException the client has.
 */
fun interface HttpJson {
    fun get(url: String): String
}

/** A non-2xx HTTP answer (`requests`' status_code / content-type / body). */
class HttpStatusException(
    val status: Int,
    val body: String = "",
    val contentType: String = "",
    val url: String = "",
) : IOException("$status for url $url")

/** scryfall.ScryfallError: a 404 from Scryfall (unknown name). */
class ScryfallException(message: String) : Exception(message)

/**
 * Port of mtg_card_scanner/scryfall.py's printing lookup: `ScryfallClient._get`
 * (throttle + 404 handling), `_search_all` (PAGED at 175 — follows has_more /
 * next_page, never truncates at page 1) and `get_all_printings` (paper only,
 * no token/emblem/art-series layouts, DFC face images grafted up, image-less
 * printings dropped).
 *
 * Printings are Python-model JSON maps ([MiniJson]); [getAllPrintings]
 * mutates them exactly as the server does (grafting `image_uris`).
 *
 * One instance = one client: the ≥110 ms spacing is between consecutive
 * requests of this instance, measured from the end of the previous response
 * (Python sets `_last_request` after the response arrives, 404s included).
 * Python has NO retry here — a 5xx or transport failure propagates — so
 * neither does this.
 */
class ScryfallPrintings(
    private val http: HttpJson,
    private val nanoClock: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = { ms -> Thread.sleep(ms) },
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val SCRYFALL_BASE = "https://api.scryfall.com"
        const val MIN_DELAY_NANOS = 110_000_000L          // _MIN_DELAY = 0.11 s
        const val MAX_SEARCH_PAGES = 8                    // _MAX_SEARCH_PAGES
        val SKIP_PRINT_LAYOUTS = setOf("token", "double_faced_token", "emblem", "art_series")

        /** The query `get_all_printings` sends, in its dict order. */
        fun printingsParams(name: String): List<Pair<String, String>> = listOf(
            "q" to "!\"$name\"",
            "unique" to "prints",
            "order" to "released",
            "dir" to "asc",
            "include_extras" to "true",
        )

        /** `requests`' URL for `url` + `params` (urlencode = quote_plus, UTF-8). */
        fun urlWithParams(url: String, params: List<Pair<String, String>>): String =
            if (params.isEmpty()) url
            else url + "?" + params.joinToString("&") { (k, v) -> quotePlus(k) + "=" + quotePlus(v) }

        /** urllib.parse.quote_plus: unreserved `A-Za-z0-9_.-~` kept, space -> '+', else %XX of UTF-8. */
        fun quotePlus(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                when {
                    c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                        c == '_'.code || c == '.'.code || c == '-'.code || c == '~'.code -> sb.append(c.toChar())
                    c == ' '.code -> sb.append('+')
                    else -> sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
                }
            }
            return sb.toString()
        }
    }

    private var lastRequest: Long? = null

    /** ScryfallClient._get: throttle, GET, 404 -> [ScryfallException], JSON body. */
    fun getJson(url: String, params: List<Pair<String, String>> = emptyList()): Map<String, Any?> {
        lastRequest?.let { last ->
            val wait = MIN_DELAY_NANOS - (nanoClock() - last)
            if (wait > 0) sleeper((wait + 999_999) / 1_000_000)
        }
        val body = try {
            http.get(urlWithParams(url, params))
        } catch (e: HttpStatusException) {
            lastRequest = nanoClock()
            if (e.status == 404) {
                val detail = if (e.contentType.startsWith("application/json"))
                    Py.str(Py.get(Py.map(MiniJson.parse(e.body)), "details", "not found"))
                else "not found"
                throw ScryfallException("404 from Scryfall ($url): $detail")
            }
            throw e
        }
        lastRequest = nanoClock()
        return Py.map(MiniJson.parse(body)) ?: throw IOException("Scryfall answered non-object JSON for $url")
    }

    /** _search_all: every result of a /cards/search query, following pagination. */
    fun searchAll(params: List<Pair<String, String>>): List<MutableMap<String, Any?>> {
        var data = getJson("$SCRYFALL_BASE/cards/search", params)
        val cards = ArrayList<MutableMap<String, Any?>>()
        addData(cards, data)
        var pages = 1
        while (Py.truthy(data["has_more"]) && Py.truthy(data["next_page"]) && pages < MAX_SEARCH_PAGES) {
            data = getJson(data["next_page"] as String)
            addData(cards, data)
            pages++
        }
        if (Py.truthy(data["has_more"])) log("[scryfall] page cap reached — using the first ${cards.size} printings")
        return cards
    }

    @Suppress("UNCHECKED_CAST")
    private fun addData(into: MutableList<MutableMap<String, Any?>>, page: Map<String, Any?>) {
        val d = page["data"]
        if (Py.truthy(d)) for (c in d as List<*>) into.add(c as MutableMap<String, Any?>)
    }

    /**
     * get_all_printings: every PAPER printing of [name] (exact name), oldest
     * first, with images — DFC front-face `image_uris` grafted up. Digital-only
     * printings are dropped here (they stay in the identification INDEX).
     * Throws [ScryfallException] for an unknown name.
     */
    fun getAllPrintings(name: String): List<MutableMap<String, Any?>> {
        val cards = searchAll(printingsParams(name))
        val paper = cards.filter { c ->
            // Missing (or EMPTY) `games` counts as paper: `c.get("games") or ["paper"]`.
            val games = c["games"]
            val isPaper = if (!Py.truthy(games)) true else when (games) {
                is List<*> -> "paper" in games
                is String -> "paper" in games
                else -> false
            }
            isPaper && c["layout"] !in SKIP_PRINT_LAYOUTS
        }
        for (c in paper) {
            if (!Py.truthy(c["image_uris"])) {
                val faces = c["card_faces"]
                if (Py.truthy(faces)) {
                    val front = Py.map((faces as List<*>)[0])
                    if (Py.truthy(front?.get("image_uris"))) c["image_uris"] = front!!["image_uris"]
                }
            }
        }
        val withImages = paper.filter { Py.truthy(it["image_uris"]) }
        log("[scryfall] ${withImages.size} paper printings with images (of ${cards.size} total, " +
            "${cards.size - paper.size} digital-only dropped)")
        return withImages
    }
}
