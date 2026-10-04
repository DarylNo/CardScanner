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
 * holds its thresholds: with the outline forced null, every card on a smooth
 * tray must still be accepted and every thing that is not a card refused —
 * including an empty TEXTURED tray read against a STALE reference (the review
 * of 1.1.11: learned with a card on it and the card lifted, learned out of
 * focus, a light under AE lock, a nudged mat), where the old three tests let
 * the tray's own texture through as "print".
 *
 * Each scene runs through the REAL [AutoScanner]: learn the empty tray (for a
 * stale scene: the tray as it WAS), then the scene until the occupancy +
 * stillness Trigger; the evidence is measured on the Trigger's own box against
 * the scanner's own learned gradient (the accessor the app uses). A non-card
 * the mask never calls occupied never triggers — the gate is never asked, which
 * is counted, not failed. The tables are printed; they are what the thresholds
 * were chosen from (and the commit messages quote them).
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
    /** The fallback is for smooth trays; on a textured one it stands aside (a stale reference reads as print there). */
    private fun smoothTray(tray: String) = !tray.startsWith("grain")

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

    private fun shifted(t: (Double, Double) -> Double, dx: Double, dy: Double): (Double, Double) -> Double = { x, y -> t(x + dx, y + dy) }

    /** A playmat: blocks of random grey ([block] sample px) round [level] ± [amp]. */
    private fun playmat(level: Double, amp: Double, seed: Long, block: Double): (Double, Double) -> Double {
        val rnd = java.util.Random(seed)
        val n = 64
        val v = DoubleArray(n * n) { level + amp * (rnd.nextDouble() * 2 - 1) }
        return { x, y -> v[(y / block).toInt().coerceIn(0, n - 1) * n + (x / block).toInt().coerceIn(0, n - 1)] }
    }

    /** A smooth card-sized thing (a face-down sleeve, a blank) of [level] over [base], [sw]×[sh] centred. */
    private fun smoothThing(base: (Double, Double) -> Double, level: Double, sw: Double, sh: Double): (Double, Double) -> Double {
        val x0 = W / 2.0 - sw / 2; val y0 = H / 2.0 - sh / 2
        return { x, y -> if (x >= x0 && x < x0 + sw && y >= y0 && y < y0 + sh) level else base(x, y) }
    }

    /**
     * An empty tray read against a STALE reference ([learn] is what the scanner
     * learned, [now] the empty tray as it is) — the review of 1.1.11, at sensor
     * noise σ 0.5 and 1. Nothing here is a card.
     */
    private fun staleNonCards(): List<Scene> {
        val out = ArrayList<Scene>()
        fun add(cls: String, tray: String, detail: String, learn: (Double, Double) -> Double, now: (Double, Double) -> Double,
                card: SyntheticTray.Card? = null, learnBlur: Double = 0.0) {
            for (noise in listOf(0.5, 1.0)) {
                val n = ++seq
                out += Scene(false, cls, tray, "$detail σ$noise",
                    SyntheticTray.render(W, H, learn, card, blur = learnBlur, noise = noise, seed = 1000 + n),
                    SyntheticTray.render(W, H, now, noise = noise, seed = 5000 + n))
            }
        }
        // AE locked, the room light brightens an UNCLIPPED textured tray (grain 215 clips at 255).
        for (lvl in listOf(80.0, 120.0)) for (amp in listOf(5.0, 8.0, 12.0)) for (g in listOf(1.3, 1.5, 1.6, 1.8)) {
            val t = SyntheticTray.grain(W, H, lvl, amp, 77)
            add("AE-lock light", "grain $lvl/$amp", "×$g", t, SyntheticTray.exposure(t, g, 0.0))
        }
        // Learned slightly out of focus, then the lens sharpened it (the focus passes).
        for (lvl in listOf(120.0, 170.0)) for (amp in listOf(5.0, 8.0, 12.0)) for (b in listOf(0.8, 1.2, 1.4, 1.6, 2.0)) {
            val t = SyntheticTray.grain(W, H, lvl, amp, 77)
            add("focus", "grain $lvl/$amp", "learned at blur $b, now sharp", t, t, learnBlur = b)
        }
        // The mat / the mount nudged.
        for (lvl in listOf(120.0, 160.0)) for (amp in listOf(5.0, 8.0, 12.0)) for (d in listOf(1.0, 2.0, 3.0)) {
            val t = SyntheticTray.grain(W, H, lvl, amp, 77)
            add("nudge", "grain $lvl/$amp", "shift $d px", t, shifted(t, d, d * 0.5))
        }
        for (amp in listOf(30.0, 60.0)) for (blk in listOf(4.0, 8.0)) for (d in listOf(1.0, 2.0, 4.0)) {
            val t = playmat(130.0, amp, 9, blk)
            add("nudge", "playmat $amp/$blk", "shift $d px", t, shifted(t, d, d * 0.7))
        }
        // Learned WITH a card on it (a double-tap, a new Area, a zoom change, a rebind), then the card lifted.
        var face = 0L
        for (lvl in listOf(120.0, 170.0, 215.0)) for (amp in listOf(5.0, 8.0, 12.0)) for (border in Border.values()) for (size in listOf(0.6, 0.85)) {
            val t = SyntheticTray.grain(W, H, lvl, amp, 77)
            val c = SyntheticTray.Card(SyntheticTray.cardFace(11 + (face++ % 7), border), W / 2.0 + 3, H / 2.0 - 4, size * H, 2.0, false)
            add("lifted card", "grain $lvl/$amp", "${border.label} size $size", t, t, card = c)
        }
        for (lvl in listOf(190.0, 215.0, 240.0, 40.0)) for (border in Border.values()) for (size in listOf(0.6, 0.85)) {
            val t = SyntheticTray.flat(lvl)
            val c = SyntheticTray.Card(SyntheticTray.cardFace(11 + (face++ % 7), border), W / 2.0 + 3, H / 2.0 - 4, size * H, 2.0, false)
            add("lifted card", "flat $lvl", "${border.label} size $size", t, t, card = c)
        }
        // …or a SMOOTH card-sized thing learned in and lifted off a mat: its core reads as all "smooth tray".
        for ((name, t) in listOf("playmat 30/4" to playmat(130.0, 30.0, 9, 4.0), "playmat 60/8" to playmat(130.0, 60.0, 9, 8.0),
                "grain 170/8" to SyntheticTray.grain(W, H, 170.0, 8.0, 77), "grain 170/12" to SyntheticTray.grain(W, H, 170.0, 12.0, 77)))
            for ((sw, sh) in listOf(56.0 to 78.0, 80.0 to 112.0, 110.0 to 154.0))
                add("lifted smooth thing", name, "${sw.toInt()}×${sh.toInt()}", smoothThing(t, 225.0, sw, sh), t)
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
    private fun pct1(v: Double?) = v?.let { "%.1f%%".format(it * 100) } ?: "—"

    /** What the fallback said before the review of 1.1.11 (print + shape + size): the hazard the stale scenes show. */
    private fun oldRule(e: PrintEvidence.Evidence) = e.printed && e.cardShaped && e.bigEnough

    private fun table(results: List<Result>) {
        fun spread(v: List<Double>): String {
            if (v.isEmpty()) return "—"
            val s = v.sorted()
            return "${pct(s.first())} / ${pct(s[s.size / 2])} / ${pct(s.last())}"
        }
        val cards = results.filter { it.s.isCard }
        val non = results.filter { !it.s.isCard }
        println("\n=== CARDS — the outline forced null: the fallback alone (core inset ${PrintEvidence.CORE_INSET}, textured now > ${PrintEvidence.PRINT_GRAD}) ===")
        println("%-18s %-10s %4s %8s %8s  %-20s %-11s %-9s %-9s %-9s".format("border", "tray", "n", "accept", "outline", "print min/med/max", "box aspect", "share", "tray tex", "ring max"))
        for ((key, rs) in cards.groupBy { it.s.cls to it.s.tray }) {
            val t = rs.filter { it.triggered }
            println("%-18s %-10s %4d %8s %8s  %-20s %-11s %-9s %-9s %-9s".format(key.first, key.second, rs.size,
                "${rs.count { it.accepted }}/${rs.size}", "${rs.count { it.outline }}/${rs.size}",
                spread(t.map { it.evidence.print }),
                if (t.isEmpty()) "—" else "%.2f–%.2f".format(t.minOf { it.evidence.aspect }, t.maxOf { it.evidence.aspect }),
                if (t.isEmpty()) "—" else "${pct(t.minOf { it.evidence.share })}–${pct(t.maxOf { it.evidence.share })}",
                if (t.isEmpty()) "—" else "≤ ${pct1(t.maxOf { it.evidence.trayTexture })}",
                pct1(t.mapNotNull { it.evidence.ring }.maxOrNull())))
        }
        for ((label, f) in listOf<Pair<String, (Scene) -> String>>(
            "blur" to { it.detail.substringAfter("blur ").substringBefore(" ") },
            "size" to { it.detail.substringAfter("size ").substringBefore(" ") },
            "angle" to { it.detail.substringAfter("angle ") },
            "sleeve" to { it.detail.substringBefore(" ") },
        )) {
            println("  by $label: " + cards.filter { smoothTray(it.s.tray) }.groupBy { f(it.s) }.entries.joinToString("  ·  ") { (k, rs) ->
                val t = rs.filter { it.triggered }
                "$k: accept ${rs.count { it.accepted }}/${rs.size}, print min ${if (t.isEmpty()) "—" else pct(t.minOf { it.evidence.print })}, outline ${rs.count { it.outline }}/${rs.size}"
            })
        }
        println("\n=== NOT CARDS — accept must be 0 (trig = the occupancy trigger fired, so the gate is asked; old = print + shape + size alone) ===")
        println("%-20s %-16s %4s %5s %5s %7s  %-10s %-11s %-10s %-11s %-11s %8s".format("event", "tray", "n", "trig", "old", "accept", "print max", "box aspect", "share", "tray tex", "ring", "outline"))
        for ((key, rs) in non.groupBy { it.s.cls to it.s.tray }) {
            val t = rs.filter { it.triggered }
            println("%-20s %-16s %4d %5d %5d %7s  %-10s %-11s %-10s %-11s %-11s %8s".format(key.first, key.second, rs.size, t.size,
                t.count { oldRule(it.evidence) }, "${rs.count { it.accepted }}/${t.size}",
                if (t.isEmpty()) "—" else pct(t.maxOf { it.evidence.print }),
                if (t.isEmpty()) "—" else "%.2f–%.2f".format(t.minOf { it.evidence.aspect }, t.maxOf { it.evidence.aspect }),
                if (t.isEmpty()) "—" else "${pct(t.minOf { it.evidence.share })}–${pct(t.maxOf { it.evidence.share })}",
                if (t.isEmpty()) "—" else "${pct(t.minOf { it.evidence.trayTexture })}–${pct(t.maxOf { it.evidence.trayTexture })}",
                t.mapNotNull { it.evidence.ring }.let { r -> if (r.isEmpty()) "none" else "${pct(r.min())}–${pct(r.max())}" },
                "${rs.count { it.outline }}/${rs.size}"))
        }
        for (r in cards.filter { smoothTray(it.s.tray) && !it.accepted }.take(25)) println("  CARD REFUSED: ${r.name} " + (if (r.triggered) r.evidence.describe() else "(never triggered)"))
        for (r in non.filter { it.accepted }) println("  NON-CARD ACCEPTED: ${r.name} ${r.evidence.describe()}")
    }

    /** The alternatives for the original three tests: the lowest card against the highest card-shaped, big-enough non-card. */
    private fun alternatives(cards: List<Result>, non: List<Result>) {
        val shaped = non.filter { it.triggered && it.evidence.cardShaped && it.evidence.bigEnough }
        println("\n=== THE ALTERNATIVES — card minimum vs the highest card-shaped, big-enough non-card (${shaped.size} of them, fresh references) ===")
        for (v in VARIANTS) {
            val c = cards.filter { it.triggered }.minBy { it.ev.getValue(v).print }
            val n = shaped.maxByOrNull { it.ev.getValue(v).print }
            println("  core inset %.2f, textured > %2d: card min %4s · non-card max %4s%s   (%s | %s)".format(v.first, v.second,
                pct(c.ev.getValue(v).print), n?.let { pct(it.ev.getValue(v).print) } ?: "—", if (v == CHOSEN) "  ← chosen" else "",
                c.name, n?.name ?: "—"))
        }
    }

    @Test fun cardsOnASmoothTrayAreAcceptedWithoutAnOutlineAndNothingElseIs() {
        val results = (cards() + nonCards()).map(::run)
        table(results)
        val cards = results.filter { it.s.isCard }
        val non = results.filter { !it.s.isCard }
        alternatives(cards, non)
        val smooth = cards.filter { smoothTray(it.s.tray) }
        val textured = cards.filter { !smoothTray(it.s.tray) }
        println("\nTOTAL: smooth-tray cards ${smooth.count { it.accepted }}/${smooth.size} accepted (outline found ${smooth.count { it.outline }}); " +
            "textured-tray cards ${textured.count { it.accepted }}/${textured.size} accepted — the fallback stands aside there, the outline found " +
            "${textured.count { it.outline }}/${textured.size}; not cards ${non.count { it.accepted }}/${non.count { it.triggered }} triggered accepted (${non.size} scenes). " +
            "Smooth-tray card print ≥ ${pct(smooth.minOf { it.evidence.print })}, tray texture ≤ ${pct1(smooth.maxOf { it.evidence.trayTexture })}, " +
            "print ÷ ring ≥ ${"%.1f".format(smooth.filter { (it.evidence.ring ?: 0.0) > 0 }.minOf { it.evidence.print / it.evidence.ring!! })}, " +
            "box ${"%.2f".format(smooth.minOf { it.evidence.aspect })}–${"%.2f".format(smooth.maxOf { it.evidence.aspect })}, share ≥ ${pct(smooth.minOf { it.evidence.share })}; " +
            "non-card print ≤ ${pct(non.filter { it.triggered }.maxOf { it.evidence.print })}")
        assertTrue("every card triggers", cards.all { it.triggered })
        val refusedCards = smooth.filter { !it.accepted }.map { "${it.name}: ${it.evidence.describe()}" }
        assertTrue("every card on a smooth tray accepted on print evidence:\n" + refusedCards.joinToString("\n"), refusedCards.isEmpty())
        // On a textured tray the fallback stands aside — always for the learned tray's texture, never by chance.
        assertTrue("the fallback stands aside on the textured tray", textured.all { it.triggered && !it.evidence.trayClean && !it.accepted })
        val acceptedNon = non.filter { it.accepted }.map { "${it.name}: ${it.evidence.describe()}" }
        assertTrue("nothing that is not a card accepted:\n" + acceptedNon.joinToString("\n"), acceptedNon.isEmpty())
        assertTrue("the non-card set really asks the gate (it triggers)", non.count { it.triggered } >= 80)
        // Margins, not just a pass: the thresholds sit well inside the measured gap.
        assertTrue(smooth.minOf { it.evidence.print } >= 2 * PrintEvidence.MIN_PRINT)
        assertTrue(non.filter { it.triggered }.maxOf { it.evidence.print } <= PrintEvidence.MIN_PRINT / 2)
        assertTrue("a smooth tray reads smooth, well inside the limit", smooth.maxOf { it.evidence.trayTexture } <= PrintEvidence.MAX_TRAY_TEXTURE / 4)
        assertTrue("a card's print stands out of its ring with room to spare",
            smooth.all { it.evidence.ring == null || it.evidence.print >= 1.5 * PrintEvidence.RING_FACTOR * it.evidence.ring!! })
    }

    /**
     * THE REVIEW OF 1.1.11: an empty textured tray against a stale reference —
     * the card it was learned with lifted, a light under AE lock, a sharper focus,
     * a nudge — reads as "print" over a box that is the whole Area (always
     * card-shaped and big enough). None may be accepted; the table shows which
     * test caught each, and that the old three tests would have shot them.
     */
    @Test fun anEmptyTrayAgainstAStaleReferenceIsNeverACard() {
        val results = staleNonCards().map(::run)
        table(results)
        val trig = results.filter { it.triggered }
        val old = trig.filter { oldRule(it.evidence) }
        println("\nSTALE: ${results.size} scenes, ${trig.size} triggered; the old three tests accepted ${old.size} " +
            "(${old.groupBy { it.s.cls }.entries.joinToString { "${it.key} ${it.value.size}" }}); now accepted ${results.count { it.accepted }}. " +
            "Caught by the learned tray's texture ${old.count { !it.evidence.trayClean }} (the ring alone would also refuse ${old.count { !it.evidence.standsOut }}; " +
            "it alone caught ${old.count { it.evidence.trayClean && !it.evidence.standsOut }}); " +
            "the outline found ${results.count { it.outline }} of them (a false card the outline sees is shot with or without the fallback).")
        for (r in old) println("  OLD WOULD SHOOT: ${r.name} ${r.evidence.describe()}")
        val accepted = results.filter { it.accepted }.map { "${it.name}: ${it.evidence.describe()}" }
        assertTrue("an empty tray against a stale reference accepted:\n" + accepted.joinToString("\n"), accepted.isEmpty())
        // The scenes really are the hazard: the old tests would have shot plenty, in every class but the flat-tray lift.
        assertTrue("the stale set exercises the old rule (${old.size})", old.size >= 100)
        for (cls in listOf("AE-lock light", "focus", "nudge", "lifted card", "lifted smooth thing"))
            assertTrue("$cls: the old rule shot some", old.any { it.s.cls == cls })
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

    /**
     * A learned tray with faint texture (gradient 8–14: under the mask's "textured",
     * over [PrintEvidence.TRAY_GRAD]) and the same texture now a little stronger —
     * a light under AE lock: every pixel reads as new "print", the box is the whole
     * sample. The learned tray's texture stands the fallback aside.
     */
    @Test fun aTexturedLearnedTrayStandsTheFallbackAside() {
        val w = 60; val h = 80
        val faint = IntArray(w * h) { i -> val x = i % w; val y = i / w; if ((x / 2 + y / 2) % 2 == 0) 110 else 104 }   // gradient 12
        val brighter = IntArray(w * h) { faint[it] * 2 - 100 }                                                    // ×2 contrast: 24
        val eg = Detection.gradMap(Gray(w, h, faint))
        val e = PrintEvidence.measure(Gray(w, h, brighter), eg, Box(0, 0, w, h, 1.0))
        assertTrue(e.describe(), e.printed && e.cardShaped && e.bigEnough)          // the old three tests: a card
        assertTrue(e.describe(), e.trayTexture > 0.9 && !e.trayClean)
        assertNull("the box fills the sample: no ring", e.ring)
        assertFalse(e.looksLikeACard)
        assertTrue(e.describe(), e.describe().contains("tray texture") && e.describe().contains("no ring"))
    }

    /**
     * A smooth thing learned into a textured mat and lifted: the box's core was
     * smooth in the learned tray and is all "new" mat texture now — exactly as
     * textured as the mat round the box.
     */
    @Test fun printNoMoreTexturedThanRoundTheBoxIsNotACard() {
        val w = 100; val h = 120
        val mat = IntArray(w * h) { i -> val x = i % w; val y = i / w; if ((x / 3 + y / 3) % 2 == 0) 160 else 110 }
        val box = Box(30, 25, 40, 56, 0.2)
        val learned = IntArray(w * h) { i -> val x = i % w; val y = i / w
            if (x in box.x until box.x + box.w && y in box.y until box.y + box.h) 225 else mat[i] }
        val e = PrintEvidence.measure(Gray(w, h, mat), Detection.gradMap(Gray(w, h, learned)), box)
        assertTrue(e.describe(), e.printed && e.cardShaped && e.bigEnough)
        assertTrue(e.describe(), e.ring != null && e.ring!! > 0.5 && !e.standsOut)
        assertFalse(e.looksLikeACard)
        // The same print on a smooth tray stands out: a card.
        val flat = IntArray(w * h) { i -> val x = i % w; val y = i / w
            if (x in box.x until box.x + box.w && y in box.y until box.y + box.h) mat[i] else 100 }
        val c = PrintEvidence.measure(Gray(w, h, flat), Detection.gradMap(Gray(w, h, IntArray(w * h) { 100 })), box)
        assertEquals(c.describe(), 0.0, c.ring!!, 1e-9)
        assertEquals(0.0, c.trayTexture, 1e-9)
        assertTrue(c.describe(), c.looksLikeACard)
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
