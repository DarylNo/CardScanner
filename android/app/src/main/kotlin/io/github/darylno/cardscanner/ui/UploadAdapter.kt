package io.github.darylno.cardscanner.ui

import io.github.darylno.cardscanner.net.ScanOutcome
import io.github.darylno.cardscanner.net.UploadJob
import io.github.darylno.cardscanner.net.UploadQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [UploadPort] over the NET agent's [UploadQueue]. The queue has ONE listener
 * slot; this fans it out to every attached screen. The Handheld price-check
 * flag rides in the job's persisted `tag`, so an outcome delivered after a
 * process restart still opens the price check.
 */
class UploadAdapter(private val queue: UploadQueue) : UploadPort {
    private val listeners = CopyOnWriteArrayList<UploadPort.Listener>()

    init {
        queue.listener = object : UploadQueue.Listener {
            override fun onOutcome(job: UploadJob, outcome: ScanOutcome) {
                val o = map(outcome)
                val pc = job.tag == TAG_PRICE_CHECK
                listeners.forEach { it.onOutcome(job.id, job.manual, pc, job.replaceScanId, o) }
            }

            override fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?) {
                listeners.forEach { it.onState(pending, lastError, nextRetryInMs) }
            }
        }
        queue.start()
    }

    override fun enqueue(capture: Captured, manual: Boolean, priceCheck: Boolean, replaceScanId: Long?): String =
        queue.enqueue(
            capture.primary, capture.fallbacks, manual, replaceScanId,
            if (priceCheck) TAG_PRICE_CHECK else null,
        ).id

    override fun retryNow() = queue.retryNow()
    override fun addListener(l: UploadPort.Listener) { listeners.addIfAbsent(l) }
    override fun removeListener(l: UploadPort.Listener) { listeners.remove(l) }
    override val pending: Int get() = queue.pendingCount()

    companion object {
        const val TAG_PRICE_CHECK = "pricecheck"

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
