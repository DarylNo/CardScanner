package io.github.darylno.cardscanner.phoneserver

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.server.ApiRequest
import io.github.darylno.cardscanner.core.server.F2fAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The one-time re-price on the phone's own store — server/store.py's `_migrate` /
 * `_reprice_all` (tests/test_store.py, the same rows): the F2F matcher changed for
 * EVERY set in 1.1.17 and the sweep never re-asks an answer it recorded, so opening
 * a store from before drops every F2F answer ONCE (PRAGMA user_version 0 → 1) and
 * the sweep prices them again (the owner's call).
 */
@RunWith(RobolectricTestRunner::class)
class SqliteScanStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sel = mapOf("condition" to "NM", "finish" to "Non-Foil", "quantity" to 1L, "foil" to false)
    private val boltA25 = mapOf("id" to "a25-bolt", "name" to "Lightning Bolt", "set" to "a25",
        "set_name" to "Masters 25", "collector_number" to "141")
    private val boltPlst = mapOf("id" to "plst-bolt", "name" to "Lightning Bolt", "set" to "plst",
        "set_name" to "The List", "collector_number" to "A25-141")

    private data class Old(val status: String, val selection: Map<String, Any?>?, val f2f: Map<String, Any?>?,
                           val candidates: List<Map<String, Any?>>)

    private val oldRows = listOf(
        // 1: a selected List pick the old matcher never found → the empty marker
        Old("selected",
            mapOf("scryfall_id" to "plst-bolt", "name" to "Lightning Bolt", "set" to "plst",
                "set_name" to "The List", "collector_number" to "A25-141") + sel,
            mapOf("conditions" to emptyMap<String, Any?>(), "searched" to true),
            listOf(boltPlst + ("f2f_conditions" to emptyMap<String, Any?>()),
                boltA25 + ("f2f_conditions" to mapOf("NM" to 3.49)))),
        // 2: a selected List pick filed ANOTHER card's price (the name-blind fallback)
        Old("selected",
            mapOf("scryfall_id" to "plst-mort", "name" to "Mortuary", "set" to "plst",
                "set_name" to "The List", "collector_number" to "STH-66") + sel,
            mapOf("name" to "Mortuary", "set_code" to "plst", "collector_number" to "STH-66", "foil" to false,
                "handle" to "buried-alive-tmp", "url" to "https://f2f/b",
                "conditions" to mapOf("NM" to 4.99, "PL" to 3.99)),
            listOf(mapOf("id" to "plst-mort", "name" to "Mortuary", "set" to "plst", "collector_number" to "STH-66"))),
        // 3: unpicked — the original priced, its List twin recorded as "no listing", and the
        //    scan's OWN top-match price (scan time / the desktop's "Search F2F price") — the
        //    old matcher's answer too
        Old("candidates", null,
            mapOf("name" to "Lightning Bolt", "set_code" to "a25", "collector_number" to "141", "foil" to false,
                "handle" to "bolt-a25", "url" to "https://f2f/a", "conditions" to mapOf("NM" to 3.49, "PL" to 2.79)),
            listOf(boltA25 + ("f2f_conditions" to mapOf("NM" to 3.49, "PL" to 2.79)),
                boltPlst + ("f2f_conditions" to emptyMap<String, Any?>()))),
        // 4: a selected ordinary printing — the old matcher's answer as well
        Old("selected",
            mapOf("scryfall_id" to "m10-bolt", "name" to "Lightning Bolt", "set" to "m10",
                "set_name" to "Magic 2010", "collector_number" to "146") + sel,
            mapOf("name" to "Lightning Bolt", "set_code" to "m10", "collector_number" to "146", "foil" to false,
                "handle" to "bolt-m10", "url" to "https://f2f/m", "conditions" to mapOf("NM" to 2.99)),
            listOf(mapOf("id" to "m10-bolt", "name" to "Lightning Bolt", "set" to "m10",
                "collector_number" to "146", "f2f_conditions" to mapOf("NM" to 2.99)))),
        // 5: a hand-edited upper-case set code — no set is special any more
        Old("selected",
            mapOf("scryfall_id" to "mb2-saga", "name" to "Urza's Saga", "set" to "MB2",
                "set_name" to "Mystery Booster 2", "collector_number" to "114") + sel,
            mapOf("conditions" to mapOf("NM" to 59.99)),
            emptyList()),
        // 6: unpicked — every candidate's answer drops, whatever its set
        Old("candidates", null, null, listOf(
            mapOf("id" to "u1", "name" to "Earl of Squirrel", "set" to "ulst", "collector_number" to "108",
                "f2f_conditions" to mapOf("NM" to 7.99)),
            mapOf("id" to "u2", "name" to "Earl of Squirrel", "set" to "ust", "collector_number" to "108",
                "f2f_conditions" to mapOf("NM" to 0.99)),
            mapOf("id" to "c1", "name" to "Slivdrazi Monstrosity", "set" to "cmb1", "collector_number" to "102",
                "f2f_conditions" to mapOf("NM" to 54.99)),
            mapOf("id" to "c2", "name" to "Slivdrazi Monstrosity", "set" to "cmb2", "collector_number" to "102",
                "f2f_conditions" to mapOf("NM" to 54.99)),
            mapOf("id" to "m2", "name" to "Uro, Titan of Nature's Wrath", "set" to "mb2", "collector_number" to "93",
                "f2f_conditions" to mapOf("NM" to 59.99)))),
        // 7: a scan with no answer anywhere (no card found) — untouched, byte for byte
        Old("candidates", null, null, emptyList()),
    )

    /** [cands] as the re-price leaves them: only the f2f_conditions key gone. */
    private fun stripped(cands: List<Map<String, Any?>>): List<Map<String, Any?>> =
        cands.map { c -> c.filterKeys { it != "f2f_conditions" } }

    /** A store file as 1.1.16 and earlier left it: today's table, user_version 0. */
    private fun writeOldDb(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(SqliteScanStore.SCHEMA)
            oldRows.forEachIndexed { i, r ->
                db.execSQL("INSERT INTO scans (created_at, updated_at, status, identified, card_read, confidence," +
                    " candidates, selection, f2f) VALUES (?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
                    "2026-10-0${i + 1}T10:00:00+00:00", "2026-10-0${i + 1}T11:00:00+00:00", r.status, 1L,
                    """{"name": "x"}""", """{"name": "high"}""", MiniJson.stringify(r.candidates),
                    r.selection?.let { MiniJson.stringify(it) }, r.f2f?.let { MiniJson.stringify(it) }))
            }
            assertEquals(0, db.version)
        } finally {
            db.close()
        }
    }

    private fun <T> raw(file: File, block: (SQLiteDatabase) -> T): T {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try { return block(db) } finally { db.close() }
    }

    private fun rawRows(file: File): Map<Long, Map<String, Any?>> = raw(file) { db ->
        db.rawQuery("SELECT * FROM scans", null).use { c ->
            val out = LinkedHashMap<Long, Map<String, Any?>>()
            while (c.moveToNext()) {
                val row = LinkedHashMap<String, Any?>()
                for (i in 0 until c.columnCount) row[c.getColumnName(i)] = when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    else -> c.getString(i)
                }
                out[row["id"] as Long] = row
            }
            out
        }
    }

    private fun userVersion(file: File): Int = raw(file) { it.version }

    @Suppress("UNCHECKED_CAST")
    private fun cands(row: Map<String, Any?>?): List<Map<String, Any?>> = row!!["candidates"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun conditions(f2f: Any?): Any? = (f2f as Map<String, Any?>)["conditions"]

    @Test fun openingAnOldStoreDropsEveryF2fAnswerOnce() {
        val file = File(tmp.root, "old.db")
        writeOldDb(file)
        val before = rawRows(file)
        val s = SqliteScanStore(file)
        try {
            val rows = s.list().associateBy { it["id"] as Long }
            // every scan's own answer → NULL: a selection's (so the sweep prices it again)
            // and an unpicked scan's top-match price — in every set
            assertEquals((1L..7L).associateWith { null }, rows.mapValues { it.value["f2f"] })
            // every candidate's answer → the key gone, in every set
            assertEquals(emptyList<Long>(), rows.keys.sorted().filter { sid -> cands(rows[sid]).any { "f2f_conditions" in it } })
            // the candidates re-written with ONLY that key gone: every other key, the key
            // order and the JSON spelling as they were (row 2 never had one)
            val after = rawRows(file)
            oldRows.forEachIndexed { i, old ->
                assertEquals("${i + 1}", MiniJson.stringify(stripped(old.candidates)), after[i + 1L]!!["candidates"])
            }
            // nothing else moves: not updated_at, status, selection or the rest
            for ((sid, row) in before) for ((col, v) in row) {
                if (col == "f2f" || col == "candidates") continue
                assertEquals("$sid.$col", v, after[sid]!![col])
            }
            assertEquals(before[2]!!["candidates"], after[2]!!["candidates"])
            assertEquals(before[7], after[7])                            // untouched row, byte for byte
            assertEquals(1, userVersion(file))
        } finally {
            s.close()
        }
    }

    @Test fun theRepriceRunsExactlyOnce() {
        val file = File(tmp.root, "old.db")
        writeOldDb(file)
        val s = SqliteScanStore(file)
        assertNull(s.get(4)!!["f2f"])                                     // the open dropped m10's answer …
        val new = linkedMapOf("name" to "Lightning Bolt", "set_code" to "plst", "collector_number" to "A25-141",
            "foil" to false, "handle" to "bolt-plist", "url" to "https://f2f/p", "conditions" to mapOf("NM" to 3.49))
        val m10 = linkedMapOf("name" to "Lightning Bolt", "set_code" to "m10", "collector_number" to "146",
            "foil" to false, "handle" to "bolt-m10", "url" to "https://f2f/m", "conditions" to mapOf("NM" to 3.25))
        s.update(1, mapOf("f2f" to new))
        s.update(4, mapOf("f2f" to m10))                                  // … and the NEW matcher priced it
        val c3 = cands(s.get(3)).map { LinkedHashMap(it) }
        c3[1]["f2f_conditions"] = emptyMap<String, Any?>()              // the NEW matcher's miss
        s.update(3, mapOf("candidates" to c3))
        s.close()
        val s2 = SqliteScanStore(file)                                    // re-open: no second reset
        try {
            assertEquals(new, s2.get(1)!!["f2f"])
            assertEquals(m10, s2.get(4)!!["f2f"])
            assertEquals(emptyMap<String, Any?>(), cands(s2.get(3))[1]["f2f_conditions"])
            assertEquals(1, userVersion(file))
        } finally {
            s2.close()
        }
    }

    @Test fun aFreshStoreIsStampedWithoutAReprice() {
        val file = File(tmp.root, "fresh.db")
        SqliteScanStore(file).close()
        assertEquals(1, userVersion(file))
        var s = SqliteScanStore(file)
        val sid = s.create(true, emptyMap(), emptyMap(), listOf(boltPlst + ("f2f_conditions" to emptyMap<String, Any?>()),
            boltA25 + ("f2f_conditions" to mapOf("NM" to 3.49))))["id"] as Long
        val marker = mapOf("conditions" to emptyMap<String, Any?>(), "searched" to true)
        s.update(sid, mapOf("status" to "selected", "selection" to boltPlst, "f2f" to marker))
        s.close()
        s = SqliteScanStore(file)                                         // a new answer is never re-dropped
        try {
            assertEquals(marker, s.get(sid)!!["f2f"])
            assertEquals(listOf(emptyMap<String, Any?>(), mapOf("NM" to 3.49)), cands(s.get(sid)).map { it["f2f_conditions"] })
        } finally {
            s.close()
        }
    }

    /** One bad row never stops the re-price of the rest (nor the store opening). */
    @Test fun aRowThatDoesNotDecodeIsLeftAlone() {
        val file = File(tmp.root, "old.db")
        writeOldDb(file)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("INSERT INTO scans (created_at, updated_at, candidates, selection, f2f) VALUES ('t', 't', " +
                """'[{"set": "m10", "f2f_conditions": {}', '{"set": "m10"}', '{"conditions": {}}')""")
        }
        val before = rawRows(file)
        SqliteScanStore(file).close()
        val after = rawRows(file)
        assertEquals(before[8], after[8])                                // torn JSON: untouched
        assertNull(after[4]!!["f2f"])                                    // the rest re-priced
        assertEquals(1, userVersion(file))
    }

    /** End to end on the phone's backend: every dropped answer the sweep owns is owed again
     *  (a missing f2f_conditions reads exactly like null in PriceSweep) — the 4 selections and
     *  the 7 candidates of the 2 unpicked scans, nothing else. A picked scan's candidates were
     *  never the sweep's to price (only its selection is), and an unpicked scan's own top-match
     *  f2f is not either (the sweep reads only its candidates): dropping those owes nothing. */
    @Test fun theSweepRepricesExactlyWhatTheResetDropped() {
        val file = File(tmp.root, "old.db")
        writeOldDb(file)
        val s = SqliteScanStore(file)
        val calls = ArrayList<Pair<String, String>>()
        val b = PhoneBackend(s, PhotoDir(tmp.newFolder("photos")), pages = { null }, version = "t",
            search = { emptyList() }, lanIp = { null }, packRows = { 0 },
            f2f = { name, set, cn, foil, _ ->
                calls += set to cn
                F2fAnswer.Found(linkedMapOf("name" to name, "set_code" to set, "collector_number" to cn, "foil" to foil,
                    "handle" to "h", "url" to "http://f2f/h", "conditions" to linkedMapOf("NM" to 3.49, "PL" to 2.79)))
            },
            launch = { it.run() }, now = { 0.0 })
        try {
            fun priceMissing() = MiniJson.parse(String(b.handle(ApiRequest("POST", "/api/scans/price-missing")).body)) as Map<*, *>
            assertEquals(11L, priceMissing()["queued"])
            assertEquals(listOf("plst" to "A25-141", "plst" to "STH-66", "m10" to "146", "MB2" to "114",  // selections
                "a25" to "141", "plst" to "A25-141",                                                    // scan 3's prints
                "ulst" to "108", "ust" to "108", "cmb1" to "102", "cmb2" to "102", "mb2" to "93")       // scan 6's prints
                .sortedWith(compareBy({ it.first }, { it.second })),
                calls.sortedWith(compareBy({ it.first }, { it.second })))
            assertEquals(0L, priceMissing()["queued"])
            assertNull(s.get(3)!!["f2f"])                                 // no sweep re-asks the top match
            assertTrue(s.list().all { r -> r["selection"] == null || r["f2f"] != null })
        } finally {
            s.close()
        }
    }
}
