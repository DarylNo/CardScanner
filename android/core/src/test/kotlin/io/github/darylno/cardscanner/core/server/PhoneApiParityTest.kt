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

    @Test fun thePricingSessionMatchesTheServer() {
        val result = GoldenApi.replaySweep { clock -> MemoryScanStore(clock) }
        if (result.isNotEmpty()) fail(result.joinToString("\n"))
    }

    @Test fun theExportSessionMatchesTheServer() {
        val result = GoldenApi.replayExport(null) { clock -> MemoryScanStore(clock) }
        if (result.isNotEmpty()) fail(result.joinToString("\n"))
    }

    @Test fun theExportSessionMatchesWithTheLayoutOnDisk() {
        val dir = createTempDir("layout")
        try {
            val file = java.io.File(dir, "export_layout.json")
            val result = GoldenApi.replayExport(file) { clock -> MemoryScanStore(clock) }
            if (result.isNotEmpty()) fail(result.joinToString("\n"))
            // the last saved layout survives a restart (a fresh store on the same file)
            assertEquals("collector_number",
                ((LayoutStore(file).load()["columns"] as List<*>)[0] as Map<*, *>)["field"])
            file.writeText("{not json")
            assertEquals(Export.defaultLayout(), LayoutStore(file).load())   // bad file → default
        } finally { dir.deleteRecursively() }
    }

    @Test fun moneyRoundsTheExactBinaryValueLikePython() {
        // values checked against Python f"{v:.2f}": 2.675 is 2.67499999… in binary; 0.125 is an exact tie → even
        for ((v, want) in listOf(2.675 to "2.67", 0.125 to "0.12", 0.375 to "0.38", 1.005 to "1.00",
            3.015 to "3.02", 1.005 * 3 to "3.01", 5.0 to "5.00", 1234567.891 to "1234567.89")) {
            assertEquals("$v", want, Export.money(v))
        }
        assertEquals("", Export.money(null))
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
