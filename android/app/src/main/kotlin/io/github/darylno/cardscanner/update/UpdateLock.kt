package io.github.darylno.cardscanner.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.ident.ArtPackStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import okhttp3.Request
import org.json.JSONObject

/**
 * The update lock (owner, 2026-09-30): "builds stop working if the repo has a
 * new release". Once the repo's latest RELEASE (GitHub `releases/latest`, which
 * never returns a prerelease such as the rolling `art-pack` tag) is newer than
 * this build AND carries the APK, the scan screen stops scanning until the
 * update is installed.
 *
 * What stops, and what never does:
 *  - New captures stop (the camera screen shows "Update required" + Download).
 *  - The scans, the review pages, export and the phone server keep working, and
 *    scans already queued are still identified — the phone holds the only copy
 *    of the owner's scans, so an update must never lock them away.
 *
 * Offline: a check that fails changes nothing. A phone that has never seen a
 * newer release keeps scanning; one that has stays locked (the answer is kept)
 * until it is updated — installing the new APK unlocks by itself, because the
 * lock is "the latest seen is newer than THIS build".
 */
class UpdateLock(
    ctx: Context,
    private val current: String,
    private val fetch: () -> String? = ::fetchLatest,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Latest(val version: String, val apkUrl: String)

    private val prefs = ctx.getSharedPreferences("update_lock", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "update-check").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The newest release seen, or null (never checked, or no release with the APK). */
    val latest: Latest?
        get() {
            val v = prefs.getString(K_VERSION, null) ?: return null
            val u = prefs.getString(K_URL, null) ?: return null
            return Latest(v, u)
        }

    /** True when a newer release than this build is out: scanning stops. */
    val locked: Boolean get() = latest?.let { isNewer(it.version, current) } == true

    val checkedAt: Long get() = prefs.getLong(K_CHECKED, 0L)

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Check in the background: at most every [MIN_INTERVAL_MS] unless [force]; listeners hear the result on the main thread. */
    fun checkAsync(force: Boolean = false) {
        if (!force && now() - checkedAt < MIN_INTERVAL_MS) return
        try {
            io.execute { if (check()) main.post { listeners.forEach { runCatching { it() } } } }
        } catch (_: Exception) { }
    }

    /** One check, on the calling thread. True when the lock state (or the latest seen) changed. */
    fun check(): Boolean {
        val body = try { fetch() } catch (_: Exception) { null } ?: return false   // offline: keep what we know
        val l = parse(body) ?: return false
        val before = latest to locked
        DebugLog.global.i("update", "latest release ${l.version} · this build $current" + if (isNewer(l.version, current)) " — UPDATE REQUIRED" else "")
        prefs.edit().putString(K_VERSION, l.version).putString(K_URL, l.apkUrl).putLong(K_CHECKED, now()).apply()
        return before != (latest to locked)
    }

    companion object {
        const val REPO = "DarylNo/CardScanner"
        const val APK_NAME = "mtg-card-scanner-android.apk"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        /** GitHub allows 60 anonymous API calls an hour per IP; the app spends at most 2. */
        const val MIN_INTERVAL_MS = 30L * 60 * 1000
        private const val K_VERSION = "version"
        private const val K_URL = "apk_url"
        private const val K_CHECKED = "checked_at"

        /** `releases/latest`'s JSON → the version + APK link, or null (draft/prerelease, or no APK attached yet). */
        fun parse(json: String): Latest? = try {
            val o = JSONObject(json)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) null
            else {
                val tag = o.optString("tag_name").takeIf { it.isNotBlank() && parseVersion(it) != null }
                val assets = o.optJSONArray("assets")
                var url: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name") == APK_NAME) url = a.optString("browser_download_url").takeIf { it.startsWith("https://") }
                }
                if (tag != null && url != null) Latest(tag, url) else null
            }
        } catch (_: Exception) { null }

        /** "v1.2.3" / "1.2.3" / "1.2.3-debug" → [1, 2, 3]; null if there are no numbers. */
        fun parseVersion(v: String): List<Int>? {
            val core = v.trim().removePrefix("v").takeWhile { it.isDigit() || it == '.' }
            val parts = core.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
            return parts.ifEmpty { null }
        }

        /** Is [latest] newer than [current]? Numeric, part by part (1.10.0 > 1.9.9); unparseable → false (never lock on garbage). */
        fun isNewer(latest: String, current: String): Boolean {
            val a = parseVersion(latest) ?: return false
            val b = parseVersion(current) ?: return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        fun fetchLatest(): String? {
            val req = Request.Builder().url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", ArtPackStore.USER_AGENT)
                .build()
            ArtPackStore.defaultClient().newCall(req).execute().use { r ->
                return if (r.isSuccessful) r.body?.string() else null
            }
        }
    }
}
