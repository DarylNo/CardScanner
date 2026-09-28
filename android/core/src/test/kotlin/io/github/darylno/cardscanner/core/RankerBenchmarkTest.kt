package io.github.darylno.cardscanner.core

import org.junit.Test
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * JVM timing of ranking 120 candidate printings (the `_cap_candidates` cap) —
 * printed, never asserted. Images are decoded up front (the source hands out
 * clones), so this is the ranker's own cost:
 *
 *  - cold: every candidate's four region pHashes computed (first scan of a name)
 *  - warm: region hashes memoized (a repeat scan of the same name) — only the
 *    scan's own hashes + 120 × 4 popcounts + sort
 *  - best_match on the warm cache
 */
class RankerBenchmarkTest {
    private fun timeMs(reps: Int, block: () -> Unit): Double {
        val t0 = System.nanoTime()
        repeat(reps) { block() }
        return (System.nanoTime() - t0) / 1e6 / reps
    }

    @Test fun benchmark() {
        OpenCvTest.load()
        val all = PrintingRankerParityTest.run {
            loadFixtures()
            listOf("Lightning Bolt", "Shivan Dragon", "Diabolic Edict").flatMap { printings.getValue(it) }
        }
        val input = VisualMatch.capCandidates(all)
        check(input.size == VisualMatch.MAX_CANDIDATES_PER_SCAN)
        val dir = PrintingRankerParityTest.file("images").path
        val decoded: Map<String, Mat> = input.associate { it.id to Imgcodecs.imread(File(dir, "${it.id}.jpg").path, Imgcodecs.IMREAD_COLOR) }
        val source = ImageSource { id, _ -> decoded.getValue(id).clone() }
        val scan = PrintingRankerParityTest.readScan("bolt_m10")

        repeat(3) { PrintingRanker(source).rankPrintings(scan, input) }   // warm-up / JIT
        val tClone = timeMs(10) { for (m in decoded.values) m.clone().release() }
        val tCold = timeMs(10) { PrintingRanker(source).rankPrintings(scan, input) }
        val warm = PrintingRanker(source).also { it.rankPrintings(scan, input) }
        val tWarm = timeMs(50) { warm.rankPrintings(scan, input) }
        val tBest = timeMs(50) { warm.bestMatch(scan, input) }

        println("── ranker benchmark (JVM ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cpus, single thread)")
        println(String.format("  rank 120 printings, cold (4 region pHashes each) %8.2f ms  (of which %.2f ms cloning Mats)", tCold, tClone))
        println(String.format("  rank 120 printings, warm (hashes memoized)       %8.2f ms", tWarm))
        println(String.format("  best_match, warm                                 %8.2f ms", tBest))
        scan.release()
        decoded.values.forEach { it.release() }
    }
}
