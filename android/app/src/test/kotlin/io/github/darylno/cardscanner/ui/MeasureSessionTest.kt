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
        assertTrue(lines().any { it.contains(" START zoom-b: ") && it.endsWith("[AE lock false, zoom to fit on, refocus never]") })
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
     * Review of 1.1.18 (F5): the zoom steps (A1, the probe step, B, A2) pin "Refocus on a card" to
     * Never — a periodic pass in one arm and not another, or Every card's hold on each card, would
     * confound the A/B and the probe — exactly as Zoom to fit and AE lock are pinned; the owner's
     * own is restored at Stop / Finish (the original's 5th field). The steps before keep the owner's.
     */
    @Test
    fun theZoomStepsPinRefocusToNeverAndTheOwnersIsRestored_F5() {
        settings.refocusMin = 10
        val m = session()
        m.start()
        assertEquals("the original carries it", "${settings.aeLock},${settings.vibration},${settings.checkMs},${settings.zoomFit},10",
            settings.measureOriginal)
        assertTrue(lines().any { it.startsWith("session START") && it.endsWith("refocus on a card every 10 min)") })
        assertEquals("the set-up step leaves the owner's", 10, settings.refocusMin)
        m.goTo("zoom-a1")
        assertEquals(-1, settings.refocusMin)
        assertTrue(lines().any { it.contains(" START zoom-a1: ") && it.endsWith("refocus never]") })
        m.next(); assertEquals("zoom-probe", m.step.id); assertEquals(-1, settings.refocusMin)
        settings.refocusMin = 0                                      // changed from the computer mid-step…
        m.next(); assertEquals("zoom-b", m.step.id); assertEquals("…B pins it again", -1, settings.refocusMin)
        m.back(); assertEquals(-1, settings.refocusMin)
        m.next(); m.next(); assertEquals("zoom-a2", m.step.id); assertEquals(-1, settings.refocusMin)
        m.finish(abandoned = true)
        assertEquals("the owner's own is back after Stop", 10, settings.refocusMin)
        // "Run the zoom steps", finished: the owner's Every card is back.
        settings.refocusMin = 0
        val z = session()
        z.start(MeasureSession.Plan.ZOOM)
        assertEquals("A1 pins it from the first step", -1, settings.refocusMin)
        while (z.active && !z.isLast) z.next()
        assertEquals("zoom-done", z.step.id)
        z.next()
        assertFalse(z.active)
        assertEquals(0, settings.refocusMin)
        assertTrue(MeasureSession.ZOOM_STEPS.last().instruction.contains("Refocus on a card"))
    }

    /**
     * A session saved by 1.1.17 kept four fields, one by 1.1.16 three: neither saved "Refocus on a
     * card", so the first zoom step about to pin it saves the owner's own first (5th field, after a
     * 1.1.16 original gets its Zoom to fit as the 4th) and Stop / Finish put it back; an older
     * original stopped before any pin leaves it alone.
     */
    @Test
    fun anOlderSessionKeepsTheOwnersRefocus_F5() {
        val prefs = app.getSharedPreferences("ui_settings", android.content.Context.MODE_PRIVATE)
        // 1.1.17: four fields, on "foil" by id.
        settings.measurePlan = null
        settings.zoomFit = true; settings.refocusMin = 2
        settings.measureStep = 7
        prefs.edit().putString("measure_step_id", "foil").apply()
        settings.measureOriginal = "false,true,2500,true"
        val m = session()
        assertEquals("foil", m.step.id)
        m.next()
        assertEquals("zoom-a1", m.step.id); assertEquals(-1, settings.refocusMin)
        assertEquals("false,true,2500,true,2", settings.measureOriginal)
        assertTrue(lines().any { it == "a session started on an older build: your Refocus on a card (every 2 min) is kept now, before this step sets it" })
        m.finish(abandoned = true)
        assertEquals(2, settings.refocusMin); assertTrue(settings.zoomFit)
        // 1.1.16: three fields, its own index for "foil", no id.
        settings.zoomFit = true; settings.refocusMin = 30
        settings.measureStep = 7
        prefs.edit().remove("measure_step_id").apply()
        settings.measureOriginal = "false,true,2500"
        val old = session()
        assertEquals("foil", old.step.id)
        old.next()
        assertEquals("zoom-a1", old.step.id); assertEquals(-1, settings.refocusMin)
        assertEquals("Zoom to fit 4th, Refocus 5th", "false,true,2500,true,30", settings.measureOriginal)
        old.next(); old.next(); old.next(); old.next()                     // probe, B, A2, AE lock
        assertEquals("ae-lock", old.step.id)
        old.next(); old.next()                                            // done → Finish
        assertFalse(old.active)
        assertEquals(30, settings.refocusMin); assertTrue(settings.zoomFit)
        // A 4-field original stopped before any pin: Refocus on a card is left as it is.
        settings.refocusMin = 10
        settings.measureStep = 3
        prefs.edit().putString("measure_step_id", "lift").apply()
        settings.measureOriginal = "false,true,2500,false"
        session().finish(abandoned = true)
        assertEquals(10, settings.refocusMin)
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
        assertTrue("the probe step runs the probe (no longer a placeholder): ${m.steps[1].instruction}",
            m.steps[1].instruction.contains("Tap Run probe") && !m.steps[1].instruction.contains("not built"))
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
     * answer. No zoom probe ran in this session (its half is not measured), so the verdict can only be NO.
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

    /** A probe run's result as the runner hands it over (ZoomProbe.verdict's fields; a centred, settled run on camera 0). */
    private fun result(run: Int, valid: Boolean = true, centred: Boolean? = true, same: Int? = null, reason: String? = null,
                       camera: String = "0", stream: String = "1600×1200") =
        io.github.darylno.cardscanner.core.ZoomProbe.Result(run, valid, reason, if (valid) centred else null,
            if (valid) io.github.darylno.cardscanner.core.ZoomProbe.HFit(612.0, 3.0, 0.002, 0.0, 2.0) else null, if (valid) 0.001 else null,
            15, if (valid) 15 else 9, 0, 0, true, 1.43, 0.0, null, stream, 2.2, same, 612.0, camera = camera)

    private fun outcome(kind: io.github.darylno.cardscanner.core.ZoomProbeRun.Kind, words: String,
                        r: io.github.darylno.cardscanner.core.ZoomProbe.Result? = null, numbered: Boolean = r != null) =
        io.github.darylno.cardscanner.core.ZoomProbeRun.Outcome(kind, words, r, if (r?.valid == true) FloatArray(64 * 88) { r.run.toFloat() } else null, numbered)

    /**
     * The probe step (1.1.17): each run the scan screen hands over is kept for the session
     * (AppSettings.zoomProbeRuns) under its number — a refusal that logged none ("probe
     * refused — …") leaves the number for the owner's next Run probe — and what the card says
     * after it is ZoomProbe.combine over the session's runs (a run with no result says its own
     * words). The valid runs' 1× thumbnails come back for the next run's same-card check. The
     * last verdict is kept past the session (zoomProbeLast: Diagnostics, /api/device), with the
     * time, the camera and the stream; the runs and thumbnails go with the session.
     */
    @Test
    fun theProbeStepKeepsEachRunAndSaysWhatTheyShowTogether() {
        val m = session()
        m.start(MeasureSession.Plan.ZOOM)
        m.next()
        assertEquals("zoom-probe", m.step.id)
        assertEquals(MeasureSession.Action.PROBE, m.step.action)
        assertEquals("Zoom probe", m.step.title)
        assertEquals(1, m.nextProbeRun())
        assertEquals(null, m.probeLine())
        // Refused before it began: its own words, no number used, nothing kept.
        val wait = "Let the card scan first (wait for the ✓), then Run probe"
        assertEquals(wait, m.recordProbe(1, outcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.REFUSED, wait)))
        assertEquals(1, m.nextProbeRun())
        assertEquals(null, settings.zoomProbeLast)
        assertTrue(lines().last(), lines().last() == "step 2/5 zoom-probe: run 1 → refused — $wait")
        // Run 1, valid.
        now += 40_000
        val one = m.recordProbe(1, outcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 1: valid — …", result(1)))
        assertEquals("run 1 valid — lift this card, put a DIFFERENT card down, let it scan, then Run again", one)
        assertEquals(one, m.probeLine())
        assertEquals(2, m.nextProbeRun())
        assertEquals(listOf(1), m.probeEarlier().map { it.run })
        assertTrue(lines().last(), lines().last().startsWith("step 2/5 zoom-probe: run 1 → done — run 1 valid"))
        val last = io.github.darylno.cardscanner.core.MiniJson.parse(settings.zoomProbeLast!!) as Map<*, *>
        assertEquals(1_040_000L, last["at"]); assertEquals("0", last["camera"]); assertEquals("1600×1200", last["stream"])
        // What Diagnostics and the computer show: what the run FOUND, no "Run again" (review of a43cb5b) — the card keeps the prompt.
        assertEquals("run 1 valid (one more card needed)", last["words"])
        assertEquals(1L, last["valid_cards"])
        assertTrue("${last["probe_half"]}", (last["probe_half"] as String).endsWith(" · knee: not measured in this build (MAX_ZOOM stays 2.00×)"))
        assertEquals(true, (last["last"] as Map<*, *>)["valid"])
        // Run 2: cancelled (it logged its number) — its own words; the number is used.
        val cancelled = "Zoom probe cancelled (Back) — nothing filed"
        assertEquals(cancelled, m.recordProbe(2, outcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.CANCELLED, cancelled, numbered = true)))
        assertEquals(3, m.nextProbeRun())
        // Run 3, valid on a different card: the two agree.
        val three = m.recordProbe(3, outcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 3: valid — …", result(3)))
        assertEquals("2 cards agree: the zoom is the centred crop, frames settle within the dropped ones — Next", three)
        assertEquals(listOf(1, 3), m.probeEarlier().map { it.run })
        assertEquals(listOf(1, 2, 3), m.probeRuns.map { it.run })
        assertEquals(listOf("done", "cancelled", "done"), m.probeRuns.map { it.kind })
        // The screen closed and reopened: the runs and the words are still there (the thumbnails are memory only).
        assertEquals(three, session().probeLine())
        assertEquals(4, session().nextProbeRun())
        // The session ends: the probe's verdict is logged before "session END"; the runs go, the last verdict stays.
        m.next(); m.next(); m.next()
        assertEquals("zoom-done", m.step.id)
        m.next()
        val l = lines()
        assertTrue(l[l.size - 2], l[l.size - 2].startsWith("probe VERDICT: 2 cards agree") &&
            l[l.size - 2].contains("probe half of default-on: ✓ 2 valid run(s) on different cards (needs 2) ✓ centred ✓ settle · knee: not measured in this build"))
        assertTrue(l.last().startsWith("session END"))
        assertEquals(null, settings.zoomProbeRuns)
        assertTrue(m.probeEarlier().isEmpty())
        assertEquals("2 cards agree: the zoom is the centred crop, frames settle within the dropped ones",
            (io.github.darylno.cardscanner.core.MiniJson.parse(settings.zoomProbeLast!!) as Map<*, *>)["words"])
        m.start()
        assertEquals(1, m.nextProbeRun())
        assertEquals(null, m.probeLine())
        m.finish(abandoned = true)
        assertEquals("a session with no probe run logs no probe verdict", 0, lines().count { it.startsWith("probe VERDICT") } - 1)
    }

    /**
     * The session's probe runs count together only on ONE camera and stream (review of a60e51d:
     * the owner can change the lens or High res between runs on the step, and a run at
     * 2048×1536 read as the second card of a run at 1600×1200): the newest run's camera and
     * stream decide which runs count — the card, the last verdict (labelled with that camera
     * and stream) and the session's end alike — and the words say which count apart.
     */
    @Test
    fun probeRunsOnAnotherCameraOrStreamCountApart() {
        val m = session()
        m.start(MeasureSession.Plan.ZOOM)
        m.next()
        val kind = io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE
        m.recordProbe(1, outcome(kind, "Run 1", result(1)))
        val hi = m.recordProbe(2, outcome(kind, "Run 2", result(2, stream = "2048×1536")))
        assertFalse("not two cards that agree: $hi", hi.startsWith("2 cards agree"))
        assertEquals("run 2 valid — lift this card, put a DIFFERENT card down, let it scan, then Run again · " +
            "run 1 (camera 0 · 1600×1200) counts apart: another camera or stream", hi)
        assertEquals(1, m.probeCombined()!!.validCards)
        var last = io.github.darylno.cardscanner.core.MiniJson.parse(settings.zoomProbeLast!!) as Map<*, *>
        assertEquals("2048×1536", last["stream"]); assertEquals(1L, last["valid_cards"])
        assertEquals("run 2 valid (one more card needed) · run 1 (camera 0 · 1600×1200) counts apart: another camera or stream", last["words"])
        // Another lens at the first stream: apart too.
        val lens = m.recordProbe(3, outcome(kind, "Run 3", result(3, camera = "2")))
        assertFalse(lens, lens.startsWith("2 cards agree"))
        // Back on camera 0 at 1600×1200: runs 1 and 4 are the two cards; the end's verdict says so.
        val four = m.recordProbe(4, outcome(kind, "Run 4", result(4)))
        assertTrue(four, four.startsWith("2 cards agree: the zoom is the centred crop"))
        assertTrue(four, four.endsWith(" · run 2 (camera 0 · 2048×1536), run 3 (camera 2 · 1600×1200) count apart: another camera or stream"))
        last = io.github.darylno.cardscanner.core.MiniJson.parse(settings.zoomProbeLast!!) as Map<*, *>
        assertEquals("1600×1200", last["stream"]); assertEquals("0", last["camera"]); assertEquals(2L, last["valid_cards"])
        m.finish(abandoned = true)
        val v = lines().last { it.startsWith("probe VERDICT") }
        assertTrue(v, v.startsWith("probe VERDICT: 2 cards agree") && v.contains("✓ 2 valid run(s) on different cards (needs 2)"))
    }

    /**
     * The combined verdict at the session's end (1.1.17): the A/B half and the probe half
     * together. The probe measures no knee in this build (the pre-registered fallback), so its
     * half is its facts — two cards, centred, settle — and the A/B's identification decides
     * (review of a43cb5b: a knee hard-coded as a failing rule read NO and "the probe half fails"
     * whatever the rig did). A passing A/B and two agreeing runs read YES; one run short reads NO
     * with the probe half "does not" (never "fails"); a rule nothing judged is "–", never ✗. No
     * probe run: "not measured".
     */
    @Test
    fun theVerdictCarriesTheProbeHalf() {
        val m = session()
        m.start(MeasureSession.Plan.ZOOM)
        m.arm(1, 1.0, 612, 212.0, listOf(3, 1, 1, 1, 1, 2, 0, 3, 1, 1))
        m.next()
        m.recordProbe(1, outcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 1", result(1)))
        m.recordProbe(2, outcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 2", result(2)))
        m.next()
        m.arm(11, 1.9, 1163, 344.0, listOf(3, 3, 3, 3, 3, 2, 1, 3, 1, 1))
        m.next()
        m.arm(21, 1.0, 612, 212.0, listOf(3, 1, 1, 1, 1, 2, 0, 3, 1, 1))
        m.next()
        assertEquals("zoom-done", m.step.id)
        val card = m.countExtra(auto = true, zoom = null)
        // The probe half by short names (review of a60e51d: the full words pushed the done card past 45 % of the
        // screen); the full words are the log's. Both halves pass: YES — the knee is a note, not a failing rule.
        assertTrue(card, card.contains("Zoom to fit ON by default: YES — both halves pass\n"))
        assertTrue(card, card.endsWith("\nProbe: ✓ 2 cards ✓ centred ✓ settle · knee not measured"))
        assertFalse("each probe rule once on the card: $card", card.contains("probe:"))
        m.next()
        val l = lines()
        val end = l.indexOfLast { it.startsWith("session END") }
        assertTrue(l[end - 4], l[end - 4].startsWith("probe VERDICT: 2 cards agree"))
        assertTrue(l[end - 3], l[end - 3].startsWith("zoom A/B (same 10 cards"))
        assertTrue(l[end - 2], l[end - 2].startsWith("zoom A/B per card"))
        val v = l[end - 1]
        assertTrue(v, v.startsWith("zoom VERDICT: Zoom to fit ON by default: YES — both halves pass · " +
            "✓ probe: 2 valid run(s) on different cards (needs 2) · ✓ probe: centred · ✓ probe: settle · ✓ B worse on none"))
        assertTrue(v, v.endsWith(" · note: the probe measures no knee in this build — the A/B decides · note: MAX_ZOOM is still 2.00× in this build"))
        assertFalse(v, v.contains("knee: not measured") || v.contains("fails"))
        // One run short: NO, and the probe half "does not" — it is incomplete, not failed.
        val o = session()
        o.start(MeasureSession.Plan.ZOOM)
        o.arm(1, 1.0, 612, 212.0, listOf(3, 1, 1, 1, 1, 2, 0, 3, 1, 1)); o.next()
        o.recordProbe(1, outcome(io.github.darylno.cardscanner.core.ZoomProbeRun.Kind.DONE, "Run 1", result(1))); o.next()
        o.arm(11, 1.9, 1163, 344.0, listOf(3, 3, 3, 3, 3, 2, 1, 3, 1, 1)); o.next()
        o.arm(21, 1.0, 612, 212.0, listOf(3, 1, 1, 1, 1, 2, 0, 3, 1, 1)); o.next()
        val oc = o.countExtra(true, null)
        assertTrue(oc, oc.contains("Zoom to fit ON by default: NO — the A/B half passes; the probe half does not (see Probe)\n"))
        assertTrue(oc, oc.endsWith("\nProbe: ✗ 1 of 2 cards ✓ centred ✓ settle · knee not measured"))
        o.next()
        val ov = lines().last { it.startsWith("zoom VERDICT") }
        assertTrue(ov, ov.startsWith("zoom VERDICT: Zoom to fit ON by default: NO — the A/B half passes, the probe half does not · " +
            "✗ probe: 1 valid run(s) on different cards (needs 2) · ✓ probe: centred · ✓ probe: settle · "))
        // The same A/B with no probe run at all: the probe half is not measured.
        val n = session()
        n.start(MeasureSession.Plan.ZOOM)
        n.arm(1, 1.0, 612, 212.0, listOf(3, 1, 1, 1, 1, 2, 0, 3, 1, 1)); n.next(); n.next()
        n.arm(11, 1.9, 1163, 344.0, listOf(3, 3, 3, 3, 3, 2, 1, 3, 1, 1)); n.next()
        n.arm(21, 1.0, 612, 212.0, listOf(3, 1, 1, 1, 1, 2, 0, 3, 1, 1)); n.next()
        assertTrue(n.countExtra(true, null), n.countExtra(true, null).contains("the A/B half passes; no zoom probe ran"))
        n.next()
        val v2 = lines().last { it.startsWith("zoom VERDICT") }
        assertTrue(v2, v2.startsWith("zoom VERDICT: Zoom to fit ON by default: NO — the A/B half passes; the probe half is not measured (no zoom probe ran this session)"))
        assertFalse(lines().takeLast(5).any { it.startsWith("probe VERDICT") })
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
