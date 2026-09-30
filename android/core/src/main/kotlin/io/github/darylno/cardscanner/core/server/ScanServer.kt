package io.github.darylno.cardscanner.core.server

/**
 * The phone server's API router: pricing ([PriceSweep]) in front of the scan
 * endpoints ([PhoneApi]) — `/api/scans/price-missing` must never be read as a
 * scan id. Returns null for paths neither owns (pages, startup reads).
 */
class ScanServer(val api: PhoneApi, val sweep: PriceSweep) {
    fun handle(req: ApiRequest): ApiResponse? {
        val p = req.path
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

    private companion object {
        val PRICE_PATH = Regex("/api/scans/([^/]+)/(price|price-check)")
    }
}
