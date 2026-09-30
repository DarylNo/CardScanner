package io.github.darylno.cardscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.R

/**
 * Share: people on the phone's local network (shop Wi-Fi or this phone's
 * hotspot) scan the QR / type the 6-digit code and get the review pages the
 * phone serves — as GUESTS (review, pick, flag for deletion; deleting,
 * clearing and export are the paired computer's). "New guest code" ends every
 * guest session; the server itself keeps running.
 */
class ShareActivity : AppCompatActivity() {
    private lateinit var app: App
    private lateinit var body: LinearLayout
    private lateinit var chrome: ScanChrome
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    private fun dp(v: Int) = chrome.dp(v)
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        chrome = ScanChrome(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(32))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ScanChrome.Palette.BG)
            addView(chrome.topBar(getString(R.string.share_title), getString(R.string.back)) { finish() },
                LinearLayout.LayoutParams(match(), wrap()))
            addView(ScrollView(this@ShareActivity).apply { addView(body) }, LinearLayout.LayoutParams(match(), 0, 1f))
        }
        chrome.applyInsets(root)
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Amber card: guests get FULL access. Shown in every state — never hide it. */
    private fun warningCard() = chrome.card(ScanChrome.Palette.WARN_BG, ScanChrome.Palette.WARN_STROKE).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false   // else the multi-line text is shifted down and its last line clipped
        addView(chrome.text("⚠", 18f, ScanChrome.Palette.WARN), LinearLayout.LayoutParams(wrap(), wrap()).apply { marginEnd = dp(12) })
        addView(chrome.text(getString(R.string.share_warning), 14f, ScanChrome.Palette.WARN), LinearLayout.LayoutParams(0, wrap(), 1f))
    }

    private fun errorCard(msg: String) = chrome.card(ScanChrome.Palette.ERR_BG, ScanChrome.Palette.ERR_STROKE).apply {
        addView(chrome.text(msg, 14f, ScanChrome.Palette.ERR))
    }

    private fun render() {
        body.removeAllViews()
        val gw = app.gateway
        gw.error?.let { body.addView(errorCard(getString(R.string.share_start_failed, it)), lp(8)) }
        if (!gw.running) {
            val hero = chrome.card().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
            hero.addView(chrome.text(getString(R.string.share_hero_title), 22f, ScanChrome.Palette.TEXT, bold = true))
            hero.addView(chrome.text(getString(R.string.share_hero_body), 15f, ScanChrome.Palette.TEXT_DIM), lp(8))
            hero.addView(chrome.primaryButton(getString(R.string.share_start)) { start() },
                LinearLayout.LayoutParams(match(), dp(52)).apply { topMargin = dp(20) })
            body.addView(hero, lp(8))
            body.addView(warningCard(), lp(12))
        } else {
            val addrs = gw.localAddresses()
            val code = gw.code()
            body.addView(warningCard(), lp(8))
            // "Sharing is on" status line.
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(View(this@ShareActivity).apply { background = chrome.dot(ScanChrome.Palette.OK) },
                    LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
                addView(chrome.text(getString(R.string.share_on), 14f, ScanChrome.Palette.OK, bold = true))
            }, lp(16))
            if (addrs.isEmpty()) {
                body.addView(errorCard(getString(R.string.share_no_wifi)), lp(12))
            } else {
                val url = gw.joinUrl(addrs.first())
                // QR on WHITE (black-on-white, own quiet zone + card padding): must stay scannable.
                val size = dp(232)
                val qrCard = FrameLayout(this).apply {
                    background = chrome.rounded(Color.WHITE, 20f)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    addView(ImageView(this@ShareActivity).apply {
                        setImageBitmap(gw.qr(url, size))
                        contentDescription = url
                    }, FrameLayout.LayoutParams(size, size))
                }
                body.addView(qrCard, LinearLayout.LayoutParams(wrap(), wrap()).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16)
                })
                body.addView(chrome.text(getString(R.string.share_scan_qr), 14f, ScanChrome.Palette.TEXT_DIM).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }, lp(10))
            }
            // Join code, huge and spaced.
            val codeCard = chrome.card().apply { gravity = Gravity.CENTER_HORIZONTAL }
            codeCard.addView(chrome.text(getString(R.string.share_join_code).uppercase(), 12f, ScanChrome.Palette.TEXT_FAINT, bold = true).apply {
                letterSpacing = 0.08f
                gravity = Gravity.CENTER_HORIZONTAL
            }, lp())
            codeCard.addView(chrome.text(code.chunked(3).joinToString(" "), 46f, ScanChrome.Palette.TEXT, bold = true).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.12f
                gravity = Gravity.CENTER_HORIZONTAL
                setLineSpacing(0f, 1f)
            }, lp(4))
            if (addrs.isNotEmpty()) {
                codeCard.addView(chrome.divider(), chrome.dividerParams().apply { topMargin = dp(12); bottomMargin = dp(12) })
                codeCard.addView(chrome.text(getString(R.string.share_or_open), 13f, ScanChrome.Palette.TEXT_DIM).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }, lp())
                for (a in addrs) {
                    codeCard.addView(chrome.text("http://$a:${gw.port}", 16f, ScanChrome.Palette.TEXT).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        setTextIsSelectable(true)
                    }, lp(6))
                }
            }
            body.addView(codeCard, lp(16))
            body.addView(chrome.destructiveButton(getString(R.string.share_stop)) { gw.newGuestCode(); render() },
                LinearLayout.LayoutParams(match(), dp(52)).apply { topMargin = dp(20) })
            body.addView(chrome.text(getString(R.string.share_stop_note), 13f, ScanChrome.Palette.TEXT_FAINT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }, lp(8))
        }
    }

    private fun start() {
        // The foreground-service notification (with its Stop action) needs this on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            app.gateway.start()
        } catch (e: Exception) {
            body.addView(errorCard(getString(R.string.share_start_failed, e.message)), 0, lp(8))
            return
        }
        body.postDelayed({ render() }, 500)
        body.postDelayed({ render() }, 2000)
    }
}
