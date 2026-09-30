package io.github.darylno.cardscanner.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.github.darylno.cardscanner.App
import io.github.darylno.cardscanner.gateway.GatewayService

/**
 * The review UI is NOT re-implemented natively: this is a WebView of the
 * phone server's own `/phone?panel=1[&detail=<id>]` (scans
 * list, filters, printing picker, walk-around price check with Keep/Discard) —
 * the same page a computer on the LAN gets.
 *
 * Stage 4: served by the phone itself on loopback (`http://127.0.0.1:<port>`,
 * cleartext allowed for loopback only — res/xml/network_security_config.xml),
 * through the same gateway as the LAN, as the phone's OWNER: the gateway's
 * in-memory owner token ([gateway.AdminPairing.ownerToken]) is set as the
 * admin cookie before the first load. The server is (re)started here if the
 * notification's Stop ended it.
 */
class PanelActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var errorView: TextView
    private val ui = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phone = App.of(this).phoneServer
        val root = FrameLayout(this)
        web = WebView(this)
        errorView = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            setTextColor(0xffe5e7eb.toInt())
            textSize = 16f
            visibility = android.view.View.GONE
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(errorView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.setBackgroundColor(0xff0f1115.toInt())
        setContentView(root)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(0xff0f1115.toInt())
        web.addJavascriptInterface(Bridge(), "CardScannerApp")
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    showError("Couldn't load the scans page: ${error.description}")
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Price check: Back = Keep (the page only deletes on Discard).
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        phone.start()                                   // idempotent; the server may have been stopped
        val detail = intent.getLongExtra(EXTRA_DETAIL, 0L)
        val deadline = System.currentTimeMillis() + START_WAIT_MS
        val load = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val st = GatewayService.status.value
                when {
                    st.running -> {
                        val base = phone.localBase()
                        CookieManager.getInstance().setCookie(base, phone.ownerCookie())
                        CookieManager.getInstance().flush()
                        web.loadUrl(buildUrl(base, detail))
                    }
                    st.error != null -> showError("The scanner's server couldn't start: ${st.error}")
                    System.currentTimeMillis() > deadline -> showError("The scanner's server didn't start — try again.")
                    else -> ui.postDelayed(this, 100)
                }
            }
        }
        ui.post(load)
    }

    override fun onStart() {
        super.onStart()
        GatewayService.screenVisible(true)
    }

    override fun onStop() {
        GatewayService.screenVisible(false)
        super.onStop()
    }

    private fun showError(msg: String) {
        runOnUiThread {
            errorView.text = msg
            errorView.visibility = android.view.View.VISIBLE
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            web.removeJavascriptInterface("CardScannerApp")
            web.destroy()
        }
        super.onDestroy()
    }

    /** `window.CardScannerApp.close()` — the page's Close button. */
    private inner class Bridge {
        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }
    }

    companion object {
        const val EXTRA_DETAIL = "detail"
        private const val START_WAIT_MS = 8_000L

        fun buildUrl(base: String, detail: Long): String {
            val sb = StringBuilder(base.trimEnd('/')).append("/phone?panel=1")
            if (detail > 0) sb.append("&detail=").append(detail)
            return sb.toString()
        }

        fun intent(ctx: Context, detail: Long = 0L): Intent =
            Intent(ctx, PanelActivity::class.java).putExtra(EXTRA_DETAIL, detail)
    }
}
