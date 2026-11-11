package io.github.darylno.cardscanner.core

import java.util.concurrent.ConcurrentHashMap

/**
 * The play data, READ side — PORT of mtg_card_scanner/play_data.py, never re-tuned
 * here (PlayDataParityTest holds it to scripts/export_play_data_fixtures.py).
 *
 * The share of recent decks running each card, format by format (Standard,
 * Pioneer, Modern, Legacy, Vintage, Pauper, Premodern): a weekly CI job builds
 * `play-data.json` from public decklist caches and publishes it to the rolling
 * `play-data` prerelease; the phone downloads it (app `ident/PlayDataStore`) and
 * this joins it to every scan at READ time ([flag], beside CardList.flag) — a
 * dictionary lookup, never stored, so a new week's file re-tags every scan.
 *
 * Keyed by oracle_id (popularity's `oracle_id`, scans from 1.1.20 on); older
 * scans fall back to the NAME ([CardList.nameKeys]) unless the file lists that
 * name as `ambiguous` (it names 2+ oracle cards). Per format the answer is
 * {"tier": staple|played|fringe, "bp", "rank"} (in a deck of that format),
 * {"tier": "basic"}, or, off the scan's own legality, {"tier": "unplayed" |
 * "banned" | "not_legal"}; null = not known. No pack = no `play` key at all.
 *
 * The CI builder half of play_data.py is NOT ported: the phone only reads.
 */
object PlayData {
    const val PACK_KIND = "cardscanner-play-data"
    const val PACK_VERSION = 1L
    const val PACK_FILE = "play-data.json"
    /** The rolling DATA prerelease the weekly job publishes to (never `v*`, never latest). */
    const val PACK_TAG = "play-data"
    const val WINDOW_DAYS = 60L

    /** Tier thresholds in basis points of decks (1 bp = 0.01 % of decks). */
    const val STAPLE_BP = 1_000L      // ≥ 10 % of decks
    const val PLAYED_BP = 200L        // ≥ 2 %
    val TIERS = listOf("staple", "played", "fringe", "unplayed", "basic", "banned", "not_legal")

    /** (key, label) in the reference order — the Scryfall legality key "not played" reads. */
    val FORMATS: List<Pair<String, String>> = listOf(
        "standard" to "Standard",
        "pioneer" to "Pioneer",
        "modern" to "Modern",
        "legacy" to "Legacy",
        "vintage" to "Vintage",
        "pauper" to "Pauper",
        "premodern" to "Premodern",
    )
    private val FORMAT_KEYS = FORMATS.map { it.first }

    /** A file that could not be used; the message is the short reason shown to the owner. */
    class PlayDataException(reason: String) : IllegalArgumentException(reason)

    class Source(val decks: Long, val events: Long, val weighted: Boolean)

    /** One card in a format's decks: its share in basis points and its rank there. */
    class Hit(val bp: Long, val rank: Long)

    class Format(val decks: Long, val events: Long, val sources: Map<String, Source>, val cards: Map<String, Hit>)

    /** A parsed file. [formats] keep [FORMATS] order. */
    class Pack(
        val asOf: String,
        val windowDays: Long,
        val builtAt: String?,
        val formats: Map<String, Format>,
        val basic: Set<String>,
        val names: Map<String, String>,
        val ambiguous: Set<String>,
    ) {
        /**
         * Name → (known, oracle_id) for scans with no oracle_id (only old scans need it):
         * pure in the name and this pack, so memoising never changes an answer.
         */
        internal val byName = ConcurrentHashMap<String, Pair<Boolean, String>>()

        /** Total decks in the file (every format), for summaries. */
        val totalDecks: Long get() = formats.values.sumOf { it.decks }
    }

    /** staple / played / fringe for a card seen in at least one deck. */
    fun tierForBp(bp: Long): String = when {
        bp >= STAPLE_BP -> "staple"
        bp >= PLAYED_BP -> "played"
        else -> "fringe"
    }

    /**
     * Python's `isinstance(v, int) and not isinstance(v, bool)`: a JSON INTEGER — never
     * 2689.0, true or "2689" ([MiniJson] keeps integers [Long] and floats [Double]).
     * An integer past 64 bits ([java.math.BigInteger]) is refused too (the reference
     * would take it; no real file carries one).
     */
    private fun int(v: Any?): Long? = v as? Long

    private fun malformed(): Nothing = throw PlayDataException("malformed")

    private fun parseFormat(f: Any?): Format {
        val m = f as? Map<*, *> ?: malformed()
        val decks = int(m["decks"]) ?: malformed()
        val events = int(m["events"]) ?: malformed()
        val srcIn = m["sources"] as? Map<*, *> ?: malformed()
        val cardsIn = m["cards"] as? Map<*, *> ?: malformed()
        val cards = HashMap<String, Hit>(cardsIn.size * 2)
        for ((oid, v) in cardsIn) {
            val l = v as? List<*> ?: malformed()
            if (l.size != 2) malformed()
            val bp = int(l[0]) ?: malformed()
            val rank = int(l[1]) ?: malformed()
            if (bp < 1 || rank < 1) malformed()
            cards[oid as String] = Hit(bp, rank)
        }
        val src = LinkedHashMap<String, Source>()
        for ((key, s) in srcIn) {
            val sm = s as? Map<*, *> ?: malformed()
            val sd = int(sm["decks"]) ?: malformed()
            val se = int(sm["events"]) ?: malformed()
            val w = sm["weighted"] as? Boolean ?: malformed()
            src[key as String] = Source(sd, se, w)
        }
        return Format(decks, events, src, cards)
    }

    /**
     * The published file → the [Pack] used for lookups. Throws [PlayDataException]
     * ("not JSON" | "not a play data file" | "unsupported version" | "malformed" |
     * "no formats"). Entries of the wrong shape are a malformed file, not skipped:
     * a half-read one must never replace the last good one. A format key this build
     * does not know is ignored (a newer file may carry more).
     */
    fun parse(text: String): Pack {
        val doc = try { MiniJson.parse(text) } catch (e: IllegalArgumentException) { throw PlayDataException("not JSON") }
        val obj = doc as? Map<*, *> ?: throw PlayDataException("not a play data file")
        if (obj["kind"] != PACK_KIND) throw PlayDataException("not a play data file")
        // An integer and exactly ours: a JSON 1.0 or "1" is not version 1.
        if (int(obj["version"]) != PACK_VERSION) throw PlayDataException("unsupported version")
        val formats = obj["formats"] as? Map<*, *> ?: malformed()
        val basic = obj["basic"] as? List<*> ?: malformed()
        val names = obj["names"] as? Map<*, *> ?: malformed()
        val ambiguous = obj["ambiguous"] as? List<*> ?: malformed()
        val asOf = obj["as_of"] as? String ?: malformed()
        val windowDays = int(obj["window_days"]) ?: malformed()
        if (!basic.all { it is String } || !ambiguous.all { it is String } || !names.values.all { it is String }) malformed()
        val parsed = LinkedHashMap<String, Format>()
        for (key in FORMAT_KEYS) {                     // our order; unknown keys ignored
            if (formats.containsKey(key)) parsed[key] = parseFormat(formats[key])
        }
        if (parsed.isEmpty()) throw PlayDataException("no formats")
        @Suppress("UNCHECKED_CAST")
        return Pack(
            asOf = asOf,
            windowDays = windowDays,
            builtAt = obj["built_at"] as? String,
            formats = parsed,
            basic = (basic as List<String>).toHashSet(),
            names = HashMap(names as Map<String, String>),
            ambiguous = (ambiguous as List<String>).toHashSet(),
        )
    }

    /** (known, oracle_id) from the name alone — play_for's fallback for scans with no oracle_id. */
    private fun resolveName(name: String?, pack: Pack): Pair<Boolean, String> {
        if (name == null) return false to ""
        pack.byName[name]?.let { return it }
        val keys = CardList.nameKeys(name)
        var known = keys.isNotEmpty()
        var oid = ""
        for (k in keys) {
            if (k in pack.ambiguous) { known = false; break }        // another oracle card shares this name
            val hit = pack.names[k]
            if (!hit.isNullOrEmpty()) { oid = hit; break }
        }
        val r = known to oid
        if (pack.byName.size < MEMO_LIMIT) pack.byName[name] = r
        return r
    }

    private const val MEMO_LIMIT = 50_000

    /**
     * {format: answer | null} for one card, every format in the pack, in [FORMATS]
     * order; null only when there is no pack. [popularity] is the stored popularity
     * summary (its `oracle_id` and its scan-time `formats`); [name] is the card's name
     * for scans that carry no oracle_id.
     */
    fun playFor(popularity: Any?, name: Any?, pack: Pack?): LinkedHashMap<String, Any?>? {
        if (pack == null) return null
        val pop = popularity as? Map<*, *> ?: emptyMap<String, Any?>()
        var oid = pop["oracle_id"] as? String ?: ""
        var known = oid.isNotEmpty()                  // do we know WHICH card this is?
        if (oid.isEmpty()) {
            val r = resolveName(name as? String, pack)
            known = r.first; oid = r.second
        }
        val out = LinkedHashMap<String, Any?>()
        val formats = pop["formats"] as? Map<*, *> ?: emptyMap<String, Any?>()
        for ((key, data) in pack.formats) {
            if (!known) { out[key] = null; continue }
            if (oid.isNotEmpty() && oid in pack.basic) { out[key] = linkedMapOf("tier" to "basic"); continue }
            val hit = if (oid.isNotEmpty()) data.cards[oid] else null
            if (hit != null) {                       // in a deck: membership beats a stale snapshot
                out[key] = linkedMapOf("tier" to tierForBp(hit.bp), "bp" to hit.bp, "rank" to hit.rank)
                continue
            }
            out[key] = when (formats[key]) {
                "legal", "restricted" -> linkedMapOf("tier" to "unplayed")
                "banned" -> linkedMapOf("tier" to "banned")
                "not_legal" -> linkedMapOf("tier" to "not_legal")
                else -> null                         // legality never recorded (old scan)
            }
        }
        return out
    }

    /** `src["name"]` when it is a string with something besides whitespace (Python's strip()). */
    private fun named(src: Any?): String? {
        val n = (src as? Map<*, *>)?.get("name") as? String ?: return null
        return if (CardList.strip(n).isNotEmpty()) n else null
    }

    /**
     * The play answers for the card the scan IS — read off the same printing the
     * pages' popularity pill reads (popOf: the selection's popularity, else the top
     * candidate's), so the chips always describe the same card.
     */
    fun scanPlay(scan: Map<String, Any?>, pack: Pack?): LinkedHashMap<String, Any?>? {
        val sel = scan["selection"] as? Map<*, *>
        val cands = scan["candidates"] as? List<*> ?: emptyList<Any?>()
        val top = cands.firstOrNull() as? Map<*, *>
        var src: Map<*, *>? = null
        for (s in listOf(sel, top)) {
            val p = s?.get("popularity") as? Map<*, *>
            if (p != null && p.isNotEmpty()) { src = s; break }
        }
        if (src != null) {
            val name = named(src) ?: named(sel) ?: named(top) ?: named(scan["card_read"])
            return playFor(src["popularity"], name, pack)
        }
        return playFor(null, named(sel) ?: named(top) ?: named(scan["card_read"]), pack)
    }

    /**
     * `play` on a scan read; [pack] null (no data yet) leaves it untouched — the same
     * map, no key, so the pages show nothing; with a pack every scan carries
     * {format: answer | null} for every format in it. Copy-on-write like CardList.flag.
     */
    fun flag(scan: Map<String, Any?>, pack: Pack?): Map<String, Any?> {
        if (pack == null) return scan
        val out = LinkedHashMap(scan)
        out["play"] = scanPlay(scan, pack)
        return out
    }

    /** GET /api/play-data: what the pages say about the data behind the chips. */
    fun status(pack: Pack?): Map<String, Any?> {
        if (pack == null) return linkedMapOf("loaded" to false)
        val formats = LinkedHashMap<String, Any?>()
        for ((k, f) in pack.formats) {
            val sources = LinkedHashMap<String, Any?>()
            for (s in f.sources.keys.sorted()) {
                val v = f.sources.getValue(s)
                sources[s] = linkedMapOf("decks" to v.decks, "events" to v.events, "weighted" to v.weighted)
            }
            formats[k] = linkedMapOf("decks" to f.decks, "events" to f.events, "sources" to sources, "played" to f.cards.size.toLong())
        }
        return linkedMapOf("loaded" to true, "as_of" to pack.asOf, "window_days" to pack.windowDays, "formats" to formats)
    }

    /** The format's label ("Modern"), or the key itself for one this build doesn't know. */
    fun label(key: String): String = FORMATS.firstOrNull { it.first == key }?.second ?: key
}
