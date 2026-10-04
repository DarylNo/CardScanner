package io.github.darylno.cardscanner.core

/**
 * Where the Tray locks focus (owner on 1.1.10: "No zoom? Focus working?").
 *
 * - The lock at a bind / camera re-open / new Area / mode switch has no card
 *   to aim at yet: the CENTRE OF THE SCAN AREA (the frame centre with no Area).
 * - The first-card pass (and every pass the soft-capture watchdog or a failed
 *   pass re-arms) runs while a card sits still in the Area: the CENTRE OF THE
 *   CARD, i.e. the Trigger's mask box mapped out of the [sampleW]×[sampleH]
 *   detection sample (which covers [area] of the upright frame). Before 1.1.11
 *   that pass metered the Area centre too, so a card lying off-centre in a
 *   wide Area was focused on through the tray beside it.
 *
 * Pure (JVM-tested); the camera code maps the result onto the sensor with
 * [Point.toSensor].
 */
object FocusPoint {
    /**
     * A metering point as fractions [u], [v] of the UPRIGHT frame. [onCard] =
     * it is the card's centre (else the Area / frame centre).
     */
    class Point(val u: Double, val v: Double, val onCard: Boolean) {
        /** "card centre (0.52, 0.48)" / "Area centre (0.50, 0.50)" — the focus log's words. */
        fun describe(): String = (if (onCard) "card centre" else "Area centre") + " (%.2f, %.2f)".format(u, v)

        /**
         * The point in SENSOR pixels of a [sw]×[sh] sensor frame delivered at
         * [rotation] (CameraX rotationDegrees): `[x, y]`, inside the frame.
         */
        fun toSensor(sw: Int, sh: Int, rotation: Int): IntArray {
            val uw = Rotation.uprightWidth(sw, sh, rotation)
            val uh = Rotation.uprightHeight(sw, sh, rotation)
            val x = (u * uw).toInt().coerceIn(0, uw - 1)
            val y = (v * uh).toInt().coerceIn(0, uh - 1)
            return intArrayOf(Rotation.sensorX(x, y, sw, sh, rotation), Rotation.sensorY(x, y, sw, sh, rotation))
        }
    }

    /** The Area's centre (the frame's with no Area): the lock before any card is there. */
    fun areaCentre(area: RoiFrac?): Point {
        val a = area ?: FULL
        return Point((a.x0 + a.x1) / 2, (a.y0 + a.y1) / 2, onCard = false)
    }

    /**
     * The card's centre when [box] (sample pixels; it covers pixels x..x+w-1)
     * is known and the sample size is valid, else [areaCentre].
     */
    fun choose(area: RoiFrac?, box: Box?, sampleW: Int, sampleH: Int): Point {
        if (box == null || sampleW <= 0 || sampleH <= 0 || box.w <= 0 || box.h <= 0) return areaCentre(area)
        val a = area ?: FULL
        val fx = ((box.x + box.w / 2.0) / sampleW).coerceIn(0.0, 1.0)
        val fy = ((box.y + box.h / 2.0) / sampleH).coerceIn(0.0, 1.0)
        return Point(a.x0 + (a.x1 - a.x0) * fx, a.y0 + (a.y1 - a.y0) * fy, onCard = true)
    }

    private val FULL = RoiFrac(0.0, 0.0, 1.0, 1.0)
}
