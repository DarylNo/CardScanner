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
 * asks at every Tray trigger ([CardFocusState.holdFor], the analysis thread) and
 * tells it from the main thread (arms, passes started and ended, a tap, a bind).
 */
class CardFocusStateTest {
    private val min = 60_000_000_000L
    private val s = 1_000_000_000L
    private val lines = mutableListOf<String>()
    private fun state(minutes: Int = 5) = CardFocusState(log = { lines += it }).also { it.minutes = minutes }

    private companion object {
        /** L3a: the why of the pass that cut off a chain's last try. */
        const val LAST_PASS_WHY = "the pass that cut off try 3 of 3 — the chain gave up; it still focuses this card, counted for no chain"
        /** L2: the line when Never drops an armed periodic chain. */
        const val NEVER_DROPPED = "periodic refocus dropped — Refocus on a card is Never now: the next card shoots at once (no hold, no retry)"
    }

    /** One card pass, as the controller runs it: started at [at], ended with [ok] at [endAt]. */
    private fun CardFocusState.pass(ok: Boolean?, at: Long, endAt: Long = at): CardFocusState.End =
        passEnded(passStarted(at)!!.pass, ok, endAt)

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
        assertEquals("the first card's hold is the 1.5 s one", CardFocusState.Hold.CARD, f.holdFor(0))
        assertEquals(CardFocusState.End.FOCUSED, f.pass(true, 0, 10 * s))
        assertFalse(f.isDue)
        assertEquals(10 * s, f.lastCardFocusNs)
        assertFalse("4 min 59 s later: not yet", f.holdForCard(10 * s + 5 * min - s))
        assertTrue(lines.isEmpty())
        assertEquals("5 min later: held for a periodic pass", CardFocusState.Hold.PERIODIC, f.holdFor(10 * s + 5 * min + 50 * s))
        assertTrue(f.isDue)
        assertEquals("periodic: every 5 min — 5 min since the last focus on a card", f.why)
        assertEquals("one line when the periodic pass is armed: $lines", 1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("periodic refocus: every 5 min — 5 min since the last focus on a card"))
        // Still armed (no answer yet): the next trigger is held too — periodic — without arming or logging again.
        assertEquals(CardFocusState.Hold.PERIODIC, f.holdFor(10 * s + 6 * min))
        assertEquals(1, lines.size)
        assertEquals(CardFocusState.End.FOCUSED, f.pass(true, 10 * s + 6 * min))
        assertFalse("the clock starts again at that answer", f.holdForCard(10 * s + 10 * min))
        assertTrue(f.holdForCard(10 * s + 11 * min))
    }

    /** The interval never arms on top of a pass already armed (bind / new Area / zoom change / watchdog / retry). */
    @Test fun aPeriodicPassNeverDoubleArmsOnTopOfAnArmedOne() {
        val f = state(2)
        f.arm("first card after the camera bind")
        f.pass(true, 0)
        f.arm("first card in the new Area", resumesRefocus = true)      // 40 min later, a new Area — the interval is long past too
        assertEquals(CardFocusState.Hold.CARD, f.holdFor(40 * min))
        assertEquals("first card in the new Area", f.why)
        assertTrue("no periodic arm, no line: $lines", lines.isEmpty())
        f.arm("watchdog: a soft capture")
        assertEquals(CardFocusState.Hold.CARD, f.holdFor(41 * min))
        assertEquals("watchdog: a soft capture", f.why)
        assertTrue(lines.isEmpty())
    }

    /**
     * A NOT-focused answer is an answer: the interval restarts from it, so a lens that will not
     * converge is not asked again on every card — the failing chain still retries (3 tries in
     * all, the existing give-up), and the next chain comes one interval after the chain ENDED.
     * A periodic chain's retries are periodic holds too (they wait for the answer).
     */
    @Test fun aNotFocusedAnswerRestartsTheClock_theChainStillGivesUpAfterThree() {
        val f = state(5)
        f.arm("first card after the camera bind")
        assertEquals(CardFocusState.End.RETRY, f.pass(false, 1 * min))
        assertEquals("retry on the next card", CardFocusState.Hold.CARD, f.holdFor(2 * min))
        assertEquals("retry 2 of 3 (the last card pass failed)", f.why)
        assertEquals(CardFocusState.End.RETRY, f.pass(false, 2 * min))
        assertEquals(CardFocusState.End.GAVE_UP, f.pass(false, 3 * min))
        assertFalse(f.isDue)
        assertEquals(3 * min, f.lastCardFocusNs)
        assertEquals(3 * min, f.lastChainEndNs)
        assertFalse("given up: no pass until the interval has passed since the chain ended", f.holdForCard(7 * min))
        assertEquals(CardFocusState.Hold.PERIODIC, f.holdFor(8 * min))
        assertEquals("periodic: every 5 min — 5 min since the last card focus gave up", f.why)
        // A fresh chain: three tries again, each a periodic hold.
        assertEquals(CardFocusState.End.RETRY, f.pass(false, 8 * min))
        assertEquals(CardFocusState.Hold.PERIODIC, f.holdFor(9 * min))
        assertEquals(CardFocusState.End.RETRY, f.pass(false, 9 * min))
        assertEquals(CardFocusState.End.GAVE_UP, f.pass(false, 10 * min))
        assertFalse(f.holdForCard(14 * min))
        assertTrue(f.holdForCard(15 * min))
    }

    /**
     * Review of 1.1.18 (F1): a chain that never ANSWERS must not restart on the very next card. A lens
     * with no AF metering — the N200's camera 3, fixed focus: CameraX fails every AF-only pass at once
     * (IllegalArgumentException, no answer) — after one AE-metered tap answered: the first chain gives
     * up, and the next card must NOT start another chain (the old answer is long past the interval);
     * the next chain comes one interval after the give-up, and so on.
     */
    @Test fun aChainThatNeverAnswersWaitsAnIntervalAfterItGivesUp_F1() {
        val f = state(5)
        f.tapped()
        f.tapAnswered(0)                                          // the one answer this lens ever gives
        f.arm("first card in the new Area", resumesRefocus = true) // 10 min later, a new Area
        for (i in 1..3) {
            assertTrue("try $i", f.holdForCard(10 * min + i * s))
            val end = f.pass(null, 10 * min + i * s)               // no AF metering: no answer
            assertEquals("try $i", if (i < 3) CardFocusState.End.RETRY else CardFocusState.End.GAVE_UP, end)
        }
        assertEquals("no answer: the last card focus is still the tap", 0L, f.lastCardFocusNs)
        assertFalse("given up at 10 min 3 s: the next card shoots at once", f.holdForCard(10 * min + 10 * s))
        assertFalse(f.holdForCard(15 * min + 2 * s))
        assertTrue("one interval after the give-up", f.holdForCard(15 * min + 3 * s))
        for (i in 1..3) {
            if (i > 1) assertTrue("periodic try $i", f.holdForCard(15 * min + (3 + i) * s))
            f.pass(null, 15 * min + (3 + i) * s)
        }
        assertFalse("given up again at 15 min 6 s", f.isDue)
        assertFalse(f.holdForCard(15 * min + 10 * s))
        assertFalse(f.holdForCard(20 * min + 5 * s))
        assertTrue(f.holdForCard(20 * min + 6 * s))
    }

    /** A cancelled pass (null) answered nothing: the clock does not move, but it is a failure — through to the give-up. */
    @Test fun aCancelledPassIsNotAnAnswer() {
        val f = state(5)
        f.arm("first card after the camera bind")
        f.pass(true, 0)
        assertTrue(f.holdForCard(6 * min))
        assertEquals(CardFocusState.End.RETRY, f.pass(null, 6 * min))
        assertEquals(0L, f.lastCardFocusNs)
        assertTrue(f.holdForCard(6 * min + 10 * s))
        assertEquals(CardFocusState.End.RETRY, f.pass(null, 6 * min + 10 * s))
        assertTrue(f.holdForCard(6 * min + 20 * s))
        assertEquals(CardFocusState.End.GAVE_UP, f.pass(null, 6 * min + 20 * s))
        assertEquals("still no answer since the first card", 0L, f.lastCardFocusNs)
        assertFalse("the give-up restarts the interval (F1)", f.holdForCard(6 * min + 30 * s))
        assertFalse(f.holdForCard(11 * min + 19 * s))
        assertTrue(f.holdForCard(11 * min + 20 * s))
    }

    /**
     * Review of 1.1.18 (F2): CameraX answers a pass that never converges only after 5 s, the first
     * card's hold gives up at 1.5 s, and the NEXT card's pass then cuts the old one off (CameraX
     * cancels it). Each cut-off pass counts as ended with no answer — a failure toward the give-up —
     * so the chain ends after three, not never (before: every card was held, forever). The cut-off
     * pass's own late end is CUT_OFF: nothing counted twice, and it must not release the newer hold.
     * The pass that cut off the last try still runs on its card; its end counts for no chain, and its
     * why says so (L3a).
     */
    @Test fun passesCutOffByTheNextCardEndTheChain_F2() {
        val f = state(5)
        f.arm("first card after the camera bind")
        assertTrue(f.holdForCard(0))
        val p1 = f.passStarted(0)!!
        assertNull("nothing was out", p1.cutOff)
        // 1.5 s: the hold shoots; the pass sweeps on. 3 s: the next card.
        assertTrue("the next card is held: the chain is still due", f.holdForCard(3 * s))
        val p2 = f.passStarted(3 * s)!!
        assertEquals("pass 1 is cut off by pass 2: a failure", CardFocusState.End.RETRY, p2.cutOff)
        assertEquals("retry 2 of 3 (the last card pass was cut off by the next card)", f.why)
        assertEquals("pass 1's own (cancelled) end comes later: already counted", CardFocusState.End.CUT_OFF,
            f.passEnded(p1.pass, null, 3 * s + 1))
        assertTrue(f.holdForCard(6 * s))
        val p3 = f.passStarted(6 * s)!!
        assertEquals(CardFocusState.End.RETRY, p3.cutOff)
        assertTrue(f.holdForCard(9 * s))
        val p4 = f.passStarted(9 * s)!!
        assertEquals("the third cut-off gives up", CardFocusState.End.GAVE_UP, p4.cutOff)
        assertFalse(f.isDue)
        // Re-review of 1.1.18 (L3a): pass 4 still runs on its card (starting it is what cancels pass 3, still
        // sweeping) — its why says what it is, not the stale "retry 3 of 3 …" of the chain it ended.
        assertEquals(LAST_PASS_WHY, f.why)
        assertEquals("the pass carries its own why (the controller logs it)", LAST_PASS_WHY, p4.pass.why)
        assertEquals("retry 2 of 3 (the last card pass was cut off by the next card)", p2.pass.why)
        assertFalse("the chain is over: the next card shoots at once", f.holdForCard(12 * s))
        assertEquals(CardFocusState.End.CUT_OFF, f.passEnded(p3.pass, null, 9 * s + 1))
        // Pass 4 (it cut off the last try) answers at last — NOT focused after CameraX's 5 s: a plain answer.
        assertEquals(CardFocusState.End.OUTSIDE, f.passEnded(p4.pass, false, 14 * s))
        assertEquals(14 * s, f.lastCardFocusNs)
        assertFalse(f.holdForCard(5 * min + 13 * s))
        assertTrue("one interval after the latest of the give-up and that answer", f.holdForCard(5 * min + 14 * s))
    }

    /** A pass of a chain that a new arm replaced counts for no chain (a new Area / zoom change while it ran). */
    @Test fun aPassOfAnOlderChainCountsForNoChain() {
        val f = state(5)
        f.arm("first card after the camera bind")
        val p = f.passStarted(0)!!.pass
        f.arm("first card in the new Area", resumesRefocus = true)
        assertEquals(CardFocusState.End.OUTSIDE, f.passEnded(p, false, 2 * s))
        assertTrue("the new Area's pass is still due", f.isDue)
        assertEquals("first card in the new Area", f.why)
        assertEquals(CardFocusState.End.FOCUSED, f.pass(true, 3 * s))
    }

    /**
     * Review of 1.1.18 (F4): a tap lock is the owner's focus point. It disarms the card pass AND pauses
     * "Refocus on a card" — before, it only restarted the interval, so 5 min later a periodic pass threw
     * the tap away. The pause holds until a new Area or a camera re-open — leaving the scan screen and
     * coming back (the arms that re-lock elsewhere, `resumesRefocus`; a zoom change and the probe too).
     * NOT a Tray ↔ Tap to scan switch (re-review L1: that is CameraController.setAuto, which arms
     * nothing — the words promised it). The watchdog still arms its one pass on a soft capture but
     * does not lift it. Logged once per pause; Diagnostics says so.
     */
    @Test fun aTapPausesRefocusUntilANewAreaOrACameraReOpen_F4() {
        val f = state(5)
        f.arm("first card after the camera bind", resumesRefocus = true)
        f.pass(true, 0)
        assertTrue("the pause began", f.tapped())
        assertFalse(f.isDue)
        f.tapAnswered(1 * min)
        assertTrue(f.isPaused)
        assertFalse("6 min after the tap: still the tap's lock", f.holdForCard(6 * min))
        assertFalse(f.holdForCard(600 * min))
        assertFalse("a second tap: no second line", f.tapped())
        val paused = lines.filter { it.startsWith("refocus paused — you tapped to focus") }
        assertEquals("logged once: $lines", 1, paused.size)
        assertEquals("refocus paused — you tapped to focus; no periodic pass until a new Area or a camera re-open " +
            "(leaving the scan screen and coming back) — Refocus on a card: Every 5 min", paused[0])
        assertEquals("refocus paused — you tapped to focus (every 5 min) · last card focus 9 min ago", f.describe(10 * min))
        // The watchdog's one pass still runs on a soft capture — and the pause stays.
        f.arm("watchdog: a soft capture")
        assertEquals(CardFocusState.Hold.CARD, f.holdFor(601 * min))
        f.pass(true, 601 * min)
        assertTrue(f.isPaused)
        assertFalse(f.holdForCard(700 * min))
        // Every card is paused too.
        f.minutes = RefocusPolicy.EVERY_CARD
        assertFalse(f.holdForCard(701 * min))
        f.minutes = 5
        // A new Area lifts it (one line) and starts its own first-card pass; the interval runs again from that.
        f.arm("first card in the new Area", resumesRefocus = true)
        assertFalse(f.isPaused)
        assertEquals(1, lines.count { it == "refocus on a card resumed — first card in the new Area" })
        assertEquals(CardFocusState.Hold.CARD, f.holdFor(702 * min))
        f.pass(true, 702 * min)
        assertFalse(f.holdForCard(706 * min))
        assertEquals(CardFocusState.Hold.PERIODIC, f.holdFor(707 * min))
        assertEquals("refocus every 5 min · last card focus 5 min ago", f.describe(707 * min))
    }

    /**
     * Re-review of 1.1.18 (L2): switching "Refocus on a card" to Never drops a PERIODIC chain already
     * armed — before, the setter only changed the interval and holdFor kept answering PERIODIC for the
     * armed chain, so a pinned Never (the zoom steps), a PATCH or Settings still held the next card up
     * to 5.5 s, retries included. Logged once.
     */
    @Test fun settingNeverDropsAnArmedPeriodicChain_L2() {
        val f = state(5)
        f.arm("first card after the camera bind")
        f.pass(true, 0)
        assertEquals(CardFocusState.Hold.PERIODIC, f.holdFor(6 * min))
        assertEquals("the periodic pass fails: a retry is owed", CardFocusState.End.RETRY, f.pass(false, 6 * min, 6 * min + 5 * s))
        assertEquals(CardFocusState.Hold.PERIODIC, f.holdFor(6 * min + 10 * s))
        lines.clear()
        f.minutes = RefocusPolicy.NEVER
        assertFalse("Never: the armed periodic chain is dropped", f.isDue)
        assertNull("the next card shoots at once", f.holdFor(6 * min + 11 * s))
        assertNull(f.holdFor(600 * min))
        assertEquals(listOf(NEVER_DROPPED), lines)
        f.minutes = RefocusPolicy.NEVER
        assertEquals("nothing armed: no second line", 1, lines.size)
    }

    /** L2: a pass of the dropped chain still out ends OUTSIDE — counted for no chain; its answer still moves the clock. */
    @Test fun settingNeverMidPassCountsThatPassForNoChain_L2() {
        val f = state(RefocusPolicy.EVERY_CARD)
        f.arm("first card after the camera bind")
        f.pass(true, 0)
        assertEquals(CardFocusState.Hold.PERIODIC, f.holdFor(10 * s))
        val p = f.passStarted(10 * s)!!.pass
        f.minutes = RefocusPolicy.NEVER
        assertFalse("Never: the chain is dropped mid-pass", f.isDue)
        assertEquals("its chain is over", CardFocusState.End.OUTSIDE, f.passEnded(p, false, 15 * s))
        assertEquals("an answer still moves the clock", 15 * s, f.lastCardFocusNs)
        assertNull(f.holdFor(20 * s))
    }

    /** L2: Never leaves a chain of another kind alone — the first card after a bind / new Area, the watchdog, their retries. */
    @Test fun settingNeverLeavesAFirstCardOrWatchdogChainAlone_L2() {
        val f = state(5)
        f.arm("first card in the new Area", resumesRefocus = true)
        f.minutes = RefocusPolicy.NEVER
        assertTrue("Never still focuses the first card", f.isDue)
        assertEquals(CardFocusState.Hold.CARD, f.holdFor(0))
        assertEquals(CardFocusState.End.RETRY, f.pass(false, 0))
        f.minutes = 5
        f.minutes = RefocusPolicy.NEVER
        assertTrue("…and its retry", f.isDue)
        assertEquals(CardFocusState.End.FOCUSED, f.pass(true, 1 * s))
        f.arm("watchdog: a soft capture")
        f.minutes = 10
        f.minutes = RefocusPolicy.NEVER
        assertEquals(CardFocusState.Hold.CARD, f.holdFor(2 * s))
        assertTrue(lines.toString(), lines.none { it == NEVER_DROPPED })
    }

    /**
     * Re-review of 1.1.18 (L4, pre-existing): the analyzer's focus request reaches the main thread
     * after the hold began. A tap in between disarms the pass (the owner's lock) — and the request
     * must then start NO card pass, or it would replace the tapped point; nor after an older pass's
     * answer ended the chain meanwhile. passStarted says so (null: none due); the controller then
     * releases the hold and starts nothing.
     */
    @Test fun aPassAskedForAfterATapOrAnEndedChainStartsNothing_L4() {
        val f = state(5)
        f.arm("first card after the camera bind", resumesRefocus = true)
        assertEquals(CardFocusState.Hold.CARD, f.holdFor(0))
        f.tapped()                                                    // the owner taps before the request is handled
        assertNull("no card pass after a tap", f.passStarted(1 * s))
        assertFalse(f.isDue)
        // An older pass answers while the next card's request is on its way.
        f.arm("first card in the new Area", resumesRefocus = true)
        assertEquals(CardFocusState.Hold.CARD, f.holdFor(2 * s))
        val p1 = f.passStarted(2 * s)!!.pass                          // card 1's pass; its 1.5 s hold shoots without it
        assertEquals(CardFocusState.Hold.CARD, f.holdFor(5 * s))       // card 2: still due, held
        assertEquals(CardFocusState.End.FOCUSED, f.passEnded(p1, true, 5 * s + 1))   // pass 1 answers first
        assertNull("the chain is over: card 2's request starts nothing", f.passStarted(5 * s + 2))
        assertEquals("nothing was cut off: pass 1 already ended", 5 * s + 1, f.lastCardFocusNs)
    }

    @Test fun aFreshBindForgetsTheLastCardFocus() {
        val f = state(5)
        f.arm("first card after the camera bind")
        f.pass(true, 0)
        f.bound()
        assertNull(f.lastCardFocusNs)
        assertNull(f.lastChainEndNs)
        assertFalse("nothing armed by bound() itself, nothing due without an answer", f.holdForCard(60 * min))
    }

    @Test fun neverAndEveryCard() {
        val never = state(RefocusPolicy.NEVER)
        never.arm("first card after the camera bind")
        assertTrue("Never still focuses the first card", never.holdForCard(0))
        never.pass(true, 0)
        assertFalse(never.holdForCard(500 * min))
        assertTrue(lines.isEmpty())

        val every = state(RefocusPolicy.EVERY_CARD)
        every.arm("first card after the camera bind")
        every.pass(true, 0)
        for (i in 1..3) {
            assertEquals("card $i", CardFocusState.Hold.PERIODIC, every.holdFor(i * s))
            assertEquals("periodic: every card", every.why)
            every.pass(true, i * s, i * s + 1)
        }
        assertEquals("one line per pass: $lines", 3, lines.size)
    }

    @Test fun theIntervalCanChangeWhileRunning() {
        val f = state(30)
        f.arm("first card after the camera bind")
        f.pass(true, 0)
        assertFalse(f.holdForCard(6 * min))
        f.minutes = 5
        assertTrue(f.holdForCard(6 * min))
    }

    @Test fun diagnostics() {
        val f = state(5)
        assertEquals("refocus every 5 min · last card focus none yet", f.describe(0))
        f.arm("x"); f.pass(true, 1 * min)
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
