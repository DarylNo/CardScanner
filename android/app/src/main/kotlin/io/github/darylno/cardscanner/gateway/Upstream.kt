package io.github.darylno.cardscanner.gateway

import java.io.IOException

/**
 * Where [GatewayServer] forwards an authenticated guest's request: the home scanner
 * server. In the app this is the NET package's `ServerClient.proxy` (pinned TLS + address
 * failover); in tests it is a plain-HTTP MockWebServer.
 *
 * @param method HTTP method, upper case ("GET", "POST", ...).
 * @param pathAndQuery path plus raw query, always starting with "/" (e.g. "/api/scans?x=1").
 * @param headers end-to-end request headers, names LOWER-CASE; hop-by-hop headers, Host,
 *   Content-Length and the gateway's own `cs_guest` cookie are already removed. Includes
 *   `content-type` when the guest sent one (use it as the body's media type).
 * @param body the request body, or null when there is none. POST/PUT/PATCH always get a
 *   (possibly empty) array.
 * @return the upstream response, streaming; the gateway closes it after relaying the body.
 * @throws IOException when the server is unreachable (the guest gets a 502 page).
 */
fun interface Upstream {
    @Throws(IOException::class)
    fun proxy(method: String, pathAndQuery: String, headers: Map<String, String>, body: ByteArray?): okhttp3.Response
}
