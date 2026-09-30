package io.github.darylno.cardscanner.phoneserver

import io.github.darylno.cardscanner.core.server.MemoryScanStore
import io.github.darylno.cardscanner.core.server.PhoneApi
import io.github.darylno.cardscanner.core.server.ScanImages
import io.github.darylno.cardscanner.ident.PhoneIdentifier
import io.github.darylno.cardscanner.net.ScanOutcome
import io.github.darylno.cardscanner.net.UploadJob
import io.github.darylno.cardscanner.net.UploadQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Stage 4: the upload queue files into the PHONE's store. [LocalScanUploader]
 * answers exactly like the computer's POST /api/scan did, so the queue's
 * fallback retry (weak primary → the raw frames, replacing the primary's row)
 * and the scan screen's outcomes work unchanged — end to end here through the
 * real UploadQueue and PhoneApi (the store the golden fixtures pin).
 */
@RunWith(RobolectricTestRunner::class)
class LocalScanUploaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private var tick = 0
    private val store = MemoryScanStore { "T${++tick}" }
    private val photos = HashMap<Long, ByteArray>()
    private val api = PhoneApi(store, object : ScanImages {
        override fun read(id: Long) = photos[id]
        override fun write(id: Long, jpeg: ByteArray) { photos[id] = jpeg }
        override fun delete(id: Long) { photos.remove(id) }
    })
    private val identifyCalls: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    private val script = LinkedBlockingQueue<Any>()
    private val queues = mutableListOf<UploadQueue>()

    private fun uploader(dir: java.io.File = tmp.newFolder()) = LocalScanUploader(
        identify = { frames ->
            identifyCalls += frames.map { String(it) }
            when (val n = script.poll() ?: error("script exhausted")) {
                is Throwable -> throw n
                else -> @Suppress("UNCHECKED_CAST") (n as Map<String, Any?>)
            }
        },
        file = { result, photo, replace -> api.fileScan(result, photo, replace ?: 0) },
        answers = dir,
    )

    private fun cand(id: String, set: String) = mapOf("id" to id, "name" to "Opt", "set" to set, "collector_number" to "1")
    private val weak = mapOf("identified" to false, "card_read" to mapOf("name" to "Opt"), "confidence" to mapOf("name" to "low"),
        "candidates" to listOf(cand("a", "dom"), cand("b", "xln")), "error" to "No confident art match.")
    private val strong = mapOf("identified" to true, "card_read" to mapOf("name" to "Opt"), "confidence" to mapOf("name" to "high"),
        "candidates" to listOf(cand("a", "dom"), cand("b", "xln")))
    private val noCard = mapOf("identified" to false, "no_card" to true, "card_read" to emptyMap<String, Any?>(),
        "candidates" to emptyList<Any?>(), "error" to "No card detected.")

    private class Rec : UploadQueue.Listener {
        val outcomes = LinkedBlockingQueue<Pair<UploadJob, ScanOutcome>>()
        val errors: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun onOutcome(job: UploadJob, outcome: ScanOutcome) { outcomes.put(job to outcome) }
        override fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?) { lastError?.let { errors += it } }
        fun next() = outcomes.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no outcome within 10 s")
    }

    private fun queue(rec: Rec): UploadQueue =
        UploadQueue(tmp.newFolder(), uploader(), backoffMs = listOf(20, 40)).also { it.listener = rec; queues += it; it.start() }

    @After fun tearDown() { queues.forEach { it.stop() } }

    @Test fun aWeakPrimaryFallsBackToTheRawFramesAndReplacesItsRow() {
        script.put(weak); script.put(strong)
        val rec = Rec()
        queue(rec).enqueue("P".toByteArray(), listOf("R0", "R1", "R2").map { it.toByteArray() }, manual = false)
        val (_, out) = rec.next()
        assertTrue("$out", out is ScanOutcome.NeedsPick)
        assertTrue(out.usedFallback)
        assertEquals(listOf(listOf("P"), listOf("R0", "R1", "R2")), identifyCalls)    // primary, then all raw frames
        assertEquals(1, store.count())                                               // the fallback REPLACED the row
        val row = store.list().single()
        assertEquals(true, row["identified"])
        assertEquals("R0", String(photos[row["id"] as Long]!!))                      // the photo of what identified it
    }

    @Test fun noCardFilesNothing() {
        script.put(noCard)
        val rec = Rec()
        queue(rec).enqueue("P".toByteArray(), emptyList(), manual = false)
        assertTrue(rec.next().second is ScanOutcome.NoCard)
        assertEquals(0, store.count())
    }

    @Test fun withoutTheCardDatabaseTheScanWaitsAndIsFiledOnce() {
        script.put(PhoneIdentifier.NoArtPackException()); script.put(PhoneIdentifier.NoArtPackException()); script.put(strong)
        val rec = Rec()
        queue(rec).enqueue("P".toByteArray(), emptyList(), manual = true)
        assertTrue(rec.next().second is ScanOutcome.NeedsPick)
        assertEquals(1, store.count())
        assertTrue(rec.errors.any { it.contains("card database") })                  // what the scan screen shows
    }

    @Test fun aRetryReplacesTheRowItRetries() {
        script.put(weak)
        val u = uploader()
        val first = u.scan(listOf("P".toByteArray()), null, "job1-p")
        script.put(strong)
        val again = u.scan(listOf("Q".toByteArray()), (first.getLong("id")), "job2-p")
        // As /api/scan does (golden-pinned): the unpicked row is deleted and the retry filed anew.
        assertEquals(1, store.count())
        assertEquals(again.getLong("id"), store.list().single()["id"])
        assertEquals(true, store.list().single()["identified"])
        assertEquals(null, photos[first.getLong("id")])
    }

    @Test fun aReSentJobGetsTheRowItAlreadyFiled() {
        script.put(strong)
        val dir = tmp.newFolder()
        val a = uploader(dir).scan(listOf("P".toByteArray()), null, "nonce-p")
        val b = uploader(dir).scan(listOf("P".toByteArray()), null, "nonce-p")        // a new process, same job
        assertEquals(a.getLong("id"), b.getLong("id"))
        assertEquals(1, identifyCalls.size)
        assertEquals(1, store.count())
    }

    @Test fun aCaptureThatCannotBeReadIsGivenUp() {
        script.put(PhoneIdentifier.UndecodableCaptureException())
        val rec = Rec()
        queue(rec).enqueue("P".toByteArray(), emptyList(), manual = true)
        val out = rec.next().second
        assertTrue("$out", out is ScanOutcome.Rejected && out.code == 422)
        assertEquals(0, store.count())
    }
}
