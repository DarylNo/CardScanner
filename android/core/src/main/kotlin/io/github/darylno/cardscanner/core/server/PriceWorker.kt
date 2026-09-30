package io.github.darylno.cardscanner.core.server

/**
 * The phone's price loop (owner, 2026-09-30): not a timed sweep but "if there
 * is something to price, price it". While anything is owed it checks every
 * [pollS] seconds (a check is a store read — F2F is only called for owed
 * prices, through [PriceSweep.tick], so pacing, the breaker cooldown and a
 * manual-stop pause all still hold). When nothing is owed it sleeps until
 * [wake] — a filed scan, a pick, a finish change.
 */
class PriceWorker(
    private val sweep: PriceSweep,
    private val now: () -> Double,
    private val pollS: Double = 5.0,
    private val onError: (Throwable) -> Unit = {},
) {
    private val lock = Object()
    private var woken = false
    private var stopped = true
    private var thread: Thread? = null

    fun start() = synchronized(lock) {
        if (thread != null) return
        stopped = false
        woken = true                       // first pass straight away: price what's already owed
        thread = Thread(::loop, "price-worker").apply { isDaemon = true; start() }
    }

    fun stop() {
        val t = synchronized(lock) {
            stopped = true
            lock.notifyAll()
            thread.also { thread = null }
        }
        if (t != null && t !== Thread.currentThread()) t.join(2_000)
    }

    /** Something may now be owed: check at once instead of waiting out the poll. */
    fun wake() = synchronized(lock) { woken = true; lock.notifyAll() }

    private fun loop() {
        var last = PriceSweep.Tick.IDLE
        while (true) {
            synchronized(lock) {
                if (!stopped && !woken) {
                    if (last == PriceSweep.Tick.IDLE) {
                        sweep.nextCheckAt = null          // nothing owed: wait for the next scan
                        lock.wait()
                    } else {
                        sweep.nextCheckAt = now() + pollS
                        lock.wait((pollS * 1000).toLong())
                    }
                }
                if (stopped) { sweep.nextCheckAt = null; return }
                woken = false
            }
            last = try { sweep.tick() } catch (e: Exception) { onError(e); PriceSweep.Tick.OWED }
        }
    }
}
