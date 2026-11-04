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
 * one-time re-price on open ([repriceAll]) is SqliteScanStoreTest.
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
            var reset = Triple(0, 0, 0)
            db.beginTransaction()                    // EXCLUSIVE; re-read the stamp inside it
            try {
                if (db.version < 1) {
                    reset = repriceAll(db)
                    db.version = 1
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            val (nSel, nTop, nCand) = reset
            if (nSel > 0 || nTop > 0 || nCand > 0) {
                DebugLog.global.i("store", "re-price: $nSel selection(s) and $nCand candidate print(s) " +
                    "dropped their F2F answer, $nTop unpicked scan(s) their top-match price")
            }
        } catch (e: Exception) {
            DebugLog.global.w("store", "re-price failed, retried on the next open: $e")
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
         * store.py's `_reprice_all` — the one-time re-price (PRAGMA user_version 0 → 1,
         * release 1.1.17): EVERY F2F answer recorded before it is dropped once, and
         * PriceWorker prices again from the phone's own connection at its usual pacing — a
         * one-time catch-up; new scans still jump the queue (PriceSweep.priceCheck). The
         * owner chose all, not just The List's sets: the 1.1.17 matcher change (F2fPricer,
         * facetoface.py) reaches every set. The old set-name fallback was collector- and
         * name-blind in EVERY set — an unstocked card filed another card of the same set's
         * price (Mortuary [STH-66] → Buried Alive's $4.99) — and a title with variant
         * brackets ("Lightning Bolt [401] [Rulebook Showcase] [Commander Legends: Battle for
         * Baldur's Gate] [Non-Foil]", about a third of F2F's titles) never matched its own
         * collector, so a showcase / borderless / Secret Lair printing often holds the
         * regular printing's price. The List's prints were never found under their own SKUs
         * at all (plst = MB1 / FMB1 / PLIST / PLST, ulst = UPLIST, cmb2 = CMB1 + NOPW). And
         * the sweep never re-asks an answer it recorded, so without this none of that would
         * reach a card already scanned.
         *
         * In place: a scan's `f2f` column → NULL (a selection's price, owed again; an
         * unpicked scan's own top-match price from scan time or the desktop's "Search F2F
         * price" — PriceSweep reads only its candidates, so that one is owed nowhere), and
         * every candidate's `f2f_conditions` key → removed (PriceSweep reads a missing key
         * exactly like null — owed on an unpicked scan; a picked scan prices only its
         * selection). Writes only those two columns, only on rows that change: updated_at,
         * status, the selection and everything else stay as they were. A row whose JSON
         * does not decode is left alone. Returns (selections, unpicked scans' own prices,
         * candidate prints) reset. The caller holds the transaction.
         */
        internal fun repriceAll(db: SQLiteDatabase): Triple<Int, Int, Int> {
            var nSel = 0
            var nTop = 0
            var nCand = 0
            data class Raw(val id: Long, val sel: String?, val f2fNull: Boolean, val cands: String?)
            val rows = db.rawQuery("SELECT id, selection, f2f IS NULL, candidates FROM scans", null).use { c ->
                val out = ArrayList<Raw>()
                while (c.moveToNext()) out += Raw(c.getLong(0), if (c.isNull(1)) null else c.getString(1),
                    c.getLong(2) != 0L, if (c.isNull(3)) null else c.getString(3))
                out
            }
            for (r in rows) {
                val (sel, cands) = try {
                    (if (r.sel.isNullOrEmpty()) null else MiniJson.parse(r.sel)) to
                        (if (r.cands.isNullOrEmpty()) null else MiniJson.parse(r.cands))
                } catch (e: IllegalArgumentException) {
                    continue
                }
                if (!r.f2fNull) {
                    db.execSQL("UPDATE scans SET f2f = NULL WHERE id = ?", arrayOf<Any?>(r.id))
                    if (sel is Map<*, *> && sel.isNotEmpty()) nSel++ else nTop++
                }
                if (cands is List<*>) {
                    var dropped = 0
                    for (c in cands) {
                        if (c is Map<*, *> && c.containsKey("f2f_conditions")) {
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
            return Triple(nSel, nTop, nCand)
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
