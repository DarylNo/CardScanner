package io.github.darylno.cardscanner.core

import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.util.Random

/**
 * JVM timing of the identification hot path — printed, never asserted (CI
 * machines vary; the phone number is measured on the device in Stage 2).
 *
 *  - hashing one 630×880 flattened card: half-size + L, core grid, dense grid
 *  - scoring a full-size index (49,000 rows) on the core grid, then the
 *    400-row shortlist on the dense grid — i.e. ArtMatcher.identify's cost
 *    once the crops are hashed
 */
class HashBenchmarkTest {
    private fun timeMs(reps: Int, block: () -> Unit): Double {
        val t0 = System.nanoTime()
        repeat(reps) { block() }
        return (System.nanoTime() - t0) / 1e6 / reps
    }

    @Test fun benchmark() {
        OpenCvTest.load()
        val card = Imgcodecs.imread(HashParityTest.file("photo/mh2_186.png").path, Imgcodecs.IMREAD_COLOR)
        check(card.cols() == 630 && card.rows() == 880)

        repeat(3) { ArtHasher.variants(ArtHasher.halfSizeGray(card), ArtHasher.CORE_PARAMS) }  // warm-up / JIT
        val half = ArtHasher.halfSizeGray(card)
        val tHalf = timeMs(20) { ArtHasher.halfSizeGray(card) }
        var core: List<ArtHash> = emptyList()
        val tCore = timeMs(10) { core = ArtHasher.variants(half, ArtHasher.CORE_PARAMS) }
        var dense: List<ArtHash> = emptyList()
        val tDense = timeMs(5) { dense = ArtHasher.variants(half, ArtHasher.DENSE_PARAMS) }

        val n = 49_000
        val rnd = Random(1)
        val h64 = LongArray(n) { rnd.nextLong() }
        val h256 = LongArray(n * 4) { rnd.nextLong() }
        val meta = List(n) { ArtMatcher.Entry("id$it", "Card ${it % 20000}", "set", "$it", "") }
        val m = ArtMatcher(h64, h256, meta)
        repeat(3) { m.identify(core, dense, 5) }
        val tCoarse = timeMs(10) { m.score(core) }
        val shortlist = IntArray(ArtThresholds.SHORTLIST) { it * 97 }
        val tShort = timeMs(20) { m.score(dense, shortlist) }
        val tIdentify = timeMs(10) { m.identify(core, dense, 5) }

        println("── art-hash benchmark (JVM ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cpus, single thread)")
        println(String.format("  half-size + L of 630×880 card        %8.2f ms", tHalf))
        println(String.format("  core grid hashing (%3d crops × 2 pHash) %6.2f ms", core.size, tCore))
        println(String.format("  dense grid hashing (%3d crops × 2 pHash) %5.2f ms", dense.size, tDense))
        println(String.format("  score %,d rows × %d core crops      %8.2f ms", n, core.size, tCoarse))
        println(String.format("  score %d shortlist × %d dense crops     %8.2f ms", shortlist.size, dense.size, tShort))
        println(String.format("  identify (both scores + sort + dedupe) %7.2f ms", tIdentify))
        println(String.format("  ⇒ card → top-5 ≈ %.0f ms", tHalf + tCore + tDense + tIdentify))
        card.release()
    }
}
