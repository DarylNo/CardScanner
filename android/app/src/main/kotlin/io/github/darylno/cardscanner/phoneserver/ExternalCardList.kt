package io.github.darylno.cardscanner.phoneserver

import android.content.Context
import io.github.darylno.cardscanner.core.CardList
import io.github.darylno.cardscanner.core.CardListSource
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.ui.AppSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The external card list on the phone (owner, 2026-10-12): Settings → External card
 * list holds the URL (AppSettings, never the repo); the list is fetched at app start
 * and on "Refresh now", one plain GET of the URL exactly as entered, on its own
 * low-priority thread — scanning never waits for it. The last good list is cached in
 * filesDir; offline / an HTTP error / bad JSON keep the cache and record the error
 * (core [CardListSource], the reference's port). The review pages get `list_tags`
 * on every scan read (PhoneApi), evaluated against [current] at that moment.
 *
 * The URL may carry a key in its query string, so it is NEVER logged: the log says
 * "card list: 1,234 cards" or the error, nothing more.
 */
class ExternalCardList(
    ctx: Context,
    private val settings: AppSettings,
    fetch: (String) -> Pair<Int, String> = ::httpGet,
) {
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "card-list").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    // Built empty, then pointed at the saved URL on the worker thread: reading and
    // parsing a big cached list must not hold up app start.
    private val source = CardListSource("", File(ctx.filesDir, "card_list_cache.json"), fetch) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    }

    /** The list to tag scans with right now (null = the feature is off). */
    fun current(): CardList.Parsed? = source.current()

    fun status(): Map<String, Any?> = source.status()

    /** "1,234 cards, updated …", "Off — no URL set", or the last error. */
    fun summary(): String = CardList.summary(source.status())

    /** App start: load the cache for the saved URL, then fetch once. */
    fun start() = exec.execute {
        source.setUrl(settings.cardListUrl)
        refreshNow()
    }

    /** A new URL from Settings (empty = off): saved, the cache of another URL dropped, fetched. */
    fun setUrl(url: String, done: () -> Unit = {}) {
        settings.cardListUrl = url.trim()
        DebugLog.global.i("list", if (url.isBlank()) "external card list turned off" else "external card list URL changed")
        exec.execute {
            source.setUrl(settings.cardListUrl)
            refreshNow()
            done()
        }
    }

    /** Settings → Refresh now. [done] runs on the worker thread once the attempt is over. */
    fun refresh(done: () -> Unit = {}) = exec.execute { refreshNow(); done() }

    private fun refreshNow() {
        if (source.current() == null) return
        val ok = runCatching { source.refresh() }.getOrDefault(false)
        val st = source.status()
        if (ok) DebugLog.global.i("list", "card list: ${st["count"]} cards (generated ${st["generated_at"] ?: "?"})")
        else DebugLog.global.w("list", "card list refresh failed: ${st["error"]} — keeping ${st["count"]} cached")
    }

    companion object {
        private const val MAX_BYTES = 32 shl 20

        /** One GET of [url] exactly as entered → (status, body). Any failure → IOException ("unreachable"). */
        fun httpGet(url: String): Pair<Int, String> {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 20_000
                    c.readTimeout = 30_000
                    c.instanceFollowRedirects = true
                    c.setRequestProperty("Accept", "application/json")
                    val code = c.responseCode
                    if (code != 200) return code to ""
                    val out = ByteArrayOutputStream()
                    c.inputStream.use { ins ->
                        val buf = ByteArray(64 shl 10)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_BYTES) throw IOException("list too large")
                        }
                    }
                    return code to out.toString(Charsets.UTF_8.name())
                } finally { c.disconnect() }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {          // a malformed URL, a refused cleartext address, …
                throw IOException(e.javaClass.simpleName)
            }
        }
    }
}
