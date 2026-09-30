package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.RoiFrac

/**
 * The scanner phone's own settings, from the browser (owner request
 * 2026-09-30: "what settings can we hit from the browser"). Phone server
 * only — the computer's server has no `/api/device`, so the desktop page
 * hides the panel there (a 404). Admin only ([ScanServer.adminOnly]).
 *
 *   GET   /api/device               → [state]
 *   PATCH /api/device {…}           → apply what's given, answer the new state
 *                                     (`mode`: "tray" | "tap")
 *   GET   /api/device/snapshot.jpg  → the camera's current upright frame, the
 *                                     space the scan Area fractions live in
 *
 * `roi` is the scan Area as fractions of the upright frame
 * `{"x0","y0","x1","y1"}` (the app's [RoiFrac]; each side ≥ its minimum), or
 * null for the full frame.
 */
class DeviceApi(private val device: DeviceControl) {

    /** What the phone applies; the app backs it with its settings + the live camera screen. */
    interface DeviceControl {
        fun read(): DeviceState
        /** Store [s] and apply it to the live camera screen if one is open. */
        fun write(s: DeviceState)
        /** The current upright camera frame as JPEG, or null when the scan screen isn't open. */
        fun snapshot(): ByteArray?
        /** True while the scan screen (the camera) is open. */
        fun cameraLive(): Boolean
    }

    /** [auto] = the mode: true = "tray" (hands-free), false = "tap" (tap to scan). */
    data class DeviceState(
        val auto: Boolean, val roi: RoiFrac?, val torch: Boolean,
        val vibration: Boolean, val highRes: Boolean, val aeLock: Boolean,
        /** How long the blue ✓ stays up after each scan, in ms ([CHECK_MS_MIN]..[CHECK_MS_MAX]). */
        val checkMs: Int = CHECK_MS_DEFAULT,
    )

    companion object {
        const val MODE_TRAY = "tray"
        const val MODE_TAP = "tap"
        const val CHECK_MS_DEFAULT = 2_500
        const val CHECK_MS_MIN = 250
        const val CHECK_MS_MAX = 10_000
    }

    fun handle(req: ApiRequest): ApiResponse? = when {
        req.path == "/api/device" && req.method == "GET" -> ApiResponse.json(200, state())
        req.path == "/api/device" && req.method == "PATCH" -> patch(req)
        req.path == "/api/device/snapshot.jpg" && req.method == "GET" ->
            device.snapshot()?.let { ApiResponse(200, "image/jpeg", it) }
                ?: ApiResponse.json(503, mapOf("error" to "the camera isn't running — open the scan screen on the phone"))
        else -> null
    }

    fun state(): Map<String, Any?> {
        val s = device.read()
        return linkedMapOf(
            "mode" to if (s.auto) MODE_TRAY else MODE_TAP,
            "roi" to s.roi?.let { linkedMapOf("x0" to it.x0, "y0" to it.y0, "x1" to it.x1, "y1" to it.y1) },
            "torch" to s.torch, "vibration" to s.vibration, "high_res" to s.highRes,
            "ae_lock" to s.aeLock, "check_ms" to s.checkMs,
            "camera_live" to device.cameraLive(),
        )
    }

    private fun patch(req: ApiRequest): ApiResponse {
        val body = req.body?.let {
            try { MiniJson.parse(String(it, Charsets.UTF_8)) as? Map<*, *> } catch (e: MiniJson.ParseException) { null }
        } ?: return ApiResponse.json(422, mapOf("detail" to "body must be a JSON object"))
        var s = device.read()
        for ((k, v) in body) {
            s = when (k) {
                "mode" -> when (v) {
                    MODE_TRAY -> s.copy(auto = true)
                    MODE_TAP -> s.copy(auto = false)
                    else -> return bad("mode must be \"$MODE_TRAY\" or \"$MODE_TAP\"")
                }
                "torch" -> s.copy(torch = v as? Boolean ?: return bad("torch must be true or false"))
                "vibration" -> s.copy(vibration = v as? Boolean ?: return bad("vibration must be true or false"))
                "high_res" -> s.copy(highRes = v as? Boolean ?: return bad("high_res must be true or false"))
                "ae_lock" -> s.copy(aeLock = v as? Boolean ?: return bad("ae_lock must be true or false"))
                "check_ms" -> s.copy(checkMs = (v as? Number)?.toDouble()
                    ?.takeIf { it == Math.floor(it) && it >= CHECK_MS_MIN && it <= CHECK_MS_MAX }?.toInt()
                    ?: return bad("check_ms must be a whole number of ms from $CHECK_MS_MIN to $CHECK_MS_MAX"))
                "roi" -> s.copy(roi = if (v == null) null else roi(v) ?: return bad(
                    "roi must be null or {x0,y0,x1,y1} fractions of the frame, each side at least ${RoiFrac.MIN_SIDE}"))
                else -> return bad("unknown setting: $k")
            }
        }
        device.write(s)
        return ApiResponse.json(200, state())
    }

    private fun roi(v: Any?): RoiFrac? {
        val m = v as? Map<*, *> ?: return null
        val n = listOf("x0", "y0", "x1", "y1").map { (m[it] as? Number)?.toDouble() ?: return null }
        return try { RoiFrac(n[0], n[1], n[2], n[3]) } catch (e: IllegalArgumentException) { null }
    }

    private fun bad(msg: String) = ApiResponse.json(400, mapOf("error" to msg))
}
