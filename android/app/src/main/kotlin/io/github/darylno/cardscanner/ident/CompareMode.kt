package io.github.darylno.cardscanner.ident

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.f2f.CronetTransport
import io.github.darylno.cardscanner.f2f.F2fTransport
import io.github.darylno.cardscanner.f2f.OkHttpTransport
import io.github.darylno.cardscanner.ocr.MlKitOcrEngine
import io.github.darylno.cardscanner.ui.Captured
import io.github.darylno.cardscanner.ui.Outcome
import io.github.darylno.cardscanner.ui.UploadPort
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * Stage 2D "compare" mode — hidden, default OFF (Diagnostics). The server
 * stays the judge: scans upload and file exactly as before. When ON, after
 * the server's final answer to a capture arrives, the phone identifies the
 * SAME primary JPEG with [PhoneIdentifier] and records a [CompareRow] in
 * [log] (server vs phone: name, printing, confidence, auto-pick, OCR, phone
 * timings). Nothing the phone concludes is sent anywhere or acted on.
 *
 * Capture bytes are held in memory only for jobs captured while the mode is
 * ON (bounded, [MAX_HELD]); a process death loses them and those jobs are
 * simply not compared. The latest capture is also kept for "Test on last
 * capture" (in memory; also in the cache dir while the mode is ON).
 */
class CompareMode(private val ctx: Context, private val flag: Flag) {
    /** Where the ON/OFF switch lives (AppSettings in the app). */
    interface Flag { var compareMode: Boolean }

    val store = ArtPackStore(File(ctx.filesDir, "artpack"))
    val log = CompareLog(File(ctx.filesDir, "compare/compare.jsonl"))

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "compare-io").apply { priority = Thread.MIN_PRIORITY } }
    private val held = LinkedHashMap<String, ByteArray>()
    private val lastFile = File(ctx.cacheDir, "compare/last_capture.jpg")
    @Volatile private var lastJobId: String? = null
    @Volatile private var lastBytes: ByteArray? = null
    @Volatile private var lastServer: Pair<Outcome, String>? = null   // outcome, job id

    /**
     * Stage 3 preview hook: every AUTO capture the phone identifies (not
     * "Test on last capture"), with its photo — the preview files it into the
     * phone's own store. Called on the identifier thread.
     */
    @Volatile var onPhoneIdentified: ((ident: PhoneIdentifier.Identified, jpeg: ByteArray) -> Unit)? = null

    /** One line for Diagnostics: what compare mode is doing now. */
    @Volatile var status: String = ""
        private set

    @Volatile private var identifier: PhoneIdentifier? = null
    private var transport: F2fTransport? = null

    val enabled: Boolean get() = flag.compareMode

    fun setEnabled(on: Boolean) {
        flag.compareMode = on
        if (on) checkPackAsync(force = false) else {
            synchronized(held) { held.clear() }
            identifier?.cancelAll()
        }
        log.notifyChanged()
    }

    /** Called on the upload thread right after a capture is queued. */
    fun onEnqueued(jobId: String, capture: Captured) {
        lastJobId = jobId
        lastBytes = capture.primary
        lastServer = null
        if (!enabled) return                 // OFF: nothing but one reference in memory
        runIo { writeLast(capture.primary) }
        synchronized(held) {
            held[jobId] = capture.primary
            while (held.size > MAX_HELD) held.remove(held.keys.first())
        }
    }

    /** Called (upload worker thread) with the server's FINAL outcome for a job. */
    fun onOutcome(jobId: String, outcome: Outcome) {
        if (jobId == lastJobId) lastServer = outcome to jobId
        val bytes = synchronized(held) { held.remove(jobId) } ?: return
        if (!enabled || outcome is Outcome.Rejected) return
        run(jobId, "auto", bytes, outcome) { }
    }

    /** Diagnostics → "Test on last capture". [done] runs on the identifier thread. */
    fun testLastCapture(done: (String) -> Unit) {
        val bytes = lastBytes ?: runCatching { lastFile.takeIf { it.isFile }?.readBytes() }.getOrNull()
        if (bytes == null) { done(NO_CAPTURE); return }
        val server = lastServer
        run(lastJobId ?: "last", "test", bytes, server?.first, done)
    }

    private fun run(jobId: String, source: String, bytes: ByteArray, server: Outcome?, done: (String) -> Unit) {
        val id = identifier()
        status = "identifying ${if (source == "test") "last capture" else "job $jobId"}…"
        log.notifyChanged()
        val job = id.submit(bytes) { r ->
            val msg = r.fold(
                onSuccess = { ident ->
                    if (source == "auto") runCatching { onPhoneIdentified?.invoke(ident, bytes) }
                    val row = server?.let { buildRow(jobId, source, it, ident) }
                    if (row != null) log.add(row)
                    val phone = phoneSummary(ident)
                    "phone: ${phone.label()} (${phone.confidence ?: "-"}${if (ident.result.wouldAutoPick) ", would auto-pick" else ""})" +
                        " · ${ident.totalMs} ms" +
                        (row?.let { " · server: ${it.server.label()} · " + if (it.agree) "AGREE" else "DISAGREE (${it.disagreements().joinToString()})" }
                            ?: " · server answer not known")
                },
                onFailure = { e ->
                    when (e) {
                        is PhoneIdentifier.NoArtPackException -> NO_PACK
                        is CancellationException -> "cancelled"
                        else -> "phone identify failed: ${e.javaClass.simpleName}: ${e.message}"
                    }
                },
            )
            status = msg
            log.notifyChanged()
            done(msg)
        }
        if (job == null) done("identifier stopped")
    }

    @Synchronized
    private fun identifier(): PhoneIdentifier = identifier ?: run {
        val t: F2fTransport = try {
            CronetTransport.create(ctx.applicationContext)
        } catch (e: Throwable) {
            OkHttpTransport()                    // no packaged Cronet (e.g. tests): plain OkHttp
        }
        transport = t
        PhoneIdentifier(
            matcher = { store.matcher() },
            http = TransportHttpJson(t),
            images = CachedImageSource(File(ctx.cacheDir, "scryfall-small")),
            ocr = MlKitOcrEngine(),
            onCancel = { t.cancel() },
        ).also { identifier = it }
    }

    /** Check for a new pack in the background (weekly on Wi-Fi, or now if [force]). */
    fun checkPackAsync(force: Boolean, done: (ArtPackStore.Check) -> Unit = {}) {
        runIo {
            val c = store.check(force, unmetered(ctx))
            if (c !is ArtPackStore.Check.Skipped) store.matcher()   // load it off the main thread
            log.notifyChanged()
            done(c)
        }
    }

    /** Warm the pack at app start when compare mode is on. */
    fun onAppStart() {
        if (enabled) checkPackAsync(force = false)
    }

    private fun runIo(block: () -> Unit) {
        try { io.execute { runCatching(block) } } catch (_: Exception) { }
    }

    private fun writeLast(b: ByteArray) {
        lastFile.parentFile?.mkdirs()
        val tmp = File(lastFile.parentFile, "last_capture.tmp")
        tmp.writeBytes(b)
        if (!tmp.renameTo(lastFile)) tmp.delete()
    }

    companion object {
        const val MAX_HELD = 8
        const val NO_PACK = "no art pack yet — tap Check for pack (the first CI build may still be running)"
        const val NO_CAPTURE = "no capture yet — scan a card first"

        fun unmetered(ctx: Context): Boolean = try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps != null && (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        } catch (_: Exception) {
            false
        }

        fun phoneSummary(ident: PhoneIdentifier.Identified): SideSummary =
            SideSummary.fromResult(JSONObject(MiniJson.stringify(ident.result.json)), ident.result.wouldAutoPick)

        /** The server side of a final upload outcome; null for a rejected upload (nothing to compare). */
        fun serverSummary(o: Outcome): SideSummary? = when (o) {
            is Outcome.NoCard -> SideSummary(identified = false, noCard = true, name = null, printingId = null,
                set = null, collectorNumber = null, confidence = null, autoPick = false, ocrConfirmed = false,
                candidates = 0, error = o.error)
            is Outcome.AutoFiled -> SideSummary.fromResult(o.json, autoPick = true)
            is Outcome.NeedsPick -> SideSummary.fromResult(o.json, autoPick = false)
            is Outcome.BestGuess -> SideSummary.fromResult(o.json, autoPick = false)
            is Outcome.NoMatch -> SideSummary.fromResult(o.json, autoPick = false)
            is Outcome.Rejected -> null
        }

        fun buildRow(jobId: String, source: String, server: Outcome, ident: PhoneIdentifier.Identified,
                     at: Long = System.currentTimeMillis()): CompareRow? {
            val s = serverSummary(server) ?: return null
            return CompareRow(at, jobId, source, s, phoneSummary(ident), server.usedFallback, ident.timingsMs,
                phoneOcrText = ident.result.ocrText)
        }
    }
}

/**
 * [UploadPort] with the Compare tap: every capture is handed to [compare]
 * after it is queued and every final outcome after it is delivered. The
 * upload itself is [delegate]'s, untouched.
 */
class CompareUploadPort(private val delegate: UploadPort, private val compare: CompareMode) : UploadPort by delegate {
    init {
        delegate.addListener(object : UploadPort.Listener {
            override fun onOutcome(jobId: String, manual: Boolean, priceCheck: Boolean, replaceScanId: Long?, outcome: Outcome) {
                try { compare.onOutcome(jobId, outcome) } catch (_: Exception) { }
            }

            override fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?) {}
        })
    }

    override fun enqueue(capture: Captured, manual: Boolean, priceCheck: Boolean, replaceScanId: Long?): String {
        val id = delegate.enqueue(capture, manual, priceCheck, replaceScanId)
        try { compare.onEnqueued(id, capture) } catch (_: Exception) { }
        return id
    }
}
