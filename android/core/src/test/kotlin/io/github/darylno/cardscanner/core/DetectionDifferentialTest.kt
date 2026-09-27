package io.github.darylno.cardscanner.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Holds the Kotlin port to phone.html's REAL detection code: identical frame
 * sequences go through both (the page's code runs under Node via
 * phone_harness.js), and every step's state must match — mode, counters,
 * whether the empty tray is learned, triggers, next-card events and the
 * detected box. This is the proof behind "the tuned detection is unchanged"
 * (CLAUDE.md); it runs in CI, so the page and the app cannot drift apart.
 */
class DetectionDifferentialTest {
    private val mw = DetectConst.MW

    // ── scene synthesis (deterministic) ─────────────────────────────────────
    private class Scene(val h: Int, seed: Int) {
        val rnd = Random(seed)
        val tray = IntArray(176 * h) { i ->
            val x = i % 176; val y = i / 176
            // smooth tray with a darker rim, like the rig's photo tray
            (150 + (x * 30) / 176 + (y * 20) / h - if (x < 4 || x > 171 || y < 3 || y > h - 4) 60 else 0)
                .coerceIn(0, 255)
        }
        fun noise(v: Int, amp: Int) = (v + rnd.nextInt(-amp, amp + 1)).coerceIn(0, 255)
    }

    private fun empty(s: Scene, offset: Int = 0) =
        Gray(mw, s.h, IntArray(mw * s.h) { s.noise(s.tray[it] + offset, 2) })

    /** A card: printed-looking detail inside a dark border, at (x0,y0) size w×h. */
    private fun card(s: Scene, x0: Int, y0: Int, w: Int, h: Int, face: Int, offset: Int = 0): Gray {
        val px = IntArray(mw * s.h) { s.noise(s.tray[it] + offset, 2) }
        val faceRnd = Random(face)
        val blocks = IntArray(64) { faceRnd.nextInt(0, 256) }
        for (y in y0 until minOf(s.h, y0 + h)) for (x in x0 until minOf(mw, x0 + w)) {
            val border = x - x0 < 2 || x0 + w - x <= 2 || y - y0 < 2 || y0 + h - y <= 2
            val v = if (border) 20 else blocks[((x - x0) / 5 % 8) + 8 * ((y - y0) / 6 % 8)]
            px[y * mw + x] = s.noise(v + offset, 2)
        }
        return Gray(mw, s.h, px)
    }

    /** A hand passing: a big blob of random change over the frame. */
    private fun hand(base: Gray, s: Scene): Gray {
        val px = base.px.copyOf()
        val cx = s.rnd.nextInt(mw); val cy = s.rnd.nextInt(base.h)
        for (y in 0 until base.h) for (x in 0 until mw) {
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < 900) px[y * mw + x] = s.rnd.nextInt(0, 256)
        }
        return Gray(mw, base.h, px)
    }

    // ── both implementations over one scenario ──────────────────────────────
    private data class Step(val op: String, val frame: Gray? = null, val on: Boolean = true)

    private fun runJs(steps: List<Step>): JSONArray {
        val scenario = JSONObject().put("steps", JSONArray(steps.map { st ->
            JSONObject().put("op", st.op).put("on", st.on).apply {
                st.frame?.let { put("frame", JSONObject().put("h", it.h).put("px", JSONArray(it.px))) }
            }
        }))
        val harness = File(javaClass.getResource("/phone_harness.js")!!.toURI())
        val proc = ProcessBuilder(System.getProperty("nodeBin", "node"), harness.path,
            System.getProperty("phoneHtml")).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        proc.outputStream.use { it.write(scenario.toString().toByteArray()) }
        val out = proc.inputStream.readBytes().decodeToString()
        assertTrue("node harness timed out", proc.waitFor(120, TimeUnit.SECONDS))
        assertEquals("node harness failed: $out", 0, proc.exitValue())
        return JSONArray(out)
    }

    private fun runKotlin(steps: List<Step>): List<JSONObject> {
        val sc = AutoScanner()
        return steps.map { st ->
            var trigger = false; var next = false
            when (st.op) {
                "tick" -> when (sc.tick(st.frame)) {
                    is AutoScanner.Event.Trigger -> trigger = true
                    is AutoScanner.Event.NextCard -> next = true
                    else -> {}
                }
                "captureDone" -> sc.captureDone()
                "manual" -> sc.manualScan(st.frame)
                "noCard" -> sc.onNoCard(st.frame, sc.scannedFrame)
                "reset" -> sc.reset()
                "auto" -> sc.setAuto(st.on)
            }
            val box = st.frame?.let { sc.boxFor(it) }
            JSONObject()
                .put("mode", when (sc.mode) {
                    AutoScanner.Mode.WATCHING -> "watching"
                    AutoScanner.Mode.SCANNING -> "scanning"
                    AutoScanner.Mode.AWAIT_NEXT -> "awaitNext"
                })
                .put("stableCount", sc.stableCount).put("bootCount", sc.bootCount)
                .put("emptyRefreshTick", sc.emptyRefreshTick).put("hasEmptyRef", sc.hasEmptyRef)
                .put("trigger", trigger).put("next", next)
                .put("box", box?.let { JSONArray(listOf(it.x, it.y, it.w, it.h, it.maskFrac)) } ?: JSONObject.NULL)
        }
    }

    private fun assertSame(name: String, steps: List<Step>) {
        val js = runJs(steps)
        val kt = runKotlin(steps)
        assertEquals("$name: step count", js.length(), kt.size)
        for (i in kt.indices) {
            val a = js.getJSONObject(i); val b = kt[i]
            for (k in listOf("mode", "stableCount", "bootCount", "emptyRefreshTick", "hasEmptyRef", "trigger", "next")) {
                assertEquals("$name step $i (${steps[i].op}) $k", a.get(k).toString(), b.get(k).toString())
            }
            val ab = a.opt("box"); val bb = b.opt("box")
            if (ab == null || ab == JSONObject.NULL || bb == JSONObject.NULL) {
                assertEquals("$name step $i box", ab.toString(), bb.toString())
            } else {
                ab as JSONArray; bb as JSONArray
                for (j in 0 until 4) assertEquals("$name step $i box[$j]", ab.getInt(j), bb.getInt(j))
                assertEquals("$name step $i maskFrac", ab.getDouble(4), bb.getDouble(4), 1e-12)
            }
        }
    }

    // ── scenarios ───────────────────────────────────────────────────────────
    @Test fun placeSettleTriggerSwapRemove() {
        val s = Scene(h = 132, seed = 1)
        val steps = mutableListOf<Step>()
        repeat(5) { steps += Step("tick", empty(s)) }                       // learn the tray
        repeat(2) { steps += Step("tick", hand(empty(s), s)) }               // hand comes in
        repeat(4) { steps += Step("tick", card(s, 50, 20, 60, 84, face = 7)) } // settles → trigger
        steps += Step("tick", card(s, 50, 20, 60, 84, 7))                    // ignored while scanning
        steps += Step("captureDone")
        repeat(3) { steps += Step("tick", card(s, 50, 20, 60, 84, 7)) }      // awaiting next
        repeat(4) { steps += Step("tick", card(s, 54, 22, 60, 84, face = 99)) } // swapped card
        steps += Step("captureDone")
        repeat(2) { steps += Step("tick", hand(card(s, 54, 22, 60, 84, 99), s)) }
        repeat(30) { steps += Step("tick", empty(s)) }                      // removed; empty refresh at 25
        assertSame("place/swap/remove", steps)
        assertTrue(runKotlin(steps).count { it.getBoolean("trigger") } >= 2)
    }

    @Test fun exposureDriftStillCountsAsSteady() {
        val s = Scene(h = 100, seed = 2)
        val steps = mutableListOf<Step>()
        repeat(5) { steps += Step("tick", empty(s)) }
        for (off in listOf(0, 6, 12, 18, 24)) steps += Step("tick", card(s, 40, 10, 58, 80, 3, offset = off))
        assertSame("exposure drift", steps)
    }

    @Test fun noCardReseedResetManualAutoAndAspectChange() {
        val s = Scene(h = 120, seed = 3)
        val holder = { card(s, 60, 15, 55, 80, face = 0) }   // face 0 still textured — fine
        val steps = mutableListOf<Step>()
        repeat(5) { steps += Step("tick", empty(s)) }
        repeat(3) { steps += Step("tick", holder()) }
        steps += Step("captureDone")
        steps += Step("noCard", holder())                    // server saw no card → re-seed
        repeat(3) { steps += Step("tick", holder()) }
        steps += Step("reset")
        steps += Step("noCard", holder())                    // reset raced a no_card re-seed
        repeat(3) { steps += Step("tick", card(s, 20, 20, 50, 70, 5)) }
        steps += Step("manual", card(s, 20, 20, 50, 70, 5))
        steps += Step("tick", card(s, 20, 20, 50, 70, 5))
        steps += Step("captureDone")
        steps += Step("auto", on = false)
        steps += Step("tick", empty(s))
        steps += Step("auto", on = true)
        val t = Scene(h = 64, seed = 4)                      // new scan-area aspect
        repeat(6) { steps += Step("tick", empty(t)) }
        repeat(3) { steps += Step("tick", card(t, 70, 5, 40, 52, 9)) }
        assertSame("noCard/reset/manual/auto/aspect", steps)
    }

    @Test fun fuzz() {
        for (seed in 1..12) {
            val s = Scene(h = listOf(48, 90, 132)[seed % 3], seed = 100 + seed)
            val r = Random(seed)
            val steps = mutableListOf<Step>()
            var cardAt: Triple<Int, Int, Int>? = null
            repeat(160) {
                val roll = r.nextInt(100)
                when {
                    roll < 6 -> cardAt = null
                    roll < 12 -> cardAt = Triple(r.nextInt(0, 110), r.nextInt(0, s.h / 3), r.nextInt(1, 50))
                    roll < 14 -> steps += Step("captureDone")
                    roll < 15 -> steps += Step("reset")
                    roll < 16 -> steps += Step("auto", on = r.nextBoolean())
                }
                val c = cardAt
                val base = if (c == null) empty(s, r.nextInt(-3, 4))
                           else card(s, c.first, c.second, 60, minOf(84, s.h - c.second), c.third, r.nextInt(-3, 4))
                val frame = if (r.nextInt(100) < 12) hand(base, s) else base
                steps += when (r.nextInt(100)) {
                    in 0..1 -> Step("noCard", frame)
                    2 -> Step("manual", frame)
                    else -> Step("tick", frame)
                }
            }
            assertSame("fuzz seed $seed", steps)
        }
    }
}
