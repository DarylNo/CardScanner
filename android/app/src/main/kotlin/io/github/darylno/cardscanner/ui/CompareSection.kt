package io.github.darylno.cardscanner.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.darylno.cardscanner.BuildConfig
import io.github.darylno.cardscanner.R
import io.github.darylno.cardscanner.ident.ArtPackStore
import io.github.darylno.cardscanner.ident.CompareLog
import io.github.darylno.cardscanner.ident.CompareMode
import io.github.darylno.cardscanner.ident.CompareRow
import io.github.darylno.cardscanner.ident.SideSummary
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics → "On-phone identify (Stage 2)": the Compare-mode switch, the
 * art pack's state, a one-off "Test on last capture", the running summary
 * (agreement, phone timings, OCR-confirmed rates) and the disagreements, with
 * "Copy report" (compact JSON). Styled like the Network test below it.
 */
class CompareSection(private val activity: Activity, private val chrome: ScanChrome, private val compare: CompareMode) {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var packText: TextView
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var listBox: LinearLayout
    private var testMessage: String? = null
    private val listener = CompareLog.Listener { ui.post { refresh() } }

    private fun dp(v: Int) = chrome.dp(v)
    private fun s(id: Int, vararg a: Any) = activity.getString(id, *a)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    fun build(col: LinearLayout) {
        col.addView(chrome.sectionHeader(s(R.string.cmp_header)))
        val card = chrome.card()
        card.addView(chrome.text(s(R.string.cmp_intro), 14f, ScanChrome.Palette.TEXT_DIM))
        card.addView(chrome.switchRow(s(R.string.cmp_switch), s(R.string.cmp_switch_desc), compare.enabled) {
            compare.setEnabled(it)
        }, lp(8))
        card.addView(chrome.divider(), chrome.dividerParams())
        packText = mono("", 12f, ScanChrome.Palette.TEXT_DIM)
        card.addView(packText, lp(4))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(chrome.secondaryButton(s(R.string.cmp_check_pack)) {
            testMessage = s(R.string.cmp_checking)
            refresh()
            compare.checkPackAsync(force = true) { c -> ui.post { testMessage = checkText(c); refresh() } }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(chrome.secondaryButton(s(R.string.cmp_test_last)) {
            testMessage = s(R.string.cmp_testing)
            refresh()
            compare.testLastCapture { msg -> ui.post { testMessage = msg; refresh() } }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        card.addView(row, lp(10))
        statusText = chrome.text("", 13f, ScanChrome.Palette.TEXT).apply { setPadding(dp(4), dp(10), dp(4), 0) }
        card.addView(statusText, lp())
        col.addView(card, lp())

        col.addView(chrome.sectionHeader(s(R.string.cmp_summary_header)))
        val sum = chrome.card()
        summaryText = mono("", 12f, ScanChrome.Palette.TEXT)
        sum.addView(summaryText)
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(chrome.secondaryButton(s(R.string.cmp_copy)) { copyReport() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        actions.addView(chrome.destructiveButton(s(R.string.cmp_clear)) { compare.log.clear() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        sum.addView(actions, lp(14))
        col.addView(sum, lp())

        col.addView(chrome.sectionHeader(s(R.string.cmp_disagreements)))
        listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, lp())

        compare.log.addListener(listener)
        refresh()
    }

    fun destroy() {
        compare.log.removeListener(listener)
        ui.removeCallbacksAndMessages(null)
    }

    private fun checkText(c: ArtPackStore.Check): String = when (c) {
        is ArtPackStore.Check.Skipped -> s(R.string.cmp_check_skipped, c.reason)
        ArtPackStore.Check.NoPackYet -> s(R.string.cmp_no_pack_yet)
        is ArtPackStore.Check.UpToDate -> s(R.string.cmp_up_to_date, c.manifest.buildDate)
        is ArtPackStore.Check.Installed -> s(R.string.cmp_installed, c.manifest.rows, c.manifest.buildDate)
        is ArtPackStore.Check.Failed -> s(R.string.cmp_check_failed, c.message)
    }

    private fun refresh() {
        val m = compare.store.installedManifest()
        val last = compare.store.lastCheckedAt()?.let { iso(Date(it)) } ?: "never"
        packText.text = if (m == null) s(R.string.cmp_pack_none, last)
        else s(R.string.cmp_pack_line, m.rows, m.buildDate, m.sha256.take(12), last)
        statusText.text = testMessage ?: compare.status.ifEmpty { s(if (compare.enabled) R.string.cmp_on_idle else R.string.cmp_off) }

        val sum = compare.log.summary()
        summaryText.text = if (sum.n == 0) s(R.string.cmp_no_rows) else buildString {
            append("compared        ").append(sum.n).append('\n')
            append("name agree      ").append(pct(sum.nameAgreePct)).append('\n')
            append("printing agree  ").append(pct(sum.printingAgreePct)).append('\n')
            append("auto-pick agree ").append(pct(sum.autoPickAgreePct)).append('\n')
            append("phone total ms  median ").append(sum.medianTotalMs ?: "-").append(" · p95 ").append(sum.p95TotalMs ?: "-").append('\n')
            append("OCR-confirmed   phone ").append(pct(sum.phoneOcrRate)).append(" · server ").append(pct(sum.serverOcrRate)).append('\n')
            append("auto-picked     phone ").append(pct(sum.phoneAutoPickRate)).append(" · server ").append(pct(sum.serverAutoPickRate))
            if (sum.serverFallbackRows > 0) append("\nserver used raw-frame retry on ").append(sum.serverFallbackRows).append(" (phone saw the primary only)")
        }

        listBox.removeAllViews()
        val bad = compare.log.rows().filter { !it.agree }.asReversed().take(MAX_LISTED)
        if (bad.isEmpty()) {
            listBox.addView(chrome.card().apply {
                addView(chrome.text(s(R.string.cmp_no_disagreements), 14f, ScanChrome.Palette.TEXT_DIM))
            }, lp())
            return
        }
        for ((i, r) in bad.withIndex()) listBox.addView(rowCard(r), lp(if (i == 0) 0 else 10))
    }

    private fun rowCard(r: CompareRow) = chrome.card(padDp = 12).apply {
        addView(chrome.text("${time(r.at)} · ${r.source} · job ${r.jobId} · ${r.disagreements().joinToString()}",
            12f, ScanChrome.Palette.WARN, bold = true))
        addView(mono("server ${side(r.server)}${if (r.serverUsedFallback) " [raw retry]" else ""}\n" +
            "phone  ${side(r.phone)}\n" +
            "ms     " + r.timingsMs.entries.joinToString(" ") { "${it.key}=${it.value}" },
            11f, ScanChrome.Palette.TEXT_DIM).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun side(x: SideSummary): String = x.label() + " (" + listOfNotNull(
        x.confidence, if (x.autoPick) "auto" else null, if (x.ocrConfirmed) "ocr" else null,
        if (!x.identified && !x.noCard && x.name != null) "unconfident" else null,
    ).joinToString(", ") + ")"

    private fun copyReport() {
        val meta = JSONObject().apply {
            put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("at", iso(Date()))
            compare.store.installedManifest()?.let { put("pack", "${it.rows} rows · ${it.buildDate} · ${it.sha256.take(12)}") }
        }
        val cm = activity.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Card Scanner identify compare", compare.log.report(meta).toString()))
        Toast.makeText(activity, s(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun mono(t: String, size: Float, color: Int) = chrome.text(t, size, color).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    private fun pct(v: Double?) = v?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-"
    private fun time(ms: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    private fun iso(d: Date) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(d)

    companion object {
        const val MAX_LISTED = 50
    }
}
