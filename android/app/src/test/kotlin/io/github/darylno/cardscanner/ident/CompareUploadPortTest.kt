package io.github.darylno.cardscanner.ident

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.darylno.cardscanner.ui.Captured
import io.github.darylno.cardscanner.ui.Outcome
import io.github.darylno.cardscanner.ui.UploadPort
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The Compare tap never changes the upload: every enqueue reaches the real
 * port with the same arguments and its job id comes back unchanged; outcomes
 * still reach every listener. With Compare mode OFF (the default) nothing is
 * held or identified; ON, an outcome triggers a phone run (here: no art pack
 * yet, which must be reported, not crash).
 */
@RunWith(RobolectricTestRunner::class)
class CompareUploadPortTest {
    private class Flag(override var compareMode: Boolean = false) : CompareMode.Flag

    private class FakePort : UploadPort {
        val enqueued = mutableListOf<Captured>()
        val listeners = mutableListOf<UploadPort.Listener>()
        override fun enqueue(capture: Captured, manual: Boolean, priceCheck: Boolean, replaceScanId: Long?): String {
            enqueued += capture
            return "%012d".format(enqueued.size)
        }
        override fun retryNow() {}
        override fun addListener(l: UploadPort.Listener) { listeners += l }
        override fun removeListener(l: UploadPort.Listener) { listeners -= l }
        override val pending: Int get() = 0
        fun deliver(jobId: String, o: Outcome) = listeners.forEach { it.onOutcome(jobId, false, false, null, o) }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun capture() = Captured(ByteArray(100) { it.toByte() }, emptyList(), true, "t", null)
    private val outcome = Outcome.NeedsPick(JSONObject().put("identified", true), 5, false)

    @Test
    fun off_isAPureDelegate() {
        val flag = Flag(false)
        val compare = CompareMode(ctx, flag)
        val real = FakePort()
        val port = CompareUploadPort(real, compare)
        val cap = capture()
        assertEquals("000000000001", port.enqueue(cap, manual = true, priceCheck = false, replaceScanId = null))
        assertSame(cap, real.enqueued.single())
        val seen = mutableListOf<String>()
        port.addListener(object : UploadPort.Listener {
            override fun onOutcome(jobId: String, manual: Boolean, priceCheck: Boolean, replaceScanId: Long?, outcome: Outcome) { seen += jobId }
            override fun onState(pending: Int, lastError: String?, nextRetryInMs: Long?) {}
        })
        real.deliver("000000000001", outcome)
        assertEquals(listOf("000000000001"), seen)
        assertEquals("", compare.status)
        assertTrue(compare.log.rows().isEmpty())
    }

    @Test
    fun on_runsThePhoneAfterTheServerAnswers_andReportsMissingPack() {
        val compare = CompareMode(ctx, Flag(true))
        val real = FakePort()
        val port = CompareUploadPort(real, compare)
        val id = port.enqueue(capture(), manual = false, priceCheck = false, replaceScanId = null)
        real.deliver(id, outcome)
        val deadline = System.currentTimeMillis() + 20_000
        while (compare.status != CompareMode.NO_PACK && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(CompareMode.NO_PACK, compare.status)
        assertTrue("no pack → no comparison row", compare.log.rows().isEmpty())

        // Test on last capture: same answer, and the callback always fires.
        val msg = AtomicReference<String>()
        val done = CountDownLatch(1)
        compare.testLastCapture { msg.set(it); done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertEquals(CompareMode.NO_PACK, msg.get())
    }

    @Test
    fun rejectedOrUnknownJobs_areIgnored() {
        val compare = CompareMode(ctx, Flag(true))
        val real = FakePort()
        CompareUploadPort(real, compare)
        real.deliver("999999999999", outcome)                 // never captured here
        real.deliver("000000000001", Outcome.Rejected(400, "bad", false))
        Thread.sleep(50)
        assertFalse(compare.status.startsWith("identifying"))
    }
}
