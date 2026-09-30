package io.github.darylno.cardscanner.phoneserver

import io.github.darylno.cardscanner.core.server.ApiRequest
import io.github.darylno.cardscanner.core.server.ApiResponse
import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.server.DeviceApi
import io.github.darylno.cardscanner.core.server.F2fLookup
import io.github.darylno.cardscanner.core.server.LayoutStore
import io.github.darylno.cardscanner.core.server.PhoneApi
import io.github.darylno.cardscanner.core.server.PriceSweep
import io.github.darylno.cardscanner.core.server.PriceWorker
import io.github.darylno.cardscanner.core.server.ScanServer
import io.github.darylno.cardscanner.core.server.ScanStore
import io.github.darylno.cardscanner.core.server.ROLE_ADMIN
import io.github.darylno.cardscanner.core.server.ROLE_GUEST
import io.github.darylno.cardscanner.gateway.GatewayServer
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
 *  - the scan API + pricing → [ScanServer] ([PhoneApi] + [PriceSweep], both
 *    golden-tested against server/app.py);
 *  - pricing is a loop, not a timed sweep ([worker], owner 2026-09-30): it
 *    checks every 5 s while anything is owed and sleeps until the next
 *    scan/pick/edit when nothing is; a pick, a finish change or a filed scan
 *    also gets a price check so THAT scan jumps the queue;
 *  - `/api/search` → the ported search_candidates over Scryfall ([search]);
 *  - the pages' startup reads (version, health, setup, update-check,
 *    price-debug) answered in the Python shapes, so the pages load;
 *  - export (3d): TXT + the CSV column builder (layout, preview, download),
 *    server/export.py ported and golden-tested, layout saved beside the DB.
 *
 * Access control: [LocalUpstream] plugs this into the existing GatewayServer
 * (LAN only, codes, lockouts, cookies), which says who is asking — the paired
 * computer (admin) or a guest — and [ScanServer] refuses a guest the
 * admin-only routes (delete, clear, export, sweep controls).
 */
class PhoneBackend(
    val store: ScanStore,
    val photos: PhotoDir,
    private val pages: (String) -> ByteArray?,
    private val version: String,
    private val search: (String) -> List<Map<String, Any?>>,
    private val lanIp: () -> String?,
    private val packRows: () -> Int,
    f2f: F2fLookup,
    launch: (Runnable) -> Unit,
    now: () -> Double,
    interrupt: (Boolean) -> Unit = {},
    private val paceS: () -> Double? = { null },
    private val f2fEvents: () -> List<Map<String, Any?>> = { emptyList() },
    layouts: LayoutStore = LayoutStore(null),
    /** The phone's own settings from the browser (admin only — [ScanServer.adminOnly]). */
    private val device: DeviceApi? = null,
    /** The update lock's view: (latest seen, a newer one is out, its APK link) — null = never checked. */
    private val update: () -> Triple<String?, Boolean, String?> = { Triple(null, false, null) },
) {
    val api = PhoneApi(store, photos)
    val sweep = PriceSweep(store, api, f2f, launch, now, interrupt, paceS)
    val server = ScanServer(api, sweep, layouts)
    val worker = PriceWorker(sweep, now)

    /**
     * A capture the phone identified: file it (a Retry replaces the row it retries,
     * as /api/scan's replace_scan_id does), then price it (scan-time pricing).
     */
    fun file(result: Map<String, Any?>, photo: ByteArray?, replaceScanId: Long? = null): Map<String, Any?> {
        val row = if (replaceScanId != null) api.fileScan(result, photo, replaceScanId) else api.fileScan(result, photo)
        // Filed: pricing is best effort from here — it must never fail the filing.
        runCatching { (row["id"] as? Number)?.let { sweep.priceCheck(it.toLong()) } }
        runCatching { worker.wake() }
        return row
    }

    fun handle(req: ApiRequest): ApiResponse {
        server.handle(req)?.let { res ->
            kickPriceCheck(req, res)
            if (req.method != "GET" && req.method != "HEAD" && res.status < 300) worker.wake()
            return res
        }
        device?.handle(req)?.let { return it }
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
                // Always "built": the phone's card database is its art pack, fetched by the app
                // (its banner + Settings show that state). The page's "Build (~1 hr)" and
                // "Download all images" are the retired computer's and would 404 here.
                ApiResponse.json(200, linkedMapOf("index_built" to true, "indexed" to n, "total" to n,
                    "building" to false, "error" to null, "images" to 0L, "images_total" to 0L,
                    "prefetching" to false, "prefetch_error" to null, "build_progress" to null,
                    "prefetch_progress" to null))
            }
            get && req.path == "/api/update-check" -> {
                // The desktop's update banner. A newer release also stops the phone scanning (UpdateLock).
                val (latest, newer, apk) = update()
                ApiResponse.json(200, linkedMapOf("current" to version, "latest" to latest,
                    "update_available" to newer, "can_self_update" to false,
                    "download_url" to (apk ?: "https://github.com/DarylNo/CardScanner/releases/latest")))
            }
            get && req.path == "/api/price-debug" ->
                ApiResponse.json(200, linkedMapOf("pace_s" to paceS()?.let { Math.round(it * 100) / 100.0 },
                    "next_check_s" to sweep.status()["next_check_s"], "events" to f2fEvents()))
            get && req.path == "/api/search" -> {
                val q = req.query["q"].orEmpty()
                val cands = if (q.isBlank()) emptyList() else runCatching { search(q) }.getOrDefault(emptyList())
                ApiResponse.json(200, mapOf("candidates" to cands))
            }
            else -> ApiResponse.json(404, mapOf("detail" to "Not Found"))
        }
    }

    /** A pick or a finish change left f2f cleared: price that scan now (one consumer). */
    private fun kickPriceCheck(req: ApiRequest, res: ApiResponse) {
        if (res.status != 200) return
        val m = SCAN_ID.matchEntire(req.path) ?: return
        val isSelect = req.method == "POST" && m.groupValues[2] == "/select"
        val isFinish = req.method == "PATCH" && m.groupValues[2].isEmpty() &&
            req.body?.let { runCatching { (MiniJson.parse(String(it)) as? Map<*, *>)?.containsKey("finish") }.getOrNull() } == true
        if (isSelect || isFinish) m.groupValues[1].toLongOrNull()?.let { sweep.priceCheck(it) }
    }

    private fun page(name: String): ApiResponse {
        val bytes = pages(name) ?: return ApiResponse.json(404, mapOf("detail" to "Not Found"))
        return ApiResponse(200, "text/html; charset=utf-8", bytes)
    }

    companion object {
        private val SCAN_ID = Regex("/api/scans/(\\d+)(/select)?")

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
            // The role comes ONLY from the gateway (it drops any a client sends and sets its
            // own); no header fails CLOSED as a guest.
            val role = if (headers[GatewayServer.ROLE_HEADER] == GatewayServer.ROLE_ADMIN) ROLE_ADMIN else ROLE_GUEST
            backend.handle(ApiRequest(method, path, PhoneBackend.parseQuery(query), body, role))
        } catch (e: Exception) {
            ApiResponse.json(500, mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"))
        }
        return Response.Builder()
            .request(Request.Builder().url("http://phone.local$pathAndQuery").build())
            .protocol(Protocol.HTTP_1_1)
            .code(res.status)
            .message(REASONS[res.status] ?: "OK")
            .header("Cache-Control", "no-store")
            // The gateway relays HEADERS (a body's media type alone never reaches the browser).
            .header("Content-Type", res.contentType)
            .apply { res.headers.forEach { (k, v) -> header(k, v) } }
            .body(res.body.toResponseBody(res.contentType.toMediaType()))
            .build()
    }

    private companion object {
        val REASONS = mapOf(200 to "OK", 400 to "Bad Request", 404 to "Not Found", 422 to "Unprocessable Entity",
            500 to "Internal Server Error")
    }
}
