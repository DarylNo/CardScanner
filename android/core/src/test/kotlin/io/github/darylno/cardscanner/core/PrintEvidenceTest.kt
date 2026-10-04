package io.github.darylno.cardscanner.core

import io.github.darylno.cardscanner.core.SyntheticTray.Border
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * MEASURES the card-shape gate's print-evidence fallback ([PrintEvidence]) on
 * synthetic detection samples ([SyntheticTray], 176×235 — a portrait Area) and
 * holds its thresholds: with the outline forced null, every card must still be
 * accepted and every thing that is not a card refused.
 *
 * Each scene runs through the REAL [AutoScanner]: learn the empty tray, then the
 * scene until the occupancy + stillness Trigger; the evidence is measured on the
 * Trigger's own box against the scanner's own learned gradient (the accessor the
 * app uses). A non-card the mask never calls occupied never triggers — the gate
 * is never asked, which is counted, not failed. The table is printed; it is what
 * the thresholds were chosen from (and the commit message quotes it).
 *
 * The "outline" column is CardQuad.find on the same sample (the live outline
 * before smoothing) — informational: it shows which classes the finder already
 * sees, i.e. where the fallback is only a backstop.
 */
class PrintEvidenceTest {
    companion object {
        @BeforeClass @JvmStatic fun load() = OpenCvTest.load()

        const val W = 176
        const val H = 235
        /** The alternatives the thresholds were chosen from: core inset × "textured now" gradient. */
        val VARIANTS: List<Pair<Double, Int>> = listOf(0.0, 0.15, 0.25, 0.30).flatMap { i ->
            listOf(DetectConst.GRAD_THR, PrintEvidence.PRINT_GRAD, 28).map { i to it } }
        val CHOSEN = PrintEvidence.CORE_INSET to PrintEvidence.PRINT_GRAD
    }

    class Scene(val isCard: Boolean, val cls: String, val tray: String, val detail: String, val empty: Gray, val scene: Gray)

    class Result(val s: Scene, val box: Box?, val ev: Map<Pair<Double, Int>, PrintEvidence.Evidence>, val outline: Boolean) {
        val triggered get() = box != null
        val evidence get() = ev.getValue(CHOSEN)
        val accepted get() = triggered && evidence.looksLikeACard
        val name get() = "${s.cls} ${s.tray} ${s.detail}"
    }

    private val trays: Map<String, (Double, Double) -> Double> = linkedMapOf(
        "light 190" to SyntheticTray.flat(190.0),
        "light 215" to SyntheticTray.flat(215.0),
        "white 240" to SyntheticTray.flat(240.0),
        "dark 40" to SyntheticTray.flat(40.0),
        "grain 215" to SyntheticTray.grain(W, H, 215.0, 5.0, 77),     // a playmat / wood: texture right at GRAD_THR
    )

    private fun skin(tray: String) = if (tray.startsWith("dark")) 150.0 else 160.0

    private var seq = 0L

    private fun scene(isCard: Boolean, cls: String, tray: String, detail: String, sceneTray: (Double, Double) -> Double,
                      card: SyntheticTray.Card? = null, blur: Double = 0.0, noise: Double = 1.0): Scene {
        val n = ++seq
        val empty = SyntheticTray.render(W, H, trays.getValue(tray), noise = noise, seed = 1000 + n)
        val s = SyntheticTray.render(W, H, sceneTray, card, blur = blur, noise = noise, seed = 5000 + n)
        return Scene(isCard, cls, tray, detail, empty, s)
    }

    private fun cards(): List<Scene> {
        val out = ArrayList<Scene>()
        var face = 0L
        for (border in Border.values()) for ((tray, t) in trays) for (sleeve in listOf(false, true))
            for (blur in listOf(0.0, 1.0, 2.0)) for (size in listOf(1.0, 0.85, 0.6, 0.4)) for (angle in listOf(2.0, 10.0, 88.0)) {
                // 1.0 = the card FILLS the Area (an Area drawn tight on it): upright only — the outline finder gives up there.
                if (size == 1.0 && angle != 2.0) continue
                val sideways = angle > 45
                val ch = size * (if (sideways) W else H)
                val c = SyntheticTray.Card(SyntheticTray.cardFace(11 + (face++ % 7), border), W / 2.0 + 3, H / 2.0 - 4, ch, angle, sleeve)
                out += scene(true, border.label, tray, "${if (sleeve) "sleeved" else "bare"} blur $blur size $size angle $angle", t, c, blur)
            }
        return out
    }

    private fun nonCards(): List<Scene> {
        val out = ArrayList<Scene>()
        val cx = W / 2.0 + 3; val cy = H / 2.0 - 4
        val sizes = listOf(6.0 to 8.0, 12.0 to 16.0, 20.0 to 28.0, 30.0 to 22.0)
        for ((tray, t) in trays) {
            out += scene(false, "bare tray", tray, "noise only", t)
            for (amp in listOf(40.0, 60.0, 80.0)) for ((sx, sy) in sizes)
                out += scene(false, "glare", tray, "+$amp σ $sx×$sy", SyntheticTray.blob(t, amp, cx, cy, sx, sy))
            for (amp in listOf(-30.0, -45.0, -60.0)) for ((sx, sy) in sizes)
                out += scene(false, "shadow", tray, "$amp σ $sx×$sy", SyntheticTray.blob(t, amp, cx, cy, sx, sy))
            for ((g, o) in listOf(1.0 to 35.0, 1.0 to -50.0, 1.25 to 0.0, 0.75 to 0.0, 1.15 to 10.0, 0.6 to 0.0))
                out += scene(false, "exposure", tray, "×$g ${if (o >= 0) "+" else ""}$o", SyntheticTray.exposure(t, g, o))
            for (blur in listOf(1.0, 2.5)) {
                out += scene(false, "hand", tray, "fist 80×110 blur $blur", SyntheticTray.hand(t, skin(tray), cx, cy, 40.0, 55.0), blur = blur)
                out += scene(false, "hand", tray, "back 110×150 blur $blur", SyntheticTray.hand(t, skin(tray), cx, cy, 55.0, 75.0), blur = blur)
                out += scene(false, "hand", tray, "hand+arm from below blur $blur",
                    SyntheticTray.hand(t, skin(tray), cx, H * 0.62, 45.0, 50.0, armW = 60.0), blur = blur)
            }
            // A noisier sensor (σ 2.5 levels at sample resolution — a box average of ~6×6 frame px
            // leaves far less): noise alone crosses GRAD_THR in places, so the mask calls the tray occupied.
            out += scene(false, "noisy σ2.5", tray, "bare", t, noise = 2.5)
            out += scene(false, "noisy σ2.5", tray, "×0.75", SyntheticTray.exposure(t, 0.75, 0.0), noise = 2.5)
            out += scene(false, "noisy σ2.5", tray, "glare +60 σ 12×16", SyntheticTray.blob(t, 60.0, cx, cy, 12.0, 16.0), noise = 2.5)
        }
        return out
    }

    private fun run(s: Scene): Result {
        val sc = AutoScanner()
        repeat(5) { sc.tick(s.empty) }
        check(sc.hasEmptyRef) { "the empty tray was not learned" }
        val eg = sc.emptyGradient()!!
        var box: Box? = null
        repeat(4) { val e = sc.tick(s.scene); if (box == null && e is AutoScanner.Event.Trigger) box = e.box }
        val b = box
        val ev = if (b == null) emptyMap() else VARIANTS.associateWith { (i, g) -> PrintEvidence.measure(s.scene, eg, b, i, g) }
        val outline = runCatching { CardOutline.findSampleQuad(s.scene) != null }.getOrDefault(false)
        return Result(s, b, ev, outline)
    }

    private fun pct(v: Double) = "%.0f%%".format(v * 100)

    private fun table(results: List<Result>) {
        fun spread(v: List<Double>): String {
            if (v.isEmpty()) return "—"
            val s = v.sorted()
            return "${pct(s.first())} / ${pct(s[s.size / 2])} / ${pct(s.last())}"
        }
        val cards = results.filter { it.s.isCard }
        val non = results.filter { !it.s.isCard }
        println("\n=== CARDS — the outline forced null: the fallback alone (core inset ${PrintEvidence.CORE_INSET}, textured now > ${PrintEvidence.PRINT_GRAD}) ===")
        println("%-18s %-10s %4s %8s %8s  %-20s %-11s %-10s".format("border", "tray", "n", "accept", "outline", "print min/med/max", "box aspect", "share"))
        for ((key, rs) in cards.groupBy { it.s.cls to it.s.tray }) {
            val t = rs.filter { it.triggered }
            println("%-18s %-10s %4d %8s %8s  %-20s %-11s %-10s".format(key.first, key.second, rs.size,
                "${rs.count { it.accepted }}/${rs.size}", "${rs.count { it.outline }}/${rs.size}",
                spread(t.map { it.evidence.print }),
                if (t.isEmpty()) "—" else "%.2f–%.2f".format(t.minOf { it.evidence.aspect }, t.maxOf { it.evidence.aspect }),
                if (t.isEmpty()) "—" else "${pct(t.minOf { it.evidence.share })}–${pct(t.maxOf { it.evidence.share })}"))
        }
        for ((label, f) in listOf<Pair<String, (Scene) -> String>>(
            "blur" to { it.detail.substringAfter("blur ").substringBefore(" ") },
            "size" to { it.detail.substringAfter("size ").substringBefore(" ") },
            "angle" to { it.detail.substringAfter("angle ") },
            "sleeve" to { it.detail.substringBefore(" ") },
        )) {
            println("  by $label: " + cards.groupBy { f(it.s) }.entries.joinToString("  ·  ") { (k, rs) ->
                val t = rs.filter { it.triggered }
                "$k: accept ${rs.count { it.accepted }}/${rs.size}, print min ${if (t.isEmpty()) "—" else pct(t.minOf { it.evidence.print })}, outline ${rs.count { it.outline }}/${rs.size}"
            })
        }
        println("\n=== NOT CARDS — accept must be 0 (trig = the occupancy trigger fired, so the gate is asked) ===")
        println("%-10s %-10s %4s %5s %7s  %-10s %-11s %-10s %8s".format("event", "tray", "n", "trig", "accept", "print max", "box aspect", "share", "outline"))
        for ((key, rs) in non.groupBy { it.s.cls to it.s.tray }) {
            val t = rs.filter { it.triggered }
            println("%-10s %-10s %4d %5d %7s  %-10s %-11s %-10s %8s".format(key.first, key.second, rs.size, t.size,
                "${rs.count { it.accepted }}/${t.size}",
                if (t.isEmpty()) "—" else pct(t.maxOf { it.evidence.print }),
                if (t.isEmpty()) "—" else "%.2f–%.2f".format(t.minOf { it.evidence.aspect }, t.maxOf { it.evidence.aspect }),
                if (t.isEmpty()) "—" else "${pct(t.minOf { it.evidence.share })}–${pct(t.maxOf { it.evidence.share })}",
                "${rs.count { it.outline }}/${rs.size}"))
        }
        // How the alternatives separate: the lowest card against the highest non-card that passes shape + size.
        val shaped = non.filter { it.triggered && it.evidence.cardShaped && it.evidence.bigEnough }
        println("\n=== THE ALTERNATIVES — card minimum vs the highest card-shaped, big-enough non-card (${shaped.size} of them) ===")
        for (v in VARIANTS) {
            val c = cards.filter { it.triggered }.minBy { it.ev.getValue(v).print }
            val n = shaped.maxByOrNull { it.ev.getValue(v).print }
            println("  core inset %.2f, textured > %2d: card min %4s · non-card max %4s%s   (%s | %s)".format(v.first, v.second,
                pct(c.ev.getValue(v).print), n?.let { pct(it.ev.getValue(v).print) } ?: "—", if (v == CHOSEN) "  ← chosen" else "",
                c.name, n?.name ?: "—"))
        }
        val ct = cards.filter { it.triggered }
        println("\nTOTAL: cards ${cards.count { it.accepted }}/${cards.size} accepted (outline found ${cards.count { it.outline }}/${cards.size}); " +
            "not cards ${non.count { it.accepted }}/${non.count { it.triggered }} triggered accepted (${non.size} scenes). " +
            "Card print ≥ ${pct(ct.minOf { it.evidence.print })}, box ${"%.2f".format(ct.minOf { it.evidence.aspect })}–${"%.2f".format(ct.maxOf { it.evidence.aspect })}, " +
            "share ≥ ${pct(ct.minOf { it.evidence.share })}; non-card print ≤ ${pct(non.filter { it.triggered }.maxOf { it.evidence.print })}")
        for (r in cards.filter { !it.accepted }.take(25)) println("  CARD REFUSED: ${r.name} " + (if (r.triggered) r.evidence.describe() else "(never triggered)"))
        for (r in non.filter { it.accepted }) println("  NON-CARD ACCEPTED: ${r.name} ${r.evidence.describe()}")
    }

    @Test fun cardsAreAcceptedWithoutAnOutlineAndNothingElseIs() {
        val results = (cards() + nonCards()).map(::run)
        table(results)
        val cards = results.filter { it.s.isCard }
        val non = results.filter { !it.s.isCard }
        assertTrue("every card triggers", cards.all { it.triggered })
        val refusedCards = cards.filter { !it.accepted }.map { "${it.name}: ${it.evidence.describe()}" }
        assertTrue("every card accepted on print evidence:\n" + refusedCards.joinToString("\n"), refusedCards.isEmpty())
        val acceptedNon = non.filter { it.accepted }.map { "${it.name}: ${it.evidence.describe()}" }
        assertTrue("nothing that is not a card accepted:\n" + acceptedNon.joinToString("\n"), acceptedNon.isEmpty())
        assertTrue("the non-card set really asks the gate (it triggers)", non.count { it.triggered } >= 80)
        // Margins, not just a pass: the thresholds sit well inside the measured gap.
        assertTrue(cards.minOf { it.evidence.print } >= 2 * PrintEvidence.MIN_PRINT)
        assertTrue(non.filter { it.triggered }.maxOf { it.evidence.print } <= PrintEvidence.MIN_PRINT / 2)
    }

    // ── the function itself ──

    @Test fun aFlatPatchHasNoPrintAndATexturedOneDoes() {
        val w = 40; val h = 40
        val empty = Gray(w, h, IntArray(w * h) { 100 })
        val eg = Detection.gradMap(empty)
        val flat = Gray(w, h, IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 10..29 && y in 6..33) 230 else 100 })
        val box = Box(10, 6, 20, 28, 0.3)
        val e = PrintEvidence.measure(flat, eg, box)
        assertEquals("a flat bright patch's edges sit outside the core", 0.0, e.print, 1e-9)
        assertEquals(1.4, e.aspect, 1e-9)
        assertEquals(20.0 * 28 / (40 * 40), e.share, 1e-9)
        assertFalse(e.looksLikeACard)
        val printed = Gray(w, h, IntArray(w * h) { i -> val x = i % w; val y = i / w
            if (x in 10..29 && y in 6..33) (if ((x / 2 + y / 2) % 2 == 0) 230 else 120) else 100 })
        val p = PrintEvidence.measure(printed, eg, box)
        assertTrue(p.describe(), p.print > 0.9 && p.looksLikeACard)
        assertTrue(p.describe(), p.describe().startsWith("print ") && p.describe().contains("of the Area"))
    }

    @Test fun textureTheEmptyTrayAlreadyHadIsNotPrint() {
        val w = 40; val h = 40
        val checker = IntArray(w * h) { i -> val x = i % w; val y = i / w; if ((x / 2 + y / 2) % 2 == 0) 230 else 120 }
        val empty = Gray(w, h, checker)
        val p = PrintEvidence.measure(Gray(w, h, checker.copyOf()), Detection.gradMap(empty), Box(10, 6, 20, 28, 0.3))
        assertEquals(0.0, p.print, 1e-9)
    }

    @Test fun theBoxShapeAndSizeRulesHoldInEitherOrientation() {
        val g = Gray(100, 100, IntArray(10000) { 100 })
        val eg = Detection.gradMap(g)
        assertEquals(1.4, PrintEvidence.measure(g, eg, Box(10, 10, 28, 20, 0.1)).aspect, 1e-9)   // sideways
        assertFalse(PrintEvidence.measure(g, eg, Box(10, 10, 20, 20, 0.1)).cardShaped)          // square
        assertFalse(PrintEvidence.measure(g, eg, Box(10, 10, 10, 30, 0.1)).cardShaped)          // a pen
        assertFalse(PrintEvidence.measure(g, eg, Box(10, 10, 10, 14, 0.1)).bigEnough)           // 1.4 % of the Area
        // A box touching the sample's edge never reads outside it.
        PrintEvidence.measure(g, eg, Box(0, 0, 100, 100, 1.0))
    }

    /** The accessor is a COPY of the learned gradient — nothing outside can change what the scanner compares against. */
    @Test fun theEmptyGradientAccessorIsAReadOnlyCopy() {
        val sc = AutoScanner()
        assertNull(sc.emptyGradient())
        val tray = SyntheticTray.render(W, H, SyntheticTray.flat(200.0), seed = 3)
        repeat(5) { sc.tick(tray) }
        val a = sc.emptyGradient()!!
        assertArrayEquals(Detection.gradMap(tray), a)
        a.fill(255)
        assertArrayEquals(Detection.gradMap(tray), sc.emptyGradient()!!)
    }
}
