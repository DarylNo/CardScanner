package io.github.darylno.cardscanner.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.darylno.cardscanner.R
import io.github.darylno.cardscanner.ident.CompareMode
import io.github.darylno.cardscanner.phoneserver.PhoneServerPreview
import java.util.concurrent.Executors

/**
 * Diagnostics → "Phone server (preview)" (Stage 3): run the phone's own copy of
 * the server beside the computer's. ON turns Compare mode on too (that's what
 * identifies each capture on the phone) and serves the review pages from the
 * phone at the shown address + code; each capture the phone identifies is
 * filed into the phone's store. Scans + photo size + free space are shown; at
 * 10,000 scans it turns into a warning (the owner's rule) but keeps going.
 */
class PreviewSection(
    private val activity: Activity,
    private val chrome: ScanChrome,
    private val preview: PhoneServerPreview,
    private val compare: CompareMode,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var addressText: TextView
    private lateinit var usageText: TextView
    private var error: String? = null
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 3000) }
    }

    private fun dp(v: Int) = chrome.dp(v)
    private fun s(id: Int, vararg a: Any) = activity.getString(id, *a)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    fun build(col: LinearLayout) {
        col.addView(chrome.sectionHeader(s(R.string.pv_header)))
        val card = chrome.card()
        card.addView(chrome.text(s(R.string.pv_intro), 14f, ScanChrome.Palette.TEXT_DIM))
        card.addView(chrome.switchRow(s(R.string.pv_switch), s(R.string.pv_switch_desc), preview.running) { on ->
            if (on) {
                if (!compare.enabled) compare.setEnabled(true)
                io.execute {
                    error = preview.start()
                    ui.post { refresh() }
                }
            } else {
                io.execute { preview.stop(); ui.post { refresh() } }
            }
        }, lp(8))
        card.addView(chrome.divider(), chrome.dividerParams())
        addressText = chrome.text("", 15f, ScanChrome.Palette.TEXT).apply {
            setTextIsSelectable(true); setPadding(dp(4), dp(8), dp(4), 0)
        }
        card.addView(addressText, lp())
        usageText = chrome.text("", 12f, ScanChrome.Palette.TEXT_DIM).apply {
            typeface = Typeface.MONOSPACE; setPadding(dp(4), dp(8), dp(4), 0)
        }
        card.addView(usageText, lp())
        card.addView(chrome.destructiveButton(s(R.string.pv_clear)) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.pv_clear_confirm)
                .setPositiveButton(R.string.pv_clear_ok) { _, _ ->
                    io.execute {
                        for (row in preview.store.list()) {
                            val id = (row["id"] as Number).toLong()
                            preview.photos.delete(id); preview.store.delete(id)
                        }
                        ui.post { refresh() }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }, lp(12))
        col.addView(card, lp())
        ui.post(tick)
    }

    fun destroy() {
        ui.removeCallbacksAndMessages(null)
        io.shutdown()
    }

    private fun refresh() {
        addressText.text = when {
            error != null -> s(R.string.pv_error, error!!)
            preview.running -> preview.joinUrl()?.let { s(R.string.pv_open, it, preview.code ?: "") }
                ?: s(R.string.pv_no_wifi)
            else -> s(R.string.pv_off)
        }
        io.execute {
            val u = preview.usage()
            ui.post {
                usageText.text = u.label()
                usageText.setTextColor(if (u.warn) ScanChrome.Palette.WARN else ScanChrome.Palette.TEXT_DIM)
            }
        }
    }
}
