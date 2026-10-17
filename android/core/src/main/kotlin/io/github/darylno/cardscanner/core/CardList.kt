package io.github.darylno.cardscanner.core

import java.io.File
import java.io.IOException
import java.text.Normalizer

/**
 * The external card list — PORT of mtg_card_scanner/card_list.py, never re-tuned
 * here (CardListParityTest holds it to scripts/export_card_list_fixtures.py).
 *
 * A read-only list of card NAMES with tags the owner points the phone at
 * (Settings → External card list; the URL lives in AppSettings, never the repo).
 * Matching is by name, any printing: [normalizeName] on both sides, full name
 * or any face. Tags are evaluated when a scan is READ ([flag]), never stored.
 */
object CardList {
    /** Tags the pages draw as their own chips, in display order. */
    val KNOWN_TAGS = listOf("wanted", "sold_out")
    const val FACE_SEP = " // "

    private val TRANSLATE = mapOf(
        '‘' to '\'', '’' to '\'', '‚' to '\'', '‛' to '\'', '′' to '\'',
        '“' to '"', '”' to '"', '„' to '"', '‟' to '"', '″' to '"',
        '–' to '-', '—' to '-',
    )
    // The reference's whitespace, spelled out (neither Python's \s nor Java's).
    private const val WS_CLASS = "[\t\n\u000b\u000c\r \u001c-\u001f\u0085   -     　]+"
    private val WS = Regex(WS_CLASS)
    private val EDGE_WS = Regex("^$WS_CLASS|$WS_CLASS$")
    private val FACES = Regex(" ?// ?")

    /** A parsed list: what the pages tag with. */
    class Parsed(val generatedAt: String?, val count: Long, val index: Map<String, List<String>>) {
        fun toJson(): Map<String, Any?> = linkedMapOf("generated_at" to generatedAt, "count" to count, "index" to index)
    }

    /** A list that could not be used; the message is the short reason shown to the owner. */
    class ListException(reason: String) : IllegalArgumentException(reason)

    fun normalizeName(name: String): String {
        val nfd = Normalizer.normalize(name, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        var i = 0
        while (i < nfd.length) {
            val cp = nfd.codePointAt(i)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        val translated = buildString(sb.length) { for (c in sb) append(TRANSLATE[c] ?: c) }.lowercase()
        val s = WS.replace(translated, " ").trim(' ')
        return FACES.replace(s, FACE_SEP).trim(' ')
    }

    private fun strip(s: String) = EDGE_WS.replace(s, "")

    /** The full normalised name, then each face. */
    fun nameKeys(name: String): List<String> {
        val full = normalizeName(name)
        if (full.isEmpty()) return emptyList()
        val keys = arrayListOf(full)
        for (part in full.split(FACE_SEP)) {
            val face = part.trim(' ')
            if (face.isNotEmpty() && face !in keys) keys += face
        }
        return keys
    }

    /** The list body → [Parsed]; throws [ListException] ("not JSON" | "no cards list"). */
    fun parse(text: String): Parsed {
        val doc = try { MiniJson.parse(text) } catch (e: IllegalArgumentException) { throw ListException("not JSON") }
        val obj = doc as? Map<*, *> ?: throw ListException("no cards list")
        val cards = obj["cards"] as? List<*> ?: throw ListException("no cards list")
        val index = LinkedHashMap<String, ArrayList<String>>()
        var count = 0L
        for (entry in cards) {
            val e = entry as? Map<*, *> ?: continue
            val name = e["name"] as? String ?: continue
            val keys = nameKeys(name)
            if (keys.isEmpty()) continue
            count++
            val tags = ArrayList<String>()
            for (t in e["tags"] as? List<*> ?: emptyList<Any?>()) {
                val tag = (t as? String)?.let { strip(it) } ?: continue
                if (tag.isNotEmpty() && tag !in tags) tags += tag
            }
            for (k in keys) {
                val have = index.getOrPut(k) { ArrayList() }
                for (t in tags) if (t !in have) have += t
            }
        }
        return Parsed(obj["generated_at"] as? String, count, index)
    }

    /** Every tag the list gives [name] (full name or any face), known tags first. */
    fun tagsFor(name: String?, index: Map<String, List<String>>): List<String> {
        val found = ArrayList<String>()
        for (k in nameKeys(name ?: "")) for (t in index[k].orEmpty()) if (t !in found) found += t
        return KNOWN_TAGS.filter { it in found } + found.filter { it !in KNOWN_TAGS }
    }

    /** The name the scan is: its pick, else its top candidate, else what was read. */
    fun scanName(scan: Map<String, Any?>): String? {
        val top = (scan["candidates"] as? List<*>)?.firstOrNull()
        for (src in listOf(scan["selection"], top, scan["card_read"])) {
            val n = (src as? Map<*, *>)?.get("name") as? String ?: continue
            if (strip(n).isNotEmpty()) return n
        }
        return null
    }

    /**
     * The one-line state for Settings and Diagnostics, from [CardListSource.status]:
     * "Off — no URL set", "No list yet", "1,234 cards, updated <time>", plus
     * " · last error: <reason> (<time>)" while the newest attempt failed.
     */
    fun summary(status: Map<String, Any?>): String {
        if (status["enabled"] != true) return "Off — no URL set"
        val n = (status["count"] as? Number)?.toLong() ?: 0L
        val updated = status["updated_at"] as? String
        val head = if (updated == null) "No list yet"
            else "%,d %s, updated %s".format(java.util.Locale.US, n, if (n == 1L) "card" else "cards", updated)
        val err = status["error"] as? String ?: return head
        return "$head · last error: $err (${status["error_at"] ?: "?"})"
    }

    /** list_tags on a scan read; [list] null (the feature off) leaves it untouched — no key. */
    fun flag(scan: Map<String, Any?>, list: Parsed?): Map<String, Any?> {
        if (list == null) return scan
        val out = LinkedHashMap(scan)
        out["list_tags"] = tagsFor(scanName(scan), list.index)
        return out
    }
}

/**
 * The URL, the last good list (cached on disk) and the last error — PORT of
 * card_list.CardListSource. [fetch] returns (HTTP status, body) or throws
 * IOException (unreachable); [clock] stamps updates. [refresh] never throws.
 */
class CardListSource(
    url: String,
    private val cacheFile: File,
    private val fetch: (String) -> Pair<Int, String>,
    private val clock: () -> String,
) {
    private val lock = Any()
    var url: String = ""; private set
    private var list: CardList.Parsed? = null
    private var updatedAt: String? = null
    private var error: String? = null
    private var errorAt: String? = null

    init { setUrl(url) }

    fun setUrl(newUrl: String) {
        val u = newUrl.trim()
        synchronized(lock) {
            if (u == url && (list != null || u.isEmpty())) return
            url = u; list = null; updatedAt = null; error = null; errorAt = null
            if (u.isEmpty()) return
            try {
                val cached = MiniJson.parse(cacheFile.readText(Charsets.UTF_8)) as? Map<*, *> ?: return
                if (cached["url"] == u) {
                    list = CardList.parse(cached["body"] as? String ?: return)
                    updatedAt = cached["fetched_at"] as? String
                }
            } catch (e: Exception) { /* no usable cache */ }
        }
    }

    /** null when off; an empty list until the first fetch lands. */
    fun current(): CardList.Parsed? = synchronized(lock) {
        if (url.isEmpty()) null else list ?: CardList.Parsed(null, 0, emptyMap())
    }

    fun refresh(): Boolean {
        val u = synchronized(lock) { url }
        if (u.isEmpty()) return false
        val body: String
        val parsed: CardList.Parsed
        try {
            val (code, text) = fetch(u)
            if (code != 200) throw CardList.ListException("HTTP $code")
            parsed = CardList.parse(text)
            body = text
        } catch (e: CardList.ListException) {
            return failed(u, e.message ?: "error")
        } catch (e: IOException) {
            return failed(u, "unreachable")
        }
        val now = clock()
        synchronized(lock) {
            if (u != url) return false
            list = parsed; updatedAt = now; error = null; errorAt = null
        }
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(MiniJson.stringify(linkedMapOf("url" to u, "fetched_at" to now, "body" to body)), Charsets.UTF_8)
            if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile) }
        } catch (e: IOException) { /* in memory; the next good fetch caches it */ }
        return true
    }

    private fun failed(u: String, reason: String): Boolean {
        val now = clock()
        synchronized(lock) { if (u == url) { error = reason; errorAt = now } }
        return false
    }

    /** For Settings / Diagnostics. Never the URL (it may carry a key). */
    fun status(): Map<String, Any?> = synchronized(lock) {
        linkedMapOf("enabled" to url.isNotEmpty(), "count" to (list?.count ?: 0L),
            "generated_at" to list?.generatedAt, "updated_at" to updatedAt,
            "error" to error, "error_at" to errorAt)
    }
}
