package io.github.darylno.cardscanner.ui

import android.os.Handler
import android.os.Looper
import io.github.darylno.cardscanner.camera.FocusLog
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * The camera block of Settings → Diagnostics and the debug report (Settings has
 * no camera of its own; the scan screen's CameraController holds it).
 *
 * Before 1.1.11 this was ONE string copied at bind — before any focus lock had
 * run — so "focus:", "last frame:", "analyzer:" and "last capture:" were stale
 * and the open-thread asks that read them could not be answered. Now:
 * - while the scan screen has a camera, [current] reads it LIVE (on the main
 *   thread; from another thread — the phone server's report — it waits ≤ 1 s
 *   for the main thread, else falls back to the snapshot);
 * - a snapshot is kept after every bind and every capture, and when the scan
 *   screen lets go of its camera, so the block still says something (with its
 *   age) once the screen is gone.
 */
object CameraDiagnostics {
    @Volatile private var live: (() -> String)? = null
    @Volatile var last: String? = null
        private set
    @Volatile var lastAtMs: Long = 0L
        private set

    /** The scan screen's camera is up: read [source] for live values (replaces an older screen's). */
    fun attach(source: () -> String) { live = source }

    /** The screen that attached [source] lets go — only its own (a newer screen may already have attached). */
    fun detach(source: () -> String) { if (live === source) live = null }

    /** Keep [text] as the latest snapshot, taken at [nowMs]. */
    fun snapshot(text: String?, nowMs: Long = System.currentTimeMillis()) {
        if (text == null) return
        last = text
        lastAtMs = nowMs
    }

    /**
     * The block to show: live when a camera is attached and answers in time
     * (also kept as the snapshot), else the last snapshot with its age.
     */
    fun current(nowMs: Long = System.currentTimeMillis(), timeoutMs: Long = 1_000): String {
        val src = live
        if (src != null) {
            val text = readOnMain(src, timeoutMs)
            if (text != null) {
                snapshot(text, nowMs)
                return render(text, null)
            }
        }
        val l = last
        return render(l, l?.let { nowMs - lastAtMs }, liveFailed = src != null)
    }

    /**
     * [ageMs] null = read live just now. [liveFailed] = a camera was attached but
     * its read threw or did not answer in time (the main thread was busy).
     */
    fun render(text: String?, ageMs: Long?, liveFailed: Boolean = false): String = when {
        text == null -> "camera: open the scanner screen once to collect camera details"
        ageMs == null -> "camera (live — read from the scan screen's camera now):\n$text"
        else -> "camera (snapshot from ${FocusLog.age(ageMs)} ago — " +
            (if (liveFailed) "the live read failed or timed out" else "the scan screen is closed; open it for live values") +
            "):\n$text"
    }

    private fun readOnMain(src: () -> String, timeoutMs: Long): String? {
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) return runCatching(src).getOrNull()
        val task = FutureTask(src)
        if (!Handler(main).post(task)) return null
        return runCatching { task.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrElse { task.cancel(false); null }
    }
}
