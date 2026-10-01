package io.github.darylno.cardscanner.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs

/**
 * [ScanPhoto] against the SERVER's `card_detect.scan_photo` answers per
 * synthetic scene (scripts/export_detect_fixtures.py; `--check` in CI):
 * found or kept, the photo's size and its pixels (a 1/4 thumbnail, mean abs
 * diff < [MAD_TOL] — the fixtures come from cv2, this runs OpenCV 4.9) — from
 * the frame as uploaded (`photo`) and from the phone's own flattened upload
 * (`flat_photo`, through the [Flatten] port on the server's quad), which is
 * what the phone files.
 */
class ScanPhotoParityTest {
    companion object {
        const val MAD_TOL = 2.0
        lateinit var expected: JSONObject
        lateinit var scenes: JSONObject

        @BeforeClass @JvmStatic
        fun loadFixtures() {
            OpenCvTest.load()
            expected = JSONObject(OpenCvTest.detectFile("expected.json").readText())
            scenes = expected.getJSONObject("scenes")
        }
    }

    private fun names(): List<String> = scenes.keys().asSequence().toList().sorted()

    private fun quadOf(entry: JSONObject): FloatArray? {
        if (entry.isNull("quad")) return null
        val a: JSONArray = entry.getJSONArray("quad")
        return FloatArray(8) { a.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
    }

    @Test
    fun constantsAreTheServers() {
        assertEquals(expected.getDouble("photo_fill"), ScanPhoto.FILL, 0.0)
        val m = expected.getJSONArray("photo_margin")
        assertEquals(m.getInt(0), ScanPhoto.MARGIN_X)
        assertEquals(m.getInt(1), ScanPhoto.MARGIN_Y)
        val s = expected.getJSONArray("photo_size")
        assertEquals(s.getInt(0), ScanPhoto.PHOTO_W)
        assertEquals(s.getInt(1), ScanPhoto.PHOTO_H)
        // … and they are the formula, not just the numbers.
        assertEquals(Math.round(CardQuad.CARD_W * (1 / ScanPhoto.FILL - 1) / 2).toInt(), ScanPhoto.MARGIN_X)
        assertEquals(Math.round(CardQuad.CARD_H * (1 / ScanPhoto.FILL - 1) / 2).toInt(), ScanPhoto.MARGIN_Y)
    }

    /** scan_photo([input]) against one fixture entry; appends a report line. */
    private fun check(name: String, input: Mat, want: JSONObject, thumbName: String, report: StringBuilder) {
        val r = ScanPhoto.of(listOf(input))
        try {
            assertEquals("$name: detected", want.getBoolean("detected"), r.detected)
            assertEquals(0, r.frame)
            val size = want.getJSONArray("size")
            val photo = r.photo
            if (photo == null) {
                // Kept as uploaded: the server's "photo" is the frame's own size.
                assertEquals("$name: kept width", size.getInt(0), input.cols())
                assertEquals("$name: kept height", size.getInt(1), input.rows())
                report.append(String.format("%-36s kept %dx%d\n", name, input.cols(), input.rows()))
                return
            }
            assertEquals("$name: photo width", size.getInt(0), photo.cols())
            assertEquals("$name: photo height", size.getInt(1), photo.rows())
            assertEquals(ScanPhoto.PHOTO_W, photo.cols())
            assertEquals(ScanPhoto.PHOTO_H, photo.rows())
            assertEquals(CvType.CV_8UC3, photo.type())
            val ref = OpenCvTest.readBgr(thumbName)
            val small = CardQuadParityTest.thumb(photo, expected.getInt("thumb"))
            try {
                assertEquals("$name: thumb size", ref.size(), small.size())
                val mad = CardQuadParityTest.meanAbsDiff(small, ref)
                report.append(String.format("%-36s photo %dx%d  MAD %.4f\n", name, photo.cols(), photo.rows(), mad))
                assertTrue("$name: photo differs from the server's, MAD $mad", mad < MAD_TOL)
            } finally {
                ref.release(); small.release()
            }
        } finally {
            r.photo?.release()
        }
    }

    @Test
    fun photoFromTheFrameMatchesTheServer() {
        val report = StringBuilder("scene (frame as uploaded)\n")
        var n = 0
        for (name in names()) {
            val entry = scenes.getJSONObject(name)
            if (!entry.has("photo")) continue
            n++
            val img = OpenCvTest.readBgr("$name.png")
            try {
                check(name, img, entry.getJSONObject("photo"), "$name.photo.png", report)
            } finally {
                img.release()
            }
        }
        println(report)
        assertTrue("no photo fixtures", n >= 9)
    }

    @Test
    fun photoFromTheFlattenedUploadMatchesTheServer() {
        val report = StringBuilder("scene (phone's flattened upload)\n")
        var n = 0
        for (name in names()) {
            val entry = scenes.getJSONObject(name)
            if (!entry.has("flat_photo")) continue
            n++
            val img = OpenCvTest.readBgr("$name.png")
            val flat = Flatten.flatten(img, quadOf(entry)!!, entry.getDouble("margin"))
            try {
                check("$name.flat", flat, entry.getJSONObject("flat_photo"), "$name.flat.photo.png", report)
            } finally {
                img.release(); flat.release()
            }
        }
        println(report)
        assertTrue("no flat_photo fixtures", n >= 7)
    }

    @Test
    fun theSharpestFrameIsTheOneTheServerPicks() {
        val all = names()
        val mats = all.map { OpenCvTest.readBgr("$it.png") }
        try {
            val i = ScanPhoto.sharpestIndex(mats)
            assertEquals(expected.getString("sharpest_scene"), all[i])
            // The multi-frame fallback: the photo comes from that frame.
            val r = ScanPhoto.of(mats)
            try {
                assertEquals(i, r.frame)
                assertEquals(scenes.getJSONObject(all[i]).getJSONObject("photo").getBoolean("detected"), r.detected)
            } finally {
                r.photo?.release()
            }
        } finally {
            mats.forEach { it.release() }
        }
    }

    @Test
    fun noEdges_keepsTheFrame() {
        val img = OpenCvTest.readBgr("empty_tray.png")
        try {
            val r = ScanPhoto.of(listOf(img))
            assertNull(r.photo)
            assertEquals(false, r.detected)
            assertEquals(0, r.frame)
        } finally {
            img.release()
        }
    }

    @Test
    fun jpegIsTheReferencesQuality_andDecodesToThePhoto() {
        val img = OpenCvTest.readBgr("white_tray_black_border.png")
        val r = ScanPhoto.of(listOf(img))
        try {
            assertNotNull(r.photo)
            val photo = r.photo!!
            val bytes = ScanPhoto.jpeg(photo)
            assertTrue(bytes.size > 10_000)
            assertEquals(0xFF, bytes[0].toInt() and 0xFF); assertEquals(0xD8, bytes[1].toInt() and 0xFF)
            val back = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
            try {
                assertEquals(ScanPhoto.PHOTO_W, back.cols())
                assertEquals(ScanPhoto.PHOTO_H, back.rows())
                assertTrue(CardQuadParityTest.meanAbsDiff(back, photo) < MAD_TOL)
            } finally {
                back.release()
            }
        } finally {
            r.photo?.release(); img.release()
        }
    }
}
