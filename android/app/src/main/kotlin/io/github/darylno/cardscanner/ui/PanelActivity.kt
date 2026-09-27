package io.github.darylno.cardscanner.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.github.darylno.cardscanner.net.Pin

/**
 * The review UI is NOT re-implemented natively: this is a WebView of the
 * server's own `/phone?panel=1[&detail=<id>][&pricecheck=1]` (scans list,
 * filters, printing picker, walk-around price check with Keep/Discard).
 *
 * TLS: the server's cert is self-signed, so every load raises an SSL error.
 * We proceed ONLY when the presented leaf hashes to the paired pin — never on
 * the error type (an "untrusted" error from an impostor looks identical).
 * [WebView.clearSslPreferences] runs on every open so a proceed remembered
 * from before a re-pair can never carry over.
 */
class PanelActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var errorView: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = intent.getStringExtra(EXTRA_BASE)
        val pin = intent.getStringExtra(EXTRA_PIN)
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

        if (base == null || !Pin.isValid(pin)) {
            showError("Not paired with a scanner server — open Settings and pair first.")
            return
        }

        web.clearSslPreferences()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(0xff0f1115.toInt())
        web.addJavascriptInterface(Bridge(), "CardScannerApp")
        web.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val cert = error.certificate?.x509Certificate
                if (cert != null && Pin.matches(cert, pin)) {
                    handler.proceed()
                } else {
                    handler.cancel()
                    showError(
                        "The server's certificate does NOT match the paired fingerprint.\n\n" +
                            "If the server was reinstalled, re-pair in Settings. Otherwise something " +
                            "else is answering at ${error.url} — do not continue."
                    )
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    showError("Couldn't load the scans page: ${error.description}\n\n$base")
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Price check: Back = Keep (the page only deletes on Discard).
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        web.loadUrl(buildUrl(base, intent.getLongExtra(EXTRA_DETAIL, 0L), intent.getBooleanExtra(EXTRA_PRICE_CHECK, false)))
    }

    private fun showError(msg: String) {
        runOnUiThread {
            errorView.text = msg
            errorView.visibility = android.view.View.VISIBLE
        }
    }

    override fun onDestroy() {
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
        const val EXTRA_BASE = "base"
        const val EXTRA_PIN = "pin"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_PRICE_CHECK = "pricecheck"

        fun buildUrl(base: String, detail: Long, priceCheck: Boolean): String {
            val sb = StringBuilder(base.trimEnd('/')).append("/phone?panel=1")
            if (detail > 0) {
                sb.append("&detail=").append(detail)
                if (priceCheck) sb.append("&pricecheck=1")
            }
            return sb.toString()
        }

        fun intent(ctx: Context, base: String?, pin: String?, detail: Long = 0L, priceCheck: Boolean = false): Intent =
            Intent(ctx, PanelActivity::class.java)
                .putExtra(EXTRA_BASE, base)
                .putExtra(EXTRA_PIN, pin)
                .putExtra(EXTRA_DETAIL, detail)
                .putExtra(EXTRA_PRICE_CHECK, priceCheck)
    }
}
