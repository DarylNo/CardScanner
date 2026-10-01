package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson

/**
 * The phone server's API router: pricing ([PriceSweep]) in front of the scan
 * endpoints ([PhoneApi]) — `/api/scans/price-missing` must never be read as a
 * scan id — and the exports ([Export], layout in [layouts]). Returns null for
 * paths none of them owns (pages, startup reads).
 */
class ScanServer(val api: PhoneApi, val sweep: PriceSweep, val layouts: LayoutStore = LayoutStore(null)) {
    fun handle(req: ApiRequest): ApiResponse? {
        val p = req.path
        if (req.role != ROLE_ADMIN && adminOnly(req)) {
            return ApiResponse.json(403, mapOf("error" to "only the scanner's owner can do that — flag it for deletion instead"))
        }
        if (p == "/api/export" || p.startsWith("/api/export/") || p == "/api/export.csv") {
            return Export.handle(req, api.store, layouts)
        }
        when {
            p == "/api/price-status" && req.method == "GET" -> return ApiResponse.json(200, sweep.status())
            p == "/api/price-sweep/stop" && req.method == "POST" -> return ApiResponse.json(200, sweep.stop())
            p == "/api/scans/price-missing" && req.method == "POST" -> return ApiResponse.json(200, sweep.priceMissing())
        }
        PRICE_PATH.matchEntire(p)?.let { m ->
            if (req.method != "POST") return null
            val id = m.groupValues[1].toLongOrNull() ?: return ApiResponse.json(404, mapOf("error" to "not found"))
            val (status, body) = if (m.groupValues[2] == "price") sweep.priceNow(id) else sweep.priceCheck(id)
            return ApiResponse.json(status, body)
        }
        return api.handle(req)
    }

    companion object {
        /**
         * What a guest may NOT do: delete a scan,
         * clear the list, export (TXT, CSV, the saved layout), include/exclude a
         * card from the export (PATCH "included"), run/stop the pricing sweep, or
         * see/change the phone's own settings ([DeviceApi]).
         * Guests browse, search, pick, edit condition/finish/qty, price one scan,
         * and flag scans for deletion (PATCH {"flagged": true}).
         */
        fun adminOnly(req: ApiRequest): Boolean {
            val p = req.path
            return (req.method == "DELETE" && SCAN_ID.matches(p)) ||
                (req.method == "PATCH" && SCAN_ID.matches(p) && patchesIncluded(req)) ||
                p == "/api/scans/delete-all" ||
                p == "/api/export" || p == "/api/export.csv" || p.startsWith("/api/export/") ||
                p == "/api/price-sweep/stop" || p == "/api/scans/price-missing" ||
                p == "/api/device" || p.startsWith("/api/device/") ||       // the phone's own settings
                p.startsWith("/api/debug/")                                 // the live log + debug report
        }

        private val SCAN_ID = Regex("/api/scans/[^/]+")

        /** The PATCH body names "included" — or can't be read (then PhoneApi answers 422 anyway). */
        private fun patchesIncluded(req: ApiRequest): Boolean {
            val raw = req.body ?: return false
            val m = try { MiniJson.parse(String(raw, Charsets.UTF_8)) } catch (e: MiniJson.ParseException) { return true }
            return m !is Map<*, *> || m.containsKey("included")
        }
        private val PRICE_PATH = Regex("/api/scans/([^/]+)/(price|price-check)")
    }
}
