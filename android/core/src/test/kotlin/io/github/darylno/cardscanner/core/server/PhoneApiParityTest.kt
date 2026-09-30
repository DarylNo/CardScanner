package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Replays the golden session scripts/export_api_fixtures.py recorded from the
 * REAL Python server (same seeds, same store clock, same requests in order)
 * against [PhoneApi] + [MemoryScanStore]: every status and every JSON body
 * must match. The SQLite store runs the same replay in the app's tests.
 */
class PhoneApiParityTest {
    @Test fun everyRecordedResponseMatchesTheServer() {
        val result = GoldenApi.replay { clock -> MemoryScanStore(clock) }
        if (result.isNotEmpty()) fail(result.joinToString("\n"))
    }

    @Test fun theSessionCoversEveryRoute() {
        val steps = GoldenApi.fixture()["steps"] as List<*>
        val seen = steps.map { s -> (s as Map<*, *>).let { "${it["method"]} ${GoldenApi.route(it["path"] as String)}" } }.toSet()
        for (r in listOf("GET /api/scans", "GET /api/scans/{id}", "POST /api/scans/{id}/select",
            "PATCH /api/scans/{id}", "DELETE /api/scans/{id}", "POST /api/scans/delete-all")) {
            assertTrue("fixture never exercises $r", r in seen)
        }
    }

    @Test fun foilFromFinishMatchesThePythonHelper() {
        for ((f, want) in listOf("Non-Foil" to false, " nonfoil " to false, "" to false, null to false,
            "Foil" to true, "Etched" to true, "NON-FOIL" to false)) {
            assertEquals("finish=$f", want, PhoneApi.foilFromFinish(f))
        }
    }

    @Test fun pythonIntOfBodyValues() {
        assertEquals(4L, PhoneApi.pyInt("4"))
        assertEquals(4L, PhoneApi.pyInt(" 4 "))
        assertEquals(3L, PhoneApi.pyInt(3.7))
        assertEquals(1L, PhoneApi.pyInt(true))
        assertEquals(null, PhoneApi.pyInt("4.0"))           // Python raises
        assertEquals(10L, PhoneApi.pyInt("1_0"))
    }
}

/** The golden fixture and its replay, shared by every [ScanStore] implementation's parity test. */
object GoldenApi {
    fun fixture(): Map<String, Any?> {
        val text = GoldenApi::class.java.getResourceAsStream("/api/expected.json")!!
            .use { String(it.readBytes(), Charsets.UTF_8) }
        @Suppress("UNCHECKED_CAST")
        return MiniJson.parse(text) as Map<String, Any?>
    }

    fun route(path: String) = path.replace(Regex("/api/scans/\\d+"), "/api/scans/{id}")

    /** Replays the fixture against a fresh store from [newStore]; returns one line per mismatch. */
    @Suppress("UNCHECKED_CAST")
    fun replay(newStore: (clock: () -> String) -> ScanStore): List<String> {
        val fx = fixture()
        var tick = 0
        val store = newStore { tick++; "T" + (tick).toString().padStart(4, '0') }
        val photos = HashMap<Long, ByteArray>()
        val api = PhoneApi(store, object : ScanImages {
            override fun read(id: Long) = photos[id]
            override fun delete(id: Long) { photos.remove(id) }
        })
        for (s in fx["seeds"] as List<Map<String, Any?>>) {
            store.create(s["identified"] as Boolean, s["card_read"] as Map<String, Any?>?,
                s["confidence"] as Map<String, Any?>?, s["candidates"] as List<Any?>?, s["error"] as String?)
        }
        val out = ArrayList<String>()
        for ((i, step) in (fx["steps"] as List<Map<String, Any?>>).withIndex()) {
            val body = step["body"]?.let { MiniJson.stringify(it).toByteArray() }
            val res = api.handle(ApiRequest(step["method"] as String, step["path"] as String, body = body))
            val tag = "#$i ${step["method"]} ${step["path"]}"
            if (res == null) { out += "$tag: not handled"; continue }
            val wantStatus = (step["status"] as Number).toInt()
            if (res.status != wantStatus) out += "$tag: status ${res.status}, server $wantStatus"
            val got = MiniJson.parse(String(res.body, Charsets.UTF_8))
            val diff = jsonDiff(step["response"], got, "$")
            if (diff != null) out += "$tag: $diff"
        }
        return out
    }

    /** Null when equal; numbers compare by value (Python int 2 == JSON 2), booleans stay booleans. */
    fun jsonDiff(want: Any?, got: Any?, at: String): String? = when {
        want is Map<*, *> && got is Map<*, *> -> {
            if (want.keys != got.keys) "$at keys ${want.keys} vs ${got.keys}"
            else want.keys.firstNotNullOfOrNull { k -> jsonDiff(want[k], got[k], "$at.$k") }
        }
        want is List<*> && got is List<*> ->
            if (want.size != got.size) "$at length ${want.size} vs ${got.size}"
            else want.indices.firstNotNullOfOrNull { jsonDiff(want[it], got[it], "$at[$it]") }
        want is Boolean || got is Boolean -> if (want == got) null else "$at: $want vs $got"
        want is Number && got is Number ->
            if (want.toDouble() == got.toDouble()) null else "$at: $want vs $got"
        else -> if (want == got) null else "$at: $want vs $got"
    }
}
