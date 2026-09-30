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
                    "job ${job.id.take(8)} ${if (job.manual) "manual" else "auto"}" +
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

    override fun enqueue(capture: Captured, manual: Boolean, openScan: Boolean, replaceScanId: Long?): String =
        queue.enqueue(
            capture.primary, capture.fallbacks, manual, replaceScanId,
            if (openScan) TAG_OPEN else null,
        ).id

    override fun retryNow() = queue.retryNow()
    override fun addListener(l: UploadPort.Listener) { listeners.addIfAbsent(l) }
    override fun removeListener(l: UploadPort.Listener) { listeners.remove(l) }
    override val pending: Int get() = queue.pendingCount()

    companion object {
        const val TAG_OPEN = "open"
        /** A Handheld price check still queued from 1.1.1 or older: opens like [TAG_OPEN]. */
        const val TAG_LEGACY_PRICE_CHECK = "pricecheck"

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
