package io.github.darylno.cardscanner.core

/**
 * Upright ↔ sensor coordinate mapping for camera frames.
 *
 * The camera delivers frames in the SENSOR's orientation (landscape on the
 * Nord N200 even when the phone is held portrait); everything the user sees
 * and draws — the scan area, the detection box, the uploaded crop — lives in
 * the UPRIGHT frame. Rotating every analysis frame would cost a full-frame
 * copy 5×/s, so the samplers read sensor pixels through this mapping instead
 * and only the three captured frames are ever physically rotated
 * ([Nv21Bgr]).
 *
 * Convention = CameraX `ImageInfo.rotationDegrees`: the clockwise rotation
 * that turns the SENSOR image upright, one of 0/90/180/270. With a sensor
 * image `sw`×`sh`, the upright image is `sh`×`sw` for 90/270. An upright pixel
 * (u, v) comes from sensor pixel (x, y):
 *
 *     0:   (u, v)
 *     90:  (v, sh-1-u)
 *     180: (sw-1-u, sh-1-v)
 *     270: (sw-1-v, u)
 *
 * which is exactly OpenCV `Core.rotate` with ROTATE_90_CLOCKWISE /
 * ROTATE_180 / ROTATE_90_COUNTERCLOCKWISE (RotationTest proves it on random
 * mats), so the sampled grey and the uploaded BGR can never disagree about
 * where the scan area is.
 */
object Rotation {
    /** Validates a CameraX rotation (any multiple of 90, negatives allowed) and folds it to 0/90/180/270. */
    fun normalize(degrees: Int): Int {
        require(degrees % 90 == 0) { "rotation must be a multiple of 90, got $degrees" }
        return ((degrees % 360) + 360) % 360
    }

    /** True when the upright image swaps the sensor's width and height. */
    fun swapsAxes(rotation: Int): Boolean = normalize(rotation) % 180 != 0

    fun uprightWidth(sw: Int, sh: Int, rotation: Int): Int = if (swapsAxes(rotation)) sh else sw

    fun uprightHeight(sw: Int, sh: Int, rotation: Int): Int = if (swapsAxes(rotation)) sw else sh

    /** Sensor x of upright pixel (u, v). */
    fun sensorX(u: Int, v: Int, sw: Int, sh: Int, rotation: Int): Int = when (normalize(rotation)) {
        0 -> u
        90 -> v
        180 -> sw - 1 - u
        else -> sw - 1 - v
    }

    /** Sensor y of upright pixel (u, v). */
    fun sensorY(u: Int, v: Int, sw: Int, sh: Int, rotation: Int): Int = when (normalize(rotation)) {
        0 -> v
        90 -> sh - 1 - u
        180 -> sh - 1 - v
        else -> u
    }

    /** Upright u of sensor pixel (x, y) — the inverse of [sensorX]/[sensorY]. */
    fun uprightU(x: Int, y: Int, sw: Int, sh: Int, rotation: Int): Int = when (normalize(rotation)) {
        0 -> x
        90 -> sh - 1 - y
        180 -> sw - 1 - x
        else -> y
    }

    /** Upright v of sensor pixel (x, y). */
    fun uprightV(x: Int, y: Int, sw: Int, sh: Int, rotation: Int): Int = when (normalize(rotation)) {
        0 -> y
        90 -> x
        180 -> sh - 1 - y
        else -> sw - 1 - x
    }
}
