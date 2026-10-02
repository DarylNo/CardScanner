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
}
