package io.github.darylno.cardscanner.core

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** One jittered art crop: shift down [dy], right [dx], [inset] (negative = outset), fractions of the card. */
data class CropParam(val dy: Double, val dx: Double, val inset: Double)

/** The two pHashes of one crop — 64-bit coarse + 256-bit fine as 4 big-endian words — and its pixel box. */
class ArtHash(val h64: Long, val h256: LongArray, val box: IntArray) {
    init { require(h256.size == 4 && box.size == 4) }
}

/**
 * Scan-side and index-side art fingerprints — port of `art_index.ArtIndex`
 * `_card_pil` / `_hash_variants` and `visual_match._crop_region` /
 * `crop_art_region`. Bit-exact with the server (HashParityTest).
 *
 * Scan side: the flattened card (the server's 630×880 warp) is halved with
 * INTER_AREA ([halfSizeGray]), converted to Pillow "L", and every crop of the
 * jitter grid that stays inside the card is hashed ([variants]).
 * Index side: an artwork's Scryfall `small` image is hashed at full size on
 * the plain art crop ([indexHash]).
 */
object ArtHasher {
    // visual_match._ART_Y0/_ART_Y1/_ART_X0/_ART_X1
    const val ART_Y0 = 0.09
    const val ART_Y1 = 0.47
    const val ART_X0 = 0.03
    const val ART_X1 = 0.97

    /** art_index._HASH_SCALE — crops come from a half-size warp. */
    const val HASH_SCALE = 0.5

    val JITTER_SHIFTS = doubleArrayOf(-0.06, -0.03, 0.0, 0.03, 0.06)
    val JITTER_INSETS = doubleArrayOf(-0.06, 0.0, 0.06, 0.12)
    val DENSE_SHIFTS = doubleArrayOf(-0.06, -0.04, -0.02, 0.0, 0.02, 0.04, 0.06)
    val DENSE_INSETS = doubleArrayOf(-0.10, -0.07, -0.04, -0.01, 0.02, 0.05, 0.08, 0.12)

    /** `_variant_params`: dy outermost, then dx, then inset — the order is part of the contract. */
    fun variantParams(shifts: DoubleArray, insets: DoubleArray): List<CropParam> =
        shifts.flatMap { dy -> shifts.flatMap { dx -> insets.map { ins -> CropParam(dy, dx, ins) } } }

    /**
     * art_index._CORE_PARAMS — NB: built from the full _JITTER_* grid (5×5×4 = 100),
     * not the unused _CORE_SHIFTS/_CORE_INSETS; mirrored as the server runs it.
     */
    val CORE_PARAMS: List<CropParam> = variantParams(JITTER_SHIFTS, JITTER_INSETS)

    /** art_index._DENSE_PARAMS (7×7×8 = 392), used on the shortlist only. */
    val DENSE_PARAMS: List<CropParam> = variantParams(DENSE_SHIFTS, DENSE_INSETS)

    /** `_crop_region`'s box: `(int(w*x0), int(h*y0), int(w*x1), int(h*y1))` — truncation, as Python's int(). */
    fun cropBox(w: Int, h: Int, y0: Double, y1: Double, x0: Double, x1: Double): IntArray =
        intArrayOf((w * x0).toInt(), (h * y0).toInt(), (w * x1).toInt(), (h * y1).toInt())

    fun hashCrop(crop: GrayImage, box: IntArray): ArtHash =
        ArtHash(PHash.phash64(crop), PHash.phash256(crop), box)

    /** Index side: `crop_art_region(img)` → phash / phash(hash_size=16) (ArtIndexBuilder.build). */
    fun indexHash(image: GrayImage): ArtHash {
        val box = cropBox(image.width, image.height, ART_Y0, ART_Y1, ART_X0, ART_X1)
        return hashCrop(image.crop(box[0], box[1], box[2], box[3]), box)
    }

    /**
     * `_hash_variants(card, params)`: one [ArtHash] per crop of the grid that lies
     * inside the card, in grid order (out-of-bounds crops are skipped, not clamped).
     */
    fun variants(card: GrayImage, params: List<CropParam> = CORE_PARAMS): List<ArtHash> {
        val artH = ART_Y1 - ART_Y0
        val artW = ART_X1 - ART_X0
        val out = ArrayList<ArtHash>(params.size)
        for (p in params) {
            // Same expression shape as Python: (Y0 + dy) + ((inset * art_h) / 2)
            val y0 = ART_Y0 + p.dy + p.inset * artH / 2
            val y1 = ART_Y1 + p.dy - p.inset * artH / 2
            val x0 = ART_X0 + p.dx + p.inset * artW / 2
            val x1 = ART_X1 + p.dx - p.inset * artW / 2
            if (y0 < 0 || x0 < 0 || y1 > 1 || x1 > 1) continue
            val box = cropBox(card.width, card.height, y0, y1, x0, x1)
            out.add(hashCrop(card.crop(box[0], box[1], box[2], box[3]), box))
        }
        return out
    }

    /** A continuous 8UC3 BGR Mat → Pillow "L". */
    fun grayFromBgrMat(bgr: Mat): GrayImage {
        require(bgr.type() == CvType.CV_8UC3) { "expected 8UC3 BGR, got ${CvType.typeToString(bgr.type())}" }
        val m = if (bgr.isContinuous) bgr else bgr.clone()
        val buf = ByteArray(m.cols() * m.rows() * 3)
        m.get(0, 0, buf)
        return PHash.grayFromBgr(buf, m.cols(), m.rows())
    }

    /**
     * `_card_pil`: the warped card halved with `cv2.resize(..., INTER_AREA)` to
     * `(int(w*0.5), int(h*0.5))`, then Pillow "L" (the crop is taken after the
     * luma conversion here — equivalent, as both are per-pixel).
     */
    fun halfSizeGray(cardBgr: Mat): GrayImage {
        val w = (cardBgr.cols() * HASH_SCALE).toInt()
        val h = (cardBgr.rows() * HASH_SCALE).toInt()
        val half = Mat()
        try {
            Imgproc.resize(cardBgr, half, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            return grayFromBgrMat(half)
        } finally {
            half.release()
        }
    }
}
