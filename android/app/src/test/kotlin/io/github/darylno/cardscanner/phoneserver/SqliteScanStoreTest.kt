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
 * the sweep prices them again (the owner's call). Then the one-time re-ask
 * (`_reask_misses`, 1 → 2, 1.1.18): 1.1.17 recorded every meld half as "no F2F
 * listing", so a 1.1.17 store drops every recorded MISS once — never a price.
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

    /** A store file as 1.1.16 and earlier left it (today's table, user_version 0), or — [version] 1 — as 1.1.17 did. */
    private fun writeOldDb(file: File, rows: List<Old> = oldRows, version: Int = 0) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(SqliteScanStore.SCHEMA)
            rows.forEachIndexed { i, r ->
                db.execSQL("INSERT INTO scans (created_at, updated_at, status, identified, card_read, confidence," +
                    " candidates, selection, f2f) VALUES (?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
                    "2026-10-0${i + 1}T10:00:00+00:00", "2026-10-0${i + 1}T11:00:00+00:00", r.status, 1L,
                    """{"name": "x"}""", """{"name": "high"}""", MiniJson.stringify(r.candidates),
                    r.selection?.let { MiniJson.stringify(it) }, r.f2f?.let { MiniJson.stringify(it) }))
            }
            assertEquals(0, db.version)
            if (version != 0) db.version = version
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
            assertEquals(2, userVersion(file))                       // v1, then v2 (a no-op here)
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
            assertEquals(2, userVersion(file))
        } finally {
            s2.close()
        }
    }

    @Test fun aFreshStoreIsStampedWithoutAReprice() {
        val file = File(tmp.root, "fresh.db")
        SqliteScanStore(file).close()
        assertEquals(2, userVersion(file))
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
        assertEquals(2, userVersion(file))
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

    // ── the one-time re-ask of the misses (PRAGMA user_version 1 → 2) — store.py's `_reask_misses`,
    // tests/test_store.py's `_V1_ROWS`. 1.1.17's matcher priced no meld half (F2F's "Midnight Scavengers //
    // Chittering Host [96a] [B]" for Scryfall's #96) and its re-price ran BEFORE the fix, so the owner's meld
    // halves were recorded "no F2F listing" — and the sweep never re-asks a recorded miss.

    private val marker = mapOf("conditions" to emptyMap<String, Any?>(), "searched" to true)
    private val ms = mapOf("id" to "emn-96", "name" to "Midnight Scavengers", "set" to "emn",
        "set_name" to "Eldritch Moon", "collector_number" to "96")
    private val gr = mapOf("id" to "emn-91", "name" to "Graf Rats", "set" to "emn",
        "set_name" to "Eldritch Moon", "collector_number" to "91")
    private val bruna = mapOf("id" to "emn-15", "name" to "Bruna, the Fading Light", "set" to "emn",
        "set_name" to "Eldritch Moon", "collector_number" to "15")
    private val brunaSoi = mapOf("id" to "soi-5", "name" to "Bruna, the Fading Light", "set" to "soi",
        "set_name" to "Shadows over Innistrad", "collector_number" to "5")
    private val m10Price = mapOf("name" to "Lightning Bolt", "set_code" to "m10", "collector_number" to "146",
        "foil" to false, "handle" to "bolt-m10", "url" to "https://f2f/m", "conditions" to mapOf("NM" to 2.99))
    private val brunaPrice = mapOf("name" to "Bruna, the Fading Light", "set_code" to "soi", "collector_number" to "5",
        "foil" to false, "handle" to "bruna-soi", "url" to "https://f2f/s", "conditions" to mapOf("NM" to 1.49))
    private val none = emptyMap<String, Any?>()

    private val v1Rows = listOf(
        // 1, 2: the owner's report — picked meld halves 1.1.17 recorded as "no listing" (their candidates too)
        Old("selected", mapOf("scryfall_id" to "emn-96") + ms + sel, marker, listOf(ms + ("f2f_conditions" to none))),
        Old("selected", mapOf("scryfall_id" to "emn-91") + gr + sel, marker, listOf(gr + ("f2f_conditions" to none))),
        // 3: a picked card the new matcher priced — untouched, byte for byte
        Old("selected",
            mapOf("scryfall_id" to "m10-bolt", "name" to "Lightning Bolt", "set" to "m10",
                "set_name" to "Magic 2010", "collector_number" to "146") + sel,
            m10Price,
            listOf(mapOf("id" to "m10-bolt", "name" to "Lightning Bolt", "set" to "m10",
                "collector_number" to "146", "f2f_conditions" to mapOf("NM" to 2.99)))),
        // 4: unpicked — one print recorded "no listing", one priced, and the scan's own top-match price
        Old("candidates", null, brunaPrice,
            listOf(bruna + ("f2f_conditions" to none), brunaSoi + ("f2f_conditions" to mapOf("NM" to 1.49, "PL" to 1.19)))),
        // 5: unpicked, every print priced — untouched, byte for byte
        Old("candidates", null, null, listOf(brunaSoi + ("f2f_conditions" to mapOf("NM" to 1.49)))),
        // 6: a marker with no "searched" key is a miss all the same
        Old("selected", mapOf("scryfall_id" to "emn-15") + bruna + sel, mapOf("conditions" to none), emptyList()),
        // 7: a scan with no answer anywhere (no card found) — untouched, byte for byte
        Old("candidates", null, null, emptyList()),
    )

    @Test fun openingA117StoreDropsOnlyItsMissesOnce() {
        val file = File(tmp.root, "v1.db")
        writeOldDb(file, v1Rows, version = 1)
        val before = rawRows(file)
        val s = SqliteScanStore(file)
        try {
            val rows = s.list().associateBy { it["id"] as Long }
            // the selections' "no listing" markers → NULL (owed again); every price stays
            assertEquals(mapOf(1L to null, 2L to null, 3L to m10Price, 4L to brunaPrice, 5L to null, 6L to null, 7L to null),
                rows.mapValues { it.value["f2f"] })
            // every candidate's {} → the key gone; every price stays
            assertEquals(listOf(listOf("-"), listOf("-"), listOf(mapOf("NM" to 2.99)),
                listOf("-", mapOf("NM" to 1.49, "PL" to 1.19)), listOf(mapOf("NM" to 1.49)), emptyList(), emptyList()),
                (1L..7L).map { sid -> cands(rows[sid]).map { it["f2f_conditions"] ?: "-" } })
            val after = rawRows(file)
            assertEquals(MiniJson.stringify(listOf(ms)), after[1]!!["candidates"])     // only that key gone
            assertEquals(MiniJson.stringify(listOf(bruna, brunaSoi + ("f2f_conditions" to mapOf("NM" to 1.49, "PL" to 1.19)))),
                after[4]!!["candidates"])
            // nothing else moves: not updated_at, status, selection or the rest
            for ((sid, row) in before) for ((col, v) in row) {
                if (col == "f2f" || col == "candidates") continue
                assertEquals("$sid.$col", v, after[sid]!![col])
            }
            for (sid in listOf(3L, 5L, 7L)) assertEquals("$sid", before[sid], after[sid])   // byte for byte
            assertEquals(before[4]!!["f2f"], after[4]!!["f2f"])
            assertEquals(2, userVersion(file))
            // the fixed matcher's own misses, recorded after the open, are never re-dropped
            s.update(1, mapOf("f2f" to marker))
            val c4 = cands(s.get(4)).map { LinkedHashMap(it) }
            c4[0]["f2f_conditions"] = none
            s.update(4, mapOf("candidates" to c4))
        } finally {
            s.close()
        }
        val s2 = SqliteScanStore(file)
        try {
            assertEquals(marker, s2.get(1)!!["f2f"])
            assertEquals(none, cands(s2.get(4))[0]["f2f_conditions"])
            assertEquals(2, userVersion(file))
        } finally {
            s2.close()
        }
    }

    @Test fun a116StoreRunsTheRepriceThenTheReask() {
        val file = File(tmp.root, "old.db")
        writeOldDb(file)
        SqliteScanStore(file).close()
        assertEquals(2, userVersion(file))
        assertTrue(rawRows(file).values.all { it["f2f"] == null })        // v1's reset: every answer
    }

    @Test fun aFreshStoreIsStamped2() {
        val file = File(tmp.root, "fresh.db")
        SqliteScanStore(file).close()
        assertEquals(2, userVersion(file))
    }

    /** A real failure part way (a trigger aborts the 4th row's write after rows 1 and 2 were written): every
     *  row and the version stay as they were, the store still opens, and the next open does it. */
    @Test fun aFailedReaskRollsBackAndTheStoreStillOpens() {
        val file = File(tmp.root, "v1.db")
        writeOldDb(file, v1Rows, version = 1)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TRIGGER fail_row4 BEFORE UPDATE ON scans WHEN OLD.id = 4 " +
                "BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END")
        }
        val before = rawRows(file)
        val s = SqliteScanStore(file)
        try {
            assertEquals(marker, s.get(1)!!["f2f"])
        } finally {
            s.close()
        }
        assertEquals(before, rawRows(file))
        assertEquals(1, userVersion(file))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.execSQL("DROP TRIGGER fail_row4") }
        SqliteScanStore(file).close()                                     // the next open does it
        assertNull(rawRows(file)[1]!!["f2f"])
        assertEquals(2, userVersion(file))
    }

    /** End to end on the phone's backend: in the 1.1.17 store nothing is owed; after the open the sweep owes
     *  exactly the dropped misses it prices — the 3 selections' and the one unpicked print's. A picked scan's
     *  dropped candidate misses owe nothing (only its selection is priced), and no price is asked again. */
    @Test fun theSweepReasksExactlyTheMissesTheReaskDropped() {
        val file = File(tmp.root, "v1.db")
        writeOldDb(file, v1Rows, version = 1)
        val s = SqliteScanStore(file)
        val calls = ArrayList<Triple<String, String, String>>()
        val b = PhoneBackend(s, PhotoDir(tmp.newFolder("photos")), pages = { null }, version = "t",
            search = { emptyList() }, lanIp = { null }, packRows = { 0 },
            f2f = { name, set, cn, foil, _ ->
                calls += Triple(name, set, cn)
                F2fAnswer.Found(linkedMapOf("name" to name, "set_code" to set, "collector_number" to cn, "foil" to foil,
                    "handle" to "h", "url" to "http://f2f/h", "conditions" to linkedMapOf("NM" to 3.49, "PL" to 2.79)))
            },
            launch = { it.run() }, now = { 0.0 })
        try {
            fun priceMissing() = MiniJson.parse(String(b.handle(ApiRequest("POST", "/api/scans/price-missing")).body)) as Map<*, *>
            assertEquals(4L, priceMissing()["queued"])
            val order = compareBy<Triple<String, String, String>>({ it.first }, { it.second }, { it.third })
            assertEquals(listOf(Triple("Midnight Scavengers", "emn", "96"), Triple("Graf Rats", "emn", "91"),  // the owner's
                Triple("Bruna, the Fading Light", "emn", "15"),                                               // row 6's pick
                Triple("Bruna, the Fading Light", "emn", "15")).sortedWith(order),                            // row 4's print
                calls.sortedWith(order))
            assertEquals(0L, priceMissing()["queued"])
            assertEquals(m10Price, s.get(3)!!["f2f"])                                       // prices kept
            assertEquals(mapOf("NM" to 1.49, "PL" to 1.19), cands(s.get(4))[1]["f2f_conditions"])
            assertEquals(mapOf("NM" to 3.49, "PL" to 2.79), conditions(s.get(1)!!["f2f"]))  // priced again
        } finally {
            s.close()
        }
    }
}
