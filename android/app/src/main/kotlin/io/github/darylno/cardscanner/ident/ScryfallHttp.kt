package io.github.darylno.cardscanner.ident

import io.github.darylno.cardscanner.core.HttpJson
import io.github.darylno.cardscanner.core.HttpStatusException
import io.github.darylno.cardscanner.core.ImageSource
import io.github.darylno.cardscanner.f2f.F2fTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The server's Scryfall identity (scryfall.USER_AGENT), sent verbatim. */
object ScryfallHeaders {
    const val USER_AGENT = "MTGCardScanner/1.0 (contact: your-email@example.com)"
    /** visual_match._USER_AGENT — the image fetcher's. */
    const val IMAGE_USER_AGENT = "MTGCardScanner/1.0 visual-match (contact: your-email@example.com)"
    val JSON: Map<String, String> = linkedMapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")
    /** scryfall.py: `timeout=10`. */
    const val TIMEOUT_MS = 10_000L
}

/**
 * [HttpJson] over an [F2fTransport] — Cronet in the app (Chrome's stack, the
 * transport Stage 1c measured), OkHttp as the fallback. The throttle is NOT
 * here: [io.github.darylno.cardscanner.core.ScryfallPrintings] spaces its own
 * requests exactly as scryfall.py does (≥110 ms, measured from the previous
 * response). A non-2xx becomes [HttpStatusException] (404 → "unknown name"
 * upstream).
 */
class TransportHttpJson(private val transport: F2fTransport) : HttpJson {
    override fun get(url: String): String {
        val r = transport.get(url, ScryfallHeaders.JSON, ScryfallHeaders.TIMEOUT_MS)
        if (r.status !in 200..299) {
            // Scryfall's errors are JSON objects; the transport doesn't surface content-type.
            val ct = if (r.body.trimStart().startsWith("{")) "application/json" else ""
            throw HttpStatusException(r.status, r.body, ct, url)
        }
        return r.body
    }

    fun cancel() = transport.cancel()
    fun close() = transport.close()
}

/**
 * The ranker's [ImageSource] on the phone: Scryfall `small` images through a
 * small on-disk LRU cache ([maxFiles] / [maxBytes], least-recently-used by
 * mtime), fetched with plain OkHttp and spaced like visual_match
 * (`_REQUEST_DELAY` 0.12 s from the previous response, 8 s timeout).
 *
 * Decoded with OpenCV's `Imgcodecs.imdecode` — NOT BitmapFactory: Stage 2B
 * measured OpenCV's libjpeg-turbo decode to Pillow's exact pixels, and the
 * region hashes only agree with the server's on identical pixels.
 */
class CachedImageSource(
    private val dir: File,
    private val client: OkHttpClient = defaultClient(),
    private val maxFiles: Int = 4000,
    private val maxBytes: Long = 80L shl 20,
    private val nanoClock: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : ImageSource {
    private var lastRequest: Long? = null
    private val lock = Any()
    @Volatile var hits = 0; private set
    @Volatile var fetches = 0; private set

    init { dir.mkdirs() }

    override fun load(scryfallId: String, url: String): Mat {
        require(scryfallId.isNotEmpty() && scryfallId.none { it == '/' || it == '\\' }) { "bad id $scryfallId" }
        val f = File(dir, "$scryfallId.jpg")
        val bytes = if (f.isFile) {
            hits++
            f.setLastModified(System.currentTimeMillis())
            f.readBytes()
        } else {
            fetch(url).also { store(f, it) }
        }
        return decode(bytes) ?: run {
            f.delete()                              // a corrupt cache entry must not stick
            throw IOException("could not decode image for $scryfallId")
        }
    }

    private fun fetch(url: String): ByteArray = synchronized(lock) {
        lastRequest?.let { last ->
            val wait = DELAY_NANOS - (nanoClock() - last)
            if (wait > 0) sleeper((wait + 999_999) / 1_000_000)
        }
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", ScryfallHeaders.IMAGE_USER_AGENT).build())
                .execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
                    fetches++
                    return r.body?.bytes() ?: throw IOException("empty image body")
                }
        } finally {
            lastRequest = nanoClock()
        }
    }

    private fun store(f: File, bytes: ByteArray) {
        try {
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) tmp.delete()
            trim()
        } catch (_: IOException) {
            // cache is best-effort
        }
    }

    /** Evict least-recently-used files beyond the caps. */
    fun trim() {
        val files = dir.listFiles { x -> x.isFile && x.name.endsWith(".jpg") }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (files.size <= maxFiles && total <= maxBytes) return
        files.sortBy { it.lastModified() }
        var count = files.size
        for (x in files) {
            if (count <= maxFiles && total <= maxBytes) break
            total -= x.length()
            if (x.delete()) count--
        }
    }

    companion object {
        const val DELAY_NANOS = 120_000_000L       // visual_match._REQUEST_DELAY
        const val TIMEOUT_S = 8L                   // visual_match._IMAGE_FETCH_TIMEOUT

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        /** `Imgcodecs.imdecode(bytes, IMREAD_COLOR)` — 8UC3 BGR, or null when undecodable. */
        fun decode(bytes: ByteArray): Mat? {
            val buf = MatOfByte(*bytes)
            try {
                val m = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
                if (m.empty()) { m.release(); return null }
                return m
            } finally {
                buf.release()
            }
        }
    }
}
