package io.github.darylno.cardscanner.phoneserver

import android.content.Context
import android.os.StatFs
import io.github.darylno.cardscanner.BuildConfig
import io.github.darylno.cardscanner.core.PrintingCandidates
import io.github.darylno.cardscanner.core.ScanPhoto
import io.github.darylno.cardscanner.core.ScryfallPrintings
import io.github.darylno.cardscanner.core.server.DeviceApi
import io.github.darylno.cardscanner.core.server.LayoutStore
import io.github.darylno.cardscanner.f2f.OkHttpTransport
import io.github.darylno.cardscanner.gateway.AdminPairing
import io.github.darylno.cardscanner.gateway.GatewayServer
import io.github.darylno.cardscanner.gateway.GatewayService
import io.github.darylno.cardscanner.gateway.LocalAddresses
import io.github.darylno.cardscanner.ident.LocalIdentify
import io.github.darylno.cardscanner.ident.PhoneIdentifier
import io.github.darylno.cardscanner.ident.TransportHttpJson
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 4 (owner, 2026-09-30: full cutover, the computer app is retired): the
 * phone IS the scanner's server. Every capture is identified on the phone and
 * filed in this store ([uploader], behind the upload queue); the review pages
 * — the app's own review screen and any computer on the LAN — are served from
 * here through the gateway ([GatewayService] keeps it up while the app runs,
 * screen off included): LAN only, the paired computer is admin, the 6-digit
 * code is admin too while Settings → "Guests get full access" is on (the
 * default) and a guest when it is off, lockouts as before.
 *
 * Storage: the owner's rule (2026-09-30) — at [WARN_AT] scans show the photo
 * size and free space as a warning, then keep going; nothing is pruned.
 */
class PhoneServer(
    private val ctx: Context,
    private val identify: LocalIdentify,
    val device: DeviceBridge,
    private val updates: io.github.darylno.cardscanner.update.UpdateLock? = null,
    private val debugHeader: () -> String = { "" },
    /** The external card list now (null = off) — list_tags on every scan read. */
    private val cardList: () -> io.github.darylno.cardscanner.core.CardList.Parsed? = { null },
    /** The play data now (null = none yet) — `play` on every scan read, GET /api/play-data. */
    private val playData: () -> io.github.darylno.cardscanner.core.PlayData.Pack? = { null },
    /**
     * Where the store is opened and the pricing worker started / stopped: one thread, in
     * order, never the caller's ([onServing] / [onStopped] come from the service's main
     * thread). Tests pass one they run by hand.
     */
    private val lifecycle: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "store-open").apply { isDaemon = true } },
    /**
     * Runs a task after a delay in ms — a worker start that failed is tried again this way (the
     * task itself only queues the retry on [lifecycle]). Tests pass one they run by hand.
     */
    private val later: (Long, Runnable) -> Unit = { ms, r -> RETRY_TIMER.schedule(r, ms, TimeUnit.MILLISECONDS) },
    /**
     * Settings → "Guests get full access" now (AppSettings.guestsFullAccess): handed to the gateway,
     * which asks it per request. Off by default HERE (tests build a PhoneServer without settings);
     * the app passes the setting, which is on by default.
     */
    private val guestsFullAccess: () -> Boolean = { false },
) :
    GatewayService.Host {
    private val root = File(ctx.filesDir, "phoneserver")

    /**
     * Held while the store opens (the [storeLazy]'s lock). The open runs the store's one-time
     * migrations in ONE transaction — 1.1.17's re-price parses every row and rewrites every
     * priced one: est. seconds on the N200 with thousands of scans, not measured — so it must
     * never run on the main thread (an ANR; a kill mid-migration rolls it back to run again).
     * The "store" log line says how long it took. Tests hold it to keep the open pending.
     */
    internal val storeOpening = Any()
    private val storeLazy = lazy(storeOpening) { openStore() }
    val store: SqliteScanStore by storeLazy
    /**
     * The store has opened (its migrations done): reading it no longer waits for the open — but
     * it can still wait for the store's lock (list() holds it while it decodes every row), so the
     * main thread never reads it either way (Settings counts on its io thread).
     */
    val storeOpen: Boolean get() = storeLazy.isInitialized()
    /** The thread that opened the store (null = not yet) — tests check it was never the main one. */
    @Volatile internal var storeOpenedOn: String? = null
        private set

    private fun openStore(): SqliteScanStore {
        val t0 = System.nanoTime()
        val s = SqliteScanStore(File(root, "scans.db"))
        storeOpenedOn = Thread.currentThread().name
        io.github.darylno.cardscanner.core.DebugLog.global.i("store",
            "opened in ${(System.nanoTime() - t0) / 1_000_000} ms on ${Thread.currentThread().name}")
        return s
    }

    val photos = PhotoDir(File(root, "scan_images"))
    private val scryfall by lazy { ScryfallPrintings(TransportHttpJson(OkHttpTransport())) }

    private val backendLazy = lazy {
        PhoneBackend(
            store, photos,
            pages = { name -> runCatching { ctx.assets.open("phoneserver/$name").use { it.readBytes() } }.getOrNull() },
            version = "${BuildConfig.VERSION_NAME} (phone)",
            search = { q -> PrintingCandidates.searchCandidates(scryfall.getAllPrintings(q)) },
            lanIp = { LocalAddresses.list().firstOrNull() },
            packRows = { identify.store.installedManifest()?.rows ?: 0 },
            f2f = f2f,
            launch = { sweepExec.execute(it) },
            now = { System.nanoTime() / 1e9 },
            interrupt = { if (it) f2f.stop.set() else f2f.stop.clear() },
            paceS = { f2f.paceS() },
            f2fEvents = { f2f.recentEvents() },
            layouts = LayoutStore(File(root, "export_layout.json")),
            device = DeviceApi(device),
            debug = io.github.darylno.cardscanner.core.server.DebugApi(io.github.darylno.cardscanner.core.DebugLog.global, debugHeader),
            update = { updates?.let { u -> Triple(u.latest?.version, u.locked, u.latest?.apkUrl) } ?: Triple(null, false, null) },
            cardList = cardList,
            playData = playData,
        )
    }
    /** Opens the store on first use (see [storeOpening]): off the main thread only. */
    val backend: PhoneBackend by backendLazy
    private val f2f by lazy { PhoneF2f(ctx, File(ctx.cacheDir, "facetoface")) }
    private val sweepExec = Executors.newSingleThreadExecutor { r -> Thread(r, "price-sweep").apply { isDaemon = true } }

    /** The paired computer(s): admin. The 6-digit code's role is the "Guests get full access" setting, asked by [GatewayServer] per request. */
    val admins = AdminPairing(File(root, "admins.json"))

    /** The upload queue's door: identify on the phone, file here (see [LocalScanUploader]). */
    val uploader: LocalScanUploader by lazy {
        LocalScanUploader(
            identify = { frames ->
                identify.ensurePack()            // no card database yet → fetch it (rate-limited)
                val log = io.github.darylno.cardscanner.core.DebugLog.global
                try {
                    val r = identify.identifier().identifyFrames(frames)
                    log.i("identify", describeIdentified(frames.size, r.result.json, r.timingsMs))
                    r.photo?.let { log.i("photo", describePhoto(it, frames.size)) }
                    LocalScanUploader.Answer(r.result.json, r.photo?.jpeg, r.photo?.frame ?: 0)
                } catch (e: Exception) {
                    log.w("identify", "${frames.size} frame(s): not identified now, will retry — ${e.javaClass.simpleName}: ${e.message}")
                    throw e
                }
            },
            file = { result, photo, replace -> backend.file(result, photo, replace) },
            answers = File(root, "upload_answers"),
        )
    }

    private val st get() = GatewayService.status.value
    val running: Boolean get() = st.running
    val code: String? get() = st.code.takeIf { st.running }
    val port: Int get() = if (st.running) st.port else PORT

    /** Keep the server up (idempotent): the scan and review screens call this. */
    fun start() = GatewayService.start(ctx, PORT)

    /** Stop serving (the notification's Stop does the same). */
    fun stop() = GatewayService.stop(ctx)

    // ── GatewayService.Host ───────────────────────────────────────────────────
    // The service calls these on the MAIN thread. None of them opens the store there: the
    // gateway asks for the backend per request (a server thread waits for the open), and the
    // worker is started / stopped on [lifecycle], in call order.

    override fun newServer(port: Int) =
        GatewayServer(LocalUpstream { backend }, port, admins = admins, guestsFullAccess = guestsFullAccess)

    /**
     * Bumped by every [onServing] and [onStopped] (on the caller's thread, before their task is
     * queued): a start retry carries the generation it was made in and is dropped once it moved,
     * so a stop (or a newer start) always wins over an older failed start's retry.
     */
    private val servingGen = AtomicInteger()

    override fun onServing() = startWorker(servingGen.incrementAndGet(), attempt = 1)

    /**
     * Starts the price worker on [lifecycle] (the store opens there). If that fails — the open
     * threw, e.g. SQLITE_FULL — it is tried again [START_RETRY_MS] later, again on [lifecycle],
     * for as long as the server keeps serving: otherwise the worker stayed down until the next
     * start of the server, although the store opens fine later on a request or an upload (and
     * 1.1.17's re-price leaves every scan owed, review or not). Every failure and retry is logged.
     */
    private fun startWorker(gen: Int, attempt: Int): Unit = onLifecycle("start the price worker") {
        val log = io.github.darylno.cardscanner.core.DebugLog.global
        // Only a retry is dropped: a first start always runs (a stop queued behind it stops it).
        if (attempt > 1 && gen != servingGen.get()) {
            log.i("server", "price worker: retry #${attempt - 1} dropped — the server stopped (or restarted) since")
            return@onLifecycle
        }
        try {
            backend.worker.start()           // opens the store here; price whatever is owed, then wait for scans
            if (attempt > 1) log.i("server", "price worker started on retry #${attempt - 1}")
        } catch (e: Exception) {
            log.e("server", "couldn't start the price worker (try $attempt) — trying again in " +
                "${START_RETRY_MS / 1000} s", e)
            later(START_RETRY_MS, Runnable { startWorker(gen, attempt + 1) })
        }
    }

    override fun onStopped() {
        admins.cancelCode()                  // a code shown before the stop is not good after a restart
        servingGen.incrementAndGet()         // a start retry still pending is dropped
        // Queued behind any start: a stop before the open finished still stops the worker that
        // start will have started. A backend never built had no worker to stop.
        onLifecycle("stop the price worker") {
            if (!backendLazy.isInitialized()) return@onLifecycle
            backend.sweep.cancel()           // first: a running sweep ends within one card…
            backend.worker.stop()            // …so the worker's join doesn't time out on it
        }
    }

    private fun onLifecycle(what: String, block: () -> Unit) {
        val work = Runnable {
            try { block() } catch (e: Exception) {
                // Never up to the thread's uncaught handler: that would end the app. Logged instead.
                io.github.darylno.cardscanner.core.DebugLog.global.e("server", "couldn't $what", e)
            }
        }
        try { lifecycle.execute(work) } catch (_: java.util.concurrent.RejectedExecutionException) { work.run() }
    }

    /** The phone's own review screen: the local address and the owner's admin cookie. */
    private val deleteExec = Executors.newSingleThreadExecutor { r -> Thread(r, "owner-delete").apply { isDaemon = true } }

    /**
     * Delete scans as the owner, off the UI thread — the review screen's swipes whose
     * Undo window hadn't run out when the screen closed (see PanelActivity.onPause).
     * Goes through the API like the page's own DELETE, so the photo goes too and the
     * pricing worker is told; an id already deleted just answers 404.
     */
    fun deleteScansAsOwner(ids: List<Long>, done: () -> Unit = {}) {
        val work = Runnable {
            for (id in ids) runCatching {
                backend.handle(io.github.darylno.cardscanner.core.server.ApiRequest("DELETE", "/api/scans/$id",
                    role = io.github.darylno.cardscanner.core.server.ROLE_ADMIN))
            }
            done()
        }
        try { deleteExec.execute(work) } catch (_: java.util.concurrent.RejectedExecutionException) { work.run() }
    }

    /**
     * Delete EVERY scan and its photo as the owner (Settings → Delete all scans), off the
     * UI thread, through the API's own `POST /api/scans/delete-all` (what the pages'
     * "Clear all" sends) so the photos go too and the pricing worker is told.
     * [done] gets the number deleted (−1 when the request failed), on the worker thread.
     */
    fun deleteAllScansAsOwner(done: (Int) -> Unit = {}) {
        val work = Runnable {
            val n = try {
                val res = backend.handle(io.github.darylno.cardscanner.core.server.ApiRequest(
                    "POST", "/api/scans/delete-all", body = "{}".toByteArray(Charsets.UTF_8),
                    role = io.github.darylno.cardscanner.core.server.ROLE_ADMIN))
                val body = io.github.darylno.cardscanner.core.MiniJson.parse(String(res.body, Charsets.UTF_8)) as? Map<*, *>
                if (res.status == 200) ((body?.get("deleted") as? Number)?.toInt() ?: -1) else -1
            } catch (e: Exception) { -1 }
            io.github.darylno.cardscanner.core.DebugLog.global.i("scans",
                if (n >= 0) "deleted all scans from Settings: $n" else "delete all scans from Settings FAILED")
            done(n)
        }
        try { deleteExec.execute(work) } catch (_: java.util.concurrent.RejectedExecutionException) { work.run() }
    }

    /** One log line per identification: what it read, how sure, how many printings, OCR, timings. */
    internal fun describeIdentified(frames: Int, json: Map<String, Any?>, timings: Map<String, Long>): String {
        val read = json["card_read"] as? Map<*, *>
        val name = read?.get("name") ?: "?"
        val cands = json["candidates"] as? List<*> ?: emptyList<Any?>()
        val top = cands.firstOrNull() as? Map<*, *>
        val t = timings.entries.joinToString(" ") { "${it.key} ${it.value}" }
        val what = when {
            json["no_card"] == true -> "no card — ${json["error"]}"
            json["identified"] == true -> "$name · ${cands.size} printing(s)" +
                (top?.let { " · top ${it["set"]} ${it["collector_number"]}" } ?: "") +
                (if (top?.get("ocr_confirmed") == true) " · OCR ✓" else "") +
                ((json["confidence"] as? Map<*, *>)?.get("name")?.let { " · $it" } ?: "")
            else -> "not identified — ${json["error"]}"
        }
        return "${frames} frame(s): $what · ms: $t"
    }

    /** The photo filed with a scan (the reference's photo step, [ScanPhoto]). */
    internal fun describePhoto(p: PhoneIdentifier.Photo, frames: Int): String {
        val jpeg = p.jpeg
        return if (jpeg != null) {
            "card straightened ${ScanPhoto.PHOTO_W}×${ScanPhoto.PHOTO_H} from frame ${p.frame + 1}/$frames · ${jpeg.size / 1024} KB · ${p.ms} ms"
        } else {
            "no card edges — frame ${p.frame + 1}/$frames kept as uploaded · ${p.ms} ms"
        }
    }

    fun localBase(): String = "http://127.0.0.1:$port"
    fun ownerCookie(): String = "${GatewayServer.ADMIN_COOKIE}=${admins.ownerToken}"

    /** The address to open on the computer (join link with the code). */
    fun joinUrl(): String? {
        val c = code ?: return null
        val ip = LocalAddresses.list().firstOrNull() ?: return null
        return LocalAddresses.joinUrl(ip, port, c)
    }

    /** A fresh one-time ADMIN pairing link (the code is also typed-in-able), or null without Wi-Fi. */
    fun adminPairUrl(): Pair<String, String>? {
        if (!running) return null
        val ip = LocalAddresses.list().firstOrNull() ?: return null
        val c = admins.newCode()
        return LocalAddresses.joinUrl(ip, port, c) to c
    }

    /**
     * "N scans · X MB of photos · Y GB free" and whether it's past the warning line.
     * Waits for the store to open: off the main thread only (Settings reads it on its io thread).
     */
    fun usage(): Usage {
        val n = runCatching { store.count() }.getOrDefault(0)
        val bytes = photos.sizeBytes()
        val free = runCatching { StatFs(ctx.filesDir.path).availableBytes }.getOrDefault(-1L)
        return Usage(n, bytes, free)
    }

    data class Usage(val scans: Int, val photoBytes: Long, val freeBytes: Long) {
        val warn: Boolean get() = scans >= WARN_AT
        fun label(): String {
            val mb = photoBytes / (1024.0 * 1024.0)
            val base = "$scans scans · ${"%.1f".format(mb)} MB of photos" +
                if (freeBytes >= 0) " · ${"%.1f".format(freeBytes / (1024.0 * 1024 * 1024))} GB free" else ""
            return if (warn) "⚠ $base — past $WARN_AT scans; clear old scans if space runs low" else base
        }
    }

    companion object {
        const val PORT = 8090
        const val WARN_AT = 10_000
        /** A price worker start that failed (the store's open threw) is tried again after this. */
        const val START_RETRY_MS = 30_000L

        /** The default [later]: one daemon thread, only ever asleep between failed starts. */
        private val RETRY_TIMER: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "store-retry").apply { isDaemon = true } }
        }
    }
}
