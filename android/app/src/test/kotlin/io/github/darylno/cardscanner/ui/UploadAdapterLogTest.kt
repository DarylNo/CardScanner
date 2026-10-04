package io.github.darylno.cardscanner.ui

import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.net.UploadQueue
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The queue's log lines name the job so a capture can be followed to its scan:
 * 1.1.10 printed the first 8 of the 12-digit "%012d" id — "00000000" for every
 * job — and nothing tied a capture to its job.
 */
class UploadAdapterLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private var queue: UploadQueue? = null

    @After fun stop() { queue?.stop() }

    @Test fun aJobIsNamedByItsNumberAndNonceNotByLeadingZeros() {
        val q = UploadQueue(tmp.newFolder("jobs"), { _, _, _ -> JSONObject() }).also { queue = it }
        val job = q.enqueue(byteArrayOf(1), emptyList(), manual = false)
        assertEquals("000000000001", job.id)
        assertNotEquals("the 1.1.10 label", "00000000", UploadAdapter.jobLabel(job))
        assertEquals("1 (${job.nonce.take(8)})", UploadAdapter.jobLabel(job))
        assertEquals("0 (abcdefgh)", UploadAdapter.jobLabel(job.copy(id = "000000000000", nonce = "abcdefgh-1234")))
        assertEquals("capture #42 → job 1 (${job.nonce.take(8)}) queued · 3 pending", UploadAdapter.enqueuedLine(42, job, 3))
        assertEquals("capture → job 1 (${job.nonce.take(8)}) queued (retry of #9) · 1 pending",
            UploadAdapter.enqueuedLine(0, job.copy(replaceScanId = 9), 1))
    }

    @Test fun theCaptureTheJobAndTheOutcomeShareOneName() {
        val q = UploadQueue(tmp.newFolder("jobs2"), { _, _, _ ->
            JSONObject("""{"identified": true, "id": 77, "selection": {"auto_picked": true}}""")
        }, backoffMs = listOf(10L)).also { queue = it }
        val adapter = UploadAdapter(q)
        val done = CountDownLatch(1)
        adapter.addListener(object : UploadPort.Listener {
            override fun onOutcome(jobId: String, manual: Boolean, openScan: Boolean, replaceScanId: Long?, outcome: Outcome) { done.countDown() }
            override fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?) {}
        })
        val id = adapter.enqueue(Captured(byteArrayOf(1, 2), emptyList(), true, "t", null, burstId = 1234), manual = false,
            openScan = false, replaceScanId = null)
        assertTrue(done.await(10, TimeUnit.SECONDS))
        val queueLines = DebugLog.global.all().filter { it.tag == "queue" }.map { it.msg }
        val enq = queueLines.last { it.startsWith("capture #1234 → job ") }
        val label = enq.removePrefix("capture #1234 → job ").substringBefore(" queued")
        assertEquals(id.trimStart('0'), label.substringBefore(" ("))
        assertTrue(queueLines.joinToString("\n"), queueLines.any { it == "job $label auto → #77 auto-filed" })
    }
}
