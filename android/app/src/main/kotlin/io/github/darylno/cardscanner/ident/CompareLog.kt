package io.github.darylno.cardscanner.ident

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One side's answer, reduced to what Compare mode compares: the top NAME
 * (`card_read.name` — present for a best guess too, null for no card / no
 * match), the top PRINTING (the auto-picked selection on the server, else
 * `candidates[0]`), its confidence, and the auto-pick / OCR flags.
 */
data class SideSummary(
    val identified: Boolean,
    val noCard: Boolean,
    val name: String?,
    val printingId: String?,
    val set: String?,
    val collectorNumber: String?,
    /** `confidence.name` ("high"/"medium"/"low"), null when absent. */
    val confidence: String?,
    /** Server: the row came back `selection.auto_picked`. Phone: the server's grounds would auto-pick. */
    val autoPick: Boolean,
    /** `candidates[0].ocr_confirmed`. */
    val ocrConfirmed: Boolean,
    val candidates: Int,
    val error: String?,
) {
    fun label(): String = when {
        noCard -> "no card"
        name == null -> "no match"
        else -> name + (set?.let { " · ${it.uppercase()} #${collectorNumber ?: "?"}" } ?: "")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("identified", identified)
        put("no_card", noCard)
        put("name", name ?: JSONObject.NULL)
        put("printing", printingId ?: JSONObject.NULL)
        put("set", set ?: JSONObject.NULL)
        put("cn", collectorNumber ?: JSONObject.NULL)
        put("confidence", confidence ?: JSONObject.NULL)
        put("auto_pick", autoPick)
        put("ocr_confirmed", ocrConfirmed)
        put("candidates", candidates)
        put("error", error ?: JSONObject.NULL)
    }

    companion object {
        private fun JSONObject.str(k: String): String? =
            if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

        fun fromJson(o: JSONObject) = SideSummary(
            identified = o.optBoolean("identified"), noCard = o.optBoolean("no_card"),
            name = o.str("name"), printingId = o.str("printing"), set = o.str("set"),
            collectorNumber = o.str("cn"), confidence = o.str("confidence"),
            autoPick = o.optBoolean("auto_pick"), ocrConfirmed = o.optBoolean("ocr_confirmed"),
            candidates = o.optInt("candidates"), error = o.str("error"),
        )

        /**
         * A pipeline/server result object ([o] as org.json). [autoPick] is
         * supplied by the caller: the server's row says it (`selection.auto_picked`),
         * the phone's is `IdentifyDecisions.shouldAutoPick`.
         */
        fun fromResult(o: JSONObject, autoPick: Boolean): SideSummary {
            val noCard = o.optBoolean("no_card")
            val read = o.optJSONObject("card_read")
            val name = read?.str("name")
            val cands = o.optJSONArray("candidates") ?: JSONArray()
            val first = if (cands.length() > 0) cands.optJSONObject(0) else null
            val sel = o.optJSONObject("selection")
            val useSel = autoPick && sel != null && sel.str("scryfall_id") != null
            return SideSummary(
                identified = o.optBoolean("identified"),
                noCard = noCard,
                name = if (noCard) null else name,
                printingId = if (useSel) sel!!.str("scryfall_id") else first?.str("id"),
                set = if (useSel) sel!!.str("set") else first?.str("set"),
                collectorNumber = if (useSel) sel!!.str("collector_number") else first?.str("collector_number"),
                confidence = o.optJSONObject("confidence")?.str("name"),
                autoPick = autoPick,
                ocrConfirmed = first?.optBoolean("ocr_confirmed") == true,
                candidates = cands.length(),
                error = o.str("error"),
            )
        }
    }
}

/**
 * One comparison: the server's answer to a capture vs the phone's answer to
 * the SAME primary JPEG. [serverUsedFallback] marks rows where the server
 * needed the 3 raw frames (its multi-frame retry) — the phone only ever sees
 * the primary, so those rows compare different inputs.
 */
data class CompareRow(
    val at: Long,
    val jobId: String,
    /** "auto" (after an upload) or "test" (Diagnostics → Test on last capture). */
    val source: String,
    val server: SideSummary,
    val phone: SideSummary,
    val serverUsedFallback: Boolean,
    /** Phone per-stage ms: decode, blank, identify, printings, ranking, ocr, total. */
    val timingsMs: Map<String, Long>,
    /**
     * What the phone's OCR read (canonical words), null when it didn't run —
     * so a wrong confirmation is diagnosed from the text, not guessed at.
     */
    val phoneOcrText: String? = null,
) {
    /** Same top name (both "no card"/"no match" counts as agreeing). */
    val nameAgree: Boolean get() = server.name == phone.name && server.noCard == phone.noCard
    /** Same top printing (both none counts as agreeing). */
    val printingAgree: Boolean get() = server.printingId == phone.printingId
    val autoPickAgree: Boolean get() = server.autoPick == phone.autoPick
    val agree: Boolean get() = nameAgree && printingAgree && autoPickAgree

    /** What disagrees, for the list ("name", "printing", "auto-pick"). */
    fun disagreements(): List<String> = buildList {
        if (!nameAgree) add("name")
        if (!printingAgree) add("printing")
        if (!autoPickAgree) add("auto-pick")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at)
        put("job", jobId)
        put("source", source)
        put("server", server.toJson())
        put("phone", phone.toJson())
        put("server_fallback", serverUsedFallback)
        put("ms", JSONObject(timingsMs as Map<*, *>))
        put("phone_ocr_text", phoneOcrText ?: JSONObject.NULL)
        put("name_agree", nameAgree)
        put("printing_agree", printingAgree)
        put("auto_pick_agree", autoPickAgree)
    }

    companion object {
        fun fromJson(o: JSONObject): CompareRow {
            val ms = LinkedHashMap<String, Long>()
            o.optJSONObject("ms")?.let { m -> m.keys().forEach { k -> ms[k] = m.optLong(k) } }
            return CompareRow(
                at = o.optLong("at"), jobId = o.optString("job"), source = o.optString("source", "auto"),
                server = SideSummary.fromJson(o.getJSONObject("server")),
                phone = SideSummary.fromJson(o.getJSONObject("phone")),
                serverUsedFallback = o.optBoolean("server_fallback"), timingsMs = ms,
                phoneOcrText = if (o.has("phone_ocr_text") && !o.isNull("phone_ocr_text")) o.optString("phone_ocr_text") else null,
            )
        }
    }
}

/** The report's numbers over a set of rows. */
data class CompareSummary(
    val n: Int,
    val nameAgreePct: Double?,
    val printingAgreePct: Double?,
    val autoPickAgreePct: Double?,
    val medianTotalMs: Long?,
    val p95TotalMs: Long?,
    val phoneOcrRate: Double?,
    val serverOcrRate: Double?,
    val phoneAutoPickRate: Double?,
    val serverAutoPickRate: Double?,
    val serverFallbackRows: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("n", n)
        put("name_agree_pct", nameAgreePct ?: JSONObject.NULL)
        put("printing_agree_pct", printingAgreePct ?: JSONObject.NULL)
        put("auto_pick_agree_pct", autoPickAgreePct ?: JSONObject.NULL)
        put("median_total_ms", medianTotalMs ?: JSONObject.NULL)
        put("p95_total_ms", p95TotalMs ?: JSONObject.NULL)
        put("ocr_confirmed_rate_phone", phoneOcrRate ?: JSONObject.NULL)
        put("ocr_confirmed_rate_server", serverOcrRate ?: JSONObject.NULL)
        put("auto_pick_rate_phone", phoneAutoPickRate ?: JSONObject.NULL)
        put("auto_pick_rate_server", serverAutoPickRate ?: JSONObject.NULL)
        put("server_fallback_rows", serverFallbackRows)
    }

    companion object {
        private fun pct(k: Int, n: Int): Double? = if (n == 0) null else Math.round(1000.0 * k / n) / 10.0

        /** Nearest-rank percentile (p in 0..100) of [sorted]. */
        fun percentile(sorted: List<Long>, p: Int): Long? {
            if (sorted.isEmpty()) return null
            val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        fun of(rows: List<CompareRow>): CompareSummary {
            val n = rows.size
            val totals = rows.mapNotNull { it.timingsMs["total"] }.sorted()
            return CompareSummary(
                n = n,
                nameAgreePct = pct(rows.count { it.nameAgree }, n),
                printingAgreePct = pct(rows.count { it.printingAgree }, n),
                autoPickAgreePct = pct(rows.count { it.autoPickAgree }, n),
                medianTotalMs = percentile(totals, 50),
                p95TotalMs = percentile(totals, 95),
                phoneOcrRate = pct(rows.count { it.phone.ocrConfirmed }, n),
                serverOcrRate = pct(rows.count { it.server.ocrConfirmed }, n),
                phoneAutoPickRate = pct(rows.count { it.phone.autoPick }, n),
                serverAutoPickRate = pct(rows.count { it.server.autoPick }, n),
                serverFallbackRows = rows.count { it.serverUsedFallback },
            )
        }
    }
}

/**
 * The last [capacity] comparison rows as JSON lines in [file] (oldest first),
 * rewritten atomically on every add. Thread-safe. Unreadable lines are skipped.
 */
class CompareLog(private val file: File, private val capacity: Int = CAPACITY) {
    fun interface Listener { fun onChanged() }

    private val rows = ArrayList<CompareRow>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    init {
        try {
            if (file.isFile) file.forEachLine { line ->
                if (line.isNotBlank()) try { rows += CompareRow.fromJson(JSONObject(line)) } catch (_: Exception) { }
            }
        } catch (_: Exception) {
        }
        while (rows.size > capacity) rows.removeAt(0)
    }

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun add(row: CompareRow) {
        synchronized(rows) {
            rows += row
            while (rows.size > capacity) rows.removeAt(0)
            persist()
        }
        notifyChanged()
    }

    fun clear() {
        synchronized(rows) { rows.clear(); persist() }
        notifyChanged()
    }

    /** Tell listeners something compare-related changed (status line, pack) without a new row. */
    fun notifyChanged() {
        listeners.forEach { runCatching { it.onChanged() } }
    }

    /** Oldest first. */
    fun rows(): List<CompareRow> = synchronized(rows) { rows.toList() }

    fun summary(): CompareSummary = CompareSummary.of(rows())

    /** The compact report "Copy report" puts on the clipboard. */
    fun report(meta: JSONObject = JSONObject()): JSONObject {
        val all = rows()
        return JSONObject().apply {
            put("probe", "identify-compare-stage2")
            meta.keys().forEach { k -> put(k, meta.get(k)) }
            put("summary", CompareSummary.of(all).toJson())
            put("disagreements", JSONArray(all.filter { !it.agree }.takeLast(REPORT_DISAGREEMENTS).map { it.toJson() }))
            put("timings_ms", JSONArray(all.takeLast(REPORT_TIMINGS).map { JSONObject(it.timingsMs as Map<*, *>) }))
        }
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w -> for (r in rows) { w.write(r.toJson().toString()); w.write("\n") } }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // diagnostics only: never let a full disk break scanning
        }
    }

    companion object {
        const val CAPACITY = 200
        const val REPORT_DISAGREEMENTS = 50
        const val REPORT_TIMINGS = 50
    }
}
