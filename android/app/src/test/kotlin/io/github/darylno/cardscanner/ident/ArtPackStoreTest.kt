package io.github.darylno.cardscanner.ident

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * [ArtPackStore] against MockWebServer serving the pack the SERVER's writer
 * exported (core's artpack/fixture.bin.gz, scripts/export_pack_fixture.py):
 * manifest → sha256/size check → install → load; the non-atomic CI upload
 * (manifest/pack mismatch → retry once); "no pack yet" (404); the weekly /
 * Wi-Fi gate; failures never touching an installed pack.
 */
class ArtPackStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private var clock = 1_000_000_000_000L

    private val pack: ByteArray by lazy {
        File(requireNotNull(javaClass.getResource("/artpack/fixture.bin.gz")).toURI()).readBytes()
    }
    private val rows: Int by lazy {
        JSONObject(File(requireNotNull(javaClass.getResource("/artpack/expected.json")).toURI()).readText())
            .getJSONArray("rows").length()
    }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun store(dir: File = tmp.root) = ArtPackStore(dir,
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        server.url("/art-pack").toString().trimEnd('/'), now = { clock })

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun manifest(body: ByteArray = pack, version: Int = 1, date: String = "2026-09-28T06:17:00Z") = JSONObject()
        .put("format_version", version).put("build_time", 1790000000).put("build_date", date)
        .put("rows", rows).put("file", "art-pack.bin.gz").put("size", body.size).put("sha256", sha(body))
        .toString(1)

    private fun ok(s: String) = MockResponse().setBody(s)
    private fun ok(b: ByteArray) = MockResponse().setBody(Buffer().write(b))
    private fun notFound() = MockResponse().setResponseCode(404).setBody("Not Found")

    @Test
    fun installsVerifiedPack_andLoadsIt() {
        server.enqueue(ok(manifest()))
        server.enqueue(ok(pack))
        val s = store()
        assertNull(s.matcher())
        val c = s.check(force = false, unmetered = true)
        assertTrue("$c", c is ArtPackStore.Check.Installed)
        assertEquals("/art-pack/art-pack.json", server.takeRequest().path)
        assertEquals("/art-pack/art-pack.bin.gz", server.takeRequest().path)
        assertEquals(rows, s.matcher()!!.size)
        assertEquals(sha(pack), s.installedManifest()!!.sha256)

        // A fresh store (app restart) loads the installed file without the network.
        val again = store()
        assertEquals(rows, again.matcher()!!.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun samePack_isUpToDate_withoutRedownloading() {
        server.enqueue(ok(manifest()))
        server.enqueue(ok(pack))
        val s = store()
        s.check(force = true, unmetered = false)
        server.enqueue(ok(manifest(date = "2026-09-29T06:17:00Z")))
        val c = s.check(force = true, unmetered = false)
        assertTrue("$c", c is ArtPackStore.Check.UpToDate)
        assertEquals(3, server.requestCount)
        assertEquals("2026-09-29T06:17:00Z", s.installedManifest()!!.buildDate)
    }

    @Test
    fun shaMismatch_retriesOnce_thenInstalls() {
        val stale = pack.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        server.enqueue(ok(manifest()))        // new manifest …
        server.enqueue(ok(stale))             // … old pack still being replaced
        server.enqueue(ok(manifest()))
        server.enqueue(ok(pack))
        val c = store().check(force = true, unmetered = true)
        assertTrue("$c", c is ArtPackStore.Check.Installed)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun shaMismatchTwice_fails_andKeepsTheInstalledPack() {
        server.enqueue(ok(manifest()))
        server.enqueue(ok(pack))
        val s = store()
        assertTrue(s.check(force = true, unmetered = true) is ArtPackStore.Check.Installed)

        val other = ByteArray(100) { 7 }
        repeat(2) {
            server.enqueue(ok(manifest(other)))
            server.enqueue(ok(pack))           // doesn't match the new manifest
        }
        val c = s.check(force = true, unmetered = true)
        assertTrue("$c", c is ArtPackStore.Check.Failed)
        assertTrue((c as ArtPackStore.Check.Failed).message.contains("doesn't match"))
        assertEquals(sha(pack), s.installedManifest()!!.sha256)
        assertEquals(rows, store().matcher()!!.size)
        assertFalse("no temp file left", File(tmp.root, ".download.tmp").exists())
    }

    @Test
    fun noReleaseYet_isNoPackYet_andRetriedAfterADay() {
        server.enqueue(notFound())
        val s = store()
        assertEquals(ArtPackStore.Check.NoPackYet, s.check(force = false, unmetered = true))
        assertNull(s.matcher())
        clock += 3_600_000L
        assertTrue(s.check(force = false, unmetered = true) is ArtPackStore.Check.Skipped)
        assertEquals(1, server.requestCount)
        clock += ArtPackStore.NO_PACK_RETRY_MS
        server.enqueue(notFound())
        assertEquals(ArtPackStore.Check.NoPackYet, s.check(force = false, unmetered = true))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun manifestButNoPackYet_isNoPackYet() {
        server.enqueue(ok(manifest()))
        server.enqueue(notFound())
        server.enqueue(ok(manifest()))
        server.enqueue(notFound())
        assertEquals(ArtPackStore.Check.NoPackYet, store().check(force = true, unmetered = true))
    }

    @Test
    fun weeklyAndWifiGate() {
        val s = store()
        assertTrue(s.check(force = false, unmetered = false) is ArtPackStore.Check.Skipped)
        assertEquals(0, server.requestCount)

        server.enqueue(ok(manifest()))
        server.enqueue(ok(pack))
        assertTrue(s.check(force = false, unmetered = true) is ArtPackStore.Check.Installed)
        clock += 24 * 3_600_000L
        assertFalse(s.due(unmetered = true))
        assertTrue(s.check(force = false, unmetered = true) is ArtPackStore.Check.Skipped)
        clock += ArtPackStore.CHECK_INTERVAL_MS
        assertTrue(s.due(unmetered = true))
        assertFalse(s.due(unmetered = false))
        // The user can always ask.
        server.enqueue(ok(manifest()))
        assertTrue(s.check(force = true, unmetered = false) is ArtPackStore.Check.UpToDate)
    }

    @Test
    fun newerFormat_isRefused() {
        server.enqueue(ok(manifest(version = 2)))
        val c = store().check(force = true, unmetered = true)
        assertTrue("$c", c is ArtPackStore.Check.Failed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun packThatMatchesItsManifestButIsNotAPack_isRefused() {
        val junk = "definitely not a pack".toByteArray()
        server.enqueue(ok(manifest(junk)))
        server.enqueue(ok(junk))
        val s = store()
        val c = s.check(force = true, unmetered = true)
        assertTrue("$c", c is ArtPackStore.Check.Failed)
        assertNull(s.installedManifest())
        assertNull(s.matcher())
    }

    @Test
    fun serverError_fails_withoutRecordingACheck() {
        server.enqueue(MockResponse().setResponseCode(503))
        val s = store()
        val c = s.check(force = false, unmetered = true)
        assertTrue("$c", c is ArtPackStore.Check.Failed)
        assertNull(s.lastCheckedAt())
        assertTrue("a failure leaves the check due", s.due(unmetered = true))
    }

    @Test
    fun corruptInstalledPack_isDropped() {
        server.enqueue(ok(manifest()))
        server.enqueue(ok(pack))
        store().check(force = true, unmetered = true)
        File(tmp.root, ArtPackStore.PACK_FILE).writeBytes(ByteArray(10))
        val s = store()
        assertNull(s.matcher())
        assertNull(s.installedManifest())
        assertNotNull(s.lastCheckedAt())
    }
}
