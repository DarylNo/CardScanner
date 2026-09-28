package io.github.darylno.cardscanner.core

/**
 * Port of the candidate side of mtg_card_scanner/pipeline.py: the exact
 * candidate JSON the server stores and returns (`_candidate_dict`), manual
 * search's list (`search_candidates`), and the projection half of
 * `_ranked_candidates` (sort by art distance, top N, print counts,
 * `art_decisive`), plus visual_match's 120-printing ranking cap.
 *
 * The art DISTANCES themselves come from the art matcher (a separate port);
 * here a ranked printing is just a printing map carrying `phash_distance` /
 * `multi_distance`, as visual_match.rank_printings returns it.
 */
object PrintingCandidates {
    const val MAX_CANDIDATES_PER_SCAN = 120      // visual_match._MAX_CANDIDATES_PER_SCAN
    const val ART_DECISIVE_GAP = 30L             // pipeline._ART_DECISIVE_GAP
    const val ART_DECISIVE_CEILING = 140L        // pipeline._ART_DECISIVE_CEILING
    const val SCAN_TOP_N = 12                    // scan_candidates(top_n=12)
    const val SEARCH_TOP_N = 40                  // search_candidates(top_n=40)

    /** pipeline._candidate_dict, key for key and in the same order. */
    fun candidateDict(p: Map<String, Any?>, printCount: Long? = null): LinkedHashMap<String, Any?> {
        val images = Py.map(p["image_uris"]).takeIf { Py.truthy(it) } ?: emptyMap()
        val normal = Py.get(images, "normal", "")
        return linkedMapOf(
            "popularity" to Popularity.summarize(p, printCount),
            "id" to Py.get(p, "id", ""),
            "name" to Py.get(p, "name", ""),
            "set" to Py.get(p, "set", ""),
            "set_name" to Py.get(p, "set_name", ""),
            "collector_number" to Py.get(p, "collector_number", ""),
            "rarity" to Py.get(p, "rarity", ""),
            "released_at" to Py.get(p, "released_at", ""),
            "border_color" to Py.get(p, "border_color", ""),
            "frame" to Py.get(p, "frame", ""),
            "promo" to Py.truthy(Py.get(p, "promo", false)),
            "finishes" to Py.get(p, "finishes", ArrayList<Any?>()),
            "image_small" to Py.get(images, "small", ""),
            "image_normal" to (if (Py.truthy(normal)) normal else Py.get(images, "large", "")),
            "phash_distance" to p["phash_distance"],
            "multi_distance" to p["multi_distance"],
        )
    }

    private fun countFor(counts: Map<String, Long>, p: Map<String, Any?>): Long? = counts[Popularity.oracleKey(p)]

    /**
     * search_candidates: manual re-identification — every printing of the
     * name, newest first (stable on equal dates), no art ranking.
     */
    fun searchCandidates(printings: List<Map<String, Any?>>, topN: Int = SEARCH_TOP_N): List<LinkedHashMap<String, Any?>> {
        val sorted = printings.sortedWith { a, b ->
            // sorted(key=released_at, reverse=True) is stable: equal keys keep input order.
            releasedAt(b).compareTo(releasedAt(a))
        }
        val counts = Popularity.printCountsByOracle(sorted)
        return sorted.take(topN).map { candidateDict(it, countFor(counts, it)) }
    }

    private fun releasedAt(p: Map<String, Any?>): String = Py.get(p, "released_at", "") as String

    /** visual_match._cap_candidates: ≤120 printings, else the oldest 60 + newest 60. */
    fun <T> capForRanking(printings: List<T>): List<T> {
        if (printings.size <= MAX_CANDIDATES_PER_SCAN) return printings
        val half = MAX_CANDIDATES_PER_SCAN / 2
        return printings.subList(0, half) + printings.subList(printings.size - half, printings.size)
    }

    /**
     * The projection half of pipeline._ranked_candidates. [allPrintings] are
     * every paper printing (print counts span ALL of them, not just the ranked
     * cap); [ranked] is the art matcher's output in ITS order. Sorted (stably)
     * by multi_distance, falling back to phash_distance (missing -> 2^30), the
     * top [topN] projected, [ocrHint] applied (pipeline._apply_ocr_hint — a
     * separate port; identity by default), then `art_decisive` marked.
     */
    fun rankedCandidates(
        allPrintings: List<Map<String, Any?>>,
        ranked: List<Map<String, Any?>>,
        topN: Int = SCAN_TOP_N,
        ocrHint: (List<LinkedHashMap<String, Any?>>) -> List<LinkedHashMap<String, Any?>> = { it },
    ): List<LinkedHashMap<String, Any?>> {
        val sorted = ranked.sortedWith { a, b -> Py.compareNum(rankKey(a), rankKey(b)) }
        val counts = Popularity.printCountsByOracle(allPrintings)
        val candidates = ocrHint(sorted.take(topN).map { candidateDict(it, countFor(counts, it)) })
        markArtDecisive(candidates)
        return candidates
    }

    private fun rankKey(p: Map<String, Any?>): Any? =
        p["multi_distance"] ?: Py.get(p, "phash_distance", 1L shl 30)

    /** pipeline._mark_art_decisive: flag candidates[0] when its art lead is beyond doubt. */
    fun markArtDecisive(candidates: List<MutableMap<String, Any?>>) {
        if (candidates.size < 2 || Py.truthy(candidates[0]["ocr_confirmed"])) return
        val d0 = candidates[0]["multi_distance"]
        val d1 = candidates[1]["multi_distance"]
        if (d0 != null && d1 != null && Py.compareNum(d0, ART_DECISIVE_CEILING) <= 0 &&
            Py.compareNum(Py.minus(d1, d0), ART_DECISIVE_GAP) >= 0
        ) candidates[0]["art_decisive"] = true
    }
}
