package io.github.darylno.cardscanner.ident

import android.content.Context
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.PlayData
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps the play data ([store], `play-data.json` from the `play-data` release) on the
 * phone, the way [LocalIdentify] keeps the card database: fetched at app start on ANY
 * network when the phone has none, then weekly on Wi-Fi (when the scan screen opens),
 * and by Settings → "Check for a newer card database". Nothing waits for it: until it
 * lands the scans simply carry no `play`.
 *
 * Everything runs on its own low-priority thread ("play-data"): the first [PlayDataStore.current]
 * parses ~1 MB, which must never happen on the main thread.
 */
class PlayDataSync(
    private val ctx: Context,
    /** False in unit tests (Robolectric): every check is Skipped, nothing is downloaded. */
    private val fetchAllowed: Boolean = true,
    val store: PlayDataStore = PlayDataStore(File(ctx.filesDir, "playdata")),
) {
    @Volatile var checking = false
        private set
    /** The last check's answer (Skipped / NoDataYet / UpToDate / Installed / Failed), for Settings + Diagnostics. */
    @Volatile var lastCheck: PlayDataStore.Check? = null
        private set

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "play-data").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /** The play data now, for the phone server's scan reads (may parse on first use — a server thread). */
    fun current(): PlayData.Pack? = store.current()

    /** The pack in memory (never the disk) — for screens on the main thread. */
    fun loaded(): PlayData.Pack? = store.loaded()

    /** App start: load the installed file, then fetch now if there is none, else the weekly Wi-Fi check. */
    fun onAppStart(done: (PlayDataStore.Check) -> Unit = {}) = run(force = null, done)

    /** The scan screen opened: the weekly (due-gated, Wi-Fi) check. */
    fun onScreen() = run(force = false)

    /** Settings → Check for a newer card database: now, any network. */
    fun checkNow(done: (PlayDataStore.Check) -> Unit = {}) = run(force = true, done)

    /** [force] null = "now if the phone has no play data, else the weekly check" (decided off the main thread). */
    private fun run(force: Boolean?, done: (PlayDataStore.Check) -> Unit = {}) {
        if (!fetchAllowed) {
            PlayDataStore.Check.Skipped("unit tests never download the play data").also { lastCheck = it; runCatching { done(it) } }
            return
        }
        try {
            io.execute {
                checking = true
                val (c, now) = try {
                    val f = force ?: (runCatching { store.current() }.getOrNull() == null)
                    store.check(f, LocalIdentify.unmetered(ctx)) to f
                } catch (e: Exception) {
                    PlayDataStore.Check.Failed(e.javaClass.simpleName) to (force ?: false)
                } finally { checking = false }
                lastCheck = c
                DebugLog.global.i("play", "play data check (${if (now) "now" else "weekly"}): $c")
                if (c is PlayDataStore.Check.Installed) store.loaded()?.let { DebugLog.global.i("play", PlayDataStore.logLine(it)) }
                runCatching { store.current() }                 // load it off the main thread
                runCatching { done(c) }
            }
        } catch (_: Exception) { }
    }
}
