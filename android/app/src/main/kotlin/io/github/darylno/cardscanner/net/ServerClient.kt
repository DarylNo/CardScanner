package io.github.darylno.cardscanner.net

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/** A non-2xx answer from the server (the request WAS delivered — never failed over). */
class HttpException(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(200)}")

/** No server is paired yet (UI → SetupActivity). */
class NotPairedException : IOException("No server paired")

/** What [UploadQueue] needs from the network — [ServerClient] in the app, a fake in tests. */
fun interface ScanUploader {
    /**
     * POST /api/scan. Non-2xx → [HttpException]; transport failure → [IOException].
     * [uploadId] makes a re-send idempotent: the server answers a repeat with
     * the scan it already filed (a lost reply is re-sent by the queue).
     */
    fun scan(files: List<ByteArray>, replaceScanId: Long?, uploadId: String?): JSONObject
}

/**
 * The app's one door to the server. Thread-safe.
 *
 * Failover: tries lastGood first, then the configured URLs in order, and moves
 * on ONLY when the attempt failed before any request byte was written
 * (connect-phase: DNS, refused, no route, connect timeout, handshake). Once a
 * request may have reached the server, its failure is the caller's (a retried
 * POST /api/scan on another address would file the card twice). A pin
 * mismatch at one address moves on to the next (nothing was sent to it);
 * only when NO address verifies is [PinMismatchException] thrown — re-pair.
 */
class ServerClient(
    private val store: ConfigStore,
    private val timeouts: Timeouts = Timeouts(),
) : ScanUploader {

    private class Built(val pin: String, val pinned: PinnedClient, val client: OkHttpClient)

    @Volatile private var built: Built? = null
    private val lock = Any()

    /** Set in requestHeadersStart: from here on the attempt is NOT failover-able. */
    private val requestStarted = ThreadLocal<Boolean>()

    private val listener = object : EventListener() {
        override fun requestHeadersStart(call: Call) {
            requestStarted.set(true)
        }
    }

    fun config(): ServerConfig? = store.load()

    /** Save a (re-)pairing. The first URL is taken as lastGood (it just answered). */
    fun updateConfig(c: ServerConfig) {
        store.save(c)
        c.urls.firstOrNull()?.let(store::setLastGood)
        clientFor(c.pin) // rebuild now if the pin changed
    }

    /** The best base URL to open in the WebView: lastGood if still configured, else the first. */
    fun currentBase(): String? {
        val c = store.load() ?: return null
        val good = store.lastGood()
        return if (good != null && good in c.urls) good else c.urls.firstOrNull()
    }

    /** The pinned client for the current pairing (rebuilt, old pool evicted, when the pin changes). */
    fun client(): OkHttpClient = clientFor((store.load() ?: throw NotPairedException()).pin).client

    private fun clientFor(pin: String): Built {
        built?.let { if (it.pin == pin) return it }
        synchronized(lock) {
            built?.let { if (it.pin == pin) return it }
            val old = built
            val pinned = pinnedClient(pin, timeouts)
            val client = pinned.client.newBuilder().eventListener(listener).build()
            val b = Built(pin, pinned, client)
            built = b
            old?.client?.connectionPool?.evictAll()  // validated against the old pin
            return b
        }
    }

    /** Candidate order: lastGood (if configured), then the rest in configured order. */
    fun orderedBases(): List<String> {
        val c = store.load() ?: return emptyList()
        val good = store.lastGood()
        return if (good != null && good in c.urls) listOf(good) + (c.urls - good) else c.urls
    }

    /**
     * Run [block] against each base URL until one gets past the connect phase.
     * Records lastGood on success. See the class KDoc for what fails over.
     */
    fun <T> withFailover(block: (base: String) -> T): T {
        val c = store.load() ?: throw NotPairedException()
        val b = clientFor(c.pin)
        val bases = orderedBases()
        val errors = ArrayList<Pair<String, IOException>>()
        var mismatch: PinMismatchException? = null
        for (base in bases) {
            requestStarted.set(false)
            b.pinned.trustManager.mismatchOnThread.set(null)
            try {
                val result = block(base)
                if (store.lastGood() != base) store.setLastGood(base)
                return result
            } catch (e: IOException) {
                val seen = pinMismatchIn(e, b.pinned.trustManager)
                if (seen != null) {
                    // A foreign cert at a stale address (e.g. a shop router on
                    // :8443 where the home LAN IP used to be) must not strand
                    // the upload: the handshake aborted before any request byte
                    // was sent, and the next address is pinned too. Only when NO
                    // address verifies is it reported — then the UI re-pairs.
                    if (mismatch == null) mismatch = PinMismatchException(base, seen.ifEmpty { null })
                    continue
                }
                if (requestStarted.get() == true) throw e   // may have reached the server
                errors += base to e
            } finally {
                requestStarted.set(false)
                b.pinned.trustManager.mismatchOnThread.set(null)
            }
        }
        mismatch?.let { throw it }
        val last = errors.lastOrNull()?.second
        val summary = errors.joinToString("; ") { (u, e) -> "$u: ${e.message ?: e.javaClass.simpleName}" }
        throw IOException("No server address reachable ($summary)", last).apply {
            errors.dropLast(1).forEach { addSuppressed(it.second) }
        }
    }

    private fun execute(request: Request): Response = client().newCall(request).execute()

    private fun getJson(path: String): JSONObject = withFailover { base ->
        execute(Request.Builder().url(base + path).get().build()).use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, body)
            JSONObject(body)
        }
    }

    /** GET /api/version → the server's version string (for the banner). */
    fun version(): String = getJson("/api/version").optString("version", "?")

    /** GET /api/addresses → every base URL the server answers on, best first (normalized). */
    fun addresses(): List<String> {
        val arr = getJson("/api/addresses").optJSONArray("urls") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { normalizeBaseUrl(arr.optString(it)) }.distinct()
    }

    /**
     * POST /api/scan: multipart `files` (frame0.jpg…, image/jpeg) + optional
     * `replace_scan_id`. Returns the server's JSON (see phone.html submitScan).
     */
    override fun scan(files: List<ByteArray>, replaceScanId: Long?, uploadId: String?): JSONObject {
        require(files.isNotEmpty()) { "no frames" }
        val jpeg = "image/jpeg".toMediaType()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            files.forEachIndexed { i, bytes -> addFormDataPart("files", "frame$i.jpg", bytes.toRequestBody(jpeg)) }
            if (replaceScanId != null && replaceScanId > 0) addFormDataPart("replace_scan_id", replaceScanId.toString())
            if (!uploadId.isNullOrBlank()) addFormDataPart("client_upload_id", uploadId)
        }.build()
        return withFailover { base ->
            execute(Request.Builder().url("$base/api/scan").post(body).build()).use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw HttpException(r.code, text)
                JSONObject(text)
            }
        }
    }

    /**
     * Raw pass-through for the guest gateway: [pathAndQuery] starts with "/".
     * Returns the response whatever its status (streaming — the CALLER closes
     * it). [headers] are sent as given minus Host / Content-Length (OkHttp sets
     * them); the gateway strips hop-by-hop headers and its own cookie first.
     */
    fun proxy(method: String, pathAndQuery: String, headers: Headers, body: RequestBody?): Response {
        val path = if (pathAndQuery.startsWith("/")) pathAndQuery else "/$pathAndQuery"
        val h = headers.newBuilder().removeAll("Host").removeAll("Content-Length").build()
        val m = method.uppercase()
        // OkHttp refuses a body on GET/HEAD and requires one on POST/PUT/PATCH.
        val b = when {
            m == "GET" || m == "HEAD" -> null
            body == null && m in BODY_REQUIRED -> ByteArray(0).toRequestBody(null)
            else -> body
        }
        return withFailover { base ->
            execute(Request.Builder().url(base + path).headers(h).method(m, b).build())
        }
    }

    private companion object {
        val BODY_REQUIRED = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    }
}
