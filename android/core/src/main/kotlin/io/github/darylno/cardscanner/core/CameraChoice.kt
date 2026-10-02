package io.github.darylno.cardscanner.core

import java.util.Locale

/**
 * Which camera scans (owner, 2026-10-02: "add it to a camera setting. Thinking
 * about when people are brought on to test. Different phones with different
 * options"). Pure: the app reads the phone's lenses ([Lens]) from Camera2 and
 * this decides.
 *
 * AUTOMATIC (no choice saved) is the rule the app always had: camera "0" when
 * it faces back — on the Nord N200 that is the 13 MP main camera, and its id 3
 * is a 2 MP fixed-focus module that must never be picked by default — else the
 * first back camera, else the first external one. A saved choice the phone
 * doesn't have (a settings backup restored onto another phone, an external
 * camera unplugged) falls back to automatic rather than to nothing.
 *
 * Front cameras are never offered: the phone is mounted screen-up, and the
 * preview of a front camera is mirrored while its analysis frames are not, so a
 * drawn scan Area would land on the wrong side of the card.
 */
object CameraChoice {
    enum class Facing { BACK, FRONT, EXTERNAL }

    /**
     * One camera as Camera2 reports it. [megapixels]: the sensor's pixel array;
     * [autofocus]: true = can focus (minimum focus distance > 0), false = fixed
     * focus, null = unknown; [focalMm]: the first focal length.
     */
    data class Lens(
        val id: String, val facing: Facing,
        val megapixels: Double? = null, val autofocus: Boolean? = null, val focalMm: Float? = null,
    )

    /** The lenses a user may pick from: back and external, in id order as the phone lists them. */
    fun offered(lenses: List<Lens>): List<Lens> = lenses.filter { it.facing != Facing.FRONT }

    /** The camera Automatic uses on this phone, or null when there is none. */
    fun automatic(lenses: List<Lens>): String? {
        val o = offered(lenses)
        return o.firstOrNull { it.id == "0" && it.facing == Facing.BACK }?.id
            ?: o.firstOrNull { it.facing == Facing.BACK }?.id
            ?: o.firstOrNull()?.id
    }

    /** The camera to open for the saved [chosen] (null = automatic): the choice when this phone offers it, else automatic. */
    fun resolve(lenses: List<Lens>, chosen: String?): String? =
        if (chosen != null && offered(lenses).any { it.id == chosen }) chosen else automatic(lenses)

    /** "Camera 0 · back · 13 MP · autofocus · 4.7 mm" — what a tester needs to tell the lenses apart. */
    fun label(l: Lens): String = listOfNotNull(
        "Camera ${l.id}",
        l.facing.name.lowercase(Locale.ROOT),
        l.megapixels?.let { if (it >= 10) "%.0f MP".format(Locale.ROOT, it) else "%.1f MP".format(Locale.ROOT, it) },
        when (l.autofocus) { true -> "autofocus"; false -> "fixed focus"; null -> null },
        l.focalMm?.let { "%.1f mm".format(Locale.ROOT, it) },
    ).joinToString(" · ")
}
