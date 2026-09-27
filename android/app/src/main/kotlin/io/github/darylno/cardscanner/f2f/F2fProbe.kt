package io.github.darylno.cardscanner.f2f

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil

/**
 * Stage 1c: price [cards] over one [F2fTransport] at the rig's pacing and
 * report how the storefront treated THIS phone — 429s, timings, pace.
 * One run = one fresh "sweep" (fresh slow start), like the rig's.
 *
 * The rig's sweep breaker is mirrored: 5 consecutive unavailable cards end
 * the run (the rig would stand down for 10 min) — no point hammering a
 * tripped bucket, and it is itself the answer.
 */
class F2fProbe(
    private val transport: F2fTransport,
    private val cards: List<ProbeCard> = ProbeCards.ALL,
    private val stop: StopSignal = StopSignal(),
    private val base: String = F2f.BASE,
    private val clock: ProbeClock = ProbeClock.SYSTEM,
    private val waiter: Waiter = stop,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        fun onRequest(r: RequestRecord, stats: ProbeStats) {}
        fun onCard(index: Int, card: ProbeCard, outcome: PriceOutcome, stats: ProbeStats) {}
        fun onCardStart(index: Int, card: ProbeCard) {}
    }

    val stats = ProbeStats(transport.name, transport.detail)

    fun run(): ProbeStats {
        stop.onSet { transport.cancel() }
        val fetcher = F2fFetcher(transport, clock, waiter, { stop.isSet }, { r ->
            stats.add(r)
            listener.onRequest(r, stats)
        })
        val pricer = F2fPricer(fetcher, base) { stop.isSet }
        val t0 = clock.nowS()
        var consecutiveUnavailable = 0
        try {
            for ((i, card) in cards.withIndex()) {
                if (stop.isSet) break
                listener.onCardStart(i, card)
                val before = stats.requests
                val out = pricer.price(card)
                stats.paceNow = fetcher.pacer.delay
                if (out is PriceOutcome.Stopped) break
                stats.addCard(card, out, stats.requests - before)
                listener.onCard(i, card, out, stats)
                consecutiveUnavailable = if (out is PriceOutcome.Unavailable) consecutiveUnavailable + 1 else 0
                if (consecutiveUnavailable >= BREAKER) {
                    stats.breakerTripped = true
                    break
                }
            }
        } finally {
            stats.durationS = clock.nowS() - t0
            stats.stopped = stop.isSet
            stats.paceNow = fetcher.pacer.delay
            stats.finished = true
        }
        return stats
    }

    companion object {
        const val BREAKER = 5
    }
}

/** Running tallies for one run; read from the UI thread, written by the probe thread. */
class ProbeStats(val transport: String, val detail: String) {
    @Volatile var requests = 0; private set
    @Volatile var ok = 0; private set
    @Volatile var tooMany = 0; private set
    @Volatile var server5xx = 0; private set
    @Volatile var client4xx = 0; private set
    @Volatile var timeouts = 0; private set
    @Volatile var errors = 0; private set
    @Volatile var first429At: Int? = null; private set
    @Volatile var paceNow: Double = F2f.START_DELAY
    @Volatile var paceMax: Double = F2f.START_DELAY; private set
    @Volatile var durationS: Double = 0.0
    @Volatile var breakerTripped = false
    @Volatile var stopped = false
    @Volatile var finished = false
    private val latencies = mutableListOf<Long>()
    private val protocols = linkedSetOf<String>()
    private val seq = StringBuilder()
    private val recent = ArrayDeque<RequestRecord>()
    val cardLines = mutableListOf<String>()
    @Volatile var priced = 0; private set
    @Volatile var notListed = 0; private set
    @Volatile var unavailable = 0; private set

    @Synchronized
    fun add(r: RequestRecord) {
        requests++
        when {
            r.outcome == "429" -> { tooMany++; if (first429At == null) first429At = requests }
            r.status != null && r.status in 200..399 && r.outcome != "bad-json" -> ok++
            r.outcome.startsWith("5xx") -> server5xx++
            r.outcome.startsWith("4xx") -> client4xx++
            r.outcome == "timeout" -> timeouts++
            else -> errors++
        }
        if (r.status != null) latencies += r.latencyMs
        r.protocol?.let { protocols += it }
        paceNow = r.paceS
        if (r.paceS > paceMax) paceMax = r.paceS
        if (seq.isNotEmpty()) seq.append(' ')
        seq.append(if (r.kind == "suggest") 's' else 'p').append(r.status ?: r.outcome.substringBefore(':')).append('/').append(r.latencyMs)
        recent.addLast(r)
        while (recent.size > 8) recent.removeFirst()
    }

    @Synchronized
    fun addCard(card: ProbeCard, out: PriceOutcome, reqs: Int) {
        val what = when (out) {
            is PriceOutcome.Found -> {
                priced++
                val nm = out.conditions["NM"] ?: out.conditions.values.minOrNull()
                "NM " + (nm?.let { String.format(Locale.US, "%.2f", it) } ?: "?")
            }
            PriceOutcome.NotListed -> { notListed++; "not listed" }
            is PriceOutcome.Unavailable -> { unavailable++; "UNAVAILABLE" }
            PriceOutcome.Stopped -> "stopped"
        }
        cardLines += "${card.label}: $what ($reqs req)"
    }

    @Synchronized fun recentRequests(): List<RequestRecord> = recent.toList()

    @Synchronized fun latencyMeanMs(): Long? = if (latencies.isEmpty()) null else latencies.average().toLong()

    @Synchronized fun latencyP95Ms(): Long? = percentile(95.0)

    @Synchronized fun latencyP50Ms(): Long? = percentile(50.0)

    private fun percentile(p: Double): Long? {
        if (latencies.isEmpty()) return null
        val s = latencies.sorted()
        return s[(ceil(p / 100.0 * s.size).toInt() - 1).coerceIn(0, s.size - 1)]
    }

    val cardsDone: Int get() = priced + notListed + unavailable

    /** The one-line answer. */
    fun verdict(): String {
        val net = timeouts + errors
        return when {
            requests == 0 -> "NO DATA — nothing was sent"
            net * 2 > requests -> "INCONCLUSIVE — $net/$requests requests failed at the network (no answer from the store); check the connection and rerun"
            breakerTripped -> "FAIL — throttled: $BREAKER cards in a row unavailable (the rig's breaker would stand down), $tooMany×429 of $requests" +
                (first429At?.let { ", first 429 at request #$it" } ?: "")
            tooMany == 0 && unavailable == 0 -> "PASS — 0×429 in $requests requests; pace reached ${fmt(paceNow)} s" +
                (if (stopped) " (stopped early)" else "")
            tooMany == 0 -> "PASS (with $unavailable unavailable card(s) from non-429 errors) — 0×429 in $requests requests"
            unavailable == 0 -> "MARGINAL — $tooMany×429 of $requests (first at #$first429At) but pacing recovered; every card answered"
            else -> "FAIL — throttled: $tooMany×429 of $requests (first at #$first429At), $unavailable card(s) unavailable"
        }
    }

    fun verdictTone(): Int = when {
        verdict().startsWith("PASS") -> 1
        verdict().startsWith("FAIL") -> -1
        else -> 0
    }

    /** Compact human summary for the live card. */
    @Synchronized
    fun summary(): String = buildString {
        append("requests ").append(requests)
        append(" · 200 ").append(ok).append(" · 429 ").append(tooMany)
        val other = server5xx + client4xx + timeouts + errors
        append(" · other ").append(other)
        if (other > 0) append(" (5xx ").append(server5xx).append(", 4xx ").append(client4xx)
            .append(", timeout ").append(timeouts).append(", err ").append(errors).append(')')
        append('\n')
        append("latency mean ").append(latencyMeanMs()?.let { "$it ms" } ?: "—")
        append(" · p95 ").append(latencyP95Ms()?.let { "$it ms" } ?: "—")
        append('\n')
        append("pace ").append(fmt(paceNow)).append(" s (max ").append(fmt(paceMax)).append(" s)")
        if (protocols.isNotEmpty()) append(" · ").append(protocols.joinToString("/"))
        append('\n')
        append("cards priced ").append(priced).append(" · not listed ").append(notListed)
            .append(" · unavailable ").append(unavailable)
        if (durationS > 0) append(" · ").append(fmt(durationS)).append(" s")
    }

    @Synchronized
    fun toJson(): JSONObject = JSONObject().apply {
        put("transport", transport)
        put("stack", detail)
        put("verdict", verdict())
        put("requests", requests)
        put("status", JSONObject().apply {
            put("200", ok); put("429", tooMany); put("5xx", server5xx); put("4xx", client4xx)
            put("timeout", timeouts); put("error", errors)
        })
        put("first_429_at", first429At ?: JSONObject.NULL)
        put("lat_ms", JSONObject().apply {
            put("mean", latencyMeanMs() ?: JSONObject.NULL)
            put("p50", latencyP50Ms() ?: JSONObject.NULL)
            put("p95", latencyP95Ms() ?: JSONObject.NULL)
            put("max", latencies.maxOrNull() ?: JSONObject.NULL)
        })
        put("pace_end_s", round2(paceNow))
        put("pace_max_s", round2(paceMax))
        put("protocols", JSONArray(protocols.toList()))
        put("duration_s", round2(durationS))
        put("cards", JSONObject().apply {
            put("priced", priced); put("not_listed", notListed); put("unavailable", unavailable)
        })
        put("breaker", breakerTripped)
        put("stopped", stopped)
        // s=suggest p=product, then status (or timeout/error) / latency ms
        put("seq", seq.toString())
        put("card_results", JSONArray(cardLines.toList()))
    }

    companion object {
        const val BREAKER = F2fProbe.BREAKER
        fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
        private fun round2(v: Double) = Math.round(v * 100) / 100.0
    }
}

/**
 * ~30 real printings, cheap to chase, old to new, basics to power — the
 * set / collector / set names are Scryfall's (looked up 2026-09-27), which
 * is exactly what the rig feeds get_price.
 */
object ProbeCards {
    val ALL: List<ProbeCard> = listOf(
        ProbeCard("Lightning Bolt", "m10", "Magic 2010", "146"),
        ProbeCard("Lightning Bolt", "m11", "Magic 2011", "149"),
        ProbeCard("Sol Ring", "cmr", "Commander Legends", "472"),
        ProbeCard("Thoughtseize", "ths", "Theros", "107"),
        ProbeCard("Ragavan, Nimble Pilferer", "mh2", "Modern Horizons 2", "138"),
        ProbeCard("Fling", "akh", "Amonkhet", "132"),
        ProbeCard("Forest", "dmu", "Dominaria United", "274"),
        ProbeCard("Island", "m21", "Core Set 2021", "263"),
        ProbeCard("Black Lotus", "lea", "Limited Edition Alpha", "232"),
        ProbeCard("Counterspell", "mh2", "Modern Horizons 2", "267"),
        ProbeCard("Smothering Tithe", "rna", "Ravnica Allegiance", "22"),
        ProbeCard("Murder", "m20", "Core Set 2020", "109"),
        ProbeCard("Shivan Dragon", "m10", "Magic 2010", "156"),
        ProbeCard("Grizzly Bears", "10e", "Tenth Edition", "268"),
        ProbeCard("Diabolic Edict", "tmp", "Tempest", "128"),
        ProbeCard("Llanowar Elves", "m19", "Core Set 2019", "314"),
        ProbeCard("Birds of Paradise", "m12", "Magic 2012", "165"),
        ProbeCard("Dark Ritual", "a25", "Masters 25", "82"),
        ProbeCard("Swords to Plowshares", "ema", "Eternal Masters", "32"),
        ProbeCard("Brainstorm", "ice", "Ice Age", "61"),
        ProbeCard("Cultivate", "m21", "Core Set 2021", "177"),
        ProbeCard("Arcane Signet", "eld", "Throne of Eldraine", "331"),
        ProbeCard("Doubling Season", "rav", "Ravnica: City of Guilds", "158"),
        ProbeCard("The One Ring", "ltr", "The Lord of the Rings: Tales of Middle-earth", "246"),
        ProbeCard("Orcish Bowmasters", "ltr", "The Lord of the Rings: Tales of Middle-earth", "103"),
        ProbeCard("Sheoldred, the Apocalypse", "dmu", "Dominaria United", "107"),
        ProbeCard("Bone Splinters", "m20", "Core Set 2020", "92"),
        ProbeCard("Mana Crypt", "ema", "Eternal Masters", "225"),
        ProbeCard("Wastes", "ogw", "Oath of the Gatewatch", "183a"),
        ProbeCard("Rhystic Study", "pcy", "Prophecy", "45"),
        ProbeCard("Demonic Tutor", "uma", "Ultimate Masters", "93"),
        // One foil, so the foil rung of the ladder is exercised too.
        ProbeCard("Sol Ring", "cmr", "Commander Legends", "472", foil = true),
    )
}
