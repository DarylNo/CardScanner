package io.github.darylno.cardscanner.ident

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.PlayData
import io.github.darylno.cardscanner.ui.DiagnosticsText
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [PlayDataStore] against MockWebServer (the [ArtPackStoreTest] pattern): install → load
 * (and again after a restart, without the network); the same file is up to date and
 * never rewritten; "no data yet" (404) is re-asked after a day; the weekly / Wi-Fi
 * gate; a file [PlayData.parse] refuses, a server error or no answer keep the
 * installed file and record no check; a corrupt installed file is dropped.
 */
class PlayDataStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private var clock = 1_000_000_000_000L

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun store(dir: File = tmp.root) = PlayDataStore(dir,
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        server.url("/play-data").toString().trimEnd('/'), now = { clock })

    private val seize = "00000000-0000-0000-0000-00000000000a"

    /** A play-data.json in the published shape (play_data.dump_pack: sorted keys, compact). */
    private fun file(asOf: String = "2026-11-10", builtAt: String? = "2026-11-10T07:41:02Z", pretty: Boolean = false): String {
        fun fmt(decks: Long, mtgo: Long, paper: Long, cards: Map<String, List<Long>>) = linkedMapOf(
            "cards" to cards, "decks" to decks, "events" to 256L,
            "sources" to linkedMapOf(
                "mtgo" to linkedMapOf("decks" to mtgo, "events" to 177L, "weighted" to (mtgo >= 200)),
                "paper" to linkedMapOf("decks" to paper, "events" to 79L, "weighted" to (paper >= 200))))
        val doc = linkedMapOf<String, Any?>(
            "ambiguous" to listOf("lightning bolt"), "as_of" to asOf, "basic" to emptyList<String>(),
            "built_at" to builtAt,
            "formats" to linkedMapOf(
                "modern" to fmt(16831, 6146, 10685, mapOf(seize to listOf(2689L, 9L))),
                "pioneer" to fmt(1437, 1437, 130, mapOf(seize to listOf(1500L, 30L)))),
            "kind" to PlayData.PACK_KIND, "names" to mapOf("thoughtseize" to seize),
            "skipped" to emptyMap<String, Any?>(), "version" to 1L, "window_days" to 60L)
        val text = MiniJson.stringify(doc)
        return if (pretty) text.replace(",", ",\n ") + "\n" else text + "\n"
    }

    private fun ok(s: String) = MockResponse().setBody(s)
    private fun notFound() = MockResponse().setResponseCode(404).setBody("Not Found")
    private val installed get() = File(tmp.root, PlayData.PACK_FILE)

    @Test
    fun installsAndLoads_thenARestartReadsItWithoutTheNetwork() {
        server.enqueue(ok(file()))
        val s = store()
        assertNull(s.current())
        assertNull(s.loaded())
        val c = s.check(force = false, unmetered = true)
        assertEquals(PlayDataStore.Check.Installed("2026-11-10", "2026-11-10T07:41:02Z"), c)
        assertSame(c, s.lastCheck)
        val req = server.takeRequest()
        assertEquals("/play-data/play-data.json", req.path)
        assertEquals(PlayDataStore.USER_AGENT, req.getHeader("User-Agent"))
        val p = s.current()!!
        assertSame("the installed pack is the one in memory", p, s.loaded())
        assertEquals(listOf("pioneer", "modern"), p.formats.keys.toList())     // FORMATS order, not the file's
        assertEquals(2689L, p.formats.getValue("modern").cards.getValue(seize).bp)
        assertEquals(clock, s.lastCheckedAt())
        assertFalse("no temp file left", File(tmp.root, ".download.tmp").exists())

        val again = store()                     // an app restart
        assertNull("nothing parsed until asked", again.loaded())
        assertEquals("2026-11-10", again.current()!!.asOf)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun theSameFile_isUpToDate_withoutRewritingIt() {
        server.enqueue(ok(file()))
        val s = store()
        s.check(force = true, unmetered = true)
        val before = installed.readText()
        val first = s.current()
        // the same as_of and built_at, laid out differently: nothing rewritten, nothing re-read
        server.enqueue(ok(file(pretty = true)))
        clock += 3_600_000L
        val c = s.check(force = true, unmetered = false)
        assertEquals(PlayDataStore.Check.UpToDate("2026-11-10", "2026-11-10T07:41:02Z"), c)
        assertEquals(before, installed.readText())
        assertSame(first, s.current())
        assertEquals("the check is recorded", clock, s.lastCheckedAt())
        // a rebuild of the same window (a new built_at) IS new
        server.enqueue(ok(file(builtAt = "2026-11-10T09:00:00Z")))
        assertTrue(s.check(force = true, unmetered = true) is PlayDataStore.Check.Installed)
        assertEquals("2026-11-10T09:00:00Z", s.current()!!.builtAt)
        // and a new week replaces it in memory at once
        server.enqueue(ok(file(asOf = "2026-11-17")))
        assertTrue(s.check(force = true, unmetered = true) is PlayDataStore.Check.Installed)
        assertEquals("2026-11-17", s.loaded()!!.asOf)
        assertEquals("2026-11-17", store().current()!!.asOf)
    }

    @Test
    fun noDataYet_isRecorded_andAskedAgainOnlyAfterADay() {
        server.enqueue(notFound())
        val s = store()
        assertEquals(PlayDataStore.Check.NoDataYet, s.check(force = false, unmetered = true))
        assertNull(s.current())
        assertEquals(clock, s.lastCheckedAt())
        clock += 3_600_000L
        assertEquals(PlayDataStore.Check.Skipped("checked recently"), s.check(force = false, unmetered = true))
        assertEquals(1, server.requestCount)
        clock += PlayDataStore.NO_DATA_RETRY_MS - 3_600_000L - 1
        assertFalse(s.due(unmetered = true))
        clock += 1
        assertTrue(s.due(unmetered = true))
        server.enqueue(ok(file()))
        assertTrue(s.check(force = false, unmetered = true) is PlayDataStore.Check.Installed)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun weeklyOnWifi_andTheUserCanAlwaysAsk() {
        val s = store()
        assertTrue("never checked, no data: due at once", s.due(unmetered = true))
        assertEquals(PlayDataStore.Check.Skipped("not on Wi-Fi"), s.check(force = false, unmetered = false))
        assertEquals(0, server.requestCount)
        server.enqueue(ok(file()))
        assertTrue(s.check(force = false, unmetered = true) is PlayDataStore.Check.Installed)
        clock += 24 * 3_600_000L
        assertFalse(s.due(unmetered = true))
        assertEquals(PlayDataStore.Check.Skipped("checked recently"), s.check(force = false, unmetered = true))
        clock += PlayDataStore.CHECK_INTERVAL_MS
        assertTrue(s.due(unmetered = true))
        assertFalse(s.due(unmetered = false))
        assertEquals(1, server.requestCount)
        server.enqueue(ok(file()))
        assertTrue(s.check(force = true, unmetered = false) is PlayDataStore.Check.UpToDate)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRefusedFile_keepsTheInstalledOne_andRecordsNoCheck() {
        server.enqueue(ok(file()))
        val s = store()
        s.check(force = true, unmetered = true)
        val installedAt = clock
        val before = installed.readText()
        val bad = listOf(
            "{nope" to "not JSON",
            """{"kind":"something else"}""" to "not a play data file",
            file().replace("\"version\":1", "\"version\":2") to "unsupported version",
            file().replace("\"version\":1", "\"version\":1.0") to "unsupported version",
            file().replace("[2689,9]", "[2689.0,9]") to "malformed",
            file().replace("\"modern\"", "\"brawl\"").replace("\"pioneer\"", "\"commander\"") to "no formats",
        )
        for ((text, reason) in bad) {
            clock += 60_000L
            server.enqueue(ok(text))
            assertEquals(text.take(40), PlayDataStore.Check.Failed(reason), s.check(force = true, unmetered = true))
            assertEquals(before, installed.readText())
            assertEquals("2026-11-10", s.current()!!.asOf)
            assertEquals("no check recorded", installedAt, s.lastCheckedAt())
        }
        assertEquals("2026-11-10", store().current()!!.asOf)
    }

    @Test
    fun aServerError_orNoAnswer_failsWithoutRecordingACheck() {
        server.enqueue(MockResponse().setResponseCode(503))
        val s = store()
        assertEquals(PlayDataStore.Check.Failed("HTTP 503"), s.check(force = false, unmetered = true))
        assertNull(s.lastCheckedAt())
        assertTrue("a failure leaves the check due", s.due(unmetered = true))
        server.shutdown()
        assertEquals(PlayDataStore.Check.Failed("unreachable"), s.check(force = false, unmetered = true))
        assertNull(s.lastCheckedAt())
        assertNull(s.current())
    }

    @Test
    fun aCorruptInstalledFile_isDropped() {
        server.enqueue(ok(file()))
        store().check(force = true, unmetered = true)
        installed.writeText("{\"kind\": \"cardscanner-play-data\", \"version\": 1, \"formats\": ")
        val s = store()
        assertNull(s.current())
        assertFalse("deleted, so the next check fetches it", installed.exists())
        assertFalse(s.hasFile)
        assertTrue("no data: due again at once", s.due(unmetered = true))
        assertNotNull(s.lastCheckedAt())
    }

    @Test
    fun theLogAndDiagnosticsLines() {
        val p = PlayData.parse(file())
        assertEquals("play data: as of 2026-11-10 · Pioneer 1,437 · Modern 16,831 decks", PlayDataStore.logLine(p))
        assertEquals("play data as of 2026-11-10 · pioneer 1,437 (MTGO only) · modern 16,831 decks",
            PlayDataStore.diagnosticsLine(p))
        val paperOnly = PlayData.parse(file().replace("\"decks\":6146,\"events\":177,\"weighted\":true",
            "\"decks\":150,\"events\":177,\"weighted\":false"))
        assertEquals("play data as of 2026-11-10 · pioneer 1,437 (MTGO only) · modern 16,831 (paper only) decks",
            PlayDataStore.diagnosticsLine(paperOnly))
        assertEquals(p.formats.values.sumOf { it.decks }, p.totalDecks)
        // Diagnostics: the line, none, a file not parsed yet, and the last failure
        assertEquals("play data as of 2026-11-10 · pioneer 1,437 (MTGO only) · modern 16,831 decks",
            DiagnosticsText.playDataLine(p, true, null))
        assertEquals("play data none", DiagnosticsText.playDataLine(null, false, null))
        assertEquals("play data none · last check failed: unreachable",
            DiagnosticsText.playDataLine(null, false, PlayDataStore.Check.Failed("unreachable")))
        assertEquals("play data installed, not loaded yet", DiagnosticsText.playDataLine(null, true, null))
        assertEquals("play data as of 2026-11-10 · pioneer 1,437 (MTGO only) · modern 16,831 decks · last check failed: HTTP 500",
            DiagnosticsText.playDataLine(p, true, PlayDataStore.Check.Failed("HTTP 500")))
    }

    /**
     * What Settings and Diagnostics show as the last failure ([PlayDataSync.lastFailure]): a failure
     * replaces the last one, an answer clears it, a skipped check (not on Wi-Fi, checked recently,
     * unit tests) leaves it — the scan screen's "not on Wi-Fi" used to hide the app start's
     * "unreachable" (ScreensSmokeTest.playData_aSkippedCheckNeverHidesTheLastFailure_anAnswerClearsIt).
     */
    @Test
    fun theLastFailure_survivesASkippedCheck_andAnAnswerClearsIt() {
        val unreachable = PlayDataStore.Check.Failed("unreachable")
        val refused = PlayDataStore.Check.Failed("unsupported version")
        assertEquals(unreachable, PlayDataSync.failureAfter(null, unreachable))
        assertEquals(refused, PlayDataSync.failureAfter(unreachable, refused))
        for (skip in listOf("not on Wi-Fi", "checked recently", "unit tests never download the play data")) {
            assertEquals(skip, unreachable, PlayDataSync.failureAfter(unreachable, PlayDataStore.Check.Skipped(skip)))
            assertNull(skip, PlayDataSync.failureAfter(null, PlayDataStore.Check.Skipped(skip)))
        }
        for (answer in listOf(PlayDataStore.Check.NoDataYet, PlayDataStore.Check.UpToDate("2026-11-10", null),
                PlayDataStore.Check.Installed("2026-11-10", "2026-11-10T07:41:02Z"))) {
            assertNull("$answer", PlayDataSync.failureAfter(unreachable, answer))
        }
    }
}
