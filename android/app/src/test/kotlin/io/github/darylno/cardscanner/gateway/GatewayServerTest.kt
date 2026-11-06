package io.github.darylno.cardscanner.gateway

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import fi.iki.elonen.NanoHTTPD
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GatewayServerTest {

    private lateinit var upstreamServer: MockWebServer
    private lateinit var gateway: GatewayServer
    private var now = 1_000_000L
    private val upClient = OkHttpClient.Builder().followRedirects(false).build()
    private val client = OkHttpClient.Builder().followRedirects(false)
        .readTimeout(20, TimeUnit.SECONDS).build()

    /** Plain-HTTP stand-in for ServerClient.proxy, as the app adapter will do it. */
    private class OkUpstream(private val base: () -> HttpUrl, private val client: OkHttpClient) : Upstream {
        override fun proxy(method: String, pathAndQuery: String, headers: Map<String, String>, body: ByteArray?): Response {
            val media = headers["content-type"]?.toMediaTypeOrNull()
            val b = base()
            val req = Request.Builder()
                .url("http://${b.host}:${b.port}$pathAndQuery")
                .method(method, body?.toRequestBody(media))
            headers.forEach { (k, v) -> req.header(k, v) }
            return client.newCall(req.build()).execute()
        }
    }

    @Before
    fun setUp() {
        upstreamServer = MockWebServer()
        upstreamServer.start()
        val base = upstreamServer.url("/")
        gateway = GatewayServer(OkUpstream({ base }, upClient), port = 0, hostname = "127.0.0.1", clock = { now })
        gateway.startServing()
    }

    @After
    fun tearDown() {
        gateway.stop()
        try { upstreamServer.shutdown() } catch (_: IOException) {}
    }

    private fun url(pathAndQuery: String) = "http://127.0.0.1:${gateway.listeningPort}$pathAndQuery"

    private fun get(pathAndQuery: String, cookie: String? = null, ua: String? = null): Response {
        val b = Request.Builder().url(url(pathAndQuery))
        cookie?.let { b.header("Cookie", it) }
        ua?.let { b.header("User-Agent", it) }
        return client.newCall(b.build()).execute()
    }

    /** Joins with the right code; returns "cs_guest=<token>". */
    private fun join(ua: String = DESKTOP_UA): String {
        get("/join?code=${gateway.code}", ua = ua).use { r ->
            assertEquals(302, r.code)
            val sc = r.header("Set-Cookie")!!
            return sc.substringBefore(';')
        }
    }

    private val rnd = java.util.Random(20_261_106)

    /** A random 6-digit code that is NOT [g]'s code right now (so a "join" can only come from a sent right code). */
    private fun wrongCode(g: GatewayServer = gateway): String {
        while (true) {
            val c = String.format(java.util.Locale.ROOT, "%06d", rnd.nextInt(1_000_000))
            if (c != g.code) return c
        }
    }

    /** One join attempt, and whether its code was actually compared (not refused by a lockout or the pause). */
    private fun GatewayServer.tryJoin(ip: String, code: String, at: Long): Pair<GatewayServer.JoinResult, Boolean> {
        val before = joinCodesCompared
        val r = attemptJoin(ip, code, at)
        return r to (joinCodesCompared != before)
    }

    /** The network-wide pause (20 wrong codes from all addresses in 5 min), not an address's own lockout. */
    private fun isPause(r: GatewayServer.JoinResult) = r is GatewayServer.JoinResult.Locked && r.paused

    // ---- join flow -------------------------------------------------------------------

    /** The live log records joins — never the code itself (a guest could read a report pasted anywhere). */
    @Test
    fun joinsAreLoggedWithoutTheCode() {
        val log = io.github.darylno.cardscanner.core.DebugLog.global
        val from = log.lastSeq
        get("/join?code=000000".let { if (gateway.code == "000000") "/join?code=111111" else it }).close()
        join()
        val lines = log.since(from).map { it.msg }
        assertTrue(lines.toString(), lines.any { it.startsWith("wrong join code from") })
        assertTrue(lines.toString(), lines.any { it.startsWith("guest joined from") })
        assertTrue(lines.toString(), lines.none { it.contains(gateway.code) })
    }

    @Test
    fun noCookieGetsCodePageAndNothingIsProxied() {
        get("/").use { r ->
            assertEquals(200, r.code)
            val body = r.body!!.string()
            assertTrue(body.contains("Enter the 6-digit code"))
            assertTrue(body.contains("action=\"/join\""))
            assertEquals("no-store", r.header("Cache-Control"))
        }
        get("/api/scans?limit=5", cookie = "cs_guest=deadbeefdeadbeefdeadbeefdeadbeef").use { r ->
            assertTrue(r.body!!.string().contains("Enter the 6-digit code"))
        }
        val post = Request.Builder().url(url("/api/scans/1/select")).post("{}".toRequestBody()).build()
        client.newCall(post).execute().use { r -> assertEquals(401, r.code) }
        assertEquals(0, upstreamServer.requestCount)
    }

    @Test
    fun badCodeIsRejectedWithoutCookie() {
        val wrong = if (gateway.code == "000000") "111111" else "000000"
        get("/join?code=$wrong").use { r ->
            assertEquals(403, r.code)
            assertNull(r.header("Set-Cookie"))
            assertTrue(r.body!!.string().contains("not right"))
        }
        get("/join?code=12345").use { r -> assertEquals(403, r.code) }
        get("/join").use { r -> assertEquals(200, r.code); assertNull(r.header("Set-Cookie")) }
        assertEquals(0, gateway.sessionCount)
    }

    @Test
    fun goodCodeSetsHardenedCookieAndRedirectsByUserAgent() {
        get("/join?code=${gateway.code}", ua = DESKTOP_UA).use { r ->
            assertEquals(302, r.code)
            assertEquals("/", r.header("Location"))
            val sc = r.header("Set-Cookie")!!
            assertTrue(sc, Regex("^cs_guest=[0-9a-f]{32}; ").containsMatchIn(sc))
            assertTrue(sc.contains("HttpOnly"))
            assertTrue(sc.contains("SameSite=Lax"))
            assertTrue(sc.contains("Path=/"))
        }
        get("/join?code=${gateway.code}", ua = PHONE_UA).use { r ->
            assertEquals("/phone?panel=1", r.header("Location"))
        }
        get("/join?code=${gateway.code}", ua = "Mozilla/5.0 (iPhone) Mobile/15E148").use { r ->
            assertEquals("/phone?panel=1", r.header("Location"))
        }
        // spaces are tolerated ("123 456")
        get("/join?code=${gateway.code.take(3)}%20${gateway.code.drop(3)}").use { r -> assertEquals(302, r.code) }
        assertEquals(4, gateway.sessionCount)
        val a = join(); val b = join()
        assertNotEquals(a, b)
    }

    /**
     * An address's 8th wrong code in 5 min locks it for 5 min — logged ONCE, when the lock starts.
     * Before, every refused join of a locked address logged a line ("join from … refused — locked
     * out"), so one device hammering /join pushed everything else out of the 3000-line debug log.
     */
    @Test
    fun lockoutAfterEightBadTriesForFiveMinutes() {
        val log = io.github.darylno.cardscanner.core.DebugLog.global
        val from = log.lastSeq
        val wrong = if (gateway.code == "000000") "111111" else "000000"
        repeat(7) { get("/join?code=$wrong").use { r -> assertEquals(403, r.code) } }
        get("/join?code=$wrong").use { r ->
            assertEquals(429, r.code)
            assertEquals("300", r.header("Retry-After"))
            val body = r.body!!.string()
            // this device's own lockout, not the network-wide pause (8 < 20 wrong codes in all)
            assertTrue(body, body.contains("Too many wrong codes from this device. Wait 5 min"))
            assertFalse(body, body.contains("on this network"))
        }
        // even the right code is refused while locked
        get("/join?code=${gateway.code}").use { r -> assertEquals(429, r.code); assertNull(r.header("Set-Cookie")) }
        repeat(5) { get("/join?code=$wrong").use { r -> assertEquals(429, r.code) } }
        now += GatewayServer.LOCKOUT_MS - 1
        get("/join?code=${gateway.code}").use { r -> assertEquals(429, r.code) }
        now += 2
        get("/join?code=${gateway.code}").use { r -> assertEquals(302, r.code) }
        val lines = log.since(from).filter { it.tag == "server" }.map { it.msg }
        assertEquals(lines.toString(), 7, lines.count { it == "wrong join code from 127.0.0.1" })
        assertEquals(lines.toString(), listOf("8 wrong join codes from 127.0.0.1 within 5 min — that address is locked out 5 min"),
            lines.filter { it.contains("locked") })
        assertTrue(lines.toString(), lines.none { it.contains(wrong) })
    }

    @Test
    fun failuresOutsideTheWindowDoNotAccumulate() {
        val wrong = if (gateway.code == "000000") "111111" else "000000"
        repeat(7) { get("/join?code=$wrong").use { r -> assertEquals(403, r.code) } }
        now += GatewayServer.FAIL_WINDOW_MS + 1
        repeat(7) { get("/join?code=$wrong").use { r -> assertEquals(403, r.code) } }
        get("/join?code=${gateway.code}").use { r -> assertEquals(302, r.code) }
    }

    // ---- global budget / atomic attempts / LAN-only -----------------------------------

    /**
     * The 20th wrong code from all addresses still rotates the code — and now also PAUSES joining
     * for every address until the oldest of the 20 leaves the 5-min window. Before (≤ 4064c73)
     * this test asserted the opposite half: the rotation reset every count ("127.0.0.1's 7
     * failures were reset") and the new code joined at once — the reset is what let a search
     * spread over 3 addresses run forever (see [aSearchSpreadOverThreeAddressesComparesAtMostTwentyCodes]).
     */
    @Test
    fun globalBudgetRotatesTheCodeAndPausesJoiningAfterTwentyDistributedFailures() {
        val cookie = join()
        val oldCode = gateway.code
        val wrong = if (oldCode == "000000") "111111" else "000000"
        val rotated = mutableListOf<String>()
        gateway.onCodeRotated = { synchronized(rotated) { rotated += it } }
        val log = io.github.darylno.cardscanner.core.DebugLog.global
        val from = log.lastSeq
        // 7 over HTTP from 127.0.0.1 (one short of its per-IP lock) …
        repeat(7) { get("/join?code=$wrong").use { r -> assertEquals(403, r.code) } }
        // … and 12 more spread over addresses no per-IP lock ever sees twice.
        for (i in 0 until 12) {
            val ip = if (i % 2 == 0) "fe80::1234:${i}" else "192.168.43.${10 + i}"
            assertTrue(gateway.attemptJoin(ip, wrong, now) is GatewayServer.JoinResult.Wrong)
        }
        assertEquals(oldCode, gateway.code)
        assertTrue(rotated.isEmpty())
        // the 20th failure in the window rotates the code and pauses joining (its own answer says so)
        val twentieth = gateway.attemptJoin("fe80::beef", wrong, now)
        assertTrue(isPause(twentieth))
        assertEquals(GatewayServer.FAIL_WINDOW_MS, (twentieth as GatewayServer.JoinResult.Locked).remainingMs)
        val newCode = gateway.code
        assertNotEquals(oldCode, newCode)   // 1-in-a-million flake would need a retry here
        assertEquals(listOf(newCode), rotated)

        // paused: the old code, the NEW code and a blank /join all get the network-wide 429
        for (q in listOf("?code=$oldCode", "?code=$newCode", "")) {
            get("/join$q").use { r ->
                assertEquals(q, 429, r.code)
                assertNull(r.header("Set-Cookie"))
                assertEquals("300", r.header("Retry-After"))
                val body = r.body!!.string()
                assertTrue(body, body.contains("Too many wrong codes on this network — joining is paused for 5 min"))
                assertFalse(body, body.contains("from this device"))
                assertFalse(body, body.contains(newCode))
            }
        }
        // logged ONCE (not per refused join), never with a code
        val lines = log.since(from).filter { it.tag == "server" }.map { it.msg }
        assertEquals(lines.toString(), 1, lines.count { it.contains("joining paused") })
        assertTrue(lines.toString(), lines.any { it == "20 wrong join codes within 5 min — guest code rotated, joining paused 5 min" })
        assertTrue(lines.toString(), lines.none { it.contains(oldCode) || it.contains(newCode) })
        // the existing session still works
        upstreamServer.enqueue(MockResponse().setBody("still in"))
        get("/api/version", cookie = cookie).use { r -> assertEquals("still in", r.body!!.string()) }
        assertEquals(1, gateway.sessionCount)
        // once the oldest wrong code is 5 min old the pause is over: the old code is wrong, the new one joins
        now += GatewayServer.FAIL_WINDOW_MS
        get("/join?code=$oldCode").use { r -> assertEquals(403, r.code); assertNull(r.header("Set-Cookie")) }
        get("/join?code=$newCode").use { r -> assertEquals(302, r.code) }
    }

    /**
     * The final review's attack on 1.1.19 (confirmed by a probe that joined after ~0.4–2.4 M
     * guesses at one frozen instant, never locked): three LAN addresses — IPv4 aliases,
     * link-local IPv6, all [GatewayServer.isLocalClient] — send 7 / 7 / 6 wrong codes in turn,
     * one short of each per-IP lock; the 20th rotated the code and RESET every count, round after
     * round, so guessing was bounded only by the request rate. Now 100 000 attempts at one
     * instant compare at most 20 codes, rotate the code once, log once — and a guess that lands
     * on the code (every 5 000th sends the right one) is refused like any other.
     */
    @Test
    fun aSearchSpreadOverThreeAddressesComparesAtMostTwentyCodes() {
        val rotated = mutableListOf<String>()
        gateway.onCodeRotated = { rotated += it }
        val log = io.github.darylno.cardscanner.core.DebugLog.global
        val from = log.lastSeq
        val plan = listOf("192.168.43.10" to 7, "192.168.43.11" to 7, "fe80::1c2b:3a4d:5e6f:7081" to 6)
            .flatMap { (ip, n) -> List(n) { ip } }                       // one round = 20 attempts
        var compared = 0
        var joined = 0
        var rightSent = 0
        for (k in 0 until 100_000) {
            val code = if (k % 5_000 == 4_999) gateway.code.also { rightSent++ } else wrongCode()
            val (r, c) = gateway.tryJoin(plan[k % plan.size], code, now)
            if (c) compared++
            if (r is GatewayServer.JoinResult.Joined || r is GatewayServer.JoinResult.JoinedAdmin) joined++
        }
        val facts = "compared $compared of 100000, joined $joined, rotations ${rotated.size}"
        println("3 addresses, 7 / 7 / 6, frozen clock: $facts")
        assertEquals(20, rightSent)
        assertEquals(facts, 0, joined)
        assertTrue(facts, compared <= GatewayServer.GLOBAL_MAX_FAILURES)
        assertEquals(facts, 1, rotated.size)
        assertEquals(facts, 1, log.since(from).count { it.tag == "server" && it.msg.contains("joining paused") })
        // a fresh address with the right code: still the pause, not a join
        assertTrue(isPause(gateway.attemptJoin("192.168.43.99", gateway.code, now)))
    }

    /**
     * The same search with time running: 50 attempts a second for an hour, round-robin over five
     * addresses (IPv4, IPv6 link-local and ULA, IPv4-mapped). No 5-min window ever sees more than
     * 20 codes compared — ≤ 12 × 20 + 20 in the hour — and nothing joins. Each slot the search
     * refills as it frees starts a pause again (a rotation, one log line): the log grows with the
     * codes compared, never with the 180 000 refused.
     */
    @Test
    fun anHourOfAttackFromFiveAddressesComparesAtMostTwentyCodesPerFiveMinutes() {
        val ips = listOf("192.168.1.20", "10.0.0.7", "fe80::a1b2:c3ff:fed4:e5f6", "fd7a:115c:a1e0::9", "::ffff:192.168.1.21")
        val t0 = now
        val comparedAt = ArrayList<Long>()
        var joined = 0
        var rotations = 0
        gateway.onCodeRotated = { rotations++ }
        val log = io.github.darylno.cardscanner.core.DebugLog.global
        val from = log.lastSeq
        val attempts = 3_600 * 50
        for (k in 0 until attempts) {
            val t = t0 + k * 20L
            val (r, c) = gateway.tryJoin(ips[k % ips.size], wrongCode(), t)
            if (c) comparedAt += t
            if (r is GatewayServer.JoinResult.Joined || r is GatewayServer.JoinResult.JoinedAdmin) joined++
        }
        var worst = 0
        var j = 0
        for (i in comparedAt.indices) {                                  // the busiest 5-min window
            while (comparedAt[i] - comparedAt[j] >= GatewayServer.FAIL_WINDOW_MS) j++
            worst = maxOf(worst, i - j + 1)
        }
        val pauseLines = log.since(from, limit = 3_000).count { it.tag == "server" && it.msg.contains("joining paused") }
        val facts = "compared ${comparedAt.size} of $attempts in the hour, busiest 5 min $worst, joined $joined, " +
            "rotations $rotations, pause lines logged $pauseLines"
        println("5 addresses, 50 / s for an hour: $facts")
        assertEquals(facts, 0, joined)
        assertTrue(facts, worst <= GatewayServer.GLOBAL_MAX_FAILURES)
        assertTrue(facts, comparedAt.size <= 12 * GatewayServer.GLOBAL_MAX_FAILURES + GatewayServer.GLOBAL_MAX_FAILURES)
        // one line per pause (a compared wrong code that fills the window), never one per refused join
        assertEquals(facts, rotations, pauseLines)
        assertTrue(facts, rotations <= comparedAt.size)
    }

    /**
     * Re-review of the pause: a search paced just UNDER it — one wrong code every 15.79 s (5 min / 19),
     * round-robin over 3 addresses so none reaches its own 8 — never fills the 5-min window, so with a
     * rotation only at a pause the code never changed and the search walked 000000…999999 against ONE
     * code (a probe joined on guess 132 708, ≈ 24 days; ≈ 91 days on average, not the ≈ 174 the pause
     * promised). The code now also changes after every [GatewayServer.GLOBAL_MAX_FAILURES] wrong codes
     * compared since it last changed, however slowly they came: no code ever faces more than 20 guesses,
     * so each ≤ 20 are independent draws against a fresh code. No pause (the search stays under it), one
     * `server` line per change, and the Share screen hears each new code.
     */
    @Test
    fun aSearchPacedUnderThePauseStillChangesTheCodeEveryTwentyWrongCodes() {
        val ips = listOf("192.168.43.10", "192.168.43.11", "fe80::1c2b:3a4d:5e6f:7081")
        val rotated = mutableListOf<String>()
        var epoch = 0                                    // which code (counted by change, never by its digits:
        gateway.onCodeRotated = { rotated += it; epoch++ }   // two of 100 random codes repeat 1 run in 200)
        val log = io.github.darylno.cardscanner.core.DebugLog.global
        val from = log.lastSeq
        val step = GatewayServer.FAIL_WINDOW_MS / (GatewayServer.GLOBAL_MAX_FAILURES - 1) + 1
        val perCode = HashMap<Int, Int>()
        var compared = 0
        var refused = 0
        val attempts = 2_000
        for (k in 0 until attempts) {
            val target = epoch
            val (r, c) = gateway.tryJoin(ips[k % ips.size], wrongCode(), now + k * step)
            if (c) { compared++; perCode.merge(target, 1, Int::plus) }
            if (r is GatewayServer.JoinResult.Locked) refused++
            assertFalse(r is GatewayServer.JoinResult.Joined || r is GatewayServer.JoinResult.JoinedAdmin)
        }
        val worst = perCode.values.maxOrNull() ?: 0
        val lines = log.since(from, limit = 3_000).filter { it.tag == "server" && it.msg.contains("guest code rotated") }
        val facts = "compared $compared of $attempts, refused $refused, codes ${perCode.size}, most guesses at one code $worst, " +
            "rotations ${rotated.size}, rotation lines ${lines.size}"
        println("paced under the pause (one per ${step} ms, 3 addresses): $facts")
        assertEquals(facts, attempts, compared)                       // under the pause: every code compared
        assertEquals(facts, 0, refused)
        assertTrue(facts, worst <= GatewayServer.GLOBAL_MAX_FAILURES) // no code faces more than 20 guesses
        assertEquals(facts, attempts / GatewayServer.GLOBAL_MAX_FAILURES, rotated.size)
        assertEquals(facts, rotated.size, lines.size)
        assertTrue(facts, lines.none { it.msg.contains("joining paused") })
        assertTrue(facts, lines.none { l -> rotated.any { l.msg.contains(it) } })   // never the code
    }

    /**
     * Re-review of the pause: the failure windows run on the wall clock, and a clock set BACK (NTP fixing a
     * fast clock, the owner) while the 20 are inside the window left them stamped in the "future" — joining
     * stayed paused for the whole step (1445 min in a probe), and an address's own lockout likewise. An entry
     * stamped after now now counts as now, and a lockout never runs past [GatewayServer.LOCKOUT_MS] from now:
     * a step back of a day leaves at most the usual 5 min.
     */
    @Test
    fun aClockSetBackNeverLeavesMoreThanFiveMinutesOfPauseOrLockout() {
        now = 1_762_400_000_000L                // a real wall clock (2025-11), so a day back is still a real time
        val t0 = now
        val day = 24 * 60 * 60_000L
        // the network-wide pause
        for (i in 0 until GatewayServer.GLOBAL_MAX_FAILURES) gateway.attemptJoin("10.50.0.${i + 1}", wrongCode(), t0)
        assertTrue(isPause(gateway.attemptJoin("10.50.1.1", gateway.code, t0)))
        val back = t0 - day
        val r = gateway.attemptJoin("10.50.1.2", gateway.code, back)
        assertTrue("still a pause right after the step back", isPause(r))
        assertTrue("…of at most 5 min, not a day (${(r as GatewayServer.JoinResult.Locked).remainingMs} ms)",
            r.remainingMs <= GatewayServer.FAIL_WINDOW_MS)
        assertTrue("the current code joins 5 min after the step back",
            gateway.attemptJoin("10.50.1.3", gateway.code, back + GatewayServer.FAIL_WINDOW_MS + 1) is GatewayServer.JoinResult.Joined)
        // an address's own lockout
        val t1 = back + 2 * GatewayServer.FAIL_WINDOW_MS
        repeat(GatewayServer.MAX_FAILURES) { gateway.attemptJoin("10.60.0.1", wrongCode(), t1) }
        val own = gateway.attemptJoin("10.60.0.1", gateway.code, t1 - day)
        assertTrue("locked right after the step back", own is GatewayServer.JoinResult.Locked && !own.paused)
        assertTrue("…for at most 5 min (${(own as GatewayServer.JoinResult.Locked).remainingMs} ms)",
            own.remainingMs <= GatewayServer.LOCKOUT_MS)
        assertTrue("its code joins once that has passed",
            gateway.attemptJoin("10.60.0.1", gateway.code, t1 - day + GatewayServer.LOCKOUT_MS + 1) is GatewayServer.JoinResult.Joined)
    }

    /** The pause is a wait, not a lock-out for good: once it lapses a guest with the code the phone shows NOW joins. */
    @Test
    fun afterThePauseLapsesAGuestWithTheCurrentCodeJoins() {
        val t0 = now
        // 20 wrong codes over 2 minutes from 20 addresses: the 20th rotates the code and starts the pause
        for (i in 0 until GatewayServer.GLOBAL_MAX_FAILURES) gateway.attemptJoin("10.9.0.${i + 1}", wrongCode(), t0 + i * 6_000L)
        val code = gateway.code
        // until the first of them is 5 min old, even the current code is refused — from any address
        now = t0 + 2 * 60_000L
        get("/join?code=$code").use { resp ->
            assertEquals(429, resp.code)
            assertEquals("180", resp.header("Retry-After"))
            val body = resp.body!!.string()
            assertTrue(body, body.contains("joining is paused for 3 min"))
        }
        now = t0 + GatewayServer.FAIL_WINDOW_MS - 1
        val r = gateway.attemptJoin("192.168.50.5", code, now)
        assertTrue(isPause(r))
        assertEquals(1L, (r as GatewayServer.JoinResult.Locked).remainingMs)
        get("/join?code=$code").use { resp ->
            assertEquals(429, resp.code)
            assertEquals("1", resp.header("Retry-After"))
            val body = resp.body!!.string()
            assertTrue(body, body.contains("joining is paused for 1 s"))      // never "1 min" for the last millisecond
        }
        // …then the current code joins, over HTTP like a guest's phone
        now = t0 + GatewayServer.FAIL_WINDOW_MS
        get("/join?code=$code", ua = PHONE_UA).use { resp ->
            assertEquals(302, resp.code)
            assertEquals("/phone?panel=1", resp.header("Location"))
        }
        assertEquals(1, gateway.sessionCount)
    }

    /**
     * The cost of the pause falls on NEW joins only: a guest already in, the paired computer
     * (its `cs_admin` cookie) and the phone's own review screen (the owner token) never need a
     * code, so they carry on with their roles as before. A pairing code made during the pause
     * waits behind it like the 6-digit code, and pairs once it lapses.
     */
    @Test
    fun duringThePauseSessionsAlreadyInThePairedComputerAndTheOwnerCarryOn() {
        var full = true
        val admins = AdminPairing(null, clock = { now })
        val base = upstreamServer.url("/")
        val g = GatewayServer(OkUpstream({ base }, upClient), port = 0, hostname = "127.0.0.1", clock = { now },
            admins = admins, guestsFullAccess = { full })
        g.startServing()
        try {
            fun call(p: String, cookie: String? = null): Response {
                val b = Request.Builder().url("http://127.0.0.1:${g.listeningPort}$p")
                cookie?.let { b.header("Cookie", it) }
                return client.newCall(b.build()).execute()
            }
            fun roleSent(cookie: String): String? {
                upstreamServer.enqueue(MockResponse().setBody("ok"))
                call("/api/scans", cookie).use { r -> assertEquals(200, r.code); assertEquals("ok", r.body!!.string()) }
                return upstreamServer.takeRequest(5, TimeUnit.SECONDS)!!.getHeader(GatewayServer.ROLE_HEADER)
            }
            val guest = call("/join?code=${g.code}").use { it.header("Set-Cookie")!!.substringBefore(';') }
            val computer = call("/join?code=${admins.newCode()}").use { r ->
                r.headers("Set-Cookie").first { it.startsWith(GatewayServer.ADMIN_COOKIE) }.substringBefore(';')
            }
            val owner = "${GatewayServer.ADMIN_COOKIE}=${admins.ownerToken}"
            // the attack: 20 wrong codes from 20 addresses
            for (i in 0 until GatewayServer.GLOBAL_MAX_FAILURES) g.attemptJoin("10.20.0.${i + 1}", wrongCode(g), now)
            assertTrue(isPause(g.attemptJoin("10.20.1.1", g.code, now)))
            // a NEW guest with the right code, and a pairing code made now: both refused, no cookie
            val pairing = admins.newCode()
            for (code in listOf(g.code, pairing)) {
                call("/join?code=$code").use { r ->
                    assertEquals(429, r.code)
                    assertTrue(r.headers("Set-Cookie").isEmpty())
                    assertTrue(r.body!!.string().contains("on this network"))
                }
            }
            // everyone already in carries on, the role header as before
            assertEquals("guest, full access on", GatewayServer.ROLE_ADMIN, roleSent(guest))
            full = false
            assertEquals("guest, full access off", GatewayServer.ROLE_GUEST, roleSent(guest))
            assertEquals("paired computer", GatewayServer.ROLE_ADMIN, roleSent(computer))
            assertEquals("the phone's own review screen", GatewayServer.ROLE_ADMIN, roleSent(owner))
            // pause over: the pairing code (10 min) pairs
            now += GatewayServer.FAIL_WINDOW_MS
            call("/join?code=$pairing").use { r ->
                assertEquals(302, r.code)
                assertTrue(r.headers("Set-Cookie").any { it.startsWith(GatewayServer.ADMIN_COOKIE + "=") })
            }
        } finally {
            g.stop()
        }
    }

    /**
     * What survives from the old rules: an address still locks at its 8th wrong code in 5 min
     * (its own lockout, not the pause) — and its count now survives a rotation, which used to
     * clear it (so 7 + 7 + … from one address after each rotation never locked).
     */
    @Test
    fun anAddressStillLocksAtItsEighthAndItsCountSurvivesARotation() {
        val t0 = now
        val code0 = gateway.code
        // 13 wrong from others at t0, then 7 from A a minute later: the 20th (A's 7th) rotates + pauses
        for (i in 0 until 13) assertTrue(gateway.attemptJoin("10.30.0.${i + 1}", wrongCode(), t0) is GatewayServer.JoinResult.Wrong)
        repeat(6) { assertTrue(gateway.attemptJoin("192.168.7.7", wrongCode(), t0 + 60_000) is GatewayServer.JoinResult.Wrong) }
        assertTrue(isPause(gateway.attemptJoin("192.168.7.7", wrongCode(), t0 + 60_000)))
        assertNotEquals(code0, gateway.code)
        // the pause ends when the 13 leave the window (t0 + 5 min); A's 7 are still in it, so A's 8th locks A
        val eighth = gateway.attemptJoin("192.168.7.7", wrongCode(), t0 + GatewayServer.FAIL_WINDOW_MS)
        assertTrue("A's 8th wrong code in 5 min: $eighth", eighth is GatewayServer.JoinResult.Locked && !isPause(eighth))
        assertEquals(GatewayServer.LOCKOUT_MS, (eighth as GatewayServer.JoinResult.Locked).remainingMs)
        // A is locked — even with the right code — while another address joins
        assertTrue(gateway.attemptJoin("192.168.7.7", gateway.code, t0 + GatewayServer.FAIL_WINDOW_MS + 1) is GatewayServer.JoinResult.Locked)
        assertTrue(gateway.attemptJoin("192.168.7.8", gateway.code, t0 + GatewayServer.FAIL_WINDOW_MS + 1) is GatewayServer.JoinResult.Joined)
    }

    /**
     * The PAUSE is a window, not a lifetime count: 19 wrong codes, then a 20th after they left the
     * window, pause nothing. The CODE, though, changes at that 20th — 20 wrong codes since it last
     * changed, however slowly (re-review: before, a search paced under the pause faced one code
     * forever; this test pinned that the code stayed). Right codes count toward neither.
     */
    @Test
    fun thePauseIsAWindowButTheCodeChangesAfterTwentyWrongCodesWhateverTheirPace() {
        val oldCode = gateway.code
        val wrong = if (oldCode == "000000") "111111" else "000000"
        var rotations = 0
        gateway.onCodeRotated = { rotations++ }
        for (i in 0 until 19) gateway.attemptJoin("10.0.0.${i + 2}", wrong, now)
        assertEquals(0, rotations)
        now += GatewayServer.FAIL_WINDOW_MS + 1
        val twentieth = gateway.attemptJoin("10.0.1.1", wrong, now)
        assertTrue("the 20th, past the window, pauses nothing", twentieth is GatewayServer.JoinResult.Wrong)
        val newCode = gateway.code
        assertEquals("…but the code changed (a rotation — a new random code may repeat the digits, 1 in 10⁶)", 1, rotations)
        assertTrue("a guest with the new code joins at once (no pause)",
            gateway.attemptJoin("10.0.2.1", newCode, now) is GatewayServer.JoinResult.Joined)
        // right codes count toward neither
        repeat(30) { gateway.attemptJoin("10.0.2.${it + 2}", newCode, now) }
        assertEquals(1, rotations)
    }

    @Test
    fun parallelBadAttemptsFromOneIpNeverEvaluateMoreThanEight() {
        val oldCode = gateway.code
        val wrong = if (oldCode == "000000") "111111" else "000000"
        val threads = 64
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until threads).map {
                pool.submit<GatewayServer.JoinResult> { start.await(); gateway.attemptJoin("10.1.2.3", wrong, now) }
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            // 7 plain rejections + the 8th that lands the lock; everything else refused unevaluated
            assertEquals(7, results.count { it is GatewayServer.JoinResult.Wrong })
            assertEquals(threads - 7, results.count { it is GatewayServer.JoinResult.Locked })
        } finally {
            pool.shutdownNow()
        }
        // if more than 8 had been evaluated as failures, 20 would have rotated the code
        assertEquals(oldCode, gateway.code)
        // locked even for the right code
        assertTrue(gateway.attemptJoin("10.1.2.3", oldCode, now) is GatewayServer.JoinResult.Locked)
    }

    /**
     * The pause check, the comparison and the failure record are one critical section for the
     * network-wide count too: 64 addresses guessing at the same instant get 19 plain rejections,
     * the 20th starts the pause, and the other 44 are refused without being compared.
     */
    @Test
    fun parallelWrongCodesFromManyAddressesNeverCompareMoreThanTwenty() {
        val codes = List(64) { wrongCode() }
        val rotations = java.util.concurrent.atomic.AtomicInteger()
        gateway.onCodeRotated = { rotations.incrementAndGet() }
        val comparedBefore = gateway.joinCodesCompared
        val pool = Executors.newFixedThreadPool(codes.size)
        val start = CountDownLatch(1)
        val results = try {
            val futures = codes.mapIndexed { i, c ->
                pool.submit<GatewayServer.JoinResult> { start.await(); gateway.attemptJoin("10.3.${i / 8}.${i % 8 + 1}", c, now) }
            }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(GatewayServer.GLOBAL_MAX_FAILURES - 1, results.count { it is GatewayServer.JoinResult.Wrong })
        assertEquals(codes.size - GatewayServer.GLOBAL_MAX_FAILURES + 1, results.count { isPause(it) })
        assertEquals(GatewayServer.GLOBAL_MAX_FAILURES.toLong(), gateway.joinCodesCompared - comparedBefore)
        assertEquals(1, rotations.get())
    }

    /**
     * New guest code (the owner's own press on the Share screen, `stopSharing`) ends a pause along
     * with every guest session: the phone's owner can always start over. A search still running
     * then gets 20 more codes before joining pauses again.
     */
    @Test
    fun newGuestCodeEndsAPause() {
        for (i in 0 until GatewayServer.GLOBAL_MAX_FAILURES) gateway.attemptJoin("10.40.0.${i + 1}", wrongCode(), now)
        assertTrue(isPause(gateway.attemptJoin("10.40.1.1", gateway.code, now)))
        val code = gateway.stopSharing()
        get("/join?code=$code").use { r -> assertEquals(302, r.code) }
        for (i in 0 until GatewayServer.GLOBAL_MAX_FAILURES - 1) {
            assertTrue(gateway.attemptJoin("10.40.2.${i + 1}", wrongCode(), now) is GatewayServer.JoinResult.Wrong)
        }
        assertTrue(isPause(gateway.attemptJoin("10.40.3.1", wrongCode(), now)))
    }

    @Test
    fun parallelBadHttpJoinsFromOneIpGetSevenRejectionsThenLockout() {
        val wrong = if (gateway.code == "000000") "111111" else "000000"
        val n = 24
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until n).map {
                pool.submit<Int> {
                    start.await()
                    // a fresh client per call: separate connections, separate server threads
                    OkHttpClient.Builder().followRedirects(false).build()
                        .newCall(Request.Builder().url(url("/join?code=$wrong")).build()).execute().use { it.code }
                }
            }
            start.countDown()
            val codes = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(codes.toString(), 7, codes.count { it == 403 })
            assertEquals(codes.toString(), n - 7, codes.count { it == 429 })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun isLocalClientRanges() {
        val allowed = listOf(
            "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.43.7",
            "127.0.0.1", "127.8.9.10",
            "fe80::1", "fe80::a1b2:c3ff:fed4:e5f6%wlan0", "FE80::1", "febf:ffff::1", "[fe80::1]",
            "fc00::1", "fd7a:115c:a1e0::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "::1", "0:0:0:0:0:0:0:1",
            "::ffff:192.168.1.5", "::ffff:10.0.0.2", "::FFFF:172.20.1.1", "::ffff:c0a8:0105", "0:0:0:0:0:ffff:127.0.0.1",
        )
        val refused = listOf(
            "100.64.0.1", "100.100.100.100", "100.127.255.255",          // Tailscale / CGNAT
            "8.8.8.8", "172.15.255.255", "172.32.0.1", "192.169.0.1", "11.0.0.1", "169.254.1.1", "0.0.0.0",
            "255.255.255.255", "1.2.3.4",
            "2001:db8::1", "2607:fb90:1234::5", "fec0::1", "fe7f::1", "ff02::1", "::", "fb00::1",
            "::ffff:100.64.0.1", "::ffff:8.8.8.8", "::192.168.1.1", "64:ff9b::192.168.1.1",
            "", "?", "localhost", "10.0.0", "10.0.0.256", "10.0.0.1.2", "1::2::3", "fe80::1::", "fe80:::1",
            "fe80::12345", "gggg::1", "fe80::+1", "fe80::１", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7", "10.0.0.1 ",
        )
        for (ip in allowed) assertTrue("should allow '$ip'", GatewayServer.isLocalClient(ip))
        for (ip in refused) {
            if (ip == "10.0.0.1 ") continue   // trimmed → allowed; listed only to document trimming
            assertFalse("should refuse '$ip'", GatewayServer.isLocalClient(ip))
        }
        assertTrue(GatewayServer.isLocalClient("10.0.0.1 "))
    }

    @Test
    fun offLanClientIsRefusedBeforeAnythingElse() {
        // NanoHTTPD reports every loopback peer as 127.0.0.1, so drive serve() with a session
        // whose peer is a Tailscale / public address; the real socket path is covered below.
        for (ip in listOf("100.101.102.103", "2607:fb90::5", "::ffff:100.64.1.1")) {
            for (path in listOf("/join", "/", "/api/scans")) {
                val r = gateway.serve(FakeSession(ip, path, mapOf("code" to listOf(gateway.code))))
                assertEquals(403, r.status.requestStatus)
                val body = r.data.readBytes().toString(Charsets.UTF_8)
                assertTrue(body, body.contains("Only devices on this phone's Wi-Fi or hotspot can join"))
            }
        }
        assertEquals(0, gateway.sessionCount)
        assertEquals(0, upstreamServer.requestCount)
        // a LAN peer through the same entry point gets the normal code page
        assertEquals(200, gateway.serve(FakeSession("192.168.43.20", "/", emptyMap())).status.requestStatus)
        // end to end over a real socket: loopback is local
        get("/").use { r -> assertEquals(200, r.code); assertTrue(r.body!!.string().contains("Enter the 6-digit code")) }
    }

    private class FakeSession(
        private val ip: String,
        private val path: String,
        private val params: Map<String, List<String>>,
    ) : NanoHTTPD.IHTTPSession {
        override fun execute() {}
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = mapOf("remote-addr" to "127.0.0.1")
        override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getMethod() = NanoHTTPD.Method.GET
        @Deprecated("NanoHTTPD API") override fun getParms(): Map<String, String> = params.mapValues { it.value.first() }
        override fun getParameters(): Map<String, List<String>> = params
        override fun getQueryParameterString(): String? = null
        override fun getUri() = path
        override fun parseBody(files: MutableMap<String, String>?) {}
        override fun getRemoteIpAddress() = ip
        override fun getRemoteHostName() = ip
    }

    // ---- proxy -----------------------------------------------------------------------

    @Test
    fun proxiesGetWithQueryStatusAndHeaders() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setHeader("Cache-Control", "no-store")
            .setHeader("X-Server", "cardscanner")
            .setBody("""{"scans":[]}"""))
        get("/api/scans?limit=5&q=Shivan%20Dragon&x=a%2Bb", cookie = cookie).use { r ->
            assertEquals(200, r.code)
            assertEquals("""{"scans":[]}""", r.body!!.string())
            assertEquals("application/json", r.header("Content-Type"))
            assertEquals("no-store", r.header("Cache-Control"))
            assertEquals("cardscanner", r.header("X-Server"))
        }
        val rec = upstreamServer.takeRequest()
        assertEquals("GET", rec.method)
        assertEquals("/api/scans?limit=5&q=Shivan%20Dragon&x=a%2Bb", rec.path)
    }

    @Test
    fun proxiesPostBodyAndErrorStatus() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json")
            .setBody("""{"detail":"bad foil"}"""))
        val req = Request.Builder().url(url("/api/scans/7/select?merge=1")).header("Cookie", cookie)
            .post("""{"printing":"abc","foil":true}""".toRequestBody("application/json".toMediaTypeOrNull())).build()
        client.newCall(req).execute().use { r ->
            assertEquals(422, r.code)
            assertEquals("""{"detail":"bad foil"}""", r.body!!.string())
        }
        val rec = upstreamServer.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/api/scans/7/select?merge=1", rec.path)
        assertEquals("""{"printing":"abc","foil":true}""", rec.body.readUtf8())
        assertTrue(rec.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun proxiesMultipartUploadBinaryIntact() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setBody("ok"))
        val bytes = ByteArray(300_000) { (it * 31 + 7).toByte() }
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("files", "frame0.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())).build()
        client.newCall(Request.Builder().url(url("/api/scan")).header("Cookie", cookie).post(body).build()).execute()
            .use { r -> assertEquals(200, r.code) }
        val rec = upstreamServer.takeRequest()
        val sent = Buffer().also { body.writeTo(it) }.readByteArray()
        assertTrue(rec.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        assertTrue(sent.contentEquals(rec.body.readByteArray()))
    }

    @Test
    fun proxiesDeleteAnd204() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setResponseCode(204))
        upstreamServer.enqueue(MockResponse().setBody("after"))
        val req = Request.Builder().url(url("/api/scans/12")).header("Cookie", cookie).delete().build()
        client.newCall(req).execute().use { r ->
            assertEquals(204, r.code)
            assertEquals("", r.body!!.string())
        }
        // the keep-alive connection is still sane after a bodyless response
        get("/api/after", cookie = cookie).use { r -> assertEquals("after", r.body!!.string()) }
        val rec = upstreamServer.takeRequest()
        assertEquals("DELETE", rec.method)
        assertEquals("/api/scans/12", rec.path)
        assertEquals("/api/after", upstreamServer.takeRequest().path)
    }

    @Test
    fun stripsHopByHopAndOurCookieUpstream() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setBody("x"))
        val req = Request.Builder().url(url("/api/version"))
            .header("Cookie", "theme=dark; $cookie; lang=en")
            .header("Keep-Alive", "timeout=5")
            .header("Proxy-Authorization", "Basic Zm9vOmJhcg==")
            .header("TE", "trailers")
            .header("Trailer", "X-Checksum")
            .header("Upgrade", "h2c")
            .header("X-Keep-Me", "yes")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { r -> assertEquals(200, r.code) }
        val rec = upstreamServer.takeRequest()
        assertEquals("theme=dark; lang=en", rec.getHeader("Cookie"))
        for (h in listOf("Keep-Alive", "Proxy-Authorization", "TE", "Trailer", "Upgrade", "remote-addr", "http-client-ip")) {
            assertNull("$h leaked upstream", rec.getHeader(h))
        }
        assertEquals("yes", rec.getHeader("X-Keep-Me"))
        assertEquals("application/json", rec.getHeader("Accept"))
        assertFalse(rec.headers.toString().contains(cookie.substringAfter('=')))
    }

    @Test
    fun onlyOurCookieMeansNoCookieHeaderUpstream() {
        val cookie = join()
        upstreamServer.enqueue(MockResponse().setBody("x"))
        get("/", cookie = cookie).use { r -> assertEquals(200, r.code) }
        assertNull(upstreamServer.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun stripsHopByHopFromResponseKeepsMultipleSetCookieAndRelativizesLocation() {
        val cookie = join()
        val upHost = upstreamServer.hostName
        upstreamServer.enqueue(MockResponse().setResponseCode(307)
            .setHeaders(Headers.Builder()
                .add("Location", "http://$upHost:${upstreamServer.port}/phone?panel=1&detail=5")
                .add("Keep-Alive", "timeout=99")
                .add("Proxy-Authenticate", "Basic")
                .add("Upgrade", "h2c")
                .add("Set-Cookie", "a=1; Path=/")
                .add("Set-Cookie", "b=2; Path=/")
                .add("X-End", "1")
                .build())
            .setBody("moved"))
        get("/phone?detail=5", cookie = cookie).use { r ->
            assertEquals(307, r.code)
            assertEquals("/phone?panel=1&detail=5", r.header("Location"))
            assertNull(r.header("Keep-Alive"))
            assertNull(r.header("Proxy-Authenticate"))
            assertNull(r.header("Upgrade"))
            assertEquals(listOf("a=1; Path=/", "b=2; Path=/"), r.headers("Set-Cookie"))
            assertEquals("1", r.header("X-End"))
            assertEquals("moved", r.body!!.string())
        }
    }

    @Test
    fun streamsLargeBody() {
        val cookie = join()
        val big = ByteArray(12 * 1024 * 1024) { (it % 251).toByte() }
        upstreamServer.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(big)))
        // chunked upstream (unknown length) must stream too
        upstreamServer.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
            .setChunkedBody(Buffer().write(big), 64 * 1024))
        for (i in 0 until 2) {
            get("/scan_images/big.jpg", cookie = cookie).use { r ->
                assertEquals(200, r.code)
                val got = r.body!!.bytes()
                assertEquals(big.size, got.size)
                assertTrue(MessageDigest.getInstance("SHA-256").digest(big)
                    .contentEquals(MessageDigest.getInstance("SHA-256").digest(got)))
            }
        }
    }

    @Test
    fun firstBytesArriveBeforeUpstreamFinishes() {
        val cookie = join()
        val body = ByteArray(2 * 1024 * 1024) { 1 }
        // ~2 s upstream: 256 KB every 250 ms
        upstreamServer.enqueue(MockResponse().setBody(Buffer().write(body)).throttleBody(256 * 1024, 250, TimeUnit.MILLISECONDS))
        val t0 = System.nanoTime()
        get("/slow", cookie = cookie).use { r ->
            val src = r.body!!.byteStream()
            val first = ByteArray(64 * 1024)
            var n = 0
            while (n < first.size) { val k = src.read(first, n, first.size - n); if (k < 0) break; n += k }
            val firstMs = (System.nanoTime() - t0) / 1_000_000
            var total = n.toLong()
            val buf = ByteArray(65536)
            while (true) { val k = src.read(buf); if (k < 0) break; total += k }
            val allMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals(body.size.toLong(), total)
            assertTrue("first 64 KB at ${firstMs}ms, all at ${allMs}ms", firstMs < allMs - 800)
        }
    }

    @Test
    fun upstreamDownGives502Page() {
        val cookie = join()
        upstreamServer.shutdown()
        get("/api/scans", cookie = cookie).use { r ->
            assertEquals(502, r.code)
            assertTrue(r.header("Content-Type")!!.startsWith("text/html"))
            assertTrue(r.body!!.string().contains("Scanner server unreachable (home server offline or Tailscale down)"))
        }
    }

    @Test
    fun upstreamThrowingAnythingGives502() {
        val g = GatewayServer({ _, _, _, _ -> throw IllegalStateException("boom") }, port = 0, hostname = "127.0.0.1")
        g.startServing()
        try {
            val cookie = client.newCall(Request.Builder().url("http://127.0.0.1:${g.listeningPort}/join?code=${g.code}").build())
                .execute().use { it.header("Set-Cookie")!!.substringBefore(';') }
            client.newCall(Request.Builder().url("http://127.0.0.1:${g.listeningPort}/x").header("Cookie", cookie).build())
                .execute().use { r -> assertEquals(502, r.code) }
        } finally {
            g.stop()
        }
    }

    @Test
    fun stopSharingRotatesCodeAndKillsSessions() {
        val cookie = join()
        val oldCode = gateway.code
        upstreamServer.enqueue(MockResponse().setBody("in"))
        get("/", cookie = cookie).use { r -> assertEquals("in", r.body!!.string()) }

        val newCode = gateway.stopSharing()
        assertEquals(newCode, gateway.code)
        assertNotEquals(oldCode, newCode)   // 1-in-a-million flake would need a retry here
        assertEquals(0, gateway.sessionCount)

        get("/", cookie = cookie).use { r -> assertTrue(r.body!!.string().contains("Enter the 6-digit code")) }
        get("/join?code=$oldCode").use { r -> assertEquals(403, r.code) }
        get("/join?code=$newCode").use { r -> assertEquals(302, r.code) }
        assertEquals(1, upstreamServer.requestCount)
    }

    /**
     * Settings → "Guests get full access" (owner, 2026-11-05: "Any guest to the web site to pick
     * cards has full admin access"): the gateway asks the setting on EVERY request, so a guest
     * session already open is admin while it is on and a guest the moment it goes off (and back);
     * a client's own role header is dropped either way; no cookie is still the code page.
     */
    @Test
    fun guestsFullAccess_isAskedPerRequest_andNeverOpensTheDoor() {
        var full = true
        val admins = AdminPairing(null)
        val base = upstreamServer.url("/")
        val g = GatewayServer(OkUpstream({ base }, upClient), port = 0, hostname = "127.0.0.1", clock = { now },
            admins = admins, guestsFullAccess = { full })
        g.startServing()
        try {
            fun at(p: String) = "http://127.0.0.1:${g.listeningPort}$p"
            fun roleSent(cookie: String?, claim: String? = null): String? {
                upstreamServer.enqueue(MockResponse().setBody("ok"))
                val b = Request.Builder().url(at("/api/scans/1")).delete()
                cookie?.let { b.header("Cookie", it) }
                claim?.let { b.header(GatewayServer.ROLE_HEADER, it) }
                client.newCall(b.build()).execute().use { r -> assertEquals(200, r.code) }
                return upstreamServer.takeRequest(5, TimeUnit.SECONDS)!!.getHeader(GatewayServer.ROLE_HEADER)
            }
            // no cookie: the code page (401 for a DELETE), nothing proxied — the setting never opens the door
            val before = upstreamServer.requestCount
            client.newCall(Request.Builder().url(at("/api/scans/1")).delete().build()).execute().use { r ->
                assertEquals(401, r.code)
                assertTrue(r.body!!.string().contains("Enter the code"))
            }
            client.newCall(Request.Builder().url(at("/api/scans/1")).delete()
                .header(GatewayServer.ROLE_HEADER, "admin").build()).execute().use { r -> assertEquals(401, r.code) }
            assertEquals(before, upstreamServer.requestCount)
            val guest = client.newCall(Request.Builder().url(at("/join?code=${g.code}")).build()).execute()
                .use { it.header("Set-Cookie")!!.substringBefore(';') }
            assertEquals("on: a Share-code session is admin", GatewayServer.ROLE_ADMIN, roleSent(guest))
            full = false
            assertEquals("flipped off: the SAME session is a guest on its next request", GatewayServer.ROLE_GUEST, roleSent(guest))
            assertEquals("off: a client's own role header is dropped", GatewayServer.ROLE_GUEST, roleSent(guest, claim = "admin"))
            full = true
            assertEquals("back on: admin again at once", GatewayServer.ROLE_ADMIN, roleSent(guest, claim = "guest"))
            // the paired computer is admin whatever the setting says
            val admin = client.newCall(Request.Builder().url(at("/join?code=${admins.newCode()}")).build()).execute()
                .use { r -> r.headers("Set-Cookie").first { it.startsWith(GatewayServer.ADMIN_COOKIE) }.substringBefore(';') }
            full = false
            assertEquals(GatewayServer.ROLE_ADMIN, roleSent(admin))
        } finally {
            g.stop()
        }
    }

    @Test
    fun cookieHelpers() {
        assertEquals(listOf("abc"), GatewayServer.cookieValues("x=1; cs_guest=abc; y=2"))
        assertEquals(emptyList<String>(), GatewayServer.cookieValues(null))
        assertEquals("x=1; y=2", GatewayServer.stripOurCookie("x=1; cs_guest=abc; y=2"))
        assertNull(GatewayServer.stripOurCookie("cs_guest=abc"))
        assertEquals("/a%20b/%C3%A9/c", GatewayServer.encodePath("/a b/é/c"))
        assertEquals("/api/scans/12", GatewayServer.encodePath("/api/scans/12"))
    }

    companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36"
        const val PHONE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36"
    }
}
