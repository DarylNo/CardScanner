package io.github.darylno.cardscanner.net

import org.json.JSONObject

/** A non-2xx answer: 4xx gives the job up, 5xx/408/429 retry ([UploadQueue]). */
class HttpException(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(200)}")

/** What [UploadQueue] needs from the network — the phone itself ([phoneserver.LocalScanUploader]) in the app, a fake in tests. */
fun interface ScanUploader {
    /**
     * POST /api/scan. Non-2xx → [HttpException]; transport failure → [IOException].
     * [uploadId] makes a re-send idempotent: the server answers a repeat with
     * the scan it already filed (a lost reply is re-sent by the queue).
     */
    fun scan(files: List<ByteArray>, replaceScanId: Long?, uploadId: String?): JSONObject
}
