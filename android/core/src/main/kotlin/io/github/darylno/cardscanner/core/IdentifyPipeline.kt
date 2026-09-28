package io.github.darylno.cardscanner.core

import org.opencv.core.Mat
import java.util.concurrent.CancellationException

/**
 * `Pipeline.scan_candidates` end to end on the phone (Stage 2): the ported
 * pieces glued exactly where mtg_card_scanner/pipeline.py glues them —
 *
 *  - frames sorted by [Sharpness.frameSharpness];
 *  - blank-surface guard: [CardExtract.frameIsBlank] per frame;
 *  - `ArtIndex.identify`: [CardExtract.warpOrFrame] → [ArtMatcher.identify];
 *  - `_ranked_candidates`: [ScryfallPrintings.getAllPrintings] (via
 *    [printings]) → [PrintingRanker.rankPrintings] on the warped sharpest
 *    frame → [PrintingCandidates.rankedCandidates] (sort by multi, top N,
 *    print counts), whose OCR hint is `_apply_ocr_hint`:
 *    [CardExtract.extractCard], detected only → [OcrStrip.readBottomStrip]
 *    → [IdentifyDecisions.applyOcrHint]; then `art_decisive`.
 *
 * Every DECISION is [IdentifyDecisions.scanCandidates]'s, so the result map
 * has the server's /api/scan pipeline shape (identified, no_card, card_read,
 * confidence, candidates, error). Nothing here is tuned: this is wiring.
 *
 * Not thread-safe (one identification at a time — the ranker's hash memo is
 * shared across calls, as the server's is). [cancelled] is polled between
 * stages; a cancelled run throws [CancellationException].
 */
class IdentifyPipeline(
    private val matcher: ArtMatcher?,
    private val printings: (String) -> List<MutableMap<String, Any?>>,
    private val ranker: PrintingRanker,
    private val ocr: OcrEngine?,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    /** What one run produced. [timingsMs] keys: blank, identify, printings, ranking, ocr, total. */
    class Result(
        val json: MutableMap<String, Any?>,
        val timingsMs: LinkedHashMap<String, Long>,
        /** The canonical collector-line text OCR read (null = OCR never ran). */
        val ocrText: String?,
        /** Printings of the name the ranker could not hash (image fetch/decode failures). */
        val skippedPrintings: Int,
        /** How many paper printings Scryfall returned (null = never looked up). */
        val printingCount: Int?,
    ) {
        /** The server's scan-time auto-pick grounds, applied to this result. */
        val wouldAutoPick: Boolean get() = IdentifyDecisions.shouldAutoPick(json)
    }

    private class Clock(val now: () -> Long) {
        val acc = LinkedHashMap<String, Long>()
        inline fun <T> time(key: String, block: () -> T): T {
            val t0 = now()
            try {
                return block()
            } finally {
                acc[key] = (acc[key] ?: 0L) + (now() - t0)
            }
        }
    }

    /**
     * Identify the card in [frames] (8UC3 BGR, decoded as the server's upload
     * handler decodes them — `Imgcodecs.imdecode`). The caller keeps ownership
     * of [frames]. [cancelled] is polled between stages.
     */
    fun scan(frames: List<Mat>, topN: Int = PrintingCandidates.SCAN_TOP_N,
             cancelled: () -> Boolean = { false }): Result {
        require(frames.isNotEmpty()) { "no frames" }
        val clock = Clock(nanoClock)
        val t0 = nanoClock()
        var ocrText: String? = null
        var skipped = 0
        var printingCount: Int? = null
        fun checkCancel() { if (cancelled()) throw CancellationException("identification cancelled") }

        val json = IdentifyDecisions.scanCandidates(
            frames = frames,
            sharpness = { Sharpness.frameSharpness(it) },
            isBlank = { f -> checkCancel(); clock.time("blank") { CardExtract.frameIsBlank(f) } },
            identify = matcher?.let { m ->
                { f: Mat ->
                    checkCancel()
                    clock.time("identify") {
                        val w = CardExtract.warpOrFrame(f)
                        try { m.identify(w.card, topN = 5) } finally { w.card.release() }
                    }
                }
            },
            ranked = { frame, name, n ->
                checkCancel()
                val all = clock.time("printings") { printings(name) }
                printingCount = all.size
                checkCancel()
                val ranking = clock.time("ranking") {
                    val w = CardExtract.warpOrFrame(frame)
                    try {
                        ranker.rankPrintings(w.card, all.map { rankerPrinting(it) })
                    } finally { w.card.release() }
                }
                skipped = ranking.skipped.size
                val rankedMaps = ranking.ranked.map { rankedMap(it) }
                PrintingCandidates.rankedCandidates(all, rankedMaps, n) { cands ->
                    // _apply_ocr_hint: fewer than two → untouched; no quad → no read.
                    if (cands.size < 2) cands
                    else {
                        checkCancel()
                        clock.time("ocr") {
                            // The server's blanket `except Exception: return candidates`.
                            try {
                                val e = CardExtract.extractCard(frame)
                                try {
                                    if (!e.detected || ocr == null) cands
                                    else {
                                        val text = OcrStrip.readBottomStrip(e.card, ocr)
                                        ocrText = text
                                        @Suppress("UNCHECKED_CAST")
                                        IdentifyDecisions.applyOcrHint(cands, text, e.detected)
                                            as List<LinkedHashMap<String, Any?>>
                                    }
                                } finally { e.card.release() }
                            } catch (c: CancellationException) {
                                throw c
                            } catch (_: Exception) {
                                cands
                            }
                        }
                    }
                }
            },
            topN = topN,
        )
        // scanCandidates swallows exceptions from its stages (as the server does);
        // a cancellation must not come back looking like a real answer.
        checkCancel()
        clock.acc["total"] = nanoClock() - t0
        val ms = LinkedHashMap<String, Long>()
        for (k in listOf("blank", "identify", "printings", "ranking", "ocr", "total")) {
            ms[k] = (clock.acc[k] ?: 0L) / 1_000_000L
        }
        return Result(json, ms, ocrText, skipped, printingCount)
    }

    companion object {
        /** Python's `str(v)` for a JSON scalar the ranker reads (absent/None → ""). */
        private fun s(v: Any?): String = when (v) {
            null -> ""
            is String -> v
            else -> Py.str(v)
        }

        /**
         * A Scryfall printing map as visual_match reads it: `p.get("id", "")`,
         * `p.get("set")`, `(image_uris or {}).get(small|normal|large)`,
         * `bool(p.get("promo"))`, … [Printing.payload] keeps the map so the
         * ranked result can be `{**p, …}`.
         */
        fun rankerPrinting(p: Map<String, Any?>): Printing {
            val uris = Py.map(p["image_uris"]).takeIf { Py.truthy(it) } ?: emptyMap()
            fun uri(k: String): String? = uris[k]?.let { s(it) }
            return Printing(
                id = s(p["id"]),
                set = if (p.containsKey("set")) s(p["set"]) else null,
                collectorNumber = s(p["collector_number"]),
                typeLine = s(p["type_line"]),
                borderColor = p["border_color"]?.let { s(it) },
                promo = Py.truthy(p["promo"]),
                setType = s(p["set_type"]),
                imageSmall = uri("small"), imageNormal = uri("normal"), imageLarge = uri("large"),
                payload = p,
            )
        }

        /** rank_printings' `{**p, phash_distance, multi_distance, corner_distance, phash_hash}`. */
        fun rankedMap(r: PrintingRanker.Ranked): MutableMap<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            val out = LinkedHashMap((r.printing.payload as? Map<String, Any?>) ?: emptyMap())
            out["phash_distance"] = r.phashDistance.toLong()
            out["multi_distance"] = r.multiDistance.toLong()
            out["corner_distance"] = r.cornerDistance.toLong()
            out["phash_hash"] = r.phashHash
            return out
        }

        /** An [ArtMatcher] over a loaded [ArtPack] — row strings decoded on access, no per-row objects. */
        fun matcherFor(pack: ArtPack): ArtMatcher = ArtMatcher(pack.h64, pack.h256,
            object : AbstractList<ArtMatcher.Entry>() {
                override val size: Int get() = pack.size
                override fun get(index: Int) = ArtMatcher.Entry(
                    pack.scryfallId(index), pack.name(index), pack.setCode(index),
                    pack.collectorNumber(index), pack.artist(index))
            })
    }
}
