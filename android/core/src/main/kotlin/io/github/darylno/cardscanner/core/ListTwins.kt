package io.github.darylno.cardscanner.core

/**
 * Port of mtg_card_scanner/list_twins.py: The List (Scryfall set `plst`,
 * which also took in the old Mystery Booster `mb1`/`fmb1`) reprints EXACTLY
 * its original printing — same art, same frame, the same collector line
 * ("141/249 U" / "A25 • EN" on plst A25-141; never "PLST", never the
 * compound number), only a small planeswalker icon added. Scryfall's plst
 * collector number is the original's set code, a dash and the original's
 * number (JUD-78† and M19-185j append a "†" / "j"; CHK-160 and LCI-1 match
 * nothing of their name).
 *
 * Neither the collector-line OCR nor the art hash can tell such a pair apart,
 * so [OcrMatch.matchPrinting] and the art-decisive pick treat a twin pair as
 * ambiguous, and [withTwins] keeps both in the candidate list.
 *
 * PORT, never re-tune: `resources/ocr/expected.json`'s `list_twins` section
 * (every ordered pair of the real printings, hand shapes, rankings) is made by
 * the server's code (scripts/export_ocr_fixtures.py, `--check` in CI).
 */
object ListTwins {
    /** `LIST_SETS`. */
    val LIST_SETS: Set<String> = setOf("plst")

    /** What may follow the original's number in the List's: nothing, "†" or "j". */
    private val SUFFIXES = listOf("", "†", "j")

    /** `str(c.get(key, ""))`. */
    private fun field(c: Map<String, Any?>, key: String): String =
        if (c.containsKey(key)) Py.str(c[key]) else ""

    /** `is_list_printing`: a printing of a List set (any case). */
    fun isListPrinting(c: Map<String, Any?>): Boolean = field(c, "set").lowercase() in LIST_SETS

    /**
     * `is_list_twin`: List printing [t] reproduces printing [x] — [t]'s
     * collector number split at its FIRST "-" is [x]'s set code
     * (case-insensitive) and [x]'s number exactly, or with "†"/"j" appended.
     * Exact strings, never the OCR canonicalisation.
     */
    fun isListTwin(t: Map<String, Any?>, x: Map<String, Any?>): Boolean {
        if (!isListPrinting(t) || isListPrinting(x)) return false
        val cn = field(t, "collector_number")
        val dash = cn.indexOf('-')
        if (dash < 0) return false
        val head = cn.substring(0, dash)
        val num = cn.substring(dash + 1)
        val xs = field(x, "set")
        val xn = field(x, "collector_number")
        if (head.isEmpty() || xs.isEmpty() || xn.isEmpty()) return false
        return head.uppercase() == xs.uppercase() && SUFFIXES.any { num == xn + it }
    }

    /** `twins`: either one is the other's List twin. */
    fun twins(a: Map<String, Any?>, b: Map<String, Any?>): Boolean = isListTwin(a, b) || isListTwin(b, a)

    /** `has_twin_among`: some candidate is [c]'s List twin. */
    fun hasTwinAmong(c: Map<String, Any?>, candidates: List<Map<String, Any?>>): Boolean =
        candidates.any { twins(c, it) }

    /**
     * `with_twins`: `ranked[:topN]`, each followed by its List twin partners
     * that ranked below the slice (in ranked order, each once). A partner in
     * the slice stays put; one not in [ranked] is not added. The result may
     * run past [topN]. The same element instances are returned.
     */
    fun <T : Map<String, Any?>> withTwins(ranked: List<T>, topN: Int): List<T> {
        val n = maxOf(0, minOf(topN, ranked.size))
        val taken = BooleanArray(ranked.size) { it < n }
        val out = ArrayList<T>(n + 2)
        for (i in 0 until n) {
            out += ranked[i]
            for (j in n until ranked.size) {
                if (!taken[j] && twins(ranked[i], ranked[j])) {
                    taken[j] = true
                    out += ranked[j]
                }
            }
        }
        return out
    }
}
