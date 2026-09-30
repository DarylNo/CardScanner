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
    private var thread: Thread? = null
    /** Each start is a new generation; a thread whose generation is gone exits (a stop → start
     *  whose join timed out must never leave the old thread looping beside the new one). */
    private var generation = 0L

    fun start() = synchronized(lock) {
        if (thread != null) return
        val gen = ++generation
        woken = true                       // first pass straight away: price what's already owed
        thread = Thread({ loop(gen) }, "price-worker").apply { isDaemon = true; start() }
    }

    fun stop() {
        val t = synchronized(lock) {
            generation++
            lock.notifyAll()
            thread.also { thread = null }
        }
        if (t != null && t !== Thread.currentThread()) t.join(2_000)
    }

    /** Something may now be owed: check at once instead of waiting out the poll. */
    fun wake() = synchronized(lock) { woken = true; lock.notifyAll() }

    private fun loop(gen: Long) {
        var last = PriceSweep.Tick.IDLE
        while (true) {
            synchronized(lock) {
                if (gen == generation && !woken) {
                    if (last == PriceSweep.Tick.IDLE) {
                        sweep.nextCheckAt = null          // nothing owed: wait for the next scan
                        lock.wait()
                    } else {
                        sweep.nextCheckAt = now() + pollS
                        lock.wait((pollS * 1000).toLong())
                    }
                }
                if (gen != generation) { if (thread == null) sweep.nextCheckAt = null; return }
                woken = false
            }
            last = try { sweep.tick() } catch (e: Exception) { onError(e); PriceSweep.Tick.OWED }
        }
    }
}
