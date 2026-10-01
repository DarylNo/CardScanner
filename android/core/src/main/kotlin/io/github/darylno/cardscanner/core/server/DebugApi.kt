package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.DebugLog

/**
 * The live log and the debug report, for the paired computer (admin only —
 * [ScanServer.adminOnly]). Phone server only: the reference server has no
 * `/api/debug/…` (404), so the desktop's 🐞 panel shows its App tab only here.
 *
 *   GET /api/debug/log?since=<seq>&limit=<n>  → {"seq": last, "entries": [{seq,t,level,tag,msg}…]}
 *   GET /api/debug/report.txt                 → the report: [header] (device, server, card
 *                                                database, camera, queue…) + every log line
 */
class DebugApi(private val log: DebugLog, private val header: () -> String) {

    fun handle(req: ApiRequest): ApiResponse? {
        if (req.method != "GET" && req.method != "HEAD") return null
        return when (req.path) {
            "/api/debug/log" -> {
                val since = req.query["since"]?.toLongOrNull() ?: 0L
                val limit = (req.query["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 3000)
                ApiResponse.json(200, linkedMapOf(
                    "seq" to log.lastSeq,
                    "entries" to log.since(since, limit).map {
                        linkedMapOf("seq" to it.seq, "t" to it.t, "level" to it.level.toString(), "tag" to it.tag, "msg" to it.msg)
                    },
                ))
            }
            "/api/debug/report.txt" -> ApiResponse(200, "text/plain; charset=utf-8", report(log, header).toByteArray(Charsets.UTF_8),
                headers = mapOf("Content-Disposition" to "attachment; filename=\"cardscanner-debug.txt\""))
            else -> null
        }
    }

    companion object {
        /** The debug report: the header, then the log oldest first. */
        fun report(log: DebugLog, header: () -> String): String = buildString {
            append("Card Scanner debug report\n\n")
            append(runCatching(header).getOrElse { "(diagnostics failed: ${it.message})" }.trimEnd()).append("\n\n")
            val entries = log.all()
            append("── log (").append(entries.size).append(" lines, oldest first) ──\n")
            for (e in entries) append(e.line()).append('\n')
        }
    }
}
