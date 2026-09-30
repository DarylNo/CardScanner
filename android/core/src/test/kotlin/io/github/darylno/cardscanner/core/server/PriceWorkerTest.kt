package io.github.darylno.cardscanner.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's price loop: price what's owed, re-check on the poll while
 * anything is owed, and sleep — no store reads, no F2F — until woken when
 * nothing is (owner, 2026-09-30).
 */
class PriceWorkerTest {
    private var clock = 0
    private val store = MemoryScanStore { "T%04d".format(++clock) }
    private val api = PhoneApi(store, object : ScanImages {
        override fun read(id: Long): ByteArray? = null
        override fun write(id: Long, jpeg: ByteArray) {}
        override fun delete(id: Long) {}
    })
    private val lookups = CopyOnWriteArrayList<String>()
    private var answer: (String) -> F2fAnswer = { F2fAnswer.NotListed }
    private val f2f = F2fLookup { name, set, _, _, _ -> lookups += "$name/$set"; answer(set) }
    private val sweep = PriceSweep(store, api, f2f, launch = { it.run() }, now = { System.nanoTime() / 1e9 })

    private fun file(name: String, vararg sets: String) = api.fileScan(mapOf(
        "identified" to true, "card_read" to mapOf("name" to name), "confidence" to mapOf("name" to "high"),
        "candidates" to sets.mapIndexed { i, s -> mapOf("id" to "$name$s", "name" to name, "set" to s,
            "collector_number" to "${i + 1}") }), null)

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            if (System.nanoTime() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test fun tickReportsWhatItFound() {
        assertEquals(PriceSweep.Tick.IDLE, sweep.tick())
        file("Opt", "dom", "xln")
        assertEquals(PriceSweep.Tick.RAN, sweep.tick())
        assertEquals(listOf("Opt/dom", "Opt/xln"), lookups)
        assertEquals(PriceSweep.Tick.IDLE, sweep.tick())                // both searched: nothing owed
    }

    @Test fun pricesWhatIsOwedThenSleepsUntilTheNextScan() {
        file("Opt", "dom")
        val w = PriceWorker(sweep, now = { System.nanoTime() / 1e9 }, pollS = 0.05)
        w.start()
        try {
            waitFor("the owed price") { lookups.size == 1 }
            waitFor("idle") { sweep.nextCheckAt == null && !sweep.active }
            Thread.sleep(200)                                        // 4 polls' worth: nothing to fetch
            assertEquals(1, lookups.size)
            file("Shock", "m19")
            w.wake()                                                 // the next scan wakes it
            waitFor("the new scan's price") { lookups.size == 2 }
            assertEquals("Shock/m19", lookups[1])
        } finally { w.stop() }
        assertNull(sweep.nextCheckAt)
    }

    @Test fun keepsPollingWhileAPriceIsStillOwed() {
        val calls = AtomicInteger()
        answer = { if (calls.incrementAndGet() < 3) F2fAnswer.Unavailable("429") else F2fAnswer.NotListed }
        file("Opt", "dom")
        val w = PriceWorker(sweep, now = { System.nanoTime() / 1e9 }, pollS = 0.05)
        w.start()
        try {
            // transport failures leave it owed: the poll retries it, no wake needed
            waitFor("the retries") { calls.get() == 3 }
            waitFor("idle") { sweep.nextCheckAt == null && !sweep.active }
        } finally { w.stop() }
        assertEquals(3, lookups.size)
    }
}
