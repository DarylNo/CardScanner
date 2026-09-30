package io.github.darylno.cardscanner.ident

import io.github.darylno.cardscanner.core.ArtMatcher
import io.github.darylno.cardscanner.core.HttpJson
import io.github.darylno.cardscanner.core.HttpStatusException
import io.github.darylno.cardscanner.core.ImageSource
import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.OcrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * [PhoneIdentifier] with fakes at every edge — built from :core's committed
 * server fixtures: the art index the server exported (arthash index_rows),
 * the RECORDED Scryfall pages (fake [HttpJson]), the committed `small` images
 * (fake [ImageSource]), a canned OCR engine, and a real flattened scan as the
 * "capture primary" JPEG. Proves the orchestration: result in the server's
 * /api/scan shape, per-stage timings, one run at a time, cancellation.
 * (Server-parity of the answers themselves: core's IdentifyParityTest.)
 */
class PhoneIdentifierTest {
    companion object {
        lateinit var matcher: ArtMatcher
        lateinit var responses: Map<String, Any?>

        fun res(path: String): File = File(requireNotNull(PhoneIdentifierTest::class.java.getResource(path)) { "missing $path" }.toURI())

        @Suppress("UNCHECKED_CAST")
        @BeforeClass @JvmStatic
        fun load() {
            nu.pattern.OpenCV.loadLocally()
            val rows = (MiniJson.parse(res("/arthash/expected.json").readText()) as Map<String, Any?>)["index_rows"] as List<List<String>>
            val h64 = LongArray(rows.size)
            val h256 = LongArray(4 * rows.size)
            val meta = rows.mapIndexed { i, r ->
                h64[i] = java.lang.Long.parseUnsignedLong(r[5], 16)
                for (w in 0 until 4) h256[4 * i + w] = java.lang.Long.parseUnsignedLong(r[6].substring(16 * w, 16 * w + 16), 16)
                ArtMatcher.Entry(r[0], r[1], r[2], r[3], r[4])
            }
            matcher = ArtMatcher(h64, h256, meta)
            val text = GZIPInputStream(res("/printings/recorded.json.gz").inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
            responses = (MiniJson.parse(text) as Map<String, Any?>)["responses"] as Map<String, Any?>
        }

        val primary: ByteArray by lazy { res("/ranker/scans/bolt_m10.jpg").readBytes() }
    }

    /** Serves the recorded Scryfall pages; can block to test cancellation. */
    private class FakeScryfall(val gate: CountDownLatch? = null) : HttpJson {
        val calls = AtomicInteger()
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        @Volatile var cancelled = false

        @Suppress("UNCHECKED_CAST")
        override fun get(url: String): String {
            calls.incrementAndGet()
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
            try {
                if (gate != null) {
                    while (!gate.await(10, TimeUnit.MILLISECONDS)) if (cancelled) throw InterruptedIOException("canceled")
                }
                val rec = responses[url] as? Map<String, Any?> ?: throw IOException("no recording for $url")
                val status = (rec["status"] as Long).toInt()
                val body = rec["body"].let { if (it is String) it else MiniJson.stringify(it) }
                if (status !in 200..299) throw HttpStatusException(status, body, rec["content_type"] as String, url)
                return body
            } finally {
                concurrent.decrementAndGet()
            }
        }
    }

    private class FixtureImages : ImageSource {
        val loads = AtomicInteger()
        override fun load(scryfallId: String, url: String): Mat {
            loads.incrementAndGet()
            val f = res("/ranker/images").resolve("$scryfallId.jpg")
            if (!f.isFile) throw IOException("no image $scryfallId")
            return Imgcodecs.imread(f.path, Imgcodecs.IMREAD_COLOR)
        }
    }

    private class CannedOcr(val text: String) : OcrEngine {
        val calls = AtomicInteger()
        override fun read(bgr: Mat): String { calls.incrementAndGet(); return text }
    }

    @Test
    fun identify_producesTheServerShape_andTimings() {
        val http = FakeScryfall()
        val ocr = CannedOcr("146/249 C M10 EN")
        val images = FixtureImages()
        val id = PhoneIdentifier({ matcher }, http, images, ocr)
        try {
            val r = id.identify(primary)
            val json = r.result.json
            assertEquals(listOf("identified", "card_read", "confidence", "candidates", "error"), json.keys.toList())
            assertEquals(true, json["identified"])
            @Suppress("UNCHECKED_CAST")
            val cands = json["candidates"] as List<Map<String, Any?>>
            assertEquals(12, cands.size)
            assertEquals("m10", cands[0]["set"])
            assertEquals(true, cands[0]["ocr_confirmed"])
            assertTrue(r.result.wouldAutoPick)
            assertEquals(2, ocr.calls.get())
            assertEquals(1, http.calls.get())                   // one page of Lightning Bolt
            assertEquals(listOf("decode", "blank", "identify", "printings", "ranking", "ocr", "total"),
                r.timingsMs.keys.toList())
            assertTrue(r.timingsMs.values.all { it >= 0 })
            assertTrue(r.totalMs >= r.decodeMs)

            // The ranker's per-printing hash memo outlives a run, as the server's does.
            val before = images.loads.get()
            id.identify(primary)
            assertEquals(before, images.loads.get())

            // What gets filed: the server's /api/scan shape, the OCR-confirmed printing first.
            val top = (r.result.json["candidates"] as List<*>)[0] as Map<*, *>
            assertEquals("Lightning Bolt", (r.result.json["card_read"] as Map<*, *>)["name"])
            assertEquals("m10", top["set"])
            assertEquals(true, top["ocr_confirmed"])

            // The upload queue's fallback: several frames through the same pipeline.
            val multi = id.identifyFrames(listOf(primary, primary))
            assertEquals(true, multi.result.json["identified"])
        } finally {
            id.shutdown()
        }
    }

    @Test(expected = PhoneIdentifier.NoArtPackException::class)
    fun noPack_throws() {
        PhoneIdentifier({ null }, FakeScryfall(), FixtureImages(), null).identify(primary)
    }

    @Test(expected = IOException::class)
    fun undecodableCapture_throws() {
        PhoneIdentifier({ matcher }, FakeScryfall(), FixtureImages(), null).identify(ByteArray(64) { 1 })
    }

    @Test
    fun submit_runsOneAtATime() {
        val http = FakeScryfall()
        val id = PhoneIdentifier({ matcher }, http, FixtureImages(), null)
        val done = CountDownLatch(3)
        val ok = AtomicInteger()
        repeat(3) { id.submit(primary) { r -> if (r.isSuccess) ok.incrementAndGet(); done.countDown() } }
        assertTrue(done.await(120, TimeUnit.SECONDS))
        assertEquals(3, ok.get())
        assertEquals(1, http.maxConcurrent.get())
        assertEquals(0, id.pending)
        id.shutdown()
    }

    @Test
    fun cancel_abortsTheInFlightLookup() {
        val gate = CountDownLatch(1)
        val http = FakeScryfall(gate)
        val id = PhoneIdentifier({ matcher }, http, FixtureImages(), null, onCancel = { http.cancelled = true })
        val result = AtomicReference<Result<PhoneIdentifier.Identified>>()
        val done = CountDownLatch(1)
        val job = id.submit(primary) { result.set(it); done.countDown() }
        assertNotNull(job)
        // Wait until it is blocked on Scryfall, then cancel.
        val deadline = System.currentTimeMillis() + 60_000
        while (http.calls.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        job!!.cancel()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertTrue(result.get().exceptionOrNull() is CancellationException)
        assertTrue(http.cancelled)
        id.shutdown()
    }

    @Test
    fun cancelledBeforeStart_neverRuns() {
        val http = FakeScryfall()
        val id = PhoneIdentifier({ matcher }, http, FixtureImages(), null)
        val job = id.Job().also { it.cancel() }
        try {
            id.identify(primary, job)
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(0, http.calls.get())
        id.shutdown()
        assertFalse(id.submit(primary) {} != null)
    }
}
