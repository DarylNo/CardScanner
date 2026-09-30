package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Roles on the phone server: a guest can
 * browse, pick, edit and flag, but never delete, clear, export or drive the
 * sweep; the admin can do everything, including deleting just the flagged.
 */
class GuestRoleTest {
    private var tick = 0
    private val store = MemoryScanStore { "T${++tick}" }
    private val api = PhoneApi(store, object : ScanImages {
        override fun read(id: Long): ByteArray? = null
        override fun write(id: Long, jpeg: ByteArray) {}
        override fun delete(id: Long) {}
    })
    private val server = ScanServer(api, PriceSweep(store, api, { _, _, _, _, _ -> F2fAnswer.NotListed },
        launch = { it.run() }, now = { 0.0 }))

    private fun call(role: String, method: String, path: String, body: String? = null) =
        server.handle(ApiRequest(method, path, body = body?.toByteArray(), role = role))!!

    private fun seed(n: Int) = repeat(n) {
        api.fileScan(mapOf("identified" to true, "card_read" to mapOf("name" to "Opt"),
            "candidates" to listOf(mapOf("id" to "o$it", "name" to "Opt", "set" to "dom",
                "collector_number" to "60"), mapOf("id" to "x$it", "name" to "Opt", "set" to "xln",
                "collector_number" to "65"))), null)
    }

    @Test fun meSaysWhoIsAsking() {
        assertEquals(mapOf("role" to "guest"), MiniJson.parse(String(call(ROLE_GUEST, "GET", "/api/me").body)))
        assertEquals(mapOf("role" to "admin"), MiniJson.parse(String(call(ROLE_ADMIN, "GET", "/api/me").body)))
    }

    @Test fun aGuestCannotDeleteClearExportOrRunTheSweep() {
        seed(2)
        for ((m, p) in listOf("DELETE" to "/api/scans/1", "POST" to "/api/scans/delete-all",
            "GET" to "/api/export", "GET" to "/api/export.csv", "GET" to "/api/export/layout",
            "PUT" to "/api/export/layout", "POST" to "/api/export/preview",
            "POST" to "/api/price-sweep/stop", "POST" to "/api/scans/price-missing")) {
            assertEquals("$m $p", 403, call(ROLE_GUEST, m, p, "{}").status)
        }
        // dropping a card from the owner's export is the owner's call too
        assertEquals(403, call(ROLE_GUEST, "PATCH", "/api/scans/1", """{"included":false}""").status)
        assertEquals(403, call(ROLE_GUEST, "PATCH", "/api/scans/1", """{"flagged":true,"included":0}""").status)
        assertEquals(true, store.get(1)!!["included"])
        assertEquals(200, call(ROLE_ADMIN, "PATCH", "/api/scans/1", """{"included":false}""").status)
        assertEquals(2, store.count())                                       // nothing was deleted
    }

    @Test fun aGuestPicksEditsPricesAndFlags() {
        seed(1)
        assertEquals(200, call(ROLE_GUEST, "GET", "/api/scans").status)
        assertEquals(200, call(ROLE_GUEST, "POST", "/api/scans/1/select",
            """{"printing":{"id":"x0","name":"Opt","set":"xln","collector_number":"65"}}""").status)
        assertEquals(200, call(ROLE_GUEST, "PATCH", "/api/scans/1", """{"condition":"LP","quantity":2}""").status)
        assertEquals(200, call(ROLE_GUEST, "POST", "/api/scans/1/price-check").status)
        val flagged = MiniJson.parse(String(call(ROLE_GUEST, "PATCH", "/api/scans/1", """{"flagged":true}""").body)) as Map<*, *>
        assertEquals(true, flagged["flagged"])
        assertEquals(1, store.count())
    }

    @Test fun theAdminDeletesOnlyTheFlagged() {
        seed(3)
        call(ROLE_GUEST, "PATCH", "/api/scans/1", """{"flagged":true}""")
        call(ROLE_GUEST, "PATCH", "/api/scans/3", """{"flagged":true}""")
        // the admin confirmed "1 flagged" (scan 1); scan 3 was flagged after — it stays
        val r = call(ROLE_ADMIN, "POST", "/api/scans/delete-all", """{"only":"flagged","ids":[1]}""")
        assertEquals(mapOf("deleted" to 1L), MiniJson.parse(String(r.body)))
        assertEquals(listOf(3L, 2L), store.list().map { it["id"] })
        assertEquals(200, call(ROLE_ADMIN, "GET", "/api/export.csv").status)
    }
}
