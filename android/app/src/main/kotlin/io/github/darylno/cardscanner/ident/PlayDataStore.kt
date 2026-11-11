package io.github.darylno.cardscanner.ident

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.PlayData
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The play data on the phone (1.1.20): the share of recent decks running each card,
 * format by format. CI publishes `play-data.json` weekly to the rolling `play-data`
 * prerelease (.github/workflows/play-data.yml, writer mtg_card_scanner/play_data.py);
 * this downloads it, checks it with [PlayData.parse] (the reference's own refusals)
 * and keeps the last good one. The review pages get `play` on every scan read
 * (PhoneApi, from [current]) — a dictionary lookup, nothing per scan is fetched.
 *
 *  - [check]: not forced and on a metered network → Skipped("not on Wi-Fi"); not due →
 *    Skipped("checked recently"). Due: data installed → weekly; none and the last
 *    answer was "no data yet" (404 — the first CI build) → a day; none, never
 *    checked → now. A 404 is [Check.NoDataYet] (recorded); a file [PlayData.parse]
 *    refuses is [Check.Failed] with its reason, the installed file kept and NO check
 *    recorded; the same as_of AND built_at as the installed file is [Check.UpToDate]
 *    (recorded, nothing rewritten); anything else good is written tmp + atomic
 *    rename → [Check.Installed]. Any other HTTP status → Failed("HTTP n"), no answer
 *    at all → Failed("unreachable"); neither recorded, so the next non-forced check
 *    asks again.
 *  - A file that CAME and could not be used (refused, over [MAX_BYTES], not saved)
 *    is noted (`refused_at`): non-forced checks wait [REFUSED_RETRY_MS] after the
 *    last one (review of 1.1.20: it stayed due, so every scan-screen start on Wi-Fi
 *    downloaded the whole file again — in Tap to scan once per scan; a version-2
 *    file before the app updates, a bad CI build or a captive portal's page);
 *    forced checks ask at once; any recorded answer forgets it; a clock set back
 *    before it forgets it too.
 *  - [current]: parsed once and kept in memory; replaced by an install; a corrupt
 *    installed file is deleted (null). Thread-safe — the phone server's threads read
 *    it per request. It may parse (~1 MB) on first use: never call it on the main
 *    thread; the UI reads [loaded] (never touches the disk).
 *
 * Files (in [dir]): `play-data.json`, `state.json` ({checked_at, no_data[, refused_at]}). Plain
 * OkHttp (it follows the release asset's redirect to object storage). Blocking: call
 * off the main thread.
 */
class PlayDataStore(
    private val dir: File,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What a [check] came to. */
    sealed class Check {
        /** Not due (interval / metered network) — nothing was fetched. */
        data class Skipped(val reason: String) : Check() { override fun toString() = "skipped — $reason" }
        /** No release / file yet (the first CI build can run for a while). */
        data object NoDataYet : Check() { override fun toString() = "no data yet (no play-data release)" }
        data class UpToDate(val asOf: String, val builtAt: String?) : Check() {
            override fun toString() = "up to date — as of $asOf"
        }
        data class Installed(val asOf: String, val builtAt: String?) : Check() {
            override fun toString() = "installed — as of $asOf"
        }
        data class Failed(val message: String) : Check() { override fun toString() = "failed — $message" }
    }

    /** Serialises [check]s (network included) — never held by [current]. */
    private val checkLock = Any()
    /** Guards the in-memory pack and the file swap: held only for a parse or a rename. */
    private val loadLock = Any()
    @Volatile private var pack: PlayData.Pack? = null
    @Volatile var lastCheck: Check? = null
        private set

    init { dir.mkdirs() }

    private val dataFile get() = File(dir, PlayData.PACK_FILE)
    private val stateFile get() = File(dir, STATE_FILE)

    /** A file is installed (a stat — it may still be unparsed or corrupt). */
    val hasFile: Boolean get() = dataFile.isFile

    /** The pack in memory, if [current] has loaded one — never reads the disk (the UI's view). */
    fun loaded(): PlayData.Pack? = pack

    /**
     * The installed play data, parsed on first use and kept (null = none, or the
     * installed file was corrupt — then it is deleted so the next check fetches it).
     */
    fun current(): PlayData.Pack? {
        pack?.let { return it }
        synchronized(loadLock) {
            pack?.let { return it }
            if (!dataFile.isFile) return null
            return try {
                PlayData.parse(dataFile.readText(Charsets.UTF_8)).also { pack = it }
            } catch (e: PlayData.PlayDataException) {
                dataFile.delete(); null
            } catch (e: IOException) {
                null
            } catch (e: StackOverflowError) {               // absurdly nested JSON: not a file of ours
                dataFile.delete(); null
            }
        }
    }

    /** Epoch ms of the last recorded check (any answer that counts), or null. */
    fun lastCheckedAt(): Long? = (readState()["checked_at"] as? Long)?.takeIf { it > 0 }

    /** True when a non-forced [check] would fetch now. */
    fun due(unmetered: Boolean): Boolean {
        if (!unmetered) return false
        val s = readState()
        val last = s["checked_at"] as? Long ?: 0L
        val interval = when {
            dataFile.isFile -> CHECK_INTERVAL_MS
            s["no_data"] == true -> NO_DATA_RETRY_MS
            else -> 0L
        }
        if (refusedRecently(s)) return false
        return now() - last >= interval
    }

    /** A file came less than [REFUSED_RETRY_MS] ago and could not be used (a clock set back before it forgets it). */
    private fun refusedRecently(s: Map<String, Any?>): Boolean {
        val at = s["refused_at"] as? Long ?: return false
        return (now() - at) in 0L until REFUSED_RETRY_MS
    }

    /**
     * Fetch the published file and install it when it is new. [force] = the user (or a
     * phone with no data at app start) asked: ignores the interval and network kind.
     */
    fun check(force: Boolean, unmetered: Boolean): Check = synchronized(checkLock) {
        if (!force && !unmetered) return record(Check.Skipped("not on Wi-Fi"))
        if (!force && !due(unmetered = true)) return record(Check.Skipped("checked recently"))
        val result = attempt()
        when (result) {
            is Check.NoDataYet -> writeState(noData = true)
            is Check.UpToDate, is Check.Installed -> writeState(noData = false)
            else -> {}                                       // a failure records no check (a refused file: attempt noted it)
        }
        record(result)
    }

    private fun record(c: Check): Check { lastCheck = c; return c }

    private fun attempt(): Check {
        val text = try {
            client.newCall(Request.Builder().url("$baseUrl/${PlayData.PACK_FILE}")
                .header("User-Agent", USER_AGENT).header("Accept", "application/json").build()).execute().use { r ->
                if (r.code == 404) return Check.NoDataYet
                if (r.code != 200) return Check.Failed("HTTP ${r.code}")
                val body = r.body ?: return Check.Failed("empty answer")
                readCapped(body.byteStream()) ?: return refused("file larger than ${MAX_BYTES shr 20} MB")
            }
        } catch (e: IOException) {
            return Check.Failed("unreachable")
        }
        val fresh = try {
            PlayData.parse(text)
        } catch (e: PlayData.PlayDataException) {
            return refused(e.message ?: "malformed")
        } catch (e: StackOverflowError) {
            return refused("not JSON")
        }
        val installed = current()
        if (installed != null && installed.asOf == fresh.asOf && installed.builtAt == fresh.builtAt) {
            return Check.UpToDate(fresh.asOf, fresh.builtAt)
        }
        val tmp = File(dir, ".download.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            synchronized(loadLock) {
                Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                pack = fresh
            }
        } catch (e: IOException) {
            return refused("could not save it: ${e.javaClass.simpleName}")
        } finally {
            tmp.delete()
        }
        return Check.Installed(fresh.asOf, fresh.builtAt)
    }

    /** The whole body as UTF-8, or null past [MAX_BYTES]. */
    private fun readCapped(input: java.io.InputStream): String? {
        val out = ByteArrayOutputStream()
        input.use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val k = ins.read(buf)
                if (k < 0) break
                out.write(buf, 0, k)
                if (out.size() > MAX_BYTES) return null
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun readState(): Map<String, Any?> = try {
        if (stateFile.isFile) (MiniJson.parse(stateFile.readText()) as? Map<*, *>)
            ?.entries?.associate { (k, v) -> k as String to v } ?: emptyMap()
        else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    /** A recorded answer: a fresh state (it forgets any refusal). */
    private fun writeState(noData: Boolean) = putState(linkedMapOf("checked_at" to now(), "no_data" to noData))

    /** The file came and could not be used: note it (the recorded check, if any, stays as it was). */
    private fun refused(message: String): Check.Failed {
        putState(LinkedHashMap(readState()).apply { put("refused_at", now()) })
        return Check.Failed(message)
    }

    private fun putState(state: Map<String, Any?>) {
        try {
            val tmp = File(dir, "$STATE_FILE.tmp")
            tmp.writeText(MiniJson.stringify(state))
            Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) { /* not recorded: the next check comes sooner, nothing worse */ }
    }

    companion object {
        const val DEFAULT_BASE = "https://github.com/DarylNo/CardScanner/releases/download/${PlayData.PACK_TAG}"
        private const val STATE_FILE = "state.json"
        const val CHECK_INTERVAL_MS = 7L * 24 * 3600 * 1000
        const val NO_DATA_RETRY_MS = 24L * 3600 * 1000
        /** A published file this build could not use is asked again (non-forced) this long after: a day, like no data. */
        const val REFUSED_RETRY_MS = 24L * 3600 * 1000
        /** The file is ~1.1 MB today. */
        const val MAX_BYTES = 16 shl 20
        const val USER_AGENT = "CardScanner-Android play-data (+https://github.com/DarylNo/CardScanner)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)          // GitHub release assets redirect to object storage
            .followSslRedirects(true)
            .build()

        private fun n(v: Long) = String.format(Locale.US, "%,d", v)

        /** "Standard 6,726 · Pioneer 1,437 · …" — the log's summary of an install. */
        fun logLine(p: PlayData.Pack): String =
            "play data: as of ${p.asOf} · " + p.formats.entries.joinToString(" · ") { (k, f) -> "${PlayData.label(k)} ${n(f.decks)}" } + " decks"

        /**
         * Diagnostics: "play data as of 2026-11-10 · standard 6,726 · pioneer 1,437 (MTGO only) · … decks"
         * — a format that weighs only one source names it.
         */
        fun diagnosticsLine(p: PlayData.Pack): String =
            "play data as of ${p.asOf} · " + p.formats.entries.joinToString(" · ") { (k, f) ->
                "$k ${n(f.decks)}" + onlyOne(f).orEmpty()
            } + " decks"

        /** " (MTGO only)" / " (paper only)" when exactly one source was weighed, else null. */
        private fun onlyOne(f: PlayData.Format): String? {
            val weighed = f.sources.filterValues { it.weighted }.keys
            if (weighed.size != 1) return null
            val k = weighed.first()
            return " (${if (k == "mtgo") "MTGO" else k} only)"
        }
    }
}
