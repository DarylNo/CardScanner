package io.github.darylno.cardscanner.ident

import io.github.darylno.cardscanner.core.ArtMatcher
import io.github.darylno.cardscanner.core.HttpJson
import io.github.darylno.cardscanner.core.IdentifyPipeline
import io.github.darylno.cardscanner.core.ImageSource
import io.github.darylno.cardscanner.core.OcrEngine
import io.github.darylno.cardscanner.core.PrintingRanker
import io.github.darylno.cardscanner.core.ScryfallPrintings
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The ON-PHONE identifier (Stage 2D): thin orchestration over :core's
 * [IdentifyPipeline] — the server's `Pipeline.scan_candidates`, ported and
 * differentially tested (IdentifyParityTest). Input is exactly what the
 * server receives: the capture's primary JPEG (the card flattened WITH a
 * margin), decoded like the upload handler decodes it (`Imgcodecs.imdecode`);
 * the pipeline re-detects and warps inside it as `extract_card` does.
 *
 * Stage 4: this IS the scanner's identifier — [phoneserver.LocalScanUploader]
 * runs every capture through it and files the answer in the phone's store.
 *
 * One identification at a time on its own thread ([submit]); a run is
 * cancellable ([Job.cancel], [cancelAll]) — polled between stages, and an
 * in-flight Scryfall request is aborted through [onCancel]. One Scryfall
 * client and one ranker are kept for the identifier's lifetime, so the
 * ≥110 ms Scryfall spacing and the per-printing hash memo span runs, as on the
 * server.
 */
class PhoneIdentifier(
    private val matcher: () -> ArtMatcher?,
    http: HttpJson,
    images: ImageSource,
    private val ocr: OcrEngine?,
    private val onCancel: () -> Unit = {},
    private val nanoClock: () -> Long = System::nanoTime,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "phone-identify").apply { priority = Thread.MIN_PRIORITY }
    },
) {
    private val scryfall = ScryfallPrintings(http)
    private val ranker = PrintingRanker(images)
    private val runLock = Any()
    private val active = HashSet<Job>()

    /** A result plus the decode time the pipeline doesn't see. */
    class Identified(val result: IdentifyPipeline.Result, val decodeMs: Long, val totalMs: Long) {
        /** Per-stage timings, decode first; `total` = decode + pipeline. */
        val timingsMs: LinkedHashMap<String, Long>
            get() = LinkedHashMap<String, Long>().also {
                it["decode"] = decodeMs
                for ((k, v) in result.timingsMs) if (k != "total") it[k] = v
                it["total"] = totalMs
            }
    }

    class NoArtPackException : Exception("no art pack installed yet")

    /**
     * Scryfall couldn't be reached for this card's printings (offline, 429, 5xx):
     * the ported decisions would file it "identified, no printings" — correct on the
     * always-online computer, wrong on a phone at a shop — so the run is refused and
     * the upload queue tries again later. A 404 (no such card) is an answer, not this.
     */
    class ScryfallUnreachableException(cause: Throwable) :
        java.io.IOException("can't reach Scryfall for the printings (${cause.message})", cause)

    /** A capture that isn't a readable JPEG — retrying can't help. */
    class UndecodableCaptureException : java.io.IOException("capture JPEG doesn't decode")

    /** A queued or running identification. */
    inner class Job internal constructor() {
        internal val cancelled = AtomicBoolean(false)
        val isCancelled: Boolean get() = cancelled.get()
        fun cancel() {
            if (cancelled.compareAndSet(false, true)) onCancel()
        }
    }

    /**
     * Blocking identification of one primary JPEG (serialised with every other
     * run). Throws [NoArtPackException] without a pack, [CancellationException]
     * when [job] is cancelled, IOException when the JPEG doesn't decode.
     */
    fun identify(jpeg: ByteArray, job: Job? = null): Identified = identifyFrames(listOf(jpeg), job)

    /**
     * The server's multi-frame `scan_candidates` over several JPEGs (the upload
     * queue's fallback: the raw upright crops after a weak primary answer).
     * Every frame must decode; the same exceptions as [identify].
     */
    fun identifyFrames(jpegs: List<ByteArray>, job: Job? = null): Identified = synchronized(runLock) {
        require(jpegs.isNotEmpty()) { "no frames" }
        val cancelled = { job?.isCancelled == true }
        if (cancelled()) throw CancellationException("identification cancelled")
        val m = matcher() ?: throw NoArtPackException()
        val t0 = nanoClock()
        val frames = ArrayList<org.opencv.core.Mat>(jpegs.size)
        try {
            for (j in jpegs) frames += CachedImageSource.decode(j) ?: throw UndecodableCaptureException()
            val decodeMs = (nanoClock() - t0) / 1_000_000L
            var unreachable: Throwable? = null
            val printings = { name: String ->
                try {
                    scryfall.getAllPrintings(name)
                } catch (e: io.github.darylno.cardscanner.core.ScryfallException) {
                    throw e                                  // 404: the name has no printings — an answer
                } catch (e: Exception) {
                    unreachable = e                          // network / 429 / 5xx: not an answer
                    throw e
                }
            }
            val pipeline = IdentifyPipeline(m, printings, ranker, ocr, nanoClock)
            val r = pipeline.scan(frames, cancelled = cancelled)
            unreachable?.let { throw ScryfallUnreachableException(it) }
            return Identified(r, decodeMs, (nanoClock() - t0) / 1_000_000L)
        } finally {
            frames.forEach { it.release() }
        }
    }

    /**
     * Queue [jpeg] for identification on the identifier's thread; [done] runs
     * there with the outcome (a cancelled job reports a CancellationException).
     * Returns null when the identifier is shut down.
     */
    fun submit(jpeg: ByteArray, done: (Result<Identified>) -> Unit): Job? {
        val job = Job()
        synchronized(active) { active += job }
        return try {
            executor.execute {
                val r = try {
                    Result.success(identify(jpeg, job))
                } catch (e: Throwable) {
                    Result.failure(e)
                } finally {
                    synchronized(active) { active -= job }
                }
                try { done(r) } catch (_: Exception) { }
            }
            job
        } catch (e: RejectedExecutionException) {
            synchronized(active) { active -= job }
            null
        }
    }

    /** Jobs queued or running. */
    val pending: Int get() = synchronized(active) { active.size }

    fun cancelAll() {
        val all = synchronized(active) { active.toList() }
        all.forEach { it.cancel() }
    }

    fun shutdown() {
        cancelAll()
        executor.shutdown()
    }
}
