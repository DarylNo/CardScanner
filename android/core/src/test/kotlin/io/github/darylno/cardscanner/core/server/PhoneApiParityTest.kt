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
