package io.github.darylno.cardscanner.ui

/**
 * "How long will the battery last at this pace?" — the owner's fun extra on
 * the scan screen. Fed the battery % over time; estimates from THIS session's
 * measured drain (camera + screen + scanning), not the phone's generic guess.
 * Pure: the caller passes the clock and the reading.
 */
class BatteryEstimate(private val windowMs: Long = 20 * 60_000L) {
    private data class Sample(val atMs: Long, val pct: Double)
    private val samples = ArrayDeque<Sample>()
    private var charging = false
    private var lastPct: Double? = null

    fun record(nowMs: Long, pct: Double, isCharging: Boolean) {
        lastPct = pct
        if (isCharging != charging) samples.clear()   // a plug/unplug restarts the measurement
        charging = isCharging
        if (charging) return
        samples.addLast(Sample(nowMs, pct))
        while (samples.size > 1 && nowMs - samples.first().atMs > windowMs) samples.removeFirst()
    }

    /**
     * Minutes left, or null until the drain is measurable: at least 3 minutes
     * of data and at least 1% used (a 1% step is the battery's resolution —
     * less than that is noise).
     */
    fun minutesLeft(nowMs: Long): Double? {
        if (charging || samples.size < 2) return null
        val a = samples.first(); val b = samples.last()
        val used = a.pct - b.pct
        val mins = (b.atMs - a.atMs) / 60_000.0
        if (mins < 3.0 || used < 1.0) return null
        return b.pct / (used / mins)
    }

    /** "🔋 62% · ~2h 10m", "⚡ 80%", "🔋 62%" (still measuring), or "" with no reading. */
    fun label(nowMs: Long): String {
        val pct = lastPct ?: return ""
        val p = "%.0f%%".format(pct)
        if (charging) return "⚡ $p"
        val m = minutesLeft(nowMs) ?: return "🔋 $p"
        val total = m.toInt()
        val left = if (total >= 60) "${total / 60}h ${"%02d".format(total % 60)}m" else "${total}m"
        return "🔋 $p · ~$left"
    }
}
