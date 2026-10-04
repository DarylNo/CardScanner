package io.github.darylno.cardscanner.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Diagnostics camera block is LIVE while the scan screen has its camera
 * (1.1.11 — it was a bind-time copy, so focus / last frame / analyzer / last
 * capture were stale), else the last snapshot with its age; a report built off
 * the main thread never hangs on a busy main thread.
 */
@RunWith(RobolectricTestRunner::class)
class CameraDiagnosticsTest {
    private var reads = 0
    private val source: () -> String = { reads++; "focus: passes this session: $reads focused" }

    @After fun detach() { CameraDiagnostics.detach(source) }

    @Test fun whileTheCameraIsUpEveryReadIsLiveAndKeptAsTheSnapshot() {
        CameraDiagnostics.attach(source)
        val a = CameraDiagnostics.current(nowMs = 1_000)
        assertTrue(a, a.startsWith("camera (live — read from the scan screen's camera now):\n"))
        assertTrue(a, a.endsWith("1 focused"))
        val b = CameraDiagnostics.current(nowMs = 2_000)
        assertTrue("read again, not a copy", b.endsWith("2 focused"))
        assertEquals("focus: passes this session: 2 focused", CameraDiagnostics.last)
        assertEquals(2_000, CameraDiagnostics.lastAtMs)
    }

    @Test fun onceTheScreenLetsGoItIsTheSnapshotWithItsAge() {
        CameraDiagnostics.attach(source)
        CameraDiagnostics.current(nowMs = 10_000)
        CameraDiagnostics.detach(source)
        val t = CameraDiagnostics.current(nowMs = 10_000 + 125_000)
        assertTrue(t, t.startsWith("camera (snapshot from 2 min 5 s ago — the scan screen is closed; open it for live values):\n"))
        assertTrue(t, t.endsWith("1 focused"))
    }

    @Test fun anOlderScreenLettingGoDoesNotDetachTheNewOne() {
        val newer: () -> String = { "newer screen" }
        CameraDiagnostics.attach(source)
        CameraDiagnostics.attach(newer)          // the scan screen was re-created before the old one was destroyed
        CameraDiagnostics.detach(source)         // the old one's onDestroy
        assertTrue(CameraDiagnostics.current(nowMs = 1).endsWith("newer screen"))
        CameraDiagnostics.detach(newer)
    }

    @Test fun aSourceThatThrowsFallsBackToTheSnapshot() {
        CameraDiagnostics.snapshot("the last good block", nowMs = 0)
        val broken: () -> String = { error("camera released") }
        CameraDiagnostics.attach(broken)
        val t = CameraDiagnostics.current(nowMs = 3_000)
        CameraDiagnostics.detach(broken)
        assertTrue(t, t.startsWith("camera (snapshot from 3.0 s ago — the live read failed or timed out):\n"))
        assertTrue(t, t.endsWith("the last good block"))
    }

    /** The phone server builds the report on its own thread: a main thread that never runs the read must not hang it. */
    @Test fun offTheMainThreadABusyMainThreadTimesOutToTheSnapshot() {
        CameraDiagnostics.snapshot("snapshot block", nowMs = 0)
        CameraDiagnostics.attach(source)
        val pool = Executors.newSingleThreadExecutor()
        try {
            // Robolectric's main looper is paused: the posted read never runs, exactly like a stuck UI thread.
            val t = pool.submit<String> { CameraDiagnostics.current(nowMs = 1_000, timeoutMs = 50) }.get(5, TimeUnit.SECONDS)
            assertTrue(t, t.contains("the live read failed or timed out"))
            assertTrue(t, t.endsWith("snapshot block"))
            assertEquals("the read never ran", 0, reads)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun nothingYetSaysHowToGetIt() {
        assertEquals("camera: open the scanner screen once to collect camera details", CameraDiagnostics.render(null, null))
    }
}
