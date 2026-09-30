package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.ArtworkSplit
import io.github.darylno.cardscanner.core.IdentifyDecisions
import io.github.darylno.cardscanner.core.Py

/** One F2F lookup's answer (facetoface.get_price). */
sealed class F2fAnswer {
    /** F2FPrice.to_dict(): name, set_code, collector_number, foil, handle, url, conditions. */
    data class Found(val price: Map<String, Any?>) : F2fAnswer() {
        @Suppress("UNCHECKED_CAST")
        val conditions: Map<String, Any?> get() = (price["conditions"] as? Map<String, Any?>).orEmpty()
    }
    /** A confirmed miss (get_price → None): recorded, not re-searched. */
    data object NotListed : F2fAnswer()
    /** F2FUnavailableError: UNKNOWN — nothing written, stays retryable. */
    data class Unavailable(val why: String) : F2fAnswer()
}

/** get_price(name, set_code, collector_number, foil, set_name). */
fun interface F2fLookup {
    fun price(name: String, setCode: String, collectorNumber: String, foil: Boolean, setName: String): F2fAnswer
}

/**
 * Port of server/app.py's pricing (Stage 3c): the ONE-consumer F2F sweep and
 * everything that feeds it — PORT, never re-tune (CLAUDE.md "Pricing sweep"):
 *
 *  - targets: a selected scan's selection (until searched); an UNPICKED scan's
 *    every candidate print not yet searched, except a different artwork past
 *    a clean break (other_art) — those cost no F2F budget;
 *  - ONE consumer: a sweep is claimed atomically; price checks jump the FRONT
 *    of a running sweep's queue instead of starting a second consumer;
 *  - every target is re-checked before its fetch (picks land mid-sweep) and
 *    every write re-checks under [PhoneApi.selectLock] (write-if-current);
 *  - a confirmed miss records an empty search, Unavailable writes nothing;
 *  - circuit breaker: 5 consecutive Unavailable → stop, cool down 10 min;
 *  - Stop cancels within one card and pauses the auto-sweep 10 min; a manual
 *    start (price-missing) clears both the pause and the cooldown;
 *  - the auto tick also re-applies the auto-pick grounds to old rows.
 *
 * [launch] runs a claimed sweep (a background thread on the phone; inline in
 * the parity test, like Starlette's background tasks). [interrupt] aborts
 * in-flight waits (the fetcher's stop signal). [now] is monotonic seconds.
 */
class PriceSweep(
    private val store: ScanStore,
    private val api: PhoneApi,
    private val f2f: F2fLookup,
    private val launch: (Runnable) -> Unit,
    private val now: () -> Double,
    private val interrupt: (Boolean) -> Unit = {},
    private val paceS: () -> Double? = { null },
) {
    data class Target(val kind: String, val sid: Long, val c: Map<String, Any?>, val foil: Boolean, val label: String)

    // The `sweep` dict.
    @Volatile var active = false; private set
    @Volatile var total = 0; private set
    @Volatile var done = 0; private set
    @Volatile var current = ""; private set
    @Volatile private var started = 0.0
    @Volatile private var cancel = false
    @Volatile private var manualStopAt: Double? = null
    @Volatile private var backoffUntil: Double? = null
    @Volatile var nextCheckAt: Double? = null
    private val priority = ArrayDeque<Target>()
    /** `sweep_lock`. */
    private val sweepLock = Any()

    // ── endpoints ────────────────────────────────────────────────────────────

    /** GET /api/price-status. */
    fun status(): Map<String, Any?> {
        val t = now()
        val elapsed = if (active) t - started else 0.0
        val rate = if (active && elapsed > 0) done / elapsed else null
        val remaining = maxOf(0, total - done)
        val nextCheck = nextCheckAt?.let { nc ->
            val cs = listOfNotNull(nc, backoffUntil, manualStopAt?.plus(600))
            Math.round(maxOf(0.0, cs.max() - t))
        }
        return linkedMapOf(
            "active" to active, "total" to total.toLong(), "done" to done.toLong(), "current" to current,
            "cancelling" to cancel,
            "cooldown_s" to (backoffUntil?.let { Math.round(maxOf(0.0, it - t)) } ?: 0L),
            "pace_s" to paceS()?.let { Math.round(it * 100) / 100.0 },
            "next_check_s" to nextCheck,
            "rate_per_s" to rate?.takeIf { it != 0.0 }?.let { Math.round(it * 100) / 100.0 },
            "eta_s" to rate?.takeIf { it != 0.0 }?.let { Math.round(remaining / it) },
        )
    }

    /** POST /api/price-sweep/stop. */
    fun stop(): Map<String, Any?> {
        manualStopAt = now()
        if (active) {
            cancel = true
            interrupt(true)
            return mapOf("stopping" to true)
        }
        return mapOf("stopping" to false)
    }

    /** Server shutdown: cancel a running sweep within one card, WITHOUT the manual-stop pause. */
    fun cancel() {
        if (active) { cancel = true; interrupt(true) }
    }

    /** POST /api/scans/price-missing. */
    fun priceMissing(): Map<String, Any?> {
        manualStopAt = null
        backoffUntil = null                // a manual start overrides the cooldown
        val targets = collectTargets()
        if (targets.isEmpty()) return linkedMapOf("queued" to 0L, "already_running" to active)
        if (!claim(targets)) return linkedMapOf("queued" to 0L, "already_running" to true)
        launch(Runnable { run(targets) })
        return linkedMapOf("queued" to targets.size.toLong())
    }

    /** POST /api/scans/{id}/price-check → (status, body). */
    fun priceCheck(id: Long): Pair<Int, Map<String, Any?>> {
        if (store.get(id) == null) return 404 to mapOf("error" to "not found")
        val targets = collectTargets(only = id)
        if (targets.isEmpty()) return 200 to mapOf("queued" to 0L)
        repeat(3) {
            if (claim(targets)) {
                launch(Runnable { run(targets) })
                return 200 to mapOf("queued" to targets.size.toLong())
            }
            synchronized(sweepLock) {
                if (active) {
                    if (cancel) return 200 to linkedMapOf("queued" to 0L, "busy" to true)
                    val queued = priority.map { Triple(it.kind, it.sid, it.c["id"]) }.toSet()
                    val fresh = targets.filter { Triple(it.kind, it.sid, it.c["id"]) !in queued }
                    priority.addAll(fresh)
                    total += fresh.size
                    return 200 to linkedMapOf("queued" to fresh.size.toLong(), "sweeping" to true)
                }
            }
        }
        return 200 to linkedMapOf("queued" to 0L, "busy" to true)
    }

    /**
     * POST /api/scans/{id}/price — price ONE scan now (synchronous): the
     * selection, else the top candidate. A failed search never clears a price;
     * it returns the row with a transient `f2f_search` flag.
     */
    fun priceNow(id: Long): Pair<Int, Any?> {
        val scan = store.get(id) ?: return 404 to mapOf("error" to "not found")
        val sel = Py.map(scan["selection"])
        @Suppress("UNCHECKED_CAST")
        val printing: Map<String, Any?>? = if (Py.truthy(sel)) sel
            else (scan["candidates"] as? List<Map<String, Any?>>)?.firstOrNull()
        if (printing == null || !Py.truthy(Py.get(printing, "name"))) {
            return 400 to mapOf("error" to "nothing to price — no selection or candidates")
        }
        if (active) return 200 to LinkedHashMap(scan).apply { put("f2f_search", "sweeping") }
        val wasSelected = Py.truthy(sel)
        val pid = listOf(Py.get(printing, "scryfall_id"), Py.get(printing, "id")).firstOrNull { Py.truthy(it) } ?: ""
        val foil = Py.truthy(Py.get(printing, "foil", false))
        when (val p = lookup(printing, foil)) {
            is F2fAnswer.Unavailable -> {
                val row = store.get(id) ?: return 404 to mapOf("error" to "not found")
                row["f2f_search"] = "unavailable"          // unreachable ≠ not listed
                return 200 to row
            }
            is F2fAnswer.Found -> {
                val expect = if (wasSelected) Expect(true, pid, foil) else Expect(false)
                writePriceIfCurrent(id, expect, p.price)?.let { return 200 to it }
            }
            F2fAnswer.NotListed -> Unit
        }
        val row = store.get(id) ?: return 404 to mapOf("error" to "not found")
        row["f2f_search"] = "not_found"
        return 200 to row
    }

    /** What one [tick] found: it priced, work is owed but held back, or nothing is owed. */
    enum class Tick { RAN, OWED, IDLE }

    /**
     * One auto check (the Python daemon's loop body): skip while a sweep runs;
     * retro auto-picks; honour a manual-stop pause and the breaker cooldown;
     * then price whatever is still owed. Runs the sweep in the CALLER's
     * thread. On the phone [PriceWorker] calls this every 5 s while anything
     * is owed and sleeps until the next scan when nothing is.
     */
    fun tick(): Tick {
        if (active) return Tick.OWED
        retroFixScans()
        val items = collectTargets()
        if (items.isEmpty()) return Tick.IDLE
        manualStopAt?.let { if (now() - it < 600) return Tick.OWED }
        backoffUntil?.let { if (now() < it) return Tick.OWED }
        return if (claim(items)) { run(items); Tick.RAN } else Tick.OWED
    }

    /** The rig's fixed-interval form of [tick] (`_auto_sweep_loop`). */
    fun autoTick(intervalS: Double) {
        nextCheckAt = now() + intervalS
        tick()
    }

    // ── the sweep ────────────────────────────────────────────────────────────

    /** `_collect_price_targets`. */
    fun collectTargets(only: Long? = null): List<Target> {
        val out = ArrayList<Target>()
        val rows = if (only == null) store.list() else listOfNotNull(store.get(only))
        for (s in rows) {
            val sid = (s["id"] as Number).toLong()
            val sel = Py.map(s["selection"])
            if (Py.truthy(sel)) {
                if (s["f2f"] == null && Py.truthy(Py.get(sel!!, "name"))) {
                    out += Target("scan", sid, sel, Py.truthy(Py.get(sel, "foil", false)), (Py.get(sel, "name", "") ?: "").toString())
                }
            } else {
                ArtworkSplit.flagOtherArt(s)
                @Suppress("UNCHECKED_CAST")
                val cands = (s["candidates"] as? List<Map<String, Any?>>).orEmpty()
                val pending = cands.filter {
                    Py.get(it, "f2f_conditions") == null && Py.truthy(Py.get(it, "name")) && it["other_art"] != true
                }
                for ((i, c) in pending.withIndex()) {
                    var label = Py.str(Py.get(c, "name", ""))
                    if (Py.truthy(Py.get(c, "set"))) {
                        label += " [${Py.str(c["set"]).uppercase()} #${Py.str(Py.get(c, "collector_number", ""))}]"
                    }
                    if (pending.size > 1) label += " · print ${i + 1}/${pending.size}"
                    out += Target("print", sid, c, false, label)
                }
            }
        }
        return out
    }

    /** `_claim_sweep`: atomically flip to active. */
    fun claim(targets: List<Target>): Boolean = synchronized(sweepLock) {
        if (active) return false
        active = true; total = targets.size + priority.size; done = 0; current = ""; cancel = false
        started = now()
        interrupt(false)
        true
    }

    /** `_end_sweep_locked` (caller holds [sweepLock]). */
    private fun endLocked() {
        active = false; current = ""; cancel = false
        priority.clear()
        interrupt(false)
    }

    /** `_run_sweep` — requires a successful [claim]. */
    fun run(items: List<Target>) {
        var unavailableStreak = 0
        val queue = ArrayDeque(items)
        var ended = false
        try {
            while (true) {
                val t: Target = synchronized(sweepLock) {
                    priority.removeFirstOrNull() ?: queue.removeFirstOrNull() ?: run {
                        endLocked(); ended = true; null
                    }
                } ?: break
                if (cancel) break
                current = t.label.ifEmpty { Py.str(Py.get(t.c, "name", "")) }
                try {
                    val row = store.get(t.sid) ?: continue          // deleted mid-sweep
                    if (t.kind == "print") {
                        if (Py.truthy(row["selection"])) continue    // a pick landed — candidates moot
                        val cc = candidatesOf(row).firstOrNull { it["id"] == t.c["id"] }
                        if (cc == null || Py.get(cc, "f2f_conditions") != null) continue
                    } else {
                        val sel = Py.map(row["selection"]).orEmpty()
                        if (row["f2f"] != null || sel["scryfall_id"] != t.c["scryfall_id"] ||
                            Py.truthy(Py.get(sel, "foil", false)) != t.foil) continue
                    }
                    val p = lookup(t.c, t.foil)
                    if (p is F2fAnswer.Unavailable) {
                        // No writes — the target stays unsearched and retryable.
                        if (cancel) break                            // a Stop, not storefront weather
                        unavailableStreak++
                        if (unavailableStreak >= 5) {
                            backoffUntil = now() + 600
                            break
                        }
                        continue
                    }
                    unavailableStreak = 0
                    val found = p as? F2fAnswer.Found
                    if (t.kind == "scan") {
                        synchronized(api.selectLock) {
                            val r = store.get(t.sid)
                            val sel = Py.map(r?.get("selection")).orEmpty()
                            if (r != null && r["f2f"] == null && sel["scryfall_id"] == t.c["scryfall_id"] &&
                                Py.truthy(Py.get(sel, "foil", false)) == t.foil) {
                                store.update(t.sid, mapOf("f2f" to (found?.price
                                    ?: linkedMapOf("conditions" to emptyMap<String, Any?>(), "searched" to true))))
                            }
                        }
                    } else {
                        synchronized(api.selectLock) {
                            val r = store.get(t.sid)
                            if (r != null && !Py.truthy(r["selection"])) {
                                val cands = candidatesOf(r)
                                for (cc in cands) if (cc["id"] == t.c["id"]) cc["f2f_conditions"] = found?.conditions ?: emptyMap<String, Any?>()
                                store.update(t.sid, mapOf("candidates" to cands))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // anything but a lookup answer skips all writes
                } finally {
                    done++
                }
            }
        } finally {
            if (!ended) synchronized(sweepLock) { endLocked() }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun lookup(c: Map<String, Any?>, foil: Boolean): F2fAnswer = f2f.price(
        Py.str(Py.get(c, "name", "")), Py.str(Py.get(c, "set", "")),
        Py.str(Py.get(c, "collector_number", "")), foil, Py.str(Py.get(c, "set_name", "")))

    private data class Expect(val selected: Boolean, val scryfallId: Any? = null, val foil: Boolean = false)

    /** `_write_price_if_current`. */
    private fun writePriceIfCurrent(id: Long, expect: Expect, value: Map<String, Any?>?): MutableMap<String, Any?>? =
        synchronized(api.selectLock) {
            val row = store.get(id) ?: return null
            val sel = Py.map(row["selection"]).orEmpty()
            if (expect.selected) {
                if (sel["scryfall_id"] != expect.scryfallId || Py.truthy(Py.get(sel, "foil", false)) != expect.foil) return row
            } else if (sel.isNotEmpty()) return row
            store.update(id, mapOf("f2f" to value))
        }

    /** `_retro_fix_scans`: drop art-series candidates; auto-pick single / art-decisive identified rows. */
    fun retroFixScans() {
        for (s in store.list()) {
            if (Py.truthy(s["selection"])) continue
            val sid = (s["id"] as Number).toLong()
            val cands = candidatesOf(s)
            val kept = cands.filter { "art series" !in Py.str(Py.get(it, "set_name", "") ?: "").lowercase() }.toMutableList()
            if (kept.size != cands.size) {
                synchronized(api.selectLock) {
                    val row = store.get(sid)
                    if (row != null && !Py.truthy(row["selection"])) store.update(sid, mapOf("candidates" to kept))
                }
            }
            IdentifyDecisions.markArtDecisive(kept)
            if (Py.truthy(s["identified"]) && kept.isNotEmpty() &&
                (kept.size == 1 || Py.truthy(Py.get(kept[0], "art_decisive")))) {
                api.autoPick(sid, kept[0])
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun candidatesOf(row: Map<String, Any?>): MutableList<MutableMap<String, Any?>> =
        ((row["candidates"] as? List<Map<String, Any?>>).orEmpty()).map { LinkedHashMap(it) as MutableMap<String, Any?> }.toMutableList()
}
