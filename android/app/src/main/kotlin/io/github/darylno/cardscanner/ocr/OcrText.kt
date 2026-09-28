package io.github.darylno.cardscanner.ocr

/**
 * The glue between a recogniser's structured result and the matcher — kept
 * free of ML Kit types so it is unit-tested on the plain JVM.
 *
 * RapidOCR (the rig) returns one row per detected text line and
 * `read_bottom_strip` joins `row[1]` with " ". ML Kit returns blocks of
 * lines; flattening them in ML Kit's reading order (block, then line) and
 * joining with " " gives the same kind of string, which OcrStrip then joins
 * across the two variants and canonicalises exactly like the server.
 * Spaces/case/punctuation never matter downstream (`_canon` drops them);
 * only the ORDER of the lines can, and only for a token split across lines.
 */
object OcrText {
    /** [blocks] = per block, its lines' text in order. */
    fun joinLines(blocks: List<List<String>>): String = blocks.flatten().joinToString(" ")
}
