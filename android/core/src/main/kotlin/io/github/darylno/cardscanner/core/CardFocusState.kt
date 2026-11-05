package io.github.darylno.cardscanner.core

/**
 * The Tray's card-focus pass — moved out of CameraController (1.1.18) so it can be tested
 * with a clock: whether the NEXT card waits for an AF pass metered on it ([isDue]), why
 * ([why], the focus log's words), how long its hold waits ([Hold]), the passes under way
 * ([passStarted] / [passEnded]: 3 tries a chain, then it gives up), and the clock "Refocus on
 * a card" ([RefocusPolicy], [minutes]) counts from: the later of the last pass on a card — or
 * a tap — that ANSWERED ([lastCardFocusNs]) and the end of the last chain.
 *
 * Who calls what:
 * - [arm] — the camera code, for the first card after a bind / camera re-open / new Area /
 *   mode switch / zoom change / the zoom probe (`resumesRefocus`: each also lifts a tap's
 *   pause), and the soft-capture watchdog (it does not) — each starts a fresh chain;
 *   [disarm] — leaving Mount; [tapped] — a tap lock in Tray: the owner chose the focus point,
 *   so no card pass and no periodic refocus until one of the resuming arms (logged once).
 * - [holdFor] — the ANALYSIS thread at every Tray trigger: hold this capture for a pass, and
 *   how ([Hold])? An armed pass → its chain's kind. Else, when the policy says a periodic pass
 *   is due (and no tap paused it), it arms one (logging ONE line) → [Hold.PERIODIC]. Never
 *   arms on top of an armed pass.
 * - [passStarted] / [passEnded] — the main thread, around each card pass. A pass still out
 *   when the next one starts is CUT OFF by it (CameraX cancels it): it counts as ended with
 *   no answer — a failure toward the chain's give-up — and its own late end is [End.CUT_OFF]
 *   (nothing counted twice; it must not release the newer pass's hold). An answer (focused or
 *   NOT focused — a lens that will not converge is then not asked again on every card) moves
 *   [lastCardFocusNs]; no answer (cancelled, cut off, an error) does not. A chain END (focused
 *   or given up) restarts the interval too, so a chain that never answers waits an interval
 *   before the next one (review of 1.1.18: it restarted on the very next card, forever).
 *   [tapAnswered] — a tap's answer.
 * - [bound] — a fresh bind forgets the clock (the bind arms the first card).
 *
 * Thread-safety: every read and write is under this object's monitor — the analysis
 * thread's check-then-arm in [holdFor] is ONE step, so a pass answered on the main thread
 * (or the watchdog's arm on the capture thread) can never land between the check and the
 * arm: exactly one periodic pass per due interval. Nothing blocks inside the monitor and
 * nothing is called from it ([log] runs after it is released), so no lock order can form.
 */
class CardFocusState(private val tries: Int = TRIES, private val log: (String) -> Unit = {}) {
    /**
     * How a card pass ended: [FOCUSED] (the chain ends), [RETRY] on the next card, [GAVE_UP]
     * (the chain ends after [TRIES] failures), [OUTSIDE] (its chain was already over — given up,
     * re-armed or disarmed: nothing counted, though an answer still moves the clock), or
     * [CUT_OFF] (the next card's pass cut it off and counted it then: nothing more).
     */
    enum class End { FOCUSED, RETRY, GAVE_UP, OUTSIDE, CUT_OFF }

    /**
     * How long a held Tray trigger waits for its pass. [CARD]: the first card after a bind /
     * re-open / new Area / mode switch / zoom change / the probe, the watchdog's pass and their
     * retries — the 1.1.14 hold, unchanged. [PERIODIC]: a "Refocus on a card" chain (Every
     * card included), its retries too — it waits for the camera's answer (the analyzer's cap is
     * just above CameraX's own 5 s AF timeout), so a periodic pass never shoots mid-sweep.
     */
    enum class Hold { CARD, PERIODIC }

    /** A card pass under way: handed out by [passStarted], given back to [passEnded]. */
    class Pass internal constructor(val number: Int, internal val chain: Int)

    /** [passStarted]'s answer: the new [pass], and how the pass it cut off ended ([cutOff]; null = none was out). */
    class Started(val pass: Pass, val cutOff: End?)

    private var due = false
    private var whyNow = "first card"
    private var fails = 0
    /** Bumped by every arm: a pass of an older chain ends [End.OUTSIDE]. */
    private var chain = 0
    private var chainPeriodic = false
    private var out: Pass? = null
    private var passes = 0
    private var lastAnsweredNs: Long? = null
    private var chainEndedNs: Long? = null
    private var lastChainGaveUp = false
    private var paused = false
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
    /** When the last chain ended (focused or gave up); null = none since the bind. */
    val lastChainEndNs: Long? @Synchronized get() = chainEndedNs
    /** A tap paused "Refocus on a card" (until a new Area, a camera re-open or a mode switch). */
    val isPaused: Boolean @Synchronized get() = paused
    /** Periodic passes armed since this object was made (Diagnostics). */
    val periodic: Int @Synchronized get() = periodicArms

    /**
     * The next card gets a pass ([why] goes in the focus log): a fresh chain — the failure count
     * starts again — held as [Hold.CARD]. [resumesRefocus]: the first-card arms (bind / re-open /
     * new Area / mode switch / zoom change / the probe — each re-locks somewhere else, so the
     * tap's point is gone) lift a tap's pause, logged once; the watchdog's arm does not. Any thread.
     */
    fun arm(why: String, resumesRefocus: Boolean = false) {
        val resumed: Boolean
        synchronized(this) {
            chain++; whyNow = why; fails = 0; due = true; chainPeriodic = false
            resumed = resumesRefocus && paused
            if (resumed) paused = false
        }
        if (resumed) runCatching { log("refocus on a card resumed — $why") }
    }

    /** Leaving Mount: no card pass. */
    @Synchronized fun disarm() { due = false }

    /**
     * A tap lock in Tray: the owner chose the focus point — no card pass, and "Refocus on a card"
     * pauses until a new Area, a camera re-open, a mode switch (or a zoom change / the zoom probe,
     * which re-lock elsewhere anyway). The watchdog still arms its one pass on a soft capture.
     * Logged once per pause; true when this tap began it.
     */
    fun tapped(): Boolean {
        val began: Boolean
        val label: String
        synchronized(this) {
            due = false
            began = !paused
            paused = true
            label = RefocusPolicy.label(interval)
        }
        if (began) runCatching {
            log("refocus paused — you tapped to focus; no periodic pass until a new Area, a camera re-open or a mode switch " +
                "(Refocus on a card: $label)")
        }
        return began
    }

    /** A fresh bind: the clock is forgotten (the bind arms the first-card pass itself). */
    @Synchronized fun bound() { lastAnsweredNs = null; chainEndedNs = null; lastChainGaveUp = false }

    /**
     * The analysis thread, at a Tray trigger at [nowNs]: hold this capture for a focus pass on
     * the card, and how? An armed pass → its chain's [Hold]. Else — no tap pausing it — when
     * [RefocusPolicy.due] says so, a periodic pass is armed — a fresh chain, its why "periodic:
     * every N min — M min since the last focus on a card" — logged once, [Hold.PERIODIC]. Else
     * null: the card shoots at once.
     */
    fun holdFor(nowNs: Long): Hold? {
        val armedLine: String
        synchronized(this) {
            if (due) return if (chainPeriodic) Hold.PERIODIC else Hold.CARD
            if (paused) return null
            val since = sinceLocked()
            if (!RefocusPolicy.due(nowNs, since, interval)) return null
            chain++; fails = 0; due = true; chainPeriodic = true
            // The clock counts from a give-up when that was the later event (the words say so).
            val fromGiveUp = lastChainGaveUp && chainEndedNs != null && since == chainEndedNs
            whyNow = RefocusPolicy.periodicWhy(nowNs, since, interval, fromGiveUp)
            periodicArms++
            armedLine = "periodic refocus: " + whyNow.removePrefix("periodic: ") + " — this card is held for a focus pass on it"
        }
        runCatching { log(armedLine) }
        return Hold.PERIODIC
    }

    /** [holdFor] as a yes / no. */
    fun holdForCard(nowNs: Long): Boolean = holdFor(nowNs) != null

    /**
     * The main thread, as a card pass starts at [nowNs]: its [Pass]. A pass still out is CUT
     * OFF by this one (CameraX cancels it): it ends here with no answer — a failure toward the
     * give-up ([Started.cutOff] says how its chain took it) — so passes cut off card after card
     * (a lens slower than the hold) still end their chain.
     */
    @Synchronized fun passStarted(nowNs: Long): Started {
        val cut = out?.let { endLocked(it, null, nowNs, cutOff = true) }
        val p = Pass(++passes, chain)
        out = p
        return Started(p, cut)
    }

    /**
     * The main thread: [pass] ended at [nowNs] with [ok] (true focused, false NOT focused / timed
     * out, null cancelled / an error — no answer). [End.CUT_OFF] when the next pass already cut it
     * off (counted then; it must not release the newer pass's hold). Else answers move
     * [lastCardFocusNs]; a pass of the current chain: focused → [End.FOCUSED] (the chain ends);
     * else a failure — [End.RETRY] on the next card (its why says so) until [tries] failed, then
     * [End.GAVE_UP] (the chain ends: the next periodic pass is one interval later). A pass whose
     * chain is over → [End.OUTSIDE].
     */
    @Synchronized fun passEnded(pass: Pass, ok: Boolean?, nowNs: Long): End {
        if (out !== pass) return End.CUT_OFF
        out = null
        return endLocked(pass, ok, nowNs, cutOff = false)
    }

    private fun endLocked(pass: Pass, ok: Boolean?, nowNs: Long, cutOff: Boolean): End {
        if (ok != null) lastAnsweredNs = nowNs
        if (pass.chain != chain || !due) return End.OUTSIDE
        if (ok == true) { due = false; chainEndedNs = nowNs; lastChainGaveUp = false; return End.FOCUSED }
        if (++fails >= tries) { due = false; chainEndedNs = nowNs; lastChainGaveUp = true; return End.GAVE_UP }
        whyNow = "retry ${fails + 1} of $tries (the last card pass " + (if (cutOff) "was cut off by the next card" else "failed") + ")"
        return End.RETRY
    }

    /** A tap's focus answered at [nowNs] (focused or not): the owner's point counts as the last card focus. */
    @Synchronized fun tapAnswered(nowNs: Long) { lastAnsweredNs = nowNs }

    /** The later of the last answer and the last chain end: what the interval counts from. */
    private fun sinceLocked(): Long? {
        val a = lastAnsweredNs
        val e = chainEndedNs
        return if (a == null) e else if (e == null) a else maxOf(a, e)
    }

    /** Diagnostics: "refocus every 5 min · last card focus 3 min ago" ("refocus paused — you tapped to focus (every 5 min) · …"). */
    @Synchronized fun describe(nowNs: Long): String = RefocusPolicy.describe(interval, nowNs, lastAnsweredNs, paused)

    companion object {
        /** A card focus that fails is retried on the next card, this many passes in all. */
        const val TRIES = 3
    }
}
