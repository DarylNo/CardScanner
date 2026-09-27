package io.github.darylno.cardscanner.f2f

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Stage 1c measurement tool (Settings → Diagnostics → long-press → Network
 * test): a PORT of the rig's Face to Face Games client, mtg_card_scanner/
 * facetoface.py — same headers, same adaptive pacing, same two-attempt retry —
 * so what the phone measures is what the rig's sweep would do from the phone.
 * Nothing in the normal app uses this package.
 *
 * Deliberately NOT ported: the 24h disk cache (we are measuring the network).
 * The pacing parity is proven by F2fPacingParityTest against a trace the real
 * Python code produced (scripts/export_f2f_pacing_fixture.py) — port, never
 * re-tune; change facetoface.py first.
 */
object F2f {
    const val BASE = "https://facetofacegames.com"

    /** facetoface._USER_AGENT, verbatim: identified bots are throttled hard. */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** The session headers _default_get_json sets, in its order. */
    val HEADERS: Map<String, String> = linkedMapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Accept-Language" to "en-CA,en;q=0.9",
    )

    const val START_DELAY = 2.0     // slow start: 1 req / 2s
    const val FLOOR_DELAY = 0.5
    const val CEIL_DELAY = 10.0
    const val SPEEDUP = 0.9         // × on success
    const val SLOWDOWN = 2.0        // × on 429
    const val IDLE_RESET_S = 120.0  // quiet this long → next burst slow-starts
    const val TIMEOUT_S = 8L
    const val SUGGEST_LIMIT = 20
}

/** Monotonic seconds (time.monotonic()). */
fun interface ProbeClock {
    fun nowS(): Double

    companion object {
        val SYSTEM = ProbeClock { System.nanoTime() / 1e9 }
    }
}

/** An interruptible wait (facetoface._wait): true = aborted by Stop. */
fun interface Waiter {
    fun await(seconds: Double): Boolean
}

/**
 * threading.Event for the Stop button: [await] sleeps but wakes the moment
 * [set] is called; [onSet] hooks let an in-flight request be cancelled too.
 */
class StopSignal : Waiter {
    private val lock = Object()
    @Volatile var isSet: Boolean = false
        private set
    private val hooks = mutableListOf<() -> Unit>()

    fun set() {
        val toRun: List<() -> Unit>
        synchronized(lock) {
            isSet = true
            lock.notifyAll()
            toRun = hooks.toList()
        }
        toRun.forEach { runCatching { it() } }
    }

    fun clear() = synchronized(lock) { isSet = false }

    fun onSet(hook: () -> Unit) = synchronized(lock) { hooks += hook }

    override fun await(seconds: Double): Boolean {
        val deadline = System.nanoTime() + (seconds * 1e9).toLong()
        synchronized(lock) {
            while (!isSet) {
                val leftMs = (deadline - System.nanoTime()) / 1_000_000
                if (leftMs <= 0) return false
                lock.wait(leftMs)
            }
            return true
        }
    }
}

/**
 * facetoface._throttle / _feedback: slow start → AIMD. Successes speed up
 * ×0.9 down to the floor, 429s double up to the ceiling, non-429 errors change
 * nothing, and 120 s of quiet resets to the slow start.
 */
class Pacer(private val clock: ProbeClock, private val waiter: Waiter) {
    @Volatile var delay: Double = F2f.START_DELAY
        private set
    private var last: Double? = null      // Python: 0.0 vs a huge monotonic() → "idle"

    /** Wait out the current spacing. True = aborted. */
    @Synchronized
    fun throttle(): Boolean {
        val now = clock.nowS()
        val l = last
        if (l == null || now - l > F2f.IDLE_RESET_S) delay = F2f.START_DELAY
        val elapsed = if (l == null) Double.POSITIVE_INFINITY else now - l
        var aborted = false
        if (elapsed < delay) aborted = waiter.await(delay - elapsed)
        last = clock.nowS()
        return aborted
    }

    @Synchronized
    fun feedback(ok: Boolean) {
        delay = if (ok) maxOf(F2f.FLOOR_DELAY, delay * F2f.SPEEDUP)
        else minOf(F2f.CEIL_DELAY, delay * F2f.SLOWDOWN)
    }
}

/** One HTTP answer from a transport (any status — the fetcher judges it). */
data class TransportResponse(
    val status: Int,
    val body: String,
    val protocol: String?,
    val retryAfter: String?,
)

/** The network under test. Throws IOException on transport failure. */
interface F2fTransport {
    /** Short id for reports: "okhttp" / "cronet". */
    val name: String

    /** Human detail for the report (library + version, provider). */
    val detail: String

    fun get(url: String, headers: Map<String, String>, timeoutMs: Long): TransportResponse

    /** Abort the in-flight request (Stop). */
    fun cancel()

    fun close() {}
}

/** One request's outcome, for the live view and the report. */
data class RequestRecord(
    val seq: Int,
    val kind: String,          // "suggest" | "product"
    val attempt: Int,
    val status: Int?,          // HTTP status, null when no response arrived
    val outcome: String,       // "200", "429", "5xx:503", "4xx:404", "timeout", "error:<Type>", "bad-json", "cancelled"
    val latencyMs: Long,
    val paceS: Double,         // pacing delay after this request's feedback
    val waitS: Double,         // extra retry wait scheduled after it (429 / error)
    val protocol: String?,
)

/**
 * facetoface._default_get_json minus the disk cache: throttle, GET, then
 * 429 → pace down + wait min(max(Retry-After, 2×attempt), 15) and retry once;
 * other failure → wait 0.8 s and retry once; success → pace up. Two attempts,
 * then null ("unavailable", never "not listed").
 */
class F2fFetcher(
    private val transport: F2fTransport,
    private val clock: ProbeClock,
    private val waiter: Waiter,
    private val isStopped: () -> Boolean,
    private val onRequest: (RequestRecord) -> Unit = {},
    private val timeoutMs: Long = F2f.TIMEOUT_S * 1000,
) {
    val pacer = Pacer(clock, waiter)
    private var seq = 0

    private class HttpStatusError(val status: Int) : IOException("HTTP $status")

    fun getJson(url: String, kind: String): JSONObject? {
        for (attempt in 1..2) {
            if (isStopped()) return null
            if (pacer.throttle()) return null
            val t0 = clock.nowS()
            var status: Int? = null
            var protocol: String? = null
            try {
                val resp = transport.get(url, F2f.HEADERS, timeoutMs)
                status = resp.status
                protocol = resp.protocol
                val ms = ((clock.nowS() - t0) * 1000).toLong()
                if (resp.status == 429) {
                    pacer.feedback(false)
                    val wait = resp.retryAfter?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
                    val waited = minOf(maxOf(wait, 2.0 * attempt), 15.0)
                    record(kind, attempt, 429, "429", ms, waited, protocol)
                    if (waiter.await(waited)) return null
                    continue
                }
                if (resp.status >= 400) throw HttpStatusError(resp.status)
                val data = try {
                    JSONObject(resp.body)
                } catch (e: Exception) {
                    throw IOException("bad-json", e)
                }
                pacer.feedback(true)
                record(kind, attempt, resp.status, resp.status.toString(), ms, 0.0, protocol)
                return data
            } catch (e: Exception) {
                val ms = ((clock.nowS() - t0) * 1000).toLong()
                val stopped = isStopped()
                val retry = !stopped && attempt < 2
                record(kind, attempt, status, if (stopped) "cancelled" else classify(e), ms, if (retry) 0.8 else 0.0, protocol)
                if (!retry) return null
                if (waiter.await(0.8)) return null
                continue
            }
        }
        return null
    }

    private fun record(kind: String, attempt: Int, status: Int?, outcome: String, ms: Long, waitS: Double, protocol: String?) {
        onRequest(RequestRecord(++seq, kind, attempt, status, outcome, ms, pacer.delay, waitS, protocol))
    }

    companion object {
        fun classify(e: Throwable): String = when {
            e is HttpStatusError -> (if (e.status >= 500) "5xx:" else "4xx:") + e.status
            e is IOException && e.message == "bad-json" -> "bad-json"
            e is SocketTimeoutException || e is TimeoutException -> "timeout"
            e is InterruptedIOException && (e.message ?: "").contains("timeout", ignoreCase = true) -> "timeout"
            else -> "error:" + e.javaClass.simpleName
        }
    }
}
