package io.github.darylno.cardscanner.gateway

import fi.iki.elonen.NanoHTTPD
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The guest gateway: serves the home server's review pages to people on the phone's local
 * network (shop Wi-Fi or the phone's own hotspot) who have no Tailscale.
 *
 * **Security model — FULL ACCESS, deliberately.** The owner decided that guests may do
 * everything the owner can (review, pick, delete, export, price sweeps, even the server's
 * update/setup endpoints): nothing is filtered by path or method. The ONLY gate is the
 * 6-digit [JoinCode] shown on the phone:
 *  - `GET /join?code=NNNNNN` with the right code sets `cs_guest=<128-bit random hex>`
 *    (HttpOnly, SameSite=Lax, Path=/) and 302s to `/phone?panel=1` (mobile UA) or `/`.
 *  - every other request without a live session cookie gets a small "enter the code" page.
 *  - 8 wrong codes from one IP within 5 minutes lock that IP out (429) for 5 minutes.
 *  - [stopSharing] rotates the code and forgets every session.
 * Risks the owner accepted: anyone who reads the code (shoulder-surfing, a photo of the
 * QR) gets full control until sharing stops; the LAN leg is plain HTTP, so on an open
 * shop Wi-Fi the cookie and all traffic can be sniffed by other clients of that network.
 * SameSite=Lax keeps other websites from riding the cookie on POST/DELETE, and a DNS-
 * rebinding page never has the cookie (it is scoped to the phone's IP host).
 *
 * With a session, EVERYTHING is proxied through [upstream]: any method, path and raw query
 * intact, request body relayed, status + end-to-end headers passed through (hop-by-hop
 * headers dropped both ways, our cookie dropped on the way up), response body streamed.
 * Upstream failure → 502 page.
 *
 * @param hostname interface to bind; null = all interfaces (0.0.0.0).
 * @param port 8080 by default; 0 picks a free port (see [getListeningPort]).
 */
class GatewayServer(
    private val upstream: Upstream,
    port: Int = DEFAULT_PORT,
    hostname: String? = null,
    val joinCode: JoinCode = JoinCode(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : NanoHTTPD(hostname, port) {

    private val sessions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failures = HashMap<String, Attempts>()   // guarded by itself

    private class Attempts {
        val times = ArrayDeque<Long>()
        var lockedUntil = 0L
    }

    /** The code guests must enter right now. */
    val code: String get() = joinCode.current

    /** Number of guests who have joined since sharing (re)started. */
    val sessionCount: Int get() = sessions.size

    /** Binds and starts serving on daemon threads. Throws IOException if the port is taken. */
    @Throws(IOException::class)
    fun startServing() = start(SOCKET_READ_TIMEOUT, true)

    /**
     * Ends every guest session and rotates the join code (a new share needs the new code).
     * Returns the new code. Does not stop the listener — call [stop] for that.
     */
    fun stopSharing(): String {
        sessions.clear()
        synchronized(failures) { failures.clear() }
        return joinCode.rotate()
    }

    override fun stop() {
        stopSharing()
        super.stop()
    }

    // NanoHTTPD would gzip text/JSON a second time on top of whatever upstream sent.
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            route(session)
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "gateway request failed", e)
            html(500, "Gateway error", "<p>Something went wrong in the scanner phone's gateway.</p>")
        }
        return response
    }

    private fun route(session: IHTTPSession): Response {
        val method = session.method ?: return closing(session, html(400, "Bad request", "<p>Unsupported method.</p>"))
        val path = session.uri ?: "/"
        if (path == JOIN_PATH) return closing(session, join(session))
        if (!hasSession(session.headers["cookie"])) return closing(session, codePage(session, null))
        return proxy(session, method, path)
    }

    // ---- join / lockout --------------------------------------------------------------

    private fun join(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress ?: "?"
        val now = clock()
        lockedFor(ip, now)?.let { return tooMany(it) }
        val given = session.parameters["code"]?.firstOrNull()
        if (given.isNullOrBlank()) return codePage(session, null)
        if (!joinCode.matches(given)) {
            recordFailure(ip, now)?.let { return tooMany(it) }
            return codePage(session, "That code is not right — check the scanner phone and try again.", status = 403)
        }
        synchronized(failures) { failures.remove(ip) }
        val token = newToken()
        sessions += token
        val ua = session.headers["user-agent"].orEmpty()
        val target = if (ua.contains("Mobi") || ua.contains("Android")) MOBILE_TARGET else DESKTOP_TARGET
        val r = newFixedLengthResponse(SimpleStatus(302, "Found"), "text/html; charset=utf-8",
            "<a href=\"$target\">Continue</a>")
        r.addHeader("Location", target)
        r.addHeader("Set-Cookie", "$COOKIE=$token; Path=/; HttpOnly; SameSite=Lax")
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    /** Remaining lockout in ms, or null when [ip] may try. */
    private fun lockedFor(ip: String, now: Long): Long? = synchronized(failures) {
        val a = failures[ip] ?: return null
        if (a.lockedUntil > now) a.lockedUntil - now else null
    }

    /** Counts a wrong code; returns the lockout in ms when this failure triggers one. */
    private fun recordFailure(ip: String, now: Long): Long? = synchronized(failures) {
        // Forget IPs whose last failure is out of the window and who are not locked.
        failures.entries.removeAll { (_, a) -> a.lockedUntil <= now && (a.times.lastOrNull() ?: 0L) <= now - FAIL_WINDOW_MS }
        val a = failures.getOrPut(ip) { Attempts() }
        while (a.times.isNotEmpty() && a.times.first() <= now - FAIL_WINDOW_MS) a.times.removeFirst()
        a.times.addLast(now)
        if (a.times.size >= MAX_FAILURES) {
            a.times.clear()
            a.lockedUntil = now + LOCKOUT_MS
            LOCKOUT_MS
        } else null
    }

    private fun tooMany(remainingMs: Long): Response {
        val r = html(429, "Too many tries",
            "<p>Too many wrong codes from this device. Wait ${(remainingMs + 59_999) / 60_000} min and try again.</p>")
        r.addHeader("Retry-After", ((remainingMs + 999) / 1000).toString())
        return r
    }

    private fun hasSession(cookieHeader: String?): Boolean =
        cookieValues(cookieHeader).any { it in sessions }

    private fun newToken(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Unauthenticated answers never read the body; close so it can't be parsed as a request. */
    private fun closing(session: IHTTPSession, r: Response): Response {
        val h = session.headers
        val len = h["content-length"]?.trim()?.toLongOrNull() ?: 0L
        if (len > 0 || h["transfer-encoding"] != null) r.closeConnection(true)
        return r
    }

    // ---- proxy -----------------------------------------------------------------------

    private fun proxy(session: IHTTPSession, method: Method, path: String): Response {
        val h = session.headers
        if (h["transfer-encoding"] != null) {
            return closing(session, html(411, "Length required", "<p>Chunked uploads are not supported.</p>"))
        }
        val len = h["content-length"]?.trim()?.let { it.toLongOrNull() ?: -1L }
        if (len != null && (len < 0 || len > MAX_BODY_BYTES)) {
            return closing(session, html(413, "Too large", "<p>Request body too large.</p>"))
        }
        val body: ByteArray? = when {
            len != null && len > 0 -> readExactly(session.inputStream, len.toInt())
            method.name in BODY_METHODS -> ByteArray(0)
            else -> null
        }
        // NanoHTTPD 2.3.1 only assigns queryParameterString when the request HAS a '?', and
        // the session object is reused across keep-alive requests — so a query-less request
        // would inherit the previous request's query (e.g. "code=…" from /join). The
        // parameter map IS rebuilt per request, so it tells us whether this one had a query.
        val query = if (session.parameters.isEmpty()) null else session.queryParameterString
        val pathAndQuery = encodePath(path) + if (query.isNullOrEmpty()) "" else "?$query"
        val upHeaders = upstreamHeaders(h)

        val up = try {
            upstream.proxy(method.name, pathAndQuery, upHeaders, body)
        } catch (e: Exception) {
            LOG.log(Level.INFO, "upstream unreachable: ${e.javaClass.simpleName}: ${e.message}")
            return badGateway()
        }
        return relay(up, method)
    }

    private fun relay(up: okhttp3.Response, method: Method): Response {
        val code = up.code
        val status = SimpleStatus(code, up.message.ifBlank { reason(code) })
        val contentType = up.header("Content-Type")
        val noBody = method == Method.HEAD || code in 100..199 || code == 204 || code == 304
        val r: ProxyResponse
        if (noBody) {
            val declared = if (method == Method.HEAD) up.header("Content-Length")?.toLongOrNull() ?: 0L else 0L
            up.close()
            r = ProxyResponse(status, contentType, ByteArrayInputStream(ByteArray(0)), declared)
        } else {
            val upBody = up.body
            val length = upBody?.contentLength() ?: 0L
            val stream = if (upBody == null) ByteArrayInputStream(ByteArray(0)) else RelayStream(upBody.byteStream(), up)
            r = ProxyResponse(status, contentType, stream, if (length < 0) -1 else length)
            if (stream is RelayStream) stream.onFailure = { r.closeConnection(true) }
            if (upBody == null) up.close()
        }
        val connectionListed = up.headers("Connection").flatMap { it.split(',') }.map { it.trim().lowercase() }.toSet()
        val requestHost = up.request.url.host
        for (name in up.headers.names()) {
            val lower = name.lowercase()
            if (isHopByHop(lower) || lower in connectionListed || lower == "content-length" || lower == "content-type") continue
            val values = up.headers(name)
            when (lower) {
                "set-cookie" -> r.setCookies(values)
                "location" -> r.addHeader(name, relativeLocation(values.first(), requestHost))
                else -> r.addHeader(name, values.joinToString(", "))
            }
        }
        return r
    }

    /** An absolute redirect back to the upstream host would leave the guest's network. */
    private fun relativeLocation(location: String, upstreamHost: String): String {
        val url = location.toHttpUrlOrNull() ?: return location
        if (!url.host.equals(upstreamHost, ignoreCase = true)) return location
        return url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "") + (url.encodedFragment?.let { "#$it" } ?: "")
    }

    private fun upstreamHeaders(h: Map<String, String>): Map<String, String> {
        val connectionListed = h["connection"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in h) {
            val name = k.lowercase()
            if (isHopByHop(name) || name in connectionListed || name in DROP_UPSTREAM) continue
            val value = if (name == "cookie") stripOurCookie(v) ?: continue else v
            if (!isHeaderSafe(name, value)) continue   // OkHttp rejects non-ASCII header text
            out[name] = value
        }
        return out
    }

    // ---- pages -----------------------------------------------------------------------

    private fun codePage(session: IHTTPSession, error: String?, status: Int = 200): Response {
        val method = session.method
        val st = if (status == 200 && method != Method.GET && method != Method.HEAD) 401 else status
        val err = error?.let { "<p class=\"err\">$it</p>" } ?: ""
        return html(st, "Join the card scanner", """
            <h1>Card scanner</h1>
            <p>Enter the 6-digit code shown on the scanner phone.</p>
            $err
            <form method="get" action="$JOIN_PATH">
              <input name="code" inputmode="numeric" pattern="[0-9 ]{6,7}" maxlength="7"
                     autocomplete="one-time-code" autofocus required placeholder="123456">
              <button type="submit">Join</button>
            </form>
        """.trimIndent())
    }

    private fun badGateway(): Response = html(502, "Scanner server unreachable",
        "<h1>Scanner server unreachable</h1><p>Scanner server unreachable (home server offline or Tailscale down).</p>" +
            "<p><a href=\"\">Try again</a></p>")

    private fun html(code: Int, title: String, body: String): Response {
        val page = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
<style>body{font:17px/1.4 system-ui,sans-serif;max-width:26rem;margin:12vh auto;padding:0 16px;background:#111;color:#eee}
h1{font-size:1.4rem}input{font-size:1.6rem;letter-spacing:.3em;width:9ch;padding:.3em;border-radius:6px;border:1px solid #666}
button{font-size:1.1rem;padding:.55em 1.2em;margin-left:.4em;border-radius:6px;border:0;background:#3a7bd5;color:#fff}
.err{color:#ff8a80}a{color:#8ab4f8}</style></head><body>$body</body></html>"""
        val r = newFixedLengthResponse(SimpleStatus(code, reason(code)), "text/html; charset=utf-8", page)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    // ---- plumbing --------------------------------------------------------------------

    private class SimpleStatus(private val code: Int, private val text: String) : Response.IStatus {
        override fun getDescription(): String = "$code ${text.replace(Regex("[\\r\\n]"), " ")}"
        override fun getRequestStatus(): Int = code
    }

    /** Response that can emit several Set-Cookie lines (NanoHTTPD keeps one value per name). */
    private class ProxyResponse(status: IStatus, mime: String?, data: InputStream, length: Long) :
        Response(status, mime, data, length) {
        fun setCookies(values: List<String>) = addHeader("Set-Cookie", values.joinToString("\n"))
        override fun printHeader(pw: PrintWriter, key: String, value: String) {
            if (key.equals("Set-Cookie", ignoreCase = true) && value.contains('\n')) {
                value.split('\n').forEach { super.printHeader(pw, key, it) }
            } else super.printHeader(pw, key, value)
        }
    }

    /** Upstream body; closing it releases the OkHttp response. A read failure drops the socket. */
    private class RelayStream(input: InputStream, private val up: okhttp3.Response) : FilterInputStream(input) {
        var onFailure: (() -> Unit)? = null
        override fun read(): Int = guard { super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = guard { super.read(b, off, len) }
        private inline fun guard(block: () -> Int): Int = try { block() } catch (e: IOException) { onFailure?.invoke(); throw e }
        override fun close() {
            try { super.close() } catch (_: IOException) {} finally { up.close() }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val COOKIE = "cs_guest"
        const val JOIN_PATH = "/join"
        const val MOBILE_TARGET = "/phone?panel=1"
        const val DESKTOP_TARGET = "/"
        const val MAX_FAILURES = 8
        const val FAIL_WINDOW_MS = 5 * 60_000L
        const val LOCKOUT_MS = 5 * 60_000L
        const val MAX_BODY_BYTES = 64L * 1024 * 1024

        private val LOG = Logger.getLogger("GatewayServer")
        private val HOP_BY_HOP = setOf("connection", "keep-alive", "te", "trailer", "trailers", "transfer-encoding", "upgrade")
        private val DROP_UPSTREAM = setOf("host", "content-length", "expect", "remote-addr", "http-client-ip")
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH")

        internal fun isHopByHop(lowerName: String) = lowerName in HOP_BY_HOP || lowerName.startsWith("proxy-")

        /** Every value of a cookie named [COOKIE] in a Cookie header. */
        internal fun cookieValues(header: String?): List<String> =
            header.orEmpty().split(';').mapNotNull {
                val p = it.trim()
                if (p.startsWith("$COOKIE=")) p.substring(COOKIE.length + 1).trim().trim('"') else null
            }

        /** The Cookie header minus our own cookie; null when nothing else remains. */
        internal fun stripOurCookie(header: String): String? =
            header.split(';').map { it.trim() }
                .filter { it.isNotEmpty() && it.substringBefore('=').trim() != COOKIE }
                .joinToString("; ").ifEmpty { null }

        private fun isHeaderSafe(name: String, value: String): Boolean =
            name.isNotEmpty() && name.all { it in '!'..'~' } &&
                value.all { it == '\t' || it in ' '..'~' }

        /** NanoHTTPD hands us a percent-DECODED path; re-encode what isn't a legal path char. */
        internal fun encodePath(path: String): String {
            val sb = StringBuilder(path.length + 8)
            for (b in path.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xff)
                val ch = c.toChar()
                if (c < 0x80 && (ch.isLetterOrDigit() || ch in "/-._~!$&'()*+,;=:@")) sb.append(ch)
                else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
            return if (sb.startsWith("/")) sb.toString() else "/$sb"
        }

        private fun readExactly(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw IOException("request body ended after $off of $n bytes")
                off += r
            }
            return buf
        }

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 301 -> "Moved Permanently"; 302 -> "Found"
            303 -> "See Other"; 304 -> "Not Modified"; 307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 409 -> "Conflict"; 411 -> "Length Required"; 413 -> "Payload Too Large"
            422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"
            502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
            else -> "Status"
        }
    }
}
