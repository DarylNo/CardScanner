package io.github.darylno.cardscanner.ui

/**
 * Cards-per-minute for the scan screen: a rolling rate over the last
 * [windowMs] plus the session total. Only scans that FILED a card count
 * (the server made a row) — empty-tray / no-card captures don't pad it.
 * Pure: the caller passes the clock, so it's testable without Android.
 */
class ScanRate(private val windowMs: Long = 5 * 60_000L) {
    private val times = ArrayDeque<Long>()
    var total = 0
        private set

    fun record(nowMs: Long) {
        times.addLast(nowMs)
        total++
        prune(nowMs)
    }

    /**
     * Cards per minute over the window, or null until two cards exist (one
     * card has no rate). Measured from the first card in the window to now,
     * but never over less than a minute — so the first quick pair doesn't
     * read as "60/min".
     */
    fun perMinute(nowMs: Long): Double? {
        prune(nowMs)
        if (times.size < 2) return null
        val spanMs = maxOf(60_000L, nowMs - times.first())
        return times.size * 60_000.0 / spanMs
    }

    /** "12/min · 87", "— /min · 1", or "" before the first card. */
    fun label(nowMs: Long): String {
        if (total == 0) return ""
        val r = perMinute(nowMs)
        val rate = if (r == null) "—" else if (r >= 10) "%.0f".format(r) else "%.1f".format(r)
        return "$rate/min · $total"
    }

    private fun prune(nowMs: Long) {
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) times.removeFirst()
    }
}
