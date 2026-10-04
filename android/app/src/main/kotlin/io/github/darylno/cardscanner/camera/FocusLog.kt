package io.github.darylno.cardscanner.camera

/**
 * "Is it focusing?" (owner on 1.1.10: "No zoom? Focus working?") — before
 * 1.1.11 nothing about focus reached the debug log: a lock's result lived
 * only in a note the Diagnostics screen copied ONCE, at bind, before any
 * lock had run. Everything here is the words and numbers of the `focus` log
 * tag and the Diagnostics focus line. Pure Kotlin (JVM-tested), no camera.
 */
object FocusLog {
    /** CameraX gives a focus action this long, then completes it NOT focused (FocusMeteringControl.AUTO_FOCUS_TIMEOUT_DURATION, 1.5.3). */
    const val CAMERAX_AF_TIMEOUT_MS = 5_000L
    /** A NOT-focused answer this close to the CameraX timeout is counted as "timed out". */
    private const val TIMEOUT_SLACK_MS = 250L

    /** How a focus pass ended. */
    enum class Outcome(val word: String) {
        FOCUSED("focused"),
        FAILED("NOT focused"),
        TIMED_OUT("timed out"),
        CANCELLED("cancelled"),
        SUPERSEDED("superseded by a newer focus"),
    }

    /**
     * [ok] = FocusMeteringResult.isFocusSuccessful (null when the future failed:
     * cancelled by a newer action / camera closed). [superseded] = a newer action
     * started meanwhile (its result must not change state).
     */
    fun classify(ok: Boolean?, elapsedMs: Long, superseded: Boolean): Outcome = when {
        superseded -> Outcome.SUPERSEDED
        ok == true -> Outcome.FOCUSED
        ok == false && elapsedMs >= CAMERAX_AF_TIMEOUT_MS - TIMEOUT_SLACK_MS -> Outcome.TIMED_OUT
        ok == false -> Outcome.FAILED
        else -> Outcome.CANCELLED
    }

    /** CaptureResult.CONTROL_AF_STATE in words (the Camera2 constants, 0..6). */
    fun afStateName(state: Int?): String = when (state) {
        null -> "unknown"
        0 -> "INACTIVE"
        1 -> "PASSIVE_SCAN"
        2 -> "PASSIVE_FOCUSED"
        3 -> "ACTIVE_SCAN"
        4 -> "FOCUSED_LOCKED"
        5 -> "NOT_FOCUSED_LOCKED"
        6 -> "PASSIVE_UNFOCUSED"
        else -> "state $state"
    }

    /**
     * The lens position: LENS_FOCUS_DISTANCE in dioptres (1/m; 0 = infinity),
     * with the distance in cm when the lens reports a physical scale
     * ([calibrated]: LENS_INFO_FOCUS_DISTANCE_CALIBRATION is APPROXIMATE or
     * CALIBRATED — an UNCALIBRATED lens's dioptres are only an ordering).
     */
    fun lens(dpt: Float?, calibrated: Boolean): String = when {
        dpt == null -> "lens ? dpt"
        dpt <= 0f -> "lens 0.00 dpt" + if (calibrated) " (∞)" else ""
        calibrated -> "lens %.2f dpt (≈ %.0f cm)".format(dpt, 100.0 / dpt)
        else -> "lens %.2f dpt".format(dpt)
    }

    /** "AF FOCUSED_LOCKED · lens 4.20 dpt (≈ 24 cm)" — the camera's own report at that moment. */
    fun afAndLens(afState: Int?, dpt: Float?, calibrated: Boolean): String =
        "AF ${afStateName(afState)} · ${lens(dpt, calibrated)}"

    /**
     * A pass starting: "#7 first card → card centre (0.52, 0.48) = sensor 832,600 of 1600×1200 · now AF … · lens …".
     * [why] = what asked for it (camera bound, new Area, first card, watchdog, tap…),
     * [where] = the metering point in words, [sensor] = `[x, y, w, h]` or null.
     */
    fun startLine(n: Int, why: String, where: String, sensor: IntArray?, afState: Int?, dpt: Float?, calibrated: Boolean): String =
        "#$n $why → $where" +
            (sensor?.takeIf { it.size == 4 }?.let { " = sensor ${it[0]},${it[1]} of ${it[2]}×${it[3]}" } ?: "") +
            " · now ${afAndLens(afState, dpt, calibrated)}"

    /** A pass ending: "#7 first card: focused in 420 ms · AF FOCUSED_LOCKED · lens …" (+ the error for a cancelled one). */
    fun resultLine(n: Int, why: String, outcome: Outcome, elapsedMs: Long, afState: Int?, dpt: Float?, calibrated: Boolean,
                   error: String? = null): String =
        "#$n $why: ${outcome.word} in $elapsedMs ms · ${afAndLens(afState, dpt, calibrated)}" +
            (if (outcome == Outcome.TIMED_OUT) " (CameraX gives up after ${CAMERAX_AF_TIMEOUT_MS / 1000} s)" else "") +
            (error?.takeIf { it.isNotBlank() && (outcome == Outcome.CANCELLED || outcome == Outcome.SUPERSEDED) }?.let { " — $it" } ?: "")

    /** The soft-capture watchdog re-arming the card pass. */
    fun watchdogLine(value: Double, median: Double, ratio: Double, samples: Int): String =
        "watchdog: capture sharpness %.1f < %.2f × the session median %.1f (last %d flattened Tray captures) — focusing again on the next card"
            .format(value, ratio, median, samples)

    /** The card pass failed [tries] times in a row and stops trying. */
    fun givenUpLine(tries: Int): String =
        "card focus failed $tries times in a row — no more card passes until a tap, a new Area, a camera re-open " +
            "or the soft-capture watchdog; the lens stays where it is"

    /** "12.3 s" / "2 min 5 s" / "1 h 3 min" — how old a reading is. */
    fun age(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return when {
            s < 60 -> if (ms < 10_000) "%.1f s".format(ms.coerceAtLeast(0) / 1000.0) else "$s s"
            s < 3600 -> "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }
}

/**
 * The session's focus passes, for the Diagnostics "focus" line. Thread-safe
 * (results land on the main thread, the watchdog on the capture thread).
 */
class FocusStats(private val clock: () -> Long = System::currentTimeMillis) {
    private var focused = 0
    private var failed = 0
    private var timedOut = 0
    private var cancelled = 0
    private var watchdog = 0
    private var givenUp = 0
    private var last: String? = null
    private var lastAt = 0L

    @Synchronized fun record(outcome: FocusLog.Outcome, why: String, elapsedMs: Long) {
        when (outcome) {
            FocusLog.Outcome.FOCUSED -> focused++
            FocusLog.Outcome.FAILED -> failed++
            FocusLog.Outcome.TIMED_OUT -> timedOut++
            FocusLog.Outcome.CANCELLED, FocusLog.Outcome.SUPERSEDED -> cancelled++
        }
        last = "$why: ${outcome.word} in $elapsedMs ms"
        lastAt = clock()
    }

    @Synchronized fun watchdogFired() { watchdog++ }
    @Synchronized fun gaveUp() { givenUp++ }

    /** "passes 3 focused · 1 NOT focused · 0 timed out · 1 cancelled · last: … (2 min 5 s ago) · watchdog re-arms 0 · …" */
    @Synchronized fun summary(): String = buildString {
        append("passes this session: $focused focused · $failed NOT focused · $timedOut timed out · $cancelled cancelled")
        append(" · last: ").append(last?.let { "$it (${FocusLog.age(clock() - lastAt)} ago)" } ?: "none yet")
        append(" · watchdog re-arms $watchdog")
        if (givenUp > 0) append(" · card focus given up $givenUp×")
    }
}

/**
 * The camera's per-frame report (CaptureResult), kept for the last frames by
 * SENSOR_TIMESTAMP — which is the ImageProxy timestamp of the same frame — so
 * a capture can say what AF state and lens position ITS frames had, not just
 * whatever the newest frame said. Written on the camera thread, read on the
 * analysis / capture threads.
 */
class FrameMetaRing(private val capacity: Int = 64) {
    class Meta(val timestampNs: Long, val afState: Int?, val focusDpt: Float?, val exposureNs: Long?, val iso: Int?) {
        /** "AF FOCUSED_LOCKED · lens 4.20 dpt (≈ 24 cm) · exposure 16.7 ms · ISO 400". */
        fun describe(calibrated: Boolean): String =
            FocusLog.afAndLens(afState, focusDpt, calibrated) +
                (exposureNs?.let { " · exposure %.1f ms".format(it / 1e6) } ?: "") +
                (iso?.let { " · ISO $it" } ?: "")
    }

    private val ring = ArrayDeque<Meta>()

    @Synchronized fun put(m: Meta) {
        ring.addLast(m)
        while (ring.size > capacity) ring.removeFirst()
    }

    /** The frame with exactly this sensor timestamp, or null (already rolled out / not reported yet). */
    @Synchronized fun at(timestampNs: Long): Meta? = ring.lastOrNull { it.timestampNs == timestampNs }

    @Synchronized fun latest(): Meta? = ring.lastOrNull()

    @Synchronized fun clear() = ring.clear()
}
