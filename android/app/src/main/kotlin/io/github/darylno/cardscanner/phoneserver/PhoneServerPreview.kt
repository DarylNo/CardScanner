package io.github.darylno.cardscanner.phoneserver

import android.content.Context
import android.os.StatFs
import io.github.darylno.cardscanner.BuildConfig
import io.github.darylno.cardscanner.core.PrintingCandidates
import io.github.darylno.cardscanner.core.ScryfallPrintings
import io.github.darylno.cardscanner.core.server.LayoutStore
import io.github.darylno.cardscanner.f2f.OkHttpTransport
import io.github.darylno.cardscanner.gateway.AdminPairing
import io.github.darylno.cardscanner.gateway.GatewayServer
import io.github.darylno.cardscanner.gateway.LocalAddresses
import io.github.darylno.cardscanner.ident.ArtPackStore
import io.github.darylno.cardscanner.ident.TransportHttpJson
import java.io.File
import java.util.concurrent.Executors

/**
 * Stage 3 preview (Diagnostics, hidden, default OFF): the phone runs its own
 * copy of the server beside the computer's. While ON, every capture the
 * phone identifies (Compare mode) is ALSO filed into the phone's own store,
 * and the review pages are served from the phone on the LAN — through the
 * existing gateway, so the same 6-digit code, lockouts and LAN-only rule
 * apply. The computer's server stays the real path; nothing here reaches it.
 *
 * Storage: the owner's rule (2026-09-30) — at [WARN_AT] scans show the photo
 * size and free space as a warning, then keep going; nothing is pruned.
 */
class PhoneServerPreview(private val ctx: Context, private val pack: ArtPackStore) {
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
            packRows = { pack.installedManifest()?.rows ?: 0 },
            f2f = f2f,
            launch = { sweepExec.execute(it) },
            now = { System.nanoTime() / 1e9 },
            interrupt = { if (it) f2f.stop.set() else f2f.stop.clear() },
            paceS = { f2f.paceS() },
            f2fEvents = { f2f.recentEvents() },
            layouts = LayoutStore(File(root, "export_layout.json")),
        )
    }
    private val f2f by lazy { PhoneF2f(ctx, File(ctx.cacheDir, "facetoface")) }
    private val sweepExec = Executors.newSingleThreadExecutor { r -> Thread(r, "price-sweep").apply { isDaemon = true } }

    /** The paired computer(s): admin; everyone with the 6-digit code is a guest. */
    val admins = AdminPairing(File(root, "admins.json"))

    @Volatile private var server: GatewayServer? = null
    val running: Boolean get() = server != null
    val code: String? get() = server?.code
    val port: Int get() = server?.listeningPort ?: PORT

    /** Start serving on the LAN; returns an error message, or null when it's up. */
    @Synchronized
    fun start(): String? {
        if (server != null) return null
        val s = GatewayServer(LocalUpstream(backend), PORT, admins = admins)
        return try {
            s.startServing()
            server = s
            backend.worker.start()           // price whatever is owed, then wait for scans
            null
        } catch (e: Exception) {
            "could not start on port $PORT: ${e.message}"
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        admins.cancelCode()                 // a code shown before the stop is not good after a restart
        backend.worker.stop()
        backend.sweep.cancel()
    }

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

    /** File one identified capture ([result] = the pipeline's scan_candidates answer). */
    fun file(result: Map<String, Any?>, photo: ByteArray?): Map<String, Any?> =
        backend.file(result, photo)

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
