package io.github.darylno.cardscanner.phoneserver

import android.content.Context
import io.github.darylno.cardscanner.core.server.F2fAnswer
import io.github.darylno.cardscanner.core.server.F2fLookup
import io.github.darylno.cardscanner.f2f.CronetTransport
import io.github.darylno.cardscanner.f2f.F2f
import io.github.darylno.cardscanner.f2f.F2fCache
import io.github.darylno.cardscanner.f2f.F2fFetcher
import io.github.darylno.cardscanner.f2f.F2fPricer
import io.github.darylno.cardscanner.f2f.F2fTransport
import io.github.darylno.cardscanner.f2f.OkHttpTransport
import io.github.darylno.cardscanner.f2f.PriceOutcome
import io.github.darylno.cardscanner.f2f.ProbeCard
import io.github.darylno.cardscanner.f2f.ProbeClock
import io.github.darylno.cardscanner.f2f.StopSignal
import java.io.File

/**
 * The phone's F2F client for the sweep: facetoface.get_price ([F2fPricer] —
 * query ladder, foil/collector narrowing, SKU set-code confirmation) over
 * [F2fFetcher] (slow start → AIMD pacing, 429/Retry-After handling, the 24 h
 * disk cache) on Cronet — Chrome's own TLS, which drew the generous bucket in
 * the Stage 1c test (64/64 × 200, 0 × 429). Stop ([stop]) aborts in-flight
 * waits and the request itself; a stopped lookup reads as Unavailable, never
 * as "not listed".
 */
class PhoneF2f(ctx: Context, cacheDir: File) : F2fLookup {
    val stop = StopSignal()
    private val transport: F2fTransport = try {
        CronetTransport.create(ctx.applicationContext)
    } catch (e: Throwable) {
        OkHttpTransport()                 // no packaged Cronet (tests): the pacing still protects this path
    }
    private val events = ArrayDeque<Map<String, Any?>>()
    val fetcher = F2fFetcher(transport, ProbeClock.SYSTEM, stop, isStopped = { stop.isSet },
        onRequest = { r ->
            io.github.darylno.cardscanner.core.DebugLog.global.i("f2f", "${r.kind} → ${r.outcome}" + (if (r.waitS > 0) " · waited ${r.waitS}s" else "") +
                " · pace ${Math.round(r.paceS * 100) / 100.0}s")
            synchronized(events) {
                events.addLast(linkedMapOf("t" to System.currentTimeMillis() / 1000.0, "url" to r.kind,
                    "status" to r.outcome, "wait_s" to r.waitS, "delay_s" to Math.round(r.paceS * 100) / 100.0))
                while (events.size > 80) events.removeFirst()
            }
        },
        cache = F2fCache(cacheDir))
    private val pricer = F2fPricer(fetcher, isStopped = { stop.isSet })

    init { stop.onSet { transport.cancel() } }

    override fun price(name: String, setCode: String, collectorNumber: String, foil: Boolean, setName: String): F2fAnswer =
        when (val o = pricer.price(ProbeCard(name, setCode, setName, collectorNumber, foil))) {
            is PriceOutcome.Found -> F2fAnswer.Found(linkedMapOf(
                "name" to name, "set_code" to setCode, "collector_number" to collectorNumber, "foil" to foil,
                "handle" to o.handle, "url" to "${F2f.BASE}/products/${o.handle}",
                "conditions" to LinkedHashMap<String, Any?>(o.conditions)))
            PriceOutcome.NotListed -> F2fAnswer.NotListed
            is PriceOutcome.Unavailable -> F2fAnswer.Unavailable(o.what)
            PriceOutcome.Stopped -> F2fAnswer.Unavailable("stopped")
        }

    fun paceS(): Double = fetcher.pacer.delay

    fun recentEvents(): List<Map<String, Any?>> = synchronized(events) { events.toList() }
}
