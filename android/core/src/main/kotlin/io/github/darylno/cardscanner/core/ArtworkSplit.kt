package io.github.darylno.cardscanner.core

/**
 * Port of mtg_card_scanner/artwork.py: which of a scan's candidates are a
 * DIFFERENT artwork than the one in front of the camera (`other_art`).
 *
 * Split only on a CLEAN break: the best multi_distance must be ≤ 140 and two
 * neighbouring (sorted) distances must jump by ≥ 40 — Fling's same-art Δ78–84
 * vs Δ146+ splits, Bone Splinters' wide same-art Δ124–190 does not. The first
 * such jump is the cut; everything past it is other-art, except an
 * OCR-confirmed candidate. Any missing distance, or fewer than two
 * candidates, splits nothing. Derived on read, never stored.
 */
object ArtworkSplit {
    const val ART_BREAK_GAP = 40L
    const val ART_BREAK_CEILING = 140L

    /** Ids of the candidates past a clean artwork break (else empty). Ids may be null, as in Python. */
    fun otherArtIds(candidates: List<Map<String, Any?>>): Set<Any?> {
        val ds = candidates.map { it["multi_distance"] }
        if (candidates.size < 2 || ds.any { it == null }) return emptySet()
        val ordered = ds.sortedWith { a, b -> Py.compareNum(a, b) }
        if (Py.compareNum(ordered[0], ART_BREAK_CEILING) > 0) return emptySet()
        var cut: Any? = null
        for (i in 1 until ordered.size) {
            if (Py.compareNum(Py.minus(ordered[i], ordered[i - 1]), ART_BREAK_GAP) >= 0) { cut = ordered[i - 1]; break }
        }
        if (cut == null) return emptySet()
        val out = LinkedHashSet<Any?>()
        for (c in candidates) {
            if (Py.compareNum(c["multi_distance"], cut) > 0 && !Py.truthy(c["ocr_confirmed"])) out.add(c["id"])
        }
        return out
    }

    /** flag_other_art on a candidate list: sets `other_art` true/false on each, in place. */
    fun flagOtherArt(candidates: List<MutableMap<String, Any?>>): List<MutableMap<String, Any?>> {
        val other = otherArtIds(candidates)
        for (c in candidates) c["other_art"] = c["id"] in other
        return candidates
    }

    /** flag_other_art(scan): the same, on `scan["candidates"]` (absent/empty -> no-op). */
    @Suppress("UNCHECKED_CAST")
    fun flagOtherArt(scan: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val cands = scan["candidates"]
        if (Py.truthy(cands)) flagOtherArt(cands as List<MutableMap<String, Any?>>)
        return scan
    }
}
