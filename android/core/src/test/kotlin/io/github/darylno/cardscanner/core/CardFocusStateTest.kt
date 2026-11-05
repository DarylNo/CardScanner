package io.github.darylno.cardscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Tray's card-focus pass with "Refocus on a card" (1.1.18): what CameraController
 * asks at every Tray trigger ([CardFocusState.holdForCard], the analysis thread) and
 * tells it from the main thread (arms, answers, a tap, a bind).
 */
class CardFocusStateTest {
    private val min = 60_000_000_000L
    private val s = 1_000_000_000L
    private val lines = mutableListOf<String>()
    private fun state(minutes: Int = 5) = CardFocusState(log = { lines += it }).also { it.minutes = minutes }

    @Test fun nothingArmedNothingAnswered_noHold() {
        val f = state()
        assertFalse(f.isDue)
        assertFalse("no card focus since the bind: the first-card pass is the bind's job", f.holdForCard(100 * min))
        assertNull(f.lastCardFocusNs)
        assertTrue(lines.isEmpty())
    }

    @Test fun afterAFocusedPass_theNextCardIsHeldOnlyOnceTheIntervalHasPassed() {
        val f = state(5)
        f.arm("first card after the camera bind")
        assertTrue(f.holdForCard(0))
        assertEquals(CardFocusState.End.FOCUSED, f.passEnded(true, 10 * s))
        assertFalse(f.isDue)
        assertEquals(10 * s, f.lastCardFocusNs)
        assertFalse("4 min 59 s later: not yet", f.holdForCard(10 * s + 5 * min - s))
        assertTrue(lines.isEmpty())
        assertTrue("5 min later: held for a pass", f.holdForCard(10 * s + 5 * min + 50 * s))
        assertTrue(f.isDue)
        assertEquals("periodic: every 5 min — 5 min since the last focus on a card", f.why)
        assertEquals("one line when the periodic pass is armed: $lines", 1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("periodic refocus: every 5 min — 5 min since the last focus on a card"))
        // Still armed (no answer yet): the next trigger is held too, without arming or logging again.
        assertTrue(f.holdForCard(10 * s + 6 * min))
        assertEquals(1, lines.size)
        assertEquals(CardFocusState.End.FOCUSED, f.passEnded(true, 10 * s + 6 * min))
        assertFalse("the clock starts again at that answer", f.holdForCard(10 * s + 10 * min))
        assertTrue(f.holdForCard(10 * s + 11 * min))
    }

    /** The interval never arms on top of a pass already armed (bind / new Area / zoom change / watchdog / retry). */
    @Test fun aPeriodicPassNeverDoubleArmsOnTopOfAnArmedOne() {
        val f = state(2)
        f.arm("first card after the camera bind")
        f.passEnded(true, 0)
        f.arm("first card in the new Area")             // 40 min later, a new Area — the interval is long past too
        assertTrue(f.holdForCard(40 * min))
        assertEquals("first card in the new Area", f.why)
        assertTrue("no periodic arm, no line: $lines", lines.isEmpty())
        f.arm("watchdog: a soft capture")
        assertTrue(f.holdForCard(41 * min))
        assertEquals("watchdog: a soft capture", f.why)
        assertTrue(lines.isEmpty())
    }

    /**
     * A NOT-focused answer is an answer: the interval restarts from it, so a lens that will not
     * converge is not asked again on every card — the failing chain still retries (3 tries in
     * all, the existing give-up), and the next chain starts one interval after its last answer.
     */
    @Test fun aNotFocusedAnswerRestartsTheClock_theChainStillGivesUpAfterThree() {
        val f = state(5)
        f.arm("first card after the camera bind")
        assertEquals(CardFocusState.End.RETRY, f.passEnded(false, 1 * min))
        assertTrue("retry on the next card", f.holdForCard(2 * min))
        assertEquals("retry 2 of 3 (the last card pass failed)", f.why)
        assertEquals(CardFocusState.End.RETRY, f.passEnded(false, 2 * min))
        assertEquals(CardFocusState.End.GAVE_UP, f.passEnded(false, 3 * min))
        assertFalse(f.isDue)
        assertEquals(3 * min, f.lastCardFocusNs)
        assertFalse("given up: no pass until the interval has passed since the last answer", f.holdForCard(7 * min))
        assertTrue(f.holdForCard(8 * min))
        assertTrue(f.why, f.why.startsWith("periodic: every 5 min"))
        // A fresh chain: three tries again.
        assertEquals(CardFocusState.End.RETRY, f.passEnded(false, 8 * min))
        assertEquals(CardFocusState.End.RETRY, f.passEnded(false, 9 * min))
        assertEquals(CardFocusState.End.GAVE_UP, f.passEnded(false, 10 * min))
    }

    /** A cancelled / superseded pass (null) answered nothing: the clock does not move. */
    @Test fun aCancelledPassIsNotAnAnswer() {
        val f = state(5)
        f.arm("first card after the camera bind")
        f.passEnded(true, 0)
        assertTrue(f.holdForCard(6 * min))
        assertEquals(CardFocusState.End.RETRY, f.passEnded(null, 6 * min))
        assertEquals(0L, f.lastCardFocusNs)
    }

    @Test fun aTapIsAFocusTooAndDisarms() {
        val f = state(5)
        f.arm("first card after the camera bind")
        f.disarm()                                       // the owner tapped: their point wins
        assertFalse(f.holdForCard(1 * min))
        f.tapAnswered(2 * min)
        assertEquals(2 * min, f.lastCardFocusNs)
        assertFalse(f.holdForCard(6 * min))
        assertTrue(f.holdForCard(7 * min))
    }

    @Test fun aFreshBindForgetsTheLastCardFocus() {
        val f = state(5)
        f.arm("first card after the camera bind")
        f.passEnded(true, 0)
        f.bound()
        assertNull(f.lastCardFocusNs)
        assertFalse("nothing armed by bound() itself, nothing due without an answer", f.holdForCard(60 * min))
    }

    @Test fun neverAndEveryCard() {
        val never = state(RefocusPolicy.NEVER)
        never.arm("first card after the camera bind")
        assertTrue("Never still focuses the first card", never.holdForCard(0))
        never.passEnded(true, 0)
        assertFalse(never.holdForCard(500 * min))
        assertTrue(lines.isEmpty())

        val every = state(RefocusPolicy.EVERY_CARD)
        every.arm("first card after the camera bind")
        every.passEnded(true, 0)
        for (i in 1..3) {
            assertTrue("card $i", every.holdForCard(i * s))
            assertEquals("periodic: every card", every.why)
            every.passEnded(true, i * s + 1)
        }
        assertEquals("one line per pass: $lines", 3, lines.size)
    }

    @Test fun theIntervalCanChangeWhileRunning() {
        val f = state(30)
        f.arm("first card after the camera bind")
        f.passEnded(true, 0)
        assertFalse(f.holdForCard(6 * min))
        f.minutes = 5
        assertTrue(f.holdForCard(6 * min))
    }

    @Test fun diagnostics() {
        val f = state(5)
        assertEquals("refocus every 5 min · last card focus none yet", f.describe(0))
        f.arm("x"); f.passEnded(true, 1 * min)
        assertEquals("refocus every 5 min · last card focus 3 min ago", f.describe(4 * min + 30 * s))
    }

    /**
     * The analysis thread asks while the main thread answers and arms: check-then-arm is one
     * step, so exactly one periodic pass is armed per due interval, never two, and an answer
     * never lands between the check and the arm.
     */
    @Test fun checkThenArmIsAtomic() {
        val f = state(RefocusPolicy.EVERY_CARD)
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val held = AtomicInteger()
        repeat(64) { pool.execute { go.await(); if (f.holdForCard(1)) held.incrementAndGet() } }
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals("every ask is held…", 64, held.get())
        assertEquals("…by ONE periodic arm: $lines", 1, lines.size)
    }
}
