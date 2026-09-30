package io.github.darylno.cardscanner.phoneserver

import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.net.HttpException
import io.github.darylno.cardscanner.net.ScanUploader
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Stage 4: the upload queue's door is the phone itself. What the computer's
 * `POST /api/scan` did — identify the frames (the ported, parity-tested
 * pipeline: [identify]), then file the answer with the replace-a-Retry rule
 * ([file] = PhoneApi.fileScan, golden-tested against /api/scan) — happens
 * here, and the answer has the same JSON shape, so [UploadQueue] (persistence,
 * the no-card / not-identified multi-frame fallback, `replace_scan_id`) and
 * every outcome the scan screen handles stay exactly as they were.
 *
 * Idempotent by [uploadId] like the server's `client_upload_id`: the answer
 * is written to [answers] right after filing, so a job re-run after a crash
 * gets the row it already filed instead of a second one.
 *
 * Failures: no pack yet / no network for Scryfall / a stopped run → IOException
 * (the queue retries with backoff until they pass); a frame that doesn't decode
 * (or a malformed call) → HTTP 422 (the queue gives that job up rather than
 * retrying it forever).
 */
class LocalScanUploader(
    private val identify: (List<ByteArray>) -> Map<String, Any?>,
    private val file: (result: Map<String, Any?>, photo: ByteArray, replaceScanId: Long?) -> Map<String, Any?>,
    private val answers: File,
) : ScanUploader {

    override fun scan(files: List<ByteArray>, replaceScanId: Long?, uploadId: String?): JSONObject {
        require(files.isNotEmpty()) { "no frames" }
        val saved = uploadId?.let { answerFile(it) }
        if (saved != null && saved.isFile) {
            runCatching { return JSONObject(saved.readText(Charsets.UTF_8)) }
        }
        val result = try {
            identify(files)
        } catch (e: io.github.darylno.cardscanner.ident.PhoneIdentifier.UndecodableCaptureException) {
            throw HttpException(422, e.message ?: "bad capture")
        } catch (e: IOException) {
            throw e
        } catch (e: CancellationException) {
            throw IOException("identification stopped", e)
        } catch (e: io.github.darylno.cardscanner.ident.PhoneIdentifier.NoArtPackException) {
            throw IOException("waiting for the card database (art pack)", e)
        } catch (e: IllegalArgumentException) {
            throw HttpException(422, e.message ?: "bad capture")
        }
        val row = file(result, files[0], replaceScanId?.takeIf { it > 0 })
        val text = MiniJson.stringify(row)
        if (saved != null) {
            // The row is filed: nothing after this may throw, or the queue would retry and
            // file the card a second time. A failed write only loses the dedupe for a re-run.
            try {
                saved.parentFile?.mkdirs()
                val tmp = File(saved.path + ".tmp")
                tmp.writeText(text, Charsets.UTF_8)
                if (!tmp.renameTo(saved)) { saved.delete(); tmp.renameTo(saved) }
                prune()
            } catch (_: Exception) { }
        }
        return JSONObject(text)
    }

    private fun answerFile(uploadId: String): File {
        val safe = uploadId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
        return File(answers, "$safe.json")
    }

    /** Keep the newest [KEEP] answers: a re-run only ever asks about a recent job. */
    private fun prune() {
        val all = answers.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (all.size <= KEEP) return
        all.sortedBy { it.lastModified() }.take(all.size - KEEP).forEach { it.delete() }
    }

    companion object {
        const val KEEP = 200
    }
}
