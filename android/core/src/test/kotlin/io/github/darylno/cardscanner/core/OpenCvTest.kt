package io.github.darylno.cardscanner.core

import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * Loads the desktop OpenCV natives (org.openpnp bundles them) once per test
 * JVM, and reads the card-detect fixtures that scripts/export_detect_fixtures.py
 * writes from the SERVER's code.
 */
object OpenCvTest {
    private val loaded: Unit by lazy { nu.pattern.OpenCV.loadLocally() }

    fun load() = loaded

    /** A file under test resources `detect/`. */
    fun detectFile(name: String): File =
        File(requireNotNull(OpenCvTest::class.java.getResource("/detect/$name")) { "missing fixture detect/$name" }.toURI())

    /** A fixture PNG as 8UC3 BGR (lossless, so identical to the array the server saw). */
    fun readBgr(name: String): Mat {
        load()
        val m = Imgcodecs.imread(detectFile(name).path, Imgcodecs.IMREAD_COLOR)
        check(!m.empty()) { "could not read detect/$name" }
        return m
    }
}
