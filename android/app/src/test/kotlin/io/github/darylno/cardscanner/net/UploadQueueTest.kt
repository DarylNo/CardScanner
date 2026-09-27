package io.github.darylno.cardscanner.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class UploadQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private val queues = mutableListOf<UploadQueue>()
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        queues.forEach { it.stop() }
        servers.forEach { runCatching { it.shutdown() } }
    }

    /** Records every call; answers from a script of responses/throwables. */
    private class FakeUploader(vararg script: Any) : ScanUploader {
        val calls: MutableList<Pair<List<String>, Long?>> = Collections.synchronizedList(mutableListOf())
        private val script = LinkedBlockingQueue(script.toList())
        var default: Any? = null

        override fun scan(files: List<ByteArray>, replaceScanId: Long?): JSONObject {
            calls += files.map { String(it) } to replaceScanId
            when (val next = script.poll() ?: default ?: error("script exhausted")) {
                is Throwable -> throw next
                is String -> return JSONObject(next)
                else -> error("bad script entry $next")
            }
        }
    }

    private class Recorder : UploadQueue.Listener {
        val outcomes = LinkedBlockingQueue<Pair<UploadJob, ScanOutcome>>()
        val errors: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun onOutcome(job: UploadJob, outcome: ScanOutcome) { outcomes.put(job to outcome) }
        override fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?) {
            if (lastError != null) errors += lastError
        }
        fun next(): Pair<UploadJob, ScanOutcome> =
            outcomes.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no outcome within 10 s")
    }

    private fun queue(uploader: ScanUploader, rec: Recorder? = null, dir: java.io.File = tmp.root): UploadQueue =
        UploadQueue(dir, uploader, backoffMs = listOf(20, 40)).also {
            it.listener = rec
            queues += it
        }

    private val identified = """{"id":11,"identified":true,"card_read":{"name":"Shivan Dragon"},"candidates":[{}],"selection":null}"""
    private val autoPicked = """{"id":12,"identified":true,"card_read":{"name":"Sol Ring"},"selection":{"auto_picked":true}}"""
    private val bestGuess = """{"id":7,"identified":false,"card_read":{"name":"?"},"candidates":[{"name":"x"}]}"""
    private val noMatch = """{"id":8,"identified":false,"candidates":[],"error":"Could not identify"}"""
    private val noCard = """{"no_card":true,"identified":false,"error":"No card detected."}"""

    @Test
    fun classifyMirrorsSubmitScan() {
        assertTrue(ScanOutcome.classify(JSONObject(noCard)) is ScanOutcome.NoCard)
        val auto = ScanOutcome.classify(JSONObject(autoPicked))
        assertTrue(auto is ScanOutcome.AutoFiled)
        assertEquals(12L, auto.scanId)
        assertEquals("Sol Ring", auto.cardName)
        assertTrue(ScanOutcome.classify(JSONObject("""{"id":3,"identified":true,"merged_into":2}""")) is ScanOutcome.AutoFiled)
        assertTrue(ScanOutcome.classify(JSONObject(identified)) is ScanOutcome.NeedsPick)
        assertTrue(ScanOutcome.classify(JSONObject(bestGuess)) is ScanOutcome.BestGuess)
        val nm = ScanOutcome.classify(JSONObject(noMatch))
        assertTrue(nm is ScanOutcome.NoMatch)
        assertEquals("Could not identify", (nm as ScanOutcome.NoMatch).error)
        assertEquals(8L, nm.scanId)
    }

    @Test
    fun uploadsInOrder() {
        val up = FakeUploader().apply { default = identified }
        val rec = Recorder()
        val q = queue(up, rec)
        val jobs = (1..3).map { q.enqueue("p$it".toByteArray(), emptyList(), manual = false, tag = "t$it") }
        q.start()
        val got = (1..3).map { rec.next() }
        assertEquals(jobs.map { it.id }, got.map { it.first.id })
        assertEquals(listOf("t1", "t2", "t3"), got.map { it.first.tag })
        assertEquals(listOf(listOf("p1"), listOf("p2"), listOf("p3")), up.calls.map { it.first })
        assertTrue(got.all { it.second is ScanOutcome.NeedsPick && !it.second.usedFallback })
        waitUntil { q.pendingCount() == 0 }
    }

    @Test
    fun jobsSurviveANewInstance() {
        val dir = tmp.newFolder("q")
        val first = queue(FakeUploader(), null, dir)     // never started = process died
        val a = first.enqueue("A".toByteArray(), listOf("a0".toByteArray()), manual = true, replaceScanId = 99)
        val b = first.enqueue("B".toByteArray(), emptyList(), manual = false)
        assertEquals(2, first.pendingCount())

        val up = FakeUploader().apply { default = autoPicked }
        val rec = Recorder()
        val second = queue(up, rec, dir)
        assertEquals(2, second.pendingCount())
        second.start()
        val (j1, o1) = rec.next()
        val (j2, _) = rec.next()
        assertEquals(a.id, j1.id)
        assertTrue(j1.manual)
        assertEquals(99L, j1.replaceScanId)
        assertEquals(b.id, j2.id)
        assertTrue(o1 is ScanOutcome.AutoFiled)
        assertEquals(listOf("A") to 99L, up.calls[0])
        // a later enqueue gets a later id than the resumed ones
        val c = second.enqueue("C".toByteArray(), emptyList(), manual = false)
        assertTrue(c.id > b.id)
        rec.next()
    }

    @Test
    fun weakPrimaryFallsBackWithReplaceScanId() {
        val up = FakeUploader(bestGuess, identified)
        val rec = Recorder()
        val q = queue(up, rec)
        q.enqueue("P".toByteArray(), listOf("f0", "f1", "f2").map { it.toByteArray() }, manual = true, replaceScanId = 3)
        q.start()
        val (_, out) = rec.next()
        assertTrue(out is ScanOutcome.NeedsPick)
        assertTrue(out.usedFallback)
        assertEquals(listOf("P") to 3L, up.calls[0])
        assertEquals(listOf("f0", "f1", "f2") to 7L, up.calls[1])   // the row the primary created
    }

    @Test
    fun noCardPrimaryFallsBackWithJobReplaceId() {
        val up = FakeUploader(noCard, noCard)
        val rec = Recorder()
        val q = queue(up, rec)
        q.enqueue("P".toByteArray(), listOf("f0".toByteArray()), manual = false)
        q.start()
        val (_, out) = rec.next()
        assertTrue(out is ScanOutcome.NoCard)
        assertTrue(out.usedFallback)
        assertEquals(listOf("f0") to null, up.calls[1])
    }

    @Test
    fun fallbackNoCardKeepsThePrimaryRow() {
        val up = FakeUploader(bestGuess, noCard)
        val rec = Recorder()
        val q = queue(up, rec)
        q.enqueue("P".toByteArray(), listOf("f0".toByteArray()), manual = false)
        q.start()
        val (_, out) = rec.next()
        assertTrue(out is ScanOutcome.BestGuess)
        assertEquals(7L, out.scanId)
        assertTrue(out.usedFallback)
    }

    @Test
    fun identifiedPrimaryNeverSendsFallbacks() {
        val up = FakeUploader(autoPicked)
        val rec = Recorder()
        val q = queue(up, rec)
        q.enqueue("P".toByteArray(), listOf("f0".toByteArray()), manual = false)
        q.start()
        assertTrue(rec.next().second is ScanOutcome.AutoFiled)
        assertEquals(1, up.calls.size)
    }

    @Test
    fun serverErrorAndTransportAreRetriedThenSucceed() {
        val up = FakeUploader(HttpException(503, "busy"), IOException("Connection refused"), identified)
        val rec = Recorder()
        val q = queue(up, rec)
        q.enqueue("P".toByteArray(), emptyList(), manual = false)
        q.start()
        val (_, out) = rec.next()
        assertTrue(out is ScanOutcome.NeedsPick)
        assertEquals(3, up.calls.size)
        assertTrue(rec.errors.any { it.contains("503") })
        assertTrue(rec.errors.any { it.contains("refused") })
    }

    @Test
    fun clientErrorDropsTheJobAndMovesOn() {
        val up = FakeUploader(HttpException(400, "no decodable image uploaded"), identified)
        val rec = Recorder()
        val q = queue(up, rec)
        q.enqueue("bad".toByteArray(), emptyList(), manual = false)
        q.enqueue("good".toByteArray(), emptyList(), manual = false)
        q.start()
        val (_, o1) = rec.next()
        assertEquals(ScanOutcome.Rejected(400, "no decodable image uploaded"), o1)
        assertTrue(rec.next().second is ScanOutcome.NeedsPick)
        assertEquals(2, up.calls.size)
        waitUntil { q.pendingCount() == 0 }
    }

    @Test
    fun retryNowSkipsTheBackoffWait() {
        val up = FakeUploader(IOException("down"), identified)
        val rec = Recorder()
        val q = UploadQueue(tmp.root, up, backoffMs = listOf(60_000)).also { it.listener = rec; queues += it }
        q.enqueue("P".toByteArray(), emptyList(), manual = false)
        q.start()
        waitUntil { up.calls.size == 1 }
        Thread.sleep(50)
        assertNull(rec.outcomes.peek())
        q.retryNow()
        assertTrue(rec.next().second is ScanOutcome.NeedsPick)
    }

    /** End to end over pinned TLS: primary best-guess → 3 raw frames with replace_scan_id. */
    @Test
    fun fallbackOverTheWire() {
        val held = HeldCertificate.Builder().rsa2048().commonName("mtg-card-scanner").build()
        val s = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
            start()
            servers += this
        }
        s.enqueue(MockResponse().setResponseCode(502).setBody("gateway"))
        s.enqueue(MockResponse().setBody(bestGuess))
        s.enqueue(MockResponse().setBody(identified))
        val client = ServerClient(
            InMemoryConfigStore(ServerConfig(listOf("https://127.0.0.1:${s.port}"), Pin.sha256Hex(held.certificate))),
            Timeouts(connectMs = 2_000, readMs = 5_000, writeMs = 5_000),
        )
        val rec = Recorder()
        val q = queue(client, rec)
        q.enqueue("PRIMARY".toByteArray(), listOf("R0", "R1", "R2").map { it.toByteArray() }, manual = true)
        q.start()
        val (_, out) = rec.next()
        assertTrue(out is ScanOutcome.NeedsPick)
        assertTrue(out.usedFallback)
        assertEquals(3, s.requestCount)
        s.takeRequest()                                    // the 502, retried
        val primary = s.takeRequest().body.readUtf8()
        assertTrue(primary.contains("PRIMARY"))
        assertFalse(primary.contains("replace_scan_id"))
        val fb = s.takeRequest().body.readUtf8()
        for (i in 0..2) assertTrue(fb.contains("""name="files"; filename="frame$i.jpg""""))
        assertTrue(fb.contains("R0") && fb.contains("R1") && fb.contains("R2"))
        assertTrue(fb.contains("""name="replace_scan_id""""))
        assertTrue(fb.contains("\r\n7\r\n"))
        assertNotNull(q.lastOutcome)
    }

    private fun waitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in 10 s")
            Thread.sleep(10)
        }
    }
}
