package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.Py
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Port of server/export.py (Stage 3d) — PORT, never re-tune; golden-tested
 * against the real server (`api/export.json`, scripts/export_api_fixtures.py):
 *
 *  - TXT: the Mana Exchange mass-entry lines `Qty SET Number Condition Finish`,
 *    identical printing+condition+finish rows summed, first-seen (newest) order;
 *  - CSV: the owner's column layout from the desktop builder, combined or one
 *    line per scan, Python csv quoting (QUOTE_MINIMAL, \r\n, a lone empty
 *    field written as ""), spreadsheet formulas neutralised, money with
 *    Python's exact-binary 2-decimal rounding (2.675 → "2.67").
 */
object Export {
    // ── TXT ─────────────────────────────────────────────────────────────────

    private fun s(v: Any?): String = if (Py.truthy(v)) Py.str(v) else ""
    private fun or(v: Any?, d: String): String = if (Py.truthy(v)) Py.str(v) else d
    private fun qty(sel: Map<String, Any?>): Long =
        if (Py.truthy(sel["quantity"])) PhoneApi.pyInt(sel["quantity"]) ?: throw IllegalArgumentException("quantity") else 1L

    /** `selection_to_line`, or null where Python raises (the row is skipped). */
    fun selectionToLine(sel: Map<String, Any?>): String? {
        val q = if (Py.truthy(sel["quantity"])) PhoneApi.pyInt(sel["quantity"]) ?: return null else 1L
        val setCode = (if (Py.truthy(sel["set"])) Py.str(sel["set"]) else s(sel["set_code"])).uppercase()
        val collector = s(sel["collector_number"]).trim { it.isWhitespace() }
        val condition = or(sel["condition"], "NM").uppercase()
        val finish = or(sel["finish"], "Non-Foil").trim { it.isWhitespace() }.replace(" ", "-")
        if (setCode.isEmpty() || collector.isEmpty()) return null
        val norm = mapOf("NONFOIL" to "Non-Foil", "NON-FOIL" to "Non-Foil", "FOIL" to "Foil",
            "ETCHED" to "Etched")[finish.uppercase()] ?: finish
        return "$q $setCode $collector $condition $norm"
    }

    /** `build_mx_export`. */
    fun buildMxExport(scans: List<Map<String, Any?>>): String {
        val totals = LinkedHashMap<List<String>, Long>()
        for (scan in scans) {
            val sel = Py.map(scan["selection"])
            if (!Py.truthy(sel)) continue
            val parts = selectionToLine(sel!!)?.split(" ") ?: continue
            if (parts.size != 5) continue                   // Python's 5-way unpack raises → skipped
            val key = parts.subList(1, 5)
            totals[key] = (totals[key] ?: 0L) + parts[0].toLong()
        }
        val lines = totals.map { (k, q) -> "$q ${k.joinToString(" ")}" }
        return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
    }

    // ── CSV ─────────────────────────────────────────────────────────────────

    private val MX_TO_F2F = mapOf("NM" to listOf("NM", "PL"), "LP" to listOf("PL", "NM"),
        "MP" to listOf("MP", "PL", "NM"), "HP" to listOf("HP", "PL", "NM"), "DMG" to listOf("DMG", "HP", "PL", "NM"))

    private fun pyFloat(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().toDouble()
        else -> throw IllegalArgumentException("float($v)")
    }

    /** `f2f_price`: the condition's own listing, then the nearest, else the cheapest. */
    fun f2fPrice(scan: Map<String, Any?>): Double? {
        val f2f = Py.map(scan["f2f"])
        val conds = Py.map(if (Py.truthy(f2f)) f2f!!["conditions"] else null)
        if (!Py.truthy(conds)) return null
        val sel = Py.map(scan["selection"])
        val cond = or(if (Py.truthy(sel)) sel!!["condition"] else null, "NM").uppercase()
        for (k in MX_TO_F2F[cond] ?: listOf("NM")) conds!![k]?.let { return pyFloat(it) }
        return pyFloat(conds!!.values.reduce { a, b -> if (Py.compareNum(b, a) < 0) b else a })
    }

    /** `f"{v:.2f}"` — Python rounds the EXACT binary value half-even (2.675 → 2.67). */
    fun money(v: Double?): String =
        if (v == null) "" else BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toPlainString()

    private fun finish(sel: Map<String, Any?>): String {
        val f = or(sel["finish"], "Non-Foil").trim { it.isWhitespace() }
        return mapOf("NONFOIL" to "Non-Foil", "NON-FOIL" to "Non-Foil", "NON FOIL" to "Non-Foil",
            "FOIL" to "Foil", "ETCHED" to "Etched")[f.uppercase()] ?: f
    }

    private fun pop(sel: Map<String, Any?>): String {
        val p = if (Py.truthy(sel["popularity"])) sel["popularity"] else emptyMap<String, Any?>()
        return if (p is Map<*, *>) s(p["label"]) else ""
    }

    private fun f2fUrl(scan: Map<String, Any?>): String {
        val f = Py.map(scan["f2f"])
        val u = s(if (Py.truthy(f)) f!!["url"] else null)
        return if (u.isNotEmpty() && u.startsWith("/")) "https://www.facetofacegames.com$u" else u
    }

    private class Field(val header: String, val value: (Map<String, Any?>, Map<String, Any?>, Long) -> String)

    /** `CSV_FIELDS` — keys are the saved-layout contract; order is the builder's. */
    private val FIELDS: LinkedHashMap<String, Field> = linkedMapOf(
        "quantity" to Field("Quantity") { _, _, q -> q.toString() },
        "name" to Field("Name") { _, sel, _ -> s(sel["name"]) },
        "set_code" to Field("Set Code") { _, sel, _ -> s(sel["set"]).uppercase() },
        "set_name" to Field("Set Name") { _, sel, _ -> s(sel["set_name"]) },
        "collector_number" to Field("Collector Number") { _, sel, _ -> s(sel["collector_number"]) },
        "condition" to Field("Condition") { _, sel, _ -> or(sel["condition"], "NM").uppercase() },
        "finish" to Field("Finish") { _, sel, _ -> finish(sel) },
        "foil" to Field("Foil") { _, sel, _ -> if (finish(sel) != "Non-Foil") "Yes" else "No" },
        "price" to Field("Price") { sc, _, _ -> money(f2fPrice(sc)) },
        "total" to Field("Total") { sc, _, q -> money(f2fPrice(sc)?.let { it * q }) },
        "popularity" to Field("Popularity") { _, sel, _ -> pop(sel) },
        "scryfall_id" to Field("Scryfall ID") { _, sel, _ -> s(sel["scryfall_id"]) },
        "scan_id" to Field("Scan #") { sc, _, _ -> s(sc["id"]) },
        "scanned_at" to Field("Scanned At") { sc, _, _ -> s(sc["created_at"]) },
        "auto_picked" to Field("Auto Picked") { _, sel, _ -> if (Py.truthy(sel["auto_picked"])) "Yes" else "No" },
        "f2f_url" to Field("F2F Link") { sc, _, _ -> f2fUrl(sc) },
    )

    val DELIMITERS = linkedMapOf("comma" to ",", "semicolon" to ";", "tab" to "\t")

    fun defaultLayout(): MutableMap<String, Any?> = linkedMapOf(
        "columns" to listOf("quantity", "name", "set_code", "set_name", "collector_number", "condition",
            "finish", "price").map { linkedMapOf<String, Any?>("field" to it, "header" to FIELDS[it]!!.header) }
            .toMutableList<Any?>(),
        "header" to true, "delimiter" to "comma", "combine" to true)

    /** The builder's field list (`/api/export/layout` → fields). */
    fun fields(): List<Map<String, Any?>> = FIELDS.map { (k, f) -> linkedMapOf("field" to k, "header" to f.header) }

    class LayoutError(message: String) : Exception(message)

    /** `normalize_layout`; throws [LayoutError] with the message the page shows. */
    fun normalizeLayout(raw: Any?): MutableMap<String, Any?> {
        val m = raw as? Map<*, *> ?: throw LayoutError("layout must be an object")
        val cols = m["columns"] as? List<*>
        if (cols == null || cols.isEmpty()) throw LayoutError("add at least one column")
        if (cols.size > 40) throw LayoutError("too many columns (40 max)")
        val out = ArrayList<Any?>()
        for (c in cols) {
            val field = (c as? Map<*, *>)?.get("field")
            if (c !is Map<*, *> || field !is String || field !in FIELDS) {
                throw LayoutError("unknown column: ${repr(if (c is Map<*, *>) c["field"] else c)}")
            }
            val h = c["header"]
            val header = (if (h != null) pyStr(h) else "").trim { it.isWhitespace() }.let { take(it, 80) }
            out += linkedMapOf<String, Any?>("field" to field, "header" to header.ifEmpty { FIELDS[field]!!.header })
        }
        val delim = if (m.containsKey("delimiter")) m["delimiter"] else "comma"
        if (delim !is String || delim !in DELIMITERS) throw LayoutError("unknown delimiter: ${repr(delim)}")
        return linkedMapOf("columns" to out,
            "header" to (if (m.containsKey("header")) Py.truthy(m["header"]) else true),
            "delimiter" to delim,
            "combine" to (if (m.containsKey("combine")) Py.truthy(m["combine"]) else true))
    }

    /** `build_csv` (layout already normalised). */
    @Suppress("UNCHECKED_CAST")
    fun buildCsv(scans: List<Map<String, Any?>>, layout: Map<String, Any?>, limit: Int? = null): String {
        data class Row(val scan: Map<String, Any?>, val sel: Map<String, Any?>, val q: Long)
        val rows = ArrayList<Row>()
        val index = HashMap<List<String>, Int>()
        val combine = layout["combine"] == true
        for (scan in scans) {
            val sel = Py.map(scan["selection"])
            if (!Py.truthy(sel)) continue
            val q = qty(sel!!)
            if (combine) {
                val key = listOf(s(sel["set"]).uppercase(), s(sel["collector_number"]),
                    or(sel["condition"], "NM").uppercase(), finish(sel))
                val at = index[key]
                if (at != null) {
                    var r0 = rows[at]
                    // Newest copy stays the row; its price may not have landed yet.
                    if (f2fPrice(r0.scan) == null && f2fPrice(scan) != null) {
                        r0 = r0.copy(scan = LinkedHashMap(r0.scan).apply { put("f2f", scan["f2f"]) })
                    }
                    rows[at] = r0.copy(q = r0.q + q)
                    continue
                }
                index[key] = rows.size
            }
            rows += Row(scan, sel, q)
        }
        val delim = DELIMITERS[layout["delimiter"] as String]!!
        val cols = layout["columns"] as List<Map<String, Any?>>
        val out = StringBuilder()
        fun write(cells: List<String>) {
            if (cells.size == 1 && cells[0].isEmpty()) { out.append("\"\"\r\n"); return }
            cells.joinTo(out, delim) { quote(it, delim) }
            out.append("\r\n")
        }
        if (layout["header"] == true) write(cols.map { cell(it["header"] as String) })
        for (r in if (limit != null) rows.take(limit) else rows) {
            write(cols.map { cell(FIELDS[it["field"] as String]!!.value(r.scan, r.sel, r.q)) })
        }
        return out.toString()
    }

    private val FORMULA = Regex("^[=+@\t\r]")
    private fun cell(v: String) = if (FORMULA.containsMatchIn(v)) "'$v" else v

    /** csv QUOTE_MINIMAL: quote on the delimiter, a quote, CR or LF; quotes doubled. */
    private fun quote(v: String, delim: String): String =
        if (v.contains(delim) || v.contains('"') || v.contains('\n') || v.contains('\r'))
            "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** `s[:n]` by code points, like a Python str slice. */
    private fun take(s: String, n: Int): String =
        if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    /** `str(v)` for a JSON value. */
    private fun pyStr(v: Any?): String = if (v is Map<*, *> || v is List<*>) repr(v) else Py.str(v)

    /** `repr(v)` for a JSON value. */
    fun repr(v: Any?): String = when (v) {
        null -> "None"
        is Boolean -> if (v) "True" else "False"
        is String -> {
            val q = if (v.contains('\'') && !v.contains('"')) '"' else '\''
            val body = v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                .let { if (q == '\'') it.replace("'", "\\'") else it }
            "$q$body$q"
        }
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { "${repr(it.key)}: ${repr(it.value)}" }
        is List<*> -> v.joinToString(", ", "[", "]") { repr(it) }
        else -> Py.str(v)
    }

    // ── endpoints ───────────────────────────────────────────────────────────

    /**
     * `/api/export`, `/api/export/layout` (GET/PUT), `/api/export/preview`,
     * `/api/export.csv`; null for anything else.
     */
    fun handle(req: ApiRequest, store: ScanStore, layouts: LayoutStore): ApiResponse? {
        fun body(): Map<String, Any?>? = req.body?.let {
            try { Py.map(MiniJson.parse(String(it, Charsets.UTF_8))) } catch (e: MiniJson.ParseException) { null }
        }
        fun unprocessable() = ApiResponse.json(422, mapOf("detail" to "body must be a JSON object"))
        return when {
            req.path == "/api/export" && req.method == "GET" ->
                ApiResponse(200, "text/plain; charset=utf-8",
                    buildMxExport(store.includedSelected()).toByteArray(Charsets.UTF_8),
                    mapOf("Content-Disposition" to "attachment; filename=cards.txt"))
            req.path == "/api/export/layout" && req.method == "GET" ->
                ApiResponse.json(200, linkedMapOf("layout" to layouts.load(), "default" to defaultLayout(),
                    "fields" to fields()))
            req.path == "/api/export/layout" && req.method == "PUT" -> {
                val b = body() ?: return unprocessable()
                try {
                    val layout = normalizeLayout(b["layout"])
                    layouts.save(layout)
                    ApiResponse.json(200, mapOf("layout" to layout))
                } catch (e: LayoutError) {
                    ApiResponse.json(400, mapOf("error" to e.message))
                }
            }
            req.path == "/api/export/preview" && req.method == "POST" -> {
                val b = body() ?: return unprocessable()
                try {
                    val layout = normalizeLayout(b["layout"])
                    val scans = store.includedSelected()
                    ApiResponse.json(200, linkedMapOf("csv" to buildCsv(scans, layout, limit = 8),
                        "rows" to buildCsv(scans, LinkedHashMap(layout).apply { put("header", false) })
                            .let { (it.length - it.replace("\r\n", "").length) / 2 }.toLong()))
                } catch (e: LayoutError) {
                    ApiResponse.json(400, mapOf("error" to e.message))
                }
            }
            req.path == "/api/export.csv" && req.method == "GET" ->
                // BOM: Excel otherwise reads UTF-8 accents (Lórien, Æther) as mojibake.
                ApiResponse(200, "text/csv; charset=utf-8",
                    ("\uFEFF" + buildCsv(store.includedSelected(), layouts.load())).toByteArray(Charsets.UTF_8),
                    mapOf("Content-Disposition" to "attachment; filename=cards.csv"))
            else -> null
        }
    }
}

/**
 * `LayoutStore`: the owner's saved CSV layout, a JSON file beside the scan
 * database ([file] null keeps it in memory). A missing or bad file reads as
 * the default layout.
 */
class LayoutStore(private val file: File?) {
    @Volatile private var mem: Map<String, Any?>? = null

    @Suppress("UNCHECKED_CAST")
    fun load(): MutableMap<String, Any?> {
        if (file == null) {
            return (mem?.let { MiniJson.parse(MiniJson.stringify(it)) as MutableMap<String, Any?> }
                ?: Export.defaultLayout())
        }
        return try {
            Export.normalizeLayout(MiniJson.parse(file.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            Export.defaultLayout()
        }
    }

    @Synchronized
    fun save(layout: Map<String, Any?>) {
        if (file == null) { mem = layout; return }
        file.parentFile?.mkdirs()
        val tmp = File(file.path.removeSuffix(".json") + ".tmp")
        tmp.writeText(MiniJson.stringify(layout), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
