package io.github.darylno.cardscanner.ui

import org.json.JSONObject

/**
 * Status-line wording, mirrored from server/static/phone.html (`submitScan`,
 * `tick`, `popTag`) so an operator who knows the browser page reads the same
 * sentences here. Pure functions over the `/api/scan` JSON.
 */
object StatusText {
    const val WAITING = "Tray: waiting for a steady view…"
    const val WATCHING = "Tray: watching for a card…"
    const val HOLD_STILL = "Card detected — hold still…"
    const val NO_SHAPE = "Something in the Area — no card shape yet…"
    const val NEW_CARD = "New card…"
    const val AUTO_OFF = "Tap to scan — card in the box, tap the shutter."
    const val CAPTURING = "Capturing…"
    const val SCANNING = "Scanning…"
    const val NO_CARD = "No card detected."
    const val QUEUED_OPEN = "Queued — the scan opens once it's identified"
    const val AREA_DRAG = "Drag a box around the tray…"
    /** Drawing an Area while zoomed: the camera goes back to the whole frame first. */
    const val AREA_ZOOMING_OUT = "Zooming out to the whole frame…"
    const val AREA_DRAG_TRAY = "Tray: draw a box around where the card will sit (Cancel keeps the current area)."
    const val AREA_DEFAULT = "Scan area drawn for you — put the card in the box, or tap Area to draw your own."
    const val AREA_UNCHANGED = "Scan area unchanged."
    const val AREA_TOO_SMALL = "Area too small — tap Area and drag again."
    const val AREA_CLEARED = "Scan area cleared — full frame. Empty the tray to re-learn…"
    const val AREA_SET = "Scan area set. Empty the tray to re-learn…"
    const val RELEARN = "Re-learning the empty tray — keep it clear…"

    private val POP_CALLOUT = mapOf("staple" to "★ staple", "strong" to "very popular", "solid" to "popular")

    /** phone.html popOf(): the SELECTION's popularity first, else candidates[0]'s. */
    fun popTag(scan: JSONObject): String {
        val sel = scan.optJSONObject("selection")?.optJSONObject("popularity")
        val cand = scan.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("popularity")
        val p = sel ?: cand ?: return ""
        val tag = POP_CALLOUT[p.optString("tier")] ?: return ""
        return " · $tag"
    }

    fun name(scan: JSONObject): String =
        scan.optJSONObject("card_read")?.optString("name")?.takeIf { it.isNotEmpty() } ?: "?"

    fun autoPicked(scan: JSONObject): Boolean =
        scan.optJSONObject("selection")?.optBoolean("auto_picked", false) == true

    /** ✓ identified (phone.html submitScan's `data.identified` branch). */
    fun identified(scan: JSONObject, manual: Boolean): String =
        if (autoPicked(scan)) "✓ ${name(scan)}${popTag(scan)} — exact printing confident, auto-filed ⚠"
        else "✓ ${name(scan)}${popTag(scan)}" + if (manual) " — pick a printing." else " — filed for desktop pick. Next card…"

    fun bestGuess(scan: JSONObject, manual: Boolean): String =
        "? Best guess ${name(scan)}${popTag(scan)}" + if (manual) " — verify & pick." else " — verify on desktop."

    fun noMatch(scan: JSONObject): String =
        "✗ " + (scan.optString("error").takeIf { it.isNotEmpty() && it != "null" } ?: "No match.") +
            " — Retry with a fresh capture?"

    fun opening(scan: JSONObject): String = "✓ ${name(scan)}${popTag(scan)} — opening…"

    /** Queue indicator: "2 identifying…" / "3 waiting (no connection?) — retrying in 8s". */
    fun queue(pending: Int, lastError: String?, nextRetryInMs: Long?): String = when {
        pending <= 0 -> ""
        // Stage 4: the queue's "upload" is the phone identifying + filing the card itself.
        lastError != null && lastError.contains("card database", ignoreCase = true) ->
            "$pending waiting for the card database"
        lastError != null && nextRetryInMs != null ->
            "$pending waiting (no connection?) — retrying in ${(nextRetryInMs + 999) / 1000}s"
        lastError != null -> "$pending waiting (no connection?)"
        else -> "$pending identifying…"
    }
}
