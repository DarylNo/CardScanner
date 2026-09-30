package io.github.darylno.cardscanner.gateway

import io.github.darylno.cardscanner.ident.LocalIdentify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 4's always-on server: when it stops by itself, and when a missing pack is re-fetched. */
class IdleStopTest {
    private val min = 60_000L
    private val idle = GatewayService.IDLE_STOP_MS

    @Test fun stopsAfterHalfAnHourWithNothingOnScreenAndNoRemoteUse() {
        assertTrue(GatewayService.shouldIdleStop(now = idle, lastRemote = 0, lastVisible = 0, visible = 0))
        assertFalse(GatewayService.shouldIdleStop(now = idle - 1, lastRemote = 0, lastVisible = 0, visible = 0))
    }

    @Test fun aVisibleScreenKeepsItRunning() {
        assertFalse(GatewayService.shouldIdleStop(now = 10 * idle, lastRemote = 0, lastVisible = 0, visible = 1))
    }

    @Test fun theLastScreenOrComputerRequestRestartsTheClock() {
        assertFalse(GatewayService.shouldIdleStop(now = idle + 5 * min, lastRemote = 10 * min, lastVisible = 0, visible = 0))
        assertFalse(GatewayService.shouldIdleStop(now = idle + 5 * min, lastRemote = 0, lastVisible = 10 * min, visible = 0))
        assertTrue(GatewayService.shouldIdleStop(now = idle + 10 * min, lastRemote = 10 * min, lastVisible = 3 * min, visible = 0))
    }

    @Test fun onlyLoopbackIsTheOwnPanel() {
        assertTrue(GatewayServer.isLoopback("127.0.0.1"))
        assertTrue(GatewayServer.isLoopback("::1"))
        assertFalse(GatewayServer.isLoopback("192.168.1.20"))
    }

    @Test fun aMissingPackIsRefetchedAtMostOnceAMinute() {
        val every = LocalIdentify.ENSURE_EVERY_MS
        assertTrue(LocalIdentify.ensureDue(hasPack = false, checking = false, now = every, lastForcedAt = 0))
        assertFalse(LocalIdentify.ensureDue(hasPack = false, checking = false, now = every - 1, lastForcedAt = 0))
        assertFalse(LocalIdentify.ensureDue(hasPack = false, checking = true, now = 10 * every, lastForcedAt = 0))
        assertFalse(LocalIdentify.ensureDue(hasPack = true, checking = false, now = 10 * every, lastForcedAt = 0))
    }
}
