package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Refocus on a card" (1.1.18, owner: "Need it to focus every once in a while"): the choices, when a pass is due, the words. */
class RefocusPolicyTest {
    private val min = 60_000_000_000L
    private val s = 1_000_000_000L

    @Test fun choicesDefaultAndNormalize() {
        assertEquals(listOf(0, 2, 5, 10, 30, -1), RefocusPolicy.CHOICES.toList())
        assertEquals(RefocusPolicy.EVERY_CARD, 0)
        assertEquals(RefocusPolicy.NEVER, -1)
        assertEquals("the provisional default", 5, RefocusPolicy.DEFAULT)
        for (m in RefocusPolicy.CHOICES) {
            assertTrue("$m", RefocusPolicy.isChoice(m))
            assertEquals(m, RefocusPolicy.normalize(m))
        }
        for (m in listOf(1, 3, 7, 60, -2, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertFalse("$m", RefocusPolicy.isChoice(m))
            assertEquals("a stored value that is not offered reads as the default: $m", RefocusPolicy.DEFAULT, RefocusPolicy.normalize(m))
        }
    }

    @Test fun neverIsNeverDue_everyCardAlwaysIs() {
        for (last in listOf(null, 0L, -100 * min)) {
            assertFalse("never ($last)", RefocusPolicy.due(1_000 * min, last, RefocusPolicy.NEVER))
            assertTrue("every card ($last)", RefocusPolicy.due(1_000 * min, last, RefocusPolicy.EVERY_CARD))
        }
        assertTrue("every card: even right after a pass", RefocusPolicy.due(5 * min, 5 * min, RefocusPolicy.EVERY_CARD))
        assertFalse("any other negative is never", RefocusPolicy.due(1_000 * min, 0L, -7))
    }

    @Test fun anIntervalIsDueOnceThatLongHasPassedSinceTheLastCardFocus() {
        val last = 3 * min + 17 * s
        assertFalse(RefocusPolicy.due(last, last, 5))
        assertFalse("one ns short", RefocusPolicy.due(last + 5 * min - 1, last, 5))
        assertTrue("exactly 5 min", RefocusPolicy.due(last + 5 * min, last, 5))
        assertTrue(RefocusPolicy.due(last + 40 * min, last, 5))
        assertFalse(RefocusPolicy.due(last + 29 * min, last, 30))
        assertTrue(RefocusPolicy.due(last + 2 * min, last, 2))
        assertFalse("a clock that went back is not due", RefocusPolicy.due(last - 10 * min, last, 2))
    }

    /**
     * No card focus since the bind is NOT due: every bind / re-open / new Area / mode switch /
     * zoom change already arms the first-card pass, so a periodic arm there would arm twice;
     * and when that chain ended without an answer (cancelled 3×) or a tap is still in flight,
     * the policy waits for an answer instead of overriding it.
     */
    @Test fun noCardFocusYetIsNotDue() {
        for (m in listOf(2, 5, 10, 30)) assertFalse("$m", RefocusPolicy.due(1_000 * min, null, m))
    }

    @Test fun labels() {
        assertEquals("Every card", RefocusPolicy.label(0))
        assertEquals("Every 2 min", RefocusPolicy.label(2))
        assertEquals("Every 5 min", RefocusPolicy.label(5))
        assertEquals("Every 10 min", RefocusPolicy.label(10))
        assertEquals("Every 30 min", RefocusPolicy.label(30))
        assertEquals("Never", RefocusPolicy.label(-1))
        assertEquals(listOf("Every card", "Every 2 min", "Every 5 min", "Every 10 min", "Every 30 min", "Never"),
            RefocusPolicy.CHOICES.map(RefocusPolicy::label))
    }

    @Test fun theFocusLogAndDiagnosticsWords() {
        assertEquals("periodic: every 5 min — 7 min since the last focus on a card",
            RefocusPolicy.periodicWhy(10 * min + 40 * s, 3 * min, 5))
        assertEquals("periodic: every card", RefocusPolicy.periodicWhy(10 * min, 3 * min, 0))
        assertEquals("periodic: every card", RefocusPolicy.periodicWhy(10 * min, null, 0))
        assertEquals("refocus every 5 min · last card focus 3 min ago", RefocusPolicy.describe(5, 13 * min + 59 * s, 10 * min))
        assertEquals("refocus every 5 min · last card focus 42 s ago", RefocusPolicy.describe(5, 10 * min + 42 * s, 10 * min))
        assertEquals("refocus every 5 min · last card focus none yet", RefocusPolicy.describe(5, 13 * min, null))
        assertEquals("refocus every card · last card focus none yet", RefocusPolicy.describe(0, 13 * min, null))
        assertEquals("refocus never · last card focus 2 h 5 min ago", RefocusPolicy.describe(-1, 125 * min, 0L))
    }
}
