package io.github.darylno.cardscanner.phoneserver

import android.content.Context
import android.os.StatFs
import io.github.darylno.cardscanner.BuildConfig
import io.github.darylno.cardscanner.core.PrintingCandidates
import io.github.darylno.cardscanner.core.ScryfallPrintings
import io.github.darylno.cardscanner.core.server.DeviceApi
import io.github.darylno.cardscanner.core.server.LayoutStore
import io.github.darylno.cardscanner.f2f.OkHttpTransport
import io.github.darylno.cardscanner.gateway.AdminPairing
import io.github.darylno.cardscanner.gateway.GatewayServer
import io.github.darylno.cardscanner.gateway.GatewayService
import io.github.darylno.cardscanner.gateway.LocalAddresses
import io.github.darylno.cardscanner.ident.LocalIdentify
import io.github.darylno.cardscanner.ident.TransportHttpJson
import java.io.File
import java.util.concurrent.Executors

/**
 * Stage 4 (owner, 2026-09-30: full cutover, the computer app is retired): the
 * phone IS the scanner's server. Every capture is identified on the phone and
 * filed in this store ([uploader], behind the upload queue); the review pages
 * — the app's own review screen and any computer on the LAN — are served from
 * here through the gateway ([GatewayService] keeps it up while the app runs,
 * screen off included): LAN only, the paired computer is admin, the 6-digit
 * code makes a guest, lockouts as before.
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
) :
    GatewayService.Host {
    private val root = File(ctx.filesDir, "phoneserver")
    val store: SqliteScanStore by lazy { SqliteScanStore(File(root, "scans.db")) }
    val photos = PhotoDir(File(root, "scan_images"))
    private val scryfall by lazy { ScryfallPrintings(TransportHttpJson(OkHttpTransport())) }

    val backend: PhoneBackend by lazy {
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
        )
    }
    private val f2f by lazy { PhoneF2f(ctx, File(ctx.cacheDir, "facetoface")) }
    private val sweepExec = Executors.newSingleThreadExecutor { r -> Thread(r, "price-sweep").apply { isDaemon = true } }

    /** The paired computer(s): admin; everyone with the 6-digit code is a guest. */
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
                    r.result.json
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
    override fun newServer(port: Int) = GatewayServer(LocalUpstream(backend), port, admins = admins)

    override fun onServing() {
        backend.worker.start()               // price whatever is owed, then wait for scans
    }

    override fun onStopped() {
        admins.cancelCode()                  // a code shown before the stop is not good after a restart
        backend.sweep.cancel()               // first: a running sweep ends within one card…
        backend.worker.stop()                // …so the worker's join doesn't time out on it
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

    /** "N scans · X MB of photos · Y GB free" and whether it's past the warning line. */
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
    }
}
