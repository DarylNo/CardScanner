package io.github.darylno.cardscanner.net

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What one scan came to, classified exactly like phone.html `submitScan`:
 * `no_card` → [NoCard]; `identified` → [AutoFiled] when the server auto-picked
 * (`selection.auto_picked`; a legacy `merged_into` counts too — current
 * servers keep repeats as their own rows) else [NeedsPick]; not identified
 * but with candidates → [BestGuess]; nothing → [NoMatch] (offer Retry).
 * [Rejected] = the server refused the upload (4xx) and the job was dropped.
 *
 * [usedFallback]: the sharp primary frame wasn't enough and the 3 raw frames
 * were re-sent (the server's multi-frame retry).
 */
sealed class ScanOutcome {
    abstract val usedFallback: Boolean

    /** The server's scan row id, when one exists. */
    open val scanId: Long? get() = null

    /** The server's JSON (null for [NoCard]/[Rejected]). */
    open val json: JSONObject? get() = null

    /** `card_read.name`, when the server read one. */
    val cardName: String?
        get() = json?.optJSONObject("card_read")?.optString("name")?.takeIf { it.isNotBlank() }

    data class NoCard(val error: String, override val usedFallback: Boolean = false) : ScanOutcome()

    data class AutoFiled(
        override val json: JSONObject,
        override val scanId: Long?,
        override val usedFallback: Boolean = false,
    ) : ScanOutcome()

    data class NeedsPick(
        override val json: JSONObject,
        override val scanId: Long?,
        override val usedFallback: Boolean = false,
    ) : ScanOutcome()

    data class BestGuess(
        override val json: JSONObject,
        override val scanId: Long?,
        override val usedFallback: Boolean = false,
    ) : ScanOutcome()

    data class NoMatch(
        override val json: JSONObject,
        override val scanId: Long?,
        override val usedFallback: Boolean = false,
    ) : ScanOutcome() {
        /** The server's reason, as phone.html shows it (`data.error || 'No match.'`). */
        val error: String get() = json.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: "No match."
    }

    data class Rejected(val code: Int, val body: String, override val usedFallback: Boolean = false) : ScanOutcome()

    fun withFallback(): ScanOutcome = when (this) {
        is NoCard -> copy(usedFallback = true)
        is AutoFiled -> copy(usedFallback = true)
        is NeedsPick -> copy(usedFallback = true)
        is BestGuess -> copy(usedFallback = true)
        is NoMatch -> copy(usedFallback = true)
        is Rejected -> copy(usedFallback = true)
    }

    companion object {
        fun classify(json: JSONObject): ScanOutcome {
            if (json.optBoolean("no_card")) {
                val err = json.optString("error").takeIf { it.isNotBlank() && it != "null" }
                return NoCard(err ?: "No card detected.")
            }
            val id = json.optLong("id", 0L).takeIf { it > 0 }
            if (json.optBoolean("identified")) {
                val merged = json.optLong("merged_into", 0L).takeIf { it > 0 }
                val auto = json.optJSONObject("selection")?.optBoolean("auto_picked") == true
                return if (auto || merged != null) AutoFiled(json, merged ?: id) else NeedsPick(json, id)
            }
            val cands = json.optJSONArray("candidates")
            return if (cands != null && cands.length() > 0) BestGuess(json, id) else NoMatch(json, id)
        }
    }
}

/** A queued capture. [tag] is free-form, persisted, handed back with the outcome (e.g. "handheld"). */
data class UploadJob(
    val id: String,
    val createdAt: Long,
    val manual: Boolean,
    val replaceScanId: Long?,
    val tag: String?,
    /**
     * Random per job, persisted with it: the server's re-send dedupe key. The
     * [id] counter restarts at 1 whenever the app starts with an empty queue,
     * so keying uploads on it (1.0.6–1.0.8) made a fresh scan reuse an old
     * upload id and get the OLD card's reply back, filing nothing.
     */
    val nonce: String = java.util.UUID.randomUUID().toString(),
)

/**
 * Persistent, ordered upload queue: the capture thread hands over JPEG bytes
 * and returns immediately; a single worker thread uploads jobs strictly in
 * FIFO order, surviving dropped connections (Tailscale away from home) and
 * process death.
 *
 * On disk: `<dir>/job-<seq>/` with `primary.jpg`, `fallback0..2.jpg`,
 * `meta.json` (+ `primary_result.json` once the primary was answered). A job
 * is assembled in `.tmp-job-<seq>/` and renamed into place, so a crash never
 * leaves a half-written job that looks real; `meta.json` updates are
 * tmp + atomic rename too.
 *
 * Per job: upload the primary (sharpest, flattened) frame; if the server says
 * no_card or not identified AND fallbacks exist, persist stage=fallback and
 * re-send the 3 raw frames with `replace_scan_id` = the row the primary
 * created (else the job's own replaceScanId) — reproducing the server's
 * multi-frame retry. Only the FINAL outcome is delivered. Transport errors,
 * 5xx, 408 and 429 retry forever with backoff 1,2,4,8,16,30,30… s
 * ([retryNow] resets it); any other 4xx drops the job as [ScanOutcome.Rejected].
 */
class UploadQueue(
    private val dir: File,
    private val uploader: ScanUploader,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
) {
    interface Listener {
        /** Worker thread. */
        fun onOutcome(job: UploadJob, outcome: ScanOutcome)

        /** Worker thread. [pending] includes the job in flight; [nextRetryInMs] set while backing off. */
        fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?)
    }

    @Volatile var listener: Listener? = null

    /** The most recent final outcome (for a UI that attaches late). */
    @Volatile var lastOutcome: Pair<UploadJob, ScanOutcome>? = null
        private set

    private val lock = Object()
    private var kick = false        // retryNow / stop: ends any wait
    private var newJob = false      // enqueue: ends an IDLE wait only (backoff is honoured)
    private var resetBackoff = false
    @Volatile private var running = false
    private var worker: Thread? = null
    private var seq: Long

    init {
        dir.mkdirs()
        dir.listFiles()?.filter { it.name.startsWith(".tmp-") }?.forEach { it.deleteRecursively() }
        seq = jobDirs().mapNotNull { it.name.removePrefix(JOB_PREFIX).toLongOrNull() }.maxOrNull() ?: 0L
    }

    /**
     * Persist a capture (fsync'd, atomic) and wake the worker. Safe from any
     * thread; does disk I/O only, never network.
     */
    fun enqueue(
        primary: ByteArray,
        fallbacks: List<ByteArray>,
        manual: Boolean,
        replaceScanId: Long? = null,
        tag: String? = null,
    ): UploadJob {
        val n = synchronized(lock) { ++seq }
        val id = "%012d".format(n)
        val job = UploadJob(id, System.currentTimeMillis(), manual, replaceScanId?.takeIf { it > 0 }, tag)
        val tmp = File(dir, ".tmp-$JOB_PREFIX$id")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IOException("cannot create $tmp")
        writeSynced(File(tmp, PRIMARY), primary)
        fallbacks.forEachIndexed { i, b -> writeSynced(File(tmp, "fallback$i.jpg"), b) }
        writeSynced(File(tmp, META), Meta(job, STAGE_PRIMARY, null).toJson().toString().toByteArray())
        Files.move(tmp.toPath(), File(dir, JOB_PREFIX + id).toPath(), StandardCopyOption.ATOMIC_MOVE)
        synchronized(lock) {
            newJob = true
            lock.notifyAll()
        }
        return job
    }

    /** Start the worker (idempotent). Jobs left from a previous process resume first. */
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            worker = Thread(::runLoop, "upload-queue").apply { isDaemon = true; start() }
        }
    }

    /** Stop the worker (jobs stay on disk). */
    fun stop() {
        val t: Thread?
        synchronized(lock) {
            running = false
            kick = true
            lock.notifyAll()
            t = worker
            worker = null
        }
        t?.join(5_000)
    }

    /** Skip the current backoff wait and reset the backoff ladder (e.g. network came back, re-paired). */
    fun retryNow() {
        synchronized(lock) {
            resetBackoff = true
            kick = true
            lock.notifyAll()
        }
    }

    /** Jobs on disk, including the one in flight. */
    fun pendingCount(): Int = jobDirs().size

    // ── worker ───────────────────────────────────────────────────────────────

    private sealed class Step {
        class Done(val outcome: ScanOutcome) : Step()
        class Retry(val error: String) : Step()
    }

    private fun runLoop() {
        var failures = 0
        var lastError: String? = null
        while (running) {
            synchronized(lock) {
                if (resetBackoff) { failures = 0; resetBackoff = false }
            }
            val jobDir = jobDirs().firstOrNull()
            if (jobDir == null) {
                notifyState(0, null, null)
                lastError = null
                waitFor(null, idle = true)
                continue
            }
            notifyState(pendingCount(), lastError, null)
            val meta = readMeta(jobDir)
            if (meta == null) {               // unreadable job: never wedge the queue on it
                jobDir.deleteRecursively()
                continue
            }
            when (val step = process(jobDir, meta)) {
                is Step.Done -> {
                    failures = 0
                    lastError = null
                    jobDir.deleteRecursively()
                    lastOutcome = meta.job to step.outcome
                    try {
                        listener?.onOutcome(meta.job, step.outcome)
                    } catch (_: Exception) {
                        // a UI bug must not stall uploads
                    }
                }
                is Step.Retry -> {
                    lastError = step.error
                    val delay = backoffMs[minOf(failures, backoffMs.size - 1)]
                    failures++
                    notifyState(pendingCount(), lastError, delay)
                    waitFor(delay, idle = false)
                }
            }
        }
    }

    private fun process(jobDir: File, meta0: Meta): Step {
        var meta = meta0
        val fallbacks = (0 until 8).map { File(jobDir, "fallback$it.jpg") }.takeWhile { it.isFile }
        try {
            if (meta.stage == STAGE_PRIMARY) {
                // Stage-specific ids: a re-send after a lost reply gets the
                // row the server already filed instead of filing it twice.
                val json = uploader.scan(listOf(File(jobDir, PRIMARY).readBytes()), meta.job.replaceScanId,
                                         "${meta.job.nonce}-p")
                val out = ScanOutcome.classify(json)
                val weak = out is ScanOutcome.NoCard || !json.optBoolean("identified")
                if (!weak || fallbacks.isEmpty()) return Step.Done(out)
                writeAtomic(File(jobDir, PRIMARY_RESULT), json.toString().toByteArray())
                meta = meta.copy(stage = STAGE_FALLBACK, primaryScanId = out.scanId)
                writeAtomic(File(jobDir, META), meta.toJson().toString().toByteArray())
            }
            val primaryOut = readPrimaryOutcome(jobDir)
            val replace = meta.primaryScanId ?: meta.job.replaceScanId
            if (fallbacks.isEmpty()) return Step.Done(primaryOut ?: ScanOutcome.NoCard("No card detected."))
            val json = uploader.scan(fallbacks.map { it.readBytes() }, replace, "${meta.job.nonce}-f")
            val out = ScanOutcome.classify(json).withFallback()
            // no_card on the raw frames leaves the primary's row untouched on
            // the server (its no_card path returns before replacing) — report
            // that row rather than hiding it behind "No card detected".
            if (out is ScanOutcome.NoCard && primaryOut != null && primaryOut.scanId != null) {
                return Step.Done(primaryOut.withFallback())
            }
            return Step.Done(out)
        } catch (e: HttpException) {
            if (e.code >= 500 || e.code == 408 || e.code == 429) return Step.Retry("Server error ${e.code}")
            val primaryOut = if (meta.stage == STAGE_FALLBACK) readPrimaryOutcome(jobDir) else null
            if (primaryOut != null && primaryOut.scanId != null) return Step.Done(primaryOut.withFallback())
            return Step.Done(ScanOutcome.Rejected(e.code, e.body, meta.stage == STAGE_FALLBACK))
        } catch (e: IOException) {
            return Step.Retry(e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            // Malformed JSON etc.: keep the scan, try again later.
            return Step.Retry(e.message ?: e.javaClass.simpleName)
        } catch (e: Throwable) {
            // Stage 4: identification runs on this thread — an Error (OOM, a missing native
            // lib) must not kill the process, which would restart on the same job and loop.
            return Step.Retry(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readPrimaryOutcome(jobDir: File): ScanOutcome? = try {
        val f = File(jobDir, PRIMARY_RESULT)
        if (f.isFile) ScanOutcome.classify(JSONObject(f.readText())) else null
    } catch (_: Exception) {
        null
    }

    private fun waitFor(ms: Long?, idle: Boolean) {
        synchronized(lock) {
            val deadline = ms?.let { System.currentTimeMillis() + it }
            while (running && !kick && !(idle && newJob)) {
                if (deadline == null) {
                    lock.wait()
                } else {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    lock.wait(left)
                }
            }
            kick = false
            newJob = false
        }
    }

    private fun notifyState(pending: Int, lastError: String?, nextRetryInMs: Long?) {
        try {
            listener?.onState(pending, lastError, nextRetryInMs)
        } catch (_: Exception) {
        }
    }

    // ── disk ─────────────────────────────────────────────────────────────────

    private data class Meta(val job: UploadJob, val stage: String, val primaryScanId: Long?) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", job.id)
            put("createdAt", job.createdAt)
            put("manual", job.manual)
            job.replaceScanId?.let { put("replaceScanId", it) }
            job.tag?.let { put("tag", it) }
            put("nonce", job.nonce)
            put("stage", stage)
            primaryScanId?.let { put("primaryScanId", it) }
        }
    }

    private fun readMeta(jobDir: File): Meta? = try {
        val o = JSONObject(File(jobDir, META).readText())
        val job = UploadJob(
            id = o.getString("id"),
            createdAt = o.optLong("createdAt"),
            manual = o.optBoolean("manual"),
            replaceScanId = o.optLong("replaceScanId", 0L).takeIf { it > 0 },
            tag = if (o.has("tag")) o.optString("tag") else null,
            // A job queued by 1.0.6–1.0.8 has no nonce: id + creation time is
            // unique across restarts, and stable across this job's re-sends.
            nonce = o.optString("nonce").ifEmpty { "${o.getString("id")}-${o.optLong("createdAt")}" },
        )
        if (!File(jobDir, PRIMARY).isFile) null
        else Meta(job, o.optString("stage", STAGE_PRIMARY), o.optLong("primaryScanId", 0L).takeIf { it > 0 })
    } catch (_: Exception) {
        null
    }

    private fun jobDirs(): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.startsWith(JOB_PREFIX) }
            .sortedBy { it.name }

    private fun writeSynced(f: File, bytes: ByteArray) {
        FileOutputStream(f).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    private fun writeAtomic(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        writeSynced(tmp, bytes)
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        val DEFAULT_BACKOFF_MS: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
        private const val JOB_PREFIX = "job-"
        private const val PRIMARY = "primary.jpg"
        private const val META = "meta.json"
        private const val PRIMARY_RESULT = "primary_result.json"
        private const val STAGE_PRIMARY = "primary"
        private const val STAGE_FALLBACK = "fallback"
    }
}
