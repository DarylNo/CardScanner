package io.github.darylno.cardscanner.phoneserver

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.server.ScanStore
import io.github.darylno.cardscanner.core.server.isoNow
import java.io.File

/**
 * [ScanStore] on the phone's own SQLite (Stage 3a) — the rig's `scans` table,
 * column for column (server/store.py), so a row reads back exactly as the
 * Python store's `dict(row)`. JSON fields are TEXT, decoded on every read.
 * The golden API replay runs against this class too (SqliteScanStoreTest).
 */
class SqliteScanStore(file: File, private val clock: () -> String = ::isoNow) : ScanStore {
    private val db: SQLiteDatabase
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(SCHEMA)
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
    included    INTEGER NOT NULL DEFAULT 1
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
