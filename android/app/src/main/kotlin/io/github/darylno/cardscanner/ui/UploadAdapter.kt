package io.github.darylno.cardscanner.ui

import io.github.darylno.cardscanner.net.ScanOutcome
import io.github.darylno.cardscanner.net.UploadJob
import io.github.darylno.cardscanner.net.UploadQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [UploadPort] over the NET agent's [UploadQueue]. The queue has ONE listener
 * slot; this fans it out to every attached screen. The "open this scan" flag
 * (tapped with Auto off) rides in the job's persisted `tag`, so an outcome
 * delivered after a process restart still opens the scan.
 */
class UploadAdapter(private val queue: UploadQueue) : UploadPort {
    private val listeners = CopyOnWriteArrayList<UploadPort.Listener>()
    @Volatile private var lastLoggedError: String? = null

    init {
        queue.listener = object : UploadQueue.Listener {
            override fun onOutcome(job: UploadJob, outcome: ScanOutcome) {
                val o = map(outcome)
                val open = job.tag == TAG_OPEN || job.tag == TAG_LEGACY_PRICE_CHECK
                io.github.darylno.cardscanner.core.DebugLog.global.i("queue",
                    "job ${jobLabel(job)} ${if (job.manual) "manual" else "auto"}" +
                        (job.replaceScanId?.let { " (retry of #$it)" } ?: "") + " → " + describe(o))
                listeners.forEach { it.onOutcome(job.id, job.manual, open, job.replaceScanId, o) }
            }

            override fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?) {
                if (lastError != lastLoggedError) {
                    lastLoggedError = lastError
                    if (lastError != null) io.github.darylno.cardscanner.core.DebugLog.global.w("queue",
                        "$pending waiting — $lastError" + (nextRetryInMs?.let { " (retry in ${it / 1000}s)" } ?: ""))
                    else io.github.darylno.cardscanner.core.DebugLog.global.i("queue", "connection back; $pending pending")
                }
                listeners.forEach { it.onState(pending, lastError, nextRetryInMs) }
            }
        }
        queue.start()
    }

    override fun enqueue(capture: Captured, manual: Boolean, openScan: Boolean, replaceScanId: Long?): String {
        val job = queue.enqueue(
            capture.primary, capture.fallbacks, manual, replaceScanId,
            if (openScan) TAG_OPEN else null,
        )
        // Ties the capture lines ("#n") to the queue's outcome line ("job …") — 1.1.10 logged every job as 00000000.
        runCatching {
            io.github.darylno.cardscanner.core.DebugLog.global.i("queue", enqueuedLine(capture.burstId, job, queue.pendingCount()))
        }
        return job.id
    }

    override fun retryNow() = queue.retryNow()
    override fun addListener(l: UploadPort.Listener) { listeners.addIfAbsent(l) }
    override fun removeListener(l: UploadPort.Listener) { listeners.remove(l) }
    override val pending: Int get() = queue.pendingCount()

    companion object {
        const val TAG_OPEN = "open"

        /** A Handheld price check still queued from 1.1.1 or older: opens like [TAG_OPEN]. */
        const val TAG_LEGACY_PRICE_CHECK = "pricecheck"

        /**
         * A job as the log names it: its queue number (the "%012d" id without the
         * zeros — 1.1.10 printed the first 8 of 12 digits, always "00000000") and the
         * first 8 of its per-job nonce (the number restarts with an empty queue at
         * launch; the nonce never repeats). Not a secret: the nonce only de-duplicates
         * re-sends inside the phone.
         */
        fun jobLabel(job: UploadJob): String =
            job.id.trimStart('0').ifEmpty { "0" } + " (" + job.nonce.take(8) + ")"

        /** "capture #12 → job 7 (3f2a9c1b) queued · 2 pending" (a shutter retry names the row it replaces). */
        fun enqueuedLine(burstId: Long, job: UploadJob, pending: Int): String =
            (if (burstId > 0) "capture #$burstId" else "capture") + " → job ${jobLabel(job)} queued" +
                (job.replaceScanId?.let { " (retry of #$it)" } ?: "") + " · $pending pending"

        fun describe(o: Outcome): String = when (o) {
            is Outcome.NoCard -> "no card" + if (o.usedFallback) " (after the raw-frame retry)" else ""
            is Outcome.AutoFiled -> "#${o.scanId} auto-filed"
            is Outcome.NeedsPick -> "#${o.scanId} filed, needs a pick"
            is Outcome.BestGuess -> "#${o.scanId} best guess"
            is Outcome.NoMatch -> "#${o.scanId} no match"
            is Outcome.Rejected -> "rejected ${o.code}: ${o.body.take(200)}"
        } + if (o.usedFallback && o !is Outcome.NoCard) " (via the raw frames)" else ""

        fun map(o: ScanOutcome): Outcome = when (o) {
            is ScanOutcome.NoCard -> Outcome.NoCard(o.error, o.usedFallback)
            is ScanOutcome.AutoFiled -> Outcome.AutoFiled(o.json, o.scanId ?: 0L, o.usedFallback)
            is ScanOutcome.NeedsPick -> Outcome.NeedsPick(o.json, o.scanId ?: 0L, o.usedFallback)
            is ScanOutcome.BestGuess -> Outcome.BestGuess(o.json, o.scanId ?: 0L, o.usedFallback)
            is ScanOutcome.NoMatch -> Outcome.NoMatch(o.json, o.scanId ?: 0L, o.usedFallback)
            is ScanOutcome.Rejected -> Outcome.Rejected(o.code, o.body, o.usedFallback)
        }
    }
}
