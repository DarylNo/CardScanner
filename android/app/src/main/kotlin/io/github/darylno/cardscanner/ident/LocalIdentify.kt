package io.github.darylno.cardscanner.ident

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.darylno.cardscanner.f2f.CronetTransport
import io.github.darylno.cardscanner.f2f.F2fTransport
import io.github.darylno.cardscanner.f2f.OkHttpTransport
import io.github.darylno.cardscanner.ocr.MlKitOcrEngine
import java.io.File
import java.util.concurrent.Executors

/**
 * Stage 4: the phone identifies every scan itself. Owns the fingerprint pack
 * ([store], published by CI to the `art-pack` release — the phone's card
 * database) and the one [PhoneIdentifier] ([identifier]: the server's
 * pipeline, ported and parity-tested).
 *
 * The pack is needed before any card can be identified, so a phone without
 * one fetches it at app start on ANY network; after that it re-checks weekly
 * on Wi-Fi (the pack is rebuilt weekly). Scans taken before the pack lands
 * wait in the upload queue and are identified once it does.
 */
class LocalIdentify(private val ctx: Context) {
    val store = ArtPackStore(File(ctx.filesDir, "artpack"))

    @Volatile var lastCheck: ArtPackStore.Check? = null
        private set
    @Volatile var checking = false
        private set

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "artpack").apply { priority = Thread.MIN_PRIORITY } }
    @Volatile private var ident: PhoneIdentifier? = null

    val hasPack: Boolean get() = store.installedManifest() != null

    /** The identifier (built on first use: Cronet for Scryfall, ML Kit OCR). */
    @Synchronized
    fun identifier(): PhoneIdentifier = ident ?: run {
        val t: F2fTransport = try {
            CronetTransport.create(ctx.applicationContext)
        } catch (e: Throwable) {
            OkHttpTransport()                    // no packaged Cronet (e.g. tests): plain OkHttp
        }
        PhoneIdentifier(
            matcher = { store.matcher() },
            http = TransportHttpJson(t),
            images = CachedImageSource(File(ctx.cacheDir, "scryfall-small")),
            ocr = MlKitOcrEngine(),
            onCancel = { t.cancel() },
        ).also { ident = it }
    }

    /** App start: fetch the pack now if the phone has none, else the weekly Wi-Fi check. */
    fun onAppStart(done: (ArtPackStore.Check) -> Unit = {}) = checkAsync(force = !hasPack, done)

    @Volatile private var lastForcedAt = 0L

    /**
     * A queued scan needs the pack and there is none: fetch it again — at most once a
     * minute (the queue polls every 30 s), so a first download that failed offline is
     * retried once the phone is back online, not only at the next app start.
     */
    fun ensurePack(now: Long = System.currentTimeMillis()) {
        if (!ensureDue(hasPack, checking, now, lastForcedAt)) return
        lastForcedAt = now
        checkAsync(force = true)
    }

    /** The scan screen opened: the missing-pack retry, or the (due-gated) weekly check. */
    fun onScreen() = if (hasPack) checkAsync(force = false) else ensurePack()

    /** Check for a new pack in the background ([force] = now, any network). */
    fun checkAsync(force: Boolean, done: (ArtPackStore.Check) -> Unit = {}) {
        try {
            io.execute {
                checking = true
                val c = try { store.check(force, unmetered(ctx)) } finally { checking = false }
                lastCheck = c
                runCatching { store.matcher() }            // load it off the main thread
                runCatching { done(c) }
            }
        } catch (_: Exception) { }
    }

    companion object {
        const val ENSURE_EVERY_MS = 60_000L

        /** Fetch the missing pack now? Not while one is installed or a check runs, and once a minute. */
        fun ensureDue(hasPack: Boolean, checking: Boolean, now: Long, lastForcedAt: Long): Boolean =
            !hasPack && !checking && now - lastForcedAt >= ENSURE_EVERY_MS

        fun unmetered(ctx: Context): Boolean = try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps != null && (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        } catch (_: Exception) {
            false
        }
    }
}
