package io.github.darylno.cardscanner.net

import okhttp3.Request
import java.io.IOException

/**
 * Result of [Pairing.probe]: the fingerprint (64 lowercase hex) of whatever
 * certificate [baseUrl] presented, or an error when nothing answered.
 */
data class ProbeResult(val baseUrl: String?, val fingerprintHex: String?, val error: String?) {
    val ok: Boolean get() = fingerprintHex != null
}

/**
 * Pairing = learn the pin (from the desktop QR's `#pin=`, or by probing and
 * letting the user compare fingerprints — TOFU), then prove it with a real
 * pinned request and fetch every address the server answers on.
 */
object Pairing {

    /**
     * Connect with the PROBE trust manager: it records the leaf and aborts
     * the handshake, so no request byte is ever sent to an unverified peer.
     * The fingerprint is read from the trust manager, never from the
     * exception type (Android may wrap it differently — research_4 §1.3).
     */
    fun probe(baseUrl: String, timeouts: Timeouts = Timeouts(readMs = 10_000, writeMs = 10_000)): ProbeResult {
        val base = normalizeBaseUrl(baseUrl) ?: return ProbeResult(null, null, "Not a server address: $baseUrl")
        val probe = pinnedClient(null, timeouts)
        var error: String? = null
        try {
            probe.client.newCall(Request.Builder().url("$base/api/version").get().build()).execute().close()
            error = "Server answered without TLS verification — refusing" // impossible with the probe TM
        } catch (e: IOException) {
            if (probe.trustManager.lastSeen == null) error = e.message ?: e.javaClass.simpleName
        } finally {
            probe.client.connectionPool.evictAll()
        }
        val seen = probe.trustManager.lastSeen
        return if (seen != null) ProbeResult(base, Pin.sha256Hex(seen), null) else ProbeResult(base, null, error)
    }

    /**
     * Verify [pin] against [baseUrl] with real requests (GET /api/version, then
     * /api/addresses) and return the config to save: urls = [baseUrl] (the one
     * that worked, first) + the server's own list, deduplicated. A server
     * without /api/addresses (older build) pairs with just [baseUrl].
     *
     * Throws [PinMismatchException] when the server's cert isn't [pin],
     * [HttpException] / [IOException] when it can't be verified. The caller
     * saves the result (e.g. [ServerClient.updateConfig]).
     */
    fun pair(
        baseUrl: String,
        pin: String,
        clientFactory: (ConfigStore) -> ServerClient = { ServerClient(it) },
    ): ServerConfig {
        val base = normalizeBaseUrl(baseUrl) ?: throw IOException("Not a server address: $baseUrl")
        val p = Pin.normalize(pin) ?: throw IllegalArgumentException("malformed pin")
        val client = clientFactory(InMemoryConfigStore(ServerConfig(listOf(base), p)))
        client.version()
        val more = try {
            client.addresses()
        } catch (e: HttpException) {
            if (e.code == 404) emptyList() else throw e
        }
        return ServerConfig((listOf(base) + more).distinct(), p)
    }
}
