package io.github.darylno.cardscanner.ui

import io.github.darylno.cardscanner.core.DebugLog
import io.github.darylno.cardscanner.core.MiniJson
import io.github.darylno.cardscanner.core.ZoomCoordinator
import io.github.darylno.cardscanner.core.ZoomFit
import io.github.darylno.cardscanner.core.ZoomProbe
import io.github.darylno.cardscanner.core.ZoomProbeRun
import io.github.darylno.cardscanner.core.server.DeviceApi
import org.json.JSONArray
import java.util.Locale
import kotlin.math.abs

/**
 * The guided measurement session (owner, 2026-10-02: "a test mode that prompts
 * me to do all these tests"): the rig session that decides the shadow-proof
 * threshold, whether real sleeved cards yield a live outline, the card's real
 * pixel size (the zoom question) and the N200 timings — as a card on the scan
 * screen that walks through the steps, counts the captures during each one,
 * applies the settings a step needs (AE lock, vibration, check mark time, Zoom to
 * fit) and restores the owner's own at the end, and writes a `test` marker into
 * the debug log at every step so the `shadow` / `outline` / `capture` lines can be
 * read against what was being done. The session survives leaving the screen
 * (plan, step and counters live in [AppSettings]); Stop or the last step ends it.
 *
 * THE ZOOM A/B (1.1.17): the same 10 cards at 1× (A1), zoomed by "Zoom to fit the
 * Area" (B) and at 1× again (A2) — each arm step pins the switch, so B's zoom never
 * leaks into another step (Back included) — with each Tray capture's zoom, card
 * size, strip detail and ISO and its identification tallied ([AbRecord]) and
 * judged by rules written down before the data ([AbSummary]); the summary and the
 * verdict are logged before "session END". "Run the zoom steps" ([Plan.ZOOM]) runs
 * just those.
 *
 * THE ZOOM PROBE'S STEP (1.1.17, between A1 and B): the scan screen's Run probe runs
 * core ZoomProbeRun on the card A1 left; each run that used its number is kept for the
 * session ([recordProbe], AppSettings.zoomProbeRuns), the card shows what the runs say
 * TOGETHER (ZoomProbe.combine), and the session's verdict at the end is the A/B half
 * and the probe half together — never YES in this build: the probe measures no knee
 * (ZoomProbe.DETAIL_VALIDATED is false). The last verdict outlives the session
 * (AppSettings.zoomProbeLast, for Diagnostics and `/api/device`).
 *
 * Pure state: the scan screen hosts the card, this decides what it says.
 */
class MeasureSession(
    private val settings: AppSettings,
    private val log: DebugLog = DebugLog.global,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * One step. [target] = how many times to do it (0 = not counted); [minutes] = how
     * long to hold it (0 = no timer); [question] asks for a Yes/No before Next; the
     * nullable settings are applied when the step starts (null = inherited from the
     * step before). [arm] = the zoom A/B arm ("A1" | "B" | "A2") whose captures are
     * tallied; [action] = what the step's own button does ([Action.PROBE]: the scan
     * screen's Run probe / Cancel probe / Run again).
     */
    class Step(
        val id: String,
        val title: String,
        val instruction: String,
        val target: Int = 0,
        val minutes: Int = 0,
        val question: String? = null,
        val aeLock: Boolean? = null,
        val vibration: Boolean? = null,
        val checkMs: Int? = null,
        val zoomFit: Boolean? = null,
        val arm: String? = null,
        val action: Action? = null,
    )

    enum class Action { PROBE }

    /** Which steps a session runs: the whole rig session, or just the zoom A/B ("Run the zoom steps"). */
    enum class Plan(val key: String) {
        FULL("full"), ZOOM("zoom");

        val steps: List<Step> get() = if (this == ZOOM) ZOOM_STEPS else STEPS
    }

    val plan: Plan get() = if (settings.measurePlan == Plan.ZOOM.key) Plan.ZOOM else Plan.FULL
    val steps: List<Step> get() = plan.steps
    val active: Boolean get() = settings.measureStep >= 0

    /**
     * The current step, resolved by its saved id first (an index into another step
     * list lands elsewhere); a session saved by 1.1.16 (an index, no id) is read
     * against 1.1.16's own list ([LEGACY_IDS]).
     */
    val index: Int get() {
        val list = steps
        val id = settings.measureStepId
        if (id != null) list.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return it }
        val i = settings.measureStep
        if (id == null && plan == Plan.FULL) LEGACY_IDS.getOrNull(i)?.let { old ->
            list.indexOfFirst { it.id == old }.takeIf { it >= 0 }?.let { return it }
        }
        return i.coerceIn(0, list.size - 1)
    }
    val step: Step get() = steps[index]
    /** Captures taken since this step started. */
    val captures: Int get() = settings.measureCaptures
    val stepStartedAt: Long get() = settings.measureStepStartedAt
    /** The Yes/No given for this step, or null. */
    val answer: Boolean? get() = settings.measureAnswer
    val isLast: Boolean get() = index == steps.size - 1

    /** Begin at step 1 of [plan]: remember the owner's own settings, apply the first step's, log. */
    fun start(plan: Plan = Plan.FULL) {
        if (active) finish(abandoned = true)
        settings.measurePlan = if (plan == Plan.FULL) null else plan.key
        settings.measureAb = null
        settings.zoomProbeRuns = null
        probeThumbs.clear()
        settings.measureOriginal = "${settings.aeLock},${settings.vibration},${settings.checkMs},${settings.zoomFit}"
        log.i("test", "session START${if (plan == Plan.ZOOM) ", the zoom steps" else ""} (your settings: AE lock ${settings.aeLock}, " +
            "vibration ${settings.vibration}, check ${settings.checkMs} ms, zoom to fit ${settings.zoomFit})")
        enter(0)
    }

    /**
     * A capture happened while the session is on. On a zoom A/B arm step it is also
     * recorded: its burst id, whether it was the shutter, the zoom it was shot at, the
     * card's long side in frame px, the strip detail and the ISO.
     */
    fun captured(burstId: Long = 0L, manual: Boolean = false, zoom: Double = 1.0, cardLongPx: Int = 0,
                 stripDetail: Double? = null, iso: Int? = null) {
        if (!active) return
        settings.measureCaptures = settings.measureCaptures + 1
        val arm = step.arm ?: return
        save(records + AbRecord(arm, burstId, zoom, cardLongPx, stripDetail, iso, manual))
    }

    /** Capture [burstId] became upload job [jobId] (the outcome is matched by it). */
    fun queued(jobId: String, burstId: Long) {
        if (!active || burstId <= 0) return
        val r = records
        val i = r.indexOfLast { it.burst == burstId && it.job == null }
        if (i < 0) return
        save(r.toMutableList().also { it[i] = it[i].copy(job = jobId) })
    }

    /** Job [jobId]'s identification: [rank] (see [AbSummary]), the name read, OCR ✓, auto-filed, printings found. */
    fun identified(jobId: String, rank: Int, name: String?, ocr: Boolean, autoPicked: Boolean, printings: Int) {
        if (!active) return
        val r = records
        val i = r.indexOfFirst { it.job == jobId }
        if (i < 0) return
        save(r.toMutableList().also {
            it[i] = it[i].copy(rank = rank, name = name, ocr = ocr, autoPicked = autoPicked, printings = printings)
        })
    }

    /** [identified] from the upload queue's [outcome]. */
    fun identified(jobId: String, outcome: Outcome) {
        if (!active) return
        val f = facts(outcome)
        identified(jobId, f.rank, f.name, f.ocr, f.autoPicked, f.printings)
    }

    /** This session's zoom A/B records, oldest first. */
    val records: List<AbRecord> get() {
        val raw = settings.measureAb ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(AbRecord::fromJson) }
        }.getOrDefault(emptyList())
    }

    private fun save(r: List<AbRecord>) {
        settings.measureAb = if (r.isEmpty()) null else JSONArray().apply { r.takeLast(MAX_RECORDS).forEach { put(it.toJson()) } }.toString()
    }

    fun abSummary(): AbSummary = abSummary(records)

    // ── the zoom probe's runs this session (1.1.17) ──

    /**
     * One attempt at the probe that used its run number ([ZoomProbeRun.Outcome.numbered], or one
     * with a result): how it ended ([kind] = ZoomProbeRun.Kind's word), its own [words], when, and
     * its [result] (null: cancelled, or refused after its start, before one was measured).
     */
    class ProbeRun(val run: Int, val kind: String, val words: String, val at: Long, val result: ZoomProbe.Result?) {
        fun toJson(): Map<String, Any?> = linkedMapOf("run" to run, "kind" to kind, "words" to words, "at" to at, "result" to result?.toMap())

        companion object {
            fun fromJson(m: Map<*, *>): ProbeRun? = runCatching {
                ProbeRun((m["run"] as Number).toInt(), m["kind"] as String, m["words"] as String, (m["at"] as Number).toLong(),
                    (m["result"] as? Map<*, *>)?.let { ZoomProbe.Result.fromMap(it) })
            }.getOrNull()
        }
    }

    /**
     * The earlier VALID runs' 1× card thumbnails, for the next run's same-card check — in
     * memory only (64×88 floats each): after the app restarts the check has nothing to
     * compare with and says nothing (the plan's "if the earlier thumbnail is still in memory").
     */
    private val probeThumbs = ArrayList<ZoomProbeRun.Earlier>()

    /** This session's probe runs, oldest first (AppSettings.zoomProbeRuns). */
    val probeRuns: List<ProbeRun> get() {
        val raw = settings.zoomProbeRuns ?: return emptyList()
        return runCatching { (MiniJson.parse(raw) as List<*>).mapNotNull { (it as? Map<*, *>)?.let(ProbeRun::fromJson) } }.getOrDefault(emptyList())
    }

    /** The number the owner's next Run probe gets: one past the last this session used. */
    fun nextProbeRun(): Int = (probeRuns.maxOfOrNull { it.run } ?: 0) + 1

    /** The thumbnails the next run compares its card with ([ZoomProbeRun]'s `earlier`). */
    fun probeEarlier(): List<ZoomProbeRun.Earlier> = probeThumbs.toList()

    /**
     * What this session's measured runs say together (ZoomProbe.combine: those on the newest run's
     * camera and stream — the owner may change the lens or High res between runs), or null when none measured.
     */
    fun probeCombined(): ZoomProbe.Combined? =
        probeRuns.mapNotNull { it.result }.takeIf { it.isNotEmpty() }?.let { ZoomProbe.combine(it) }

    /** The probe step's line between runs: what the session's runs say together, or null. */
    fun probeLine(): String? = probeCombined()?.words

    /**
     * Run [run] ended ([o], from the scan screen's ProbeListener): keep it for the session when it
     * used its number, keep its thumbnail when valid, keep the session's verdict as the last one
     * when it measured, mark the step in the log — and answer the words for the card: what the
     * session's runs now say together, or — no result — the run's own (a refusal says what to do).
     */
    fun recordProbe(run: Int, o: ZoomProbeRun.Outcome): String {
        val at = clock()
        o.thumbnail?.let { t -> if (o.result?.valid == true) { probeThumbs.removeAll { it.run == run }; probeThumbs += ZoomProbeRun.Earlier(run, t) } }
        if (active && (o.numbered || o.result != null))
            saveProbeRuns(probeRuns.filter { it.run != run } + ProbeRun(run, o.kind.word, o.words, at, o.result))
        val result = o.result
        val results = if (active) probeRuns.mapNotNull { it.result } else listOfNotNull(result)
        val combined = if (result != null) ZoomProbe.combine(results) else null
        val words = combined?.words ?: o.words
        if (result != null && combined != null) settings.zoomProbeLast = MiniJson.stringify(lastProbe(at, result, combined, results.size))
        if (active) log.i("test", "step ${index + 1}/${steps.size} ${step.id}: run $run → ${o.kind.word} — $words")
        return words
    }

    private fun saveProbeRuns(r: List<ProbeRun>) {
        settings.zoomProbeRuns = if (r.isEmpty()) null else MiniJson.stringify(r.takeLast(MAX_PROBE_RUNS).map { it.toJson() })
    }

    /** The last verdict as Diagnostics and `/api/device` read it (DeviceApi's `zoom_probe`). */
    private fun lastProbe(at: Long, r: ZoomProbe.Result, c: ZoomProbe.Combined, runs: Int): Map<String, Any?> = linkedMapOf(
        "at" to at, "camera" to r.camera, "stream" to r.stream, "run" to r.run, "runs" to runs,
        "valid_cards" to c.validCards, "centred" to c.centred, "settle_ok" to c.settleOk,
        "reports_zoom_ratio" to c.reportsZoomRatio, "z_pipe" to c.zPipe,
        "words" to c.words, "probe_half" to c.checks(), "last" to r.toMap(),
    )

    /**
     * What the count line adds on this step (text only): on an arm step the arm's live
     * tally and its notes ([notes]); on the last step the A/B summary and verdict.
     */
    fun countExtra(auto: Boolean, zoom: ZoomCoordinator.State?): String {
        if (!active) return ""
        val s = step
        val arm = s.arm
        return when {
            arm != null -> {
                val mine = records.filter { it.arm == arm && !it.manual }
                val shutter = records.count { it.arm == arm && it.manual }
                " · OCR ✓ ${mine.count { it.rank == 3 }} · auto-filed ${mine.count { it.autoPicked }} · identifying ${mine.count { it.rank == null }}" +
                    (if (shutter > 0) " · $shutter shutter (not counted)" else "") +
                    notes(s, auto, zoom).joinToString("") { "\n$it" }
            }
            isLast -> {
                // The probe half by its rules' short names (review of a60e51d: its words pushed the done card
                // past 45 % of the screen); the words are on the probe's step and in the log's probe VERDICT.
                val probe = probeCombined()
                (records.takeIf { it.isNotEmpty() }?.let { "\n" + abSummary(it).cardText(probe?.let(::probeChecks)) } ?: "") +
                    (probe?.let { "\nProbe: " + it.shortChecks() } ?: "")
            }
            else -> ""
        }
    }

    fun answer(yes: Boolean) {
        if (!active) return
        settings.measureAnswer = yes
        log.i("test", "step ${index + 1}/${steps.size} ${step.id}: answer ${if (yes) "YES" else "NO"} — ${step.question}")
    }

    /** Next step; on the last step this ends the session. */
    fun next() {
        if (!active) return
        leave("done")
        if (isLast) finish(abandoned = false) else enter(index + 1)
    }

    fun back() {
        if (!active || index == 0) return
        leave("went back")
        enter(index - 1)
    }

    /**
     * Stop (abandoned) or the last step done: restore the owner's settings, log the zoom
     * A/B (when any arm ran) BEFORE "session END", clear the state.
     */
    fun finish(abandoned: Boolean) {
        if (!active) return
        if (abandoned) leave("stopped")
        val n = steps.size
        val done = if (abandoned) index else n
        val at = index + 1
        restore()
        // The combined verdict (1.1.17): the probe's half first, then the A/B with both halves.
        val probe = probeCombined()
        probe?.let { log.i("test", it.logLine()) }
        records.takeIf { it.isNotEmpty() }?.let { r -> abSummary(r).logLines(probe?.let(::probeChecks)).forEach { log.i("test", it) } }
        log.i("test", "session END (${if (abandoned) "stopped at step $at" else "all $n steps done"}, $done/$n; your settings restored)")
        settings.measureStep = -1
        settings.measureStepId = null
        settings.measurePlan = null
        settings.measureAb = null
        settings.zoomProbeRuns = null
        probeThumbs.clear()
        settings.measureCaptures = 0
        settings.measureAnswer = null
        settings.measureOriginal = null
    }

    private fun enter(i: Int) {
        val list = steps
        val s = list[i]
        settings.measureStep = i
        settings.measureStepId = s.id
        settings.measureCaptures = 0
        settings.measureAnswer = null
        settings.measureStepStartedAt = clock()
        s.aeLock?.let { settings.aeLock = it }
        s.vibration?.let { settings.vibration = it }
        s.checkMs?.let { settings.checkMs = it }
        if (s.zoomFit != null) keepOwnersZoomFit()
        s.zoomFit?.let { settings.zoomFit = it }
        // Entering an arm (by Next or Back) starts it afresh: a re-run replaces its records.
        s.arm?.let { arm -> records.let { r -> if (r.any { it.arm == arm }) save(r.filter { it.arm != arm }) } }
        val applied = listOfNotNull(s.aeLock?.let { "AE lock $it" }, s.vibration?.let { "vibration $it" }, s.checkMs?.let { "check $it ms" },
            s.zoomFit?.let { "zoom to fit ${if (it) "on" else "off"}" })
        log.i("test", "step ${i + 1}/${list.size} START ${s.id}: ${s.title}" +
            (if (s.target > 0) " ×${s.target}" else "") + (if (s.minutes > 0) " ${s.minutes} min" else "") +
            (if (applied.isNotEmpty()) " [${applied.joinToString(", ")}]" else ""))
    }

    private fun leave(how: String) {
        val s = step
        val secs = ((clock() - settings.measureStepStartedAt) / 1000L).coerceAtLeast(0)
        // An arm's burst range, BEFORE the answer (the answer ends the line).
        val bursts = s.arm?.let { arm -> records.filter { it.arm == arm && it.burst > 0 }.map { it.burst } }.orEmpty()
        val range = when {
            bursts.isEmpty() -> ""
            bursts.min() == bursts.max() -> " · capture #${bursts.min()}"
            else -> " · captures #${bursts.min()}–#${bursts.max()}"
        }
        log.i("test", "step ${index + 1}/${steps.size} END ${s.id} ($how) after $secs s: ${settings.measureCaptures} capture(s)" +
            (if (s.target > 0) " of ×${s.target}" else "") + range +
            (settings.measureAnswer?.let { " · answer ${if (it) "YES" else "NO"}" } ?: ""))
    }

    /**
     * A session started on 1.1.16 saved three fields ("aeLock,vibration,checkMs"): none of
     * its steps touched Zoom to fit, so the first time one of ours is about to pin it the
     * switch is still the owner's own — save it now, as the 4th field, or Stop / Finish
     * would leave it wherever the last zoom step put it (review of 9df0293: a 1.1.16
     * session resumed after the update lock's update walked into the zoom steps and ended
     * with the owner's Zoom to fit off). Once only: the original then has four fields.
     */
    private fun keepOwnersZoomFit() {
        val o = settings.measureOriginal ?: return
        if (o.split(",").size != 3) return
        settings.measureOriginal = "$o,${settings.zoomFit}"
        log.i("test", "a session started on an older build: your Zoom to fit (${settings.zoomFit}) is kept now, before this step sets it")
    }

    private fun restore() {
        val o = settings.measureOriginal?.split(",") ?: return
        if (o.size < 3) return
        o[0].toBooleanStrictOrNull()?.let { settings.aeLock = it }
        o[1].toBooleanStrictOrNull()?.let { settings.vibration = it }
        o[2].toIntOrNull()?.let { settings.checkMs = it }
        // 1.1.17 saves Zoom to fit too; a session started on 1.1.16 saved three fields — the 4th is
        // added when one of our steps first pins the switch ([keepOwnersZoomFit]), else it is left alone.
        o.getOrNull(3)?.toBooleanStrictOrNull()?.let { settings.zoomFit = it }
    }

    /** What an upload outcome means to the A/B: its rank (3 OCR ✓ · 2 auto-filed · 1 needs a pick · 0 none), name, OCR ✓, auto-filed, printings. */
    class Facts(val rank: Int, val name: String?, val ocr: Boolean, val autoPicked: Boolean, val printings: Int)

    companion object {
        /** Cards per zoom A/B arm: the same 10 cards each time. */
        const val AB_CARDS = 10
        /** The A/B records kept (3 arms × 10 cards, re-runs and strays): the oldest go first. */
        const val MAX_RECORDS = 120
        /** The probe runs kept per session (2 agreeing runs decide; a third if they disagree): the oldest go first. */
        const val MAX_PROBE_RUNS = 12

        /** The probe half of the session's verdict, as the A/B's checks read ("✓ probe: centred"). */
        fun probeChecks(c: ZoomProbe.Combined): List<AbSummary.Check> =
            c.checkList().map { (ok, words) -> AbSummary.Check(ok, "probe: $words") }

        /** 1.1.16's step ids, in its order: a session it saved kept only an index into these. */
        val LEGACY_IDS = listOf("setup", "short-check", "swaps", "lift", "hands", "lean", "lights", "foil", "ae-lock", "done")

        fun facts(o: Outcome): Facts {
            val json = when (o) {
                is Outcome.AutoFiled -> o.json
                is Outcome.NeedsPick -> o.json
                is Outcome.BestGuess -> o.json
                is Outcome.NoMatch -> o.json
                else -> null
            }
            val name = json?.optJSONObject("card_read")?.optString("name")?.takeIf { it.isNotEmpty() }
            val cands = json?.optJSONArray("candidates")
            val ocr = cands?.optJSONObject(0)?.optBoolean("ocr_confirmed", false) == true
            val auto = o is Outcome.AutoFiled
            val rank = when (o) {
                is Outcome.AutoFiled -> if (ocr) 3 else 2
                is Outcome.NeedsPick, is Outcome.BestGuess -> if (ocr) 3 else 1
                else -> 0
            }
            return Facts(rank, name, ocr && rank == 3, auto, cands?.length() ?: 0)
        }

        fun abSummary(records: List<AbRecord>): AbSummary = AbSummary(records, AB_CARDS)

        private fun f2(v: Double) = "%.2f".format(Locale.ROOT, v)

        /**
         * The count line's notes on an arm step (text only): B says the zoom it runs at
         * — or that it is at 1× and why; every arm warns when Tap to scan is on; A1 says
         * what B will zoom (an Area too wide, or one that can't zoom where it is, makes B
         * 1× too) and the off-centre hint. [zoom] = the camera's ([ZoomCoordinator.state]),
         * null = no camera (nothing said about the zoom).
         */
        fun notes(step: Step, auto: Boolean, zoom: ZoomCoordinator.State?): List<String> {
            val out = ArrayList<String>()
            val area = zoom?.let { it.area ?: it.target }
            // B says what the camera is AT, not only what the Area asks (review of 9df0293: a
            // zoom that failed — the coordinator maps 1× — still read "zoom 1.90×", and the
            // owner learnt it only from Finish's "void"): the target only while it settles
            // or once applied; a ratio more than ZoomCoordinator.AGREE off it is said plainly.
            if (step.arm == "B" && zoom != null && area != null) out += when {
                area.limit == ZoomFit.Limit.NO_AREA -> "B is at 1.00× — no Area is drawn: go Back and draw one tight round where the cards land"
                area.belowMin -> "B is at 1.00× — the ${if (area.limit == ZoomFit.Limit.FIT) "Area" else "camera"} allows only ${f2(area.raw)}×: " +
                    "go Back and draw a tighter Area"
                !zoom.enabled -> "B is at ${f2(zoom.ratio)}× — Zoom to fit is off on the camera (changed from the computer?): go Back, then Next to try again"
                zoom.drawing -> "zoom " + area.describe() + " once the Area is drawn"
                zoom.settling -> "zoom → " + area.describe() + " — settling…"
                abs(zoom.ratio - area.z) <= ZoomCoordinator.AGREE * area.z -> "zoom " + area.describe()
                zoom.ratio < ZoomFit.MIN_USEFUL -> "B is at ${f2(zoom.ratio)}× — the camera did not zoom" +
                    (zoom.problem?.let { " ($it)" } ?: "") + ": go Back, then Next to try again"
                else -> "zoom ${f2(zoom.ratio)}× — the camera's answer to " + area.describe()
            }
            if (step.arm != null && !auto) out += "Tap to scan is on — switch to Tray"
            if (step.arm == "A1" && zoom != null && area != null) {
                out += when {
                    area.limit == ZoomFit.Limit.NO_AREA -> "no Area is drawn — draw one tight round where the cards land, or B will be 1× too"
                    area.belowMin && area.limit != ZoomFit.Limit.FIT -> "this camera allows only ${f2(area.raw)}× (${area.limit.words}) — B will be 1× too"
                    area.belowMin && zoom.offCentre != null -> "this Area can't zoom where it is (its fit allows only ${f2(area.raw)}×) — " +
                        "move it toward the middle or draw a tighter one first, or B will be 1× too"
                    area.belowMin -> "this Area is too wide to zoom (it allows only ${f2(area.raw)}×) — draw a tighter one first, or B will be 1× too"
                    else -> "this Area would zoom ${f2(area.z)}× with Zoom to fit on"
                }
                zoom.offCentre?.let { out += "off-centre Area: " + it.short() }
            }
            return out
        }

        // ── the zoom A/B's steps (1.1.17) ──
        private val ZOOM_A1 = Step("zoom-a1", "Same cards at 1×",
            "Tray. Draw the Area tight round where the cards land — about a card's width of tray round them; Zoom to fit needs a small Area. " +
            "Then, from an empty tray showing \"Watching for a card\", put down the SAME 10 cards one at a time, in order, lifting each before the next. " +
            "Use cards with 2+ printings and a printed set code and collector number (2015-style frame or newer), no foils, the same sleeves throughout. " +
            "Leave the 10th on the tray.",
            target = AB_CARDS, aeLock = false, zoomFit = false, arm = "A1")
        private val ZOOM_PROBE = Step("zoom-probe", "Zoom probe",
            "Leave the card on the tray, untouched — it has scanned and nothing more is filed. Tap Run probe and keep hands and shadows off the tray " +
            "for about 30 s: the camera zooms in and out on the card, then comes back. Then run it once more on a DIFFERENT card: lift this one, " +
            "put the other down, let it scan (it is filed like any scan), tap Run again. Any button while it runs only cancels it.",
            aeLock = false, zoomFit = false, action = Action.PROBE)
        private val ZOOM_B = Step("zoom-b", "Same cards zoomed",
            "Zoom to fit is now ON (the line below says how far). The card you left must NOT scan again while the zoom changes. " +
            "Then lift it and wait for \"Watching for a card\" (a couple of seconds of \"no card shape yet\" is normal — the tray was re-learned with the card on it) " +
            "and put the SAME 10 cards down in the same order. Before Next, lift the last card.",
            target = AB_CARDS, question = "Did the card you left stay un-scanned when the zoom came on?",
            aeLock = false, zoomFit = true, arm = "B")
        private val ZOOM_A2 = Step("zoom-a2", "Same cards at 1× again",
            "A again — Zoom to fit is OFF. Wait for \"Watching for a card\", then the same 10 cards once more, in order. " +
            "This checks that 1× gives the same answers twice.",
            target = AB_CARDS, aeLock = false, zoomFit = false, arm = "A2")

        /** The rig session (PR #27's checklist), in order, with the zoom A/B before AE lock. Durations and counts are the plan's. */
        val STEPS: List<Step> = listOf(
            Step("setup", "Set up",
                "Tray mode, with the Area drawn around the tray. Vibration is on, AE lock and Zoom to fit are off for now. " +
                "Put a card down: its outline should appear, go blue with a ✓ at the capture, then snap to the card's corners with no grey in between.",
                question = "Did the outline appear, go blue and snap to the card?",
                aeLock = false, vibration = true, checkMs = DeviceApi.CHECK_MS_DEFAULT, zoomFit = false),
            Step("short-check", "Short check time",
                "The check mark time is now 250 ms. Lift the card and put it back (or put a new one down): the ✓ should still hold until the capture finishes, with no grey flash before it.",
                question = "Did the ✓ hold with no grey flash?", checkMs = DeviceApi.CHECK_MS_MIN),
            Step("swaps", "Swaps without emptying the tray",
                "Swap cards 20 times WITHOUT the tray ever looking empty: drop the next card on top, or slide it in. Mix sizes, sleeved and unsleeved. Let each one settle.",
                target = 20, checkMs = DeviceApi.CHECK_MS_DEFAULT),
            Step("lift", "Lift and replace",
                "Lift the SAME card and put it straight back, about where it was. 10 times, some fast, some slow.", target = 10),
            Step("hands", "Hand passes",
                "Pass your hand over the card without touching it: 5 quick passes, 5 slow ones. Then rest your hand on the tray beside the card for 3 seconds, twice.", target = 10),
            Step("lean", "Lean in",
                "Lean over the tray so your shadow falls on the card, hold for 3 seconds, lean back. 10 times. Then do 3 over the EMPTY tray.", target = 10),
            Step("lights", "Lights",
                "Leave a card untouched and change the room light: lamp on and off, a blind or a door, your phone's torch from the side. About 3 minutes.", minutes = 3),
            Step("foil", "Foil under the lamp",
                "A sleeved foil under the lamp. Move your head and the lamp so the glare moves across it. About 1 minute.", minutes = 1),
            ZOOM_A1, ZOOM_PROBE, ZOOM_B, ZOOM_A2,
            Step("ae-lock", "AE lock on",
                "AE lock is now ON. Repeat: 5 swaps without emptying the tray, then 5 lean-ins.", target = 10, aeLock = true, zoomFit = false),
            Step("done", "Done",
                "That is everything. Wait until no card is still identifying (the chip at the top), then Next: it ends the session, puts your settings back " +
                "— AE lock, vibration, check time and Zoom to fit, even if changed from the computer; your Area stays as drawn — and opens the share sheet " +
                "for the debug report — send it over. The test scans stay in Scans: delete them there."),
        )

        /** "Run the zoom steps (about 10 min)": A1, the probe step, B, A2, done. */
        val ZOOM_STEPS: List<Step> = listOf(
            ZOOM_A1, ZOOM_PROBE, ZOOM_B, ZOOM_A2,
            Step("zoom-done", "Done",
                "Wait until no card is still identifying (the chip at the top), then Next. Your settings are put back — AE lock, vibration, check time AND " +
                "Zoom to fit, even if changed from the computer; your Area stays as drawn — and the share sheet opens with the debug report. " +
                "The 30 test scans stay in Scans: delete them there."),
        )
    }
}
