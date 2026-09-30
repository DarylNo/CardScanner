package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.ArtworkSplit
import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.Py

/** One HTTP request, transport-free (the phone's NanoHTTPD server and the JVM tests both build these). */
class ApiRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    /** [ROLE_ADMIN] or [ROLE_GUEST] — set by the gateway from the session, never by the client. */
    val role: String = ROLE_ADMIN,
)

const val ROLE_ADMIN = "admin"
const val ROLE_GUEST = "guest"

class ApiResponse(val status: Int, val contentType: String, val body: ByteArray,
                  val headers: Map<String, String> = emptyMap()) {
    companion object {
        fun json(status: Int, value: Any?) =
            ApiResponse(status, "application/json", MiniJson.stringify(value).toByteArray(Charsets.UTF_8))
    }
}

/** Where scan photos live (`scan_images/<id>.jpg` on the rig). */
interface ScanImages {
    fun read(id: Long): ByteArray?
    fun write(id: Long, jpeg: ByteArray)
    fun delete(id: Long)
}

/**
 * Port of server/app.py's scan endpoints (Stage 3b): the JSON the review pages
 * (phone.html / desktop.html) read and write, answered from the phone's own
 * [ScanStore]. PORT, never re-tune: `PhoneApiParityTest` replays the golden
 * session scripts/export_api_fixtures.py records from the REAL server.
 *
 *  GET    /api/scans              newest first, other_art derived per read
 *  GET    /api/scans/{id}         one row, other_art derived; 404 {"error":"not found"}
 *  GET    /api/scans/{id}/image   the scan photo (404 {"error":"no image"})
 *  POST   /api/scans/{id}/select  pick a printing
 *  PATCH  /api/scans/{id}         included / condition / finish / quantity
 *  DELETE /api/scans/{id}         row + photo
 *  POST   /api/scans/delete-all   {"only":"unselected"} keeps picked rows; "flagged" = only flagged ones
 *
 * and [fileScan], `/api/scan`'s filing once the phone has identified a capture.
 *
 * The phone is ALWAYS the one F2F consumer (CLAUDE.md "Pricing sweep"): a
 * pick or a finish change never prices inline — it leaves f2f cleared for the
 * sweep, which is the Python server's sweep-active path.
 *
 * [handle] returns null for any path it doesn't own, so the HTTP layer can
 * serve pages / other endpoints.
 */
class PhoneApi(val store: ScanStore, private val images: ScanImages) {
    /** `select_lock`: every selection read-modify-write — picks, edits, the sweep's writes, retro picks. */
    val selectLock = Any()

    fun handle(req: ApiRequest): ApiResponse? {
        val p = req.path
        if (p == "/api/me") return if (req.method == "GET") ApiResponse.json(200, mapOf("role" to req.role)) else null
        if (p == "/api/scans") return if (req.method == "GET") listScans() else null
        if (p == "/api/scans/delete-all") return if (req.method == "POST") deleteAll(req) else null
        val m = SCAN_PATH.matchEntire(p) ?: return null
        val id = m.groupValues[1].toLongOrNull() ?: return notFound()
        return when (m.groupValues[2] to req.method) {
            "" to "GET" -> getScan(id)
            "" to "PATCH" -> patchScan(id, req)
            "" to "DELETE" -> deleteScan(id)
            "/select" to "POST" -> select(id, req)
            "/image" to "GET" -> images.read(id)?.let { ApiResponse(200, "image/jpeg", it) }
                ?: ApiResponse.json(404, mapOf("error" to "no image"))
            else -> null
        }
    }

    private fun listScans() = ApiResponse.json(200, store.list().map { ArtworkSplit.flagOtherArt(it) })

    private fun getScan(id: Long): ApiResponse {
        val scan = store.get(id) ?: return notFound()
        return ApiResponse.json(200, ArtworkSplit.flagOtherArt(scan))
    }

    private fun select(id: Long, req: ApiRequest): ApiResponse {
        val body = bodyObject(req) ?: return unprocessable()
        val scan = store.get(id) ?: return notFound()
        val printingRaw = Py.get(body, "printing")
        val printing: Map<String, Any?> = if (Py.truthy(printingRaw)) Py.map(printingRaw)
            ?: return serverError() else emptyMap()
        if (!Py.truthy(Py.get(printing, "set")) || !Py.truthy(Py.get(printing, "collector_number"))) {
            return ApiResponse.json(400, mapOf("error" to "printing needs set + collector_number"))
        }
        val cardRead = Py.map(scan["card_read"]) ?: emptyMap()
        val condition = Py.str(or(Py.get(body, "condition"), Py.get(cardRead, "condition_estimate"), "NM")).uppercase()
        val finish = or(Py.get(body, "finish"), "Non-Foil")
        val quantity = maxOf(1L, pyInt(or(Py.get(body, "quantity"), 1L)) ?: return serverError())
        return ApiResponse.json(200, applySelection(id, printing, condition, finish, quantity))
    }

    /** `_apply_selection_core(…, "NM", "Non-Foil", 1, auto=True)` — the retro auto-pick. */
    fun autoPick(id: Long, printing: Map<String, Any?>): MutableMap<String, Any?>? =
        applySelection(id, printing, "NM", "Non-Foil", 1L, auto = true)

    /** `_apply_selection_core`: pick [printing]; auto picks carry `auto_picked`. */
    private fun applySelection(id: Long, printing: Map<String, Any?>, condition: String, finish: Any?,
                               quantity: Long, auto: Boolean = false): MutableMap<String, Any?>? {
        val selection = linkedMapOf<String, Any?>(
            "scryfall_id" to Py.get(printing, "id", ""),
            "name" to Py.get(printing, "name", ""),
            "set" to Py.get(printing, "set", ""),
            "set_name" to Py.get(printing, "set_name", ""),
            "collector_number" to Py.get(printing, "collector_number", ""),
            "condition" to condition,
            "finish" to finish,
            "quantity" to quantity,
            "foil" to foilFromFinish(finish),
            "image_normal" to Py.get(printing, "image_normal", ""),
            "popularity" to Py.get(printing, "popularity"),
        )
        if (auto) selection["auto_picked"] = true
        return synchronized(selectLock) {
            // error=None: a best-guess scan the user then picks leaves the Problems view.
            store.update(id, linkedMapOf("status" to "selected", "selection" to selection,
                "error" to null, "f2f" to null))
        }
    }

    private fun patchScan(id: Long, req: ApiRequest): ApiResponse {
        val body = bodyObject(req) ?: return unprocessable()
        var scan: MutableMap<String, Any?>?
        val selection: MutableMap<String, Any?>
        var changed = false
        synchronized(selectLock) {
            scan = store.get(id) ?: return notFound()
            val fields = LinkedHashMap<String, Any?>()
            if (body.containsKey("included")) fields["included"] = Py.truthy(body["included"])
            if (body.containsKey("flagged")) fields["flagged"] = Py.truthy(body["flagged"])
            selection = LinkedHashMap(Py.map(scan!!["selection"]) ?: emptyMap())
            // Only an ALREADY-selected scan has a selection to edit: never invent one.
            for (key in listOf("condition", "finish", "quantity")) {
                if (body.containsKey(key) && selection.isNotEmpty()) {
                    selection[key] = body[key]
                    changed = true
                }
            }
            if (changed && selection.isNotEmpty()) {
                selection["condition"] = Py.str(Py.get(selection, "condition", "NM")).uppercase()
                selection["quantity"] = maxOf(1L, pyInt(or(Py.get(selection, "quantity"), 1L)) ?: return serverError())
                selection["foil"] = foilFromFinish(Py.get(selection, "finish", "Non-Foil"))
                fields["selection"] = selection
            }
            if (fields.isNotEmpty()) scan = store.update(id, fields)
        }
        if (changed && selection.isNotEmpty() && body.containsKey("finish")) {
            // The phone is the one F2F consumer: clear the now-wrong-finish price for the sweep.
            return ApiResponse.json(200, store.update(id, mapOf("f2f" to null)))
        }
        return ApiResponse.json(200, scan)
    }

    /**
     * `_scan_once` after identification: file [result] (the pipeline's
     * scan_candidates answer) and return what `/api/scan` answers.
     *  - no_card → nothing stored: {"no_card":true,"identified":false,"error":…};
     *  - a retry ([replaceScanId] > 0) replaces the old row only while it is
     *    still unpicked (a pick made meanwhile is kept);
     *  - the photo is kept for review (a save failure never breaks the scan);
     *  - auto-pick NM / Non-Foil / ×1 (auto_picked) when there is exactly one
     *    printing or the top one is OCR-confirmed or art-decisive.
     * Pricing is the sweep's job (the phone is the one F2F consumer).
     */
    fun fileScan(result: Map<String, Any?>, photo: ByteArray?, replaceScanId: Long = 0): Map<String, Any?> {
        if (Py.truthy(Py.get(result, "no_card"))) {
            return linkedMapOf("no_card" to true, "identified" to false,
                "error" to Py.get(result, "error", "No card detected."))
        }
        if (replaceScanId != 0L) {
            val old = store.get(replaceScanId)
            if (old != null && old["status"] != "selected") {
                images.delete(replaceScanId)
                store.delete(replaceScanId)
            }
        }
        val identified = Py.truthy(result["identified"])
        var scan = store.create(identified, Py.map(result["card_read"]), Py.map(result["confidence"]),
            result["candidates"] as List<Any?>?, Py.get(result, "error") as String?)
        val id = (scan["id"] as Number).toLong()
        if (photo != null) runCatching { images.write(id, photo) }
        @Suppress("UNCHECKED_CAST")
        val cands = (result["candidates"] as? List<Map<String, Any?>>).orEmpty()
        if (identified && cands.isNotEmpty() && (cands.size == 1 ||
                Py.truthy(Py.get(cands[0], "ocr_confirmed")) || Py.truthy(Py.get(cands[0], "art_decisive")))) {
            scan = applySelection(id, cands[0], "NM", "Non-Foil", 1L, auto = true) ?: scan
        }
        return scan
    }

    private fun deleteScan(id: Long): ApiResponse {
        images.delete(id)
        return ApiResponse.json(200, mapOf("deleted" to store.delete(id)))
    }

    private fun deleteAll(req: ApiRequest): ApiResponse {
        val body = if (req.body == null || req.body.isEmpty()) emptyMap() else bodyObject(req) ?: return unprocessable()
        val only = (if (Py.truthy(Py.get(body, "only"))) Py.str(body["only"]) else "").lowercase()
        val targets = if (only == "flagged") store.list().filter { it["flagged"] == true }
            else store.list().filter { only != "unselected" || it["status"] != "selected" }
        for (s in targets) {
            val id = (s["id"] as Number).toLong()
            images.delete(id)
            store.delete(id)
        }
        return ApiResponse.json(200, mapOf("deleted" to targets.size.toLong()))
    }

    private fun notFound() = ApiResponse.json(404, mapOf("error" to "not found"))
    private fun unprocessable() = ApiResponse.json(422, mapOf("detail" to "body must be a JSON object"))
    private fun serverError() = ApiResponse.json(500, mapOf("error" to "Internal Server Error"))

    /** The request body as a JSON object (FastAPI `body: dict = Body(...)`), null when it isn't one. */
    private fun bodyObject(req: ApiRequest): Map<String, Any?>? {
        val raw = req.body ?: return null
        return try { Py.map(MiniJson.parse(String(raw, Charsets.UTF_8))) } catch (e: MiniJson.ParseException) { null }
    }

    companion object {
        private val SCAN_PATH = Regex("/api/scans/([^/]+)(/select|/image)?")

        /** `_foil_from_finish`. */
        fun foilFromFinish(finish: Any?): Boolean {
            val s = if (Py.truthy(finish)) Py.str(finish) else ""
            return s.trim { it.isWhitespace() }.lowercase() !in setOf("non-foil", "nonfoil", "")
        }

        /** Python `a or b or …`: the first truthy value, else the last. */
        private fun or(vararg vs: Any?): Any? = vs.firstOrNull { Py.truthy(it) } ?: vs.last()

        /** Python `int(v)` for what a JSON body can carry; null where Python raises. */
        internal fun pyInt(v: Any?): Long? = when (v) {
            is String -> v.trim { it.isWhitespace() }.replace("_", "").toLongOrNull()
            null -> null
            else -> if (Py.isNumber(v)) Py.toInt(v) else null
        }
    }
}
