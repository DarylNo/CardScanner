package io.github.darylno.cardscanner.core

/**
 * The app's live log (owner, 2026-09-30: "see what is going on live"): what the
 * scanner is doing — triggers, captures, identifications with their distances and
 * timings, the queue, prices, the camera, the server — in one in-memory ring the
 * paired computer reads live (🐞 → App) and a debug report bundles. The app also
 * mirrors every entry to logcat under one tag, so `adb logcat -s CardScanner`
 * shows the same lines.
 *
 * Never log a secret: no guest/admin codes, cookies or tokens.
 */
class DebugLog(private val capacity: Int = 3000, private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(val seq: Long, val t: Long, val level: Char, val tag: String, val msg: String) {
        fun line(): String = "${iso(t)} $level/$tag: $msg"
    }

    private val ring = ArrayDeque<Entry>()
    private var seq = 0L

    /** Called for every entry (the app mirrors to logcat). Must not throw or block. */
    @Volatile var sink: ((Entry) -> Unit)? = null

    val lastSeq: Long get() = synchronized(ring) { seq }

    fun i(tag: String, msg: String) = add('I', tag, msg)
    fun w(tag: String, msg: String) = add('W', tag, msg)
    fun e(tag: String, msg: String, err: Throwable? = null) =
        add('E', tag, if (err == null) msg else "$msg — ${err.javaClass.simpleName}: ${err.message}")

    private fun add(level: Char, tag: String, msg: String) {
        val e = synchronized(ring) {
            val entry = Entry(++seq, clock(), level, tag, msg.take(MAX_MSG))
            ring.addLast(entry)
            while (ring.size > capacity) ring.removeFirst()
            entry
        }
        runCatching { sink?.invoke(e) }
    }

    /** Entries after [after] (oldest first), at most [limit] — the newest ones when there are more. */
    fun since(after: Long, limit: Int = 500): List<Entry> = synchronized(ring) {
        val out = ring.filter { it.seq > after }
        if (out.size > limit) out.takeLast(limit) else out
    }

    fun all(): List<Entry> = synchronized(ring) { ring.toList() }

    companion object {
        const val MAX_MSG = 2000

        /** The process-wide log every part of the app writes to. */
        val global = DebugLog()

        fun iso(t: Long): String {
            val c = java.util.Calendar.getInstance()
            c.timeInMillis = t
            return "%04d-%02d-%02d %02d:%02d:%02d.%03d".format(c[java.util.Calendar.YEAR], c[java.util.Calendar.MONTH] + 1,
                c[java.util.Calendar.DAY_OF_MONTH], c[java.util.Calendar.HOUR_OF_DAY], c[java.util.Calendar.MINUTE],
                c[java.util.Calendar.SECOND], c[java.util.Calendar.MILLISECOND])
        }
    }
}
