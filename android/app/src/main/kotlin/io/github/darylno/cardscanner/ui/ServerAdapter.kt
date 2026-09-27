package io.github.darylno.cardscanner.ui

import io.github.darylno.cardscanner.gateway.Upstream
import io.github.darylno.cardscanner.net.Pairing
import io.github.darylno.cardscanner.net.PrefsConfigStore
import io.github.darylno.cardscanner.net.ServerClient
import io.github.darylno.cardscanner.net.ServerConfig
import io.github.darylno.cardscanner.net.normalizeBaseUrl
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** [ServerPort] over the NET agent's PrefsConfigStore + ServerClient + Pairing. */
class ServerAdapter(private val store: PrefsConfigStore, val client: ServerClient) : ServerPort {
    override val isPaired: Boolean get() = store.load() != null
    override fun bestBase(): String? = client.currentBase()
    override fun pin(): String? = store.load()?.pin
    override fun urls(): List<String> = store.load()?.urls ?: emptyList()

    override fun setUrls(urls: List<String>) {
        val c = store.load() ?: return
        val clean = urls.mapNotNull { normalizeBaseUrl(it) }.distinct()
        if (clean.isNotEmpty()) store.save(ServerConfig(clean, c.pin))
    }

    override fun version(): String = client.version()

    override fun refreshAddresses(): List<String> {
        val c = store.load() ?: return emptyList()
        val learned = client.addresses()
        // Keep the user's own entries (e.g. one typed by hand) after the server's list.
        val merged = (learned + c.urls).distinct()
        store.save(ServerConfig(merged, c.pin))
        return merged
    }

    override fun probe(base: String): String {
        val r = Pairing.probe(base)
        return r.fingerprintHex ?: throw IOException(r.error ?: "No answer from $base")
    }

    override fun pair(base: String, pin: String): List<String> {
        val cfg = Pairing.pair(base, pin)
        client.updateConfig(cfg)
        return cfg.urls
    }

    override fun normalize(input: String): String? = normalizeBaseUrl(input)

    override fun unpair() = store.clear()

    /** The gateway's upstream: ServerClient.proxy (pinned TLS + failover). */
    fun upstream(): Upstream = Upstream { method, pathAndQuery, headers, body ->
        val hb = Headers.Builder()
        for ((k, v) in headers) hb.addUnsafeNonAscii(k, v)
        val rb = body?.toRequestBody(headers["content-type"]?.toMediaTypeOrNull())
        client.proxy(method, pathAndQuery, hb.build(), rb)
    }
}
