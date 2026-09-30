package io.github.darylno.cardscanner.ident

import io.github.darylno.cardscanner.core.ArtMatcher
import io.github.darylno.cardscanner.core.ArtPack
import io.github.darylno.cardscanner.core.IdentifyPipeline
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The artwork fingerprint pack on the phone (the card
 * database): CI publishes `art-pack.json` (manifest) + `art-pack.bin.gz` to the
 * rolling `art-pack` release (.github/workflows/art-pack.yml, writer
 * mtg_card_scanner/art_pack.py); this downloads, verifies and loads it.
 *
 *  - The manifest's `sha256`/`size` describe the file AS DOWNLOADED; the pack
 *    is only installed when both match, then [ArtPack.read] re-verifies its
 *    own trailing SHA-256 and structure.
 *  - CI uploads the pack first and the manifest second, NOT atomically: a
 *    client can read a new manifest against the old pack (or the reverse).
 *    A mismatch is therefore retried ONCE (fresh manifest + pack) before it
 *    counts as an error; the installed pack is never touched by a failure.
 *  - No release / no manifest yet (404 — the first CI build can run for
 *    hours) is [Check.NoPackYet], not an error; it is re-asked after
 *    [NO_PACK_RETRY_MS] rather than a week.
 *  - Checks run at most every [CHECK_INTERVAL_MS], on an unmetered network,
 *    unless the user asks ([check] with `force`).
 *
 * Files (in [dir]): `art-pack.bin.gz` (the verified download), `art-pack.json`
 * (its manifest), `state.json` (last check times); writes are tmp + atomic
 * rename. Plain OkHttp — never the pinned server client. Blocking: call off
 * the main thread. Thread-safe.
 */
class ArtPackStore(
    private val dir: File,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The manifest fields the app reads (art_pack.export_pack). */
    data class Manifest(
        val formatVersion: Int,
        val buildTime: Long,
        val buildDate: String,
        val rows: Int,
        val file: String,
        val size: Long,
        val sha256: String,
    ) {
        companion object {
            fun parse(text: String): Manifest {
                val o = JSONObject(text)
                return Manifest(
                    formatVersion = o.getInt("format_version"),
                    buildTime = o.optLong("build_time"),
                    buildDate = o.optString("build_date"),
                    rows = o.optInt("rows"),
                    file = o.optString("file").ifBlank { PACK_FILE },
                    size = o.getLong("size"),
                    sha256 = o.getString("sha256").lowercase(),
                )
            }
        }
    }

    /** What a [check] came to. */
    sealed class Check {
        /** Not due (interval / metered network) — nothing was fetched. */
        data class Skipped(val reason: String) : Check()
        /** The release or manifest isn't there yet (first build still running). */
        data object NoPackYet : Check()
        data class UpToDate(val manifest: Manifest) : Check()
        data class Installed(val manifest: Manifest) : Check()
        data class Failed(val message: String) : Check()
    }

    private val lock = Any()
    @Volatile private var pack: ArtPack? = null
    @Volatile private var matcher: ArtMatcher? = null
    @Volatile var lastCheck: Check? = null
        private set

    init { dir.mkdirs() }

    private val packFile get() = File(dir, PACK_FILE)
    private val manifestFile get() = File(dir, MANIFEST_FILE)
    private val stateFile get() = File(dir, STATE_FILE)

    /** The installed manifest, if a pack is installed. */
    fun installedManifest(): Manifest? = try {
        if (manifestFile.isFile && packFile.isFile) Manifest.parse(manifestFile.readText()) else null
    } catch (_: Exception) {
        null
    }

    /**
     * The loaded matcher, reading the installed pack on first use (null = no
     * pack installed, or it failed to parse — then it is deleted so the next
     * check downloads a fresh one).
     */
    fun matcher(): ArtMatcher? {
        matcher?.let { return it }
        synchronized(lock) {
            matcher?.let { return it }
            if (!packFile.isFile) return null
            return try {
                val p = ArtPack.read(packFile)
                pack = p
                IdentifyPipeline.matcherFor(p).also { matcher = it }
            } catch (e: Exception) {
                packFile.delete(); manifestFile.delete()
                null
            }
        }
    }

    /** The loaded pack (after [matcher]), for status lines. */
    fun loadedPack(): ArtPack? = pack

    /** Epoch ms of the last completed manifest fetch (any answer), or null. */
    fun lastCheckedAt(): Long? = readState().optLong(K_CHECKED, 0L).takeIf { it > 0 }

    /** True when a non-forced [check] would fetch now. */
    fun due(unmetered: Boolean): Boolean {
        if (!unmetered) return false
        val s = readState()
        val last = s.optLong(K_CHECKED, 0L)
        val interval = if (installedManifest() == null && s.optBoolean(K_NO_PACK)) NO_PACK_RETRY_MS
        else if (installedManifest() == null) 0L else CHECK_INTERVAL_MS
        return now() - last >= interval
    }

    /**
     * Fetch the manifest and, when it names a pack we don't have, download,
     * verify and install it. [force] = the user asked (ignores interval and
     * network kind); otherwise [unmetered] must be true and the check due.
     */
    fun check(force: Boolean, unmetered: Boolean): Check = synchronized(lock) {
        if (!force && !unmetered) return record(Check.Skipped("not on Wi-Fi"))
        if (!force && !due(unmetered = true)) return record(Check.Skipped("checked recently"))
        val result = try {
            attempt(retryLeft = 1)
        } catch (e: IOException) {
            Check.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (result !is Check.Failed) {
            writeState(JSONObject().put(K_CHECKED, now()).put(K_NO_PACK, result is Check.NoPackYet))
        }
        record(result)
    }

    private fun record(c: Check): Check { lastCheck = c; return c }

    private fun attempt(retryLeft: Int): Check {
        val manifestText = get("$baseUrl/$MANIFEST_FILE") ?: return Check.NoPackYet
        val manifest = try {
            Manifest.parse(manifestText)
        } catch (e: Exception) {
            return Check.Failed("unreadable manifest: ${e.message}")
        }
        if (manifest.formatVersion != ArtPack.FORMAT_VERSION) {
            return Check.Failed("pack format ${manifest.formatVersion} needs a newer app (this one reads ${ArtPack.FORMAT_VERSION})")
        }
        val installed = installedManifest()
        if (installed != null && installed.sha256 == manifest.sha256 && packFile.isFile) {
            // Keep the manifest fresh (build date etc. may be re-published).
            writeAtomic(manifestFile, manifestText.toByteArray())
            return Check.UpToDate(manifest)
        }
        val tmp = File(dir, ".download.tmp")
        try {
            val (size, sha) = download("$baseUrl/${manifest.file}", tmp)
                ?: return if (retryLeft > 0) attempt(retryLeft - 1) else Check.NoPackYet
            if (size != manifest.size || sha != manifest.sha256) {
                // Pack and manifest are replaced one after the other: re-read both once.
                if (retryLeft > 0) return attempt(retryLeft - 1)
                return Check.Failed("pack doesn't match its manifest (sha256 $sha, expected ${manifest.sha256}) — try again later")
            }
            val loaded = try {
                ArtPack.read(tmp)
            } catch (e: Exception) {
                return Check.Failed("downloaded pack is unreadable: ${e.message}")
            }
            Files.move(tmp.toPath(), packFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            writeAtomic(manifestFile, manifestText.toByteArray())
            pack = loaded
            matcher = IdentifyPipeline.matcherFor(loaded)
            return Check.Installed(manifest)
        } finally {
            tmp.delete()
        }
    }

    /** Body of a GET, or null on 404 (not published yet). Other failures throw. */
    private fun get(url: String): String? {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            return r.body?.string() ?: ""
        }
    }

    /** Stream [url] to [to], returning (bytes, sha256 hex) — null on 404. */
    private fun download(url: String, to: File): Pair<Long, String>? {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            val md = MessageDigest.getInstance("SHA-256")
            var n = 0L
            val body = r.body ?: throw IOException("empty body for $url")
            body.byteStream().use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val k = input.read(buf)
                        if (k < 0) break
                        md.update(buf, 0, k)
                        out.write(buf, 0, k)
                        n += k
                        if (n > MAX_PACK_BYTES) throw IOException("pack larger than ${MAX_PACK_BYTES / (1 shl 20)} MB")
                    }
                    out.fd.sync()
                }
            }
            return n to md.digest().joinToString("") { String.format("%02x", it) }
        }
    }

    private fun readState(): JSONObject = try {
        if (stateFile.isFile) JSONObject(stateFile.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    private fun writeState(o: JSONObject) = writeAtomic(stateFile, o.toString().toByteArray())

    private fun writeAtomic(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val DEFAULT_BASE = "https://github.com/DarylNo/CardScanner/releases/download/art-pack"
        const val PACK_FILE = "art-pack.bin.gz"
        const val MANIFEST_FILE = "art-pack.json"
        private const val STATE_FILE = "state.json"
        private const val K_CHECKED = "checked_at"
        private const val K_NO_PACK = "no_pack"
        const val CHECK_INTERVAL_MS = 7L * 24 * 3600 * 1000
        const val NO_PACK_RETRY_MS = 24L * 3600 * 1000
        const val MAX_PACK_BYTES = 256L shl 20
        const val USER_AGENT = "CardScanner-Android art-pack (+https://github.com/DarylNo/CardScanner)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)          // GitHub release assets redirect to object storage
            .followSslRedirects(true)
            .build()
    }
}
