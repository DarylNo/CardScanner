package io.github.darylno.cardscanner.core

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Port of mtg_card_scanner/ocr_id.py: the printing's identity read off the
 * card's own collector line ("087/254 · MH1 · EN"), matched against the
 * candidate printings with an OCR-confusion-tolerant comparator.
 *
 * PORT, never re-tune: [canon] and [matchPrinting] give the server's answers
 * for every row of `resources/ocr/expected.json` (made by the SERVER's code,
 * scripts/export_ocr_fixtures.py, `--check` in CI). The OCR ENGINE is the only
 * swapped part — RapidOCR on the rig, ML Kit on the phone ([OcrEngine]); its
 * text feeds [matchPrinting] exactly where RapidOCR's joined rows feed
 * `match_printing`.
 *
 * Candidates are the server's candidate JSON objects as plain Kotlin maps
 * (`id`, `set`, `collector_number`, …) — the shape `/api/scans` stores.
 */
object OcrMatch {
    /** `_CONFUSION`: two characters compare equal when they share a class. */
    val CONFUSION: Map<Char, Char> = mapOf(
        '1' to '1', 'I' to '1', 'L' to '1', '|' to '1',
        '0' to '0', 'O' to '0', 'Q' to '0', 'D' to '0',
        '5' to '5', 'S' to '5',
        '2' to '2', 'Z' to '2',
        '8' to '8', 'B' to '8',
        '6' to '6', 'G' to '6',
    )

    /**
     * Python's `str.isalnum()` for one code point: a letter (Lu/Ll/Lt/Lm/Lo)
     * or a number (Nd/Nl/No) — wider than Kotlin's isLetterOrDigit, which
     * drops Nl/No ('²', '½', 'Ⅷ').
     */
    private fun isAlnum(cp: Int): Boolean = Character.isLetter(cp) || when (Character.getType(cp)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    /** `_canon`: uppercase, alphanumeric-only, confusion classes collapsed. */
    fun canon(s: String): String {
        val up = s.uppercase()            // full case mapping, like str.upper() ('ß' → "SS")
        val out = StringBuilder(up.length)
        var i = 0
        while (i < up.length) {
            val cp = up.codePointAt(i)
            i += Character.charCount(cp)
            if (!isAlnum(cp)) continue
            val mapped = if (cp < 0x10000) CONFUSION[cp.toChar()] else null
            if (mapped != null) out.append(mapped) else out.appendCodePoint(cp)
        }
        return out.toString()
    }

    /**
     * `canon_words`: the text as canonical WORDS, single-space separated —
     * every run of alphanumerics is one word (as [canon]), anything else
     * separates. A set code only counts as a WHOLE word: run together,
     * letters met across words and forged one (an AVR Emancipation Angel,
     * which prints no set code, "confirmed" as UMA #15, 2026-09-29).
     */
    fun canonWords(s: String): String {
        val up = s.uppercase()
        val words = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < up.length) {
            val cp = up.codePointAt(i)
            i += Character.charCount(cp)
            if (isAlnum(cp)) {
                val mapped = if (cp < 0x10000) CONFUSION[cp.toChar()] else null
                if (mapped != null) cur.append(mapped) else cur.appendCodePoint(cp)
            } else if (cur.isNotEmpty()) {
                words += cur.toString(); cur.setLength(0)
            }
        }
        if (cur.isNotEmpty()) words += cur.toString()
        return words.joinToString(" ")
    }

    /**
     * `_LANG_TAGS`: language tags a reader may glue onto the set code when it
     * drops the "•" ("UMA•EN" → "UMAEN"; "FN" is the rig's measured misread).
     */
    val LANG_TAGS: Set<String> = listOf(
        "EN", "FN", "FR", "DE", "IT", "ES", "PT", "JA", "JP", "KO", "RU", "ZH",
        "CS", "CT", "PH").map { canon(it) }.toSet()

    /** `c.get(key, "")` then `str(...)` — absent → "". */
    private fun field(c: Map<String, Any?>, key: String): String =
        if (c.containsKey(key)) c[key].toString() else ""


    /**
     * `match_printing`: the id of the ONE candidate the OCR [blob] names, or
     * null when zero or several match (ambiguity never guesses).
     *
     *  1. a compound collector number ("A25-85", "2019-2"; canonical ≥4
     *     chars) found in the blob wins outright when exactly one candidate
     *     has it — a List copy prints its ORIGINAL set's code;
     *  2. else a UNIQUE set-code hit — the code as a WHOLE word, or glued to
     *     a language tag ([LANG_TAGS]); never the start of another word
     *     (artists MSCHF / Milivoj read as MSC… / M11…);
     *  3. else, among several set hits, a unique collector-number hit.
     * Compound and collector numbers compare against the words run together
     * (the reader splits them at "/" and "-").
     */
    fun matchPrinting(blob: String, candidates: List<Map<String, Any?>>): String? {
        val words = canonWords(blob).split(' ').filter { it.isNotEmpty() }   // idempotent
        if (words.isEmpty()) return null
        val b = words.joinToString("")

        val compound = candidates.filter { c ->
            val cn = field(c, "collector_number")
            '-' in cn && canon(cn).let { it.length >= 4 && b.contains(it) }
        }
        if (compound.size == 1) return compound[0]["id"]?.toString()

        val hits = candidates.filter { c ->
            val code = canon(field(c, "set"))
            code.length >= 2 && words.any {
                it == code || (it.startsWith(code) && it.substring(code.length) in LANG_TAGS)
            }
        }
        if (hits.size == 1) return hits[0]["id"]?.toString()

        if (hits.size > 1) {
            val collHits = hits.filter { c ->
                canon(field(c, "collector_number")).let { it.length >= 2 && b.contains(it) }
            }
            if (collHits.size == 1) return collHits[0]["id"]?.toString()
        }
        return null
    }
}

/**
 * The on-device text recogniser behind the collector-line read. Returns
 * whatever text it found in [bgr] (any spacing/case — [OcrStrip.readBottomStrip]
 * canonicalises), "" for nothing. Implementations: ML Kit in the app.
 */
fun interface OcrEngine {
    fun read(bgr: Mat): String
}

/**
 * `read_bottom_strip`'s image side: the bottom-left strip crop of the flattened
 * card and the two preprocessing variants (measured on the rig: colour upscale
 * reads the digits best, Otsu binarisation the set-code line), exact ports of
 * the cv2 calls.
 */
object OcrStrip {
    /** `_STRIP_Y` — bottom strip of the 630×880 warp. */
    val STRIP_Y = doubleArrayOf(0.88, 0.99)
    /** `_STRIP_X` — collector + set-code lines live bottom-left. */
    val STRIP_X = doubleArrayOf(0.02, 0.60)

    /** Python's `int(h * f)` bounds: [y0, y1, x0, x1] of the strip in a [w]×[h] card. */
    fun stripBounds(w: Int, h: Int): IntArray = intArrayOf(
        (h * STRIP_Y[0]).toInt(), (h * STRIP_Y[1]).toInt(),
        (w * STRIP_X[0]).toInt(), (w * STRIP_X[1]).toInt())

    /** `card_bgr[y0:y1, x0:x1]` (a view; empty extents clamp like numpy). */
    fun strip(card: Mat): Mat {
        val (y0, y1, x0, x1) = stripBounds(card.cols(), card.rows()).toList()
        return card.submat(Rect(x0, y0, maxOf(0, x1 - x0), maxOf(0, y1 - y0)))
    }

    /**
     * The two images the engine reads, in `read_bottom_strip`'s order:
     *  0. the colour strip, ×2.5 INTER_CUBIC;
     *  1. grey → Otsu binary, ×4 INTER_CUBIC.
     * The caller releases them.
     */
    fun variants(card: Mat): List<Mat> {
        val s = strip(card)
        val gray = Mat()
        val otsu = Mat()
        val v0 = Mat()
        val v1 = Mat()
        try {
            Imgproc.cvtColor(s, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.threshold(gray, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.resize(s, v0, Size(), 2.5, 2.5, Imgproc.INTER_CUBIC)
            Imgproc.resize(otsu, v1, Size(), 4.0, 4.0, Imgproc.INTER_CUBIC)
        } finally {
            s.release(); gray.release(); otsu.release()
        }
        return listOf(v0, v1)
    }

    /**
     * `read_bottom_strip`: run [engine] on both variants, union the text into
     * canonical words ([OcrMatch.canonWords]). Any failure reads as "" — a broken engine must never
     * break a scan (it just leaves the art ranking untouched).
     */
    fun readBottomStrip(card: Mat, engine: OcrEngine): String {
        var vs: List<Mat> = emptyList()
        return try {
            vs = variants(card)
            OcrMatch.canonWords(vs.joinToString(" ") { engine.read(it) })
        } catch (e: Exception) {
            ""
        } finally {
            vs.forEach { it.release() }
        }
    }
}
