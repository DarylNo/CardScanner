package io.github.darylno.cardscanner.ui

import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.server.DeviceApi

/**
 * The guided measurement session (owner, 2026-10-02: "a test mode that prompts
 * me to do all these tests"): the rig session that decides the shadow-proof
 * threshold, whether real sleeved cards yield a live outline, the card's real
 * pixel size (the zoom question) and the N200 timings — as a card on the scan
 * screen that walks through the steps, counts the captures during each one,
 * applies the settings a step needs (AE lock, vibration, check mark time) and
 * restores the owner's own at the end, and writes a `test` marker into the
 * debug log at every step so the `shadow` / `outline` / `capture` lines can be
 * read against what was being done. The session survives leaving the screen
 * (step and counters live in [AppSettings]); Stop or the last step ends it.
 *
 * Pure state: the scan screen hosts the card, this decides what it says.
 */
class MeasureSession(
    private val settings: AppSettings,
    private val log: DebugLog = DebugLog.global,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * One step. [target] = how many times to do it (0 = not counted); [minutes] = how
     * long to hold it (0 = no timer); [question] asks for a Yes/No before Next; the
     * nullable settings are applied when the step starts.
     */
    class Step(
        val id: String,
        val title: String,
        val instruction: String,
        val target: Int = 0,
        val minutes: Int = 0,
        val question: String? = null,
        val aeLock: Boolean? = null,
        val vibration: Boolean? = null,
        val checkMs: Int? = null,
    )

    val steps: List<Step> get() = STEPS
    val active: Boolean get() = settings.measureStep >= 0
    val index: Int get() = settings.measureStep.coerceIn(0, STEPS.size - 1)
    val step: Step get() = STEPS[index]
    /** Captures taken since this step started. */
    val captures: Int get() = settings.measureCaptures
    val stepStartedAt: Long get() = settings.measureStepStartedAt
    /** The Yes/No given for this step, or null. */
    val answer: Boolean? get() = settings.measureAnswer
    val isLast: Boolean get() = index == STEPS.size - 1

    /** Begin at step 1: remember the owner's own settings, apply the first step's, log. */
    fun start() {
        if (active) finish(abandoned = true)
        settings.measureOriginal = "${settings.aeLock},${settings.vibration},${settings.checkMs}"
        log.i("test", "session START (your settings: AE lock ${settings.aeLock}, vibration ${settings.vibration}, check ${settings.checkMs} ms)")
        enter(0)
    }

    /** A capture happened while the session is on. */
    fun captured() {
        if (!active) return
        settings.measureCaptures = settings.measureCaptures + 1
    }

    fun answer(yes: Boolean) {
        if (!active) return
        settings.measureAnswer = yes
        log.i("test", "step ${index + 1}/${STEPS.size} ${step.id}: answer ${if (yes) "YES" else "NO"} — ${step.question}")
    }

    /** Next step; on the last step this ends the session. */
    fun next() {
        if (!active) return
        leave("done")
        if (isLast) finish(abandoned = false) else enter(index + 1)
    }

    fun back() {
        if (!active || index == 0) return
        leave("went back")
        enter(index - 1)
    }

    /** Stop (abandoned) or the last step done: restore the owner's settings, clear the state. */
    fun finish(abandoned: Boolean) {
        if (!active) return
        if (abandoned) leave("stopped")
        val done = if (abandoned) index else STEPS.size
        restore()
        log.i("test", "session END (${if (abandoned) "stopped at step ${index + 1}" else "all ${STEPS.size} steps done"}, $done/${STEPS.size}; your settings restored)")
        settings.measureStep = -1
        settings.measureCaptures = 0
        settings.measureAnswer = null
        settings.measureOriginal = null
    }

    private fun enter(i: Int) {
        settings.measureStep = i
        settings.measureCaptures = 0
        settings.measureAnswer = null
        settings.measureStepStartedAt = clock()
        val s = STEPS[i]
        s.aeLock?.let { settings.aeLock = it }
        s.vibration?.let { settings.vibration = it }
        s.checkMs?.let { settings.checkMs = it }
        val applied = listOfNotNull(s.aeLock?.let { "AE lock $it" }, s.vibration?.let { "vibration $it" }, s.checkMs?.let { "check $it ms" })
        log.i("test", "step ${i + 1}/${STEPS.size} START ${s.id}: ${s.title}" +
            (if (s.target > 0) " ×${s.target}" else "") + (if (s.minutes > 0) " ${s.minutes} min" else "") +
            (if (applied.isNotEmpty()) " [${applied.joinToString(", ")}]" else ""))
    }

    private fun leave(how: String) {
        val s = step
        val secs = ((clock() - settings.measureStepStartedAt) / 1000L).coerceAtLeast(0)
        log.i("test", "step ${index + 1}/${STEPS.size} END ${s.id} ($how) after $secs s: ${settings.measureCaptures} capture(s)" +
            (if (s.target > 0) " of ×${s.target}" else "") + (settings.measureAnswer?.let { " · answer ${if (it) "YES" else "NO"}" } ?: ""))
    }

    private fun restore() {
        val o = settings.measureOriginal?.split(",") ?: return
        if (o.size != 3) return
        o[0].toBooleanStrictOrNull()?.let { settings.aeLock = it }
        o[1].toBooleanStrictOrNull()?.let { settings.vibration = it }
        o[2].toIntOrNull()?.let { settings.checkMs = it }
    }

    companion object {
        /** The rig session (PR #27's checklist), in order. Durations and counts are the plan's. */
        val STEPS: List<Step> = listOf(
            Step("setup", "Set up",
                "Tray mode, with the Area drawn around the tray. Vibration is on and AE lock is off for now. " +
                "Put a card down: its outline should appear, go blue with a ✓ at the capture, then snap to the card's corners with no grey in between.",
                question = "Did the outline appear, go blue and snap to the card?",
                aeLock = false, vibration = true, checkMs = DeviceApi.CHECK_MS_DEFAULT),
            Step("short-check", "Short check time",
                "The check mark time is now 250 ms. Lift the card and put it back (or put a new one down): the ✓ should still hold until the capture finishes, with no grey flash before it.",
                question = "Did the ✓ hold with no grey flash?", checkMs = DeviceApi.CHECK_MS_MIN),
            Step("swaps", "Swaps without emptying the tray",
                "Swap cards 20 times WITHOUT the tray ever looking empty: drop the next card on top, or slide it in. Mix sizes, sleeved and unsleeved. Let each one settle.",
                target = 20, checkMs = DeviceApi.CHECK_MS_DEFAULT),
            Step("lift", "Lift and replace",
                "Lift the SAME card and put it straight back, about where it was. 10 times, some fast, some slow.", target = 10),
            Step("hands", "Hand passes",
                "Pass your hand over the card without touching it: 5 quick passes, 5 slow ones. Then rest your hand on the tray beside the card for 3 seconds, twice.", target = 10),
            Step("lean", "Lean in",
                "Lean over the tray so your shadow falls on the card, hold for 3 seconds, lean back. 10 times. Then do 3 over the EMPTY tray.", target = 10),
            Step("lights", "Lights",
                "Leave a card untouched and change the room light: lamp on and off, a blind or a door, your phone's torch from the side. About 3 minutes.", minutes = 3),
            Step("foil", "Foil under the lamp",
                "A sleeved foil under the lamp. Move your head and the lamp so the glare moves across it. About 1 minute.", minutes = 1),
            Step("ae-lock", "AE lock on",
                "AE lock is now ON. Repeat: 5 swaps without emptying the tray, then 5 lean-ins.", target = 10, aeLock = true),
            Step("done", "Done",
                "That is everything. Next ends the session, puts your settings back and opens the share sheet for the debug report — send it over."),
        )
    }
}
