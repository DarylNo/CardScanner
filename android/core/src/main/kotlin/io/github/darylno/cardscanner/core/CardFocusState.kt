package io.github.darylno.cardscanner.core

/**
 * The Tray's card-focus pass — moved out of CameraController (1.1.18) so it can be tested
 * with a clock: whether the NEXT card waits for an AF pass metered on it ([isDue]), why
 * ([why], the focus log's words), how many passes of the current chain failed (3 tries,
 * then it gives up), and when the last pass on a card — or a tap — ANSWERED
 * ([lastCardFocusNs]), which "Refocus on a card" ([RefocusPolicy], [minutes]) counts from.
 *
 * Who calls what:
 * - [arm] — the camera code, for the first card after a bind / camera re-open / new Area /
 *   mode switch / zoom change / the zoom probe, and the soft-capture watchdog (each starts
 *   a fresh chain); [disarm] — a tap (the owner's point wins) or leaving Mount.
 * - [holdForCard] — the ANALYSIS thread at every Tray trigger: hold this capture for a
 *   pass? True when one is armed; else, when the policy says a periodic pass is due, it
 *   arms one (logging ONE line) and answers true. Never arms on top of an armed pass.
 * - [passEnded] — the main thread, when a card pass ends: an answer (focused or NOT
 *   focused — a lens that will not converge is then not asked again on every card) moves
 *   the clock; a cancelled / superseded pass (null) does not. [tapAnswered] — a tap's answer.
 * - [bound] — a fresh bind forgets the last card focus (the bind arms the first card).
 *
 * Thread-safety: every read and write is under this object's monitor — the analysis
 * thread's check-then-arm in [holdForCard] is ONE step, so a pass answered on the main
 * thread (or the watchdog's arm on the capture thread) can never land between the check and
 * the arm: exactly one periodic pass per due interval. Nothing blocks inside the monitor and
 * nothing is called from it ([log] runs after it is released), so no lock order can form.
 */
class CardFocusState(private val tries: Int = TRIES, private val log: (String) -> Unit = {}) {
    /** How a card pass ended for the chain: focused (disarmed), retry on the next card, or the chain gave up. */
    enum class End { FOCUSED, RETRY, GAVE_UP }

    private var due = false
    private var whyNow = "first card"
    private var fails = 0
    private var lastAnsweredNs: Long? = null
    private var interval = RefocusPolicy.DEFAULT
    private var periodicArms = 0

    /** "Refocus on a card" in minutes ([RefocusPolicy]; normalized: a value not offered reads as the default). */
    var minutes: Int
        @Synchronized get() = interval
        @Synchronized set(v) { interval = RefocusPolicy.normalize(v) }

    /** The next card waits for a pass on it. */
    val isDue: Boolean @Synchronized get() = due
    /** Why the next (or the running) card pass runs — the focus log's words. */
    val why: String @Synchronized get() = whyNow
    /** When the last pass on a card, or a tap, answered (the caller's clock); null = none since the bind. */
    val lastCardFocusNs: Long? @Synchronized get() = lastAnsweredNs
    /** Periodic passes armed since this object was made (Diagnostics). */
    val periodic: Int @Synchronized get() = periodicArms

    /** The next card gets a pass ([why] goes in the focus log); a fresh chain — the failure count starts again. */
    @Synchronized fun arm(why: String) { whyNow = why; fails = 0; due = true }

    @Synchronized fun disarm() { due = false }

    /** A fresh bind: the last card focus is forgotten (the bind arms the first-card pass itself). */
    @Synchronized fun bound() { lastAnsweredNs = null }

    /**
     * The analysis thread, at a Tray trigger at [nowNs]: hold this capture for a focus pass on
     * the card? An armed pass → true. Else, when [RefocusPolicy.due] says so, a periodic pass is
     * armed — a fresh chain, its why "periodic: every N min — M min since the last focus on a
     * card" — logged once, and true. Else false: the card shoots at once.
     */
    fun holdForCard(nowNs: Long): Boolean {
        val armedLine: String
        synchronized(this) {
            if (due) return true
            if (!RefocusPolicy.due(nowNs, lastAnsweredNs, interval)) return false
            arm(RefocusPolicy.periodicWhy(nowNs, lastAnsweredNs, interval))
            periodicArms++
            armedLine = "periodic refocus: " + whyNow.removePrefix("periodic: ") + " — this card is held for a focus pass on it"
        }
        runCatching { log(armedLine) }
        return true
    }

    /**
     * A card pass ended at [nowNs] with [ok] (true focused, false NOT focused / timed out, null
     * cancelled or superseded — no answer). Answers move [lastCardFocusNs]. Focused → disarmed;
     * else the chain counts a failure: [End.RETRY] on the next card (its why says so) until
     * [tries] failed, then [End.GAVE_UP] (disarmed — the next periodic pass starts a new chain).
     */
    @Synchronized fun passEnded(ok: Boolean?, nowNs: Long): End {
        if (ok != null) lastAnsweredNs = nowNs
        if (ok == true) { due = false; return End.FOCUSED }
        if (++fails >= tries) { due = false; return End.GAVE_UP }
        whyNow = "retry ${fails + 1} of $tries (the last card pass failed)"
        return End.RETRY
    }

    /** A tap's focus answered at [nowNs] (focused or not): the owner's point counts as the last card focus. */
    @Synchronized fun tapAnswered(nowNs: Long) { lastAnsweredNs = nowNs }

    /** Diagnostics: "refocus every 5 min · last card focus 3 min ago". */
    @Synchronized fun describe(nowNs: Long): String = RefocusPolicy.describe(interval, nowNs, lastAnsweredNs)

    companion object {
        /** A card focus that fails is retried on the next card, this many passes in all. */
        const val TRIES = 3
    }
}
