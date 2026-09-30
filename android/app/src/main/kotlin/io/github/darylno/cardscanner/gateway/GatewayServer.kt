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
 *  - 8 wrong codes from one IP within 5 minutes lock that IP out (429) for 5 minutes. The
 *    lockout check, the comparison and the failure record happen in ONE critical section,
 *    so parallel connections from one IP can never get more than 8 guesses evaluated.
 *  - 20 wrong codes within 5 minutes from ALL addresses combined rotate the code (existing
 *    sessions stay valid; [onCodeRotated] fires so the phone can show the new one) and
 *    reset the failure counters: a search spread over many source addresses (IPv6 privacy
 *    addresses, spare IPv4s) restarts against a new unknown code. Active lockouts stay.
 *  - LAN only: clients whose address is not private/link-local ([isLocalClient]) get a 403
 *    before anything else — the listener binds every interface, so without this the join
 *    page would be reachable over Tailscale (100.64/10) and over cellular public IPv6.
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
    /**
     * Roles (the phone server): when set, the one-time admin code pairs a computer as
     * ADMIN (a long-lived [ADMIN_COOKIE]); the 6-digit code makes a GUEST; and every
     * proxied request carries [ROLE_HEADER] = admin|guest (a client's own is dropped).
     * Null (the Share gateway to the computer): one role, today's behaviour.
     */
    val admins: AdminPairing? = null,
) : NanoHTTPD(hostname, port) {

    private val sessions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failures = HashMap<String, Attempts>()   // guarded by itself
    private val globalFailures = ArrayDeque<Long>()      // guarded by [failures]

    /**
     * Called (off the lock, on the request thread) with the new code when the global
     * brute-force budget rotates it. Not called by [stopSharing], whose caller has the code.
     */
    @Volatile
    var onCodeRotated: ((String) -> Unit)? = null

    /** Outcome of one join attempt; see [attemptJoin]. */
    internal sealed class JoinResult {
        object Joined : JoinResult()
        class JoinedAdmin(val token: String) : JoinResult()
        object Wrong : JoinResult()
        class Locked(val remainingMs: Long) : JoinResult()
    }

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
        return synchronized(failures) {
            failures.clear()
            globalFailures.clear()
            joinCode.rotate()
        }
    }

    override fun stop() {
        stopSharing()
        super.stop()
    }

    // NanoHTTPD would gzip text/JSON a second time on top of whatever upstream sent.
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        // getRemoteIpAddress() is the socket's peer (NanoHTTPD's "remote-addr" HEADER can be
        // overwritten by the client; this field cannot).
        if (!isLocalClient(session.remoteIpAddress.orEmpty())) {
            val r = html(403, "Not on this network",
                "<h1>Card scanner</h1><p>Only devices on this phone's Wi-Fi or hotspot can join.</p>")
            r.closeConnection(true)   // never read a body from an off-LAN client
            return r
        }
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
        val role = roleOf(session.headers["cookie"]) ?: return closing(session, codePage(session, null))
        return proxy(session, method, path, role)
    }

    // ---- join / lockout --------------------------------------------------------------

    private fun join(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress ?: "?"
        val now = clock()
        val given = session.parameters["code"]?.firstOrNull()
        if (given.isNullOrBlank()) {
            val locked = synchronized(failures) { failures[ip]?.lockedUntil?.takeIf { it > now }?.minus(now) }
            return if (locked != null) tooMany(locked) else codePage(session, null)
        }
        val result = attemptJoin(ip, given, now)
        when (result) {
            is JoinResult.Locked -> return tooMany(result.remainingMs)
            JoinResult.Wrong ->
                return codePage(session, "That code is not right — check the scanner phone and try again.", status = 403)
            is JoinResult.JoinedAdmin, JoinResult.Joined -> Unit
        }
        val ua = session.headers["user-agent"].orEmpty()
        val target = if (ua.contains("Mobi") || ua.contains("Android")) MOBILE_TARGET else DESKTOP_TARGET
        val r = newFixedLengthResponse(SimpleStatus(302, "Found"), "text/html; charset=utf-8",
            "<a href=\"$target\">Continue</a>")
        r.addHeader("Location", target)
        if (result is JoinResult.JoinedAdmin) {
            // Remembered until revoked on the phone (≈10 years; the phone forgets it on revoke).
            r.addHeader("Set-Cookie", "$ADMIN_COOKIE=${result.token}; Path=/; Max-Age=315360000; HttpOnly; SameSite=Lax")
        } else {
            val token = newToken()
            sessions += token
            r.addHeader("Set-Cookie", "$COOKIE=$token; Path=/; HttpOnly; SameSite=Lax")
        }
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    /**
     * One join attempt from [ip] with code [given]: lockout check → attempt reserved in the
     * per-IP and global windows → constant-time compare → reservation kept (wrong) or
     * released (right), all inside ONE critical section. Separate check/record blocks let N
     * parallel connections from one IP all pass the check before any failure landed.
     * The compare is a few nanoseconds, so holding the lock across it costs nothing.
     */
    internal fun attemptJoin(ip: String, given: String, now: Long): JoinResult {
        var rotatedTo: String? = null
        val result = synchronized(failures) {
            // Forget IPs whose last failure is out of the window and who are not locked.
            failures.entries.removeAll { (_, a) -> a.lockedUntil <= now && (a.times.lastOrNull() ?: 0L) <= now - FAIL_WINDOW_MS }
            while (globalFailures.isNotEmpty() && globalFailures.first() <= now - FAIL_WINDOW_MS) globalFailures.removeFirst()
            val a = failures.getOrPut(ip) { Attempts() }
            if (a.lockedUntil > now) return@synchronized JoinResult.Locked(a.lockedUntil - now)
            while (a.times.isNotEmpty() && a.times.first() <= now - FAIL_WINDOW_MS) a.times.removeFirst()
            // Reserve before comparing.
            a.times.addLast(now)
            globalFailures.addLast(now)
            if (joinCode.matches(given)) {
                failures.remove(ip)
                globalFailures.removeLast()
                return@synchronized JoinResult.Joined
            }
            admins?.redeem(given, now)?.let { token ->
                failures.remove(ip)
                globalFailures.removeLast()
                return@synchronized JoinResult.JoinedAdmin(token)
            }
            val out: JoinResult = if (a.times.size >= MAX_FAILURES) {
                a.times.clear()
                a.lockedUntil = now + LOCKOUT_MS
                JoinResult.Locked(LOCKOUT_MS)
            } else JoinResult.Wrong
            if (globalFailures.size >= GLOBAL_MAX_FAILURES) {
                // A distributed search restarts against a new unknown code. Counters reset;
                // lockouts in force stay (they are penalties, not counts); sessions stay.
                rotatedTo = joinCode.rotate()
                admins?.cancelCode()        // a distributed search must not keep aiming at the admin code
                globalFailures.clear()
                failures.values.forEach { it.times.clear() }
                failures.entries.removeAll { (_, x) -> x.lockedUntil <= now }
            }
            out
        }
        rotatedTo?.let { code ->
            LOG.log(Level.WARNING, "gateway: $GLOBAL_MAX_FAILURES wrong join codes within the window — code rotated")
            try { onCodeRotated?.invoke(code) } catch (e: Exception) { LOG.log(Level.WARNING, "onCodeRotated failed", e) }
        }
        return result
    }

    private fun tooMany(remainingMs: Long): Response {
        val r = html(429, "Too many tries",
            "<p>Too many wrong codes from this device. Wait ${(remainingMs + 59_999) / 60_000} min and try again.</p>")
        r.addHeader("Retry-After", ((remainingMs + 999) / 1000).toString())
        return r
    }

    /** [ROLE_ADMIN] for a paired computer's cookie, [ROLE_GUEST] for a live guest session, else null. */
    private fun roleOf(cookieHeader: String?): String? {
        val a = admins
        if (a != null && cookieValues(cookieHeader, ADMIN_COOKIE).any { a.isAdmin(it) }) return ROLE_ADMIN
        return if (cookieValues(cookieHeader).any { it in sessions }) ROLE_GUEST else null
    }

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

    private fun proxy(session: IHTTPSession, method: Method, path: String, role: String): Response {
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
        if (admins != null) upHeaders[ROLE_HEADER] = role

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

    private fun upstreamHeaders(h: Map<String, String>): MutableMap<String, String> {
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
            <p>Enter the ${if (admins != null) "code" else "6-digit code"} shown on the scanner phone.</p>
            $err
            <form method="get" action="$JOIN_PATH">
              <input name="code" inputmode="numeric" pattern="${if (admins != null) "[0-9 ]{6,9}" else "[0-9 ]{6,7}"}" maxlength="${if (admins != null) 9 else 7}"
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
        /** The phone server's port (Stage 4: the one server; the Share screen shares it). */
        const val DEFAULT_PORT = 8090
        const val COOKIE = "cs_guest"
        const val ADMIN_COOKIE = "cs_admin"
        /** Set by the gateway on every proxied request when [admins] is on; a client's own is dropped. */
        const val ROLE_HEADER = "x-cardscanner-role"
        const val ROLE_ADMIN = "admin"
        const val ROLE_GUEST = "guest"
        const val JOIN_PATH = "/join"
        const val MOBILE_TARGET = "/phone?panel=1"
        const val DESKTOP_TARGET = "/"
        const val MAX_FAILURES = 8
        const val GLOBAL_MAX_FAILURES = 20
        const val FAIL_WINDOW_MS = 5 * 60_000L
        const val LOCKOUT_MS = 5 * 60_000L
        const val MAX_BODY_BYTES = 64L * 1024 * 1024

        private val LOG = Logger.getLogger("GatewayServer")
        private val HOP_BY_HOP = setOf("connection", "keep-alive", "te", "trailer", "trailers", "transfer-encoding", "upgrade")
        private val DROP_UPSTREAM = setOf("host", "content-length", "expect", "remote-addr", "http-client-ip", ROLE_HEADER)
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH")

        /**
         * True when [ip] (the socket peer, as NanoHTTPD reports it) is on the phone's own
         * network: IPv4 10/8, 172.16/12, 192.168/16, loopback; IPv6 link-local fe80::/10,
         * ULA fc00::/7, ::1; IPv4-mapped IPv6 (::ffff:a.b.c.d) judged by its IPv4.
         * Everything else is refused — notably Tailscale/CGNAT 100.64/10, public IPv4 and
         * global IPv6 (cellular). Pure: no DNS, unparseable input is refused.
         */
        fun isLocalClient(ip: String): Boolean {
            var s = ip.trim()
            if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1)
            s = s.substringBefore('%')   // zone id: fe80::1%wlan0
            if (s.isEmpty()) return false
            parseIpv4(s)?.let { return isLocalV4(it) }
            val b = parseIpv6(s) ?: return false
            val u = IntArray(16) { b[it].toInt() and 0xff }
            if ((0 until 10).all { u[it] == 0 } && u[10] == 0xff && u[11] == 0xff) {
                return isLocalV4(intArrayOf(u[12], u[13], u[14], u[15]))
            }
            if ((0 until 15).all { u[it] == 0 } && u[15] == 1) return true            // ::1
            if (u[0] == 0xfe && (u[1] and 0xc0) == 0x80) return true                    // fe80::/10
            if ((u[0] and 0xfe) == 0xfc) return true                                     // fc00::/7
            return false
        }

        private fun isLocalV4(a: IntArray): Boolean =
            a[0] == 10 || a[0] == 127 ||
                (a[0] == 172 && a[1] in 16..31) ||
                (a[0] == 192 && a[1] == 168)

        private fun parseIpv4(s: String): IntArray? {
            val parts = s.split('.')
            if (parts.size != 4) return null
            val out = IntArray(4)
            for ((i, p) in parts.withIndex()) {
                if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
                out[i] = p.toInt().takeIf { it <= 255 } ?: return null
            }
            return out
        }

        private fun parseIpv6(s: String): ByteArray? {
            if (!s.contains(':')) return null
            val dbl = s.indexOf("::")
            if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) return null
            fun groups(part: String, allowV4Tail: Boolean): List<Int>? {
                if (part.isEmpty()) return emptyList()
                val out = ArrayList<Int>()
                val pieces = part.split(':')
                for ((i, g) in pieces.withIndex()) {
                    if (allowV4Tail && i == pieces.lastIndex && g.contains('.')) {
                        val v4 = parseIpv4(g) ?: return null
                        out += (v4[0] shl 8) or v4[1]; out += (v4[2] shl 8) or v4[3]
                    } else {
                        if (g.isEmpty() || g.length > 4) return null
                        if (!g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                        out += g.toInt(16)
                    }
                }
                return out
            }
            val words: List<Int> = if (dbl >= 0) {
                val head = groups(s.substring(0, dbl), allowV4Tail = false) ?: return null
                val tail = groups(s.substring(dbl + 2), allowV4Tail = true) ?: return null
                if (head.size + tail.size > 7) return null
                head + List(8 - head.size - tail.size) { 0 } + tail
            } else {
                groups(s, allowV4Tail = true)?.takeIf { it.size == 8 } ?: return null
            }
            val bytes = ByteArray(16)
            for ((i, w) in words.withIndex()) { bytes[2 * i] = (w shr 8).toByte(); bytes[2 * i + 1] = w.toByte() }
            return bytes
        }

        internal fun isHopByHop(lowerName: String) = lowerName in HOP_BY_HOP || lowerName.startsWith("proxy-")

        /** Every value of a cookie named [name] in a Cookie header. */
        internal fun cookieValues(header: String?, name: String = COOKIE): List<String> =
            header.orEmpty().split(';').mapNotNull {
                val p = it.trim()
                if (p.startsWith("$name=")) p.substring(name.length + 1).trim().trim('"') else null
            }

        /** The Cookie header minus our own cookies; null when nothing else remains. */
        internal fun stripOurCookie(header: String): String? =
            header.split(';').map { it.trim() }
                .filter { it.isNotEmpty() && it.substringBefore('=').trim() !in setOf(COOKIE, ADMIN_COOKIE) }
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
