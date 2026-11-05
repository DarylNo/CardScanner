package io.github.darylno.cardscanner.core

/**
 * "Refocus on a card" (1.1.18, owner 2026-11-05: "Need it to focus every once in a while").
 *
 * The Tray locks the lens: a card-metered AF pass runs only for the FIRST card after a
 * bind / camera re-open / new Area / mode switch / zoom change, after the soft-capture
 * watchdog (which a slow drift never trips — the median it compares against drifts too),
 * or on a tap. A lens that drifts (heat, a nudged mount, a thicker sleeve) stayed soft for
 * the whole session. This policy adds a pass on the next card once [minutes] have passed
 * since the last focus on a card; the hold → pass → retry / give-up machinery is the same.
 *
 * Choices (Settings → Camera, `/api/device` `refocus_min`): [EVERY_CARD] (each card waits
 * for its own pass), 2 / 5 / 10 / 30 minutes, [NEVER] (the first-card pass only, as before).
 * [DEFAULT] 5 min is PROVISIONAL — a judgement, not a measurement: the rig's `focus` lines
 * (how often a periodic pass moves the lens, and by how much) decide it.
 *
 * Pure (JVM-tested); [CardFocusState] holds the state the camera code asks it about.
 */
object RefocusPolicy {
    /** Every card waits for a focus pass on it (up to the analyzer's 1.5 s hold). */
    const val EVERY_CARD = 0
    /** Only the first card after a camera start / new Area / zoom change (the pre-1.1.18 behaviour). */
    const val NEVER = -1
    /** Settings → Camera → "Refocus on a card", in the order offered (minutes). */
    val CHOICES: IntArray = intArrayOf(EVERY_CARD, 2, 5, 10, 30, NEVER)
    /** PROVISIONAL (see the class): a judgement until the rig's focus lines are in. */
    const val DEFAULT = 5

    private const val NS_PER_S = 1_000_000_000L
    private const val NS_PER_MIN = 60 * NS_PER_S

    fun isChoice(minutes: Int): Boolean = minutes in CHOICES

    /** A stored value that is not offered (an older or hand-edited one) reads as the default. */
    fun normalize(minutes: Int): Int = if (isChoice(minutes)) minutes else DEFAULT

    /**
     * Is a periodic pass due on the card now at [nowNs]? [lastCardFocusNs] = when the last pass
     * on a card (or a tap) ANSWERED, on the same monotonic clock, or null when none has since the
     * bind. [NEVER] (any negative) → false; [EVERY_CARD] → true; else at least [minutes] since it.
     *
     * No answer yet is NOT due: every bind / re-open / new Area / mode switch / zoom change arms
     * the first-card pass itself, so a periodic arm there would arm twice; and when that chain
     * ended with no answer (cancelled three times) or a tap is still in flight, the policy waits
     * for an answer rather than overriding it. A clock that went back is not due either.
     */
    fun due(nowNs: Long, lastCardFocusNs: Long?, minutes: Int): Boolean = when {
        minutes < 0 -> false
        minutes == EVERY_CARD -> true
        lastCardFocusNs == null -> false
        else -> nowNs - lastCardFocusNs >= minutes * NS_PER_MIN
    }

    /** "Every card", "Every 5 min", "Never" — the Settings row, the desktop's dropdown and the log. */
    fun label(minutes: Int): String = when {
        minutes < 0 -> "Never"
        minutes == EVERY_CARD -> "Every card"
        else -> "Every $minutes min"
    }

    /** The focus log's why for a periodic pass: "periodic: every 5 min — 7 min since the last focus on a card" / "periodic: every card". */
    fun periodicWhy(nowNs: Long, lastCardFocusNs: Long?, minutes: Int): String =
        if (minutes == EVERY_CARD || lastCardFocusNs == null) "periodic: every card"
        else "periodic: every $minutes min — ${(nowNs - lastCardFocusNs).coerceAtLeast(0) / NS_PER_MIN} min since the last focus on a card"

    /** Diagnostics' focus line: "refocus every 5 min · last card focus 3 min ago" ("refocus never", "… none yet"). */
    fun describe(minutes: Int, nowNs: Long, lastCardFocusNs: Long?): String =
        "refocus " + label(minutes).replaceFirstChar { it.lowercase() } +
            " · last card focus " + (lastCardFocusNs?.let { ago(nowNs - it) + " ago" } ?: "none yet")

    /** "42 s", "3 min", "2 h 5 min" (whole units, rounded down). */
    private fun ago(ns: Long): String {
        val s = ns.coerceAtLeast(0) / NS_PER_S
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }
}
