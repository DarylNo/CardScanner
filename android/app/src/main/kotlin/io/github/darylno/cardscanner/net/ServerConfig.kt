package io.github.darylno.cardscanner.net

/**
 * A paired server: every base URL it answers on (best first — home LAN, then
 * Tailscale MagicDNS, then tailnet IPs, as `/api/addresses` orders them) and
 * the ONE pin that vouches for all of them (the server has a single cert).
 */
data class ServerConfig(val urls: List<String>, val pin: String)

/** Persistence for the pairing + the last base URL that answered. */
interface ConfigStore {
    fun load(): ServerConfig?
    fun save(c: ServerConfig)
    fun lastGood(): String?
    fun setLastGood(url: String)
}

/** For tests and previews. Thread-safe. */
class InMemoryConfigStore(initial: ServerConfig? = null, lastGood: String? = null) : ConfigStore {
    @Volatile private var config: ServerConfig? = initial
    @Volatile private var good: String? = lastGood

    override fun load(): ServerConfig? = config
    override fun save(c: ServerConfig) { config = c }
    override fun lastGood(): String? = good
    override fun setLastGood(url: String) { good = url }
}

const val DEFAULT_PORT = 8443

/**
 * What a human types or a QR carries → canonical base URL `https://host:port`
 * (no path, query, fragment or trailing slash), or null if it isn't one.
 *
 * Accepts `192.168.1.5`, `host:8443`, `rig.tail1234.ts.net`,
 * `https://host:8443/phone#pin=…`, `[fd7a::1]:8443`. Missing port → 8443 (the
 * server's port, not HTTPS's 443). `http://` is upgraded: the server only
 * speaks HTTPS, so a typed http:// can only mean the same server. Any other
 * scheme is rejected.
 */
fun normalizeBaseUrl(input: String?): String? {
    var s = input?.trim() ?: return null
    if (s.isEmpty()) return null
    s.indexOf('#').let { if (it >= 0) s = s.substring(0, it) }
    val schemeEnd = s.indexOf("://")
    if (schemeEnd >= 0) {
        val scheme = s.substring(0, schemeEnd).lowercase()
        if (scheme != "https" && scheme != "http") return null
        s = s.substring(schemeEnd + 3)
    }
    val end = s.indexOfFirst { it == '/' || it == '?' }
    if (end >= 0) s = s.substring(0, end)
    s.indexOf('@').let { if (it >= 0) s = s.substring(it + 1) } // no userinfo
    if (s.isEmpty()) return null

    val host: String
    var port = DEFAULT_PORT
    if (s.startsWith("[")) {                       // IPv6 literal
        val close = s.indexOf(']')
        if (close < 0) return null
        host = s.substring(1, close)
        val rest = s.substring(close + 1)
        if (rest.isNotEmpty()) {
            if (!rest.startsWith(":")) return null
            port = rest.substring(1).toIntOrNull() ?: return null
        }
        if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) return null
    } else {
        val colon = s.lastIndexOf(':')
        if (colon >= 0) {
            if (s.indexOf(':') != colon) return null   // bare IPv6 without brackets: ambiguous
            port = s.substring(colon + 1).toIntOrNull() ?: return null
            host = s.substring(0, colon)
        } else {
            host = s
        }
        if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
    }
    if (port !in 1..65535) return null
    val h = host.lowercase()
    return if (h.contains(':')) "https://[$h]:$port" else "https://$h:$port"
}
