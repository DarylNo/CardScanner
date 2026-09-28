package io.github.darylno.cardscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isNotEmpty
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.BuildConfig
import io.github.darylno.cardscanner.R
import io.github.darylno.cardscanner.f2f.CronetTransport
import io.github.darylno.cardscanner.f2f.F2fProbe
import io.github.darylno.cardscanner.f2f.F2fTransport
import io.github.darylno.cardscanner.f2f.OkHttpTransport
import io.github.darylno.cardscanner.f2f.PriceOutcome
import io.github.darylno.cardscanner.f2f.ProbeCard
import io.github.darylno.cardscanner.f2f.ProbeCards
import io.github.darylno.cardscanner.f2f.ProbeStats
import io.github.darylno.cardscanner.f2f.StopSignal
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Hidden diagnostics (long-press DIAGNOSTICS in Settings). Two tools:
 *
 * Stage 2 — On-phone identify / Compare mode ([CompareSection]): the switch,
 * the art pack, "Test on last capture" and the server-vs-phone report.
 *
 * Stage 1c (docs/PHONE_ONLY_PLAN.md): does Face to Face Games' storefront
 * throttle the PHONE? Prices [ProbeCards.ALL] at the rig's pacing over Cronet
 * (a real Chrome TLS handshake) and over plain OkHttp, and reports 429s and
 * timings. A measurement tool only — reached by long-pressing the
 * DIAGNOSTICS header in Settings; the normal app never touches it.
 *
 * "Run both" goes Cronet FIRST: the storefront's bucket is per-IP, so the
 * second run can inherit a bucket the first one drained. Cronet is the stack
 * we'd ship, so it gets the clean, cold bucket; if Cronet itself trips the
 * bucket that is already the answer (FAIL), and a contaminated OkHttp run
 * after it can't change the decision. Every run waits out a cooldown
 * (default 5 min, well past the rig's 120 s idle reset) after the previous
 * one, shown as a countdown.
 */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var chrome: ScanChrome
    private lateinit var col: LinearLayout
    private lateinit var status: TextView
    private lateinit var resultsBox: LinearLayout
    private val buttons = mutableListOf<Button>()
    private lateinit var stopBtn: Button
    private lateinit var cooldownBtn: Button
    private var compareSection: CompareSection? = null

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "f2f-probe") }
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var stop: StopSignal? = null
    @Volatile private var statusText: String = ""
    @Volatile private var lastRunEnd: Long? = null     // elapsedRealtime ms
    private var cooldownMin = DEFAULT_COOLDOWN_MIN
    private val results = linkedMapOf<String, ProbeStats>()     // latest per transport
    private val runErrors = linkedMapOf<String, String>()
    private val runLog = mutableListOf<String>()               // order + start times, for the report

    private fun dp(v: Int) = chrome.dp(v)
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(top) }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (running) ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chrome = ScanChrome(this)
        statusText = getString(R.string.nettest_idle)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ScanChrome.Palette.BG)
            addView(chrome.topBar(getString(R.string.diag_title), getString(R.string.back)) { finish() },
                LinearLayout.LayoutParams(match(), wrap()))
            addView(ScrollView(this@DiagnosticsActivity).apply { addView(col) }, LinearLayout.LayoutParams(match(), 0, 1f))
        }
        chrome.applyInsets(root)
        setContentView(root)
        build()
        refresh()
    }

    override fun onDestroy() {
        compareSection?.destroy()
        stop?.set()
        worker.shutdown()      // no interrupt: the StopSignal already ends every wait
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun build() {
        compareSection = CompareSection(this, chrome, App.of(this).compare).also { it.build(col) }

        col.addView(chrome.sectionHeader(getString(R.string.nettest_title)))
        val intro = chrome.card()
        intro.addView(chrome.text(getString(R.string.nettest_intro, ProbeCards.ALL.size), 14f, ScanChrome.Palette.TEXT_DIM))
        col.addView(intro, lp())

        col.addView(chrome.sectionHeader(getString(R.string.nettest_run)))
        val run = chrome.card()
        run.addView(chrome.primaryButton(getString(R.string.nettest_run_both)) { start(listOf(CRONET, OKHTTP)) }
            .also { buttons += it }, lp())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(chrome.secondaryButton(getString(R.string.nettest_run_cronet)) { start(listOf(CRONET)) }.also { buttons += it },
            LinearLayout.LayoutParams(0, wrap(), 1f).apply { marginEnd = dp(6) })
        row.addView(chrome.secondaryButton(getString(R.string.nettest_run_okhttp)) { start(listOf(OKHTTP)) }.also { buttons += it },
            LinearLayout.LayoutParams(0, wrap(), 1f).apply { marginStart = dp(6) })
        run.addView(row, lp(10))
        cooldownBtn = chrome.secondaryButton("") {
            cooldownMin = COOLDOWN_CHOICES[(COOLDOWN_CHOICES.indexOf(cooldownMin) + 1) % COOLDOWN_CHOICES.size]
            refresh()
        }.also { buttons += it }
        run.addView(cooldownBtn, lp(10))
        stopBtn = chrome.destructiveButton(getString(R.string.nettest_stop)) { stop?.set() }
        run.addView(stopBtn, lp(10))
        status = chrome.text("", 14f, ScanChrome.Palette.TEXT, bold = true).apply { setPadding(dp(4), dp(14), dp(4), 0) }
        run.addView(status, lp())
        col.addView(run, lp())

        col.addView(chrome.sectionHeader(getString(R.string.nettest_results)))
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(resultsBox, lp())
        col.addView(chrome.secondaryButton(getString(R.string.nettest_copy)) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Card Scanner network test", report().toString(1)))
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }, lp(14))
    }

    private fun start(order: List<String>) {
        if (running) return
        running = true
        val signal = StopSignal()
        stop = signal
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.post(ticker)
        worker.execute {
            try {
                for (t in order) {
                    if (signal.isSet) break
                    if (!cooldown(signal, t)) break
                    runOne(signal, t)
                }
            } finally {
                statusText = getString(if (signal.isSet) R.string.nettest_stopped else R.string.nettest_done)
                running = false
                ui.post {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    refresh()
                }
            }
        }
    }

    /** Wait out the per-IP cooldown since the previous run. False = stopped. */
    private fun cooldown(signal: StopSignal, next: String): Boolean {
        val end = lastRunEnd ?: return true
        val until = end + cooldownMin * 60_000L
        while (true) {
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) return true
            val s = (left + 999) / 1000
            statusText = getString(R.string.nettest_cooling, s / 60, s % 60, label(next))
            if (signal.await(minOf(1.0, left / 1000.0))) return false
        }
    }

    private fun runOne(signal: StopSignal, t: String) {
        statusText = getString(R.string.nettest_starting, label(t))
        val transport: F2fTransport = try {
            if (t == CRONET) CronetTransport.create(applicationContext) else OkHttpTransport()
        } catch (e: Throwable) {
            synchronized(results) { runErrors[t] = "${e.javaClass.simpleName}: ${e.message}" }
            return
        }
        synchronized(results) { runErrors.remove(t); runLog += "$t@${iso(Date())}" }
        val cards = ProbeCards.ALL
        val probe = F2fProbe(transport, cards, signal, listener = object : F2fProbe.Listener {
            override fun onCardStart(index: Int, card: ProbeCard) {
                statusText = getString(R.string.nettest_progress, label(t), index + 1, cards.size, card.label)
            }

            override fun onCard(index: Int, card: ProbeCard, outcome: PriceOutcome, stats: ProbeStats) {}
        })
        synchronized(results) { results[t] = probe.stats }
        try {
            probe.run()
        } catch (e: Throwable) {
            synchronized(results) { runErrors[t] = "${e.javaClass.simpleName}: ${e.message}" }
        } finally {
            transport.close()
            lastRunEnd = SystemClock.elapsedRealtime()
        }
    }

    private fun label(t: String) = if (t == CRONET) "Cronet" else "OkHttp"

    private fun refresh() {
        status.text = statusText
        stopBtn.isEnabled = running
        stopBtn.alpha = if (running) 1f else 0.4f
        buttons.forEach { it.isEnabled = !running; it.alpha = if (running) 0.4f else 1f }
        cooldownBtn.text = getString(R.string.nettest_cooldown, cooldownMin)
        resultsBox.removeAllViews()
        val snapshot = synchronized(results) { results.toMap() to runErrors.toMap() }
        if (snapshot.first.isEmpty() && snapshot.second.isEmpty()) {
            resultsBox.addView(chrome.card().apply {
                addView(chrome.text(getString(R.string.nettest_no_results), 14f, ScanChrome.Palette.TEXT_DIM))
            }, lp())
            return
        }
        for (t in listOf(CRONET, OKHTTP)) {
            val s = snapshot.first[t]
            val err = snapshot.second[t]
            if (s == null && err == null) continue
            val card = chrome.card()
            card.addView(chrome.text(label(t), 16f, bold = true))
            if (s != null) {
                card.addView(chrome.text(s.detail, 12f, ScanChrome.Palette.TEXT_FAINT).apply { setPadding(0, dp(2), 0, dp(8)) })
                card.addView(mono(s.summary(), 12f, ScanChrome.Palette.TEXT))
                val v = if (s.finished) s.verdict() else getString(R.string.nettest_running_verdict, s.verdict())
                val tone = when (if (s.finished) s.verdictTone() else 0) {
                    1 -> ScanChrome.Palette.OK
                    -1 -> ScanChrome.Palette.ERR
                    else -> ScanChrome.Palette.WARN
                }
                card.addView(chrome.text(v, 14f, tone, bold = true).apply { setPadding(0, dp(10), 0, 0) })
                val recent = s.recentRequests()
                if (recent.isNotEmpty()) {
                    card.addView(mono(recent.joinToString("\n") { r ->
                        String.format(Locale.US, "#%-3d %-7s %-9s %5d ms  pace %.2f s%s",
                            r.seq, r.kind, r.outcome, r.latencyMs, r.paceS, r.protocol?.let { " $it" } ?: "")
                    }, 11f, ScanChrome.Palette.TEXT_DIM).apply { setPadding(0, dp(10), 0, 0) })
                }
            }
            if (err != null) card.addView(chrome.text(getString(R.string.nettest_error, err), 13f, ScanChrome.Palette.ERR)
                .apply { setPadding(0, dp(8), 0, 0) })
            resultsBox.addView(card, lp(if (resultsBox.isNotEmpty()) 12 else 0))
        }
    }

    private fun mono(t: String, size: Float, color: Int) = chrome.text(t, size, color).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    /** The compact JSON the owner pastes back. */
    private fun report(): JSONObject = JSONObject().apply {
        put("probe", "f2f-stage1c")
        put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        put("network", networkKind())
        put("at", iso(Date()))
        put("cards", ProbeCards.ALL.size)
        put("cooldown_min", cooldownMin)
        synchronized(results) {
            put("order", JSONArray(runLog.toList()))
            put("runs", JSONArray(results.values.map { it.toJson() }))
            if (runErrors.isNotEmpty()) put("errors", JSONObject(runErrors.toMap()))
        }
    }

    private fun networkKind(): String = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    } catch (e: Exception) {
        "unknown"
    }

    private fun iso(d: Date) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)

    companion object {
        const val CRONET = "cronet"
        const val OKHTTP = "okhttp"
        const val DEFAULT_COOLDOWN_MIN = 5
        val COOLDOWN_CHOICES = listOf(5, 10, 15, 2, 0)
    }
}
