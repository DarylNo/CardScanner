package io.github.darylno.cardscanner.ui

import androidx.test.core.app.ApplicationProvider
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.server.DeviceApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The guided measurement session: steps in order, captures counted per step,
 * a step's settings applied on entry and the owner's own restored at the end,
 * every transition marked in the log, and the state surviving a new instance
 * (the scan screen can be left and reopened mid-session).
 */
@RunWith(RobolectricTestRunner::class)
class MeasureSessionTest {
    private val app = ApplicationProvider.getApplicationContext<App>()
    private val settings = app.settings
    private val log = DebugLog()
    private var now = 1_000_000L
    private fun session() = MeasureSession(settings, log, clock = { now })
    private fun lines() = log.all().map { it.msg }

    @Test
    fun walksTheStepsAppliesTheirSettingsAndRestoresTheOwners() {
        settings.aeLock = true; settings.vibration = false; settings.checkMs = 4000
        val m = session()
        assertFalse(m.active)
        m.start()
        assertTrue(m.active); assertEquals(0, m.index)
        // Step 1 wants AE lock off, vibration on, the default check time.
        assertFalse(settings.aeLock); assertTrue(settings.vibration); assertEquals(DeviceApi.CHECK_MS_DEFAULT, settings.checkMs)
        assertTrue(lines().any { it.startsWith("session START") && it.contains("AE lock true") })
        assertTrue(lines().any { it.startsWith("step 1/${m.steps.size} START setup") })
        m.answer(true)
        m.captured(); m.captured(); m.captured()
        assertEquals(3, m.captures)
        now += 45_000
        m.next()
        assertEquals(1, m.index); assertEquals(0, m.captures); assertNull(m.answer)
        assertTrue(lines().any { it.startsWith("step 1/${m.steps.size} END setup (done) after 45 s: 3 capture(s)") && it.endsWith("answer YES") })
        assertEquals(DeviceApi.CHECK_MS_MIN, settings.checkMs)                      // "short check time"
        m.next()
        assertEquals(DeviceApi.CHECK_MS_DEFAULT, settings.checkMs)                  // swaps: back to the default
        assertEquals("swaps", m.step.id); assertEquals(20, m.step.target)
        m.back()
        assertEquals("short-check", m.step.id)
        assertTrue(lines().any { it.contains("END swaps (went back)") })
        // Run to the end: the AE-lock step turns it on, Finish restores the owner's own.
        while (m.active && !m.isLast) m.next()
        assertTrue(m.isLast); assertTrue(settings.aeLock)
        m.next()
        assertFalse(m.active)
        assertTrue(settings.aeLock); assertFalse(settings.vibration); assertEquals(4000, settings.checkMs)
        assertTrue(lines().last().startsWith("session END (all ${m.steps.size} steps done"))
        assertEquals(-1, settings.measureStep); assertNull(settings.measureOriginal)
    }

    @Test
    fun stopRestoresTheSettingsAndSaysWhereItStopped() {
        settings.aeLock = false; settings.vibration = true; settings.checkMs = 1500
        val m = session()
        m.start(); m.next(); m.next()                       // on step 3
        assertEquals(DeviceApi.CHECK_MS_DEFAULT, settings.checkMs)
        m.finish(abandoned = true)
        assertFalse(m.active)
        assertEquals(1500, settings.checkMs)
        assertTrue(lines().any { it.contains("END swaps (stopped)") })
        assertTrue(lines().last().startsWith("session END (stopped at step 3"))
    }

    @Test
    fun theSessionSurvivesANewInstance() {
        val a = session()
        a.start(); a.next(); a.captured(); a.captured()
        val b = session()                                   // the screen was closed and reopened
        assertTrue(b.active); assertEquals(1, b.index); assertEquals(2, b.captures)
        b.finish(abandoned = true)
        assertFalse(session().active)
    }

    @Test
    fun captureAndAnswerDoNothingWithoutASession() {
        val m = session()
        m.captured(); m.answer(true); m.next(); m.back()
        assertFalse(m.active); assertEquals(0, lines().size)
    }

    /** Next until the step [id] (bounded: a step list without it stops on its last step). */
    private fun MeasureSession.goTo(id: String) {
        repeat(steps.size) { if (active && step.id != id && !isLast) next() }
    }

    /**
     * The zoom A/B (1.1.17): every zoom step pins "Zoom to fit the Area" — A1, the probe
     * step and A2 OFF, B ON (and the set-up and AE-lock steps OFF) — so B's zoom never
     * leaks into another step, Back included; AE lock is off on all four; Stop or Finish
     * puts the owner's own switch back, whichever it was.
     */
    @Test
    fun theZoomStepsSwitchZoomToFitAndTheOwnersIsRestored() {
        settings.zoomFit = true; settings.aeLock = true
        val m = session()
        m.start()
        assertFalse("the set-up step pins Zoom to fit off", settings.zoomFit)
        assertTrue(lines().any { it.startsWith("session START") && it.contains("zoom to fit true") && it.contains("AE lock true") })
        m.goTo("zoom-a1")
        assertEquals("zoom-a1", m.step.id)
        assertFalse(settings.zoomFit); assertFalse(settings.aeLock)
        assertEquals("the A1 arm counts the same 10 cards", 10, m.step.target)
        m.next()
        assertEquals("zoom-probe", m.step.id); assertFalse(settings.zoomFit); assertFalse(settings.aeLock)
        m.next()
        assertEquals("zoom-b", m.step.id)
        assertTrue("B pins it on", settings.zoomFit); assertFalse(settings.aeLock)
        assertTrue(lines().any { it.contains(" START zoom-b: ") && it.endsWith("[AE lock false, zoom to fit on]") })
        m.back()
        assertEquals("zoom-probe", m.step.id)
        assertFalse("Back to the probe step: off again", settings.zoomFit)
        m.next(); assertTrue(settings.zoomFit)
        m.next()
        assertEquals("zoom-a2", m.step.id); assertFalse(settings.zoomFit)
        m.next()
        assertEquals("ae-lock", m.step.id); assertFalse("the AE-lock step pins it off too", settings.zoomFit); assertTrue(settings.aeLock)
        assertEquals("done", m.steps[m.index + 1].id)
        m.back(); m.back()
        assertEquals("zoom-b", m.step.id); assertTrue(settings.zoomFit)
        m.finish(abandoned = true)
        assertTrue("the owner's own (on) is back", settings.zoomFit); assertTrue(settings.aeLock)
        // An owner with it off gets it off back, even when stopped on B.
        settings.zoomFit = false
        val n = session()
        n.start(); n.goTo("zoom-b")
        assertTrue(settings.zoomFit)
        n.finish(abandoned = true)
        assertFalse(settings.zoomFit)
        // Finishing the whole session restores it too.
        settings.zoomFit = true
        val f = session()
        f.start()
        while (f.active && !f.isLast) f.next()
        assertFalse(settings.zoomFit)
        f.next()
        assertFalse(f.active); assertTrue(settings.zoomFit)
    }

    /**
     * A session started on 1.1.16 kept "aeLock,vibration,checkMs" — three fields. Its
     * Stop still restores those three, and leaves Zoom to fit (which it never saved) alone.
     */
    @Test
    fun aSessionStartedOnAnOlderBuildStillRestores() {
        settings.aeLock = false; settings.vibration = true; settings.checkMs = 1000; settings.zoomFit = true
        settings.measureStep = 3
        settings.measureOriginal = "true,false,4000"
        val m = session()
        assertTrue(m.active)
        m.finish(abandoned = true)
        assertFalse(m.active)
        assertTrue(settings.aeLock); assertFalse(settings.vibration); assertEquals(4000, settings.checkMs)
        assertTrue("a 3-field original leaves Zoom to fit alone", settings.zoomFit)
    }

    /**
     * A 1.1.16 session resumed on this build walks INTO steps that pin Zoom to fit (the
     * zoom steps, AE lock, set-up on Back) — its 3-field original never saved the switch,
     * so the first pin saves the owner's own first, and Stop / Finish put it back (review
     * of 9df0293: it was left wherever the last zoom step had put it — off). The update
     * lock makes this likely: it stops a 1.1.16 phone scanning mid-session.
     */
    @Test
    fun aSessionResumedFromAnOlderBuildPutsTheOwnersZoomToFitBack() {
        val prefs = app.getSharedPreferences("ui_settings", android.content.Context.MODE_PRIVATE)
        /** A 1.1.16 session on its step [index] (its own list: LEGACY_IDS), no step id. */
        fun legacy(index: Int, zoomFit: Boolean): MeasureSession {
            settings.zoomFit = zoomFit
            settings.measureStep = index
            prefs.edit().remove("measure_step_id").apply()
            settings.measureOriginal = "false,true,2500"
            return session().also { assertTrue(it.active); assertEquals(MeasureSession.LEGACY_IDS[index], it.step.id) }
        }
        // At "lights", Next through the zoom steps (B pins it on, A2 / AE lock off) to Finish.
        val walked = legacy(6, zoomFit = true)
        while (walked.active && !walked.isLast) walked.next()
        assertFalse("AE lock pinned it off", settings.zoomFit)
        walked.next()
        assertFalse(walked.active)
        assertTrue("the owner's own (on) is back after Finish", settings.zoomFit)
        assertFalse(settings.aeLock); assertTrue(settings.vibration); assertEquals(2500, settings.checkMs)
        // At 1.1.16's "ae-lock", Back once lands on zoom-a2 (off), then Stop.
        val back = legacy(8, zoomFit = true)
        back.back()
        assertEquals("zoom-a2", back.step.id); assertFalse(settings.zoomFit)
        back.finish(abandoned = true)
        assertTrue("the owner's own (on) is back after Stop", settings.zoomFit)
        // At "lift", Back to set-up (it pins it off now), then Stop.
        val setup = legacy(3, zoomFit = true)
        repeat(3) { setup.back() }
        assertEquals("setup", setup.step.id); assertFalse(settings.zoomFit)
        setup.finish(abandoned = true)
        assertTrue(settings.zoomFit)
        // An owner with it OFF, stopped on B (on): off again.
        val off = legacy(7, zoomFit = false)
        off.goTo("zoom-b")
        assertTrue(settings.zoomFit)
        off.finish(abandoned = true)
        assertFalse(settings.zoomFit)
        assertNull(settings.measureOriginal)
    }

    /**
     * The step is resumed by its id, not a bare index: four steps went in before "ae-lock",
     * so an index taken from another step list lands somewhere else. A session saved by
     * 1.1.16 (an index, no id) is read against 1.1.16's own list.
     */
    @Test
    fun aResumedSessionStaysOnItsStepAcrossTheNewSteps() {
        val prefs = app.getSharedPreferences("ui_settings", android.content.Context.MODE_PRIVATE)
        session().start()
        settings.measureStep = 2                                    // stale: "swaps" here
        prefs.edit().putString("measure_step_id", "ae-lock").apply()
        val m = session()
        assertEquals("ae-lock", m.step.id)
        m.next()
        assertEquals("done", m.step.id)
        m.finish(abandoned = true)
        // 1.1.16 saved index 8 = its "ae-lock" (setup … foil, ae-lock, done) and no id.
        prefs.edit().remove("measure_step_id").apply()
        settings.measureStep = 8
        settings.measureOriginal = "false,true,2500"
        val old = session()
        assertTrue(old.active)
        assertEquals("ae-lock", old.step.id)
        old.next()
        assertEquals("done", old.step.id)
    }

    /**
     * "Run the zoom steps (about 10 min)": A1, the probe step, B, A2 and a done step — no
     * other step — with the same pins as in the full session; the plan survives a new
     * instance and Finish puts the full list back for the next session.
     */
    @Test
    fun theZoomPlanRunsOnlyTheZoomSteps() {
        settings.zoomFit = false; settings.aeLock = true; settings.checkMs = 4000
        val m = session()
        m.start(MeasureSession.Plan.ZOOM)
        assertEquals(listOf("zoom-a1", "zoom-probe", "zoom-b", "zoom-a2", "zoom-done"), m.steps.map { it.id })
        assertEquals(listOf("A1", null, "B", "A2", null), m.steps.map { it.arm })
        assertEquals(MeasureSession.Action.PROBE, m.steps[1].action)
        assertTrue("the probe step says it is a placeholder", m.steps[1].instruction.contains("not built"))
        assertEquals("zoom-a1", m.step.id)
        assertFalse(settings.aeLock); assertFalse(settings.zoomFit)
        assertEquals("the owner's check time is left alone", 4000, settings.checkMs)
        assertTrue(lines().any { it.startsWith("session START") && it.contains("the zoom steps") && it.contains("zoom to fit false") })
        assertEquals(MeasureSession.Plan.ZOOM, session().plan)          // the screen was closed and reopened
        m.next(); m.next()
        assertEquals("zoom-b", session().step.id); assertTrue(settings.zoomFit)
        while (m.active && !m.isLast) m.next()
        assertEquals("zoom-done", m.step.id)
        m.next()
        assertFalse(m.active)
        assertTrue(lines().last().startsWith("session END (all 5 steps done, 5/5"))
        assertTrue(settings.aeLock); assertFalse(settings.zoomFit)
        assertEquals("the next session is the full one again", MeasureSession.Plan.FULL, session().plan)
        assertEquals("setup", session().steps.first().id)
    }

    /** One arm's 10 cards: capture → queued → identified, as the scan screen reports them. */
    private fun MeasureSession.arm(firstBurst: Long, z: Double, h: Int, sd: Double, ranks: List<Int?>, names: List<String?> = ranks.indices.map { "c${it + 1}" }) {
        for ((i, r) in ranks.withIndex()) {
            val burst = firstBurst + i
            captured(burst, manual = false, zoom = z, cardLongPx = h, stripDetail = sd, iso = 400)
            queued("job$burst", burst)
        }
        // Outcomes land in any order; they are matched by job id.
        for ((i, r) in ranks.withIndex().reversed()) {
            if (r == null) continue                                   // still identifying
            val burst = firstBurst + i
            identified("job$burst", rank = r, name = names[i], ocr = r == 3, autoPicked = r >= 2, printings = 4)
        }
    }

    /**
     * The A/B tally: Tray captures only (a shutter capture is flagged and left out),
     * outcomes matched by job id, cards paired by name across A1 / B / A2, a card whose
     * two 1× runs disagree left out as unstable, each written-down rule printed ✓/✗,
     * "still identifying n", and each arm's END line naming its burst range before the
     * answer. The probe half is not built yet, so the verdict can only be NO.
     */
    @Test
    fun theAbTallyPairsCardsAndAppliesTheRules() {
        val m = session()
        m.start(MeasureSession.Plan.ZOOM)
        m.arm(1, 1.0, 612, 212.0, listOf(3, 1, 1, 1, 1, 2, 0, 3, 1, 1))
        m.identified("job999", rank = 3, name = "nobody", ocr = true, autoPicked = true, printings = 2)   // no such job: ignored
        m.next(); m.next()                                                                              // the probe step, then B
        m.captured(50, manual = true, zoom = 1.9, cardLongPx = 1100, stripDetail = 300.0, iso = 400)   // a shutter capture
        m.queued("job50", 50); m.identified("job50", rank = 3, name = "cX", ocr = true, autoPicked = true, printings = 3)
        m.arm(11, 1.9, 1163, 344.0, listOf(3, 3, 3, 3, 3, 2, 1, 3, 1, null))
        m.answer(true)
        m.next()
        assertTrue(lines().any { it.contains("END zoom-b (done)") && it.contains(" · captures #11–#50 · answer YES") && it.endsWith("answer YES") })
        m.arm(21, 1.0, 612, 212.0, listOf(3, 1, 1, 1, 1, 2, 0, 3, 3, 1))
        val s = m.abSummary()
        assertEquals(10, s.cards.size)
        val words = s.summaryLine()
        assertEquals("zoom A/B (same 10 cards; A1 1.00× / B 1.90× / A2 1.00×): card 612 → 1163 px long (×1.90) · strip detail 212 → 344 · " +
            "ISO 400 → 400 · OCR ✓ 2 → 6 → 3 · auto-filed 3 → 7 → 4 · no card/no match 1 → 0 → 1 · unstable at 1× 1 · " +
            "B better 5 / worse 0 · still identifying 1 · left out: 1 shutter capture", words)
        val per = s.perCardLine()
        assertTrue(per, per.contains("2 c2 A1 1 · B 3 · A2 1;"))
        assertTrue(per, per.contains("9 c9 A1 1 · B 1 · A2 3 (unstable at 1×);"))
        assertTrue(per, per.endsWith("10 c10 A1 1 · B … · A2 1"))
        assertEquals(listOf(true, true, true, true, true, true), s.checks.map { it.ok })
        val v = s.verdictLine()
        assertTrue(v, v.startsWith("zoom VERDICT: Zoom to fit ON by default: NO — the A/B half passes; the probe half is not measured"))
        for (want in listOf("✓ B worse on none", "✓ B better − worse = 5 (needs 4)", "✓ no extra B captures (10 of ×10)",
                "✓ no more no-card/no-match in B (0 vs A1 1 / A2 1)", "✓ B zoomed 1.90× (A at 1.00×)", "✓ post-fit within ±3 %"))
            assertTrue("$want in $v", v.contains(want))
        // One stable card worse at B, two cards fewer better: rules 1 and 2 fail.
        val worse = MeasureSession.abSummary(m.records.map {
            when {
                it.arm == "B" && it.name == "c1" -> it.copy(rank = 1, ocr = false, autoPicked = false)
                it.arm == "B" && it.name in listOf("c2", "c3") -> it.copy(rank = 1, ocr = false, autoPicked = false)
                else -> it
            }
        })
        assertEquals(listOf(false, false, true, true, true, true), worse.checks.map { it.ok })
        assertTrue(worse.verdictLine(), worse.verdictLine().contains("NO — the A/B half fails · ✗ B worse on 1 (needs none) · ✗ B better − worse = 2 (needs 4)"))
        // B never zoomed (an Area round the whole tray): void, whatever else it says.
        val flat = MeasureSession.abSummary(m.records.map { if (it.arm == "B") it.copy(z = 1.0, h = 612) else it })
        assertTrue(flat.verdictLine(), flat.verdictLine().startsWith("zoom VERDICT: void — B ran at 1.00×: draw a tighter Area"))
        // A1 already OCR-confirms nearly every card: zoom has no job here.
        val easy = MeasureSession.abSummary(m.records.map { if (it.name != "c7") it.copy(rank = 3, ocr = true, autoPicked = true) else it })
        assertTrue(easy.verdictLine(), easy.verdictLine().contains("zoom has no job at this mount height"))
        // An arm with no Tray captures: incomplete.
        val noA2 = MeasureSession.abSummary(m.records.filter { it.arm != "A2" })
        assertTrue(noA2.verdictLine(), noA2.verdictLine().startsWith("zoom VERDICT: incomplete — no Tray captures in A2"))
        // Re-entering an arm (Next then Back) clears its records for the re-run; the others stay.
        m.next(); m.back()
        assertEquals("zoom-a2", m.step.id)
        assertEquals(0, m.records.count { it.arm == "A2" })
        assertEquals(11, m.records.count { it.arm == "B" })
    }

    /**
     * The card left on the tray scanned AGAIN when the zoom came on (the B question's
     * NO): that capture is an extra B capture — rule 3 fails — and never takes the 10th
     * card's place; a B capture identified as another name is a name mismatch, left out.
     */
    @Test
    fun anExtraBCaptureIsCountedNotPairedAndAWrongNameIsLeftOut() {
        val a1 = (1..10).map { AbRecord("A1", it.toLong(), 1.0, 612, 200.0, 400, false, rank = 1, name = "c$it") }
        val a2 = (1..10).map { AbRecord("A2", 40L + it, 1.0, 612, 200.0, 400, false, rank = 1, name = "c$it") }
        val b = listOf(AbRecord("B", 20, 1.9, 1163, 300.0, 400, false, rank = 3, name = "c10")) +       // the left card, again
            (1..10).map { AbRecord("B", 20L + it, 1.9, 1163, 300.0, 400, false, rank = 3, name = if (it == 4) "Wrong Card" else "c$it") }
        val s = MeasureSession.abSummary(a1 + b + a2)
        assertEquals(1, s.extraB)
        assertEquals(10, s.cards.size)
        assertEquals("the 10th card keeps its own B capture", 30L, s.cards[9].b!!.burst)
        assertTrue("c4's B capture is the mismatch", s.cards[3].mismatch)
        assertEquals("every card but the mismatch, the 10th once", 9, s.better)
        assertFalse(s.checks[2].ok)
        assertEquals("extra B captures (11 of ×10)", s.checks[2].words)
        assertTrue(s.summaryLine(), s.summaryLine().endsWith("left out: 1 name mismatch, 1 extra B capture"))
        assertTrue(s.perCardLine(), s.perCardLine().contains("4 c4 A1 1 · B 3 · A2 1 (name mismatch: c4 / Wrong Card)"))
    }

    /**
     * Captures still identifying (or whose outcome was missed — no name, no rank) take
     * their cards' places by position, however many sit side by side: they are "still
     * identifying", never "missing" (rank 0). Review of 9df0293: two adjacent ones in B
     * were scored as two extra B captures plus two cards B made worse — rule 1 failed on
     * no evidence — and two at A2's tail became two phantom "?" cards and two unstable ones.
     */
    @Test
    fun capturesStillIdentifyingSideBySideTakeTheirCardsPlaces() {
        fun rec(arm: String, burst: Long, z: Double, rank: Int?, card: Int) =
            AbRecord(arm, burst, z, if (z > 1) 1163 else 612, 200.0, 400, false, rank = rank, name = if (rank == null) null else "c$card")
        val a1 = (1..10).map { rec("A1", it.toLong(), 1.0, 1, it) }
        val a2 = (1..10).map { rec("A2", 40L + it, 1.0, 1, it) }
        // B: cards 5 and 6 still identifying, side by side.
        val b = (1..10).map { rec("B", 20L + it, 1.9, if (it == 5 || it == 6) null else 3, it) }
        val s = MeasureSession.abSummary(a1 + b + a2)
        assertEquals(10, s.cards.size)
        assertEquals("no extra B captures", 0, s.extraB)
        assertEquals("still identifying is not worse", 0, s.worse)
        assertEquals(8, s.better); assertEquals(2, s.pending)
        assertEquals(listOf(25L, 26L), listOf(s.cards[4].b?.burst, s.cards[5].b?.burst))
        val per = s.perCardLine()
        assertTrue(per, per.contains("5 c5 A1 1 · B … · A2 1; 6 c6 A1 1 · B … · A2 1;"))
        assertTrue(s.checks[0].words, s.checks[0].ok)
        assertTrue(s.checks[2].words, s.checks[2].ok)
        assertFalse(s.summaryLine(), s.summaryLine().contains("left out"))
        // A2: the last two still identifying.
        val tail = MeasureSession.abSummary(a1 + (1..10).map { rec("B", 20L + it, 1.9, 3, it) } +
            (1..10).map { rec("A2", 40L + it, 1.0, if (it >= 9) null else 1, it) })
        assertEquals(10, tail.cards.size)
        assertEquals(2, tail.pending); assertEquals(8, tail.better); assertEquals(0, tail.worse)
        assertTrue(tail.summaryLine(), tail.summaryLine().startsWith("zoom A/B (same 10 cards;"))
        assertTrue(tail.summaryLine(), tail.summaryLine().contains("unstable at 1× 0 ·"))
        assertTrue(tail.perCardLine(), tail.perCardLine().endsWith("9 c9 A1 1 · B 3 · A2 …; 10 c10 A1 1 · B 3 · A2 …"))
        assertFalse(tail.perCardLine(), tail.perCardLine().contains("?"))
        // One card B never shot beside one still identifying: one missing, one identifying —
        // which of the two is a guess (the first takes the capture), the counts are not.
        val gap = MeasureSession.abSummary(a1 + (1..10).filter { it != 5 }.map { rec("B", 20L + it, 1.9, if (it == 6) null else 3, it) } + a2)
        assertEquals(10, gap.cards.size)
        assertEquals(0, gap.extraB); assertEquals(1, gap.pending); assertEquals(1, gap.worse); assertEquals(8, gap.better)
    }

    /**
     * "Zoom has no job at this mount height" is said on A1 alone — A1 already OCR-confirms
     * nearly every card (fewer than MIN_NET_BETTER left to improve). Rule 2 out of reach
     * for another reason (cards unstable at 1×, still identifying) says THAT, and what to
     * do: review of 9df0293 — A1 confirming 0 of 10 with 7 cards unstable read "no job".
     */
    @Test
    fun zoomHasNoJobOnlyWhenA1AlreadyConfirmsNearlyEveryCard() {
        fun rec(arm: String, burst: Long, z: Double, rank: Int?, card: Int) =
            AbRecord(arm, burst, z, if (z > 1) 1163 else 612, 200.0, 400, false, rank = rank, name = "c$card",
                ocr = rank == 3, autoPicked = rank != null && rank >= 2)
        val a1 = (1..10).map { rec("A1", it.toLong(), 1.0, 1, it) }
        val b = (1..10).map { rec("B", 20L + it, 1.9, 3, it) }
        val a2 = (1..10).map { rec("A2", 40L + it, 1.0, if (it <= 7) 3 else 1, it) }      // 7 unstable at 1×
        val s = MeasureSession.abSummary(a1 + b + a2)
        assertFalse("A1 confirms none: zoom has a job", s.noJob)
        val v = s.verdictLine()
        assertFalse(v, v.contains("no job"))
        assertTrue(v, v.contains("✗ B better − worse = 3 (needs 4)"))
        assertTrue(v, v.contains("note: rule 2 is out of reach this run — at most 3 stable cards can improve (7 unstable at 1×, 0 still identifying); " +
            "run the zoom steps again with 10 other cards"))
        val card = s.cardText()
        assertFalse(card, card.contains("no job"))
        assertTrue(card, card.contains("rule 2 out of reach this run (7 unstable at 1×, 0 still identifying)"))
        // Still identifying: wait first.
        val waiting = MeasureSession.abSummary(a1 + b.map { if (it.burst > 23) it.copy(rank = null, name = null) else it } + a2.map { it.copy(rank = 1) })
        assertTrue(waiting.verdictLine(), waiting.verdictLine().contains("at most 3 stable cards can improve (0 unstable at 1×, 7 still identifying); wait until no card is still identifying"))
        // A1 confirms 7 of 10: at most 3 can improve whatever B does — no job.
        val easy = MeasureSession.abSummary(a1.map { if (it.burst <= 7) it.copy(rank = 3, ocr = true, autoPicked = true) else it } + b + a2.map { it.copy(rank = if (it.burst <= 47) 3 else 1) })
        assertTrue(easy.noJob)
        assertTrue(easy.verdictLine(), easy.verdictLine().contains("note: zoom has no job at this mount height — A1 already OCR-confirms 7 of 10"))
        assertFalse(easy.verdictLine(), easy.verdictLine().contains("out of reach"))
        // A1 with captures missing: a missed card is not a confirmed one (5 of 5 shot, 10 cards).
        val missed = MeasureSession.abSummary(a1.filter { it.burst <= 5 }.map { it.copy(rank = 3, ocr = true, autoPicked = true) } + b + a2)
        assertFalse(missed.verdictLine(), missed.noJob)
    }

    /** An upload outcome's rank: 3 OCR ✓ · 2 auto-filed without OCR · 1 needs a pick or a best guess · 0 none. */
    @Test
    fun anOutcomeIsRankedFromItsJson() {
        fun json(ocr: Boolean, n: Int = 3) = org.json.JSONObject().put("card_read", org.json.JSONObject().put("name", "Opt"))
            .put("candidates", org.json.JSONArray().apply { repeat(n) { put(org.json.JSONObject().put("ocr_confirmed", ocr && it == 0)) } })
        val ocrd = MeasureSession.facts(Outcome.AutoFiled(json(true), 7, false))
        assertEquals(3, ocrd.rank); assertTrue(ocrd.ocr); assertTrue(ocrd.autoPicked); assertEquals("Opt", ocrd.name); assertEquals(3, ocrd.printings)
        val single = MeasureSession.facts(Outcome.AutoFiled(json(false, 1), 7, false))
        assertEquals(2, single.rank); assertFalse(single.ocr); assertTrue(single.autoPicked)
        assertEquals(1, MeasureSession.facts(Outcome.NeedsPick(json(false), 7, false)).rank)
        assertEquals(1, MeasureSession.facts(Outcome.BestGuess(json(false), 7, false)).rank)
        assertEquals(0, MeasureSession.facts(Outcome.NoMatch(org.json.JSONObject(), 7, false)).rank)
        assertEquals(0, MeasureSession.facts(Outcome.NoCard("No card detected.", false)).rank)
        assertEquals(0, MeasureSession.facts(Outcome.Rejected(422, "bad", false)).rank)
    }

    /** Finish logs the A/B summary, the per-card line and the verdict BEFORE "session END", then forgets the tally. */
    @Test
    fun theSummaryIsLoggedBeforeSessionEnd() {
        val m = session()
        m.start(MeasureSession.Plan.ZOOM)
        m.arm(1, 1.0, 612, 212.0, List(10) { 1 })
        m.next(); m.next()
        m.arm(11, 1.9, 1163, 344.0, List(10) { 3 })
        m.next()
        m.arm(21, 1.0, 612, 212.0, List(10) { 1 })
        m.next()
        assertEquals("zoom-done", m.step.id)
        m.next()
        val l = lines()
        val end = l.indexOfLast { it.startsWith("session END") }
        assertEquals("session END is the last line", l.size - 1, end)
        assertTrue(l[end - 3], l[end - 3].startsWith("zoom A/B (same 10 cards"))
        assertTrue(l[end - 2], l[end - 2].startsWith("zoom A/B per card"))
        assertTrue(l[end - 1], l[end - 1].startsWith("zoom VERDICT: "))
        assertTrue(session().records.isEmpty())
        // No arm step reached: nothing about A/B at all.
        val n = session()
        n.start(); n.finish(abandoned = true)
        assertFalse(lines().takeLast(3).any { it.startsWith("zoom ") })
    }

    /**
     * What the A1 count line notes (text only): Tap to scan, an Area too wide to zoom
     * (B would be 1× too), what a good Area would zoom, and the off-centre hint; B says
     * the zoom it runs at, or that it is at 1× and why.
     */
    @Test
    fun theArmNotesSayWhatBWillZoom() {
        // The camera answers each zoom request with what was asked (the B line says what it is AT).
        fun state(area: io.github.darylno.cardscanner.core.RoiFrac?, enabled: Boolean): io.github.darylno.cardscanner.core.ZoomCoordinator.State {
            var asked: Pair<Int, Double>? = null
            val z = io.github.darylno.cardscanner.core.ZoomCoordinator(object : io.github.darylno.cardscanner.core.ZoomCoordinator.Port {
                override fun request(gen: Int, ratio: Double) { asked = gen to ratio }
                override fun closeGate(why: String) {}
                override fun map(ratio: Double, view: io.github.darylno.cardscanner.core.RoiFrac?, settle: Boolean, why: String) {}
                override fun drawingReady(ratio: Double) {}
                override fun later(ms: Long, block: () -> Unit) {}
                override fun readBack(): Double? = null
            })
            z.cameraBound(2.6, 10.0); z.setArea(area); z.setEnabled(enabled)
            asked?.let { (g, r) -> z.onApplied(g, r) }
            return z.state()
        }
        val a1 = MeasureSession.STEPS.first { it.id == "zoom-a1" }
        val b = MeasureSession.STEPS.first { it.id == "zoom-b" }
        val good = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        val edge = io.github.darylno.cardscanner.core.RoiFrac(0.45, 0.25, 0.95, 0.75)
        assertEquals(listOf("this Area would zoom 1.90× with Zoom to fit on"), MeasureSession.notes(a1, auto = true, zoom = state(good, false)))
        assertEquals(listOf("Tap to scan is on — switch to Tray",
            "this Area can't zoom where it is (its fit allows only 1.06×) — move it toward the middle or draw a tighter one first, or B will be 1× too",
            "off-centre Area: centred, it would zoom 1.90× (this Area: 1.00×)"), MeasureSession.notes(a1, auto = false, zoom = state(edge, false)))
        val wide = io.github.darylno.cardscanner.core.RoiFrac(0.03, 0.03, 0.97, 0.97)
        assertEquals(listOf("this Area is too wide to zoom (it allows only 1.01×) — draw a tighter one first, or B will be 1× too"),
            MeasureSession.notes(a1, auto = true, zoom = state(wide, false)))
        assertEquals(listOf("no Area is drawn — draw one tight round where the cards land, or B will be 1× too"),
            MeasureSession.notes(a1, auto = true, zoom = state(null, false)))
        assertEquals("no camera: nothing to say about the zoom", emptyList<String>(), MeasureSession.notes(a1, auto = true, zoom = null))
        assertEquals(listOf("zoom 1.90× (limited by the Area fit)"), MeasureSession.notes(b, auto = true, zoom = state(good, true)))
        assertEquals(listOf("B is at 1.00× — the Area allows only 1.06×: go Back and draw a tighter Area"),
            MeasureSession.notes(b, auto = true, zoom = state(edge, true)))
    }

    /**
     * B's line says the zoom the camera is AT, not only what the Area asks: while it
     * settles, once applied, and plainly when the camera did not zoom — review of
     * 9df0293: a "Camera is not active" failure (the coordinator maps 1×) still read
     * "zoom 1.90× (limited by the Area fit)", so all 10 B cards would be shot at 1× and
     * the owner would learn it only from Finish's "void".
     */
    @Test
    fun theBLineSaysWhatTheCameraIsAtNotOnlyWhatTheAreaAsks() {
        val b = MeasureSession.STEPS.first { it.id == "zoom-b" }
        val good = io.github.darylno.cardscanner.core.RoiFrac(0.25, 0.25, 0.75, 0.75)
        val asks = io.github.darylno.cardscanner.core.ZoomFit.choose(good, 2.6, 10.0)
        fun st(ratio: Double, settling: Boolean = false, enabled: Boolean = true, problem: String? = null, drawing: Boolean = false) =
            io.github.darylno.cardscanner.core.ZoomCoordinator.State(enabled, ratio,
                if (enabled) asks else io.github.darylno.cardscanner.core.ZoomFit.Choice.none(io.github.darylno.cardscanner.core.ZoomFit.Limit.OFF),
                settling, drawing, problem, area = asks)
        fun notes(z: io.github.darylno.cardscanner.core.ZoomCoordinator.State) = MeasureSession.notes(b, auto = true, zoom = z)
        assertEquals(listOf("zoom 1.90× (limited by the Area fit)"), notes(st(1.9)))
        assertEquals("a read-back within 1 % IS the request", listOf("zoom 1.90× (limited by the Area fit)"), notes(st(1.885)))
        assertEquals(listOf("zoom → 1.90× (limited by the Area fit) — settling…"), notes(st(1.0, settling = true)))
        val failed = "1.90× could not be applied (Camera is not active, 6 tries)"
        assertEquals(listOf("B is at 1.00× — the camera did not zoom ($failed): go Back, then Next to try again"), notes(st(1.0, problem = failed)))
        assertEquals(listOf("B is at 1.00× — the camera did not zoom: go Back, then Next to try again"), notes(st(1.0)))
        val clamped = "asked for 1.90×, the camera reports 1.70× — mapping with 1.70×"
        assertEquals(listOf("zoom 1.70× — the camera's answer to 1.90× (limited by the Area fit)"), notes(st(1.7, problem = clamped)))
        assertEquals(listOf("B is at 1.00× — Zoom to fit is off on the camera (changed from the computer?): go Back, then Next to try again"),
            notes(st(1.0, enabled = false)))
        assertEquals(listOf("zoom 1.90× (limited by the Area fit) once the Area is drawn"), notes(st(1.0, drawing = true)))
        // The real coordinator: asked → settling; answered → applied; the camera inactive past the retries → 1×, said so.
        var asked: Pair<Int, Double>? = null
        val z = io.github.darylno.cardscanner.core.ZoomCoordinator(object : io.github.darylno.cardscanner.core.ZoomCoordinator.Port {
            override fun request(gen: Int, ratio: Double) { asked = gen to ratio }
            override fun closeGate(why: String) {}
            override fun map(ratio: Double, view: io.github.darylno.cardscanner.core.RoiFrac?, settle: Boolean, why: String) {}
            override fun drawingReady(ratio: Double) {}
            override fun later(ms: Long, block: () -> Unit) {}
            override fun readBack(): Double? = null
        })
        z.cameraBound(2.6, 10.0); z.setArea(good); z.setEnabled(true)
        assertEquals(listOf("zoom → 1.90× (limited by the Area fit) — settling…"), notes(z.state()))
        asked!!.let { (g, r) -> z.onApplied(g, r) }
        assertEquals(listOf("zoom 1.90× (limited by the Area fit)"), notes(z.state()))
        z.cameraStopped("the scan screen stopped"); z.cameraStarted("the scan screen started")
        val g = asked!!.first
        repeat(io.github.darylno.cardscanner.core.ZoomCoordinator.MAX_TRIES + 1) { z.onFailed(g, notActive = true, message = "Camera is not active") }
        assertEquals(listOf("B is at 1.00× — the camera did not zoom ($failed): go Back, then Next to try again"), notes(z.state()))
    }
}
