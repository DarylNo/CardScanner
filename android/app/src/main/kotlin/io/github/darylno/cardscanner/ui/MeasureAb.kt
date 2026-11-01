package io.github.darylno.cardscanner.ui

import io.github.darylno.cardscanner.core.ZoomFit
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * One capture during a zoom A/B arm step (MeasureSession: A1 / B / A2 — the same
 * cards at 1×, zoomed, at 1× again) and, once it lands, its identification. [z] =
 * the zoom it was shot at, [h] = the card's long side in frame px (0 = no quad),
 * [sd] = the collector strip's detail on the 630×880 identification raster
 * (ZoomDetail.identRaster), [iso] = the chosen frame's ISO, [manual] = a shutter
 * capture (flagged and left out). [job] links the upload job; [rank] is null
 * while it is still identifying (see [AbSummary.rankOf]).
 */
data class AbRecord(
    val arm: String,
    val burst: Long,
    val z: Double,
    val h: Int,
    val sd: Double?,
    val iso: Int?,
    val manual: Boolean,
    val job: String? = null,
    val rank: Int? = null,
    val name: String? = null,
    val ocr: Boolean = false,
    val autoPicked: Boolean = false,
    val printings: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("a", arm); put("b", burst); put("z", z); put("h", h); put("m", manual)
        sd?.let { put("sd", it) }; iso?.let { put("iso", it) }; job?.let { put("j", it) }
        rank?.let { put("r", it) }; name?.let { put("n", it) }; printings?.let { put("p", it) }
        if (ocr) put("o", true)
        if (autoPicked) put("ap", true)
    }

    companion object {
        fun fromJson(o: JSONObject): AbRecord? = runCatching {
            AbRecord(
                arm = o.getString("a"), burst = o.optLong("b"), z = o.optDouble("z", 1.0), h = o.optInt("h"),
                sd = if (o.has("sd")) o.getDouble("sd") else null, iso = if (o.has("iso")) o.getInt("iso") else null,
                manual = o.optBoolean("m"), job = if (o.has("j")) o.getString("j") else null,
                rank = if (o.has("r")) o.getInt("r") else null, name = if (o.has("n")) o.getString("n") else null,
                ocr = o.optBoolean("o"), autoPicked = o.optBoolean("ap"), printings = if (o.has("p")) o.getInt("p") else null,
            )
        }.getOrNull()
    }
}

/**
 * The zoom A/B's tally and its rules (1.1.17), from the arm records. Pure.
 *
 * PER CAPTURE, Tray captures only (a shutter capture is flagged and left out): the
 * zoom (A must be 1.00×, B the target), the card's long side in px, the strip
 * detail on the fixed 630×880 identification raster (comparable across zooms:
 * ZoomFit.choose never lets B exceed lossless; the frame-scale "sharpness a / [b]
 * / c" is not, and is only reported on the capture line), the ISO, and the
 * outcome RANK: 3 = OCR ✓, 2 = auto-filed without OCR (one printing or
 * art_decisive), 1 = identified but needs a pick, or a best guess, 0 = no match,
 * no card, or missing.
 *
 * PAIRING: by name across A1 / B / A2 (a name unique in its arm); a capture with
 * no name (still identifying, its outcome missed, a no-match) takes its card's
 * place between its neighbours' captures, by position; the rest by order. A
 * capture still identifying is "still identifying", never "missing" (rank 0).
 * Three named captures paired by order whose names differ are a name
 * mismatch: flagged and left out. A B capture with no 1× partner is an extra B
 * capture. "Stable" = rank(A1) == rank(A2); among stable cards, B is better if its
 * rank is higher, worse if lower.
 *
 * THE RULES, written down before the data (ZOOM_PLAN: piece 2):
 * 1. B is worse on no stable card.
 * 2. B better − B worse ≥ [MIN_NET_BETTER] (a one-sided sign test, p ≤ 0.0625).
 * 3. B adds no captures beyond the target, and no more no-card / no-match
 *    outcomes than A1 or A2.
 * 4. B was really zoomed (≥ ZoomFit.MIN_USEFUL), A at 1× — else the A/B is void.
 * Plus the post-fit check: B's card size scales with the zoom within ±3 %.
 * A tie stays OFF: every Area or lens change costs a re-learn for nothing. If A1
 * already OCR-confirms nearly every card, B cannot win — the verdict says so.
 *
 * The probe half of the default-on verdict (piece 1, the zoom probe) is not built
 * in this version: [verdictLine] takes its checks when there are some, and says
 * "not measured" until then — so this build's verdict is never YES.
 */
class AbSummary(records: List<AbRecord>, val target: Int = MeasureSession.AB_CARDS) {
    class Check(val ok: Boolean, val words: String) {
        override fun toString() = (if (ok) "✓ " else "✗ ") + words
    }

    /** One physical card: its capture in each arm (null = missing), and whether its names disagree. */
    class Card(val a1: AbRecord?, val b: AbRecord?, val a2: AbRecord?, val mismatch: Boolean) {
        val name: String? get() = a1?.name ?: a2?.name ?: b?.name
    }

    enum class State { INCOMPLETE, VOID, FAIL, PASS }

    private val tray: Map<String, List<AbRecord>> =
        ARMS.associateWith { arm -> records.filter { it.arm == arm && !it.manual }.sortedBy { it.burst } }
    /** Shutter captures during the arms: flagged and left out. */
    val shutter: Int = records.count { it.manual && it.arm in ARMS }
    val cards: List<Card>
    /** B captures with no 1× partner (a card scanned twice, or a phantom). */
    val extraB: Int

    init {
        val lists = ARMS.map { tray.getValue(it) }          // A1, B, A2
        fun unique(l: List<AbRecord>) = l.mapNotNull { it.name }.groupingBy { it }.eachCount().filterValues { it == 1 }.keys
        val uniq = lists.map(::unique)
        // By identity: two captures can carry equal fields (a burst id of 0 = unknown).
        val used: MutableSet<AbRecord> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        fun pos(arm: Int, r: AbRecord?) = if (r == null) -1 else lists[arm].indexOfFirst { it === r }
        /** One card being paired: its capture per arm (A1, B, A2). */
        class Slot(val r: Array<AbRecord?>) {
            /** Its place in the session: its A1 position, else A2's, else B's. */
            fun ref(pos: (Int, AbRecord?) -> Int): Int = pos(0, r[0]).takeIf { it >= 0 } ?: pos(2, r[2]).takeIf { it >= 0 } ?: pos(1, r[1])
        }
        val slots = ArrayList<Slot>()
        // 1. By name: a name unique in at least two arms pairs those captures.
        val names = LinkedHashSet<String>().apply { (lists[0] + lists[2] + lists[1]).mapNotNull { it.name }.forEach { add(it) } }
        for (n in names) {
            val r = Array(3) { a -> lists[a].firstOrNull { it.name == n }?.takeIf { n in uniq[a] } }
            if (r.count { it != null } < 2) continue
            r.filterNotNull().forEach { used += it }
            slots += Slot(r)
        }
        // 2. Paired cards missing an arm take that arm's leftover shots BETWEEN their neighbours'
        //    captures there (in burst order) — a capture still identifying has no name yet (nor
        //    one whose outcome was missed), a no-match never has one. A run of such cards side by
        //    side shares the shots between the same neighbours: its own name first, then by
        //    position, k-th shot to k-th card (review of 9df0293: with two side by side neither
        //    was placed, so a card still identifying scored as MISSING). More shots than cards:
        //    the rest are extras (a card scanned twice); fewer: the first cards take them —
        //    which card went unshot is a guess there, how many is not.
        val ordered = slots.sortedBy { it.ref(::pos) }
        for (a in 0 until 3) {
            var k = 0
            while (k < ordered.size) {
                if (ordered[k].r[a] != null) { k++; continue }
                val run = (k until ordered.size).takeWhile { ordered[it].r[a] == null }.map { ordered[it] }
                val lo = ordered.getOrNull(k - 1)?.r?.get(a)?.burst ?: Long.MIN_VALUE
                val hi = ordered.getOrNull(k + run.size)?.r?.get(a)?.burst ?: Long.MAX_VALUE
                val between = lists[a].filter { it !in used && it.burst > lo && it.burst < hi }.toMutableList()
                for (s in run) {
                    val n = s.r.firstNotNullOfOrNull { it?.name } ?: continue
                    between.singleOrNull { it.name == n }?.let { r -> s.r[a] = r; used += r; between.removeAll { it === r } }
                }
                for ((s, r) in run.filter { it.r[a] == null }.zip(between)) { s.r[a] = r; used += r }
                k += run.size
            }
        }
        // 3. The rest by order.
        val rest = lists.map { l -> l.filter { it !in used } }
        var extra = 0
        for (i in 0 until rest.maxOf { it.size }) {
            val r = Array(3) { a -> rest[a].getOrNull(i) }
            if (r[0] == null && r[2] == null) { extra++; continue }    // a B capture with no 1× partner
            slots += Slot(r)
        }
        cards = slots.withIndex().sortedWith(compareBy({ it.value.ref(::pos) }, { it.index })).map { (_, s) ->
            val named = s.r.mapNotNull { it?.name }
            Card(s.r[0], s.r[1], s.r[2], mismatch = named.toSet().size > 1)
        }
        extraB = extra
    }

    private fun arm(a: String) = tray.getValue(a)
    private fun ocr(a: String) = arm(a).count { it.rank == 3 }
    private fun auto(a: String) = arm(a).count { it.autoPicked }
    private fun none(a: String) = arm(a).count { it.rank == 0 }
    /** Tray captures still identifying (no outcome yet), every arm. */
    val pending: Int = ARMS.sumOf { a -> tray.getValue(a).count { it.rank == null } }

    private val judged = cards.filter { !it.mismatch }
    private fun unstable(c: Card): Boolean { val r1 = rankOf(c.a1); val r2 = rankOf(c.a2); return r1 != null && r2 != null && r1 != r2 }
    private val unstable = judged.count(::unstable)
    private val stable = judged.filter { c ->
        val r1 = rankOf(c.a1); val r2 = rankOf(c.a2); val rB = rankOf(c.b)
        r1 != null && r2 != null && rB != null && r1 == r2
    }
    val better = stable.count { rankOf(it.b)!! > rankOf(it.a1)!! }
    val worse = stable.count { rankOf(it.b)!! < rankOf(it.a1)!! }
    /** Stable cards B could still beat (A1 below OCR ✓): fewer than [MIN_NET_BETTER] and rule 2 is out of reach this run. */
    private val room = stable.count { rankOf(it.a1)!! < 3 }
    /**
     * A1's cards NOT OCR-confirmed — a card A1 never shot counts as one (the target, if
     * A1 has fewer captures): the most B could improve, whatever 1× does the second time.
     */
    private val a1Open = maxOf(tray.getValue("A1").size, target) - tray.getValue("A1").count { it.rank == 3 }
    private val mismatches = cards.count { it.mismatch }

    private fun median(v: List<Double>): Double? = if (v.isEmpty()) null else v.sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
    private fun zOf(l: List<AbRecord>) = median(l.map { it.z })
    private fun hOf(l: List<AbRecord>) = median(l.filter { it.h > 0 }.map { it.h.toDouble() })
    private val zA1 = zOf(arm("A1")); private val zB = zOf(arm("B")); private val zA2 = zOf(arm("A2"))
    /** Rule 4's evidence: B's lowest zoom, and the A capture furthest from 1×. */
    private val bMin = arm("B").minOfOrNull { it.z }
    private val aWorst = (arm("A1") + arm("A2")).maxByOrNull { abs(it.z - 1.0) }?.z
    private val zoomed = bMin != null && bMin >= ZoomFit.MIN_USEFUL - 1e-9
    private val aAtOne = aWorst == null || abs(aWorst - 1.0) <= 0.005

    val checks: List<Check> = run {
        val bN = arm("B").size
        val noA1 = none("A1"); val noA2 = none("A2"); val noB = none("B")
        val oneX = arm("A1") + arm("A2")
        val hA = hOf(oneX); val hB = hOf(arm("B")); val zA = zOf(oneX)
        val residual = if (hA != null && hB != null && zA != null && zB != null && hA > 0) hB / (hA * zB / zA) - 1.0 else null
        listOf(
            Check(worse == 0, if (worse == 0) "B worse on none" else "B worse on $worse (needs none)"),
            Check(better - worse >= MIN_NET_BETTER, "B better − worse = ${better - worse} (needs $MIN_NET_BETTER)"),
            Check(bN <= target, if (bN <= target) "no extra B captures ($bN of ×$target)" else "extra B captures ($bN of ×$target)"),
            Check(noB <= minOf(noA1, noA2),
                "${if (noB <= minOf(noA1, noA2)) "no more" else "more"} no-card/no-match in B ($noB vs A1 $noA1 / A2 $noA2)"),
            Check(zoomed && aAtOne, when {
                !zoomed -> "B ran at ${x(bMin)} (needs ≥ ${x(ZoomFit.MIN_USEFUL)})"
                !aAtOne -> "A ran at ${x(aWorst)} (needs ${x(1.0)})"
                else -> "B zoomed ${x(zB)} (A at ${x(1.0)})"
            }),
            if (residual == null) Check(false, "post-fit: no card sizes to compare")
            else Check(abs(residual) <= POST_FIT, if (abs(residual) <= POST_FIT) "post-fit within ±3 %% (%+.1f %%)".format(Locale.ROOT, residual * 100)
                else "post-fit %+.1f %% (needs ±3 %%)".format(Locale.ROOT, residual * 100)),
        )
    }

    val state: State = when {
        ARMS.any { arm(it).isEmpty() } -> State.INCOMPLETE
        !(zoomed && aAtOne) -> State.VOID
        checks.all { it.ok } -> State.PASS
        else -> State.FAIL
    }

    /**
     * "zoom has no job at this mount height": A1 ALONE already OCR-confirms so many cards
     * that rule 2 cannot pass ([a1Open] < [MIN_NET_BETTER]) — the plan's condition. Cards
     * unstable at 1×, still identifying or mismatched say nothing about the mount height
     * (review of 9df0293: A1 confirming 0 of 10 with 7 unstable read "no job"): that is
     * [outOfReach], with its own words.
     */
    val noJob: Boolean get() = state != State.INCOMPLETE && state != State.VOID && a1Open < MIN_NET_BETTER
    /** Rule 2 out of reach THIS run for another reason: too few stable cards left to improve. Run again, never a lower bar. */
    val outOfReach: Boolean get() = state != State.INCOMPLETE && state != State.VOID && !noJob && room < MIN_NET_BETTER

    /** Why the stable pool is small, and what to do: "(7 unstable at 1×, 0 still identifying); run the zoom steps again …". */
    private fun outOfReachWhy(): String =
        "(${listOfNotNull("$unstable unstable at 1×", "$pending still identifying",
            mismatches.takeIf { it > 0 }?.let { plural(it, "name mismatch", "name mismatches") }).joinToString(", ")})"
    private fun outOfReachDo(): String =
        if (pending > 0) "wait until no card is still identifying" else "run the zoom steps again with $target other cards"

    private fun x(z: Double?) = z?.let { "%.2f×".format(Locale.ROOT, it) } ?: "—"
    private fun n(v: Double?) = v?.let { "%.0f".format(Locale.ROOT, it) } ?: "—"
    private fun counts(f: (String) -> Int) = ARMS.joinToString(" → ") { f(it).toString() }
    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

    /** The `test` log's summary line. */
    fun summaryLine(): String = buildString {
        val hA1 = hOf(arm("A1")); val hB = hOf(arm("B"))
        append("zoom A/B (same ${cards.size} cards; A1 ${x(zA1)} / B ${x(zB)} / A2 ${x(zA2)}): ")
        append("card ${n(hA1)} → ${n(hB)} px long")
        if (hA1 != null && hB != null && hA1 > 0) append(" (×%.2f)".format(Locale.ROOT, hB / hA1))
        append(" · strip detail ${n(median(arm("A1").mapNotNull { it.sd }))} → ${n(median(arm("B").mapNotNull { it.sd }))}")
        append(" · ISO ${n(median(arm("A1").mapNotNull { it.iso?.toDouble() }))} → ${n(median(arm("B").mapNotNull { it.iso?.toDouble() }))}")
        append(" · OCR ✓ ${counts(::ocr)} · auto-filed ${counts(::auto)} · no card/no match ${counts(::none)}")
        append(" · unstable at 1× $unstable · B better $better / worse $worse · still identifying $pending")
        val left = listOfNotNull(
            shutter.takeIf { it > 0 }?.let { plural(it, "shutter capture", "shutter captures") },
            mismatches.takeIf { it > 0 }?.let { plural(it, "name mismatch", "name mismatches") },
            extraB.takeIf { it > 0 }?.let { plural(it, "extra B capture", "extra B captures") },
        )
        if (left.isNotEmpty()) append(" · left out: ").append(left.joinToString(", "))
    }

    /** The `test` log's per-card line: each card's rank in A1 / B / A2. */
    fun perCardLine(): String = "zoom A/B per card (3 = OCR ✓, 2 = auto-filed, 1 = needs a pick, 0 = none; … identifying, – missing): " +
        cards.withIndex().joinToString("; ") { (i, c) ->
            "${i + 1} ${c.name ?: "?"} A1 ${shown(c.a1)} · B ${shown(c.b)} · A2 ${shown(c.a2)}" + when {
                c.mismatch -> " (name mismatch: " + listOfNotNull(c.a1, c.b, c.a2).mapNotNull { it.name }.distinct().joinToString(" / ") + ")"
                unstable(c) -> " (unstable at 1×)"
                else -> ""
            }
        }

    private fun shown(r: AbRecord?) = if (r == null) "–" else r.rank?.toString() ?: "…"

    private fun incompleteWords() = "incomplete — no Tray captures in " + ARMS.filter { arm(it).isEmpty() }.joinToString(" / ")
    private fun voidWords() =
        if (!zoomed) "void — B ran at ${x(bMin)}: draw a tighter Area (B must zoom ≥ ${x(ZoomFit.MIN_USEFUL)})"
        else "void — A ran at ${x(aWorst)} (A must be at ${x(1.0)})"

    /**
     * The `test` log's verdict line. [probe] = the probe half's checks (piece 1) — null
     * until the zoom probe is built, so Zoom to fit ON by default reads NO.
     */
    fun verdictLine(probe: List<Check>? = null): String = buildString {
        append("zoom VERDICT: ")
        when (state) {
            State.INCOMPLETE -> { append(incompleteWords()).append(" · Zoom to fit ON by default: NO"); return@buildString }
            State.VOID -> append(voidWords()).append(" · Zoom to fit ON by default: NO")
            State.FAIL -> append("Zoom to fit ON by default: NO — the A/B half fails")
            State.PASS -> when {
                probe == null -> append("Zoom to fit ON by default: NO — the A/B half passes; the probe half is not measured (the zoom probe is not built in this version)")
                probe.all { it.ok } -> append("Zoom to fit ON by default: YES — both halves pass")
                else -> append("Zoom to fit ON by default: NO — the A/B half passes, the probe half fails")
            }
        }
        probe?.forEach { append(" · ").append(it) }
        checks.forEach { append(" · ").append(it) }
        if (noJob) append(" · note: zoom has no job at this mount height — A1 already OCR-confirms ${ocr("A1")} of " +
            "${maxOf(arm("A1").size, target)} (B can improve at most $a1Open; rule 2 needs $MIN_NET_BETTER)")
        if (outOfReach) append(" · note: rule 2 is out of reach this run — at most ${plural(room, "stable card", "stable cards")} can improve " +
            "${outOfReachWhy()}; ${outOfReachDo()}")
        append(" · note: MAX_ZOOM is still %.2f× in this build".format(Locale.ROOT, ZoomFit.MAX_ZOOM))
    }

    /** The summary, the per-card line and the verdict — logged before "session END". */
    fun logLines(): List<String> = listOf(summaryLine(), perCardLine(), verdictLine())

    /** The done card's two lines (the full ones are in the debug report). */
    fun cardText(): String = buildString {
        append("A/B, same ${cards.size} cards (B ${x(zB)}): OCR ✓ ${counts(::ocr)} · B better $better / worse $worse · still identifying $pending\n")
        append("Zoom to fit ON by default: NO — ")
        append(when (state) {
            State.INCOMPLETE -> incompleteWords()
            State.VOID -> voidWords()
            State.FAIL -> checks.filter { !it.ok }.joinToString(" · ")
            State.PASS -> "the A/B half passes; the probe half is not measured yet"
        })
        if (noJob) append(" · zoom has no job at this mount height")
        if (outOfReach) append(" · rule 2 out of reach this run ${outOfReachWhy()}")
    }

    companion object {
        val ARMS = listOf("A1", "B", "A2")
        /** Rule 2's bar: B better − B worse ≥ 4 is a one-sided sign test at p ≤ 0.0625 (written down before the data). */
        const val MIN_NET_BETTER = 4
        /** The post-fit check's tolerance (ZoomFit.PostFit's ±3 %). */
        const val POST_FIT = 0.03

        /** A card's rank in one arm: its record's (null = still identifying), 0 when it has none (missing). */
        fun rankOf(r: AbRecord?): Int? = if (r == null) 0 else r.rank
    }
}
