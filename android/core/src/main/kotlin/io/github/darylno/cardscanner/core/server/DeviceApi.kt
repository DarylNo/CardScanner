package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.RoiFrac
import io.github.darylno.cardscanner.core.ZoomCoordinator

/**
 * The scanner phone's own settings, from the browser (owner request
 * 2026-09-30: "what settings can we hit from the browser"). Phone server
 * only — the computer's server has no `/api/device`, so the desktop page
 * hides the panel there (a 404). Admin only ([ScanServer.adminOnly]).
 *
 *   GET   /api/device               → [state]
 *   PATCH /api/device {…}           → apply what's given, answer the new state
 *                                     (`mode`: "tray" | "tap"; `camera`: "auto"
 *                                     or an id from `cameras` [{id, label}])
 *   GET   /api/device/snapshot.jpg  → the camera's current upright frame, the
 *                                     space the scan Area fractions live in
 *
 * `roi` is the scan Area as fractions of the upright frame
 * `{"x0","y0","x1","y1"}` (the app's [RoiFrac]; each side ≥ its minimum), or
 * null for the full frame.
 *
 * "Zoom to fit the Area" (1.1.11): `zoom_fit` (true / false) is the switch;
 * `zoom` = `{ratio, target, limit, fit, lossless, max, settling, drawing}` while
 * the scan screen has its camera, else null (`drawing`: an Area is being drawn on
 * the phone, the camera zoomed out to 1× for it). `roi` stays BASE (1×) fractions at any zoom,
 * and the snapshot stays a base-space picture (the zoomed view on grey), so a
 * box drawn on it still maps 1:1.
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
        /** The cameras the Camera setting offers on this phone, as (id, label); empty = unknown. */
        fun cameras(): List<Pair<String, String>> = emptyList()
        /** The live zoom (the scan screen's camera), or null when it isn't open. */
        fun zoom(): ZoomCoordinator.State? = null
    }

    /** [auto] = the mode: true = "tray" (hands-free), false = "tap" (tap to scan). */
    data class DeviceState(
        val auto: Boolean, val roi: RoiFrac?, val torch: Boolean,
        val vibration: Boolean, val highRes: Boolean, val aeLock: Boolean,
        /** How long the blue ✓ stays up after each scan, in ms ([CHECK_MS_MIN]..[CHECK_MS_MAX]). */
        val checkMs: Int = CHECK_MS_DEFAULT,
        /** The camera that scans: a Camera2 id, or null = Automatic ([CAMERA_AUTO] on the wire). */
        val cameraId: String? = null,
        /** "Zoom to fit the Area" (off by default). */
        val zoomFit: Boolean = false,
    )

    companion object {
        const val MODE_TRAY = "tray"
        const val MODE_TAP = "tap"
        const val CAMERA_AUTO = "auto"
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
            "camera" to (s.cameraId ?: CAMERA_AUTO),
            "cameras" to device.cameras().map { (id, label) -> linkedMapOf("id" to id, "label" to label) },
            "camera_live" to device.cameraLive(),
            "zoom_fit" to s.zoomFit,
            "zoom" to device.zoom()?.let(::zoomJson),
        )
    }

    /** The live zoom for the desktop's Scanner panel: what is applied, what the Area asks for, what bound it. */
    private fun zoomJson(z: ZoomCoordinator.State): Map<String, Any?> {
        val t = z.target
        fun r2(v: Double?) = v?.takeIf { it.isFinite() }?.let { Math.round(it * 100) / 100.0 }
        return linkedMapOf(
            "ratio" to r2(z.ratio),
            "target" to r2(t.z),
            "limit" to (if (t.belowMin) "below ${"%.2f".format(java.util.Locale.ROOT, io.github.darylno.cardscanner.core.ZoomFit.MIN_USEFUL)}× (${t.limit.words})" else t.limit.words),
            "fit" to r2(t.fit),
            "lossless" to r2(t.lossless),
            "max" to r2(t.lensMax),
            "settling" to z.settling,
            // An Area being drawn on the phone: the camera is zoomed out to 1× for it (the panel says so).
            "drawing" to z.drawing,
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
                "zoom_fit" -> s.copy(zoomFit = v as? Boolean ?: return bad("zoom_fit must be true or false"))
                "check_ms" -> s.copy(checkMs = (v as? Number)?.toDouble()
                    ?.takeIf { it == Math.floor(it) && it >= CHECK_MS_MIN && it <= CHECK_MS_MAX }?.toInt()
                    ?: return bad("check_ms must be a whole number of ms from $CHECK_MS_MIN to $CHECK_MS_MAX"))
                "camera" -> s.copy(cameraId = when {
                    v == CAMERA_AUTO -> null
                    v is String && device.cameras().any { it.first == v } -> v
                    else -> return bad("camera must be \"$CAMERA_AUTO\" or one of this phone's cameras: " +
                        device.cameras().joinToString(", ") { it.first }.ifEmpty { "(none listed)" })
                })
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
