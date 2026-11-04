package io.github.darylno.cardscanner.phoneserver

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.server.ScanStore
import io.github.darylno.cardscanner.core.server.isoNow
import java.io.File

/**
 * [ScanStore] on the phone's own SQLite (Stage 3a) — the rig's `scans` table,
 * column for column (server/store.py), so a row reads back exactly as the
 * Python store's `dict(row)`. JSON fields are TEXT, decoded on every read.
 * The golden API replay runs against this class too (PhoneServerTest); the
 * one-time List re-price on open ([REPRICE_SETS]) is SqliteScanStoreTest.
 */
class SqliteScanStore(file: File, private val clock: () -> String = ::isoNow) : ScanStore {
    private val db: SQLiteDatabase
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(SCHEMA)
        // Databases made before the flag existed (the Stage 3 preview): add it, as store.py does.
        val cols = db.rawQuery("PRAGMA table_info(scans)", null).use { c ->
            val out = HashSet<String>(); while (c.moveToNext()) out += c.getString(1); out
        }
        if ("flagged" !in cols) db.execSQL("ALTER TABLE scans ADD COLUMN flagged INTEGER NOT NULL DEFAULT 0")
        migrate()
    }

    /**
     * store.py's `_migrate`: data migrations by PRAGMA user_version, each in ONE
     * transaction with its stamp, so each runs exactly once (a fresh file runs them
     * on an empty table and is stamped too). A failure rolls back and leaves the
     * version as it was — retried on the next open; the store still opens (it holds
     * the only copy of the scans).
     */
    private fun migrate() {
        if (db.version >= 1) return
        try {
            var reset = 0 to 0
            db.beginTransaction()                    // EXCLUSIVE; re-read the stamp inside it
            try {
                if (db.version < 1) {
                    reset = repriceSets(db, REPRICE_SETS)
                    db.version = 1
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (reset.first > 0 || reset.second > 0) {
                DebugLog.global.i("store", "List re-price: ${reset.first} selection(s) and " +
                    "${reset.second} candidate print(s) owed a price again")
            }
        } catch (e: Exception) {
            DebugLog.global.w("store", "List re-price failed, retried on the next open: $e")
        }
    }

    override fun create(identified: Boolean, cardRead: Map<String, Any?>?, confidence: Map<String, Any?>?,
                        candidates: List<Any?>?, error: String?): MutableMap<String, Any?> {
        val id = synchronized(lock) {
            val now = clock()
            db.insertOrThrow("scans", null, ContentValues().apply {
                put("created_at", now); put("updated_at", now); put("status", "candidates")
                put("identified", if (identified) 1L else 0L)
                if (error == null) putNull("error") else put("error", error)
                put("card_read", MiniJson.stringify(cardRead ?: emptyMap<String, Any?>()))
                put("confidence", MiniJson.stringify(confidence ?: emptyMap<String, Any?>()))
                put("candidates", MiniJson.stringify(candidates ?: emptyList<Any?>()))
            })
        }
        return get(id)!!
    }

    override fun list(): List<MutableMap<String, Any?>> = synchronized(lock) {
        db.rawQuery("SELECT * FROM scans ORDER BY id DESC", null).use { c ->
            val out = ArrayList<MutableMap<String, Any?>>(c.count)
            while (c.moveToNext()) out += row(c)
            out
        }
    }

    override fun get(id: Long): MutableMap<String, Any?>? = synchronized(lock) {
        db.rawQuery("SELECT * FROM scans WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) row(c) else null
        }
    }

    override fun update(id: Long, fields: Map<String, Any?>): MutableMap<String, Any?>? {
        for (k in fields.keys) require(k in ScanStore.UPDATABLE) { "cannot update column '$k'" }
        if (fields.isEmpty()) return get(id)
        synchronized(lock) {
            val cv = ContentValues()
            for ((k, v) in fields) {
                when (val enc = ScanStore.encode(k, v)) {
                    null -> cv.putNull(k)
                    is Long -> cv.put(k, enc)
                    is String -> cv.put(k, enc)
                    else -> cv.put(k, enc.toString())
                }
            }
            cv.put("updated_at", clock())
            db.update("scans", cv, "id = ?", arrayOf(id.toString()))
        }
        return get(id)
    }

    override fun delete(id: Long): Boolean = synchronized(lock) {
        db.delete("scans", "id = ?", arrayOf(id.toString())) > 0
    }

    override fun count(): Int = synchronized(lock) { DatabaseUtils.queryNumEntries(db, "scans").toInt() }

    fun close() = synchronized(lock) { db.close() }

    private fun row(c: Cursor): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (col in ScanStore.COLUMNS) {
            val i = c.getColumnIndexOrThrow(col)
            val raw: Any? = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                else -> c.getString(i)
            }
            out[col] = ScanStore.decode(col, raw)
        }
        return out
    }

    companion object {
        /**
         * The one-time re-price (PRAGMA user_version 0 → 1, release 1.1.17) — store.py's
         * `REPRICE_SETS`. The F2F matcher changed for exactly these sets in that release
         * (F2fPricer's SKU set rules and the narrowed set-name fallback): The List (plst —
         * Scryfall folded mb1/fmb1 into it), the Unfinity List (ulst), Mystery Booster 2
         * (mb2) and the Mystery Booster playtest cards (cmb1, cmb2). Before it a List print
         * was never found (an empty "searched" marker for good) or filed ANOTHER card's
         * price through the name-blind fallback (Mortuary [STH-66] → Buried Alive's $4.99),
         * and the sweep never re-asks an answer it recorded. So those answers are dropped
         * once and the sweep prices them again; every other set's answer came from the same
         * matching before and after, and is kept.
         */
        val REPRICE_SETS = setOf("plst", "ulst", "mb2", "cmb1", "cmb2")

        /**
         * store.py's `_reprice_sets`: drop the F2F answers recorded for printings of
         * [sets], in place — a selection's `f2f` column → NULL, a candidate's
         * `f2f_conditions` key → removed (PriceSweep reads a missing key exactly like
         * null: both are owed). Writes only those two columns, only on rows that change:
         * updated_at, status, the selection and everything else stay as they were. A row
         * whose JSON does not decode is left alone. Returns (selections, candidates)
         * reset. The caller holds the transaction.
         */
        internal fun repriceSets(db: SQLiteDatabase, sets: Set<String>): Pair<Int, Int> {
            var nSel = 0
            var nCand = 0
            data class Raw(val id: Long, val sel: String?, val f2fNull: Boolean, val cands: String?)
            val rows = db.rawQuery("SELECT id, selection, f2f IS NULL, candidates FROM scans", null).use { c ->
                val out = ArrayList<Raw>()
                while (c.moveToNext()) out += Raw(c.getLong(0), if (c.isNull(1)) null else c.getString(1),
                    c.getLong(2) != 0L, if (c.isNull(3)) null else c.getString(3))
                out
            }
            fun setCode(m: Map<*, *>): String = (m["set"] as? String).orEmpty().lowercase()
            for (r in rows) {
                val (sel, cands) = try {
                    (if (r.sel.isNullOrEmpty()) null else MiniJson.parse(r.sel)) to
                        (if (r.cands.isNullOrEmpty()) null else MiniJson.parse(r.cands))
                } catch (e: IllegalArgumentException) {
                    continue
                }
                if (!r.f2fNull && sel is Map<*, *> && setCode(sel) in sets) {
                    db.execSQL("UPDATE scans SET f2f = NULL WHERE id = ?", arrayOf<Any?>(r.id))
                    nSel++
                }
                if (cands is List<*>) {
                    var dropped = 0
                    for (c in cands) {
                        if (c is Map<*, *> && c.containsKey("f2f_conditions") && setCode(c) in sets) {
                            @Suppress("UNCHECKED_CAST")
                            (c as MutableMap<String, Any?>).remove("f2f_conditions")   // MiniJson: LinkedHashMap
                            dropped++
                        }
                    }
                    if (dropped > 0) {
                        db.execSQL("UPDATE scans SET candidates = ? WHERE id = ?",
                            arrayOf<Any?>(MiniJson.stringify(cands), r.id))
                        nCand += dropped
                    }
                }
            }
            return nSel to nCand
        }

        /** server/store.py's `_SCHEMA`, verbatim. */
        const val SCHEMA = """
CREATE TABLE IF NOT EXISTS scans (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'candidates',
    identified  INTEGER NOT NULL DEFAULT 0,
    error       TEXT,
    card_read   TEXT NOT NULL DEFAULT '{}',
    confidence  TEXT NOT NULL DEFAULT '{}',
    candidates  TEXT NOT NULL DEFAULT '[]',
    selection   TEXT,
    f2f         TEXT,
    included    INTEGER NOT NULL DEFAULT 1,
    flagged     INTEGER NOT NULL DEFAULT 0
)"""
    }
}

/** Scan photos as `<dir>/<id>.jpg`, like the rig's scan_images/. */
class PhotoDir(val dir: File) : io.github.darylno.cardscanner.core.server.ScanImages {
    private fun f(id: Long) = File(dir, "$id.jpg")
    override fun read(id: Long): ByteArray? = f(id).takeIf { it.isFile }?.readBytes()
    override fun write(id: Long, jpeg: ByteArray) { dir.mkdirs(); f(id).writeBytes(jpeg) }
    override fun delete(id: Long) { f(id).delete() }

    /** Bytes the photos take (the 10,000-scan warning shows it). */
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}
