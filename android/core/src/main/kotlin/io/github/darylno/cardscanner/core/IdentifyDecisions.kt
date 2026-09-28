package io.github.darylno.cardscanner.core

/**
 * The identification DECISIONS of mtg_card_scanner/pipeline.py (and the
 * server's scan-time auto-pick in server/app.py), ported over the same
 * candidate JSON the server stores — plain Kotlin maps, mutated in place
 * exactly where Python mutates its dicts.
 *
 * PORT, never re-tune: every helper answers every row of
 * `resources/ocr/expected.json`, which scripts/export_ocr_fixtures.py makes by
 * calling the real Python functions (`--check` in CI). The distance
 * thresholds live in [ArtThresholds]; the two pipeline-only ones are here.
 *
 * Numbers in candidate maps may be any [Number] (the server's are ints;
 * a JSON reader may hand back Integer/Long/BigDecimal) — compared as Double,
 * exact for every integer the pipeline produces.
 */
object IdentifyDecisions {
    /** `_OCR_ART_SLACK`: an OCR hit may promote only within this many points of the best art. */
    const val OCR_ART_SLACK = 60
    /** `_ART_DECISIVE_GAP`: #1's lead over #2 that makes the art alone decisive. */
    const val ART_DECISIVE_GAP = 30
    /** `_ART_DECISIVE_CEILING`: … and only at or under this distance. */
    const val ART_DECISIVE_CEILING = 140

    /** Python truthiness of a JSON value. */
    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    private fun num(c: Map<String, Any?>, key: String): Double? = (c[key] as? Number)?.toDouble()

    /** `_confidence_for`. */
    fun confidenceFor(distance: Int): String = ArtThresholds.confidenceFor(distance)

    /** `_is_confident` over the top NAME matches' distances (already deduped by name). */
    fun isConfident(distances: List<Int>): Boolean {
        if (distances.isEmpty()) return false
        val d = distances[0]
        if (d <= ArtThresholds.MAX_CONFIDENT_DISTANCE) return true
        val gap = if (distances.size > 1) distances[1] - d else (1 shl 30)
        return d <= ArtThresholds.MARGIN_CONFIDENT_DISTANCE && gap >= ArtThresholds.MARGIN_MIN_GAP
    }

    /** `distance > _NO_CARD_DISTANCE`: nothing card-like in frame — reject the scan. */
    fun isNoCard(distance: Int): Boolean = distance > ArtThresholds.NO_CARD_DISTANCE

    /**
     * `_art_agrees`: [hit]'s multi_distance within [slack] of the best among
     * [candidates]. No art data (hit or list) never blocks.
     */
    fun artAgrees(hit: Map<String, Any?>, candidates: List<Map<String, Any?>>,
                  slack: Int = OCR_ART_SLACK): Boolean {
        val dists = candidates.mapNotNull { num(it, "multi_distance") }
        val hd = num(hit, "multi_distance")
        if (hd == null || dists.isEmpty()) return true
        return hd <= dists.min() + slack
    }

    /** `_mark_art_decisive`: flag candidates[0] `art_decisive` when its lead is beyond doubt. */
    fun markArtDecisive(candidates: List<MutableMap<String, Any?>>) {
        if (candidates.size < 2 || truthy(candidates[0]["ocr_confirmed"])) return
        val d0 = num(candidates[0], "multi_distance")
        val d1 = num(candidates[1], "multi_distance")
        if (d0 != null && d1 != null && d0 <= ART_DECISIVE_CEILING && d1 - d0 >= ART_DECISIVE_GAP) {
            candidates[0]["art_decisive"] = true
        }
    }

    /**
     * `_ranked_candidates`' order: by multi_distance, else phash_distance,
     * else last (1<<30); stable, so ties keep the matcher's order.
     */
    fun <M : Map<String, Any?>> sortRanked(ranked: List<M>): List<M> = ranked.sortedBy {
        num(it, "multi_distance") ?: num(it, "phash_distance") ?: (1 shl 30).toDouble()
    }

    /**
     * `_apply_ocr_hint` after the read: when [ocrText] uniquely names one
     * candidate whose art ALSO agrees, flag it `ocr_confirmed` and move it to
     * the front. [detected] = the card quad was found (no quad → no read).
     * Fewer than two candidates, no match, a disagreeing art or any error
     * leave [candidates] untouched (the same list is returned).
     */
    fun applyOcrHint(candidates: List<MutableMap<String, Any?>>, ocrText: String,
                     detected: Boolean = true): List<MutableMap<String, Any?>> {
        if (candidates.size < 2 || !detected) return candidates
        val sid = OcrMatch.matchPrinting(ocrText, candidates) ?: return candidates
        if (sid.isEmpty()) return candidates                 // `if not sid`
        // Python's `c["id"]` raises on a candidate without an id, which the
        // blanket except turns into "unchanged" — at whichever step it is hit.
        var hit: MutableMap<String, Any?>? = null
        for (c in candidates) {
            if (!c.containsKey("id")) return candidates
            if (c["id"]?.toString() == sid) { hit = c; break }
        }
        if (hit == null || !artAgrees(hit, candidates)) return candidates
        hit["ocr_confirmed"] = true
        if (candidates.any { !it.containsKey("id") }) return candidates
        return listOf(hit) + candidates.filter { it["id"]?.toString() != sid }
    }

    /** `_ranked_candidates` after the art matcher: sort, OCR hint, art-decisive mark. */
    fun finishCandidates(ranked: List<MutableMap<String, Any?>>, ocrText: String,
                         detected: Boolean = true): List<MutableMap<String, Any?>> {
        val out = applyOcrHint(sortRanked(ranked), ocrText, detected)
        markArtDecisive(out)
        return out
    }

    /**
     * The server's scan-time auto-pick grounds (server/app.py): an identified
     * scan whose candidates are exactly one printing, or whose #1 is
     * OCR-confirmed or art-decisive. Files NM / Non-Foil / ×1, auto_picked.
     */
    fun shouldAutoPick(result: Map<String, Any?>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val cands = (result["candidates"] as? List<Map<String, Any?>>).orEmpty()
        if (!truthy(result["identified"]) || cands.isEmpty()) return false
        return cands.size == 1 || truthy(cands[0]["ocr_confirmed"]) || truthy(cands[0]["art_decisive"])
    }

    /** `_safe`: non-ASCII code points → '?'. */
    fun safe(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x80) sb.append(cp.toChar()) else sb.append('?')
        }
        return sb.toString()
    }

    /** `_identify`: sharpest frame first; retry the others while unconfident, keep a strictly better one. */
    fun <F> identifyFrames(frames: List<F>, identify: (F) -> List<ArtMatcher.Match>): List<ArtMatcher.Match> {
        var matches = identify(frames[0])
        for (frame in frames.drop(1)) {
            if (isConfident(matches.map { it.distance })) break
            val retry = identify(frame)
            if (retry.isNotEmpty() && (matches.isEmpty() || retry[0].distance < matches[0].distance)) {
                matches = retry
            }
        }
        return matches
    }

    /** `ArtIndexError`: its message is shown as-is (anything else gets a prefix). */
    class ArtIndexError(message: String) : Exception(message)

    private fun lowConf() = linkedMapOf<String, Any?>("name" to "low", "set" to "low", "collector" to "low")

    private fun fail(error: String, noCard: Boolean = false): MutableMap<String, Any?> {
        val r = linkedMapOf<String, Any?>("identified" to false)
        if (noCard) r["no_card"] = true
        r["card_read"] = linkedMapOf<String, Any?>()
        r["confidence"] = lowConf()
        r["candidates"] = emptyList<Any?>()
        r["error"] = error
        return r
    }

    /**
     * `Pipeline.scan_candidates` — every decision and the exact result shape
     * (identified, no_card, card_read, confidence, candidates, error), with
     * the image work behind functions:
     *
     * @param frames the burst; sorted by [sharpness], sharpest first (stable)
     * @param isBlank the blank-surface guard per frame (extract + is_blank_surface)
     * @param identify ArtIndex.identify(top_n=5) per frame; null = no art index
     *   configured. May throw ([ArtIndexError] → its message, else a prefix).
     * @param ranked `_ranked_candidates(sharpest frame, name, topN)` — all
     *   printings, art-ranked, finished ([finishCandidates]); may throw.
     */
    fun <F> scanCandidates(
        frames: List<F>,
        sharpness: (F) -> Double,
        isBlank: (F) -> Boolean,
        identify: ((F) -> List<ArtMatcher.Match>)?,
        ranked: (F, String, Int) -> List<Map<String, Any?>>,
        topN: Int = 12,
    ): MutableMap<String, Any?> {
        val sorted = frames.sortedByDescending(sharpness)
        if (identify == null) return fail("No art index configured.")
        if (sorted.all(isBlank)) return fail("No card detected (blank surface in frame).", noCard = true)

        val matches = try {
            identifyFrames(sorted, identify)
        } catch (e: ArtIndexError) {
            return fail(safe(e.message ?: ""))
        } catch (e: Exception) {
            return fail("Art identification failed: ${safe(e.message ?: "")}")
        }
        if (matches.isEmpty()) return fail("Art index returned no matches.")

        val best = matches[0]
        val distance = best.distance
        if (isNoCard(distance)) return fail("No card detected (best art score $distance).", noCard = true)
        val conf = confidenceFor(distance)
        val confidence = linkedMapOf<String, Any?>("name" to conf, "set" to conf, "collector" to conf)
        val guesses = matches.take(3).joinToString("  ") { "'${safe(it.name)}' d=${it.distance}" }
        val readDict = linkedMapOf<String, Any?>(
            "name" to best.name,
            "set_code" to best.set,
            "collector_number" to best.collectorNumber,
            "foil" to false,
            "language" to "en",
            "condition_estimate" to "NM",
            "condition_reason" to "not assessed (no vision model)",
            "artist" to best.artist,
            "alternates" to matches.drop(1).take(2).map {
                linkedMapOf<String, Any?>("name" to it.name, "distance" to it.distance)
            },
        )
        fun result(identified: Boolean, candidates: List<Map<String, Any?>>, error: String?) =
            linkedMapOf<String, Any?>("identified" to identified, "card_read" to readDict,
                "confidence" to confidence, "candidates" to candidates, "error" to error)

        if (!isConfident(matches.map { it.distance })) {
            // Best-guess grid below the margin ceiling; never auto-trusted.
            var candidates: List<Map<String, Any?>> = emptyList()
            if (distance <= ArtThresholds.MARGIN_CONFIDENT_DISTANCE) {
                try {
                    candidates = ranked(sorted[0], best.name, topN)
                } catch (_: Exception) {
                }
            }
            return result(false, candidates,
                "No confident art match (best: $guesses). Use manual search.")
        }
        val candidates = try {
            ranked(sorted[0], best.name, topN)
        } catch (e: Exception) {
            return result(true, emptyList(),
                "Printings lookup failed for '${safe(best.name)}': ${safe(e.message ?: "")}")
        }
        return result(true, candidates, null)
    }
}
