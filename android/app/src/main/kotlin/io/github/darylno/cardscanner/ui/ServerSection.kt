package io.github.darylno.cardscanner.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.darylno.cardscanner.R
import io.github.darylno.cardscanner.gateway.QrBitmap
import io.github.darylno.cardscanner.ident.ArtPackStore
import io.github.darylno.cardscanner.ident.LocalIdentify
import io.github.darylno.cardscanner.phoneserver.PhoneServer
import java.util.concurrent.Executors

/**
 * Settings → "This phone's server" (Stage 4: the phone IS the scanner's server).
 * Where a computer opens it (address + guest code), pairing a computer as
 * admin (one-time code + QR) and forgetting paired computers, the card
 * database (art pack) with a check-now, and storage — scans, photo size, free
 * space; at 10,000 scans it turns into a warning (the owner's rule) but keeps
 * going.
 */
class ServerSection(
    private val activity: Activity,
    private val chrome: ScanChrome,
    private val phone: PhoneServer,
    private val identify: LocalIdentify,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var addressText: TextView
    private lateinit var packText: TextView
    private lateinit var usageText: TextView
    private lateinit var pairedText: TextView
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 3000) }
    }

    private fun dp(v: Int) = chrome.dp(v)
    private fun s(id: Int, vararg a: Any) = activity.getString(id, *a)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    fun build(col: LinearLayout) {
        col.addView(chrome.sectionHeader(s(R.string.pv_header)).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
        val card = chrome.card()
        card.addView(chrome.text(s(R.string.pv_intro), 14f, ScanChrome.Palette.TEXT_DIM))
        addressText = chrome.text("", 15f, ScanChrome.Palette.TEXT).apply {
            setTextIsSelectable(true); setPadding(dp(4), dp(10), dp(4), 0)
        }
        card.addView(addressText, lp())
        pairedText = chrome.text("", 12f, ScanChrome.Palette.TEXT_DIM).apply { setPadding(dp(4), dp(8), dp(4), 0) }
        card.addView(pairedText, lp())
        card.addView(chrome.primaryButton(s(R.string.pv_pair)) { pair() }, lp(12))
        card.addView(chrome.destructiveButton(s(R.string.pv_forget)) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.pv_forget_confirm)
                .setPositiveButton(R.string.pv_forget) { _, _ ->
                    io.execute { phone.admins.revokeAll(); ui.post { refresh() } }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }, lp(8))
        card.addView(chrome.divider(), chrome.dividerParams())
        packText = chrome.text("", 12f, ScanChrome.Palette.TEXT_DIM).apply { setPadding(dp(4), dp(8), dp(4), 0) }
        card.addView(packText, lp())
        card.addView(chrome.secondaryButton(s(R.string.pv_pack_check)) {
            identify.checkAsync(force = true) { ui.post { refresh() } }
            refresh()
        }, lp(8))
        usageText = chrome.text("", 12f, ScanChrome.Palette.TEXT_DIM).apply {
            typeface = Typeface.MONOSPACE; setPadding(dp(4), dp(10), dp(4), 0)
        }
        card.addView(usageText, lp())
        col.addView(card, lp())
        ui.post(tick)
    }

    fun destroy() {
        phone.admins.cancelCode()           // the dialog can go without onDismiss (activity destroyed)
        ui.removeCallbacksAndMessages(null)
        io.shutdown()
    }

    /** One-time admin code: link + QR + the digits, valid 10 min, retired when the dialog closes. */
    private fun pair() {
        val (url, code) = phone.adminPairUrl() ?: run {
            AlertDialog.Builder(activity).setMessage(R.string.pv_pair_off).setPositiveButton(R.string.pv_done, null).show()
            return
        }
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0) }
        box.addView(ImageView(activity).apply {
            setImageBitmap(QrBitmap.encode(url, dp(220)))
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(dp(220), dp(220)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(TextView(activity).apply {
            text = s(R.string.pv_pair_msg, url, code.chunked(4).joinToString(" "))
            setTextIsSelectable(true); setPadding(0, dp(12), 0, 0)
        })
        AlertDialog.Builder(activity)
            .setTitle(R.string.pv_pair_title)
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(R.string.pv_done, null)
            .setOnDismissListener { phone.admins.cancelCode(); refresh() }
            .show()
    }

    private fun refresh() {
        pairedText.text = s(R.string.pv_paired, phone.admins.count)
        addressText.text = when {
            phone.running -> phone.joinUrl()?.let { s(R.string.pv_open, it.substringBefore("/join"), phone.code ?: "") }
                ?: s(R.string.pv_no_wifi)
            else -> s(R.string.pv_off)
        }
        val m = identify.store.installedManifest()
        packText.text = when {
            identify.checking -> s(R.string.pv_pack_checking)
            m != null -> s(R.string.pv_pack, m.rows, m.buildDate) +
                (identify.lastCheck as? ArtPackStore.Check.Failed)?.let { "\n" + s(R.string.pv_pack_failed, it.message) }.orEmpty()
            else -> s(R.string.pv_pack_none) +
                (identify.lastCheck as? ArtPackStore.Check.Failed)?.let { "\n" + s(R.string.pv_pack_failed, it.message) }.orEmpty()
        }
        io.execute {
            val u = phone.usage()
            ui.post {
                usageText.text = u.label()
                usageText.setTextColor(if (u.warn) ScanChrome.Palette.WARN else ScanChrome.Palette.TEXT_DIM)
            }
        }
    }
}
