package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson

/**
 * Port of server/store.py (Stage 3a): one row per scanned card — the read, the
 * art-ranked candidates, the chosen printing, the F2F price, and `included`.
 *
 * Rows are plain ordered maps shaped exactly like the Python `dict(row)`:
 * `id, created_at, updated_at, status, identified, error, card_read,
 * confidence, candidates, selection, f2f, included` — the JSON fields
 * decoded, `identified`/`included` as booleans. Implementations store the
 * JSON fields as TEXT and decode on every read, as SQLite does on the rig, so
 * a caller mutating a returned row never touches the stored one.
 *
 * [clock] stamps created_at / updated_at (`_now()`: ISO seconds, UTC); the
 * parity tests pass a counter. updated_at is stamped once per [update] call
 * with at least one field, never on a no-op — the same count as Python.
 */
interface ScanStore {
    fun create(identified: Boolean, cardRead: Map<String, Any?>?, confidence: Map<String, Any?>?,
               candidates: List<Any?>?, error: String? = null): MutableMap<String, Any?>

    /** Newest first (`ORDER BY id DESC`). */
    fun list(): List<MutableMap<String, Any?>>

    fun get(id: Long): MutableMap<String, Any?>?

    /**
     * `update_scan`: whitelisted columns only (status, identified, error,
     * card_read, confidence, candidates, selection, f2f, included); an
     * unknown key throws, like Python's ValueError. Returns the row (null
     * when it doesn't exist).
     */
    fun update(id: Long, fields: Map<String, Any?>): MutableMap<String, Any?>?

    /** True when a row was deleted. */
    fun delete(id: Long): Boolean

    fun count(): Int

    /** `included_selected`: selected, flagged for export, with a selection. */
    fun includedSelected(): List<MutableMap<String, Any?>> =
        list().filter { it["included"] == true && it["status"] == "selected" && isTruthy(it["selection"]) }

    companion object {
        val JSON_FIELDS = listOf("card_read", "confidence", "candidates", "selection", "f2f")
        val UPDATABLE = setOf("status", "identified", "error", "card_read", "confidence",
            "candidates", "selection", "f2f", "included", "flagged")

        /** `_encode`: JSON fields to text (null stays null), booleans to 0/1. */
        fun encode(field: String, value: Any?): Any? = when (field) {
            in JSON_FIELDS -> value?.let { MiniJson.stringify(it) }
            "identified", "included", "flagged" -> if (isTruthy(value)) 1L else 0L
            else -> value
        }

        /** `_row_to_dict` for one stored column value. */
        fun decode(field: String, raw: Any?): Any? = when (field) {
            in JSON_FIELDS -> if (raw == null || raw == "") null else MiniJson.parse(raw as String)
            "identified", "included", "flagged" -> (raw as Number).toLong() != 0L
            else -> raw
        }

        val COLUMNS = listOf("id", "created_at", "updated_at", "status", "identified", "error",
            "card_read", "confidence", "candidates", "selection", "f2f", "included", "flagged")

        internal fun isTruthy(v: Any?): Boolean = when (v) {
            null -> false
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty()
            is Collection<*> -> v.isNotEmpty()
            is Map<*, *> -> v.isNotEmpty()
            else -> true
        }
    }
}

/**
 * In-memory [ScanStore] for tests and the JVM parity replay: each row is kept
 * ENCODED (JSON fields as text, booleans 0/1) and decoded on read, exactly as
 * the SQLite implementations behave.
 */
class MemoryScanStore(private val clock: () -> String = ::isoNow) : ScanStore {
    private val rows = sortedMapOf<Long, MutableMap<String, Any?>>()
    private var nextId = 1L

    @Synchronized
    override fun create(identified: Boolean, cardRead: Map<String, Any?>?, confidence: Map<String, Any?>?,
                        candidates: List<Any?>?, error: String?): MutableMap<String, Any?> {
        val now = clock()
        val id = nextId++
        rows[id] = linkedMapOf(
            "id" to id, "created_at" to now, "updated_at" to now, "status" to "candidates",
            "identified" to ScanStore.encode("identified", identified), "error" to error,
            "card_read" to MiniJson.stringify(cardRead ?: emptyMap<String, Any?>()),
            "confidence" to MiniJson.stringify(confidence ?: emptyMap<String, Any?>()),
            "candidates" to MiniJson.stringify(candidates ?: emptyList<Any?>()),
            "selection" to null, "f2f" to null, "included" to 1L, "flagged" to 0L,
        )
        return get(id)!!
    }

    @Synchronized
    override fun list(): List<MutableMap<String, Any?>> = rows.keys.reversed().map { decodeRow(rows[it]!!) }

    @Synchronized
    override fun get(id: Long): MutableMap<String, Any?>? = rows[id]?.let(::decodeRow)

    @Synchronized
    override fun update(id: Long, fields: Map<String, Any?>): MutableMap<String, Any?>? {
        for (k in fields.keys) require(k in ScanStore.UPDATABLE) { "cannot update column '$k'" }
        if (fields.isEmpty()) return get(id)
        val now = clock()                        // stamped even when the row is gone, like Python
        val row = rows[id] ?: return null
        for ((k, v) in fields) row[k] = ScanStore.encode(k, v)
        row["updated_at"] = now
        return get(id)
    }

    @Synchronized
    override fun delete(id: Long): Boolean = rows.remove(id) != null

    @Synchronized
    override fun count(): Int = rows.size

    private fun decodeRow(stored: Map<String, Any?>): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (c in ScanStore.COLUMNS) out[c] = ScanStore.decode(c, stored[c])
        return out
    }
}

/** `datetime.now(timezone.utc).isoformat(timespec="seconds")` — "2026-09-30T12:00:00+00:00". */
fun isoNow(): String = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
    .format(ISO_SECONDS_UTC)

private val ISO_SECONDS_UTC: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+00:00'")
