package io.github.darylno.cardscanner.phoneserver

import io.github.darylno.cardscanner.core.server.ApiRequest
import io.github.darylno.cardscanner.core.server.ApiResponse
import io.github.darylno.cardscanner.core.server.PhoneApi
import io.github.darylno.cardscanner.core.server.ScanStore
import io.github.darylno.cardscanner.gateway.Upstream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLDecoder

/**
 * The phone's own server (Stage 3b preview): the SAME review pages the
 * computer serves (server/static/phone.html + desktop.html, packaged into the
 * app's assets at build time) over the phone's [ScanStore].
 *
 *  - the scan API → [PhoneApi] (golden-tested against server/app.py);
 *  - `/api/search` → the ported search_candidates over Scryfall ([search]);
 *  - the pages' startup reads (version, health, setup, update-check,
 *    price-status/-debug) answered in the Python shapes, so the pages load;
 *  - pricing (3c) and export (3d) are not here yet: 501 with a clear error.
 *
 * Access control is NOT here: [LocalUpstream] plugs this into the existing
 * GatewayServer (LAN only, 6-digit code, lockouts, session cookie).
 */
class PhoneBackend(
    val store: ScanStore,
    val photos: PhotoDir,
    private val pages: (String) -> ByteArray?,
    private val version: String,
    private val search: (String) -> List<Map<String, Any?>>,
    private val lanIp: () -> String?,
    private val packRows: () -> Int,
) {
    val api = PhoneApi(store, photos)

    fun handle(req: ApiRequest): ApiResponse {
        api.handle(req)?.let { return it }
        val get = req.method == "GET" || req.method == "HEAD"
        return when {
            get && req.path == "/" -> page("desktop.html")
            get && req.path == "/phone" -> page("phone.html")
            get && req.path == "/api/version" ->
                ApiResponse.json(200, linkedMapOf("version" to version, "lan_ip" to lanIp(), "is_lan" to (lanIp() != null)))
            get && req.path == "/api/health" ->
                ApiResponse.json(200, linkedMapOf("ok" to true, "version" to version,
                    "ocr" to linkedMapOf("available" to true, "error" to null)))
            get && req.path == "/api/setup/status" -> {
                val n = packRows().toLong()
                ApiResponse.json(200, linkedMapOf("index_built" to (n > 1000), "indexed" to n, "total" to n,
                    "building" to false, "error" to null, "images" to 0L, "images_total" to 0L,
                    "prefetching" to false, "prefetch_error" to null, "build_progress" to null,
                    "prefetch_progress" to null))
            }
            get && req.path == "/api/update-check" ->
                ApiResponse.json(200, linkedMapOf("current" to version, "latest" to null,
                    "update_available" to null, "can_self_update" to false,
                    "download_url" to "https://github.com/DarylNo/CardScanner/releases/latest"))
            get && req.path == "/api/price-status" ->
                ApiResponse.json(200, linkedMapOf("active" to false, "total" to 0L, "done" to 0L,
                    "current" to null, "cancelling" to false, "cooldown_s" to 0L, "pace_s" to null,
                    "next_check_s" to null, "rate_per_s" to null, "eta_s" to null))
            get && req.path == "/api/price-debug" ->
                ApiResponse.json(200, linkedMapOf("pace_s" to null, "next_check_s" to null, "events" to emptyList<Any>()))
            get && req.path == "/api/search" -> {
                val q = req.query["q"].orEmpty()
                val cands = if (q.isBlank()) emptyList() else runCatching { search(q) }.getOrDefault(emptyList())
                ApiResponse.json(200, mapOf("candidates" to cands))
            }
            req.path.startsWith("/api/export") || req.path.contains("price") ->
                ApiResponse.json(501, mapOf("error" to "not on the phone yet (pricing and export arrive in the next preview)"))
            else -> ApiResponse.json(404, mapOf("detail" to "Not Found"))
        }
    }

    private fun page(name: String): ApiResponse {
        val bytes = pages(name) ?: return ApiResponse.json(404, mapOf("detail" to "Not Found"))
        return ApiResponse(200, "text/html; charset=utf-8", bytes)
    }

    companion object {
        /** `a=1&b=x%20y` → map (last value wins, like FastAPI's scalar query params). */
        fun parseQuery(raw: String?): Map<String, String> {
            if (raw.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in raw.split('&')) {
                if (part.isEmpty()) continue
                val k = part.substringBefore('=')
                val v = if ('=' in part) part.substringAfter('=') else ""
                out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            }
            return out
        }
    }
}

/**
 * [Upstream] that answers from the phone's [PhoneBackend] instead of the
 * computer — so the preview reuses the Share gateway unchanged (LAN-only,
 * join code, lockouts, cookies, relay).
 */
class LocalUpstream(private val backend: PhoneBackend) : Upstream {
    override fun proxy(method: String, pathAndQuery: String, headers: Map<String, String>, body: ByteArray?): Response {
        val path = pathAndQuery.substringBefore('?').let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        val query = if ('?' in pathAndQuery) pathAndQuery.substringAfter('?') else null
        val res = try {
            backend.handle(ApiRequest(method, path, PhoneBackend.parseQuery(query), body))
        } catch (e: Exception) {
            ApiResponse.json(500, mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"))
        }
        return Response.Builder()
            .request(Request.Builder().url("http://phone.local$pathAndQuery").build())
            .protocol(Protocol.HTTP_1_1)
            .code(res.status)
            .message(REASONS[res.status] ?: "OK")
            .header("Cache-Control", "no-store")
            .body(res.body.toResponseBody(res.contentType.toMediaType()))
            .build()
    }

    private companion object {
        val REASONS = mapOf(200 to "OK", 400 to "Bad Request", 404 to "Not Found", 422 to "Unprocessable Entity",
            500 to "Internal Server Error", 501 to "Not Implemented")
    }
}
