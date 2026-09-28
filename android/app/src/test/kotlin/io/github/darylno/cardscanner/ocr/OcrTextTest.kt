package io.github.darylno.cardscanner.ocr

import io.github.darylno.cardscanner.core.OcrMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The ML Kit → matcher glue, without ML Kit (it can't run on the JVM). */
class OcrTextTest {
    private fun c(id: String, set: String, cn: String) =
        mapOf<String, Any?>("id" to id, "set" to set, "collector_number" to cn)

    private val edict = listOf(c("a25", "a25", "85"), c("plst", "plst", "A25-85"),
                               c("mh1", "mh1", "87"), c("tmp", "tmp", "128"))

    @Test fun joinsBlocksThenLinesWithSpaces() {
        assertEquals("087/254 U MH1 • EN Illus. John Avon",
            OcrText.joinLines(listOf(listOf("087/254 U", "MH1 • EN"), listOf("Illus. John Avon"))))
        assertEquals("", OcrText.joinLines(emptyList()))
        assertEquals("", OcrText.joinLines(listOf(emptyList())))
        assertEquals("a b", OcrText.joinLines(listOf(listOf("a"), emptyList(), listOf("b"))))
    }

    @Test fun sameCanonAsRapidOcrRowsJoined() {
        // RapidOCR: " ".join(row[1] for row in result); ML Kit: the same lines, grouped in blocks.
        val rows = listOf("0087/254 U", "MH1 • EN", "Illus. John Avon")
        assertEquals(OcrMatch.canon(rows.joinToString(" ")),
            OcrMatch.canon(OcrText.joinLines(listOf(rows.take(2), rows.drop(1).take(0), rows.drop(2)))))
    }

    @Test fun joinedTextFeedsTheMatcher() {
        // ML Kit's typical slips — lowercase, I/1, O/0, S/5 — are the confusion classes.
        assertEquals("mh1", OcrMatch.matchPrinting(OcrText.joinLines(listOf(listOf("O87/2S4 u", "mhI • EN"))), edict))
        assertEquals("plst", OcrMatch.matchPrinting(OcrText.joinLines(listOf(listOf("A25-85", "PLST"))), edict))
        assertNull(OcrMatch.matchPrinting(OcrText.joinLines(listOf(listOf("Illus. Kev Walker"))), edict))
    }
}
